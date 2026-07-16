(ns dao.stream.file
  "Live-tail, read/write file transport for DaoStream.

   `open!` positions at the current end of file; the in-memory ringbuffer holds
   only writes made after open, so reads are a true `tail -f`. Writes append to
   disk asynchronously (honoring the non-blocking `put!` contract); durability is
   reconciled at `close!`, which blocks on the final flush on clj; on Node/cljd/browser
   it returns promptly and the flush drains via the async runtime's parking (await `flushed`).

   A `FileStream` wraps two pieces:
   1. a bounded ringbuffer (`:evict-oldest`, capacity 1024 by default) holding
      post-open writes as segments — `next`/waiters/`closed?` delegate here;
   2. a host-specific async disk writer carrying a poison cell: the first failed
      async write is captured there, `put!` reads it first and fails fast, and
      `close!` surfaces the same cause."
  ;; The :clj branch avoids JVM type references (no java.io :import, no
  ;; ^Class hints on receivers): ClojureDart host-eval reads it and cannot
  ;; resolve JVM types as Dart types. Interop on untyped receivers compiles
  ;; cleanly (same constraint as dao.stream.file-output-stream).
  (:require #?@(:cljd [["dart:io" :as dart-io] ["dart:async" :as dart-async]
                       ["dart:typed_data" :as typed]]
                :clj [[clojure.java.io :as io]])
            [dao.stream :as ds]
            [dao.stream.ringbuffer])
  #?(:cljs (:require-macros [dao.stream])))


;; =============================================================================
;; Host byte-array predicate
;; =============================================================================
;; `bytes?` exists only on clj/cljd; cljs has no core byte-array predicate, so
;; the cljs branch validates explicitly with Uint8Array (Node and browser).

#?(:clj (defn- byte-array?
          [v]
          (bytes? v))
   :cljd (defn- byte-array?
           [v]
           (instance? typed/Uint8List v))
   :cljs (defn- byte-array?
           [v]
           (instance? js/Uint8Array v)))


;; =============================================================================
;; clj disk writer — agent + send-off on the expandable I/O pool
;; =============================================================================
;; The agent action captures the first write failure in `error` and gates the
;; rest, so the agent never enters the opaque "failed/needs-restart" state and
;; `await` always returns cleanly. `put!` and `close!` both consult `error`.

#?(:clj (defn- open-writer!
          [path]
          (let [file (io/file path)]
            (when-let [parent (.getParentFile file)] (.mkdirs parent))
            (when-not (.exists file) (.createNewFile file))
            (when-not (.isFile file)
              (throw (ex-info "dao.stream.file path must be a regular file"
                              {:path path})))
            (let [out (io/output-stream file :append true)]
              {:out out, :agent (agent out), :error (atom nil)}))))


#?(:clj (defn- writer-error
          [writer]
          (deref (:error writer))))


#?(:clj (defn- schedule-write!
          [writer val]
          (let [error (:error writer)]
            (send-off (:agent writer)
                      (fn [out]
                        (when (nil? @error)
                          (try (.write out ^bytes val)
                               (catch Throwable t (reset! error t))))
                        out)))))


#?(:clj
   (defn- close-writer!
     "Blocks: drains pending appends, surfaces the real write error if any,
      then flushes and closes the handle. Returns nil (clj genuinely blocks)."
     [writer]
     (let [out (:out writer)]
       (try (await (:agent writer))
            (when-let [e @(:error writer)] (throw e))
            (.flush out)
            nil
            (finally (.close out))))))


;; =============================================================================
;; cljd disk writer — Future microtask chain wrapping writeFromSync
;; =============================================================================
;; Microtask FIFO ordering preserves write order. close! cannot synchronously
;; block on a Dart event loop, so it returns a Future the async runtime parks
;; on.

#?(:cljd (defn- open-writer!
           [path]
           (let [file (dart-io/File. path)]
             (.createSync file .recursive true)
             (let [raf (.openSync file .mode dart-io/FileMode.append)]
               {:raf raf,
                :error (atom nil),
                :chain (atom (dart-async/Future.value nil))}))))


#?(:cljd (defn- writer-error
           [writer]
           (deref (:error writer))))


#?(:cljd (defn- schedule-write!
           [writer val]
           (let [error (:error writer)
                 ^dart-io/RandomAccessFile raf (:raf writer)]
             (swap! (:chain writer) (fn [^dart-async/Future p]
                                      (.then p
                                             (fn [_]
                                               (when (nil? @error)
                                                 (try (.writeFromSync raf val)
                                                      (catch Exception e
                                                        (reset! error e))))
                                               nil)))))))


#?(:cljd (defn- close-writer!
           [writer]
           (let [^dart-async/Future chain @(:chain writer)
                 ^dart-io/RandomAccessFile raf (:raf writer)]
             (.then chain
                    (fn [_]
                      (.closeSync raf)
                      (when-let [e @(:error writer)] (throw e))
                      nil)))))


;; =============================================================================
;; cljs disk writer — Node (fs.write chain) or browser (OPFS writable)
;; =============================================================================
;; Selected on js/process.versions.node (robust against bundler process shims).

#?(:cljs (defn- node?
           []
           (and (exists? js/process)
                (some? (.-versions js/process))
                (some? (.. js/process -versions -node)))))


#?(:cljs (defn- writer-error
           [writer]
           (deref (:error writer))))


#?(:cljs
   (defn- open-writer!
     [path]
     (let [error (atom nil)]
       (if (node?)
         (let [fs (js/require "fs")]
           {:node true,
            :fs fs,
            :fd (.openSync fs path "a"),
            :error error,
            :chain (atom (js/Promise.resolve))})
         ;; Browser OPFS: async setup, returns immediately. The writable
         ;; is a pending promise; writes queue on :chain until it
         ;; resolves.
         (let [writable
               (-> (js/navigator.storage.getDirectory)
                   (.then (fn [dir]
                            (.getFileHandle dir path #js {:create true})))
                   (.then (fn [handle]
                            (.then (.getFile handle)
                                   (fn [file]
                                     (.then (.createWritable
                                              handle
                                              #js {:keepExistingData true})
                                            (fn [w]
                                              (.then (.seek w (.-size file))
                                                     (fn [_] w)))))))))]
           {:node false,
            :error error,
            :writable writable,
            :chain (atom (js/Promise.resolve))})))))


#?(:cljs (defn- schedule-write!
           [writer val]
           (let [error (:error writer)]
             (swap! (:chain writer)
                    (fn [p]
                      (.then p
                             (fn [_]
                               (if (:node writer)
                                 (js/Promise. (fn [resolve reject]
                                                (let [fs ^js (:fs writer)]
                                                  (.write fs
                                                          (:fd writer)
                                                          val
                                                          (fn [err]
                                                            (if err
                                                              (do (reset! error err)
                                                                  (reject err))
                                                              (resolve)))))))
                                 (-> (:writable writer)
                                     (.then (fn [w] (.write w val)))
                                     (.catch (fn [err]
                                               (reset! error err)
                                               (js/Promise.reject err))))))
                             ;; upstream rejected (poison): propagate, do not
                             ;; write
                             (fn [err] (js/Promise.reject err))))))))


#?(:cljs
   (defn- close-writer!
     "Returns a Promise that resolves once writes are drained and the handle is
      flushed and closed. The handle is released in a finally step so a
      poisoned/rejected write chain still closes the fd (Node) / writable
      (browser) before the cause is rethrown — mirroring clj's `finally`.
      cljs cannot synchronously block, so durability is realized when the async
      runtime unparks on this promise."
     [writer]
     (let [error (:error writer)]
       (if (:node writer)
         (let [fs ^js (:fs writer)
               fd (:fd writer)]
           (-> @(:chain writer)
               (.then (fn [_]
                        ;; fsync only on the clean path (skipped when the
                        ;; chain rejected)
                        (js/Promise.
                          (fn [resolve reject]
                            (.fsync fs
                                    fd
                                    (fn [err]
                                      (if err (reject err) (resolve))))))))
               (.finally (fn []
                           ;; ALWAYS close the fd, clean or poisoned — no
                           ;; handle leak
                           (js/Promise.
                             (fn [resolve _]
                               (.close fs fd (fn [_] (resolve)))))))
               (.then (fn [_] (when-let [e @error] (throw e)) :ok))))
         (-> @(:chain writer)
             (.finally (fn []
                         ;; ALWAYS close the writable, clean or poisoned
                         (-> (:writable writer)
                             (.then (fn [w] (.close w)))
                             (.catch (fn [_] :ignored)))))
             (.then (fn [_] (when-let [e @error] (throw e)) :ok)))))))


;; =============================================================================
;; FileStream — shared across hosts; host code is confined to the writer fns
;; =============================================================================

(defrecord FileStream
  [path ring writer puts done]

  ds/IDaoStreamReader

  (next [_this cursor] (ds/next ring cursor))


  ds/IDaoStreamWriter

  (append!
    [_this val]
    (when-not (byte-array? val)
      (throw (ex-info "dao.stream.file expects a byte-array value"
                      {:path path})))
    (when-let [e (writer-error writer)] (throw e))
    (let [result (ds/append! ring val)]
      (when (= :ok (:result result))
        (schedule-write! writer val)
        (swap! puts inc))
      result))


  ds/IDaoStreamBound

  (close!
    [_this]
    (let [woke (ds/close! ring)]
      (reset! done (close-writer! writer))
      #?(:cljs (when-let [p @done] (.catch p (fn [_]))))
      woke))


  (closed? [_this] (ds/closed? ring))


  ds/IDaoStreamWaitable

  (register-reader-waiter!
    [_this position entry]
    (ds/register-reader-waiter! ring position entry))


  (register-writer-waiter!
    [_this entry]
    (ds/register-writer-waiter! ring entry)))


(defn tail-position
  "The live tail: the absolute next-write position. A reader that received
   `:daostream/gap` resyncs by setting its cursor to `{:position (tail-position
   fs)}`. Transport-specific accessor, outside the reader protocol — see the
   design doc's resync discussion."
  [fs]
  (deref (:puts fs)))


(defn flushed
  "The pending close flush. nil on clj (close! already blocked); on cljs/cljd a
   host promise/future that resolves once the post-close flush is durable.
   Lets async hosts await durability without a synchronous block."
  [fs]
  (deref (:done fs)))


(def ^:private default-capacity 1024)


(defn make-file-stream
  "Open a live-tail file stream at `path`. `capacity` bounds the in-memory
   ringbuffer (segment count, default 1024; nil = unbounded). `eviction-policy`
   defaults to :evict-oldest."
  ([path] (make-file-stream path default-capacity :evict-oldest))
  ([path capacity eviction-policy]
   (let [ring (ds/open! {:type :ringbuffer,
                         :capacity capacity,
                         :eviction-policy (or eviction-policy :evict-oldest)})]
     (->FileStream path ring (open-writer! path) (atom 0) (atom nil)))))


(ds/defopen :file
            [descriptor]
            (make-file-stream (:path descriptor)
                              (if (contains? descriptor :capacity)
                                (:capacity descriptor)
                                default-capacity)
                              (or (:eviction-policy descriptor) :evict-oldest)))

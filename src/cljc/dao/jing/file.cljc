(ns dao.jing.file
  "File-backed stream materializer for DaoJing, on top of dao.stream.log.

   Observes an append-only file stream of UTF-8 EDN-encoded `:cas` records
   `[k :cas expected v]`, folds them into a materialized target atom via
   `jing/materialize-step`, and returns an atom handle that `jing/cas!`,
   `jing/get`, `jing/delete!`, and `jing/close!` operate on directly.

   Compaction (`compact-store!`) rebuilds the log from the current target
   state, reclaiming dead space from overwritten or deleted keys. The handle
   atom lets compaction swap the underlying stream without changing the
   caller's reference."
  (:require #?@(:cljd [["dart:convert" :as convert] ["dart:io" :as dart-io]
                       ["dart:typed_data" :as typed]])
            [clojure.edn :as edn]
            [dao.jing :as jing]
            [dao.stream :as ds]
            [dao.stream.log]))


;; =============================================================================
;; Serialization / Byte Conversion
;; =============================================================================

(defn ->bytes
  "Convert a string or serializable payload into UTF-8 bytes."
  [payload]
  #?(:clj (.getBytes ^String payload "UTF-8")
     :cljs (js/Buffer.from payload "utf8")
     :cljd (typed/Uint8List.fromList (convert/utf8.encode payload))))


(defn bytes->str
  "Convert UTF-8 bytes back to a string."
  [b]
  #?(:clj (String. ^bytes b "UTF-8")
     :cljs (.toString ^js b "utf8")
     :cljd (convert/utf8.decode b)))


(defn encode-record
  "Encode a [k :cas expected v] tuple into a UTF-8 byte array."
  [record]
  (->bytes (pr-str record)))


(defn decode-record
  "Decode a byte array or UTF-8 payload back into a canonical [k :cas expected v] tuple.
   Returns nil for any record that is not a 4-element :cas vector."
  [b]
  (when b
    (try (let [s (if (string? b) b (bytes->str b))
               v (edn/read-string s)]
           (if (and (vector? v) (= (count v) 4) (= (second v) :cas)) v nil))
         (catch #?(:clj Exception
                   :cljs :default
                   :cljd Object)
                _
           nil))))


;; =============================================================================
;; File Stream Observer & Reducers
;; =============================================================================

(defn reduce-file-stream
  "Fold an entire file stream from position 0 into an in-memory map via
   jing/materialize-step. Stops on unparseable or corrupt records."
  [stream]
  (loop [cursor {:position 0}
         idx {}]
    (let [res (ds/next stream cursor)]
      (if (and (map? res) (contains? res :ok))
        (let [{b :ok, next-cursor :cursor} res
              record (decode-record b)]
          (if record
            (recur next-cursor (jing/materialize-step idx record))
            idx))
        idx))))


(defn step-incremental-file!
  "Single-step incremental materializer over a file stream.
   Reads next record at @cursor-atom, decodes it, updates @target-atom via
   jing/materialize-step, advances cursor. Returns stream signal."
  [target-atom cursor-atom stream]
  (let [res (ds/next stream @cursor-atom)]
    (cond (map? res) (let [record (decode-record (:ok res))]
                       (when record
                         (swap! target-atom jing/materialize-step record))
                       (reset! cursor-atom (:cursor res))
                       :ok)
          (= res :blocked) :wait
          (= res :end) :complete
          (= res :daostream/gap) :resync)))


;; =============================================================================
;; Platform Lock & File Helpers
;; =============================================================================

(defn- rename-file!
  [src dest]
  #?(:clj (java.nio.file.Files/move
            (.toPath (java.io.File. src))
            (.toPath (java.io.File. dest))
            (into-array java.nio.file.CopyOption
                        [java.nio.file.StandardCopyOption/REPLACE_EXISTING
                         java.nio.file.StandardCopyOption/ATOMIC_MOVE]))
     :cljs (let [fs (js/require "fs")] (.renameSync fs src dest))
     :cljd (let [d (dart-io/File dest)
                 s (dart-io/File src)]
             (when (.existsSync d) (.deleteSync d))
             (.renameSync s dest))))


(defn- close-quietly!
  [stream]
  (try (ds/close! stream)
       (catch #?(:clj Exception
                 :cljs :default
                 :cljd Object)
              _
         nil)))


(defn- cleanup-compact-file!
  [compact-path]
  #?(:clj (let [f (java.io.File. compact-path)] (when (.exists f) (.delete f)))
     :cljs (try (.unlinkSync (js/require "fs") compact-path) (catch :default _))
     :cljd (try (let [f (dart-io/File compact-path)]
                  (when (.existsSync f) (.deleteSync f)))
                (catch #?(:cljd Object
                          :default Exception)
                       _))))


;; =============================================================================
;; Store handle helper
;; =============================================================================

(defn- make-file-fns
  [stream target]
  {:get-fn (fn file-get
             [k not-found]
             (let [s @target]
               (if (contains? s k) (clojure.core/get s k) not-found))),
   :cas-fn (fn file-cas!
             [k expected v]
             (let [rec [k :cas expected v]
                   res (ds/append! stream (encode-record rec))]
               (if (and (map? res) (= :ok (:result res)))
                 (let [old @target
                       new (jing/materialize-step old rec)]
                   (if (= old new)
                     (and (= expected jing/absent)
                          (= (clojure.core/get old k jing/absent) v))
                     (do (reset! target new) true)))
                 false))),
   :delete-fn (fn file-delete
                [k]
                (let [current (clojure.core/get @target k jing/absent)]
                  (if (= current jing/absent)
                    true
                    (let [rec [k :cas current jing/absent]
                          res (ds/append! stream (encode-record rec))]
                      (if (and (map? res) (= :ok (:result res)))
                        (do (swap! target jing/materialize-step rec) true)
                        false)))))})


;; =============================================================================
;; Compaction
;; =============================================================================

(def ^:private compact-lock
  #?(:clj (Object.)
     :cljs (js-obj)
     :cljd (Object.)))


(defn- do-with-lock
  [f]
  #?(:clj (locking compact-lock (f))
     :default (f)))


(defn compact-store!
  "Garbage-collect a file store: write live entries to a `.compact` log,
   flush, rename, reopen, and swap the handle atom. Returns the handle."
  [handle]
  (do-with-lock
    (fn []
      (let [h @handle
            old-stream (:stream h)
            target @(:target h)
            path (:path h)
            compact-path (str path ".compact")
            new-stream (ds/open! {:type :append-log, :path compact-path})]
        (try (doseq [[k v] target]
               (when (not= v jing/absent)
                 (let [rec [k :cas jing/absent v]
                       res (ds/append! new-stream (encode-record rec))]
                   (when-not (= :ok (:result res))
                     (throw (ex-info "Compaction failed to write live key"
                                     {:key k, :res res}))))))
             (ds/close! old-stream)
             (ds/close! new-stream)
             (rename-file! compact-path path)
             (let [swapped-stream (ds/open! {:type :append-log, :path path})
                   target (:target h)]
               (reset! handle (merge {:stream swapped-stream,
                                      :target target,
                                      :encode-fn encode-record,
                                      :closed false,
                                      :path path}
                                     (make-file-fns swapped-stream target)))
               handle)
             (catch #?(:clj Exception
                       :cljs :default
                       :cljd Object)
                    e
               (close-quietly! new-stream)
               (cleanup-compact-file! compact-path)
               (close-quietly! old-stream)
               (let [restored (ds/open! {:type :append-log, :path path})
                     recovered (reduce-file-stream restored)
                     target (atom recovered)]
                 (reset! handle (merge {:stream restored,
                                        :target target,
                                        :encode-fn encode-record,
                                        :closed false,
                                        :path path}
                                       (make-file-fns restored target))))
               (throw e)))))))


;; =============================================================================
;; Store Constructor
;; =============================================================================

(defn create-file-store
  "Creates a persistent file-backed stream materializer using `dao.stream.log`.
   Folds the append-only file stream on startup to reconstruct state. Returns
   an atom handle that jing/cas!, jing/get, jing/delete!, and jing/close!
   operate on."
  [path]
  (cleanup-compact-file! (str path ".compact"))
  (let [stream (ds/open! {:type :append-log, :path path})
        recovered (reduce-file-stream stream)
        target (atom recovered)]
    (atom (merge {:stream stream,
                  :target target,
                  :encode-fn encode-record,
                  :closed false,
                  :path path}
                 (make-file-fns stream target)))))


(defn create-kv-file
  "Alias for `create-file-store` for backward compatibility."
  [path]
  (create-file-store path))

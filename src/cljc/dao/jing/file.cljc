(ns dao.jing.file
  "A single-file, persistent backend for IKVStore using the Bitcask (append-only log) architecture.
   Provides true block storage over a local file."
  (:require
    #?@(:cljd [["dart:convert" :as convert] ["dart:io" :as dart-io]
               ["dart:typed_data" :as typed]])
    [clojure.edn :as edn]
    [dao.jing :as kv]
    [dao.stream :as ds]
    [dao.stream.log]))


(defn- ->bytes
  [payload]
  #?(:clj (.getBytes ^String payload "UTF-8")
     :cljs (js/Buffer.from payload "utf8")
     :cljd (typed/Uint8List.fromList (convert/utf8.encode payload))))


(defn- bytes->str
  [b]
  #?(:clj (String. ^bytes b "UTF-8")
     :cljs (.toString ^js b "utf8")
     :cljd (convert/utf8.decode b)))


(defn- recover-index
  "Scans stream sequentially, returning {:index {k {:cursor cursor :rev rev}}}"
  [stream]
  (let [idx (atom {})]
    (loop [cursor {:position 0}]
      (let [res (ds/next stream cursor)]
        (if-not (map? res)
          {:index @idx}
          (let [{b :ok, next-cursor :cursor} res
                ;; Defense-in-depth: the transport's torn-tail scan
                ;; validates only frame-length boundaries, so a
                ;; length-consistent but corrupt payload (bit-rot, or a
                ;; length-extended-but-unflushed tail) can survive it. Stop
                ;; the walk on an unparseable record rather than crashing
                ;; open; everything recovered so far stands.
                parsed (try (edn/read-string (bytes->str b))
                            (catch #?(:clj Exception
                                      :cljs :default
                                      :cljd Object)
                                   _
                              nil))]
            (if-not (vector? parsed)
              {:index @idx}
              (let [[k rev _] parsed]
                (if (= rev -1)
                  (swap! idx dissoc k)
                  (swap! idx assoc k {:cursor cursor, :rev rev}))
                (recur next-cursor)))))))))


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


#_{:clj-kondo/ignore [:unused-binding]}


(defn- do-with-lock
  [lock f]
  #?(:clj (locking lock (f))
     :default (f)))


(defn- close-quietly!
  "Close a stream, swallowing any error (it may already be closed)."
  [stream]
  (try (ds/close! stream)
       (catch #?(:clj Exception
                 :cljs :default
                 :cljd Object)
              _
         nil)))


(defrecord KVFile
  [path state-atom]

  kv/IKVStore

  (put!
    [_ k v-map]
    (do-with-lock state-atom
                  (fn []
                    (let [state @state-atom
                          stream (:stream state)
                          v-map (assoc v-map :rev 0)
                          payload (pr-str [k 0 v-map])
                          {res :result, cursor :cursor}
                          (ds/put! stream (->bytes payload))]
                      (when (= :ok res)
                        (reset! state-atom (assoc-in state
                                                     [:index k]
                                                     {:cursor cursor, :rev 0}))
                        true)))))


  (cas!
    [_ k old-rev v-map]
    (do-with-lock state-atom
                  (fn []
                    (let [state @state-atom
                          stream (:stream state)
                          current (get-in state [:index k])
                          current-rev (or (:rev current) 0)]
                      (if (not= old-rev current-rev)
                        false
                        (let [new-rev (inc current-rev)
                              v-map (assoc v-map :rev new-rev)
                              payload (pr-str [k new-rev v-map])
                              {res :result, cursor :cursor}
                              (ds/put! stream (->bytes payload))]
                          (when (= :ok res)
                            (reset! state-atom (assoc-in state
                                                         [:index k]
                                                         {:cursor cursor,
                                                          :rev new-rev}))
                            true)))))))


  (get
    [this k not-found]
    (let [state @state-atom
          idx (get-in state [:index k])
          stream (:stream state)]
      (if-not idx
        not-found
        (let [res (try (ds/next stream (:cursor idx))
                       (catch #?(:clj Exception
                                 :cljs :default
                                 :cljd Object)
                              _
                         ::closed))]
          (cond (map? res) (let [payload (bytes->str (:ok res))
                                 [_ _ v-map] (edn/read-string payload)]
                             v-map)
                (or (= ::closed res) (ds/closed? stream))
                (if (:closed @state-atom)
                  not-found
                  (do #?(:clj (Thread/yield)) (kv/get this k not-found)))
                :else not-found)))))


  (delete!
    [_ k]
    (do-with-lock state-atom
                  (fn []
                    (let [state @state-atom
                          stream (:stream state)
                          payload (pr-str [k -1 nil])]
                      (when (= :ok
                               (:result (ds/put! stream (->bytes payload))))
                        (reset! state-atom (update state :index dissoc k))
                        true)))))


  (compact!
    [_]
    (do-with-lock
      state-atom
      (fn []
        (let [state @state-atom
              old-stream (:stream state)
              old-index (:index state)
              compact-path (str path ".compact")
              new-stream (ds/open! {:type :append-log, :path compact-path})]
          (try
            (let [new-index
                  (reduce-kv
                    (fn [idx k {:keys [cursor rev]}]
                      (let [res (ds/next old-stream cursor)]
                        (if-not (map? res)
                          (throw (ex-info
                                   "Compaction failed to read live key"
                                   {:key k, :cursor cursor}))
                          (let [payload (bytes->str (:ok res))
                                [_ _ v-map] (edn/read-string payload)
                                new-payload (pr-str [k rev v-map])
                                {put-res :result, new-cursor :cursor}
                                (ds/put! new-stream
                                         (->bytes new-payload))]
                            (if (= :ok put-res)
                              (assoc idx k {:cursor new-cursor, :rev rev})
                              (throw (ex-info
                                       "Compaction failed to write live key"
                                       {:key k, :result put-res})))))))
                    {}
                    old-index)]
              (ds/close! old-stream)
              (ds/close! new-stream)
              (rename-file! compact-path path)
              (let [swapped-stream (ds/open! {:type :append-log, :path path})]
                (reset! state-atom {:index new-index,
                                    :stream swapped-stream,
                                    :closed false})
                true))
            (catch #?(:clj Exception
                      :cljs :default
                      :cljd Object)
                   e
              ;; A failure after old-stream is closed (the rename or
              ;; the reopen below) would leave state-atom pointing at
              ;; a dead stream, wedging every future get in the
              ;; ::closed retry loop. Discard the half-built
              ;; compaction log and restore a live stream by
              ;; reopening whatever now sits at `path` (the original
              ;; log if the rename had not run, the compacted log if
              ;; it had) with a freshly recovered index.
              (close-quietly! new-stream)
              #?(:clj (let [f (java.io.File. compact-path)]
                        (when (.exists f) (.delete f)))
                 :cljs (try (.unlinkSync (js/require "fs") compact-path)
                            (catch :default _))
                 :cljd (try (let [f (dart-io/File compact-path)]
                              (when (.existsSync f) (.deleteSync f)))
                            (catch #?(:cljd Object
                                      :default Exception)
                                   _)))
              (close-quietly! old-stream)
              (let [restored (ds/open! {:type :append-log, :path path})
                    recovered (recover-index restored)]
                (reset! state-atom (assoc recovered
                                          :stream restored
                                          :closed false)))
              (throw e)))))))


  (close!
    [_]
    (do-with-lock state-atom
                  (fn []
                    (let [state @state-atom]
                      (when-not (:closed state)
                        (swap! state-atom assoc :closed true)
                        (ds/close! (:stream state))))))))


(defn create-kv-file
  "Creates a persistent, single-file KVStore using a Bitcask append-only log.
   Reads the entire file on startup to rebuild the in-memory index; a torn
   trailing record from a crashed append is truncated during recovery.

   Durability window: appends are written but not fsync'd, so a process crash is
   safe (the OS still holds the bytes) while a power loss can lose the most recent
   un-flushed appends."
  [path]
  ;; Clean up any orphaned .compact file from a previous crash
  #?(:clj (let [f (java.io.File. (str path ".compact"))]
            (when (.exists f) (.delete f)))
     :cljs (try (.unlinkSync (js/require "fs") (str path ".compact"))
                (catch :default _))
     :cljd (try (let [f (dart-io/File (str path ".compact"))]
                  (when (.existsSync f) (.deleteSync f)))
                (catch #?(:cljd Object
                          :default Exception)
                       _)))
  ;; Important: ds/open! for log transport automatically truncates torn
  ;; tails
  (let [stream (ds/open! {:type :append-log, :path path})
        state (recover-index stream)]
    (->KVFile path (atom (assoc state :stream stream)))))

(ns dao.jing.kv.file
  "A single-file, persistent backend for IKVStore using the Bitcask (append-only log) architecture.
   Provides true block storage over a local file."
  (:require
    #?@(:cljd [["dart:convert" :as convert]
               ["dart:typed_data" :as typed]])
    [clojure.edn :as edn]
    [dao.jing.kv :as kv]
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


#_{:clj-kondo/ignore [:unused-binding]}


(defn- do-with-lock
  [lock f]
  #?(:clj (locking lock (f))
     :default (f)))


(defrecord KVFile
  [state-atom stream]

  kv/IKVStore

  (put!
    [_ k v-map]
    (do-with-lock state-atom
                  (fn []
                    (let [state @state-atom
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
    [_ k not-found]
    (let [state @state-atom
          idx (get-in state [:index k])]
      (if-not idx
        not-found
        (let [res (ds/next stream (:cursor idx))]
          (if-not (map? res)
            not-found
            (let [payload (bytes->str (:ok res))
                  [_ _ v-map] (edn/read-string payload)]
              v-map))))))


  (delete!
    [_ k]
    (do-with-lock state-atom
                  (fn []
                    (let [state @state-atom
                          payload (pr-str [k -1 nil])]
                      (when (= :ok
                               (:result (ds/put! stream (->bytes payload))))
                        (reset! state-atom (update state :index dissoc k))
                        true)))))


  (close!
    [_]
    (do-with-lock state-atom
                  (fn []
                    (let [state @state-atom]
                      (when-not (:closed state)
                        (swap! state-atom assoc :closed true)
                        (ds/close! stream)))))))


(defn create-kv-file
  "Creates a persistent, single-file KVStore using a Bitcask append-only log.
   Reads the entire file on startup to rebuild the in-memory index; a torn
   trailing record from a crashed append is truncated during recovery.

   Durability window: appends are written but not fsync'd, so a process crash is
   safe (the OS still holds the bytes) while a power loss can lose the most recent
   un-flushed appends. No compaction yet: overwritten and deleted records remain
   as dead space in the log until a future GC/merge sweep reclaims them."
  [path]
  ;; Important: ds/open! for log transport automatically truncates torn
  ;; tails
  (let [stream (ds/open! {:type :append-log, :path path})
        state (recover-index stream)]
    (->KVFile (atom state) stream)))

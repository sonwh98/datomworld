;; Shared by the J4b probe scripts (loaded with load-file on the JVM and
;; under jolt): open a durable store directory through dao.space.store, the
;; path every host takes, and publish an index into it with its HEAD.
(ns common
  (:require [dao.jing :as jing]
            [dao.space.index :as index]
            [dao.space.store :as store]
            [dao.stream :as stream]
            [dao.stream.memory-log :as memory-log]
            [dao.stream.ringbuffer :as ringbuffer]))


(defn datoms
  [n]
  (mapv (fn [i] [(inc i) :probe/n i 1 1]) (range n)))


(defn publish-content!
  "Publish `n` datoms into the open durable store without moving HEAD: the
   manifest address (its content is durable, nothing names it yet)."
  [st n]
  (let [local (:dao.stream/handle
                (memory-log/create! {:dao.stream/type :dao.stream/memory-log}))
        _ (doseq [d (datoms n)] (stream/append! local d))
        intake (:dao.stream/handle
                 (ringbuffer/create! {:dao.stream/type :dao.stream/ringbuffer
                                      :dao.stream.ringbuffer/capacity 4096}))
        {:keys [manifest-address]} (index/publish-index! local [intake])
        drain (fn drain
                [state]
                (let [r (jing/observe-step! st state)]
                  (case (:signal r)
                    :dao.stream/ok (drain (:state r))
                    (:dao.stream/blocked :dao.stream/end) nil
                    (throw (ex-info "observer hit a gap or defect" r)))))]
    (drain (jing/observer-state
             [{:stream intake
               :cursor (:dao.stream/cursor (stream/cursor intake :dao.stream/oldest))}]))
    manifest-address))


(defn publish!
  "publish-content!, then move HEAD to it: the manifest address."
  [st n]
  (let [m (publish-content! st n)]
    ((:head-fn st) m)
    m))


(defn open-or-refusal
  "[:acquired store] or [:refused message] for `dir`."
  [dir]
  (try [:acquired (store/open dir)]
       (catch Throwable t
         (if (re-find #"is locked by another process" (str (ex-message t)))
           [:refused (ex-message t)]
           (throw t)))))


(defn pid
  []
  (.pid (java.lang.ProcessHandle/current)))

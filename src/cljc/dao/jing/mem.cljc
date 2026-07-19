(ns dao.jing.mem
  "An ephemeral, thread-safe in-memory KVStore backend for dao.jing/IKVStore."
  (:require [dao.jing :as jing]))


(defrecord KVMem
  [state-atom]

  jing/IKVStore

  (put! [_ k v-map] (swap! state-atom assoc k (assoc v-map :rev 0)) true)


  (cas!
    [_ k old-rev v-map]
    (loop []
      (let [state @state-atom
            current (get state k)
            current-rev (or (:rev current) 0)]
        (if (not= old-rev current-rev)
          false
          (if (compare-and-set! state-atom
                                state
                                (assoc state
                                       k (assoc v-map :rev (inc current-rev))))
            true
            (recur))))))


  (get
    [_ k not-found]
    (let [s @state-atom] (if (contains? s k) (get s k) not-found)))


  (delete! [_ k] (swap! state-atom dissoc k) true)


  (close! [_] nil))


(defn create-kv-mem
  "Creates an ephemeral, thread-safe in-memory KVStore.
   Useful for testing and single-process ephemeral spaces."
  []
  (->KVMem (atom {})))

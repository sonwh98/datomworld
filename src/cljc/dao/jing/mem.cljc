(ns dao.jing.mem
  "An ephemeral, thread-safe in-memory KVStore backend for dao.jing/IKVStore."
  (:require [dao.jing :as jing]))


(defrecord KVMem
  [state-atom]

  jing/IKVStore

  (put! [_ k v] (swap! state-atom assoc k v) true)


  (cas!
    [_ k expected v]
    (loop []
      (let [state @state-atom
            ;; contains? rather than a nil test: nil is a legal stored
            ;; value, so absence has to be its own fact
            current (if (contains? state k) (get state k) jing/absent)]
        (if (not= expected current)
          false
          (if (compare-and-set! state-atom state (assoc state k v))
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

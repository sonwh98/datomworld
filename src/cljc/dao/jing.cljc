(ns dao.jing
  "The minimal key-value storage boundary for DaoJing.
   This protocol represents the 'dumb storage' layer (analogous to Datomic's KVStore,
   as specified in docs/datomic.md) that holds immutable datom segments and mutable
   stream references. It is entirely agnostic to Datalog, indexing, or datoms.
   It only stores opaque byte maps."
  (:refer-clojure :exclude [get]))


(defprotocol IKVStore

  (put!
    [this k v-map]
    "Write an entry unconditionally. Used for writing new immutable segments (which never overwrite).
     Returns true on success.")

  (cas!
    [this k old-rev v-map]
    "Compare-And-Swap an entry. Only writes v-map if the current revision matches old-rev.
     Used for updating mutable references like the stream root pointer.
     Returns true if successful, false if the CAS failed (meaning another writer won).")

  (get
    [this k not-found]
    "Read an entry by key. Returns the v-map (which includes its :rev), or not-found if the key
     is absent. The 2-arg signature mirrors clojure.core/get and Datomic's KVStore/get (get(Object, Object)).")

  (delete!
    [this k]
    "Remove an entry by key.")

  (compact!
    [this]
    "Perform garbage collection on the store to reclaim dead space from overwritten or deleted entries.")

  (close!
    [this]
    "Release the storage backend resources."))


(defrecord KVMem
  [state-atom]

  IKVStore

  (put! [_ k v-map] (swap! state-atom assoc k (assoc v-map :rev 0)) true)


  (cas!
    [_ k old-rev v-map]
    (loop []
      (let [state @state-atom
            current (clojure.core/get state k)
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
    (let [s @state-atom]
      (if (contains? s k) (clojure.core/get s k) not-found)))


  (delete! [_ k] (swap! state-atom dissoc k) true)


  (compact! [_] true)


  (close! [_] nil))


(defn create-kv-mem
  "Creates an ephemeral, thread-safe in-memory KVStore.
   Useful for testing and single-process ephemeral spaces."
  []
  (->KVMem (atom {})))

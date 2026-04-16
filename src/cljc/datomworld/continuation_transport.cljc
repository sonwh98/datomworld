(ns datomworld.continuation-transport
  "Pure transport stream helpers shared across hosts.

  Transport state:
    {:k-stream <RingBufferStream>
     :cursors {:register-vm {:position n} :stack-vm {:position n}}
     :pending-ks {position k}}"
  (:require
    [dao.stream :as ds]
    [dao.stream.transport.ringbuffer]))


(defn init-state
  "Create a fresh transport state. vm-keys is a collection of keywords
   identifying the consuming VMs (e.g. [:register-vm :stack-vm])."
  [vm-keys]
  {:k-stream
   (ds/open! {:type :ringbuffer, :capacity nil}),
   :cursors (into {} (map (fn [k] [k {:position 0}])) vm-keys),
   :pending-ks {}})


(defn append-datom
  "Append one datom-like event record to transport.
   Returns [transport' datom]."
  [transport a v]
  (let [len (count transport)
        e (+ 7000 len)
        t (+ 1 len)
        datom [e a v t 0]
        _ (ds/put! transport datom)]
    [transport datom]))


(defn enqueue-k
  "Append a continuation summary to transport and store the full k
   payload off-stream in :pending-ks."
  [state summary k]
  (let [pos (count (:k-stream state))
        [transport* _] (append-datom (:k-stream state)
                                     :stream/k
                                     summary)]
    (-> state
        (assoc :k-stream transport*)
        (assoc-in [:pending-ks pos] k))))


(defn consume-k-for
  "Consume the next continuation addressed to vm-key from transport.
   Returns [state' message-or-nil].
   Always advances and persists the cursor, even when no matching message exists."
  [state vm-key]
  (let [transport (:k-stream state)
        cur (get-in state [:cursors vm-key])]
    (loop [c cur]
      (let [result (ds/next transport c)]
        (if (keyword? result)
          [(assoc-in state [:cursors vm-key] c) nil]
          (let [{:keys [ok cursor]} result
                [_e a summary _t _m] ok]
            (if (and (= a :stream/k) (= (:to summary) vm-key))
              (let [msg-pos (dec (:position cursor))
                    k (get-in state [:pending-ks msg-pos])
                    [transport* _] (append-datom transport
                                                 :stream/deliver
                                                 {:message-pos msg-pos,
                                                  :to vm-key,
                                                  :control (:control summary)})]
                [(-> state
                     (assoc :k-stream transport*)
                     (assoc-in [:cursors vm-key] cursor)
                     (update :pending-ks dissoc msg-pos))
                 {:from (:from summary),
                  :to (:to summary),
                  :summary summary,
                  :k k}])
              (recur cursor))))))))


(defn in-flight-summary
  "Read transport from each vm cursor and summarize continuation events
   visible between that cursor and stream head."
  [transport cursors]
  (into []
        (mapcat
          (fn [[vm-key cur]]
            (loop [c cur
                   acc []]
              (let [result (ds/next transport c)]
                (if (keyword? result)
                  acc
                  (let [{:keys [ok cursor]} result
                        [_e a summary _t _m] ok]
                    (if (and (= a :stream/k)
                             (= (:to summary) vm-key))
                      (recur cursor
                             (conj acc
                                   {:position (dec (:position cursor)),
                                    :from (:from summary),
                                    :to (:to summary),
                                    :control (:control summary),
                                    :k-depth (:k-depth summary)}))
                      (recur cursor acc))))))))
        cursors))

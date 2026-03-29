(ns datomworld.continuation-transport
  "Pure transport stream helpers shared across hosts.

  Transport state:
    {:continuation-stream <RingBufferStream>
     :cursors {:register-vm {:position n} :stack-vm {:position n}}
     :pending-continuations {position continuation}}"
  (:require
    [dao.stream :as ds]))


(defn init-state
  "Create a fresh transport state. vm-keys is a collection of keywords
   identifying the consuming VMs (e.g. [:register-vm :stack-vm])."
  [vm-keys]
  {:continuation-stream
   (ds/make-ring-buffer-stream nil),
   :cursors (into {} (map (fn [k] [k {:position 0}])) vm-keys),
   :pending-continuations {}})


(defn append-datom
  "Append one datom-like event record to transport.
   Returns [transport' datom]."
  [transport a v]
  (let [len (ds/length transport)
        e (+ 7000 len)
        t (+ 1 len)
        datom [e a v t 0]
        _ (ds/put! transport datom)]
    [transport datom]))


(defn enqueue-continuation
  "Append a continuation summary to transport and store the full continuation
   payload off-stream in :pending-continuations."
  [state summary continuation]
  (let [pos (ds/length (:continuation-stream state))
        [transport* _] (append-datom (:continuation-stream state)
                                     :stream/continuation
                                     summary)]
    (-> state
        (assoc :continuation-stream transport*)
        (assoc-in [:pending-continuations pos] continuation))))


(defn consume-continuation-for
  "Consume the next continuation addressed to vm-key from transport.
   Returns [state' message-or-nil].
   Always advances and persists the cursor, even when no matching message exists."
  [state vm-key]
  (let [transport (:continuation-stream state)
        cur (get-in state [:cursors vm-key])]
    (loop [c cur]
      (let [result (ds/next transport c)]
        (if (keyword? result)
          [(assoc-in state [:cursors vm-key] c) nil]
          (let [{:keys [ok cursor]} result
                [_e a summary _t _m] ok]
            (if (and (= a :stream/continuation) (= (:to summary) vm-key))
              (let [msg-pos (dec (:position cursor))
                    continuation (get-in state [:pending-continuations msg-pos])
                    [transport* _] (append-datom transport
                                                 :stream/deliver
                                                 {:message-pos msg-pos,
                                                  :to vm-key,
                                                  :control (:control summary)})]
                [(-> state
                     (assoc :continuation-stream transport*)
                     (assoc-in [:cursors vm-key] cursor)
                     (update :pending-continuations dissoc msg-pos))
                 {:from (:from summary),
                  :to (:to summary),
                  :summary summary,
                  :continuation continuation}])
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
                    (if (and (= a :stream/continuation)
                             (= (:to summary) vm-key))
                      (recur cursor
                             (conj acc
                                   {:position (dec (:position cursor)),
                                    :from (:from summary),
                                    :to (:to summary),
                                    :control (:control summary),
                                    :continuation-depth (:continuation-depth
                                                          summary)}))
                      (recur cursor acc))))))))
        cursors))

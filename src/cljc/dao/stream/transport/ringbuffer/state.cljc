(ns dao.stream.transport.ringbuffer.state)


(def ^:private put-result-key ::put-result)
(def ^:private closed-put-result ::closed)
(def ^:private take-result-key ::take-result)


(defn initial-state
  ([] (initial-state 0))
  ([position]
   {:buffer {}
    :head position
    :tail position
    :closed false
    :reader-waiters {}
    :writer-waiters []}))


(defn count-state
  [state]
  (- (:tail state) (:head state)))


(defn put-state
  [state capacity val]
  (let [head (:head state)
        tail (:tail state)
        available (- tail head)]
    (cond
      (:closed state)
      (assoc state put-result-key closed-put-result)

      (and capacity (>= available capacity))
      (assoc state put-result-key :full)

      :else
      (let [woken-entry (get (:reader-waiters state) tail)
            next-state (-> state
                           (assoc-in [:buffer tail] val)
                           (update :tail inc))]
        (if woken-entry
          (-> next-state
              (update :reader-waiters dissoc tail)
              (assoc put-result-key {:ok :ok
                                     :woken-entry woken-entry}))
          (assoc next-state put-result-key {:ok :ok}))))))


(defn put-outcome
  [state val]
  (let [put-result (get state put-result-key)]
    (when (= put-result closed-put-result)
      (throw (ex-info "Cannot put to closed stream" {})))
    (let [{:keys [woken-entry]} put-result]
      (if (= :full put-result)
        {:result :full}
        {:result :ok
         :woke (if woken-entry
                 [{:entry woken-entry
                   :value val
                   :position (dec (:tail state))}]
                 [])}))))


(defn next-outcome
  [state cursor]
  (let [pos (:position cursor)
        head (:head state)
        tail (:tail state)]
    (cond
      (< pos head) :daostream/gap
      (< pos tail) {:ok (get-in state [:buffer pos])
                    :cursor (update cursor :position inc)}
      (:closed state) :end
      :else :blocked)))


(defn close-state
  [state]
  (assoc state :closed true
         :reader-waiters {}
         :writer-waiters []))


(defn close-outcome
  [state]
  (let [reader-woken (mapv (fn [[_pos entry]]
                             {:entry entry
                              :value nil})
                           (:reader-waiters state))
        writer-woken (mapv (fn [entry]
                             {:entry entry
                              :value nil})
                           (:writer-waiters state))]
    {:woke (into reader-woken writer-woken)}))


(defn closed-state?
  [state]
  (:closed state))


(defn register-reader-waiter-state
  [state position entry]
  (assoc-in state [:reader-waiters position] entry))


(defn register-writer-waiter-state
  [state entry]
  (update state :writer-waiters conj entry))


(defn drain-one-state
  [state]
  (let [head (:head state)
        tail (:tail state)]
    (if (< head tail)
      (let [val (get-in state [:buffer head])
            state' (-> state
                       (update :buffer dissoc head)
                       (update :head inc))
            writer-waiters (:writer-waiters state')]
        (if (seq writer-waiters)
          (let [writer-entry (first writer-waiters)
                datom (:datom writer-entry)
                tail' (:tail state')
                reader-entry (get (:reader-waiters state') tail')]
            (-> state'
                (assoc-in [:buffer tail'] datom)
                (update :tail inc)
                (update :writer-waiters (comp vec rest))
                (cond-> reader-entry
                  (update :reader-waiters dissoc tail'))
                (assoc take-result-key
                       {:ok val
                        :woke (cond-> [{:entry writer-entry
                                        :value datom}]
                                reader-entry
                                (conj {:entry reader-entry
                                       :value datom
                                       :position tail'}))})))
          (assoc state' take-result-key {:ok val
                                         :woke []})))
      (if (:closed state)
        (assoc state take-result-key :end)
        (assoc state take-result-key :empty)))))


(defn drain-one-outcome
  [state]
  (get state take-result-key))

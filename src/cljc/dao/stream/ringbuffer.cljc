(ns dao.stream.ringbuffer
  "Reference implementation of DaoStream using a memory-backed ring buffer.
   
   state-atom holds: {:buffer {} :head 0 :tail 0 :closed false :reader-waiters {} :writer-waiters []}
     :buffer          — map of absolute-index -> value
     :head            — absolute index of next take! position (oldest available)
     :tail            — absolute index of next put! position
     :closed          — boolean
     :reader-waiters  — map of position -> wait-set-entry; woken when put! appends at that position
     :writer-waiters  — vector of wait-set-entries; first one woken when drain-one! frees space"
  (:require
    [dao.stream :as ds]
    [yin.module :as module])
  #?(:cljs
     (:require-macros
       [dao.stream])))


(def ^:private put-result-key ::put-result)
(def ^:private closed-put-result ::closed)
(def ^:private take-result-key ::take-result)
(def ^:private default-eviction-policy :reject)
(def ^:private supported-eviction-policies #{:reject :evict-oldest})


(defn- initial-state
  ([] (initial-state 0))
  ([position]
   {:buffer {},
    :head position,
    :tail position,
    :closed false,
    :reader-waiters {},
    :writer-waiters []}))


(defn- count-state
  [state]
  (- (:tail state) (:head state)))


(defn- normalize-eviction-policy
  [eviction-policy]
  (let [policy (or eviction-policy default-eviction-policy)]
    (when-not (contains? supported-eviction-policies policy)
      (throw (ex-info "Unsupported ringbuffer eviction policy"
                      {:eviction-policy eviction-policy,
                       :supported-policies supported-eviction-policies})))
    policy))


(defn- append-state
  [state val]
  (let [tail (:tail state)
        woken-entry (get (:reader-waiters state) tail)
        next-state (-> state
                       (assoc-in [:buffer tail] val)
                       (update :tail inc))]
    (if woken-entry
      (-> next-state
          (update :reader-waiters dissoc tail)
          (assoc put-result-key {:ok :ok, :woken-entry woken-entry}))
      (assoc next-state put-result-key {:ok :ok}))))


(defn- evict-oldest-state
  [state]
  (let [head (:head state)
        tail (:tail state)]
    (if (< head tail)
      (-> state
          (update :buffer dissoc head)
          (update :head inc))
      state)))


(defn- put-state
  [state capacity eviction-policy val]
  (let [head (:head state)
        tail (:tail state)
        available (- tail head)]
    (cond (:closed state) (assoc state put-result-key closed-put-result)
          (and capacity (>= available capacity))
          (case eviction-policy
            :evict-oldest (if (pos? capacity)
                            (recur (evict-oldest-state state)
                                   capacity
                                   eviction-policy
                                   val)
                            (assoc state put-result-key :full))
            :reject (assoc state put-result-key :full))
          :else (append-state state val))))


(defn- put-outcome
  [state val]
  (let [put-result (get state put-result-key)]
    (when (= put-result closed-put-result)
      (throw (ex-info "Cannot put to closed stream" {})))
    (let [{:keys [woken-entry]} put-result]
      (if (= :full put-result)
        {:result :full}
        {:result :ok,
         :woke
         (if woken-entry
           [{:entry woken-entry, :value val, :position (dec (:tail state))}]
           [])}))))


(defn- next-outcome
  [state cursor]
  (let [pos (:position cursor)
        head (:head state)
        tail (:tail state)]
    (cond (< pos head) :daostream/gap
          (< pos tail) {:ok (get-in state [:buffer pos]),
                        :cursor (update cursor :position inc)}
          (:closed state) :end
          :else :blocked)))


(defn- close-state
  [state]
  (assoc state
         :closed true
         :reader-waiters {}
         :writer-waiters []))


(defn- close-outcome
  [state]
  (let [reader-woken (mapv (fn [[_pos entry]] {:entry entry, :value nil})
                           (:reader-waiters state))
        writer-woken (mapv (fn [entry] {:entry entry, :value nil})
                           (:writer-waiters state))]
    {:woke (into reader-woken writer-woken)}))


(defn- closed-state?
  [state]
  (:closed state))


(defn- register-reader-waiter-state
  [state position entry]
  (assoc-in state [:reader-waiters position] entry))


(defn- register-writer-waiter-state
  [state entry]
  (update state :writer-waiters conj entry))


(defn- drain-one-state
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
                (cond-> reader-entry (update :reader-waiters dissoc tail'))
                (assoc take-result-key
                       {:ok val,
                        :woke (cond-> [{:entry writer-entry, :value datom}]
                                reader-entry (conj {:entry reader-entry,
                                                    :value datom,
                                                    :position tail'}))})))
          (assoc state' take-result-key {:ok val, :woke []})))
      (if (:closed state)
        (assoc state take-result-key :end)
        (assoc state take-result-key :empty)))))


(defn- drain-one-outcome
  [state]
  (get state take-result-key))


#?(:cljd
   (deftype RingBufferStream
     [capacity eviction-policy state-atom]

     cljd.core/ICounted

     (-count [_] (count-state @state-atom))


     ds/IDaoStreamWriter

     (put!
       [_this val]
       (let [result
             (swap! state-atom put-state capacity eviction-policy val)]
         (put-outcome result val)))


     ds/IDaoStreamReader

     (next [_this cursor] (next-outcome @state-atom cursor))


     ds/IDaoStreamBound

     (close!
       [_this]
       (let [result @state-atom]
         (swap! state-atom close-state)
         (close-outcome result)))


     (closed? [_this] (closed-state? @state-atom))


     ds/IDaoStreamWaitable

     (register-reader-waiter!
       [_this position entry]
       (swap! state-atom register-reader-waiter-state position entry))


     (register-writer-waiter!
       [_this entry]
       (swap! state-atom register-writer-waiter-state entry))


     ds/IDaoStreamDrainable

     (-drain-one!
       [_this]
       (let [result (swap! state-atom drain-one-state)]
         (drain-one-outcome result))))
   :clj (deftype RingBufferStream
          [capacity eviction-policy state-atom]

          clojure.lang.Counted

          (count [_] (count-state @state-atom))


          ds/IDaoStreamWriter

          (put!
            [_this val]
            (let [result
                  (swap! state-atom put-state capacity eviction-policy val)]
              (put-outcome result val)))


          ds/IDaoStreamReader

          (next [_this cursor] (next-outcome @state-atom cursor))


          ds/IDaoStreamBound

          (close!
            [_this]
            (let [result @state-atom]
              (swap! state-atom close-state)
              (close-outcome result)))


          (closed? [_this] (closed-state? @state-atom))


          ds/IDaoStreamWaitable

          (register-reader-waiter!
            [_this position entry]
            (swap! state-atom register-reader-waiter-state position entry))


          (register-writer-waiter!
            [_this entry]
            (swap! state-atom register-writer-waiter-state entry))


          ds/IDaoStreamDrainable

          (-drain-one!
            [_this]
            (let [result (swap! state-atom drain-one-state)]
              (drain-one-outcome result))))
   :default
   (deftype RingBufferStream
     [capacity eviction-policy state-atom]

     ICounted

     (-count [_] (count-state @state-atom))


     ds/IDaoStreamWriter

     (put!
       [_this val]
       (let [result
             (swap! state-atom put-state capacity eviction-policy val)]
         (put-outcome result val)))


     ds/IDaoStreamReader

     (next [_this cursor] (next-outcome @state-atom cursor))


     ds/IDaoStreamBound

     (close!
       [_this]
       (let [result @state-atom]
         (swap! state-atom close-state)
         (close-outcome result)))


     (closed? [_this] (closed-state? @state-atom))


     ds/IDaoStreamWaitable

     (register-reader-waiter!
       [_this position entry]
       (swap! state-atom register-reader-waiter-state position entry))


     (register-writer-waiter!
       [_this entry]
       (swap! state-atom register-writer-waiter-state entry))


     ds/IDaoStreamDrainable

     (-drain-one!
       [_this]
       (let [result (swap! state-atom drain-one-state)]
         (drain-one-outcome result)))))


(defn- make-ring-buffer-stream*
  [capacity eviction-policy position]
  (->RingBufferStream capacity
                      (normalize-eviction-policy eviction-policy)
                      (atom (initial-state position))))


(defn make-ring-buffer-stream
  ([capacity] (make-ring-buffer-stream capacity 0))
  ([capacity position] (make-ring-buffer-stream* capacity nil position)))


(ds/defopen :ringbuffer
            [descriptor]
            (let [{:keys [capacity eviction-policy]} descriptor]
              (make-ring-buffer-stream* capacity eviction-policy 0)))


;; ============================================================
;; Yin VM Module API (returns effect descriptors)
;; ============================================================

(defn make
  ([] (make nil))
  ([cap] {:effect :stream/make, :capacity cap}))


(defn put!
  [s v]
  {:effect :stream/put, :stream s, :val v})


(defn cursor
  [s]
  {:effect :stream/cursor, :stream s})


(defn next!
  [c]
  {:effect :stream/next, :cursor c})


(defn take!
  [s]
  {:effect :stream/take, :stream s})


(defn close!
  [s]
  {:effect :stream/close, :stream s})


(module/register-module! 'stream
                         {'make make,
                          'put! put!,
                          'cursor cursor,
                          'next! next!,
                          'take! take!,
                          'close! close!})

(ns dao.stream.v2.ringbuffer
  "Fixed-capacity, evict-oldest DaoStream v2 reference transport."
  (:require [dao.stream.v2 :as stream]))


(def transport-type :dao.stream/ringbuffer)
(def capacity-key :dao.stream.ringbuffer/capacity)


(defn- result
  [outcome]
  {:dao.stream/outcome outcome})


(defn- valid-spec?
  [spec]
  (and (map? spec)
       (= transport-type (:dao.stream/type spec))
       (integer? (get spec capacity-key))
       (pos? (get spec capacity-key))))


(defn- fresh-state
  [capacity]
  (atom {:identity (random-uuid)
         :capacity capacity
         :first 0
         :tail 0
         :values {}
         :closed? false
         :attachments {}}))


(declare attach!)


(deftype RingHandle
  [state attachment-id owner?]

  stream/IDaoStreamDescriptor

  (descriptor
    [_]
    (let [s @state]
      {:dao.stream/outcome :dao.stream/ok
       :dao.stream/descriptor {:dao.stream/type transport-type
                               :dao.stream/identity (:identity s)}
       :dao.stream/identity (:identity s)}))


  stream/IDaoStreamReader

  (cursor
    [_ anchor]
    (let [s @state]
      (if (and (not owner?) (not (get-in s [:attachments attachment-id :open?])))
        (result :dao.stream/closed)
        (case anchor
          :dao.stream/oldest (assoc (result :dao.stream/ok)
                                    :dao.stream/cursor
                                    {:dao.stream.ringbuffer/identity (:identity s)
                                     :dao.stream.ringbuffer/position (:first s)})
          :dao.stream/newest (assoc (result :dao.stream/ok)
                                    :dao.stream/cursor
                                    {:dao.stream.ringbuffer/identity (:identity s)
                                     :dao.stream.ringbuffer/position (:tail s)})
          (result :dao.stream/invalid-anchor)))))


  (next
    [_ cursor-value]
    (let [s @state]
      (cond
        (not (map? cursor-value)) (result :dao.stream/invalid-cursor)
        (not (contains? cursor-value :dao.stream.ringbuffer/identity))
        (result :dao.stream/invalid-cursor)
        (not= (:identity s) (:dao.stream.ringbuffer/identity cursor-value))
        (result :dao.stream/cursor-mismatch)
        (not (integer? (:dao.stream.ringbuffer/position cursor-value)))
        (result :dao.stream/invalid-cursor)
        :else
        (let [pos (:dao.stream.ringbuffer/position cursor-value)
              frozen (when-not owner? (get-in s [:attachments attachment-id :tail]))
              visible-tail (if (some? frozen) frozen (:tail s))]
          (cond
            (< pos (:first s))
            (assoc (result :dao.stream/gap)
                   :dao.stream/cursor
                   {:dao.stream.ringbuffer/identity (:identity s)
                    :dao.stream.ringbuffer/position (:first s)})
            (< pos visible-tail)
            (if (contains? (:values s) pos)
              {:dao.stream/outcome :dao.stream/ok
               :dao.stream/value (get (:values s) pos)
               :dao.stream/cursor {:dao.stream.ringbuffer/identity (:identity s)
                                   :dao.stream.ringbuffer/position (inc pos)}}
              (result :dao.stream/transport-error))
            (or (:closed? s) (some? frozen)) (result :dao.stream/end)
            :else (result :dao.stream/blocked))))))


  stream/IDaoStreamWriter

  (append!
    [_ value]
    (if (and (not owner?) (not (get-in @state [:attachments attachment-id :open?])))
      (result :dao.stream/closed)
      (let [out (atom nil)]
        (swap! state
               (fn [s]
                 (if (:closed? s)
                   (do (reset! out (result :dao.stream/closed)) s)
                   (let [p (:tail s) n (inc p)
                         first' (max (:first s) (- n (:capacity s)))]
                     (reset! out (result :dao.stream/ok))
                     (-> s (assoc :tail n :first first')
                         (assoc :values
                                (assoc (if (> first' (:first s))
                                         (dissoc (:values s) (:first s))
                                         (:values s)) p value)))))))
        @out)))


  stream/IDaoStreamClosable

  (close!
    [_]
    (swap! state
           (fn [s]
             (if owner?
               (assoc s :closed? true)
               (assoc-in s [:attachments attachment-id]
                         {:open? false :tail (:tail s)}))))
    (result :dao.stream/ok)))


(defn create!
  "Create a ring buffer from a creation specification."
  [spec]
  (if-not (valid-spec? spec)
    (result :dao.stream/invalid-spec)
    (let [state (fresh-state (get spec capacity-key))
          id (random-uuid)]
      (swap! state assoc-in [:attachments id] {:open? true})
      {:dao.stream/outcome :dao.stream/ok
       :dao.stream/handle (RingHandle. state id true)
       :dao.stream/identity (:identity @state)})))


(defn- resolve-state
  [resolver identity]
  (let [x (if (fn? resolver) (resolver identity) (get resolver identity))]
    (cond (instance? RingHandle x) (.-state ^RingHandle x)
          (instance? #?(:clj clojure.lang.IAtom :cljs cljs.core/Atom) x) x
          :else nil)))


(defn attach!
  "Attach using a host-owned resolver (identity -> handle/state)."
  [resolver descriptor]
  (if-not (and (map? descriptor)
               (= transport-type (:dao.stream/type descriptor))
               (contains? descriptor :dao.stream/identity))
    (result :dao.stream/invalid-descriptor)
    (if-let [state (resolve-state resolver (:dao.stream/identity descriptor))]
      (let [id (random-uuid)]
        (swap! state assoc-in [:attachments id] {:open? true})
        {:dao.stream/outcome :dao.stream/ok
         :dao.stream/handle (RingHandle. state id false)
         :dao.stream/attachment id})
      (result :dao.stream/not-found))))


(defn make-attacher
  "Return a unary attach! closure over host-owned resolver state."
  [resolver]
  (fn [descriptor] (attach! resolver descriptor)))

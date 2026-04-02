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
    [dao.stream :as ds]))


#?(:clj
   (deftype RingBufferStream
     [capacity state-atom]

     clojure.lang.Counted

     (count
       [_]
       (let [state @state-atom]
         (- (:tail state) (:head state))))


     ds/IDaoStreamWriter

     (put!
       [_this val]
       (let [result (swap! state-atom
                           (fn [s]
                             (let [head (:head s)
                                   tail (:tail s)
                                   available (- tail head)]
                               (cond
                                 (:closed s)
                                 (assoc s ::put-result ::closed)
                                 (and capacity (>= available capacity))
                                 (assoc s ::put-result :full)
                                 :else
                                 (let [woken-entry (get (:reader-waiters s) tail)
                                       next-state (-> s
                                                      (assoc-in [:buffer tail] val)
                                                      (update :tail inc))]
                                   (if woken-entry
                                     (-> next-state
                                         (update :reader-waiters dissoc tail)
                                         (assoc ::put-result {:ok :ok, :woken-entry woken-entry}))
                                     (assoc next-state ::put-result {:ok :ok})))))))]
         (when (= (::put-result result) ::closed)
           (throw (ex-info "Cannot put to closed stream" {})))
         (let [{:keys [woken-entry]} (::put-result result)]
           (if (= :full (::put-result result))
             {:result :full}
             {:result :ok,
              :woke (if woken-entry
                      [{:entry woken-entry, :value val, :position (dec (:tail result))}]
                      [])}))))


     ds/IDaoStreamReader

     (next
       [_this cursor]
       (let [pos (:position cursor)
             state @state-atom
             head (:head state)
             tail (:tail state)]
         (cond (< pos head) :daostream/gap
               (< pos tail) {:ok (get-in state [:buffer pos]),
                             :cursor (update cursor :position inc)}
               (:closed state) :end
               :else :blocked)))


     ds/IDaoStreamBound

     (close!
       [_this]
       (let [state @state-atom]
         (swap! state-atom
                (fn [s]
                  (assoc s :closed true :reader-waiters {} :writer-waiters [])))
         (let [reader-woken (mapv (fn [[_pos entry]] {:entry entry, :value nil})
                                  (:reader-waiters state))
               writer-woken (mapv (fn [entry] {:entry entry, :value nil})
                                  (:writer-waiters state))]
           {:woke (into reader-woken writer-woken)})))


     (closed? [_this] (:closed @state-atom))


     ds/IDaoStreamWaitable

     (register-reader-waiter!
       [_this position entry]
       (swap! state-atom assoc-in [:reader-waiters position] entry))


     (register-writer-waiter!
       [_this entry]
       (swap! state-atom update :writer-waiters conj entry))


     ds/IDaoStreamDrainable

     (-drain-one!
       [_this]
       (let [result (swap! state-atom
                           (fn [s]
                             (let [head (:head s)
                                   tail (:tail s)]
                               (if (< head tail)
                                 (let [val (get-in s [:buffer head])
                                       s' (-> s
                                              (update :buffer dissoc head)
                                              (update :head inc))
                                       writer-waiters (:writer-waiters s')]
                                   (if (seq writer-waiters)
                                     ;; Pop first writer, write their datom, wake them.
                                     ;; ALSO: check if any reader was waiting for this new tail position.
                                     (let [writer-entry (first writer-waiters)
                                           datom (:datom writer-entry)
                                           tail (:tail s')
                                           reader-entry (get (:reader-waiters s') tail)
                                           s'' (-> s'
                                                   (assoc-in [:buffer tail] datom)
                                                   (update :tail inc)
                                                   (update :writer-waiters (comp vec rest))
                                                   (cond-> reader-entry (update :reader-waiters dissoc tail))
                                                   (assoc ::take-result
                                                          {:ok val,
                                                           :woke (cond-> [{:entry writer-entry, :value datom}]
                                                                   reader-entry (conj {:entry reader-entry, :value datom, :position tail}))}))]
                                       s'')
                                     ;; No writer waiting
                                     (assoc s' ::take-result {:ok val, :woke []})))
                                 (if (:closed s)
                                   (assoc s ::take-result :end)
                                   (assoc s ::take-result :empty))))))]
         (::take-result result))))

   :cljs
   (deftype RingBufferStream
     [capacity state-atom]

     ICounted

     (-count
       [_]
       (let [state @state-atom]
         (- (:tail state) (:head state))))


     ds/IDaoStreamWriter

     (put!
       [_this val]
       (let [result (swap! state-atom
                           (fn [s]
                             (let [head (:head s)
                                   tail (:tail s)
                                   available (- tail head)]
                               (cond
                                 (:closed s)
                                 (assoc s ::put-result ::closed)
                                 (and capacity (>= available capacity))
                                 (assoc s ::put-result :full)
                                 :else
                                 (let [woken-entry (get (:reader-waiters s) tail)
                                       next-state (-> s
                                                      (assoc-in [:buffer tail] val)
                                                      (update :tail inc))]
                                   (if woken-entry
                                     (-> next-state
                                         (update :reader-waiters dissoc tail)
                                         (assoc ::put-result {:ok :ok, :woken-entry woken-entry}))
                                     (assoc next-state ::put-result {:ok :ok})))))))]
         (when (= (::put-result result) ::closed)
           (throw (ex-info "Cannot put to closed stream" {})))
         (let [{:keys [woken-entry]} (::put-result result)]
           (if (= :full (::put-result result))
             {:result :full}
             {:result :ok,
              :woke (if woken-entry
                      [{:entry woken-entry, :value val, :position (dec (:tail result))}]
                      [])}))))


     ds/IDaoStreamReader

     (next
       [_this cursor]
       (let [pos (:position cursor)
             state @state-atom
             head (:head state)
             tail (:tail state)]
         (cond (< pos head) :daostream/gap
               (< pos tail) {:ok (get-in state [:buffer pos]),
                             :cursor (update cursor :position inc)}
               (:closed state) :end
               :else :blocked)))


     ds/IDaoStreamBound

     (close!
       [_this]
       (let [state @state-atom]
         (swap! state-atom
                (fn [s]
                  (assoc s :closed true :reader-waiters {} :writer-waiters [])))
         (let [reader-woken (mapv (fn [[_pos entry]] {:entry entry, :value nil})
                                  (:reader-waiters state))
               writer-woken (mapv (fn [entry] {:entry entry, :value nil})
                                  (:writer-waiters state))]
           {:woke (into reader-woken writer-woken)})))


     (closed? [_this] (:closed @state-atom))


     ds/IDaoStreamWaitable

     (register-reader-waiter!
       [_this position entry]
       (swap! state-atom assoc-in [:reader-waiters position] entry))


     (register-writer-waiter!
       [_this entry]
       (swap! state-atom update :writer-waiters conj entry))


     ds/IDaoStreamDrainable

     (-drain-one!
       [_this]
       (let [result (swap! state-atom
                           (fn [s]
                             (let [head (:head s)
                                   tail (:tail s)]
                               (if (< head tail)
                                 (let [val (get-in s [:buffer head])
                                       s' (-> s
                                              (update :buffer dissoc head)
                                              (update :head inc))
                                       writer-waiters (:writer-waiters s')]
                                   (if (seq writer-waiters)
                                     ;; Pop first writer, write their datom, wake them.
                                     ;; ALSO: check if any reader was waiting for this new tail position.
                                     (let [writer-entry (first writer-waiters)
                                           datom (:datom writer-entry)
                                           tail (:tail s')
                                           reader-entry (get (:reader-waiters s') tail)
                                           s'' (-> s'
                                                   (assoc-in [:buffer tail] datom)
                                                   (update :tail inc)
                                                   (update :writer-waiters (comp vec rest))
                                                   (cond-> reader-entry (update :reader-waiters dissoc tail))
                                                   (assoc ::take-result
                                                          {:ok val,
                                                           :woke (cond-> [{:entry writer-entry, :value datom}]
                                                                   reader-entry (conj {:entry reader-entry, :value datom, :position tail}))}))]
                                       s'')
                                     ;; No writer waiting
                                     (assoc s' ::take-result {:ok val, :woke []})))
                                 (if (:closed s)
                                   (assoc s ::take-result :end)
                                   (assoc s ::take-result :empty))))))]
         (::take-result result)))))


(defn make-ring-buffer-stream
  [capacity]
  (->RingBufferStream capacity (atom {:buffer {} :head 0 :tail 0 :closed false :reader-waiters {} :writer-waiters []})))


(defmethod ds/open! :ringbuffer [descriptor]
  (let [capacity (get-in descriptor [:transport :capacity])]
    (make-ring-buffer-stream capacity)))

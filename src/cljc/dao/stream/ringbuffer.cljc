(ns dao.stream.ringbuffer
  "Fixed-capacity, evict-oldest DaoStream v2 reference transport."
  (:require [dao.stream :as stream]))


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
  ;; The logical identity is carried in descriptors and cursors.  It therefore
  ;; belongs to DaoStream's portable data domain, unlike a host UUID object.
  (atom {:identity (str (random-uuid))
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
                    ;; The contract's recovery position is the earliest retained
                    ;; position, which equals the tail when nothing is retained.
                    ;; It must never be an evicted position: a frozen attachment
                    ;; whose visible history is fully evicted would otherwise be
                    ;; handed its own tail back and re-gap forever.  Recovering
                    ;; past that tail is correct; the next read answers `end`.
                    :dao.stream.ringbuffer/position (:first s)})
            (< pos visible-tail)
            ;; `:first`, `:tail`, and `:values` are updated as one state value;
            ;; positions in this interval are always retained by the transport.
            {:dao.stream/outcome :dao.stream/ok
             :dao.stream/value (get (:values s) pos)
             :dao.stream/cursor {:dao.stream.ringbuffer/identity (:identity s)
                                 :dao.stream.ringbuffer/position (inc pos)}}
            (or (:closed? s) (some? frozen)) (result :dao.stream/end)
            :else (result :dao.stream/blocked))))))


  stream/IDaoStreamWriter

  (append!
    [_ value]
    ;; Check attachment liveness *inside* the one state transition.  A deref
    ;; before swap! permits an append that began before an attachment close to
    ;; land after it; this form gives append!/close! a single linearization point.
    (let [out (volatile! nil)]
      (swap! state
             (fn [s]
               (if (or (:closed? s)
                       (and (not owner?)
                            (not (get-in s [:attachments attachment-id :open?]))))
                 (do (vreset! out (result :dao.stream/closed)) s)
                 (let [p (:tail s) n (inc p)
                       first' (max (:first s) (- n (:capacity s)))]
                   (vreset! out (result :dao.stream/ok))
                   (-> s (assoc :tail n :first first')
                       (assoc :values
                              (assoc (if (> first' (:first s))
                                       (dissoc (:values s) (:first s))
                                       (:values s)) p value)))))))
      @out))


  stream/IDaoStreamClosable

  (close!
    [_]
    (swap! state
           (fn [s]
             (if owner?
               (assoc s :closed? true)
               ;; Preserve the first frozen tail.  Later idempotent closes must
               ;; not make post-close values visible through this attachment.
               (if (get-in s [:attachments attachment-id :open?])
                 (assoc-in s [:attachments attachment-id]
                           {:open? false :tail (:tail s)})
                 s))))
    (result :dao.stream/ok)))


(defn create!
  "Create a ring buffer from a creation specification."
  [spec]
  (if-not (valid-spec? spec)
    (result :dao.stream/invalid-spec)
    (let [state (fresh-state (get spec capacity-key))]
      {:dao.stream/outcome :dao.stream/ok
       :dao.stream/handle (RingHandle. state nil true)
       :dao.stream/identity (:identity @state)})))


(defn- resolve-state
  [resolver identity]
  (let [x (if (fn? resolver) (resolver identity) (get resolver identity))]
    (cond (instance? RingHandle x) (.-state ^RingHandle x)
          #?(:cljd false
             :clj (instance? clojure.lang.IAtom x)
             :cljs (instance? cljs.core/Atom x)) x
          :else nil)))


(defn attach!
  "Attach using a host-owned resolver (identity -> handle/state)."
  [resolver descriptor]
  (if-not (and (map? descriptor)
               (= transport-type (:dao.stream/type descriptor))
               (contains? descriptor :dao.stream/identity))
    (result :dao.stream/invalid-descriptor)
    (if-let [state (resolve-state resolver (:dao.stream/identity descriptor))]
      ;; A resolver is host composition, not a trusted decoder.  Confirm that
      ;; its result actually denotes the descriptor's identity before minting a
      ;; handle; otherwise a mis-keyed directory silently attaches elsewhere.
      (if (= (:dao.stream/identity descriptor) (:identity @state))
        (let [id (str (random-uuid))]
          (swap! state assoc-in [:attachments id] {:open? true})
          {:dao.stream/outcome :dao.stream/ok
           :dao.stream/handle (RingHandle. state id false)
           :dao.stream/attachment id})
        (result :dao.stream/not-found))
      (result :dao.stream/not-found))))


(defn make-attacher
  "Return a unary attach! closure over host-owned resolver state.
   This small directory is composition/test infrastructure, never a registry
   owned by DaoStream or this namespace."
  [resolver]
  (fn [descriptor] (attach! resolver descriptor)))

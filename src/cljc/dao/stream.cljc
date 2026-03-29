(ns dao.stream
  "DaoStream: bidirectional channel with cursors.

  Two distinct things:

  Descriptor (serializable):
    {:capacity nil    ;; nil = unbounded, int = bounded
     :closed   false} ;; snapshot of closed status at serialization time

  IStream (operational, not serializable):
    Protocol with stateful implementations. RingBufferStream is the reference
    implementation backed by an atom.

  Cursor (plain map, constructed inline by caller):
    {:position n}"
  (:refer-clojure :exclude [next]))


;; =============================================================================
;; IStream Protocol
;; =============================================================================

(defprotocol IStream

  (put! [this val])
  ;; Mutates: appends val. Returns :ok or :full. Throws if closed.
  (take! [this])
  ;; Mutates: consumes next value. Returns {:ok val}, :empty (open), or
  ;; :end (closed).
  (next [this cursor])
  ;; Reads at cursor position. Returns {:ok val :cursor cursor'}, :blocked,
  ;; :end, or :daostream/gap.
  (length [this])
  ;; Reads: count of available (untaken) values.
  (close! [this])
  ;; Mutates: marks stream closed.
  (closed? [this]))


;; Reads: boolean.


(defmulti open!
  "Realize a descriptor into an operational IStream transport."
  (fn [descriptor] (get-in descriptor [:transport :type])))


;; =============================================================================
;; RingBufferStream — reference implementation
;; =============================================================================
;;
;; state-atom holds: {:buffer {} :head 0 :tail 0 :closed false}
;;   :buffer — map of absolute-index -> value
;;   :head   — absolute index of next take! position (oldest available)
;;   :tail   — absolute index of next put! position
;;   :closed — boolean
;;
;; Memory is reclaimed during take! via dissoc.
;; Cursors reading at pos < head receive :daostream/gap.

(defrecord RingBufferStream
  [capacity state-atom]

  IStream

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
                              (-> s
                                  (assoc-in [:buffer tail] val)
                                  (update :tail inc)
                                  (assoc ::put-result :ok))))))]
      (when (= (::put-result result) ::closed)
        (throw (ex-info "Cannot put to closed stream" {})))
      (::put-result result)))


  (take!
    [_this]
    (let [result (swap! state-atom
                        (fn [s]
                          (let [head (:head s)
                                tail (:tail s)]
                            (if (< head tail)
                              (let [val (get-in s [:buffer head])]
                                (-> s
                                    (update :buffer dissoc head)
                                    (update :head inc)
                                    (assoc ::take-result {:ok val})))
                              (if (:closed s)
                                (assoc s ::take-result :end)
                                (assoc s ::take-result :empty))))))]
      (::take-result result)))


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


  (length
    [_this]
    (let [state @state-atom]
      (- (:tail state) (:head state))))


  (close! [_this] (swap! state-atom assoc :closed true) nil)


  (closed? [_this] (:closed @state-atom)))


(defmethod open! :ringbuffer [descriptor]
  (let [capacity (get-in descriptor [:transport :capacity])]
    (->RingBufferStream capacity (atom {:buffer {} :head 0 :tail 0 :closed false}))))


;; =============================================================================
;; Utilities
;; =============================================================================

(defn ->seq
  "Convert an `IStream` into a lazy Clojure sequence of values that have been
   appended but not yet consumed.  A cursor walks the stream until `next` hits
   `:blocked`, `:end`, or a gap, at which point the lazy sequence terminates.
   The `ctx` argument is ignored here but retained so callers can keep the same
   calling shape they use for other stream helpers."
  [_ stream]
  (when stream
    (letfn [(walk
              [cursor]
              (lazy-seq (let [result (next stream cursor)]
                          (cond (map? result) (cons (:ok result)
                                                    (walk (:cursor result)))
                                (#{:blocked :end :daostream/gap} result) nil
                                :else nil))))]
      (walk {:position 0}))))

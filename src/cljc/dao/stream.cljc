(ns dao.stream
  "DaoStream: bidirectional channel with cursors.

  Two distinct things:

  Descriptor (serializable):
    {:capacity nil    ;; nil = unbounded, int = bounded
     :closed   false} ;; snapshot of closed status at serialization time

  IStream (operational, not serializable):
    Protocol with stateful implementations. LazySeqStream is the reference
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


;; =============================================================================
;; LazySeqStream — reference implementation
;; =============================================================================
;;
;; state-atom holds: {:log [] :head 0 :closed false}
;;   :log    — vector of all appended values
;;   :head   — absolute index of next take! position
;;   :closed — boolean

(defrecord LazySeqStream
  [capacity state-atom]

  IStream

  (put!
    [_this val]
    (let [state @state-atom]
      (when (:closed state)
        (throw (ex-info "Cannot put to closed stream" {})))
      (let [log (:log state)
            head (:head state)
            available (- (count log) head)]
        (if (and capacity (>= available capacity))
          :full
          (do (swap! state-atom update :log conj val) :ok)))))


  (take!
    [_this]
    (let [state @state-atom
          head (:head state)
          log (:log state)
          len (count log)]
      (if (< head len)
        (let [val (get log head)]
          (swap! state-atom update :head inc)
          {:ok val})
        (if (:closed state) :end :empty))))


  (next
    [_this cursor]
    (let [pos (:position cursor)
          state @state-atom
          head (:head state)
          log (:log state)
          len (count log)]
      (cond (< pos head) :daostream/gap
            (< pos len) {:ok (get log pos),
                         :cursor (update cursor :position inc)}
            (:closed state) :end
            :else :blocked)))


  (length
    [_this]
    (let [state @state-atom] (- (count (:log state)) (:head state))))


  (close! [_this] (swap! state-atom assoc :closed true) nil)


  (closed? [_this] (:closed @state-atom)))


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

;; Scripted DaoStream v2 handles for the binding tests. Where a test needs
;; an outcome the v2 ring buffer never produces — full, closed,
;; transport-error — the runtime plan's fixture rule applies: a reified
;; handle returning the scripted outcome.
(ns dao.gui.event.scripted
  (:require [dao.stream :as stream]))


(defn scripted-output
  "A v2 handle for one output destination. Admissions are decided by admit,
  a function of the count appended so far returning :dao.stream/ok or a
  refusing outcome (:dao.stream/full, :dao.stream/closed,
  :dao.stream/invalid-value, :dao.stream/transport-error); swapping the
  atom's :admit releases a parked interval the way a freed slot once did.
  Accepted values are retained and readable, so drain works unchanged.
  Returns {:handle handle :script state-atom}."
  [admit]
  (let [state (atom {:admit admit, :appended [], :closed? false})]
    {:script state,
     :handle
     (reify
       stream/IDaoStreamDescriptor
       (descriptor
         [_]
         {:dao.stream/outcome :dao.stream/ok,
          :dao.stream/descriptor {:dao.stream/type :dao.gui.event.scripted/output},
          :dao.stream/identity :dao.gui.event.scripted/output})


       stream/IDaoStreamReader

       (cursor
         [_ anchor]
         (if (contains? stream/standard-anchors anchor)
           {:dao.stream/outcome :dao.stream/ok,
            :dao.stream/cursor
            {:dao.gui.event.scripted/position
             (if (= :dao.stream/newest anchor)
               (count (:appended @state))
               0)}}
           {:dao.stream/outcome :dao.stream/invalid-anchor}))

       (next
         [_ c]
         (let [s @state, p (:dao.gui.event.scripted/position c)]
           (cond
             (not (integer? p)) {:dao.stream/outcome :dao.stream/invalid-cursor},
             (< p (count (:appended s)))
             {:dao.stream/outcome :dao.stream/ok,
              :dao.stream/value (nth (:appended s) p),
              :dao.stream/cursor {:dao.gui.event.scripted/position (inc p)}},
             (:closed? s) {:dao.stream/outcome :dao.stream/end},
             :else {:dao.stream/outcome :dao.stream/blocked})))


       stream/IDaoStreamWriter

       (append!
         [_ value]
         (let [decision ((:admit @state) (count (:appended @state)))]
           (if (= :dao.stream/ok decision)
             (do (swap! state update :appended conj value)
                 {:dao.stream/outcome :dao.stream/ok})
             {:dao.stream/outcome decision})))


       stream/IDaoStreamClosable

       (close!
         [_]
         (swap! state assoc :closed? true)
         {:dao.stream/outcome :dao.stream/ok}))}))


(defn scripted-input
  "A v2 reader handle whose every read answers outcome (an outcome keyword
  such as :dao.stream/transport-error) and counts reads, so a test can
  assert exactly one read per advance. Returns {:handle handle :reads
  counter-atom}."
  [outcome]
  (let [reads (atom 0)]
    {:reads reads,
     :handle
     (reify
       stream/IDaoStreamDescriptor
       (descriptor
         [_]
         {:dao.stream/outcome :dao.stream/ok,
          :dao.stream/descriptor {:dao.stream/type :dao.gui.event.scripted/input},
          :dao.stream/identity :dao.gui.event.scripted/input})


       stream/IDaoStreamReader

       (cursor
         [_ _anchor]
         {:dao.stream/outcome :dao.stream/ok,
          :dao.stream/cursor {:dao.gui.event.scripted/position 0}})

       (next
         [_ _c]
         (swap! reads inc)
         {:dao.stream/outcome outcome}))}))

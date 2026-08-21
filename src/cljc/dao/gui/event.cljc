;; dao.gui.event: the portable input interpreter downstream of terminal
;; presentation. One binding consumes canonical runtime inputs (presented
;; geometry, terminal profiles and signals, normalized pointer packets,
;; timer results, subscription commands, control values) and produces
;; effects, targeted pointer values, recognized gesture values, dispatch
;; fan-out, and diagnostics as explicit stream data.
;;
;; The public contract is a total reducer: (step state runtime-input) =>
;; {:state next-state :outputs [...]}. Every output carries the causing
;; :runtime/seq and its own :output/seq. Specification:
;; docs/design/dao.gui.event.md.
(ns dao.gui.event
  (:require [dao.gui.event.decl :as decl]
            [dao.gui.event.fault :as fault]
            [dao.gui.event.pointer :as pointer]
            [dao.gui.event.trace :as trace]))


(def state-version 1)


(def legal-runtime-sources
  "The only legal :runtime/source values in version 1."
  #{:geometry :profile :pointer :timer :subscription :terminal :control})


(def standard-gesture-kinds
  "Subscriber-visible semantic kinds of the standard recognizer vocabulary."
  #{:tap :long-press :pan :swipe :fling :transform :edge-pan :pressure-press})


(def legal-phases #{:recognized :start :update :end :cancel})


(def ^:private message-kind-sources
  {:geometry #{:dao.terminal/presented-geometry},
   :profile #{:dao.terminal/input-profile},
   :terminal #{:dao.terminal/reset :dao.terminal/coordinate-space-change
               :dao.terminal/input-loss :dao.terminal/rejection
               :dao.terminal/frame-skipped :dao.terminal/protocol-error}})


(def ^:private input-kind-sources
  {:pointer #{:pointer},
   :timer #{:dao.gui.event/timer-fired},
   :control #{:dao.gui.event/teardown}})


;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------

(defn initial-state
  "The version-1 empty interpreter value."
  []
  {:dao.gui.event/state-version state-version,
   :generation-id nil,
   :last-runtime-time-us nil,
   :last-runtime-seq -1,
   :active-coordinate-space-id nil,
   :coordinate-spaces {},
   :geometry {:active nil},
   :profiles {},
   :subscriptions {},
   :subscription-order [],
   :pointers {},
   :arenas {},
   :next-arena-id 0,
   :timers {}})


(defn fixture-projection
  "The eleven-key public state projection used by conformance fixtures."
  [state-or-binding]
  (let [state (if (contains? state-or-binding :state)
                (:state state-or-binding)
                state-or-binding)]
    {:generation-id (:generation-id state),
     :coordinate-space-id (:active-coordinate-space-id state),
     :active-frame-id (get-in state [:geometry :active :frame-id]),
     :profile-ids (sort (keys (:profiles state))),
     :subscription-ids (:subscription-order state),
     :active-pointer-ids (vec (trace/edn-sort (keys (:pointers state)))),
     :active-arena-ids (vec (sort (keys (:arenas state)))),
     :scheduled-timer-keys (vec (trace/edn-sort
                                  (keys (into {}
                                              (filter (fn [[_k timer]]
                                                        (= :scheduled
                                                           (:status timer))))
                                              (:timers state))))),
     :last-runtime-seq (:last-runtime-seq state),
     :last-runtime-time-us (:last-runtime-time-us state),
     :next-arena-id (:next-arena-id state)}))


;; ---------------------------------------------------------------------------
;; Diagnostics
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Envelope validation
;; ---------------------------------------------------------------------------

(defn- valid-envelope?
  [runtime-input]
  (and (map? (:runtime/value runtime-input))
       (integer? (:runtime/seq runtime-input))
       (integer? (:runtime/time-us runtime-input))
       (contains? legal-runtime-sources (:runtime/source runtime-input))))


(defn- kind-agrees-with-source?
  [source value]
  (or (and (contains? message-kind-sources source)
           (contains? (get message-kind-sources source) (:message/kind value)))
      (and (contains? input-kind-sources source)
           (contains? (get input-kind-sources source) (:input/kind value)))
      (and (= :subscription source)
           (contains? #{:add :remove} (:subscription/op value)))))


;; ---------------------------------------------------------------------------
;; Subscription registry
;; ---------------------------------------------------------------------------

(defn- legal-event-kind?
  [k]
  (or (= :pointer k)
      (contains? standard-gesture-kinds k)
      (qualified-keyword? k)))


(defn- subscription-fault
  [reason]
  (fault/diagnostic :dao.gui.event/unrecognized-event-kind :error reason))


(defn- step-subscription
  [state value]
  (let [{:keys [subscription/op subscription/id]} value]
    (if (= :remove op)
      (if (nil? id)
        {:state state,
         :outputs [(subscription-fault :malformed-subscription-command)]}
        {:state (-> state
                    (update :subscriptions dissoc id)
                    (update :subscription-order
                            (fn [order] (vec (remove #(= % id) order))))),
         :outputs []})
      (let [existing? (contains? (:subscriptions state) id)
            node-id (:node-id value)
            event-kind (:event-kind value)
            phases (:gesture/phases value)
            raw? (boolean (:raw? value))
            malformed? (or (nil? id)
                           (nil? node-id)
                           (not (legal-event-kind? event-kind))
                           (and (some? phases)
                                (or (not (set? phases))
                                    (empty? phases)
                                    (not (every? legal-phases phases)))))]
        (cond malformed? {:state state,
                          :outputs [(subscription-fault
                                      :malformed-subscription-command)]}
              existing? {:state state,
                         :outputs [(subscription-fault
                                     :duplicate-subscription-id)]}
              :else {:state (-> state
                                (assoc-in [:subscriptions id]
                                          {:subscription/id id,
                                           :subscriber/id (:subscriber/id
                                                            value),
                                           :node-id node-id,
                                           :event-kind event-kind,
                                           :gesture/phases phases,
                                           :raw? raw?})
                                (update :subscription-order conj id)),
                     :outputs []})))))


;; ---------------------------------------------------------------------------
;; Teardown
;; ---------------------------------------------------------------------------

(defn- step-teardown
  [state _value]
  ;; Teardown cancels every active arena, emits the final cancellation
  ;; dispatches and timer cancels, releases the registry, and closes the
  ;; outputs. Later inputs are ignored because the outputs are closed.
  (let [[cancelled outputs] (pointer/cancel-arenas state
                                                   :teardown
                                                   (set (keys (:arenas state)))
                                                   nil)]
    {:state (-> cancelled
                (assoc :closed true)
                (assoc :subscriptions {})
                (assoc :subscription-order [])
                (assoc :pointers {})
                (assoc :arenas {})),
     :outputs outputs}))


;; ---------------------------------------------------------------------------
;; Terminal stream values: geometry, profiles, space changes, reset
;; ---------------------------------------------------------------------------

(defn- valid-region?
  [region]
  (let [bounds (:bounds region)]
    (and (map? bounds)
         (fault/finite-number? (:x bounds))
         (fault/finite-number? (:y bounds))
         (fault/finite-number? (:width bounds))
         (fault/finite-number? (:height bounds))
         (>= (:width bounds) 0)
         (>= (:height bounds) 0)
         (fault/finite-number? (:paint-order region)))))


(defn- validate-node
  "Validate one presented node. Returns {:regions [...] :diagnostics [...]}
  where regions carry the node's interaction path for hit testing."
  [node]
  (let [node-id (:node-id node)
        path (vec (:interaction/path node))
        recognizers-by-node (into {}
                                  (map (fn [entry]
                                         [(:node-id entry)
                                          (vec (:recognizers entry))])
                                       path))]
    (reduce (fn [{:keys [regions diagnostics seen]} region]
              (if (valid-region? region)
                {:regions (conj regions
                                {:node-id node-id,
                                 :interaction/path path,
                                 :recognizers-by-node recognizers-by-node,
                                 :touch-action (:touch-action node),
                                 :bounds (:bounds region),
                                 :paint-order (:paint-order region)}),
                 :diagnostics diagnostics,
                 :seen seen}
                {:regions regions,
                 :diagnostics (conj diagnostics
                                    (fault/diagnostic
                                      :dao.gui.event/unsupported-region
                                      :warning :invalid-region
                                      :node-id node-id)),
                 :seen seen}))
            {:regions [], :diagnostics [], :seen #{}}
            (:regions node))))


(defn- validate-path-declarations
  "Validate recognizer declarations on one interaction path. Invalid
  declarations are omitted with one :invalid-recognizer diagnostic each;
  duplicate (node-id, recognizer-id) identities in the same frame are
  omitted with a diagnostic. :seen accumulates candidate identities across
  the whole frame."
  [path seen]
  (reduce (fn [{:keys [path diagnostics seen]} entry]
            (let [node-id (:node-id entry)
                  {:keys [kept omitted new-seen]}
                  (reduce (fn [{:keys [kept omitted seen]}
                               {:keys [recognizer/id], :as decl}]
                            (let [reason (decl/validate-declaration decl)]
                              (if (or reason (contains? seen [node-id id]))
                                {:kept kept,
                                 :omitted
                                 (conj omitted
                                       (fault/diagnostic
                                         :dao.gui.event/invalid-recognizer
                                         :error (or reason
                                                    :duplicate-candidate)
                                         :node-id node-id
                                         :recognizer/id id)),
                                 :seen seen}
                                {:kept (conj kept decl),
                                 :omitted omitted,
                                 :seen (conj seen [node-id id])})))
                          {:kept [], :omitted [], :seen seen}
                          (:recognizers entry))]
              {:path (conj path (assoc entry :recognizers kept)),
               :diagnostics (into diagnostics omitted),
               :seen new-seen}))
          {:path [], :diagnostics [], :seen seen}
          path))


(defn- step-geometry
  [state value]
  (let [{:keys [generation-id coordinate-space-id frame-id]} value
        {adopted :state, fault :fault}
        (fault/adopt-or-stale-generation state generation-id)]
    (cond (some? fault) {:state state, :outputs [fault]}
          (or (nil? coordinate-space-id)
              (not= coordinate-space-id (:active-coordinate-space-id adopted)))
          {:state state,
           :outputs [(fault/diagnostic
                       :dao.gui.event/coordinate-space-mismatch
                       :error :coordinate-space-mismatch
                       :coordinate-space-id coordinate-space-id)]}
          (let [active-frame (get-in adopted [:geometry :active :frame-id])]
            (and (some? active-frame) (<= (or frame-id 0) active-frame)))
          {:state state,
           :outputs [(fault/diagnostic :dao.gui.event/stale-frame-input
                                       :error :stale-frame
                                       :frame-id frame-id)]}
          :else (let [validated
                      (reduce (fn [acc node]
                                (let [path-result (validate-path-declarations
                                                    (:interaction/path node)
                                                    (:seen acc))
                                      node' (assoc node
                                                   :interaction/path
                                                   (:path path-result))
                                      node-result (validate-node node')]
                                  {:regions (into (:regions acc)
                                                  (:regions node-result)),
                                   :diagnostics
                                   (-> (:diagnostics acc)
                                       (into (:diagnostics path-result))
                                       (into (:diagnostics node-result))),
                                   :seen (:seen path-result)}))
                              {:regions [], :diagnostics [], :seen #{}}
                              (vec (:nodes value)))]
                  {:state (assoc adopted
                                 :geometry {:active value,
                                            :regions (:regions validated)}),
                   :outputs (:diagnostics validated)}))))


(defn- step-profile
  [state value]
  (let [{:keys [generation-id profile-id capabilities thresholds]} value
        {adopted :state, fault :fault}
        (fault/adopt-or-stale-generation state generation-id)]
    (cond (some? fault) {:state state, :outputs [fault]}
          (or (nil? profile-id) (not (integer? profile-id)))
          {:state state,
           :outputs [(fault/diagnostic :dao.gui.event/profile-mismatch
                                       :error
                                       :malformed-profile)]}
          (contains? (:profiles adopted) profile-id)
          {:state state,
           :outputs [(fault/diagnostic :dao.gui.event/profile-mismatch
                                       :error :duplicate-profile-id
                                       :profile-id profile-id)]}
          :else {:state (-> adopted
                            (assoc-in [:profiles profile-id]
                                      {:profile-id profile-id,
                                       :capabilities (set capabilities),
                                       :thresholds (or thresholds {})})
                            (assoc :latest-profile-id profile-id)),
                 :outputs []})))


(defn- clear-generation-state
  [state generation-id]
  (let [[timers timer-effects] (fault/scheduled-timer-cancels (:timers state))]
    {:state (assoc state
                   :generation-id generation-id
                   :active-coordinate-space-id nil
                   :coordinate-spaces {}
                   :geometry {:active nil}
                   :profiles {}
                   :latest-profile-id nil
                   :pointers {}
                   :arenas {}
                   :timers timers
                   :last-input-seq nil),
     :outputs timer-effects}))


(defn- step-reset
  [state value]
  (clear-generation-state state (:generation-id value)))


(defn- step-coordinate-space-change
  [state value]
  (let [{:keys [generation-id old-coordinate-space-id coordinate-space-id
                viewport]}
        value
        {adopted :state, fault :fault}
        (fault/adopt-or-stale-generation state generation-id)]
    (cond
      (some? fault) {:state state, :outputs [fault]}
      (not (and (map? viewport)
                (fault/finite-number? (:width viewport))
                (fault/finite-number? (:height viewport))
                (pos? (:width viewport))
                (pos? (:height viewport))))
      {:state state,
       :outputs [(fault/diagnostic :dao.gui.event/coordinate-space-mismatch
                                   :error :invalid-viewport
                                   :coordinate-space-id coordinate-space-id)]}
      (not= old-coordinate-space-id (:active-coordinate-space-id adopted))
      {:state state,
       :outputs [(fault/diagnostic :dao.gui.event/coordinate-space-mismatch
                                   :error :old-space-mismatch
                                   :coordinate-space-id coordinate-space-id)]}
      :else (let [[cancel-state cancel-outputs]
                  (pointer/cancel-all-arenas adopted :coordinate-space-change)
                  [timers timer-effects] (fault/scheduled-timer-cancels
                                           (:timers cancel-state))]
              {:state (-> cancel-state
                          (assoc :active-coordinate-space-id
                                 coordinate-space-id)
                          (update :coordinate-spaces
                                  assoc
                                  coordinate-space-id
                                  {:id coordinate-space-id,
                                   :viewport {:width (:width viewport),
                                              :height (:height viewport)}})
                          (assoc :geometry {:active nil})
                          (assoc :timers timers)),
               ;; the arena cancellations are observable output, not an
               ;; implementation detail: cancellation effects before
               ;; cancellation events before their dispatches
               :outputs (into (vec cancel-outputs) timer-effects)}))))


(defn- step-input-loss
  [state value]
  ;; The terminal reports packet facts only; the runtime maps affected
  ;; pointers to arenas and cancels them. Without a provable pointer set
  ;; every active arena in the generation is cancelled.
  (let [affected (set (:affected-pointer-ids value))
        arena-ids (if (seq affected)
                    (set (keep (fn [[_pid pointer]] (:arena-id pointer))
                               (select-keys (:pointers state) affected)))
                    (set (keys (:arenas state))))
        [cancelled outputs]
        (pointer/cancel-arenas state :input-loss arena-ids value)]
    {:state cancelled, :outputs outputs}))


(defn- step-terminal
  [state value]
  (case (:message/kind value)
    :dao.terminal/reset (step-reset state value)
    :dao.terminal/coordinate-space-change (step-coordinate-space-change state
                                                                        value)
    :dao.terminal/input-loss (step-input-loss state value)
    ;; rejection, frame-skipped, and protocol-error do not alter active
    ;; geometry or pointer capture
    {:state state, :outputs []}))


(defn- step-pointer
  [state runtime-input]
  (pointer/step-pointer state runtime-input))


(defn- step-timer
  [state runtime-input]
  (pointer/step-timer state runtime-input))


(def ^:private source-handlers
  {:subscription step-subscription,
   :control step-teardown,
   :geometry step-geometry,
   :profile step-profile,
   :terminal step-terminal,
   :pointer step-pointer,
   :timer step-timer})


;; ---------------------------------------------------------------------------
;; Total reducer
;; ---------------------------------------------------------------------------

(defn- tag-outputs
  [runtime-seq values]
  (vec (map-indexed (fn [i v]
                      (assoc v
                             :output/seq i
                             :runtime/seq runtime-seq))
                    values)))


(defn step
  "One canonical runtime input maps the interpreter value to the next
  interpreter value and an ordered vector of output values."
  [state runtime-input]
  (if (:closed state)
    {:state state, :outputs []}
    (if-not (valid-envelope? runtime-input)
      {:state state,
       :outputs (tag-outputs (:runtime/seq runtime-input)
                             [(fault/diagnostic
                                :dao.gui.event/unrecognized-event-kind
                                :error
                                :malformed-runtime-input)])}
      (let [rt-seq (:runtime/seq runtime-input)
            time-us (:runtime/time-us runtime-input)
            source (:runtime/source runtime-input)
            value (:runtime/value runtime-input)]
        (if-not (kind-agrees-with-source? source value)
          {:state state,
           :outputs (tag-outputs rt-seq
                                 [(fault/diagnostic
                                    :dao.gui.event/unrecognized-event-kind
                                    :error
                                    :unrecognized-event-kind)])}
          (if (and (some? (:last-runtime-time-us state))
                   (< time-us (:last-runtime-time-us state)))
            {:state state,
             :outputs (tag-outputs rt-seq
                                   [(fault/diagnostic
                                      :dao.gui.event/late-runtime-input
                                      :error
                                      :regressing-time)])}
            (let [handler (get source-handlers source)
                  ;; pointer and timer handlers consume the full envelope
                  ;; (they need the canonical seq and time)
                  handler-args (if (contains? #{:pointer :timer} source)
                                 [state runtime-input]
                                 [state value])
                  {handler-state :state, outputs :outputs} (apply handler
                                                                  handler-args)]
              {:state (assoc handler-state
                             :last-runtime-time-us time-us
                             :last-runtime-seq rt-seq),
               :outputs (tag-outputs rt-seq (vec outputs))})))))))


(defn replay
  "Fold step over a sequence of canonical runtime inputs, collecting all
  outputs in input order."
  [state inputs]
  (reduce (fn [{:keys [state outputs]} input]
            (let [{next-state :state, out :outputs} (step state input)]
              {:state next-state, :outputs (into (vec outputs) out)}))
          {:state state, :outputs []}
          inputs))


;; ---------------------------------------------------------------------------
;; Binding
;; ---------------------------------------------------------------------------

(defn bind
  "Data-oriented public constructor. Creates no ambient singleton and
  registers no host callbacks. :offer maps one canonical input to the next
  binding value plus its ordered outputs."
  [{:keys [inputs outputs], :as _spec}]
  (letfn [(make
            [state]
            {:dao.gui.event/binding-version state-version,
             :inputs inputs,
             :outputs outputs,
             :state state,
             :offer (fn [runtime-input]
                      (let [{next-state :state, out :outputs}
                            (step state runtime-input)]
                        {:binding (make next-state), :outputs out}))})]
    (make (initial-state))))

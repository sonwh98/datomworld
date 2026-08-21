;; Custom recognizer machines expressed as machine data. The evaluator is
;; total: transitions are tested in declaration order, the first true guard
;; runs, and an otherwise-unmatched legal input leaves the state unchanged
;; and emits nothing. Specification: docs/design/dao.gui.event.md section
;; Recognizer Machine Data.
(ns dao.gui.event.machine
  (:require [dao.gui.event.geom :as geom]
            [dao.gui.event.trace :as trace]))


(def legal-selectors
  #{:pointer/down :pointer/move :pointer/up :pointer/cancel :contacts/changed
    :timer/fired :arena/accepted :arena/rejected :arena/cancelled})


(def legal-operators
  #{:state/get :sample/get :pointer/get :config/get :profile/get :setting :and
    :or :not := :not= :< :<= :> :>= :contains? :+ :- :* :/ :abs :min :max :clamp
    :contacts/count :contacts/centroid :contacts/span :contacts/angle
    :contacts/ids :distance :delta :elapsed-us :velocity :direction
    :edge-distance})


(def legal-actions
  #{:state/assoc :state/dissoc :window/push :timer/start :timer/cancel
    :arena/hold :arena/accept :arena/reject :emit :goto})


(def legal-emission-phases #{:recognized :start :update :end :cancel})


(def declared-settings
  "Profile threshold keys a :setting lookup may name. Declaration overrides
  travel through :config/get instead."
  #{:motion/slop :tap/max-duration-us :multi-tap/max-delay-us :multi-tap/slop
    :long-press/delay-us :swipe/min-distance :swipe/max-duration-us
    :swipe/min-velocity :fling/min-velocity :fling/max-velocity
    :velocity/window-us :edge/width :pressure/start-threshold
    :pressure/release-threshold})


(def ^:private operator-arities
  "Exact [min max] argument counts per operator; nil max is unbounded."
  {:state/get [1 1],
   :sample/get [1 1],
   :pointer/get [1 1],
   :config/get [1 1],
   :profile/get [1 1],
   :setting [1 1],
   :not [1 1],
   :abs [1 1],
   :contacts/count [0 0],
   :contacts/centroid [0 0],
   :contacts/span [0 0],
   :contacts/angle [0 0],
   :contacts/ids [0 0],
   :elapsed-us [0 0],
   :velocity [0 0],
   :edge-distance [1 1],
   :contains? [2 2],
   := [2 2],
   :not= [2 2],
   :< [2 2],
   :<= [2 2],
   :> [2 2],
   :>= [2 2],
   :distance [2 2],
   :delta [2 2],
   :+ [2 nil],
   :- [2 nil],
   :* [2 nil],
   :/ [2 nil],
   :and [1 nil],
   :or [1 nil],
   :min [1 nil],
   :max [1 nil],
   :clamp [3 3],
   :direction [1 2]})


(def ^:private action-arities
  {:state/assoc [3 3],
   :state/dissoc [2 2],
   :window/push [3 3],
   :timer/start [3 3],
   :timer/cancel [2 2],
   :arena/hold [1 1],
   :arena/accept [1 1],
   :arena/reject [1 1],
   :emit [3 3],
   :goto [2 2]})


(def ^:private faulted ::fault)


;; ---------------------------------------------------------------------------
;; Validation
;; ---------------------------------------------------------------------------

(defn- non-finite-number?
  "NaN and infinities are not valid machine literals or results. NaN equals
  itself under Clojure =, so detection uses (* v 0): only a finite number
  times zero is zero."
  [v]
  (and (number? v) (not (zero? (* v 0)))))


(defn- non-finite-literals
  "Every non-finite numeric literal of an EDN value."
  [x]
  (cond (map? x) (mapcat non-finite-literals (concat (keys x) (vals x)))
        (set? x) (mapcat non-finite-literals x)
        (sequential? x) (mapcat non-finite-literals x)
        (non-finite-number? x) [x]
        :else []))


(defn- expression-faults
  "Validation faults of one expression tree: unknown operators, arity
  violations, undeclared settings, and non-finite literals. A vector whose
  head is a keyword is an operator call: heads outside the legal operator
  set are unknown operators, not data."
  [expr]
  (cond (map? expr) (mapcat expression-faults (concat (keys expr) (vals expr)))
        (set? expr) (mapcat expression-faults expr)
        (sequential? expr)
        (let [head (first expr)]
          (if (keyword? head)
            (if (contains? legal-operators head)
              (let [[lo hi] (get operator-arities head)
                    n (count (rest expr))]
                (concat (when (or (< n lo) (and (some? hi) (> n hi)))
                          [{:reason :invalid-arity, :operator head}])
                        (when (and (= :setting head)
                                   (keyword? (second expr))
                                   (not (contains? declared-settings
                                                   (second expr))))
                          [{:reason :undeclared-setting,
                            :setting (second expr)}])
                        (mapcat expression-faults (rest expr))))
              (concat [{:reason :unknown-operator, :operator head}]
                      (mapcat expression-faults (rest expr))))
            (mapcat expression-faults expr)))
        :else (map (fn [v] {:reason :non-finite-literal, :value v})
                   (non-finite-literals expr))))


(defn- action-expressions
  "The expression operands of every action in a transition."
  [actions]
  (mapcat (fn [action]
            (when (vector? action)
              (case (first action)
                :state/assoc [(nth action 2 nil)]
                :window/push [(nth action 2 nil)]
                :timer/start [(nth action 2 nil)]
                :emit [(nth action 2 nil)]
                [])))
          actions))


(defn- transition-faults
  "Validation faults of one transition."
  [transition state-id state-ids window-ids]
  (if-not (map? transition)
    [{:reason :malformed-transition, :state state-id}]
    (let [actions (vec (:actions transition))]
      (concat
        (when-not (contains? legal-selectors (:on transition))
          [{:reason :unknown-selector, :state state-id}])
        (map (fn [fault] (assoc fault :state state-id))
             (expression-faults (:when transition true)))
        (for [action actions
              :when (not (and (vector? action)
                              (contains? legal-actions (first action))))]
          {:reason :unknown-action, :state state-id})
        (for [action actions
              :when (and (vector? action)
                         (contains? action-arities (first action))
                         (let [[lo hi] (get action-arities (first action))
                               n (count action)]
                           (or (< n lo) (and (some? hi) (> n hi)))))]
          {:reason :invalid-arity, :state state-id})
        (map (fn [fault] (assoc fault :state state-id))
             (expression-faults (action-expressions actions)))
        (for [action actions
              :when (and (vector? action)
                         (= :goto (first action))
                         (not (contains? state-ids (second action))))]
          {:reason :unknown-state, :state state-id})
        (for [action actions
              :when (and (vector? action)
                         (= :window/push (first action))
                         (not (contains? window-ids (second action))))]
          {:reason :undeclared-window, :state state-id})
        (for [action actions
              :when (and (vector? action)
                         (= :emit (first action))
                         (not (contains? legal-emission-phases
                                         (second action))))]
          {:reason :illegal-emission-phase, :state state-id})
        ;; accept and reject at most once per transition
        (when (> (count (filter (fn [a]
                                  (and (vector? a)
                                       (contains? #{:arena/accept :arena/reject}
                                                  (first a))))
                                actions))
                 1)
          [{:reason :duplicate-decision, :state state-id}])
        ;; an emit before the accept of the same transition would emit from
        ;; an unaccepted candidate
        (when (and (some (fn [a] (and (vector? a) (= :emit (first a)))) actions)
                   (some (fn [a] (and (vector? a) (= :arena/accept (first a))))
                         actions)
                   (< (count (take-while (fn [a]
                                           (not (and (vector? a)
                                                     (= :emit (first a)))))
                                         actions))
                      (count (take-while (fn [a]
                                           (not (and (vector? a)
                                                     (= :arena/accept
                                                        (first a)))))
                                         actions))))
          [{:reason :emit-before-accept, :state state-id}])))))


(defn validate
  "Validate machine data. Returns a seq of {:reason k} fault values; empty
  when the machine is well formed. Every rule of Recognizer Machine Data is
  enforced: keyword state ids, valid initial state, bounded windows, legal
  selectors, operators, and actions, arities, finite EDN literals, declared
  settings, cancel transitions for non-terminal states, unique transition
  order, decision cardinality, and emit-after-accept ordering."
  [machine-data]
  (let [states (:states machine-data)
        state-ids (set (keys states))
        windows (:windows machine-data)
        window-ids (set (keys windows))]
    (concat
      (when (not= 1 (:machine/version machine-data))
        [{:reason :invalid-version}])
      (when-not (contains? state-ids (:initial machine-data))
        [{:reason :unknown-initial-state}])
      (when (or (not (map? windows))
                (some (fn [[_id w]] (not (pos-int? (:capacity w)))) windows))
        [{:reason :invalid-window}])
      (when (seq (non-finite-literals (:state machine-data)))
        (for [v (non-finite-literals (:state machine-data))]
          {:reason :non-finite-literal, :value v}))
      (mapcat
        (fn [[state-id transitions]]
          (concat
            (when-not (keyword? state-id)
              [{:reason :non-keyword-state, :state state-id}])
            (when-not (sequential? transitions)
              [{:reason :invalid-transitions, :state state-id}])
            (when (and (sequential? transitions)
                       (seq transitions)
                       (not (some #(= :pointer/cancel (:on %)) transitions)))
              [{:reason :missing-cancel-transition, :state state-id}])
            ;; transition order must be unique: the same selector with the
            ;; same guard is dead code
            (when (sequential? transitions)
              (let [pairs (map (juxt :on (fn [t] (get t :when true)))
                               (filter map? transitions))]
                (when (not= (count pairs) (count (set pairs)))
                  [{:reason :duplicate-transition, :state state-id}])))
            (when (sequential? transitions)
              (mapcat
                (fn [transition]
                  (transition-faults transition state-id state-ids window-ids))
                (filter some? transitions)))))
        ;; host map iteration never participates: state faults are emitted
        ;; in stable EDN order across CLJ, CLJS, and CLJD
        (sort-by key trace/edn-compare states)))))


;; ---------------------------------------------------------------------------
;; Evaluation
;; ---------------------------------------------------------------------------

(defn initial-state
  [machine-data]
  {:machine/state (:initial machine-data),
   ::locals (:state machine-data {}),
   ::windows (into {} (map (fn [[id _w]] [id []]) (:windows machine-data))),
   ::start-time-us nil})


(defn- position?
  [v]
  (and (map? v) (number? (:x v)) (number? (:y v))))


(defn- evaluate
  "Evaluate one expression. Returns the value, nil for a missing optional
  lookup, or ::fault when the candidate must fault. The evaluator is total:
  comparisons requiring numeric operands are false for missing or
  non-numeric values, and :and/:or short-circuit left to right while
  propagating faults from operands that were actually evaluated."
  [expr ctx state]
  (cond
    (not (or (vector? expr) (map? expr) (set? expr))) expr
    (empty? expr) expr
    (and (keyword? (first expr)) (contains? legal-operators (first expr)))
    (let [op (first expr)
          args (rest expr)
          eval-args (fn []
                      (let [vs (map (fn [e] (evaluate e ctx state)) args)]
                        (if (some (fn [v] (= v faulted)) vs) [faulted] vs)))]
      (case op
        :state/get (get (::locals state) (first args))
        :sample/get (get (:sample ctx) (first args))
        :pointer/get (get (:pointer ctx) (first args))
        :config/get (get-in ctx [:config (first args)])
        :profile/get (get (:profile ctx) (first args))
        :setting (or (get-in ctx [:config (first args)])
                     (get-in ctx [:profile :thresholds (first args)])
                     faulted)
        :and (reduce (fn [_acc v]
                       (cond (= v faulted) (reduced faulted)
                             (false? v) (reduced false)
                             :else true))
                     true
                     (map (fn [e] (evaluate e ctx state)) args))
        :or (reduce (fn [_acc v]
                      (cond (= v faulted) (reduced faulted)
                            (true? v) (reduced true)
                            :else false))
                    false
                    (map (fn [e] (evaluate e ctx state)) args))
        :not (let [v (evaluate (first args) ctx state)]
               (if (= v faulted) faulted (not v)))
        (:contains?) (let [[a b] (eval-args)]
                       (cond (set? a) (contains? a b)
                             (map? a) (contains? a b)
                             (vector? a) (boolean (some #{b} a))
                             :else false))
        (:= :not= :< :<= :> :>=)
        (let [vs (vec (eval-args))]
          (cond (some #(= % faulted) vs) faulted
                (not= 2 (count vs)) false
                (some nil? vs) false
                ;; ordered comparisons require finite numeric operands;
                ;; equality compares any EDN values
                (and (contains? #{:< :<= :> :>=} op)
                     (some (fn [v]
                             (or (not (number? v)) (non-finite-number? v)))
                           vs))
                false
                :else (case op
                        := (= (first vs) (second vs))
                        :not= (not= (first vs) (second vs))
                        :< (< (first vs) (second vs))
                        :<= (<= (first vs) (second vs))
                        :> (> (first vs) (second vs))
                        :>= (>= (first vs) (second vs)))))
        (:+ :- :* :/)
        (let [vs (vec (eval-args))]
          (if (or (some #(= % faulted) vs)
                  (some #(or (nil? %) (not (number? %))) vs)
                  (< (count vs) 2))
            false
            (let [result
                  (case op
                    :+ (reduce + vs)
                    :- (- (first vs) (reduce + (rest vs)))
                    :* (reduce * vs)
                    :/ (if (some zero? (rest vs)) faulted (reduce / vs)))]
              (if (= result faulted)
                faulted
                (if (or (not= result result)
                        (= result ##Inf)
                        (= result ##-Inf))
                  faulted
                  result)))))
        :abs
        (let [v (evaluate (first args) ctx state)]
          (if (and (number? v) (not (non-finite-number? v))) (abs v) false))
        (:min :max) (let [vs (vec (eval-args))]
                      (cond (some #(= % faulted) vs) faulted
                            (or (empty? vs)
                                (some #(or (nil? %) (not (number? %))) vs))
                            false
                            :else (case op
                                    :min (reduce min vs)
                                    :max (reduce max vs))))
        :clamp (let [[v lo hi] (vec (eval-args))]
                 (if (some #(or (nil? %) (not (number? %))) [v lo hi])
                   false
                   (geom/clamp v lo hi)))
        :contacts/count (count (:contacts ctx))
        :contacts/centroid (geom/centroid (:contacts ctx))
        :contacts/span (geom/span (:contacts ctx))
        :contacts/angle (geom/angle (:contacts ctx))
        :contacts/ids (geom/contact-ids (:contacts ctx))
        :distance (let [[a b] (vec (eval-args))]
                    (cond (some #(= % faulted) [a b]) faulted
                          (or (nil? a) (nil? b)) false
                          (or (not (position? a)) (not (position? b))) faulted
                          :else (geom/distance a b)))
        :delta (let [[a b] (vec (eval-args))]
                 (cond (some #(= % faulted) [a b]) faulted
                       (or (nil? a) (nil? b)) false
                       (or (not (position? a)) (not (position? b))) faulted
                       :else {:x (- (double (:x a)) (double (:x b))),
                              :y (- (double (:y a)) (double (:y b)))}))
        :elapsed-us (if (nil? (::start-time-us state))
                      0
                      (- (:time-us ctx) (::start-time-us state)))
        :velocity (geom/window-velocity (:window ctx))
        :direction (let [v (evaluate (first args) ctx state)
                         axis (if (> (count args) 1)
                                (evaluate (second args) ctx state)
                                :free)]
                     (cond (= v faulted) faulted
                           (nil? v) nil
                           (not (position? v)) faulted
                           :else (geom/direction v axis)))
        :edge-distance
        (let [edge (first args)
              viewport (get-in ctx [:coordinate-space :viewport])
              position (get-in ctx [:sample :position])]
          (cond (nil? viewport) faulted
                (not (position? position)) faulted
                :else (geom/edge-distance position edge viewport)))
        ;; unreachable: operator membership checked above
        nil))
    (map? expr) (let [entries
                      (map (fn [entry]
                             (let [v (evaluate (second entry) ctx state)]
                               [(first entry) v]))
                           expr)]
                  (if (some (fn [[_k v]] (= v faulted)) entries)
                    faulted
                    (into {} entries)))
    (set? expr) (let [vs (map (fn [e] (evaluate e ctx state)) expr)]
                  (if (some (fn [v] (= v faulted)) vs) faulted (set vs)))
    ;; vector literal: evaluate children in declaration order
    :else (vec (map (fn [e] (evaluate e ctx state)) expr))))


(defn- transition-matches?
  [transition machine-input ctx state]
  (and (= (:on transition) (:machine/input machine-input))
       (let [guard (evaluate (:when transition true) ctx state)]
         (if (= guard faulted) ::fault (boolean guard)))))


(defn- apply-action
  [action ctx state effects emissions decision accepted?-after]
  (let [op (first action)]
    (case op
      :state/assoc (let [value (evaluate (nth action 2) ctx state)]
                     (if (= value faulted)
                       ::fault
                       [(assoc-in state [::locals (nth action 1)] value) effects
                        emissions decision accepted?-after]))
      :state/dissoc [(update-in state [::locals] dissoc (nth action 1)) effects
                     emissions decision accepted?-after]
      :window/push
      (let [value (evaluate (nth action 2) ctx state)
            window-id (nth action 1)
            capacity (get-in ctx [::machine :windows window-id :capacity] 32)
            current (get-in state [::windows window-id] [])]
        (if (= value faulted)
          ::fault
          [(assoc-in state
                     [::windows window-id]
                     (vec (take-last capacity (conj current value)))) effects
           emissions decision accepted?-after]))
      :timer/start
      (let [duration (evaluate (nth action 2) ctx state)]
        (if (or (= duration faulted) (not (number? duration)))
          ::fault
          [state (conj effects [:timer/start (nth action 1) (long duration)])
           emissions decision accepted?-after]))
      :timer/cancel [state (conj effects [:timer/cancel (nth action 1)])
                     emissions decision accepted?-after]
      :arena/hold [state effects emissions :hold accepted?-after]
      :arena/accept [state effects emissions :accept true]
      :arena/reject [state effects emissions :reject accepted?-after]
      :emit (let [payload (evaluate (nth action 2) ctx state)]
              (if (= payload faulted)
                ::fault
                (let [position (:position payload)]
                  [state effects
                   (conj emissions
                         {:phase (nth action 1),
                          :payload (dissoc payload :position),
                          :position position}) decision accepted?-after])))
      :goto (if (contains? (get-in ctx [::machine :states]) (nth action 1))
              [(assoc state :machine/state (nth action 1)) effects emissions
               decision accepted?-after]
              ::fault)
      ;; validated machines never reach this
      [state effects emissions decision accepted?-after])))


(defn step
  "Step one custom machine. The result matches the standard machine
  contract: {:state :effects :emissions :decision :fault?}. An input
  outside the declared selector alphabet faults the candidate; an
  unmatched legal input is the total no-op default."
  [machine-data ctx machine-input state _config]
  (if-not (contains? legal-selectors (:machine/input machine-input))
    {:state (assoc state :machine/state :rejected),
     :effects [],
     :emissions [],
     :decision :reject,
     :fault? true}
    (let [state (if (nil? (::start-time-us state))
                  (assoc state ::start-time-us (:time-us ctx))
                  state)
          state-with-machine (assoc ctx ::machine machine-data)
          transitions (get-in machine-data [:states (:machine/state state)] [])
          matching (reduce (fn [_ transition]
                             (let [matched (transition-matches?
                                             transition
                                             machine-input
                                             state-with-machine
                                             state)]
                               (if (= matched ::fault)
                                 (reduced ::fault)
                                 (if matched (reduced transition) nil))))
                           nil
                           transitions)]
      (cond (= matching ::fault) {:state (assoc state :machine/state :rejected),
                                  :effects [],
                                  :emissions [],
                                  :decision :reject,
                                  :fault? true}
            (nil? matching)
            ;; total default: unmatched legal input is a no-op
            {:state state, :effects [], :emissions [], :decision :hold}
            :else (let [{:keys [actions]} matching
                        result (reduce (fn [{:keys [state effects emissions
                                                    decision accepted?]} action]
                                         (let [applied (apply-action
                                                         action
                                                         state-with-machine
                                                         state
                                                         effects
                                                         emissions
                                                         decision
                                                         accepted?)]
                                           (if (= applied ::fault)
                                             (reduced ::fault)
                                             {:state (nth applied 0),
                                              :effects (nth applied 1),
                                              :emissions (nth applied 2),
                                              :decision (nth applied 3),
                                              :accepted? (nth applied 4)})))
                                       {:state state,
                                        :effects [],
                                        :emissions [],
                                        :decision :hold,
                                        :accepted? false}
                                       actions)]
                    (if (= result ::fault)
                      {:state (assoc state :machine/state :rejected),
                       :effects [],
                       :emissions [],
                       :decision :reject,
                       :fault? true}
                      {:state (:state result),
                       :effects (:effects result),
                       :emissions (:emissions result),
                       :decision (or (:decision result) :hold),
                       :fault? false}))))))

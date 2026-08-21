;; Custom recognizer machines expressed as machine data. The evaluator is
;; total: transitions are tested in declaration order, the first true guard
;; runs, and an otherwise-unmatched legal input leaves the state unchanged
;; and emits nothing. Specification: docs/design/dao.gui.event.md section
;; Recognizer Machine Data.
(ns dao.gui.event.machine
  (:require [dao.gui.event.geom :as geom]))


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

(def ^:private faulted ::fault)


;; ---------------------------------------------------------------------------
;; Validation
;; ---------------------------------------------------------------------------

(defn- expression-operators
  "All operator keywords used in an expression tree, including unknown
  head keywords of operator-shaped vectors and operators inside literal
  collections."
  [expr]
  (cond (sequential? expr) (if (and (seq expr) (keyword? (first expr)))
                             (into #{}
                                   (concat [(first expr)]
                                           (mapcat expression-operators
                                                   (rest expr))))
                             (into #{} (mapcat expression-operators expr)))
        (map? expr) (into #{}
                          (mapcat expression-operators
                                  (concat (keys expr) (vals expr))))
        (set? expr) (into #{} (mapcat expression-operators expr))
        :else #{}))


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


(defn validate
  "Validate machine data. Returns a seq of {:reason k} fault values; empty
  when the machine is well formed."
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
      (mapcat
        (fn [[state-id transitions]]
          (when-not (sequential? transitions)
            [{:reason :invalid-transitions, :state state-id}])
          (concat
            (when (and (sequential? transitions)
                       (seq transitions)
                       (not (some #(= :pointer/cancel (:on %)) transitions)))
              [{:reason :missing-cancel-transition, :state state-id}])
            (mapcat
              (fn [transition]
                (concat
                  (when-not (contains? legal-selectors (:on transition))
                    [{:reason :unknown-selector, :state state-id}])
                  (let [actions (:actions transition)]
                    (concat
                      (let [unknown (filter (fn [op]
                                              (not (contains? legal-operators
                                                              op)))
                                            (expression-operators
                                              (concat [(:when transition true)]
                                                      (action-expressions actions))))]
                        (for [op unknown]
                          {:reason :unknown-operator,
                           :state state-id,
                           :operator op}))
                      (for [action actions
                            :when (not (and (vector? action)
                                            (contains? legal-actions
                                                       (first action))))]
                        {:reason :unknown-action, :state state-id})
                      (for [action actions
                            :when (and (vector? action)
                                       (= :goto (first action))
                                       (not (contains? state-ids
                                                       (second action))))]
                        {:reason :unknown-state, :state state-id})
                      (for [action actions
                            :when (and (vector? action)
                                       (= :window/push (first action))
                                       (not (contains? window-ids
                                                       (second action))))]
                        {:reason :undeclared-window, :state state-id})
                      (for [action actions
                            :when (and (vector? action)
                                       (= :emit (first action))
                                       (not (contains? legal-emission-phases
                                                       (second action))))]
                        {:reason :illegal-emission-phase, :state state-id})
                      ;; an emit must follow an accept in the same
                      ;; transition or rely on previous acceptance
                      ;; accept and reject at most once per transition
                      (when (> (count (filter (fn [a]
                                                (and (vector? a)
                                                     (contains? #{:arena/accept
                                                                  :arena/reject}
                                                                (first a))))
                                              actions))
                               1)
                        [{:reason :duplicate-decision, :state state-id}])))))
              (filter some? transitions))))
        states))))


;; ---------------------------------------------------------------------------
;; Evaluation
;; ---------------------------------------------------------------------------

(defn initial-state
  [machine-data]
  {:machine/state (:initial machine-data),
   ::locals (:state machine-data {}),
   ::windows (into {} (map (fn [[id _w]] [id []]) (:windows machine-data))),
   ::start-time-us nil})


(defn- evaluate
  "Evaluate one expression. Returns the value, nil for a missing optional
  lookup, or ::fault when the candidate must fault."
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
        :and (reduce (fn [_acc v] (if (false? v) (reduced false) (boolean v)))
                     true
                     (map (fn [e] (evaluate e ctx state)) args))
        :or (reduce (fn [_acc v] (if (true? v) (reduced true) (boolean v)))
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
                (some #(or (nil? %) (and (number? %) (not= % %))) vs) false
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
        :abs (let [v (evaluate (first args) ctx state)]
               (if (number? v) (abs v) false))
        (:min :max) (let [vs (vec (eval-args))]
                      (if (some #(or (nil? %) (not (number? %))) vs)
                        false
                        (case op
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
                    (if (or (nil? a) (nil? b)) false (geom/distance a b)))
        :delta (let [[a b] (vec (eval-args))]
                 (if (or (nil? a) (nil? b))
                   false
                   {:x (- (double (:x a)) (double (:x b))),
                    :y (- (double (:y a)) (double (:y b)))}))
        :elapsed-us (if (nil? (::start-time-us state))
                      0
                      (- (:time-us ctx) (::start-time-us state)))
        :velocity (geom/window-velocity (:window ctx))
        :direction (let [v (evaluate (first args) ctx state)
                         axis (if (> (count args) 1)
                                (evaluate (second args) ctx state)
                                :free)]
                     (if (nil? v) nil (geom/direction v axis)))
        :edge-distance (let [edge (first args)
                             viewport (get-in ctx
                                              [:coordinate-space :viewport])
                             position (get-in ctx [:sample :position])]
                         (if (nil? viewport)
                           faulted
                           (geom/edge-distance position edge viewport)))
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
  contract: {:state :effects :emissions :decision :fault?}."
  [machine-data ctx machine-input state _config]
  (let [state (if (nil? (::start-time-us state))
                (assoc state ::start-time-us (:time-us ctx))
                state)
        state-with-machine (assoc ctx ::machine machine-data)
        transitions (get-in machine-data [:states (:machine/state state)] [])
        matching (reduce (fn [_ transition]
                           (let [matched (transition-matches? transition
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
                     :fault? false})))))

;; Recognizer declaration validation and canonical defaults for presented
;; geometry. Presented declarations must already be canonical: contacts are
;; ranges, never the authored integer shorthand. Specification:
;; docs/design/dao.gui.event.md sections Authored Interaction Data,
;; Recognizer Machine Data, Standard Recognizers.
(ns dao.gui.event.decl
  (:require [dao.gui.event.machine :as machine]))


(def ^:private standard-machines
  #{:dao.gui.event/tap :dao.gui.event/long-press :dao.gui.event/pan
    :dao.gui.event/swipe :dao.gui.event/fling :dao.gui.event/transform
    :dao.gui.event/edge-pan :dao.gui.event/pressure-press})


(def ^:private machine->gesture-kinds
  {:dao.gui.event/tap #{:tap},
   :dao.gui.event/long-press #{:long-press},
   :dao.gui.event/pan #{:pan :drag},
   :dao.gui.event/swipe #{:swipe},
   :dao.gui.event/fling #{:fling},
   :dao.gui.event/transform #{:transform :scale :pinch :rotation},
   :dao.gui.event/edge-pan #{:edge-pan},
   :dao.gui.event/pressure-press #{:pressure-press}})


(def ^:private universal-config-keys
  "Keys every standard configuration may carry."
  #{:contacts :join-after-accept :contact-loss})


(def ^:private machine->config-keys
  {:dao.gui.event/tap #{:count :max-duration-us :slop :max-delay-us
                        :multi-tap-slop},
   :dao.gui.event/long-press #{:delay-us :slop},
   :dao.gui.event/pan #{:axis :start-at :slop},
   :dao.gui.event/swipe #{:direction :min-distance :max-duration-us
                          :min-velocity},
   :dao.gui.event/fling #{:direction :min-velocity :max-velocity},
   :dao.gui.event/transform #{:translation-slop :scale-slop :rotation-slop},
   :dao.gui.event/edge-pan #{:edge :axis :slop},
   :dao.gui.event/pressure-press #{:start-threshold :release-threshold
                                   :peak-threshold}})


(def ^:private legal-modes #{:exclusive :cooperative})
(def ^:private legal-contact-loss #{:end :degrade :hold})
(def ^:private legal-axes #{:x :y :free})
(def ^:private legal-start-at #{:slop :down})
(def ^:private legal-directions #{:left :right :up :down :any})
(def ^:private legal-edges #{:left :right :top :bottom})


(defn- finite-number?
  [v]
  (and (number? v)
       (<= -1.7976931348623157E308 (double v) 1.7976931348623157E308)))


(defn- valid-contacts?
  [contacts]
  (and (map? contacts)
       (pos-int? (:min contacts))
       (pos-int? (:max contacts))
       (<= (:min contacts) (:max contacts))))


(defn- config-value-fault
  "Enum and range validation for the per-machine configuration keys."
  [machine config]
  (case machine
    :dao.gui.event/tap
    (cond (and (contains? config :count) (not (pos-int? (:count config))))
          :invalid-count
          (some #(and (contains? config %)
                      (not (finite-number? (get config %))))
                [:max-duration-us :slop :max-delay-us :multi-tap-slop])
          :invalid-threshold)
    :dao.gui.event/long-press (cond (and (contains? config :delay-us)
                                         (not (pos-int? (:delay-us config))))
                                    :invalid-threshold
                                    (and (contains? config :slop)
                                         (not (finite-number? (:slop config))))
                                    :invalid-threshold)
    :dao.gui.event/pan
    (cond (and (contains? config :axis)
               (not (contains? legal-axes (:axis config))))
          :invalid-axis
          (and (contains? config :start-at)
               (not (contains? legal-start-at (:start-at config))))
          :invalid-start-at
          (and (contains? config :slop) (not (finite-number? (:slop config))))
          :invalid-threshold)
    :dao.gui.event/swipe
    (cond (and (contains? config :direction)
               (not (contains? legal-directions (:direction config))))
          :invalid-direction
          (some #(and (contains? config %)
                      (not (or (pos-int? (get config %))
                               (finite-number? (get config %)))))
                [:min-distance :max-duration-us :min-velocity])
          :invalid-threshold)
    :dao.gui.event/fling
    (cond (and (contains? config :direction)
               (not (contains? legal-directions (:direction config))))
          :invalid-direction
          (some #(and (contains? config %)
                      (not (finite-number? (get config %))))
                [:min-velocity :max-velocity])
          :invalid-threshold)
    :dao.gui.event/transform
    (when (some #(and (contains? config %)
                      (not (finite-number? (get config %))))
                [:translation-slop :scale-slop :rotation-slop])
      :invalid-threshold)
    :dao.gui.event/edge-pan
    (cond (and (contains? config :edge)
               (not (contains? legal-edges (:edge config))))
          :invalid-edge
          (and (contains? config :axis)
               (not (contains? legal-axes (:axis config))))
          :invalid-axis
          (and (contains? config :slop) (not (finite-number? (:slop config))))
          :invalid-threshold)
    :dao.gui.event/pressure-press
    (when (some #(and (contains? config %)
                      (not (finite-number? (get config %))))
                [:start-threshold :release-threshold :peak-threshold])
      :invalid-threshold)))


(defn validate-declaration
  "Returns nil when the presented declaration is valid, else a reason
  keyword for the :dao.gui.event/invalid-recognizer diagnostic. Custom
  machines run their complete machine validation here: a malformed machine
  is omitted at installation rather than faulting per input later."
  [{:keys [recognizer/id gesture/kind machine config arena], :as decl}]
  (let [machine-faults (when (and (map? machine)
                                  (contains? machine :machine/version))
                         (machine/validate machine))]
    (cond
      (not (and (map? decl) (some? id) (keyword? kind))) :malformed-declaration
      (not (or (contains? standard-machines machine)
               (and (map? machine) (contains? machine :machine/version))))
      :unknown-machine
      (and (contains? standard-machines machine)
           (not (contains? (get machine->gesture-kinds machine) kind)))
      :illegal-gesture-kind
      (not (map? config)) :malformed-config
      ;; the authored integer shorthand is not presentable: contacts must
      ;; be a canonical range map
      (and (contains? config :contacts)
           (not (valid-contacts? (:contacts config))))
      :non-canonical-contacts
      (and (contains? config :contact-loss)
           (not (contains? legal-contact-loss (:contact-loss config))))
      :illegal-contact-loss
      (and (contains? config :join-after-accept)
           (not (boolean? (:join-after-accept config))))
      :illegal-join-after-accept
      (and (contains? standard-machines machine)
           (some (fn [k]
                   (and (contains? config k)
                        (not (or (contains? universal-config-keys k)
                                 (contains? (get machine->config-keys machine)
                                            k)))))
                 (keys config)))
      :unknown-config-key
      (and (contains? standard-machines machine)
           (config-value-fault machine config))
      (config-value-fault machine config)
      (not (map? arena)) :malformed-arena
      (and (contains? arena :mode) (not (contains? legal-modes (:mode arena))))
      :illegal-arena-mode
      ;; cooperative mode cannot coexist without a non-nil group: reject
      ;; the declaration rather than silently treating it as exclusive
      (and (= :cooperative (:mode arena)) (nil? (:coexistence/group arena)))
      :cooperative-without-group
      (and (contains? arena :priority) (not (finite-number? (:priority arena))))
      :invalid-priority
      ;; complete validation of a custom machine
      (seq machine-faults) (:reason (first machine-faults))
      :else nil)))


(defn join-after-accept
  "Whether an accepted winner admits a later joining contact."
  [decl]
  (get-in decl [:config :join-after-accept] false))

(ns datomworld.demo.artifact-runner
  (:require [clojure.string :as string]
            [dao.gui.event :as event]
            [datomworld.demo.artifact-scene :as scene]))


(def initial-state scene/initial-state)
(def pan-sensitivity 0.005)


(def pointer-event-phases
  {"pointerdown" :down,
   "pointermove" :move,
   "pointerup" :up,
   "pointercancel" :cancel,
   "lostpointercapture" :cancel})


(def profile-thresholds
  {:motion/slop 18.0,
   :tap/max-duration-us 300000,
   :multi-tap/max-delay-us 300000,
   :multi-tap/slop 100.0,
   :long-press/delay-us 500000,
   :swipe/min-distance 48.0,
   :swipe/max-duration-us 500000,
   :swipe/min-velocity 500.0,
   :fling/min-velocity 50.0,
   :fling/max-velocity 8000.0,
   :velocity/window-us 100000,
   :edge/width 20.0,
   :pressure/start-threshold 0.5,
   :pressure/release-threshold 0.5})


(defn input-profile
  [{:keys [generation-id profile-id capabilities]}]
  {:message/kind :dao.terminal/input-profile,
   :generation-id generation-id,
   :profile-id profile-id,
   :capabilities (set capabilities),
   :thresholds profile-thresholds})


(defn pointer-runtime-input
  [{:keys [runtime-seq runtime-time-us packet]}]
  {:runtime/seq runtime-seq,
   :runtime/time-us runtime-time-us,
   :runtime/source :pointer,
   :runtime/value packet})


(defn keyboard-runtime-input
  [{:keys [runtime-seq runtime-time-us packet]}]
  {:runtime/seq runtime-seq,
   :runtime/time-us runtime-time-us,
   :runtime/source :keyboard,
   :runtime/value packet})


(defn keyboard-code
  [code]
  (if (keyword? code)
    code
    (-> (str code)
        (string/replace #"([a-z0-9])([A-Z])" "$1-$2")
        string/lower-case
        keyword)))


(defn presented-geometry
  [generation-id frame-id coordinate-space-id width height]
  {:message/kind :dao.terminal/presented-geometry,
   :generation-id generation-id,
   :frame-id frame-id,
   :coordinate-space-id coordinate-space-id,
   :nodes [{:node-id ::artifact-surface,
            :interaction/path
            [{:node-id ::artifact-surface,
              :recognizers [{:recognizer/id [::artifact-surface ::orbit],
                             :gesture/kind :pan,
                             :machine :dao.gui.event/pan,
                             :config {:contacts {:min 1, :max 2},
                                      :axis :free,
                                      :start-at :slop,
                                      :slop 18.0,
                                      :contact-loss :degrade,
                                      :join-after-accept true},
                             :arena {:priority 0,
                                     :mode :cooperative,
                                     :coexistence/group ::artifact-motion}}
                            {:recognizer/id [::artifact-surface ::zoom],
                             :gesture/kind :scale,
                             :machine :dao.gui.event/transform,
                             :config {:contacts {:min 2, :max 2},
                                      :scale-slop 0.02,
                                      :contact-loss :degrade},
                             :arena {:priority 0,
                                     :mode :cooperative,
                                     :coexistence/group ::artifact-motion}}
                            {:recognizer/id [::artifact-surface ::tap],
                             :gesture/kind :tap,
                             :machine :dao.gui.event/tap,
                             :config {:count 1,
                                      :max-duration-us 300000,
                                      :slop 18.0,
                                      :max-delay-us 300000,
                                      :multi-tap-slop 100.0,
                                      :contacts {:min 1, :max 1},
                                      :contact-loss :end},
                             :arena {:priority 0,
                                     :mode :exclusive,
                                     :coexistence/group nil}}],
              :touch-action :none}],
            :touch-action :none,
            :regions [{:bounds {:x 0.0, :y 0.0, :width width, :height height},
                       :paint-order 0}]}]})


(defn subscription-command
  [id event-kind]
  {:subscription/op :add,
   :subscription/id id,
   :subscriber/id ::artifact-controller,
   :node-id ::artifact-surface,
   :event-kind event-kind,
   :gesture/phases #{:recognized :start :update :end :cancel}})


(defn boot-values
  [{:keys [generation-id frame-id coordinate-space-id profile-id width height]}]
  [{:runtime/source :terminal,
    :runtime/value {:message/kind :dao.terminal/coordinate-space-change,
                    :generation-id generation-id,
                    :coordinate-space-id coordinate-space-id,
                    :viewport {:width width, :height height}},
    :runtime/time-us 0,
    :runtime/seq 0}
   {:runtime/source :geometry,
    :runtime/value (presented-geometry generation-id
                                       frame-id
                                       coordinate-space-id
                                       width
                                       height),
    :runtime/time-us 1,
    :runtime/seq 1}
   {:runtime/source :profile,
    :runtime/value (input-profile {:generation-id generation-id,
                                   :profile-id profile-id,
                                   :capabilities #{:touch :mouse :stylus
                                                   :keyboard}}),
    :runtime/time-us 2,
    :runtime/seq 2}
   {:runtime/source :subscription,
    :runtime/value (subscription-command "artifact-pan" :pan),
    :runtime/time-us 3,
    :runtime/seq 3}
   {:runtime/source :subscription,
    :runtime/value (subscription-command "artifact-scale" :scale),
    :runtime/time-us 4,
    :runtime/seq 4}
   {:runtime/source :subscription,
    :runtime/value (subscription-command "artifact-tap" :tap),
    :runtime/time-us 5,
    :runtime/seq 5}])


(defn advance-until-progress
  "Advances a binding for at most max-steps calls. Hosts use this bounded
   helper after draining outputs; a blocked input is returned to the host for
   retry on its next progress opportunity rather than spinning."
  [binding max-steps]
  (loop [binding binding
         n 0
         statuses []]
    (if (>= n max-steps)
      {:binding binding, :statuses statuses, :retry? true}
      (let [{next-binding :binding, status :status} (event/advance binding)
            statuses (conj statuses status)]
        (if (= :advanced status)
          (recur next-binding (inc n) statuses)
          {:binding next-binding,
           :statuses statuses,
           :retry? (= :blocked status)})))))


(defn- value-or
  [x fallback]
  (if (number? x) (double x) fallback))


(defn drag-gesture?
  [gesture]
  (contains? #{:pan :drag} (:gesture/kind gesture)))


(defn reduce-gesture
  [state gesture]
  (case (:gesture/kind gesture)
    :pan (let [{:keys [x y]} (or (get-in gesture [:payload :delta]) {})
               dx (value-or x 0.0)
               dy (value-or y 0.0)]
           (-> state
               (update :cam-rot-y + (* pan-sensitivity dx))
               (update :cam-rot-x
                       #(max -1.4835
                             (min 1.4835 (+ % (* pan-sensitivity dy)))))))
    :scale (let [delta (value-or (get-in gesture [:payload :scale-delta]) 1.0)]
             (update state
                     :zoom
                     #(max scene/min-zoom (min scene/max-zoom (* % delta)))))
    :tap (assoc state :pulse 1.0)
    state))


(def keyboard-zoom-in-factor 0.9)
(def keyboard-zoom-out-factor 1.1)
(def keyboard-rotation-step 0.08)


(defn- keyboard-zoom-factor
  [key]
  (case (:logical key)
    "+" keyboard-zoom-in-factor
    "-" keyboard-zoom-out-factor
    (case (:code key)
      :numpad-add keyboard-zoom-in-factor
      :numpad-subtract keyboard-zoom-out-factor
      nil)))


(defn- keyboard-rotation-delta
  [key]
  (case (:code key)
    :key-w {:pitch (- keyboard-rotation-step)}
    :key-s {:pitch keyboard-rotation-step}
    :key-a {:yaw (- keyboard-rotation-step)}
    :key-d {:yaw keyboard-rotation-step}
    nil))


(defn reduce-keyboard
  [state keyboard-event]
  (if (= :down (:event/phase keyboard-event))
    (if-let [factor (keyboard-zoom-factor (:key keyboard-event))]
      (update state
              :zoom
              #(max scene/min-zoom (min scene/max-zoom (* % factor))))
      (if-let [{:keys [pitch yaw]} (keyboard-rotation-delta (:key
                                                              keyboard-event))]
        (-> state
            (update :cam-rot-x
                    #(max -1.4835 (min 1.4835 (+ % (double (or pitch 0.0))))))
            (update :cam-rot-y #(+ % (double (or yaw 0.0)))))
        state))
    state))


(defn tick-state
  [state]
  (-> state
      (update :pulse #(* (double (or % 0.0)) scene/pulse-decay-factor))
      (update :phase #(+ (double (or % 0.0)) scene/phase-step))))

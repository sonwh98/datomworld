;; Shared constructors for dao.gui.event tests. Every value mirrors the
;; canonical shapes in docs/design/dao.gui.event.md so tests read like the
;; specification's examples.
(ns dao.gui.event.util)

(def generation "c18496e9-1a16-4b1d-9028-e35ba0dc7af8")
(def space-id 7)
(def frame-id 42)
(def profile-id 3)
(def t0 812000000)


(defn rt
  "Canonical runtime input envelope."
  [seq time-us source value]
  {:runtime/seq seq,
   :runtime/time-us time-us,
   :runtime/source source,
   :runtime/value value})


(defn tap-decl
  "Canonical single tap recognizer declaration."
  ([node-id] (tap-decl node-id [node-id :tap]))
  ([_node-id recognizer-id]
   {:recognizer/id recognizer-id,
    :gesture/kind :tap,
    :machine :dao.gui.event/tap,
    :config {:count 1,
             :contacts {:min 1, :max 1},
             :join-after-accept false,
             :contact-loss :end},
    :arena {:priority 0, :mode :exclusive, :coexistence/group nil}}))


(defn pan-decl
  ([node-id] (pan-decl node-id [node-id :pan]))
  ([_node-id recognizer-id]
   {:recognizer/id recognizer-id,
    :gesture/kind :pan,
    :machine :dao.gui.event/pan,
    :config {:contacts {:min 1, :max 1},
             :axis :free,
             :start-at :slop,
             :contact-loss :end},
    :arena {:priority 0, :mode :exclusive, :coexistence/group nil}}))


(defn transform-decl
  ([node-id] (transform-decl node-id [node-id :transform]))
  ([_node-id recognizer-id]
   {:recognizer/id recognizer-id,
    :gesture/kind :transform,
    :machine :dao.gui.event/transform,
    :config {:contacts {:min 1, :max 5},
             :translation-slop nil,
             :scale-slop nil,
             :rotation-slop nil,
             :contact-loss :degrade},
    :arena {:priority 0, :mode :exclusive, :coexistence/group nil}}))


(defn presented-geometry
  "Presented geometry for one node with one rectangular region."
  [{:keys [generation-id frame-id coordinate-space-id node-id recognizers
           touch-action x y width height paint-order],
    :or {generation-id generation,
         frame-id frame-id,
         coordinate-space-id space-id,
         node-id ::save,
         recognizers nil,
         touch-action :manipulation,
         x 24,
         y 16,
         width 96,
         height 32,
         paint-order 100}}]
  (let [recognizers (or recognizers [(tap-decl node-id)])]
    {:message/kind :dao.terminal/presented-geometry,
     :generation-id generation-id,
     :frame-id frame-id,
     :coordinate-space-id coordinate-space-id,
     :nodes [{:node-id node-id,
              :interaction/path [{:node-id node-id,
                                  :recognizers recognizers,
                                  :touch-action touch-action}],
              :touch-action touch-action,
              :regions [{:bounds {:x x, :y y, :width width, :height height},
                         :paint-order paint-order}]}]}))


(def fallback-thresholds
  "The normative fallback profile from the specification."
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
  [{:keys [generation-id profile-id capabilities thresholds],
    :or {generation-id generation, profile-id profile-id, capabilities #{}},
    :as _opts}]
  {:message/kind :dao.terminal/input-profile,
   :generation-id generation-id,
   :profile-id profile-id,
   :capabilities (set capabilities),
   :thresholds (merge fallback-thresholds thresholds)})


(defn coordinate-space-change
  [{:keys [generation-id old-coordinate-space-id coordinate-space-id width
           height reason],
    :or {generation-id generation,
         old-coordinate-space-id nil,
         coordinate-space-id 8,
         width 844.0,
         height 390.0,
         reason :orientation-change}}]
  {:message/kind :dao.terminal/coordinate-space-change,
   :generation-id generation-id,
   :old-coordinate-space-id old-coordinate-space-id,
   :coordinate-space-id coordinate-space-id,
   :viewport {:width width, :height height},
   :reason reason})


(defn pointer-packet
  "Normalized pointer packet. opts: :generation-id :frame-id
  :coordinate-space-id :profile-id :input-seq :primary? :buttons :type
  :pressure :samples (extra coalesced samples before the actual one)."
  [{:keys [phase id x y time-us generation-id frame-id coordinate-space-id
           profile-id input-seq type primary? buttons pressure samples],
    :or {generation-id generation,
         frame-id frame-id,
         coordinate-space-id space-id,
         profile-id profile-id,
         input-seq 100,
         type :touch,
         primary? true,
         time-us t0}}]
  (let [buttons (if (some? buttons)
                  buttons
                  (case phase
                    (:down :move) 1
                    (:up :cancel) 0
                    0))
        actual {:time-us time-us, :position {:x x, :y y}, :sample/kind :actual}
        sample (cond-> actual (some? pressure) (assoc :pressure pressure))]
    {:input/kind :pointer,
     :generation-id generation-id,
     :frame-id frame-id,
     :coordinate-space-id coordinate-space-id,
     :profile-id profile-id,
     :input-seq input-seq,
     :pointer {:id id,
               :type type,
               :primary? (boolean primary?),
               :buttons buttons,
               :modifiers #{}},
     :phase phase,
     :samples (into (vec samples) [sample])}))


(defn sample
  [time-us x y & {:keys [kind pressure]}]
  (cond-> {:time-us time-us,
           :position {:x x, :y y},
           :sample/kind (or kind :coalesced)}
    (some? pressure) (assoc :pressure pressure)))


(defn sub-add
  ([id node-id] (sub-add id node-id :tap))
  ([id node-id event-kind]
   {:subscription/op :add,
    :subscription/id id,
    :subscriber/id ::project-controller,
    :node-id node-id,
    :event-kind event-kind}))


(defn sub-remove
  [id]
  {:subscription/op :remove, :subscription/id id})


(defn teardown
  ([] (teardown :binding-closed))
  ([reason] {:input/kind :dao.gui.event/teardown, :reason reason}))


(defn timer-fired
  [{:keys [generation-id coordinate-space-id arena-id recognizer-id timer-id
           timer-seq time-us recognizer/id],
    :or {generation-id generation,
         coordinate-space-id space-id,
         timer-id :long-press,
         timer-seq 1,
         time-us t0}}]
  {:input/kind :dao.gui.event/timer-fired,
   :generation-id generation-id,
   :coordinate-space-id coordinate-space-id,
   :arena-id arena-id,
   :recognizer/id (or recognizer-id id),
   :timer-id timer-id,
   :timer-seq timer-seq,
   :time-us time-us})

;; Standard recognizer machines as total normative transition algorithms.
;; Each machine is a pure function of (machine-input, frozen context,
;; machine-local state, config) returning a new state, timer effects,
;; proposed emissions, and a proposed arena decision. The runtime owns
;; windows, timers, ranking, and arena resolution. Specification:
;; docs/design/dao.gui.event.md sections Standard Recognizers and
;; Recognizer Machine Data.
(ns dao.gui.event.recognizer
  (:require #?(:cljd ["dart:math" :as math])
            [dao.gui.event.geom :as geom]
            [dao.gui.event.machine :as machine]))


(defn setting
  "Declaration override first, snapshotted profile second. ::fault marks a
  missing required value, which faults the candidate."
  [ctx config-key profile-key]
  (let [override (get-in ctx [:config config-key])]
    (if (some? override)
      override
      (let [profile-value (get-in ctx [:profile :thresholds profile-key])]
        (if (some? profile-value) profile-value ::fault)))))


(defn hold
  [state]
  {:state state, :effects [], :emissions [], :decision :hold})


(defn reject
  [state]
  {:state (assoc state :machine/state :rejected),
   :effects [],
   :emissions [],
   :decision :reject})


(defn fault
  [state]
  {:state (assoc state :machine/state :rejected),
   :effects [],
   :emissions [],
   :decision :reject,
   :fault? true})


;; ---------------------------------------------------------------------------
;; Tap and repeated tap
;; ---------------------------------------------------------------------------

(def tap-initial-state
  {:machine/state :awaiting-down,
   :contacts-reached? false,
   :completed-count 0,
   :current-pointer-ids #{},
   :current-down-time-us nil,
   :current-origin-positions {},
   :current-positions {},
   :first-tap-centroid nil,
   :last-tap-centroid nil,
   :last-up-time-us nil,
   :sequence-start-time-us nil})


(defn- tap-required-contacts
  [config]
  (get-in config [:contacts :max] 1))


(defn- tap-slop-exceeded?
  "Motion slop against this tap's recorded down position, and multi-tap
  slop against the first tap centroid, checked on every sample."
  [state packet slop multi-slop]
  (let [pid (get-in packet [:pointer :id])]
    (boolean (some (fn [sample]
                     (let [origin (get (:current-origin-positions state) pid)
                           first-centroid (:first-tap-centroid state)]
                       (or (and (some? origin)
                                (> (geom/distance (:position sample) origin)
                                   (double slop)))
                           (and (some? first-centroid)
                                (> (geom/distance (:position sample)
                                                  first-centroid)
                                   (double multi-slop))))))
                   (:samples packet)))))


(defn- positions-centroid
  [positions]
  (geom/centroid (into {} (map (fn [[id p]] [id {:position p}]) positions))))


(defn step-tap
  [ctx machine-input state config]
  (let [packet (:packet ctx)
        pid (get-in packet [:pointer :id])
        time-us (:time-us ctx)
        count-config (get config :count 1)
        required (tap-required-contacts config)
        slop (setting ctx :slop :motion/slop)
        max-duration (setting ctx :max-duration-us :tap/max-duration-us)
        max-delay (setting ctx :max-delay-us :multi-tap/max-delay-us)
        multi-slop (setting ctx :multi-tap-slop :multi-tap/slop)]
    (if (some #{::fault} [slop max-duration max-delay multi-slop])
      (fault state)
      (case (:machine/state state)
        (:ended :rejected) (hold state)
        (case (:machine/input machine-input)
          :pointer/down
          (let [sample-pos (get-in ctx [:sample :position])
                contacts-count (count (:contacts ctx))]
            (cond
              (> contacts-count required) (reject state)
              (= :between-taps (:machine/state state))
              (let [deadline (+ (:last-up-time-us state) (long max-delay))]
                (cond (> time-us deadline) (reject state)
                      (and (some? (:first-tap-centroid state))
                           (> (geom/distance sample-pos
                                             (:first-tap-centroid state))
                              (double multi-slop)))
                      (reject state)
                      :else {:state (assoc state
                                           :machine/state :contacts-down
                                           :current-pointer-ids #{pid}
                                           :current-down-time-us time-us
                                           :current-origin-positions
                                           {pid sample-pos}
                                           :current-positions {pid sample-pos}),
                             :effects [[:timer/cancel :next-tap]],
                             :emissions [],
                             :decision :hold}))
              :else
              ;; awaiting-down or a further admitted contact
              (let [first? (= :awaiting-down (:machine/state state))]
                (if (and (not first?)
                         (contains? (:current-pointer-ids state) pid))
                  (hold state)
                  {:state
                   (assoc state
                          :machine/state :contacts-down
                          :contacts-reached?
                          (>= (count (conj (:current-pointer-ids state) pid))
                              required)
                          :current-pointer-ids
                          (conj (:current-pointer-ids state) pid)
                          :current-down-time-us
                          (if first? time-us (:current-down-time-us state))
                          :sequence-start-time-us
                          (or (:sequence-start-time-us state) time-us)
                          :current-origin-positions
                          (assoc (:current-origin-positions state)
                                 pid sample-pos)
                          :current-positions (assoc (:current-positions state)
                                                    pid sample-pos)),
                   :effects [],
                   :emissions [],
                   :decision :hold}))))
          :pointer/move
          (if (= :contacts-down (:machine/state state))
            (if (tap-slop-exceeded? state packet slop multi-slop)
              (reject state)
              (hold (assoc state
                           :current-positions
                           (if (contains? (:current-pointer-ids state) pid)
                             (assoc (:current-positions state)
                                    pid (get-in ctx [:sample :position]))
                             (:current-positions state)))))
            (hold state))
          :pointer/up
          (if (= :contacts-down (:machine/state state))
            (let [in-tap? (contains? (:current-pointer-ids state) pid)]
              (if-not in-tap?
                (hold state)
                (let [positions (assoc (:current-positions state)
                                       pid (get-in ctx [:sample :position]))
                      remaining (disj (:current-pointer-ids state) pid)]
                  ;; reject only when the required minimum contact count
                  ;; was never reached
                  (if-not (:contacts-reached? state)
                    (reject state)
                    (if (pos? (count remaining))
                      (hold (assoc state
                                   :current-pointer-ids remaining
                                   :current-positions positions))
                      (let [tap-duration (- time-us
                                            (or (:current-down-time-us state)
                                                time-us))
                            ;; the tap centroid is over every required
                            ;; contact's final up position
                            tap-centroid (positions-centroid positions)]
                        (cond
                          (> tap-duration (long max-duration)) (reject state)
                          (and (some? (:first-tap-centroid state))
                               (> (geom/distance tap-centroid
                                                 (:first-tap-centroid state))
                                  (double multi-slop)))
                          (reject state)
                          :else
                          (let [completed (inc (:completed-count state))]
                            (if (= completed count-config)
                              {:state (assoc state
                                             :machine/state :ended
                                             :completed-count completed
                                             :last-tap-centroid tap-centroid
                                             :last-up-time-us time-us
                                             :current-pointer-ids #{}
                                             :current-origin-positions {}
                                             :current-positions {}),
                               :effects [[:timer/cancel :next-tap]],
                               :emissions
                               [{:phase :recognized,
                                 :payload {:count count-config,
                                           :contacts required,
                                           :duration-us
                                           (- time-us
                                              (or
                                                (:sequence-start-time-us
                                                  state)
                                                time-us))},
                                 :position tap-centroid}],
                               :decision :accept}
                              {:state (assoc state
                                             :machine/state :between-taps
                                             :completed-count completed
                                             :first-tap-centroid
                                             (or (:first-tap-centroid state)
                                                 tap-centroid)
                                             :last-tap-centroid tap-centroid
                                             :last-up-time-us time-us
                                             :current-pointer-ids #{}
                                             :current-origin-positions {}
                                             :current-positions {}),
                               :effects [[:timer/start :next-tap
                                          (long max-delay)]],
                               :emissions [],
                               :decision :hold})))))))))
            (hold state))
          :pointer/cancel (if (= :between-taps (:machine/state state))
                            {:state (assoc state :machine/state :rejected),
                             :effects [[:timer/cancel :next-tap]],
                             :emissions [],
                             :decision :reject}
                            (reject state))
          :timer/fired (if (and (= :between-taps (:machine/state state))
                                (= :next-tap (:timer/id machine-input)))
                         (reject state)
                         (hold state))
          :contacts/changed (if (> (count (:contacts ctx)) required)
                              (reject state)
                              (hold state))
          ;; arena lifecycle inputs leave the tap state unchanged
          (hold state))))))


(defn project-tap
  [_ctx state config]
  {:tap/count (get config :count 1),
   :tap/completed-count (:completed-count state),
   :tap/first-centroid (:first-tap-centroid state),
   :tap/last-up-time-us (:last-up-time-us state)})


;; ---------------------------------------------------------------------------
;; Long press
;; ---------------------------------------------------------------------------

(def long-press-initial-state
  {:machine/state :possible,
   :origin-centroid nil,
   :down-time-us nil,
   :last-centroid nil})


(defn- contact-range
  [config]
  [(get-in config [:contacts :min] 1) (get-in config [:contacts :max] 1)])


(defn- payload-translation
  [origin centroid last-centroid down-time time-us]
  {:translation {:x (- (double (:x centroid)) (double (:x origin))),
                 :y (- (double (:y centroid)) (double (:y origin)))},
   :delta {:x (- (double (:x centroid)) (double (:x last-centroid))),
           :y (- (double (:y centroid)) (double (:y last-centroid)))},
   :duration-us (- time-us (or down-time time-us))})


(defn step-long-press
  [ctx machine-input state config]
  (let [time-us (:time-us ctx)
        [contact-min contact-max] (contact-range config)
        delay (setting ctx :delay-us :long-press/delay-us)
        slop (setting ctx :slop :motion/slop)
        centroid (geom/centroid (:contacts ctx))]
    (if (some #{::fault} [delay slop])
      (fault state)
      (case (:machine/state state)
        (:ended :rejected) (hold state)
        :active
        (case (:machine/input machine-input)
          :pointer/move (let [origin (:origin-centroid state)
                              last (:last-centroid state)]
                          (if (or (nil? origin) (nil? last))
                            (hold state)
                            {:state (assoc state :last-centroid centroid),
                             :effects [],
                             :emissions [{:phase :update,
                                          :payload (payload-translation
                                                     origin
                                                     centroid
                                                     last
                                                     (:down-time-us state)
                                                     time-us),
                                          :position centroid}],
                             :decision :hold}))
          :pointer/up {:state (assoc state :machine/state :ended),
                       :effects [],
                       :emissions [{:phase :end,
                                    :payload (payload-translation
                                               (:origin-centroid state)
                                               centroid
                                               (or (:last-centroid state)
                                                   (:origin-centroid state))
                                               (:down-time-us state)
                                               time-us),
                                    :position centroid}],
                       :decision :hold}
          :pointer/cancel
          (let [last-payload
                (payload-translation
                  (:origin-centroid state)
                  (or (:last-centroid state) (:origin-centroid state))
                  (or (:last-centroid state) (:origin-centroid state))
                  (:down-time-us state)
                  time-us)]
            {:state (assoc state :machine/state :ended),
             :effects [],
             :emissions [{:phase :cancel,
                          :payload (assoc last-payload
                                          :reason :pointer-cancel),
                          :position (or (:last-centroid state)
                                        (:origin-centroid state))}],
             :decision :hold})
          :contacts/changed
          (let [n (count (:contacts ctx))]
            (if (or (< n contact-min) (> n contact-max))
              (let [last-payload
                    (payload-translation
                      (:origin-centroid state)
                      (or (:last-centroid state) (:origin-centroid state))
                      (or (:last-centroid state) (:origin-centroid state))
                      (:down-time-us state)
                      time-us)]
                {:state (assoc state :machine/state :ended),
                 :effects [],
                 :emissions [{:phase :cancel,
                              :payload (assoc last-payload
                                              :reason :pointer-cancel),
                              :position (or (:last-centroid state)
                                            (:origin-centroid state))}],
                 :decision :hold})
              (hold state)))
          (hold state))
        ;; :possible
        (case (:machine/input machine-input)
          :pointer/down
          (let [n (count (:contacts ctx))]
            (cond (> n contact-max) (reject state)
                  ;; the timer starts on the final required down: the
                  ;; minimum
                  (and (nil? (:down-time-us state)) (>= n contact-min))
                  {:state (assoc state
                                 :origin-centroid centroid
                                 :last-centroid centroid
                                 :down-time-us time-us),
                   :effects [[:timer/start :long-press (long delay)]],
                   :emissions [],
                   :decision :hold}
                  :else (hold state)))
          :pointer/move (let [origin (:origin-centroid state)]
                          (if (and (some? origin)
                                   (> (geom/distance centroid origin)
                                      (double slop)))
                            {:state (assoc state :machine/state :rejected),
                             :effects [[:timer/cancel :long-press]],
                             :emissions [],
                             :decision :reject}
                            (hold state)))
          :pointer/up {:state (assoc state :machine/state :rejected),
                       :effects [[:timer/cancel :long-press]],
                       :emissions [],
                       :decision :reject}
          :pointer/cancel {:state (assoc state :machine/state :rejected),
                           :effects [[:timer/cancel :long-press]],
                           :emissions [],
                           :decision :reject}
          :timer/fired (if (= :long-press (:timer/id machine-input))
                         {:state (assoc state :machine/state :active),
                          :effects [],
                          :emissions [{:phase :start,
                                       :payload {:translation {:x 0.0, :y 0.0},
                                                 :delta {:x 0.0, :y 0.0},
                                                 :duration-us (long delay)},
                                       :position (:origin-centroid state)}],
                          :decision :accept}
                         (hold state))
          :contacts/changed (let [n (count (:contacts ctx))]
                              (if (or (< n contact-min) (> n contact-max))
                                {:state (assoc state :machine/state :rejected),
                                 :effects [[:timer/cancel :long-press]],
                                 :emissions [],
                                 :decision :reject}
                                (hold state)))
          (hold state))))))


;; ---------------------------------------------------------------------------
;; Pan / drag
;; ---------------------------------------------------------------------------

(def pan-initial-state
  {:machine/state :possible,
   :origin-centroid nil,
   :accept-centroid nil,
   :last-centroid nil,
   :down-time-us nil,
   :paused? false})


(defn- velocity-of
  [ctx]
  (geom/window-velocity (:window ctx)))


(defn- axis-zero
  "Zero the orthogonal components of an axis pan."
  [axis v]
  (case axis
    :x (assoc v :y 0.0)
    :y (assoc v :x 0.0)
    v))


(defn- pan-payload
  [axis accept-centroid last-centroid centroid ctx]
  {:translation
   (axis-zero axis
              {:x (- (double (:x centroid)) (double (:x accept-centroid))),
               :y (- (double (:y centroid)) (double (:y accept-centroid)))}),
   :delta (axis-zero axis
                     {:x (- (double (:x centroid)) (double (:x last-centroid))),
                      :y (- (double (:y centroid))
                            (double (:y last-centroid)))}),
   :velocity (axis-zero axis (velocity-of ctx))})


(defn- pan-axis-qualified?
  [axis displacement slop]
  (let [dx (abs (:x displacement))
        dy (abs (:y displacement))]
    (case axis
      :x (and (> dx slop) (<= dy slop))
      :y (and (> dy slop) (<= dx slop))
      (or (> dx slop) (> dy slop)))))


(defn step-pan
  [ctx machine-input state config]
  (let [time-us (:time-us ctx)
        [contact-min contact-max] (contact-range config)
        axis (get config :axis :free)
        start-at (get config :start-at :slop)
        slop (setting ctx :slop :motion/slop)
        centroid (geom/centroid (:contacts ctx))]
    (if (some #{::fault} [slop])
      (fault state)
      (case (:machine/state state)
        (:ended :rejected) (hold state)
        :panning
        (if (:paused? state)
          (case (:machine/input machine-input)
            :contacts/changed (let [n (count (:contacts ctx))]
                                (if (>= n contact-min)
                                  {:state (assoc state
                                                 :paused? false
                                                 :last-centroid centroid
                                                 :accept-centroid centroid),
                                   :effects [],
                                   :emissions [],
                                   :decision :hold}
                                  (hold state)))
            (hold state))
          (case (:machine/input machine-input)
            :pointer/move (if (or (nil? (:accept-centroid state))
                                  (nil? (:last-centroid state)))
                            (hold state)
                            {:state (assoc state :last-centroid centroid),
                             :effects [],
                             :emissions [{:phase :update,
                                          :payload (pan-payload
                                                     axis
                                                     (:accept-centroid state)
                                                     (:last-centroid state)
                                                     centroid
                                                     ctx),
                                          :position centroid}],
                             :decision :hold})
            :pointer/up (let [remaining (dec (count (:contacts ctx)))]
                          (if (< remaining contact-min)
                            {:state (assoc state :machine/state :ended),
                             :effects [],
                             :emissions [{:phase :end,
                                          :payload (pan-payload
                                                     axis
                                                     (:accept-centroid state)
                                                     (:last-centroid state)
                                                     centroid
                                                     ctx),
                                          :position centroid}],
                             :decision :hold}
                            (hold (assoc state :last-centroid centroid))))
            :pointer/cancel
            {:state (assoc state :machine/state :ended),
             :effects [],
             :emissions [{:phase :cancel,
                          :payload (assoc (pan-payload
                                            axis
                                            (:accept-centroid state)
                                            (:last-centroid state)
                                            (or (:last-centroid state)
                                                centroid)
                                            ctx)
                                          :reason :pointer-cancel),
                          :position (or (:last-centroid state) centroid)}],
             :decision :hold}
            :contacts/changed
            (let [n (count (:contacts ctx))]
              (cond (< n contact-min)
                    (case (get config :contact-loss :end)
                      :hold {:state (assoc state :paused? true),
                             :effects [],
                             :emissions [],
                             :decision :hold}
                      ;; :end and :degrade both end below the minimum
                      {:state (assoc state :machine/state :ended),
                       :effects [],
                       :emissions [{:phase :end,
                                    :payload (pan-payload
                                               axis
                                               (:accept-centroid state)
                                               (:last-centroid state)
                                               (:last-centroid state)
                                               ctx),
                                    :position (or (:last-centroid state)
                                                  centroid)}],
                       :decision :hold})
                    (> n contact-max) (hold state)
                    ;; within range: rebase the reference so cumulative
                    ;; values stay continuous across the contact change
                    :else (let [last (:last-centroid state)
                                jump {:x (- (double (:x centroid))
                                            (double (:x last))),
                                      :y (- (double (:y centroid))
                                            (double (:y last)))}]
                            {:state (assoc state
                                           :last-centroid centroid
                                           :accept-centroid
                                           {:x (+ (double (:x (:accept-centroid
                                                                state)))
                                                  (double (:x jump))),
                                            :y (+ (double (:y (:accept-centroid
                                                                state)))
                                                  (double (:y jump)))}),
                             :effects [],
                             :emissions [],
                             :decision :hold})))
            (hold state)))
        ;; :possible
        (case (:machine/input machine-input)
          :pointer/down (let [n (count (:contacts ctx))]
                          (cond (> n contact-max) (reject state)
                                (< n contact-min) (hold state)
                                :else
                                ;; the first in-range down records the
                                ;; reference centroid
                                (if (some? (:origin-centroid state))
                                  (hold state)
                                  {:state (assoc state
                                                 :origin-centroid centroid
                                                 :last-centroid centroid
                                                 :down-time-us time-us),
                                   :effects [],
                                   :emissions [],
                                   :decision :hold})))
          :pointer/move
          (let [origin (:origin-centroid state)]
            (if (nil? origin)
              (hold state)
              (let [displacement
                    {:x (- (double (:x centroid)) (double (:x origin))),
                     :y (- (double (:y centroid)) (double (:y origin)))}]
                (if (pan-axis-qualified? axis displacement (double slop))
                  (let [accept-anchor (if (= :down start-at) origin centroid)]
                    {:state (assoc state
                                   :machine/state :panning
                                   :accept-centroid accept-anchor
                                   :last-centroid centroid),
                     :effects [],
                     :emissions
                     [{:phase :start,
                       :payload
                       {:translation (axis-zero axis
                                                (if (= :down start-at)
                                                  displacement
                                                  {:x 0.0, :y 0.0})),
                        :delta (axis-zero axis
                                          (if (= :down start-at)
                                            displacement
                                            {:x 0.0, :y 0.0})),
                        :velocity (axis-zero axis (velocity-of ctx))},
                       :position centroid}],
                     :decision :accept})
                  (hold state)))))
          :pointer/up (reject state)
          :pointer/cancel (reject state)
          :contacts/changed
          (let [n (count (:contacts ctx))]
            (if (or (< n contact-min) (> n contact-max))
              (reject state)
              ;; shift the origin by the centroid jump so accumulated
              ;; pre-acceptance displacement stays continuous
              (let [last-c (:last-centroid state)
                    jump
                    (if (some? last-c)
                      {:x (- (double (:x centroid)) (double (:x last-c))),
                       :y (- (double (:y centroid)) (double (:y last-c)))}
                      {:x 0.0, :y 0.0})
                    origin (:origin-centroid state)]
                (hold (assoc state
                             :origin-centroid
                             (if (some? origin)
                               {:x (+ (double (:x origin)) (double (:x jump))),
                                :y (+ (double (:y origin)) (double (:y jump)))}
                               centroid)
                             :last-centroid centroid)))))
          (hold state))))))


;; ---------------------------------------------------------------------------
;; Swipe and fling
;; ---------------------------------------------------------------------------

(def swipe-initial-state
  {:machine/state :possible, :origin-centroid nil, :down-time-us nil})


(defn- displacement-vector
  [origin centroid]
  {:x (- (double (:x centroid)) (double (:x origin))),
   :y (- (double (:y centroid)) (double (:y origin)))})


(defn- speed-of
  [v]
  (geom/sqrt (+ (* (double (:x v)) (double (:x v)))
                (* (double (:y v)) (double (:y v))))))


(defn- final-required-up?
  [ctx config]
  (let [contact-min (get-in config [:contacts :min] 1)]
    ;; the contact set still includes the lifting pointer
    (= (count (:contacts ctx)) contact-min)))


(defn step-swipe
  [ctx machine-input state config]
  (let [time-us (:time-us ctx)
        direction-config (get config :direction :any)
        min-distance (setting ctx :min-distance :swipe/min-distance)
        max-duration (setting ctx :max-duration-us :swipe/max-duration-us)
        min-velocity (setting ctx :min-velocity :swipe/min-velocity)]
    (if (some #{::fault} [min-distance max-duration min-velocity])
      (fault state)
      (case (:machine/state state)
        (:ended :rejected) (hold state)
        (case (:machine/input machine-input)
          :pointer/down (let [n (count (:contacts ctx))
                              contact-max (get-in config [:contacts :max] 1)]
                          (cond (> n contact-max) (reject state)
                                (= n contact-max)
                                (let [centroid (geom/centroid (:contacts
                                                                ctx))]
                                  {:state (assoc state
                                                 :origin-centroid centroid
                                                 :down-time-us time-us),
                                   :effects [],
                                   :emissions [],
                                   :decision :hold})
                                :else (hold state)))
          :pointer/move (hold state)
          :pointer/up
          (if (final-required-up? ctx config)
            (let [centroid (geom/centroid (:contacts ctx))
                  origin (:origin-centroid state)]
              (if (nil? origin)
                (reject state)
                (let [displacement (displacement-vector origin centroid)
                      distance (speed-of displacement)
                      duration (- time-us (or (:down-time-us state) time-us))
                      velocity (velocity-of ctx)
                      speed (speed-of velocity)
                      direction (geom/direction displacement :free)]
                  (if (and (>= distance (double min-distance))
                           (<= duration (long max-duration))
                           (>= speed (double min-velocity))
                           (or (= :any direction-config)
                               (= direction-config direction)))
                    {:state (assoc state :machine/state :ended),
                     :effects [],
                     :emissions [{:phase :recognized,
                                  :payload {:direction direction,
                                            :displacement displacement,
                                            :distance distance,
                                            :duration-us duration,
                                            :velocity velocity},
                                  :position centroid}],
                     :decision :accept}
                    (reject state)))))
            (hold state))
          :pointer/cancel (reject state)
          :contacts/changed
          (let [n (count (:contacts ctx))
                [lo hi] (contact-range config)]
            (if (or (< n lo) (> n hi)) (reject state) (hold state)))
          (hold state))))))


(defn step-fling
  [ctx machine-input state config]
  (let [direction-config (get config :direction :any)
        min-velocity (setting ctx :min-velocity :fling/min-velocity)
        max-velocity (setting ctx :max-velocity :fling/max-velocity)]
    (if (some #{::fault} [min-velocity max-velocity])
      (fault state)
      (case (:machine/state state)
        (:ended :rejected) (hold state)
        (case (:machine/input machine-input)
          :pointer/up (if (final-required-up? ctx config)
                        (let [centroid (geom/centroid (:contacts ctx))
                              velocity (velocity-of ctx)
                              speed (speed-of velocity)
                              direction (geom/direction velocity :free)]
                          (if (and (>= speed (double min-velocity))
                                   (<= speed (double max-velocity))
                                   (or (= :any direction-config)
                                       (= direction-config direction)))
                            {:state (assoc state :machine/state :ended),
                             :effects [],
                             :emissions [{:phase :recognized,
                                          :payload {:direction direction,
                                                    :velocity velocity,
                                                    :speed speed},
                                          :position centroid}],
                             :decision :accept}
                            (reject state)))
                        (hold state))
          :pointer/down (let [n (count (:contacts ctx))
                              contact-max (get-in config [:contacts :max] 1)]
                          (cond (> n contact-max) (reject state)
                                :else (hold state)))
          :pointer/cancel (reject state)
          :contacts/changed
          (let [n (count (:contacts ctx))
                [lo hi] (contact-range config)]
            (if (or (< n lo) (> n hi)) (reject state) (hold state)))
          (hold state))))))


;; ---------------------------------------------------------------------------
;; Transform (translation, scale, rotation)
;; ---------------------------------------------------------------------------

(def transform-initial-state
  {:machine/state :possible,
   :anchor-centroid nil,
   :baseline-centroid nil,
   :baseline-span nil,
   :baseline-angle nil,
   :cumulative-translation {:x 0.0, :y 0.0},
   :cumulative-scale 1.0,
   :cumulative-rotation 0.0})


(defn- norm-angle
  "Normalize a rotation delta to [-pi, pi)."
  [a]
  (cond (< a (- geom/pi)) (+ a (* 2 geom/pi))
        (>= a geom/pi) (- a (* 2 geom/pi))
        :else a))


(defn- transform-span-angle
  "Span and angle from the two lowest pointer ids."
  [contacts]
  (if (< (count contacts) 2)
    {:span 0.0, :angle 0.0, :two? false}
    (let [ids (geom/contact-ids contacts)
          lo (get-in contacts [(nth ids 0) :position])
          hi (get-in contacts [(nth ids 1) :position])]
      {:span (geom/distance lo hi),
       :angle (geom/atan2 (- (double (:y hi)) (double (:y lo)))
                          (- (double (:x hi)) (double (:x lo)))),
       :two? true})))


(defn- log-quantity
  [x]
  #?(:clj (Math/log x)
     :cljs (js/Math.log x)
     :cljd (math/log x)))


(defn- transform-accept-qualified?
  [config displacement ctx contacts state]
  (let [translation-slop (or (get config :translation-slop)
                             (get-in ctx [:profile :thresholds :motion/slop]))
        scale-slop (get config :scale-slop)
        rotation-slop (get config :rotation-slop)
        {:keys [span angle two?]} (transform-span-angle contacts)
        baseline-span (:baseline-span state 0.0)
        baseline-angle (:baseline-angle state 0.0)]
    (if (nil? translation-slop)
      ::fault
      (boolean (or (> (speed-of displacement) (double translation-slop))
                   (and (some? scale-slop)
                        two?
                        (pos? baseline-span)
                        (> (abs (log-quantity (/ span baseline-span)))
                           (double scale-slop)))
                   (and (some? rotation-slop)
                        two?
                        (> (abs (- angle baseline-angle))
                           (double rotation-slop))))))))


(defn step-transform
  [ctx machine-input state config]
  (let [centroid (geom/centroid (:contacts ctx))
        {:keys [span angle two?]} (transform-span-angle (:contacts ctx))]
    (case (:machine/state state)
      (:ended :rejected) (hold state)
      :active
      (case (:machine/input machine-input)
        :pointer/move
        (let [baseline-c (:baseline-centroid state)
              delta {:x (- (double (:x centroid)) (double (:x baseline-c))),
                     :y (- (double (:y centroid)) (double (:y baseline-c)))}
              scale-delta (if (and two? (pos? (:baseline-span state)))
                            (/ span (:baseline-span state))
                            1.0)
              rotation-delta
              (if two? (norm-angle (- angle (:baseline-angle state))) 0.0)
              new-translation
              {:x (+ (double (get-in state [:cumulative-translation :x]))
                     (double (:x delta))),
               :y (+ (double (get-in state [:cumulative-translation :y]))
                     (double (:y delta)))}
              new-scale (* (:cumulative-scale state) scale-delta)
              new-rotation (+ (:cumulative-rotation state) rotation-delta)]
          {:state (assoc state
                         :baseline-centroid centroid
                         :baseline-span (if two? span (:baseline-span state))
                         :baseline-angle (if two? angle (:baseline-angle state))
                         :cumulative-translation new-translation
                         :cumulative-scale new-scale
                         :cumulative-rotation new-rotation),
           :effects [],
           :emissions [{:phase :update,
                        :payload {:translation new-translation,
                                  :delta delta,
                                  :scale new-scale,
                                  :scale-delta scale-delta,
                                  :rotation new-rotation,
                                  :rotation-delta rotation-delta},
                        :position centroid}],
           :decision :hold})
        :pointer/up (let [remaining (dec (count (:contacts ctx)))
                          contact-min (get-in config [:contacts :min] 1)]
                      (if (< remaining contact-min)
                        {:state (assoc state :machine/state :ended),
                         :effects [],
                         :emissions
                         [{:phase :end,
                           :payload {:translation (:cumulative-translation
                                                    state),
                                     :delta {:x 0.0, :y 0.0},
                                     :scale (:cumulative-scale state),
                                     :scale-delta 1.0,
                                     :rotation (:cumulative-rotation state),
                                     :rotation-delta 0.0},
                           :position centroid}],
                         :decision :hold}
                        (hold state)))
        :pointer/cancel {:state (assoc state :machine/state :ended),
                         :effects [],
                         :emissions
                         [{:phase :cancel,
                           :payload {:translation (:cumulative-translation
                                                    state),
                                     :delta {:x 0.0, :y 0.0},
                                     :scale (:cumulative-scale state),
                                     :scale-delta 1.0,
                                     :rotation (:cumulative-rotation state),
                                     :rotation-delta 0.0,
                                     :reason :pointer-cancel},
                           :position centroid}],
                         :decision :hold}
        :contacts/changed
        (let [n (count (:contacts ctx))
              [lo hi] (contact-range config)]
          (cond
            (< n lo)
            {:state (assoc state :machine/state :ended),
             :effects [],
             :emissions
             [{:phase :end,
               :payload {:translation (:cumulative-translation state),
                         :delta {:x 0.0, :y 0.0},
                         :scale (:cumulative-scale state),
                         :scale-delta 1.0,
                         :rotation (:cumulative-rotation state),
                         :rotation-delta 0.0},
               :position (or (:baseline-centroid state) centroid)}],
             :decision :hold}
            (> n hi) (hold state)
            :else
            ;; rebase without discontinuity in cumulative values: the
            ;; accept anchor shifts by the same centroid jump so a
            ;; join cannot artificially breach the slop threshold
            (let [last-c (:baseline-centroid state)
                  anchor (:anchor-centroid state)
                  jump
                  (if (some? last-c)
                    {:x (- (double (:x centroid)) (double (:x last-c))),
                     :y (- (double (:y centroid)) (double (:y last-c)))}
                    {:x 0.0, :y 0.0})]
              (hold (assoc state
                           :baseline-centroid centroid
                           :anchor-centroid
                           (if (some? anchor)
                             {:x (+ (double (:x anchor)) (double (:x jump))),
                              :y (+ (double (:y anchor)) (double (:y jump)))}
                             centroid)
                           :baseline-span (if two? span (:baseline-span state))
                           :baseline-angle
                           (if two? angle (:baseline-angle state)))))))
        (hold state))
      ;; :possible
      (case (:machine/input machine-input)
        :pointer/down (let [n (count (:contacts ctx))
                            [lo hi] (contact-range config)]
                        (cond (> n hi) (reject state)
                              (< n lo) (hold state)
                              :else (if (some? (:anchor-centroid state))
                                      (hold state)
                                      (hold (assoc state
                                                   :anchor-centroid centroid
                                                   :baseline-centroid centroid
                                                   :baseline-span (if two? span 0.0)
                                                   :baseline-angle
                                                   (if two? angle 0.0))))))
        :pointer/move
        (let [anchor (:anchor-centroid state)]
          (if (nil? anchor)
            (hold state)
            (let [displacement
                  {:x (- (double (:x centroid)) (double (:x anchor))),
                   :y (- (double (:y centroid)) (double (:y anchor)))}
                  qualified? (transform-accept-qualified? config
                                                          displacement
                                                          ctx
                                                          (:contacts ctx)
                                                          state)]
              (cond (= ::fault qualified?) (fault state)
                    qualified?
                    {:state (assoc state
                                   :machine/state :active
                                   :cumulative-translation displacement
                                   :baseline-centroid centroid
                                   :baseline-span
                                   (if two? span (:baseline-span state))
                                   :baseline-angle
                                   (if two? angle (:baseline-angle state))),
                     :effects [],
                     :emissions [{:phase :start,
                                  :payload {:translation displacement,
                                            :delta displacement,
                                            :scale 1.0,
                                            :scale-delta 1.0,
                                            :rotation 0.0,
                                            :rotation-delta 0.0},
                                  :position centroid}],
                     :decision :accept}
                    :else (hold state)))))
        :pointer/up (reject state)
        :pointer/cancel (reject state)
        :contacts/changed
        (let [n (count (:contacts ctx))
              [lo hi] (contact-range config)]
          (if (or (< n lo) (> n hi))
            (reject state)
            ;; shift the anchor by the centroid jump: a joining contact
            ;; must not artificially breach the slop threshold
            (let [last-c (:baseline-centroid state)
                  anchor (:anchor-centroid state)
                  jump (if (some? last-c)
                         {:x (- (double (:x centroid)) (double (:x last-c))),
                          :y (- (double (:y centroid)) (double (:y last-c)))}
                         {:x 0.0, :y 0.0})]
              (hold (assoc state
                           :baseline-centroid centroid
                           :anchor-centroid
                           (if (some? anchor)
                             {:x (+ (double (:x anchor)) (double (:x jump))),
                              :y (+ (double (:y anchor)) (double (:y jump)))}
                             centroid)
                           :baseline-span (if two? span (:baseline-span state))
                           :baseline-angle
                           (if two? angle (:baseline-angle state)))))))
        (hold state)))))


;; ---------------------------------------------------------------------------
;; Edge pan
;; ---------------------------------------------------------------------------

(def edge-pan-initial-state
  {:machine/state :possible,
   :in-edge? false,
   :origin-centroid nil,
   :accept-centroid nil,
   :last-centroid nil,
   :down-time-us nil})


(defn- inward-displacement
  [edge origin centroid]
  (case edge
    :left (- (double (:x centroid)) (double (:x origin)))
    :right (- (double (:x origin)) (double (:x centroid)))
    :top (- (double (:y origin)) (double (:y centroid)))
    :bottom (- (double (:y centroid)) (double (:y origin)))))


(defn step-edge-pan
  [ctx machine-input state config]
  (let [edge (get config :edge :left)
        slop (setting ctx :slop :motion/slop)
        centroid (geom/centroid (:contacts ctx))
        sample-pos (get-in ctx [:sample :position])
        viewport (get-in ctx [:coordinate-space :viewport])]
    (if (some #{::fault} [slop])
      (fault state)
      (case (:machine/state state)
        (:ended :rejected) (hold state)
        :panning
        ;; lifecycle matches pan once accepted
        (case (:machine/input machine-input)
          :pointer/move (if (or (nil? (:accept-centroid state))
                                (nil? (:last-centroid state)))
                          (hold state)
                          {:state (assoc state :last-centroid centroid),
                           :effects [],
                           :emissions [{:phase :update,
                                        :payload
                                        (assoc (pan-payload
                                                 :free
                                                 (:accept-centroid state)
                                                 (:last-centroid state)
                                                 centroid
                                                 ctx)
                                               :edge edge),
                                        :position centroid}],
                           :decision :hold})
          :pointer/up (let [remaining (dec (count (:contacts ctx)))
                            contact-min (get-in config [:contacts :min] 1)]
                        (if (< remaining contact-min)
                          {:state (assoc state :machine/state :ended),
                           :effects [],
                           :emissions [{:phase :end,
                                        :payload
                                        (assoc (pan-payload
                                                 :free
                                                 (:accept-centroid state)
                                                 (:last-centroid state)
                                                 centroid
                                                 ctx)
                                               :edge edge),
                                        :position centroid}],
                           :decision :hold}
                          (hold state)))
          :pointer/cancel
          {:state (assoc state :machine/state :ended),
           :effects [],
           :emissions [{:phase :cancel,
                        :payload (assoc (pan-payload
                                          :free
                                          (:accept-centroid state)
                                          (:last-centroid state)
                                          (or (:last-centroid state)
                                              centroid)
                                          ctx)
                                        :edge edge
                                        :reason :pointer-cancel),
                        :position (or (:last-centroid state) centroid)}],
           :decision :hold}
          (hold state))
        ;; :possible
        (case (:machine/input machine-input)
          :pointer/down (let [edge-width (setting ctx nil :edge/width)]
                          (cond (= ::fault edge-width) (fault state)
                                (nil? viewport) (fault state)
                                (> (geom/edge-distance sample-pos edge viewport)
                                   (double edge-width))
                                ;; outside the edge strip: this
                                ;; recognizer can never start
                                (reject state)
                                :else (hold (assoc state
                                                   :in-edge? true
                                                   :origin-centroid centroid
                                                   :last-centroid centroid
                                                   :down-time-us (:time-us ctx)))))
          :pointer/move (if (:in-edge? state)
                          (let [origin (:origin-centroid state)]
                            (if (nil? origin)
                              (hold state)
                              (if (> (inward-displacement edge origin centroid)
                                     (double slop))
                                {:state (assoc state
                                               :machine/state :panning
                                               :accept-centroid centroid
                                               :last-centroid centroid),
                                 :effects [],
                                 :emissions [{:phase :start,
                                              :payload
                                              {:translation {:x 0.0, :y 0.0},
                                               :delta {:x 0.0, :y 0.0},
                                               :velocity (velocity-of ctx),
                                               :edge edge},
                                              :position centroid}],
                                 :decision :accept}
                                (hold state))))
                          (hold state))
          :pointer/up (reject state)
          :pointer/cancel (reject state)
          (hold state))))))


;; ---------------------------------------------------------------------------
;; Pressure press
;; ---------------------------------------------------------------------------

(def pressure-press-initial-state
  {:machine/state :possible, :last-pressure nil, :peak-emitted? false})


(defn step-pressure-press
  [ctx machine-input state config]
  (let [pressure (get-in ctx [:sample :pressure])
        start-threshold
        (or (get config :start-threshold)
            (get-in ctx [:profile :thresholds :pressure/start-threshold]))
        ;; release defaults to the start threshold when unconfigured
        release-threshold
        (or (get config :release-threshold)
            (get config :start-threshold)
            (get-in ctx [:profile :thresholds :pressure/release-threshold])
            start-threshold)
        peak-threshold (get config :peak-threshold)
        contact-range (contact-range config)
        n (count (:contacts ctx))]
    (if (or (nil? start-threshold) (nil? release-threshold))
      (fault state)
      (case (:machine/state state)
        (:ended :rejected) (hold state)
        :active
        (case (:machine/input machine-input)
          :pointer/move
          (cond (nil? pressure) (hold state)
                (< pressure (double release-threshold))
                {:state (assoc state :machine/state :ended),
                 :effects [],
                 :emissions [{:phase :cancel,
                              :payload {:pressure (or (:last-pressure
                                                        state)
                                                      pressure),
                                        :peak? (:peak-emitted? state),
                                        :reason :pointer-cancel},
                              :position (geom/centroid (:contacts ctx))}],
                 :decision :hold}
                (= pressure (:last-pressure state)) (hold state)
                :else (let [peak? (or (:peak-emitted? state)
                                      (and (some? peak-threshold)
                                           (>= pressure
                                               (double peak-threshold))))]
                        {:state (assoc state
                                       :last-pressure pressure
                                       :peak-emitted? peak?),
                         :effects [],
                         :emissions
                         [{:phase :update,
                           :payload {:pressure pressure, :peak? peak?},
                           :position (geom/centroid (:contacts ctx))}],
                         :decision :hold}))
          :pointer/up {:state (assoc state :machine/state :ended),
                       :effects [],
                       :emissions
                       [{:phase :end,
                         :payload {:pressure (or (:last-pressure state)
                                                 pressure),
                                   :peak? (:peak-emitted? state)},
                         :position (geom/centroid (:contacts ctx))}],
                       :decision :hold}
          :pointer/cancel {:state (assoc state :machine/state :ended),
                           :effects [],
                           :emissions
                           [{:phase :cancel,
                             :payload {:pressure (or (:last-pressure state)
                                                     pressure),
                                       :peak? (:peak-emitted? state),
                                       :reason :pointer-cancel},
                             :position (geom/centroid (:contacts ctx))}],
                           :decision :hold}
          :contacts/changed
          (if (or (< n (first contact-range)) (> n (second contact-range)))
            {:state (assoc state :machine/state :ended),
             :effects [],
             :emissions [{:phase :cancel,
                          :payload {:pressure (or (:last-pressure state)
                                                  0.0),
                                    :peak? (:peak-emitted? state),
                                    :reason :pointer-cancel},
                          :position (geom/centroid (:contacts ctx))}],
             :decision :hold}
            (hold state))
          (hold state))
        ;; :possible
        (case (:machine/input machine-input)
          :pointer/down (if (or (> n (second contact-range))
                                (< n (first contact-range)))
                          (reject state)
                          (hold (assoc state :last-pressure pressure)))
          :pointer/move
          (cond (nil? pressure) (hold state)
                (>= pressure (double start-threshold))
                (let [peak? (and (some? peak-threshold)
                                 (>= pressure (double peak-threshold)))]
                  {:state (assoc state
                                 :machine/state :active
                                 :last-pressure pressure
                                 :peak-emitted? peak?),
                   :effects [],
                   :emissions [{:phase :start,
                                :payload {:pressure pressure, :peak? peak?},
                                :position (geom/centroid (:contacts ctx))}],
                   :decision :accept})
                :else (hold state))
          :pointer/up (reject state)
          :pointer/cancel (reject state)
          :contacts/changed (if (or (> n (second contact-range))
                                    (< n (first contact-range)))
                              (reject state)
                              (hold state))
          (hold state))))))


;; ---------------------------------------------------------------------------
;; Capability requirements
;; ---------------------------------------------------------------------------

(def machine->required-capabilities
  {:dao.gui.event/pressure-press #{:pressure}})


(defn required-capabilities
  [machine]
  (if (map? machine) #{} (get machine->required-capabilities machine #{})))


;; ---------------------------------------------------------------------------
;; Machine dispatch
;; ---------------------------------------------------------------------------

(defn initial-state
  [machine _config]
  (if (map? machine)
    (machine/initial-state machine)
    (case machine
      :dao.gui.event/tap tap-initial-state
      :dao.gui.event/long-press long-press-initial-state
      :dao.gui.event/pan pan-initial-state
      :dao.gui.event/swipe swipe-initial-state
      :dao.gui.event/fling swipe-initial-state
      :dao.gui.event/transform transform-initial-state
      :dao.gui.event/edge-pan edge-pan-initial-state
      :dao.gui.event/pressure-press pressure-press-initial-state
      {:machine/state :possible})))


(defn step
  "Step one candidate machine. Returns
  {:state :effects [:emissions] :decision :fault?}."
  [machine ctx machine-input state config]
  (if (map? machine)
    (machine/step machine ctx machine-input state config)
    (case machine
      :dao.gui.event/tap (step-tap ctx machine-input state config)
      :dao.gui.event/long-press (step-long-press ctx machine-input state config)
      :dao.gui.event/pan (step-pan ctx machine-input state config)
      :dao.gui.event/swipe (step-swipe ctx machine-input state config)
      :dao.gui.event/fling (step-fling ctx machine-input state config)
      :dao.gui.event/transform (step-transform ctx machine-input state config)
      :dao.gui.event/edge-pan (step-edge-pan ctx machine-input state config)
      :dao.gui.event/pressure-press
      (step-pressure-press ctx machine-input state config)
      (hold state))))


(defn arbitration-projection
  [machine ctx state config]
  (if (map? machine)
    {}
    (case machine
      :dao.gui.event/tap (project-tap ctx state config)
      {})))


(defn admits-join?
  "Whether a candidate's current machine state admits a joining contact at
  the given resulting contact count."
  [machine _state config contact-count-after]
  (let [max (get-in config [:contacts :max] 1)
        min (get-in config [:contacts :min] 1)]
    (and (<= contact-count-after max)
         (>= contact-count-after min)
         (case machine
           :dao.gui.event/tap false
           :dao.gui.event/long-press false
           :dao.gui.event/pan true
           :dao.gui.event/swipe false
           :dao.gui.event/fling false
           :dao.gui.event/transform true
           :dao.gui.event/edge-pan true
           :dao.gui.event/pressure-press true
           true))))

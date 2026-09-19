(ns datomworld.demo.voxel-input
  "Keyboard and pointer input for the voxel demo, routed through
   dao.gui.event. The Flutter (CLJD) and browser (CLJS) wrappers only
   translate a native key or pointer transition into the fields of a portable
   packet; the runtime input stream, the focus record, the look recognizer,
   the on-screen button regions, and held-key tracking live here so both
   frontends share one interpreter."
  (:require [clojure.string :as string]
            [dao.gui.event :as event]
            [dao.stream :as stream]
            [dao.stream.ringbuffer :as rb]
            [datomworld.demo.voxel-controls :as controls]))


(def surface ::surface)
(def focus-id ::surface-focus)


(def ^:private generation-id 1)
(def ^:private profile-id 1)
(def ^:private look-slop 4.0)


(defonce ^:private viewport*
  ;; the host surface size; remembered across restarts so a canvas measured
  ;; before start! is not forgotten
  (atom {:width 720.0, :height 540.0}))


(def ^:private output-keys
  [:effects :trace :pointer :keyboard :gesture :dispatch :diagnostic])


(def code->action
  "Portable physical key code -> the runner's motion vocabulary."
  {:key-w :forward,
   :key-s :back,
   :key-a :left,
   :key-d :right,
   :space :up,
   :shift-left :down,
   :shift-right :down,
   :arrow-left :look-left,
   :arrow-right :look-right,
   :arrow-up :look-up,
   :arrow-down :look-down})


(defn browser-code
  "KeyboardEvent.code (\"KeyW\", \"ShiftLeft\") -> portable code (:key-w,
   :shift-left)."
  [code]
  (-> (str code)
      (string/replace #"([a-z0-9])([A-Z])" "$1-$2")
      string/lower-case
      keyword))


(defn logical-key
  "The keyboard value carries a logical key only when it is one printable
   character; named keys (\"Shift\", \"ArrowLeft\") are reported as nil."
  [k]
  (when (and (string? k) (= 1 (count k))) k))


(defn reduce-held
  "Folds one subscriber-delivered keyboard event into the set of held codes."
  [held event]
  (case (:event/phase event)
    :down (conj held (get-in event [:key :code]))
    :up (disj held (get-in event [:key :code]))
    :cancel (reduce disj held (:released-key-codes event))
    held))


(defn held-actions
  [codes]
  (into #{} (keep code->action) codes))


;; --- boot ---

(def ^:private focus-command
  {:subscription/op :focus/set, :focus/id focus-id, :node-id surface})


(def ^:private look-recognizer
  {:recognizer/id [surface ::look],
   :gesture/kind :pan,
   :machine :dao.gui.event/pan,
   :config {:contacts {:min 1, :max 1},
            :axis :free,
            :start-at :slop,
            :slop look-slop,
            :contact-loss :end},
   :arena {:priority 0, :mode :exclusive, :coexistence/group nil}})


(def ^:private default-ids
  {:generation-id generation-id,
   :coordinate-space-id 1,
   :frame-id 1,
   :viewport {:width 720.0, :height 540.0}})


(defn- coordinate-space-change
  [{:keys [generation-id coordinate-space-id viewport]} old-coordinate-space-id]
  {:runtime/source :terminal,
   :runtime/value (cond-> {:message/kind
                           :dao.terminal/coordinate-space-change,
                           :generation-id generation-id,
                           :coordinate-space-id coordinate-space-id,
                           :viewport viewport}
                    old-coordinate-space-id
                    (assoc :old-coordinate-space-id old-coordinate-space-id))})


(defn- control-node
  [{:keys [node-id x y width height]}]
  {:node-id node-id,
   :interaction/path [{:node-id node-id, :recognizers []}],
   :touch-action :none,
   :regions [{:bounds {:x x, :y y, :width width, :height height},
              :paint-order 1}]})


(defn- presented-geometry
  "The look surface with the on-screen buttons above it, so a press that
   lands on a button is hit-tested to the button and never to the surface."
  [{:keys [generation-id coordinate-space-id frame-id viewport]}]
  {:runtime/source :geometry,
   :runtime/value
   {:message/kind :dao.terminal/presented-geometry,
    :generation-id generation-id,
    :frame-id frame-id,
    :coordinate-space-id coordinate-space-id,
    :nodes (into [{:node-id surface,
                   :interaction/path [{:node-id surface,
                                       :recognizers [look-recognizer]}],
                   :touch-action :none,
                   :regions [{:bounds (assoc viewport :x 0.0 :y 0.0),
                              :paint-order 0}]}]
                 (map control-node)
                 (controls/layout viewport))}})


(def ^:private control-subscriptions
  (mapv (fn [action]
          {:runtime/source :subscription,
           :runtime/value {:subscription/op :add,
                           :subscription/id (str "voxel-control-"
                                                 (name action)),
                           :subscriber/id ::controller,
                           :node-id (controls/node-id action),
                           :event-kind :pointer,
                           :raw? true}})
        controls/actions))


(defn boot-values
  "Terminal values that open a generation: a surface that recognizes a look
   drag with on-screen buttons above it, a profile, subscriptions for the
   keys, the look gestures and the buttons' raw presses, and initial focus."
  [ids]
  (let [ids (merge default-ids ids)]
    (-> [(coordinate-space-change ids nil)
         (presented-geometry ids)
         {:runtime/source :profile,
          :runtime/value {:message/kind :dao.terminal/input-profile,
                          :generation-id (:generation-id ids),
                          :profile-id profile-id,
                          :capabilities #{:keyboard :mouse :touch :stylus},
                          :thresholds {:motion/slop look-slop,
                                       :velocity/window-us 100000}}}
         {:runtime/source :subscription,
          :runtime/value {:subscription/op :add,
                          :subscription/id "voxel-keys",
                          :subscriber/id ::controller,
                          :node-id surface,
                          :event-kind :keyboard,
                          :keyboard/phases #{:down :up :cancel}}}
         {:runtime/source :subscription,
          :runtime/value {:subscription/op :add,
                          :subscription/id "voxel-look",
                          :subscriber/id ::controller,
                          :node-id surface,
                          :event-kind :pan,
                          :gesture/phases #{:start :update}}}]
        (into control-subscriptions)
        (conj {:runtime/source :subscription, :runtime/value focus-command}))))


(defn look-delta
  "The {:x :y} drag movement a delivered event carries, or nil when the
   event is not a look gesture."
  [event]
  (when (and (= :gesture (:event/kind event)) (= :pan (:gesture/kind event)))
    (get-in event [:payload :delta])))


;; --- runtime input stream ---

(defonce ^:private input* (atom nil))


(defn- ring
  [capacity]
  (:dao.stream/handle (rb/create! {:dao.stream/type rb/transport-type,
                                   rb/capacity-key capacity})))


(defn- open
  []
  (let [input (ring 1024)
        outputs (zipmap output-keys (repeatedly #(ring 64)))
        dispatch (:dispatch outputs)]
    {:input input,
     :binding (event/bind {:inputs {:runtime-input input}, :outputs outputs}),
     :dispatch dispatch,
     :cursor (:dao.stream/cursor (stream/cursor dispatch stream/anchor-oldest)),
     :runtime-seq 0,
     :time-us -1,
     :key-seq -1,
     :pointer-seq -1,
     :pointers #{},
     :coordinate-space-id 1,
     :frame-id 1}))


(defn- next-time
  [st requested-us]
  (max (inc (:time-us st)) (long requested-us)))


(defn- advance-until-blocked
  [binding]
  (let [{next-binding :binding, status :status} (event/advance binding)]
    (if (= :advanced status) (recur next-binding) next-binding)))


(defn- offer
  "Appends one runtime input at the next seq and steps the binding until the
   input stream has nothing left to read."
  [st source value time-us]
  (let [seq (inc (:runtime-seq st))
        envelope {:runtime/seq seq,
                  :runtime/time-us time-us,
                  :runtime/source source,
                  :runtime/value value}
        appended? (= :dao.stream/ok
                     (:dao.stream/outcome (stream/append! (:input st)
                                                          envelope)))]
    (cond-> st
      appended? (assoc :runtime-seq seq
                       :time-us time-us
                       :binding (advance-until-blocked (:binding st))))))


(defn- offer-now
  [st source value]
  (offer st source value (next-time st 0)))


(defn- drain
  "Reads the subscriber dispatches the last step appended and returns
   [next-cursor events]."
  [out cursor]
  (loop [cursor cursor
         events []]
    (let [read (stream/next out cursor)]
      (case (:dao.stream/outcome read)
        :dao.stream/ok (recur (:dao.stream/cursor read)
                              (conj events (:event (:dao.stream/value read))))
        ;; an evicted position resumes at the recovery cursor the gap carried
        :dao.stream/gap (recur (:dao.stream/cursor read) events)
        [cursor events]))))


(defn- transact!
  "Applies f to the open input state and returns the events that reached
   the subscriber. Inert when no input is open."
  [f]
  (if-let [st @input*]
    (let [st (f st)
          [cursor events] (drain (:dispatch st) (:cursor st))]
      (reset! input* (assoc st :cursor cursor))
      events)
    []))


(defn stop!
  "Tears the interpreter down and releases the input stream. Idempotent."
  []
  (transact! #(offer-now %
                         :control
                         {:input/kind :dao.gui.event/teardown}))
  (reset! input* nil)
  :stopped)


(defn start!
  "Opens a fresh input stream and event binding, focused on the voxel
   surface."
  []
  (stop!)
  (reset! input*
          (reduce (fn [st {:keys [runtime/source runtime/value]}]
                    (offer-now st source value))
                  (open)
                  (boot-values {:viewport @viewport*})))
  :started)


(defn key!
  "Feeds one native key transition, given as {:phase :code :logical :location
   :modifiers :repeat? :time-us}, and returns the keyboard events delivered
   to the voxel subscriber."
  [{:keys [phase code logical location modifiers repeat? time-us]}]
  (transact!
    (fn [st]
      (let [time-us (next-time st (or time-us 0))
            input-seq (inc (:key-seq st))]
        (-> st
            (assoc :key-seq input-seq)
            (offer :keyboard
                   {:input/kind :keyboard,
                    :generation-id generation-id,
                    :input-seq input-seq,
                    :time-us time-us,
                    :phase phase,
                    :focus-id focus-id,
                    :repeat? (and (= :down phase) (boolean repeat?)),
                    :modifiers (or modifiers #{}),
                    :key {:code code,
                          :logical logical,
                          :location (or location :standard)}}
                   time-us))))))


(defn focus!
  "The host surface gained focus: the runtime focuses the voxel surface."
  []
  (transact! #(offer-now % :subscription focus-command)))


(defn focus-lost!
  "The host surface lost focus: returns the :cancel event that releases
   every held key."
  []
  (transact! #(offer-now %
                         :terminal
                         {:message/kind :dao.terminal/focus-lost,
                          :generation-id generation-id,
                          :focus-id focus-id})))


(defn- deliverable-pointer?
  "A press starts a drag only for touch, pen, or the mouse's primary button;
   every later transition must belong to a press that started."
  [st {:keys [phase id type buttons]}]
  (if (= :down phase)
    (or (not= :mouse type) (pos? (bit-and (or buttons 0) 1)))
    (contains? (:pointers st) id)))


(defn pointer!
  "Feeds one native pointer transition, given as {:phase :id :type :buttons
   :position {:x :y} :pressure :modifiers :time-us} with positions local to
   the surface, and returns the events delivered to the voxel subscriber."
  [{:keys [phase id type buttons position pressure modifiers time-us],
    :as native}]
  (transact!
    (fn [st]
      (if-not (deliverable-pointer? st native)
        st
        (let [time-us (next-time st (or time-us 0))
              pointer-seq (inc (:pointer-seq st))
              pointers (conj (:pointers st) id)
              packet {:input/kind :pointer,
                      :generation-id generation-id,
                      :frame-id (:frame-id st),
                      :coordinate-space-id (:coordinate-space-id st),
                      :profile-id profile-id,
                      :input-seq pointer-seq,
                      :pointer {:id id,
                                :type type,
                                :primary? (= id (first (sort pointers))),
                                :buttons (or buttons 0),
                                :modifiers (or modifiers #{}),
                                :pressure (or pressure 0.0)},
                      :phase phase,
                      :samples [{:time-us time-us,
                                 :position position,
                                 :sample/kind :actual}]}]
          (-> st
              (assoc :pointer-seq pointer-seq
                     :pointers (if (#{:up :cancel} phase)
                                 (disj pointers id)
                                 pointers))
              (offer :pointer packet time-us)))))))


(defn resize!
  "The host surface changed size. Mints a new coordinate space and geometry
   so pointer hit-testing keeps covering the whole surface; the size is
   remembered for the next start!."
  [width height]
  (let [size {:width (double width), :height (double height)}]
    (if (= size @viewport*)
      []
      (do (reset! viewport* size)
          (transact!
            (fn [st]
              (let [old-id (:coordinate-space-id st)
                    ids {:generation-id generation-id,
                         :coordinate-space-id (inc old-id),
                         :frame-id (inc (:frame-id st)),
                         :viewport size}]
                (reduce (fn [st {:keys [runtime/source runtime/value]}]
                          (offer-now st source value))
                        (assoc st
                               :coordinate-space-id (:coordinate-space-id ids)
                               :frame-id (:frame-id ids))
                        [(coordinate-space-change ids old-id)
                         (presented-geometry ids)]))))))))

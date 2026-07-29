(ns datomworld.media.controller
  "Pure media-player policy plus a Yang/Yin execution boundary."
  (:require
    [clojure.string :as str]
    [yang.clojure :as yang]
    [yin.vm :as vm]
    [yin.vm.ast-walker :as ast-walker]
    [yin.vm.register :as register]
    [yin.vm.stack :as stack]))


(def initial-state
  {:player/status :empty
   :player/source nil
   :player/media-kind nil
   :player/duration-seconds 0.0
   :player/position-seconds 0.0
   :player/buffered []
   :player/volume 1.0
   :player/muted? false
   :player/rate 1.0
   :player/fullscreen? false
   :player/seeking? false
   :player/error nil
   :player/next-command-id 0})


(defn event-kind
  [event]
  (or (:player.event/kind event) (:media.event/kind event)))


(defn- media-kind
  [mime-type]
  (cond
    (str/starts-with? (or mime-type "") "audio/") :audio
    (str/starts-with? (or mime-type "") "video/") :video
    :else :unknown))


(defn- finite-number?
  [x]
  (and (number? x)
       #?(:clj (Double/isFinite (double x))
          :cljs (js/Number.isFinite x)
          :cljd true)))


(defn- clamp
  [x low high]
  (max low (min high x)))


(defn- source-id
  [state]
  (get-in state [:player/source :media/source-id]))


(defn- stale-source-event?
  [state event]
  (when-let [event-source (:media/source-id event)]
    (not= event-source (source-id state))))


(defn- command
  [state kind values]
  (let [id (:player/next-command-id state 0)]
    [(update state :player/next-command-id inc)
     (merge {:media.command/id id, :media.command/kind kind} values)]))


(defn- media-result
  ([state] (media-result state [] []))
  ([state media-commands settings-commands]
   {:state state
    :media-commands (vec media-commands)
    :settings-commands (vec settings-commands)
    :view-model state}))


(defn- settings-command
  [state]
  {:settings.command/kind :save
   :settings/value
   {:player/volume (:player/volume state)
    :player/muted? (:player/muted? state)
    :player/rate (:player/rate state)}})


(defn transition
  "Apply one normalized event. Media commands express intent; only observed
   media events update actual playback state."
  [state event]
  (let [state (or state initial-state)
        kind (event-kind event)]
    (if (and (not= kind :source-selected)
             (stale-source-event? state event))
      (media-result state)
      (case kind
        :source-selected
        (let [source (select-keys event
                                  [:media/source-id :media/name
                                   :media/type :media/size
                                   :media/container
                                   :media/format-version])
              loaded (-> state
                         (assoc :player/status :loading
                                :player/source source
                                :player/media-kind
                                (media-kind (:media/type event))
                                :player/duration-seconds 0.0
                                :player/position-seconds 0.0
                                :player/buffered []
                                :player/seeking? false
                                :player/error nil))
              [next-state cmd] (command loaded
                                        :load-source
                                        {:media/source-id
                                         (:media/source-id event)})]
          (media-result next-state [cmd] []))

        :source-closed
        (media-result
          (merge state
                 (select-keys initial-state
                              [:player/status :player/source
                               :player/media-kind :player/duration-seconds
                               :player/position-seconds :player/buffered
                               :player/seeking? :player/error])))

        :loaded-metadata
        (media-result
          (assoc state
                 :player/status :ready
                 :player/duration-seconds
                 (if (finite-number? (:media/duration-seconds event))
                   (double (:media/duration-seconds event))
                   0.0)
                 :player/video-width (:media/width event)
                 :player/video-height (:media/height event)
                 :player/error nil))

        :playing (media-result (assoc state
                                      :player/status :playing
                                      :player/error nil))
        :pause (media-result (assoc state :player/status :paused))
        :waiting (media-result (assoc state :player/status :waiting))
        :seeking (media-result (assoc state :player/seeking? true))
        :seeked (media-result (assoc state :player/seeking? false))
        :ended (media-result (assoc state :player/status :ended
                                   :player/seeking? false))

        :time
        (media-result
          (cond-> state
            (finite-number? (:media/position-seconds event))
            (assoc :player/position-seconds
                   (double (:media/position-seconds event)))
            (finite-number? (:media/duration-seconds event))
            (assoc :player/duration-seconds
                   (double (:media/duration-seconds event)))
            (vector? (:media/buffered event))
            (assoc :player/buffered (:media/buffered event))))

        :volume-change
        (let [next-state (cond-> state
                           (finite-number? (:media/volume event))
                           (assoc :player/volume
                                  (clamp (double (:media/volume event)) 0.0 1.0))
                           (boolean? (:media/muted? event))
                           (assoc :player/muted? (:media/muted? event)))]
          (media-result next-state [] [(settings-command next-state)]))

        :rate-change
        (let [next-state (if (and (finite-number? (:media/rate event))
                                  (pos? (:media/rate event)))
                           (assoc state :player/rate
                                  (double (:media/rate event)))
                           state)]
          (media-result next-state [] [(settings-command next-state)]))

        :fullscreen-change
        (media-result
          (assoc state :player/fullscreen? (boolean (:media/fullscreen? event))))

        :error
        (media-result
          (assoc state :player/status :error
                 :player/error (or (:media/error event) :media-error)))

        :command-failed
        (media-result
          (cond-> (assoc state :player/error (:media/error event))
            (= :play (:media.command/kind event))
            (assoc :player/status :paused)))

        :settings-loaded
        (media-result
          (merge state
                 (select-keys (:settings/value event)
                              [:player/volume :player/muted? :player/rate])))

        :ui/toggle-play
        (if-not (:player/source state)
          (media-result state)
          (let [kind (if (= :playing (:player/status state)) :pause :play)
                [next-state cmd] (command state kind {})]
            (media-result next-state [cmd] [])))

        :ui/seek
        (if-not (:player/source state)
          (media-result state)
          (let [duration (:player/duration-seconds state)
                requested (double (or (:media/position-seconds event) 0.0))
                position (clamp requested 0.0 (max 0.0 duration))
                [next-state cmd] (command
                                   state
                                   :seek
                                   {:media/position-seconds position})]
            (media-result next-state [cmd] [])))

        :ui/set-volume
        (let [[next-state cmd]
              (command state
                       :set-volume
                       {:media/volume
                        (clamp (double (or (:media/volume event) 0.0))
                               0.0
                               1.0)})]
          (media-result next-state [cmd] []))

        :ui/toggle-muted
        (let [[next-state cmd]
              (command state :set-muted
                       {:media/muted? (not (:player/muted? state))})]
          (media-result next-state [cmd] []))

        :ui/set-rate
        (let [rate (double (or (:media/rate event) 1.0))
              [next-state cmd] (command state :set-rate
                                        {:media/rate (clamp rate 0.25 4.0)})]
          (media-result next-state [cmd] []))

        :ui/toggle-fullscreen
        (let [command-kind (if (:player/fullscreen? state)
                             :exit-fullscreen
                             :request-fullscreen)
              [next-state cmd] (command state command-kind {})]
          (media-result next-state [cmd] []))

        (media-result state)))))


(defn transition-ast
  "Compile a controller invocation with Yang. State and event are literal
   immutable values embedded in the resulting Universal AST."
  [state event]
  (yang/compile-form (list 'media-transition state event)))


(defn- run-with
  [create-vm state event]
  (-> (create-vm {:env {'media-transition transition}})
      (vm/eval (transition-ast state event))
      (vm/value)))


(defn run-transition
  "Production controller boundary: Yang -> Universal AST -> register Yin.VM."
  [state event]
  (run-with register/create-vm state event))


(defn run-transition-all-vms
  [state event]
  {:ast-walker (run-with ast-walker/create-vm state event)
   :stack (run-with stack/create-vm state event)
   :register (run-with register/create-vm state event)})

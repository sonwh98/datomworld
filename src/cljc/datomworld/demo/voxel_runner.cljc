(ns datomworld.demo.voxel-runner
  "Shared runtime state and per-tick logic for the voxel demo. The
   Flutter (CLJD) and browser (CLJS) wrappers own only their platform-
   specific timer, time source, native key translation, and widget tree;
   everything else lives here so the two frontends stay in sync."
  (:require [dao.postgraphics.terminal :as terminal]
            [dao.stream.ringbuffer :as rb]
            [datomworld.demo.voxel-controls :as controls]
            [datomworld.demo.voxel-input :as input]
            [datomworld.demo.voxel-scene :as scene]))


(defonce frame-stream
  (:dao.stream/handle (rb/create! {:dao.stream/type rb/transport-type,
                                   rb/capacity-key 4})))


(defonce ^:private player* (atom scene/default-player))
(defonce ^:private keys-down* (atom #{}))
(defonce ^:private controls-down* (atom {}))
(defonce ^:private chunk-mesh* (atom nil))
(defonce ^:private chunk-data* (atom nil))


(defn ensure-chunk-mesh!
  "Builds the chunk + face-culled mesh on first call; idempotent."
  []
  (when (nil? @chunk-mesh*)
    (let [chunk (scene/build-chunk)]
      (reset! chunk-data* chunk)
      (reset! chunk-mesh* (scene/chunk-mesh chunk)))))


(defn reset-player!
  "Resets the player to the scene's default spawn pose. Called on start!
   so each entry into the demo begins from the same camera."
  []
  (reset! player* scene/default-player))


(defn- apply-events!
  "Folds the events the event runtime delivered into the demo: keyboard
   events update the held keys, raw presses on the on-screen buttons update
   the pressed buttons, and look gestures turn the player."
  [events]
  (doseq [event events]
    (case (:event/kind event)
      :keyboard (swap! keys-down* input/reduce-held event)
      :pointer (swap! controls-down* controls/reduce-pressed event)
      :gesture (when-let [delta (input/look-delta event)]
                 (swap! player* scene/look delta))
      nil)))


(defn start-input!
  "Opens the dao.gui.event input stream with nothing held or pressed."
  []
  (reset! keys-down* #{})
  (reset! controls-down* {})
  (input/start!))


(defn stop-input!
  "Closes the input stream and drops all held-key and pressed-button state so
   a paused or disposed demo does not resume mid-motion the next time
   start-input! runs."
  []
  (input/stop!)
  (reset! keys-down* #{})
  (reset! controls-down* {}))


(defn key-input!
  "Feeds one native key transition (see datomworld.demo.voxel-input/key!)
   through dao.gui.event and updates the held keys from what it delivers."
  [native-key]
  (apply-events! (input/key! native-key)))


(defn pointer-input!
  "Feeds one native pointer transition (see
   datomworld.demo.voxel-input/pointer!); a drag turns the player and a press
   on a button moves it."
  [native-pointer]
  (apply-events! (input/pointer! native-pointer))
  ;; a lifted or cancelled finger always releases its button; dao.gui.event
  ;; reports no raw :cancel for a single-contact press, so this does not rely
  ;; on the event
  (when (#{:up :cancel} (:phase native-pointer))
    (swap! controls-down* dissoc (:id native-pointer))))


(defn resize-input!
  "Reports the host surface size so a drag anywhere on it is recognized and
   the buttons are hit where they are drawn. Presses in progress end, since
   the buttons moved."
  [width height]
  (apply-events! (input/resize! width height))
  (reset! controls-down* {}))


(defn pressed-controls
  "The on-screen buttons currently held, for drawing them pressed."
  []
  (controls/pressed-actions @controls-down*))


(defn player-pos
  "The player's current pose, for tests and any frontend that reads the
   runtime state directly rather than through the frame stream."
  []
  @player*)


(defn focus-input!
  "Reports host focus gained or lost. Losing focus releases every held key."
  [focused?]
  (apply-events! (if focused? (input/focus!) (input/focus-lost!))))


(defn tick!
  "Advances the player by dt seconds (with chunk collision) and pushes
   the resulting frame onto frame-stream. The time source stays on the
   caller so platforms can use their native monotonic clock."
  [dt]
  (let [new-player
        (scene/integrate-motion @player*
                                (into (input/held-actions @keys-down*)
                                      (pressed-controls))
                                dt
                                @chunk-data*)]
    (reset! player* new-player)
    (terminal/put-frame! frame-stream
                         (scene/frame-from-state new-player @chunk-mesh*))))

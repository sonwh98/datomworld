(ns datomworld.demo.voxel-runner
  "Shared runtime state and per-tick logic for the voxel demo. The
   Flutter (CLJD) and browser (CLJS) wrappers own only their platform-
   specific timer, time source, keyboard mapping, and widget tree;
   everything else lives here so the two frontends stay in sync."
  (:require [dao.postgraphics.terminal :as terminal]
            [dao.stream.v2.ringbuffer :as rb]
            [datomworld.demo.voxel-scene :as scene]))


(defonce frame-stream
  (:dao.stream/handle (rb/create! {:dao.stream/type rb/transport-type,
                                   rb/capacity-key 4})))


(defonce ^:private player* (atom scene/default-player))
(defonce ^:private keys-down* (atom #{}))
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


(defn key-down!
  "Marks an action keyword as held. Each platform's key listener maps its
   native key code to an action (:forward/:back/:left/:right/:up/:down/
   :look-left/:look-right/:look-up/:look-down) and forwards it here."
  [action]
  (when action (swap! keys-down* conj action)))


(defn key-up!
  [action]
  (when action (swap! keys-down* disj action)))


(defn clear-keys!
  "Drops all held-key state so a paused/disposed demo does not resume
   mid-motion the next time start! runs."
  []
  (reset! keys-down* #{}))


(defn tick!
  "Advances the player by dt seconds (with chunk collision) and pushes
   the resulting frame onto frame-stream. The time source stays on the
   caller so platforms can use their native monotonic clock."
  [dt]
  (let [new-player
        (scene/integrate-motion @player* @keys-down* dt @chunk-data*)]
    (reset! player* new-player)
    (terminal/put-frame! frame-stream
                         (scene/frame-from-state new-player @chunk-mesh*))))

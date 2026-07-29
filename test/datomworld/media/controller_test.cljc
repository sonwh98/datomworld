(ns datomworld.media.controller-test
  (:require
    [clojure.test :refer [deftest is]]
    [datomworld.media.controller :as controller]))


(deftest source-selection-emits-load-command-test
  (let [{:keys [state media-commands]}
        (controller/transition
          controller/initial-state
          {:player.event/kind :source-selected
           :media/source-id "source-1"
           :media/name "movie.mp4"
           :media/type "video/mp4"
           :media/size 2048
           :media/container :native})]
    (is (= :loading (:player/status state)))
    (is (= "source-1" (get-in state [:player/source :media/source-id])))
    (is (= :native (get-in state [:player/source :media/container])))
    (is (= :video (:player/media-kind state)))
    (is (= [{:media.command/id 0
             :media.command/kind :load-source
             :media/source-id "source-1"}]
           media-commands))))


(deftest media-observations-are-source-scoped-test
  (let [selected (:state
                   (controller/transition
                     controller/initial-state
                     {:player.event/kind :source-selected
                      :media/source-id "current"
                      :media/name "movie.mp4"
                      :media/type "video/mp4"
                      :media/size 2048}))
        stale (:state
                (controller/transition
                  selected
                  {:media.event/kind :loaded-metadata
                   :media/source-id "old"
                   :media/duration-seconds 90.0}))
        current (:state
                  (controller/transition
                    selected
                    {:media.event/kind :loaded-metadata
                     :media/source-id "current"
                     :media/duration-seconds 90.0
                     :media/width 1920
                     :media/height 1080}))]
    (is (= selected stale))
    (is (= :ready (:player/status current)))
    (is (= 90.0 (:player/duration-seconds current)))))


(deftest controls-emit-intent-without-optimistic-playback-test
  (let [ready (assoc controller/initial-state
                     :player/status :ready
                     :player/source {:media/source-id "source-1"}
                     :player/duration-seconds 100.0)
        play-result (controller/transition
                      ready
                      {:player.event/kind :ui/toggle-play})
        seek-result (controller/transition
                      (:state play-result)
                      {:player.event/kind :ui/seek
                       :media/position-seconds 140.0})]
    (is (= :ready (get-in play-result [:state :player/status])))
    (is (= :play
           (get-in play-result [:media-commands 0 :media.command/kind])))
    (is (= 100.0
           (get-in seek-result
                   [:media-commands 0 :media/position-seconds])))))


(deftest settings-follow-observed-media-state-test
  (let [{:keys [state settings-commands]}
        (controller/transition
          controller/initial-state
          {:media.event/kind :volume-change
           :media/volume 0.4
           :media/muted? true})]
    (is (= 0.4 (:player/volume state)))
    (is (true? (:player/muted? state)))
    (is (= :save
           (get-in settings-commands [0 :settings.command/kind])))))


(deftest play-rejection-restores-paused-state-test
  (let [{:keys [state]}
        (controller/transition
          (assoc controller/initial-state :player/status :playing)
          {:media.event/kind :command-failed
           :media.command/kind :play
           :media/error :autoplay-blocked})]
    (is (= :paused (:player/status state)))
    (is (= :autoplay-blocked (:player/error state)))))


(deftest yang-yin-controller-parity-test
  (let [event {:player.event/kind :source-selected
               :media/source-id "source-1"
               :media/name "track.ogg"
               :media/type "audio/ogg"
               :media/size 1024}
        direct (controller/transition controller/initial-state event)
        results (controller/run-transition-all-vms
                  controller/initial-state
                  event)]
    (is (= direct (:ast-walker results)))
    (is (= direct (:stack results)))
    (is (= direct (:register results)))))

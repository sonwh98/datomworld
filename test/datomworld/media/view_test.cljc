(ns datomworld.media.view-test
  (:require
    [clojure.test :refer [deftest is]]
    [dao.postgraphics.lowering :as lowering]
    [datomworld.media.controller :as controller]
    [datomworld.media.view :as view]))


(defn- regions
  [frame]
  (filter #(= :meta/region (:op/kind %)) frame))


(deftest player-frame-is-transparent-valid-postgraphics-test
  (let [frame (view/frame controller/initial-state
                          {:viewport-width 960 :viewport-height 540})]
    (is (= {:op/kind :frame/clear :color [0.0 0.0 0.0 0.0]}
           (first frame)))
    (is (= frame
           (lowering/validate-frame! frame
                                     {:supports-image? true
                                      :supports-render-targets? true})))))


(deftest loaded-player-exposes-complete-core-control-regions-test
  (let [state (assoc controller/initial-state
                     :player/status :playing
                     :player/source {:media/source-id "source-1"
                                     :media/name "Movie.mp4"}
                     :player/duration-seconds 100.0
                     :player/position-seconds 25.0
                     :player/buffered [[0.0 60.0]])
        frame (view/frame state
                          {:viewport-width 960 :viewport-height 540})
        node-ids (set (map #(get-in % [:op/meta :node-id])
                           (regions frame)))]
    (is (every? node-ids
                [:player/play-pause :player/seek :player/mute
                 :player/rate :player/fullscreen :player/open-file]))
    (is (some #(and (= :draw/fill-rect (:op/kind %))
                    (= :player/progress
                       (get-in % [:op/meta :node-id])))
              frame))))


(deftest responsive-layout-moves-controls-test
  (let [state (assoc controller/initial-state
                     :player/status :ready
                     :player/source {:media/source-id "source-1"
                                     :media/name "Movie.mp4"})
        narrow (view/layout state {:viewport-width 420
                                   :viewport-height 760})
        wide (view/layout state {:viewport-width 1000
                                 :viewport-height 600})]
    (is (= :compact (:layout/mode narrow)))
    (is (= :wide (:layout/mode wide)))
    (is (not= (:controls/y narrow) (:controls/y wide)))))

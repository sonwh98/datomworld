(ns datomworld.media.datom-log-test
  (:require
    [clojure.test :refer [deftest is]]
    [datomworld.media.datom-log :as datom-log]))


(deftest stream-values-become-canonical-d5-datoms-test
  (let [datoms (datom-log/value->datoms
                 7
                 :event-stream
                 {:media.event/kind :time
                  :media/position-seconds 12.5})]
    (is (every? #(= 5 (count %)) datoms))
    (is (every? #(= -1032 (nth % 0)) datoms))
    (is (every? #(= 7 (nth % 3)) datoms))
    (is (every? #(= 1 (nth % 4)) datoms))
    (is (some #(= [-1032 :stream/origin :event-stream 7 1] %)
              datoms))
    (is (some #(= :media/position-seconds (nth % 1)) datoms))))


(deftest browser-capabilities-never-enter-the-datom-stream-test
  (let [datoms (datom-log/value->datoms
                 1
                 :event-stream
                 {:media/name "movie.mp4"
                  :media/object-url "blob:secret"
                  :media/file :opaque-browser-object})]
    (is (= #{:stream/origin :media/name}
           (set (map second datoms))))
    (is (not-any? #(re-find #"blob:" (pr-str %)) datoms))))


(deftest state-datoms-contain-only-changed-facts-test
  (let [before {:player/status :ready
                :player/position-seconds 2.0
                :player/volume 1.0}
        after (assoc before
                     :player/status :playing
                     :player/position-seconds 2.25)
        datoms (datom-log/changes->datoms 9 before after)]
    (is (= #{:stream/origin
             :player/status
             :player/position-seconds}
           (set (map second datoms))))
    (is (not-any? #(= :player/volume (second %)) datoms))))

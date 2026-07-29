(ns datomworld.media.format-test
  (:require
    [clojure.test :refer [deftest is]]
    [datomworld.media.format :as media-format]))


(deftest movie-manifest-is-canonical-d5-test
  (let [datoms (media-format/manifest-datoms
                 {:media/name "clip.mp4"
                  :media/type "video/mp4"
                  :media/size 4096
                  :media/duration-seconds 12.5})]
    (is (every? #(= 5 (count %)) datoms))
    (is (every? #(= -1025 (first %)) datoms))
    (is (= :movie
           (media-format/manifest-value
             datoms :datom.media/kind)))
    (is (= :native-payload
           (media-format/manifest-value
             datoms :datom.media/codec-mode)))
    (is (= ".datmov" (media-format/extension-for-kind :movie)))))


(deftest music-manifest-round-trips-through-edn-test
  (let [datoms (media-format/manifest-datoms
                 {:media/name "track.flac"
                  :media/type "audio/flac"
                  :media/size 8192})
        encoded (media-format/encode-manifest datoms)
        decoded (media-format/decode-manifest encoded)]
    (is (= datoms decoded))
    (is (media-format/valid-manifest? decoded))
    (is (= {:media/name "track.flac"
            :media/type "audio/flac"
            :media/size 8192
            :media/kind :music
            :media/container :datmus
            :media/format-version 1}
           (media-format/manifest->metadata decoded)))))


(deftest malformed-or-inconsistent-manifests-are-rejected-test
  (is (false? (media-format/valid-manifest?
                [[-1025 :datom.media/version 99 1 1]])))
  (is (false? (media-format/valid-manifest?
                [[-1025 :datom.media/version 1 1 0]])))
  (is (false? (media-format/valid-manifest?
                {:not "datoms"}))))

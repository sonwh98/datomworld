(ns datomworld.media.format
  "Pure manifest contract for .datmov and .datmus lossless containers."
  (:require
    #?(:clj [clojure.edn :as edn]
       :cljs [cljs.reader :as reader])
    [clojure.string :as str]))


(def version 1)
(def movie-magic "DATMOV1\n")
(def music-magic "DATMUS1\n")
(def header-size 12)
(def maximum-manifest-bytes (* 1024 1024))


(def ^:private manifest-attributes
  #{:datom.media/version
    :datom.media/kind
    :datom.media/codec-mode
    :datom.media/original-name
    :datom.media/original-mime
    :datom.media/payload-bytes
    :datom.media/duration-seconds})


(defn kind-for-type
  [mime-type]
  (cond
    (str/starts-with? (or mime-type "") "video/") :movie
    (str/starts-with? (or mime-type "") "audio/") :music
    :else nil))


(defn extension-for-kind
  [kind]
  (case kind
    :movie ".datmov"
    :music ".datmus"
    nil))


(defn magic-for-kind
  [kind]
  (case kind
    :movie movie-magic
    :music music-magic
    nil))


(defn manifest-value
  [datoms attribute]
  (some (fn [[_ attr value _ _]]
          (when (= attr attribute) value))
        datoms))


(defn manifest-datoms
  [{:keys [media/name media/type media/size media/duration-seconds]}]
  (let [kind (kind-for-type type)
        facts [[:datom.media/version version]
               [:datom.media/kind kind]
               [:datom.media/codec-mode :native-payload]
               [:datom.media/original-name name]
               [:datom.media/original-mime type]
               [:datom.media/payload-bytes size]]
        facts (cond-> facts
                (number? duration-seconds)
                (conj [:datom.media/duration-seconds
                       (double duration-seconds)]))]
    (mapv (fn [[attribute value]]
            [-1025 attribute value 1 1])
          facts)))


(defn valid-manifest?
  [datoms]
  (and
    (vector? datoms)
    (every? (fn [datom]
              (and (vector? datom)
                   (= 5 (count datom))
                   (= -1025 (nth datom 0))
                   (contains? manifest-attributes (nth datom 1))
                   (= 1 (nth datom 3))
                   (= 1 (nth datom 4))))
            datoms)
    (= version (manifest-value datoms :datom.media/version))
    (contains? #{:movie :music}
               (manifest-value datoms :datom.media/kind))
    (= :native-payload
       (manifest-value datoms :datom.media/codec-mode))
    (string? (manifest-value datoms :datom.media/original-name))
    (let [mime (manifest-value datoms :datom.media/original-mime)
          kind (manifest-value datoms :datom.media/kind)]
      (and (string? mime) (= kind (kind-for-type mime))))
    (let [size (manifest-value datoms :datom.media/payload-bytes)]
      (and (integer? size) (not (neg? size))))))


(defn encode-manifest
  [datoms]
  (pr-str datoms))


(defn decode-manifest
  [text]
  (try
    #?(:clj (edn/read-string text)
       :cljs (reader/read-string text))
    (catch #?(:clj Exception :cljs :default) _
      nil)))


(defn manifest->metadata
  [datoms]
  (when (valid-manifest? datoms)
    (let [kind (manifest-value datoms :datom.media/kind)]
      {:media/name
       (manifest-value datoms :datom.media/original-name)
       :media/type
       (manifest-value datoms :datom.media/original-mime)
       :media/size
       (manifest-value datoms :datom.media/payload-bytes)
       :media/kind kind
       :media/container (if (= kind :movie) :datmov :datmus)
       :media/format-version version})))

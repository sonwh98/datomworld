(ns dao.jing.cbor-fixtures-test
  "Structural self-tests of the frozen DaoJing CBOR corpus. They need no
   codec: they prove the resource is well formed, its digests match its
   bytes, and its equivalence and distinction groups say what the plan
   requires. Codec conformance against the corpus is phase J1/J2 work."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.cbor-fixtures :as fx]))


(def required-categories
  #{"collections" "frames" "metadata" "unicode" "identifiers" "bytes" "numerics"
    "malformed" "boring-options" "injectivity" "unsupported-values"})


(def required-equivalence-prefixes
  "The declared normalizations of the plan's acceptance paragraph."
  ["integer-width/" "sortedness/" "sequence-realization/" "metadata-stripped/"
   "reduced-ratio/" "canonical-nan" "float32-widening/"])


(def required-distinction-prefixes
  "The retained distinctions of the plan's acceptance paragraph."
  ["numeric-kind/" "decimal-scale/" "signed-zero/" "retained-metadata/"
   "pathological-identifiers"])


(def required-refusals
  "Every refusal class of the v1 vocabulary."
  #{"identifier-tag-39" "unknown-tag" "unknown-frame-name" "malformed-frame"
    "native-float" "trailing-data" "non-canonical" "duplicate-key"
    "duplicate-element" "equality-collapse" "invalid-utf8" "unpaired-surrogate"
    "unsupported-simple" "malformed-cbor" "malformed-number"
    "unsupported-value"})


(def frame-name-hex
  "Each named frame's name as its CBOR text item (head byte, then UTF-8)."
  {"dao.jing/list" "6d64616f2e6a696e672f6c697374"
   "dao.jing/keyword" "7064616f2e6a696e672f6b6579776f7264"
   "dao.jing/symbol" "6f64616f2e6a696e672f73796d626f6c"
   "dao.jing/float64" "7064616f2e6a696e672f666c6f61743634"})


(defn- starts-with?
  [s prefix]
  (and (<= (count prefix) (count s)) (= prefix (subs s 0 (count prefix)))))


(defn- includes-bytes?
  "True when hex string part occurs in hex string s at a byte boundary."
  [s part]
  (boolean (some #(= part (subs s % (+ % (count part))))
                 (range 0 (inc (- (count s) (count part))) 2))))


(defn- canonical
  []
  (fx/cases-of-kind "canonical"))


(defn- id
  [c]
  (get c "id"))


(deftest corpus-loads
  (is (= "dao.jing.cbor-fixtures" (get (fx/corpus) "format")))
  (is (= 1 (get (fx/corpus) "version")))
  (is (seq (fx/cases))))


(deftest json-round-trips
  (is (= (fx/corpus) (fx/json-round-trip (fx/read-text)))))


(deftest case-ids-are-unique
  (let [ids (map id (fx/cases))]
    (is (every? string? ids))
    (is (= (count ids) (count (set ids))))))


(deftest cases-follow-their-kind
  (let [classes (set (get (fx/corpus) "refusal-classes"))]
    (doseq [c (fx/cases)]
      (let [input (get c "input")
            hex (get c "hex")
            refusal (get c "refusal")]
        (testing (id c)
          (case (get c "kind")
            "canonical" (is (and (map? input) (string? hex) (pos? (count hex))
                                 (nil? refusal)))
            "encode-refusal" (is (and (map? input) (nil? hex)
                                      (nil? (get c "sha256"))
                                      (contains? classes refusal)
                                      (nil? (get c "equivalence"))
                                      (empty? (get c "distinct"))))
            "decode-refusal" (is (and (nil? input) (string? hex)
                                      (contains? classes refusal)
                                      (nil? (get c "equivalence"))
                                      (empty? (get c "distinct"))))
            (is false "unknown kind"))
          (is (pos? (count (get c "plan"))))
          (is (seq (get c "categories"))))))))


(deftest hex-strings-are-well-formed
  (doseq [c (fx/cases)
          :let [hex (get c "hex")]
          :when (some? hex)]
    (testing (id c)
      (is (fx/hex? hex))
      (is (fx/hex? (get c "sha256")))
      (is (= 64 (count (get c "sha256")))))))


(deftest recorded-sha256-is-the-digest-of-the-bytes
  (doseq [c (fx/cases)
          :let [hex (get c "hex")]
          :when (some? hex)]
    (is (= (get c "sha256") (jing/sha256-bytes (fx/hex->bytes hex))) (id c))))


(deftest equivalence-groups-share-bytes
  (let [groups (group-by #(get % "equivalence")
                         (filter #(get % "equivalence") (canonical)))]
    (is (seq groups))
    (doseq [[g members] groups]
      (testing g
        (is (<= 2 (count members)))
        (is (= 1 (count (set (map #(get % "hex") members)))))))))


(defn- distinction-groups
  []
  (reduce (fn [acc c]
            (reduce (fn [acc g] (update acc g (fnil conj []) c))
                    acc
                    (get c "distinct")))
          {}
          (canonical)))


(deftest retained-distinctions-differ-in-bytes-and-address
  (let [groups (distinction-groups)]
    (is (seq groups))
    (doseq [[g members] groups]
      (testing g
        (is (<= 2 (count members)))
        (is (= (count members) (count (set (map #(get % "hex") members)))))
        (is (= (count members)
               (count (set (map #(get % "sha256") members)))))))))


(deftest equal-bytes-only-within-one-equivalence-group
  (let [cs (canonical)]
    (doseq [[_ members] (group-by #(get % "hex") cs)
            :when (< 1 (count members))]
      (let [groups (set (map #(get % "equivalence") members))]
        (is (and (= 1 (count groups)) (some? (first groups)))
            (str "shared bytes outside one group: " (mapv id members)))))
    (is (= (count (set (map #(get % "hex") cs)))
           (count (set (map #(get % "sha256") cs))))
        "distinct canonical bytes have distinct fixture addresses")))


(deftest pathological-identifiers-are-pairwise-distinct
  (let [members (get (distinction-groups) "pathological-identifiers")]
    (is (<= 20 (count members)))
    (is (= (count members) (count (set (map #(get % "hex") members)))))
    (is (= (count members) (count (set (map #(get % "sha256") members)))))))


(deftest every-required-category-is-covered
  (let [seen (frequencies (mapcat #(get % "categories") (fx/cases)))]
    (doseq [category required-categories]
      (is (pos? (get seen category 0)) category))))


(deftest every-declared-normalization-and-distinction-has-a-group
  (let [eq-groups (set (keep #(get % "equivalence") (canonical)))
        distinct-groups (set (keys (distinction-groups)))]
    (doseq [prefix required-equivalence-prefixes]
      (is (some #(starts-with? % prefix) eq-groups) prefix))
    (doseq [prefix required-distinction-prefixes]
      (is (some #(starts-with? % prefix) distinct-groups) prefix))))


(deftest every-required-refusal-class-occurs
  (let [seen (set (keep #(get % "refusal") (fx/cases)))]
    (is (= required-refusals (set (get (fx/corpus) "refusal-classes"))))
    (doseq [cls required-refusals]
      (is (contains? seen cls) cls))))


(deftest every-named-frame-has-accepted-and-malformed-cases
  (doseq [[frame-name name-hex] frame-name-hex]
    (testing frame-name
      (is (some #(includes-bytes? (get % "hex") name-hex) (canonical)))
      (is (some #(and (= "malformed-frame" (get % "refusal"))
                      (includes-bytes? (get % "hex") name-hex))
                (fx/cases-of-kind "decode-refusal"))))))

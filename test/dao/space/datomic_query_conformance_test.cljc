(ns dao.space.datomic-query-conformance-test
  "The DaoSpace-side gate for the frozen datomic-q-v1 corpus (phase 0 of
   docs/design/dao.space.query.datomic-compat.md).

   Two things are proven here, and neither is Datomic compatibility:

   1. Corpus integrity: the schema, the classification and evidence
      vocabularies, the supported-domain inventory, the fixtures and errata
      the cases cite, and the BLAKE3 digest of the resource bytes.
   2. DaoSpace-side behavior: core `dao.space.query/q` + `collect` against
      the fixture data, with an honest split by case status.
        executable / difference  assert the recorded expectation
        refusal                  assert the core throws the recorded message
        pending                  assert only their own pending status; an
                                 optional `daospace-observed` record is a
                                 regression pin of what the core does today,
                                 labelled as NOT a compatibility claim

   No case here is `oracle-executed`: the Client oracle backend is not yet
   selected (datomic-q-v1.provenance.edn). Agreement among DaoSpace
   implementations alone cannot establish Datomic compatibility, and an
   unverified expectation never counts as a passing conformance assertion.

   The digest is pinned as a literal; a test run never regenerates the
   corpus, its expectations, or its digest (README: promotion procedure)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [dao.jing.cbor-fixtures :as cfx]
            [dao.space.datomic-query-fixtures :as fx]
            [dao.space.query :as query]))


(def host
  #?(:cljd "cljd" :clj "clj" :cljs "cljs"))


;; ==========================================================================
;; Small portable helpers
;; ==========================================================================

(def ^:private hex-digits "0123456789abcdef")


(defn- byte-hex
  [n]
  (str (subs hex-digits (quot n 16) (inc (quot n 16)))
       (subs hex-digits (rem n 16) (inc (rem n 16)))))


(defn- code-at
  [^String s i]
  #?(:cljd (.codeUnitAt s i)
     :clj (int (.charAt s (int i)))
     :cljs (.charCodeAt s i)))


(defn- ascii?
  [s]
  (every? #(< (code-at s %) 128) (range (count s))))


(defn- ascii-bytes
  [s]
  (cfx/hex->bytes (apply str (map #(byte-hex (code-at s %)) (range (count s))))))


(defn- assert-none
  "Assert an immutable collection of [id what] failures is empty."
  [failures label]
  (is (empty? failures)
      (str label ": " (count failures) " failing; first: "
           (pr-str (take 8 failures)))))


(defn- thrown-message
  "The ex-message f throws, ::none when it returns, [::other repr] else."
  [f]
  (try (f)
       ::none
       (catch #?(:cljd Object :clj Throwable :cljs :default) e
         (or (ex-message e) [::other (str e)]))))


(defn- contains-text?
  [s needle]
  (and (string? s) (str/includes? s needle)))


;; ==========================================================================
;; Pinned values (reviewed; never recomputed by a test run)
;; ==========================================================================

(def resource-blake3
  "BLAKE3 of datomic-q-v1.json's bytes, frozen at corpus review."
  "692ba5a018413a3a36a6e4bc2c6f6f5ecde80fcb02de7bccdc625e671315c54b")


(def expected-case-count 120)


;; ==========================================================================
;; Corpus integrity
;; ==========================================================================

(defn- decode-observed
  [o]
  (fx/decode (get o "value")))


(def ^:private implemented-comparisons
  "The comparison rules `same?` implements; keep in step with its case."
  #{"exact" "unordered-coll" "unordered-nested" "row-count" "slice-size" "numeric-value"})


(def ^:private conclusive-oracle-outcomes
  "Oracle outcomes that are recorded, conclusive evidence. `pending` (not
   observed) and `inconclusive` (observed, nothing established) are not."
  #{"agrees" "agrees-refusal" "agrees-contract-expectation"
    "differs-documented" "datomic-accepts"})


(def ^:private required-case-keys
  ["id" "point" "query" "inputs" "classification" "evidence" "status" "expect"
   "comparison" "hosts" "oracle"])


(defn- vocab
  [k]
  (set (get-in (fx/corpus) ["vocabulary" k])))


(defn- case-problems
  "The integrity problems of one case, as strings."
  [c]
  (let [status (get c "status")
        kind (get-in c ["expect" "kind"])
        point-ids (set (map #(get % "id") (fx/points)))
        errata (cfx/read-path fx/errata-path)]
    (concat
      (for [k required-case-keys :when (not (contains? c k))]
        (str "missing key " k))
      (when-not (contains? point-ids (get c "point")) ["undeclared point"])
      (when-not (contains? (vocab "statuses") status) ["bad status"])
      (when-not (contains? (vocab "classifications") (get c "classification"))
        ["bad classification"])
      (when-not (contains? (vocab "evidence") (get c "evidence")) ["bad evidence"])
      (when-not (contains? (vocab "comparisons") (get c "comparison")) ["bad comparison"])
      (when-not (contains? (vocab "expect-kinds") kind) ["bad expect kind"])
      (when (and (get c "workload") (not (contains? (vocab "workloads") (get c "workload"))))
        ["bad workload"])
      (when-not (and (seq (get c "hosts")) (every? (vocab "hosts") (get c "hosts")))
        ["bad hosts"])
      ;; the honest status/expectation pairing
      (when (and (= "pending" status) (not= "pending" kind))
        ["pending case carries a non-pending expectation"])
      (when (and (not= "pending" status) (= "pending" kind))
        ["non-pending case carries a pending expectation"])
      (when (and (= "pending" kind) (contains? (get c "expect") "value"))
        ["pending expectation carries a value"])
      (when (and (= "pending" kind) (not (string? (get-in c ["expect" "reason"]))))
        ["pending expectation has no reason"])
      (when (and (= "refusal" status) (not= "refusal" kind))
        ["refusal case without a refusal expectation"])
      (when (and (= "refusal" kind) (not (string? (get-in c ["expect" "message-contains"]))))
        ["refusal expectation has no message-contains"])
      (when (and (#{"executable" "difference"} status) (not= "value" kind))
        ["executable case without a value expectation"])
      ;; unverified cases never assert; nothing is oracle-executed yet
      (when (and (= "unverified" (get c "classification"))
                 (not= "pending" status))
        ["unverified classification must be pending"])
      ;; oracle evidence is a conclusive recorded execution, kept distinct from
      ;; an inconclusive or unobserved one (Phase 5 r11 ruling)
      (when-not (contains? (vocab "oracle") (get c "oracle"))
        ["bad oracle outcome"])
      (when (not= (= "oracle-executed" (get c "evidence"))
                  (contains? conclusive-oracle-outcomes (get c "oracle")))
        ["oracle-executed evidence must match a conclusive oracle outcome"])
      (when (and (not= "pending" (get c "oracle"))
                 (not (contains-text? (cfx/read-path fx/observations-path)
                                      (str "\"" (get c "id") "\""))))
        ["oracle outcome without a preserved observation"])
      (when (and (contains? #{"differs-documented" "datomic-accepts" "inconclusive"}
                            (get c "oracle"))
                 (not (string? (get c "oracle-note"))))
        ["oracle outcome without an oracle-note"])
      (for [p (get c "entity-ids")
            :when (not (contains? (fx/fixtures) (get p "source")))]
        (str "entity-ids names unknown fixture " (get p "source")))
      (when (and (= "difference" status)
                 (not= "documented-difference" (get c "classification")))
        ["difference status requires documented-difference classification"])
      (when (and (#{"row-count" "slice-size"} (get c "comparison"))
                 (= "value" kind)
                 (not (integer? (get-in c ["expect" "value"]))))
        ["size comparison needs an integer expectation"])
      ;; a rule `same?` does not implement may not back an asserting case
      (when (and (not (contains? implemented-comparisons (get c "comparison")))
                 (or (not= "pending" status) (get c "daospace-observed")))
        [(str "comparison " (get c "comparison") " is not implemented by same?")])
      (when-let [l (get c "limitation")]
        (when-not (contains-text? errata (str "## " l " "))
          [(str "limitation " l " has no errata entry")]))
      (when (and (get c "daospace-observed")
                 (not (#{"value" "refusal"} (get-in c ["daospace-observed" "kind"]))))
        ["bad daospace-observed"])
      (for [in (get c "inputs")
            :when (and (contains? in "db")
                       (not (contains? (fx/fixtures) (get in "db"))))]
        (str "unknown fixture " (get in "db"))))))


(deftest every-case-is-well-formed
  (assert-none (mapcat (fn [c] (map (fn [p] [(get c "id") p]) (case-problems c)))
                       (fx/cases))
               "case schema"))


(deftest case-ids-are-unique
  (let [ids (map #(get % "id") (fx/cases))]
    (is (= (count ids) (count (set ids))) "no duplicate case id")))


(deftest the-supported-domain-inventory-is-covered
  (let [covered (set (map #(get % "point") (fx/cases)))
        declared (map #(get % "id") (fx/points))]
    (is (= (count declared) (count (set declared))) "no duplicate point id")
    (assert-none (for [p declared :when (not (contains? covered p))] [p "no case"])
                 "contract points without a case")
    (assert-none (for [p (fx/points)
                       :when (not (contains? (vocab "classifications")
                                             (get p "classification")))]
                   [(get p "id") "bad classification"])
                 "point classifications")))


(deftest the-web-app-workload-classes-are-covered
  (let [seen (set (keep #(get % "workload") (fx/cases)))]
    (is (= (vocab "workloads") seen) "all five workload classes have a case")))


(deftest every-datum-decodes
  (let [failures
        (atom [])]
    (doseq [c (fx/cases)]
      (try (fx/case-query c)
           (fx/case-inputs c)
           (fx/case-options c)
           (when (= "value" (get-in c ["expect" "kind"])) (fx/case-expected c))
           (when-let [o (get c "daospace-observed")]
             (when (= "value" (get o "kind")) (decode-observed o)))
           (catch #?(:cljd Object :clj Throwable :cljs :default) e
             (swap! failures conj [(get c "id") (str (ex-message e))]))))
    (assert-none @failures "decoding")))


(deftest every-fixture-decodes-and-is-referenced
  (let [used (set (for [c (fx/cases) in (get c "inputs") :when (contains? in "db")]
                    (get in "db")))]
    (assert-none (for [id (keys (fx/fixtures))
                       :when (not (contains? used id))]
                   [id "fixture unused"])
                 "unused fixtures")
    (assert-none (for [id (keys (fx/fixtures))
                       :let [r (try (fx/fixture-source id) nil
                                    (catch #?(:cljd Object :clj Throwable :cljs :default) e
                                      (str (ex-message e))))]
                       :when r]
                   [id r])
                 "fixture loading")))


(deftest typed-values-keep-their-kind
  (testing "tagged numerics are not plain JSON numbers"
    (let [d (fx/decode {"decimal" {"exponent" -1 "mantissa" "10"}})
          d2 (fx/decode {"decimal" {"exponent" -2 "mantissa" "100"}})]
      (is (not (cbor/content= d d2)) "1.0M and 1.00M stay content-distinct")
      (is (cbor/decimal? d)))
    (is (not (cbor/content=
               (fx/decode {"float64-bits" "0000000000000000"})
               (fx/decode {"float64-bits" "8000000000000000"})))
        "signed zeros stay content-distinct"))
  (testing "structure"
    (is (= '(:find ?e) (fx/decode {"list" [{"kw" "find"} {"sym" "?e"}]})))
    (is (list? (fx/decode {"list" [1 2]})))
    (is (= {:a/b #{1}} (fx/decode {"map" [[{"kw" "a/b"} {"set" [1]}]]})))
    (is (= 'x/y (fx/decode {"sym" "x/y"})))
    (is (= [1 "a" nil true] (fx/decode [1 "a" nil true]))))
  (testing "bare non-integer numbers and untagged objects are refused"
    (is (not= ::none (thrown-message #(fx/decode {"a" 1}))))
    (is (not= ::none (thrown-message #(fx/decode {"kw" "a" "sym" "b"}))))
    (is (not= ::none (thrown-message #(fx/decode {"nope" 1}))))))


(deftest the-resource-bytes-are-the-frozen-bytes
  (let [text (fx/read-text)]
    (is (ascii? text) "the corpus is pure ASCII, so its text is its bytes")
    (is (= resource-blake3 (jing/blake3-bytes (ascii-bytes text)))
        "datomic-q-v1.json is byte-identical to the digest pinned at review")
    (is (= expected-case-count (count (fx/cases))) "case count is pinned")
    (is (contains-text? (cfx/read-path fx/provenance-path) resource-blake3)
        "provenance records the same digest")))


(deftest entity-id-substitution-touches-only-annotated-positions
  (let [c {"id" "t"
           "entity-ids" [{"source" "g", "path" ["query" 3 1]}
                         {"source" "g", "path" [1 0]}]}
        q '[:find ?x :where (r 1 ?x) [?x :n/v 1]]
        ins [:db [1 "Ada" 1]]
        l->r {"g" {1 100}}]
    (testing "ordinary numbers equal to a logical id stay unchanged"
      (is (= ['[:find ?x :where (r 100 ?x) [?x :n/v 1]] [:db [100 "Ada" 1]]]
             (fx/substitute-entity-ids c q ins l->r))))
    (testing "an unannotated case is untouched"
      (is (= [q ins] (fx/substitute-entity-ids {"id" "u"} q ins l->r))))
    (testing "a position that is not an integer, or names an unmapped id, is refused"
      (is (not= ::none (thrown-message
                         #(fx/substitute-entity-ids c q [:db ["no" "Ada" 1]] l->r))))
      (is (not= ::none (thrown-message
                         #(fx/substitute-entity-ids c q ins {"g" {2 200}})))))))


(deftest the-companion-documents-exist
  (is (contains-text? (cfx/read-path fx/readme-path) "datomic-q-v1"))
  (is (contains-text? (cfx/read-path fx/errata-path) "## E1 ")))


(deftest oracle-evidence-names-its-backend-and-its-superseded-runs
  (let [prov (cfx/read-path fx/provenance-path)]
    (is (seq (filter #(= "oracle-executed" (get % "evidence")) (fx/cases)))
        "conclusive oracle outcomes exist")
    (testing "oracle-executed evidence requires recorded backend provenance"
      (is (contains-text? prov ":backend :datomic-local"))
      (is (contains-text? prov ":backend-version \"1.0.301\""))
      (is (contains-text? prov ":status :executed")))
    (testing "historical observations are preserved with the run that made them"
      (let [obs (cfx/read-path fx/observations-path)]
        (is (contains-text? obs ":run :jupiter-r1"))
        (is (contains-text? obs ":run :local-r2"))))))


;; ==========================================================================
;; Executable DaoSpace-side cases
;; ==========================================================================

(defn- norm-nested
  "Pull results: vectors that are map values are multisets (cardinality-many
   order is unspecified)."
  [x]
  (cond
    (map? x) (into {} (map (fn [[k v]]
                             [k (if (vector? v)
                                  (frequencies (map norm-nested v))
                                  (norm-nested v))]))
                   x)
    (set? x) (set (map norm-nested x))
    (vector? x) (mapv norm-nested x)
    :else x))


(defn- cell-same?
  "Value agreement across hosts: two numerics agree when they compare equal in
   exact numeric order (kind-loose, so an integral double equals the integer
   on a host that cannot tell them apart); sets and sequences agree
   structurally, everything else by =."
  [a b]
  (cond
    (and (cbor/numeric? a) (cbor/numeric? b)) (zero? (cbor/num-compare a b))
    (and (set? a) (set? b))
    ;; a BIJECTION, not mere coverage: each element of a consumes one distinct
    ;; element of b (the relation is an equivalence, so greedy matching is
    ;; exact). "every a has some match in b" would accept #{1 1.0} for #{1 2}.
    (and (= (count a) (count b))
         (loop [as (seq a)
                bs (vec b)]
           (if-not as
             true
             (let [i (first (keep-indexed (fn [i y] (when (cell-same? (first as) y) i)) bs))]
               (and i (recur (next as) (into (subvec bs 0 i) (subvec bs (inc i)))))))))
    (and (sequential? a) (sequential? b))
    (and (= (count a) (count b)) (every? true? (map cell-same? a b)))
    :else (= a b)))


(deftest numeric-value-set-agreement-is-a-bijection
  (testing "two elements of one set may not both match the same element of the other"
    (is (not (cell-same? (set [1 (cbor/float64 1)]) #{1 2}))))
  (testing "a genuine bijection across kinds agrees"
    (is (cell-same? #{1 2} (set [(cbor/float64 2) (cbor/float64 1)]))))
  (testing "sequences and nested sets stay structural"
    (is (cell-same? [#{1 2} 3] [#{2 1} (cbor/float64 3)]))
    (is (not (cell-same? [#{1 2}] [#{1 3}])))
    (is (not (cell-same? #{1} #{1 2})))))


(defn- same?
  "Apply the comparison rule named by the case."
  [rule expected actual]
  (case rule
    "exact" (= expected actual)
    "unordered-coll" (= (frequencies expected) (frequencies actual))
    "unordered-nested" (= (norm-nested expected) (norm-nested actual))
    ("row-count" "slice-size") (= expected (count actual))
    "numeric-value" (cell-same? expected actual)
    false))


(defn- run-core
  "q + collect over the case's typed query, inputs and (when present) the
   trailing option map."
  [c]
  (let [opts (fx/case-options c)]
    (query/collect (apply query/q (fx/case-query c)
                          (concat (fx/case-inputs c) (when opts [opts]))))))


(defn- runs-here?
  [c]
  (some #{host} (get c "hosts")))


(defn- run-expectation
  "Failure string, or nil, for one {kind value|refusal} expectation."
  [c rule kind expected-fn needle]
  (case kind
    "value" (let [r (try [:ok (run-core c)]
                         (catch #?(:cljd Object :clj Throwable :cljs :default) e
                           [:threw (str (ex-message e))]))]
              (cond
                (= :threw (first r)) (str "threw " (second r))
                (not (same? rule (expected-fn) (second r)))
                (str "expected " (pr-str (expected-fn)) " got " (pr-str (second r)))))
    "refusal" (let [m (thrown-message #(run-core c))]
                (cond
                  (= ::none m) "expected a refusal, query returned"
                  (not (contains-text? m needle))
                  (str "refusal message " (pr-str m) " lacks " (pr-str needle))))))


(defn- case-failure
  [c]
  (let [status (get c "status")
        rule (get c "comparison")]
    (case status
      ("executable" "difference")
      (run-expectation c rule "value" #(fx/case-expected c) nil)
      "refusal"
      (run-expectation c rule "refusal" nil (get-in c ["expect" "message-contains"]))
      "pending"
      (when-let [o (get c "daospace-observed")]
        ;; regression pin of today's core behavior, NOT a compatibility claim
        (run-expectation c rule (get o "kind") #(decode-observed o)
                         (get o "message-contains"))))))


(defn- failures-for
  [pred]
  (vec (keep (fn [c]
               (when (and (runs-here? c) (pred c))
                 (when-let [f (case-failure c)] [(get c "id") f])))
             (fx/cases))))


(deftest this-host-runs-the-cases-it-declares
  (let [mine (filter runs-here? (fx/cases))]
    (is (pos? (count (filter #(= "executable" (get % "status")) mine)))
        "executable cases are selected for this host")
    (is (pos? (count (filter #(= "refusal" (get % "status")) mine)))
        "refusal cases are selected for this host")))


(deftest executable-cases-hold-on-daospace
  (assert-none (failures-for #(= "executable" (get % "status"))) "executable"))


(deftest documented-difference-cases-hold-on-daospace
  (assert-none (failures-for #(= "difference" (get % "status"))) "difference"))


(deftest refusal-cases-are-refused-by-the-core
  (assert-none (failures-for #(= "refusal" (get % "status"))) "refusal"))


(deftest pending-cases-assert-only-their-pending-status
  (testing "a pending case claims nothing about compatibility"
    (assert-none (for [c (fx/cases)
                       :when (and (= "pending" (get c "status"))
                                  ;; an oracle outcome is Datomic's answer, not a
                                  ;; DaoSpace-side assertion; only a value expectation is
                                  (contains? (get c "expect") "value"))]
                   [(get c "id") "pending case asserts an answer"])
                 "pending claims"))
  (testing "core observations are regression pins, not compatibility claims"
    (assert-none (failures-for #(and (= "pending" (get %  "status"))
                                     (get % "daospace-observed")))
                 "core observation")))


(deftest every-status-is-exercised
  (let [by (frequencies (map #(get % "status") (fx/cases)))]
    (doseq [s ["executable" "refusal" "difference" "pending"]]
      (is (pos? (get by s 0)) (str "corpus has " s " cases")))))


(deftest every-vocabulary-comparison-is-implemented
  (is (= implemented-comparisons (vocab "comparisons"))
      "the corpus vocabulary and same? agree"))


#?(:cljd nil
   :clj
   (do
     (defn- resource-pin-holds?
       []
       (let [text (fx/read-text)]
         (and (ascii? text)
              (= resource-blake3 (jing/blake3-bytes (ascii-bytes text))))))


     ;; One run cannot both modify the resource and pass: after every test of
     ;; this namespace has run, the pin is asserted again (the CBOR precedent).
     (clojure.test/use-fixtures
       :once
       (fn [run-tests]
         (run-tests)
         (is (resource-pin-holds?)
             "datomic-q-v1.json is still the frozen resource after the whole run")))))

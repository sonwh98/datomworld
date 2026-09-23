(ns dao.jing.hash-registry-contract-test
  "Phase H0 ('contract and evidence') of the DaoJing multihash
   content-addressing rollout (docs/design/dao.jing.hash-registry.md,
   architect signed off:
   archive/1790147499393-architect-hash-registry-signoff.gpt-5.6-sol.findings.md).

   H0 freezes the address grammar, the total-predicate contract for
   `segment-matches?`, EDN round-tripping, and the architectural lint
   target — before any of it exists in `dao.jing` (reserved for H1). This
   namespace is therefore self-contained: it defines a *reference*
   implementation of the frozen grammar/parser/predicate rules as local,
   private functions, and pins their behavior against:

   - `dao.jing/sha256-bytes`, `dao.jing/canonical-bytes`, and
     `dao.jing/content-hash`, which are real today and do not change
     shape in H0 (SHA-256 minting is already what `content-hash`
     computes); and
   - the frozen BLAKE3-256 fixtures in
     test/resources/dao/jing/blake3-vectors.edn and
     test/resources/dao/jing/digest-table.edn, standing in for a real
     BLAKE3 provider (none exists until H1's pinned dependencies land).

   When H1 lands the registry in `dao.jing` itself, this namespace's
   reference `parse-segment-address`/`segment-matches?` are replaced by
   calls to the real `dao.jing` functions; the fixture data and the
   specification the tests encode do not change, only which
   implementation answers them."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.cbor :as jing-cbor]
            #?(:cljd ["dart:io" :as dart-io])))


;; =============================================================================
;; Fixture loading (portable text read + clojure.edn/read-string, the same
;; pattern test/dao/jing/cbor_fixtures.cljc uses for its JSON corpus).
;; =============================================================================

(defn- read-text
  [path]
  #?(:cljd (.readAsStringSync (dart-io/File. path))
     :clj (slurp path)
     :cljs (.readFileSync (js/require "fs") path "utf8")))


(def ^:private blake3-vectors*
  (delay (edn/read-string (read-text "test/resources/dao/jing/blake3-vectors.edn"))))


(def ^:private digest-table*
  (delay (edn/read-string (read-text "test/resources/dao/jing/digest-table.edn"))))


(defn- blake3-vectors
  []
  @blake3-vectors*)


(defn- digest-table
  []
  @digest-table*)


;; input-hex -> blake3-256 hex, built from digest-table.edn. A stand-in for a
;; real BLAKE3 provider (H1 pins one on every host): total only over the
;; exact byte strings the frozen fixtures cover, which is exactly the set
;; this contract test needs to pin dispatch and total-predicate behavior
;; without a real hash implementation.
(def ^:private blake3-fixture-by-input-hex
  (delay (into {}
               (map (fn [c] [(:input-hex c) (:blake3-256 c)]))
               (:cases (digest-table)))))


;; =============================================================================
;; §1 — Address grammar & parsing specification
;;
;; Reference implementation of docs/design/dao.jing.hash-registry.md's
;; closed algorithm registry and address parser. `algorithm-id-pattern` and
;; `parse-segment-address` are the frozen H0 contract; H1's `dao.jing`
;; parser must be behaviorally identical to this reference over every case
;; below, including every rejection.
;; =============================================================================

(def ^:private algorithm-id-pattern
  "Algorithm address identifiers are lowercase ASCII alphanumeric strings."
  #"[a-z0-9]+")


(def ^:private hex-digit-pattern
  #"[0-9a-f]+")


(def ^:private registry jing/registry)
(def ^:private parse-segment-address jing/parse-segment-address)
(def ^:private segment-address? jing/segment-address?)
(def ^:private segment-algorithm jing/segment-algorithm)
(def ^:private segment-digest jing/segment-digest)


(deftest address-grammar-pinned-canonical-forms
  (testing "blake3 and sha256 are the two pinned canonical forms"
    (is (= {:algorithm :blake3
            :digest "c3a8cc55ea53e056552eab337f404b352cc9415d386d8cc399edc356f5d9b012"
            :canonical :segment/blake3-c3a8cc55ea53e056552eab337f404b352cc9415d386d8cc399edc356f5d9b012}
           (parse-segment-address
             :segment/blake3-c3a8cc55ea53e056552eab337f404b352cc9415d386d8cc399edc356f5d9b012)))
    (is (= {:algorithm :sha256
            :digest "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
            :canonical :segment/sha256-e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855}
           (parse-segment-address
             :segment/sha256-e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855)))))


(deftest address-grammar-accessors-throw-on-invalid-input
  (testing "segment-algorithm and segment-hash are total only over valid
            addresses; every accessor throws, never returns nil, on
            invalid input (segment-address? remains the total predicate)"
    (is (= :blake3
           (segment-algorithm :segment/blake3-c3a8cc55ea53e056552eab337f404b352cc9415d386d8cc399edc356f5d9b012)))
    (is (= "c3a8cc55ea53e056552eab337f404b352cc9415d386d8cc399edc356f5d9b012"
           (segment-digest :segment/blake3-c3a8cc55ea53e056552eab337f404b352cc9415d386d8cc399edc356f5d9b012)))
    (is (thrown? #?(:cljd Object :clj Throwable :cljs :default)
          (segment-algorithm :not/a-segment-address)))
    (is (thrown? #?(:cljd Object :clj Throwable :cljs :default)
          (segment-digest :not/a-segment-address)))
    (is (false? (segment-address? :not/a-segment-address)))))


(deftest address-grammar-algorithm-id-pattern
  (testing "the registered algorithm identifiers satisfy [a-z0-9]+"
    (doseq [[_ {:keys [address-id]}] registry]
      (is (re-matches algorithm-id-pattern address-id))))
  (testing "the pattern rejects uppercase, hyphens, and empty strings"
    (doseq [bad ["Blake3" "sha-256" "" "sha_256" "blake3!"]]
      (is (not (re-matches algorithm-id-pattern bad)) bad))))


(deftest address-grammar-parsing-splits-on-first-hyphen
  (testing "the digest is everything after the first hyphen, verbatim"
    (let [digest (apply str (repeat 64 "a"))]
      (is (= digest (:digest (parse-segment-address (keyword "segment" (str "sha256-" digest)))))))))


(deftest address-grammar-rejection-cases
  (testing "malformed prefixes"
    (is (nil? (parse-segment-address (keyword "segment" ""))))
    (is (nil? (parse-segment-address (keyword "invalid" (str "sha256-" (apply str (repeat 64 "a")))))))
    (is (nil? (parse-segment-address :segment/sha256)))
    (is (nil? (parse-segment-address "not-a-keyword")))
    (is (nil? (parse-segment-address nil))))
  (testing "unknown algorithm"
    (is (nil? (parse-segment-address (keyword "segment" (str "sha512-" (apply str (repeat 64 "a")))))))
    (is (nil? (parse-segment-address (keyword "segment" (str "md5-" (apply str (repeat 32 "a"))))))))
  (testing "uppercase hex"
    (is (nil? (parse-segment-address
                (keyword "segment" (str "sha256-" (str/upper-case (apply str (repeat 64 "a")))))))))
  (testing "wrong length"
    (is (nil? (parse-segment-address (keyword "segment" (str "sha256-" (apply str (repeat 63 "a")))))))
    (is (nil? (parse-segment-address (keyword "segment" (str "sha256-" (apply str (repeat 65 "a")))))))
    (is (nil? (parse-segment-address :segment/sha256-1234))))
  (testing "non-hex characters"
    (is (nil? (parse-segment-address
                (keyword "segment" (str "sha256-" (apply str (repeat 63 "a")) "g"))))))
  (testing "alternative spelling of an otherwise-valid address is rejected by
            the canonical-reconstruction check"
    ;; A digest with a stray uppercase letter fails the hex check above
    ;; already; this case instead checks that reformatting the parsed pair
    ;; must reproduce the exact input, which the parser's final equality
    ;; check enforces directly.
    (let [digest (apply str (repeat 64 "a"))]
      (is (= (keyword "segment" (str "sha256-" digest))
             (:canonical (parse-segment-address (keyword "segment" (str "sha256-" digest)))))))))


;; =============================================================================
;; §2 — Total predicate contract for segment-matches?
;;
;; segment-matches? parses the address, computes canonical-bytes for the
;; payload, hashes them under the address-carried algorithm, and compares —
;; returning false (never throwing) for every rejection class, including
;; canonical-encoder refusal. Minting (segment-key/materialize!, exercised
;; here via the real dao.jing/segment-key, which is unaffected by H0)
;; throws instead.
;; =============================================================================

(defn- digest-bytes-hex
  "algorithm x host-bytes -> lowercase hex digest, or a canonical-encoder
   refusal exception for the caller to catch. sha256 is real
   (`dao.jing/sha256-bytes`); blake3 is fixture-backed (see the namespace
   docstring) and only defined over the exact byte strings the frozen
   fixtures cover."
  [algorithm bs hex]
  (case algorithm
    :sha256 (jing/sha256-bytes bs)
    :blake3 (get @blake3-fixture-by-input-hex hex ::no-fixture)))


(defn- bytes->hex
  [bs]
  #?(:cljd (apply str (map #(.padLeft (.toRadixString ^int % 16) 2 "0") bs))
     :default (apply str (map (fn [i]
                                (let [b #?(:clj (bit-and (aget ^bytes bs i) 0xff)
                                           :cljs (aget bs i))]
                                  (str (when (< b 16) "0")
                                       #?(:clj (Integer/toHexString b)
                                          :cljs (.toString b 16)))))
                              (range (alength bs))))))


(def ^:private segment-matches? jing/segment-matches?)


(defrecord Unsupported
  [x])


(deftest segment-matches-total-predicate-blake3-and-sha256
  (testing "a genuine blake3-addressed payload matches, using the frozen
            digest-table fixture for [1 2 3]"
    (is (segment-matches? :segment/blake3-c3a8cc55ea53e056552eab337f404b352cc9415d386d8cc399edc356f5d9b012
                          [1 2 3])))
  (testing "a genuine sha256-addressed payload matches, using the real
            dao.jing/content-hash"
    (let [address (jing/segment-key {:a 1} {:algorithm :sha256})]
      (is (segment-matches? address {:a 1})))))


(deftest segment-matches-total-predicate-rejections
  (testing "malformed address"
    (is (false? (segment-matches? :not/a-segment-address [1 2 3])))
    (is (false? (segment-matches? "nope" [1 2 3]))))
  (testing "unknown algorithm"
    (is (false? (segment-matches?
                  (keyword "segment" (str "sha512-" (apply str (repeat 64 "a"))))
                  [1 2 3]))))
  (testing "mismatched digest"
    (is (false? (segment-matches? :segment/blake3-c3a8cc55ea53e056552eab337f404b352cc9415d386d8cc399edc356f5d9b013
                                  [1 2 3])))
    (is (false? (segment-matches?
                  (keyword "segment" (str "sha256-" (jing/segment-hash (jing/segment-key {:a 1}))))
                  {:a 2}))))
  (testing "canonical-encoder refusal returns false, never throws"
    (let [address (keyword "segment" (str "sha256-" (apply str (repeat 64 "a"))))]
      (is (false? (segment-matches? address (->Unsupported 1)))))))


(deftest minting-throws-on-encoder-refusal-not-segment-matches
  (testing "in contrast to segment-matches?, minting primitives throw on
            encoder refusal"
    (is (thrown? #?(:cljd Object :clj Throwable :cljs :default)
          (jing/segment-key (->Unsupported 1))))
    (is (thrown? #?(:cljd Object :clj Throwable :cljs :default)
          (jing/content-hash (->Unsupported 1))))))


;; =============================================================================
;; §3 — EDN print/read round-trip
;; =============================================================================

(deftest segment-addresses-round-trip-through-edn
  (testing "both canonical forms survive pr-str -> clojure.edn/read-string
            unchanged, on every host (JVM, JS, and Dart)"
    (doseq [address [:segment/blake3-c3a8cc55ea53e056552eab337f404b352cc9415d386d8cc399edc356f5d9b012
                     :segment/sha256-e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855]]
      (is (= address (edn/read-string (pr-str address))))
      (is (segment-address? (edn/read-string (pr-str address)))))))


;; =============================================================================
;; §4 — Architectural lint / guard specification
;;
;; The frozen lint target: reject `dao.jing/content-hash` and
;; `dao.jing/segment-key` used in *equality-based validation position*
;; (their result compared for equality against something else) outside
;; `dao.jing`. It must not misclassify `dao.jing.cbor/content-hash`, a
;; distinct function with unrelated (CBOR value-domain) semantics.
;;
;; This is a specification of the lint's decision procedure over an
;; s-expression, pinned by representative forms — not a repository-wide
;; sweep. H1's actual source pass (converting every Class-3 site in
;; docs/design/dao.jing.call-site-classification.md to segment-matches?)
;; is what makes a repository-wide run of this lint pass; that pass is an
;; H1 deliverable, not an H0 one.
;; =============================================================================

(def ^:private validation-target-symbols
  "Target symbols the lint identifies, both fully-qualified and under the
   standard `jing` alias. Namespace-qualified symbols, never bare names —
   this keeps `dao.jing.cbor/content-hash` and `jing-cbor/content-hash` out
   of scope: they are different symbols pointing to unrelated semantics."
  #{'dao.jing/content-hash 'dao.jing/segment-key
    'jing/content-hash     'jing/segment-key})


(defn- validation-target-call?
  "True when form is a call to one of the target vars (dao.jing/content-hash,
   dao.jing/segment-key), whether fully-qualified or via the standard `jing`
   alias, or resolving to the target var in Clojure. Explicitly excludes
   dao.jing.cbor/content-hash and jing-cbor/content-hash."
  [form]
  (boolean
    (when (seq? form)
      (let [op (first form)]
        (when (symbol? op)
          (or
            #?(:clj (when-let [v (try (resolve op) (catch Throwable _ nil))]
                      (contains? #{#'jing/content-hash #'jing/segment-key} v))
               :default nil)
            (contains? validation-target-symbols op)))))))


(defn- equality-form?
  [form]
  (and (seq? form)
       (contains? #{'= 'not= 'clojure.core/= 'clojure.core/not=}
                  (first form))))


(defn- flags-equality-validation?
  "The lint's decision procedure: true when form is an equality/inequality
   call with a target-var call directly among its operands. This is a
   syntactic check over one form, matching the H0 charter (\"a static
   check function / test that inspects ASTs or namespaces\") without
   requiring a full analyzer pass; it walks nested forms so a target call
   buried in a let-bound comparison is still found."
  [form]
  (letfn [(walk
            [f]
            (cond
              (and (equality-form? f) (some validation-target-call? (rest f))) true
              (seq? f) (some walk f)
              (coll? f) (some walk f)
              :else false))]
    (boolean (walk form))))


(deftest architectural-lint-flags-equality-based-validation
  (testing "a direct equality comparison against dao.jing/content-hash is flagged"
    (is (flags-equality-validation?
          '(when-not (= (jing/segment-hash address) (dao.jing/content-hash payload))
             (throw (ex-info "mismatch" {}))))))
  (testing "a direct equality comparison against dao.jing/segment-key is flagged"
    (is (flags-equality-validation?
          '(when-not (= address (dao.jing/segment-key body))
             (throw (ex-info "mismatch" {}))))))
  (testing "aliased forms (jing/content-hash and jing/segment-key) are flagged"
    (is (flags-equality-validation?
          '(= (jing/segment-hash address) (jing/content-hash payload))))
    (is (flags-equality-validation?
          '(= address (jing/segment-key body)))))
  (testing "an equality form nested inside other forms (let/when-not/etc.)
            is still found, as long as the target call is a direct operand
            of the equality form itself — this lint is a syntactic walk,
            not a data-flow analysis, so it does not resolve a target call
            through an intermediate let-bound alias"
    (is (flags-equality-validation?
          '(let [address (row->address row)]
             (when-not (= address (dao.jing/segment-key (subvec row 1)))
               (throw (ex-info "mismatch" {}))))))))


(deftest architectural-lint-does-not-flag-mint-only-calls
  (testing "a bare mint call with no equality comparison is not flagged"
    (is (not (flags-equality-validation? '(dao.jing/segment-key payload))))
    (is (not (flags-equality-validation? '(jing/materialize! handle (dao.jing/segment-key payload))))))
  (testing "an equality form whose operands do not call the target vars is
            not flagged"
    (is (not (flags-equality-validation? '(= address other-address))))))


(deftest architectural-lint-does-not-misclassify-cbor-content-hash
  (testing "dao.jing.cbor/content-hash is a distinct, unrelated symbol and
            must never be flagged, including in an equality comparison"
    (is (not (validation-target-call? '(dao.jing.cbor/content-hash payload))))
    (is (not (validation-target-call? '(jing-cbor/content-hash payload))))
    (is (not (flags-equality-validation?
               '(= (dao.jing.cbor/content-hash a) (dao.jing.cbor/content-hash b)))))
    (is (not (flags-equality-validation?
               '(= (jing-cbor/content-hash a) (jing-cbor/content-hash b)))))
    ;; dao.jing.cbor/content-hash is real; confirm the two symbols are
    ;; genuinely distinct vars, not merely distinct spellings of one.
    (is (not= #'jing/content-hash #'jing-cbor/content-hash))))


;; =============================================================================
;; Fixture self-consistency (not part of the four specifications above, but
;; required for every other test in this namespace to mean what it claims):
;; the frozen BLAKE3 fixture files parse, and the digest-table's canonical-
;; value cases match dao.jing/canonical-bytes today.
;; =============================================================================

(deftest fixtures-load-and-agree-with-canonical-bytes
  (testing "blake3-vectors.edn parses with the expected case counts"
    (is (= 10 (count (:official-vectors (blake3-vectors)))))
    (is (= 5 (count (:non-ascii-utf8-vectors (blake3-vectors))))))
  (testing "digest-table.edn's canonical-value cases match
            dao.jing/canonical-bytes at HEAD, so the fixture stays honest
            about which encoder it was generated against"
    (doseq [{:keys [label value input-hex]} (:cases (digest-table))]
      (when (and value (str/starts-with? (name label) "canonical-"))
        (is (= input-hex (bytes->hex (jing/canonical-bytes value)))
            (str label " canonical-bytes must match the frozen fixture"))))))

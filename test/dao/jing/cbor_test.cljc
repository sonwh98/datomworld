(ns dao.jing.cbor-test
  "`dao.jing.cbor` against the frozen corpus (phase J1, JVM and Node).

   Every corpus case runs on each host: canonical inputs encode to the
   frozen hex and digest and the frozen hex decodes back to an equal value;
   encode and decode refusals raise the frozen class. A case is skipped only
   by id, with its reason, and only where the README (or a recorded host
   limitation reported with this phase) says the host cannot build the
   input. The remaining tests prove the effective Boring options, byte
   isolation, independence from print settings, and the portable numeric
   operations."
  (:require [clojure.test :refer [deftest is testing]]
            #?@(:cljd []
                :default [[boring.core :as boring]
                          [dao.jing :as jing]
                          [dao.jing.cbor :as cbor]
                          [dao.jing.cbor.boring :as cbor-boring]
                          [dao.jing.cbor-fixtures :as fx]])))


#?(:cljd nil
   :default
   (do

     (def host
       #?(:clj :jvm :cljs :node))


     (def skip-by-id
       "Cases this host cannot build at all, with the reason."
       {:jvm {"num/js-unsafe-integer"
              "README Obligations: applies to Node only; N/A on JVM and Dart"}
        :node {"host/character"
               "ClojureScript has no character type: a char literal is a string"
               "host/nested-in-vector"
               "its unsupported member is a character; ClojureScript has none"
               "coll/map-both-slash-keywords"
               (str "ClojureScript keyword equality compares the joined name, so"
                    " (keyword nil \"a/b\") and (keyword \"a\" \"b\") are one map"
                    " key: the host cannot hold this value (reported as a"
                    " J1 finding)")}})


     (def collapse-not-applicable
       "Encode-side collapse and duplicate refusals whose members this host's
        constructor was observed to merge (cbor-v1.errata.md corrects the
        README table this way). Such a case is skipped only when the host
        really merged the members; a case not listed here must refuse, so a
        regression that starts merging fails instead of skipping."
       {:jvm #{"coll/set-signed-zero-collapse" "coll/set-decimal-scale-collapse"
               "coll/set-vector-list-collapse" "coll/map-integer-width-duplicate"}
        :node #{"coll/set-vector-list-collapse" "coll/set-nan-payload-duplicate"
                "coll/map-nan-payload-duplicate"}})


     (defn- skip!
       [id reason]
       (println (str "SKIP " (name host) " " id ": " reason)))


     (defn- refusal-of
       "The refusal class keyword f throws, ::none, or [::other message]."
       [f]
       (try (f)
            ::none
            (catch #?(:clj Throwable :cljs :default) e
              (or (cbor/refusal e)
                  [::other #?(:clj (str (type e) " " (ex-message e))
                              :cljs (str e))]))))


     (defn- guarded
       "Run one case, reporting an unexpected throw as a failure of that case
        rather than aborting the whole corpus loop."
       [id f]
       (try (f)
            (catch #?(:clj Throwable :cljs :default) e
              (is false (str id " threw " (ex-message e))))))


     (defn- hex-of
       [bs]
       (fx/bytes->hex bs))


     ;; ==========================================================================
     ;; The corpus
     ;; ==========================================================================

     (deftest canonical-cases-encode-and-round-trip
       (doseq [c (fx/cases-of-kind "canonical")
               :let [id (get c "id")
                     skip (get-in skip-by-id [host id])]]
         (if skip
           (skip! id skip)
           (guarded
             id
             #(testing id
                (let [v (fx/input->value (get c "input"))
                      bs (cbor/encode v)]
                  (is (= (get c "hex") (hex-of bs)) "encodes to the frozen bytes")
                  (is (= (get c "sha256") (jing/sha256-bytes bs)) "frozen digest")
                  (let [back (cbor/decode (fx/hex->bytes (get c "hex")))]
                    (is (= (get c "hex") (hex-of (cbor/encode back)))
                        "decoded value re-encodes to the frozen bytes")
                    (is (cbor/equiv v back) "decoded value equals the input"))))))))


     (deftest encode-refusal-cases-refuse-with-the-frozen-class
       (doseq [c (fx/cases-of-kind "encode-refusal")
               :let [id (get c "id")
                     skip (get-in skip-by-id [host id])]]
         (if skip
           (skip! id skip)
           (guarded
             id
             #(let [input (get c "input")
                    built (refusal-of (fn [] (fx/input->value input)))
                    v (when (= ::none built) (fx/input->value input))
                    n (fx/dsl-member-count input)]
                (cond
                  ;; The portable constructors are part of the codec: a
                  ;; ratio with denominator 0 is refused when it is built.
                  (not= ::none built)
                  (is (= (keyword (get c "refusal")) built) id)

                  (not (fx/constructible? v))
                  (is false (str id ": not constructible here and not listed as skipped"))

                  (and n (< (count v) n))
                  (if (contains? (collapse-not-applicable host) id)
                    (skip! id "host constructor merged the members (N/A; cbor-v1.errata.md E1)")
                    (is false (str id ": host merged members; not an N/A row for " (name host))))

                  :else
                  (is (= (keyword (get c "refusal")) (refusal-of (fn [] (cbor/encode v))))
                      id)))))))


     (deftest decode-refusal-cases-refuse-with-the-frozen-class
       (doseq [c (fx/cases-of-kind "decode-refusal")
               :let [id (get c "id")]]
         (guarded
           id
           #(is (= (keyword (get c "refusal"))
                   (refusal-of (fn [] (cbor/decode (fx/hex->bytes (get c "hex"))))))
                id))))


     (deftest groups-hold-on-produced-bytes
       (let [produced (into {}
                            (keep (fn [c]
                                    (let [id (get c "id")]
                                      (when-not (get-in skip-by-id [host id])
                                        [id (hex-of (cbor/encode
                                                      (fx/input->value (get c "input"))))]))))
                            (fx/cases-of-kind "canonical"))
             cases (filter #(contains? produced (get % "id")) (fx/cases-of-kind "canonical"))]
         (doseq [[g members] (group-by #(get % "equivalence") cases)
                 :when g]
           (is (= 1 (count (set (map #(produced (get % "id")) members)))) g))
         (doseq [[g members] (reduce (fn [acc c]
                                       (reduce #(update %1 %2 (fnil conj []) c)
                                               acc (get c "distinct")))
                                     {} cases)]
           (is (= (count members) (count (set (map #(produced (get % "id")) members))))
               g))))


     #?(:cljs
        (deftest cljs-cannot-hold-slash-crossed-identifier-keys
          ;; Evidence for the coll/map-both-slash-keywords skip: ClojureScript
          ;; keyword equality compares the joined name, so the host merges the
          ;; two keys, and decode refuses rather than silently losing one.
          (let [c (fx/case-by-id "coll/map-both-slash-keywords")]
            (is (= 1 (count (fx/input->value (get c "input")))))
            (is (= (symbol nil "a/b") (symbol "a" "b"))
                "cbor-v1.errata.md E2: ClojureScript symbols also compare by joined name")
            (is (= :host-collapse
                   (refusal-of #(cbor/decode (fx/hex->bytes (get c "hex")))))))))


     (deftest slash-crossed-symbol-set-is-held-or-refused-never-merged
       ;; #{(symbol "a" "b") (symbol nil "a/b")}: canonical bytes, distinct
       ;; under portable equality. The JVM holds both; ClojureScript cannot
       ;; and refuses host-collapse (cbor-v1.errata.md E2), never losing one.
       (let [frame "d81b826f64616f2e6a696e672f73796d626f6c82"
             hex (str "d9010282" frame "61616162" frame "f663612f62")]
         (is (= #?(:clj ::none :cljs :host-collapse)
                (refusal-of #(cbor/decode (fx/hex->bytes hex)))))))


     ;; ==========================================================================
     ;; Effective Boring options and one-item payloads
     ;; ==========================================================================

     (defn- concat-bytes
       [a b]
       (fx/hex->bytes (str (hex-of a) (hex-of b))))


     (deftest boring-profile-options-are-locked
       (is (= {:profile :canonical :stringref false :shapes false} cbor-boring/opts))
       (is (thrown? #?(:clj Exception :cljs :default)
             (boring/encode 1 (assoc cbor-boring/opts :stringref true)))
           "stringref cannot be switched on under :canonical")
       (is (thrown? #?(:clj Exception :cljs :default)
             (boring/encode 1 (assoc cbor-boring/opts :shapes true)))
           "shapes cannot be switched on under :canonical"))


     (deftest payloads-are-one-item-then-end
       (let [v {:xs ["abc" "abc" "abc"] :m [{:a 1 :b 2} {:a 3 :b 4}]}
             bs (cbor/encode v)
             h (hex-of bs)]
         (is (cbor/equiv v (cbor/decode bs)))
         (is (not (re-find #"d90100|d819" h)) "no stringref tags")
         (is (= :trailing-data (refusal-of #(cbor/decode (concat-bytes bs bs)))))
         (is (= :trailing-data
                (refusal-of #(cbor/decode (concat-bytes bs (cbor/encode {"index" 0})))))
             "an index-like frame after the item is trailing data, never skipped")))


     ;; ==========================================================================
     ;; Byte isolation and print independence
     ;; ==========================================================================

     (defn- set-byte!
       [bs i v]
       #?(:clj (aset-byte bs i (unchecked-byte v))
          :cljs (aset bs i v)))


     (deftest byte-strings-are-isolated-from-mutation
       (let [input (fx/hex->bytes "0102030405")
             encoded (cbor/encode input)
             before (hex-of encoded)]
         (set-byte! input 0 9)
         (is (= before (hex-of encoded)) "mutating the input leaves the payload")
         (is (= before (hex-of (cbor/encode (fx/hex->bytes "0102030405")))))
         (let [decoded (cbor/decode encoded)]
           (set-byte! decoded 1 9)
           (is (= "0102030405" (hex-of (cbor/decode encoded)))
               "mutating a decoded array leaves the payload and later decodes")
           (let [again (cbor/decode encoded)]
             (set-byte! encoded 5 9)
             (is (= "0102030405" (hex-of again))
                 "mutating the payload leaves an earlier decoded array")))))


     (deftest encoding-ignores-ambient-print-settings
       (let [cases (fx/cases-of-kind "canonical")]
         (doseq [c cases
                 :let [id (get c "id")]
                 :when (not (get-in skip-by-id [host id]))]
           (let [v (fx/input->value (get c "input"))]
             (is (= (get c "hex")
                    (hex-of (binding [*print-length* 1
                                      *print-level* 1
                                      *print-meta* true
                                      #?@(:clj [*print-dup* true
                                                *print-namespace-maps* true])]
                              (cbor/encode v))))
                 id)))))


     ;; ==========================================================================
     ;; Portable numeric operations
     ;; ==========================================================================

     (defn- big
       [s]
       #?(:clj (bigint s) :cljs (js/BigInt s)))


     (def ones
       [1 (big "1") (cbor/float64 1) (cbor/decimal 0 1) (cbor/decimal -1 10)
        (cbor/decimal -2 100) (cbor/ratio 1 1)])


     (deftest equal-values-compare-zero-and-hash-alike-both-ways
       (doseq [a ones b ones]
         (is (cbor/num= a b) (pr-str [a b]))
         (is (zero? (cbor/num-compare a b)) (pr-str [a b]))
         (is (= (cbor/num-hash a) (cbor/num-hash b)) (pr-str [a b])))
       (let [zeros [0 (cbor/float64 0) (cbor/float64 -0.0) (cbor/decimal -3 0)
                    (cbor/ratio 0 5)]]
         (doseq [a zeros b zeros]
           (is (cbor/num= a b) (pr-str [a b]))
           (is (= (cbor/num-hash a) (cbor/num-hash b))))))


     (deftest order-is-exact-and-total
       (let [nan (cbor/float64-from-bits "7ff8000000000000")
             ladder [(cbor/float64-from-bits "fff0000000000000")
                     (cbor/float64 -1e308)
                     (big "-100000000000000000000000")
                     -1
                     (cbor/ratio -1 3)
                     0
                     (cbor/ratio 1 10)
                     (cbor/float64 0.1)
                     (cbor/decimal -1 2)
                     1
                     (big "9007199254740992")
                     (big "9007199254740993")
                     (cbor/float64 1e308)
                     (cbor/float64-from-bits "7ff0000000000000")
                     nan]]
         (doseq [[i a] (map-indexed vector ladder)
                 [j b] (map-indexed vector ladder)]
           (is (= (compare i j) (cbor/num-compare a b)) (pr-str [a b])))
         (is (cbor/num= nan (cbor/float64-from-bits "7ff8000000000001")))
         (is (not (cbor/num= (cbor/float64 0.1) (cbor/ratio 1 10)))
             "0.1 as a float is its exact binary value, not one tenth")
         (is (cbor/num= (cbor/decimal -1 1) (cbor/ratio 1 10)))
         (is (= 1 (cbor/num-compare (big "9007199254740993")
                                    (cbor/float64 9007199254740992)))
             "no rounding through double")))


     (deftest kinds-survive-encoding
       (doseq [[a b] [[1 (cbor/float64 1)] [1 (cbor/ratio 1 1)]
                      [(cbor/decimal 0 1) (cbor/decimal -1 10)]
                      [(cbor/float64 0) (cbor/float64 -0.0)]]]
         (is (cbor/num= a b))
         (is (not= (hex-of (cbor/encode a)) (hex-of (cbor/encode b)))
             "numeric equality never merges content identity")
         (is (= (hex-of (cbor/encode b)) (hex-of (cbor/encode (cbor/decode (cbor/encode b))))))))


     (deftest collections-recurse-through-portable-equality
       (let [a [1 {:k (cbor/float64 1)} #{(cbor/decimal -1 10)}]
             b (list (cbor/ratio 1 1) {:k 1} #{(cbor/decimal 0 1)})]
         (is (cbor/equiv a b))
         (is (cbor/equiv b a))
         (is (= (cbor/equiv-hash a) (cbor/equiv-hash b))))
       (is (not (cbor/equiv [1] [2])))
       (is (not (cbor/equiv (keyword nil "a/b") (keyword "a" "b")))
           "identifiers compare by their fields")
       (is (not (cbor/equiv "1" 1))))


     ;; ==========================================================================
     ;; Hardening: nesting depth, the decimal exponent window, number guards
     ;; ==========================================================================

     (defn- nested-vector
       "k vectors nested inside each other: CBOR depth k."
       [k]
       (reduce (fn [acc _] [acc]) [] (range (dec k))))


     (defn- nested-list
       "k lists nested inside each other: CBOR depth 3k."
       [k]
       (reduce (fn [acc _] (list acc)) () (range (dec k))))


     (def ^:private list-open
       "tag 27, [\"dao.jing/list\" ...: the bytes opening one list frame."
       "d81b826d64616f2e6a696e672f6c697374")


     (defn- nested-list-hex
       "The bytes of k list frames nested inside each other."
       [k]
       (str (apply str (repeat (dec k) (str list-open "81"))) list-open "80"))


     (defn- nested-array-hex
       "The bytes of k arrays nested inside each other."
       [k]
       (str (apply str (repeat (dec k) "81")) "80"))


     (deftest nesting-depth-is-bounded-on-encode-and-decode
       (let [limit cbor/max-depth]
         (is (= 128 limit))
         (testing "arrays: exactly max-depth is accepted, one more is refused"
           (is (= ::none (refusal-of #(cbor/encode (nested-vector limit)))))
           (is (= :unsupported-value
                  (refusal-of #(cbor/encode (nested-vector (inc limit))))))
           (is (= ::none (refusal-of #(cbor/decode (fx/hex->bytes (nested-array-hex limit))))))
           (is (= :malformed-cbor
                  (refusal-of #(cbor/decode (fx/hex->bytes (nested-array-hex (inc limit))))))))
         (testing "list frames cost three levels: 42 reach 126, 43 reach 129"
           (is (= ::none (refusal-of #(cbor/decode (cbor/encode (nested-list 42))))))
           (is (= (nested-list-hex 42) (hex-of (cbor/encode (nested-list 42)))))
           (is (= :unsupported-value (refusal-of #(cbor/encode (nested-list 43)))))
           (is (= :malformed-cbor
                  (refusal-of #(cbor/decode (fx/hex->bytes (nested-list-hex 43)))))))
         (testing "hostile nesting is a refusal, never a stack overflow"
           (is (= :malformed-cbor
                  (refusal-of #(cbor/decode (fx/hex->bytes (nested-array-hex 10000))))))
           (is (= :malformed-cbor
                  (refusal-of #(cbor/decode (fx/hex->bytes (nested-list-hex 10000))))))
           (is (= :unsupported-value (refusal-of #(cbor/encode (nested-vector 10000)))))
           (is (= :unsupported-value (refusal-of #(cbor/encode (nested-list 10000))))))))


     (deftest decimal-exponent-window-is-host-independent
       (is (= [-2147483647 2147483648]
              [cbor/decimal-exponent-min cbor/decimal-exponent-max]))
       ;; tag 4 [exponent 1] with the exponent just inside and just outside
       ;; the window; every host must give these same outcomes.
       (doseq [[hex expected] [["c4821a8000000001" ::none]
                               ["c4821a8000000101" :malformed-number]
                               ["c4823a7ffffffe01" ::none]
                               ["c4823a7fffffff01" :malformed-number]]]
         (is (= expected (refusal-of #(cbor/decode (fx/hex->bytes hex)))) hex))
       (is (= :malformed-number (refusal-of #(cbor/decimal 2147483649 1))))
       (is (= :malformed-number (refusal-of #(cbor/decimal -2147483648 1)))))


     (deftest numeric-guards-refuse-instead-of-rounding
       #?(:cljs (let [unsafe (js/Number "9007199254740993")]
                  (is (= :unsupported-value (refusal-of #(cbor/num= unsafe 1))))
                  (is (= :unsupported-value (refusal-of #(cbor/num-hash unsafe))))
                  (is (= :unsupported-value (refusal-of #(cbor/num-compare 1 unsafe))))))
       (is (= :unsupported-value (refusal-of #(cbor/ratio 1.5 2))))
       (is (= :unsupported-value (refusal-of #(cbor/decimal 0 2.5)))))


     (deftest hand-built-unreduced-rational-behaves-as-reduced
       (let [r (cbor/->Rational (big "2") (big "4"))
             half (cbor/ratio 1 2)]
         (is (cbor/num= r half))
         (is (= (cbor/num-hash r) (cbor/num-hash half)))
         (is (zero? (cbor/num-compare r half)))
         (is (= "d81e820102" (hex-of (cbor/encode r))))))


     (deftest keywords-cannot-carry-metadata
       ;; Why encode has no metadata arm for keywords: neither host allows it.
       (is (thrown? #?(:clj Throwable :cljs :default)
             (with-meta :k {:doc "d"}))))))

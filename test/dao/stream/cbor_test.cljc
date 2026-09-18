(ns dao.stream.cbor-test
  "The `dao.stream.cbor` portable domain and wire bytes (JVM and ClojureScript).

   The frozen fixtures are cross-host contract: every hex string below was
   produced by pinned Boring on the JVM, and every host this test runs on
   (JVM, ClojureScript, and the ClojureDart twin of this file) must decode
   it to the paired value and re-encode that value to the identical bytes.
   The pairs deliberately include the cases where canonical orderings can
   drift: bytewise map-key order where it disagrees with length-first
   (1000 before \"a\"), set elements across types, shortest float widths
   (f9 for 1.5 and 5.5, fb for 1.1 and 1.0e-3), and the tag-258 / tag-27 /
   tag-39 carrier frames."
  (:require [clojure.test :refer [deftest is]]
            [dao.stream.cbor :as cbor]
            [dao.stream.transit :as transit]))


#?(:cljd nil
   :default
   (do


     (defn- to-hex
       [bytes]
       #?(:clj (apply str (map #(format "%02x" %) bytes))
          :cljs (apply str (map (fn [b]
                                  (let [h (.toString b 16)]
                                    (if (< b 16) (str "0" h) h)))
                                bytes))))


     (defn- from-hex
       [hex]
       #?(:clj (byte-array
                 (map (comp unchecked-byte #(Integer/parseInt % 16))
                      (map #(subs hex % (+ % 2)) (range 0 (count hex) 2))))
          :cljs (js/Uint8Array.from
                  (map #(js/parseInt (subs hex % (+ % 2)) 16)
                       (range 0 (count hex) 2)))))


     (def reader-meta-value
       (with-meta [1 :two (with-meta (list :a {:b #{1 2}}) {:line 7 :col 9})]
         {:reader/pos [12 34]}))


     (def fixtures
       "Frozen cross-host bytes: [hex expected-value]."
       [["a5d827683a77732f686f7374693132372e302e302e31d827683a77732f70617468692f79696e2f7265706cd827683a77732f706f72741923ded827703a64616f2e73747265616d2f74797065d8276e3a64616f2e73747265616d2f7773d827743a64616f2e73747265616d2f6964656e74697479696c6f676963616c2d31"
         {:dao.stream/type :dao.stream/ws :dao.stream/identity "logical-1"
          :ws/host "127.0.0.1" :ws/port 9182 :ws/path "/yin/repl"}]

        ["a2d827693a77732f6672616d65d827693a77732f76616c7565d827693a77732f76616c7565f6"
         {:ws/frame :ws/value :ws/value nil}]

        ["a5d827643a657870fb3f50624dd2f1a9fcd827643a696e7400d827643a6e65673b001ffffffffffffed827653a68616c66f93e00d827653a77696465fb3ff199999999999a"
         {:int 0 :neg -9007199254740991 :half 1.5 :wide 1.1 :exp 1.0e-3}]

        ["86f56374776fd827663a7468726565d82764666f757281f94580d90102820607"
         [true "two" :three 'four [5.5] #{6 7}]]

        ["d81b8271636c6f6a7572652f776974682d6d65746182a1d8276b3a7265616465722f706f73820c18228301d827643a74776fd81b826f64616f2e73747265616d2f6c69737482a2d827643a636f6c09d827653a6c696e650782d827623a61a1d827623a62d90102820102"
         reader-meta-value]

        ;; Reader positions are user data on every host: this exact key set rides
        ;; the wire unchanged on a symbol and a list alike (ClojureDart's compiler
        ;; metadata carries a Dart Type under :tag and is stripped; this does not).
        ["d81b8271636c6f6a7572652f776974682d6d65746182a4d827653a6c696e65184dd827673a636f6c756d6e09d827693a656e642d6c696e65184dd8276b3a656e642d636f6c756d6e0ad82763706f73"
         (with-meta (symbol "pos") {:line 77 :column 9 :end-line 77 :end-column 10})]

        ["d81b826f64616f2e73747265616d2f6c69737482a4d827653a6c696e65184dd827673a636f6c756d6e09d827693a656e642d6c696e65184dd8276b3a656e642d636f6c756d6e0a81d827643a706f73"
         (with-meta (list :pos) {:line 77 :column 9 :end-line 77 :end-column 10})]

        ;; Bytewise key order: 1000 (0x19...) sorts before "a" (0x61...) though
        ;; "a" is the shorter encoding. Length-first order would swap the pairs.
        ["a21903e861786161d827623a79"
         {1000 "x" "a" :y}]

        ["d901028401026162d827623a61"
         #{2 "b" :a 1}]])


     (deftest profile-is-a-binary-codec-profile
       (is (= "dao.stream.cbor" (:ws/subprotocol cbor/profile)))
       (is (= :binary (:ws/frame-kind cbor/profile)))
       (is (fn? (:ws/portable-value? cbor/profile)))
       (is (fn? (:ws/encode cbor/profile)))
       (is (fn? (:ws/decode cbor/profile))))


     (deftest frozen-fixtures-decode-and-re-encode-identically
       (doseq [[hex expected] fixtures]
         (let [bytes (from-hex hex)]
           (is (= expected (cbor/decode bytes))
               (str "decode drifted for " (pr-str expected)))
           (is (= hex (to-hex (cbor/encode expected)))
               (str "encode drifted for " (pr-str expected))))))


     (deftest round-trips-preserve-collection-distinctions-and-metadata
       (let [corpus [nil true false "" "s" :k :n/k 's 'n/s 0 -1 9007199254740991
                     1.5 1.1 -0.0
                     [] #{} {} (list)
                     [1 [2 [3]]] #{1 #{2}} {:a {:b {:c 1}}}
                     (list 1 (list 2))
                     {:kw [1] 'sym #{:x} "s" {:n [2]}}
                     reader-meta-value
                     (with-meta {:a 1} {:m {:line 3}})
                     (with-meta #{'x} {:s 1})
                     (with-meta 'q/w {:r 2})]]
         (doseq [value corpus]
           (let [back (cbor/decode (cbor/encode value))]
             (is (= value back) (str "round trip drifted for " (pr-str value)))
             (is (= (meta value) (meta back))
                 (str "metadata drifted for " (pr-str value)))))))


     (deftest list-and-vector-stay-distinct-through-the-wire
       (let [back (cbor/decode (cbor/encode [(list 1 2) (vector 1 2)]))]
         (is (list? (first back)))
         (is (vector? (second back))
             "the dao.stream/list frame keeps a list a list beside an equal vector")
         (is (= back [(list 1 2) [1 2]]))
         (is (double? (first (cbor/decode (cbor/encode [5.5]))))
             "a half-precision float decodes as the domain's double, not a Float")))

     (deftest encoding-is-deterministic
       (let [value {:z [#{1 2} (list 3)] :a reader-meta-value :m {1000 "x" "a" :y}}]
         (is (= (to-hex (cbor/encode value)) (to-hex (cbor/encode value))))))


     (deftest out-of-domain-values-are-refused-before-the-wire
       (let [rejects [9007199254740992            ; beyond the safe bound
                      -9007199254740992
                      ##NaN
                      ##Inf
                      (keyword "a/b" "c")        ; embedded slash cannot round-trip
                      (symbol "x" "")
                      (keyword ":lead")
                      (symbol "" "n")
                      (keyword "n" "")
                      (with-meta [1] {:k #?(:clj (Object.) :cljs (js-obj))})
                      #?(:clj (Object.) :cljs (js-obj))]]
         (doseq [value rejects]
           (is (not (cbor/portable-value? value))
               (str "domain admitted " (pr-str value)))
           (is (thrown? #?(:clj Exception :cljs :default)
                 (cbor/encode value))
               (str "encode accepted " (pr-str value))))))


     (deftest non-canonical-and-malformed-frames-are-refused
       (let [rejects ["0102"                     ; trailing second item
                      "ff"                       ; break-stop alone
                      "a201020103"               ; duplicate map keys
                      "9f01ff"                   ; indefinite-length array
                      "1801"                     ; non-minimal integer encoding
                      "fa3fc00000"               ; float32 1.5 where f9 3e00 suffices
                      "d93e7101"                 ; unknown tag 15985
                      "a1d81b82786e6f745f696e5f70726f66696c6501" ; unknown frame name
                      "a1"]]                     ; truncated map
         (doseq [hex rejects]
           (is (thrown? #?(:clj Exception :cljs :default)
                 (cbor/decode (from-hex hex)))
               (str "decode accepted " hex)))))


     (deftest transit-domain-stays-a-subset-of-the-cbor-domain
       ;; The Transit profile's public predicate states the same base domain; the
       ;; CBOR profile is that domain plus metadata. Every Transit-portable value
       ;; the transport already carries must therefore be cbor-portable too.
       (let [base [nil true "s" :k 'k 1 -1 1.5 {:a [1] :b #{2}}]]
         (doseq [value base]
           (is (transit/portable-value? value))
           (is (cbor/portable-value? value)))))))

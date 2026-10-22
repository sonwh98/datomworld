;; J2 differential parity for the jolt dao.stream.cbor host seam
;; (src/jolt/dao/stream/cbor/boring.jolt): the SAME corpus encoded on the
;; JVM (pinned Boring through dao.stream.cbor) and on jolt (the override),
;; bytes compared. The corpus lives here; no jolt-only expected values.
;;
;;   JVM:   java -cp <test:dev> clojure.main src/dev/jolt_cbor_parity.clj --emit
;;   jolt:  jolt -Srepro -M:jolt:jolt-test src/dev/jolt_cbor_parity.clj --check
;;          (bb test:jolt-cbor-parity emit|check does both)
;;
;; Also on jolt: decode(encode(v)) = v for every corpus row.
(ns jolt-cbor-parity
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [dao.host.bytes :as bytes]
            [dao.stream.cbor :as cbor]))


(def ^:private fixtures-path "build/j2-logs/cbor-parity.edn")


(def ^:private digits "0123456789abcdef")


(defn- hex-of
  [bs]
  (apply str (mapcat (fn [b]
                       [(nth digits (quot (bit-and b 0xff) 16))
                        (nth digits (rem (bit-and b 0xff) 16))])
                     (bytes/to-vector bs))))


(defn- unhex
  [s]
  (bytes/from-vector
    (mapv (fn [[a b]]
            (bit-or (bit-shift-left (str/index-of digits (str a)) 4)
                    (str/index-of digits (str b))))
          (partition 2 s))))


(defn corpus
  "Named portable values covering the profile surface."
  []
  [[:five 5]
   [:minus-five -5]
   [:zero 0]
   [:one-thousand 1000]
   [:million 1000000]
   [:top-safe 9007199254740991]
   [:bottom-safe -9007199254740991]
   [:neg-one -1]
   [:half 0.5]
   [:one-point-five 1.5]
   [:sixty-five-k 65504.0]
   [:hundred-thousand 100000.0]
   [:three-4050 3.405]
   [:quad-five-e15 4.5e15]         ; within the safe bound, not f32-exact
   [:tiny 5.960464477539063e-8]     ; 2^-24, smallest f16 subnormal
   [:not-tiny-half 2.9802322387695312e-8] ; 2^-25, NOT f16-exact
   [:minus-zero -0.0]
   [:plus-zero 0.0]
   [:power-two 2.0]
   [:string "héllo"]
   [:empty-string ""]
   [:unicode "é中😀é😀"]
   [:kw :foo]
   [:ns-kw :foo.bar/baz]
   [:sym 'foo]
   [:ns-sym 'foo.bar/baz]
   [:vector [1 2 3]]
   [:empty-vector []]
   [:list (list 1 2 3)]
   [:empty-list (list)]
   [:set #{1 2}]
   [:set-order #{100 2 30 4}]
   [:map {1 2 3 4}]
   [:map-order {"zz" 1 "a" 2 "aaa" 3 "" 4}]
   [:nested {:a [1 (list 2 3) #{4} {:b "x"}]}]
   [:meta-vector (with-meta [1 2] {:line 3 :column 4})]
   [:meta-symbol (with-meta 'foo {:m 1})]
   [:meta-map (with-meta {:a 1} {:k 2})]
   [:meta-set (with-meta #{1} {:s 1})]
   [:meta-list (with-meta '(1) {:l 1})]
   [:bytes (bytes/from-vector [1 2 250 0 128])]
   [:empty-bytes (bytes/from-vector [])]
   [:bytes-in-map {:blob (bytes/from-vector [200 100])}]
   [:deep-meta (with-meta [(with-meta [1] {:i 1})] {:o 1})]])


(defn- -emit
  []
  (let [rows (for [[label v] (corpus)]
               {:label label :hex (hex-of (cbor/encode v))})]
    (spit fixtures-path (pr-str {:writer :jvm-boring :rows rows}))
    (println "WROTE" fixtures-path (count rows) "rows")
    (System/exit 0)))


(def ^:private failures (atom 0))


(defn- check
  [label ok? detail]
  (when-not ok? (swap! failures inc))
  (println (if ok? "PASS" "FAIL") label (pr-str detail)))


(defn- -check
  []
  (let [{:keys [writer rows]} (edn/read-string (slurp fixtures-path))]
    (check "fixtures came from the JVM Boring lane" (= :jvm-boring writer)
           {:writer writer})
    (doseq [{:keys [label hex]} rows
            :let [v (some (fn [[l v]] (when (= l label) v)) (corpus))
                  bs (unhex hex)
                  jolt-hex (hex-of (cbor/encode v))
                  round (try (cbor/decode bs) (catch Exception e
                                                [:threw (ex-message e)]))]]
      (check (str label ": jolt bytes equal JVM Boring bytes")
             (= jolt-hex hex) {:jvm hex :jolt jolt-hex})
      (check (str label ": decode(encode(v)) round-trips")
             (or (= v round)
                 (and (bytes/bytes? v) (bytes/bytes? round)
                      (= (hex-of v) (hex-of round)))
                 ;; byte payloads compare by identity under =; canonical
                 ;; re-encode equality is the deep check for those rows
                 (and (some (fn [[_ w]] (bytes/bytes? w)) (cond (map? v) v :else []))
                      (= (hex-of (cbor/encode v))
                         (hex-of (cbor/encode round)))))
             {:value v :got round}))
    (doseq [[label bad-hex reason]
            [[:non-minimal-int "1805" :non-canonical]
             [:indefinite-array "9f01ff" :indefinite]
             [:unsorted-map "a203040102" :non-canonical]
             [:non-shortest-float "fa3f800000" :non-canonical]
             [:duplicate-keys "a201020103" :non-canonical]
             [:duplicate-byte-keys "a2410001410002" :non-canonical]
             [:duplicate-byte-elements "d901028241004100" :non-canonical]
             [:short-with-meta "d81b8271636c6f6a7572652f776974682d6d65746180" :frame]
             [:long-with-meta "d81b8271636c6f6a7572652f776974682d6d65746183a08000" :frame]
             [:scalar-with-meta "d81b8271636c6f6a7572652f776974682d6d65746182a000" :frame]
             [:trailing "0102" :trailing]
             [:unknown-tag "c24761626364656667" :non-canonical]
             [:bignum-tag "c249010000000000000000" :non-canonical]]]
      (let [r (try (cbor/decode (unhex bad-hex))
                   (catch Exception e [:threw (str (ex-message e)) (ex-data e)]))]
        (check (str "decode refuses " label)
               (and (vector? r) (= (first r) :threw)
                    (case label
                      :duplicate-byte-keys (= :boring/duplicate-map-key (:type (nth r 2)))
                      :duplicate-byte-elements (= :boring/duplicate-set-element (:type (nth r 2)))
                      true)) {:reason reason :got r})))
    (doseq [[label value] [[:duplicate-byte-keys (hash-map (bytes/from-vector [0]) 1
                                                           (bytes/from-vector [0]) 2)]
                           [:duplicate-byte-elements (hash-set (bytes/from-vector [0])
                                                               (bytes/from-vector [0]))]
                           [:symbol-large-meta (with-meta 'x {:n 9223372036854775807})]
                           [:symbol-bigint-meta (with-meta 'x {:n 9223372036854775808N})]
                           [:nested-symbol-meta (with-meta 'x {:s (with-meta 'y {:n 9223372036854775807})})]]]
      (let [r (try (cbor/encode value) :accepted
                   (catch Exception e (ex-data e)))]
        (check (str "encode refuses " label)
               (and (map? r)
                    (if (contains? #{:duplicate-byte-keys :duplicate-byte-elements} label)
                      (= :boring/canonical-duplicate (:type r))
                      (= :non-portable-value (:error r)))) r)))
    (println "SUMMARY" (if (zero? @failures) "ok" (str @failures " failed")))
    (System/exit (if (zero? @failures) 0 1))))


(let [mode (first *command-line-args*)]
  (case mode
    "--emit" (-emit)
    "--check" (-check)
    (do (println "pass --emit (JVM) or --check (jolt)") (System/exit 2))))

;; The J2 cross-host integer-carrier test (owner request, Q1 ruling:
;; integer carriers are named by value; in-range big-carrier? is
;; host-defined and portable code must not depend on it).
;;
;;   JVM side:   bb test:jolt-carrier encode
;;                -> java -cp <test:dev> clojure.main src/dev/jolt_carrier_test.clj --encode
;;   jolt side:  bb test:jolt-carrier verify
;;                -> jolt -Srepro -M:jolt:jolt-test src/dev/jolt_carrier_test.clj --verify
;;
;; What it proves. On the JVM, (promote 5) is a java.math.BigInteger carrier
;; and 5 a Long; on jolt both are the same exact integer. The law is that the
;; canonical bytes name the VALUE, never the carrier, so:
;;
;;   1. (JVM, encode) dao.jing/canonical-bytes of the promote-built value and
;;      of the plain value are equal byte strings, for an ordinary value, the
;;      top safe integer 2^53-1 and a large in-native-range value 2^62; the
;;      fixtures (hex bytes + the plain value) go to
;;      build/j2-logs/carrier-fixtures.edn;
;;   2. (jolt, verify) each fixture decodes to the plain value, all rows
;;      decode pairwise-equal (promoted bytes vs plain bytes), jolt's own
;;      re-encode of each decoded value reproduces the fixture bytes, and
;;      jolt's encode of (promote v) equals its encode of v — the name-by-
;;      value law held on this host too.
;;
;; Prints PASS/FAIL per check; exits nonzero on any FAIL.
(ns jolt-carrier-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [dao.host.bytes :as bytes]
            [dao.host.integer :as integer]
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]))


(def ^:private fixtures-path "build/j2-logs/carrier-fixtures.edn")

(def ^:private failures (atom 0))

(def ^:private digits "0123456789abcdef")


(defn- check
  [label ok? detail]
  (when-not ok? (swap! failures inc))
  (println (if ok? "PASS" "FAIL") label (pr-str detail)))


(defn- hex
  "Lowercase hex of host bytes `bs`, built digit by digit (portable: no
   format/aget differences between hosts)."
  [bs]
  (let [v (bytes/to-vector bs)]
    (apply str (mapcat (fn [b]
                         [(nth digits (quot (bit-and b 0xff) 16))
                          (nth digits (rem (bit-and b 0xff) 16))])
                       v))))


(defn- unhex
  "Host bytes of lowercase `s` (even length), through dao.host.bytes."
  [s]
  (bytes/from-vector
    (mapv (fn [[a b]]
            (bit-or (bit-shift-left (str/index-of digits (str a)) 4)
                    (str/index-of digits (str b))))
          (partition 2 s))))


(defn rows
  "The in-range integer rows: [label plain-value]."
  []
  [[:ordinary 5]
   [:top-safe 9007199254740991]      ; 2^53 - 1
   [:large-native 4611686018427387904]]) ; 2^62, in Long range, past 2^53


(defn- -encode
  []
  (let [rows (for [[label v] (rows)
                   :let [plain (jing/canonical-bytes v)
                         promoted (jing/canonical-bytes (integer/promote v))]]
               {:label label :value v
                :plain-hex (hex plain) :promoted-hex (hex promoted)
                :bytes-equal (bytes/equal? plain promoted)})]
    (doseq [{:keys [label bytes-equal plain-hex promoted-hex]} rows]
      (check (str "JVM encode: " label " promote-built and plain bytes equal")
             bytes-equal {:plain plain-hex :promoted promoted-hex}))
    (let [payload {:writer :jvm :rows rows}]
      (spit fixtures-path (pr-str payload))
      (println "WROTE" fixtures-path (count rows) "rows"))
    (System/exit (if (pos? @failures) 1 0))))


(defn- -verify
  []
  (let [{:keys [writer rows]} (edn/read-string (slurp fixtures-path))]
    (check "fixtures were written by the JVM lane" (= :jvm writer) {:writer writer})
    (doseq [{:keys [label value plain-hex promoted-hex bytes-equal]} rows]
      (let [plain-bs (unhex plain-hex)
            promoted-bs (unhex promoted-hex)
            decoded-plain (cbor/decode plain-bs)
            decoded-promoted (cbor/decode promoted-bs)]
        (check (str label ": JVM-side bytes were equal") (true? bytes-equal) {})
        (check (str label ": plain bytes decode to the plain value")
               (= value decoded-plain) {:value value :got decoded-plain})
        (check (str label ": promoted bytes decode to the plain value")
               (= value decoded-promoted) {:value value :got decoded-promoted})
        (check (str label ": the two decodes are equal (named by value)")
               (= decoded-plain decoded-promoted) {})
        (check (str label ": jolt re-encode reproduces the fixture bytes")
               (= (hex (jing/canonical-bytes decoded-plain)) plain-hex)
               {:reencoded (hex (jing/canonical-bytes decoded-plain))})
        (check (str label ": jolt encode of (promote v) equals encode of v")
               (= (hex (jing/canonical-bytes (integer/promote decoded-plain)))
                  plain-hex)
               {})))
    (println "SUMMARY" (if (zero? @failures) "ok" (str @failures " failed")))
    (System/exit (if (zero? @failures) 0 1))))


(let [mode (first *command-line-args*)]
  (case mode
    "--encode" (-encode)
    "--verify" (-verify)
    (do (println "pass --encode (JVM) or --verify (jolt)") (System/exit 2))))

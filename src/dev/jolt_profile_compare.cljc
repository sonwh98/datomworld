;; J1: compare the dao.host profile surface and behaviour between the JVM
;; (src/cljc/dao/host/*.cljc) and jolt (the src/jolt/dao/host/*.jolt
;; overrides). The same file runs on both hosts and prints one EDN form per
;; line, in a fixed order; `diff` of the two outputs is the comparison.
;;
;;   clojure -M src/dev/jolt_profile_compare.cljc | grep '^\[' > build/j1-logs/compare-jvm.edn
;;   . build/tools/jolt-env.sh
;;   "$JOLT_HOME/bin/jolt" -Srepro -M:jolt src/dev/jolt_profile_compare.cljc | grep '^\[' > build/j1-logs/compare-jolt.edn
;;   diff build/j1-logs/compare-jvm.edn build/j1-logs/compare-jolt.edn
;;
;; (bb compare:jolt-host runs all three; grep drops host log lines such as
;; the JVM classpath's Timbre banner.) Lines:
;;   [:api ns var arglists]          every public var, its :arglists
;;   [:case label result]            a call, its result normalized so that
;;                                   host printing never decides equality:
;;     [:int carrier decimal-text]   an exact integer and its carrier
;;     [:float bits]                 a host double by its IEEE-754 bits
;;     [:bytes [0-255 ...]]          a host byte array
;;     [:text [code-point ...]]      a string by its code points
;;     [:refused class entry]        a profile refusal's throw data
;;     [:threw message-class keys]   any other throw (not a profile refusal)
(ns jolt-profile-compare
  (:require
    [dao.host.bytes :as bytes]
    [dao.host.float :as float]
    [dao.host.integer :as integer]
    [dao.host.number :as number]
    [dao.host.text :as text]))


(def namespaces
  '[dao.host.integer dao.host.float dao.host.number dao.host.bytes dao.host.text])


(defn- norm
  [x]
  (cond
    (nil? x) nil
    (boolean? x) x
    (keyword? x) x
    (string? x) [:text (text/code-points x)]
    (bytes/bytes? x) [:bytes (bytes/to-vector x)]
    (integer/exact-integer? x) [:int
                                (cond (integer/big-carrier? x) :big
                                      (integer/safe-native? x) :safe-native
                                      :else :native)
                                (integer/to-radix-string (integer/promote x) 10)]
    (integer/host-float? x) [:float (float/float64->bits x)]
    (number/ratio? x) [:ratio (norm (number/ratio-numerator x)) (norm (number/ratio-denominator x))]
    (number/decimal? x) [:decimal (norm (number/decimal-exponent x)) (norm (number/decimal-mantissa x))]
    (vector? x) (mapv norm x)
    :else [:other (str x)]))


(defn- run-case
  [f]
  (try
    (norm (f))
    (catch #?(:clj Exception :default :default) e
      (let [d (ex-data e)]
        (if (contains? d :dao.host/refusal)
          [:refused (:dao.host/refusal d) (:dao.host/entry d)]
          [:threw (vec (sort (map str (keys d))))])))))


(defn- big
  [s]
  (integer/parse-decimal s))


(def ^:private int-texts
  ["0" "1" "-1" "5" "9007199254740991" "-9007199254740991" "9007199254740992"
   "9223372036854775807" "-9223372036854775808" "9223372036854775808"
   "-9223372036854775809" "18446744073709551615" "18446744073709551616"
   "-18446744073709551617" "340282366920938463463374607431768211455"
   "-1180591620717411303424"])


(def ^:private shift-counts [0 1 31 32 53 63 64 65 100 127 128])


(def cases
  (concat
    ;; integer
    (for [s int-texts]
      [(str "integer/parse+demote " s) #(integer/demote (big s))])
    (for [s int-texts
          f '[big-carrier? exact-integer? safe-native?]]
      [(str "integer/" f " " s)
       #((case f
           big-carrier? integer/big-carrier?
           exact-integer? integer/exact-integer?
           safe-native? integer/safe-native?)
         (integer/demote (big s)))])
    [["integer/big-carrier? (promote 5)" #(integer/big-carrier? (integer/promote 5))]
     ["integer/exact-integer? 1.0" #(integer/exact-integer? 1.0)]
     ["integer/exact-integer? 1/2" #(integer/exact-integer? (/ 1 2))]
     ["integer/host-float? 1.5" #(integer/host-float? 1.5)]
     ["integer/host-float? 1" #(integer/host-float? 1)]
     ["integer/nan-float? NaN" #(integer/nan-float? ##NaN)]
     ["integer/infinite-float? -Inf" #(integer/infinite-float? ##-Inf)]
     ["integer/to-native" #(integer/to-native (big "9007199254740991"))]
     ["integer/native->double 2^53" #(integer/native->double 9007199254740992)]
     ["integer/trunc-native -2.75" #(integer/trunc-native -2.75)]
     ["integer/trunc-native -0.5" #(integer/trunc-native -0.5)]
     ["integer/from-native 7" #(integer/demote (integer/from-native 7))]]
    (for [a int-texts
          b ["1" "-3" "7" "18446744073709551616" "-9223372036854775808"]
          [nm f] [["add" integer/add] ["sub" integer/sub] ["mul" integer/mul]
                  ["quot-trunc" integer/quot-trunc] ["rem-trunc" integer/rem-trunc]
                  ["compare-big" integer/compare-big] ["bit-and-big" integer/bit-and-big]
                  ["bit-or-big" integer/bit-or-big] ["bit-xor-big" integer/bit-xor-big]
                  ["gcd" integer/gcd]]]
      [(str "integer/" nm " " a " " b)
       ;; compare-big answers a native -1, 0 or 1, not a host big integer
       (if (= "compare-big" nm)
         #(f (big a) (big b))
         #(integer/demote (f (big a) (big b))))])
    (for [a int-texts
          k shift-counts
          [nm f] [["shift-left-big" integer/shift-left-big]
                  ["shift-right-big" integer/shift-right-big]]]
      [(str "integer/" nm " " a " " k) #(integer/demote (f (big a) k))])
    (for [a int-texts]
      [(str "integer/magnitude-bit-length " a) #(integer/magnitude-bit-length (big a))])
    (for [a int-texts
          r [2 8 10 16 36]]
      [(str "integer/to-radix-string " a " " r) #(integer/to-radix-string (big a) r)])
    (for [a int-texts]
      [(str "integer/magnitude-bytes " a) #(integer/magnitude-bytes (big a))])
    (for [s ["" "-" "+1" " 1" "0x10" "1.0" "٣" "--1" "007" "-0"]]
      [(str "integer/parse-decimal " (pr-str s)) #(integer/parse-decimal s)])
    [["integer/parse-decimal nil" #(integer/parse-decimal nil)]
     ["integer/parse-decimal 12" #(integer/parse-decimal 12)]]
    ;; the r11 amendment: one optional leading `-`, then one or more ASCII
    ;; digits, nothing else; each answered through `demote` so the value,
    ;; not the JVM's BigInteger type, is compared
    (for [s ["0" "-0" "-007" "5" "-5" "00000000000000000000000001"
             "-9223372036854775808" "9223372036854775808"
             (apply str "9" (repeat 399 "0")) (apply str "-" (repeat 400 "7"))
             "1-" "-+1" "- 1" "1 " "\t1" "1\n" "١" "１" "−1" "1_000" "1e3" "-1.0"
             "0b1" "0o7" "0x1f" "Infinity" "NaN" "1,000"]]
      [(str "integer/parse-decimal r11 " (pr-str s))
       #(integer/demote (integer/parse-decimal s))])
    ;; float
    (for [h ["0000000000000000" "8000000000000000" "3ff8000000000000" "7ff0000000000000"
             "fff0000000000000" "7ff8000000000000" "fff8000000000001" "7ff0000000000001"
             "0000000000000001" "000fffffffffffff" "7fefffffffffffff" "4330000000000001"
             "43e0000000000000" "c004000000000000"]
          [nm f] [["bits round trip" #(float/float64->bits %)]
                  ["finite?" float/finite?] ["integral?" float/integral?]
                  ["fields" float/fields]]]
      [(str "float/" nm " " h) #(f (float/bits->float64 h))])
    (for [h ["" "3ff" "3FF8000000000000" "3ff800000000000g" nil 1.5]]
      [(str "float/bits->float64 " (pr-str h)) #(float/bits->float64 h)])
    (for [t ["1.5" "0.1" "9007199254740993.0" "1." "0" "100" ".5"]]
      [(str "float/decimal-text->float " t) #(float/decimal-text->float t)])
    (for [h ["3ff0000000000000" "3fb999999999999a" "0000000000000001" "7fefffffffffffff"
             "c004000000000000" "0000000000000000"]]
      [(str "float/float-digits " h) #(float/float-digits (float/bits->float64 h))])
    ;; number
    [["number/float64-of 1" #(number/float64-of 1)]
     ["number/float-value 1.5" #(number/float-value (number/float64-of 1.5))]
     ["number/decimal-of -1 15" #(number/decimal-of -1 15)]
     ["number/decimal-of 2147483648 1" #(number/decimal-of 2147483648 1)]
     ["number/decimal-of -2147483647 big" #(number/decimal-of -2147483647 (big "18446744073709551617"))]
     ["number/decimal-of 2147483649 1" #(number/decimal-of 2147483649 1)]
     ["number/decimal-of 0 2.5" #(number/decimal-of 0 2.5)]
     ["number/decimal? 1.5" #(number/decimal? 1.5)]
     ["number/ratio-of 6 -4" #(number/ratio-of 6 -4)]
     ["number/ratio-of 3 1" #(number/ratio-of 3 1)]
     ["number/ratio-of 0 -3" #(number/ratio-of 0 -3)]
     ["number/ratio-of big big" #(number/ratio-of (big "18446744073709551617") (big "-36893488147419103234"))]
     ["number/ratio-of 1 0" #(number/ratio-of 1 0)]
     ["number/ratio-of 1.5 2" #(number/ratio-of 1.5 2)]
     ["number/ratio? 3/1 carrier" #(number/ratio? (number/ratio-of 3 1))]
     ["number/ratio= 1/1 2/2" #(= (number/ratio-of 1 1) (number/ratio-of 2 2))]
     ["number/ratio hash 1/1 2/2" #(= (hash (number/ratio-of 1 1)) (hash (number/ratio-of 2 2)))]
     ["number/pr-str ratio 3 1" #(pr-str (number/ratio-of 3 1))]]
    ;; bytes
    [["bytes/from-vector" #(bytes/from-vector [0 1 127 128 255])]
     ["bytes/alloc 3" #(bytes/alloc 3)]
     ["bytes/length" #(bytes/length (bytes/from-vector [1 2 3]))]
     ["bytes/get" #(bytes/get (bytes/from-vector [1 200 3]) 1)]
     ["bytes/slice" #(bytes/slice (bytes/from-vector [10 20 30 40]) 1 3)]
     ["bytes/equal? same" #(bytes/equal? (bytes/from-vector [1 2]) (bytes/from-vector [1 2]))]
     ["bytes/equal? differ" #(bytes/equal? (bytes/from-vector [1 2]) (bytes/from-vector [1 3]))]
     ["bytes/set!" #(let [b (bytes/alloc 2)] (bytes/set! b 1 255) b)]
     ["bytes/copy! forward overlap" #(let [b (bytes/from-vector [1 2 3 4 5 6])] (bytes/copy! b 0 b 2 4) b)]
     ["bytes/copy! backward overlap" #(let [b (bytes/from-vector [1 2 3 4 5 6])] (bytes/copy! b 2 b 0 4) b)]
     ["bytes/set! refused" #(bytes/set! (bytes/alloc 2) 2 0)]
     ["bytes/set! 256" #(bytes/set! (bytes/alloc 2) 0 256)]
     ["bytes/set! 1.0" #(bytes/set! (bytes/alloc 2) 1.0 0)]
     ["bytes/copy! refused" #(bytes/copy! (bytes/alloc 2) 0 (bytes/alloc 2) 1 2)]
     ["bytes/from-vector list" #(bytes/from-vector '(1 2))]
     ["bytes/length vector" #(bytes/length [1 2])]
     ["bytes/alloc -1" #(bytes/alloc -1)]]
    ;; text
    (for [units [[] [0x61] [0xE9] [0x4E2D] [0xD83D 0xDE00] [0x61 0xD83D 0xDE00 0x62]
                 [0xD800] [0xDC00] [0x61 0xD800] [0xDE00 0xD83D]]]
      [(str "text/units->string " units)
       #(let [s (text/units->string units)]
          [(text/unit-count s) (mapv (fn [i] (text/code-unit s i)) (range (text/unit-count s)))
           (text/code-points s)])])
    (for [s ["" "héllo" "aé中" "a😀b" "﻿a"]]
      [(str "text/utf8-encode " s) #(text/utf8-encode s)])
    [["text/utf8-encode nil" #(text/utf8-encode nil)]
     ["text/utf8-encode lone surrogate" #(text/utf8-encode (text/units->string [0xD800]))]]
    (for [v [[] [0x68 0xC3 0xA9] [0xEF 0xBB 0xBF 0x61] [0xF0 0x9F 0x98 0x80] [0xF4 0x8F 0xBF 0xBF]
             [0x80] [0xC0 0x80] [0xE0 0x80 0x80] [0xED 0xA0 0x80] [0xF4 0x90 0x80 0x80]
             [0xC3] [0xC3 0x28] [0xE9] [0xFF] [0x61 0xE2 0x82]]]
      [(str "text/utf8-decode " v) #(text/utf8-decode (bytes/from-vector v))])
    [["text/utf8-decode range" #(text/utf8-decode (bytes/from-vector [0x61 0xC3 0xA9 0x62]) 1 2)]
     ["text/utf8-decode range out" #(text/utf8-decode (bytes/from-vector [0x61]) 1 1)]]
    (for [s ["é" "ẛ̣" "각" "Å" "Å"]]
      [(str "text/nfc " (text/code-points s)) #(text/nfc s)])))


(defn -main
  []
  (doseq [n namespaces]
    (doseq [[sym v] (sort-by key (ns-publics n))]
      (prn [:api n sym (:arglists (meta v))])))
  (doseq [[label f] cases]
    (prn [:case label (run-case f)]))
  (prn [:case "number/->Rational 1 2 callable" (run-case #(number/ratio? (number/->Rational 1 2)))]))


(-main)

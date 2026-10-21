;; J1 (and J1b: dao.host.hash, dao.host.sign): compare the dao.host profile
;; surface and behaviour between the JVM
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
    [clojure.string :as str]
    [dao.host.bytes :as bytes]
    [dao.host.float :as float]
    [dao.host.hash :as hash]
    [dao.host.integer :as integer]
    [dao.host.number :as number]
    [dao.host.sign :as sign]
    [dao.host.text :as text]))


(def namespaces
  '[dao.host.integer dao.host.float dao.host.number dao.host.bytes dao.host.text
    dao.host.hash dao.host.sign])


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


(defn- hex->bytes
  [s]
  (bytes/from-vector
    (mapv (fn [i]
            (let [d #(str/index-of "0123456789abcdef" (subs s % (inc %)))]
              (+ (* 16 (d (* 2 i))) (d (inc (* 2 i))))))
          (range (quot (count s) 2)))))


(defn- pattern-bytes
  "`n` bytes, byte i = (7i + 3) mod 256."
  [n]
  (let [b (bytes/alloc n)]
    (dotimes [i n] (bytes/set! b i (mod (+ 3 (* 7 i)) 256)))
    b))


(def ^:private hash-inputs
  ;; label and a thunk answering the input bytes (§6.2 / J1b spec §4)
  (concat
    [["empty" #(bytes/alloc 0)]
     ["abc" #(text/utf8-encode "abc")]
     ["all 256 byte values" #(bytes/from-vector (vec (range 256)))]
     ["UTF-8 héllo 中😀" #(text/utf8-encode "héllo 中😀")]
     ["embedded NUL" #(bytes/from-vector [97 0 98 0 0])]]
    (for [n [63 64 65 1023 1024 1025]]
      [(str n " bytes") #(pattern-bytes n)])
    ;; over 2 MiB: many BLAKE3 chunks and SHA-256 blocks
    [["2 MiB + 1 bytes" (let [b (delay (pattern-bytes (inc (* 2 1024 1024))))] #(deref b))]]))


;; RFC 8032 §7.1 tests 1-3, as test/yin/vm/linker/sign_vectors.edn :rfc
;; records them, plus the shared test's seed of 32 ones
(def ^:private rfc8032
  [{:seed "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"
    :public "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
    :message ""
    :signature "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"}
   {:seed "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb"
    :public "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"
    :message "72"
    :signature "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"}
   {:seed "c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7"
    :public "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025"
    :message "af82"
    :signature "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a"}])


(defn- flip
  "A copy of `bs` with byte `i` xor 1."
  [bs i]
  (let [b (bytes/slice bs 0 (bytes/length bs))]
    (bytes/set! b i (bit-xor 1 (bytes/get b i)))
    b))


(def ^:private hash-cases
  (concat
    (for [[label input] hash-inputs
          [nm f] [["blake3-bytes" hash/blake3-bytes] ["sha256-bytes" hash/sha256-bytes]]]
      [(str "hash/" nm " " label) #(f (input))])
    (for [[nm f] [["blake3-bytes" hash/blake3-bytes] ["sha256-bytes" hash/sha256-bytes]]
          [label x] [["string" "abc"] ["integer" 123] ["nil" nil] ["vector" [1 2]]]]
      [(str "hash/" nm " refused " label) #(f x)])
    (for [[nm f] [["blake3-bytes" hash/blake3-bytes] ["sha256-bytes" hash/sha256-bytes]]]
      [(str "hash/" nm " argument unmodified, result fresh")
       #(let [in (bytes/from-vector [1 2 3 4 5])
              a (f in)
              b (f in)]
          [(bytes/to-vector in) (bytes/equal? a b) (identical? a b)])])))


(defn- pair-of
  "A keypair's keys and bytes, for `norm` (which prints no maps)."
  [pair]
  [(vec (sort (map str (keys pair)))) (:seed pair) (:public pair)])


(def ^:private sign-cases
  (concat
    (for [{:keys [seed public message signature]} rfc8032
          :let [s (hex->bytes seed)
                m (hex->bytes message)
                label (str "sign RFC 8032 seed " (subs seed 0 8))]
          [nm f] [["keypair-from-seed" #(pair-of (sign/keypair-from-seed s))]
                  ["public matches the vector"
                   #(bytes/equal? (hex->bytes public) (:public (sign/keypair-from-seed s)))]
                  ["sign" #(sign/sign s m)]
                  ["signature matches the vector"
                   #(bytes/equal? (hex->bytes signature) (sign/sign s m))]
                  ;; the vector's signature, made by another implementation,
                  ;; verifies here; and so does this host's own
                  ["verify vector signature"
                   #(sign/verify (hex->bytes public) m (hex->bytes signature))]
                  ["verify own signature"
                   #(sign/verify (:public (sign/keypair-from-seed s)) m (sign/sign s m))]
                  ["verify tampered signature" #(sign/verify (hex->bytes public) m (flip (hex->bytes signature) 0))]
                  ["verify tampered signature s half"
                   #(sign/verify (hex->bytes public) m (flip (hex->bytes signature) 40))]
                  ["verify tampered message"
                   #(sign/verify (hex->bytes public) (text/utf8-encode "x") (hex->bytes signature))]
                  ["verify wrong public key"
                   #(sign/verify (:public (sign/keypair-from-seed (pattern-bytes 32))) m
                                 (hex->bytes signature))]]]
      [(str label " " nm) f])
    [["sign seed of ones, hello world"
      #(let [seed (bytes/from-vector (vec (repeat 32 1)))
             msg (text/utf8-encode "hello world")
             pair (sign/keypair-from-seed seed)
             sig (sign/sign seed msg)]
         [(pair-of pair) sig (sign/verify (:public pair) msg sig)
          (sign/verify (:public pair) (text/utf8-encode "wrong msg") sig)])]
     ["sign 2 MiB + 1 message"
      #(let [seed (pattern-bytes 32)
             msg (pattern-bytes (inc (* 2 1024 1024)))
             sig (sign/sign seed msg)]
         [sig (sign/verify (:public (sign/keypair-from-seed seed)) msg sig)])]
     ["sign keypair seed is a fresh copy"
      #(let [seed (pattern-bytes 32)
             pair (sign/keypair-from-seed seed)]
         [(identical? seed (:seed pair)) (bytes/equal? seed (:seed pair))])]
     ;; a public key that is no curve point: false, never a throw
     ["sign verify non-point public key"
      #(sign/verify (bytes/from-vector (vec (repeat 32 255))) (bytes/alloc 0) (bytes/alloc 64))]
     ["sign verify all-zero signature"
      #(sign/verify (hex->bytes (:public (first rfc8032))) (bytes/alloc 0) (bytes/alloc 64))]
     ["sign keypair-from-seed 31 bytes" #(sign/keypair-from-seed (bytes/alloc 31))]
     ["sign keypair-from-seed 33 bytes" #(sign/keypair-from-seed (bytes/alloc 33))]
     ["sign keypair-from-seed vector" #(sign/keypair-from-seed (vec (repeat 32 0)))]
     ["sign sign 31-byte seed" #(sign/sign (bytes/alloc 31) (bytes/alloc 1))]
     ["sign sign message string" #(sign/sign (bytes/alloc 32) "msg")]
     ["sign verify 31-byte public" #(sign/verify (bytes/alloc 31) (bytes/alloc 1) (bytes/alloc 64))]
     ["sign verify 63-byte signature" #(sign/verify (bytes/alloc 32) (bytes/alloc 1) (bytes/alloc 63))]
     ["sign verify 65-byte signature" #(sign/verify (bytes/alloc 32) (bytes/alloc 1) (bytes/alloc 65))]
     ["sign verify message nil" #(sign/verify (bytes/alloc 32) nil (bytes/alloc 64))]]))


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
    ;; number: float64-of and float-value over a decimal and a ratio
    ;; carrier (review J1-R4): ordinary, huge, tiny, negative, zero, and
    ;; values at or next to a binary64 rounding tie
    (for [[label [e m]] [["1.5" [-1 "15"]] ["-1.5" [-1 "-15"]] ["0" [0 "0"]]
                         ["0E-5" [-5 "0"]] ["0.1" [-1 "1"]] ["123E7" [7 "123"]]
                         ["1E400" [400 "1"]] ["-1E400" [400 "-1"]] ["1E-400" [-400 "1"]]
                         ["2^53+1" [0 "9007199254740993"]]
                         ["2^53+3" [0 "9007199254740995"]]
                         ["2^-53 exactly" [-53 "11102230246251565404236316680908203125"]]
                         ["1+2^-53 (tie, to even 1.0)"
                          [-53 "100000000000000011102230246251565404236316680908203125"]]
                         ["1+3*2^-53 (tie, to even 1+2^-51)"
                          [-53 "100000000000000033306690738754696212708950042724609375"]]
                         ["1+2^-53+1E-60 (just above the tie)"
                          [-60 "1000000000000000111022302462515654042363166809082031250000001"]]
                         ["40 digits" [-39 "1234567890123456789012345678901234567890"]]]
          [nm f] [["float64-of" number/float64-of]
                  ["float-value" number/float-value]]]
      [(str "number/" nm " decimal " label)
       #(f (number/decimal-of e (integer/demote (big m))))])
    ;; KNOWN DIFFERENCE (J1 round 3), JVM side: Clojure's Ratio.doubleValue
    ;; rounds to 16 decimal digits (MathContext/DECIMAL64) and then to
    ;; binary64, twice, so the JVM's `(double ratio)` misses the nearest
    ;; double on "2/3", "(2^53+3)/2^53" and "just above the 1+2^-53 tie";
    ;; jolt (Chez exact->inexact) is correctly rounded and agrees with the
    ;; profile's own kernel, `float/ratio->float reference` below, on both
    ;; hosts. Reported to the host-boundary seat; not mimicked on jolt.
    (for [[label [n d]] [["1/3" ["1" "3"]] ["-1/3" ["-1" "3"]] ["2/3" ["2" "3"]]
                         ["0/5 (Rational carrier)" ["0" "5"]]
                         ["7/1 (Rational carrier)" ["7" "1"]]
                         ["10^400/3" [(apply str "1" (repeat 400 "0")) "3"]]
                         ["-10^400/7" [(apply str "-1" (repeat 400 "0")) "7"]]
                         ["1/10^400" ["1" (apply str "1" (repeat 400 "0"))]]
                         ["(2^53+1)/2^53 (tie, to even 1.0)" ["9007199254740993" "9007199254740992"]]
                         ["(2^53+3)/2^53 (tie, to even 1+2^-51)" ["9007199254740995" "9007199254740992"]]
                         ["just above the 1+2^-53 tie"
                          ["1000000000000000111022302462515654042363166809082031250000001"
                           "1000000000000000000000000000000000000000000000000000000000000"]]
                         ["(2^54+1)/2" ["18014398509481985" "2"]]]
          [nm f] [["float64-of" number/float64-of]
                  ["float-value" number/float-value]]]
      [(str "number/" nm " ratio " label)
       #(f (number/ratio-of (integer/demote (big n)) (integer/demote (big d))))])
    ;; the same ratios through the profile's own correctly rounded kernel
    ;; (dao.host.float/ratio->float): the reference float64-of is compared
    ;; with, on both hosts
    (for [[label [n d]] [["2/3" ["2" "3"]]
                         ["(2^53+3)/2^53" ["9007199254740995" "9007199254740992"]]
                         ["just above the 1+2^-53 tie"
                          ["1000000000000000111022302462515654042363166809082031250000001"
                           "1000000000000000000000000000000000000000000000000000000000000"]]]]
      [(str "float/ratio->float reference " label)
       #(let [r (number/ratio-of (integer/demote (big n)) (integer/demote (big d)))]
          (float/ratio->float (number/ratio-numerator r) (number/ratio-denominator r) false))])
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
    ;; units->string on a unit outside 0..0xFFFF (review J1-R5): the class,
    ;; message and ex-data of the JVM's `(char u)` throw, compared exactly
    (for [units [[0xFFFF] [0] [65536] [-1] [97 1114112] [55296 65536] [56320 97 70000]]]
      [(str "text/units->string range " units)
       #(try (text/code-points (text/units->string units))
             (catch #?(:clj Exception :default :default) e
               [:threw (instance? IllegalArgumentException e) (ex-message e) (ex-data e)]))])
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
      [(str "text/nfc " (text/code-points s)) #(text/nfc s)])
    ;; hash and sign (J1b)
    hash-cases
    sign-cases))


(defn -main
  []
  (doseq [n namespaces]
    ;; skip the name the JVM's `proxy` interns for its class (dao.host.sign's
    ;; SunEC seed replay): a host artefact, not a profile entry
    (doseq [[sym v] (sort-by key (ns-publics n))
            :when (not (str/starts-with? (name sym) (str n ".proxy$")))]
      (prn [:api n sym (:arglists (meta v))])))
  (doseq [[label f] cases]
    (prn [:case label (run-case f)]))
  (prn [:case "number/->Rational 1 2 callable" (run-case #(number/ratio? (number/->Rational 1 2)))]))


(-main)

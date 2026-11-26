(ns yang.core.numeric-token
  "Shared numeric-token classifier and Jing carrier constructor for all Yang
   frontends (architect ruling 1795693415000, RULING 2).

   One implementation parameterized by declarative per-language lexeme dialect
   tables (`dialects`):
   - `:clojure`    — exact integers (decimal, hex, octal, Nr..., N big-int),
                     binary64 floats (including integral floats like `0.0`,
                     `1.0`, `1e0`, `2.`, `-0.0`, and floats beyond 2^53),
                     exact decimals (`M` suffix), exact ratios (`n/d`),
                     symbolic floats (`##Inf`, `##-Inf`, `##NaN`).
   - `:python`     — underscore-separated float/int classification; float
                     tokens materialize as Jing `cbor/float64`.
   - `:php`        — underscore-separated float tokens materialize as Jing
                     `cbor/float64`.
   - `:typescript` — underscore-separated JS/TS Number tokens (decimal, hex,
                     octal, binary; refusing strict-mode legacy octal)
                     materialize as Jing `cbor/float64`.

   No host numeric coercion guesses a lost kind: every constructor consumes
   the original lexical token string and produces canonical Jing/host Data."
  (:require [clojure.string :as str]
            [dao.host.integer :as host-int]
            [dao.jing.cbor :as cbor]))


(def ^:private digit-alphabet "0123456789abcdefghijklmnopqrstuvwxyz")


(def dialects
  "Declarative per-language numeric lexeme dialect table."
  {:clojure {:separator nil
             :kinds #{:integer :float64 :decimal :ratio :symbolic-float}
             :allow-leading-dot? false
             :allow-trailing-dot? true
             :allow-signed? true
             :allow-legacy-octal? true}
   :python {:separator "_"
            :kinds #{:integer :float64}
            :allow-leading-dot? true
            :allow-trailing-dot? true
            :allow-signed? false
            :allow-legacy-octal? false}
   :php {:separator "_"
         :kinds #{:float64}
         :allow-leading-dot? true
         :allow-trailing-dot? true
         :allow-signed? true
         :allow-legacy-octal? false}
   :typescript {:separator "_"
                :kinds #{:float64}
                :allow-leading-dot? true
                :allow-trailing-dot? true
                :allow-signed? false
                :allow-legacy-octal? false}})


(defn strip-separator
  "Remove the dialect's digit separator (if any) from `text`."
  [dialect text]
  (if-let [sep (:separator (get dialects dialect))]
    (str/replace text sep "")
    text))


(defn normalize-decimal-float
  "Normalize decimal float text (already free of digit separators) so every
   host's IEEE-754 double parser accepts it identically:
   - preserves leading `-` (including `-0.0` and `-1e-400`), strips leading `+`
   - leading `.` -> `0.`
   - `.e` / `.E` -> `.0e` / `.0E`
   - trailing `.` -> `.0`"
  [t]
  (let [neg? (str/starts-with? t "-")
        pos? (str/starts-with? t "+")
        sign (if neg? "-" "")
        body (if (or neg? pos?) (subs t 1) t)
        body (if (str/starts-with? body ".") (str "0" body) body)
        body (str/replace body ".e" ".0e")
        body (str/replace body ".E" ".0E")
        body (if (str/ends-with? body ".") (str body "0") body)]
    (str sign body)))


(defn parse-float64
  "Parse normalized decimal float text `t` into Jing float64 content
   (`cbor/float64`): Double on JVM, double on Dart, Float64 carrier on JS."
  [t]
  (let [norm (normalize-decimal-float t)
        v #?(:cljd (double/parse norm)
             :clj (Double/parseDouble norm)
             :cljs (js/parseFloat norm))]
    (cbor/float64 v)))


(defn parse-radix-float64
  "Parse unsigned `digits` in `radix` (2, 8, or 16) into Jing float64 content."
  [digits radix]
  (let [r (double radix)
        v (reduce (fn [acc ch]
                    (+ (* acc r)
                       (double (str/index-of "0123456789abcdef"
                                             (str/lower-case (str ch))))))
                  0.0
                  digits)]
    (cbor/float64 v)))


(defn- valid-radix-digits?
  [digits radix]
  (and (pos? (count digits))
       (<= 2 radix 36)
       (let [lower (str/lower-case digits)]
         (every? (fn [ch]
                   (let [idx (str/index-of digit-alphabet (str ch))]
                     (and (some? idx) (< idx radix))))
                 lower))))


(defn- big-from-radix
  "Exact non-negative host big integer from `digits` in `radix` (2..36)."
  [digits radix]
  (let [lower (str/lower-case digits)
        r (host-int/from-native radix)]
    (reduce (fn [acc ch]
              (let [d (host-int/from-native
                        (str/index-of digit-alphabet (str ch)))]
                (host-int/add (host-int/mul acc r) d)))
            (host-int/from-native 0)
            lower)))


(defn- force-big-carrier
  "Ensure host big integer `b` stays in the host's exact big-integer carrier
   (for explicit `N`-suffixed Clojure integer literals)."
  [b]
  #?(:cljd b
     :clj (clojure.lang.BigInt/fromBigInteger b)
     :cljs b))


(defn parse-exact-integer
  "Materialize exact integer content from `digits`, `radix`, `negative?`, and
   `big?` (explicit `N` suffix). Never passes through a floating intermediate."
  [digits radix negative? big?]
  (let [mag (if (= 10 radix)
              (host-int/parse-decimal digits)
              (big-from-radix digits radix))
        signed (if negative?
                 (host-int/sub (host-int/from-native 0) mag)
                 mag)]
    (if big?
      (force-big-carrier signed)
      (host-int/demote signed))))


(defn parse-decimal
  "Parse Clojure exact decimal text `t` (without trailing `M`) into
   `cbor/decimal` without any floating intermediate."
  [t]
  (let [neg? (str/starts-with? t "-")
        pos? (str/starts-with? t "+")
        body (if (or neg? pos?) (subs t 1) t)
        e-idx (or (str/index-of body "e") (str/index-of body "E"))
        mant-part (if e-idx (subs body 0 e-idx) body)
        exp-part (if e-idx (subs body (inc e-idx)) nil)
        dot-idx (str/index-of mant-part ".")
        int-digits (if dot-idx (subs mant-part 0 dot-idx) mant-part)
        frac-digits (if dot-idx (subs mant-part (inc dot-idx)) "")
        digits-raw (str int-digits frac-digits)
        ;; strip leading zeros so decimal-digits? gets a clean run, keeping "0"
        digits-trimmed (str/replace digits-raw #"^0+" "")
        digits (if (empty? digits-trimmed) "0" digits-trimmed)
        signed-digits (if (and neg? (not= digits "0")) (str "-" digits) digits)
        explicit-exp (if exp-part
                       (let [eneg? (str/starts-with? exp-part "-")
                             epos? (str/starts-with? exp-part "+")
                             ebody (if (or eneg? epos?) (subs exp-part 1) exp-part)
                             ebig (host-int/parse-decimal ebody)]
                         (if eneg?
                           (host-int/sub (host-int/from-native 0) ebig)
                           ebig))
                       (host-int/from-native 0))
        scale-shift (host-int/from-native (count frac-digits))
        exponent (host-int/sub explicit-exp scale-shift)
        mantissa (host-int/parse-decimal signed-digits)]
    (cbor/decimal (host-int/demote exponent) (host-int/demote mantissa))))


(defn parse-ratio
  "Parse Clojure exact ratio `num-str` / `den-str`. Refuses zero denominator,
   reduces by gcd, and returns an exact integer when reduced denominator is 1
   (matching JVM Clojure's reader for e.g. `4/2` -> `2`, `0/2` -> `0`)."
  [num-str den-str]
  (let [neg? (str/starts-with? num-str "-")
        pos? (str/starts-with? num-str "+")
        nbody (if (or neg? pos?) (subs num-str 1) num-str)
        nmag (host-int/parse-decimal nbody)
        n (if neg? (host-int/sub (host-int/from-native 0) nmag) nmag)
        d (host-int/parse-decimal den-str)
        zero (host-int/from-native 0)
        one (host-int/from-native 1)]
    (when (zero? (host-int/compare-big d zero))
      (throw (ex-info "Divide by zero" {:numerator num-str :denominator den-str})))
    (let [g (host-int/gcd n d)
          rn (host-int/quot-trunc n g)
          rd (host-int/quot-trunc d g)]
      (if (zero? (host-int/compare-big rd one))
        (host-int/demote rn)
        (cbor/ratio (host-int/demote rn) (host-int/demote rd))))))


;; =============================================================================
;; Per-dialect classifiers
;; =============================================================================

(defn- classify-clojure
  [text]
  (cond
    (= text "##Inf") {:kind :symbolic-float :value ##Inf :text text}
    (= text "##-Inf") {:kind :symbolic-float :value ##-Inf :text text}
    (= text "##NaN") {:kind :symbolic-float :value ##NaN :text text}

    ;; Decimal M literal: [+-]?[0-9]+(\.[0-9]*)?([eE][+-]?[0-9]+)?M
    (re-matches #"^[+-]?[0-9]+(?:\.[0-9]*)?(?:[eE][+-]?[0-9]+)?M$" text)
    {:kind :decimal :text (subs text 0 (dec (count text)))}

    ;; Binary64 float literal: must contain '.' or 'e'/'E', no 'M'
    (re-matches #"^[+-]?[0-9]+(?:\.[0-9]*(?:[eE][+-]?[0-9]+)?|[eE][+-]?[0-9]+)$" text)
    {:kind :float64 :text text}

    ;; Ratio literal: [+-]?[0-9]+/[0-9]+
    (re-matches #"^([+-]?[0-9]+)/([0-9]+)$" text)
    (let [[_ n d] (re-matches #"^([+-]?[0-9]+)/([0-9]+)$" text)]
      {:kind :ratio :numerator n :denominator d :text text})

    ;; Hex integer: [+-]?0[xX][0-9a-fA-F]+N?
    (re-matches #"^([+-]?)0[xX]([0-9a-fA-F]+)(N?)$" text)
    (let [[_ sign digits big-s] (re-matches #"^([+-]?)0[xX]([0-9a-fA-F]+)(N?)$" text)]
      {:kind :integer :radix 16 :digits digits
       :negative? (= sign "-") :big? (= big-s "N") :text text})

    ;; Octal integer: [+-]?0[0-7]+N?
    (re-matches #"^([+-]?)0([0-7]+)(N?)$" text)
    (let [[_ sign digits big-s] (re-matches #"^([+-]?)0([0-7]+)(N?)$" text)]
      {:kind :integer :radix 8 :digits digits
       :negative? (= sign "-") :big? (= big-s "N") :text text})

    ;; Arbitrary radix integer: [+-]?([0-9]+)[rR]([0-9a-zA-Z]+)
    (re-matches #"^([+-]?)([0-9]+)[rR]([0-9a-zA-Z]+)$" text)
    (let [[_ sign r-str digits] (re-matches #"^([+-]?)([0-9]+)[rR]([0-9a-zA-Z]+)$" text)
          radix (host-int/to-native (host-int/parse-decimal r-str))]
      (if (valid-radix-digits? digits radix)
        {:kind :integer :radix radix :digits digits
         :negative? (= sign "-") :big? false :text text}
        {:kind :invalid :reason :invalid-radix-number :text text}))

    ;; Decimal integer: [+-]?(0|[1-9][0-9]*)N?
    (re-matches #"^([+-]?)(0|[1-9][0-9]*)(N?)$" text)
    (let [[_ sign digits big-s] (re-matches #"^([+-]?)(0|[1-9][0-9]*)(N?)$" text)]
      {:kind :integer :radix 10 :digits digits
       :negative? (= sign "-") :big? (= big-s "N") :text text})

    :else
    {:kind :invalid :reason :invalid-number :text text}))


(defn- classify-python
  [text]
  (let [t (strip-separator :python text)
        lower (str/lower-case t)]
    (if (and (not (re-find #"^0[xob]" lower))
             (re-find #"[.e]" lower))
      {:kind :float64 :text t}
      {:kind :integer :text t})))


(defn- classify-php
  [text]
  {:kind :float64 :text (strip-separator :php text)})


(defn- classify-typescript
  [text]
  (let [t (strip-separator :typescript text)
        lower (str/lower-case t)]
    (cond
      (str/starts-with? lower "0x")
      (let [digits (subs lower 2)]
        (if (re-matches #"[0-9a-f]+" digits)
          {:kind :float64 :radix 16 :digits digits :text text}
          {:kind :invalid :reason :invalid-token :text text}))

      (str/starts-with? lower "0o")
      (let [digits (subs lower 2)]
        (if (re-matches #"[0-7]+" digits)
          {:kind :float64 :radix 8 :digits digits :text text}
          {:kind :invalid :reason :invalid-token :text text}))

      (str/starts-with? lower "0b")
      (let [digits (subs lower 2)]
        (if (re-matches #"[01]+" digits)
          {:kind :float64 :radix 2 :digits digits :text text}
          {:kind :invalid :reason :invalid-token :text text}))

      (re-find #"^0[0-9]" lower)
      {:kind :invalid :reason :legacy-octal :text text}

      :else
      {:kind :float64 :text t})))


(defn classify
  "Classify numeric lexeme `text` under `dialect` (`:clojure`, `:python`,
   `:php`, `:typescript`). Pure and total."
  [dialect text]
  (case dialect
    :clojure (classify-clojure text)
    :python (classify-python text)
    :php (classify-php text)
    :typescript (classify-typescript text)
    {:kind :invalid :reason :unknown-dialect :dialect dialect :text text}))


(defn materialize-classified
  "Construct canonical Jing/host Data from a `classify` result map. Throws
   on `:invalid`."
  [{:keys [kind text radix digits negative? big? numerator denominator value]
    :as classified}]
  (case kind
    :float64 (if radix
               (parse-radix-float64 digits radix)
               (parse-float64 text))
    :symbolic-float (cbor/float64 value)
    :integer (parse-exact-integer digits radix negative? big?)
    :decimal (parse-decimal text)
    :ratio (parse-ratio numerator denominator)
    (throw (ex-info (str "Invalid number: " text)
                    (assoc classified :type :reader-exception :ex-kind :reader-error)))))


(defn materialize
  "Classify and materialize numeric token `text` under `dialect` into
   canonical Jing/host Data."
  [dialect text]
  (materialize-classified (classify dialect text)))


(defn float64-token
  "Materialize a lexeme already known by `dialect`'s grammar to be a
   floating-point literal into Jing float64 content."
  [dialect text]
  (parse-float64 (strip-separator dialect text)))

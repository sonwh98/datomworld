(ns yin.vm.debruijn-code
  "B1 (docs/design/yin.vm.debruijn-vm.md S2): the executable de Bruijn
   image dimension (`:yin.debruijn.code/*`) and its validator. Standalone:
   no lowerer (B2), no VM (B3), no linker (B6) -- only the dimension's
   descriptor, its derived opcode table, its own exact scalar encoder, the
   one function that computes H (`image-hash`), and the validator that is
   the sole admitter of an executable image on this path (S3).

   The opcode table is derived AS DATA from `yin.vm.code/vector-operand-
   table` (S2): every mnemonic's operand shape is read straight off that
   table and renamespaced into `:yin.debruijn.code/*`, except `:var`,
   which splits into `:load-bound` (depth + position) and `:load-free`
   (name), and `:closure`, which trades its parameter-symbol vector for an
   arity and a body pc. `yin.vm.code`, `:yin.code/*`, and the merged
   dormant projection namespace (`yin.vm.debruijn`, `yin.vm.pipeline`) are
   never edited and never reached through a private var; the small
   framing and length-prefix helpers below (`to-hex`, `framed`,
   `int64-le-hex`, `double-le-hex`, `utf8-byte-length`, `code-unit-at`)
   are copied from `yin.vm.debruijn`, which documents them as meant to be
   copied, not imported. The UTF-16 surrogate-pairing guard is
   independently reimplemented to the same rule, not copied, per this
   phase's own instruction.

   This dimension's own executable scalar encoder is deliberately
   distinct from `yin.vm.debruijn/encode-value`: it never applies NFC to
   strings or identifier parts (exact bytes as supplied), it never folds
   an integral double into a long (1 and 1.0 hash distinctly), and it
   supports :ratio and :bigint as their own classes rather than excluding
   them -- the merged projection's canonical-value-table declares both
   out of its own domain; this dimension does not inherit that
   exclusion. Host support for a class is explicit per host (see
   `host-supported-scalar-classes`).

   B1 does not build a decode/receive path: `image-hash`, `encode-image`,
   and `encode-scalar` all go from an already-constructed Clojure value
   (a hand-built canonical instruction vector, or a scalar inside one) to
   its canonical bytes and hash. The 'CLJS receiver refuses a foreign
   image whose wire bytes claim :double for an integral value' rule (S2)
   describes a later receive/decode path this phase does not implement;
   see the final report for why that is out of this file box."
  (:require [clojure.set :as set]
            [dao.jing :as jing]
            [yin.vm :as vm]
            [yin.vm.code :as code])
  #?(:cljd (:import ["dart:typed_data" ByteData])))


;; =============================================================================
;; The opcode table, derived as data from yin.vm.code/vector-operand-table
;; =============================================================================

(defn- renamespace-slots
  "One mnemonic's operand slots, carried over unchanged in shape: only the
   attribute is moved from `:yin.code/*` into this dimension's own
   `:yin.debruijn.code/*` namespace; the kind and order are read straight
   off `code/vector-operand-table`, never hand-copied."
  [slots]
  (mapv (fn [[attr kind]] [(keyword "yin.debruijn.code" (name attr)) kind]) slots))


(def ^:private carried-mnemonics
  "Every mnemonic whose operand shape is unchanged from the named table
   (S2): all of them except `:var` and `:closure`, the two lexical-
   addressing mnemonics this dimension redefines below."
  (disj code/mnemonics :var :closure))


(def opcode-table
  "S2's operand table for `:yin.debruijn.code/*`, in `yin.vm.code/vector-
   operand-table`'s own positional form: mnemonic -> ordered `[attribute
   kind]` operands. `:var` becomes `:load-bound` (a depth and a position,
   both `:uint`) or `:load-free` (a name, `:sym`); `:closure` carries an
   arity (`:uint`) and a body reference (`:pc`) instead of a parameter
   vector. Every other mnemonic is `renamespace-slots` applied to
   `code/vector-operand-table`'s own entry, so a change to that table's
   shape for a carried mnemonic is inherited here automatically rather
   than silently drifting out of sync."
  (into (into {}
              (map (fn [mnemonic]
                     [mnemonic (renamespace-slots (get code/vector-operand-table mnemonic))]))
              carried-mnemonics)
        {:load-bound [[:yin.debruijn.code/depth :uint] [:yin.debruijn.code/position :uint]],
         :load-free [[:yin.debruijn.code/name :sym]],
         :closure [[:yin.debruijn.code/arity :uint] [:yin.debruijn.code/body :pc]]}))


(def mnemonics
  (set (keys opcode-table)))


;; =============================================================================
;; The descriptor
;; =============================================================================

(def scalar-classes
  "This dimension's own executable scalar domain (S2), in the fixed order
   that assigns each class its tag byte below. Deliberately distinct from
   `yin.vm.debruijn/canonical-value-table`: :ratio and :bigint are their
   own classes here, not excluded, and :int64 is named :long (S2's own
   wording) with no NFC or integral-double folding anywhere in this
   table's encoding."
  [:nil :bool :long :double :ratio :bigint :char :string :keyword :symbol
   :vector :list :map :set])


(def lowering-contract-version
  "Bumped whenever this dimension's opcode table, scalar table, or byte
   rules change shape -- the version the descriptor hash folds in, so an
   incompatible lowering never collides with an old image's H."
  1)


(def descriptor
  "The `:yin.debruijn.code/*` dimension declared as data (S2): a lowering-
   contract version, arity (the opcode table's own mnemonic count), the
   ordered opcode table, the canonical encoding rule, and one lift
   morphism from `:yin.debruijn.code/*` to `:yin.code/*` -- declared and
   exported here as a documented contract point only. The morphism's
   exact shape and implementation are B2's file box; this phase names
   only its target (`:yin.code/*`) here as data; its intended rule
   (diagnostic binder names or synthesized names for bound positions) is
   prose, kept in this docstring rather than hashed, per P3-4 of the
   debruijn-b1-fix review: this whole vector feeds `descriptor-hash` (via
   `encode-scalar`), so an editorial change to a hashed sentence would
   fork every image's H even though `lowering-contract-version` already
   guards every real shape change. `image-hash`'s own docstring carries
   the H formula in prose for the same reason -- descriptor stays pure
   data: keywords, version numbers, arity, slots."
  [[:yin.debruijn.code/dimension :dim/lowering-contract-version lowering-contract-version]
   [:yin.debruijn.code/dimension :dim/arity (count opcode-table)]
   [:yin.debruijn.code/dimension :dim/slots opcode-table]
   [:yin.debruijn.code/dimension
    :dim/encoding
    {:hash :sha256,
     :scalar-classes scalar-classes,
     :framing :tagged-and-length-delimited,
     :fixed-width-kinds [:uint :pc],
     :nfc false,
     :integral-double-folding false,
     :map-set-order :canonical-encoded-bytes}]
   [:yin.debruijn.code/dimension :dim/lift-to [:yin.code/*]]])


;; =============================================================================
;; Small framing and length-prefix helpers
;; =============================================================================
;; Copied from `yin.vm.debruijn`, which documents them as meant to be
;; copied rather than imported: the width-padded hex renderer, the
;; tagged-and-length-delimited framing shape, the little-endian int64 and
;; IEEE-754 double byte rules, the UTF-8 byte-length counter, and the
;; per-host UTF-16 code-unit reader those two need. Not one private var of
;; that namespace is referenced.

(def ^:private hex-digit-chars "0123456789abcdef")


(defn- to-hex
  "Width-padded lowercase hex of the non-negative integer n."
  [n width]
  (let [base (if (zero? n)
               "0"
               (loop [n n, acc ""]
                 (if (zero? n)
                   acc
                   (recur (unsigned-bit-shift-right n 4)
                          (str (nth hex-digit-chars (bit-and n 0xf)) acc)))))
        padded (str (apply str (repeat width "0")) base)]
    (subs padded (- (count padded) width))))


(defn- code-unit-at
  [s i]
  #?(:clj (int (.charAt ^String s i))
     :cljs (.charCodeAt s i)
     :cljd (.codeUnitAt s i)))


(defn- utf8-byte-length
  "The byte length of `s` under UTF-8, counted from its code units so
   every host agrees without encoding anything: a surrogate pair is one
   4-byte sequence, never two 3-byte ones."
  [s]
  (let [unit-length (fn [c]
                      (cond (< c 0x80) 1
                            (< c 0x800) 2
                            :else 3))
        n (count s)]
    (loop [i 0, total 0]
      (if (>= i n)
        total
        (let [c (code-unit-at s i)]
          (if (and (<= 0xD800 c 0xDBFF)
                   (< (inc i) n)
                   (<= 0xDC00 (code-unit-at s (inc i)) 0xDFFF))
            (recur (+ i 2) (+ total 4))
            (recur (inc i) (+ total (unit-length c)))))))))


(def ^:private class-tag
  (into {} (map-indexed (fn [i c] [c (to-hex i 2)])) scalar-classes))


(defn- framed
  "One self-delimiting encoded part under this dimension's own class-tag
   table: the class's tag byte, the 8-hex UTF-8 byte length of `content`,
   and `content` itself."
  [class content]
  (str (get class-tag class) (to-hex (utf8-byte-length content) 8) content))


(defn- int64-le-hex
  "8 bytes, two's complement, little-endian. A :cljs number -- which this
   dimension only lets reach here as a safe integer, per the CLJS
   classification rule below -- decomposes through floor and mod by
   2^32, whose low bytes survive the host's 32-bit bit ops."
  [v]
  #?(:cljs (let [lo (mod v 4294967296)
                 hi (js/Math.floor (/ v 4294967296))
                 byte-at (fn [x i]
                           (to-hex (bit-and (unsigned-bit-shift-right x (* i 8))
                                            0xff)
                                   2))]
             (apply str (map #(byte-at (if (< % 4) lo hi) (mod % 4)) (range 8))))
     :default (let [n (long v)]
                (apply str (map #(to-hex (bit-and (unsigned-bit-shift-right n (* % 8)) 0xff) 2)
                                (range 8))))))


(defn- double-le-hex
  "IEEE-754 bits, little-endian, one quiet NaN encoding regardless of a
   host's NaN payload. :clj reads the bits through doubleToLongBits,
   :cljs through a DataView with littleEndian explicitly true, :cljd
   through a big-endian ByteData store read in reverse."
  [v]
  (if (not= v v)
    "000000000000f87f"
    #?(:clj (int64-le-hex (Double/doubleToLongBits (double v)))
       :cljs (let [view (js/DataView. (js/ArrayBuffer. 8))]
               (.setFloat64 view 0 v true)
               (apply str (map #(to-hex (.getUint8 view %) 2) (range 8))))
       :cljd (let [bd (ByteData. 8)]
               (.setFloat64 bd 0 v)
               (apply str (map #(to-hex (.getUint8 bd %) 2) (range 7 -1 -1)))))))


;; =============================================================================
;; Host scalar classification (S2 item 4)
;; =============================================================================

(defn- well-formed-utf16?
  "True when every surrogate in `s` is paired: the precondition every
   host's UTF-8 encoder needs to agree. Matches the rule `yin.vm.debruijn/
   well-formed-utf16?` enforces, independently written rather than
   reused, per this phase's own instruction."
  [s]
  (let [n (count s)]
    (loop [i 0]
      (if (>= i n)
        true
        (let [c (code-unit-at s i)]
          (cond
            (<= 0xDC00 c 0xDFFF) false
            (<= 0xD800 c 0xDBFF) (if (and (< (inc i) n)
                                          (<= 0xDC00 (code-unit-at s (inc i)) 0xDFFF))
                                   (recur (+ i 2))
                                   false)
            :else (recur (inc i))))))))


(defn- ident-parts-ok?
  [x]
  (and (or (nil? (namespace x)) (well-formed-utf16? (namespace x)))
       (well-formed-utf16? (name x))))


(defn- host-char?
  "True only where the host has a character type distinct from a
   one-codepoint string (S2 item 4): the JVM only. On CLJS this always
   returns false -- JS has no character type at all. On ClojureDart,
   `char?` does NOT reliably distinguish a genuine char from an ordinary
   one-codepoint string (verified: `(dc/scalar-class \"a\")` returned
   :char on Dart before this fix), so this dimension never classifies a
   CLJD value as :char either, rather than trust an ambiguous host
   predicate."
  #_{:clj-kondo/ignore [:unused-binding]}
  [v]
  #?(:cljd false :cljs false :clj (char? v)))


(defn- host-ratio?
  "Ratio is supported as its own class on the JVM, where `clojure.lang.
   Ratio` and `ratio?` are real, unambiguous types; refused on CLJS (S2
   item 4: 'JS has neither natively') and on ClojureDart, whose cljd.core
   defines no `ratio?` and no ratio type at all -- unlike `yin.vm.debruijn`
   and `dao.jing.cbor`, this dimension defines no project-local Rational
   carrier of its own to fill that gap, so Dart falls in with CLJS here."
  #_{:clj-kondo/ignore [:unused-binding]}
  [v]
  #?(:cljd false :cljs false :clj (ratio? v)))


(defn- host-bigint?
  "Bigint is supported only where this repo already has a host-verified,
   unambiguous predicate for it: `clojure.lang.BigInt` / `BigInteger` on
   the JVM, distinct from `int?` (which is also true for both on the
   JVM, so it cannot be used to tell them apart). `yin.vm.debruijn/
   numeric-class` -- the existing repo pattern for cross-host numeric
   classification -- does not attempt bigint detection on :cljd either
   (its :cljd branch falls straight to double-class for anything
   `int?` does not claim); this dimension matches that absence rather
   than guess an unverified ClojureDart class name that could either
   silently misclassify or fail to compile. A Dart or JS bigint value is
   refused with :unsupported-value rather than encoded."
  #_{:clj-kondo/ignore [:unused-binding]}
  [v]
  #?(:cljd false
     :cljs false
     :clj (or (instance? clojure.lang.BigInt v) (instance? java.math.BigInteger v))))


(defn- host-long?
  "A JVM boxed integral type; a CLJS safe integer (S2 item 4's own
   wording); a Dart native int (`int?`), the same test `yin.vm.debruijn/
   numeric-class` already uses for this host."
  [v]
  #?(:clj (or (instance? Long v) (instance? Integer v) (instance? Short v) (instance? Byte v))
     :cljs (and (number? v) (js/Number.isSafeInteger v))
     :cljd (int? v)))


(defn- host-double?
  "On the JVM, exactly `java.lang.Double` -- not 'every remaining number':
   a JVM BigDecimal or Float is a distinct type this dimension's 14-class
   domain does not declare, and folding either into :double would hash
   its value at whatever precision `(double v)` happens to produce
   instead of refusing it, exactly the silent canonicalization S2
   forbids. On CLJS and CLJD, every remaining number: not a ratio, not a
   bigint, not a long by this host's own rule -- CLJS has no BigDecimal/
   Float distinction from a plain number to lose, so the complement rule
   stays correct there."
  [v]
  #?(:clj (instance? Double v)
     :default (and (number? v) (not (host-long? v)) (not (host-ratio? v)) (not (host-bigint? v)))))


(defn scalar-class
  "Classify `v` under this dimension's own S2 scalar domain: the class
   keyword when `v` is representable, nil when it is not. Recursive over
   vectors, lists, maps, and sets -- a collection classifies only when
   every element does. No normalization and no folding of any kind
   happen here; classification is the identity question, not the byte
   rule (`encode-scalar` owns the bytes)."
  [v]
  (cond
    (nil? v) :nil
    (boolean? v) :bool
    (host-char? v) :char
    (host-ratio? v) :ratio
    (host-bigint? v) :bigint
    (host-long? v) :long
    (host-double? v) :double
    (string? v) (when (well-formed-utf16? v) :string)
    (keyword? v) (when (ident-parts-ok? v) :keyword)
    (symbol? v) (when (ident-parts-ok? v) :symbol)
    (vector? v) (when (every? #(some? (scalar-class %)) v) :vector)
    (map? v) (when (and (not (record? v))
                        (every? (fn [[k x]]
                                  (and (some? (scalar-class k))
                                       (some? (scalar-class x))))
                                v))
               :map)
    (set? v) (when (every? #(some? (scalar-class %)) v) :set)
    (sequential? v) (when (every? #(some? (scalar-class %)) v) :list)
    :else nil))


(defn scalar-value?
  [v]
  (some? (scalar-class v)))


(def host-supported-scalar-classes
  "The scalar classes this host's encoder can represent (S2 item 4/5):
   every declared class except the ones this host's own predicates above
   never return. CLJS and CLJD both exclude :char, :ratio, and :bigint
   (see `host-char?`, `host-ratio?`, `host-bigint?`); the JVM excludes
   none."
  (into #{}
        (remove #?(:cljs #{:char :ratio :bigint} :cljd #{:char :ratio :bigint} :clj #{}))
        scalar-classes))


;; =============================================================================
;; The exact executable scalar encoder (S2 item 3)
;; =============================================================================

(defn- ident-content
  "Namespace and name framed separately, exactly as supplied: no NFC.
   An absent namespace frames as :nil rather than an empty string, so
   `sym` and `ns/sym` never collide."
  [x]
  (str (if-let [ns (namespace x)] (framed :string ns) (framed :nil ""))
       (framed :string (name x))))


(defn encode-scalar
  "This dimension's own scalar encoder (S2 item 3), deliberately distinct
   from `yin.vm.debruijn/encode-value`: exact numeric bits and exact
   UTF-8 bytes, no NFC, no integral-double-to-long folding, :ratio and
   :bigint as their own classes, map and set entries ordered by their own
   encoded bytes. A value outside `scalar-class`'s domain -- including an
   unpaired UTF-16 surrogate, which `well-formed-utf16?` already refuses
   at classification -- throws a qualified :unsupported-value diagnostic
   naming the value; nothing is silently canonicalized."
  [v]
  (case (scalar-class v)
    :nil (framed :nil "")
    :bool (framed :bool (if v "01" "00"))
    :long (framed :long (int64-le-hex v))
    :double (framed :double (double-le-hex v))
    ;; :ratio never arises on :cljs or :cljd (`host-ratio?` is always
    ;; false on both), but `case` still compiles every branch, and neither
    ;; host resolves a bare `numerator`/`denominator` var (ClojureDart has
    ;; no ratio type at all; dao.jing.cbor's own Rational carrier needs
    ;; its own host-specific accessor wrapper for exactly this reason) --
    ;; guarded out here rather than left as an unresolved-symbol error on
    ;; a branch that can't run.
    :ratio #?(:cljs (framed :ratio "")
              :cljd (framed :ratio "")
              ;; JVM Ratio numerator/denominator are BigIntegers and can
              ;; exceed int64 range (e.g. (/ 10000000000000000000N 3)); a
              ;; component outside that range is refused with the same
              ;; qualified diagnostic every other out-of-domain value
              ;; gets, rather than an unqualified IllegalArgumentException
              ;; from int64-le-hex's own (long v) cast.
              :default (let [num (numerator v), den (denominator v)]
                         (when-not (and (<= Long/MIN_VALUE num Long/MAX_VALUE)
                                        (<= Long/MIN_VALUE den Long/MAX_VALUE))
                           (throw (ex-info "Ratio component outside int64 range in executable scalar encoding"
                                           {:rule :unsupported-value, :value v})))
                         (framed :ratio (str (framed :long (int64-le-hex num))
                                             (framed :long (int64-le-hex den))))))
    :bigint (framed :bigint (str v))
    :char (framed :char (str v))
    :string (framed :string v)
    :keyword (framed :keyword (ident-content v))
    :symbol (framed :symbol (ident-content v))
    :vector (framed :vector (apply str (map encode-scalar v)))
    :list (framed :list (apply str (map encode-scalar v)))
    :map (let [entries (sort (map (fn [[k x]] [(encode-scalar k) (encode-scalar x)]) v))]
           (when (some (fn [[a b]] (= (first a) (first b))) (partition 2 1 entries))
             (throw (ex-info "Map keys collide in canonical executable encoding"
                             {:rule :unsupported-value, :value v})))
           (framed :map (apply str (mapcat identity entries))))
    :set (let [elements (sort (map encode-scalar v))]
           (when (some (fn [[a b]] (= a b)) (partition 2 1 elements))
             (throw (ex-info "Set elements collide in canonical executable encoding"
                             {:rule :unsupported-value, :value v})))
           (framed :set (apply str elements)))
    (throw (ex-info "Unsupported value in executable scalar encoding"
                    {:rule :unsupported-value, :value v}))))


;; =============================================================================
;; The canonical positional instruction vector's bytes, and image-hash
;; =============================================================================

(def ^:private canonical-mnemonic-order
  "A fixed order for the mnemonic tag table, independent of hash-map
   iteration order on any host: the mnemonic keywords sorted."
  (vec (sort mnemonics)))


(def ^:private mnemonic-tag
  (into {} (map-indexed (fn [i m] [m (to-hex i 2)])) canonical-mnemonic-order))


(defn- mnemonic-of
  [t]
  (when (and (vector? t) (seq t)) (nth t 0)))


(defn- tuple-kinds
  [t]
  (mapv second (get opcode-table (mnemonic-of t))))


(defn- encode-operand
  "One operand's bytes under its declared kind (S2 item 6): a :data/:sym/
   :kw operand is a scalar under `encode-scalar` (a bare symbol or
   keyword is already inside that domain); :str and :bool are framed
   directly; :uint, :pc, and :buffer are fixed-width 8-byte little-endian
   integers with no tag or length prefix at all -- self-delimiting by
   construction, since every tuple's arity is fixed by its mnemonic, so a
   framing tag would only be redundant weight."
  [kind x]
  (case kind
    (:data :sym :kw) (encode-scalar x)
    ;; a non-nil :str operand is a string, i.e. exactly `encode-scalar`'s
    ;; :string class -- routed through it rather than framed directly, so
    ;; it gets the same well-formed-utf16? guard a :const string value
    ;; already gets, instead of hashing an unpaired surrogate unrefused
    :str (if (nil? x) (framed :string "") (encode-scalar x))
    :bool (framed :bool (if x "01" "00"))
    (:uint :pc :buffer) (int64-le-hex (or x 0))))


(defn- encode-tuple
  [t]
  (let [kinds (tuple-kinds t)]
    (apply str (get mnemonic-tag (mnemonic-of t))
           (map-indexed (fn [i kind] (encode-operand kind (nth t (inc i)))) kinds))))


(defn encode-image
  "The canonical positional instruction vector's own bytes (S2 item 6):
   every pc-indexed tuple's bytes, in pc order, concatenated with no
   outer header -- pc order and each tuple's fixed arity are the only
   framing the whole vector needs."
  [v]
  (apply str (map encode-tuple v)))


(def descriptor-hash
  "The descriptor's own content hash, under this dimension's own
   encoder -- the same 'hash the descriptor through its own canonical
   encoder' shape `yin.vm.debruijn/dimension-hash` uses for its
   descriptor, computed here with this dimension's distinct encoder
   rather than that one."
  (jing/sha256 (encode-scalar descriptor)))


(defn image-hash
  "The ONE function that computes H (S2 item 6): `jing/sha256` over the
   descriptor hash (which already folds in the lowering-contract
   version) concatenated with the canonical instruction vector's own
   bytes -- pc-indexed tuples, refs already resolved to pcs, no header,
   exact scalar bytes. `jing/sha256`, not `jing/sha256-bytes`: this
   dimension's canonical encoding is hex text (`encode-image` returns a
   string, via `framed`/`to-hex`), the same shape `jing/sha256` hashes
   for `yin.vm.debruijn/dimension-hash`, so hashing the string directly
   is the fit; `sha256-bytes` would apply to a raw byte buffer this
   encoder never produces. H is the ONLY executable and sharing identity
   this file computes: no projection fingerprint, no second identity."
  [v]
  (jing/sha256 (str descriptor-hash (encode-image v))))


;; =============================================================================
;; Host-boundary scalar-class support (S2 item 5)
;; =============================================================================

(defn image-scalar-classes
  "Every scalar class this image uses, scanned from every :data-kind
   operand across every mnemonic -- not only :const's value, but also
   :store-get's and :store-put's :data operands, since those are hashed
   bytes too and a scalar class hiding only there would let S2's host-
   support scan (below) miss it. S2's own wording names :const alone;
   scanning every :data operand is strictly more conservative than that
   floor, never less, so it does not weaken the design's own rule."
  [v]
  (into #{}
        (mapcat (fn [t]
                  (keep-indexed (fn [i kind]
                                  (when (= :data kind) (scalar-class (nth t (inc i)))))
                                (tuple-kinds t))))
        v))


(defn host-unsupported-classes
  [v]
  (set/difference (image-scalar-classes v) host-supported-scalar-classes))


(defn validate-host-support
  "S2 item 5's host-boundary refusal: nil when every scalar class `v`
   uses is representable on this host, else a qualified :unsupported-
   value diagnostic naming the missing classes. A host-boundary outcome,
   worded distinctly from a :dao.stream/gap, per the design."
  [v]
  (let [missing (host-unsupported-classes v)]
    (when (seq missing)
      {:rule :unsupported-value, :boundary :host, :missing-classes missing})))


;; =============================================================================
;; The validator (S2 item 7, S3)
;; =============================================================================
;; Two passes: generic structural well-formedness over this dimension's
;; own opcode table (mirroring `yin.vm.code/well-formed-vector?`'s rules,
;; not its code -- that private rule vector operates over the named
;; table and is not reused), then the scope rule S3 adds: a :load-bound
;; operand must address a real enclosing frame and a real parameter
;; inside it, not merely carry two nonnegative integers.

(defn- tuple-defect
  [rule pc]
  {:rule rule, :pc pc})


(defn- nonempty
  [v]
  (when-not (and (vector? v) (pos? (count v))) (tuple-defect :nonempty 0)))


(defn- mnemonic-rule
  [v]
  (some (fn [pc]
          (when-not (contains? mnemonics (mnemonic-of (nth v pc)))
            (tuple-defect :mnemonic pc)))
        (range (count v))))


(defn- arity-rule
  [v]
  (some (fn [pc]
          (let [t (nth v pc)]
            (when-not (= (inc (count (get opcode-table (mnemonic-of t)))) (count t))
              (tuple-defect :arity pc))))
        (range (count v))))


(defn- nonneg-int?
  "A nonnegative integer, bounded to the safe-integer range on CLJS: JVM
   `integer?` already excludes an unsafe-magnitude double, but CLJS's
   `integer?` is true for any integral-valued JS number, including one
   past 2^53 that is not exactly representable. Left unbounded there, an
   image like `[:call 1e30 true]` would pass this validator on CLJS while
   the same image is correctly rejected on JVM (`integer?` false for a
   Double), and `int64-le-hex`'s own floor/mod decomposition would then
   produce silently inexact bytes for it -- one image, valid on one host
   and not another, is exactly what the sole-admitter validator exists to
   prevent."
  [x]
  (and (integer? x) (not (neg? x))
       #?(:cljs (js/Number.isSafeInteger x) :default true)))


(def ^:private operand-kind-checks
  "Every operand kind this dimension's table uses except :pc, which
   `target-bounds-rule` owns because judging it needs the vector's own
   length."
  {:data vm/plain-data?,
   :sym symbol?,
   :kw keyword?,
   ;; a non-nil :str operand must also be well-formed UTF-16 (the same
   ;; guard `encode-scalar` applies to a :string value), so a malformed
   ;; :str operand is rejected here, before hashing -- not only inside
   ;; `encode-operand`
   :str (fn [x] (or (nil? x) (and (string? x) (well-formed-utf16? x)))),
   :bool (fn [x] (or (nil? x) (boolean? x))),
   :buffer (fn [x] (or (nil? x) (nonneg-int? x))),
   :uint nonneg-int?})


(defn- operand-kind-rule
  [v]
  (some (fn [pc]
          (let [t (nth v pc), kinds (tuple-kinds t)]
            (some (fn [i]
                    (let [check (get operand-kind-checks (nth kinds (dec i)))]
                      (when (and check (not (check (nth t i))))
                        (tuple-defect :operand-kind pc))))
                  (range 1 (count t)))))
        (range (count v))))


(def ^:private saturated-operands
  "The `[mnemonic index]` operands a canonical tuple must materialize
   even though their :operand-kind check alone would tolerate nil -- the
   same set `yin.vm.code`'s own :saturation rule judges
   (`code/vector-operand-table`'s saturated pairs), over this
   dimension's carried :gensym, :stream-make, :call, and :ffi-call.
   :ffi-call's argc (index 2) is also already rejected as nil by the
   stricter `:uint nonneg-int?` kind check above, so this entry is
   redundant today; it is kept anyway so this set matches the source
   table's own saturated pairs exactly, rather than silently drifting if
   the :uint kind check is ever relaxed."
  #{[:gensym 1] [:stream-make 1] [:call 2] [:ffi-call 2]})


(defn- saturation-rule
  [v]
  (some (fn [pc]
          (let [t (nth v pc)]
            (when (some #(and (contains? saturated-operands [(mnemonic-of t) %])
                              (nil? (nth t %)))
                        (range 1 (count t)))
              (tuple-defect :saturation pc))))
        (range (count v))))


(defn- target-bounds-rule
  [v]
  (let [length (count v)]
    (some (fn [pc]
            (let [t (nth v pc), kinds (tuple-kinds t)]
              (some (fn [i]
                      (when (= :pc (nth kinds (dec i)))
                        (let [x (nth t i)]
                          (when-not (and (nonneg-int? x) (<= x (dec length)))
                            (tuple-defect :target-bounds pc)))))
                    (range 1 (count t)))))
          (range (count v)))))


(defn- terminator-rule
  [v]
  (let [pc (dec (count v))]
    (when-not (contains? code/terminators (mnemonic-of (nth v pc)))
      (tuple-defect :terminator pc))))


(def ^:private structural-rules
  [nonempty mnemonic-rule arity-rule operand-kind-rule saturation-rule
   target-bounds-rule terminator-rule])


(defn well-formed-image?
  "Generic structural well-formedness of one canonical instruction vector
   `v` against this dimension's own `opcode-table`: nil when `v` is well
   formed, else the first defect as `{:rule r :pc p}` -- `:nonempty`,
   `:mnemonic`, `:arity`, `:operand-kind`, `:saturation`, `:target-
   bounds`, or `:terminator`. Malformed rows and out-of-range :pc
   operands (jump/branch-false targets, closure bodies) are rejected
   here; :load-bound's own scope rule is `scope-defect`, below."
  [v]
  (some #(% v) structural-rules))


(defn- successors
  [v pc]
  (let [t (nth v pc), op (mnemonic-of t)]
    (cond
      (= :jump op) [(nth t 1)]
      (= :branch-false op) [(nth t 1) (inc pc)]
      (contains? code/terminators op) []
      :else [(inc pc)])))


(defn- walk-body
  "Breadth-first from `entry` over `successors`, recording every visited
   pc's enclosing arity chain (innermost first) into `owner0` and
   collecting the `[body-pc chain]` of every nested :closure this walk
   passes through -- a :closure instruction's own body is a different
   body, scheduled by the caller, never merged into this one; the walk
   continues past the :closure instruction itself into the rest of this
   body.

   A pc this walk reaches that `owner0` already claims under a DIFFERENT
   chain -- reachable through a cross-body :jump, not only through a
   second :closure declaration -- stops the walk and reports that pc as
   `:conflict` rather than silently keeping whichever chain got there
   first: two different declared scopes for one pc is a malformed image,
   not a race to resolve."
  [v entry chain owner0]
  (loop [frontier [entry], owner owner0, nested []]
    (if-let [pc (first frontier)]
      (cond
        (or (neg? pc) (>= pc (count v)))
        (recur (rest frontier) owner nested)

        (contains? owner pc)
        (if (= (get owner pc) chain)
          (recur (rest frontier) owner nested)
          {:owner owner, :nested nested, :conflict pc})

        :else
        (let [t (nth v pc), op (mnemonic-of t)
              nested' (if (= :closure op)
                        (conj nested [(nth t 2) (cons (nth t 1) chain)])
                        nested)]
          (recur (into (vec (rest frontier)) (successors v pc))
                 (assoc owner pc chain)
                 nested')))
      {:owner owner, :nested nested, :conflict nil})))


(defn- all-body-chains
  "pc -> enclosing arity chain (innermost first), for every pc reachable
   from the image's own root body (entry 0, empty chain) or from any
   :closure's declared body -- the S3 scope resolver's own data, built
   once per validation rather than per :load-bound operand. A pc this
   walk never reaches (dead code in a hand-built fixture) is simply
   absent from the result; `scope-defect` treats that as a defect too,
   since a :load-bound at an unreached pc cannot be validated at all.

   Two :closure instructions -- anywhere in the image, in either order,
   walked first or second -- declaring different arities for the SAME
   body pc is a conflict, not a choice of which one wins: returns
   `{:conflict pc}` the moment that pc's second, disagreeing chain is
   reached, instead of `{:owner ...}`."
  [v]
  (loop [queue [[0 '()]], owner {}]
    (if-let [[entry chain] (first queue)]
      (cond
        (and (contains? owner entry) (not= (get owner entry) chain))
        {:conflict entry}

        (contains? owner entry)
        (recur (rest queue) owner)

        :else
        (let [{:keys [owner nested conflict]} (walk-body v entry chain owner)]
          (if conflict
            {:conflict conflict}
            (recur (into (vec (rest queue)) nested) owner))))
      {:owner owner})))


(defn scope-defect
  "S3's second scope check, over and above `well-formed-image?`'s
   nonnegative-shape check on every :uint operand: every :load-bound
   [depth position] must address a real enclosing frame and a real
   parameter inside it, walked against each body's own declared arity
   and its enclosing-body chain. A shape-valid `[:load-bound [5 0]]`
   pointing past a body's declared arity or enclosing chain is a defect
   here even though `well-formed-image?` alone would accept it.

   Two :closure instructions declaring different arities for the same
   body pc -- in either declaration order, and whether reached through
   ordinary body scheduling or a cross-body :jump -- are `all-body-
   chains`'s own `:scope-conflict` defect, checked first: which chain
   would otherwise 'win' depends on walk order, so this is rejected
   before any :load-bound is even examined.

   Returns the first defect as `{:rule :scope :pc p}` or
   `{:rule :scope-conflict :pc p}`, or nil."
  [v]
  (let [{:keys [owner conflict]} (all-body-chains v)]
    (if conflict
      (tuple-defect :scope-conflict conflict)
      (some (fn [pc]
              (let [t (nth v pc)]
                (when (= :load-bound (mnemonic-of t))
                  (let [depth (nth t 1), position (nth t 2), chain (get owner pc)]
                    (when (or (nil? chain)
                              (not (nonneg-int? depth))
                              (not (nonneg-int? position))
                              (>= depth (count chain))
                              (>= position (nth chain depth)))
                      (tuple-defect :scope pc))))))
            (range (count v))))))


(defn image-defect
  "The B1 image validator (S2, S3): the first defect in `v`, checking
   generic shape first, then the scope rule S3 adds. nil when `v` is a
   valid executable image on this path -- the only admitter of one; no
   projected reader participates."
  [v]
  (or (well-formed-image? v) (scope-defect v)))

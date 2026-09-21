(ns yin.vm.debruijn
  "Alpha-canonical de Bruijn projection of the named Universal AST
   (docs/design/yin.vm.debruijn-projection.md).

   This namespace is D0-D3 of that design: the published
   :yin.debruijn/* dimension and its hash domain, §5's canonical value
   table with the NFC seam, §2's root framing and input validation, §3's
   scope resolver, §4-§5's Merkle records — node hashes over the
   dimension hash, hash-consed records with canonically spelled scalars,
   and the root fingerprint — the settled canonical byte encoder whose
   rules make those bytes identical on the JVM, JS, and Dart hosts, and
   the pure d5 storage adapter over the projected records. The dao.stream
   forward-step is D4's; it is not here.

   The projection is an interpreter over plain AST datoms and never
   executes values, primitives, streams, continuations, or effects. Its
   input domain is a fully macro-expanded Universal AST, so an
   unexpanded macro call site is a terminal diagnostic: an application
   whose operator is a :lambda carrying :macro? true is unexpanded. A free
   variable whose name matches a registry macro cannot be detected from
   these datoms — that detection limit is inherited and documented here,
   not enforced.

   Input contract (§2): assert-only d5 datoms (only the reserved
   :db/retract op is diagnosed; any other provenance m stays valid, and t
   and m never enter projected identity); exactly one :yin/root true fact
   per graph; the fact index resets at every root-frame boundary because
   emitter tempids may restart at -16; attributes outside the :yin
   namespace are ignored; :yin/tail? and :yin/macro-name are
   tolerated-and-ignored emission metadata; an unknown :yin/* attribute
   on a walked node is a diagnostic.

   Scope convention (§3, the owner's settled decisions): frame depth 0 is
   the innermost frame, position is the source parameter index with the
   leftmost parameter at 0, frames search inner-to-outer, and positions
   search right-to-left within a frame — duplicate parameters are
   rightmost-wins, matching `yin.vm.engine/bind-params`. A bound
   occurrence is {:bound [frame-depth position]}; a free one {:free name}.

   Canonical limits fixed in D0 (§5): NFC normalisation collisions and
   the intentional 1/1.0 int64 collision are inherited limits of the
   repository encoding; the Dart NFC source is the unorm_dart package
   (Unicode 16.0, owner-settled 2026-09-21) behind the one
   host-dispatched `normalize-nfc` seam."
  (:require [dao.datom :as datom]
            [dao.jing :as jing]
            [dao.stream :as stream]
            #?(:cljd ["package:unorm_dart/unorm_dart.dart" :as unorm]))
  #?(:cljd (:import ["dart:typed_data" ByteData Uint8List])))


;; =============================================================================
;; D0: the published dimension and its hash domain (§4, datom.md §META-PROTOCOL)
;; =============================================================================

(def canonical-value-table
  "§5's canonical value table as data: the classes a projected scalar may
   carry and the rule that fixes each one's encoding. Bounds are symbolic
   (:signed-int64, :ieee-754-safe-integer) so the table — and therefore
   the descriptor that embeds it — prints identically on every host;
   `canonical-class` below pins the numbers. D3's encoder consumes this
   table; D0 consumes it for validation."
  {:classes [:nil :bool :int64 :double :string :bytes :keyword :symbol
             :map :set :vector :list],
   :framing {:slots :tagged-and-length-delimited},
   :nil {:encoding :own-tag},
   :bool {:bytes {false "00", true "01"}},
   :int64 {:domain :signed-int64, :endianness :little},
   :double {:format :ieee-754,
            :nan :one-quiet-nan,
            :signed-zero :distinct,
            :in-range-integral :int64},
   :string {:encoding :utf-8, :normalization :nfc, :framing :length-prefixed},
   :bytes {:framing :length-prefixed},
   :keyword {:components [:namespace :name], :encoding :nfc-utf-8},
   :symbol {:components [:namespace :name], :encoding :nfc-utf-8},
   :map {:key-order :canonical-encoded-bytes},
   :set {:element-order :canonical-encoded-bytes},
   ;; vectors and lists are distinct classes: `vector?` observes the
   ;; difference, so one class would be an undeclared semantic collision
   :vector {:order :positional},
   :list {:order :positional},
   :numbers {:javascript {:int64 :safe-integer-only,
                          :unsafe-integer :diagnostic},
             :out-of-domain [:bigint :ratio :char :other-numeric]},
   :limits [:nfc-normalization-collisions
            :int64-integral-double-collision]})


(def dimension-slots
  "The projected d5 semantic slots in §4's declared order, each as
   [slot-name slot-type slot-role]. Roles: :scalar enters node identity;
   :child is an ordered child ref carrying child hashes; :marker is
   storage/index (§5 excludes :yin.debruijn/hash and :yin.debruijn/root
   from every node hash). :yin.debruijn/operands is the one
   ordered-vector slot."
  [[:yin.debruijn/hash :hash :marker]
   [:yin.debruijn/type :keyword :scalar]
   [:yin.debruijn/arity :int64 :scalar]
   [:yin.debruijn/bound [:tuple :int64 :int64] :scalar]
   [:yin.debruijn/free :symbol :scalar]
   [:yin.debruijn/value :canonical :scalar]
   [:yin.debruijn/op :keyword :scalar]
   [:yin.debruijn/key :symbol :scalar]
   [:yin.debruijn/prefix :string :scalar]
   [:yin.debruijn/buffer :int64 :scalar]
   [:yin.debruijn/parked-id :keyword :scalar]
   [:yin.debruijn/macro? :bool :scalar]
   [:yin.debruijn/body :ref :child]
   [:yin.debruijn/operator :ref :child]
   [:yin.debruijn/operands [:ordered-vector :ref] :child]
   [:yin.debruijn/test :ref :child]
   [:yin.debruijn/consequent :ref :child]
   [:yin.debruijn/alternate :ref :child]
   [:yin.debruijn/target :ref :child]
   [:yin.debruijn/val-node :ref :child]
   [:yin.debruijn/source :ref :child]
   [:yin.debruijn/root :bool :marker]])


(def descriptor
  "The :yin.debruijn/* dimension published per datom.md's meta-protocol:
   the small d3 subgraph [s a v] declaring arity, ordered slots, the
   canonical encoding rule parameterized by the slot types, and the
   anchor morphisms. The content hash of this subgraph — `dimension-hash`
   below — IS the dimension's identity and the projection's hash domain
   separator: not an ad hoc text string."
  [[:yin.debruijn/dimension :dim/arity 22]
   [:yin.debruijn/dimension :dim/slots dimension-slots]
   [:yin.debruijn/dimension
    :dim/encoding
    {:hash :sha256,
     :node-hash "SHA-256(dimension-hash || encode(tag-specific-slots-in-descriptor-order))",
     :slot-order :descriptor,
     :tag-specific-slots-only true,
     :hash-excluded-slots [:yin.debruijn/hash :yin.debruijn/root],
     :child-order :declared,
     :values canonical-value-table}]
   [:yin.debruijn/dimension
    :dim/projection-to
    [[:d1 "content-addressing floor: every projected node reduces to its node hash"]
     [:d3 "semantic floor: each projected record is (h, :yin.debruijn/<slot>, v)"]]]
   [:yin.debruijn/dimension
    :dim/lift-from
    [[:d5 "the named :yin/* Universal AST datoms (yin.vm/ast->datoms-with-root)"
      "yin.vm.debruijn/project-datoms is the lift"]]]])


;; dimension-hash is minted below, beside the canonical encoder: §5's
;; domain separator is the descriptor encoded through the settled encoder
;; and hashed, and the encoder's definitions intervene between the two.


;; =============================================================================
;; D0: the canonical value table as validation, and the NFC seam (§5)
;; =============================================================================

(def ^:private int64-double-lower
  "The int64 domain's double bounds: -2^63 inclusive to +2^63 exclusive.
   Every integral double in that interval is an integer inside signed
   int64, and +2^63 itself is the first double outside it."
  -9223372036854775808.0)


(def ^:private int64-double-upper 9223372036854775808.0)


(defn- bytes-like?
  [v]
  #?(:clj (bytes? v)
     :cljs (instance? js/Uint8Array v)
     :cljd (instance? Uint8List v)))


(defn- negative-zero?
  "True only for -0.0: +0.0 and -0.0 compare equal, so the sign is read
   off the reciprocal's infinity — the §5 rule that keeps them distinct."
  [v]
  (and (zero? v) (= (/ -1.0 0.0) (/ 1.0 v))))


(defn- integral-double?
  [v]
  #?(:clj (= v (Math/floor v))
     :cljs (= v (js/Math.floor v))
     :cljd (= v (.floorToDouble v))))


(defn- double-class
  "§5 numeric canonicalisation of the double v: :int64 when v is integral
   and inside the signed int64 domain (integer 1 and integral double 1.0
   intentionally collide, on every host), :double otherwise — all NaNs
   are one quiet-NaN class, the infinities and the signed zeros are
   doubles. On :cljs an unsafe integral (§5's JavaScript number) returns
   nil: its exact integer identity is not recoverable, so it is a
   diagnostic rather than a silently misclaimed int64."
  [v]
  (cond
    (not= v v) :double
    (or (= (/ 1.0 0.0) v) (= (/ -1.0 0.0) v)) :double
    (negative-zero? v) :double
    (not (integral-double? v)) :double
    (not (and (>= v int64-double-lower) (< v int64-double-upper))) :double
    ;; §5's JavaScript number: int64 only when a safe integer, so ±2^53-1
    ;; bound the claim. The literals stay inside the gated branch.
    #?(:cljs (not (and (>= v -9007199254740991) (<= v 9007199254740991)))
       :default false) nil
    :else :int64))


(defn- numeric-class
  "Classify a number under §5's numeric canonicalisation. Exact host
   integers (Long on the JVM, Dart int) are int64 by construction; the
   out-of-domain numerics — bigints, ratios, and friends — classify nil.
   Caveat for the :cljd branch: on a JS-compiled Dart target `int?` also
   holds for integral doubles, so §5's safe-integer rule would govern
   there as on :cljs — the cljd lane is native today, where Dart int is
   exactly int64."
  [v]
  #?(:clj (cond
            (int? v) :int64
            (float? v) (double-class (double v))
            :else nil)
     :cljs (double-class v)
     :cljd (if (int? v) :int64 (double-class v))))


(defn- code-unit-at
  [s i]
  #?(:clj (int (.charAt ^String s i))
     :cljs (.charCodeAt s i)
     :cljd (.codeUnitAt s i)))


(defn- well-formed-utf16?
  "True when every surrogate in `s` is paired — the precondition for the
   hosts' UTF-8 encoders to agree. An unpaired surrogate reaches storage
   as itself on some hosts and as the replacement character on others,
   so byte identity is unmakeable there: such a string classifies
   unsupported, in a value or in an ident's parts alike."
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
  "An identifier classifies only when its namespace and name are both
   well-formed UTF-16: ident parts are strings under NFC."
  [x]
  (and (or (nil? (namespace x))
           (well-formed-utf16? (namespace x)))
       (well-formed-utf16? (name x))))


(defn canonical-class
  "Classify `v` under §5's canonical value table: the class keyword when
   v is inside the canonical domain, nil when v is unsupported (a
   diagnostic at the walk). Recursive over collections; maps and sets
   sort by canonical encoded bytes only at D3 encode time, so any
   iteration order classifies identically. Records are not the plain
   map they print as — their host shapes disagree — so they diagnose."
  [v]
  (cond
    (nil? v) :nil
    (boolean? v) :bool
    (string? v) (when (well-formed-utf16? v) :string)
    (keyword? v) (when (ident-parts-ok? v) :keyword)
    (symbol? v) (when (ident-parts-ok? v) :symbol)
    (bytes-like? v) :bytes
    (map? v) (when (and (not (record? v))
                        (every? (fn [entry]
                                  (and (canonical-class (key entry))
                                       (canonical-class (val entry))))
                                v))
               :map)
    (set? v) (when (every? canonical-class v) :set)
    (vector? v) (when (every? canonical-class v) :vector)
    (sequential? v) (when (every? canonical-class v) :list)
    (number? v) (numeric-class v)))


(defn canonical-value?
  "True when `v` is inside §5's canonical value domain — equivalently,
   when `canonical-class` is non-nil."
  [v]
  (some? (canonical-class v)))


(defn normalize-nfc
  "The one NFC seam of the projection (§5): every string entering
   canonical encoding passes through here. :clj delegates to
   java.text.Normalizer, :cljs to String.prototype.normalize, and :cljd
   to the unorm_dart package (Unicode 16.0, owner-settled 2026-09-21) so
   every ClojureDart target normalizes identically. NFC normalisation
   collisions — distinct inputs with one composed form — are an
   inherited limit of the repository encoding."
  [^String s]
  #?(:clj (java.text.Normalizer/normalize s java.text.Normalizer$Form/NFC)
     :cljs (.normalize s "NFC")
     :cljd (unorm/nfc s)))


;; =============================================================================
;; D0: the walked node grammar (§2)
;; =============================================================================

(def node-grammar
  "§2's walked node grammar and child order as the walk vocabulary —
   exactly the emitter's table. Scalars are [attribute slot-kind] in
   declared order; children are [attribute cardinality] in the fixed
   walk order. Slot kinds: :canonical is the full §5 value domain;
   :syms is an ordered vector of symbols; the rest are the obvious
   scalar types."
  {:literal {:scalars [[:yin/value :canonical]], :children []}
   :variable {:scalars [[:yin/name :symbol]], :children []}
   :lambda {:scalars [[:yin/params :syms] [:yin/macro? :bool]],
            :children [[:yin/body :node]]}
   :application {:scalars [],
                 :children [[:yin/operator :node] [:yin/operands :nodes]]}
   :dao.stream.apply/call {:scalars [[:yin/op :keyword]],
                           :children [[:yin/operands :nodes]]}
   :if {:scalars [],
        :children [[:yin/test :node] [:yin/consequent :node]
                   [:yin/alternate :node]]}
   :vm/gensym {:scalars [[:yin/prefix :string]], :children []}
   :vm/store-get {:scalars [[:yin/key :symbol]], :children []}
   :vm/store-put {:scalars [[:yin/key :symbol] [:yin/value :canonical]],
                  :children []}
   :vm/current-continuation {:scalars [], :children []}
   :vm/park {:scalars [], :children []}
   :vm/resume {:scalars [[:yin/parked-id :keyword]],
               :children [[:yin/val-node :node]]}
   :stream/make {:scalars [[:yin/buffer :int64]], :children []}
   :stream/put {:scalars [],
                :children [[:yin/target :node] [:yin/val-node :node]]}
   :stream/cursor {:scalars [], :children [[:yin/source :node]]}
   :stream/next {:scalars [], :children [[:yin/source :node]]}
   :stream/close {:scalars [], :children [[:yin/source :node]]}})


(def ^:private optional-attributes
  "§2's optional scalar: only :yin/macro? may be absent from a
   well-formed emitter batch. Every other grammar slot is required."
  #{:yin/macro?})


(def ^:private tolerated-attributes
  "The :yin/* facts legal on a walked node of any type: :yin/type, the
   :yin/root framing fact, and §2's tolerated-and-ignored emission
   metadata."
  #{:yin/type :yin/root :yin/tail? :yin/macro-name})


(def ^:private known-attributes
  "Per node type, the complete legal :yin/* vocabulary on a walked node
   of that type: its grammar row's slots plus the tolerated set. A known
   attribute on a type whose row does not list it — a :yin/body on a
   :literal, dangling or not — is as unknown there as :yin/extra, never
   silently ignored."
  (into {}
        (map (fn [[tag {:keys [scalars children]}]]
               [tag (into tolerated-attributes
                          (map first)
                          (concat scalars children))]))
        node-grammar))


;; =============================================================================
;; D0: root framing and input validation (§2)
;; =============================================================================

(defn- check-datom
  "Validate one input datom as a d5 [e a v t m] assert. Shape first, then
   §2's assert-only rule: m must not be dao.datom's reserved :db/retract
   op — read through `reserved`, never a bare literal. An assert carrying
   any other provenance m stays valid; once this passes, t and m are
   excluded from projected identity."
  [d]
  (when-not (and (vector? d) (= 5 (count d)))
    (throw (ex-info "Malformed d5 datom in projection input"
                    {:rule :malformed-datom, :datom d})))
  (let [[e a _v t m] d]
    (when-not (and (int? e)
                   (keyword? a)
                   (some? (namespace a))
                   (int? t)
                   (int? m))
      (throw (ex-info "Malformed d5 datom in projection input"
                      {:rule :malformed-datom, :datom d})))
    (when (= m (:db/retract datom/reserved))
      (throw (ex-info "Retract datom in assert-only projection input"
                      {:rule :retract, :datom d})))
    d))


(defn frame-datoms
  "§2 root framing: split one datom stream into its rooted graphs. A
   :yin/root true marker closes the graph accumulated above it; the next
   graph starts empty, so its fact index is a fresh one — emitter
   temporary ids may restart at -16 per graph, and never merge across
   graphs. Datoms outside the :yin namespace never reach the projection,
   so they neither open nor extend a frame: trailing decorations after
   the last marker are not a partial graph, and the returned frames carry
   :yin/* datoms only. End-of-stream with :yin/* datoms no marker closes
   is a diagnostic."
  [datoms]
  (let [[frames partial]
        (reduce (fn [[frames frame] d]
                  (let [[_e a v] (check-datom d)]
                    (cond
                      (and (= :yin/root a) (true? v))
                      [(conj frames (conj frame d)) []]

                      (not= "yin" (namespace a))
                      [frames frame]

                      :else [frames (conj frame d)])))
                [[] []]
                datoms)]
    (when (seq partial)
      (throw (ex-info "End of stream with a partial rooted graph"
                      {:rule :partial-frame, :datoms (vec partial)})))
    frames))


(defn- index-frame
  "Build one frame's fact index {eid {attribute v}} over the attributes of
   one namespace. Input order never matters: the index is keyed by [e a],
   t and m are dropped, attributes in other namespaces are ignored, and a
   second fact on the same [e a] is §2's duplicate structural fact."
  [ns-name datoms]
  (reduce (fn [index d]
            (let [[e a v] (check-datom d)]
              (if-not (= ns-name (namespace a))
                index
                (if (contains? (get index e) a)
                  (throw (ex-info "Duplicate structural fact in AST datoms"
                                  {:rule :duplicate-fact,
                                   :entity e,
                                   :attribute a}))
                  (assoc-in index [e a] v)))))
          {}
          datoms))


(defn- frame-root
  "§2's exact-root validation: exactly one :yin/root true fact per graph.
   The root fact's entity is the graph's root node; several roots are a
   diagnostic, and so is none."
  [datoms]
  (let [roots (into [] (comp (filter #(= :yin/root (nth % 1)))
                             (filter #(true? (nth % 2)))
                             (map first))
                    datoms)]
    (case (count roots)
      1 (first roots)
      0 (throw (ex-info "Rooted graph has no root fact" {:rule :missing-root}))
      (throw (ex-info "Rooted graph has several root facts"
                      {:rule :multiple-roots, :roots roots})))))


(defn- node-facts
  "The walked node's facts, or §2's dangling-reference diagnostic: an eid
   the index never saw, or saw without a :yin/type, is not a node."
  [index eid path]
  (let [facts (get index eid)]
    (if (and facts (contains? facts :yin/type))
      facts
      (throw (ex-info "Dangling AST reference"
                      {:rule :dangling-ref, :entity eid, :path path})))))


(defn- check-attributes
  "§2's unknown-attribute diagnostic: every :yin/* fact on a walked node
   must be inside its own type's grammar row or §2's tolerated set. Facts
   on entities the walk never reaches are not diagnosed."
  [tag facts eid path]
  (let [known (get known-attributes tag)]
    (doseq [a (keys facts)]
      (when-not (contains? known a)
        (throw (ex-info "Unknown :yin/* attribute on a walked node"
                        {:rule :unknown-attribute,
                         :attribute a,
                         :type tag,
                         :entity eid,
                         :path path}))))))


(defn- check-unexpanded
  "§1's unexpanded-macro detection: an application whose operator is a
   :lambda carrying :macro? true is an unexpanded call site — a terminal
   diagnostic, never silently interpreted. The stated limit: a free
   variable whose name matches a registry macro is not detectable from
   these datoms."
  [index eid operator-eid path]
  (when-let [operator (get index operator-eid)]
    (when (and (= :lambda (get operator :yin/type))
               (true? (get operator :yin/macro?)))
      (throw (ex-info "Unexpanded macro call site"
                      {:rule :unexpanded-macro,
                       :entity eid,
                       :operator operator-eid,
                       :path path})))))


(defn- slot-kind-ok?
  [kind v]
  (case kind
    :canonical (canonical-value? v)
    :symbol (symbol? v)
    :syms (and (vector? v) (every? symbol? v))
    :bool (or (true? v) (false? v))
    :keyword (keyword? v)
    :string (string? v)
    ;; number? first: a non-number reaching numeric-class would be a host
    ;; type error on :cljd, not the :unsupported-value diagnostic
    :int64 (and (number? v) (= :int64 (numeric-class v)))))


(defn- validated-scalars
  "Read the grammar's scalar slots off one walked node's facts, §5-valid
   in kind and required unless optional. Returns {attribute value} for
   the present ones; anything else is a diagnostic."
  [tag {:keys [scalars]} facts eid path]
  (reduce (fn [out [attr kind]]
            (if (contains? facts attr)
              (let [v (get facts attr)]
                (if (slot-kind-ok? kind v)
                  (assoc out attr v)
                  (throw (ex-info "Unsupported slot value in AST datoms"
                                  {:rule :unsupported-value,
                                   :slot attr,
                                   :kind kind,
                                   :value v,
                                   :entity eid,
                                   :path path}))))
              (if (contains? optional-attributes attr)
                out
                (throw (ex-info "Missing required AST attribute"
                                {:rule :missing-slot,
                                 :slot attr,
                                 :type tag,
                                 :entity eid,
                                 :path path})))))
          {}
          scalars))


(defn- child-ref
  "One :node child slot's entity id, validated as an integer ref."
  [facts attr eid path]
  (let [v (if (contains? facts attr)
            (get facts attr)
            (throw (ex-info "Missing required AST attribute"
                            {:rule :missing-slot,
                             :slot attr,
                             :entity eid,
                             :path path})))]
    (if (int? v)
      v
      (throw (ex-info "Unsupported slot value in AST datoms"
                      {:rule :unsupported-value,
                       :slot attr,
                       :kind :ref,
                       :value v,
                       :entity eid,
                       :path path})))))


(defn- child-refs
  "One :nodes child slot's ordered entity ids, validated as integer refs.
   A vector is ordered data, never a set (§2)."
  [facts attr eid path]
  (let [v (if (contains? facts attr)
            (get facts attr)
            (throw (ex-info "Missing required AST attribute"
                            {:rule :missing-slot,
                             :slot attr,
                             :entity eid,
                             :path path})))]
    (if (and (vector? v) (every? int? v))
      v
      (throw (ex-info "Unsupported slot value in AST datoms"
                      {:rule :unsupported-value,
                       :slot attr,
                       :kind :refs,
                       :value v,
                       :entity eid,
                       :path path})))))


;; =============================================================================
;; D1: the scope resolver (§3)
;; =============================================================================

(defn resolve-name
  "§3 scope resolution of `name` against `stack`, the ordered frame
   vector stack: frame depth 0 is the innermost frame, each frame is the
   source-ordered parameter vector, position is the source parameter
   index with the leftmost parameter at 0. Frames search inner-to-outer;
   positions search right-to-left within a frame, so duplicate
   parameters are rightmost-wins exactly as `yin.vm.engine/bind-params`
   binds them — (fn [x x] x) resolves to the second x, [0 1]. Returns
   {:bound [frame-depth position]} or {:free name} with the symbol
   preserved exactly."
  [stack name]
  (let [position-in (fn [frame]
                      (first (filter (fn [p] (= name (nth frame p)))
                                     (range (dec (count frame)) -1 -1))))
        binding (first (keep (fn [depth]
                               (when-let [p (position-in (nth stack depth))]
                                 [depth p]))
                             (range (count stack))))]
    (if binding
      {:bound binding}
      {:free name})))


;; =============================================================================
;; D1: the projection walk (§2 grammar, §3 scopes, no hashing)
;; =============================================================================

(declare project-node mint-record)


(defn- build-node
  "Build one walked node's resolved semantic node — the §4 projected
   slots, children as resolved nodes — recursing over children in the
   grammar's fixed order under the scope at each occurrence. Returns
   [node child-hashes acc'], child-hashes being {slot h} with an ordered
   vector of hashes under :yin.debruijn/operands; `active` is the set of
   eids on the current path."
  [tag index facts scalar eid stack acc active path]
  (let [;; each step threads [node child-hashes acc]
        child (fn [[node refs acc] slot attr stack]
                (let [[n h acc'] (project-node index
                                               (child-ref facts attr eid path)
                                               stack
                                               acc
                                               active
                                               (conj path attr))]
                  [(assoc node slot n) (assoc refs slot h) acc']))
        children (fn [[node refs acc] slot attr]
                   (let [[ns hs acc']
                         (reduce (fn [[ns hs a] ref]
                                   (let [[n h a'] (project-node index
                                                                ref
                                                                stack
                                                                a
                                                                active
                                                                (conj path attr))]
                                     [(conj ns n) (conj hs h) a']))
                                 [[] [] acc]
                                 (child-refs facts attr eid path))]
                     [(assoc node slot ns) (assoc refs slot hs) acc']))
        leaf (fn [node] [node {} acc])]
    (case tag
      :literal (leaf {:yin.debruijn/type :literal,
                      :yin.debruijn/value (get scalar :yin/value)})
      :variable (let [name (get scalar :yin/name)
                      resolution (resolve-name stack name)]
                  (leaf (if-let [bound (:bound resolution)]
                          {:yin.debruijn/type :variable,
                           :yin.debruijn/bound bound}
                          {:yin.debruijn/type :variable,
                           :yin.debruijn/free (:free resolution)})))
      :lambda (let [params (get scalar :yin/params)]
                (child (leaf (merge {:yin.debruijn/type :lambda,
                                     :yin.debruijn/arity (count params)}
                                    ;; truthy only: an explicit false and an
                                    ;; absent :yin/macro? project identically
                                    (when (get scalar :yin/macro?)
                                      {:yin.debruijn/macro? (get scalar :yin/macro?)})))
                       :yin.debruijn/body
                       :yin/body
                       (into [params] stack)))
      :application (let [operator-eid (child-ref facts :yin/operator eid path)]
                     (check-unexpanded index eid operator-eid path)
                     (-> (leaf {:yin.debruijn/type :application})
                         (child :yin.debruijn/operator :yin/operator stack)
                         (children :yin.debruijn/operands :yin/operands)))
      :dao.stream.apply/call (children (leaf {:yin.debruijn/type :dao.stream.apply/call,
                                              :yin.debruijn/op (get scalar :yin/op)})
                                       :yin.debruijn/operands
                                       :yin/operands)
      :if (-> (leaf {:yin.debruijn/type :if})
              (child :yin.debruijn/test :yin/test stack)
              (child :yin.debruijn/consequent :yin/consequent stack)
              (child :yin.debruijn/alternate :yin/alternate stack))
      :vm/gensym (leaf {:yin.debruijn/type :vm/gensym,
                        :yin.debruijn/prefix (get scalar :yin/prefix)})
      :vm/store-get (leaf {:yin.debruijn/type :vm/store-get,
                           :yin.debruijn/key (get scalar :yin/key)})
      :vm/store-put (leaf {:yin.debruijn/type :vm/store-put,
                           :yin.debruijn/value (get scalar :yin/value),
                           :yin.debruijn/key (get scalar :yin/key)})
      :vm/current-continuation (leaf {:yin.debruijn/type :vm/current-continuation})
      :vm/park (leaf {:yin.debruijn/type :vm/park})
      :vm/resume (child (leaf {:yin.debruijn/type :vm/resume,
                               :yin.debruijn/parked-id (get scalar :yin/parked-id)})
                        :yin.debruijn/val-node
                        :yin/val-node
                        stack)
      :stream/make (leaf {:yin.debruijn/type :stream/make,
                          :yin.debruijn/buffer (get scalar :yin/buffer)})
      :stream/put (-> (leaf {:yin.debruijn/type :stream/put})
                      (child :yin.debruijn/target :yin/target stack)
                      (child :yin.debruijn/val-node :yin/val-node stack))
      (:stream/cursor :stream/next :stream/close)
      (child (leaf {:yin.debruijn/type tag})
             :yin.debruijn/source
             :yin/source
             stack))))


(defn- project-node
  "Project and hash-cons one source node under `stack`, memoized by
   [source-eid stack] — §2's [source-eid lexical-context] key, where the
   lexical context is the complete stack of frame parameter vectors.
   Returns [node h acc']. The memo carries the resolved node AND its
   hash, so a shared occurrence is resolved and hashed once: without the
   hash in the memo, a doubling chain of shared subgraphs is minted once
   per unfolded path, exponentially. The memo is an optimisation only:
   it is threaded explicitly in `acc` and can never affect output
   identity. Cycle detection runs before the memo, so an eid on the
   active path is a diagnostic even if it was memoized at another
   occurrence."
  [index eid stack acc active path]
  (if (contains? active eid)
    (throw (ex-info "Cyclic AST reference"
                    {:rule :cycle, :entity eid, :path path}))
    (if-let [cached (find (:memo acc) [eid stack])]
      (let [[node h] (val cached)]
        [node h acc])
      (let [facts (node-facts index eid path)
            tag (get facts :yin/type)
            grammar (or (get node-grammar tag)
                        (throw (ex-info "Unknown AST node type in projection"
                                        {:rule :unknown-node-type,
                                         :type tag,
                                         :entity eid,
                                         :path path})))
            _ (check-attributes tag facts eid path)
            scalar (validated-scalars tag grammar facts eid path)
            [node refs acc'] (build-node tag
                                         index
                                         facts
                                         scalar
                                         eid
                                         stack
                                         acc
                                         (conj active eid)
                                         path)
            [h acc''] (mint-record node refs acc')]
        [node h (assoc-in acc'' [:memo [eid stack]] [node h])]))))


;; =============================================================================
;; D2: the canonical encoder and node hashes (§5)
;; =============================================================================

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


(def ^:private slot-tag
  "The settled tag byte (hex) each canonical class and compound slot type
   renders under. Class tags are the class's index in the value table's
   declared :classes order — read through the descriptor — and the
   compound types (child refs, the fixed pair, the ordered vector) take
   bytes outside that range."
  (into {:ref "c0", :hash "c1", :tuple "c2", :ordered-vector "c3"}
        (map-indexed (fn [i class] [class (to-hex i 2)]))
        (:classes canonical-value-table)))


;; code-unit-at lives with the D0 classifier: well-formed-utf16? reads
;; code units there, before any encoder definition.


(defn- utf8-byte-length
  "The byte length of `s` under UTF-8, from its code units: a surrogate
   pair is one 4-byte sequence, never two 3-byte ones, and an unpaired
   surrogate — which the classifier has already refused — would be the
   3-byte replacement character. Pure arithmetic over code units, so
   every host agrees without encoding anything."
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


(defn- framed
  "One self-delimiting encoded part: the class's tag byte, the 8-hex byte
   length of the content, and the content. §5's tagged, length-delimited
   framing with the settled rule that lengths count UTF-8 bytes — the
   preimage is carried as text whose UTF-8 bytes are the hashed stream,
   so a byte-count prefix keeps the framing's extents on that stream."
  [class content]
  (str (get slot-tag class) (to-hex (utf8-byte-length content) 8) content))


(defn- int64-le-hex
  "The settled int64 byte rule: 8 bytes, two's complement, little-endian
   (datom.md). Exact host integers go through bit ops; a :cljs number —
   which the classifier only lets here as a safe integer — decomposes
   exactly through floor and mod by 2^32, whose low bytes survive the
   host's 32-bit bit ops."
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
  "The settled :double byte rule: the IEEE-754 bits, little-endian, with
   every NaN the one quiet encoding 7ff8000000000000 regardless of the
   payload a host happens to hold. :clj reads the bits through
   doubleToLongBits, :cljs through a DataView store with littleEndian
   explicitly true, :cljd through a big-endian ByteData store read in
   reverse — the endianness is stated, never the platform's default."
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


(defn- ident-content
  "§5 keywords and symbols encode namespace and name separately, each
   through the NFC seam — the value table declares :nfc-utf-8 for both
   parts; an absent namespace has nil's own framing, never an empty
   string's."
  [x]
  (str (if-let [ns (namespace x)]
         (framed :string (normalize-nfc ns))
         (framed :nil ""))
       (framed :string (normalize-nfc (name x)))))


(defn encode-value
  "Encode one canonical-domain value under its value-table class: the
   full tagged, length-delimited part, under the settled D3 byte rules —
   int64 little-endian two's complement for integers and integral
   doubles alike (the recorded 1 ≡ 1.0 collision), IEEE-754 bits with one
   quiet-NaN encoding for the other doubles, UTF-8 byte lengths, NFC
   strings and ident parts. Maps and sets sort by their encoded parts,
   the §5 order rule; vectors and lists are the distinct classes the D0
   ruling split. An out-of-domain value is a diagnostic."
  [v]
  (let [class (canonical-class v)]
    (case class
      :nil (framed :nil "")
      :bool (framed :bool (if v "01" "00"))
      :int64 (framed :int64 (int64-le-hex v))
      :double (framed :double (double-le-hex v))
      :string (framed :string (normalize-nfc v))
      :bytes (framed :bytes (apply str (map #(to-hex (bit-and % 0xff) 2) v)))
      :keyword (framed :keyword (ident-content v))
      :symbol (framed :symbol (ident-content v))
      :map (let [entries (sort (map (fn [[k x]]
                                      [(encode-value k) (encode-value x)])
                                    v))]
             (when (some (fn [[a b]] (= (first a) (first b)))
                         (partition 2 1 entries))
               (throw (ex-info "Map keys collide in canonical encoding"
                               {:rule :unsupported-value, :value v})))
             (framed :map (apply str (mapcat identity entries))))
      :set (let [elements (sort (map encode-value v))]
             (when (some (fn [[a b]] (= a b)) (partition 2 1 elements))
               (throw (ex-info "Set elements collide in canonical encoding"
                               {:rule :unsupported-value, :value v})))
             (framed :set (apply str elements)))
      :vector (framed :vector (apply str (map encode-value v)))
      :list (framed :list (apply str (map encode-value v)))
      (throw (ex-info "Unsupported value in Merkle encoding"
                      {:rule :unsupported-value, :value v})))))


(defn- encode-typed
  "Encode one descriptor slot's value under its declared type:
   :canonical is polymorphic, the compound types frame their parts, and
   a declared scalar type encodes by its (already validated) value
   class."
  [type v]
  (cond
    (= :canonical type) (encode-value v)
    (vector? type) (if (= :tuple (first type))
                     (framed :tuple (apply str (map encode-typed (rest type) v)))
                     (framed :ordered-vector
                             (str (framed :int64 (int64-le-hex (count v)))
                                  (apply str (map #(encode-typed :ref %) v)))))
    (or (= :ref type) (= :hash type)) (framed :ref v)
    :else (encode-value v)))


(def dimension-hash
  "The published hash domain separator (§4): the descriptor data encoded
   through the settled canonical encoder and hashed, per datom.md's 'the
   content hash of this subgraph IS the dimension's identity'. D0-D2
   minted a transitional dao.jing digest; D3 re-pins it here over the
   settled byte rules. The descriptor content is unchanged — only the
   digest's derivation moved to the canonical encoder, so the separator
   now separates this dimension from every other by the same bytes every
   node hash uses."
  (jing/sha256 (encode-value descriptor)))


(defn canonical-value
  "A record's stored scalar is its canonical spelling (the carried D3
   obligation): the long form for integral numbers — so 1 and 1.0 hold
   one record content — the NFC form for strings and ident parts, and
   the same recursively inside collections. One content address, one
   record content: equal fingerprints imply equal :records maps.
   Canonicalizing a map whose keys merge — {1 :a, 1.0 :b}, or two string
   keys with one NFC form — is a key collision, diagnosed rather than
   letting iteration order decide which entry survives; a set whose
   elements merge — #{1 1.0}, or two spellings of é — is diagnosed the
   same way, because the merge changes its count, not just a scalar's
   spelling."
  [v]
  (let [class (canonical-class v)]
    (case class
      :nil v
      :bool v
      :int64 (long v)
      :double (double v)
      :string (normalize-nfc v)
      :bytes v
      :keyword (if-let [ns (namespace v)]
                 (keyword (normalize-nfc ns) (normalize-nfc (name v)))
                 (keyword (normalize-nfc (name v))))
      :symbol (if-let [ns (namespace v)]
                (symbol (normalize-nfc ns) (normalize-nfc (name v)))
                (symbol (normalize-nfc (name v))))
      :map (let [canonical (into {}
                                 (map (fn [[k x]]
                                        [(canonical-value k) (canonical-value x)]))
                                 v)]
             (if (< (count canonical) (count v))
               (throw (ex-info "Canonicalizing a map merges distinct keys"
                               {:rule :unsupported-value, :value v}))
               canonical))
      :set (let [canonical (into (empty v) (map canonical-value) v)]
             (if (< (count canonical) (count v))
               (throw (ex-info "Canonicalizing a set merges distinct elements"
                               {:rule :unsupported-value, :value v}))
               canonical))
      :vector (mapv canonical-value v)
      :list (apply list (map canonical-value v))
      v)))


(defn- node-hash
  "§5: hash(node) = SHA-256(dimension-hash || encode(tag-specific-slots-
   in-descriptor-order)). The preimage is the dimension hash's hex
   followed by the framed slots under the settled byte rules; the shape —
   descriptor order, tags, byte-count length delimiters, ordered child
   hashes — is what D2 fixed and D3 settled. Markers are never among the
   encodings: only present, non-marker slots reach here."
  [encodings]
  (jing/sha256 (apply str dimension-hash encodings)))


;; =============================================================================
;; D2: hash-consing and the projected records (§4)
;; =============================================================================

(defn- slot-encodings
  "The descriptor-order encodings of one record's present, non-marker
   slots — with the dimension hash, the only input to a node hash.
   Children already carry child hashes, so this is total over a record
   and serves both the mint and the storage reader's content check."
  [record]
  (for [[slot type role] dimension-slots
        :when (and (not= :marker role) (contains? record slot))]
    (encode-typed type (get record slot))))


(defn- mint-record
  "Hash-cons one resolved node (§4-§5) whose children are already
   minted, their hashes in `child-hashes` ({slot h}, an ordered vector
   under :yin.debruijn/operands): the node hash over the dimension hash
   and the node's tag-specific slots in descriptor order, then the record
   — the node with child slots carrying child hashes, scalars in their
   canonical spellings, and :yin.debruijn/hash added. Returns [h acc']
   over {:hashcons preimage->h, :records h->record, :mints n}; :mints
   counts every call, the walk's instrumentation seam. The consing memo
   is keyed on the node's preimage — the concatenated slot encodings —
   never on Clojure =, which merges a list literal with a vector literal
   and the signed zeros; a preimage hit implies the identical hash and,
   records carrying canonical spellings, the identical record. The memo
   stays an optimisation only: identity is the hash either way, so
   traversal order never enters it."
  [node child-hashes acc]
  (let [acc (update acc :mints inc)
        record (reduce (fn [r [slot _type role]]
                         (cond
                           (or (= :marker role) (not (contains? node slot))) r
                           (= :child role) (assoc r slot (get child-hashes slot))
                           ;; the carried D3 obligation: a record's stored
                           ;; scalar is its canonical spelling, so equal
                           ;; fingerprints imply equal records
                           :else (assoc r slot (canonical-value (get node slot)))))
                       node
                       dimension-slots)
        encodings (slot-encodings record)
        preimage (apply str encodings)]
    (if-let [cached (find (:hashcons acc) preimage)]
      [(val cached) acc]
      (let [h (node-hash encodings)]
        [h (-> acc
               (assoc-in [:hashcons preimage] h)
               (assoc-in [:records h] (assoc record :yin.debruijn/hash h)))]))))


(defn project-datoms-counted
  "`project-datoms` plus :mints, the number of record mints the walk
   performed — the honest instrumentation seam for the shared-subgraph
   bound: one mint per distinct [source-eid lexical-context] occurrence,
   never one per unfolded path. Output-neutral: every other key is
   exactly `project-datoms`'s."
  [datoms]
  (let [datoms (vec datoms)
        index (index-frame "yin" datoms)
        root-eid (frame-root datoms)
        [root fingerprint {:keys [records mints]}]
        (project-node index
                      root-eid
                      []
                      {:memo {}, :hashcons {}, :records {}, :mints 0}
                      #{}
                      [])]
    {:root root, :fingerprint fingerprint, :records records, :mints mints}))


(defn project-datoms
  "§6's pure projection of one complete rooted graph of :yin/* AST
   datoms, through §2 validation and §3 scope resolution to D2's Merkle
   records. Returns {:root node, :fingerprint h, :records {h record}}:
   the resolved semantic graph under :root (whose wrapper key is the
   graph's root marker — the node map carries none, so it equals the
   same term appearing as a subterm), the root node hash as
   :fingerprint, and the hash-consed projected records — each the
   resolved node with child slots carrying child hashes and
   :yin.debruijn/hash h added. A bound occurrence carries
   :yin.debruijn/bound [frame-depth position], a free one
   :yin.debruijn/free name, and a lambda's binder is its
   :yin.debruijn/arity.

   Input order, source tempids, t, m, and non-:yin/* namespaces never
   enter the result, at graph level or hash level. Every defect is
   ex-info carrying :rule (and, where the walk has descended, :entity
   and :path). For a stream carrying several adjacent graphs, frame it
   first with `frame-datoms` and project each frame — the per-frame fact
   index resets there."
  [datoms]
  (dissoc (project-datoms-counted datoms) :mints))


;; =============================================================================
;; D2: the d5 storage adapter (§4)
;; =============================================================================

(defn projected->datoms
  "The d5 storage adapter's write half (§4): one projection's records as
   d5 datoms [e a v t m]. Entities are local handles minted from
   dao.datom's first-user-id in a deterministic root-down, descriptor
   slot order walk — handles, ordinals, and row count are storage layout
   only, never identity. Each entity carries :yin.debruijn/hash h; child
   slots carry child hashes; :yin.debruijn/operands is one ordered
   vector datom, never cardinality-many; and the root entity is
   explicitly marked :yin.debruijn/root true — the root-hash marker, its
   :yin.debruijn/hash being the fingerprint. t 0 and the assert op are
   the adapter's fixed provenance, mirroring the named emitter's
   defaults."
  [{:keys [fingerprint records]}]
  (let [t 0
        m datom/default-op
        out (atom [])
        emitted (atom #{})
        next-eid (atom (dec datom/first-user-id))
        emit! (fn [e a v] (swap! out conj [e a v t m]))
        walk (fn walk
               [h]
               ;; one entity per hash: a record reachable by several paths
               ;; is emitted once, and the slot datoms already pointing at
               ;; its hash are the sharing
               (when-not (contains? @emitted h)
                 (let [e (swap! next-eid inc)
                       ;; the records came from the projection itself, so a
                       ;; missing one is a projection defect, never input:
                       ;; its rule is one `exception-diagnostic` classifies
                       ;; :internal-error
                       record (or (get records h)
                                  (throw (ex-info
                                           "Projected record set does not contain a child hash"
                                           {:rule :missing-record, :hash h})))]
                   (swap! emitted conj h)
                   (emit! e :yin.debruijn/hash h)
                   (doseq [[slot type role] dimension-slots
                           :when (and (not= :marker role) (contains? record slot))]
                     (let [v (get record slot)]
                       (emit! e slot v)
                       (when (= :child role)
                         (if (and (vector? type) (= :ordered-vector (first type)))
                           (doseq [child v] (walk child))
                           (walk v)))))
                   (when (= h fingerprint)
                     (emit! e :yin.debruijn/root true)))))]
    (walk fingerprint)
    @out))


(def ^:private record-attributes
  "Every attribute a projected record entity may carry: the declared
   dimension slots, markers included."
  (into #{} (map first) dimension-slots))


(defn- slot-shape-ok?
  "Whether `v` fits the descriptor `type` on a projected record: the
   deterministic gate the storage reader hashes behind, so a wrongly
   typed slot — :yin.debruijn/bound 5, a numeric child ref — diagnoses
   :unsupported-value instead of reaching host arithmetic inside the
   hash recomputation."
  [type v]
  (cond
    (= :canonical type) (canonical-value? v)
    (= :keyword type) (keyword? v)
    (= :symbol type) (symbol? v)
    (= :string type) (string? v)
    (= :bool type) (or (true? v) (false? v))
    (= :int64 type) (and (number? v) (= :int64 (numeric-class v)))
    (or (= :ref type) (= :hash type)) (and (string? v)
                                           (re-matches #"^[0-9a-f]{64}$" v))
    (and (vector? type) (= :tuple (first type)))
    (and (vector? v)
         (= (count v) (count (rest type)))
         (every? true? (map slot-shape-ok? (rest type) v)))
    (vector? type) (and (vector? v) (every? #(slot-shape-ok? :ref %) v))
    :else false))


(defn- projected-slots-of
  "The projected slots one §2 grammar attribute becomes (§4): the binder
   :yin/params becomes :arity, an occurrence's :yin/name becomes one of
   :bound or :free, and every other attribute keeps its name under
   :yin.debruijn."
  [attr]
  (case attr
    :yin/params [:yin.debruijn/arity]
    :yin/name [:yin.debruijn/bound :yin.debruijn/free]
    [(keyword "yin.debruijn" (name attr))]))


(def ^:private record-slots
  "Per node type, every slot its projected record may carry besides
   :yin.debruijn/hash — derived from `node-grammar`, so the reader's
   grammar and the walk's are one table. The preimage frames slots by
   type tag, not name, so this check is what keeps a slot renamed within
   one type (:free to :key on a :variable, :arity to :buffer) from
   passing under a valid address."
  (into {}
        (map (fn [[tag {:keys [scalars children]}]]
               [tag (into #{:yin.debruijn/type}
                          (mapcat (comp projected-slots-of first))
                          (concat scalars children))]))
        node-grammar))


(def ^:private optional-record-slots
  "The record slots a writer may leave absent: :macro? (only a true one
   is written) and :bound/:free, of which a :variable carries exactly
   one."
  #{:yin.debruijn/macro? :yin.debruijn/bound :yin.debruijn/free})


(defn- canonical-spelling?
  "Whether `v` is already its own canonical spelling — the form
   `canonical-value` gives every stored scalar. Exact, never =: Dart's
   1 == 1.0 and Clojure's = across a set's members would pass a stored
   1.0 whose address is 1's, and the reader would hand back a record no
   writer produces. A JS number carries one spelling per value, so every
   in-domain number is canonical there."
  [v]
  (case (canonical-class v)
    (:nil :bool :bytes) true
    :int64 #?(:cljs true :default (int? v))
    ;; :cljd first: the ClojureDart host pass also reads :clj
    :double #?(:cljd true :clj (instance? Double v) :cljs true)
    :string (= v (normalize-nfc v))
    (:keyword :symbol) (and (or (nil? (namespace v))
                                (= (namespace v) (normalize-nfc (namespace v))))
                            (= (name v) (normalize-nfc (name v))))
    :map (every? (fn [[k x]] (and (canonical-spelling? k) (canonical-spelling? x))) v)
    (:set :vector :list) (every? canonical-spelling? v)
    false))


(defn- check-record
  "The reader's record gate, after every slot fits its declared shape:
   the record's type is a grammar type, it carries exactly its type's
   slots (a :variable exactly one of :bound/:free, a :macro? only when
   true), and every scalar is its canonical spelling. Everything here is
   what the writer already guarantees, so no minted hash moves."
  [record e]
  (let [tag (get record :yin.debruijn/type)
        allowed (or (get record-slots tag)
                    (throw (ex-info "Projected record has an unknown node type"
                                    {:rule :unknown-node-type, :type tag, :entity e})))
        present (disj (set (keys record)) :yin.debruijn/hash)]
    (doseq [slot present]
      (when-not (contains? allowed slot)
        (throw (ex-info "Projected slot is outside its node type's grammar row"
                        {:rule :unknown-attribute,
                         :attribute slot,
                         :type tag,
                         :entity e}))))
    (doseq [slot allowed]
      (when-not (or (contains? present slot)
                    (contains? optional-record-slots slot))
        (throw (ex-info "Projected record lacks a required slot"
                        {:rule :missing-slot, :slot slot, :type tag, :entity e}))))
    (when (and (= :variable tag)
               (not= 1 (count (filter #(contains? present %)
                                      [:yin.debruijn/bound :yin.debruijn/free]))))
      (throw (ex-info "A projected :variable carries exactly one of :bound and :free"
                      {:rule :missing-slot,
                       :slot [:yin.debruijn/bound :yin.debruijn/free],
                       :type tag,
                       :entity e})))
    (doseq [[slot _type role] dimension-slots
            :when (and (= :scalar role) (contains? record slot))]
      (let [v (get record slot)]
        (when-not (and (canonical-spelling? v)
                       ;; the writer spells a false :macro? by its absence
                       (not (and (= :yin.debruijn/macro? slot) (false? v))))
          (throw (ex-info "Projected scalar is not its canonical spelling"
                          {:rule :noncanonical-value,
                           :slot slot,
                           :value v,
                           :entity e})))))))


(defn datoms->projected
  "The d5 storage adapter's read half (§4): projected datoms back to
   {:fingerprint h, :records {h record}} — the inverse of
   `projected->datoms` over the record set (the resolved :root graph is
   the named side's, never rebuilt here). Every entity must carry its
   :yin.debruijn/hash; exactly one entity is the :yin.debruijn/root
   marker and its hash is the fingerprint; every slot must fit its
   declared shape BEFORE its hash is recomputed — a wrongly typed slot
   diagnoses :unsupported-value, never a host exception; each record must
   then be one its writer can produce — its type's grammar slots only
   (:unknown-node-type, :unknown-attribute, :missing-slot), every scalar
   in its canonical spelling (:noncanonical-value) — because the
   preimage frames slots by type tag, not name, and equal encodings do
   not mean equal spellings; child refs must resolve to records;
   :yin.debruijn/operands must be one vector of hashes. Each record is
   re-hashed and a disagreement is a
   :hash-mismatch diagnostic — content addressing is verified at this
   boundary, never trusted. t, m, and other namespaces are layout,
   ignored."
  [datoms]
  (let [datoms (vec datoms)
        index (index-frame "yin.debruijn" datoms)
        markers (into []
                      (comp (filter #(= :yin.debruijn/root (nth % 1)))
                            (filter #(true? (nth % 2)))
                            (map first))
                      datoms)
        _ (when-not (= 1 (count markers))
            (throw (ex-info "Projected datoms have not exactly one root marker"
                            (if (zero? (count markers))
                              {:rule :missing-root}
                              {:rule :multiple-roots, :roots markers}))))
        root-e (first markers)
        records (reduce-kv (fn [recs e facts]
                             (let [h (or (when (contains? facts :yin.debruijn/hash)
                                           (get facts :yin.debruijn/hash))
                                         (throw (ex-info
                                                  "Projected entity carries no :yin.debruijn/hash"
                                                  {:rule :missing-hash, :entity e})))
                                   record (dissoc facts :yin.debruijn/root)]
                               (doseq [a (keys record)]
                                 (when-not (contains? record-attributes a)
                                   (throw (ex-info
                                            "Unknown :yin.debruijn/* attribute on a projected record"
                                            {:rule :unknown-attribute,
                                             :attribute a,
                                             :entity e}))))
                               (when-not (and (string? h)
                                              (re-matches #"^[0-9a-f]{64}$" h))
                                 (throw (ex-info "Projected hash is not 64 lowercase hex"
                                                 {:rule :malformed-hash,
                                                  :hash h,
                                                  :entity e})))
                               (doseq [[slot type role] dimension-slots
                                       :when (and (not= :marker role)
                                                  (contains? record slot))]
                                 (when-not (slot-shape-ok? type (get record slot))
                                   (throw (ex-info
                                            "Projected slot value does not fit its declared type"
                                            {:rule :unsupported-value,
                                             :slot slot,
                                             :value (get record slot),
                                             :entity e}))))
                               (check-record record e)
                               (if (contains? recs h)
                                 (throw (ex-info
                                          "Two entities claim one projected hash"
                                          {:rule :duplicate-fact,
                                           :attribute :yin.debruijn/hash,
                                           :entity e}))
                                 (do (when-not (= h (node-hash (slot-encodings record)))
                                       (throw (ex-info
                                                "Projected record does not hash to its address"
                                                {:rule :hash-mismatch,
                                                 :hash h,
                                                 :entity e})))
                                     (assoc recs h record)))))
                           {}
                           index)
        fingerprint (get-in index [root-e :yin.debruijn/hash])]
    (doseq [[h record] records
            [slot type role] dimension-slots
            :when (and (= :child role) (contains? record slot))]
      (let [refs (if (and (vector? type) (= :ordered-vector (first type)))
                   (let [v (get record slot)]
                     (when-not (vector? v)
                       (throw (ex-info
                                "Projected :yin.debruijn/operands is not an ordered vector"
                                {:rule :unsupported-value,
                                 :slot slot,
                                 :value v,
                                 :hash h})))
                     v)
                   [(get record slot)])]
        (doseq [ref refs]
          (when-not (contains? records ref)
            (throw (ex-info "Projected child hash resolves to no record"
                            {:rule :dangling-ref, :hash ref, :via h, :slot slot}))))))
    {:fingerprint fingerprint, :records records}))


;; =============================================================================
;; D4: the dao.stream forward adapter (§6)
;; =============================================================================

(def forward-default-options
  "Defaults for `forward-step`: one read or one write per call, terminal
   gap policy — the same shape `dao.stream.forward` gives its copier."
  {:batch-budget 1})


(defn forward-initial-state
  "The state `forward-step` consumes: the input cursor, the current
   graph frame (the §2 accumulation the next :yin/root marker closes),
   and pending projected output. The fact index, scope stack, and
   occurrence memo of §1 live inside the pure per-frame projection —
   the index resets with every frame (§2) — so no step carries them."
  [cursor]
  {:cursor cursor, :frame [], :pending []})


(defn- forward-budget-ok?
  "A batch budget is a positive integer: 0 would answer :continue with no
   progress under every host cadence, forever, so it is invalid input
   like any other non-positive or non-integer budget."
  [budget]
  (and (integer? budget) (pos? budget)))


(defn- forward-terminal-status
  "The terminal status of a source-side outcome, named as
   `dao.stream.forward` names its copier's."
  [outcome]
  (case outcome
    :dao.stream/end :source-ended
    :dao.stream/gap :source-gap
    :dao.stream/cursor-mismatch :source-cursor-mismatch
    :dao.stream/invalid-cursor :source-invalid-cursor
    :dao.stream/transport-error :source-transport-error
    :transport-error))


(defn- forward-destination-status
  [outcome]
  (case outcome
    :dao.stream/closed :destination-closed
    :dao.stream/invalid-value :destination-invalid-value
    :dao.stream/transport-error :destination-transport-error
    :transport-error))


(defn- forward-result
  [state status forwarded outcome diagnostic]
  (cond-> (assoc state :status status :forwarded forwarded)
    outcome (assoc :outcome outcome)
    diagnostic (assoc :diagnostic diagnostic)))


(def ^:private internal-rules
  "Diagnostic rules only the projection's own code can produce — a
   defect in it, never in its input."
  #{:missing-record})


(defn exception-diagnostic
  "The terminal classification of a throw from forward-step's read path:
   an ex-info carrying an input diagnostic — a keyword :rule outside
   `internal-rules` — is invalid input, its own ex-data; anything else
   (an internal rule, ex-data without a :rule, a plain host error, an
   Error) is an internal defect: :internal-error with the message, and
   the ex-data under :data when there is any — never mislabeled input
   and never host-divergent."
  [t]
  (let [data (ex-data t)
        rule (when (map? data) (get data :rule))]
    (if (and (keyword? rule) (not (contains? internal-rules rule)))
      {:status :invalid-input, :diagnostic data}
      {:status :internal-error,
       :diagnostic (cond-> {:rule :internal-error, :message (ex-message t)}
                     data (assoc :data data))})))


(defn forward-step
  "§6's forward interpretation: read :yin/* datoms from `source` at the
   state's cursor, frame at the :yin/root marker — projecting there —
   and emit the projected d5 tuples to `destination`. One call performs
   at most :batch-budget units of work, a unit being one read or one
   pending write; the state thread is the only state there is (§1: no
   atom, callback, timer, registry, or clock).

   Statuses: :continue (budget spent, work may remain) and :retry
   (source blocked, or destination full with pending output kept —
   pending writes are explicit and retried only by the host's cadence)
   are non-terminal; terminal statuses are :source-ended (input ended
   on a closed frame), :partial-frame (input ended with :yin/* datoms
   no marker closes — §2's diagnostic, its frame under :diagnostic),
   :invalid-input (a malformed datom or a projection diagnostic, the
   ex-data under :diagnostic; or a :batch-budget that is not a positive
   integer, :rule :invalid-budget, before any work), :internal-error (a non-diagnostic
   throwable from the read path — an internal defect, never mislabeled
   input), the source transport defects (:source-gap,
   :source-cursor-mismatch, :source-invalid-cursor,
   :source-transport-error, :transport-error), and the destination
   defects (:destination-closed, :destination-invalid-value,
   :destination-transport-error). Every result carries :forwarded — the
   count of THIS call's writes, as dao.stream.forward's does — and
   carries :outcome whenever the last transport operation produced one,
   including the blocked/full outcome a :retry hands back. Terminal
   state is data: re-stepping it is a no-op. `end` stops this
   interpreter and never closes a handle; a gap terminates (the
   terminal default policy — this adapter resumes nothing on its own)."
  ([source destination state]
   (forward-step source destination state forward-default-options))
  ([source destination state options]
   (let [state (if (map? state) state {})
         options (merge forward-default-options (or options {}))
         status (:status state)
         budget (:batch-budget options)]
     (cond
       (and status (not (contains? #{:continue :retry} status)))
       state

       (not (forward-budget-ok? budget))
       (forward-result {:cursor (:cursor state),
                        :frame (:frame state),
                        :pending (:pending state)}
                       :invalid-input
                       0
                       nil
                       {:rule :invalid-budget, :batch-budget budget})

       :else
       (loop [cursor (:cursor state)
              frame (:frame state)
              pending (:pending state)
              remaining budget
              forwarded 0]
         (if (zero? remaining)
           (forward-result {:cursor cursor, :frame frame, :pending pending}
                           :continue
                           forwarded
                           nil
                           nil)
           (if (seq pending)
             (let [result (stream/append! destination (first pending))
                   outcome (:dao.stream/outcome result)]
               (case outcome
                 :dao.stream/ok
                 (recur cursor frame (rest pending) (dec remaining) (inc forwarded))
                 :dao.stream/full
                 (forward-result {:cursor cursor, :frame frame, :pending pending}
                                 :retry
                                 forwarded
                                 outcome
                                 nil)
                 (forward-result {:cursor cursor, :frame frame, :pending pending}
                                 (forward-destination-status outcome)
                                 forwarded
                                 outcome
                                 nil)))
             (let [result (stream/next source cursor)
                   outcome (:dao.stream/outcome result)]
               (case outcome
                 :dao.stream/ok
                 ;; the try computes the successor frame; the recur stays
                 ;; outside it — a diagnostic is data, never a throw
                 (let [datum (:dao.stream/value result)
                       successor (:dao.stream/cursor result)
                       advanced (try
                                  (let [[_e a v] (check-datom datum)]
                                    (cond
                                      (and (= :yin/root a) (true? v))
                                      {:frame [],
                                       :pending (projected->datoms
                                                  (project-datoms (conj frame datum)))}

                                      ;; datoms outside the :yin namespace
                                      ;; advance the cursor without extending
                                      ;; the frame, exactly as frame-datoms
                                      ;; frames them
                                      (not= "yin" (namespace a))
                                      {:frame frame, :pending pending}

                                      :else {:frame (conj frame datum),
                                             :pending pending}))
                                  (catch #?(:cljd Object :clj Throwable :cljs :default) t
                                    (exception-diagnostic t)))]
                   (if (:diagnostic advanced)
                     (forward-result {:cursor cursor, :frame frame, :pending pending}
                                     (:status advanced)
                                     forwarded
                                     nil
                                     (:diagnostic advanced))
                     (recur successor
                            (:frame advanced)
                            (:pending advanced)
                            (dec remaining)
                            forwarded)))

                 :dao.stream/blocked
                 (forward-result {:cursor cursor, :frame frame, :pending pending}
                                 :retry
                                 forwarded
                                 outcome
                                 nil)

                 :dao.stream/end
                 (if (seq frame)
                   (forward-result {:cursor cursor, :frame frame, :pending pending}
                                   :partial-frame
                                   forwarded
                                   outcome
                                   {:rule :partial-frame, :datoms (vec frame)})
                   (forward-result {:cursor cursor, :frame frame, :pending pending}
                                   :source-ended
                                   forwarded
                                   outcome
                                   nil))

                 (forward-result {:cursor cursor, :frame frame, :pending pending}
                                 (forward-terminal-status outcome)
                                 forwarded
                                 outcome
                                 nil))))))))))

(ns yin.vm.debruijn
  "Alpha-canonical de Bruijn projection of the named Universal AST
   (docs/design/yin.vm.debruijn-projection.md).

   This namespace is D0+D1 of that design: the published :yin.debruijn/*
   dimension and its hash domain, §5's canonical value table with the NFC
   seam, §2's root framing and input validation, and §3's scope resolver.
   Node hashes, Merkle records, the canonical byte encoder, and the
   dao.stream forward-step are D2/D3/D4 and are not here: `project-datoms`
   returns the resolved semantic graph with no hashes at all.

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
            #?(:cljd ["package:unorm_dart/unorm_dart.dart" :as unorm]))
  #?(:cljd (:import ["dart:typed_data" Uint8List])))


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


(def dimension-hash
  "The published hash domain separator (§4): the content hash of the
   descriptor subgraph, per datom.md's 'the content hash of this subgraph
   IS the dimension's identity'. The descriptor is stable — its slots,
   encoding rule, and morphisms do not change under later phases. Its
   digest is transitional: D0 mints it through dao.jing's
   order-normalized content hash, and D3 re-pins this one digest over the
   settled canonical encoder. Only the digest ever changes; never the
   descriptor."
  (jing/content-hash descriptor))


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


(defn canonical-class
  "Classify `v` under §5's canonical value table: the class keyword when
   v is inside the canonical domain, nil when v is unsupported (a
   diagnostic at the walk). Recursive over collections; maps and sets
   sort by canonical encoded bytes only at D3 encode time, so any
   iteration order classifies identically."
  [v]
  (cond
    (nil? v) :nil
    (boolean? v) :bool
    (string? v) :string
    (keyword? v) :keyword
    (symbol? v) :symbol
    (bytes-like? v) :bytes
    (map? v) (when (every? (fn [entry]
                             (and (canonical-class (key entry))
                                  (canonical-class (val entry))))
                           v)
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


(def ^:private known-attributes
  "The complete legal :yin/* vocabulary on a walked node: the grammar's
   slots, :yin/type, the :yin/root framing fact, and §2's
   tolerated-and-ignored emission metadata."
  (reduce (fn [acc [_tag {:keys [scalars children]}]]
            (into acc (concat (map first scalars) (map first children))))
          #{:yin/type :yin/root :yin/tail? :yin/macro-name}
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
  "Build one frame's fact index {eid {attribute v}}. Input order never
   matters: the index is keyed by [e a], t and m are dropped, attributes
   outside the :yin namespace are ignored, and a second fact on the same
   [e a] is §2's duplicate structural fact."
  [datoms]
  (reduce (fn [index d]
            (let [[e a v] (check-datom d)]
              (if-not (= "yin" (namespace a))
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
   must be inside the grammar's vocabulary or §2's tolerated set. Facts
   on entities the walk never reaches are not diagnosed."
  [facts eid path]
  (doseq [a (keys facts)]
    (when-not (contains? known-attributes a)
      (throw (ex-info "Unknown :yin/* attribute on a walked node"
                      {:rule :unknown-attribute,
                       :attribute a,
                       :entity eid,
                       :path path})))))


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

(declare project-node)


(defn- build-node
  "Build one walked node's resolved semantic node — the §4 projected
   slots minus the hashes D2/D3 mint — recursing over children in the
   grammar's fixed order under the scope at each occurrence. Returns
   [node memo']; `active` is the set of eids on the current path."
  [tag index facts scalar eid stack memo active path]
  (let [child (fn [attr memo]
                (let [ref (child-ref facts attr eid path)]
                  (project-node index ref stack memo active (conj path attr))))
        children (fn [attr memo]
                   (reduce (fn [[nodes memo] ref]
                             (let [[node memo'] (project-node index
                                                              ref
                                                              stack
                                                              memo
                                                              active
                                                              (conj path attr))]
                               [(conj nodes node) memo']))
                           [[] memo]
                           (child-refs facts attr eid path)))]
    (case tag
      :literal [{:yin.debruijn/type :literal,
                 :yin.debruijn/value (get scalar :yin/value)}
                memo]
      :variable (let [name (get scalar :yin/name)
                      resolution (resolve-name stack name)]
                  [(if-let [bound (:bound resolution)]
                     {:yin.debruijn/type :variable,
                      :yin.debruijn/bound bound}
                     {:yin.debruijn/type :variable,
                      :yin.debruijn/free (:free resolution)})
                   memo])
      :lambda (let [params (get scalar :yin/params)
                    [body memo'] (project-node index
                                               (child-ref facts :yin/body eid path)
                                               (into [params] stack)
                                               memo
                                               active
                                               (conj path :yin/body))]
                [(merge {:yin.debruijn/type :lambda,
                         :yin.debruijn/arity (count params),
                         :yin.debruijn/body body}
                        ;; truthy only: an explicit false and an absent
                        ;; :yin/macro? project identically
                        (when (get scalar :yin/macro?)
                          {:yin.debruijn/macro? (get scalar :yin/macro?)}))
                 memo'])
      :application (let [operator-eid (child-ref facts :yin/operator eid path)
                         _ (check-unexpanded index eid operator-eid path)
                         [operator memo'] (project-node index
                                                        operator-eid
                                                        stack
                                                        memo
                                                        active
                                                        (conj path :yin/operator))
                         [operands memo''] (children :yin/operands memo')]
                     [{:yin.debruijn/type :application,
                       :yin.debruijn/operator operator,
                       :yin.debruijn/operands operands}
                      memo''])
      :dao.stream.apply/call (let [[operands memo'] (children :yin/operands memo)]
                               [{:yin.debruijn/type :dao.stream.apply/call,
                                 :yin.debruijn/op (get scalar :yin/op),
                                 :yin.debruijn/operands operands}
                                memo'])
      :if (let [[test memo'] (child :yin/test memo)
                [consequent memo''] (child :yin/consequent memo')
                [alternate memo'''] (child :yin/alternate memo'')]
            [{:yin.debruijn/type :if,
              :yin.debruijn/test test,
              :yin.debruijn/consequent consequent,
              :yin.debruijn/alternate alternate}
             memo'''])
      :vm/gensym [{:yin.debruijn/type :vm/gensym,
                   :yin.debruijn/prefix (get scalar :yin/prefix)}
                  memo]
      :vm/store-get [{:yin.debruijn/type :vm/store-get,
                      :yin.debruijn/key (get scalar :yin/key)}
                     memo]
      :vm/store-put [{:yin.debruijn/type :vm/store-put,
                      :yin.debruijn/value (get scalar :yin/value),
                      :yin.debruijn/key (get scalar :yin/key)}
                     memo]
      :vm/current-continuation [{:yin.debruijn/type :vm/current-continuation}
                                memo]
      :vm/park [{:yin.debruijn/type :vm/park}
                memo]
      :vm/resume (let [[val memo'] (child :yin/val-node memo)]
                   [{:yin.debruijn/type :vm/resume,
                     :yin.debruijn/parked-id (get scalar :yin/parked-id),
                     :yin.debruijn/val-node val}
                    memo'])
      :stream/make [{:yin.debruijn/type :stream/make,
                     :yin.debruijn/buffer (get scalar :yin/buffer)}
                    memo]
      :stream/put (let [[target memo'] (child :yin/target memo)
                        [val memo''] (child :yin/val-node memo')]
                    [{:yin.debruijn/type :stream/put,
                      :yin.debruijn/target target,
                      :yin.debruijn/val-node val}
                     memo''])
      (:stream/cursor :stream/next :stream/close)
      (let [[source memo'] (child :yin/source memo)]
        [{:yin.debruijn/type tag,
          :yin.debruijn/source source}
         memo']))))


(defn- project-node
  "Project one source node under `stack`, memoized by [source-eid stack]
   — §2's [source-eid lexical-context] key, where the lexical context is
   the complete stack of frame parameter vectors. The memo is an
   optimisation only: it is threaded explicitly and can never affect
   output identity. Cycle detection runs before the memo, so an eid on
   the active path is a diagnostic even if it was memoized at another
   occurrence."
  [index eid stack memo active path]
  (if (contains? active eid)
    (throw (ex-info "Cyclic AST reference"
                    {:rule :cycle, :entity eid, :path path}))
    (if-let [cached (find memo [eid stack])]
      [(val cached) memo]
      (let [facts (node-facts index eid path)
            _ (check-attributes facts eid path)
            tag (get facts :yin/type)
            grammar (or (get node-grammar tag)
                        (throw (ex-info "Unknown AST node type in projection"
                                        {:rule :unknown-node-type,
                                         :type tag,
                                         :entity eid,
                                         :path path})))
            scalar (validated-scalars tag grammar facts eid path)
            [node memo'] (build-node tag
                                     index
                                     facts
                                     scalar
                                     eid
                                     stack
                                     memo
                                     (conj active eid)
                                     path)]
        [node (assoc memo' [eid stack] node)]))))


(defn project-datoms
  "§6's pure projection of one complete rooted graph of :yin/* AST
   datoms. D0+D1 scope: §2 validation and §3 scope resolution only — no
   node hashes (D2/D3 mint the Merkle records and root fingerprint this
   will carry). Returns {:root node}, the resolved semantic graph; the
   :root wrapper key is the graph's root marker — the node map itself
   carries none, so it equals the same term appearing as a subterm. A
   bound occurrence carries :yin.debruijn/bound [frame-depth position],
   a free one :yin.debruijn/free name, and a lambda's binder is its
   :yin.debruijn/arity.

   Input order, source tempids, t, m, and non-:yin/* namespaces never
   enter the result. Every defect is ex-info carrying :rule (and, where
   the walk has descended, :entity and :path). For a stream carrying
   several adjacent graphs, frame it first with `frame-datoms` and
   project each frame — the per-frame fact index resets there."
  [datoms]
  (let [datoms (vec datoms)
        index (index-frame datoms)
        root-eid (frame-root datoms)
        [root _memo] (project-node index root-eid [] {} #{} [])]
    {:root root}))

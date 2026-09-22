(ns yin.vm.debruijn-linearize
  "B2 (docs/design/yin.vm.debruijn.stack.md S3.2): the stack lowerer.
   `lower-stack` consumes `yin.vm.debruijn-resolve/resolve`'s output
   (resolved tuples plus binder side table) and emits the canonical
   `:yin.debruijn.code/*` image plus its pc-keyed diagnostic side table,
   reproducing `yin.vm.linearize/lower`'s own flattening walk itself since
   `lower` cannot read resolved tuples (it reads `:yin/name` and
   `:yin/params`, which resolved tuples no longer carry). `adapt` is the
   composition `lower-stack` after `resolve`. `lift` is the reverse
   morphism, `:yin.debruijn.code/*` image plus side table back to a named
   canonical instruction vector; `named-canonical-vector` independently
   derives that same named vector from `yin.vm.linearize/lower`'s own
   output, for the structural comparison test and the lift law.

   `yin.vm.linearize` and named lowering semantics are unchanged: this
   namespace only reads `yin.vm.linearize/lower`'s output (for
   `named-canonical-vector`) and the public parts of `yin.vm.code`,
   `yin.vm.debruijn-code`, and `yin.vm.debruijn-resolve`."
  (:require [yin.vm :as vm]
            [yin.vm.code :as code]
            [yin.vm.debruijn :as debruijn]
            [yin.vm.debruijn-resolve :as resolve]))


;; =============================================================================
;; named-canonical-vector: lower's :yin.code/* datom batch -> the named
;; canonical instruction vector (UCF S7.3.2 shape)
;; =============================================================================
;; The actual named-path precedent for this step is `yin.vm.semantic/load-
;; image`'s own local `canonical` step (index the batch by entity, sort
;; instructions by `:yin.code/pc`, then read each entity's operands off
;; `yin.vm.code/vector-operand-table` with every `:pc`-kind operand
;; resolved to a position instead of an entity ref). That helper is
;; private to a namespace this phase may only read from, so it is
;; reimplemented here rather than called.

(defn- index-batch
  "Entity ids in order of first appearance, and each entity's attribute
   map. A repeated single-valued attribute keeps its last value, matching
   every other batch indexer in this lineage."
  [datoms]
  (reduce (fn [[order attrs] [e a v]]
            [(if (contains? attrs e) order (conj order e))
             (assoc-in attrs [e a] v)])
          [[] {}]
          datoms))


(defn named-canonical-vector
  "`lower`'s `:yin.code/*` datom batch turned into `{:vector v :source s}`:
   `v` is the canonical instruction vector (UCF S7.3.2), pc-indexed,
   `:yin.code/target`/`:yin.code/body` refs resolved to positions; `s` is
   the parallel pc-indexed vector of each instruction's `:yin.code/source`
   AST entity, carried only for diagnostics -- never read by
   `lower-stack` or `lift` below."
  [datoms]
  (let [[order attrs] (index-batch datoms)
        seg (first (filter #(= :segment (get-in attrs [% :yin.code/type])) order))
        structural #{:yin.code/segment :yin.code/pc :yin.code/op}
        instructions (filterv (fn [e]
                                (and (not= e seg)
                                     (some #(contains? (get attrs e) %) structural)))
                              order)
        n (count instructions)
        by-pc (into {} (map (fn [e] [(get-in attrs [e :yin.code/pc]) e])) instructions)
        ordered (mapv by-pc (range n))
        pc-of (zipmap ordered (range))
        tuple (fn [e]
                (let [ia (get attrs e)
                      mnem (:yin.code/op ia)]
                  (into [mnem]
                        (map (fn [[a kind]]
                               (if (= :pc kind)
                                 (get pc-of (get ia a))
                                 (get ia a)))
                             (get code/vector-operand-table mnem)))))]
    {:vector (mapv tuple ordered)
     :source (mapv #(get-in attrs [% :yin.code/source]) ordered)}))


;; =============================================================================
;; lower-stack: resolved tuples -> :yin.debruijn.code/* image + pc side table
;; =============================================================================
;; Reproduces `yin.vm.linearize/flatten-program`'s own recursion (operator
;; then operands, `if` test then arms, lambda bodies out of line in
;; discovery order) reading resolved attributes instead of named ones. A
;; `:variable` emits `[:load-bound depth position]` or `[:load-free name]`
;; straight from its resolved attributes; a `:lambda` emits `[:closure
;; arity body-pc]`. Every other node emits the carried mnemonic unchanged.
;; Labels are symbolic while emitting and resolved to pcs in a second
;; pass, exactly as the named linearizer's own flattening does.

(defn- flatten-resolved
  "`[pairs labels]`: `pairs` is `[[source-entity tuple] ...]` in pc order
   (tuple targets still symbolic labels for `:jump`/`:branch-false`/
   `:closure`), `labels` maps each label to the pc it marks."
  [get-attr root]
  (let [code (atom []), labels (atom {}), bodies (atom []), label-count (atom 0)
        fresh! #(swap! label-count inc)
        mark! (fn [l] (swap! labels assoc l (count @code)))
        emit! (fn [e tuple] (swap! code conj [e tuple]))]
    (letfn
      [(lower-node
         [e]
         (let [type (get-attr e :yin/type)]
           (case type
             :literal (emit! e [:const (get-attr e :yin/value)])
             :variable (let [free (get-attr e :yin.resolved/free)]
                         (emit! e (if (some? free)
                                    [:load-free free]
                                    [:load-bound (get-attr e :yin.resolved/depth)
                                     (get-attr e :yin.resolved/position)])))
             :lambda (let [l (fresh!)]
                       (swap! bodies conj [l e (get-attr e :yin/body)])
                       (emit! e [:closure (get-attr e :yin.resolved/arity) l]))
             :application (let [operands (get-attr e :yin/operands)]
                            (lower-node (get-attr e :yin/operator))
                            (emit! e [:push])
                            (doseq [o operands]
                              (lower-node o)
                              (emit! e [:push]))
                            (emit! e [:call (count operands)
                                      (boolean (get-attr e :yin/tail?))]))
             :if (let [else (fresh!), end (fresh!)]
                   (lower-node (get-attr e :yin/test))
                   (emit! e [:branch-false else])
                   (lower-node (get-attr e :yin/consequent))
                   (emit! e [:jump end])
                   (mark! else)
                   (lower-node (get-attr e :yin/alternate))
                   (mark! end))
             :dao.stream.apply/call (let [operands (get-attr e :yin/operands)]
                                      (doseq [o operands]
                                        (lower-node o)
                                        (emit! e [:push]))
                                      (emit! e [:ffi-call (get-attr e :yin/op)
                                                (count operands)]))
             :stream/make (emit! e [:stream-make (get-attr e :yin/buffer)])
             :stream/put (do (lower-node (get-attr e :yin/target))
                             (emit! e [:push])
                             (lower-node (get-attr e :yin/val-node))
                             (emit! e [:stream-put]))
             :stream/cursor (do (lower-node (get-attr e :yin/source))
                                (emit! e [:stream-cursor]))
             :stream/next (do (lower-node (get-attr e :yin/source))
                              (emit! e [:stream-next]))
             :stream/close (do (lower-node (get-attr e :yin/source))
                               (emit! e [:stream-close]))
             :vm/gensym (emit! e [:gensym (get-attr e :yin/prefix)])
             :vm/store-get (emit! e [:store-get (get-attr e :yin/key)])
             :vm/store-put (emit! e [:store-put (get-attr e :yin/key)
                                     (get-attr e :yin/value)])
             :vm/park (emit! e [:park])
             :vm/current-continuation (emit! e [:current-continuation])
             :vm/resume (do (lower-node (get-attr e :yin/val-node))
                            (emit! e [:resume (get-attr e :yin/parked-id)]))
             (throw (ex-info (str "Cannot lower-stack resolved node of type " type)
                             {:type type, :entity e})))))]
      (lower-node root)
      (emit! root [:halt])
      ;; A body may hold lambdas of its own, which append to `bodies` while
      ;; this loop runs.
      (loop [i 0]
        (when-let [[l lambda body] (get @bodies i)]
          (mark! l)
          (lower-node body)
          (emit! lambda [:return])
          (recur (inc i))))
      [@code @labels])))


(defn- resolve-labels
  [pairs labels]
  (mapv (fn [[e t]]
          [e (case (nth t 0)
               (:jump :branch-false) (assoc t 1 (get labels (nth t 1)))
               :closure (assoc t 2 (get labels (nth t 2)))
               t)])
        pairs))


(defn- pc-side-table
  "`{pc {:kind :closure, :params [sym ...], :source e}}` for every
   `:closure` pc, read from the params side table by resolved lambda id,
   and `{pc {:kind :var, :source e}}` for every load pc (section 3.2).
   `e` is always the NAMED source entity, read through the provenance
   `:source` map -- `sources` carries resolved record ids, which never
   equal the source ids `lower`'s own `:yin.code/source` names."
  [image sources source-of params]
  (into {}
        (keep (fn [pc]
                (let [t (nth image pc), rid (nth sources pc)]
                  (case (nth t 0)
                    :closure [pc {:kind :closure,
                                  :params (get params rid),
                                  :source (get source-of rid)}]
                    (:load-bound :load-free) [pc {:kind :var, :source (get source-of rid)}]
                    nil))))
        (range (count image))))


(defn lower-stack
  "`{:keys [tuples source params]}` (`yin.vm.debruijn-resolve/resolve`'s
   own return shape) -> `{:image v, :side-table st}`: `v` a
   `:yin.debruijn.code/*`-shaped canonical instruction vector, `st` the
   pc-keyed diagnostic side table section 3.2 specifies.

   Calls `yin.vm.debruijn-resolve/validate-resolved` first and refuses on
   its diagnostic, before doing anything else -- a shape-valid resolved
   set with an out-of-range bound reference is rejected here even when it
   never came from `resolve` itself."
  [{:keys [tuples source params]}]
  (when-let [defect (resolve/validate-resolved tuples source)]
    (throw (ex-info "Cannot lower-stack an invalid resolved-tuple set"
                    {:defect defect})))
  (let [{:keys [get-attr root-id error]} (vm/index-datoms tuples)]
    (when error
      (throw (ex-info "Cannot lower-stack a program with a dangling root" error)))
    (when (nil? root-id)
      (throw (ex-info "Cannot lower-stack a program with no root" {})))
    (let [[pairs labels] (flatten-resolved get-attr root-id)
          resolved-pairs (resolve-labels pairs labels)
          image (mapv second resolved-pairs)
          sources (mapv first resolved-pairs)]
      {:image image, :side-table (pc-side-table image sources source params)})))


;; =============================================================================
;; adapt: named datoms -> de Bruijn image + side table
;; =============================================================================

(defn adapt
  "The composition `lower-stack` after `resolve` (section 3.2): the one
   public entry that takes named `:yin/*` datoms straight to a
   `:yin.debruijn.code/*` image and its pc-keyed diagnostic side table."
  [named-datoms]
  (lower-stack (resolve/resolve named-datoms)))


;; =============================================================================
;; lift: de Bruijn image + side table -> named canonical vector
;; =============================================================================
;; This is genuinely different machinery from the resolver's tree walk
;; above: `lift` starts from a flat `:yin.debruijn.code/*` image, which
;; has no tree to walk, so it still needs to know, for a given pc, its
;; enclosing `:closure` chain -- the same information the removed fused
;; implementation's `body-owner`/`chain-of` computed, but only ever over
;; a de Bruijn image (never a named vector: resolution itself no longer
;; runs at this level) and only ever over an image B1's own validator has
;; already accepted. `lift`/`lift-once` therefore have this as a
;; documented precondition: they raise (an unchecked `ex-info` or an
;; index-out-of-bounds, not a named diagnostic) on a scope-invalid image
;; rather than repeating B1's `image-defect`, since B1's validator is the
;; sole admitter of an executable image on this path.

(defn- closure-ranges
  "`[[c body r] ...]` for every `:closure` of a B1-valid de Bruijn image
   `v`: the closure at pc `c` has body `[body, r]`, `r` the first
   `:return` at or after `body`. Trusts `v` is already scope valid (the
   precondition above); does not repeat B1's `image-defect`."
  [v]
  (let [n (count v)]
    (keep (fn [c]
            (let [t (nth v c)]
              (when (= :closure (nth t 0))
                (let [body (nth t 2)]
                  [c body (first (filter #(= :return (nth (nth v %) 0))
                                         (range body n)))]))))
          (range n))))


(defn- owner-of
  "pc -> the pc of the innermost `:closure` whose body range covers it,
   absent for a pc in the main sequence."
  [v]
  (into {}
        (mapcat (fn [[c body r]] (map (fn [pc] [pc c]) (range body (inc r)))))
        (closure-ranges v)))


(defn- chain-of
  "The enclosing-closure-pc chain of `pc`, innermost first."
  [owner pc]
  (loop [p pc, chain []]
    (if-let [c (get owner p)]
      (recur c (conj chain c))
      chain)))


(defn- free-names-of
  [v]
  (into #{} (keep (fn [t] (when (= :load-free (nth t 0)) (nth t 1)))) v))


(defn- synthesize-name
  "A symbol not in `used`, distinct for `[c i]` (`c` the owning `:closure`
   pc, `i` the parameter position) so two different binders never
   accidentally synthesize the same spelling."
  [used c i]
  (loop [n 0]
    (let [candidate (symbol (str "g__" c "_" i (when (pos? n) (str "_" n))))]
      (if (contains? used candidate)
        (recur (inc n))
        candidate))))


(defn- lift-params
  "pc -> parameter vector for every `:closure` of de Bruijn image `v`:
   `side-table`'s recorded parameters when present and of matching arity,
   else fresh names synthesized against `v`'s own free-name set and every
   parameter already assigned -- so a synthesized name never shadows a
   free name or collides with another synthesized binder anywhere in the
   image. `side-table` may be nil (full synthesis)."
  [v side-table]
  (let [free (free-names-of v)
        closure-pcs (keep-indexed (fn [pc t] (when (= :closure (nth t 0)) pc)) v)]
    (reduce
      (fn [acc c]
        (let [arity (nth (nth v c) 1)
              given (get-in side-table [c :params])
              used (into free (mapcat identity) (vals acc))
              params (if (and given (= (count given) arity))
                       given
                       (mapv #(synthesize-name used c %) (range arity)))]
          (assoc acc c params)))
      {}
      closure-pcs)))


(defn- lift-once
  "De Bruijn image `v` -> a named canonical vector, using `lift-params`'s
   pc -> parameter-vector map. `:load-bound [depth position]` becomes
   `[:var name]`, `name` read from the enclosing `:closure`'s parameter
   vector at `position`. `:load-free [name]` becomes `[:var name]`
   directly. `:closure`'s arity becomes its parameter vector. Every other
   tuple is copied unchanged."
  [v side-table]
  (let [owner (owner-of v)
        params (lift-params v side-table)]
    (mapv (fn [pc]
            (let [t (nth v pc), op (nth t 0)]
              (case op
                :load-bound
                (let [depth (nth t 1), position (nth t 2)
                      chain (chain-of owner pc)
                      c (nth chain depth)]
                  [:var (nth (get params c) position)])

                :load-free [:var (nth t 1)]

                :closure [:closure (get params pc) (nth t 2)]

                t)))
          (range (count v)))))


(defn- round-trips?
  "Whether `params` (a candidate pc -> parameter-vector map, from
   `lift-params`) would resolve every load in `v` back to the exact
   depth/position (`:load-bound`) or exact freeness (`:load-free`) it
   already carries -- the round-trip law a supplied side table must pass
   before `lift` trusts it, reusing `resolve-name` (the one helper this
   design reuses from the merged projection namespace) against the same
   image-level owner/chain this file already computes for `lift-once`,
   rather than re-deriving an image from a named vector the way the
   removed fused `rewrite` did."
  [v owner params]
  (every? (fn [pc]
            (let [t (nth v pc), op (nth t 0)]
              (case op
                :load-bound
                (let [depth (nth t 1), position (nth t 2)
                      chain (chain-of owner pc)
                      stack (mapv #(get params %) chain)
                      candidate (nth (get params (nth chain depth)) position)]
                  (= {:bound [depth position]} (debruijn/resolve-name stack candidate)))

                :load-free
                (let [name (nth t 1)
                      chain (chain-of owner pc)
                      stack (mapv #(get params %) chain)]
                  (= {:free name} (debruijn/resolve-name stack name)))

                true)))
          (range (count v))))


(defn lift
  "The lift (section 2/3.2): a function of a de Bruijn image `v` and its
   diagnostic side table `side-table` (may be nil) back to a
   `:yin.code/*`-shaped canonical vector. Precondition: `v` is scope
   valid (accepted by `yin.vm.debruijn-code/image-defect`); undefined
   behaviour -- an uncaught exception, not a named diagnostic -- results
   otherwise, since B1's validator is this path's sole admitter and this
   function does not repeat its check.

   A supplied `side-table` is trusted only after `round-trips?` holds for
   it; otherwise this falls back to full synthesis (`side-table` nil),
   which always round-trips by construction: synthesized names are
   pairwise distinct across the whole image and fresh against every free
   name, so `resolve-name`'s duplicate-parameter search is never
   accidentally triggered by a collision synthesis itself introduced."
  ([v] (lift v nil))
  ([v side-table]
   (let [owner (owner-of v)]
     (if (and side-table (round-trips? v owner (lift-params v side-table)))
       (lift-once v side-table)
       (let [synthesized-params (lift-params v nil)]
         (if (round-trips? v owner synthesized-params)
           (lift-once v nil)
           (throw (ex-info "Lift failed to round-trip even with synthesized names"
                           {:rule :lift-round-trip}))))))))

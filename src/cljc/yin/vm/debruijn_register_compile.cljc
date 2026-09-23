(ns yin.vm.debruijn-register-compile
  "R1 (docs/design/yin.vm.debruijn.register.md S4): `lower-register`
   consumes `yin.vm.debruijn-resolve/resolve`'s output (resolved tuples
   plus binder side table) and emits a `:yin.debruijn.register/*` image
   (`yin.vm.debruijn-register-code`'s `{:bodies [...], :instructions
   [...]}` shape) plus a pc-keyed diagnostic side table. `lift` is the
   reverse morphism, register image plus side table back to the same
   named `:yin.code/*`-shaped canonical vector `yin.vm.debruijn-linearize/
   lift` produces for a stack image (design section 3: 'both lifts land
   on the same named shape').

   THE LOWERER: a target-register-passing walk, not a stack-machine
   emit-then-move walk -- every node is lowered with a caller-supplied
   destination register and emits instructions that end with its value
   written there directly, so no `:move` is ever needed to relocate a
   value after the fact (`:if`'s two arms both target the expression's
   own one destination register for the same reason, per design section
   4.3). A body's temporary bank (T0..) is allocated by a deterministic
   linear-scan discipline: lowest free index first (`allocate-temp!`),
   freed the moment a value is consumed by its parent (`free-temp!`), so
   the physical count needed is exactly the peak number of temporaries
   simultaneously live. Locals (L0..L(n-1)) are a disjoint reserved
   range this phase's lowerer never targets or reads directly (`:load-
   bound` always reads the runtime frame chain into a fresh temporary,
   unchanged from B3's convention); see `yin.vm.debruijn-register-code`'s
   own docstring for why that is correct, not an oversight. Every
   `:call`'s `live` operand (design section 4.5) is a placeholder `[]`
   at emission time -- `fill-live` overwrites it in a second, separate
   pass, per body, once every body's instructions and pc numbering are
   final, calling `yin.vm.debruijn-register-code/body-liveness` (a
   backward dataflow over the emitted body, unrelated to the allocator's
   own forward free-list state).

   THE LIFT: the register image, viewed per body, is a flat instruction
   sequence with a recoverable grammar -- an atomic producer (`:const`/
   `:load-bound`/`:load-free`/`:closure`) is a complete expression by
   itself; any other instruction starts a chain of one or more recursively
   parsed sub-expressions terminated by exactly one `:call` (the chain is
   `[fn, arg...]`) or exactly one `:branch-false` (the chain is `[test]`,
   followed by the consequent and alternate sub-expressions in turn). This
   holds because the lowerer above only ever emits instructions in that
   shape (operator then operands left to right, fully expanded before the
   next; `if` test, then one arm at a time). `parse-expr` is that
   recursive-descent parser, translating each parsed production straight
   into `yin.vm.debruijn-linearize/named-canonical-vector`'s own tuple
   shapes (`[:const v]`, `[:var name]`, `[:closure params body-pc]`,
   `[:push]`, `[:call argc tail?]`, `[:branch-false target]`, `[:jump
   target]`, `[:return]`, `[:halt]`) -- it does not need to consult
   register identities at all, since the grammar alone (not register
   numbers) determines every sub-expression's boundary; it threads an
   explicit `base` (the absolute position the parsed production's own
   output will occupy in its body's final translated vector) top-down,
   so a `:branch-false`/`:jump` target is computed directly as an
   absolute position, with no separate label-resolution pass needed on
   this side (unlike the lowerer's own local labels, which exist only
   because a lowered `:if`'s jump targets are not yet known when the
   branch-false is first emitted -- here, both arms are fully parsed,
   and so fully sized, before their surrounding tuple is assembled).
   `:load-bound`/`:load-free` translate to `[:var name]` under the same
   trust rule `yin.vm.debruijn-linearize/lift` uses (round-trip through
   `resolve-name` before trusting a supplied side table's parameter
   names).

   `yin.vm.debruijn-linearize`, `yin.vm.debruijn-code`, `yin.vm.debruijn-
   resolve`, and `yin.vm.debruijn.stack` are only read from, never
   modified."
  (:require [yin.vm :as vm]
            [yin.vm.debruijn :as debruijn]
            [yin.vm.debruijn-register-code :as rcode]
            [yin.vm.debruijn-resolve :as resolve]))


;; =============================================================================
;; Deferred node types (design section 4.4, R0's frozen table)
;; =============================================================================
;; The SAME diagnostics R0's `node-type->register-mapping` names verbatim
;; for every node type its box defers to R2 -- copied here as data rather
;; than invented, cross-checked byte for byte against R0's frozen table by
;; this phase's own test file (requiring a test namespace from src/ is not
;; this codebase's convention; see `yin.vm.debruijn-register-code`'s
;; contract-version docstring for the identical resolution applied there).

(def deferred-diagnostics
  "Resolved node type -> the qualified diagnostic keyword R1 refuses it
   with, verbatim from R0's frozen `node-type->register-mapping`."
  {:dao.stream.apply/call :ffi-deferred-to-r2
   :stream/make :stream-deferred-to-r2
   :stream/put :stream-deferred-to-r2
   :stream/cursor :stream-deferred-to-r2
   :stream/next :stream-deferred-to-r2
   :stream/close :stream-deferred-to-r2
   :vm/gensym :gensym-deferred-to-r2
   :vm/store-get :store-deferred-to-r2
   :vm/store-put :store-deferred-to-r2
   :vm/park :park-deferred-to-r2
   :vm/resume :resume-deferred-to-r2
   :vm/current-continuation :current-continuation-deferred-to-r2})


(defn- refuse-deferred!
  [type e]
  (if-let [diagnostic (get deferred-diagnostics type)]
    (throw (ex-info (str "Cannot lower-register a node deferred to R2: " type)
                    {:rule diagnostic, :type type, :entity e}))
    (throw (ex-info (str "Cannot lower-register node of unknown type " type)
                    {:type type, :entity e}))))


;; =============================================================================
;; The per-body allocator
;; =============================================================================
;; `ctx` is one body's mutable lowering state (design section 4.2-4.3):
;; `code` (emitted `[source tuple]` pairs, source the resolved record id,
;; mirroring `lower-stack`'s own `[e tuple]` pairs), `free` (a sorted set
;; of freed temp indices, lowest first -- deterministic, independent of
;; any host map/set iteration order), `next-temp` (the next never-yet-used
;; temp index), `peak` (the highest `next-temp` ever reached, i.e. this
;; body's own peak live-temporary count, since a freed index is always
;; reused before `next-temp` advances), `labels`/`label-count` (local jump
;; labels for `:if`, resolved to this body's own local pcs before the
;; body is appended into the image), and `locals` (this body's fixed
;; arity; a temp index `i` is physical register `(+ locals i)`).

(defn- fresh-body-ctx
  [locals]
  {:code (atom []), :free (atom (sorted-set)), :next-temp (atom 0), :peak (atom 0),
   :labels (atom {}), :label-count (atom 0), :locals locals})


(defn- allocate-temp!
  "Lowest-available temp index: the lowest freed index if one exists, else
   the next never-used index."
  [ctx]
  (let [free @(:free ctx)]
    (if (seq free)
      (let [i (first free)]
        (swap! (:free ctx) disj i)
        i)
      (let [i @(:next-temp ctx)]
        (reset! (:next-temp ctx) (inc i))
        (swap! (:peak ctx) max (inc i))
        i))))


(defn- free-temp!
  [ctx i]
  (swap! (:free ctx) conj i))


(defn- reg-of
  [ctx temp-index]
  (+ (:locals ctx) temp-index))


(defn- emit!
  [ctx source tuple]
  (swap! (:code ctx) conj [source tuple]))


(defn- fresh-label!
  [ctx]
  (let [l @(:label-count ctx)]
    (swap! (:label-count ctx) inc)
    l))


(defn- mark-label!
  [ctx l]
  (swap! (:labels ctx) assoc l (count @(:code ctx))))


;; =============================================================================
;; lower-node!: one resolved record -> instructions writing its value into
;; `target-reg` (an already-resolved physical register)
;; =============================================================================
;; `bodies-queue` is the shared atom every body's lowering appends to,
;; exactly `lower-stack`'s own out-of-line body queue: each entry is
;; `{:source lambda-rid, :body-entity resolved-body-id, :arity n}`, and a
;; `:closure` instruction's own body-pc operand is temporarily the entry's
;; index into this queue -- resolved to that body's real start pc once
;; every body's own instruction count is known (`lower-register`, below).

(defn- lower-node!
  [get-attr bodies-queue ctx e target-reg]
  (let [type (get-attr e :yin/type)]
    (case type
      :literal
      (emit! ctx e [:const target-reg (get-attr e :yin/value)])

      :variable
      (let [free (get-attr e :yin.resolved/free)]
        (if (some? free)
          (emit! ctx e [:load-free target-reg free])
          (emit! ctx e [:load-bound target-reg
                        (get-attr e :yin.resolved/depth) (get-attr e :yin.resolved/position)])))

      :lambda
      (let [arity (get-attr e :yin.resolved/arity)
            body-e (get-attr e :yin/body)
            idx (do (swap! bodies-queue conj {:source e, :body-entity body-e, :arity arity})
                    (dec (count @bodies-queue)))]
        (emit! ctx e [:closure target-reg arity idx]))

      :application
      (let [op-e (get-attr e :yin/operator)
            operand-es (get-attr e :yin/operands)
            fn-temp (allocate-temp! ctx)
            fn-reg (reg-of ctx fn-temp)
            _ (lower-node! get-attr bodies-queue ctx op-e fn-reg)
            arg-temps (mapv (fn [oe]
                              (let [t (allocate-temp! ctx), r (reg-of ctx t)]
                                (lower-node! get-attr bodies-queue ctx oe r)
                                t))
                            operand-es)
            arg-regs (mapv #(reg-of ctx %) arg-temps)]
        ;; `live` (design section 4.5) is filled in a separate backward
        ;; pass over the whole assembled body, not here: `[]` is a
        ;; placeholder only, giving the tuple its final six-element arity
        ;; immediately so nothing downstream has to special-case it.
        ;; `lower-register` overwrites every `:call` tuple's live operand
        ;; with `yin.vm.debruijn-register-code/body-liveness`'s answer
        ;; once each body's instructions and pc numbering are final.
        (emit! ctx e [:call target-reg fn-reg arg-regs (boolean (get-attr e :yin/tail?)) []])
        (free-temp! ctx fn-temp)
        (doseq [t arg-temps] (free-temp! ctx t)))

      :if
      (let [test-temp (allocate-temp! ctx), test-reg (reg-of ctx test-temp)
            _ (lower-node! get-attr bodies-queue ctx (get-attr e :yin/test) test-reg)
            else-l (fresh-label! ctx), end-l (fresh-label! ctx)]
        (emit! ctx e [:branch-false test-reg else-l])
        (free-temp! ctx test-temp)
        (lower-node! get-attr bodies-queue ctx (get-attr e :yin/consequent) target-reg)
        (emit! ctx e [:jump end-l])
        (mark-label! ctx else-l)
        (lower-node! get-attr bodies-queue ctx (get-attr e :yin/alternate) target-reg)
        (mark-label! ctx end-l))

      (refuse-deferred! type e))))


;; =============================================================================
;; lower-register: resolved tuples -> {:image {...}, :side-table {...}}
;; =============================================================================

(defn- resolve-local-jumps
  [code labels]
  (mapv (fn [[e t]]
          [e (case (nth t 0)
               :jump (assoc t 1 (get labels (nth t 1)))
               :branch-false (assoc t 2 (get labels (nth t 2)))
               t)])
        code))


(defn- register-side-table
  "Mirrors `yin.vm.debruijn-linearize`'s own `pc-side-table` shape exactly,
   over this dimension's instructions: `{pc {:kind :closure, :params [...],
   :source e}}` for every `:closure` pc, `{pc {:kind :var, :source e}}` for
   every `:load-bound`/`:load-free` pc, `e` always the NAMED source entity."
  [instructions sources source-of params]
  (into {}
        (keep (fn [pc]
                (let [t (nth instructions pc), rid (nth sources pc)]
                  (case (nth t 0)
                    :closure [pc {:kind :closure, :params (get params rid),
                                  :source (get source-of rid)}]
                    (:load-bound :load-free) [pc {:kind :var, :source (get source-of rid)}]
                    nil))))
        (range (count instructions))))


(defn- fill-live
  "Design section 4.5: overwrite every `:call` tuple's placeholder `live`
   operand (position 5, emitted `[]` by `lower-node!`) with `yin.vm.
   debruijn-register-code/body-liveness`'s own answer, one body at a
   time. `body-liveness` never reads position 5, only the mnemonic, `rd`,
   `fn-reg`, `arg-regs`, and `tail?`, so this is safe to run once over
   the fully assembled, globally pc-numbered image -- the same image
   `register-image-defect` later checks, so the lowerer's own output can
   never trip its own `:live-exact` rule."
  [{:keys [bodies instructions] :as pre-image}]
  (reduce
    (fn [instrs bi]
      (reduce-kv (fn [ins pc live] (assoc ins pc (assoc (nth ins pc) 5 live)))
                 instrs
                 (rcode/body-liveness pre-image bi)))
    instructions
    (range (count bodies))))


(defn lower-register
  "`{:keys [tuples source params]}` (`yin.vm.debruijn-resolve/resolve`'s
   own return shape) -> `{:image v, :side-table st}`: `v` a `:yin.debruijn.
   register/*`-shaped `{:bodies [...], :instructions [...]}` image, `st`
   the pc-keyed diagnostic side table.

   Calls `yin.vm.debruijn-resolve/validate-resolved` first and refuses on
   its diagnostic, before doing anything else -- the same seam `lower-
   stack` uses, unconditional and not optional (design section 4.1, 2.1)."
  [{:keys [tuples source params]}]
  (when-let [defect (resolve/validate-resolved tuples source)]
    (throw (ex-info "Cannot lower-register an invalid resolved-tuple set"
                    {:defect defect})))
  (let [{:keys [get-attr root-id error]} (vm/index-datoms tuples)]
    (when error
      (throw (ex-info "Cannot lower-register a program with a dangling root" error)))
    (when (nil? root-id)
      (throw (ex-info "Cannot lower-register a program with no root" {})))
    (let [bodies-queue (atom [])
          main-ctx (fresh-body-ctx 0)
          main-temp (allocate-temp! main-ctx)
          main-reg (reg-of main-ctx main-temp)]
      (lower-node! get-attr bodies-queue main-ctx root-id main-reg)
      (emit! main-ctx root-id [:halt main-reg])
      (let [descs (loop [i 0, acc [{:ctx main-ctx, :locals 0}]]
                    (if-let [{:keys [source body-entity arity]} (get @bodies-queue i)]
                      (let [ctx (fresh-body-ctx arity)
                            t (allocate-temp! ctx), r (reg-of ctx t)]
                        (lower-node! get-attr bodies-queue ctx body-entity r)
                        (emit! ctx source [:return r])
                        (recur (inc i) (conj acc {:ctx ctx, :locals arity})))
                      acc))
            local-resolved (mapv (fn [{:keys [ctx locals]}]
                                   {:code (resolve-local-jumps @(:code ctx) @(:labels ctx))
                                    :locals locals, :registers (+ locals @(:peak ctx))})
                                 descs)
            lengths (mapv (comp count :code) local-resolved)
            starts (vec (reductions + 0 lengths))
            offset-tuple (fn [body-i [_e t]]
                           (case (nth t 0)
                             :jump (assoc t 1 (+ (nth t 1) (nth starts body-i)))
                             :branch-false (assoc t 2 (+ (nth t 2) (nth starts body-i)))
                             :closure (assoc t 3 (nth starts (inc (nth t 3))))
                             t))
            indexed (map-indexed vector local-resolved)
            instructions (into [] (mapcat (fn [[i lr]] (map #(offset-tuple i %) (:code lr)))) indexed)
            sources (into [] (mapcat (fn [[_i lr]] (map first (:code lr)))) indexed)
            bodies (mapv (fn [i {:keys [locals registers]}]
                           {:locals locals, :registers registers,
                            :start (nth starts i), :end (dec (nth starts (inc i)))})
                         (range (count local-resolved)) local-resolved)
            pre-image {:bodies bodies, :instructions instructions}
            live-instructions (fill-live pre-image)
            image {:bodies bodies, :instructions live-instructions}]
        {:image image,
         :side-table (register-side-table live-instructions sources source params)}))))


(defn adapt
  "The composition `lower-register` after `resolve` (mirrors `yin.vm.
   debruijn-linearize/adapt`)."
  [named-datoms]
  (lower-register (resolve/resolve named-datoms)))


;; =============================================================================
;; lift: register image + side table -> named canonical vector
;; =============================================================================
;; Precondition, identical in spirit to `yin.vm.debruijn-linearize/lift`'s
;; own: `image` is well formed (accepted by `yin.vm.debruijn-register-
;; code/register-image-defect`); undefined behaviour results otherwise,
;; since that validator is this path's sole admitter and this function
;; does not repeat its check.

(defn- owner-of-pc
  [{:keys [bodies]}]
  (into {}
        (mapcat (fn [[i {:keys [start end]}]] (map (fn [pc] [pc i]) (range start (inc end)))))
        (map-indexed vector bodies)))


(defn- enclosing-body-of
  "register-body-index -> the register-body-index of its immediately
   enclosing body (nil for body 0, the main/root body, which is never a
   lambda frame): the body containing the `:closure` instruction whose
   body-pc names this body's own `:start`."
  [{:keys [bodies instructions]} owner]
  (let [start->index (into {} (map-indexed (fn [i b] [(:start b) i])) bodies)]
    (into {}
          (keep (fn [pc]
                  (let [t (nth instructions pc)]
                    (when (= :closure (nth t 0))
                      (when-let [target (get start->index (nth t 3))]
                        [target (get owner pc)])))))
          (range (count instructions)))))


(defn- chain-of-body
  "The enclosing-lambda-frame chain of register-body-index `bi`, innermost
   first: `bi` itself (its own arity is depth 0) then each ancestor,
   stopping before body 0 (the root/main body is never itself a frame --
   `resolve`'s own walk begins with an empty lexical stack, extended only
   on entering a `:lambda`, section 2.1)."
  [enclosing bi]
  (if (zero? bi)
    []
    (loop [b bi, chain []]
      (if (zero? b)
        chain
        (recur (get enclosing b) (conj chain b))))))


(defn- free-names-of
  [instructions]
  (into #{} (keep (fn [t] (when (= :load-free (nth t 0)) (nth t 2)))) instructions))


(defn- synthesize-name
  [used bi i]
  (loop [n 0]
    (let [candidate (symbol (str "g__" bi "_" i (when (pos? n) (str "_" n))))]
      (if (contains? used candidate)
        (recur (inc n))
        candidate))))


(defn- register-lift-params
  "register-body-index -> parameter vector, for every lambda body of
   `image`: `side-table`'s recorded parameters (keyed by the owning
   `:closure` instruction's own pc, `resolve`'s own `:params` shape)
   when present and of matching arity, else fresh names synthesized
   against `image`'s free-name set and every parameter already assigned.
   `side-table` may be nil (full synthesis). Mirrors `yin.vm.debruijn-
   linearize`'s own `lift-params` exactly, keyed by body index instead of
   pc, since a register body is identified by index, not by a single pc."
  [{:keys [bodies instructions]} side-table]
  (let [free (free-names-of instructions)
        start->index (into {} (map-indexed (fn [i b] [(:start b) i])) bodies)
        closure-pcs (keep-indexed (fn [pc t] (when (= :closure (nth t 0)) pc)) instructions)]
    (reduce
      (fn [acc pc]
        (let [t (nth instructions pc)
              arity (nth t 2), bi (get start->index (nth t 3))
              given (get-in side-table [pc :params])
              used (into free (mapcat identity) (vals acc))
              params (if (and given (= (count given) arity))
                       given
                       (mapv #(synthesize-name used bi %) (range arity)))]
          (assoc acc bi params)))
      {}
      closure-pcs)))


(defn- register-round-trips?
  "Whether `params` (register-body-index -> parameter vector) would
   resolve every `:load-bound`/`:load-free` in `image` back to the exact
   depth/position or exact freeness it already carries -- mirrors `yin.vm.
   debruijn-linearize`'s own `round-trips?`, reusing the same `resolve-
   name` this design names as the one helper reused from the merged
   projection namespace."
  [{:keys [instructions]} owner enclosing params]
  (every? (fn [pc]
            (let [t (nth instructions pc)]
              (case (nth t 0)
                :load-bound
                (let [depth (nth t 2), position (nth t 3)
                      chain (chain-of-body enclosing (get owner pc))
                      stack (mapv #(get params %) chain)
                      candidate (nth (get params (nth chain depth)) position)]
                  (= {:bound [depth position]} (debruijn/resolve-name stack candidate)))

                :load-free
                (let [name (nth t 2)
                      chain (chain-of-body enclosing (get owner pc))
                      stack (mapv #(get params %) chain)]
                  (= {:free name} (debruijn/resolve-name stack name)))

                true)))
          (range (count instructions))))


;; -----------------------------------------------------------------------------
;; parse-range: a single left-to-right pass over one register body's flat
;; instruction range, translating it to a named tuple sequence.
;; =============================================================================
;; The register pc stream, read left to right within one body, is a
;; postfix (reverse-Polish) notation over an implicit value stack: an
;; atomic instruction (`:const`/`:load-bound`/`:load-free`/`:closure`)
;; pushes one value; `:call` pops `1 + argc` values (fn then its args, in
;; push order) and pushes back one combined value (the translated
;; operand chain, each popped value's own tuples followed by `[:push]`,
;; then `[:call argc tail?]`); `:branch-false`/`:jump` mark an `if`, whose
;; consequent and alternate sub-ranges are read directly off their own
;; operands (`:branch-false`'s target is the else pc, immediately after
;; which is the alternate; the matching `:jump` -- always the consequent's
;; own last instruction, by construction -- names the end pc) and each
;; recursively parsed over ITS OWN sub-range by this same function, then
;; pushed back as one combined value exactly as `:call` does. This holds
;; because the lowerer above only ever emits instructions in that shape
;; (operator then operands left to right, fully expanded before the next;
;; `if` test, then one arm at a time, each arm's own last instruction
;; therefore always adjacent to what follows it) -- there is no operator-
;; precedence ambiguity to resolve, unlike a general expression grammar,
;; because every reduction step consumes values strictly right-to-left
;; from the stack top and the stream never interleaves two productions.
;;
;; `base` is the absolute position this range's own translated output will
;; occupy in its body's final vector, threaded so `:branch-false`/`:jump`
;; targets can be written as absolute positions directly, with no separate
;; label-resolution pass on this side (unlike the lowerer's own local
;; labels, which exist only because a lowered `:if`'s jump targets are not
;; yet known when its `:branch-false` is first emitted; here, an `if`'s
;; consequent and alternate are each fully parsed, and so fully sized,
;; before the tuple that names their absolute targets is assembled).
;; Returns the range's one translated production (a flat tuple vector);
;; the range MUST reduce to exactly one stack value by its own end, or
;; this is a malformed image (`:lift-shape`, a precondition violation,
;; not a named diagnostic -- `register-image-defect` is this path's sole
;; admitter, per this file's own lift docstring).

(defn- var-name-at
  [image owner enclosing params pc]
  (let [t (nth (:instructions image) pc)]
    (case (nth t 0)
      :load-bound (let [depth (nth t 2), position (nth t 3)
                        chain (chain-of-body enclosing (get owner pc))]
                    (nth (get params (nth chain depth)) position))
      :load-free (nth t 2))))


(defn- start->body-index
  [{:keys [bodies]}]
  (into {} (map-indexed (fn [i b] [(:start b) i])) bodies))


;; A stack item is `{:tuples [...]}`, its own translated production, with
;; NO trailing `[:push]` yet: whether it gets one depends entirely on how
;; it is later consumed, which `parse-range` does not know at push time.
;; `named-canonical-vector`'s own source (`flatten-resolved`'s `:if`/
;; `:application` cases) pushes a value's `[:push]` only at the moment a
;; SIBLING (another operand) is about to be computed after it, or right
;; before `:call` consumes the last operand -- never for a value consumed
;; directly as an `if`'s own test, and never for a body's final,
;; unconsumed return value. `finalize-top` performs exactly that: it is
;; called immediately before stacking any new item on a nonempty stack
;; (the previous top can no longer be a pending if-test once something
;; else follows it) and immediately before a `:call` consumes its popped
;; operands (the topmost of which has not yet been finalized by a
;; following sibling, since none exists inside this `:call`).

(defn- finalize-top
  [stack]
  (if (empty? stack)
    [stack 0]
    [(conj (pop stack) (update (peek stack) :tuples conj [:push])) 1]))


(defn- push-item
  [out stack tuples]
  (let [[stack1 pushed] (finalize-top stack)]
    [(+ out pushed (count tuples)) (conj stack1 {:tuples tuples})]))


(defn- parse-range
  [image owner enclosing params base start end]
  (let [instructions (:instructions image)]
    (loop [pos start, out (long base), stack []]
      (if (>= pos end)
        (if (= 1 (count stack))
          (:tuples (first stack))
          (throw (ex-info "Malformed register body: did not reduce to one value"
                          {:rule :lift-shape, :start start, :end end, :stack-depth (count stack)})))
        (let [t (nth instructions pos)]
          (case (nth t 0)
            :const
            (let [[out1 stack1] (push-item out stack [[:const (nth t 2)]])]
              (recur (inc pos) out1 stack1))

            (:load-bound :load-free)
            (let [[out1 stack1] (push-item out stack
                                           [[:var (var-name-at image owner enclosing params pos)]])]
              (recur (inc pos) out1 stack1))

            :closure
            (let [bi (get (start->body-index image) (nth t 3))
                  [out1 stack1] (push-item out stack [[:closure (get params bi) bi]])]
              (recur (inc pos) out1 stack1))

            :call
            (let [argc (count (nth t 3)), tail? (nth t 4), n (inc argc)
                  depth (count stack)
                  popped (subvec stack (- depth n) depth)
                  kept (subvec stack 0 (- depth n))
                  [popped1 pushed] (finalize-top popped)
                  combined (conj (into [] (mapcat :tuples) popped1) [:call argc tail?])]
              (recur (inc pos) (+ out pushed 1) (conj kept {:tuples combined})))

            :branch-false
            (let [else-pc (nth t 2), jump-pos (dec else-pc), jt (nth instructions jump-pos)
                  _ (when-not (= :jump (nth jt 0))
                      (throw (ex-info "Malformed register consequent: expected :jump"
                                      {:rule :lift-shape, :pc jump-pos})))
                  end-pc (nth jt 1)
                  test (peek stack), kept (pop stack)
                  ;; `:branch-false`'s own target is the ALTERNATE's start
                  ;; (the "else" label, per `lower-node!`'s own :if
                  ;; handling: marked AFTER the consequent and its jump),
                  ;; not the consequent's -- the consequent starts right
                  ;; after the branch-false tuple itself, one slot below.
                  ;; The test itself is never finalized with a push: it is
                  ;; consumed directly, exactly as `lower-node!` never
                  ;; emits one for it either.
                  cons-base (inc out)
                  cons-tuples (parse-range image owner enclosing params cons-base (inc pos) jump-pos)
                  alt-target (+ cons-base (count cons-tuples) 1)
                  alt-tuples (parse-range image owner enclosing params alt-target else-pc end-pc)
                  end-target (+ alt-target (count alt-tuples))
                  combined (-> (vec (:tuples test))
                               (conj [:branch-false alt-target])
                               (into cons-tuples)
                               (conj [:jump end-target])
                               (into alt-tuples))]
              (recur end-pc end-target (conj kept {:tuples combined})))))))))


(defn- lift-body
  [image owner enclosing params {:keys [start end]} terminal-mnemonic]
  (conj (vec (parse-range image owner enclosing params 0 start end)) [terminal-mnemonic]))


(defn- lift-image-with
  "The whole register image translated to a named canonical vector, given
   already-trusted `params` (register-body-index -> parameter vector):
   translate each body independently (body-local absolute positions,
   `lift-body`), then offset every body's `:jump`/`:branch-false` targets
   by that body's own new start and every `:closure` target from a
   register-body-index to its body's new start -- the same two-pass shape
   `lower-register` itself uses to assign global pcs."
  [image owner enclosing params]
  (let [bodies (:bodies image)
        per-body (mapv (fn [i body]
                         (lift-body image owner enclosing params body (if (zero? i) :halt :return)))
                       (range (count bodies)) bodies)
        lengths (mapv count per-body)
        starts (vec (reductions + 0 lengths))
        offset (fn [bi tup]
                 (case (nth tup 0)
                   (:jump :branch-false) (assoc tup 1 (+ (nth tup 1) (nth starts bi)))
                   :closure (assoc tup 2 (nth starts (nth tup 2)))
                   tup))]
    (into [] (mapcat (fn [[bi tuples]] (map #(offset bi %) tuples))) (map-indexed vector per-body))))


(defn lift
  "The lift (design section 3, mirroring `yin.vm.debruijn-linearize/lift`'s
   own trust rule exactly): a register image `v` and its diagnostic side
   table (may be nil) -> a `:yin.code/*`-shaped named canonical vector. A
   supplied `side-table` is trusted only after `register-round-trips?`
   holds for its parameter names; otherwise this falls back to full
   synthesis, which always round-trips by construction."
  ([image] (lift image nil))
  ([image side-table]
   (let [owner (owner-of-pc image)
         enclosing (enclosing-body-of image owner)
         given-params (when side-table (register-lift-params image side-table))]
     (if (and given-params (register-round-trips? image owner enclosing given-params))
       (lift-image-with image owner enclosing given-params)
       (let [synthesized-params (register-lift-params image nil)]
         (if (register-round-trips? image owner enclosing synthesized-params)
           (lift-image-with image owner enclosing synthesized-params)
           (throw (ex-info "Register lift failed to round-trip even with synthesized names"
                           {:rule :lift-round-trip}))))))))

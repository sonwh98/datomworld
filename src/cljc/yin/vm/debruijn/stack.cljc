(ns yin.vm.debruijn.stack
  "B3 (docs/design/yin.vm.debruijn.stack.md, 'B3: de Bruijn VM kernel'): the
   sibling stack VM that interprets RAW positional instruction vectors --
   `[:closure arity body-pc]`, `[:load-bound depth position]`,
   `[:load-free name]`, `[:const value]`, `[:call argc tail?]`, `[:return]`,
   `[:jump target]`, `[:branch-false target]`, `[:halt]`, `[:store-get key]`,
   `[:store-put key value]` -- whose shapes come from
   `yin.vm.code/vector-operand-table` and section 2 of the design doc.

   This namespace never requires `yin.vm.debruijn-code`: every program used
   here and in its own test is a hand-built instruction vector, the same
   technique B1's own standalone validator tests use. B2 (the named-datom
   lowerer, `yin.vm.debruijn-linearize`) produces real images against this
   kernel from its own test namespace instead of from here.

   The instruction set in scope is frames, closures, loads, calls, returns,
   branches, `:const`, and `:store-get`/`:store-put` (section 4). Stream
   operations, primitives-as-effects, FFI, gensym, current-continuation,
   park, and resume are B4's phase; an opcode this namespace does not
   recognize fails loudly with a `:not-yet-implemented` ex-info rather than
   silently no-op-ing.

   Applying a resolved primitive host function (via `:load-free`) is in
   scope for `:call`, because `:load-free` (item 5, section 4) is
   meaningless without a way to invoke what it resolves to; the pure
   `(apply f args)` path below is the same one the named engine's
   `apply-call` uses for a `fn?` callee, minus its effect handling, which is
   an FFI concern this phase does not implement."
  (:refer-clojure :exclude [eval])
  (:require [yin.vm :as vm]
            [yin.vm.engine :as engine]))


;; =============================================================================
;; State
;; =============================================================================
;; The record carries the eight fields of the design's explicit VM state
;; (:segment :pc :frames :free-env :stack :continuation :store :status)
;; plus :primitives and :modules, which are not part of that transition
;; state -- they are fixed composition values for this VM instance, the same
;; role they play alongside the SemanticVM record's own :control/:env/:stack
;; state (`yin.vm.semantic/SemanticVM`) -- but are required to give
;; `:load-free` the same `env -> store -> primitives -> module registry`
;; order `yin.vm.engine/resolve-var` already implements for the named VM.

(defrecord DebruijnVM
  [segment      ; vector of instructions, pc-indexed; the whole program
   pc           ; program counter into segment
   frames       ; positional frame stack, outermost first, innermost last
   free-env     ; initial free-name environment map, fixed for this instance
   stack        ; operand stack, a vector
   continuation ; vector of return frames (innermost last); see step-call
   store        ; heap map, keyed by whatever :store-get/:store-put use
   status       ; :running or :halted
   primitives   ; primitive registry, for :load-free
   modules])    ; module registry, for :load-free


(defn create-vm
  "Build a fresh `DebruijnVM` over `segment` (a vector of instructions).

   Options: `:free-env`, `:store`, `:primitives`, `:modules` (all default to
   `{}`, matching the named VM's own empty defaults except `:primitives`,
   which callers pass `yin.vm/primitives` when they want the standard
   registry, exactly as `yin.vm/empty-state` does for the named VM).

   Always fresh: never resume or mutate a VM that has already run, per the
   design's D4 fixture restriction (the named VM's environment-leak defect
   is out of scope here and must not be reproduced by accident)."
  ([segment] (create-vm segment {}))
  ([segment opts]
   (map->DebruijnVM
     {:segment segment,
      :pc 0,
      :frames [],
      :free-env (or (:free-env opts) {}),
      :stack [],
      :continuation [],
      :store (or (:store opts) {}),
      :status :running,
      :primitives (or (:primitives opts) {}),
      :modules (or (:modules opts) {})})))


;; =============================================================================
;; Frame addressing
;; =============================================================================

(defn- frame-value
  "Section 4: frame 0 for `:load-bound` is the INNERMOST frame, read from
   the END of `:frames` -- the opposite of a naive front-of-vector reading,
   and deliberate per the design. `frames` is outermost-first, so depth `d`
   is `(count frames) - 1 - d`."
  [frames depth position]
  (let [n (count frames)
        idx (- n 1 depth)]
    (when (or (neg? idx) (>= idx n))
      (throw (ex-info "load-bound: depth out of range"
                      {:rule :load-bound-depth, :depth depth,
                       :frame-count n})))
    (let [frame (nth frames idx)]
      (when (or (neg? position) (>= position (count frame)))
        (throw (ex-info "load-bound: position out of range"
                        {:rule :load-bound-position, :depth depth,
                         :position position, :frame-arity (count frame)})))
      (nth frame position))))


(defn- bind-positional
  "The positional equivalent of the named `bind-params`
   (`yin.vm.engine/bind-params`, which zips params with args and nil-fills):
   `(vec (take arity (concat args (repeat nil))))`. Missing arguments are
   nil-filled; extra arguments are dropped. Not `bind-params` itself, which
   is name-keyed -- this is positional."
  [arity args]
  (vec (take arity (concat args (repeat nil)))))


;; =============================================================================
;; The step function
;; =============================================================================

(defn- step1
  "Execute exactly one instruction and return the resulting VM. Assumes the
   VM is not halted; callers (`step`, `run`) check that first."
  [vm]
  (let [{:keys [segment pc frames free-env stack continuation store
                primitives modules]}
        vm
        inst (nth segment pc)
        op (nth inst 0)]
    (case op
      :const
      (assoc vm :pc (inc pc) :stack (conj stack (nth inst 1)))

      :load-bound
      (let [depth (nth inst 1), position (nth inst 2)]
        (assoc vm
               :pc (inc pc)
               :stack (conj stack (frame-value frames depth position))))

      :load-free
      (let [name (nth inst 1)
            value (engine/resolve-var free-env store primitives modules
                                      name)]
        (assoc vm :pc (inc pc) :stack (conj stack value)))

      ;; This positional dimension has no `:macro?` flag on `:closure` (it
      ;; is named-datom-only, section 4): the instruction carries only
      ;; arity and a body pc, so there is nothing here to ignore.
      :closure
      (let [arity (nth inst 1), body-pc (nth inst 2)]
        (assoc vm
               :pc (inc pc)
               :stack (conj stack
                            {:type :closure, :arity arity,
                             :body-pc body-pc, :frames frames})))

      ;; The named VM (semantic.cljc) keeps a separate `val` accumulator
      ;; distinct from its operand stack `St`, so `:push` there commits
      ;; `val` onto `St` (St <- St ++ [val]). This machine has no such
      ;; split: every value-producing case above already conjes its
      ;; result straight onto `:stack`. By the time control reaches a
      ;; `:push`, the value it would push is already there -- so here
      ;; it is a no-op that only advances `pc`. B4 obligation: every future
      ;; value-producing opcode (:stream-*, :ffi-call, :gensym,
      ;; :current-continuation, :resume) must conj its result straight onto
      ;; `:stack` the same way, or a `:push` immediately after it will
      ;; silently drop the value -- there is no `val` register to recover
      ;; it from.
      :push
      (assoc vm :pc (inc pc))

      :call
      (let [argc (nth inst 1), tail? (nth inst 2)
            total (count stack)
            f-pos (- total argc 1)
            f (nth stack f-pos)
            args (subvec stack (inc f-pos) total)
            stack' (subvec stack 0 f-pos)]
        (cond
          (and (map? f) (= :closure (:type f)))
          (let [locals (bind-positional (:arity f) args)
                body-frames (conj (:frames f) locals)
                continuation' (if tail?
                                continuation
                                (conj continuation
                                      {:return-pc (inc pc), :frames frames,
                                       :stack-base (count stack')}))]
            (assoc vm
                   :pc (:body-pc f)
                   :frames body-frames
                   :stack stack'
                   :continuation continuation'))

          (fn? f)
          (assoc vm :pc (inc pc) :stack (conj stack' (apply f args)))

          :else
          (throw (ex-info "Cannot apply non-function" {:fn f}))))

      :return
      (let [val (peek stack)
            frame (peek continuation)]
        (if frame
          (assoc vm
                 :pc (:return-pc frame)
                 :frames (:frames frame)
                 :stack (conj (subvec stack 0 (:stack-base frame)) val)
                 :continuation (pop continuation))
          (assoc vm :status :halted, :stack [val])))

      :jump
      (assoc vm :pc (nth inst 1))

      :branch-false
      (let [val (peek stack), stack' (pop stack)]
        (if val
          (assoc vm :pc (inc pc) :stack stack')
          (assoc vm :pc (nth inst 1) :stack stack')))

      :halt
      (assoc vm :status :halted)

      :store-get
      (let [key (nth inst 1)]
        (assoc vm :pc (inc pc) :stack (conj stack (get store key))))

      :store-put
      (let [key (nth inst 1), value (nth inst 2)]
        (assoc vm
               :pc (inc pc)
               :stack (conj stack value)
               :store (assoc store key value)))

      (throw (ex-info (str "Not yet implemented in B3: " op)
                      {:rule :not-yet-implemented, :op op})))))


;; =============================================================================
;; IVM / IVMState
;; =============================================================================
;; No new protocol methods (section 4, point 7). `environment` is not
;; implemented at all (point 8): the design defers it until a
;; frame-to-named lift exists, and returning the raw positional frame
;; vector from it would be wrong once the method does exist.

(extend-type DebruijnVM
  vm/IVM
  (step [this] (if (= :halted (:status this)) this (step1 this)))
  (run [this]
    (loop [vm this]
      (if (= :halted (:status vm)) vm (recur (step1 vm)))))
  (eval [_ ast]
    (throw (ex-info
             "The B3 kernel executes raw instruction vectors, not AST: lower and adapt it first (yin.vm.debruijn-linearize/adapt), then load and run the resulting image"
             {:ast ast})))
  (reset [this]
    (assoc this
           :pc 0, :frames [], :stack [], :continuation [], :status :running))
  (halted? [this] (= :halted (:status this)))
  (blocked? [_this] false)
  (value [this] (peek (:stack this)))

  vm/IVMState
  (control [this] {:pc (:pc this)})
  (environment [_this]
    (throw (ex-info
             "environment is not implemented in B3: the design defers it until a frame-to-named lift exists"
             {:rule :not-yet-supported})))
  (store [this] (:store this))
  (continuation [this] (:continuation this)))

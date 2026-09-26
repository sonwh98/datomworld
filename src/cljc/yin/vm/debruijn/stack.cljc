(ns yin.vm.debruijn.stack
  "B3 + B4 (docs/design/yin.vm.debruijn.stack.md, 'B3: de Bruijn VM kernel'
   and 'B4: effects and continuations'): the sibling stack VM that
   interprets RAW positional instruction vectors --
   `[:closure arity body-pc]`, `[:load-bound depth position]`,
   `[:load-free name]`, `[:const value]`, `[:call argc tail?]`, `[:return]`,
   `[:jump target]`, `[:branch-false target]`, `[:halt]`, `[:store-get key]`,
   `[:store-put key value]`, `[:gensym prefix]`, `[:stream-make buffer]`,
   `[:stream-put]`, `[:stream-cursor]`, `[:stream-next]`, `[:stream-close]`,
   `[:current-continuation]`, `[:park]`, `[:resume parked-id]`,
   `[:ffi-call op argc]`, `[:define name]` (Rule R's definition
   transition) -- whose shapes come from
   `yin.vm.code/vector-operand-table` and section 2 of the design doc.

   B3 implemented the pure kernel: frames, closures, loads, calls, returns,
   branches, `:const`, and the store operations. B4 puts this machine on
   the shared scheduler seam `yin.vm.engine.md` states and section 4.1 of
   the design decides: the engine's bookkeeping keys replace `:status`;
   `run` is `engine/run-loop`; every blocking instruction parks a pure-data
   entry carrying this machine's register payload; one restore function,
   `stack-restore`, puts an entry back into the registers; and the FFI
   two-step lives in that restore, keyed on the entry keys `:request-sent`
   and `:call-id`, as the semantic VM places it.

   The register payload of every parked record, wait entry, and reified
   continuation is

       {:segment :pc :frames :stack :continuation :format :hash}

   `:format` is `:yin.debruijn.code` and `:hash` is the loaded image's H
   (`yin.vm.debruijn-code/image-hash`), so a continuation is never
   interpreted by a VM or against an image other than its own:
   `stack-restore` refuses any entry whose model or image differs with a
   qualified `:continuation-format` outcome. That is the same-model,
   same-image rule; cross-model transport is not a lift here and is
   deliberately unsupported (design section 4.1, item 4).

   Every program used in this namespace's own tests is a hand-built
   instruction vector, the same technique B1's standalone validator tests
   use; B2 (`yin.vm.debruijn-linearize`) produces real images against this
   kernel from its own test namespace. This namespace requires
   `yin.vm.debruijn-code` for one thing only: computing H at load time.

   B3's one accumulator-free obligation carries into every B4 opcode: there
   is no `val` register, so every value-producing instruction conjes its
   result straight onto `:stack`, and a `:push` is a pc advance."
  (:refer-clojure :exclude [eval])
  (:require [dao.stream.apply :as apply2]
            [yin.vm :as vm]
            [yin.vm.debruijn-code :as dcode]
            [yin.vm.engine :as engine]
            [yin.vm.ffi :as ffi]
            [yin.vm.module :as module]))


;; =============================================================================
;; State
;; =============================================================================
;; The record carries the design's explicit VM state (:segment :pc :frames
;; :free-env :stack :continuation :store), the engine's bookkeeping keys
;; (:blocked? :halted? :wait-set :ready-queue :parked :id-counter :value
;; :make-stream -- `yin.vm.engine.md` section 1), and the fixed composition
;; values :primitives and :modules that `:load-free` resolves through in
;; the same `env -> store -> primitives -> module registry` order
;; `yin.vm.engine/resolve-var` implements for the named VM. :hash is the
;; loaded image's H, stamped on every register payload; :bridge is the
;; explicit host-side FFI bridge state `yin.vm.ffi` attaches.

(defrecord DebruijnVM
  [segment      ; vector of instructions, pc-indexed; the whole program
   hash         ; H of `segment` (yin.vm.debruijn-code/image-hash)
   pc           ; program counter into segment
   frames       ; positional frame stack, outermost first, innermost last
   free-env     ; initial free-name environment map, fixed for this instance
   stack        ; operand stack, a vector
   continuation ; vector of return frames (innermost last); see step-call
   store        ; heap map, keyed by whatever :store-get/:store-put use
   blocked?     ; engine: true while parked in the wait set
   halted?      ; engine: true when the active continuation has completed
   wait-set     ; engine: vector of pure-data entries waiting on a transport
   ready-queue  ; engine: vector of woken entries
   parked       ; engine: parked continuations map
   id-counter   ; engine: counter for gensym, stream, cursor and park ids
   value        ; engine: last computed value
   make-stream  ; host-supplied stream constructor, or nil
   bridge       ; explicit host-side FFI bridge state, or nil
   primitives   ; primitive registry, for :load-free
   modules])    ; module registry, for :load-free and effect dispatch


(def format-tag
  "The model tag every register payload carries as `:format`."
  :yin.debruijn.code)


(defn- install-image
  "Install an admitted (or empty) `segment` as the one image, unchecked."
  [vm segment]
  (assoc vm
         :segment segment
         :hash (dcode/image-hash segment)
         :pc 0
         :frames []
         :stack []
         :continuation []
         :halted? (empty? segment)
         :blocked? false
         :value nil))


(defn load-image
  "Load `segment` (a vector of instructions) into `vm` as its one image:
   H is recomputed, the registers are reset to pc 0 with empty frames,
   stack, and continuation, and the machine is running unless the segment
   is empty. The store, the parked map, the wait set, the ready queue,
   the id counter, and the composition values survive, exactly as they
   survive a `yin.vm.semantic/load-vector`. A continuation parked under an
   earlier image cannot be restored against this one: `stack-restore`
   refuses it by H.

   `contract` is required and compared with `vm/stack-contract` first
   (`:contract-missing`, `:contract-mismatch`); the image must then pass
   `yin.vm.debruijn-code/image-defect`, Rule R included. An empty segment
   admits no code and needs neither."
  [vm segment contract]
  (when (seq segment)
    (vm/check-contract! vm/stack-contract contract)
    (when-let [defect (dcode/image-defect segment)]
      (throw (ex-info (str "Invalid stack image: " (:rule defect))
                      defect))))
  (install-image vm segment))


(defn create-vm
  "Build a fresh `DebruijnVM` over `segment` (a vector of instructions).

   Options: `:free-env`, `:store`, `:primitives`, `:modules` (all default to
   `{}`, matching the named VM's empty defaults except that `:primitives`
   defaults to `{}` here whereas `yin.vm/empty-state` populates the full
   primitive table; callers pass `yin.vm/primitives` when they want the
   standard registry), `:make-stream` (the host's stream constructor; no
   for every v2 VM), `:call-in`/`:call-out`/`:call-capacity` (the FFI pair,
   built by `yin.vm/empty-state` exactly as the semantic VM's is), and
   `:bridge` (host FFI handlers, attached by `yin.vm.ffi/attach`), and
   `:contract`, the segment's stamp, required when `segment` is non-empty
   (`load-image`). A `:free-env`, `:store`, or `:primitives` binding a
   reserved name is refused (Rule R).

   An empty segment starts halted with an empty program, as the semantic
   VM's `create-vm` does; `load-image` loads work into it.

   Always fresh: never resume or mutate a VM that has already run, per the
   design's D4 fixture restriction (the named VM's environment-leak defect
   is out of scope here and must not be reproduced by accident)."
  ([segment] (create-vm segment {}))
  ([segment opts]
   (let [base (vm/empty-state
                (assoc (select-keys opts [:modules :make-stream :call-in
                                          :call-out :call-capacity])
                       :primitives (or (:primitives opts) {})))]
     (-> (map->DebruijnVM
           {:segment [],
            :hash nil,
            :pc 0,
            :frames [],
            :free-env (vm/check-bindings! :env (or (:free-env opts) {})),
            :stack [],
            :continuation [],
            :store (merge (:store base)
                          (vm/check-bindings! :store (:store opts))),
            :blocked? false,
            :halted? true,
            :wait-set [],
            :ready-queue [],
            :parked {},
            :id-counter 0,
            :value nil,
            :make-stream (:make-stream base),
            :bridge nil,
            :primitives (:primitives base),
            :modules (or (:modules opts) {})})
         (load-image segment (:contract opts))
         (ffi/attach (:bridge opts))))))


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
;; The engine seam: register payload, restore, builders
;; =============================================================================

(defn- registers
  "The register payload of the continuation after the current instruction
   (design section 4.1, item 3): `pc'` is the advanced pc and `stack'` the
   operand stack with the instruction's operands popped. Frames and the
   return-frame continuation are the running ones. `:format` and `:hash`
   stamp the model and image identity that produced it. Free environment,
   store, primitives, and modules are not registers and stay on the state."
  [vm pc' stack']
  {:segment (:segment vm),
   :pc pc',
   :frames (:frames vm),
   :stack stack',
   :continuation (:continuation vm),
   :format format-tag,
   :hash (:hash vm)})


(defn- refuse-continuation!
  [base entry]
  (throw (ex-info "Cannot restore a continuation of another model or image"
                  {:rule :continuation-format,
                   :format (:format entry),
                   :hash (:hash entry),
                   :expected-format format-tag,
                   :expected-hash (:hash base)})))


(defn stack-restore
  "This machine's one restore function, `base entry val -> state`
   (`yin.vm.engine.md` section 2.1; design section 4.1, item 4). `base` is
   the state after the engine has done its part; `entry` is the wait, ready,
   or parked record; `val` is the value the parked instruction receives.

   It first refuses with a qualified `:continuation-format` outcome unless
   the entry's `:format` is `:yin.debruijn.code` and its `:hash` equals the
   loaded image's H: a continuation from the named VM, from the register
   kernel, or from another image is never interpreted here.

   Then the FFI two-step, on the entry keys the `yin.vm.ffi` convention
   owns, in the semantic VM's placement:

   - A `:request-sent` entry is a writer whose retained request has now been
     appended. It must not resume: its registers are re-parked as the
     call-out reader (`ffi/response-wait-entry`) and the machine stays
     blocked.
   - A `:call-id` entry is a response reader. The woken value is a response
     envelope, so `ffi/call-result` unwraps it and checks correlation, and
     the parked call leaves `:parked` rather than accumulating.

   Otherwise the registers are written from the entry and `val` is conjed
   onto the restored `:stack` -- the B3 obligation that every
   value-producing opcode lands on the stack, since there is no
   accumulator."
  [base entry val]
  (when-not (and (= format-tag (:format entry))
                 (= (:hash base) (:hash entry)))
    (refuse-continuation! base entry))
  (if (:request-sent entry)
    (-> base
        (update :wait-set (fnil conj [])
                (ffi/response-wait-entry entry (:call-id entry)))
        (assoc :value :yin/blocked
               :blocked? true
               :halted? false))
    (let [call-id (:call-id entry)
          base (if call-id (update base :parked dissoc call-id) base)
          val (if call-id (ffi/call-result val call-id) val)]
      (assoc base
             :segment (:segment entry)
             :pc (:pc entry)
             :frames (:frames entry)
             :continuation (:continuation entry)
             :stack (conj (vec (:stack entry)) val)
             :value val
             ;; Restored registers are active work: a driver resuming a
             ;; halted machine directly through `engine/resume-continuation`
             ;; gets a running one back, as the semantic VM's restore gives.
             :halted? false))))


(defn- park-entry-fns
  "Wait-entry builders for an instruction whose effect may block (design
   section 4.1, item 5): each closes over the post-instruction registers
   and returns them merged with `:reason` and the resource ids from the
   handler's result. Nothing else is attached: no handle, no closure, no
   restore function."
  [vm pc' stack']
  (let [regs (registers vm pc' stack')]
    {:stream/put (fn [_state _effect result]
                   (assoc regs
                          :reason :put
                          :stream-id (:stream-id result))),
     :stream/next (fn [_state _effect result]
                    (assoc regs
                           :reason :next
                           :cursor-ref (:cursor-ref result)
                           :stream-id (:stream-id result)))}))


(defn- run-effect
  "Dispatch one effect through the engine from the instruction at `(:pc vm)`
   with `stack'` its operand stack after popping the instruction's operands.
   On a value the machine continues at pc+1 with the value on the stack; on
   a park the engine has already placed the pure-data entry in the wait set
   and marked the machine blocked, and the registers mirror that entry."
  [vm effect stack']
  (let [pc' (inc (:pc vm))
        {:keys [state value blocked?]}
        (engine/handle-effect vm
                              effect
                              {:park-entry-fns (park-entry-fns vm pc' stack')})]
    (if blocked?
      (assoc state :pc pc' :stack stack')
      (assoc state :pc pc' :stack (conj stack' value)))))


(defn- ffi-call
  "`[:ffi-call op argc]`: park-and-call over the FFI pair (the two-step of
   `yin.vm.engine.md` section 4). The continuation after the call is parked,
   its id is the call's correlation id, and the request is appended to the
   call-in stream. An `ok` append waits on the call-out cursor for the
   correlated response; a `full` append waits as a writer retrying the
   identical request, and `stack-restore` turns that writer into the
   response reader when it wakes."
  [vm op argc]
  (let [{:keys [pc stack store]} vm
        total (count stack)
        args (subvec stack (- total argc))
        stack' (subvec stack 0 (- total argc))
        ;; The pair is checked before parking: an error raised after
        ;; park-continuation would strand a continuation in :parked and
        ;; consume an id counter.
        {:keys [call-in]} (ffi/require-call-pair! store op)
        regs (registers vm (inc pc) stack')
        parked (engine/park-continuation (assoc vm :pc (inc pc) :stack stack')
                                         regs)
        call-id (get-in parked [:value :id])
        request (apply2/request call-id op args)
        result (apply2/put-request! call-in request)
        blocked (fn [entry]
                  (-> parked
                      (update :wait-set (fnil conj []) entry)
                      (assoc :value :yin/blocked
                             :blocked? true
                             :halted? false)))]
    (case (:dao.stream/outcome result)
      :dao.stream/ok (blocked (ffi/response-wait-entry regs call-id))
      :dao.stream/full (blocked (assoc regs
                                       :request-sent true
                                       :call-id call-id
                                       :op op
                                       :reason :put
                                       :stream-id vm/call-in-stream-key
                                       :datom request))
      (throw (ex-info "FFI request could not be appended"
                      {:op op,
                       :outcome (or (:dao.stream/outcome result)
                                    (:dao.stream.apply/outcome result))})))))


;; =============================================================================
;; The step function
;; =============================================================================

(defn- step1
  "Execute exactly one instruction and return the resulting VM. Assumes the
   VM's continuation is active; callers (`step`, `run`) check that first."
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
      ;; split: every value-producing case already conjes its result
      ;; straight onto `:stack`. By the time control reaches a `:push`,
      ;; the value it would push is already there -- so here it is a no-op
      ;; that only advances `pc`. Every value-producing opcode below
      ;; (:stream-*, :gensym, :current-continuation, :resume, and the
      ;; restore after :ffi-call) honours this, or a `:push` immediately
      ;; after it would silently drop the value -- there is no `val`
      ;; register to recover it from.
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

          ;; A primitive host function, resolved by :load-free. The same
          ;; path the named engine's `apply-call` uses for a `fn?` callee:
          ;; a plain result lands on the stack; an effect descriptor (a
          ;; `require`, a `stream` module call) is dispatched
          ;; through the engine and may park with the continuation after
          ;; the call site.
          (fn? f)
          (let [result (apply f args)]
            (if (module/effect? result)
              (run-effect vm result stack')
              (assoc vm :pc (inc pc) :stack (conj stack' result))))

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
          (assoc vm :halted? true, :stack [val], :value val)))

      :jump
      (assoc vm :pc (nth inst 1))

      :branch-false
      (let [val (peek stack), stack' (pop stack)]
        (if val
          (assoc vm :pc (inc pc) :stack stack')
          (assoc vm :pc (nth inst 1) :stack stack')))

      :halt
      (assoc vm :halted? true, :value (peek stack))

      :store-get
      (let [key (nth inst 1)]
        (assoc vm :pc (inc pc) :stack (conj stack (get store key))))

      :store-put
      (let [key (nth inst 1), value (nth inst 2)]
        (assoc vm
               :pc (inc pc)
               :stack (conj stack value)
               :store (engine/store-put store key value)))

      ;; :define -- the definition transition: pop the value, write it
      ;; under the literal name, and leave it as the expression's value.
      ;; The operator is never resolved (Rule R).
      :define
      (let [value (peek stack)]
        (assoc vm
               :pc (inc pc)
               :stack (conj (pop stack) value)
               :store (engine/store-put store (nth inst 1) value)))

      ;; :gensym -- a fresh id; the engine's counter advances
      :gensym
      (let [[id vm'] (engine/gensym vm (nth inst 1))]
        (assoc vm' :pc (inc pc) :stack (conj stack id)))

      ;; :stream-make -- the composition's :make-stream, through the engine
      :stream-make
      (run-effect vm
                  {:effect :stream/make,
                   :capacity (or (nth inst 1) vm/default-stream-capacity)}
                  stack)

      ;; :stream-put -- the value on top, its target stream ref beneath
      ;; (`lower-stack` emits target, push, value, stream-put)
      :stream-put
      (let [val (peek stack), stack' (pop stack), target (peek stack')]
        (run-effect vm
                    {:effect :stream/put, :stream target, :val val}
                    (pop stack')))

      ;; :stream-cursor -- the source stream ref on top
      :stream-cursor
      (run-effect vm {:effect :stream/cursor, :stream (peek stack)} (pop stack))

      ;; :stream-next -- the cursor ref on top; may park as a reader
      :stream-next
      (run-effect vm {:effect :stream/next, :cursor (peek stack)} (pop stack))

      ;; :stream-close -- the source stream ref on top; yields nil
      :stream-close
      (run-effect vm {:effect :stream/close, :stream (peek stack)} (pop stack))

      ;; :current-continuation -- the continuation after this instruction,
      ;; as a value: the register payload under the shared tag the B0
      ;; normalizer compares by type
      :current-continuation
      (assoc vm
             :pc (inc pc)
             :stack (conj stack
                          (merge {:type :reified-continuation}
                                 (registers vm (inc pc) stack))))

      ;; :park -- the engine records the payload under a fresh parked id,
      ;; writes the record into :value, and halts the machine
      :park
      (engine/park-continuation (assoc vm :pc (inc pc))
                                (registers vm (inc pc) stack))

      ;; :resume -- the value on top is delivered to the parked
      ;; continuation named by the operand, through this machine's restore
      :resume
      (let [val (peek stack)]
        (engine/resume-continuation (assoc vm :stack (pop stack))
                                    (nth inst 1)
                                    val
                                    stack-restore))

      :ffi-call
      (ffi-call vm (nth inst 1) (nth inst 2))

      (throw (ex-info (str "Unknown opcode in segment: " op)
                      {:rule :unknown-opcode, :op op, :pc pc})))))


;; =============================================================================
;; Scheduling
;; =============================================================================

(defn- run-scheduler
  "The raw runner: already-loaded work only, through the shared scheduler
   loop with this machine's step function and its restore bound into the
   engine's run-queue resumption. `ffi/maybe-run` wraps this for bridge
   dispatch."
  [vm]
  (engine/run-loop vm
                   engine/active-continuation?
                   step1
                   #(engine/resume-from-run-queue % stack-restore)))


;; =============================================================================
;; IVM / IVMState
;; =============================================================================
;; No new protocol methods (section 4, point 7). `environment` is not
;; implemented at all (point 8): the design defers it until a
;; frame-to-named lift exists, and returning the raw positional frame
;; vector from it would be wrong once the method does exist.

(extend-type DebruijnVM
  vm/IVM
  (step [this]
    (cond (engine/active-continuation? this) (step1 this)
          ;; Between continuations: one scheduler round, as the semantic
          ;; VM's `step` runs when it has no control left.
          (or (:blocked? this) (seq (:ready-queue this)))
          (engine/scheduler-round this stack-restore)
          :else this))
  (run [this] (ffi/maybe-run this run-scheduler))
  (eval [_ ast]
    (throw (ex-info
             "The de Bruijn kernel executes raw instruction vectors, not AST: lower and adapt it first (yin.vm.debruijn-linearize/adapt), then load and run the resulting image"
             {:ast ast})))
  (reset [this]
    (assoc this
           :pc 0, :frames [], :stack [], :continuation []
           :halted? (empty? (:segment this)), :blocked? false, :value nil))
  (halted? [this] (engine/halted-with-empty-queue? this))
  (blocked? [this] (engine/vm-blocked? this))
  (value [this] (engine/vm-value this))

  vm/IVMState
  (control [this] {:pc (:pc this)})
  (environment [_this]
    (throw (ex-info
             "environment is not implemented: the design defers it until a frame-to-named lift exists"
             {:rule :not-yet-supported})))
  (store [this] (:store this))
  (continuation [this] (:continuation this)))

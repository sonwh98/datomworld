(ns yin.vm.debruijn.register
  "R4 (docs/design/yin.vm.debruijn.register.md S5, S6 R4): the register
   execution kernel that interprets positional register images
   `{:bodies [...], :instructions [...]}` under contract version 4
   (the \"r2\" contract).

   The register kernel is a sibling to `yin.vm.debruijn.stack` (B3/B4) and
   `yin.vm.semantic`. It preserves B3's frame direction, closure capture,
   nil-fill and extra-argument rules, and B0 normalization. It executes
   only validator-approved images.

   State: `{:segment :hash :images :pc :frames :free-env :registers
            :continuation :store :blocked? :halted? :wait-set :ready-queue
            :parked :id-counter :value :make-stream :bridge :primitives
            :modules}`.

   Continuation transport: register continuations and stack continuations
   are not interchangeable (section 5.1). Payloads carry `:format
   :yin.debruijn.register`, `:hash R` of the code space at the time, and
   `:image`, the offset-table row their pc falls in (yin.vm.linker.md
   section 7.3, r6). A foreign format or an `:image` the table does not
   hold is refused with qualified `:continuation-format` diagnostics.
   Resume values must be plain data, refusing host exceptions with
   `:resume-value`.

   The kernel implements all 23 opcodes, Rule R's `:define` included,
   across the pure-program tier and the
   effects tier, connecting to `yin.vm.engine` via `register-restore` and
   per-site wait-entry builders."
  (:require [dao.stream.apply :as apply2]
            [yin.vm :as vm]
            [yin.vm.debruijn-register-code :as rcode]
            [yin.vm.debruijn-register-effects :as effects]
            [yin.vm.engine :as engine]
            [yin.vm.ffi :as ffi]
            [yin.vm.module :as module]))


;; =============================================================================
;; State
;; =============================================================================

(defrecord DebruijnRegisterVM
  [segment      ; {:bodies [...], :instructions [...]}
   hash         ; R of segment (yin.vm.debruijn-register-code/register-hash)
   images       ; offset table: [[identity offset length] ...], base first
   pc           ; program counter into :instructions
   frames       ; positional lexical frame stack, outermost first
   free-env     ; initial free-name environment map
   registers    ; persistent vector for current body's register file
   continuation ; vector of return frames (innermost last)
   store        ; heap map
   blocked?     ; engine: boolean
   halted?      ; engine: boolean
   wait-set     ; engine: vector of wait entries
   ready-queue  ; engine: vector of woken entries
   parked       ; engine: map of parked continuations
   id-counter   ; engine: counter for gensym, stream, and park ids
   value        ; engine: last computed value
   make-stream  ; host stream constructor or nil
   bridge       ; host FFI bridge or nil
   primitives   ; primitive registry for :load-free
   modules])    ; module registry for :load-free and effect dispatch


(def format-tag
  "The model tag every register payload carries as `:format`."
  effects/format-tag)


;; =============================================================================
;; Body & frame addressing
;; =============================================================================

(defn- body-of-pc
  "Return the declared body map covering instruction `pc` in `segment`."
  [{:keys [bodies]} pc]
  (some (fn [{:keys [start end] :as body}]
          (when (and (<= start pc) (<= pc end)) body))
        bodies))


(defn- frame-value
  "Frame 0 for `:load-bound` is the INNERMOST frame, read from the END of
   `:frames`. `frames` is outermost-first, so depth `d` is
   `(count frames) - 1 - d`."
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
  "Positional parameter binding: missing arguments are nil-filled,
   extra arguments dropped."
  [arity args]
  (vec (take arity (concat args (repeat nil)))))


;; =============================================================================
;; Image loading & VM construction
;; =============================================================================

(defn- empty-segment?
  [segment]
  (or (nil? segment)
      (empty? segment)
      (and (map? segment) (empty? (:instructions segment)))))


(def ^:private empty-image {:bodies [], :instructions []})


(defn- install-image
  "Install `segment` as the one image. A non-empty image must pass the
   register validator. The offset table restarts at the base row
   `[R 0 n]`."
  [vm segment]
  (let [empty-seg? (empty-segment? segment)]
    (if empty-seg?
      (assoc vm
             :segment empty-image
             :hash nil
             :images [[(rcode/register-hash empty-image) 0 0]]
             :pc 0
             :frames []
             :registers []
             :continuation []
             :halted? true
             :blocked? false
             :value nil)
      (if-let [defect (rcode/register-image-defect segment)]
        (throw (ex-info (str "Invalid register image: " (:rule defect))
                        defect))
        (let [body0 (first (:bodies segment))
              reg-count (or (:registers body0) 0)
              r (rcode/register-hash segment)]
          (assoc vm
                 :segment segment
                 :hash r
                 :images [[r 0 (count (:instructions segment))]]
                 :pc 0
                 :frames []
                 :registers (vec (repeat reg-count nil))
                 :continuation []
                 :halted? false
                 :blocked? false
                 :value nil))))))


(defn load-image
  "Load `segment` (a register image `{:bodies [...], :instructions [...]}`)
   into `vm` as its one image: R is computed, the registers are reset to
   pc 0 with empty frames and continuation, and the machine is running
   unless the segment is empty. The offset table restarts at `[R 0 n]`, so
   a continuation parked under an earlier image names no row of it and
   `register-restore` refuses it.

   `contract` is required and compared with `vm/register-contract` first
   (`:contract-missing`, `:contract-mismatch`); a non-empty image must then
   pass the register validator, Rule R included. An empty segment admits
   no code and needs neither."
  [vm segment contract]
  (when-not (empty-segment? segment)
    (vm/check-contract! vm/register-contract contract))
  (install-image vm segment))


(defn- relocate
  "Shift every `:pc`-kind operand of `inst` by `offset`."
  [offset inst]
  (reduce (fn [inst [i [_ kind]]]
            (if (= :pc kind) (update inst (inc i) + offset) inst))
          inst
          (map-indexed vector (get rcode/opcode-table (nth inst 0)))))


(defn attach-image
  "Extend the code space of `vm` with `image`, non-destructively
   (yin.vm.linker.md section 7.3, act 3): `image` is admitted alone as
   `load-image` admits it, relocated by the held instruction count, and
   appended, `:bodies` shifted likewise; one `[identity offset length]`
   row, identity R of `image`, is appended to the offset table; `:hash`
   becomes R of the concatenation. `:pc`, `:registers`, `:frames`,
   `:continuation`, and every other register are unchanged: appending
   moves no instruction already held. An image whose identity is already
   a row is not attached twice."
  [vm image contract]
  (when-not (empty-segment? image)
    (vm/check-contract! vm/register-contract contract)
    (when-let [defect (rcode/register-image-defect image)]
      (throw (ex-info (str "Invalid register image: " (:rule defect))
                      defect))))
  (let [image (if (empty-segment? image) empty-image image)
        ident (rcode/register-hash image)]
    (if (some #(= ident (nth % 0)) (:images vm))
      vm
      (let [held (:segment vm)
            offset (count (:instructions held))
            shift-body #(-> % (update :start + offset) (update :end + offset))
            combined {:bodies (into (:bodies held)
                                    (map shift-body)
                                    (:bodies image)),
                      :instructions (into (:instructions held)
                                          (map #(relocate offset %))
                                          (:instructions image))}]
        (assoc vm
               :segment combined
               :hash (rcode/register-hash combined)
               :images (conj (:images vm)
                             [ident offset
                              (count (:instructions image))]))))))


(defn image-pc
  "Lift an absolute `pc` of `vm` to `[identity rel-pc]` by its offset
   table, or nil when no row holds it."
  [vm pc]
  (when-let [[ident off] (effects/image-row (:images vm) pc)]
    [ident (- pc off)]))


(defn absolute-pc
  "Lower `[identity rel-pc]` to an absolute pc of `vm` by its offset
   table, or nil when the identity is no row of it."
  [vm [ident rel-pc]]
  (some (fn [[id off]] (when (= id ident) (+ off rel-pc)))
        (:images vm)))


(defn create-vm
  "Build a fresh `DebruijnRegisterVM` over `segment`.
   Options: `:free-env`, `:store`, `:primitives`, `:modules`, `:make-stream`,
   `:call-in`/`:call-out`/`:call-capacity`, `:bridge`, and `:contract`, the
   segment's stamp, required when `segment` is non-empty (`load-image`). A
   `:free-env`, `:store`, or `:primitives` binding a reserved name is
   refused (Rule R)."
  ([segment] (create-vm segment {}))
  ([segment opts]
   (let [base (vm/empty-state
                (assoc (select-keys opts [:modules :make-stream :call-in
                                          :call-out :call-capacity])
                       :primitives (or (:primitives opts) {})))]
     (-> (map->DebruijnRegisterVM
           {:segment {:bodies [], :instructions []},
            :hash nil,
            :images [],
            :pc 0,
            :frames [],
            :free-env (vm/check-bindings! :env (or (:free-env opts) {})),
            :registers [],
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
;; Return transitions & restore
;; =============================================================================

(defn- return-transition
  "Unified return transition: restores caller frame from continuation if
   pending, otherwise halts the VM with `val`. Only pc, frames, registers,
   and continuation are restored (yin.vm.linker.md section 7.3, r7): a
   frame carries no code space, so an image attached while the call was
   in flight stays attached, and the absolute `:return-pc` still names
   the caller's instruction in the grown code space."
  [vm val]
  (let [continuation (:continuation vm)]
    (if (seq continuation)
      (let [frame (peek continuation)
            return-pc (:return-pc frame)
            body (body-of-pc (:segment vm) return-pc)
            reg-count (:registers body)
            dest (:dest frame)
            restored-regs (reduce (fn [acc [r v]] (assoc acc r v))
                                  (vec (repeat reg-count nil))
                                  (:regs frame))
            final-regs (if (some? dest)
                         (assoc restored-regs dest val)
                         restored-regs)]
        (assoc vm
               :pc return-pc
               :frames (:frames frame)
               :registers final-regs
               :continuation (pop continuation)
               :value val
               :halted? false))
      (assoc vm :halted? true, :value val))))


(defn- refuse-continuation!
  [base entry]
  (throw (ex-info "Cannot restore a continuation of another model or image"
                  {:rule :continuation-format,
                   :format (:format entry),
                   :hash (:hash entry),
                   :image (:image entry),
                   :expected-format format-tag,
                   :expected-hash (:hash base),
                   :images (:images base)})))


(defn register-restore
  "The register VM's restore function, `base entry val -> state`
   (design section 5.2.3). Validates format identity and that the entry's
   `:image` is a row of the offset table (yin.vm.linker.md section 7.3,
   r6; `:hash` is never a restore key, since it changes at every
   `attach-image`), checks that the payload is defect-free via
   effects/continuation-defect, checks that `val` is plain data, handles
   the FFI two-step, and delivers `val` via `:write-result` or
   `:return-result`. The entry restores registers only and never assigns
   `:segment`: the code space is kernel state."
  [base entry val]
  (when-not (and (= format-tag (:format entry))
                 (some #(= (:image entry) (nth % 0)) (:images base)))
    (refuse-continuation! base entry))
  (when-let [defect (effects/continuation-defect entry)]
    (throw (ex-info "Corrupt or tampered continuation payload"
                    defect)))
  (when-not (vm/plain-data? val)
    (throw (ex-info "Resume value must be plain data"
                    {:rule :resume-value, :val val})))
  (if (:request-sent entry)
    (-> base
        (update :wait-set (fnil conj [])
                (ffi/response-wait-entry entry (:call-id entry)))
        (assoc :value :yin/blocked
               :blocked? true
               :halted? false))
    (let [call-id (:call-id entry)
          base (if call-id (update base :parked dissoc call-id) base)
          val (if call-id (ffi/call-result val call-id) val)
          resume-mode (:resume-mode entry)]
      (if (= :return-result resume-mode)
        (return-transition (assoc base
                                  :pc (:pc entry)
                                  :frames (:frames entry)
                                  :continuation (:continuation entry)
                                  :halted? false)
                           val)
        (let [pc (:pc entry)
              body (body-of-pc (:segment base) pc)
              reg-count (:registers body)
              dest (:dest entry)
              restored-regs
              (reduce (fn [acc [r v]] (assoc acc r v))
                      (vec (repeat reg-count nil))
                      (:regs entry))
              final-regs (if (some? dest)
                           (assoc restored-regs dest val)
                           restored-regs)]
          (assoc base
                 :pc pc
                 :frames (:frames entry)
                 :registers final-regs
                 :continuation (:continuation entry)
                 :value val
                 :halted? false))))))


;; =============================================================================
;; Effects & FFI dispatch
;; =============================================================================

(defn- park-entry-fns
  [payload]
  {:stream/put (fn [_state effect result]
                 (assoc payload
                        :reason :put
                        :stream-id (:stream-id result)
                        :datom (:val effect))),
   :stream/next (fn [_state _effect result]
                  (assoc payload
                         :reason :next
                         :cursor-ref (:cursor-ref result)
                         :stream-id (:stream-id result)))})


(defn- run-effect
  [vm effect inst]
  (let [opts (when (rcode/boundary-opcodes (first inst))
               {:park-entry-fns
                (park-entry-fns (effects/continuation-payload vm inst))})
        pc' (inc (:pc vm))
        {:keys [state value blocked?]}
        (engine/handle-effect vm effect opts)]
    (if blocked?
      (assoc state :pc pc')
      (let [rd (nth inst 1)]
        (assoc state
               :pc pc'
               :registers (assoc (:registers state) rd value))))))


(defn- run-call-effect
  [vm effect inst]
  (let [payload (effects/continuation-payload vm inst)
        tail? (true? (nth inst 4))
        pc' (inc (:pc vm))
        {:keys [state value blocked?]}
        (engine/handle-effect vm
                              effect
                              {:park-entry-fns (park-entry-fns payload)})]
    (if blocked?
      (assoc state :pc pc')
      (if tail?
        (return-transition state value)
        (let [rd (nth inst 1)]
          (assoc state
                 :pc pc'
                 :registers (assoc (:registers state) rd value)))))))


(defn- ffi-call
  [vm inst]
  (let [{:keys [pc registers store]} vm
        op (nth inst 2)
        arg-regs (nth inst 3)
        args (mapv #(nth registers %) arg-regs)
        {:keys [call-in]} (ffi/require-call-pair! store op)
        payload (effects/continuation-payload vm inst)
        parked (engine/park-continuation (assoc vm :pc (inc pc)) payload)
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
      :dao.stream/ok (blocked (ffi/response-wait-entry payload call-id))
      :dao.stream/full (blocked (assoc payload
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
;; Instruction step function
;; =============================================================================

(defn- step1
  "Execute exactly one instruction and return the resulting VM."
  [vm]
  (let [{:keys [segment pc frames free-env registers continuation
                store primitives modules]} vm
        inst (nth (:instructions segment) pc)
        op (nth inst 0)]
    (case op
      :const
      (assoc vm
             :pc (inc pc)
             :registers (assoc registers (nth inst 1) (nth inst 2)))

      :load-bound
      (let [val (frame-value frames (nth inst 2) (nth inst 3))]
        (assoc vm
               :pc (inc pc)
               :registers (assoc registers (nth inst 1) val)))

      :load-free
      (let [val (engine/resolve-var free-env store primitives modules
                                    (nth inst 2))]
        (assoc vm
               :pc (inc pc)
               :registers (assoc registers (nth inst 1) val)))

      :closure
      (let [clos {:type :closure,
                  :arity (nth inst 2),
                  :body-pc (nth inst 3),
                  :frames frames}]
        (assoc vm
               :pc (inc pc)
               :registers (assoc registers (nth inst 1) clos)))

      :move
      (let [val (nth registers (nth inst 2))]
        (assoc vm
               :pc (inc pc)
               :registers (assoc registers (nth inst 1) val)))

      :call
      (let [rd (nth inst 1)
            fn-reg (nth inst 2)
            arg-regs (nth inst 3)
            tail? (nth inst 4)
            live (nth inst 5)
            f (nth registers fn-reg)
            args (mapv #(nth registers %) arg-regs)]
        (cond
          (and (map? f) (= :closure (:type f)))
          (let [callee-pc (:body-pc f)
                callee-body (body-of-pc segment callee-pc)
                callee-arity (:arity f)
                callee-reg-count (:registers callee-body)
                locals (bind-positional callee-arity args)
                body-frames (conj (:frames f) locals)
                callee-regs (into locals
                                  (repeat (- callee-reg-count callee-arity)
                                          nil))
                ;; The frame carries no code space (r7): its absolute
                ;; :return-pc survives every later append.
                continuation'
                (if tail?
                  continuation
                  (conj continuation
                        {:site-pc pc,
                         :return-pc (inc pc),
                         :frames frames,
                         :regs (mapv (fn [r] [r (nth registers r)]) live),
                         :live live,
                         :dest rd}))]
            (assoc vm
                   :pc callee-pc
                   :frames body-frames
                   :registers callee-regs
                   :continuation continuation'))

          (fn? f)
          (let [result (apply f args)]
            (if (module/effect? result)
              (run-call-effect vm result inst)
              (if tail?
                (return-transition vm result)
                (assoc vm
                       :pc (inc pc)
                       :registers (assoc registers rd result)))))

          :else
          (throw (ex-info "Cannot apply non-function" {:fn f}))))

      :branch-false
      (let [c (nth registers (nth inst 1))]
        (assoc vm :pc (if c (inc pc) (nth inst 2))))

      :jump
      (assoc vm :pc (nth inst 1))

      :return
      (return-transition vm (nth registers (nth inst 1)))

      :halt
      (assoc vm :halted? true, :value (nth registers (nth inst 1)))

      :store-get
      (assoc vm
             :pc (inc pc)
             :registers (assoc registers (nth inst 1) (get store (nth inst 2))))

      :store-put
      (let [key (nth inst 2)
            val (nth inst 3)]
        (assoc vm
               :pc (inc pc)
               :registers (assoc registers (nth inst 1) val)
               :store (engine/store-put store key val)))

      ;; :define -- the definition transition: register `rs` is written
      ;; under the literal name and into `rd`. The operator is never
      ;; resolved (Rule R).
      :define
      (let [val (nth registers (nth inst 3))]
        (assoc vm
               :pc (inc pc)
               :registers (assoc registers (nth inst 1) val)
               :store (engine/store-put store (nth inst 2) val)))

      :gensym
      (let [[id vm'] (engine/gensym vm (nth inst 2))]
        (assoc vm'
               :pc (inc pc)
               :registers (assoc (:registers vm') (nth inst 1) id)))

      :stream-make
      (let [cap (or (nth inst 2) vm/default-stream-capacity)]
        (run-effect vm {:effect :stream/make, :capacity cap} inst))

      :stream-put
      (let [sr (nth registers (nth inst 2))
            vr (nth registers (nth inst 3))]
        (run-effect vm {:effect :stream/put, :stream sr, :val vr} inst))

      :stream-cursor
      (let [sr (nth registers (nth inst 2))]
        (run-effect vm {:effect :stream/cursor, :stream sr} inst))

      :stream-next
      (let [cr (nth registers (nth inst 2))]
        (run-effect vm {:effect :stream/next, :cursor cr} inst))

      :stream-close
      (let [sr (nth registers (nth inst 2))]
        (run-effect vm {:effect :stream/close, :stream sr} inst))

      :current-continuation
      (let [payload (effects/continuation-payload vm inst)
            reified (merge {:type :reified-continuation} payload)]
        (assoc vm
               :pc (inc pc)
               :registers (assoc registers (nth inst 1) reified)))

      :park
      (let [payload (effects/continuation-payload vm inst)]
        (engine/park-continuation (assoc vm :pc (inc pc)) payload))

      :resume
      (let [val (nth registers (nth inst 2))]
        (engine/resume-continuation vm (nth inst 1) val register-restore))

      :ffi-call
      (ffi-call vm inst)

      (throw (ex-info (str "Unknown opcode in segment: " op)
                      {:rule :unknown-opcode, :op op, :pc pc})))))


;; =============================================================================
;; Scheduling & Protocol Implementations
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
                   #(engine/resume-from-run-queue % register-restore)))


(extend-type DebruijnRegisterVM
  vm/IVM
  (step [this]
    (cond (engine/active-continuation? this) (step1 this)
          (or (:blocked? this) (seq (:ready-queue this)))
          (engine/scheduler-round this register-restore)
          :else this))
  (run [this] (ffi/maybe-run this run-scheduler))
  (eval [_ ast]
    (throw
      (ex-info
        (str "The de Bruijn register kernel executes raw instruction vectors, "
             "not AST: lower and adapt it first "
             "(yin.vm.debruijn-register-compile/adapt), then load and run the "
             "resulting image")
        {:ast ast})))
  ;; A reset is a fresh run over the held code space, not a load: the
  ;; offset table is kernel state only the loaders and `attach-image`
  ;; write (yin.vm.linker.md section 7.3, r7), so it survives.
  (reset [this]
    (assoc (install-image this (:segment this)) :images (:images this)))
  (halted? [this] (engine/halted-with-empty-queue? this))
  (blocked? [this] (engine/vm-blocked? this))
  (value [this] (engine/vm-value this))

  vm/IVMState
  (control [this] {:pc (:pc this)})
  (environment [_this]
    (throw
      (ex-info
        (str "environment is not implemented: the design defers it until a "
             "frame-to-named lift exists")
        {:rule :not-yet-supported})))
  (store [this] (:store this))
  (continuation [this] (:continuation this)))

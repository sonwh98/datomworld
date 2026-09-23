(ns yin.vm.semantic
  "Linear CESK interpreter for executable datoms on DaoStream v2
   (`docs/design/yin.vm.semantic.md`).

   Code arrives as one `:yin.code/*` segment batch per program value. The
   loader (`load-image`, `vm-load-program`) checks §2.6 well-formedness,
   decodes the batch into an image — one vector indexed by pc of
   `[opcode & operands]` with every ref resolved to a pc integer — and
   stores it under `:code {segment-id image}`. Mnemonics in the datoms stay
   semantic and integers in the image stay mechanical (§2.4): the loader maps
   `:const` onto `:literal` and a `:call` carrying `:yin.code/tail?` onto
   `:tailcall`, and the dispatch below switches on the integers.

   The hot loop keeps the five registers — segment, pc, accumulator, operand
   stack, continuation — in `loop` locals and touches the record only at
   effect points and exits (§4.3). K is a vector of frames, innermost last:
   `{:type :return :segment :pc :env :stack-base}` for calls, and the wait
   entries the engine polls carry the same `{segment pc env stack k}` shape
   (§3.5). Park writes that map into `:parked`; resume reads it back. Nothing
   in either is a host object except values the program itself put in `env`
   or on the stack. A wait or ready entry is those registers plus `:reason`
   and resource ids — never a resolved stream handle and never a resume
   closure: `engine/check-wait-set` resolves handles from the store on every
   poll and the scheduler restores a woken entry explicitly, so the wait set
   of a blocked machine survives an EDN round-trip. A segment id is stable:
   loading different code under an id that continuations already name is a
   load error; an identical reload is accepted.

   `step` is this loop with a fuel of one instruction, so single-stepping and
   full runs execute one transition code path. AST evaluation is not here:
   lowering (`yin.vm.linearize`, Phase 2) is composition-supplied at the
   observer boundary, so `eval` resumes loaded work and refuses an AST."
  (:require [dao.jing :as jing]
            [dao.stream.apply :as apply2]
            [yin.vm :as vm]
            [yin.vm.code :as code]
            [yin.vm.engine :as engine]
            [yin.vm.ffi :as ffi]
            [yin.vm.module :as module]
            [yin.vm.telemetry :as telemetry]))


;; =============================================================================
;; SemanticVM Record
;; =============================================================================

(defrecord SemanticVM
  [blocked?       ; boolean, true if blocked
   bridge         ; explicit host-side FFI bridge state
   halted?        ; boolean, true when active continuation has completed
   k              ; continuation, a vector of frames (innermost last) or nil
   program        ; segment id of the last loaded batch
   control        ; {:segment id :pc n} or nil
   env            ; persistent lexical scope map
   stack          ; operand stack, a vector (part of control, §4.1)
   id-counter     ; integer counter for unique IDs
   parked         ; parked continuations map
   primitives     ; primitive operations map
   primitive-profiles ; portable primitive profile projection
   primitive-canonical-names ; declared aliases for identity reverse lookup
   modules        ; module registry value
   make-stream    ; host-supplied stream constructor, or nil
   call-capacity  ; declared capacity of the FFI pair
   ready-queue    ; vector of runnable continuations
   store          ; heap memory map
   value          ; last computed value
   wait-set       ; vector of continuations waiting on a transport
   telemetry      ; telemetry config map ({:stream … :vm-id …}) or nil
   telemetry-step ; telemetry snapshot counter
   telemetry-t    ; telemetry transaction counter
   telemetry-eid  ; telemetry entity-id seed, floored at datom/first-user-id
   vm-model       ; telemetry model keyword
   vm-id          ; telemetry instance id, minted by telemetry/install
   code           ; {segment-id image}; built once per segment, never written
   code-aliases]) ; {address segment-id}; additive, one address one id (UCF §7.3.4)


(declare semantic-restore scheduler-round)


;; =============================================================================
;; Register materialization
;; =============================================================================

(defn- put-registers
  "Write the five registers back into the record. This is the linear machine's
   cesk-return: the one seam where the running configuration becomes an
   observable value, and therefore where per-step trace emission (§3.6) will
   land when telemetry is designed. A nil segment means the machine has no
   control left, which is the halted state.

   An empty continuation is stored as nil rather than [] so that
   `engine/ready-for-ingress?`, which asks `(nil? (:k vm))`, sees a machine
   between evaluations."
  [vm seg pc val St E K]
  (assoc vm
         :control (when seg {:segment seg, :pc pc})
         :value val
         :stack (or St [])
         :env E
         :k (if (seq K) K nil)
         :halted? (and (not (:blocked? vm)) (nil? seg))))


(defn- response-wait-entry
  "The polling wait entry for a sent call awaiting its correlated response:
   the machine registers {segment pc env stack k} plus the call-out reader
   fields, in the shape `ffi/call-response-wait-entry` gives the walker."
  [seg pc env stack k call-id]
  {:segment seg, :pc pc, :env env, :stack stack, :k k,
   :call-id call-id,
   :reason :next,
   :cursor-ref {:type :cursor-ref, :id vm/call-out-cursor-key},
   :stream-id vm/call-out-stream-key})


(defn semantic-restore
  "Restore a wait-set or ready-queue entry into machine registers.

   Two entry shapes carry more than registers:

   - A `:request-sent` entry is a writer whose retained FFI request has now
     been appended. The call starts waiting for its correlated response,
     exactly as an immediately-sent one does, so the entry is replaced by its
     response reader and the machine stays blocked.
   - A `:call-id` entry is a response reader. The woken value is a response
     envelope, so `ffi/call-result` unwraps it, checks correlation, and the
     parked call leaves `:parked` rather than accumulating."
  ([base entry] (semantic-restore base entry (:value entry)))
  ([base entry val]
   (if (:request-sent entry)
     (assoc base
            :wait-set (conj (vec (or (:wait-set base) []))
                            (response-wait-entry (:segment entry)
                                                 (:pc entry)
                                                 (:env entry)
                                                 (:stack entry)
                                                 (:k entry)
                                                 (:call-id entry)))
            :control nil
            :k nil
            :value :yin/blocked
            :blocked? true
            :halted? false)
     (let [call-id (:call-id entry)
           base (if call-id (update base :parked dissoc call-id) base)
           val (if call-id (ffi/call-result val call-id) val)]
       (put-registers base
                      (:segment entry)
                      (:pc entry)
                      val
                      (:stack entry)
                      (:env entry)
                      (:k entry))))))


(defn- call-park-entries
  "Wait-entry builders for an instruction whose effect may block. The
   continuation of an effect is the machine state after the instruction:
   {segment, pc+1, env, stack, k} plus the transport fields the engine
   fills in (§3.5). `St` is the operand stack with the instruction's operands
   already popped."
  [seg pc E St K]
  {:stream/put (fn [_state _effect result]
                 {:segment seg, :pc (inc pc), :env E, :stack St, :k K,
                  :reason :put,
                  :stream-id (:stream-id result)})
   :stream/next (fn [_state _effect result]
                  {:segment seg, :pc (inc pc), :env E, :stack St, :k K,
                   :reason :next,
                   :cursor-ref (:cursor-ref result),
                   :stream-id (:stream-id result)})})


(defn- run-effect
  "Materialize the registers, dispatch one effect through the engine, and
   normalize the answer for the loop: `{:continue [state value]}` to go on at
   pc+1 with `value` in the accumulator, or `{:stop vm}` when the effect
   parked and the machine is blocked.

   No `:restore-fn` crosses this boundary. A parked entry is pure data and
   the scheduler below resumes it explicitly, so nothing live is attached to
   the wait set (§1.1)."
  [vm seg pc val St E K effect park-entry-fns]
  (let [{:keys [state value blocked?]}
        (engine/handle-effect (put-registers vm seg pc val St E K)
                              effect
                              (when park-entry-fns
                                {:park-entry-fns park-entry-fns}))]
    (if blocked?
      {:stop (assoc state :control nil :k nil)}
      {:continue [state value]})))


(defn- apply-call
  "The §4.2 call transition, with the arguments already isolated from the
   operand stack. Returns `{:goto [seg pc val St E K vm image]}` to continue,
   `{:stop vm}` when the call blocked, or throws for a non-function.

   A tail call grows neither K nor St: the frame the caller would have pushed
   is the frame its own caller gave it. A primitive that yields an effect
   parks with the continuation after the call site."
  [code seg pc val St E K vm tail? f args]
  (cond
    (= :closure (:type f))
    (let [E' (merge (:env f) (engine/bind-params (:params f) args))
          frame {:type :return, :segment seg, :pc (inc pc), :env E,
                 :stack-base (count St)}
          seg' (:segment f)]
      {:goto [seg' (:entry f) val St E' (if tail? K (conj K frame))
              vm (get code seg')]})
    (fn? f)
    (let [result (apply f args)]
      (if (module/effect? result)
        (let [{:keys [state value blocked?]}
              (engine/handle-effect
                (put-registers vm seg pc val St E K)
                result
                {:park-entry-fns (call-park-entries seg pc E St K)})]
          (if blocked?
            {:stop (assoc state :control nil :k nil)}
            {:goto [seg (inc pc) value St E K state (get code seg)]}))
        {:goto [seg (inc pc) result St E K vm (get code seg)]}))
    :else (throw (ex-info "Cannot apply non-function" {:fn f}))))


;; =============================================================================
;; The hot loop
;; =============================================================================

(defn- vm-hot
  "Run loaded work with the registers in loop locals.

   `fuel`, when non-nil, is a number of instructions to execute before
   materializing: `step` supplies 1 and `run` supplies nil. The loop exits by
   returning a record when the machine halts, parks, blocks on an effect, or
   runs out of fuel; every non-exit transition is one `case` arm and one
   operation on locals (§4.3). A machine with no control and no continuation
   is between evaluations, so one scheduler round is run instead."
  [vm fuel]
  (if (and (nil? (:control vm)) (nil? (:k vm)))
    (scheduler-round vm)
    (let [code (:code vm)
          {:keys [segment pc]} (:control vm)]
      (loop [seg segment
             pc pc
             val (:value vm)
             St (or (:stack vm) [])
             E (:env vm)
             K (or (:k vm) [])
             vm vm
             image (get code segment)
             fuel fuel]
        (if (and fuel (zero? fuel))
          (put-registers vm seg pc val St E K)
          (let [inst (nth (:code image) pc)
                op (nth inst 0)]
            (case op
              ;; :const — val ← literal (opcode 1, :literal)
              1 (recur seg (inc pc) (nth inst 1) St E K vm image
                       (and fuel (dec fuel)))
              ;; :var — val ← resolve(E, S, prims, modules, name) (2, :load-var)
              2 (recur seg (inc pc)
                       (engine/resolve-var E (:store vm) (:primitives vm)
                                           (:modules vm) (nth inst 1))
                       St E K vm image (and fuel (dec fuel)))
              ;; :closure — val ← clo(params, entry, seg, E) (4, :lambda)
              4 (recur seg (inc pc)
                       {:type :closure,
                        :params (nth inst 1),
                        :entry (nth inst 2),
                        :segment seg,
                        :env E}
                       St E K vm image (and fuel (dec fuel)))
              ;; :push — St ← St ⧺ [val] (22, :push)
              22 (recur seg (inc pc) val (conj St val) E K vm image
                        (and fuel (dec fuel)))
              ;; :jump — pc ← target (8, :jump)
              8 (recur seg (nth inst 1) val St E K vm image
                       (and fuel (dec fuel)))
              ;; :branch-false — pc ← val ? pc+1 : target (7, :branch)
              7 (recur seg (if val (inc pc) (nth inst 1)) val St E K vm image
                       (and fuel (dec fuel)))
              ;; :halt — halt with val as the result (23, :halt)
              23 (put-registers vm nil nil val [] E nil)
              ;; :return — pop a frame, or halt on an empty K (6, :return)
              6 (if-let [frame (peek K)]
                  (recur (:segment frame) (:pc frame) val
                         (subvec St 0 (:stack-base frame))
                         (:env frame) (pop K)
                         vm (get (:code vm) (:segment frame))
                         (and fuel (dec fuel)))
                  (put-registers vm nil nil val [] E nil))
              ;; :gensym — val ← fresh id; counter advances (9, :gensym)
              9 (let [[id vm'] (engine/gensym vm (nth inst 1))]
                  (recur seg (inc pc) id St E K vm' image
                         (and fuel (dec fuel))))
              ;; :store-get — val ← S[key] (10, :store-get)
              10 (recur seg (inc pc) (get (:store vm) (nth inst 1))
                        St E K vm image (and fuel (dec fuel)))
              ;; :store-put — S[key] ← v; val ← v (11, :store-put)
              11 (let [vm' (assoc vm :store (assoc (:store vm)
                                                   (nth inst 1)
                                                   (nth inst 2)))]
                   (recur seg (inc pc) (nth inst 2) St E K vm' image
                          (and fuel (dec fuel))))
              ;; :current-continuation — val ← {seg, pc+1, E, St, K} (19)
              19 (recur seg (inc pc)
                        {:type :reified-continuation,
                         :segment seg,
                         :pc (inc pc),
                         :env E,
                         :stack St,
                         :k K}
                        St E K vm image (and fuel (dec fuel)))
              ;; :park — write {segment pc+1 env stack k} and halt (17)
              17 (-> (put-registers vm seg pc val St E K)
                     (engine/park-continuation {:segment seg,
                                                :pc (inc pc),
                                                :env E,
                                                :stack St,
                                                :k K})
                     (assoc :control nil :k nil))
              ;; :resume — restore the parked configuration with val (18)
              18 (let [vm' (engine/resume-continuation
                             (put-registers vm seg pc val St E K)
                             (nth inst 1)
                             val
                             (fn [base parked resume-val]
                               (put-registers base
                                              (:segment parked)
                                              (:pc parked)
                                              resume-val
                                              (:stack parked)
                                              (:env parked)
                                              (:k parked))))
                       control (:control vm')]
                   (recur (:segment control) (:pc control) val
                          (or (:stack vm') []) (:env vm') (or (:k vm') [])
                          vm' (get (:code vm') (:segment control))
                          (and fuel (dec fuel))))
              ;; :call — apply f to argc popped arguments (5, :call)
              5 (let [argc (nth inst 1)
                      total (count St)
                      f-pos (- total argc 1)
                      r (apply-call code seg pc val
                                    (subvec St 0 f-pos) E K vm
                                    false (nth St f-pos)
                                    (subvec St (inc f-pos) total))]
                  (if-let [[seg' pc' val' St' E' K' vm' image'] (:goto r)]
                    (recur seg' pc' val' St' E' K' vm' image'
                           (and fuel (dec fuel)))
                    (:stop r)))
              ;; :call with :yin.code/tail? — same, frame-free (20, :tailcall)
              20 (let [argc (nth inst 1)
                       total (count St)
                       f-pos (- total argc 1)
                       r (apply-call code seg pc val
                                     (subvec St 0 f-pos) E K vm
                                     true (nth St f-pos)
                                     (subvec St (inc f-pos) total))]
                   (if-let [[seg' pc' val' St' E' K' vm' image'] (:goto r)]
                     (recur seg' pc' val' St' E' K' vm' image'
                            (and fuel (dec fuel)))
                     (:stop r)))
              ;; :stream-make — effect :stream/make (12)
              12 (let [r (run-effect vm seg pc val St E K
                                     {:effect :stream/make,
                                      :capacity (or (nth inst 1)
                                                    vm/default-stream-capacity)}
                                     nil)]
                   (if-let [[vm' val'] (:continue r)]
                     (recur seg (inc pc) val' St E K vm' image
                            (and fuel (dec fuel)))
                     (:stop r)))
              ;; :stream-put — target popped from St, value in val (13)
              13 (let [St' (pop St)
                       r (run-effect vm seg pc val St' E K
                                     {:effect :stream/put,
                                      :stream (peek St),
                                      :val val}
                                     (call-park-entries seg pc E St' K))]
                   (if-let [[vm' val'] (:continue r)]
                     (recur seg (inc pc) val' St' E K vm' image
                            (and fuel (dec fuel)))
                     (:stop r)))
              ;; :stream-cursor — source ref in val (14)
              14 (let [r (run-effect vm seg pc val St E K
                                     {:effect :stream/cursor, :stream val}
                                     nil)]
                   (if-let [[vm' val'] (:continue r)]
                     (recur seg (inc pc) val' St E K vm' image
                            (and fuel (dec fuel)))
                     (:stop r)))
              ;; :stream-next — cursor ref in val (15)
              15 (let [r (run-effect vm seg pc val St E K
                                     {:effect :stream/next, :cursor val}
                                     (call-park-entries seg pc E St K))]
                   (if-let [[vm' val'] (:continue r)]
                     (recur seg (inc pc) val' St E K vm' image
                            (and fuel (dec fuel)))
                     (:stop r)))
              ;; :stream-close — source ref in val (16)
              16 (let [r (run-effect vm seg pc val St E K
                                     {:effect :stream/close, :stream val}
                                     nil)]
                   (if-let [[vm' val'] (:continue r)]
                     (recur seg (inc pc) val' St E K vm' image
                            (and fuel (dec fuel)))
                     (:stop r)))
              ;; :ffi-call — park-and-call over the FFI pair (21)
              21 (let [ffi-op (nth inst 1)
                       argc (nth inst 2)
                       total (count St)
                       args (subvec St (- total argc))
                       St' (subvec St 0 (- total argc))
                       ;; The pair is checked before parking: an error raised
                       ;; after park-continuation would strand a continuation
                       ;; in :parked and consume an id counter.
                       {:keys [call-in]}
                       (ffi/require-call-pair! (:store vm) ffi-op)
                       vm' (put-registers vm seg pc val St' E K)
                       parked (engine/park-continuation
                                vm'
                                {:segment seg, :pc (inc pc),
                                 :env E, :stack St', :k K})
                       call-id (get-in parked [:value :id])
                       request (apply2/request call-id ffi-op args)
                       result (apply2/put-request! call-in request)]
                   (case (:dao.stream/outcome result)
                     :dao.stream/ok
                     (-> parked
                         (update :wait-set (fnil conj [])
                                 (response-wait-entry seg (inc pc) E St' K
                                                      call-id))
                         (assoc :control nil
                                :k nil
                                :value :yin/blocked
                                :blocked? true
                                :halted? false))
                     :dao.stream/full
                     (-> parked
                         (update :wait-set (fnil conj [])
                                 {:segment seg, :pc (inc pc),
                                  :env E, :stack St', :k K,
                                  :request-sent true,
                                  :call-id call-id,
                                  :op ffi-op,
                                  :reason :put,
                                  :stream-id vm/call-in-stream-key,
                                  :datom request})
                         (assoc :control nil
                                :k nil
                                :value :yin/blocked
                                :blocked? true
                                :halted? false))
                     (throw (ex-info "FFI request could not be appended"
                                     {:op ffi-op,
                                      :outcome (or (:dao.stream/outcome result)
                                                   (:dao.stream.apply/outcome
                                                     result))}))))
              ;; No decoded image holds another opcode (:move stays unused)
              (throw (ex-info "Unknown opcode in segment"
                              {:op op, :segment seg, :pc pc})))))))))


;; =============================================================================
;; Scheduler
;; =============================================================================

(defn- scheduler-round
  "One round between continuations: poll the wait set, then run whatever
   woke. Entries here carry no :resume, so `engine/resume-from-run-queue`
   with this machine's restore function is what resumes them."
  [vm]
  (let [v' (engine/check-wait-set vm)]
    (or (engine/resume-from-run-queue v' semantic-restore) v')))


(defn- resume-from-run-queue
  "Pop first entry from the ready-queue, merge store-updates, and restore
   machine registers."
  [state]
  (engine/resume-from-run-queue state semantic-restore))


(defn- semantic-run-scheduler
  "The raw runner: already-loaded work only, through the shared scheduler
   loop. `ffi/maybe-run` wraps this for bridge dispatch, and `vm/run` stops
   here — no program stream is polled.

   Woken entries stay pure data and are resumed explicitly through `resume-from-run-queue`, which dispatches the
   terminal-outcome check and the machine's restore itself."
  [vm]
  (engine/run-loop vm
                   engine/active-continuation?
                   (fn [v] (vm-hot v nil))
                   resume-from-run-queue))


;; =============================================================================
;; Loading
;; =============================================================================

(def ^:private mnemonic-aliases
  "The §2.4 mnemonic → `vm/opcode-table` key mapping. Everything not here
   decodes under its own name."
  {:const :literal,
   :var :load-var,
   :closure :lambda,
   :branch-false :branch,
   :current-continuation :current-cont,
   :ffi-call :dao.stream.apply/call})


(defn- index-batch
  "Entity ids in order of first appearance, and each entity's attribute map.
   A repeated single-valued attribute keeps its last value, as
   `yin.vm.code` reads one."
  [datoms]
  (reduce (fn [[order attrs] [e a v]]
            [(if (contains? attrs e) order (conj order e))
             (assoc-in attrs [e a] v)])
          [[] {}]
          datoms))


(defn load-image
  "Decode one `:yin.code/*` batch into an executable image.

   Returns `{:segment id, :length n, :code instructions}` where `instructions`
   is a vector indexed by pc of decoded `[opcode & operands]` vectors with
   every `:yin.code/target` and `:yin.code/body` ref resolved to a pc integer
   of the same segment, plus `:address` when the segment entity carries
   `:yin.code/hash` (§2.2) — a claim checked here: the canonical vector the
   batch reconstructs to must hash to it (UCF §7.3.4's content-integrity
   check, its `:yin.k/hash-mismatch` outcome), so a false claim never
   reaches the alias column. Throws a load error naming the entity and rule
   when the batch is not well formed (§2.6); the loader is total over the
   outcomes of its inputs and does not guess."
  [datoms]
  (let [defect (code/well-formed? datoms)]
    (when defect
      (throw (ex-info (str "Cannot load segment: " (name (:rule defect))
                           " (entity " (:entity defect) ")")
                      {:defect defect})))
    (let [[order attrs] (index-batch datoms)
          segments (filterv #(= :segment (get-in attrs [% :yin.code/type]))
                            order)
          seg (first segments)
          structural [:yin.code/segment :yin.code/pc :yin.code/op]
          instructions (filterv (fn [e]
                                  (and (not= seg e)
                                       (some #(contains? (get attrs e) %)
                                             structural)))
                                order)
          ;; Rule 3 sorts by pc, so an instruction's position is its pc.
          pc-of (zipmap instructions (range))
          resolve-ref (fn [e ref]
                        (or (get pc-of ref)
                            (throw (ex-info
                                     "Ref resolves to no instruction of this segment"
                                     {:entity e, :ref ref}))))
          decode (fn [e]
                   (let [ia (get attrs e)
                         mnem (:yin.code/op ia)
                         opcode (or (get vm/opcode-table
                                         (get mnemonic-aliases mnem mnem))
                                    (throw (ex-info "Mnemonic has no opcode"
                                                    {:op mnem, :entity e})))]
                     (case mnem
                       :const [opcode (:yin.code/value ia)]
                       :var [opcode (:yin.code/name ia)]
                       :closure [opcode (:yin.code/params ia)
                                 (resolve-ref e (:yin.code/body ia))]
                       :call (if (:yin.code/tail? ia)
                               [(:tailcall vm/opcode-table) (:yin.code/argc ia)]
                               [opcode (:yin.code/argc ia)])
                       :return [opcode]
                       :jump [opcode (resolve-ref e (:yin.code/target ia))]
                       :branch-false [opcode
                                      (resolve-ref e (:yin.code/target ia))]
                       :halt [opcode]
                       :gensym [opcode (or (:yin.code/prefix ia) "id")]
                       :store-get [opcode (:yin.code/key ia)]
                       :store-put [opcode (:yin.code/key ia)
                                   (:yin.code/value ia)]
                       :stream-make [opcode (or (:yin.code/buffer ia)
                                                vm/default-stream-capacity)]
                       :stream-put [opcode]
                       :stream-cursor [opcode]
                       :stream-next [opcode]
                       :stream-close [opcode]
                       :park [opcode]
                       :resume [opcode (:yin.code/parked-id ia)]
                       :current-continuation [opcode]
                       :ffi-call [opcode (:yin.code/ffi-op ia)
                                  (or (:yin.code/argc ia) 0)]
                       :push [opcode]
                       (throw (ex-info "Unknown mnemonic"
                                       {:op mnem, :entity e})))))
          canonical (fn [e]
                      (let [ia (get attrs e)
                            mnem (:yin.code/op ia)]
                        (into [mnem]
                              (map (fn [[a kind]]
                                     (if (= :pc kind)
                                       (resolve-ref e (get ia a))
                                       (get ia a)))
                                   (get code/vector-operand-table mnem)))))
          claimed (get-in attrs [seg :yin.code/hash])
          ;; A claimed address is earned, never trusted: the batch must
          ;; reconstruct to the canonical vector that hashes to it.
          ;; UCF §7.3.4 checks an address whenever one is claimed, so a
          ;; false claim fails the load here and never reaches the alias
          ;; column.
          canonical-vec (when claimed
                          (mapv canonical instructions))]
      (if (and claimed (not (jing/segment-matches? claimed canonical-vec)))
        (throw (ex-info (str "Cannot load segment: hash-mismatch (entity "
                             seg ")")
                        {:defect {:rule :hash-mismatch, :entity seg},
                         :claimed claimed,
                         :actual (jing/segment-key canonical-vec)}))
        (cond-> {:segment seg,
                 :length (get-in attrs [seg :yin.code/length]),
                 :code (mapv decode instructions)}
          ;; A batch carrying no hash — every `lower` output — records no
          ;; alias.
          claimed (assoc :address claimed))))))


(defn- store-image
  "Register one decoded image under its segment id, returning the code map.

   Segment identity is stable: continuations, frames, and closures name
   segments by id, so an id must never come to mean different code. An
   identical reload — a batch re-sent, a batch run twice — decodes to the
   same image and is accepted. A different image under a live id is a
   conflict, and loading it fails rather than silently redirecting every
   continuation that names the id."
  [code image]
  (let [seg (:segment image)]
    (if-let [existing (find code seg)]
      (if (= (val existing) image)
        code
        (throw (ex-info "Cannot load segment: the id already holds different code"
                        {:segment seg})))
      (assoc code seg image))))


(defn- store-alias
  "Record one loaded image's address in the `address → local-id` alias
   column (UCF §7.3.4). The column is checked: an address may never land
   under a second live local id. An image claiming no address — every batch
   the datom lane's `lower` produces — leaves the column unchanged."
  [aliases image]
  (if-let [address (:address image)]
    (if-let [prior (find aliases address)]
      (if (= (val prior) (:segment image))
        aliases
        (throw (ex-info "Cannot load segment: the address is already aliased to another id"
                        {:address address, :aliased-to (val prior)})))
      (assoc aliases address (:segment image)))
    aliases))


(defn vm-load-program
  "Load one `:yin.code/*` batch: validate it, decode it, store the image under
   `:code {segment-id image}`, record the segment's claimed address — verified
   against the batch's own content by `load-image` — in the alias column when
   it carries one, and set control to `{:segment id :pc 0}`.

   This is the loader a composition hands to `dao.stream.observer/
   run-on-stream` beside `engine/ready-for-ingress?` and the VM's runner; a
   composition whose program stream carries `:yin/*` AST datoms composes the
   Phase 2 linearizer in front of it. Parked continuations survive a load, so
   one segment can resume another's. A segment id already holding different
   code is a load error; loading the same image again is accepted."
  [vm datoms]
  (let [image (load-image datoms)]
    (assoc vm
           :program (:segment image)
           :code (store-image (:code vm) image)
           :code-aliases (store-alias (:code-aliases vm) image)
           :control {:segment (:segment image), :pc 0}
           :stack []
           :k nil
           :halted? false
           :blocked? false
           :value nil)))


(defn- decode-tuple
  "One canonical tuple onto the image, reading `(nth tuple i)` for every
   operand: mnemonics onto `vm/opcode-table` and `[:call argc tail?]`
   folded to `:tailcall` by the loader's rule — the fold UCF §7.3.2 leaves
   to the decoder. The vector is saturated and its refs are resolved pcs,
   so nothing defaults and nothing resolves here."
  [t]
  (if (= :call (nth t 0))
    (if (nth t 2)
      [(:tailcall vm/opcode-table) (nth t 1)]
      [(:call vm/opcode-table) (nth t 1)])
    (let [mnem (nth t 0)
          opcode (or (get vm/opcode-table (get mnemonic-aliases mnem mnem))
                     (throw (ex-info "Mnemonic has no opcode" {:op mnem})))]
      (into [opcode] (rest t)))))


(defn load-vector
  "Load one canonical instruction vector (UCF §7.3.2) by the direct path
   (§7.1): validate it against §7.5 — a defect throws naming the pc — then
   decode the positional operands into the same image `load-image` builds
   from the projected batch, and register it under `:code` with the
   vector's address `(jing/segment-key v)` in the alias column.

   A vector claims no local id, so one is minted below the loaded floor
   (`vm/loaded-code-floor`), as `ast-loader` mints for lowered AST batches;
   an address already aliased reloads under its id, which `store-image`
   accepts as the identical image. Options:
     :id claim this local segment id instead of minting one. The §3.1 rule
          applies: an id already holding a different image is a load error,
          an identical reload is accepted. Ignored when the address is
          already aliased."
  ([vm v] (load-vector vm v {}))
  ([vm v opts]
   (when-let [defect (code/well-formed-vector? v)]
     (throw (ex-info (str "Cannot load vector: " (name (:rule defect))
                          " (pc " (:pc defect) ")")
                     {:defect defect})))
   (let [address (jing/segment-key v)
         aliases (:code-aliases vm)
         seg (or (get aliases address)
                 (:id opts)
                 (dec (vm/loaded-code-floor (:code vm))))
         image {:segment seg,
                :length (count v),
                :code (mapv decode-tuple v),
                :address address}]
     (assoc vm
            :program seg
            :code (store-image (:code vm) image)
            :code-aliases (store-alias aliases image)
            :control {:segment seg, :pc 0}
            :stack []
            :k nil
            :halted? false
            :blocked? false
            :value nil))))


;; =============================================================================
;; SemanticVM Protocol Implementation
;; =============================================================================

(defn- vm-reset
  "Reset execution state, preserving loaded code."
  [vm]
  (assoc vm
         :control (when-let [seg (:program vm)] {:segment seg, :pc 0})
         :k nil
         :stack []
         :value nil
         :halted? (nil? (:program vm))
         :blocked? false))


(defn- vm-eval
  "Resume loaded work. An AST is refused: lowering belongs to
   `yin.vm.linearize` (Phase 2) and is composed in at the observer
   boundary, so this evaluator never learns which form travels."
  [vm ast]
  (when ast
    (throw (ex-info
             "The semantic VM executes :yin.code/* segments: lower :yin/* ASTs with yin.vm.linearize (Phase 2) or call vm-load-program with a code batch"
             {:ast ast})))
  (let [initial-env (:env vm)
        res (vm/run vm)]
    (engine/restore-initial-env initial-env res)))


(extend-type SemanticVM
  vm/IVM
  (step [vm]
    (telemetry/emit-snapshot
      (if (engine/ready-for-ingress? vm) vm (vm-hot vm 1))
      :step))
  (run [vm] (ffi/maybe-run vm semantic-run-scheduler))
  (eval [vm ast] (vm-eval vm ast))
  (reset [vm] (vm-reset vm))
  (halted? [vm] (engine/halted-with-empty-queue? vm))
  (blocked? [vm] (engine/vm-blocked? vm))
  (value [vm] (engine/vm-value vm))
  vm/IVMState
  (control [vm] (:control vm))
  (environment [vm] (:env vm))
  (store [vm] (:store vm))
  (continuation [vm] (:k vm)))


(defn create-vm
  "Create a new SemanticVM.

   Options:
     :env           initial lexical environment
     :primitives    primitive operations map
     :primitive-profiles published primitive profile registry
     :primitive-canonical-names name -> canonical name for intentional aliases
     :modules       module registry value (see `yin.vm.module`)
     :make-stream   (fn [capacity] -> create outcome); no default
     :call-in       explicit inbound request handle
     :call-out      explicit outbound response handle
     :call-capacity capacity for a constructed FFI pair
     :bridge        host FFI handlers

   As for the walker: there is no `:in-stream` (program observation belongs
   to `dao.stream.observer`), and construction is all-or-nothing — the FFI
   pair and its call-out cursor are stream operations whose failure fails
   here."
  ([] (create-vm {}))
  ([opts]
   (when (contains? opts :in-stream)
     (throw (ex-info
              "Program observation moved to dao.stream.observer: a VM no longer accepts :in-stream"
              {:in-stream (:in-stream opts)})))
   (let [env (or (:env opts) {})
         base (vm/empty-state
                (assoc (select-keys opts
                                    [:primitives :primitive-profiles
                                     :primitive-canonical-names :modules
                                     :make-stream :call-in :call-out
                                     :call-capacity])
                       :telemetry (:telemetry opts)
                       :vm-model :semantic))]
     (-> (map->SemanticVM (merge base
                                 {:bridge nil,
                                  :program nil,
                                  :control nil,
                                  :env env,
                                  :stack [],
                                  :k nil,
                                  :value nil,
                                  :code {},
                                  :code-aliases {},
                                  :halted? true,
                                  :blocked? false}))
         (telemetry/install :semantic)
         (ffi/attach (:bridge opts))
         (telemetry/emit-snapshot :init)))))

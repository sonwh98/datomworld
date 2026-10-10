(ns yin.vm.semantic-register
  "The register-shaped canonical evaluator, phase 4b stage 1 (streams and continuations; bridges deferred)."
  (:require [yin.vm :as vm]
            [yin.vm.engine :as engine]
            [yin.vm.ffi :as ffi]
            [yin.vm.module :as module]
            [yin.vm.telemetry :as telemetry]
            [yin.vm.values :as values]
            [yin.vm.semantic-register.code :as code]
            [yin.vm.semantic-register.linearize :as linearize]
            [yin.vm.semantic-register.analysis :as analysis]))


(defrecord RegisterVM
  [blocked?       ; boolean, true if blocked
   bridge         ; explicit host-side FFI bridge state
   halted?        ; boolean, true when active continuation has completed
   k              ; continuation, a vector of frames (innermost last) or nil
   program        ; segment id of the last loaded batch
   control        ; {:segment id :pc n} or nil
   env            ; persistent lexical scope map
   window         ; sparse body-local register map
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
   code-aliases   ; {address segment-id}; additive, one address one id (UCF §7.3.4)
   gc])           ; {:since :base :threshold :pinned}: heap reclamation



(defn put-registers
  "Materialize control, window, environment and continuation; value is exit-only."
  [machine seg pc W E K]
  (assoc machine :control (when seg {:segment seg :pc pc})
         :window W :env E :k (when (seq K) K)
         :halted? (and (not (:blocked? machine)) (nil? seg))))


(defn- return-to
  "The one popped-frame delivery: retain exactly the saved window and write rd."
  [frame value]
  (assoc frame :window (assoc (:window frame) (:rd frame) value)))


(defn- act-payload
  [seg pc E W K rd facts]
  {:segment seg :pc (inc pc) :env E
   :window (select-keys W (analysis/saved facts (inc pc) rd))
   :k K :deliver {:deliver :rd :rd rd}})


(defn- entry-builders
  [payload]
  {:stream/put (fn [_state _effect result]
                 (assoc payload :reason :put :stream-id (:stream-id result)))
   :stream/next (fn [_state _effect result]
                  (assoc payload :reason :next :cursor-ref (:cursor-ref result)
                         :stream-id (:stream-id result)))})


(defn- act-builders
  [seg pc E W K rd facts]
  (entry-builders (act-payload seg pc E W K rd facts)))


(defn- tail-builders
  [seg pc K]
  (entry-builders {:segment seg :site pc :k K :deliver {:deliver :return}}))


(defn- run-effect
  [machine seg pc W E K effect builders]
  (let [{:keys [state value blocked?]}
        (engine/handle-effect (put-registers machine seg pc W E K) effect
                              (when builders {:park-entry-fns builders}))]
    (if blocked?
      {:stop (assoc state :control nil :k nil)}
      {:continue [state value]})))


(defn register-restore
  "Deliver a value to an activation or through its saved return frames.
   FFI request phases and decoding are deferred to phase 4b stage 2."
  ([base entry] (register-restore base entry (:value entry)))
  ([base entry value]
   (case (get-in entry [:deliver :deliver])
     :rd (put-registers base (:segment entry) (:pc entry)
                        (assoc (:window entry) (get-in entry [:deliver :rd]) value)
                        (:env entry) (:k entry))
     :return (if-let [frame (peek (:k entry))]
               (put-registers base (:segment frame) (:pc frame)
                              (:window (return-to frame value))
                              (:env frame) (pop (:k entry)))
               (assoc (put-registers base nil nil {}
                                     (engine/without-store-of (:env base)) nil)
                      :value value))
     (throw (ex-info "Entry carries no delivery record" {:rule :deliver :entry entry})))))


(defn- not-in-slice!
  [op]
  (throw (ex-info "Instruction is not in slice 4a"
                  {:reason :not-in-slice-4a :op op})))


(defn- scheduler-round
  [machine]
  (engine/scheduler-round machine register-restore))


(defn vm-hot
  "Execute fuel instructions with the activation in locals; nil fuel runs to exit."
  [machine fuel]
  (if (and (nil? (:control machine)) (nil? (:k machine)))
    (scheduler-round machine)
    (let [{:keys [segment pc]} (:control machine)]
      (loop [machine machine seg segment pc pc W (:window machine)
             E (:env machine) K (or (:k machine) []) fuel fuel]
        (if (and fuel (zero? fuel))
          (put-registers machine seg pc W E K)
          (let [image (get (:code machine) seg)
                inst (nth (:vector image) pc)
                op (nth inst 0)
                remaining (when fuel (dec fuel))]
            (case op
              :const (recur machine seg (inc pc) (assoc W (nth inst 1) (nth inst 2)) E K remaining)
              :var (recur machine seg (inc pc)
                          (assoc W (nth inst 1)
                                 (engine/resolve-var E
                                                     (engine/active-store machine (engine/env-store-of E))
                                                     (:primitives machine) (:modules machine) (nth inst 2)))
                          E K remaining)
              :closure (recur machine seg (inc pc)
                              (assoc W (nth inst 1)
                                     (values/closure (:owner machine)
                                                     {:type :closure :params (vm/check-params! (nth inst 2))
                                                      :entry (nth inst 3) :segment seg :env E}))
                              E K remaining)
              :jump (recur machine seg (nth inst 1) W E K remaining)
              :branch-false (recur machine seg (if (get W (nth inst 1)) (inc pc) (nth inst 2)) W E K remaining)
              :define (let [value (get W (nth inst 3))
                            next-state (engine/put-active machine
                                                          (engine/env-store-of E) (nth inst 2) value)]
                        (recur next-state seg (inc pc) (assoc W (nth inst 1) value) E K remaining))
              :gensym (let [[id next-state] (engine/gensym machine (nth inst 2))]
                        (recur next-state seg (inc pc) (assoc W (nth inst 1) id) E K remaining))
              :store-get (recur machine seg (inc pc)
                                (assoc W (nth inst 1) (get (engine/active-store machine (engine/env-store-of E)) (nth inst 2)))
                                E K remaining)
              :store-put (let [next-state (engine/put-active machine
                                                             (engine/env-store-of E) (nth inst 2) (nth inst 3))]
                           (recur next-state seg (inc pc) (assoc W (nth inst 1) (nth inst 3)) E K remaining))
              :halt (assoc (put-registers machine nil nil {} (engine/without-store-of E) nil) :value (get W (nth inst 1)))
              :return (let [value (get W (nth inst 1))]
                        (if-let [frame (peek K)]
                          (recur machine (:segment frame) (:pc frame)
                                 (:window (return-to frame value)) (:env frame) (pop K) remaining)
                          (assoc (put-registers machine nil nil {} (engine/without-store-of E) nil) :value value)))
              :call (let [rd (nth inst 1) f (get W (nth inst 2))
                          args (mapv #(get W %) (nth inst 3)) tail? (nth inst 4)]
                      (case (engine/operator-kind machine f)
                        :closure (let [c (values/payload f)
                                       frame (when-not tail?
                                               {:type :return :segment seg :pc (inc pc) :env E
                                                :window (select-keys W (analysis/saved (:analysis image) (inc pc) rd)) :rd rd})]
                                   (recur machine (:segment c) (:entry c) {}
                                          (merge (:env c) (engine/bind-params (:params c) args))
                                          (if tail? K (conj K frame)) remaining))
                        :host-fn
                        (let [result (apply f args)
                              effect? (module/effect? result)
                              _ (when (and effect? (= :module/require (:effect result)))
                                  (throw (ex-info "Link is deferred to stage 2" {:reason :not-in-stage1 :op :call})))
                              r (if effect?
                                  (run-effect (engine/check-callee-effect!
                                                (put-registers machine seg pc W E K) f result)
                                              seg pc W E K result
                                              (if tail? (tail-builders seg pc K)
                                                  (act-builders seg pc E W K rd (:analysis image))))
                                  {:continue [machine result]})]
                          (if-let [[next-state value] (:continue r)]
                            (let [E' (if effect? (:env next-state) E)
                                  K' (if effect? (or (:k next-state) []) K)
                                  W' (if effect? (:window next-state) W)]
                              (if tail?
                                (if-let [frame (peek K')]
                                  (recur next-state (:segment frame) (:pc frame)
                                         (:window (return-to frame value)) (:env frame) (pop K') remaining)
                                  (assoc (put-registers next-state nil nil {} (engine/without-store-of E') nil) :value value))
                                (recur next-state seg (inc pc) (assoc W' rd value) E' K' remaining)))
                            (:stop r)))
                        :continuation
                        (let [value (engine/continuation-argument args)
                              restored (register-restore machine (values/payload f) value)
                              control (:control restored)]
                          (recur restored (:segment control) (:pc control) (:window restored)
                                 (:env restored) (or (:k restored) []) remaining))))
              (:stream-make :stream-cursor :stream-close :stream-put :stream-next)
              (let [rd (nth inst 1)
                    effect (case op
                             :stream-make (module/make-effect :stream/make {:capacity (nth inst 2)})
                             :stream-cursor (module/make-effect :stream/cursor {:stream (get W (nth inst 2))})
                             :stream-close (module/make-effect :stream/close {:stream (get W (nth inst 2))})
                             :stream-put (module/make-effect :stream/put {:stream (get W (nth inst 2)) :val (get W (nth inst 3))})
                             :stream-next (module/make-effect :stream/next {:cursor (get W (nth inst 2))}))
                    r (run-effect machine seg pc W E K effect
                                  (when (contains? #{:stream-put :stream-next} op)
                                    (act-builders seg pc E W K rd (:analysis image))))]
                (if-let [[next-state value] (:continue r)]
                  (recur next-state seg (inc pc) (assoc (:window next-state) rd value)
                         (:env next-state) (or (:k next-state) []) remaining)
                  (:stop r)))
              :current-continuation
              (let [rd (nth inst 1)
                    payload (assoc (act-payload seg pc E W K rd (:analysis image)) :type :reified-continuation)]
                (recur machine seg (inc pc) (assoc W rd (values/continuation (:owner machine) payload)) E K remaining))
              :park (-> (put-registers machine seg pc W E K)
                        (engine/park-continuation (act-payload seg pc E W K (nth inst 1) (:analysis image)))
                        (assoc :control nil :k nil))
              :resume (let [restored (engine/resume-continuation (put-registers machine seg pc W E K)
                                                                 (nth inst 1) (get W (nth inst 2)) register-restore)
                            control (:control restored)]
                        (if (:halted? restored) restored
                            (recur restored (:segment control) (:pc control) (:window restored)
                                   (:env restored) (or (:k restored) []) remaining)))
              (not-in-slice! op))))))))


(defn- register-run-scheduler
  [machine]
  (engine/run-loop machine engine/active-continuation? #(vm-hot % nil)
                   #(engine/resume-from-run-queue % register-restore)))


(defn- store-image
  [images image]
  (let [seg (:segment image)]
    (if-let [existing (find images seg)]
      (if (= (val existing) image) images
          (throw (ex-info "Cannot load segment: the id already holds different code" {:segment seg})))
      (assoc images seg image))))


(defn- store-alias
  [aliases image]
  (let [address (:address image) seg (:segment image)]
    (if-let [prior (find aliases address)]
      (if (= (val prior) seg) aliases
          (throw (ex-info "Cannot load segment: the address is already aliased to another id" {:address address :aliased-to (val prior)})))
      (assoc aliases address seg))))


(defn- admit-image
  [machine v stamp opts]
  (let [image (code/load-vector v stamp)
        seg (or (get (:code-aliases machine) (:address image)) (:id opts)
                (dec (vm/loaded-code-floor (:code machine))))]
    (assoc image :segment seg :length (count v) :analysis (analysis/analyze v))))


(defn load-vector
  "Validate and load a v4 vector; an existing address retains its local id."
  ([machine v stamp] (load-vector machine v stamp {}))
  ([machine v stamp opts]
   (let [image (admit-image machine v stamp opts) seg (:segment image)]
     (assoc machine :code (store-image (:code machine) image)
            :code-aliases (store-alias (:code-aliases machine) image)
            :program seg :control {:segment seg :pc 0} :window {} :k nil
            :halted? false :blocked? false :value nil))))


(defn attach-image
  "Attach a validated vector without changing execution fields."
  [machine v stamp]
  (let [image (admit-image machine v stamp {})]
    (assoc machine :code (store-image (:code machine) image)
           :code-aliases (store-alias (:code-aliases machine) image))))


(defn load-ast
  "Project AST datoms through the phase-3 loader."
  [machine datoms stamp]
  (vm/check-contract! vm/ast-contract stamp)
  (load-vector machine (:vector (linearize/project-datoms datoms)) code/contract))


(extend-type RegisterVM
  vm/IVM
  (step [machine] (telemetry/emit-snapshot
                    (if (engine/ready-for-ingress? machine) machine (vm-hot machine 1)) :step))
  (run [machine] (ffi/maybe-run machine register-run-scheduler))
  (eval [machine ast]
    (when ast (throw (ex-info "The register VM executes loaded vectors; use load-ast" {:ast ast})))
    (engine/restore-initial-env (:env machine) (vm/run machine)))
  (reset [machine]
    (assoc machine :control (when-let [seg (:program machine)] {:segment seg :pc 0})
           :window {} :k nil :value nil :halted? (nil? (:program machine)) :blocked? false))
  (halted? [machine] (engine/halted-with-empty-queue? machine))
  (blocked? [machine] (engine/vm-blocked? machine))
  (value [machine] (engine/vm-value machine))
  vm/IVMState
  (control [machine] (:control machine))
  (environment [machine] (:env machine))
  (store [machine] (:store machine))
  (continuation [machine] (:k machine)))


(defn create-vm
  "Create a new RegisterVM.

   Options:
     :env           initial lexical environment
     :primitives    primitive operations map
     :primitive-profiles published primitive profile registry
     :primitive-canonical-names name -> canonical name for intentional aliases
     :modules       module registry value (see `yin.vm.module`)
     :make-stream   (fn [capacity] -> create outcome); no default
     :call-in       explicit inbound request handle
     :call-out      explicit outbound response handle
     :call-out-cursor, :ffi-caller-id  `yin.vm/empty-state`'s
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
   (let [env (vm/check-bindings! :env (or (:env opts) {}))
         base (vm/empty-state
                (assoc (select-keys opts
                                    [:primitives :primitive-profiles
                                     :primitive-canonical-names :modules
                                     :make-stream :call-in :call-out
                                     :call-out-cursor :ffi-caller-id
                                     :call-capacity :link-request
                                     :link-response :origin :ancestry
                                     :capability-secret :secret-source
                                     :attach-stream :gc-threshold])
                       :telemetry (:telemetry opts)
                       :vm-model :semantic-register))]
     (-> (map->RegisterVM (merge base
                                 {:bridge nil,
                                  :program nil,
                                  :control nil,
                                  :env env,
                                  :window {},
                                  :k nil,
                                  :value nil,
                                  :code {},
                                  :code-aliases {},
                                  :halted? true,
                                  :blocked? false}))
         (telemetry/install :semantic-register)
         (ffi/attach (:bridge opts))
         (telemetry/emit-snapshot :init)))))


;; Stage 1 installs only GC traversal; linking methods belong to stage 2.
(extend-type RegisterVM
  module/IModuleKernel
  (gc-roots [machine]
    {:kernel [(:control machine) (:k machine)]
     :values [(:env machine) (:window machine) (:value machine)]})
  (gc-children [_ _x] nil))

(ns yin.repl.core
  "The local Yin shell for DaoStream v2: explicit state, synchronous steps.

   This namespace owns no socket, atom, promise, callback, clock, or namespace
   global.  It holds the one shell value a driver threads: the evaluator beside
   its separately-owned program media — `program-in`, which the macro
   expander observes, and `program-out`, which the evaluator's own observer
   watches (`docs/design/yin.vm.macro.md` §10.1) — the language, the value
   history, the v2 output medium with its cursor, and a per-medium ledger
   recording the last outcome that medium answered.  Every function takes a
   state and returns the next one.

   `yin.repl` is untouched and keeps running; this is a second implementation
   beside it."
  (:require #?(:cljd [clojure.edn :as edn]
               :cljs [cljs.reader :as reader])
            [clojure.string :as str]
            [dao.pretty :as pretty]
            [dao.stream :as stream]
            [dao.stream.ringbuffer :as ring]
            [yang.clojure :as yang.clojure]
            [yang.php :as yang.php]
            [yang.python :as yang.python]
            [yin.vm :as vm]
            [yin.vm.ast-walker :as ast-walker]
            [yin.vm.encoder :as encoder]
            [yin.vm.engine :as engine]
            [yin.vm.linearize :as linearize]
            [yin.vm.macro :as macro]
            [yin.vm.module :as module]
            [yin.vm.semantic :as semantic]
            [dao.stream.observer :as observer]))


(def output-capacity
  "Declared capacity of the composition-owned output medium, in elements."
  4096)


(def ingress-capacity
  "Declared capacity of the composition-owned program medium, in elements.  The
   shell is the only appender and its one step owner serializes evaluation, so
   eviction can only follow a producer the composition does not make; a gap is
   therefore fatal to the current evaluation rather than a normal operating
   condition."
  4096)


(def drain-budget
  "Maximum reads one output drain performs, so the drain is total even against
   a transport that could answer `gap` repeatedly."
  4096)


(def vm-constructors
  "The evaluators on `yin.vm`: the ast-walker and the linear semantic VM
   (`docs/design/yin.vm.semantic.md`)."
  {:ast-walker ast-walker/create-vm
   :semantic semantic/create-vm})


(def vm-labels {:ast-walker "ASTWalkerVM" :semantic "SemanticVM"})


(def program-loaders
  "The loader the evaluator observer hands each `program-out` value, per
   evaluator.  Every value there is one expanded canonical tree packet
   `[root rows]` (yin.vm.macro.md §2.4); each loader takes its row set — the
   ast-walker through `vm-load-rows`, the semantic VM through
   `linearize/rows-loader` over `semantic/load-vector`.  Neither evaluator
   learns that an expander ran."
  {:ast-walker (fn [vm packet]
                 (ast-walker/vm-load-rows vm (macro/packet->row-set packet)))
   :semantic (let [load-rows (linearize/rows-loader semantic/load-vector)]
               (fn [vm packet]
                 (load-rows vm (macro/packet->row-set packet))))})


(def lang-labels {:clojure "Clojure" :python "Python" :php "PHP"})


(def command-heads
  "Commands this shell answers.  `connect` and `disconnect` belong to the
   driver, which owns the RPC client; `telemetry` is answered only to say that
   the v2 slice does not have it."
  #{'vm 'lang 'compile 'reset 'help 'repl-state 'quit 'telemetry})


(def help-text
  (str "Commands:\n"
       "  (vm :ast-walker | :semantic)\n"
       "  (lang :clojure | :python | :php)\n"
       "  (compile expr)\n"
       "  (reset)\n"
       "  (connect \"daostream:ws://host:port\")\n"
       "  (disconnect)\n"
       "  (repl-state)\n"
       "  (help)\n"
       "  (quit)\n"
       "  *1, *2, *3  - last, second-to-last, and third-to-last evaluated values"))


(def telemetry-text
  (str "(telemetry) is not wired into this shell: the v2 emit path is built "
       "(yin.vm.telemetry, opt-in via the :telemetry {:stream ...} "
       "construction option — yin.vm.telemetry.implementation-plan.md), but "
       "composing a sink into this shell is not, so the command reports "
       "rather than evaluates"))


(def ingress-loss-text
  "One or more program batches were never observed from the program medium; (reset) is required before more evaluation")


;; =============================================================================
;; Rendering
;; =============================================================================

(defn- quote-symbols
  [x]
  (cond (symbol? x) (list 'quote x)
        (vector? x) (mapv quote-symbols x)
        (map? x)
        (into {} (map (fn [[k v]] [(quote-symbols k) (quote-symbols v)]) x))
        (or (list? x) (seq? x))
        (if (= 'quote (first x)) x (map quote-symbols x))
        :else x))


(defn format-value
  [value]
  (str/trimr (pretty/pp-str (quote-symbols value))))


(defn format-error
  [error]
  (str "Error: "
       #?(:cljd (or (ex-message error) (str error))
          :cljs (or (ex-message error) (.-message error) (str error))
          :clj (or (ex-message error) (str error)))))


(defn- print-arg
  [value]
  (if (string? value) value (format-value value)))


(defn- print-text
  [args]
  (str/join " " (map print-arg args)))


(defn- prn-text
  [args]
  (str (str/join " " (map format-value args)) "\n"))


;; =============================================================================
;; The output medium
;; =============================================================================

(defn- emit-output!
  [output-stream op text]
  ;; A primitive holds no state, so it cannot record an append outcome.  The
  ;; medium is an evict-oldest ring buffer, which never answers `full`; a closed
  ;; medium drops the chunk and the drain reports the closure.
  (when output-stream
    (stream/append! output-stream {:type :repl/output :op op :text text}))
  nil)


(defn- make-repl-primitives
  [output-stream]
  (merge vm/primitives
         {'print (fn [& args] (emit-output! output-stream :print (print-text args)))
          'println (fn [& args]
                     (emit-output! output-stream :println
                                   (str (print-text args) "\n")))
          'prn (fn [& args] (emit-output! output-stream :prn (prn-text args)))
          'ast->datoms vm/ast->datoms
          'datoms->ast vm/datoms->ast}))


(defn- chunk-text
  [value]
  (if (map? value) (str (:text value)) (str value)))


(defn- ledger
  [state medium outcome]
  (assoc-in state [:ledger medium] outcome))


(defn drain-output
  "Drain the output medium into text, total over every `next` outcome.

   `ok` advances to the exact successor the handle returned.  `gap` prints a
   loss notice and resumes at the recovery cursor.  `end`, `cursor-mismatch`,
   `invalid-cursor`, and `transport-error` print a notice, leave the cursor
   unchanged, and end the drain.  Whatever ended the drain is recorded in the
   ledger, so `repl-state` never has to ask a stream how it is."
  [state]
  (let [output (:output-stream state)
        cursor (:output-cursor state)]
    (cond
      (nil? output) [(ledger state :output :untried) ""]
      (nil? cursor) [(ledger state :output :dao.stream/invalid-cursor)
                     ";; output unreadable: no cursor was minted\n"]
      :else
      (loop [remaining drain-budget
             cursor cursor
             chunks []]
        (if (zero? remaining)
          [(-> state
               (assoc :output-cursor cursor)
               (ledger :output :dao.stream/blocked))
           (apply str chunks)]
          (let [result (stream/next output cursor)
                outcome (:dao.stream/outcome result)]
            (case outcome
              :dao.stream/ok
              (recur (dec remaining)
                     (:dao.stream/cursor result)
                     (conj chunks (chunk-text (:dao.stream/value result))))

              :dao.stream/gap
              (recur (dec remaining)
                     (:dao.stream/cursor result)
                     (conj chunks ";; output lost: resumed at the recovery cursor\n"))

              :dao.stream/blocked
              [(-> state
                   (assoc :output-cursor cursor)
                   (ledger :output outcome))
               (apply str chunks)]

              [(-> state
                   (assoc :output-cursor cursor)
                   (ledger :output outcome))
               (apply str (conj chunks (str ";; output " (name outcome) "\n")))])))))))


(defn- make-output-medium!
  []
  (:dao.stream/handle
    (ring/create! {:dao.stream/type ring/transport-type
                   ring/capacity-key output-capacity})))


(defn- mint-cursor
  [handle]
  (let [result (stream/cursor handle :dao.stream/oldest)]
    (when (= :dao.stream/ok (:dao.stream/outcome result))
      (:dao.stream/cursor result))))


;; =============================================================================
;; The VM and its program medium
;; =============================================================================

(defn- make-ring-stream
  "The `:make-stream` the shell hands the VM: one v2 ring buffer per call.  A
   nil capacity is the VM's default, not an unbounded stream; v2 has no
   unbounded mode."
  [capacity]
  (ring/create! {:dao.stream/type ring/transport-type
                 ring/capacity-key (or capacity vm/default-stream-capacity)}))


(defn make-vm
  "Construct the shell's evaluator on `yin.vm`.

   The shell is the composition that chooses the VM's transport: the
   ast-walker is handed `:make-stream` bound to the v2 ring buffer and the v2
   `stream` module registered in its registry.  No telemetry stream is
   installed, and the VM owns no program medium: `make-session` builds the
   medium, its attachment, the observer, and the VM together.

   `extra-primitives` is the host-supplied map merged over the REPL's own
   primitives, so an embedding host's functions win a name collision."
  ([vm-type output-stream] (make-vm vm-type output-stream nil))
  ([vm-type output-stream extra-primitives]
   (when-not (contains? vm-constructors vm-type)
     (throw (ex-info "Unknown Yin REPL VM type"
                     {:vm-type vm-type
                      :supported (vec (keys vm-constructors))})))
   ((get vm-constructors vm-type)
    {:primitives (merge (make-repl-primitives output-stream) extra-primitives)
     :modules (module/register-stream-module (module/default-registry))
     :make-stream make-ring-stream})))


(defn- make-attachment
  "Create one composition-owned medium of `capacity` elements, the unary
   attachment entry bound to it for this medium's lifetime, and an observer
   attached through that entry.

   The resolver maps the descriptor's identity to the owner handle this
   composition created, which is host composition around the ring buffer's own
   attach mechanism: the observer itself sees only a descriptor and a unary
   capability, so no transport detail crosses into it."
  [capacity]
  (let [writer (:dao.stream/handle
                 (ring/create! {:dao.stream/type ring/transport-type
                                ring/capacity-key capacity}))
        described (stream/descriptor writer)
        descriptor (:dao.stream/descriptor described)
        identity (:dao.stream/identity described)
        attach! (ring/make-attacher {(:dao.stream/identity descriptor) writer})]
    {:stream writer
     :identity identity
     :observer (observer/attach attach! descriptor)}))


(defn- standard-store
  "The expander store seeded with the standard forms, folded through the
   same row-native batch contract as any input (yin.vm.macro.md §5.4)."
  []
  (macro/seed-store (macro/make-ctx {:token :yin.repl/standard-forms
                                     :source-medium :yin.repl/standard-forms})
                    [macro/stdlib-forms]))


(defn- make-expander
  "The expander consumer for one session: a fresh incarnation token, the
   `program-in` identity as its source medium, the standard store, and the
   `program-out` writer.  No log writer: `(compile ...)` renders events."
  [program-identity program-out]
  (macro/make-expander
    (macro/make-ctx {:token (str (random-uuid))
                     :source-medium program-identity
                     :store (standard-store)})
    program-out))


(defn- make-session
  "Build the evaluator, its media, their attachments and observers, the
   expander, and the loader the evaluator observer feeds the VM, together.
   Reset and VM selection call this, so the whole composition is rebuilt as
   one and each attachment capability is bound exactly once per medium
   lifetime.

   Both evaluators sit behind the same two stages (yin.vm.macro.md §10.1):
   the expander observes `program-in` — `:program-stream`, watched by
   `:observer` — and appends each expanded tree packet to `program-out` —
   `:row-stream`, watched independently by the evaluator's `:row-observer`,
   which loads each packet and runs it."
  [vm-type output-stream extra-primitives]
  (let [vm (make-vm vm-type output-stream extra-primitives)
        program-in (make-attachment ingress-capacity)
        program-out (make-attachment ingress-capacity)]
    {:vm vm
     :load-program (get program-loaders vm-type)
     :program-stream (:stream program-in)
     :program-identity (:identity program-in)
     :observer (:observer program-in)
     :expander (make-expander (:identity program-in) (:stream program-out))
     :row-stream (:stream program-out)
     :row-observer (:observer program-out)}))


(defn- run-vm
  "Run the VM through its protocol entry point, as a plain function so it can
   be handed to observer coordination on every host."
  [vm]
  (vm/run vm))


(defn create-state
  "Create the shell value.  `:primitives` is a host-supplied map merged over
   the REPL primitives; it is kept as `:extra-primitives` so every session
   rebuild — `(reset)`, `(vm …)` — installs it again."
  ([] (create-state {}))
  ([{:keys [lang output-cursor output-stream vm-type primitives]
     :or {lang :clojure vm-type :semantic}}]
   (let [output-stream (or output-stream (make-output-medium!))
         output-cursor (or output-cursor (mint-cursor output-stream))]
     (merge
       (make-session vm-type output-stream primitives)
       {:lang lang
        :vm-type vm-type
        :extra-primitives primitives
        :output-stream output-stream
        :output-cursor output-cursor
        :ledger {:output :untried}
        :ingress-loss? false
        :last-value nil
        :last-value-2 nil
        :last-value-3 nil
        :pending-input nil
        :running? true}))))


;; =============================================================================
;; Reading and classifying input
;; =============================================================================

(defn- read-forms
  [input]
  #?(:cljd (edn/read-string (str "[" input "]"))
     :cljs (reader/read-string (str "[" input "]"))
     :clj (clojure.core/read-string (str "[" input "]"))))


(defn- command-form?
  [form]
  (and (seq? form)
       (symbol? (first form))
       (contains? command-heads (first form))))


(defn- datom?
  [value]
  (and (vector? value) (= 5 (count value))))


(defn- datom-stream?
  [value]
  (and (vector? value) (seq value) (every? datom? value)))


(defn- ast-map?
  [value]
  (and (map? value) (keyword? (:type value))))


(defn- bracket-balance
  "Open minus close brackets, ignoring string literals and `;` comments.
   Positive means the input is incomplete."
  [s]
  (loop [chars (seq s)
         depth 0
         in-str? false]
    (if (empty? chars)
      depth
      (let [ch (first chars)
            tail (rest chars)]
        (cond in-str? (cond (= ch \\) (recur (rest tail) depth true)
                            (= ch \") (recur tail depth false)
                            :else (recur tail depth true))
              (= ch \") (recur tail depth true)
              (= ch \;) (recur (drop-while #(not= % \newline) tail) depth false)
              (#{\( \[ \{} ch) (recur tail (inc depth) false)
              (#{\) \] \}} ch) (recur tail (dec depth) false)
              :else (recur tail depth false))))))


;; =============================================================================
;; Evaluation
;; =============================================================================

(defn- inject-last-value
  [state]
  (update state :vm update :store assoc
          '*1 (:last-value state)
          '*2 (:last-value-2 state)
          '*3 (:last-value-3 state)))


(defn- record-last-value
  [state state-after value]
  (assoc state-after
         :last-value value
         :last-value-2 (:last-value state)
         :last-value-3 (:last-value-2 state)))


(defn- finalize-eval
  [state state' vm']
  (let [value (vm/value vm')
        [state'' output-text] (drain-output (assoc state' :vm vm'))]
    [(record-last-value state state'' value)
     (str output-text (format-value value))]))


(defn- drain-observer
  "Advance one observer past every batch still on its medium, keeping any
   gap it recovers across.  A nil observer — a session without that medium —
   stays nil."
  [observer]
  (when observer
    (loop [observer observer]
      (let [{:keys [status] observer' :observer} (observer/observe-next observer)]
        (if (#{:ok :gap} status) (recur observer') observer')))))


(defn- consume-failed-round
  "Answer a round that threw with a recoverable shell state.

   The failed input is consumed exactly once: each of the session's
   observers resumes from the session the throw carried — the failing
   stage's, matched by the medium it holds — or from where the round began
   when it carried none, and reads past every batch still on its medium.
   The shell is the only appender and appends one batch per round, so what
   remains is the failed batch alone.  The VM is the one the round began
   with, so a half-run continuation cannot be resumed — and its error
   replayed — by the next input.  Output the round already emitted is
   drained here, once."
  [state error]
  (let [carried (get-in (ex-data error) [:session :observer])
        resume (fn [observer]
                 (if (and observer carried (= (:stream carried) (:stream observer)))
                   carried
                   observer))
        program (drain-observer (resume (:observer state)))
        rows (drain-observer (resume (:row-observer state)))
        gaps-before (+ (:ingress-gaps (:observer state) 0)
                       (:ingress-gaps (:row-observer state) 0))
        gaps-after (+ (:ingress-gaps program 0) (:ingress-gaps rows 0))
        [state' output-text]
        (drain-output
          (cond-> (assoc state :observer program :row-observer rows)
            (> gaps-after gaps-before)
            (assoc :ingress-loss? true)))]
    [state' (str output-text (format-error error))]))


(defn- ingress-gaps
  [state]
  (+ (:ingress-gaps (:observer state) 0)
     (:ingress-gaps (:row-observer state) 0)))


(defn- run-expander-stage
  "Drive the expander over `program-in` and drain its summary.  It expands
   each observed batch and appends the tree packet to `program-out`; a
   failed expansion forwards nothing, yet its cursor advances (yin.vm.macro.md
   decision 12).  Returns the state with the expander's progress and the
   drained `{:errors :forwarded}` summary."
  [state]
  (let [{:keys [observer consumer]}
        (macro/step {:observer (:observer state), :consumer (:expander state)})
        [expander summary] (macro/drain-errors consumer)]
    [(assoc state :observer observer :expander expander) summary]))


(defn- run-evaluator-stage
  "Drive the evaluator's observer over `program-out`: it loads each
   expanded tree packet into the VM and runs it.  The two stages meet only
   at `program-out`; neither calls the other."
  [{:keys [load-program] :as state}]
  (let [{:keys [observer] vm :consumer}
        (observer/run-on-stream {:observer (:row-observer state),
                                 :consumer (:vm state)}
                                engine/ready-for-ingress?
                                load-program
                                run-vm)]
    (assoc state :row-observer observer :vm vm)))


(defn- format-expansion-errors
  [errors]
  (str/join "\n" (map #(str "Error: Macro expansion failed: " (format-value %))
                      errors)))


(defn- run-evaluation
  "The evaluator half of a round whose expander forwarded a program: run
   it, then either finalize the value or consume the failure.  `state` is
   the round's starting state; `expanded` already carries the expander's
   progress, which a failing evaluation keeps."
  [state expanded]
  (try
    (let [gaps-before (ingress-gaps expanded)
          evaluated (run-evaluator-stage expanded)]
      (cond
        (> (ingress-gaps evaluated) gaps-before)
        [(assoc evaluated :ingress-loss? true)
         (str "Error: " ingress-loss-text)]

        (vm/halted? (:vm evaluated))
        (finalize-eval state evaluated
                       (engine/restore-initial-env (:env (:vm expanded))
                                                   (:vm evaluated)))

        :else
        (throw
          (ex-info
            "Program stream did not form a complete, runnable Yin VM program.
Hint: If you wanted to evaluate these datoms as data, use a quote: '[[...]]"
            {:vm-type (:vm-type state)}))))
    (catch #?(:cljd Object :clj Exception :cljs js/Error) error
      (consume-failed-round (assoc expanded :vm (:vm state)) error))))


(defn- eval-program
  "Evaluate one frontend program — the map AST the compilers emit or a
   datom-literal program — through the §10.1 composition.  The encoder
   projects it to an input batch of canonical rows with its harvest and
   declaration rows; the shell appends that batch to `program-in`, drives
   the expander, drains its summary, and drives the evaluator only when a
   program was forwarded.  An expansion failure is reported as data: its
   batch is consumed, the store keeps its previous macros, and the next
   input evaluates normally.  An encoding failure throws before anything
   is appended.

   The observers recover across a gap on their own, but the shell reads the
   gap counts around the round: an increase on any medium means one or more
   batches were never run, so resuming as though execution were complete
   would report a result built on programs the VM never saw.  The loss is
   reported and the shell refuses further evaluation until `(reset)`.

   A completed program's lexical environment does not outlive it: the VM's
   environment is restored to the one the round began with, as `vm/eval`
   does.  A round that throws is consumed by `consume-failed-round`."
  [state program]
  (if (:ingress-loss? state)
    [state (str "Error: " ingress-loss-text)]
    (let [state' (inject-last-value state)
          batch (encoder/program-batch program)
          append (stream/append! (:program-stream state') batch)]
      (if-not (= :dao.stream/ok (:dao.stream/outcome append))
        [state (str "Error: program batch not ingested: "
                    (name (:dao.stream/outcome append)))]
        (let [expanded (try
                         (run-expander-stage state')
                         (catch #?(:cljd Object :clj Exception :cljs js/Error) error
                           {:failed (consume-failed-round state error)}))]
          (if-let [failed (:failed expanded)]
            failed
            (let [[expanded {:keys [errors forwarded]}] expanded]
              (cond
                (> (ingress-gaps expanded) (ingress-gaps state'))
                [(assoc expanded :ingress-loss? true)
                 (str "Error: " ingress-loss-text)]

                (pos? forwarded) (run-evaluation state expanded)

                :else [(assoc expanded :vm (:vm state))
                       (format-expansion-errors errors)]))))))))


(defn- compile-clojure-forms
  [forms]
  (if (= 1 (count forms))
    (yang.clojure/compile (first forms))
    (yang.clojure/compile-program forms)))


(defn- compile-source
  [lang input]
  (case lang
    :clojure (compile-clojure-forms (read-forms input))
    :python (yang.python/compile input)
    :php (yang.php/compile input)
    (throw (ex-info "Unsupported Yin REPL language" {:lang lang}))))


(defn- compile-command-ast
  [state arg]
  (case (:lang state)
    :clojure (if (string? arg)
               (compile-source :clojure arg)
               (yang.clojure/compile arg))
    (if (string? arg)
      (compile-source (:lang state) arg)
      (throw (ex-info "This compile command expects a source string"
                      {:lang (:lang state) :arg arg})))))


(defn- render-compile-output
  "Render the frontend syntax, the input row batch, and what the session's
   expander would make of it against its current store: the expanded tree
   packet (or the expansion error) and the event rows.  The expansion is a
   preview; its context is discarded, so nothing is committed."
  [state ast]
  (let [batch (encoder/program-batch ast)
        {:keys [tree error log]} (macro/expand-batch batch
                                                     (get-in state [:expander :ctx]))]
    (str "AST:\n" (format-value ast)
         "\n\nInput rows:\n" (format-value batch)
         (if tree
           (str "\n\nExpanded rows:\n" (format-value tree))
           (str "\n\nExpansion error:\n" (format-value error)))
         "\n\nEvents:\n" (format-value (second log)))))


(defn- rebuild-session
  "Replace the VM, the expander, and their media, attachments, and
   observers.  The value history is cleared with them: a closure in `*1`
   names a code segment the old VM held, which the new one does not.  The
   new expander holds only the standard forms."
  [state vm-type]
  (merge state
         (make-session vm-type (:output-stream state) (:extra-primitives state))
         {:vm-type vm-type
          :ingress-loss? false
          :last-value nil
          :last-value-2 nil
          :last-value-3 nil}))


(declare repl-state)


(defn handle-command
  "Answer one shell command.  Unsupported arguments are answered as text, not
   thrown, so a mistyped command cannot end a step."
  [state form]
  (let [[command & args] form]
    (case command
      vm (let [vm-type (first args)]
           (if (contains? vm-constructors vm-type)
             [(rebuild-session state vm-type)
              (str "Switched to " (get vm-labels vm-type) " (store cleared)")]
             [state (str "Error: Unknown Yin REPL VM type " (pr-str vm-type)
                         "; supported: " (pr-str (vec (keys vm-constructors))))]))
      lang (let [lang (first args)]
             (if (contains? lang-labels lang)
               [(assoc state :lang lang) (str "Switched to " (get lang-labels lang))]
               [state (str "Error: Unknown Yin REPL language " (pr-str lang)
                           "; supported: " (pr-str (vec (keys lang-labels))))]))
      compile [state (render-compile-output state (compile-command-ast state (first args)))]
      reset [(rebuild-session state (:vm-type state))
             (str (get vm-labels (:vm-type state)) " reset")]
      help [state help-text]
      repl-state [state (format-value (repl-state state))]
      quit [(assoc state :running? false) "Bye"]
      telemetry [state telemetry-text]
      [state (str "Error: Unknown Yin REPL command " (pr-str command))])))


(defn- eval-parsed
  [state trimmed parsed]
  (try
    (let [forms (:forms parsed)
          form (when (= 1 (count forms)) (first forms))]
      (cond
        (and form (command-form? form)) (handle-command state form)
        (and form (datom-stream? form)) (eval-program state (vec form))
        (and form (ast-map? form)) (eval-program state form)
        forms (if (= :clojure (:lang state))
                (eval-program state (compile-clojure-forms forms))
                (eval-program state (compile-source (:lang state) trimmed)))
        (= :clojure (:lang state)) [state (format-error (:error parsed))]
        :else (eval-program state (compile-source (:lang state) trimmed))))
    (catch #?(:cljd Object :clj Exception :cljs js/Error) error
      (let [[state' output-text] (drain-output state)]
        [state' (str output-text (format-error error))]))))


(defn eval-input
  "Evaluate one input line locally, returning `[state text]`.

   Input whose brackets do not balance is retained as `:pending-input` and
   produces no text.  Nothing here is asynchronous: the driver, not this
   namespace, decides when the next step happens."
  [state line]
  (let [pending (or (:pending-input state) "")
        combined (if (str/blank? pending)
                   (str/trim line)
                   (str pending "\n" (str/trim line)))]
    (if (pos? (bracket-balance combined))
      [(assoc state :pending-input combined) ""]
      (let [state' (assoc state :pending-input nil)
            parsed (try {:forms (read-forms combined)}
                        (catch #?(:cljd Object :clj Exception :cljs js/Error) error
                          {:error error}))]
        (eval-parsed state' combined parsed)))))


(defn repl-state
  "A serializable summary of shell state.

   The stream summaries read the ledger rather than asking a handle whether it
   is closed: v2 has no `closed?`, and an idle medium has no last operation.
   The program media are the composition's own, observed beside the VM, so
   the shell reports what it knows of them — the declared capacity, the gaps
   the session's observers counted, and whether a loss has already refused
   further evaluation.  `:macros` maps each macro the expander's store holds
   to its lambda's root address (yin.vm.macro.md §10.1)."
  [state]
  {:lang (:lang state)
   :macros (into (sorted-map-by #(compare (str %1) (str %2)))
                 (map (fn [[sym [root _]]] [sym root]))
                 (get-in state [:expander :ctx :store]))
   :vm {:type (:vm-type state)
        :halted? (vm/halted? (:vm state))
        :blocked? (vm/blocked? (:vm state))
        :in-stream {:capacity ingress-capacity
                    :gaps (ingress-gaps state)
                    :lost? (boolean (:ingress-loss? state))}}
   :running? (:running? state)
   :output {:cursor (:output-cursor state)
            :last-outcome (get-in state [:ledger :output] :untried)}
   :telemetry {:supported? false :note telemetry-text}
   :remote {:connected? false}})

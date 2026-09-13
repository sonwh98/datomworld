(ns yin.repl.v2.core
  "The local Yin shell for DaoStream v2: explicit state, synchronous steps.

   This namespace owns no socket, atom, promise, callback, clock, or namespace
   global.  It holds the one shell value a driver threads: the evaluator beside
   its separately-owned program medium — writer, descriptor, unary attacher,
   and attached stream observer — the language, the value history, the v2
   output medium with its cursor, and a per-medium ledger recording the last
   outcome that medium answered.  Every function takes a state and returns the
   next one.

   `yin.repl` is untouched and keeps running; this is a second implementation
   beside it."
  (:require #?(:cljd [clojure.edn :as edn]
               :cljs [cljs.reader :as reader])
            [clojure.string :as str]
            [dao.pretty :as pretty]
            [dao.stream.v2 :as stream]
            [dao.stream.v2.ringbuffer :as ring]
            [yang.clojure :as yang.clojure]
            [yang.php :as yang.php]
            [yang.python :as yang.python]
            [yin.vm.v2 :as vm]
            [yin.vm.v2.ast-walker :as ast-walker]
            [yin.vm.v2.engine :as engine]
            [yin.vm.v2.module :as module]
            [dao.stream.v2.observer :as observer]))


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
  "One entry, on `yin.vm.v2`. The other evaluators (`:semantic`, `:register`,
   `:stack`, `:space`) were deleted under
   `yin.vm.v2-consumers.implementation-plan.md`; `:ast-walker` is the only
   evaluator, here and in v1's `yin.repl`."
  {:ast-walker ast-walker/create-vm})


(def vm-labels {:ast-walker "ASTWalkerVM"})


(def lang-labels {:clojure "Clojure" :python "Python" :php "PHP"})


(def command-heads
  "Commands this shell answers.  `connect` and `disconnect` belong to the
   driver, which owns the RPC client; `telemetry` is answered only to say that
   the v2 slice does not have it."
  #{'vm 'lang 'compile 'reset 'help 'repl-state 'quit 'telemetry})


(def help-text
  (str "Commands:\n"
       "  (vm :ast-walker)\n"
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
  "Telemetry is not part of the DaoStream v2 REPL slice; run yin.repl for it")


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
  "Construct the shell's evaluator on `yin.vm.v2`.

   The shell is the composition that chooses the VM's transport: the
   ast-walker is handed `:make-stream` bound to the v2 ring buffer and the v2
   `stream` module registered in its registry.  No telemetry stream is
   installed, and the VM owns no program medium: `make-session` builds the
   medium, its attachment, the observer, and the VM together."
  [vm-type output-stream]
  (when-not (contains? vm-constructors vm-type)
    (throw (ex-info "Unknown Yin REPL VM type"
                    {:vm-type vm-type
                     :supported (vec (keys vm-constructors))})))
  ((get vm-constructors vm-type)
   {:primitives (make-repl-primitives output-stream)
    :modules (module/register-stream-module (module/default-registry))
    :make-stream make-ring-stream}))


(defn- make-program-attachment
  "Create the composition-owned program medium, the unary attachment entry
   bound to it for this medium's lifetime, and an observer attached through
   that entry.

   The resolver maps the descriptor's identity to the owner handle this
   composition created, which is host composition around the ring buffer's own
   attach mechanism: the observer itself sees only a descriptor and a unary
   capability, so no transport detail crosses into it."
  []
  (let [writer (:dao.stream/handle
                 (ring/create! {:dao.stream/type ring/transport-type
                                ring/capacity-key ingress-capacity}))
        descriptor (:dao.stream/descriptor (stream/descriptor writer))
        attach! (ring/make-attacher {(:dao.stream/identity descriptor) writer})]
    {:program-stream writer
     :observer (observer/attach attach! descriptor)}))


(defn- make-session
  "Build the program medium, its attachment, the observer, and the VM
   together.  Reset and VM selection call this, so the whole composition is
   rebuilt as one and the attachment capability is bound exactly once per
   medium lifetime."
  [vm-type output-stream]
  (merge {:vm (make-vm vm-type output-stream)}
         (make-program-attachment)))


(defn- run-vm
  "Run the VM through its protocol entry point, as a plain function so it can
   be handed to observer coordination on every host."
  [vm]
  (vm/run vm))


(defn create-state
  ([] (create-state {}))
  ([{:keys [lang output-cursor output-stream vm-type]
     :or {lang :clojure vm-type :ast-walker}}]
   (let [output-stream (or output-stream (make-output-medium!))
         output-cursor (or output-cursor (mint-cursor output-stream))
         {:keys [program-stream observer vm]} (make-session vm-type output-stream)]
     {:lang lang
      :vm-type vm-type
      :vm vm
      :program-stream program-stream
      :observer observer
      :output-stream output-stream
      :output-cursor output-cursor
      :ledger {:output :untried}
      :ingress-loss? false
      :last-value nil
      :last-value-2 nil
      :last-value-3 nil
      :pending-input nil
      :running? true})))


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


(defn- eval-ast
  [state ast]
  (if (:ingress-loss? state)
    [state (str "Error: " ingress-loss-text)]
    (let [state' (inject-last-value state)]
      (finalize-eval state state' (vm/eval (:vm state') ast)))))


(defn- eval-datoms
  "Evaluate one datom-literal program by appending it through the program
   medium's writer and then driving observer coordination, which loads and
   runs every observed batch.

   The observer recovers across a gap on its own, but the shell reads the gap
   count around the round: an increase means one or more batches were never
   run, so resuming as though execution were complete would report a result
   built on programs the VM never saw.  The loss is reported and the shell
   refuses further evaluation until `(reset)`."
  [state datoms]
  (if (:ingress-loss? state)
    [state (str "Error: " ingress-loss-text)]
    (let [state' (inject-last-value state)
          append (stream/append! (:program-stream state') (vec datoms))]
      (if-not (= :dao.stream/ok (:dao.stream/outcome append))
        [state (str "Error: datom batch not ingested: "
                    (name (:dao.stream/outcome append)))]
        (let [observer0 (:observer state')
              gaps-before (:ingress-gaps observer0 0)
              {:keys [observer] vm :consumer}
              (observer/run-on-stream {:observer observer0,
                                       :consumer (:vm state')}
                                      engine/ready-for-ingress?
                                      ast-walker/vm-load-program
                                      run-vm)
              state'' (assoc state' :observer observer :vm vm)]
          (cond
            (> (:ingress-gaps observer 0) gaps-before)
            [(assoc state'' :ingress-loss? true)
             (str "Error: " ingress-loss-text)]

            (vm/halted? vm)
            (finalize-eval state state'' vm)

            :else
            (throw
              (ex-info
                "Datom stream did not form a complete, runnable Yin VM program.
Hint: If you wanted to evaluate these datoms as data, use a quote: '[[...]]"
                {:root-id (:root-id (vm/index-datoms datoms))
                 :datom-count (count datoms)}))))))))


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
  [ast]
  (str "AST:\n" (format-value ast)
       "\n\nDatoms:\n" (format-value (vec (vm/ast->datoms ast)))))


(declare repl-state)


(defn handle-command
  "Answer one shell command.  Unsupported arguments are answered as text, not
   thrown, so a mistyped command cannot end a step."
  [state form]
  (let [[command & args] form]
    (case command
      vm (let [vm-type (first args)]
           (if (contains? vm-constructors vm-type)
             (let [{:keys [program-stream observer vm]}
                   (make-session vm-type (:output-stream state))]
               [(assoc state
                       :vm-type vm-type
                       :vm vm
                       :program-stream program-stream
                       :observer observer
                       :ingress-loss? false)
                (str "Switched to " (get vm-labels vm-type) " (store cleared)")])
             [state (str "Error: Unknown Yin REPL VM type " (pr-str vm-type)
                         "; supported: " (pr-str (vec (keys vm-constructors))))]))
      lang (let [lang (first args)]
             (if (contains? lang-labels lang)
               [(assoc state :lang lang) (str "Switched to " (get lang-labels lang))]
               [state (str "Error: Unknown Yin REPL language " (pr-str lang)
                           "; supported: " (pr-str (vec (keys lang-labels))))]))
      compile [state (render-compile-output (compile-command-ast state (first args)))]
      reset (let [{:keys [program-stream observer vm]}
                  (make-session (:vm-type state) (:output-stream state))]
              [(assoc state
                      :vm vm
                      :program-stream program-stream
                      :observer observer
                      :ingress-loss? false)
               (str (get vm-labels (:vm-type state)) " reset")])
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
        (and form (datom-stream? form)) (eval-datoms state form)
        (and form (ast-map? form)) (eval-ast state form)
        forms (if (= :clojure (:lang state))
                (eval-ast state (compile-clojure-forms forms))
                (eval-ast state (compile-source (:lang state) trimmed)))
        (= :clojure (:lang state)) [state (format-error (:error parsed))]
        :else (eval-ast state (compile-source (:lang state) trimmed))))
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
   The program medium is the composition's own, observed beside the VM, so the
   shell reports what it knows of it — the declared capacity, the gaps the
   observer counted, and whether a loss has already refused further
   evaluation."
  [state]
  {:lang (:lang state)
   :vm {:type (:vm-type state)
        :halted? (vm/halted? (:vm state))
        :blocked? (vm/blocked? (:vm state))
        :in-stream {:capacity ingress-capacity
                    :gaps (:ingress-gaps (:observer state) 0)
                    :lost? (boolean (:ingress-loss? state))}}
   :running? (:running? state)
   :output {:cursor (:output-cursor state)
            :last-outcome (get-in state [:ledger :output] :untried)}
   :telemetry {:supported? false :note telemetry-text}
   :remote {:connected? false}})

(ns yin.repl
  "The local Yin shell for DaoStream: explicit state, synchronous steps.

   This namespace owns no socket, atom, promise, callback, clock, or namespace
   global.  It holds the one shell value a driver threads: the evaluator beside
   its separately-owned program media — `program-in`, which the macro
   expander observes, and `program-out`, which the evaluator's own observer
   watches (`docs/design/yin.vm.macro.md` §10.1) — the language, the value
   history, the output medium with its cursor, and a per-medium ledger
   recording the last outcome that medium answered.  Every function takes a
   state and returns the next one."
  (:require #?(:cljd [clojure.edn :as edn]
               :cljs [cljs.reader :as reader])
            [clojure.string :as str]
            [dao.pretty :as pretty]
            [dao.stream :as stream]
            [dao.stream.ringbuffer :as ring]
            [yang.clojure :as yang.clojure]
            [yang.php :as yang.php]
            [yang.python :as yang.python]
            [yin.repl.ast-index :as ast-index]
            [yin.repl.dht :as repl.dht]
            [yin.repl.index :as index]
            [yin.repl.link :as link]
            [yin.repl.query :as query]
            [yin.repl.store :as store]
            [yin.vm :as vm]
            [yin.vm.ast-walker :as ast-walker]
            [yin.vm.debruijn-code :as dcode]
            [yin.vm.debruijn-linearize :as debruijn-linearize]
            [yin.vm.debruijn-register-code :as rcode]
            [yin.vm.debruijn-register-compile :as register-compile]
            [yin.vm.debruijn.register :as register]
            [yin.vm.debruijn.stack :as stack]
            [yin.vm.encoder :as encoder]
            [yin.vm.engine :as engine]
            [yin.vm.linearize :as linearize]
            [yin.vm.macro :as macro]
            [yin.vm.module :as module]
            [yin.vm.semantic :as semantic]
            [yin.vm.values :as values]
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
  "Maximum elements one output drain reads.  It equals the output capacity,
   so one drain reaches every element the medium still holds, the round's
   result included."
  output-capacity)


(def gap-budget
  "Maximum `gap` answers one output drain accepts.  A gap is not an element,
   so it is counted apart from `drain-budget`: a gap never costs the drain
   an element, and the drain stays total against a transport that could
   answer `gap` repeatedly."
  4096)


(def link-round-budget
  "How many serve-and-run rounds one evaluation drives while its VM waits
   on a link (yin.vm.linker.md sections 6.3 and 7.2).  A round serves the
   interpreter once and runs the VM once; a local content source answers
   within a few, so the budget is reached only by a link that stays
   `:pending`, which the shell then reports as its own non-blocking state
   instead of wedging on."
  40)


(def query-row-limit
  "The most rows one `dao.space.query/q` answer may carry: members of a
   relation or collection, one for a scalar or tuple.  An answer is a
   value the REPL prints whole, so a result past a thousand rows is a
   query to narrow rather than one to read; a `def` of ordinary size
   projects to tens or hundreds of facts, so every fact of a few
   definitions still fits."
  1000)


(def query-byte-limit
  "The most canonical CBOR bytes one `dao.space.query/q` answer may
   encode to, 256 KiB.  Rows bound the count, not the size: one row can
   hold a long string literal.  The byte bound keeps every answer on the
   bounded call-out a fixed size, so the medium's retention is bounded by
   `query-pair-capacity` times this, 16 MiB."
  262144)


(def query-pair-capacity
  "Declared capacity of each medium of the session's query call pair, in
   elements.  A VM makes one call at a time and the shell answers it in
   the same drive round, so a request and its response each occupy one
   element, read before the next is made.  A rollback moves the base
   VM's call-out cursor to the newest response
   (`yin.repl.query/discard-answers`), so no reader ever falls behind by
   more than one call."
  64)


(def query-serve-budget
  "Requests one query serve round answers.  A VM parks on one call at a
   time, so a round that spends it is answering requests the shell's VM
   did not make."
  64)


(def query-drive-budget
  "The most `dao.space.query/q` calls one drive answers: one evaluation,
   or one re-check of a pending run.  A query is answered in the round
   it is asked, so without a bound a program that calls `q` without end
   would hold its input round in the drive forever.  Each call re-reads
   the published index, so a thousand calls is already a long round; a
   program past the bound is stopped with `query-call-limit-text` as its
   error and its round rolls back like any failed round."
  1024)


(def query-call-limit-text
  (str "the evaluation called dao.space.query/q more than "
       query-drive-budget
       " times and was stopped (:yin.repl.query/call-limit)"))


(def vm-constructors
  "The evaluators on `yin.vm`: the ast-walker, the linear semantic VM
   (`docs/design/yin.vm.semantic.md`), and the nameless de Bruijn stack
   and register kernels, each starting with an empty image."
  {:ast-walker ast-walker/create-vm
   :semantic semantic/create-vm
   :stack #(stack/create-vm [] %)
   :register #(register/create-vm nil %)})


(def vm-labels
  {:ast-walker "ASTWalkerVM"
   :semantic "SemanticVM"
   :stack "DebruijnStackVM"
   :register "DebruijnRegisterVM"})


(defn- packet->named-datoms
  "The named `:yin/*` datoms of one expanded tree packet, the input the de
   Bruijn resolver reads."
  [packet]
  (vm/ast->datoms
    (vm/semantic-bytecode->ast (macro/packet->row-set packet))))


(defn- append-stack-image
  "Attach `image` after the stack image `vm` already holds and start at
   its first instruction.  A closure a previous input stored (a `def`)
   names a body pc in the earlier image, so that image is kept, not
   replaced.  `stack/attach-image` admits the incoming image alone under
   the current stamp (the expander and the lowerer are its fresh
   producers), relocates and appends it, records its offset-table row,
   and keeps `:hash` the canonical H of the whole `:segment`
   (yin.vm.linker.md section 7.3).  Attaching touches no register, so
   starting the input is this function's: `:pc` at the image's row, the
   registers a fresh run starts with.  An input whose image is already
   held reruns at that row."
  [vm image]
  (let [attached (stack/attach-image vm image vm/stack-contract)]
    (assoc attached
           :pc (stack/absolute-pc attached [(dcode/image-hash image) 0])
           :frames []
           :stack []
           :continuation []
           :halted? false
           :blocked? false
           :value nil)))


(defn- append-register-image
  "The register image counterpart of `append-stack-image`.  The image it
   runs in is the concatenation, whose later main bodies end in `:halt` as
   the first does, and `:hash` is the canonical R of that concatenation.
   Starting the input sizes the register file for the main body at the
   image's row."
  [vm image]
  (let [attached (register/attach-image vm image vm/register-contract)
        pc (register/absolute-pc attached [(rcode/register-hash image) 0])
        body (some #(when (= pc (:start %)) %)
                   (:bodies (:segment attached)))]
    (assoc attached
           :pc pc
           :frames []
           :registers (vec (repeat (or (:registers body) 0) nil))
           :continuation []
           :halted? false
           :blocked? false
           :value nil)))


(def program-loaders
  "The loader the evaluator observer hands each `program-out` value, per
   evaluator.  Every value there is one expanded canonical tree packet
   `[root rows]` (yin.vm.macro.md §2.4); each loader takes its row set — the
   ast-walker through `vm-load-rows`, the semantic VM through
   `linearize/rows-loader` over `semantic/load-vector`, and the de Bruijn
   kernels through their lowerings to a stack image (H) or a register
   image (R).  No evaluator learns that an expander ran."
  {:ast-walker (fn [vm packet]
                 (ast-walker/vm-load-rows vm
                                          (macro/packet->row-set packet)
                                          vm/ast-contract))
   ;; every loader here is the trusted fresh-producer path: the only
   ;; producer of `program-out` is the expander, so each supplies the
   ;; current stamp itself
   :semantic (let [load-rows (linearize/rows-loader semantic/load-vector)]
               (fn [vm packet]
                 (load-rows vm
                            (macro/packet->row-set packet)
                            vm/ast-contract)))
   :stack (fn [vm packet]
            (append-stack-image
              vm
              (:image (debruijn-linearize/adapt
                        (packet->named-datoms packet)))))
   :register (fn [vm packet]
               (append-register-image
                 vm
                 (:image (register-compile/adapt
                           (packet->named-datoms packet)))))})


(def lang-labels {:clojure "Clojure" :python "Python" :php "PHP"})


(def command-heads
  "Commands this shell answers.  `connect` and `disconnect` belong to the
   driver, which owns the RPC client; `telemetry` is answered only to say that
   this REPL slice does not have it."
  #{'vm 'lang 'compile 'reset 'help 'repl-state 'quit 'telemetry 'abandon})


(def help-text
  (str "Commands:\n"
       "  (vm :ast-walker | :semantic | :stack | :register)\n"
       "  (lang :clojure | :python | :php)\n"
       "  (compile expr)\n"
       "  (reset)\n"
       "  (connect \"daostream:ws://host:port\")\n"
       "  (disconnect)\n"
       "  (abandon)  - give up a (require ...) that is still pending\n"
       "  (repl-state)\n"
       "  (help)\n"
       "  (quit)\n"
       "  *1, *2, *3  - last, second-to-last, and third-to-last evaluated"
       " values"))


(def telemetry-text
  (str "(telemetry) is not wired into this shell: the telemetry emit path is "
       "built (yin.vm.telemetry, opt-in via the :telemetry {:stream ...} "
       "construction option — yin.vm.telemetry.implementation-plan.md), but "
       "composing a sink into this shell is not, so the command reports "
       "rather than evaluates"))


(def ingress-loss-text
  (str "One or more program batches were never observed from the program "
       "medium; (reset) is required before more evaluation"))


(def result-loss-text
  "the evaluation halted but its result never reached the output medium")


;; =============================================================================
;; Rendering
;; =============================================================================

(defn- host-fn-namer
  "A function from a host function to the name `vm` binds it under — a
   primitive's name, or `module/export` for a host module's export — or nil.
   Lookup is by identity against the VM's own tables, never host
   reflection, so every host names a function the same way."
  [vm]
  (let [primitives (:primitives vm)
        canonical (:primitive-canonical-names vm vm/primitive-canonical-names)
        exports (for [[module-name entry] (module/module-entries (:modules vm))
                      [sym f] (if (contains? entry :bindings)
                                (:bindings entry)
                                (:slice entry))
                      :when (fn? f)]
                  [f (symbol (str module-name) (str sym))])]
    (fn [f]
      (let [n (when primitives (vm/name-of primitives canonical f))]
        (if (symbol? n)
          n
          (some (fn [[g export-name]] (when (identical? f g) export-name))
                exports))))))


(defn- typed-key-compare
  [a b]
  (cond (= a b) 0
        (= :type a) -1
        (= :type b) 1
        :else (compare (pr-str a) (pr-str b))))


(defn- typed-map
  "A `:type`-tagged value — `{:type :closure ...}`, `{:type :host-fn ...}` —
   prints `:type` first, then its other keys in printed order. Map
   iteration order is host-dependent (ClojureDart's small maps don't keep
   insertion order), so a fixed order is what makes the rendered text the
   same on every host."
  [entries]
  (into (sorted-map-by typed-key-compare) entries))


(defn- host-fn-marker
  "A host function renders as `{:type :host-fn :name '<name>}`, shaped like
   a data closure's `{:type :closure ...}` so the two read side by side and
   `:type` tells them apart. The host object itself never prints: its form
   differs per host and leaks class names and addresses."
  [namer f]
  (let [n (when namer (namer f))]
    (typed-map (cond-> [[:type :host-fn]] n (conj [:name (list 'quote n)])))))


(defn- rendered-text
  [x]
  (str/trimr (pretty/pp-str x)))


(deftype DisplayKey
  [text rank]
  #?@(:cljd [cljd.core/IPrint (-print [_ sink] (.write sink text))]
      :cljs [IPrintWithWriter (-pr-writer [_ writer _opts] (-write writer text))]))


#?(:cljd nil
   :clj (defmethod print-method DisplayKey
          [k ^java.io.Writer w]
          (.write w ^String (.-text ^DisplayKey k))))


(defn- display-order
  [[type-a key-a value-a] [type-b key-b value-b]]
  (let [c (compare type-a type-b)]
    (if-not (zero? c)
      c
      (let [c (compare key-a key-b)]
        (if (zero? c) (compare value-a value-b) c)))))


(defn- display-map
  "Rendering can make distinct keys coincide — two nameless host functions
   become one marker, a symbol and a quoted-symbol list print alike — and a
   map built on the rendered keys would drop entries. Here each key becomes
   a `DisplayKey` that prints its rendered text and orders by its rank:
   `:type` first, then key text, then value text."
  [entries]
  (let [rows (sort display-order
                   (map (fn [[k v]]
                          [(if (= :type k) 0 1) (rendered-text k) (rendered-text v) v])
                        entries))]
    (into (sorted-map-by #(compare (.-rank ^DisplayKey %1)
                                   (.-rank ^DisplayKey %2)))
          (map-indexed (fn [i [_ text _ v]] [(DisplayKey. text i) v]) rows))))


(defn- render-map
  [typed? entries]
  (let [ks (map first entries)]
    (cond (and (seq entries)
               (not (and (apply distinct? ks)
                         (apply distinct? (map pr-str ks)))))
          (display-map entries)
          typed? (typed-map entries)
          :else (into {} entries))))


(defn- quote-symbols
  "`quoted?` is true inside a `(quote ...)` form: its symbols print as they
   are, but host functions at any depth still become the marker."
  [namer quoted? x]
  (cond (symbol? x) (if quoted? x (list 'quote x))
        (fn? x) (host-fn-marker namer x)
        ;; a closure or continuation is opaque: its kind, never its payload,
        ;; a captured value or its owner tag
        (values/host-typed? x)
        (typed-map [[:type (if (values/closure? x) :closure :continuation)]])
        (vector? x) (mapv #(quote-symbols namer quoted? %) x)
        (map? x)
        (render-map
          ;; not `contains?`: on a sorted map with non-keyword keys it throws
          (some #(= :type (key %)) x)
          (map (fn [[k v]]
                 [(quote-symbols namer quoted? k)
                  (quote-symbols namer quoted? v)])
               x))
        (or (list? x) (seq? x))
        (if (= 'quote (first x))
          (apply list (map #(quote-symbols namer true %) x))
          (map #(quote-symbols namer quoted? %) x))
        :else x))


(defn format-value
  "Render `value` for the REPL. `namer` (see `host-fn-namer`) names host
   functions; without one they render nameless."
  ([value] (format-value value nil))
  ([value namer]
   (rendered-text (quote-symbols namer false value))))


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


(defn- emit-result!
  "Answer a halted evaluation's value on the output medium, after the
   output it printed, so the shell reads results from the stream exactly as
   it reads prints.  The token carries `round`, so the shell accepts only
   this round's result.  Returns the append outcome, or `:untried` when
   there is no medium."
  [output-stream round value]
  (if output-stream
    (:dao.stream/outcome
      (stream/append! output-stream
                      {:type :repl/result :round round :value value}))
    :untried))


(defn- make-repl-primitives
  [output-stream]
  (merge vm/primitives
         {'print (fn [& args]
                   (emit-output! output-stream :print (print-text args)))
          'println (fn [& args]
                     (emit-output! output-stream :println
                                   (str (print-text args) "\n")))
          'prn (fn [& args] (emit-output! output-stream :prn (prn-text args)))
          'ast->datoms vm/ast->datoms
          'datoms->ast vm/datoms->ast}))


(defn- chunk-text
  [value]
  (if (map? value) (str (:text value)) (str value)))


(defn- result-chunk?
  [value]
  (and (map? value) (= :repl/result (:type value))))


(defn- ledger
  [state medium outcome]
  (assoc-in state [:ledger medium] outcome))


(defn drain-output
  "Drain the output medium into `[state text results]`, total over every
   `next` outcome.  `results` holds, in order, every `:repl/result` token
   read, unchanged; every other element is printed text.

   `ok` advances to the exact successor the handle returned and spends one
   of `drain-budget` element reads.  `gap` prints a loss notice, resumes at
   the recovery cursor, and spends one of `gap-budget` instead, so a gap
   never starves the drain of an element it must still reach.  `end`,
   `cursor-mismatch`, `invalid-cursor`, and `transport-error` print a
   notice, leave the cursor unchanged, and end the drain.  Whatever ended the
   drain is recorded in the ledger, so `repl-state` never has to ask a stream
   how it is."
  [state]
  (let [output (:output-stream state)
        cursor (:output-cursor state)]
    (cond
      (nil? output) [(ledger state :output :untried) "" []]
      (nil? cursor) [(ledger state :output :dao.stream/invalid-cursor)
                     ";; output unreadable: no cursor was minted\n"
                     []]
      :else
      (loop [remaining drain-budget
             gaps gap-budget
             cursor cursor
             chunks []
             results []]
        (if (or (zero? remaining) (zero? gaps))
          [(-> state
               (assoc :output-cursor cursor)
               (ledger :output :dao.stream/blocked))
           (apply str chunks)
           results]
          (let [result (stream/next output cursor)
                outcome (:dao.stream/outcome result)
                value (:dao.stream/value result)]
            (case outcome
              :dao.stream/ok
              (if (result-chunk? value)
                (recur (dec remaining)
                       gaps
                       (:dao.stream/cursor result)
                       chunks
                       (conj results value))
                (recur (dec remaining)
                       gaps
                       (:dao.stream/cursor result)
                       (conj chunks (chunk-text value))
                       results))

              :dao.stream/gap
              (recur remaining
                     (dec gaps)
                     (:dao.stream/cursor result)
                     (conj chunks
                           (str ";; output lost: resumed at the recovery"
                                " cursor\n"))
                     results)

              :dao.stream/blocked
              [(-> state
                   (assoc :output-cursor cursor)
                   (ledger :output outcome))
               (apply str chunks)
               results]

              [(-> state
                   (assoc :output-cursor cursor)
                   (ledger :output outcome))
               (apply str (conj chunks (str ";; output " (name outcome) "\n")))
               results])))))))


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
  "The `:make-stream` the shell hands the VM: one ring buffer per call.  A
   nil capacity is the VM's default, not an unbounded stream; dao.stream has
   no unbounded mode."
  [capacity]
  (ring/create! {:dao.stream/type ring/transport-type
                 ring/capacity-key (or capacity vm/default-stream-capacity)}))


(defn make-vm
  "Construct the shell's evaluator on `yin.vm`.

   The shell is the composition that chooses the VM's transport: the
   evaluator is handed `:make-stream` bound to the ring buffer and the
   `stream` module registered in its registry.  No telemetry stream is
   installed, and the VM owns no program medium: `make-session` builds the
   medium, its attachment, the observer, and the VM together.

   `extra-primitives` is the host-supplied map merged over the REPL's own
   primitives, so an embedding host's functions win a name collision.

   `link-pair` is the link pair `(require ...)` lowers to
   (yin.vm.linker.md section 6.1), held in the VM's private `:resources`
   table; `make-session` composes one per VM, and the install children
   the VM starts inherit it.

   `query-pair` is the session's query call pair
   (`yin.repl.query/make-pair`), supplied as the VM's FFI pair with its
   call-out cursor; with it the registry carries the query bridge, so
   `(require 'dao.space.query)` activates `dao.space.query/q`."
  ([vm-type output-stream] (make-vm vm-type output-stream nil nil nil))
  ([vm-type output-stream extra-primitives]
   (make-vm vm-type output-stream extra-primitives nil nil))
  ([vm-type output-stream extra-primitives link-pair]
   (make-vm vm-type output-stream extra-primitives link-pair nil))
  ([vm-type output-stream extra-primitives link-pair query-pair]
   (when-not (contains? vm-constructors vm-type)
     (throw (ex-info "Unknown Yin REPL VM type"
                     {:vm-type vm-type
                      :supported (vec (keys vm-constructors))})))
   ((get vm-constructors vm-type)
    (cond-> {:primitives (merge (make-repl-primitives output-stream)
                                extra-primitives)
             :modules (cond-> (module/register-stream-module
                                (module/default-registry))
                        query-pair query/register)
             :make-stream make-ring-stream
             ;; the task's capability secret (yin.vm.linker.md 7.3, r10):
             ;; the REPL is the composition, so it mints one from its own
             ;; random source, and a fresh one for every install child the
             ;; task starts
             :capability-secret (str (random-uuid))
             :secret-source (fn [_origin] (str (random-uuid)))}
      link-pair (assoc :link-request (:requests link-pair)
                       :link-response (:responses link-pair))
      query-pair (assoc :call-in (:call-in query-pair)
                        :call-out (:call-out query-pair)
                        :call-out-cursor (:out-cursor query-pair))))))


(defn- make-runner
  "The run step the evaluator observer hands each loaded program: run the
   VM through its protocol entry point, as a plain function so it can be
   handed to observer coordination on every host, and answer a halted
   run's value on `output-stream` under `round`.  The shell reads the value
   from that stream, never from the VM record; the append outcome rides
   back on the VM as `::result-append`, so a refused append is reported
   rather than silently lost."
  [output-stream round]
  (fn [vm]
    (let [vm' (vm/run vm)]
      (if (vm/halted? vm')
        (assoc vm'
               ::result-append
               (emit-result! output-stream round (vm/value vm')))
        vm'))))


(defn- make-attachment
  "Create one composition-owned medium of `capacity` elements, the unary
   attachment entry bound to it for this medium's lifetime, and an observer
   attached through that entry.  `:attach` attaches a further, independently
   advanced observer through the same entry.

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
     :observer (observer/attach attach! descriptor)
     :attach #(observer/attach attach! descriptor)}))


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
   which loads each packet and runs it.  The code indexer's observer
   (`yin.repl.index`) is a peer attached to `program-out` beside it; the
   round advances each, and neither calls the other.  It records
   `shell-token` as provenance and publishes into `index-store`.  The AST
   indexer (`yin.repl.ast-index`), holding the session's `$ast` and `$occ`
   relations, is a third such peer.

   The link pair is the session's (`yin.repl.link/make-pair`): the VM's
   half lives in its private `:resources`, the interpreter's half beside
   it under `:link-pair`.  The content pair and the name environment the
   interpreter serves from are the shell's (`:link-source`), and outlive
   a session rebuild.

   The query call pair is the session's too (`yin.repl.query/make-pair`):
   the VM's half is its FFI pair, the interpreter's half with its request
   cursor sits under `:query-pair`, and the interpreter answers from the
   session's `:indexer`, and `:ast-indexer` for `$ast` and `$occ`, as they
   stand when it serves.

   A durable store's HEAD write travels with the store handle
   (`:head-fn`, yin.repl.store): every session the store outlives
   publishes through the same one."
  [vm-type output-stream extra-primitives shell-token index-store]
  (let [pair (link/make-pair)
        query-pair (query/make-pair query-pair-capacity)
        vm (make-vm vm-type output-stream extra-primitives pair query-pair)
        program-in (make-attachment ingress-capacity)
        program-out (make-attachment ingress-capacity)]
    {:vm vm
     :load-program (get program-loaders vm-type)
     :program-stream (:stream program-in)
     :program-identity (:identity program-in)
     :observer (:observer program-in)
     :expander (make-expander (:identity program-in) (:stream program-out))
     :row-stream (:stream program-out)
     :row-observer (:observer program-out)
     :indexer (index/make-indexer {:observer ((:attach program-out))
                                   :session-token shell-token
                                   :content-store index-store
                                   :after-publish (:head-fn index-store)})
     :ast-indexer (ast-index/make-indexer {:observer ((:attach program-out))})
     :link-pair pair
     :query-pair (dissoc query-pair :out-cursor)}))


(defn- checked-link-policy
  "Validate the `:link-policy` creation option, failing closed at
   assembly (yin.repl.link-policy.md section 3): `:manual` is the
   default and today's behavior, a function `(fn [view])` is the
   embedder's rule, and `:lease` is reserved for the phase-2 deadline --
   refused, not implemented yet.  Anything else is refused with the
   supported values named."
  [policy]
  (cond
    (or (nil? policy) (= :manual policy)) :manual
    (fn? policy) policy
    :else
    (throw
      (ex-info
        (if (= :lease policy)
          "Yin REPL :link-policy :lease is not implemented yet"
          "Unknown Yin REPL :link-policy")
        {:link-policy policy
         :supported [:manual :fn]}))))


(defn create-state
  "Create the shell value.  `:primitives` is a host-supplied map merged over
   the REPL primitives; it is kept as `:extra-primitives` so every session
   rebuild — `(reset)`, `(vm …)` — installs it again.

   `content-store` (a `dao.jing` byte-store handle), `content-client` (a
   `dao.stream.rpc` client state on a connection whose far end serves
   content) and `name-env` (module name -> manifest address) compose the
   content pair and the name environment the linker interpreter serves
   `(require ...)` from (yin.repl.link/composition); with neither content
   source, a require stays `:pending` and says so.

   `:link-policy` is the session's pending-link rule
   (yin.repl.link-policy.md), kept beside `:link-source` so `(reset)` and
   `(vm ...)` preserve it: `:manual`, the default, ends a pending run
   only at `(abandon)`; a function `(fn [view])` is consulted when a
   require parks and after each re-check that leaves the run pending,
   answering `:keep`, `:abandon` or `{:abandon reason}`; `:lease` is
   reserved for phase 2 and refused, as is anything else, at assembly.

   `index-store` is the `dao.jing` byte store the code indexer publishes
   its covered indexes into (docs/design/yin.repl.dao.space-index.md); it
   outlives a session rebuild, and defaults to a fresh in-memory store.

   `index-store-spec` is the startup selection of that store
   (yin.repl.store): `:mem` — the default, today's behaviour — or
   `{:type :file :dir dir}`, the durable content log at
   `<dir>/content.jing`, opened under an exclusive directory lock with
   its HEAD read and the snapshot it names validated — a corrupt HEAD
   or snapshot refuses construction, and a second owner of the directory
   is refused naming it.  The spec is resolved and the store opened once
   here, never switched at runtime, and supplying it together with
   `index-store` (a handle injected directly) is refused.

   `:index-recovery` carries what the durable open recovered —
   `{:manifest <address or nil> :datoms <the validated snapshot or nil>}`
   — and the indexer is rehydrated from it here, before any evaluation
   is admitted (`yin.repl.index/rehydrate`): `q` answers the previous
   run's facts, which keep their own session tokens, while this process
   mints a new token for its own.  In durable mode `(reset)` and `(vm …)`
   carry the index across the rebuild; with the memory store they start
   an empty one, as they always have.

   `{:type :dht :dir dir ...}` is the DHT store (yin.repl.dht): that
   durable directory store with a `dao.jing.dht` node composed over it.
   Its step-owner value is `:dht`, advanced only by `yin.repl.dht/step`
   from the host's ticker; a round publishes against the local store and
   never waits on it."
  ([] (create-state {}))
  ([{:keys [lang output-cursor output-stream vm-type primitives
            content-store content-client name-env link-policy index-store
            index-store-spec]
     :or {lang :clojure vm-type :semantic}}]
   (let [output-stream (or output-stream (make-output-medium!))
         output-cursor (or output-cursor (mint-cursor output-stream))
         shell-token (str (random-uuid))
         index-store-spec (cond
                            (and index-store index-store-spec)
                            (throw (ex-info
                                     (str "yin.repl/create-state takes one "
                                          "index store: :index-store-spec "
                                          "(mem or a file directory) or "
                                          ":index-store (a dao.jing handle), "
                                          "not both")
                                     {:index-store-spec index-store-spec}))

                            index-store nil

                            :else (store/checked-spec index-store-spec))
         index-store (or index-store
                         (if (= :dht (:type index-store-spec))
                           (repl.dht/open index-store-spec)
                           (store/open index-store-spec)))
         link-source (link/composition
                       {:name-env name-env
                        :content-store content-store
                        :content-client content-client})]
     (merge
       (update (make-session vm-type output-stream primitives shell-token
                             index-store)
               :indexer index/rehydrate (:recovery index-store))
       {:lang lang
        :vm-type vm-type
        :extra-primitives primitives
        :output-stream output-stream
        :output-cursor output-cursor
        :ledger {:output :untried}
        :shell-token shell-token
        :index-store (repl.dht/without-runner index-store)
        :index-store-spec index-store-spec
        :dht (:dht index-store)
        :index-recovery (or (:recovery index-store)
                            {:manifest nil :datoms nil})
        :round 0
        :ingress-loss? false
        :last-value nil
        :last-value-2 nil
        :last-value-3 nil
        :pending-input nil
        :link-source link-source
        :link-policy (checked-link-policy link-policy)
        :pending-run nil
        :running? true}))))


;; =============================================================================
;; Reading and classifying input
;; =============================================================================

#?(:cljd
   (defn- escape-percent-tokens
     "ClojureDart's reader reads a bare `%` token outside `#(...)` as an
      anonymous argument and drops the `%` (`%` becomes a symbol with an
      empty name), where the JVM and JavaScript readers read the plain
      symbol `%`, `%1`, `%&`. Answers `[text tokens]`: `input` with every
      such token replaced by `prefix` followed by its index in `tokens`, one
      index per distinct token, so the reader's duplicate refusals still
      apply. The scan walks form starts as the reader does (every `#`
      dispatch and prefix macro included) and skips strings, regexes,
      comments, character literals and `#(...)` bodies."
     [input prefix]
     (let [n (count input)
           at #(subs input % (inc %))
           ends-token #{" " "\t" "\n" "\r" "\f" "," "\"" ";" "@" "^" "`" "~"
                        "(" ")" "[" "]" "{" "}" "\\"}
           token-end (fn [i] (if (or (>= i n) (ends-token (at i))) i (recur (inc i))))
           string-end (fn [i]
                        (cond (>= i n) n
                              (= "\\" (at i)) (recur (+ i 2))
                              (= "\"" (at i)) (inc i)
                              :else (recur (inc i))))
           line-end (fn [i] (if (or (>= i n) (= "\n" (at i))) i (recur (inc i))))]
       (loop [i 0, start 0, parts [], tokens {}, frames ()]
         (if (>= i n)
           [(apply str (conj parts (subs input start)))
            (mapv key (sort-by val tokens))]
           (let [c (at i)
                 d (when (< (inc i) n) (at (inc i)))
                 [i' frames' token]
                 (cond
                   (#{" " "\t" "\n" "\r" "\f" ","} c) [(inc i) frames]
                   (= "\"" c) [(string-end (inc i)) frames]
                   (= ";" c) [(line-end i) frames]
                   (= "\\" c) [(token-end (min n (+ i 2))) frames]
                   (#{"'" "@" "^" "`" "~"} c) [(inc i) frames]
                   (#{"(" "[" "{"} c) [(inc i) (conj frames :coll)]
                   (#{")" "]" "}"} c) [(inc i) (rest frames)]
                   (= "#" c)
                   (case d
                     "(" [(+ i 2) (conj frames :anon)]
                     "{" [(+ i 2) (conj frames :coll)]
                     "\"" [(string-end (+ i 2)) frames]
                     ("_" "'" "=" "^") [(+ i 2) frames]
                     "?" [(if (and (< (+ i 2) n) (= "@" (at (+ i 2)))) (+ i 3) (+ i 2))
                          frames]
                     "!" [(line-end i) frames]
                     ("#" ":") [(token-end (+ i 2)) frames]
                     [(token-end (inc i)) frames])
                   (and (= "%" c) (not-any? #{:anon} frames))
                   (let [end (token-end (inc i))] [end frames (subs input i end)])
                   :else [(token-end i) frames])]
             (if token
               (let [index (get tokens token (count tokens))]
                 (recur i' i'
                        (conj parts (subs input start i) (str prefix index))
                        (assoc tokens token index)
                        frames'))
               (recur i' start parts tokens frames'))))))))


#?(:cljd
   (defn- restore-percent-tokens
     "`form` with each placeholder symbol of `smap` (`{placeholder token}`)
      replaced by its token, in values and in metadata alike. A qualified
      token (`%/foo`) is restored whole; an unqualified one keeps the
      namespace its placeholder gained from the reader (syntax quote,
      `#:ns{}`), as the JVM reader qualifies `%` itself. Only a branch that
      holds a placeholder is rebuilt; everything else is returned
      identical. A rebuilt branch keeps its collection type and order, and
      takes exactly `form`'s own metadata (restored), nil included: on
      ClojureDart a list built by `(apply list ...)` carries
      `{:line ... :tag <Type>}` metadata that dao.jing.cbor refuses."
     [smap form]
     (let [again #(restore-percent-tokens smap %)
           same? #(every? true? (map identical? %1 %2))
           m (meta form)
           m' (when m (again m))
           v (cond
               (symbol? form) (if-let [token (get smap (symbol (name form)))]
                                (if (namespace token)
                                  token
                                  (symbol (namespace form) (name token)))
                                form)
               (seq? form) (let [xs (map again form)]
                             (if (same? xs form) form (apply list xs)))
               (vector? form) (let [xs (mapv again form)]
                                (if (same? xs form) form xs))
               (map? form) (let [kvs (mapv (fn [[k x]] [(again k) (again x)]) form)]
                             (if (same? (mapcat identity kvs) (mapcat identity form))
                               form
                               (into (empty form) kvs)))
               (set? form) (let [xs (mapv again form)]
                             (if (same? xs form) form (into (empty form) xs)))
               :else form)]
       (if (and (identical? v form) (identical? m m'))
         form
         (with-meta v m')))))


#?(:cljd
   (defn- read-percent-forms
     "`input`'s forms read as the JVM and JavaScript readers read a bare `%`
      token (see `escape-percent-tokens`). A reader refusal that names a
      placeholder is rethrown naming the token instead."
     [input]
     (let [read #(edn/read-string (str "[" % "]"))
           prefix (loop [p "yin_repl_percent_"]
                    (if (str/includes? input p) (recur (str p "_")) p))
           [text tokens] (escape-percent-tokens input prefix)]
       (if (empty? tokens)
         (read input)
         (let [forms (try (read text)
                          (catch Object error
                            (let [message (or (ex-message error) (str error))]
                              (if (str/includes? message prefix)
                                (throw (ex-info
                                         (reduce (fn [m i]
                                                   (str/replace m (str prefix i)
                                                                (nth tokens i)))
                                                 message
                                                 (reverse (range (count tokens))))
                                         {}))
                                (throw error)))))]
           (restore-percent-tokens
             (zipmap (map #(symbol (str prefix %)) (range (count tokens)))
                     (map symbol tokens))
             forms))))))


(defn read-forms
  "The forms of one input line, as a vector, read by the host reader.
   Public so the cross-host reader tests can compare the forms, metadata
   included, that each host reads."
  [input]
  #?(:cljd (if (str/includes? input "%")
             (read-percent-forms input)
             (edn/read-string (str "[" input "]")))
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


(defn- round-id
  "The identity of the state's current round: the shell's token, minted
   once per shell value, with its round count.  A medium the caller
   supplies may already hold results from another shell or an earlier
   round; none of them carries this identity."
  [state]
  [(:shell-token state) (:round state)])


(defn- finalize-eval
  "Drain the round's output and its result from the output medium.  Only a
   result stamped with this round's identity is this round's value; the
   evaluator answers once per halted program, after its prints.  A missing
   or foreign-round result is loss, reported with the append outcome the
   runner recorded."
  [state state' vm']
  (let [append (::result-append vm')
        [state'' output-text results]
        (drain-output (assoc state' :vm (dissoc vm' ::result-append)))
        round (round-id state')
        current (filterv #(= round (:round %)) results)]
    (if (seq current)
      (let [value (:value (peek current))]
        [(record-last-value state state'' value)
         (str output-text (format-value value (host-fn-namer (:vm state''))))])
      [state''
       (str output-text "Error: " result-loss-text
            (when (and append (not= :dao.stream/ok append))
              (str " (append: " (name append) ")")))])))


(defn- drain-observer
  "Advance one observer past every batch still on its medium, keeping any
   gap it recovers across.  A nil observer — a session without that medium —
   stays nil."
  [observer]
  (when observer
    (loop [observer observer]
      (let [{:keys [status] observer' :observer}
            (observer/observe-next observer)]
        (if (#{:ok :gap} status) (recur observer') observer')))))


(defn- ingress-gaps
  "Gaps the evaluation path's observers counted.  The code indexer's are
   its own: an index loss is reported without refusing evaluation."
  [state]
  (+ (:ingress-gaps (:observer state) 0)
     (:ingress-gaps (:row-observer state) 0)))


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
                 (if (and observer
                          carried
                          (= (:stream carried) (:stream observer)))
                   carried
                   observer))
        drained (assoc state
                       :observer (drain-observer (resume (:observer state)))
                       :row-observer (drain-observer
                                       (resume (:row-observer state)))
                       :indexer (index/skip (:indexer state))
                       :ast-indexer (ast-index/skip (:ast-indexer state)))
        [state' output-text]
        (drain-output
          (cond-> drained
            (> (ingress-gaps drained) (ingress-gaps state))
            (assoc :ingress-loss? true)))]
    [state' (str output-text (format-error error))]))


(defn- run-index-stage
  "Drive the code indexer's observer over `program-out`: it commits each
   forwarded packet as one transaction and publishes the covered indexes.
   It runs before the evaluator, so a program is indexed whether its VM
   returns, parks, or raises; the two observers meet only at the medium."
  [state]
  (update state :indexer index/step (:round state)))


(defn- run-ast-index-stage
  "Drive the AST indexer's observer over `program-out`: it adds each
   forwarded packet's rows and occurrences to the session's `$ast` and
   `$occ` relations.  It runs before the evaluator for the same reason the
   code indexer does, and neither indexer reads the other."
  [state]
  (update state :ast-indexer ast-index/step))


(defn- with-index-notice
  "Append what the round's index steps lost, if anything, to its result
   text, one line per notice in the order given; the evaluation's own
   answer is unchanged."
  [[state text] notices]
  (if-let [notice (some->> (remove nil? notices) seq (str/join "\n"))]
    [state (if (str/blank? text) notice (str text "\n" notice))]
    [state text]))


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
   expanded tree packet into the VM, runs it, and answers its value on the
   output medium.  The two stages meet only at `program-out`; neither calls
   the other."
  [{:keys [load-program output-stream] :as state}]
  (let [{:keys [observer] vm :consumer}
        (observer/run-on-stream {:observer (:row-observer state),
                                 :consumer (:vm state)}
                                engine/ready-for-ingress?
                                load-program
                                (make-runner output-stream (round-id state)))]
    (assoc state :row-observer observer :vm vm)))


(defn- format-expansion-errors
  [errors]
  (str/join "\n" (map #(str "Error: Macro expansion failed: " (format-value %))
                      errors)))


(defn- link-waiting?
  "True while `vm` parks on a link wait: the two link states `require`
   parks in, or the install it waits on (yin.vm.linker.md section 7.2).
   These are the reasons the shell's interpreter can move, so they are
   the ones a round drives before reporting the VM wedged."
  [vm]
  (boolean (some #(contains? #{:link-request :link-response :install}
                             (:reason %))
                 (:wait-set vm))))


(defn- carry-link-identity
  "The round's base VM with the link identity `used` has already minted
   carried forward: link ids `[origin counter]` and install-child origin
   tags are minted once and never reused on a pair (yin.vm.linker.md
   section 7.2), and the shell's rollback to the round's base -- whose
   counters never advanced -- must not let a later require or install
   mint an identity the pair has already answered."
  [base used]
  (let [n (max (or (:id-counter base) 0) (or (:id-counter used) 0))
        o (max (or (:origins base) 0) (or (:origins used) 0))]
    (cond-> base
      (pos? n) (assoc :id-counter n)
      (pos? o) (assoc :origins o))))


(defn- link-raise
  "The raise of a resumed link wait, carrying the identity the parked
   evaluation has minted so far -- the counters of the VM the raise
   interrupted, whose every earlier run is in them -- so a rollback
   cannot reuse it.  It carries the query interpreter's pair too: the
   requests it has answered stay answered when the shell rolls back."
  [error vm state]
  (ex-info (or (ex-message error) "Link failed")
           (assoc (or (ex-data error) {})
                  ::link-counter (:id-counter vm)
                  ::link-origins (:origins vm)
                  ::query-pair (:query-pair state))))


(defn- drive-links
  "Serve the linker interpreter and run the parked VM alternately, at most
   `link-round-budget` rounds, while `vm` waits on a link.  A round that
   moves nothing ends the drive: a link whose content source cannot
   answer will not answer by waiting longer.  Returns
   `[state vm pending progress?]`, `pending` the links the interpreter
   reported `:pending`, and `progress?` true when a round moved
   something -- advanced a serve cursor, delivered a response or
   completed an install -- the fact the pending run's `:checks` resets
   on (yin.repl.link-policy.md section 3.1).  A refused or lost link
   raises as the VM's own, carrying the interrupted VM's minted
   identity.

   A VM waiting on a `dao.space.query/q` call is driven the same way: the
   query interpreter (`yin.repl.query/serve`) answers from the session's
   indexer and the VM runs on.  Only a round the VM spends waiting on a
   link counts against `link-round-budget`; a query is answered in the
   round it is asked, so its rounds are bounded by `query-drive-budget`
   calls instead: a VM still waiting on a call once the drive has
   answered that many raises `query-call-limit-text` as its own error,
   the unanswered request abandoned with it."
  [state vm]
  (loop [i 0
         calls 0
         vm vm
         state state
         pending []
         progress? false]
    (let [link? (link-waiting? vm)
          query? (query/waiting? vm)]
      (cond
        (or (vm/halted? vm) (not (or link? query?)) (>= i link-round-budget))
        [state vm pending progress?]

        (and query? (>= calls query-drive-budget))
        (throw (link-raise (ex-info query-call-limit-text
                                    {:reason :yin.repl.query/call-limit
                                     :limit query-drive-budget})
                           vm
                           (update state :query-pair query/abandon-requests)))

        :else
        (let [served (when link?
                       (link/serve {:pair (:link-pair state)
                                    :source (:link-source state)}))
              answered (when query?
                         (query/serve {:pair (:query-pair state)
                                       :indexer (:indexer state)
                                       :ast-indexer (:ast-indexer state)
                                       :dht (:dht state)
                                       :limits {:row-limit query-row-limit
                                                :byte-limit query-byte-limit}
                                       :budget (min query-serve-budget
                                                    (- query-drive-budget
                                                       calls))}))
              state (cond-> state
                      served (assoc :link-pair (:pair served))
                      answered (assoc :query-pair (:pair answered)
                                      :dht (:dht answered)))
              pending (if (seq (:pending served)) (:pending served) pending)]
          (if (or (:progress? served) (:progress? answered))
            (let [vm' (try (vm/run vm)
                           (catch #?(:cljd Object :clj Exception :cljs js/Error)
                                  error
                             (throw (link-raise error vm state))))]
              (recur (if link? (inc i) i)
                     (+ calls (:answered answered 0))
                     vm'
                     state
                     pending
                     true))
            [state vm pending progress?]))))))


(defn- tokenize
  "Answer the halted VM's value on the output medium under `state`'s
   round, as the evaluator runner does for a program it runs itself.  A
   resumed require halts outside the observer loop, so the shell emits
   its token here."
  [state vm]
  ((make-runner (:output-stream state) (round-id state)) vm))


(defn- pending-text
  [pending]
  (if (seq pending)
    (str ";; require pending: "
         (str/join ", " (map (fn [p]
                               (str (pr-str (:name p))
                                    " (link " (pr-str (:yin.link/id p)) ")"))
                             pending))
         "); lines typed meanwhile run when it completes, (abandon) gives"
         " up\n")
    (str ";; require still pending; lines typed meanwhile run when it"
         " completes, (abandon) gives up\n")))


(defn- dropped-lines-text
  [n]
  (when (pos? n)
    (str ";; dropped " n " line" (when (> n 1) "s")
         " typed while the require was pending\n")))


(def ^:private policy-abandon-text
  "The message a policy-driven abandon prints, in the shape of the
   `(abandon)` notice, so a policy abandon is never mistaken for a
   user's (yin.repl.link-policy.md section 3.3)."
  ";; the session link policy ended the require\n")


(defn- abandon-pending
  "Give up the shell's `:pending-run` (section 7.2, step 7, the abandoned
   case): every install in flight is dropped through the engine's own
   `refused`, every link wait entry is retired with the reason raised as
   the require's error, and the shell returns to the round the require
   began in, whose store and value history the parked evaluation never
   wrote.  Lines typed while the require was pending are dropped with
   it, and said so.  The linker side learns nothing: no response was
   ever promised, and one that still arrives is skipped as late.  The
   identity the parked evaluation minted -- link ids and child origins
   -- is carried onto the base, so nothing is minted twice on the
   surviving pair.  The `(abandon)` command retires with
   `:yin.repl/abandoned` and no message of its own; the session link
   policy retires with the reason it answered and prints
   `policy-abandon-text`, so a policy abandon is never mistaken for a
   user's."
  ([state] (abandon-pending state :yin.repl/abandoned nil))
  ([state reason ended-text]
   (if-let [parked (:pending-run state)]
     (let [[state' text] (drain-output state)
           ;; installs first: their waiters leave the wait set with the
           ;; refusal queued, then every remaining link entry retires
           vm (engine/abandon-installs (:vm parked) reason)
           error (try
                   (vm/run (reduce (fn [vm e]
                                     (engine/abandon-link vm (:link-id e)
                                                          reason))
                                   vm
                                   (:wait-set vm)))
                   nil
                   (catch #?(:cljd Object :clj Exception :cljs js/Error) e
                     e))]
       [(assoc state'
               :vm (query/discard-answers
                     (carry-link-identity (:base parked) vm)
                     (:query-pair state'))
               :pending-run nil)
        (str text
             ended-text
             (if error (format-error error) "")
             (dropped-lines-text (count (:pending-lines parked))))])
     [state "Nothing is pending"])))


(defn- consult-link-policy
  "Consult the shell's `:link-policy` over a run that is still pending --
   when the require parks, and after each re-check that leaves it so;
   never on a re-check that completes it (yin.repl.link-policy.md
   section 3.2).  `text` is what the round printed so far and
   `kept-text` what a kept run reports.  `:manual` is never consulted
   and keeps the run; a function's `:keep` keeps it; `:abandon` or
   `{:abandon reason}` runs the same abandon path as `(abandon)`, the
   reason defaulting to `:yin.repl/link-policy`; a throw or a return
   outside the contract is a `:keep` for this consult plus one shell
   error line -- never a silent abandon, never session death (section
   3.4).  The view the function receives is plain data, no clock, no
   handle, no secret (section 3.1).  Returns `[state text]`."
  [state text kept-text]
  (if-not (fn? (:link-policy state))
    [state (str text kept-text)]
    (let [parked (:pending-run state)
          answer (try
                   {:answer ((:link-policy state)
                             {:links (mapv (fn [p]
                                             {:name (:name p)
                                              :link-id (:yin.link/id p)})
                                           (:links parked))
                              :checks (or (:checks parked) 0)
                              :lines-retained
                              (count (:pending-lines parked))})}
                   (catch #?(:cljd Object :clj Exception :cljs js/Error) e
                     {:thrown e}))]
      (cond
        (:thrown answer)
        [state (str text kept-text (format-error (:thrown answer)) "\n")]

        (= :keep (:answer answer))
        [state (str text kept-text)]

        (= :abandon (:answer answer))
        (let [[state' text'] (abandon-pending state :yin.repl/link-policy
                                              policy-abandon-text)]
          [state' (str text text')])

        (and (map? (:answer answer))
             (= #{:abandon} (set (keys (:answer answer)))))
        (let [reason (or (:abandon (:answer answer)) :yin.repl/link-policy)
              [state' text'] (abandon-pending state reason
                                              policy-abandon-text)]
          [state' (str text text')])

        :else
        [state
         (str text
              kept-text
              (format-error
                (ex-info
                  "Link policy returned a value outside its contract"
                  {:answer (:answer answer)
                   :contract [:keep :abandon {:abandon :reason}]}))
              "\n")]))))


(defn- pending-eval
  "Answer a round whose require did not complete: the parked VM, its
   round-start base, and the pending links are the shell's
   `:pending-run`, and the prompt returns.  Nothing is wedged: another
   line re-checks the link, the host may step one itself with
   `recheck-pending`, the link policy is consulted as the run parks, and
   `(reset)` or `(vm ...)` drops it with the session."
  [state state' vm' pending]
  (let [[state'' text] (drain-output state')]
    (consult-link-policy
      (assoc state''
             :vm vm'
             :pending-run {:vm vm'
                           :base (:vm state)
                           :links pending
                           :checks 0})
      text
      (pending-text pending))))


(defn- wedged-program
  [state]
  (ex-info
    "Program stream did not form a complete, runnable Yin VM program.
Hint: If you wanted to evaluate these datoms as data, use a quote: '[[...]]"
    {:vm-type (:vm-type state)}))


(defn- run-evaluation
  "The evaluator half of a round whose expander forwarded a program: run
   it, then either finalize the value, drive its require to a conclusion,
   or park it as `:pending-run`.  `state` is the round's starting state;
   `expanded` already carries the expander's progress, which a failing
   evaluation keeps."
  [state expanded]
  (try
    (let [gaps-before (ingress-gaps expanded)
          evaluated (run-evaluator-stage expanded)
          vm (:vm evaluated)]
      (cond
        (> (ingress-gaps evaluated) gaps-before)
        [(assoc evaluated :ingress-loss? true)
         (str "Error: " ingress-loss-text)]

        (vm/halted? vm)
        (finalize-eval state evaluated
                       (engine/restore-initial-env (:env (:vm expanded)) vm))

        (or (link-waiting? vm) (query/waiting? vm))
        (let [[state' vm' pending] (drive-links evaluated vm)]
          (cond
            (vm/halted? vm')
            (finalize-eval state
                           (assoc state' :vm vm')
                           (tokenize state'
                                     (engine/restore-initial-env
                                       (:env (:vm expanded)) vm')))

            (link-waiting? vm')
            (pending-eval state state' vm' pending)

            :else (throw (wedged-program state))))

        :else (throw (wedged-program state))))
    (catch #?(:cljd Object :clj Exception :cljs js/Error) error
      ;; a link raise rolls the shell back to the round's base; the
      ;; identity the interrupted VM had minted rides on it, so nothing
      ;; is minted twice on the surviving pair
      (let [d (ex-data error)
            base (query/discard-answers
                   (if (or (::link-counter d) (::link-origins d))
                     (carry-link-identity
                       (:vm state)
                       {:id-counter (::link-counter d)
                        :origins (::link-origins d)})
                     (:vm state))
                   (:query-pair expanded))]
        (consume-failed-round (cond-> (assoc expanded :vm base)
                                (::query-pair d) (assoc :query-pair
                                                        (::query-pair d)))
                              error)))))


(defn- eval-program
  "Evaluate one frontend program — the map AST the compilers emit or a
   datom-literal program — through the §10.1 composition.  The encoder
   projects it to an input batch of canonical rows with its harvest and
   declaration rows; the shell appends that batch to `program-in`, drives
   the expander, drains its summary, and drives the code and AST indexers and then
   the evaluator only when a program was forwarded.  An expansion failure is reported as data: its
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
    (let [state' (update (inject-last-value state) :round inc)
          batch (encoder/program-batch program)
          append (stream/append! (:program-stream state') batch)]
      (if-not (= :dao.stream/ok (:dao.stream/outcome append))
        [state (str "Error: program batch not ingested: "
                    (name (:dao.stream/outcome append)))]
        (let [expanded (try
                         (run-expander-stage state')
                         (catch #?(:cljd Object
                                   :clj Exception
                                   :cljs js/Error) error
                           {:failed (consume-failed-round state error)}))]
          (if-let [failed (:failed expanded)]
            failed
            (let [[expanded {:keys [errors forwarded]}] expanded
                  indexed (cond-> expanded
                            (pos? forwarded) (-> run-index-stage
                                                 run-ast-index-stage))]
              (with-index-notice
                (cond
                  (> (ingress-gaps indexed) (ingress-gaps state'))
                  [(assoc indexed :ingress-loss? true)
                   (str "Error: " ingress-loss-text)]

                  (pos? forwarded) (run-evaluation state indexed)

                  :else [(assoc indexed :vm (:vm state))
                         (format-expansion-errors errors)])
                (when (pos? forwarded)
                  [(index/notice (:indexer expanded) (:indexer indexed))
                   (ast-index/notice (:ast-indexer indexed))])))))))))


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
        {:keys [tree error log]}
        (macro/expand-batch batch (get-in state [:expander :ctx]))]
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
   new expander holds only the standard forms.  A require still pending
   on the old VM is dropped with it: its link pair is the session's, and
   the new session answers from its own.

   Over a durable store the code index is not the session's to drop: the
   new indexer continues the old one's log, entity allocation, and
   published manifest (`yin.repl.index/carry-over`), so a rebuild never
   resets `t`.  Over the memory store it starts empty, as it always has."
  [state vm-type]
  (merge state
         (cond-> (make-session vm-type (:output-stream state)
                               (:extra-primitives state)
                               (:shell-token state)
                               (:index-store state))
           (store/durable? (:index-store state))
           (update :indexer index/carry-over (:indexer state)))
         {:vm-type vm-type
          :ingress-loss? false
          :last-value nil
          :last-value-2 nil
          :last-value-3 nil
          :pending-run nil}))


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
              (str "Switched to " (get vm-labels vm-type)
                   (if (store/durable? (:index-store state))
                     " (VM store cleared; durable code index kept)"
                     " (store cleared)"))]
             [state (str "Error: Unknown Yin REPL VM type " (pr-str vm-type)
                         "; supported: "
                         (pr-str (vec (keys vm-constructors))))]))
      lang (let [lang (first args)]
             (if (contains? lang-labels lang)
               [(assoc state :lang lang)
                (str "Switched to " (get lang-labels lang))]
               [state (str "Error: Unknown Yin REPL language " (pr-str lang)
                           "; supported: " (pr-str (vec (keys lang-labels))))]))
      compile [state (render-compile-output
                       state
                       (compile-command-ast state (first args)))]
      reset [(rebuild-session state (:vm-type state))
             (str (get vm-labels (:vm-type state)) " reset")]
      abandon (abandon-pending state)
      help [state help-text]
      repl-state [state (format-value (repl-state state))]
      quit [(assoc state :running? false) "Bye"]
      telemetry [state telemetry-text]
      [state (str "Error: Unknown Yin REPL command " (pr-str command))])))


(defn- eval-parsed*
  "The parse dispatch of one line, against a shell with no require to
   re-check."
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


(defn- fold-queued
  "Evaluate the lines retained while a require was pending, in the order
   they were typed, each exactly once, and join their texts.  A line that
   parks the shell on a require of its own stops the fold: the lines
   after it stay retained, in order, on the new `:pending-run`."
  [[state text] queued]
  (loop [state state
         text text
         [in & more :as queued] (seq queued)]
    (cond
      (empty? queued) [state text]
      (:pending-run state) [(update-in state [:pending-run :pending-lines]
                                       (fnil into []) queued)
                            text]
      :else (let [[state' text'] (eval-parsed* state
                                               (:trimmed in)
                                               (:parsed in))]
              (recur state' (str text "\n" text') more)))))


(defn- recheck-pending*
  "One bounded re-check of the shell's `:pending-run`: serve the
   interpreter, run the parked VM, and either answer its value -- the
   round the require began in finally completes, and the lines retained
   while it was pending, then `input`, evaluate against it -- consult
   the link policy when the re-check leaves the run pending, `:checks`
   counting this pending run's re-checks that made no progress and
   resetting on one that did, or, when the re-check raised the link's
   refusal, return the shell to the round's base with the error as text
   and evaluate `input` there.  `input` is nil for the host's
   `recheck-pending`, which evaluates no line of its own."
  [state input]
  (let [parked (:pending-run state)]
    (try
      (let [[state' vm' pending progress?] (drive-links state (:vm parked))]
        (if (vm/halted? vm')
          (let [vm'' (engine/restore-initial-env (:env (:base parked)) vm')
                [st text] (finalize-eval state
                                         (assoc state' :vm vm'')
                                         (tokenize state' vm''))]
            (fold-queued [(assoc st :vm vm'' :pending-run nil) text]
                         (cond-> (vec (:pending-lines parked))
                           input (conj input))))
          (let [checks (if progress? 0 (inc (or (:checks parked) 0)))
                parked' (cond-> (assoc parked
                                       :vm vm'
                                       :links pending
                                       :checks checks)
                          input (update :pending-lines
                                        (fnil conj []) input))
                [st text] (drain-output (assoc state'
                                               :vm vm'
                                               :pending-run parked'))]
            (consult-link-policy st text (pending-text pending)))))
      (catch #?(:cljd Object :clj Exception :cljs js/Error) error
        (let [[st text] (drain-output state)
              d (ex-data error)]
          (fold-queued
            [(cond-> (assoc st
                            :vm (query/discard-answers
                                  (carry-link-identity
                                    (:base parked)
                                    {:id-counter (::link-counter d)
                                     :origins (::link-origins d)})
                                  (:query-pair st))
                            :pending-run nil)
               (::query-pair d) (assoc :query-pair (::query-pair d)))
             (str text
                  (format-error error)
                  (dropped-lines-text (count (:pending-lines parked))))]
            (cond-> [] input (conj input))))))))


(defn recheck-pending
  "One re-check of the shell's `:pending-run` without an input line --
   the step an unattended host drives at its own cadence
   (yin.repl.link-policy.md section 4): serve the interpreter, run the
   parked VM, and consult the link policy when the run is still
   pending.  A re-check that completes the run folds the retained lines
   exactly as a typed line's completion does.  Returns `[state text]`,
   `text` nil when nothing is pending.  It reads no clock and takes no
   callback; the host decides when to call it."
  [state]
  (if (:pending-run state)
    (recheck-pending* state nil)
    [state nil]))


(defn- resume-pending
  "One bounded re-check of the shell's `:pending-run` behind a typed
   line: while the run stays pending the line is retained -- the parked
   VM owns the store a new evaluation would fork -- and it evaluates,
   in typing order and exactly once, when the link completes."
  [state trimmed parsed]
  (recheck-pending* state {:trimmed trimmed, :parsed parsed}))


(defn- command-line?
  [parsed]
  (let [forms (:forms parsed)
        form (when (= 1 (count forms)) (first forms))]
    (and form (command-form? form))))


(defn- eval-parsed
  [state trimmed parsed]
  (if (and (:pending-run state) (not (command-line? parsed)))
    ;; A require is still pending: re-check it first.  While it stays
    ;; pending the line is retained -- the parked VM owns the store a new
    ;; evaluation would fork -- and it evaluates, in typing order and
    ;; exactly once, when the link completes.
    (resume-pending state trimmed parsed)
    (eval-parsed* state trimmed parsed)))


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
                        (catch #?(:cljd Object
                                  :clj Exception
                                  :cljs js/Error) error
                          {:error error}))]
        (eval-parsed state' combined parsed)))))


(defn repl-state
  "A serializable summary of shell state.

   The stream summaries read the ledger rather than asking a handle whether it
   is closed: dao.stream has no `closed?`, and an idle medium has no last
   operation.
   The program media are the composition's own, observed beside the VM, so
   the shell reports what it knows of them — the declared capacity, the gaps
   the session's observers counted, and whether a loss has already refused
   further evaluation.  Each `:pending` entry carries the link policy's
   name (`:manual` or `:fn`) and the pending run's `:checks`, so a host
   can display why a run is still waiting (yin.repl.link-policy.md
   section 3.5).  `:macros` maps each macro the expander's store holds
   to its lambda's root address (yin.vm.macro.md §10.1).  `:index` is the
   code indexer's `yin.repl.index/status`: whether every committed program
   is published, whether a gap lost the indexer, and its last failure.
   `:ast-index` is the AST indexer's `yin.repl.ast-index/status`: the sizes
   of its `$ast` and `$occ` relations, whether a gap lost it, and why it
   failed."
  [state]
  {:lang (:lang state)
   :macros (into (sorted-map-by #(compare (str %1) (str %2)))
                 (map (fn [[sym entry]]
                        [sym (first (:yin.macro/tree entry))]))
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
   :pending (when-let [parked (:pending-run state)]
              (let [policy (:link-policy state)]
                (mapv (fn [p]
                        {:name (:name p)
                         :link-id (:yin.link/id p)
                         :policy (if (fn? policy) :fn policy)
                         :checks (or (:checks parked) 0)})
                      (:links parked))))
   :index (index/status (:indexer state))
   :ast-index (ast-index/status (:ast-indexer state))
   :telemetry {:supported? false :note telemetry-text}
   :remote {:connected? false}})

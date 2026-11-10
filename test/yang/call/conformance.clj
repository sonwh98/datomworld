(ns yang.call.conformance
  "The cross-language calling gates' shared composition (docs/design/
   yang.antlr.md section 8.10.11): a donor program's named export reaches a
   caller program by explicit published-export composition only.

   A donor is real source of any admitted frontend, lowered unmodified and
   published as an ordinary linker module whose one export, `body`, is the
   lowered unit. A caller task requires that module, runs its body under the
   donor runtime's own entry (`js/run-main`, `php/run-main`, `py/run-main`,
   `clj/run-main`), reads each named export from the donor runtime's global
   namespace, and projects it into the caller runtime's namespace under the
   same name; then the caller source, lowered unmodified, runs under its own
   entry. The composition is a sequence of top-level nodes: no call node is
   rewritten, no foreign `:code` is extracted, no language-pair wrapper is
   inserted, and the projected value is the donor's own callable value
   (identity survives).

   A callable value is task-local (a function is a cell, and cells are
   task-local, section 8.11), so the export is answered as a node evaluated
   in the caller's task, never as a host value carried between tasks.
   `export-program` answers the donor's export alone as the program's value.

   Every program runs linked on the three vector VMs (`semantic`, `stack`,
   `register`) over the TypeScript linked harness's content store, where
   `yang.call` and `js` already live and `php`, `py` and `clj` are
   published beside them. The AST walker is not a backend: its links are
   refused until L-b."
  (:require
    [clojure.string :as str]
    [yang.clojure :as clj-comp]
    [yang.clojure.prelude :as clj-prelude]
    [yang.core.module :as core-module]
    [yang.core.names :as names]
    [yang.core.uast :as u]
    [yang.php.antlr.cst :as php-cst]
    [yang.php.antlr.lower :as php-lower]
    [yang.php.antlr.portable-harness :as php-h]
    [yang.php.antlr.prelude :as php-prelude]
    [yang.python.antlr.lower :as py-lower]
    [yang.python.antlr.parser :as py-parser]
    [yang.python.antlr.prelude :as py-prelude]
    [yang.python.antlr.render :as py-render]
    [yang.typescript.antlr.cst :as ts-cst]
    [yang.typescript.antlr.linked-harness :as h]
    [yang.typescript.antlr.lower :as ts-lower]
    [yang.typescript.antlr.render :as render]
    [yin.vm :as vm]))


(defn- publish-guest!
  "Publish guest module `spec` into the harness store, throwing immediately
   with `result` in ex-data if publication is refused."
  [spec]
  (let [result (h/publish-guest-module! spec)]
    (when (= :refused (:status result))
      (throw (ex-info (str "Guest module publication refused: " (or (:reason result) (:status result)))
                      result)))
    result))


(def ^:private published-php
  "`php` published once into `js`'s content store, pinning the same
   `yang.call` revision (as `source-call-test` publishes it)."
  (delay (publish-guest!
           (php-prelude/module-spec (h/registry)
                                    (:call-address @h/published)))))


(def ^:private published-py
  "`py` published beside `js` and `php`, over the same host registry and
   `yang.call` revision."
  (delay (publish-guest!
           (py-prelude/module-spec (h/registry)
                                   (:call-address @h/published)))))


(def ^:private published-clj
  "`clj` published beside `js`, `php` and `py`, over the same host registry
   and `yang.call` revision."
  (delay (publish-guest!
           (clj-prelude/module-spec (h/registry)
                                    (:call-address @h/published)))))


(defn- parse-ok
  "The CST packet of `source` under `parse`, refused unless the parse is ok."
  [parse source]
  (let [pk (parse source)]
    (when-not (= :yang.cst/ok (:yang.cst/outcome pk))
      (throw (ex-info "donor or caller source does not parse"
                      {:source source, :packet (dissoc pk :yang.cst/nodes)})))
    pk))


(defn- python-export-symbol
  [export]
  (symbol (str "xlang.python.export." export)))


(defn- python-unit
  [packet exports]
  (u/lam [py-lower/globals-sym py-lower/globals-fn-sym]
         (u/seq-nodes
           (concat (map (fn [export]
                          (u/sexp->uast
                            (list 'py/global-set py-lower/globals-sym
                                  {:py/str export} (python-export-symbol export))))
                        exports)
                   [(py-lower/lower-module-body packet)]))))


(def frontends
  "Each admitted frontend's profile, as data:

   - `:runtime`, `:address`: the runtime module and its published address;
   - `:refusal`: the publication refusal key and reason of its modules;
   - `:deps` (optional): pinned requirements beyond the runtime its
     lowered units read directly (the clojure unit's `yang.call` reads);
   - `:parse`: source text to CST packet;
   - `:unit`: packet and projected exports to the lowered unit its
     `:run-main` entry takes;
   - `:read-export`: export name to the form reading the runtime's global
     namespace (JS `globalThis`, PHP's named-function table, Python's main
     module dictionary, the clojure runtime's task-local namespace cell);
   - `:project`: name and a value form to the form binding the value where
     the runtime's source resolves a called name;
   - `:render`: the `:run-main` value as test data."
  {:typescript
   {:runtime 'js,
    :address #(:address @h/published),
    :refusal [:yang.typescript.antlr/refusal :yang.typescript.antlr/undeclared-free],
    :parse #(parse-ok ts-cst/parse-source %),
    :unit (fn [packet _] (names/canonicalize (ts-lower/lower-unit packet))),
    :run-main 'js/run-main,
    :read-export (fn [export] (list 'js/global-get {:js/str export})),
    :project (fn [name value] (list 'js/define-global! name value)),
    :render render/output},
   :php
   {:runtime 'php,
    :address #(:address @published-php),
    :refusal [:yang.php/refusal :yang.php/undeclared-free],
    :parse #(parse-ok php-cst/parse-source %),
    :unit (fn [packet _] (u/lam [] (php-lower/lower-module-body packet))),
    :run-main 'php/run-main,
    :read-export (fn [export]
                   (list 'php/resolve-fn (str/lower-case export) export)),
    :project (fn [name value]
               (list 'php/declare-function! (str/lower-case name) value)),
    :render php-h/render},
   :python
   {:runtime 'py,
    :address #(:address @published-py),
    :refusal [:yang.python.antlr/refusal :yang.python.antlr/undeclared-free],
    :parse #(parse-ok py-parser/parse-source %),
    :unit python-unit,
    :run-main 'py/run-main,
    :read-export (fn [export]
                   (list 'py/global-get
                         (list 'py/module-dict
                               (list 'py/modules-ref {:py/str "__main__"}))
                         {:py/str export})),
    :project (fn [name value]
               (list 'yin/def (list 'quote (python-export-symbol name)) value)),
    :render py-render/output},
   :clojure
   {:runtime 'clj,
    :address #(:address @published-clj),
    :refusal [:yang.clojure/refusal :yang.clojure/undeclared-free],
    :deps (fn [] {'yang.call (:call-address @h/published)}),
    :parse clj-comp/read-forms,
    :unit (fn [packet exports] (clj-comp/lower-unit packet exports)),
    :run-main 'clj/run-main,
    :read-export (fn [export] (list 'clj/global-get export)),
    :project (fn [name value]
               ;; The runtime global namespace is the one binding (as
               ;; `js/define-global!` is for a JS caller): `lower-unit`
               ;; reads each projected name through `clj/global-get`, so
               ;; no task-store binding duplicates it. A caller top-level
               ;; definition of the same name publishes after the
               ;; projection and shadows it.
               (list 'clj/define-global! name value)),
    :render clj-comp/output}})


(defn- profile
  [frontend]
  (or (get frontends frontend)
      (throw (ex-info "frontend not admitted" {:frontend frontend}))))


(defn- modules
  "The host registry, admitted by every runtime profile that checks it,
   with the clojure guest library's host surface installed beside them.
   Donor publication declares free names over this same registry, so a
   clojure donor using the guest library (its `kind` and `portable`
   hosts) publishes like it runs."
  []
  (clj-prelude/admit (php-prelude/admit (h/registry))))


(defn- claims-call-sites?
  "True when map AST `ast` carries the C1 call-site key anywhere."
  [ast]
  (boolean (some #(= :yang/call-site %) (tree-seq coll? seq ast))))


(defn publish-donor!
  "Publish the donor `source` of `frontend` as the module `module` (default
   `xlang.donor.<frontend>`), exporting `body`, its unmodified lowered unit.
   Answers `{:frontend :module :address :links}`."
  ([frontend source]
   (publish-donor! frontend source (symbol (str "xlang.donor." (name frontend)))))
  ([frontend source module]
   (let [{:keys [runtime address refusal parse unit deps]} (profile frontend)
         [refusal-key reason] refusal
         ast (let [raw (core-module/module-tree
                         [(core-module/require-node runtime)
                          (u/def! 'body (unit (parse source) []))])]
               (cond-> raw
                 (claims-call-sites? raw) u/annotate-call-sites))
         spec (core-module/module-spec
                refusal-key reason (modules)
                {:name module,
                 :ast ast,
                 :exports '#{body},
                 :runtime-name runtime,
                 :runtime-address (address),
                 :deps (if deps (deps) {})})
         result (publish-guest! spec)]
     {:frontend frontend,
      :module module,
      :address (:address result),
      :links (:links result)})))


(defn- runtime-entry
  "Require and initialize each distinct runtime of `frontend-keys`, once."
  [frontend-keys]
  (into [(core-module/require-node 'yang.call)]
        (mapcat (fn [runtime]
                  [(core-module/require-node runtime)
                   (u/sexp->uast (list (symbol (str runtime) "init!")))]))
        (distinct (map (comp :runtime profile) frontend-keys))))


(defn- donor-run
  "The donor module required and its body run under its runtime's entry."
  [{:keys [frontend module]}]
  [(core-module/require-node module)
   (u/app (u/v (:run-main (profile frontend)))
          (u/v (symbol (str module) "body")))])


(defn export-program
  "The program whose value is the donor's `export`, read from its runtime's
   global namespace after its body ran."
  [donor export]
  (let [{:keys [read-export]} (profile (:frontend donor))]
    (u/mark-tails
      (u/seq-nodes (concat (runtime-entry [(:frontend donor)])
                           (donor-run donor)
                           [(u/sexp->uast (read-export export))])))))


(defn compose
  "The caller program: the donor's body run, each name of `exports` read
   from the donor runtime and projected into `caller-frontend`'s namespace
   under the same name, then `caller-source`, lowered unmodified, run under
   its runtime's entry. Its value is the caller entry's value."
  [donor exports caller-frontend caller-source]
  (let [{:keys [read-export]} (profile (:frontend donor))
        {:keys [project parse unit run-main]} (profile caller-frontend)]
    (u/mark-tails
      (u/seq-nodes
        (concat (runtime-entry [(:frontend donor) caller-frontend])
                (donor-run donor)
                (map (fn [export]
                       (let [value (symbol (str "xlang.export." export))]
                         (u/let1 value
                                 (u/sexp->uast (read-export export))
                                 (u/sexp->uast (project export value)))))
                     exports)
                [(u/app (u/v run-main) (unit (parse caller-source) exports))])))))


(defn link-source
  "The serving composition naming the runtimes and the donor module."
  [{:keys [module address]}]
  (h/source {'yang.call (:call-address @h/published),
             'js (:address @h/published),
             'php (:address @published-php),
             'py (:address @published-py),
             'clj (:address @published-clj),
             module address}))


(defn run-program
  "`{backend value}` of `ast`, linked against `donor`, on each vector VM."
  [donor ast]
  (let [src (link-source donor)]
    (into (sorted-map)
          (map (fn [backend]
                 [backend
                  (vm/value (h/run-linked backend ast {:modules (modules),
                                                       :link-source src}))]))
          (keys h/backends))))


(defn agreed
  "The one value every backend of `results` answered, else
   `{:disagreement results}`."
  [results]
  (let [vs (set (vals results))]
    (if (= 1 (count vs)) (first vs) {:disagreement results})))


(defn call
  "Publish the donor `[frontend source]`, compose its `exports` into the
   caller `[frontend source]`, run it on each vector VM and answer the
   caller's rendered value when every backend agrees (else
   `{:disagreement {backend rendered}}`)."
  [[donor-frontend donor-source] exports [caller-frontend caller-source]]
  (let [donor (publish-donor! donor-frontend donor-source)
        render (:render (profile caller-frontend))]
    (agreed (update-vals (run-program donor (compose donor exports caller-frontend
                                                     caller-source))
                         render))))


(defn export-form
  "The guest form reading a source-produced export after composition. This
   reads the public namespace, never the callable's private code slot."
  [frontend export]
  ((:read-export (profile frontend)) export))


(defn compose-exception
  "Compose real donor/caller source exactly as `compose` does, optionally
   followed by an unlowered boundary observation form. The source bodies
   and their call nodes are untouched. Boundary forms are for provenance
   assertions and malformed invocations, not substitute source-pair legs."
  ([donor exports caller-frontend caller-source]
   (compose donor exports caller-frontend caller-source))
  ([donor exports caller-frontend caller-source observation]
   (u/mark-tails
     (u/then (compose donor exports caller-frontend caller-source)
             (u/sexp->uast observation)))))


(defn observation-map-form
  "Emit a map whose known observation values are guest expressions.
   sexp->uast intentionally treats a map literal as DATA, including lists
   and symbols within it. Dynamic boundary observations use explicit assoc,
   never a transformation of parsed source or a runtime shape detector."
  [entries]
  (reduce (fn [form [k value]] (list 'assoc form k value)) {} entries))


(defn observation-vector-form
  "Emit a boundary argument vector of evaluated expressions. Like maps,
   vectors in sexp->uast are otherwise literal data."
  [expressions]
  (reduce (fn [form value] (list 'conj form value)) [] expressions))


(defn boundary-call-form
  "An abortive observer of a public entry, as guest code. All arguments are
   forms. `options` selects invoke-with when present. A returned envelope
   is recorded as :return; only the supplied exit records :raise. Transfer
   count and the after-call sentinel live in task cells. No payload is
   snapshotted here: assertions must precede rendering."
  ([callee positional named]
   (boundary-call-form callee positional named nil))
  ([callee positional named options]
   (let [phase (gensym "phase")
         transfers (gensym "transfers")
         after (gensym "after")
         escape (gensym "escape")
         condition (gensym "condition")
         returned (gensym "returned")
         raise (list 'fn [condition]
                     (list 'do
                           (list 'cell/set! transfers (list '+ 1 (list 'cell/get transfers)))
                           (list escape
                                 (observation-map-form
                                   {:route :raise :condition condition
                                    :transfers (list 'cell/get transfers)
                                    :after (list 'cell/get after)}))))
         invocation (if options
                      (list 'yang.call/invoke-with callee positional named raise options)
                      (list 'yang.call/invoke callee positional named raise))]
     (list 'let [phase (list 'cell/new false)
                 transfers (list 'cell/new 0) after (list 'cell/new false)]
           (list (list 'fn [escape]
                       (list 'if (list 'cell/get phase)
                             escape
                             (list 'do (list 'cell/set! phase true)
                                   (list 'let [returned invocation]
                                         (list 'do (list 'cell/set! after true)
                                               (observation-map-form
                                                 {:route :return :value returned
                                                  :transfers (list 'cell/get transfers)
                                                  :after (list 'cell/get after)}))))))
                 (list '%capture))))))


(defn guard-exceptions-form
  "Diagnostic outer handlers: expose an escape that bypassed the supplied
   boundary exit as :unbridged, rather than letting no-handler obscure the
   assertion. These are NOT replacement callee bridges: :unbridged always
   fails the provenance contract. Catch data stays guest-local."
  [frontend-keys form]
  (reduce
    (fn [body frontend]
      (let [e (gensym "unbridged")
            handler (list 'fn [e] {:route :unbridged :frontend frontend})]
        (case frontend
          :typescript (list 'js/try (list 'fn [] body) handler)
          (:python :php)
          (let [result (gensym "result")
                try-name (if (= frontend :python) 'py/try 'php/try)]
            (list 'let [result (list 'cell/new nil)]
                  (list try-name
                        (list 'fn [] (list 'cell/set! result body))
                        handler
                        (list 'fn [] (list 'cell/get result)))))
          :clojure body)))
    form (distinct frontend-keys)))


(defn exception-outcome
  "Uniform observation of a runtime root's output. Preserve its exception
   snapshot as data, plus the legacy render for diagnostics. This is not
   identity evidence; use boundary-call-form for that. Success-call renders
   and `call` are deliberately unchanged."
  [frontend value]
  (let [rendered ((:render (profile frontend)) value)
        [out exception result]
        (case frontend
          :python [(:py/out rendered) (:py/exception value) nil]
          :typescript [(:js/out rendered) (:js/uncaught value) nil]
          :php [(:php/out value) (or (:php/uncaught value) (:php/fatal value)) nil]
          :clojure (if (and (vector? value) (= 2 (count value))
                            (= :yin.k/error (first value)))
                     [[] (second value) nil]
                     [[] nil value]))]
    {:frontend frontend :out out :exception exception :value result
     :rendered rendered}))


(defn exception-call
  "The exception-leg counterpart of `call`, with uniform per-VM outcomes.
   Source supplies the donor raises / caller catches patterns. Always keep
   the backend map: agreement alone must not hide which VM ran a red leg."
  [[donor-frontend donor-source] exports [caller-frontend caller-source]]
  (let [donor (publish-donor! donor-frontend donor-source)]
    (update-vals
      (run-program donor (compose-exception donor exports caller-frontend caller-source))
      #(exception-outcome caller-frontend %))))


(defn error-results
  "Map `e` across each vector VM backend as an operational failure."
  [e]
  (into (sorted-map) (map (fn [backend] [backend e]) (keys h/backends))))


(defn run-exception-program
  "The exception probes' independent VM runs, concurrent on the JVM. Each
   job owns its registry, task, link pair AND content-serving composition;
   no stream cursor is shared. Only the published content store is read
   by all three. A host Exception is kept as the original host object for
   clojure.test's :error reporting, NEVER relabelled as a guest condition.
   All jobs settle even if one fails. `run-program` is unchanged."
  [donor ast]
  (try
    ;; Resolve publication delays before starting independent readers.
    (link-source donor)
    (let [jobs (mapv (fn [backend]
                       [backend
                        (future
                          (try
                            (vm/value
                              (h/run-linked backend ast
                                            {:modules (modules)
                                             :link-source (link-source donor)}))
                            (catch Exception e e)))])
                     (keys h/backends))]
      (into (sorted-map)
            (map (fn [[backend job]] [backend @job])) jobs))
    (catch Throwable e
      (error-results e))))

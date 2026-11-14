(ns yang.php.antlr.e2e-harness
  "The JVM end-to-end harness: PHP source -> CST packet -> lowering stage ->
   program envelope -> the bundled program on each of the four VMs. Output
   is the unit's bottom buffer, a latin-1 byte carrier, decoded as UTF-8
   here at the boundary."
  (:require
    [dao.host.text :as text]
    [dao.jing.mem :as mem]
    [dao.stream :as stream]
    [yang.php.antlr.cst :as cst]
    [yang.php.antlr.eval-coordinator :as eval-coordinator]
    [yang.php.antlr.linked-harness :as linked]
    [yang.php.antlr.lower :as lower]
    [yang.php.antlr.portable-harness :as portable]
    [yang.php.antlr.safepoint :as hooks]
    [yang.core.uast :as u]
    [yang.core.module :as core-module]
    [yang.safepoint :as safepoint]
    [yin.vm :as vm]
    [yin.vm.ast-walker :as walker]
    [yin.vm.debruijn.register :as rvm]
    [yin.vm.debruijn.stack :as dvm]
    [yin.vm.encoder :as encoder]
    [yin.vm.linker.interpreter :as link]
    [yin.vm.linker.publish :as publish]
    [yin.vm.module :as module]
    [yin.vm.semantic :as semantic]
    [yin.vm.semantic.derive :as derive]
    [yin.vm.semantic.linearize :as sr-linearize]
    [yin.vm.test-utils :as tu]))


(defn host-registry
  "The registry the PHP compositions run over."
  []
  (portable/host-registry))


(defn eval-compiler
  "A configured compiler service and its request/response streams for a run.
   The composition drives `eval-coordinator/serve` with the returned state."
  ([] (eval-compiler (linked/make-published)))
  ([published]
   (let [requests (tu/new-stream)
         responses (tu/new-stream)
         cursor (:dao.stream/cursor (stream/cursor requests stream/anchor-oldest))]
     {:worker (eval-coordinator/make-worker), :store (:store published),
      :registry (module/register-code-module (linked/registry)),
      :requests requests, :responses responses,
      :cursor cursor,
      :stage-requirement {:hooks true, :hooks-profile lower/eval-hook-profile,
                          :php-sp (:hook-address published)}})))


(def ^:private base-opts
  {:make-stream tu/make-stream,
   :capability-secret tu/secret,
   :primitives vm/primitives})


(def ^:private load-semantic
  (vm/fresh-code-loader semantic/load-ast vm/ast-contract))


(defn request-node
  "The `run-main` second-operand node the composition supplies for `request`:
   the harness converts the record to UAST itself — lowering never embeds
   request data."
  [request]
  (u/sexp->uast (or request :php/null)))


(defn lower-program
  "`{:ast a :warnings w}` for `source` through the lowering stage, or
   `{:diagnostics d}`. Warning-severity diagnostics do not stop the program.
  The request record travels the composition path: the harness consumes it
  (a plain record here) and composes it as `run-main`'s second operand —
  lowering itself never sees request data."
  ([source] (lower-program source nil))
  ([source request] (lower-program source request {}))
  ([source request options]
   (let [cst-log (tu/new-memory-log)
         program (tu/new-memory-log)
         diagnostics (tu/new-memory-log)]
     (stream/append! cst-log (cst/parse-source source))
     (lower/step-stage (lower/open-stage cst-log program diagnostics
                                         (cond-> (merge {:medium lower/program-medium} options)
                                           (some? request) (assoc :request-node
                                                                  (request-node request)))))
     (let [{warnings true, problems false}
           (group-by #(= :warning (:severity %)) (tu/drain diagnostics))]
       (if (seq problems)
         {:diagnostics problems}
         (let [{:yin/keys [batch root] :as envelope} (first (tu/drain program))]
           {:ast (nth batch root), :envelope envelope, :warnings (vec warnings)}))))))


(defn- compiler-effect
  [source]
  (module/make-effect :php.eval/compile {:source source}))


(defn- compiler-registry
  []
  (-> (linked/registry)
      module/register-code-module
      (module/register-host-module 'eval.compiler {'compile compiler-effect}
                                   {'compile (vm/primitive-profile 'compile :effectful [1]
                                                                   #{:php.eval/compile} :none)})))


(defn- eval-registry
  [service publications]
  (let [handler (fn [state effect _]
                  (let [request {:yang.compile/request [:eval (:source effect)]
                                 :yang.compile/unit :eval
                                 :yang.compile/frontend-revision :frontend-v1
                                 :yang.compile/compiler-revision :compiler-v1
                                 :yang.compile/goal :eval
                                 :yang.compile/provenance {:path "main.php"}
                                 :yang.compile/options {}
                                 :yang.php/source (:source effect)}
                        result (eval-coordinator/compile-and-publish!
                                 (assoc service :registry (compiler-registry)) request)]
                    (when-not (= :yang.compile/published (:yang.compile/outcome result))
                      (throw (ex-info "Eval compilation refused" result)))
                    (when-not (= :dao.stream/ok (:dao.stream/outcome (stream/append! publications result)))
                      (throw (ex-info "Eval publication stream full" result)))
                    {:state state :value result :blocked? false}))]
    (-> (compiler-registry)
        (module/register-effect-handler :php.eval/compile handler))))


(defn- eval-published
  []
  (let [store (mem/create-content-mem)
        hook (publish/publish-module! store {:name 'php.sp
                                             :ast (core-module/module-tree [(u/def! 'noop (u/lam [] (u/lit :php/null)))])
                                             :exports '#{noop} :requires {} :primitives {}})]
    {:store store :hook-address (:address hook)}))


(defn- run-eval-vm
  [backend ast]
  (let [published (eval-published)
        service (eval-compiler published)
        publications (tu/new-stream)
        registry (eval-registry service publications)
        pair (link/make-pair)
        source (link/composition {:content-store (:store published), :derivation :trusted
                                  :attempt-budget 1024})
        opts (linked/composition registry pair)
        task (if (= backend :ast-walker)
               (walker/vm-load-program (walker/create-vm opts) (vm/ast->datoms ast) vm/ast-contract)
               ((get linked/backends backend) ast opts))]
    (loop [task (vm/run task), pair pair, rounds 0]
      (if (and (vm/blocked? task) (< rounds 80))
        (let [entries (tu/drain publications)
              names (into {'php.sp (:hook-address published)}
                          (map (juxt :yang.compile/module :yang.compile/active-address)) entries)
              source (assoc source :name-env names)
              pair (:pair (link/serve {:pair pair :source source}))]
          (recur (vm/run task) pair (inc rounds)))
        (if (vm/blocked? task)
          (throw (ex-info "Eval task remained blocked" {:backend backend}))
          (vm/value task))))))


(defn packet->form
  "The nested form of a CST packet, spans dropped: `[:rule child ...]` for a
   rule, `[\"TYPE\" \"text\"]` for a token. The inverse of
   `portable-harness/packet`."
  [{:yang.cst/keys [root nodes]}]
  (letfn [(form
            [id]
            (let [{:keys [kind rule type text children]} (nth nodes id)]
              (if (= :token kind)
                [type text]
                (into [(keyword rule)] (map form) children))))]
    (form root)))


(defn latin1->text
  "The UTF-8 text of the byte carrier `s`."
  [s]
  (text/utf8-decode (.getBytes ^String s "ISO-8859-1")))


(defn render
  "The text a unit prints: its output, then, for an uncaught error, the
   fatal line (no path, plan section 3.9)."
  [{:php/keys [out uncaught fatal]}]
  (str (latin1->text out)
       (when uncaught
         (str "PHP Fatal error:  Uncaught " (:class uncaught) ": " (:message uncaught)))
       (when fatal
         (str "PHP Fatal error:  " fatal))))


(defn run-vms
  "`{vm-key rendered}` of `ast` on the four VMs; a host throw is
   `[:thrown message]`. `:execution-budget` derives the PHP safepoint hooks
   and starts one per-run counter; no option leaves the AST unchanged."
  ([registry ast] (run-vms registry ast {}))
  ([registry ast {:keys [execution-budget envelope eval-service?]}]
   (let [profile (cond-> hooks/ticks-profile
                   (some? execution-budget) (merge hooks/profile))
         ast (if envelope
               (let [projected (encoder/project envelope)
                     derived (:envelope (safepoint/derive-envelope projected profile :test/derived))
                     tree (nth (:yin/batch derived) (:yin/root derived))]
                 (vm/semantic-bytecode->ast tree))
               ast)
         ast (if (some? execution-budget) (hooks/program execution-budget ast) ast)
         opts (assoc base-opts :modules registry)
         session (tu/make-observer-session (tu/create-vm {:modules registry}))
         datoms (vec (vm/ast->datoms ast))
         projected #(:vector (sr-linearize/project ast))
         attempt (fn [f]
                   (try (render (f))
                        (catch Exception e [:thrown (ex-message e)])))]
     (tu/queue-ast! session ast)
     (if eval-service?
       (into {} (map (fn [backend] [backend (attempt #(run-eval-vm backend ast))]))
             [:ast-walker :semantic :stack :register])
       {:ast-walker (attempt #(vm/value (:consumer (tu/run-session session)))),
        :semantic (attempt #(vm/value (vm/run (load-semantic (semantic/create-vm opts)
                                                             datoms)))),
        :stack (attempt #(vm/value (vm/run (dvm/create-vm
                                             (:image (derive/stack-image (projected)))
                                             (assoc opts :contract vm/stack-contract))))),
        :register (attempt #(vm/value (vm/run (rvm/create-vm
                                                (derive/register-image (projected))
                                                (assoc opts
                                                       :contract
                                                       vm/register-contract)))))}))))


(defn run-php
  "`{vm-key rendered}` for `source`, or `{:diagnostics d}`. The optional
   `:execution-budget` is a count of PHP `:loop` and `:call` safepoints."
  ([source] (run-php source {}))
  ([source options]
   (let [initial (lower-program source)
         eval? (some #(= :eval (:construct %)) (:diagnostics initial))
         {:keys [ast diagnostics envelope]}
         (if eval?
           (lower-program source nil {:eval-service? true
                                      :eval-budget? (some? (:execution-budget options))})
           initial)]
     (if diagnostics
       {:diagnostics diagnostics}
       (run-vms (host-registry) ast (assoc options :envelope envelope :eval-service? eval?))))))


(defn run-walker
  "The ast-walker's rendering of `source` alone (fast iteration)."
  [source]
  (let [{:keys [ast diagnostics]} (lower-program source)]
    (if diagnostics
      {:diagnostics diagnostics}
      (let [registry (host-registry)
            session (tu/make-observer-session (tu/create-vm {:modules registry}))]
        (tu/queue-ast! session ast)
        (try (render (vm/value (:consumer (tu/run-session session))))
             (catch Exception e [:thrown (ex-message e)]))))))


(defn run-raw
  "The unit's value on the AST walker, consuming an optional request record
  through the production channel: the harness composes the record as
  `run-main`'s second operand — no AST rewrite involved, and lowering
  never sees request data."
  ([source] (run-raw source nil))
  ([source request]
   (let [{:keys [diagnostics envelope]} (lower-program source request)]
     (if diagnostics
       {:diagnostics diagnostics}
       (let [derived (:envelope (safepoint/derive-envelope
                                  (encoder/project envelope) hooks/ticks-profile :test/ticks))
             ast (vm/semantic-bytecode->ast (nth (:yin/batch derived) (:yin/root derived)))
             session (tu/make-observer-session
                       (tu/create-vm {:modules (host-registry)}))]
         (tu/queue-ast! session ast)
         (vm/value (:consumer (tu/run-session session))))))))

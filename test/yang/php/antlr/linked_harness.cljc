(ns yang.php.antlr.linked-harness
  "The linked profile's composition for the PHP tests (P6, docs/design/
   yang.php.md section 11): the namespace that requires this is the
   publisher of `php`, so it publishes the module once per process into one
   in-memory content store, publishes a fixture's non-entry units beside it
   bottom-up, and serves them to the entry unit's run over a
   `yin.vm.linker.interpreter` composition under `:trusted` derivation.

   A linked run builds the entry unit's task on one of the three vector VMs
   with a link pair, then runs the VM and serves the pair alternately until
   the VM stops blocking, as the REPL's drive does. The AST walker is not a
   backend here: its link of `php` is refused until L-b."
  (:require
    [dao.jing.mem :as mem]
    [yang.php.antlr.lower :as lower]
    [yang.php.antlr.portable-harness :as portable]
    [yang.php.antlr.prelude :as prelude]
    [yang.php.antlr.publication :as publication]
    [yin.vm :as vm]
    [yin.vm.debruijn.register :as rvm]
    [yin.vm.debruijn.stack :as dvm]
    [yin.vm.linker.interpreter :as link]
    [yin.vm.linker.publish :as publish]
    [yin.vm.semantic :as semantic]
    [yin.vm.semantic.derive :as derive]
    [yin.vm.semantic.linearize :as sr-linearize]
    [yin.vm.test-utils :as tu]))


(defn registry
  "The host registry a linked PHP composition registers: the `require`
   handlers, then `cell`, `data` and `integer` under the portable limits."
  []
  (portable/linked-registry))


(def published
  "`php` published once into its own content store:
   `{:store s :result r :address a}`, `r` `publish-module!`'s answer."
  (delay
    (let [store (mem/create-content-mem)
          result (publish/publish-module! store (prelude/module-spec (registry)))]
      {:store store, :result result, :address (:address result)})))


(defn publish-units!
  "`units` (`[{:name path :packet p :strict-types b}]`) published into
   `php`'s own content store, where its pinned requirement already lives:
   `publication/publish-units!`'s answer."
  [units]
  (publication/publish-units! (:store @published) (registry) (:address @published) units))


(defn source
  "The serving composition over the published store: `name-env` names `php`
   and each published module, derivation `:trusted`, the test being the
   publisher."
  [name-env]
  (link/composition {:content-store (:store @published),
                     :name-env (merge {'php (:address @published)} name-env),
                     :derivation :trusted}))


(def backends
  "Each vector backend's root task over a program AST and composition."
  {:semantic (fn [ast opts]
               (semantic/load-ast (semantic/create-vm opts)
                                  (vm/ast->datoms ast)
                                  vm/ast-contract)),
   :stack (fn [ast opts]
            (dvm/create-vm (:image (derive/stack-image (:vector (sr-linearize/project ast))))
                           (assoc opts :contract vm/stack-contract))),
   :register (fn [ast opts]
               (rvm/create-vm (derive/register-image (:vector (sr-linearize/project ast)))
                              (assoc opts :contract vm/register-contract)))})


(defn composition
  "A task's composition over `modules` and the link `pair`."
  [modules pair]
  {:make-stream tu/make-stream,
   :capability-secret tu/secret,
   :secret-source (fn [origin] (str tu/secret "/" (name origin))),
   :primitives vm/primitives,
   :modules modules,
   :link-request (:requests pair),
   :link-response (:responses pair)})


(defn drive
  "Run `task` and serve `pair` from `src` alternately until the task stops
   blocking, at most 80 rounds. Returns `[task pair]`."
  [task pair src]
  (loop [task (vm/run task)
         pair pair
         i 0]
    (if (and (vm/blocked? task) (< i 80))
      (let [pair (:pair (link/serve {:pair pair, :source src}))]
        (recur (vm/run task) pair (inc i)))
      [task pair])))


(defn run-linked
  "The finished task of the linked program `ast` on `backend`, served from
   `src`. A refused link surfaces as the program's own diagnostics."
  [backend ast src]
  (let [pair (link/make-pair)
        task ((get backends backend) ast (composition (registry) pair))]
    (first (drive task pair src))))


(defn name-env
  "The name environment naming every published module of `result`
   (`publish-units!`'s answer): module symbol to address."
  [result]
  (merge (into {} (map (fn [[path address]] [(lower/path-module-symbol path) address]))
               (:addresses result))
         (:bundles result)))


(defn run-fixture
  "`{backend value}` for the multi-unit fixture `units` (`{path packet}`,
   the entry unit `main`, default `lower/entry-path`) on the three vector
   VMs: the non-entry units are published bottom-up, the entry unit
   lowered `:linked` and run. `value` is `run-main`'s map."
  ([units] (run-fixture units lower/entry-path {}))
  ([units main opts]
   (let [entry (get units main)
         others (mapv (fn [[path packet]] {:name path, :packet packet})
                      (dissoc units main))
         result (publish-units! others)]
     (when-not (= :ok (:status result))
       (throw (ex-info "fixture units failed to publish" {:result result})))
     (let [{:keys [ast warnings]}
           (lower/lower-program entry
                                (merge {:prelude :linked,
                                        :path main,
                                        :paths (when (:computed? opts) (keys (:addresses result)))}
                                       (dissoc opts :computed? :src)))
           src (or (:src opts) (source (name-env result)))]
       {:warnings warnings,
        :values (into {}
                      (map (fn [k] [k (vm/value (run-linked k ast src))]))
                      (keys backends))}))))

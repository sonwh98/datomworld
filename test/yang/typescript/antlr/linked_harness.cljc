(ns yang.typescript.antlr.linked-harness
  "The linked profile's composition for the JavaScript tests (T1 step S5,
   docs/design/yang.typescript.md section 13): the namespace that requires
   this is the publisher of `js` and `jssp`, so it publishes each module
   once per process into one in-memory content store and serves them to
   every linked run over a `yin.vm.linker.interpreter` composition under
   `:trusted` derivation (the remediation ruling's R6). The `:verifying`
   evidence is `publish-module!`'s own `link-local` links, which
   `linked-prelude-test` asserts.

   A linked run builds the program's task on one of the three vector VMs
   with a link pair, then runs the VM and serves the pair alternately
   until the VM stops blocking, as the REPL's drive does. The AST walker
   is not a backend here: its link of `js` is refused `:undeclared-free`
   until L-b."
  (:require [yin.vm.semantic.linearize :as sr-linearize]
            [yin.vm.semantic.derive :as derive]
            [dao.jing.mem :as mem]
            [yang.core.call :as call]
            [yang.typescript.antlr.prelude :as prelude]
            [yang.typescript.antlr.safepoint :as safepoint]
            [yin.vm :as vm]
            [yin.vm.data :as data]
            [yin.vm.debruijn-code :as dcode]
            [yin.vm.debruijn-register-code :as rcode]
            [yin.vm.debruijn.register :as rvm]
            [yin.vm.debruijn.stack :as dvm]
            [yin.vm.integer :as integer]
            [yin.vm.linker.interpreter :as link]
            [yin.vm.linker.publish :as publish]
            [yin.vm.module :as module]
            [yin.vm.semantic :as semantic]
            [yin.vm.test-utils :as tu]))


(def integer-limits
  "The ordinary JavaScript composition's `integer` limits."
  {::integer/max-bits 100000, ::integer/max-digits 4300})


(defn registry
  "The full host registry a linked JavaScript composition registers: the
   `require` handler, then `cell`, `data`, `integer` under `limits`, and
   `stream` — the hook module `jssp` polls a signal stream, so the
   safepointed composition needs it even though `js` does not."
  ([] (registry integer-limits))
  ([limits]
   (-> (module/default-registry)
       module/register-cell-module
       (data/register-data-module {::data/max-items 1048576})
       (prelude/register-integer-module limits)
       module/register-stream-module)))


(def published
  "`js` published once into its own content store:
   `{:store s :result r :address a :call-address ca}`. `yang.call` is
   published first so `js` pins its shared calling runtime."
  (delay
    (let [store (mem/create-content-mem)
          call-result (publish/publish-module! store (call/module-spec (registry)))
          result (publish/publish-module! store
                                          (prelude/module-spec (registry)
                                                               (:address call-result)))]
      {:store store,
       :result result,
       :address (:address result),
       :call-address (:address call-result)})))


(def published-jssp
  "`jssp` published once into `js`'s own content store, which its pinned
   requirement already lives in: the same shape as `published`."
  (delay
    (let [{:keys [store address]} @published
          result (publish/publish-module!
                   store (safepoint/module-spec (registry) address))]
      {:store store, :result result, :address (:address result)})))


(defn source
  "The serving composition over the published store: `name-env` defaults
   to `{js <address> yang.call <address>}`, derivation `:trusted`, the test
   being the publisher. `jssp`-serving sources pass their own `name-env`."
  ([] (source {'js (:address @published),
               'yang.call (:call-address @published)}))
  ([name-env]
   (link/composition {:content-store (:store @published),
                      :name-env name-env,
                      :derivation :trusted})))


(defn safepoint-source
  "The serving composition over the published modules: `jssp` requires
   `js`, and `js` requires `yang.call`."
  []
  (source {'js (:address @published),
           'jssp (:address @published-jssp),
           'yang.call (:call-address @published)}))


(defn publish-guest-module!
  "One guest module's `spec` (lower/module-spec's) published into `js`'s
   own content store, where its pinned requirements already live:
   `publish-module!`'s answer. Guest modules are tiny beside `js`, so a
   test may publish its own; publish imports bottom-up, the `deps` map
   naming each import's address."
  [spec]
  (publish/publish-module! (:store @published) spec))


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


(def continues
  "Each vector backend's admission of the next top-level unit to a halted
   task, as `yin.repl` admits an input line: the task keeps its store,
   heap, module registry and module stores."
  {:semantic (fn [task ast]
               (semantic/load-ast task (vm/ast->datoms ast) vm/ast-contract)),
   :stack (fn [task ast]
            (let [img (:image (derive/stack-image (:vector (sr-linearize/project ast))))
                  attached (dvm/attach-image task img vm/stack-contract)]
              (assoc attached
                     :pc (dvm/absolute-pc attached [(dcode/image-hash img) 0])
                     :frames [] :stack [] :continuation []
                     :halted? false :blocked? false :value nil))),
   :register (fn [task ast]
               (let [img (derive/register-image (:vector (sr-linearize/project ast)))
                     attached (rvm/attach-image task img vm/register-contract)
                     pc (rvm/absolute-pc attached
                                         [(rcode/register-hash img) 0])
                     body (some #(when (= pc (:start %)) %)
                                (:bodies (:segment attached)))]
                 (assoc attached
                        :pc pc :frames [] :continuation []
                        :registers (vec (repeat (:registers body) nil))
                        :halted? false :blocked? false :value nil)))})


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
  "Run `task` and serve `pair` from `src` alternately until the task
   stops blocking, at most 40 rounds. Returns `[task pair]`."
  [task pair src]
  (loop [task (vm/run task)
         pair pair
         i 0]
    (if (and (vm/blocked? task) (< i 40))
      (let [pair (:pair (link/serve {:pair pair, :source src}))]
        (recur (vm/run task) pair (inc i)))
      [task pair])))


(defn run-linked
  "The finished task of the linked program `ast` on `backend`. Options:
   `:modules` (default the full registry), `:link-source` (default the
   `:trusted` composition over `js`), and `:prep`, applied to the task
   before it runs (a composition handing the task its streams, binding
   `jssp`'s signal stream among them). A refused link throws as the task's
   own error."
  ([backend ast] (run-linked backend ast {}))
  ([backend ast {:keys [modules link-source prep]}]
   (let [pair (link/make-pair)
         task ((get backends backend)
               ast
               (composition (or modules (registry)) pair))]
     (first (drive (if prep (prep task) task)
                   pair (or link-source (source)))))))


(defn runners-under
  "Each vector VM's runner of a linked program over `modules`: the
   program's value."
  [modules]
  (into {}
        (map (fn [k]
               [k (fn [ast]
                    (vm/value (run-linked k ast {:modules modules})))]))
        (keys backends)))


(def runners
  "The linked runners under the ordinary limits."
  (runners-under (registry)))

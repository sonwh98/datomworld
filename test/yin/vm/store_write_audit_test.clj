(ns yin.vm.store-write-audit-test
  "The store-write audit (Rule R, the store-key invariant): every program
   store write goes through `yin.vm.engine/store-put`, which refuses a
   reserved key; every other store mutation it detects is state
   construction on an allowlist. This gate reads every source file under
   `src` as forms (tools.reader, reader conditionals preserved, so every
   branch is read: `:clj`, `:cljs`, `:cljd`, and `:default`, in both
   `#?` and splicing `#?@`, each pinned by a fixture whose write is only
   in that branch) and fails on any detected site outside the
   allowlist, on any allowlisted site that no longer exists, and on any
   source file it cannot read. The allowlist is exact for what the
   detector sees; what it sees is exactly this:

   - A call whose head is one of `mutation-heads` and that touches a store:
     a `:store` argument (`(assoc vm :store x)`, `(update vm :store ...)`),
     a key path whose first key is `:store` or that contains it
     (`(assoc-in vm [:store k] v)`), or a store as its target. A store is a
     symbol named `store`, `new-store`, `store0`, ...; `(:store x)`,
     `(get x :store)`, or `(get-in x [:store ...])`; or a local alias of
     one of these.
   - A local alias: a symbol bound to a store by `let`, `let*`, `loop`,
     `loop*`, `when-let`, `if-let`, `when-some`, `if-some`, or
     `binding`, directly or through an earlier alias in the same or an
     enclosing binding form, and a map destructuring key bound to `:store`
     (`{heap :store}`). The alias is scoped to the binding form's body.
   - A pipeline whose threaded value is a store: `->`, `->>`, `some->`,
     `some->>`, `cond->`, `cond->>` (forms only, tests skipped), and
     `doto`. The value is a store from the start when the pipeline's
     initial value is a store (a store symbol, `(:store x)`,
     `(get x :store)`, `(get-in x [:store ...])`, or a scoped alias), or
     from the first step that moves onto one (`:store`, `(:store)`,
     `(get :store)`, `(get-in [:store ...])`). Every mutation-head step
     from then on is a site. This is conservative: a later step that leaves
     the store (`(get 'k)`) does not end it.
   - `as->`: its name is a store alias from the start when the initial
     value is a store, and after any step that is one.
   - A mutation step of any pipeline whose own arguments touch a store
     (`(->> v (assoc (:store vm) k))`), found by the ordinary walk.
   - Every `store-put` call, and every map literal with a `:store` key (a
     map rebuilt around the store).

   A site is identified by its file, the name of its top-level form, and
   its head, with a count, so the allowlist does not move with line
   numbers. `negative-fixtures` pins every covered form above, each
   pipeline shape both seeded with an extracted store and with a
   let-bound alias.

   Residual, NOT detected, pinned by `residual-fixtures`: a store passed
   across a function boundary and written under the parameter's own name
   (`(defn g [heap v] (assoc heap k v))` called as `(g (:store vm) v)`),
   a store carried inside another data structure (a map, an atom, a
   collection taken apart by `when-first`) and written from there, and a
   mutation built dynamically (`apply`, `partial`, `comp`, a head bound
   to another name). Also outside the detector, with no fixture:
   transients, host interop, and macros that expand to a write. Those
   need review; this gate does not prove them absent. JVM only: it reads
   source files."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.reader :as reader]
            [clojure.tools.reader.reader-types :as rt]))


(declare walk-sites)


(def ^:private mutation-heads
  #{"assoc" "assoc-in" "update" "update-in" "merge" "merge-with" "into"
    "dissoc" "select-keys" "conj" "swap!" "reset!" "vswap!" "vreset!"
    "store-put"})


(def ^:private binding-heads
  #{"let" "let*" "loop" "loop*" "when-let" "if-let" "when-some" "if-some"
    "binding"})


(def ^:private threading-heads
  "Pipelines whose steps receive the threaded value: `doto` receives the
   initial value at every step."
  #{"->" "->>" "some->" "some->>" "doto"})


(def ^:private conditional-threading-heads
  "Pipelines whose steps alternate test and form."
  #{"cond->" "cond->>"})


(defn- store-symbol?
  [x]
  (and (symbol? x)
       (boolean (re-find #"(^|-)store\d*'?$" (name x)))))


(defn- store-read?
  "`(:store x)`, `(get x :store)`, or `(get-in x [:store ...])`."
  [x]
  (and (seq? x)
       (or (= :store (first x))
           (and (= 'get (first x)) (= :store (nth x 2 nil)))
           (and (= 'get-in (first x))
                (vector? (nth x 2 nil))
                (= :store (first (nth x 2)))))))


(defn- store-expr?
  [x aliases]
  (or (store-symbol? x) (contains? aliases x) (store-read? x)))


(defn- head-name
  [form]
  (when (and (seq? form) (symbol? (first form)))
    (name (first form))))


(defn- mutation?
  [form aliases]
  (let [[head & args] form]
    (and (contains? mutation-heads (head-name form))
         (or (= "store-put" (name head))
             (some #(= :store %) args)
             (some #(and (vector? %) (some #{:store} %)) args)
             (store-expr? (first args) aliases)))))


(defn- bound-aliases
  "The symbols `lhs` binds to a store, given its `init`."
  [lhs init aliases]
  (cond (symbol? lhs) (if (store-expr? init aliases) #{lhs} #{})
        (map? lhs) (into #{}
                         (keep (fn [[k v]]
                                 (when (and (symbol? k) (= :store v)) k)))
                         lhs)
        :else #{}))


(defn- through-store?
  "A pipeline step that moves the threaded value onto a store: `:store`,
   `(:store)`, `(get :store)`, or `(get-in [:store ...])`."
  [step]
  (or (= :store step)
      (and (seq? step) (= :store (first step)))
      (and (seq? step) (= 'get (first step)) (= :store (second step)))
      (and (seq? step) (= 'get-in (first step))
           (vector? (second step)) (= :store (first (second step))))))


(defn- mutation-step
  "The head string of a mutation-head step, or nil."
  [step]
  (cond (and (seq? step) (contains? mutation-heads (head-name step)))
        (str (first step))
        (and (symbol? step) (contains? mutation-heads (name step)))
        (str step)))


(defn- threaded-sites
  "Mutation-head steps of a pipeline once its threaded value is a store:
   from the start when the initial value is a store (`store-expr?`, a
   scoped alias included), or from the first step that moves onto one
   (`through-store?`). Conservative: a later step that leaves the store
   does not end it. `cond->`/`cond->>` steps are their forms, skipping
   the tests."
  [form aliases]
  (let [h (head-name form)
        [_ init & more] (when (seq? form) form)
        steps (cond (contains? threading-heads h) more
                    (contains? conditional-threading-heads h)
                    (take-nth 2 (rest more))
                    :else nil)]
    (when (some? steps)
      (loop [store? (store-expr? init aliases)
             steps steps
             acc []]
        (if-let [[step & more] (seq steps)]
          (recur (or store? (through-store? step))
                 more
                 (if-let [site (when store? (mutation-step step))]
                   (conj acc site)
                   acc))
          acc)))))


(defn- as->-sites
  "`(as-> init name & steps)`: `name` is a store alias from the start when
   `init` is a store, and from the step after any step that is one."
  [form aliases]
  (let [[_ init nm & steps] form]
    (loop [store? (store-expr? init aliases)
           steps steps
           acc (vec (walk-sites init aliases))]
      (if-let [[step & more] (seq steps)]
        (let [al (if store? (conj aliases nm) aliases)]
          (recur (or store? (store-expr? step al))
                 more
                 (into acc (walk-sites step al))))
        acc))))


(defn- walk-sites
  "Every site inside `form` under the store `aliases` in scope: a head
   string for a mutation, `:map` for a map literal with a `:store` key."
  [form aliases]
  (cond
    (reader-conditional? form) (walk-sites (:form form) aliases)
    (and (= "as->" (head-name form)) (symbol? (nth form 2 nil)))
    (as->-sites form aliases)
    (and (contains? binding-heads (head-name form))
         (vector? (second form)))
    (let [[_ bindings & body] form
          [sites inner] (reduce (fn [[sites al] [lhs init]]
                                  [(into sites (walk-sites init al))
                                   (into al (bound-aliases lhs init al))])
                                [[] aliases]
                                (partition 2 bindings))]
      (into sites (mapcat #(walk-sites % inner)) body))
    (seq? form)
    (concat (when (mutation? form aliases) [(str (first form))])
            (threaded-sites form aliases)
            (mapcat #(walk-sites % aliases) form))
    (map? form)
    (concat (when (contains? form :store) [:map])
            (mapcat #(walk-sites % aliases) (concat (keys form) (vals form))))
    (coll? form) (mapcat #(walk-sites % aliases) form)
    :else nil))


(defn- form-sites
  [form]
  (walk-sites form #{}))


(defn- top-level-name
  [form]
  (if (and (seq? form) (symbol? (second form)))
    (str (second form))
    (pr-str (if (seq? form) (first form) form))))


(defn- alias-map
  "Every `:as alias` in the source, resolved to itself: enough for
   tools.reader to read `::alias/kw` without loading the namespace."
  [text]
  (into {}
        (map (fn [[_ a]] [(symbol a) (symbol a)]))
        (re-seq #":as\s+([A-Za-z0-9.*+!_?<>=-]+)" text)))


(defn sites-in-source
  "`{[top-level-name head] count}` of the store mutations in `text`, or
   `{[:unreadable] 1}` when the text cannot be read as forms."
  [text]
  (try
    (binding [reader/*alias-map* (alias-map text)
              reader/*default-data-reader-fn* (fn [_ v] v)
              *ns* (create-ns 'yin.vm.store-write-audit-reader)]
      (let [rdr (rt/indexing-push-back-reader text)
            opts {:read-cond :preserve, :eof ::eof}]
        (loop [acc {}]
          (let [form (reader/read opts rdr)]
            (if (= ::eof form)
              acc
              (recur (reduce (fn [acc head]
                               (update acc [(top-level-name form) head]
                                       (fnil inc 0)))
                             acc
                             (form-sites form))))))))
    (catch Exception e
      {[:unreadable (ex-message e)] 1})))


(defn- source-files
  []
  (->> (file-seq (io/file "src"))
       (filter #(.isFile ^java.io.File %))
       (map #(.getPath ^java.io.File %))
       (filter #(re-find #"\.clj[csd]?$" %))
       sort))


(defn source-sites
  "file -> `{[top-level-name head] count}` for every file with a site."
  []
  (into {}
        (keep (fn [f]
                (let [sites (sites-in-source (slurp f))]
                  (when (seq sites) [f sites]))))
        (source-files)))


(def allowlist
  "file -> {[top-level-name head] count}, with the reason each may write."
  {"src/cljc/yin/vm/engine.cljc"
   {;; the one program write, and `put-active`, which routes every program
    ;; write -- definitions, direct store instructions, the :vm/store-put
    ;; effect -- to the task's store or the running module's store, both
    ;; through store-put
    ["store-put" "assoc"] 1
    ["put-active" "assoc"] 1
    ["put-active" "store-put"] 2
    ;; step 5b's receiver: the live store read, never written. Stream
    ;; handles, cursor cells, and their advances are written to the private
    ;; :resources table, never the store (yin.vm.linker.md 7.3, r8).
    ["discharge-defect" :map] 1}
   ;; the constructor merge (checked); every kernel's program writes --
   ;; definitions and direct store instructions -- go through
   ;; engine/put-active
   "src/cljc/yin/vm/debruijn/stack.cljc"
   {["create-vm" :map] 1
    ["create-vm" "merge"] 1}
   "src/cljc/yin/vm/debruijn/register.cljc"
   {["create-vm" :map] 1
    ["create-vm" "merge"] 1}
   ;; construction: the FFI pair under minted keyword keys
   "src/cljc/yin/vm.cljc"
   {["empty-state" :map] 1}
   ;; the waitset library's result carries the store its :advance wrote
   "src/cljc/dao/stream/waitset.cljc"
   {["check" :map] 1}
   ;; a dao.space published-index handle, not a VM store
   "src/cljc/dao/space/query.cljc"
   {["open-published!" :map] 1}
   ;; the REPL history keys *1 *2 *3 (fixed symbols, none reserved), and
   ;; the expander consumer's macro store
   "src/cljc/yin/repl.cljc"
   {["inject-last-value" "update"] 1
    ["make-expander" :map] 1}
   ;; the content source descriptor: `:store` names a dao.jing byte-store
   ;; handle the linker interpreter serves content from, never a VM store
   "src/cljc/yin/repl/link.cljc"
   {["composition" :map] 1}
   ;; the handoff demo: shipped definitions, checked
   "src/cljc/datomworld/demo/continuation_handoff.cljc"
   {["datoms->semantic-vm" "update"] 1}
   ;; a JVM demo moving one VM's whole store to another
   "src/clj/yin/demo.clj"
   {["run-demo" "assoc"] 1}
   ;; display projections of a VM's store in the browser demos
   "src/cljs/datomworld/demo/compilation_pipeline.cljs"
   {["walker-cesk" :map] 1}
   "src/cljs/datomworld/demo/continuation_stream.cljs"
   {["vm->cesk" :map] 1}
   ;; the expander's macro store: harvest refuses the reserved key,
   ;; post-harvest installs only harvested names, make-ctx refuses a
   ;; seeded store binding it, expand-batch threads the checked store
   "src/cljc/yin/vm/macro.cljc"
   {["harvest" "assoc"] 1
    ["harvest" "dissoc"] 1
    ["post-harvest" "assoc"] 1
    ["post-harvest" "dissoc"] 1
    ["make-ctx" :map] 1
    ["expand-batch" "assoc"] 2}})


(deftest every-store-mutation-is-on-the-allowlist
  (is (= allowlist (source-sites))
      "a new store mutation must go through engine/store-put or join the
       allowlist with its reason; an unreadable file fails too"))


(def negative-fixtures
  "Common store-mutation forms outside the allowlist; each must be caught."
  ["(defn f [state v] (assoc-in state [:store 'yin/def] v))"
   "(defn f [vm v] (update vm :store assoc 'x v))"
   "(defn f [vm v] (update-in vm [:store 'x] (constantly v)))"
   "(defn f [vm v] (assoc vm :store (conj (:store vm) ['x v])))"
   "(defn f [vm] (dissoc (:store vm) 'x))"
   "(defn f [vm ks] (select-keys (get vm :store) ks))"
   "(defn f [store v] (into store {'x v}))"
   "(defn f [new-store v]\n  (merge\n    new-store\n    {'x v}))"
   "(defn f [a v] (swap! a assoc-in [:store 'x] v))"
   "(defn f [a v] (reset! a {:store v}))"
   "(defn f [vm m] (merge-with merge vm {:store m}))"
   "(defn f [vm v] (engine/store-put (:store vm) 'x v))"
   "(defn f [vm v] #?(:clj (assoc-in vm [:store 'x] v) :cljs vm))"
   ;; one fixture per host branch: the write is only in that branch
   "(defn f [vm v] #?(:clj vm :cljs (assoc-in vm [:store 'x] v)))"
   "(defn f [vm v] #?(:clj vm :cljd (assoc-in vm [:store 'x] v)))"
   "(defn f [vm v] #?(:clj vm :default (assoc-in vm [:store 'x] v)))"
   "(defn f [vm v] [#?@(:clj [vm] :cljs [(assoc-in vm [:store 'x] v)])])"
   "(defn f [vm v] [#?@(:clj [vm] :cljd [(assoc-in vm [:store 'x] v)])])"
   ;; local aliases of a store value
   "(defn f [vm v] (let [heap (:store vm)] (assoc heap 'yin/def v)))"
   "(defn f [vm v] (let [h (get vm :store) h2 h] (assoc h2 'x v)))"
   "(defn f [vm v] (let [h (get-in vm [:store])] (merge h {'x v})))"
   "(defn f [vm v] (when-let [h (:store vm)] (conj h ['x v])))"
   "(defn f [vm v] (if-let [h (:store vm)] (dissoc h 'x) vm))"
   "(defn f [vm] (loop [h (:store vm)] (recur (assoc h 'x 1))))"
   "(defn f [vm v] (let [{heap :store} vm] (assoc heap 'x v)))"
   "(defn f [vm v] (let [h (:store vm)] (let [g 1] (assoc h 'x g))))"
   ;; a pipeline threaded through the store
   "(defn f [vm v] (-> vm :store (assoc 'x v)))"
   "(defn f [vm v] (some-> vm (get :store) (update 'x inc)))"
   "(defn f [vm v] (->> vm :store (merge {'x v})))"
   ;; a pipeline seeded with an extracted store
   "(defn f [vm v] (-> (:store vm) (assoc 'yin/def v)))"
   "(defn f [vm v] (->> (:store vm) (merge {'x v})))"
   "(defn f [vm v] (some-> (get vm :store) (assoc 'x v)))"
   "(defn f [vm v] (some->> (get-in vm [:store]) (merge {'x v})))"
   "(defn f [store v] (-> store (dissoc 'x)))"
   ;; a pipeline seeded with a let-bound alias
   "(defn f [vm v] (let [h (:store vm)] (-> h (assoc 'x v))))"
   "(defn f [vm v] (let [h (:store vm)] (->> h (merge {'x v}))))"
   "(defn f [vm v] (let [h (:store vm)] (some-> h (dissoc 'x))))"
   "(defn f [vm v] (let [h (:store vm)] (some->> h (merge {'x v}))))"
   ;; the other threading and seeding shapes
   "(defn f [vm v] (cond-> (:store vm) v (assoc 'x v)))"
   "(defn f [vm v] (cond->> (:store vm) v (merge {'x v})))"
   "(defn f [vm v] (cond-> vm v :store v (assoc 'x v)))"
   "(defn f [vm v] (as-> (:store vm) s (assoc s 'x v)))"
   "(defn f [vm v] (as-> vm m (:store m) (assoc m 'x v)))"
   "(defn f [vm v] (let [h (:store vm)] (as-> h s (conj s ['x v]))))"
   "(defn f [vm v] (doto (:store vm) (assoc 'x v)))"
   ;; a store passed as the first argument of a threaded call
   "(defn f [vm v] (->> v (assoc (:store vm) 'x)))"
   "(defn f [vm v] (let [h (:store vm)] (-> 'x (as-> k (assoc h k v)))))"
   ;; every mutation head and store-symbol spelling the docstring names
   "(defn f [store v] (vswap! store assoc 'x v))"
   "(defn f [store] (vreset! store {}))"
   "(defn f [store0 v] (conj store0 ['x v]))"
   ;; every binding form the docstring names
   "(defn f [vm v] (let* [h (:store vm)] (assoc h 'x v)))"
   "(defn f [vm] (loop* [h (:store vm)] (recur (dissoc h 'x))))"
   "(defn f [vm v] (when-some [h (:store vm)] (assoc h 'x v)))"
   "(defn f [vm v] (if-some [h (:store vm)] (assoc h 'x v) vm))"
   "(defn f [vm v] (binding [h (:store vm)] (assoc h 'x v)))"
   ;; every step that moves a pipeline onto the store
   "(defn f [vm v] (-> vm (:store) (assoc 'x v)))"
   "(defn f [vm v] (-> vm (get-in [:store]) (dissoc 'x)))"
   ;; the remaining pipeline shapes seeded with a let-bound alias
   "(defn f [vm v] (let [h (:store vm)] (cond-> h v (assoc 'x v))))"
   "(defn f [vm v] (let [h (:store vm)] (cond->> h v (merge {'x v}))))"
   "(defn f [vm v] (let [h (:store vm)] (doto h (assoc 'x v))))"])


(def residual-fixtures
  "Store writes this gate documents as NOT detected (the namespace
   docstring's residual). Pinned so the documented guarantee matches the
   code: if one of these starts being detected, move it to
   `negative-fixtures` and narrow the docstring."
  ["(defn g [heap v] (assoc heap 'x v)) (defn f [vm v] (g (:store vm) v))"
   "(defn f [vm v] (let [box {:s (:store vm)}] (assoc (:s box) 'x v)))"
   "(defn f [vm v] (apply assoc (:store vm) ['x v]))"
   "(defn f [vm v] (swap! (atom (:store vm)) assoc 'x v))"
   "(defn f [vms v] (when-first [h (map :store vms)] (assoc h 'x v)))"
   "(defn f [vm v] ((partial assoc (:store vm)) 'x v))"
   "(defn f [vm v] ((comp #(assoc % 'x v) :store) vm))"
   "(defn f [vm v] (let [w assoc] (w (:store vm) 'x v)))"])


(deftest residual-forms-are-not-claimed
  (doseq [src residual-fixtures]
    (testing src
      (is (empty? (remove #(= [:map] (rest %))
                          (keys (sites-in-source src))))
          "outside the detector: covered by review, not by this gate"))))


(deftest negative-fixtures-fail-the-audit
  (doseq [src negative-fixtures]
    (testing src
      (let [sites (sites-in-source src)]
        (is (seq sites) "the mutation is detected")
        (is (not-any? #(= :unreadable (first %)) (keys sites)))))))

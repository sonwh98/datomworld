(ns yang.latte-oracle-test
  "Pinned upstream LaTTe differential harness, slices 1-9 of the Yang gap
   program. A executes the original library on Clojure. B executes explicit
   portability source on Clojure; C compiles it through Yang for the walker
   and semantic VMs. Slice 9 fails closed on the full kernel corpus. The
   legacy classifier records historical blockers;
   the context module additionally checks free-symbol admission before C.
   Adaptations, operation sequences and scope departures live beside the
   manifest in yang/latte_oracle/."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [dao.jing.cbor :as cbor]
            [clojure.tools.reader :as r]
            [clojure.tools.reader.reader-types :as rt]
            [yang.clojure :as yang]
            [yang.clojure-test :as compiler-test]
            [yang.core.call :as call]
            [yang.core.uast :as u]
            [yin.vm :as vm]
            [yin.vm.test-utils :as tu]
            [yin.vm.data :as data]
            [yin.vm.module :as module]
            [yin.vm.encoder :as encoder]
            [yin.vm.ucf.rows :as rows]
            [yin.vm.ucf.rows-support :as rows-support]
            [yin.vm.values :as values]
            [yin.vm.semantic :as semantic]
            [yin.vm.semantic.derive :as derive]
            [yin.vm.semantic.linearize :as linearize]
            [yin.vm.ucf :as ucf]))


(def ^:private manifest
  (edn/read-string (slurp (io/resource "yang/latte_oracle/manifest.edn"))))


(defn- ns-declaration-aliases
  "Alias -> full ns symbol, taken from the file's own (ns ...) declaration —
   never invented: ::alias/kw must read as :that.real.ns/kw."
  [ns-form]
  (let [alias-map (atom {})
        consume-spec (fn [spec]
                       (when (sequential? spec)
                         (let [lib (first spec)]
                           (loop [xs (rest spec)]
                             (when (seq xs)
                               (if (= :as (first xs))
                                 (do (swap! alias-map assoc (second xs) lib)
                                     (recur (drop 2 xs)))
                                 (recur (next xs))))))))]
    (loop [clauses (rest (rest ns-form))]
      (when (seq clauses)
        (let [clause (first clauses)]
          (if (and (seq? clause) (= :require (first clause)))
            (do (loop [reqs (next clause)]
                  (let [spec (first reqs)]
                    (when (and (not (nil? spec)) (not (keyword? spec)))
                      (consume-spec spec)
                      (recur (next reqs)))))
                (recur (next clauses)))
            (recur (next clauses))))))
    @alias-map))


(defn- read-forms
  "Read one latte-kernel source file with reader conditionals resolved for
   :clj, ::alias/kw keywords resolved against the file's own ns declaration,
   and ::kw resolved against the file's own namespace — so the C leg compiles
   the same keyword identities the upstream A leg runs."
  [ns-sym]
  (let [f (io/resource (str (str/replace (name ns-sym) "." "/") ".cljc"))
        pr (rt/source-logging-push-back-reader (java.io.PushbackReader. (io/reader f)) 1 (str f))
        ns-form (r/read {:read-cond :allow :features #{:clj} :eof ::eof} pr)
        declared-name (second ns-form)
        aliases (ns-declaration-aliases ns-form)]
    (binding [r/*alias-map* aliases
              *ns* (the-ns (create-ns declared-name))]
      (loop [acc []]
        (let [form (r/read {:read-cond :allow :features #{:clj} :eof ::eof} pr)]
          (if (= form ::eof) acc (recur (conj acc form))))))))


(def ^:private all-forms
  (into {} (map (fn [ns-sym] [ns-sym (read-forms ns-sym)])
                (distinct (map :ns manifest)))))


(def ^:private top-forms
  (into {} (map (fn [{:keys [ns kind name]}]
                  [[ns name] (first (filter #(and (seq? %)
                                                  (= kind (first %))
                                                  (= name (second %)))
                                            (get all-forms ns)))]))
        manifest))


(def ^:private scanned-symbols
  #{'loop 'recur 'case 'throw 'ex-info 'atom 'swap! 'reset! 'lazy-seq 'gensym
    'apply 'comp 'partial 'reduced 'reduce 'map 'mapv 'filter 'remove 'into
    'concat 'merge 'str 'symbol 'keyword 'vector 'seq 'nth 'nthnext 'assoc-in
    'count 'name 'when-let 'if-let 'when-some 'if-some 'cond})


(defn- walk-symbols
  [form]
  (cond
    (seq? form) (if (and (= 'quote (first form)) (= 2 (count form)))
                  #{}
                  (mapcat walk-symbols form))
    (vector? form) (mapcat walk-symbols form)
    (map? form) (mapcat walk-symbols (concat (keys form) (vals form)))
    (set? form) (mapcat walk-symbols form)
    (symbol? form) (if (namespace form) #{} #{form})
    :else #{}))


(defn- destructuring?
  [binding]
  (not (symbol? binding)))


(defn- classify-form
  [form]
  (let [[kind _name & body-ops] form
        fs (atom (into #{} (walk-symbols form)))]
    (when (and (= 'defn kind) (seq body-ops))
      (let [second-op (first body-ops)]
        (cond
          (seq? second-op) (swap! fs conj :multi-arity)
          (not (vector? second-op)) (swap! fs conj :docstring)
          :else (when (some destructuring? second-op)
                  (swap! fs conj :destructuring)))))
    (when (and (= 'defn kind) (vector? (first body-ops)))
      ;; loop/recur inside the params+body
      (when (some #(contains? % 'recur) (map #(into #{} (walk-symbols %)) (clojure.core/rest body-ops)))
        (swap! fs conj :recur)))
    (when (contains? @fs 'loop) (swap! fs conj :loop))
    (set (filter #(or (contains? scanned-symbols %) (keyword? %)) @fs))))


(defn- host-module
  "Register `fns` as host module `module-name`, every export `:pure`."
  [registry module-name fns arities]
  (module/register-host-module
    registry module-name fns
    (into {} (map (fn [[op]] [op (vm/primitive-profile op :pure (get arities op [1]) #{} :none)]))
          fns)))


(def ^:private kind-fns
  "The irreducible kind and name primitives (yang.clojure/kind-module), plus
   the two host Var primitives the throw-site fixtures reach."
  (assoc yang/kind-module 'var? var? 'resolve resolve))


(defn- oracle-registry
  "The stream/data modules, kind primitives, and portable outcome adapters.
   Slices 1-7 retain their host Var probes; context modules supply only the
   callback-free kind exports and cannot consult host namespaces."
  ([] (oracle-registry kind-fns))
  ([kinds]
   (let [portable (into {}
                        (map (fn [[source _]]
                               (let [op (symbol (name source))]
                                 [op (yang/outcome-adapter data/refusal-message
                                                           (get data/data-module op))])))
                        yang/library-adapters)]
     (-> (module/default-registry)
         module/register-stream-module
         module/register-cell-module
         data/register-data-module
         (host-module 'kind kinds yang/kind-arities)
         (host-module 'portable portable
                      (into {} (map (fn [op] [op (:yin.k/arities (get data/data-profiles op))]))
                            (keys portable)))))))


(def ^:private arg-vector-opts
  "The argument-vector convention with the outcome model, the guest library's
   adapters and the VM primitives' published profiles as the native names."
  {:convention :arg-vector, :outcomes true
   :adapters yang/library-adapters
   :native vm/primitive-profiles})


(defn- result-shape
  "Collection families and domain metadata, excluding reader provenance."
  [x]
  [(not-empty (apply dissoc (meta x) [:source :file :line :column :end-line :end-column]))
   (cond
     (map? x) (into {} (map (fn [[k v]] [k [(result-shape k) (result-shape v)]])) x)
     (set? x) (into #{} (map (fn [v] [v (result-shape v)])) x)
     (sequential? x) [(if (vector? x) :vector :sequence) (mapv result-shape x)]
     :else :scalar)])


(defn- unbound-ast-symbols
  "Admission for the portable context module: every variable is a lexical
   binder, declared guest function, or explicitly supplied data primitive."
  [ast program]
  (let [globals (into #{'yin/def
                        ;; the compiler's own intra-unit dispatcher, bound by
                        ;; the compiled preamble; the site stamping reuses it
                        'dsg__intra_call}
                      (concat (keys vm/primitive-profiles)
                              ;; the continuation lane's clj/ family, emitted
                              ;; inline by compile-program under :outcomes true
                              yang/continuation-family-names
                              (map first call/definitions)
                              (for [form program :when (#{'def 'defn 'defn-} (first form))]
                                (second form))
                              (for [[module exports] {'kind yang/kind-module
                                                      'cell module/cell-module
                                                      'data data/data-module
                                                      'stream module/stream-module
                                                      'portable (into {} (map (fn [[_ v]] [(symbol (name v)) nil]))
                                                                      yang/library-adapters)}
                                    export (keys exports)]
                                (symbol (str module) (name export)))))]
    (letfn [(scan
              [node bound]
              (case (:type node)
                :variable (if (or (contains? globals (:name node))
                                  (contains? bound (:name node))) #{} #{(:name node)})
                :literal #{}
                :vm/current-continuation #{}
                :lambda (scan (:body node) (into bound (:params node)))
                :application (into (scan (:operator node) bound)
                                   (mapcat #(scan % bound)) (:operands node))
                :if (into #{} (mapcat #(scan (get node %) bound))
                          [:test :consequent :alternate])
                ;; The rejection carries the node itself (print-bounded): a
                ;; bare symbol or unknown shape names its emitter directly.
                (throw (ex-info "Unexpected AST in context admission"
                                {:type (:type node)
                                 :node (binding [*print-length* 40 *print-level* 8]
                                         (pr-str node))}))))]
      (scan ast #{}))))


(defn- run-outcome
  "Compile `forms` plus `call-form` (with the library definitions they need)
   and return the RAW outcome of the call: the value channel carries
   [tag payload]. Every fixture runs on walker and semantic lanes under the
   same finite instruction budget."
  ([forms call-form] (run-outcome forms call-form (oracle-registry)))
  ([forms call-form modules] (run-outcome forms call-form modules false))
  ([forms call-form modules closed?]
   (let [program (into [] (concat forms [call-form]))
         program (into (yang/library-for program) program)
         ast (yang/compile-program program true arg-vector-opts)
         _ (when closed?
             (let [unbound (unbound-ast-symbols ast program)]
               (when (seq unbound)
                 (throw (ex-info "Unadmitted context dependencies" {:symbols unbound})))
               (is (empty? unbound) "context program is closed over its supplied modules")))
         walker (compiler-test/run-guest-ast ast modules :walker 1000000)
         semantic (compiler-test/run-guest-ast ast modules :semantic 1000000)]
     (is (= walker semantic) (str "oracle lanes agree: " (pr-str call-form)))
     (is (= (result-shape walker) (result-shape semantic))
         (str "oracle lane shapes and metadata agree: " (pr-str call-form)))
     walker)))


(defn- run-on-vm
  "run-outcome, unwrapped: the call's value, or its error outcome thrown as
   ex-info carrying the error data."
  [forms call-form]
  (let [[tag payload] (run-outcome forms call-form)]
    (if (= :yin.k/ok tag)
      payload
      (throw (ex-info (str "guest error: " (pr-str payload)) payload)))))


(def ^:private clean-forms
  (for [{:keys [ns name]} manifest
        :let [f (get top-forms [ns name])]
        :when (and f (empty? (classify-form f)))]
    f))


(def ^:private fixtures
  "qualified-fn | args for the A/C differential. The four anchors the
   architect named first, plus leaf functions classified :clean."
  {'latte-kernel.defenv/mkenv [[]]
   'latte-kernel.utils/vectorn? [[1 2 3 4] 2]})


(defn- upstream-fn
  [qsym]
  (requiring-resolve qsym))


(deftest test-oracle-classification
  (testing "every manifest entry has source"
    (is (every? (fn [{:keys [ns name]}] (get top-forms [ns name])) manifest)))
  (testing "every binding has an explicit admission decision and reason"
    (is (every? #(#{:admitted :excluded} (:disposition %)) manifest))
    (is (every? #(and (string? (:admission-reason %)) (seq (:admission-reason %))) manifest)))
  (testing "architect anchor classifications (slice 1 narrowing table)"
    ;; utils/vconcat uses loop/recur
    (is (contains? (classify-form (get top-forms ['latte_kernel.utils 'vconcat]))
                   :loop))
    ;; utils/pair? is structurally simple but calls host fn `vector` and
    ;; carries a docstring: both must be classified as blockers
    (is (contains? (classify-form (get top-forms ['latte_kernel.utils 'pair?]))
                   'vector))
    ;; syntax/alpha-norm is blocked by more than its docstring
    (is (seq (classify-form (get top-forms ['latte_kernel.syntax 'alpha-norm]))))
    ;; nbe/norm is blocked
    (is (seq (classify-form (get top-forms ['latte_kernel.nbe 'norm])))))
  (testing "A/C differential on admitted fixtures"
    (let [results
          (for [[qsym args] fixtures
                :let [f (upstream-fn qsym)
                      a-value (apply f args)
                      fname (symbol (name qsym))
                      c-result (try
                                 {:status :ok
                                  :value (run-on-vm
                                           (cond-> (vec clean-forms)
                                             (not (some #{fname} (map second clean-forms)))
                                             (conj (get top-forms
                                                        [(symbol (str/replace (namespace qsym) "-" "_"))
                                                         fname])))
                                           (list* fname args))}
                                 (catch Exception e
                                   {:status :error :msg (.getMessage e)}))]]
            {:qsym qsym, :a a-value, :c c-result})]
      (doseq [r results]
        (println "ORACLE" (:qsym r) "A=" (pr-str (:a r)) "C=" (pr-str (:c r))))
      ;; mkenv: the anchor that must pass end to end today
      (let [mkenv (first (filter #(= 'latte-kernel.defenv/mkenv (:qsym %)) results))]
        (is (= :ok (get-in mkenv [:c :status])))
        (is (= (:a mkenv) (get-in mkenv [:c :value]))))
      ;; vectorn? is classified blocked by the host stdlib names it uses
      ;; (vector/count/rem); the guest library now serves them, so A = C
      (let [v? (first (filter #(= 'latte-kernel.utils/vectorn? (:qsym %)) results))]
        (is (seq (classify-form (get top-forms ['latte_kernel.utils 'vectorn?]))))
        (is (= :ok (get-in v? [:c :status])))
        (is (= (:a v?) (get-in v? [:c :value]))))))
  (testing "keyword identities read from each file's own ns declaration"
    (let [kws (distinct (filter keyword?
                                (tree-seq coll? identity
                                          (vec (mapcat val all-forms)))))]
      ;; ::ascribe in syntax.cljc must read as :latte-kernel.syntax/ascribe
      (is (contains? (set kws) :latte-kernel.syntax/ascribe))
      ;; ::prestx/host-constant must resolve through the real alias
      (is (contains? (set kws) :latte-kernel.presyntax/host-constant))
      ;; no invented namespaces, no caller-namespace leakage
      (is (not (some #(= "latte_kernel" (namespace %)) kws)))
      (is (not (some #(= "user" (namespace %)) kws))))))


;; =============================================================================
;; Slice 3: destructuring anchors now run in the C leg
;; =============================================================================

(defn- run-with-deps
  "Compile the named upstream definitions (dependencies first) plus a call
   through yang.clojure and run it on the VM; return the call's value."
  [ns-sym dep-names fname args]
  (let [forms (mapv #(get top-forms [ns-sym %]) dep-names)]
    (run-on-vm forms (list* fname (map #(list 'quote %) args)))))


(deftest test-oracle-slice3-destructuring-anchors
  (testing "syntax/top-assumption: A (upstream) and C (yang.clojure on the VM) agree"
    (doseq [t ['(Π [x :type] x)
               '(Π [y [:ref A]] (Π [z :type] z))
               'x
               '(λ [x :type] x)
               nil]]
      (let [a ((upstream-fn 'latte-kernel.syntax/top-assumption) t)
            c (run-with-deps 'latte_kernel.syntax '[prod? top-assumption]
                             'top-assumption [t])]
        (is (= a c) (str "top-assumption on " (pr-str t)))))
    (testing "the anchor genuinely uses a nested destructuring let"
      (is (some (fn [f]
                  (and (seq? f) (= 'let (first f))
                       (some vector? (take-nth 2 (second f)))))
                (tree-seq coll? seq
                          (get top-forms ['latte_kernel.syntax 'top-assumption]))))))
  (testing "defenv/register-definition is multi-arity: rejected explicitly, never half-compiled"
    (let [form (get top-forms ['latte_kernel.defenv 'register-definition])]
      (is (contains? (classify-form form) :multi-arity))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Multi-arity"
            (yang/compile-program [form]))))))


;; =============================================================================
;; Slice 3b: multi-arity through the argument-vector convention
;; =============================================================================

(defn- run-av-with-deps
  "run-with-deps through the argument-vector convention."
  [ns-sym dep-names fname args]
  (let [forms (mapv #(get top-forms [ns-sym %]) dep-names)]
    (run-on-vm forms (list* fname (map #(list 'quote %) args)))))


(deftest test-oracle-slice3b-multi-arity
  (let [register (upstream-fn 'latte-kernel.defenv/register-definition)
        c-register (fn [& args]
                     (run-av-with-deps 'latte_kernel.defenv '[register-definition]
                                       'register-definition (vec args)))
        rdef {:name 'f, :arity 1}
        env [{'g {:name 'g}} {'h {:name 'h}}]]
    (testing "defenv/register-definition: both arities, both placements, A = C"
      (doseq [args [[env rdef]
                    [env rdef false]
                    [env rdef true]
                    [[{} {}] rdef]
                    [[{} {}] rdef true]]]
        (is (= (apply register args) (apply c-register args))
            (pr-str args)))
      (is (= [{'g {:name 'g}, 'f rdef} {'h {:name 'h}}] (c-register env rdef))
          "the two-argument form registers globally")
      (is (= [{'g {:name 'g}} {'h {:name 'h}, 'f rdef}] (c-register env rdef true))
          "the three-argument form with local? true registers locally"))
    (testing "a wrong-count call is a rejection fixture"
      (doseq [args [[env] [] [env rdef true :extra]]]
        (is (= :arity
               (try (apply c-register args) nil
                    (catch clojure.lang.ExceptionInfo e
                      (:yin.vm.data/reason (ex-data e)))))
            (pr-str args)))))
  (testing "single-arity anchors still pass through the convention"
    (doseq [t ['(Π [x :type] x) '(Π [y [:ref A]] (Π [z :type] z)) 'x nil]]
      (is (= ((upstream-fn 'latte-kernel.syntax/top-assumption) t)
             (run-av-with-deps 'latte_kernel.syntax '[prod? top-assumption]
                               'top-assumption [t]))
          (str "top-assumption on " (pr-str t))))
    (is (= ((upstream-fn 'latte-kernel.defenv/mkenv) [])
           (run-av-with-deps 'latte_kernel.defenv '[mkenv] 'mkenv [[]])))))


;; =============================================================================
;; Slice 4: loop/recur anchors, upstream A leg against the compiled C leg
;; =============================================================================

(defn- agree
  "Run upstream `qsym` and the C leg (`deps` first, then the function) on
   each argument vector and assert A = C."
  [qsym ns-sym deps arg-lists]
  (let [a-fn (upstream-fn qsym)
        fname (symbol (name qsym))]
    (doseq [args arg-lists]
      (is (= (apply a-fn args)
             (run-with-deps ns-sym deps fname args))
          (str fname " on " (pr-str args))))))


(deftest test-oracle-slice4-loop-anchors
  (testing "utils/vconcat and utils/vcons: empty and non-empty, both orders"
    (agree 'latte-kernel.utils/vconcat 'latte_kernel.utils '[vconcat]
           [[[] []] [[] [1 2]] [[1 2] []] [[1 2] [3 4]] [[3 4] [1 2]] ['[[a]] '[[b] c]]])
    (agree 'latte-kernel.utils/vcons 'latte_kernel.utils '[vconcat vcons]
           [[1 []] [1 [2 3]] [[0] [1]] ['x ['y 'z]]]))
  (testing "typing/ctx-fetch: found, not found, empty context, shadowed"
    (agree 'latte-kernel.typing/ctx-fetch 'latte_kernel.typing '[ctx-fetch]
           [['[[x A] [y B]] 'x]
            ['[[x A] [y B]] 'y]
            ['[[x A] [y B]] 'z]
            ['[] 'x]
            [nil 'x]
            ['[[x A] [y B] [x C]] 'x]
            ['[[y B] [x C] [x A]] 'x]
            ['[[x [:ref A]]] 'x]]))
  (testing "syntax/binderify: zero, one and three bindings"
    (agree 'latte-kernel.syntax/binderify 'latte_kernel.syntax '[binderify]
           [['λ [] 'body]
            ['λ '[[x :type]] 'body]
            ['Π '[[x :type] [y [:ref A]] [z :type]] '(f x y z)]]))
  (testing "syntax/appify: zero, one and three arguments"
    (agree 'latte-kernel.syntax/appify 'latte_kernel.syntax '[appify]
           [['f []] ['f '[a]] ['f '[a b c]] ['[g h] '[a [b c]]]]))
  (testing "syntax/assumptions: nil, non-product, one and two nested products"
    (agree 'latte-kernel.syntax/assumptions 'latte_kernel.syntax
           '[prod? top-assumption assumptions]
           [[nil]
            ['x]
            ['(λ [x :type] x)]
            ['(Π [x :type] x)]
            ['(Π [x :type] (Π [y [:ref A]] z))]
            ['(Π [x :type] (Π [y [:ref A]] (Π [z :type] z)))]])))


(deftest test-oracle-slice4-mk-fresh
  (let [a-fn (upstream-fn 'latte-kernel.syntax/mk-fresh)
        form (get top-forms ['latte_kernel.syntax 'mk-fresh])
        c-fn (fn [& args]
               (run-on-vm [form] (list* 'mk-fresh (map #(list 'quote %) args))))]
    (testing "the source carries a multi-arity branch that recurs"
      (let [source (get top-forms ['latte_kernel.syntax 'mk-fresh])]
        (is (= 2 (count (filter #(and (seq? %) (vector? (first %))) source))))
        (is (contains? (set (walk-symbols source)) 'recur))))
    (testing "suffix levels 0 to 5 through collisions, A = C"
      (let [forbids [#{}
                     '#{x}
                     '#{x x'}
                     '#{x x' x''}
                     '#{x x' x'' x'''}
                     '#{x x' x'' x''' x-4}
                     '#{x x' x'' x''' x-4 x-5}
                     '#{y}]]
        (doseq [forbid forbids]
          (is (= (a-fn 'x forbid) (c-fn 'x forbid)) (pr-str forbid))
          (doseq [level (range 6)]
            (is (= (a-fn 'x level forbid) (c-fn 'x level forbid))
                (str "level " level " " (pr-str forbid)))))
        (is (= 'x-5 (c-fn 'x '#{x x' x'' x''' x-4})))
        (is (= 'x (c-fn 'x #{})))))))


;; =============================================================================
;; Slice 5: outcome channel beside the value channel
;; =============================================================================

(defn- desugar
  "Drop corpus namespace aliases and normalize reader-generated fn* to fn.
   Only finite utils/zip admits eager lazy-seq adaptation; cond and if-let
   are compiler forms."
  [form]
  (let [finite-zip? (and (seq? form) (= 'defn (first form)) (= 'zip (second form)))]
    (walk/postwalk
      (fn [f]
        (cond
          (and (seq? f) (= 'lazy-seq (first f)))
          (if finite-zip? (second f)
              (throw (ex-info "Only finite utils/zip admits eager lazy-seq adaptation" {})))
          (= f 'fn*) 'fn
          (and (symbol? f) (#{"stx" "defenv" "nbe" "norm" "utils" "set"} (namespace f)))
          (symbol (name f))
          :else f))
      form)))


(defn- a-leg
  "The upstream result as a value or a caught ex-info (message and data; a
   stack trace is never compared)."
  [f args]
  (try {:value (let [v (apply f args)] (if (seq? v) (doall v) v))}
       (catch clojure.lang.ExceptionInfo e
         {:thrown {:message (ex-message e), :data (ex-data e)}})))


(defn- agrees?
  "The outcome `c` is the A leg's value as a success or its exception as the
   portable error map."
  [a c]
  (if (contains? a :value)
    (= [:yin.k/ok (:value a)] c)
    (= [:yin.k/error {:yin.k/message (get-in a [:thrown :message])
                      :yin.k/data (get-in a [:thrown :data])}]
       c)))


(defn- ab
  "Run upstream `qsym` and the outcome-model C leg (`deps` first) on `args`;
   returns [a c] and asserts they agree."
  [qsym ns-sym deps args]
  (let [fname (symbol (name qsym))
        forms (mapv #(desugar (get top-forms (if (vector? %) % [ns-sym %]))) deps)
        a (a-leg (upstream-fn qsym) args)
        c (run-outcome forms (list* fname (map #(list 'quote %) args)))]
    (is (agrees? a c) (str fname " " (pr-str args) " A=" (pr-str a) " C=" (pr-str c)))
    [a c]))


(deftest test-oracle-slice5-throw-site-fixtures
  (testing "utils/safe-get: success and failure"
    (let [[a1 _] (ab 'latte-kernel.utils/safe-get 'latte_kernel.utils '[safe-get] ['{:a 1} :a])
          [a2 c2] (ab 'latte-kernel.utils/safe-get 'latte_kernel.utils '[safe-get] ['{:a 1} :b])]
      (is (contains? a1 :value))
      (is (= "No such value in collection" (get-in a2 [:thrown :message])))
      (is (= :yin.k/error (first c2)))))
  (testing "utils/zip: even length succeeds, odd length throws"
    (doseq [s ['(a b c d) '() '(a b)]]
      (ab 'latte-kernel.utils/zip 'latte_kernel.utils '[zip] [s]))
    (let [[a _] (ab 'latte-kernel.utils/zip 'latte_kernel.utils '[zip] ['(a b c)])]
      ;; upstream is lazy: realizing the sequence is what throws
      (is (or (contains? a :thrown) (nil? (try (doall (:value a)) nil
                                               (catch clojure.lang.ExceptionInfo e e)))))))
  (testing "defenv/fetch-definition: the non-symbol, non-var branch throws"
    (ab 'latte-kernel.defenv/fetch-definition 'latte_kernel.defenv '[fetch-definition]
        ['[{} {}] 5])
    (ab 'latte-kernel.defenv/fetch-definition 'latte_kernel.defenv '[fetch-definition]
        ['[{} {}] 5 true])
    (ab 'latte-kernel.defenv/fetch-definition 'latte_kernel.defenv '[fetch-definition]
        ['[{} {foo {:name foo}}] 'foo]))
  (testing "a wrong-count call is the function's OWN arity error, not a count refusal"
    (is (= [:yin.k/error {:yin.vm.data/reason :arity, :yin.k/fn 'fetch-definition, :yin.k/argc 1}]
           (run-outcome [(desugar (get top-forms ['latte_kernel.defenv 'fetch-definition]))]
                        '(fetch-definition [{} {}])))))
  (testing "norm and nbe delta-reduction: No such definition"
    (doseq [[qsym ns-sym args] [['latte-kernel.nbe/delta-reduction 'latte_kernel.nbe
                                 ['[{} {}] [] '(no-such-definition-xyz)]]
                                ['latte-kernel.norm/delta-reduction 'latte_kernel.norm
                                 ['[{} {}] [] '(no-such-definition-xyz)]]]
            :let [[a c] (ab qsym ns-sym
                            [['latte_kernel.defenv 'fetch-definition]
                             ['latte_kernel.syntax 'ref?]
                             'delta-reduction]
                            args)]]
      (is (= "No such definition" (get-in a [:thrown :message])) (str qsym))
      (is (= :yin.k/error (first c))))))


;; =============================================================================
;; Slice 5: unfold-implicit, adapted from typing.cljc:453
;; =============================================================================

(def ^:private unfold-implicit-adaptation
  "The checked-in B-leg source of `typing/unfold-implicit`
   (test/yang/latte_oracle/unfold_implicit.edn)."
  (edn/read-string (slurp (io/resource "yang/latte_oracle/unfold_implicit.edn"))))


(defn- upstream-unfold
  "The upstream catch shape over a host implicit fn."
  [implicit-def args]
  (try [:ok ((:fn implicit-def) args) args]
       (catch clojure.lang.ExceptionInfo e
         [:ko (merge {:implicit (:name implicit-def), :msg (ex-message e)} (ex-data e)) nil])))


(deftest test-oracle-slice5-unfold-implicit
  (let [b-leg (fn [fn-form args]
                (run-outcome [(list 'def 'impl {:name ''imp, :fn fn-form})
                              unfold-implicit-adaptation]
                             (list 'unfold-implicit 'impl (list 'quote args))))
        ok-form '(fn [args] (first args))
        a-ok {:name 'imp, :fn first}
        a-throw {:name 'imp, :fn (fn [_] (throw (ex-info "implicit failed" {:msg "data wins" :extra 1})))}
        throw-form '(fn [args] (throw (ex-info "implicit failed" {:msg "data wins", :extra 1})))]
    (testing "success: [:ok value args]"
      (is (= (upstream-unfold a-ok '[7 8])
             (second (b-leg ok-form '[7 8])))))
    (testing "failure: the raised error is caught, data overrides :msg, result is [:ko ... nil]"
      (is (= (upstream-unfold a-throw '[7])
             (second (b-leg throw-form '[7]))))
      (is (= [:ko {:implicit 'imp, :msg "data wins", :extra 1} nil]
             (second (b-leg throw-form '[7])))))))


;; =============================================================================
;; Slice 5: the throw-site inventory
;; =============================================================================

(defn- throw-forms
  [form]
  (filter #(and (seq? %) (= 'throw (first %))) (tree-seq coll? seq form)))


(defn- throw-symbol-count
  "Every `throw` symbol in the manifest's top-level forms."
  []
  (count (for [{:keys [ns name]} manifest
               s (tree-seq coll? seq (get top-forms [ns name]))
               :when (= 'throw s)]
           s)))


(def ^:private fixture-sites
  "[ns fn message-fragment] of the sites slice 5 fixes with an A/B fixture."
  #{['latte_kernel.utils 'safe-get "No such value"]
    ['latte_kernel.utils 'zip "odd number"]
    ['latte_kernel.defenv 'fetch-definition "Cannot fetch"]
    ['latte_kernel.norm 'delta-reduction "No such definition"]
    ['latte_kernel.nbe 'delta-reduction "No such definition"]})


(defn- disposition
  [ns-sym fname message]
  (if (some (fn [[n f frag]]
              (and (= n ns-sym) (= f fname) message (str/includes? message frag)))
            fixture-sites)
    {:disposition :fixture}
    {:disposition :deferred
     :to (str "the slice that admits " ns-sym "/" fname)}))


(defn- throw-site-rows
  []
  (vec (for [{:keys [ns name]} manifest
             t (throw-forms (get top-forms [ns name]))
             :let [message (first (filter string? (tree-seq coll? seq t)))]]
         (merge {:ns ns, :fn name, :line (:line (meta t)), :message message}
                (disposition ns name message)))))


(def ^:private throw-site-table
  (edn/read-string (slurp (io/resource "yang/latte_oracle/throw_sites.edn"))))


(deftest test-throw-site-inventory
  (testing "the checked-in table has one row per throw symbol in the manifest forms"
    (is (= (throw-symbol-count) (count throw-site-table)))
    (is (= (throw-site-rows) throw-site-table)
        "regenerate with: clj -X:test:dev latte-oracle-tables/-main (corpus or disposition change)"))
  (testing "every row has a disposition"
    (is (every? #{:fixture :deferred :excluded} (map :disposition throw-site-table)))
    (is (every? #(or (not= :deferred (:disposition %)) (:to %)) throw-site-table)))
  (testing "slice 5 assigns the fixtures the design names"
    (let [fixtures (filter #(= :fixture (:disposition %)) throw-site-table)]
      (is (= 5 (count fixtures)))
      (is (= #{['latte_kernel.utils 'safe-get] ['latte_kernel.utils 'zip]
               ['latte_kernel.defenv 'fetch-definition] ['latte_kernel.norm 'delta-reduction]
               ['latte_kernel.nbe 'delta-reduction]}
             (set (map (juxt :ns :fn) fixtures)))))))


;; =============================================================================
;; Slice 7: per-arm case fixtures
;; =============================================================================
;;
;; The corpus has six `case` sites. Each is walked, every clause's constants are
;; extracted, and a synthetic dispatch (one arm per clause, each returning its
;; arm index, plus the site's default) runs on the host (A leg) and through
;; yang.clojure on the VM (C leg). The extracted table is checked in
;; (test/yang/latte_oracle/case_arms.edn) and regenerated, with the throw-site
;; table, by src/dev/latte_oracle_tables.clj.

(defn- case-forms
  [form]
  (filter #(and (seq? %) (= 'case (first %))) (tree-seq coll? seq form)))


(defn- case-clause-constants
  "The constants of one clause key: a list groups them, anything else is one."
  [k]
  (if (seq? k) (vec k) [k]))


(defn- case-arm-rows
  "One row per `case` site in the manifest forms: the clause constants of each
   arm, and whether the site has a default."
  []
  (vec (for [{:keys [ns name]} manifest
             c (case-forms (get top-forms [ns name]))
             :let [clauses (drop 2 c)]]
         {:ns ns, :fn name, :line (:line (meta c))
          :default? (odd? (count clauses))
          :arms (vec (map-indexed (fn [i [k _]] {:arm i, :constants (case-clause-constants k)})
                                  (partition 2 clauses)))})))


(def ^:private case-arm-table
  (edn/read-string (slurp (io/resource "yang/latte_oracle/case_arms.edn"))))


(def ^:private unmatched
  "An input no corpus clause names."
  :yang.latte-oracle/unmatched)


(defn- dispatch-clauses
  "The clauses of a synthetic `case`: each arm's constants as one group
   returning the arm index, then the default `:default` when the site has one."
  [{:keys [arms default?]}]
  (concat (mapcat (fn [{:keys [arm constants]}] [(apply list constants) arm]) arms)
          (when default? [:default])))


(defn- a-dispatch
  "The host `case` over the same clauses, applied to `input`; ::no-match when
   no clause (and no default) takes it."
  [row input]
  (try ((eval (list 'fn '[e] (list* 'case 'e (dispatch-clauses row)))) input)
       (catch IllegalArgumentException _ ::no-match)))


(defn- c-dispatch
  "The synthetic dispatch compiled through yang.clojure and run on the VM:
   the outcomes of calling it on each of `inputs`, in order."
  [row inputs]
  (let [dispatch (list 'defn 'dispatch '[e] (list* 'case 'e (dispatch-clauses row)))]
    (mapv (fn [input] (run-outcome [dispatch] (list 'dispatch (list 'quote input)))) inputs)))


(deftest test-case-arm-table
  (testing "the checked-in table is the walk of the corpus's case sites"
    (is (= 6 (count case-arm-table)))
    (is (= (case-arm-rows) case-arm-table)
        "regenerate with: clj -X:test:dev latte-oracle-tables/-main")
    (is (= #{['latte_kernel.norm 'beta-red] ['latte_kernel.norm 'beta-delta-normalize]
             ['latte_kernel.proof 'elab-proof] ['latte_kernel.proof 'elab-proof-step]
             ['latte_kernel.typing 'type-of-term] ['latte_kernel.syntax 'mk-fresh]}
           (set (map (juxt :ns :fn) case-arm-table)))))
  (testing "every arm of every site has constants, and arms never share one"
    (doseq [{:keys [arms] :as row} case-arm-table
            :let [constants (mapcat :constants arms)]]
      (is (every? (comp seq :constants) arms) (pr-str (:fn row)))
      (is (apply distinct? constants) (pr-str (:fn row))))))


(deftest test-case-arm-fixtures
  (doseq [{:keys [arms default?] :as row} case-arm-table
          :let [label (str (:ns row) "/" (:fn row) " line " (:line row))]]
    (testing label
      (testing "every constant of every arm dispatches to its own arm, A = C"
        (let [inputs (mapcat :constants arms)
              expected (mapcat (fn [{:keys [arm constants]}] (repeat (count constants) arm)) arms)]
          (is (= expected (map #(a-dispatch row %) inputs)) "host case")
          (is (= (map (fn [e] [:yin.k/ok e]) expected) (c-dispatch row inputs)) "VM")))
      (testing "an input no clause names takes the default, or fails without one"
        (let [[c] (c-dispatch row [unmatched])]
          (if default?
            (do (is (= :default (a-dispatch row unmatched)))
                (is (= [:yin.k/ok :default] c)))
            (do (is (= ::no-match (a-dispatch row unmatched)))
                (is (= :yin.k/error (first c)))
                (is (= "No matching clause in case" (:yin.k/message (second c)))))))))))


;; =============================================================================
;; Slice 7: upstream higher-order and syntax anchors, both VM lanes
;; =============================================================================

(def ^:private slice7-anchors
  (edn/read-string (slurp (io/resource "yang/latte_oracle/slice7_anchors.edn"))))


(deftest test-oracle-slice7-library-anchors
  (let [{:keys [dependency-groups anchors]} slice7-anchors]
    (is (= '#{map-with vremove term-reduce free-vars vars subst alpha-norm readable-term}
           (set (map :fn anchors))))
    (doseq [{ns-sym :ns fname :fn :keys [groups deps args]} anchors
            :let [names (distinct (concat (mapcat dependency-groups groups) deps))
                  forms (mapv #(desugar (get top-forms [ns-sym %])) names)
                  qsym (symbol (str (str/replace (str ns-sym) "_" "-") "/" fname))]]
      (testing (str qsym)
        (is (every? some? forms) "every declared dependency exists in pinned upstream")
        (doseq [arg-forms args
                :let [host-args (mapv eval arg-forms)
                      a (a-leg (upstream-fn qsym) host-args)
                      c (run-outcome forms (list* fname arg-forms))]]
          (is (agrees? a c) (str qsym " " (pr-str arg-forms)
                                 " A=" (pr-str a) " C=" (pr-str c)))
          (when (contains? a :value)
            (is (= (result-shape (:value a)) (result-shape (second c)))
                "value equality also preserves nested metadata and collection shape")))))))


(deftest test-oracle-slice7-finite-zip
  ;; The pinned corpus has one consumer, proof/compile-proof's :assume arm:
  ;; it loops over all pairs to build declarations, then reverses all pairs
  ;; to build discharges. It requires finite, fully consumed inputs; it does
  ;; not catch zip failures or depend on partial realization/error timing.
  (let [source (get top-forms ['latte_kernel.utils 'zip])
        forms [(desugar source)]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Only finite utils/zip"
          (desugar '(defn other
                      [s]
                      (lazy-seq s)))))
    (is (= 1 (count (filter #(= 'lazy-seq %) (walk-symbols source))))
        "zip is the single explicit eager lazy-seq adaptation")
    (doseq [input [nil [] '() '[a A] '(a A b B c C) '[a A b]]
            :let [a (a-leg (upstream-fn 'latte-kernel.utils/zip) [input])
                  c (run-outcome forms (list 'zip (list 'quote input)))]]
      (is (agrees? a c) (str "fully realized finite zip " (pr-str input))))
    (is (= [:yin.k/ok [true 2 '[a A] '([b B]) '(:front [a A] [b B])]]
           (run-outcome forms
                        '(let [s (zip '(a A b B))]
                           [(seq? s) (count s) (first s) (rest s) (conj s :front)]))))))


;; =============================================================================
;; Slice 8: immutable kernel state and explicit extension installation
;; =============================================================================

(def ^:private context-source
  (edn/read-string (slurp (io/resource "yang/latte_oracle/context.edn"))))


(defn- context-upstream-form
  "Keep the upstream implementation, changing only the environment boundary
   and module-local names. Both delta implementations read the SAME binding."
  [ns-sym fname]
  (let [renames (if (#{'latte_kernel.nbe 'latte_kernel.norm} ns-sym)
                  {'delta-reduction (symbol (str (last (str/split (clojure.core/name ns-sym) #"\.")) "-delta"))
                   'instantiate-def (symbol (str (last (str/split (clojure.core/name ns-sym) #"\.")) "-instantiate"))}
                  {})]
    (walk/postwalk
      (fn [x]
        (cond
          (and (seq? x) (#{'deref 'clojure.core/deref} (first x))
               (= '+unfold-implicit+ (second x))) '(:unfold-implicit def-env)
          (contains? '{get-in lookup-in not-empty context-not-empty
                       ffirst context-ffirst list* context-list-star} x)
          (get '{get-in lookup-in not-empty context-not-empty
                 ffirst context-ffirst list* context-list-star} x)
          (symbol? x) (get renames x x)
          :else x))
      (desugar (get top-forms [ns-sym fname])))))


(def ^:private context-forms
  (into context-source
        (concat
          (map #(context-upstream-form 'latte_kernel.syntax %)
               '[kind? type? variable? binder? lambda? prod? app? ascription? ref?
                 host-constant? binderify free-vars vars mk-fresh subst- rebinder subst])
          (map #(context-upstream-form 'latte_kernel.nbe %) '[instantiate-def delta-reduction])
          (map #(context-upstream-form 'latte_kernel.norm %) '[instantiate-def delta-reduction])
          [(context-upstream-form 'latte_kernel.proof 'elab-proof)]
          (map #(context-upstream-form 'latte_kernel.unparser %)
               '[unparse-term-once unparse-term-rec prod-impl-unparser])
          '[(defn unparse
              [kernel term]
              (first (unparse-term-rec (:unparser-registry kernel) (unparser-order kernel) term)))
            (defn install-fundamental-unparsers
              [kernel]
              (register-unparser kernel :fundamental :prod-impl prod-impl-unparser))])))


(defn- context-for
  "The transitive source dependency closure, preserving upstream order."
  [program]
  (let [by-name (into {} (map (juxt second identity)) context-forms)
        mentions (fn [form] (filter #(contains? by-name %) (walk-symbols form)))
        needed (loop [todo (seq (mapcat mentions program)) seen #{}]
                 (if-let [n (first todo)]
                   (if (contains? seen n)
                     (recur (next todo) seen)
                     (recur (concat (next todo) (mentions (get by-name n))) (conj seen n)))
                   seen))]
    (filterv #(contains? needed (second %)) context-forms)))


(defn- context-host-form
  "B is ordinary Clojure over the checked-in adaptation; data primitives and
   deterministic gensym retain exactly their portable contracts."
  [form]
  (walk/postwalk
    (fn [x]
      (cond
        (and (seq? x) (= 'gensym (first x)))
        (let [[_ prefix n] x]
          (list 'let ['prefix prefix 'n n]
                '(if (map? prefix)
                   [(symbol (str "G__" n (:counter prefix)))
                    (assoc prefix :counter (inc (:counter prefix)))]
                   (symbol (str prefix n)))))
        (and (seq? x) (= 'fresh-symbol (first x)))
        (let [[_ supply prefix] x]
          (list 'let ['supply supply]
                (list 'vector (list 'symbol (list 'str "G__" prefix '(:counter supply)))
                      '(assoc supply :counter (inc (:counter supply))))))
        (= x 'data/dissoc) 'clojure.core/dissoc
        :else x))
    form))


(defn- context-b
  [forms call]
  (let [ns-sym (gensym "latte-context-b-")
        sandbox (create-ns ns-sym)]
    (try
      (binding [*ns* sandbox]
        (refer 'clojure.core)
        (require '[clojure.set :refer [union difference]])
        (eval '(declare subst- rebinder subst))
        (doseq [form (concat (context-for (conj forms call)) forms)] (eval (context-host-form form)))
        (a-leg (eval (context-host-form (list 'fn [] call))) []))
      (finally (remove-ns ns-sym)))))


(defn- context-bc
  [forms call]
  (let [b (context-b forms call)
        c (run-outcome (into (context-for (conj forms call)) forms) call (oracle-registry yang/kind-module) true)]
    (is (agrees? b c) (str "context B/C " (pr-str call) " B=" (pr-str b) " C=" (pr-str c)))
    (when (contains? b :value)
      (is (= (result-shape (:value b)) (result-shape (second c)))
          "B/C preserve collection families and domain metadata"))
    c))


(def ^:private initial-context
  '(kernel-context [{} {}] definition-opacity missing-unfold-implicit))


(deftest test-oracle-slice8-context-isolation
  (let [call (walk/postwalk-replace
               {'INITIAL initial-context}
               '(let [k0 INITIAL other INITIAL
                      d (->Definition 'd [] 0 'body #{} 'T {})
                      k1 (register-definition k0 d)
                      k2 (register-definition k1 (->Axiom 'a [] 0 'T) true)
                      [fresh k3] (fresh-name k2 "fresh")
                      [fresh2 k4] (fresh-name k3 "fresh")]
                  [(fetch-definition k0 'd) (fetch-definition other 'd)
                   (fetch-definition k1 'd) (fetch-definition k2 'a true)
                   (fetch-definition k2 'd true) (qualify-def k2 'd)
                   fresh fresh2 (:fresh k0) (:fresh k2) (:fresh k3) (:fresh k4)]))
        [_ result] (context-bc [] call)]
    (is (= (first result) (second result)))
    (is (= [:ko {:msg "No such (global) definition" :def 'd}] (first result)))
    (is (= ['fresh0 'fresh1 0 0 1 2] (subvec result 6))))
  (testing "no namespace fallback, even when a host name resolves"
    (is (= [:yin.k/ok [:ko {:msg "No such (global) definition" :def 'clojure.core/map}]]
           (context-bc [] (list 'fetch-definition initial-context ''clojure.core/map)))))
  (testing "missing installations and bad names are explicit outcomes"
    (doseq [call [(list 'qualify-def initial-context ''missing)
                  (list 'fetch-definition initial-context 5)
                  '(let [k (kernel-context [{} {}] missing-opacity-policy missing-unfold-implicit)
                         k (register-definition k (->Definition 'd [] 0 'body #{} 'T {}))]
                     (elab-opaque k '[d]))]]
      (is (= :yin.k/error (first (context-bc [] call)))))))


(defn- upstream-record-data
  "Declare only the five approved record representations equivalent. Kind
   and every field survive normalization; arbitrary host objects do not."
  [x]
  (let [predicates [['latte-kernel.defenv/definition? :definition]
                    ['latte-kernel.defenv/theorem? :theorem]
                    ['latte-kernel.defenv/axiom? :axiom]
                    ['latte-kernel.defenv/notation? :notation]
                    ['latte-kernel.defenv/implicit? :implicit]]]
    (walk/postwalk
      (fn [v]
        (if-let [kind (some (fn [[pred kind]] (when ((upstream-fn pred) v) kind)) predicates)]
          (assoc (into {} v) :latte/kind kind)
          v))
      x)))


(deftest test-oracle-slice8-definition-kinds-and-delta
  (doseq [[ctor args term-args] [['->Definition '['d [] 0 'body #{} 'T {}]]
                                 ['->Definition '['opaque [] 0 'body #{} 'T {:opaque true}]]
                                 ['->Axiom '['a [] 0 'T]]
                                 ['->Theorem '['proved [] 0 'T 'proof]]
                                 ['->Theorem '['unproved [] 0 'T nil]]
                                 ['->Definition '['empty [] 0 nil #{} 'T {}]]
                                 ['->Definition '['param '[[x T]] 1 'x #{} 'T {}] ['arg]]
                                 ['->Definition '['partial '[[x T]] 1 'x #{} 'T {}]]
                                 ['->Definition '['too-many [] 0 'body #{} 'T {}] ['arg]]]
          ns-sym ['latte_kernel.nbe 'latte_kernel.norm]
          :let [record (apply (upstream-fn (symbol "latte-kernel.defenv" (name ctor))) (map eval args))
                name (:name record)
                env ((upstream-fn 'latte-kernel.defenv/register-definition) [{} {}] record)
                a (upstream-record-data
                    (a-leg (upstream-fn (symbol (str/replace (str ns-sym) "_" "-") "delta-reduction"))
                           [env [] (apply list name term-args)]))
                call (list 'let ['k (list 'register-definition initial-context (cons ctor args))]
                           (list (symbol (str (last (str/split (clojure.core/name ns-sym) #"\.")) "-delta")) 'k [] (list 'quote (apply list name term-args))))
                c (context-bc [] call)]]
    (is (agrees? a c) (str "A/B/C " ctor " " name " " ns-sym)))
  (testing "all five explicit predicates distinguish plain maps and each other"
    (is (= [:yin.k/ok [true true true true true false false]]
           (context-bc []
                       '(let [n (->Notation 'n (fn [x] x)) i (->Implicit 'i (fn [k ctx x] x))]
                          [(definition? (->Definition 'd [] 0 'b #{} 'T {}))
                           (theorem? (->Theorem 't [] 0 'T nil)) (axiom? (->Axiom 'a [] 0 'T))
                           (notation? n) (implicit? i) (definition? n) (latte-definition? {})])))))
  (testing "notation extension functions execute inside the guest"
    (let [a ((:notation-fn ((upstream-fn 'latte-kernel.defenv/->Notation) 'n (fn [x] ['expanded x]))) 7)]
      (is (= [:yin.k/ok a]
             (context-bc [] '(let [n (->Notation 'n (fn [x] ['expanded x]))]
                               ((:notation-fn n) 7))))))))


(def ^:private slice8-sequences
  (edn/read-string (slurp (io/resource "yang/latte_oracle/slice8_sequences.edn"))))


(def ^:private registry-replay
  '[(defn registry-snapshot
      [k]
      (let [r (:unparser-registry k)]
        [(:fundamental r) (:standard r) (:user r) (into #{} (keys (:unparsers r)))
         (unparse k 'x)]))
    (defn replay-registry
      [k operations]
      (loop [k k operations operations snapshots [(registry-snapshot k)]]
        (if (seq operations)
          (let [[op a b c] (first operations)
                k (case op
                    :register (register-unparser k a b c)
                    :register-default (register-unparser k a b)
                    :unregister (unregister-unparser k a))]
            (recur k (rest operations) (conj snapshots (registry-snapshot k))))
          snapshots)))])


(defn- a-registry-sequence
  [operations]
  (let [registry-var (upstream-fn 'latte-kernel.unparser/+unparser-registry+)
        initial {:fundamental [] :standard [] :user [] :unparsers {}}]
    (with-redefs-fn {registry-var (atom initial)}
      (fn []
        (let [snapshot (fn []
                         (let [r @@registry-var]
                           [(:fundamental r) (:standard r) (:user r) (set (keys (:unparsers r)))
                            ((upstream-fn 'latte-kernel.unparser/unparse) 'x)]))]
          (reduce (fn [snapshots [op a b c]]
                    (case op
                      :register ((upstream-fn 'latte-kernel.unparser/register-unparser!) a b (eval c))
                      :register-default ((upstream-fn 'latte-kernel.unparser/register-unparser!) a (eval b))
                      :unregister ((upstream-fn 'latte-kernel.unparser/unregister-unparser!) a))
                    (conj snapshots (snapshot)))
                  [(snapshot)] operations))))))


(deftest test-oracle-slice8-registry-sequences
  (let [ops (:registry slice8-sequences)
        a (a-registry-sequence ops)
        c (context-bc registry-replay (list 'replay-registry initial-context ops))]
    (is (= [:yin.k/ok a] c) "every installation/replacement/removal snapshot matches upstream"))
  (testing "fundamental installation, category order and recursive unparsing"
    (let [registry-var (upstream-fn 'latte-kernel.unparser/+unparser-registry+)
          a (with-redefs-fn {registry-var (atom {:fundamental [] :standard [] :user [] :unparsers {}})}
              (fn []
                ((upstream-fn 'latte-kernel.unparser/install-fundamental-unparsers!))
                ((upstream-fn 'latte-kernel.unparser/unparse) '(Π [x A] (Π [y B] C)))))]
      (is (= [:yin.k/ok a]
             (context-bc [] (list 'unparse (list 'install-fundamental-unparsers initial-context)
                                  ''(Π [x A] (Π [y B] C))))))))
  (testing "prior contexts retain closure-containing registries"
    (is (= [:yin.k/ok ['changed 'x 'x]]
           (context-bc []
                       (list 'let ['k0 initial-context
                                   'k1 '(register-unparser k0 :u (fn [t] ['changed true]))
                                   'k2 '(unregister-unparser k1 :u)]
                             '[(unparse k1 'x) (unparse k0 'x) (unparse k2 'x)])))))
  (doseq [[host-call guest-call] [['latte-kernel.unparser/unregister-unparser! '(unregister-unparser K :absent)]
                                  ['latte-kernel.unparser/register-unparser! '(register-unparser K :bad :x (fn [t] [t false]))]]]
    (let [a (if (= host-call 'latte-kernel.unparser/unregister-unparser!)
              (a-leg (upstream-fn host-call) [:absent])
              (a-leg (upstream-fn host-call) [:bad :x identity]))
          c (context-bc [] (walk/postwalk-replace {'K initial-context} guest-call))]
      (is (agrees? a c)))))


(def ^:private opacity-replay
  '[(defn opacity-snapshot
      [k]
      [(is-opaque? (second (fetch-definition k 'd)))
       (nbe-delta k [] '(d)) (norm-delta k [] '(d))])
    (defn replay-opacity
      [k operations]
      (loop [k k operations operations snapshots [(opacity-snapshot k)]]
        (if (seq operations)
          (let [[status [next-k ctx deps uses]]
                (elab-proof-step k [] [] {} (first operations) '[d])]
            (recur next-k (rest operations) (conj snapshots (opacity-snapshot next-k))))
          snapshots)))])


(defn- a-opacity-sequence
  [operations]
  (let [ns-sym (gensym "latte-opacity-a-")
        sandbox (create-ns ns-sym)
        ddef ((upstream-fn 'latte-kernel.defenv/->Definition) 'd [] 0 'body #{} 'T {})
        dvar (intern sandbox 'd ddef)
        opacity-var (upstream-fn 'latte-kernel.proof/+opacity-fn+)]
    (try
      (binding [*ns* sandbox]
        (with-redefs-fn {opacity-var (atom (fn [v flag] (alter-var-root v assoc-in [:opts :opaque] flag)))}
          (fn []
            (let [snapshot (fn []
                             [((upstream-fn 'latte-kernel.proof/is-opaque?) @dvar)
                              ((upstream-fn 'latte-kernel.nbe/delta-reduction) [{} {}] [] '(d))
                              ((upstream-fn 'latte-kernel.norm/delta-reduction) [{} {}] [] '(d))])]
              (reduce (fn [snapshots op]
                        ((upstream-fn (if (= :opaque op) 'latte-kernel.proof/elab-opaque
                                          'latte-kernel.proof/elab-transparent)) [{} {}] '[d])
                        (conj snapshots (snapshot)))
                      [(snapshot)] operations)))))
      (finally (remove-ns ns-sym)))))


(deftest test-oracle-slice8-opacity-propagation
  (let [ops (:opacity slice8-sequences)
        call (list 'replay-opacity
                   (list 'register-definition initial-context '(->Definition 'd [] 0 'body #{} 'T {}))
                   ops)]
    (is (= [:yin.k/ok (a-opacity-sequence ops)] (context-bc opacity-replay call))))
  (testing "local shadowing is updated in place; old/global contexts stay transparent"
    (is (= [:yin.k/ok [[nil ['global true]] [true ['(d) false]] [nil ['global true]]]]
           (context-bc []
                       (list 'let ['base initial-context
                                   'global '(register-definition base (->Definition 'd [] 0 'global #{} 'T {}))
                                   'local '(register-definition global (->Definition 'd [] 0 'local #{} 'T {}) true)
                                   'opaque '(elab-opaque local '[d])]
                             '[(vector (is-opaque? (second (fetch-definition global 'd)))
                                       (nbe-delta global [] '(d)))
                               (vector (is-opaque? (second (fetch-definition opaque 'd)))
                                       (nbe-delta opaque [] '(d)))
                               (vector (is-opaque? (second (fetch-definition global 'd)))
                                       (nbe-delta global [] '(d)))])))))
  (testing "duplicate transitions fail, and the context's original definition remains"
    (doseq [[flag step message] [[true :opaque ":opaque fails: definition is already opaque"]
                                 [false :transparent ":transparent fails: definition is not opaque"]]]
      (let [call (list 'elab-proof-step
                       (list 'register-definition initial-context
                             (list '->Definition ''d [] 0 ''body #{} ''T {:opaque flag}))
                       [] [] {} step ''[d])
            c (context-bc [] call)]
        (is (= [:yin.k/error {:yin.k/message message :yin.k/data {:name 'd}}] c)))))
  (testing "diagnostic proof arms preserve state and produce no hidden output"
    (doseq [step [:print-term :print-type :print-def :print-defenv]]
      (is (= [:yin.k/ok [0 [{} {}]]]
             (context-bc []
                         (list 'let ['result (list 'elab-proof-step initial-context [] [] {} step [])
                                     'k '(first (second result))]
                               '[(:fresh k) (:definitions k)])))))))


(def ^:private host-constant-unfold
  ;; This admitted typed boundary is checked against the ACTUAL upstream
  ;; typing/unfold-implicit, using only host-constant arguments (no inference).
  '[(defn unfold-host-constants
      [kernel ctx ddef args]
      (unfold-typed-implicit kernel ctx ddef (mapv second args) (mapv second args)))])


(deftest test-oracle-slice8-shared-implicit-installation
  (doseq [fn-form ['(fn [k ctx x] x)
                   '(fn [k ctx x] (throw (ex-info "extension failed" {:msg "data wins" :extra 7})))]]
    (let [implicit ((upstream-fn 'latte-kernel.defenv/->Implicit) 'i (eval fn-form))
          args '([:latte-kernel.presyntax/host-constant 7])
          a ((upstream-fn 'latte-kernel.typing/unfold-implicit) [{} {}] [] implicit args)
          c (context-bc host-constant-unfold
                        (list 'let ['k (list 'install-unfold-implicit initial-context 'unfold-host-constants)
                                    'i (list '->Implicit ''i fn-form)]
                              (list 'unfold-host-constants 'k [] 'i (list 'quote args))))]
      (is (= [:yin.k/ok a] c) "actual upstream catch and argument assembly, A/B/C")))
  (let [norm-binding (upstream-fn 'latte-kernel.norm/+unfold-implicit+)
        nbe-binding (upstream-fn 'latte-kernel.nbe/+unfold-implicit+)
        args '([:latte-kernel.presyntax/host-constant 7])
        implicit ((upstream-fn 'latte-kernel.defenv/->Implicit) 'i (fn [_ _ x] x))
        env ((upstream-fn 'latte-kernel.defenv/register-definition) [{} {}] implicit)
        a (with-redefs-fn {norm-binding (atom nil) nbe-binding (atom nil)}
            (fn []
              (let [f (upstream-fn 'latte-kernel.typing/unfold-implicit)
                    delta (fn []
                            [((upstream-fn 'latte-kernel.nbe/delta-reduction) env [] (cons 'i args))
                             ((upstream-fn 'latte-kernel.norm/delta-reduction) env [] (cons 'i args))])]
                ((upstream-fn 'latte-kernel.norm/install-unfold-implicit!) f)
                (let [first-result (delta)]
                  ((upstream-fn 'latte-kernel.norm/install-unfold-implicit!) (fn [_ _ _ args] [:ok 'replacement args]))
                  [first-result (delta) (identical? @@norm-binding @@nbe-binding)]))))
        call (list 'let ['k0 (list 'register-definition initial-context '(->Implicit 'i (fn [k ctx x] x)))
                         'k1 '(install-unfold-implicit k0 unfold-host-constants)
                         'k2 '(install-unfold-implicit k1 (fn [k ctx ddef args] [:ok 'replacement args]))]
                   '[(vector (nbe-delta k1 [] '(i [:latte-kernel.presyntax/host-constant 7]))
                             (norm-delta k1 [] '(i [:latte-kernel.presyntax/host-constant 7])))
                     (vector (nbe-delta k2 [] '(i [:latte-kernel.presyntax/host-constant 7]))
                             (norm-delta k2 [] '(i [:latte-kernel.presyntax/host-constant 7])))
                     true])]
    (is (= [:yin.k/ok a] (context-bc host-constant-unfold call))))
  (testing "extension installation is isolated and old bindings retain their value"
    (is (= [:yin.k/ok [true false true]]
           (context-bc []
                       (list 'let ['k0 initial-context
                                   'k1 '(install-unfold-implicit k0 (fn [k ctx d args] [:ok 'installed args]))
                                   'k2 '(install-opacity-function k1 missing-opacity-policy)]
                             '[(= (:unfold-implicit k0) missing-unfold-implicit)
                               (= (:unfold-implicit k1) (:unfold-implicit k0))
                               (= (:opacity-policy k0) definition-opacity)]))))))


(defn- assert-machine-data-within-budget!
  "Bound recursive validation work, which the VM instruction budget cannot
   interrupt. Throw outside `is` so exhaustion also stops UCF reconstruction."
  [value limit]
  (let [visits (volatile! 0)
        check vm/machine-data?
        valid? (with-redefs [vm/machine-data?
                             (fn [x]
                               (when (> (vswap! visits inc) limit)
                                 (throw (ex-info "Machine-data validation budget exhausted"
                                                 {:visits @visits :limit limit})))
                               (check x))]
                 (vm/machine-data? value))]
    (is valid?)
    (is (<= @visits limit) "descriptor environments have bounded validation work")))


(deftest test-oracle-slice8-context-reconstruction
  (let [build (list 'let ['base initial-context 'offset 4]
                    '(register-unparser base :captured (fn [term] [(+ offset term) true])))
        forms (conj (context-for [build '(unparse checkpoint 3)]) build)
        builder (yang/compile-program (into (yang/library-for forms) forms) true arg-vector-opts)
        mint (get-in @#'yang/continuation-uast [:operator :body])
        invoke (u/app (u/v 'yang.call/invoke) (u/v 'unparse)
                      (u/app (u/v 'conj) (u/app (u/v 'conj) (u/lit []) (u/v 'checkpoint)) (u/lit 3))
                      (u/lit []) (u/lam '[condition] (u/app (u/v 'clj/raise) (u/v 'condition))))
        consume (u/let1 'dsg__equal (u/sexp->uast '(fn [a b] (= a b)))
                        (#'yang/top-boundary invoke))
        ;; Transport the existing registry only after its construction's
        ;; handler has unwound. Retire and later mint the runtime through
        ;; guest operations, retaining this compilation's admission token.
        ast (yang/annotate-call-sites
              (u/let1 'built builder
                      (u/if-node (u/app (u/v '=) (u/app (u/v 'get) (u/v 'built) (u/lit 0))
                                        (u/lit :yin.k/ok))
                                 (u/then (u/def! 'clj.rt/ctx (u/lit nil))
                                         (u/let1 'checkpoint (u/app (u/v 'get) (u/v 'built) (u/lit 1))
                                                 (u/then mint consume)))
                                 (u/v 'built))))
        modules (oracle-registry yang/kind-module)
        run-bounded (fn [machine]
                      (loop [m machine n 0]
                        (cond
                          (vm/halted? m) (vm/value m)
                          (= n 1000000)
                          (throw (ex-info "Context execution budget exhausted" {:steps n}))
                          :else (recur (vm/step m) (inc n)))))]
    (is (vm/plain-data? context-source) "adaptation is plain code data")
    (is (vm/plain-data? ast) "runtime closures are never embedded as literals")
    (testing "code rows round-trip and reconstruct the closure registry on both lanes"
      (let [[_root datoms] (vm/ast->datoms-with-root ast)
            rebuilt (vm/datoms->ast (cbor/decode (cbor/encode datoms)))]
        (doseq [lane [:walker :semantic]]
          (is (= [:yin.k/ok 7] (compiler-test/run-guest-ast rebuilt modules lane 1000000))))))
    (testing "a live registry with a captured guest closure survives UCF reconstruction"
      (let [code (vm/ast->semantic-bytecode ast)
            bytecode (:vector (linearize/project-rows code))
            source-index {(ucf/code-address bytecode) #{(:root code)}}
            machine (semantic/load-vector (rows-support/new-machine {:modules modules})
                                          bytecode vm/semantic-contract)
            checkpoint (loop [m machine n 0]
                         (cond
                           (get-in m [:env 'checkpoint]) m
                           (or (vm/halted? m) (= n 1000000))
                           (throw (ex-info "Context checkpoint was not reached" {:steps n}))
                           :else (recur (vm/step m) (inc n))))
            k (get-in checkpoint [:env 'checkpoint])
            _ (is (values/closure? (get-in k [:unparser-registry :unparsers :captured
                                              :yang.call/entry])))
            ;; Sol measured 449,999 real recursive visits on both sides.
            _ (assert-machine-data-within-budget! k 500000)
            wire (rows/lift-task checkpoint (rows-support/serve-table) {:source-index source-index})
            _ (is (vm/plain-data? wire) "serialized context contains code references, not host closures")
            {:keys [attach!]} (rows-support/attacher)
            restored (rows/lower-task (rows-support/new-machine {:modules modules})
                                      (cbor/decode (cbor/encode wire)) attach!)
            k' (get-in restored [:env 'checkpoint])]
        (is (values/closure? (get-in k' [:unparser-registry :unparsers :captured
                                         :yang.call/entry])))
        (assert-machine-data-within-budget! k' 500000)
        (is (= [:yin.k/ok 7] (run-bounded restored)))
        (is (= [:yin.k/ok 7] (run-bounded checkpoint))))))
  (testing "host callbacks cannot enter code data through registry literals"
    (is (thrown? Exception
          (encoder/project
            (encoder/source-envelope
              :program "slice8-host-callback-refusal"
              [{:type :literal :value {:unparser-registry {:unparsers {:bad identity}}}}]))))))


(deftest test-oracle-slice8-admission
  (let [symbols (set (filter symbol? (tree-seq coll? seq context-forms)))
        ambient '#{atom swap! reset! deref clojure.core/deref resolve var? alter-var-root
                   defrecord println print pprint pp/pprint nano-time System/nanoTime}]
    (is (empty? (filter symbols ambient)) "context source has no ambient state, host lookup or diagnostics")
    (is (vm/plain-data? context-forms)))
  (testing "free-symbol admission catches undeclared guest dependencies before execution"
    (is (= '#{missing-extension}
           (unbound-ast-symbols (yang/compile '(fn [x] (missing-extension x))) []))))
  (testing "context modules reject undeclared namespace lookup at admission"
    (doseq [call ['(resolve 'clojure.core/map)]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unadmitted context dependencies"
            (run-outcome [] call (oracle-registry yang/kind-module) true))))))


(deftest test-oracle-slice8-implicit-delta-errors
  (let [fail-form '(fn [kernel ctx x]
                     (throw (ex-info "extension failed" {:msg "data wins" :extra 7})))
        implicit ((upstream-fn 'latte-kernel.defenv/->Implicit) 'i (eval fail-form))
        env ((upstream-fn 'latte-kernel.defenv/register-definition) [{} {}] implicit)
        term '(i [:latte-kernel.presyntax/host-constant 7])]
    (doseq [[a-name c-name] [['latte-kernel.nbe/delta-reduction 'nbe-delta]
                             ['latte-kernel.norm/delta-reduction 'norm-delta]]]
      (let [a (with-redefs-fn {(upstream-fn 'latte-kernel.norm/+unfold-implicit+) (atom nil)
                               (upstream-fn 'latte-kernel.nbe/+unfold-implicit+) (atom nil)}
                (fn []
                  ((upstream-fn 'latte-kernel.norm/install-unfold-implicit!)
                   (upstream-fn 'latte-kernel.typing/unfold-implicit))
                  (a-leg (upstream-fn a-name) [env [] term])))
            c (context-bc host-constant-unfold
                          (list 'let ['k (list 'register-definition
                                               (list 'install-unfold-implicit initial-context 'unfold-host-constants)
                                               (list '->Implicit ''i fail-form))]
                                (list c-name 'k [] (list 'quote term))))]
        (is (agrees? a c))))))


(defn- a-opacity-proof
  ([script] (a-opacity-proof script false))
  ([script install?]
   (let [ns-sym (gensym "latte-proof-a-")
         sandbox (create-ns ns-sym)
         ddef ((upstream-fn 'latte-kernel.defenv/->Definition) 'd [] 0 'body #{} 'T {})
         policy (fn [v flag] (alter-var-root v assoc-in [:opts :opaque] flag))]
     (try
       (intern sandbox 'd ddef)
       (binding [*ns* sandbox]
         (with-redefs-fn {(upstream-fn 'latte-kernel.proof/+opacity-fn+)
                          (atom (if install?
                                  (fn [_ _] (throw (ex-info "Policy not installed" {}))) policy))}
           (fn []
             (when install?
               ((upstream-fn 'latte-kernel.proof/install-opacity-function) policy))
             (a-leg (upstream-fn 'latte-kernel.proof/elab-proof) [[{} {}] [] script]))))
       (finally (remove-ns ns-sym))))))


(deftest test-oracle-slice8-upstream-proof-loop
  (testing "the original proof loop threads updated contexts across state steps"
    (doseq [script ['((:opaque d) (:transparent d) (:opaque d) (:transparent d))
                    '((:opaque d) (:opaque d))
                    '((:opaque d) (:transparent d) (:transparent d))]]
      (let [a (a-opacity-proof script)
            c (context-bc []
                          (list 'elab-proof
                                (list 'register-definition initial-context '(->Definition 'd [] 0 'body #{} 'T {}))
                                [] (list 'quote script)))]
        (is (agrees? a c) "A uses updated Vars; B/C use the returned definition environment")))))


;; =============================================================================
;; Slice 9: a fail-closed kernel gate. Historical slice tests are not the gate.
;; =============================================================================

(def ^:private kernel-gate-corpus
  (edn/read-string (slurp (io/resource "yang/latte_oracle/kernel_gate.edn"))))


(def ^:private kernel-fixture-closure
  (edn/read-string (slurp (io/resource "yang/latte_oracle/fixture_closure.edn"))))


(defn- kernel-symbol
  [{:keys [ns name]}]
  (symbol (str/replace (str ns) "_" "-") (str name)))


(defn- kernel-guest-name
  [s]
  (symbol (str "gate-" (last (str/split (namespace s) #"\.")) "-" (name s))))


(defn- kernel-arities
  [form]
  (when (#{'defn 'defn-} (first form))
    (let [header (drop-while #(not (or (vector? %) (seq? %))) (nnext form))
          params (if (vector? (first header)) [(first header)] (map first header))]
      (mapv (fn [p] (if (some #{'&} p) [:variadic (.indexOf p '&)] (count p))) params))))


(def ^:private kernel-entries
  (mapv (fn [entry]
          (let [s (kernel-symbol entry)]
            (upstream-fn s) ; load the real aliases and refers before resolving source
            (assoc entry :symbol s :arities (kernel-arities (get top-forms [(:ns entry) (:name entry)])))))
        manifest))


(defn- kernel-resolve
  [ns-sym s]
  (when (symbol? s)
    (let [n (the-ns (symbol (str/replace (str ns-sym) "_" "-")))
          v (ns-resolve n s)]
      (when (and (var? v) (str/starts-with? (str (:ns (meta v))) "latte-kernel."))
        (symbol (str (:ns (meta v))) (str (:name (meta v))))))))


(defn- kernel-rewrite
  "Resolve library names against their actual upstream namespace, respecting
   lexical binders and quote. No blanket alias stripping or data rewriting."
  [ns-sym form]
  (letfn [(bindings [pattern] (set (filter symbol? (tree-seq coll? seq pattern))))
          (forms [xs bound] (map #(step % bound) xs))
          (arity
            [xs bound]
            (let [[params & body] xs
                  body (forms body (into bound (bindings params)))
                  body (if (and (= ns-sym 'latte_kernel.syntax) (= 'free-vars (second form))
                                (= '[def-env t] params))
                         [(list* 'let '[def-env (if (map? def-env) (:definitions def-env) def-env)] body)]
                         body)]
              (list* params body)))
          (step
            [x bound]
            (cond
              (symbol? x) (cond
                            (contains? bound x) x
                            (#{"set" "clojure.set"} (namespace x)) (symbol (name x))
                            (= "pp" (namespace x)) (symbol "clojure.pprint" (name x))
                            (contains? '{get-in lookup-in not-empty context-not-empty
                                         ffirst context-ffirst list* context-list-star} x)
                            (get '{get-in lookup-in not-empty context-not-empty
                                   ffirst context-ffirst list* context-list-star} x)
                            :else (if-let [s (kernel-resolve ns-sym x)]
                                    ;; empty-env at the immutable-context boundary
                                    ;; is the same explicit initialization used by
                                    ;; the slice-8 fixtures, including default arities.
                                    (if (= s 'latte-kernel.defenv/empty-env)
                                      initial-context
                                      (if (and (= "latte-kernel.defenv" (namespace s))
                                               (str/starts-with? (name s) "->"))
                                        (symbol (name s)) (kernel-guest-name s))) x))
              (seq? x)
              (let [[op & xs] x]
                (cond
                  (= op 'quote) x
                  (and (= op 'lazy-seq) (= ns-sym 'latte_kernel.utils) (= 'zip (second form)))
                  (step (second x) bound)
                  (and (= ns-sym 'latte_kernel.proof) (= 'elab-proof-step (second form))
                       (#{'(do (elab-opaque def-env args) [:cont [def-env ctx var-deps def-uses]])
                          '(do (elab-transparent def-env args) [:cont [def-env ctx var-deps def-uses]])} x))
                  (list 'let ['next-env (step (second x) bound)]
                        '[:cont [next-env ctx var-deps def-uses]])
                  (#{'fn 'fn*} op)
                  (cons 'fn (if (vector? (first xs)) (arity xs bound)
                                (map #(arity % bound) xs)))
                  (#{'let 'loop} op)
                  (let [[bs & body] xs
                        [bs' scope] (reduce (fn [[out scope] [p init]]
                                              (let [have-type? (and (= ns-sym 'latte_kernel.proof)
                                                                    (= 'elab-have-impl (second form))
                                                                    (= p '[status rec-ty]))
                                                    ;; Both type branches return their context to the
                                                    ;; enclosing have step, including anonymous have.
                                                    init (if have-type?
                                                           (let [[_ test inferred [_ bs result]] init]
                                                             (list 'if test [inferred 'def-env]
                                                                   (list 'let bs [result 'def-env]))) init)
                                                    p (cond
                                                        have-type? [p 'def-env]
                                                        (and (seq? init)
                                                             (= 'latte-kernel.typing/rebuild-type
                                                                (kernel-resolve ns-sym (first init))))
                                                        ;; check-proof calls this with def-env', so
                                                        ;; retain the caller's actual context binding.
                                                        [p (second init)]
                                                        :else p)]
                                                [(conj out p (step init scope)) (into scope (bindings p))]))
                                            [[] bound] (partition 2 bs))]
                    (list* op bs' (forms body scope)))
                  ;; The slice-8 delta boundary: both namespaces use one binding.
                  (and (#{'deref 'clojure.core/deref} op) (= xs '(+unfold-implicit+)))
                  '(:unfold-implicit def-env)
                  :else (apply list (forms x bound))))
              (vector? x) (mapv #(step % bound) x)
              (map? x) (into (empty x) (map (fn [[k v]] [(step k bound) (step v bound)])) x)
              (set? x) (into #{} (forms x bound))
              :else x))]
    (let [[kind fname & header] form
          header (drop-while #(not (or (vector? %) (seq? %))) header)
          guest (kernel-guest-name (kernel-symbol {:ns ns-sym :name fname}))]
      (if (#{'defn 'defn-} kind)
        (list* 'defn guest
               (if (vector? (first header)) (arity header #{}) (map #(arity % #{}) header)))
        (list 'def guest (step (nth form 2) #{}))))))


(def ^:private kernel-context-adapters
  ;; Reuse the established slice-8 representation boundary; no new compiler
  ;; workaround or truncated proof-step dispatcher is admitted here.
  '#{definition? theorem? axiom? notation? implicit? latte-definition?
     local-definitions register-definition fetch-definition registered-definition? qualify-def})


(defn- kernel-adapter-clause
  "The context-adapter stub clause for `n` parameters of `symbol` (simple
   name `name`, namespace `ns-sym`). When upstream's own body for this
   arity is a single self-delegation, the stub mirrors that delegation —
   an ambient `empty-env` argument becomes the declared `initial-context`
   literal, so the adapter's declared path supplies the ambient
   environment (attribution ruling R3) instead of forwarding an arity the
   context layer does not carry. Every other clause keeps the
   arity-preserving forward."
  [ns-sym name symbol n]
  (let [form (get top-forms [ns-sym name])
        header (drop-while #(not (or (vector? %) (seq? %))) (nnext form))
        clauses (if (vector? (first header)) [header] header)
        params (mapv #(clojure.core/symbol (str "p" %)) (range n))
        clause (first (filter #(= n (count (first %))) clauses))
        body (next clause)]
    (if (and (= 1 (count body)) (seq? (first body))
             (symbol? (first (first body)))
             (= symbol (kernel-resolve ns-sym (first (first body)))))
      (let [renames (zipmap (first clause) params)]
        (list params
              (list* name
                     (map (fn [a]
                            (cond
                              (and (symbol? a) (nil? (namespace a))
                                   (= 'latte-kernel.defenv/empty-env (kernel-resolve ns-sym a)))
                              initial-context
                              (get renames a) (get renames a)
                              :else a))
                          (rest (first body))))))
      (list params (list* name params)))))


(defn- kernel-source
  [{:keys [ns name symbol arities disposition admission-reason]}]
  (cond
    (= :excluded disposition)
    (list* 'defn (kernel-guest-name symbol)
           (map (fn [n]
                  (list (mapv #(clojure.core/symbol (str "p" %)) (range n))
                        (list 'throw (list 'ex-info "Excluded nonportable kernel binding"
                                           {:refusal :nonportable-binding :binding (list 'quote symbol) :reason admission-reason})))) arities))
    (and (= ns 'latte_kernel.defenv) (contains? kernel-context-adapters name))
    (list* 'defn (kernel-guest-name symbol)
           (map (fn [n] (kernel-adapter-clause ns name symbol n)) arities))
    (and (= ns 'latte_kernel.proof) (= name '+opacity-fn+))
    ;; The old ambient binding now names the explicit field; evaluating this
    ;; binding cannot initialize an atom or install an implicit closure.
    (list 'def (kernel-guest-name symbol) :opacity-policy)
    (and (= ns 'latte_kernel.typing) (= name 'rebuild-type))
    ;; This allocation boundary returns the updated context explicitly. Its
    ;; original [status type] result remains the first component.
    (list 'defn (kernel-guest-name symbol) '[def-env ctx ty]
          '(let [[vfresh supply] (fresh-symbol {:counter (:fresh def-env)} "fresh")
                 [status ty' _] (gate-typing-type-of-term def-env (cons [vfresh ty] ctx) vfresh)]
             [[status ty'] (assoc def-env :fresh (:counter supply))]))
    (and (= ns 'latte_kernel.proof)
         (contains? '#{elab-opaque elab-transparent install-opacity-function is-opaque?} name))
    (let [adapter (first (filter #(= name (second %)) context-forms))]
      (list* 'defn (kernel-guest-name symbol) (drop 2 adapter)))
    :else (kernel-rewrite ns (get top-forms [ns name]))))


(def ^:private kernel-declaration-refusals
  ;; Pinned upstream declares this function but never defines it. Keeping a
  ;; loud branch-local refusal lets unrelated typing paths compile; executing
  ;; that branch is still a blocked fixture, never an accepted rejection.
  '[(defn gate-typing-type-of-ascribe
      [def-env ctx e u]
      (throw (ex-info "Pinned upstream declaration has no definition"
                      {:refusal :upstream-unbound
                       :binding 'latte-kernel.typing/type-of-ascribe})))])


(def ^:private kernel-sources
  (into (into {} (map (juxt second identity)) kernel-declaration-refusals)
        (map (fn [entry] [(kernel-guest-name (:symbol entry)) (kernel-source entry)])) kernel-entries))


(defn- kernel-call
  [form]
  ;; Fixture code uses fully qualified names. Constructor adapters are the
  ;; five existing context constructors, outside the 143-binding manifest.
  (letfn [(rewrite
            [x]
            (cond
              (and (seq? x) (= 'quote (first x))) x
              (symbol? x) (if (and (namespace x) (str/starts-with? (namespace x) "latte-kernel."))
                            (if (str/starts-with? (name x) "->") (symbol (name x)) (kernel-guest-name x)) x)
              (seq? x) (apply list (map rewrite x))
              (vector? x) (mapv rewrite x)
              (map? x) (into {} (map (fn [[k v]] [(rewrite k) (rewrite v)])) x)
              (set? x) (set (map rewrite x))
              :else x))]
    (rewrite form)))


(defn- kernel-closure
  [call]
  (let [sources (merge (into {} (map (juxt second identity)) context-forms) kernel-sources)
        mentions #(filter (set (keys sources)) (walk-symbols %))
        needed (loop [todo (seq (mentions call)) seen #{}]
                 (if-let [n (first todo)]
                   (if (contains? seen n) (recur (next todo) seen)
                       (recur (concat (next todo) (mentions (get sources n))) (conj seen n))) seen))
        ordered (concat context-forms kernel-declaration-refusals (map #(get kernel-sources (kernel-guest-name (:symbol %))) kernel-entries))]
    (filterv #(contains? needed (second %)) ordered)))


(defn- kernel-fixture-call
  [{:keys [call guest-call definitions project-proof?]} guest?]
  (let [env (if guest? initial-context 'latte-kernel.defenv/empty-env)
        register (if guest? 'register-definition 'latte-kernel.defenv/register-definition)
        env (reduce (fn [env definition] (list register env (if guest? (kernel-call definition) definition))) env definitions)
        call (if guest? (kernel-call (or guest-call call)) call)
        call (if project-proof?
               (list 'let ['r call] '(if (= :ok (first r)) [:ok (rest (second r))] r)) call)]
    (list 'let ['env env] call)))


(defn- evidence-data
  "EDN evidence retains all portable values and metadata. Nonportable host
   payloads are explicit evidence, not accepted representation differences."
  [x]
  (cond
    (fn? x) {:nonportable-host-value (str (class x))}
    (record? x) (evidence-data (upstream-record-data x))
    (map? x) (into {} (map (fn [[k v]] [(evidence-data k) (evidence-data v)])) x)
    (vector? x) (mapv evidence-data x)
    (set? x) (set (map evidence-data x))
    (seq? x) (apply list (map evidence-data x))
    :else x))


(defn- kernel-attempt
  [f]
  (try (f)
       (catch Exception e
         {:status :blocked :message (ex-message e)
          ;; The session and consumer are host VM recovery machinery, not
          ;; portable domain payloads. Walking their closure environments can
          ;; repeatedly expand the entire program.
          :data (evidence-data (dissoc (ex-data e) :session :consumer))
          :host-session-attached? (contains? (ex-data e) :session)
          :causes (mapv (fn [cause] {:class (str (class cause)) :message (ex-message cause)})
                        (take-while some? (iterate ex-cause e)))
          :exception (str (class e))})))


(deftest test-kernel-exhaustion-evidence-is-bounded
  (let [ast (yang/compile-program '[((fn [x] x) 1)] true arg-vector-opts)
        task (future
               (kernel-attempt
                 #(try
                    (compiler-test/run-guest-machine ast (oracle-registry) :walker 1)
                    (catch Exception e
                      ;; Some runners attach the advanced consumer directly;
                      ;; this runner carries it in the observer session.
                      (throw (ex-info (ex-message e)
                                      (assoc (ex-data e) :consumer (get-in (ex-data e) [:session :consumer]))
                                      e))))))
        result (deref task 5000 ::timeout)]
    (when (= ::timeout result) (future-cancel task))
    (is (not= ::timeout result) "Exhaustion evidence finishes within five seconds")
    (is (= :blocked (:status result)))
    (is (= "Guest instruction budget exhausted" (:message result)))
    (is (= {:lane :walker :steps 1} (:data result)))
    (is (true? (:host-session-attached? result)))
    (is (seq (:causes result)))
    (is (every? #(= "Guest instruction budget exhausted" (:message %)) (:causes result)))))


(defn- kernel-a
  [fixture]
  (kernel-attempt
    #(let [value (cond
                   (#{:opacity-proof :opacity-install} (:mode fixture))
                   (let [outcome (a-opacity-proof (:script fixture) (= :opacity-install (:mode fixture)))]
                     (if (= :ok (first (:value outcome)))
                       (update outcome :value (fn [v] [:ok (rest (second v))])) outcome))
                   (= :unfold-install (:mode fixture))
                   (with-redefs-fn {(upstream-fn 'latte-kernel.norm/+unfold-implicit+) (atom nil)
                                    (upstream-fn 'latte-kernel.nbe/+unfold-implicit+) (atom nil)}
                     (fn [] (a-leg (eval (list 'fn [] (kernel-fixture-call fixture false))) [])))
                   (= :namespace-isolation (:mode fixture))
                   (let [sandbox (create-ns (gensym "gate-ambient-a-"))]
                     (try
                       (intern sandbox 'gate-ambient-definition
                               ((upstream-fn 'latte-kernel.defenv/->Definition)
                                'gate-ambient-definition [] 0 'ambient #{} 'T {}))
                       (binding [*ns* sandbox]
                         (refer 'clojure.core)
                         (a-leg (eval (list 'fn [] (kernel-fixture-call fixture false))) []))
                       (finally (remove-ns (ns-name sandbox)))))
                   (#{:unparser :unparser-install} (:mode fixture))
                   (with-redefs-fn {(upstream-fn 'latte-kernel.unparser/+unparser-registry+)
                                    (atom {:fundamental [] :standard [] :user [] :unparsers {}})}
                     (fn []
                       (if (= :unparser-install (:mode fixture))
                         (a-leg (eval (list 'fn [] (kernel-fixture-call fixture false))) [])
                         (do
                           ((upstream-fn 'latte-kernel.unparser/register-unparser!) :gate (eval (:extension fixture)))
                           (a-leg (upstream-fn 'latte-kernel.unparser/unparse) [(:term fixture)])))))
                   :else (a-leg (eval (list 'fn [] (kernel-fixture-call fixture false))) []))]
       {:status :completed :outcome (evidence-data value) :shape (result-shape (evidence-data value))})))


(defn- kernel-c-program
  [fixture]
  (if (= :unparser (:mode fixture))
    (list 'unparse (list 'register-unparser initial-context :gate (:extension fixture)) (list 'quote (:term fixture)))
    (kernel-fixture-call fixture true)))


(defn- kernel-b
  [fixture]
  (kernel-attempt
    #(let [sandbox (create-ns (gensym "kernel-gate-b-"))
           call (kernel-c-program fixture)
           forms (kernel-closure call)
           host-form (fn [form]
                       ;; Preserve the actual one-argument gensym call. Only
                       ;; the slice-8 explicit two-argument supply is adapted.
                       (walk/postwalk
                         (fn [x]
                           (if (and (seq? x) (= 'gensym (first x)) (= 2 (count x)))
                             (cons 'clojure.core/gensym (rest x)) x)) form))]
       (try
         (binding [*ns* sandbox]
           (refer 'clojure.core)
           (require '[clojure.set :refer [union difference]])
           (eval (list* 'declare
                        (concat (map second forms)
                                (for [[ns-sym source] all-forms
                                      form source :when (= 'declare (first form))
                                      s (rest form)
                                      :let [qualified (kernel-resolve ns-sym s)]
                                      :when qualified]
                                  (kernel-guest-name qualified)))))
           (doseq [form forms] (eval (context-host-form (host-form form))))
           (let [v (a-leg (eval (list 'fn [] (context-host-form (host-form call)))) [])]
             {:status :completed :outcome (evidence-data v) :shape (result-shape (evidence-data v))}))
         (finally (remove-ns (ns-name sandbox)))))))


(def ^:private kernel-context-integration-names
  ;; Integrations also enter existing explicit-context adapters directly,
  ;; rather than their gate-* wrappers. Keep those identities explicit.
  {'latte-kernel.defenv/local-definitions 'local-definitions
   'latte-kernel.defenv/register-definition 'register-definition
   'latte-kernel.proof/is-opaque? 'is-opaque?
   'latte-kernel.unparser/unregister-unparser! 'unregister-unparser
   'latte-kernel.unparser/unparse 'unparse
   'latte-kernel.unparser/register-unparser! 'register-unparser
   'latte-kernel.unparser/unparse-term-once 'unparse-term-once
   'latte-kernel.unparser/unparse-term-rec 'unparse-term-rec
   'latte-kernel.norm/install-unfold-implicit! 'install-unfold-implicit
   'latte-kernel.unparser/install-fundamental-unparsers! 'install-fundamental-unparsers
   'latte-kernel.unparser/prod-impl-unparser 'prod-impl-unparser})


(def ^:private kernel-ambient-bindings
  "The upstream module-level state the immutable-context adaptation
   threads explicitly: the empty environment and the atoms the context
   layer carries as its kernel parameter. The retired offset table
   served these correspondences; this set derives them."
  '#{latte-kernel.defenv/empty-env
     latte-kernel.unparser/+unparser-registry+
     latte-kernel.norm/+unfold-implicit+
     latte-kernel.nbe/+unfold-implicit+
     latte-kernel.proof/+opacity-fn+})


(defn- kernel-ambient-reference?
  "True when `form` references one of the ambient state bindings outside
   `bound` (the enclosing clause's own parameters)."
  [ns-sym form bound]
  (some (fn [s]
          (and (symbol? s) (nil? (namespace s))
               (not (contains? bound s))
               (contains? kernel-ambient-bindings (kernel-resolve ns-sym s))))
        (tree-seq coll? seq form)))


(defn- kernel-clauses
  "The `[params body…]` clauses of one upstream top-level defn."
  [ns-sym name]
  (let [form (get top-forms [ns-sym name])
        header (drop-while #(not (or (vector? %) (seq? %))) (nnext form))]
    (if (vector? (first header)) [header] header)))


(defn- kernel-program-clauses
  "The `[params body…]` clauses of `fname` among the forms the C program
   assembles (the checked-in context layer, the guest sources, the
   branch-local refusals), or nil when the name is not carried."
  [fname]
  (some (fn [form]
          (when (= fname (second form))
            (let [header (drop-while #(not (or (vector? %) (seq? %))) (nnext form))]
              (if (vector? (first header)) [header] header))))
        (concat context-forms (vals kernel-sources) kernel-declaration-refusals)))


(def ^:private kernel-ambient-correspondence
  "The mechanically derived attribution paths (R6/R7), keyed
   `[upstream-symbol arity]` → #{[carrier guest-arity kind]}: for each
   admitted upstream function and arity, the guest/context callable and
   guest arity that carry the adapted form. Three shapes are derived
   from the pinned upstream forms and the checked-in context layer, all
   anchored to the context layer's own convention (context.edn's header:
   a context occupies the def-env argument throughout the adapted
   kernel):
   - :ambient-delegation — an upstream clause whose body is a single
     self-delegation through the module-level ambient state (R3's
     disjointness pair: `registered-definition?` /1) maps to the carrier
     the adapter's mirrored stub delegates to; the credit query requires
     such a site's compiled context argument to be the adapter-supplied
     ambient literal.
   - :context-shift — a carrier clause `[kernel …]`. When the upstream
     clause at the SAME arity carries the env at its head (the pinned
     upstream names that parameter `def-env`/`env`), the kernel replaced
     the parameter (register-definition /2 and /3, local-definitions /1,
     elab-opacity /3, fetch-definition, qualify-def, …); otherwise the
     kernel was ADDED at one fewer upstream argument — exactly the
     retired offset table's hand-declared +1 shifts (unregister-unparser!
     /1, unparse /1, register-unparser! /2 and /3, install-unfold-implicit!
     /1, install-fundamental-unparsers! /0, install-opacity-function /1).
     These arities thread the adapted state explicitly, so their witness
     is a site-linked record, not an ambient literal.
   - :context-copy — a declared integration name whose checked-in copy
     keeps the upstream parameter vector verbatim (is-opaque?, the
     unparse-term-* walkers) carries the same arity.
   The retired offset table `kernel-context-extra-arguments` served
   exactly the :context-shift correspondences by hand."
  (into {}
        (mapcat
          (fn [{:keys [ns name symbol]}]
            (let [integration (get kernel-context-integration-names symbol)
                  guest (kernel-guest-name symbol)
                  has-arity? (fn [fname n]
                               (let [ks (kernel-program-clauses fname)]
                                 (and fname ks (some #(= n (count (first %))) ks))))
                  carrier (fn [n]
                            (or (when (has-arity? integration n) integration)
                                ;; the stubbed adapters mirror through the
                                ;; context layer's own name
                                (when (and (contains? kernel-context-adapters name)
                                           (has-arity? name n))
                                  name)
                                (when (has-arity? guest n) guest)))
                  delegation (fn [clause]
                               (let [body (next clause)]
                                 (when (and (= 1 (count body)) (seq? (first body))
                                            (symbol? (first (first body)))
                                            (= symbol (kernel-resolve ns (first (first body)))))
                                   (rest (first body)))))
                  upstream-clauses (kernel-clauses ns name)
                  upstream-arities (into #{} (map #(count (first %))) upstream-clauses)
                  ;; the pinned upstream's env-position parameter names: the
                  ;; defenv/proof context family names the env argument
                  ;; def-env/env at the leading position it occupies
                  env-param? '#{def-env env}
                  from-delegations
                  (set (for [clause upstream-clauses
                             :let [args (delegation clause)]
                             :when (and args (kernel-ambient-reference? ns args (set (first clause))))
                             :let [g (carrier (count args))]
                             :when g]
                         [(count (first clause)) [g (count args) :ambient-delegation]]))
                  ctx-carriers (distinct
                                 (remove nil?
                                         [integration
                                          (when (some #(= name (second %)) context-forms) name)
                                          (when (and ((set (map second (vals kernel-sources))) guest)
                                                     (= 'kernel (ffirst (kernel-program-clauses guest))))
                                            guest)]))
                  from-shift
                  (set (for [g ctx-carriers
                             clause (kernel-program-clauses g)
                             :let [ps (first clause)]
                             :when (and (seq ps) (= 'kernel (first ps)))
                             :let [;; the kernel REPLACED the upstream env
                                   ;; parameter (same arity) when that clause
                                   ;; carries the env at its head; otherwise it
                                   ;; was ADDED at one fewer upstream argument
                                   ;; (the retired offset table's +1 shifts)
                                   replaced (some (fn [u]
                                                    (let [up (first u)]
                                                      (when (and (= (count up) (count ps))
                                                                 (env-param? (first up)))
                                                        (count up))))
                                                  upstream-clauses)
                                   added (let [a (dec (count ps))]
                                           (when (and (contains? upstream-arities a)
                                                      (not (some (fn [u]
                                                                   (let [up (first u)]
                                                                     (and (= (count up) a)
                                                                          (env-param? (first up)))))
                                                                 upstream-clauses)))
                                             a))
                                   m (or replaced added)]
                             :when (and m
                                        (not (contains? (set (map first from-delegations)) m)))]
                         [m [g (count ps) :context-shift]]))
                  from-copies
                  (set (for [g [(get kernel-context-integration-names symbol)]
                             :when g
                             clause (kernel-program-clauses g)
                             :let [ps (first clause)]
                             :when (and (seq ps) (not= 'kernel (first ps))
                                        (contains? upstream-arities (count ps))
                                        (not (contains? (set (map first from-delegations))
                                                        (count ps)))
                                        (not (contains? (set (map first from-shift))
                                                        (count ps))))]
                         [(count ps) [g (count ps) :context-copy]]))]
              (map (fn [[m entries]] [[symbol m] (into #{} (map second) entries)])
                   (group-by first (into (into from-delegations from-shift) from-copies))))))
        (filter #(and (contains? #{'defn 'defn-} (:kind %))
                      (= :admitted (:disposition %)))
                kernel-entries)))


(def ^:private kernel-site-prelude
  "The caller-side site channel (attribution ruling R2): one heap cell
   holding the site id of the most recent stamped application, and the
   stamping helper. A body entered without a stamped caller records the
   :gate-site/none refusal site (R2b case 6)."
  '[(def gate-site-slot (cell/new :gate-site/none))
    (defn gate-site!
      [k args]
      (cell/set! gate-site-slot k) args)])


(defn- kernel-trace-form
  "Append the selected function/arity at body entry, including indirect
   calls, carrying the CALLER's site id read from the site slot (R2: the
   record is [fname arity caller-site-id]; caller-side tagging, never
   callee-side). Only C is instrumented; A/B run their original paths."
  [form]
  (if (#{'defn 'defn-} (first form))
    (let [[op fname & header] form
          header (drop-while #(not (or (vector? %) (seq? %))) header)
          trace-arity (fn [[params & body]]
                        (let [n (if (some #{'&} params)
                                  [:variadic (.indexOf params '&)] (count params))]
                          (list* params
                                 (list 'stream/put! 'gate-execution-trace
                                       (list 'vector (list 'quote fname) n
                                             '(cell/get gate-site-slot)))
                                 body)))]
      (list* op fname (if (vector? (first header))
                        (trace-arity header) (map trace-arity header))))
    form))


(def ^:private kernel-ambient-state-constructors
  "Context-layer constructors that thread the ambient state: resolving an
   argument through one of these (at its context position) keeps the
   ambient claim alive, because upstream's ambient API reads exactly this
   state. Derived from the checked-in context layer's names."
  (into '#{kernel-context}
        (filter #(or (str/starts-with? (str %) "install-")
                     (str/starts-with? (str %) "register-")
                     (str/starts-with? (str %) "unregister-")))
        (map second context-forms)))


(defn- kernel-stamped-args?
  "The stamping wrapper around one already-stamped argument vector:
   `(dsg__intra_call gate-site! (conj [] k <args>))`. Returns the wrapped
   original arguments node. Under the continuation lane the helper returns
   the argument vector as a plain value, so there is no `data/nth` outcome
   unwrap to strip."
  [node]
  (when (and (map? node) (= :application (:type node))
             (= :variable (:type (:operator node)))
             (= 'dsg__intra_call (:name (:operator node)))
             (= 2 (count (:operands node)))
             (let [helper (first (:operands node))]
               (and (map? helper) (= :variable (:type helper))
                    (= 'gate-site! (:name helper))))
             (let [args (get-in node [:operands 1])]
               (and (map? args) (= :application (:type args))
                    (= :variable (:type (:operator args)))
                    (= 'conj (:name (:operator args))))))
    (get-in node [:operands 1 :operands 2])))


(defn- kernel-site-arguments
  "The argument expression nodes of one compiled call site: the operand
   vector the argument-vector convention assembles. `node` is either the
   intra-unit fast path `(dsg__intra_call f <args>)` or the routed guest
   call's arguments binding; the arguments are the elements of the
   constructed vector (a constant :literal, or `(conj [] e1 e2 ...)`),
   with any stamping wrapper already applied to inner expressions
   stripped first."
  [args-node]
  (let [args-node (or (kernel-stamped-args? args-node) args-node)]
    (cond
      (= :literal (:type args-node)) (:value args-node)
      (and (= :application (:type args-node))
           (= :variable (:type (:operator args-node)))
           (= 'conj (:name (:operator args-node)))
           (= :literal (:type (first (:operands args-node)))))
      (rest (:operands args-node))
      :else nil)))


(defn- kernel-intra-call?
  "The compiled shape of one intra-unit guest call under the
   argument-vector convention: an application of the unit's dispatcher
   whose first operand names the callee and whose second is the assembled
   argument vector."
  [node]
  (and (= :application (:type node))
       (= :variable (:type (:operator node)))
       (= 'dsg__intra_call (:name (:operator node)))
       (= 2 (count (:operands node)))))


(defn- kernel-routing?
  "True when `node` mentions the admission routing's own vocabulary —
   the dispatcher predicate or the invocation transfer — distinguishing
   a compiled guest call's routing from an ordinary nested let (the two
   share the let-lowering shape)."
  [node]
  (letfn [(any?
            [n]
            (or (and (map? n) (= :variable (:type n))
                     (contains? '#{dsg__admitted? yang.call/invoke
                                   yang.call/invoke-with}
                                (:name n)))
                (and (coll? n) (some any? n))))]
    (boolean (any? node))))


(defn- kernel-routed-call?
  "The compiled shape of one non-intra guest call: the admission routing
   binds the evaluated callee, then the evaluated argument vector, then
   routes. Returns the routing's arguments binding node, or nil for any
   other let-shaped chain: the arguments binding must carry a real
   argument-vector construction (a constant or a conj application)."
  [node]
  (when (and (= :application (:type node))
             (= :lambda (:type (:operator node)))
             (= 1 (count (:params (:operator node))))
             (= 1 (count (:operands node)))
             (map? (:body (:operator node))))
    (let [inner (:body (:operator node))]
      (when (and (= :application (:type inner))
                 (= :lambda (:type (:operator inner)))
                 (= 1 (count (:params (:operator inner))))
                 (= 1 (count (:operands inner)))
                 (kernel-routing? (:body (:operator inner)))
                 (or (= :literal (:type (first (:operands inner))))
                     (and (map? (first (:operands inner)))
                          (kernel-site-arguments (first (:operands inner))))))
        inner))))


(defn- kernel-resolve-site-arg
  "Classify one call-site argument expression for the derived site table:
   :ambient-literal (the adapter-supplied empty-context initialization,
   possibly through let bindings and ambient-state constructors), :literal,
   :variable (a parameter or an unresolvable binding — R2b case 7), or
   :computed. `scope` maps bound names to their init nodes; `params` are
   the enclosing binders."
  [node scope params depth]
  (cond
    (or (nil? node) (> depth 8)) :computed
    (= :literal (:type node)) :literal
    ;; constant-argument values (a fully constant argument vector's
    ;; elements arrive as data, not nodes)
    (not (coll? node)) :literal
    (= :variable (:type node))
    (if (contains? params (:name node))
      :variable
      (if-let [init (get scope (:name node))]
        (recur init scope params (inc depth))
        :variable))
    (and (= :application (:type node)) (kernel-intra-call? node)
         (contains? kernel-ambient-state-constructors
                    (get-in node [:operands 0 :name])))
    (let [args-node (second (:operands node))
          arg-nodes (kernel-site-arguments args-node)
          first-arg (if (sequential? arg-nodes) (first arg-nodes)
                        (when (= :literal (:type (or (kernel-stamped-args? args-node) args-node)))
                          (first (:value (or (kernel-stamped-args? args-node) args-node)))))]
      (if (and (= 'kernel-context (get-in node [:operands 0 :name]))
               (map? first-arg) (= :literal (:type first-arg))
               (= [{} {}] (:value first-arg)))
        :ambient-literal
        (if (and (sequential? arg-nodes) (= 1 (count arg-nodes))
                 (= :ambient-literal (kernel-resolve-site-arg (first arg-nodes)
                                                              scope params (inc depth))))
          :ambient-literal :computed)))
    ;; the compiler's outcome unwrap and let-hoist of one call: the
    ;; argument expression is (data/nth <hoisted-binding> 1), and the
    ;; binding's init is the call itself — resolve through it
    (and (= :application (:type node))
         (= :variable (:type (:operator node)))
         (= 'data/nth (:name (:operator node)))
         (= 2 (count (:operands node)))
         (let [idx (second (:operands node))]
           (and (map? idx) (= :literal (:type idx)) (number? (:value idx)) (pos? (:value idx)))))
    (recur (first (:operands node)) scope params (inc depth))

    :else :computed))


(defn- kernel-under-dispatcher
  "Split the compiled program into [outer-wrappers dispatcher-application
   main-body]: everything before the unit dispatcher binding evaluates
   before `dsg__intra_call` is bound, so a stamp there would not resolve.
   Returns the applications above the dispatcher binding, the application
   whose operator IS the dispatcher lambda, and that lambda's body — or
   [nil nil ast] when no dispatcher wrapper exists (stamping then covers
   the whole tree and the admission check reports the stamp)."
  [ast]
  (loop [wrappers [] node ast]
    (if (and (map? node) (= :application (:type node))
             (map? (:operator node)) (= :lambda (:type (:operator node)))
             (= '[dsg__admitted? dsg__intra_call] (vec (:params (:operator node)))))
      [wrappers node (:body (:operator node))]
      (if (and (map? node) (= :application (:type node))
               (map? (:operator node)) (= :lambda (:type (:operator node))))
        (recur (conj wrappers node) (:body (:operator node)))
        [nil nil ast]))))


(defn- kernel-stamp-sites
  "Assign compile-time-unique caller-site ids at the compiled program's
   APPLICATION EXPRESSIONS, post-macroexpansion (R2), and derive the site
   table mechanically (R7). Two compiled call shapes carry instrumented
   invocations: the intra-unit fast path and the admission-routed guest
   call. Each stamped site threads its argument vector through
   gate-site!, which sets the site slot after operand evaluation and
   immediately before the invocation, so nested stamped calls record
   their own sites first and the callee's body-entry record reads the
   applying site. Returns {:ast stamped-ast :sites [rows]} with one row
   per stamped site: {:site id :callee name-or-:dynamic :arg-shapes […]
   :def enclosing-definition :delegation? bool}. Unstampable calls
   (library and native names) never enter an instrumented body; the
   dispatcher's own bare application is not a guest-call shape and is
   left alone."
  [ast instrumented]
  (let [counter (atom 0)
        sites (atom [])
        stamp (fn [args-node callee def-name scope params]
                (let [k (swap! counter inc)
                      shapes (if (= :dynamic callee)
                               [:dynamic-callee]
                               (mapv #(kernel-resolve-site-arg % scope params 0)
                                     (or (kernel-site-arguments args-node) [])))]
                  (swap! sites conj {:site k
                                     :callee callee
                                     :arg-shapes (vec shapes)
                                     :def def-name
                                     :delegation? (and (symbol? callee)
                                                       (= callee def-name))})
                  ;; the stamp calls the helper the way the compiler calls
                  ;; any intra-unit definition — through the unit's
                  ;; dispatcher with [k args]. Under the continuation lane the
                  ;; helper returns the argument vector as a plain value (no
                  ;; outcome tuple to unwrap), so the stamped expression's
                  ;; value IS the argument vector.
                  {:type :application
                   :operator {:type :variable :name 'dsg__intra_call}
                   :operands [{:type :variable :name 'gate-site!}
                              {:type :application
                               :operator {:type :variable :name 'conj}
                               :operands [{:type :literal :value []}
                                          {:type :literal :value k}
                                          args-node]}]}))
        walk (fn walk
               [node def-name scope params]
               (cond
                 (map? node)
                 (let [t (:type node)]
                   (cond
                     ;; a definition: track the enclosed name, never stamp
                     (and (= :application t)
                          (= :variable (:type (:operator node)))
                          (= 'yin/def (:name (:operator node)))
                          (= :literal (:type (first (:operands node)))))
                     (assoc node :operands (mapv #(walk % (:value (first (:operands node))) scope params)
                                                 (:operands node)))

                     ;; the intra-unit fast path: the callee names an
                     ;; instrumented definition — a direct site. The
                     ;; arguments are walked FIRST (their nested sites
                     ;; stamp during argument evaluation), then wrapped.
                     (and (= :application t) (kernel-intra-call? node)
                          (= :variable (:type (first (:operands node))))
                          (contains? instrumented (get-in node [:operands 0 :name])))
                     (let [callee (first (:operands node))
                           args-node (second (:operands node))
                           walked-args (walk args-node def-name scope params)]
                       (assoc node :operands [callee
                                              (stamp walked-args
                                                     (:name callee)
                                                     def-name scope params)]))

                     ;; the admission-routed guest call: a computed or
                     ;; out-of-unit callee — an indirect site (R2). The
                     ;; stamp wraps the routed arguments binding; the walk
                     ;; then descends through the stamp into the argument
                     ;; expressions and the routing.
                     (and (= :application t) (kernel-routed-call? node))
                     (let [inner (kernel-routed-call? node)
                           stamped-inner (assoc inner :operands
                                                [(stamp (first (:operands inner))
                                                        :dynamic def-name scope params)])
                           walked-inner (walk stamped-inner def-name scope params)]
                       (-> node
                           (assoc-in [:operator :body] walked-inner)
                           (update :operands (fn [ops]
                                               (mapv #(walk % def-name scope params) ops)))))

                     ;; let-lowering: a one-param lambda applied to one
                     ;; operand binds that parameter to the operand's
                     ;; expression for the site-shape resolution
                     (and (= :application t)
                          (= :lambda (:type (:operator node)))
                          (= 1 (count (:params (:operator node))))
                          (= 1 (count (:operands node))))
                     (let [[p init] [(first (:params (:operator node))) (first (:operands node))]
                           walked-init (walk init def-name scope params)]
                       (-> node
                           (assoc-in [:operator :body]
                                     (walk (:body (:operator node)) def-name
                                           (assoc scope p walked-init) params))
                           (assoc :operands [walked-init])))

                     ;; an ordinary lambda's binders shadow: references to
                     ;; them are the caller-owned :variable shape
                     (and (map? node) (= :lambda t))
                     (assoc node :body (walk (:body node) def-name scope
                                             (into params (:params node))))

                     :else (into {} (map (fn [[k v]]
                                           (if (coll? v)
                                             [k (walk v def-name scope params)]
                                             [k v])))
                                 node)))
                 (vector? node) (mapv #(walk % def-name scope params) node)
                 (seq? node) (apply list (map #(walk % def-name scope params) node))
                 :else node))
        [wrappers dispatcher-app main-body] (kernel-under-dispatcher ast)
        stamped-body (walk main-body nil {} #{})
        stamped (if dispatcher-app
                  (reduce (fn [body wrapper]
                            (assoc-in wrapper [:operator :body] body))
                          (assoc-in dispatcher-app [:operator :body] stamped-body)
                          (rseq wrappers))
                  stamped-body)]
    {:ast stamped :sites (vec @sites)}))


(defn- kernel-record-credits?
  "One caller-site-tagged trace record `[fname arity caller-site]` against
   the attribution contract for upstream `symbol` at arity `n` (R1-R4):
   the degenerate 1:1 path matches the arity-preserving guest definition
   at any real site; a derived correspondence path matches its carrier
   callable and arity only through a SITE-LINKED record — the site
   table's static callee at that site is the carrier itself (R2b case 6:
   indirect origins refuse unless site-linked) — that is not a
   self-delegation (R2's rule: the originating call is the credited
   event). An :ambient-delegation path — the R3 disjointness pair —
   additionally requires the site's compiled context argument (position
   0) to have resolved to the adapter-supplied ambient literal;
   :context-shift and :context-copy paths thread the adapted state
   explicitly (the retired offset table's scope, R6) and impose no
   ambient-literal shape: their witness is the site-linked body entry of
   the carrier, whatever context value the site threads. The
   :gate-site/none entry site never credits."
  [record table symbol n]
  (let [[fname arity site] record]
    (and (vector? record) (= 3 (count record))
         (not= :gate-site/none site)
         (or (and (= fname (kernel-guest-name symbol)) (= arity n))
             (boolean (some (fn [[g t kind]]
                              (let [site-row (get table site)]
                                (and (= fname g) (= arity t)
                                     site-row (= g (:callee site-row))
                                     (not (:delegation? site-row))
                                     (or (not= :ambient-delegation kind)
                                         (= :ambient-literal (first (:arg-shapes site-row)))))))
                            (get kernel-ambient-correspondence [symbol n])))))))


(defn- kernel-ambient-site?
  "R2b case 5's compile-time verification, one site at a time: a site
   may claim the ambient correspondence only when the derived table says
   its compiled context argument (position 0) IS the adapter-supplied
   ambient literal and the site is not a self-delegation. A declaration
   naming any other site refuses."
  [table site-id]
  (let [site-row (get table site-id)]
    (and site-row
         (not (:delegation? site-row))
         (= :ambient-literal (first (:arg-shapes site-row))))))


(defn- kernel-stale-sites
  "R2b case 8's diagnostic: site ids the declared table carries that no
   lane's trace ever records — a stale table after a fixture edit. The
   result is recorded data (never silent non-credit): only sites the
   attribution could have consulted (direct sites of instrumented
   callees) participate."
  [table lanes]
  (let [recorded (into #{}
                       (comp (mapcat #(get-in lanes [% :executed-calls]))
                             (filter vector?)
                             (map #(nth % 2 nil)))
                       [:c-walker :c-semantic])]
    (vec (sort (for [[k s] table
                     :when (and (symbol? (:callee s)) (not (contains? recorded k)))]
                 k)))))


(defn- kernel-executed-arity?
  "The ruled attribution query over `row`'s two caller-site-tagged C-lane
   traces: upstream `symbol` at arity `n` is credited when BOTH lanes
   record a body entry the query admits (kernel-record-credits?), and
   the site table in the row is the one this program's canonical source
   hashed to (R2b case 3: a table from another fixture, or a stale table
   after an edit, refuses). The retired offset-table matching (any
   record at arity n+offset, from any call origin) is gone."
  [row symbol n]
  (let [table (:site-table row)
        hash-matches? (or (nil? (:site-table-hash row))
                          (= (:canonical-source-hash row) (:site-table-hash row)))]
    (and hash-matches?
         (every? (fn [lane]
                   (some #(kernel-record-credits? % table symbol n)
                         (get-in row [lane :executed-calls])))
                 [:c-walker :c-semantic]))))


(defn- kernel-canonical-source-hash
  "The canonical-source-hash of one fixture's inlined adaptation (R2b
   case 3): the digest of the assembled context layer and guest sources —
   the adapter this fixture inlines. Reader-generated symbols are renamed
   by first occurrence across forms and call, preserving shared references
   without depending on the process's gensym counter. A trace evaluated
   against a table whose recorded hash differs refuses attribution."
  [forms call]
  (let [source [forms call]
        gensyms (distinct (filter #(and (symbol? %)
                                        (re-matches #"^[a-zA-Z0-9_.-]+__\d+(?:__auto__)?#?$"
                                                    (name %)))
                                  (tree-seq coll? seq source)))
        renames (into {} (map-indexed (fn [i s] [s (symbol (str "$gensym_" (inc i)))])
                                      gensyms))
        payload (binding [*print-length* nil *print-level* nil]
                  (pr-str (walk/postwalk-replace renames source)))]
    (apply str (map #(format "%02x" (bit-and 255 %))
                    (.digest (java.security.MessageDigest/getInstance "SHA-256")
                             (.getBytes payload "UTF-8"))))))


(deftest test-kernel-canonical-source-hash
  (testing "fresh reads of pinned NBE source ignore reader gensym counters"
    (let [forms-a (read-forms 'latte_kernel.nbe)
          forms-b (read-forms 'latte_kernel.nbe)
          call '(latte-kernel.nbe/evaluation 'x)]
      (is (not= (pr-str forms-a) (pr-str forms-b))
          "the regression actually exercises reader-generated symbols")
      (is (= (kernel-canonical-source-hash forms-a call)
             (kernel-canonical-source-hash forms-b call)))))
  (testing "canonical names preserve symbol identity across forms and call"
    (doseq [[a b] '[[p1__123# p1__456#]
                    [value__123__auto__ value__456__auto__]
                    [G__123 G__456]]]
      (is (= (kernel-canonical-source-hash [(list 'fn [a] a)] (list a))
             (kernel-canonical-source-hash [(list 'fn [b] b)] (list b)))))
    (is (not= (kernel-canonical-source-hash '[(fn [p1__123#] p1__123#)] '(p1__123#))
              (kernel-canonical-source-hash '[(fn [p1__123#] p1__456#)] '(p1__123#)))
        "distinct generated symbols must remain distinct")
    (is (not= (kernel-canonical-source-hash '[(def x 1)] 'x)
              (kernel-canonical-source-hash '[(def x 2)] 'x))
        "ordinary source edits still invalidate the hash")))


(defn- fresh-admission-run
  "Allocate ONE admission run, at the run composition (the top of an
   evidence/binding run): a fresh scope supplied as plain data plus a
   run-local ordinal allocator. Thread the returned run through
   `kernel-row` / `kernel-compile` via the `-in-run` callers, which
   assign DISTINCT ordinals within this run. Fresh runs call this
   again and get distinct scopes; replay does NOT call this — it
   explicitly re-supplies the retained run's scope+ordinal (or reuses
   the retained compilation). No global counter: the atom is created
   per run, held only by the run map the composition threads."
  [label]
  {:scope (str (name label) "-" (java.util.UUID/randomUUID))
   :next-ordinal (atom 0)})


(defn- admission-run-ordinal
  [run]
  (let [ordinal @(:next-ordinal run)]
    (swap! (:next-ordinal run) inc)
    ordinal))


(defn- kernel-compile
  "Compile one oracle fixture under an explicit admission coordinate.

   The admitting composition supplies `admission-scope` and the
   per-scope `admission-ordinal` (both plain data); the batch token
   of the admission origin `[:source :program batch-token j]` is the
   pair `[admission-scope admission-ordinal]` and the member index
   `j` is zero, because each admission here is a separately admitted
   single-member batch — the ordinal therefore distinguishes
   admissions through the token, and projected keys (reconstructed
   from a single-member envelope, member index always zero) agree
   with the recorded origin for every ordinal. Both coordinates are
   recorded WHEN ADMITTING, before compilation, validated by the
   same origin-shape rules as every source origin
   (`vm/source-origin`), and retained with the compilation as
   `:admission-origin` plain data. There is no per-JVM global
   counter (r3 F2) and no default admission stamping every admission
   alike (r4 F2): every caller supplies its coordinates explicitly,
   and replay of one retained compilation reuses its recorded
   coordinate."
  ([fixture admission-scope admission-ordinal]
   (let [call (kernel-c-program fixture)
         forms (kernel-closure call)
         source-hash (kernel-canonical-source-hash forms call)
         admission-origin (vm/source-origin
                            :program [admission-scope admission-ordinal] 0)
         program (into (into ['(def gate-execution-trace (stream/make 1000000))]
                             kernel-site-prelude)
                       (concat (map kernel-trace-form forms) [call]))
         program (into (yang/library-for program) program)
         opts (update arg-vector-opts :native into
                      (map (fn [[op profile]] [(clojure.core/symbol "stream" (name op)) profile])
                           module/stream-profiles))
         compiled (yang/compile-program program true opts)
         ;; caller-side site ids at the compiled application expressions,
         ;; post-macroexpansion (R2), with the mechanically derived site
         ;; table (R7); every assembled definition is instrumented
         {stamped :ast sites :sites} (kernel-stamp-sites
                                       compiled (into #{} (map second) forms))
         missing (unbound-ast-symbols stamped program)]
     (if (seq missing)
       {:status :blocked :phase :admission :blocked-by (vec (sort missing))
        :owners (into {} (for [s missing]
                           [s (mapv second (filter #(some #{s} (tree-seq coll? seq %)) program))]))}
       {:status :compiled :ast stamped :forms forms :compiled-ast compiled
        :admission-origin admission-origin
        :admission-scope admission-scope
        :admission-ordinal admission-ordinal
        :site-table (into {} (map (juxt :site identity)) sites)
        :site-table-hash source-hash
        :sites sites
        :canonical-source-hash source-hash}))))


(defn- kernel-compile-in-run
  "Compile `fixture` as the next admission of `run`: the run's scope
   with a distinct ordinal assigned by the run composition."
  [run fixture]
  (kernel-compile fixture (:scope run) (admission-run-ordinal run)))


(defn- kernel-c
  [compiled lane]
  (if (= :compiled (:status compiled))
    (assoc (kernel-attempt
             #(let [machine (compiler-test/run-guest-machine
                              (:ast compiled) (oracle-registry yang/kind-module) lane 4000000)
                    v (vm/value machine)
                    ref (get-in machine [:store 'gate-execution-trace])
                    handle (get-in machine [:resources (:id ref)])]
                (when-not (and (vm/halted? machine) handle)
                  (throw (ex-info "Execution trace did not complete" {:lane lane})))
                {:status :completed :outcome (evidence-data v) :shape (result-shape (evidence-data v))
                 :executed-calls (tu/drain handle)}))
           :lane lane)
    (assoc compiled :lane lane)))


;; =============================================================================
;; Depth harness (max live-K per workload)
;; =============================================================================
;;
;; Measurement only: no admission gate and no correctness assertions. Each
;; kernel-gate fixture is a workload; the max continuation depth (the semantic
;; lane's K-vector length, the walker lane's :next chain length) is recorded
;; per lane. The same instrumentation applies to the pre-port outcome-model
;; tree and the post-port continuation tree, so the two numbers are directly
;; comparable.

(defn- kernel-lane-depth
  "The max live-K depth of one compiled fixture on `lane`."
  [compiled lane]
  (when (= :compiled (:status compiled))
    (:max-depth (compiler-test/run-guest-machine-depth
                  (:ast compiled) (oracle-registry yang/kind-module) lane 4000000))))


(defn kernel-gate-depths
  "Per-workload max live-K depth for the kernel-gate corpus, both lanes, as a
   sorted map fixture-id -> {:walker n :semantic m}."
  []
  (let [run (fresh-admission-run :depth-harness)]
    (into (sorted-map)
          (for [fixture (:fixtures kernel-gate-corpus)
                :let [compiled (kernel-compile-in-run run fixture)]]
            [(:id fixture)
             {:walker (kernel-lane-depth compiled :walker)
              :semantic (kernel-lane-depth compiled :semantic)}]))))


(defn write-kernel-gate-depths!
  "Measure the checked-out revision and write its depth EDN to `file`."
  [file]
  (let [{:keys [exit out err]} (shell/sh "git" "rev-parse" "--short=8" "HEAD")
        _ (when-not (zero? exit)
            (throw (ex-info "Cannot determine depth measurement revision" {:error err})))
        revision (str/trim out)
        depths (kernel-gate-depths)]
    (spit file (str ";; GENERATED max live-K depth per kernel-gate workload (both lanes), " revision ".\n"
                    (pr-str {:revision revision
                             :workload-count (count depths)
                             :workloads depths}) "\n"))
    depths))


(defn write-kernel-gate-comparison!
  "Compare the two depth EDNs and write the complete comparison to `file`."
  [baseline-file post-file file]
  (let [baseline (edn/read-string (slurp baseline-file))
        post (edn/read-string (slurp post-file))
        b (:workloads baseline)
        p (:workloads post)
        ids (sort (keys p))
        depth #(apply max (vals %))]
    (when-not (and (= (set (keys b)) (set ids))
                   (= (:workload-count baseline) (count b))
                   (= (:workload-count post) (count p))
                   (every? #(and (every? integer? (vals (b %)))
                                 (every? integer? (vals (p %)))
                                 (pos? (depth (b %)))) ids))
      (throw (ex-info "Incomplete or invalid depth comparison" {})))
    (let [ratios (map #(/ (depth (p %)) (depth (b %))) ids)
          maximum (apply max ratios)
          flagged (filter #(> (/ (depth (p %)) (depth (b %))) 10) ids)]
      (spit file
            (str "# Kernel-gate depth comparison\n\n"
                 "Baseline: `" (:revision baseline) "` (outcome model). Post-port: `"
                 (:revision post) "`. Each depth is the maximum of the walker and semantic lane maxima. "
                 "Ratios are post-port / baseline, rounded to two decimals.\n\n"
                 "Overlap: " (count ids) "/" (count p) ".\n\n"
                 "| Workload | Baseline | Post-port | Ratio |\n|---|---:|---:|---:|\n"
                 (apply str (for [id ids]
                              (str "| " (name id) " | " (depth (b id)) " | " (depth (p id))
                                   " | " (format "%.2f×" (double (/ (depth (p id)) (depth (b id))))) " |\n")))
                 "\n" (if (seq flagged)
                        (str "exceeds-10x: " (str/join ", " (map name flagged)))
                        "none-exceeds-10x")
                 " / max-ratio: " (format "%.2f×" (double maximum)) "\n"))
      maximum)))


(deftest test-kernel-proof-fresh-supply
  (testing "explicit have types advance the supply; inferred types and prior contexts are preserved"
    (let [run (fresh-admission-run :proof-fresh-supply)]
      (doseq [[name next-name] [['h 'h2] ['_ '_]]
              [ty allocations] [['A 1] ['_ 0]]]
        (let [fixture
              {:call
               (walk/postwalk-replace
                 {'H name 'H2 next-name 'TY ty}
                 '(let [k0 env
                        [s1 [k1 ctx1 deps1 uses1]]
                        (latte-kernel.proof/elab-have k0 '[[A ✳] [x A]] [] {} 'H 'TY 'x {})
                        [s2 [k2 ctx2 deps2 uses2]]
                        (latte-kernel.proof/elab-have k1 ctx1 deps1 uses1 'H2 'TY 'x {})
                        [s3 [k3 ctx3 deps3 uses3]]
                        (latte-kernel.proof/elab-declare k2 ctx2 deps2 uses2 'z 'A {})
                        [s4 [k4 ctx4 deps4 uses4]]
                        (latte-kernel.proof/elab-have k3 ctx3 deps3 uses3 '_ 'A 'z {})]
                    [[s1 s2 s3 s4]
                     [(:fresh k0) (:fresh k1) (:fresh k2) (:fresh k3) (:fresh k4)]
                     (mapv (fn [k] (count (second (:definitions k)))) [k0 k1 k2 k3 k4])
                     (= ctx1 '[[A ✳] [x A]]) (= ctx1 ctx2)]))}
              expected [[:ok :ok :ok :ok] [0 allocations (* 2 allocations)
                                           (inc (* 2 allocations)) (+ 2 (* 2 allocations))]
                        (if (= name '_) [0 0 0 0 0] [0 1 2 2 2])
                        true true]
              compiled (kernel-compile-in-run run fixture)]
          (is (= {:value expected} (:outcome (kernel-b fixture))))
          (doseq [lane [:walker :semantic]]
            (let [c (kernel-c compiled lane)]
              (is (= [:yin.k/ok expected] (:outcome c)) (str name " " lane " " c))))))))
  (testing "the proof loop carries the returned supply to the next step and qed"
    (let [run (fresh-admission-run :proof-loop)]
      (doseq [[script fresh]
              [['((:have h A x {}) (:have h2 A x {}) (:qed x {})) 2]
               ['((:have _ A x {}) (:have _ A x {}) (:qed x {})) 2]
               ['((:declare z A {}) (:have h A z {}) (:declare w A {}) (:have h2 A w {}) (:qed w {})) 4]]]
        (let [fixture {:call (list 'let ['r (list 'latte-kernel.proof/elab-proof 'env ''[[A ✳] [x A]]
                                                  (list 'quote script))]
                                   '[(first r) (:fresh (first (second r))) (:fresh env) (:definitions env)])}
              expected [:ok fresh 0 [{} {}]]
              compiled (kernel-compile-in-run run fixture)]
          (is (= {:value expected} (:outcome (kernel-b fixture))))
          (doseq [lane [:walker :semantic]]
            (is (= [:yin.k/ok expected] (:outcome (kernel-c compiled lane))) (str lane)))))))
  (testing "check-proof keeps the rebuilt context under its own def-env' binding"
    ;; Observe the context passed to the final comparison: check-proof's public
    ;; result contains only the proof term and type, so it cannot expose :fresh.
    (with-redefs [kernel-sources
                  (assoc kernel-sources 'gate-norm-beta-eq?
                         '(defn gate-norm-beta-eq?
                            [def-env ctx t1 t2]
                            (= 1 (:fresh def-env))))]
      (let [run (fresh-admission-run :check-proof)
            fixture {:call '(latte-kernel.proof/check-proof env '[[A ✳] [x A]] 't 'A '[[:qed x {}]])}
            expected [:ok ['x 'A]]
            compiled (kernel-compile-in-run run fixture)]
        (is (= {:value expected} (:outcome (kernel-b fixture))))
        (doseq [lane [:walker :semantic]]
          (is (= [:yin.k/ok expected] (:outcome (kernel-c compiled lane))) (str lane)))))))


(def ^:private kernel-named-integrations
  ;; Only these named integrations may contribute observed transitive calls.
  ;; Static dependencies never establish execution of any arity.
  #{:unparser-guest-closure :implicit-typed-closure :implicit-throwing-closure
    :check-assume-have-discharge :parse-to-normalize-to-type
    :h3-2-elab-inferred-anonymous :h3-2-elab-discharge-states
    :h3-3-readable-term-2 :h3-3-readable-term-3 :h3-3-unregister-unparser
    :fundamental-install-use :unfold-install-use :opacity-install-use})


(def ^:private kernel-unexercised-reasons
  (edn/read-string (slurp (io/resource "yang/latte_oracle/slice9_unexercised.edn"))))


(defn- kernel-exercise-entries
  [inventory fixtures results]
  (vec
    (for [{:keys [symbol arities disposition]} inventory
          :when (= :admitted disposition)
          n arities
          :let [direct (vec (for [[fixture row] (map vector fixtures results)
                                  :when (and (= :pass (:status row))
                                             (kernel-executed-arity? row symbol n)
                                             (= symbol (first (:call fixture)))
                                             (= n (count (rest (:call fixture)))))]
                              (:id fixture)))
                integration (vec (for [row results
                                       :when (and (= :pass (:status row))
                                                  (contains? kernel-named-integrations (:id row))
                                                  (kernel-executed-arity? row symbol n))]
                                   (:id row)))]]
      {:function-arity [symbol n]
       :exercise (cond (seq direct) :direct
                       (seq integration) :named-integration
                       :else :unexercised)
       :direct-fixture-ids direct
       :integration-fixture-ids integration
       :missing-dependency (when (and (empty? direct) (empty? integration))
                             (get kernel-unexercised-reasons [symbol n]
                                  "No passing direct fixture or named integration records this exact arity in both C lanes."))})))


(def ^:private kernel-declarations
  "The declared dispositions: explicit, versioned input to classification.
   Generated evidence is never their source of truth; provenance is in-file.
   The file's contract is validated at load: version and provenance are
   fixed, and every historical disposition must retain its exact boundary
   and a complete record - the file is authoritative for this metadata, and
   a load-time failure means the file was edited beyond its contract. A
   declaration whose revalidation guards fail leaves its row a blocker,
   not a deviation."
  (let [parsed (edn/read-string (slurp (io/resource "yang/latte_oracle/slice9_declarations.edn")))
        complete? (fn [d required] (every? d required))
        portability-required [:call :script :message :boundary :upstream-value :reason]
        upstream-required [:call :boundary :reason :upstream-revision]]
    (assert (and (= 3 (:version parsed))
                 (= "004246b48ddac39e0ecd3631a16f4e74b05cab7a" (:provenance parsed)))
            "the declarations file's version/provenance are part of its contract")
    (doseq [[id d] (:portability parsed)]
      (assert (and (complete? d portability-required)
                   (= :nonportable-host-value (:boundary d))
                   (= 'clojure.core/meta (:upstream-value d)))
              (str "incomplete or out-of-scope portability declaration: " id)))
    (doseq [[id d] (:upstream-failures parsed)]
      (assert (and (complete? d upstream-required)
                   (= (:boundary d) ({:typing-ascription :upstream-unbound-definition
                                      :compile-malformed :upstream-malformed-proof-sequence} id))
                   (= "86fca0d1d054af005cb37395eae04500d45e6279" (:upstream-revision d)))
              (str "incomplete or out-of-scope upstream-failure declaration: " id)))
    parsed))


(defn- kernel-callable-descriptor?
  "True when `value` is the shape of the admitted common-call descriptor the
   reconciled compiler publishes for a guest closure: a true function-type
   marker, an entry that is a minted closure, and an admission witness.
   Structurally invalid shapes - a bare `{:type :closure}` map, a scalar or
   missing entry, a missing or non-closure witness - fail. Descriptor
   AUTHENTICITY (that the witness is this compiler's own mint, not a copy) is
   the compiler's admission check upstream of the outcome; this predicate is
   the test-side structural half, and the payload/message/call guards around
   it carry revalidation."
  [value]
  (and (map? value)
       (= :function (:clj/type value))
       (values/closure? (get value :yang.call/entry))
       (values/closure? (get value :clj/admission))))


(defn- kernel-host-value-deviation
  [fixture a b walker semantic]
  ;; Disposition only: keep every original comparison and all four outcomes.
  ;; A new failure outside this exact host-value boundary remains a mismatch.
  ;; The declared call, boundary, upstream value and reason come from the
  ;; versioned declarations file; the guards revalidate the live execution
  ;; against the declaration - the pinned call, the declared message and
  ;; script - so a changed execution cannot ride a copied declaration.
  (let [id (:id fixture)
        declared (get-in kernel-declarations [:portability id])]
    (when (and declared
               (contains? #{:elab-after-qed :check-after-qed} id)
               (= (:call fixture) (:call declared))
               (= :completed (:status a) (:status b) (:status walker) (:status semantic))
               (= (:outcome a) (:outcome b))
               (= (get-in a [:outcome :thrown :data :meta])
                  {:nonportable-host-value (str (class clojure.core/meta))})
               ;; The declaration must describe THIS failure: same message and
               ;; same script (top-level sequential shape normalized), or the
               ;; guard fails and the row stays a blocker.
               (= (:message declared) (get-in a [:outcome :thrown :message]))
               (= (vec (:script declared))
                  (vec (get-in a [:outcome :thrown :data :script])))
               (every? (fn [c]
                         (and (= :yin.k/error (first (:outcome c)))
                              (= (get-in a [:outcome :thrown :message])
                                 (get-in c [:outcome 1 :yin.k/message]))
                              (= (dissoc (get-in a [:outcome :thrown :data]) :meta)
                                 (dissoc (get-in c [:outcome 1 :yin.k/data]) :meta))
                              (= (result-shape (dissoc (get-in a [:outcome :thrown :data]) :meta))
                                 (result-shape (dissoc (get-in c [:outcome 1 :yin.k/data]) :meta)))
                              (let [meta (get-in c [:outcome 1 :yin.k/data :meta])]
                                ;; Both representations the pipeline actually
                                ;; produces: a pre-adoption raw closure, or the
                                ;; admitted §8.10 descriptor. Anything else -
                                ;; including an invented `{:type :closure}` map -
                                ;; fails.
                                (or (values/closure? meta)
                                    (kernel-callable-descriptor? meta)))))
                       [walker semantic]))
      {:boundary (:boundary declared)
       :upstream-value (:upstream-value declared)
       :evidence {:file "slice9_evidence.edn" :fixture-id id
                  :path [:a :outcome :thrown :data :meta]}
       :reason (:reason declared)})))


(defn- kernel-upstream-failure
  "Disposition for two pinned upstream failures. The declared boundary,
   reason and upstream revision come from the versioned declarations file;
   the guards pin the original call and every observed boundary. A new
   failure must still block the gate."
  [fixture a b walker semantic]
  (let [declared (get-in kernel-declarations [:upstream-failures (:id fixture)])
        completed-c? (= :completed (:status walker) (:status semantic))
        c (:outcome walker)
        same-c? (= c (:outcome semantic))
        unbound-message "Pinned upstream declaration has no definition"
        unbound-data {:refusal :upstream-unbound :binding 'latte-kernel.typing/type-of-ascribe}
        failure
        (when (and declared completed-c? same-c?)
          (case (:id fixture)
            :typing-ascription
            (when (and (= (:call fixture) (:call declared))
                       (= :blocked (:status a))
                       (= "class java.lang.IllegalStateException" (:exception a))
                       (= "Attempting to call unbound fn: #'latte-kernel.typing/type-of-ascribe" (:message a))
                       (= :completed (:status b))
                       (= {:thrown {:message unbound-message :data unbound-data}} (:outcome b))
                       (= [:yin.k/error {:yin.k/message unbound-message :yin.k/data unbound-data}] c))
              {:boundary (:boundary declared)
               :reason (:reason declared)})
            :compile-malformed
            (when (and (= (:call fixture) (:call declared))
                       (= :blocked (:status a) (:status b))
                       (= "class java.lang.IllegalArgumentException" (:exception a) (:exception b))
                       (= "Don't know how to create ISeq from: java.lang.Long" (:message a) (:message b))
                       (nil? (:data a)) (nil? (:data b))
                       (= [:yin.k/error {:yin.k/message "seq of a non-collection is not supported"
                                         :yin.k/data {:value 42}}] c))
              {:boundary (:boundary declared)
               :reason (:reason declared)})
            nil))]
    (when failure
      (assoc failure :upstream-revision (:upstream-revision declared)
             :evidence {:file "slice9_evidence.edn" :fixture-id (:id fixture)
                        :paths [[:a] [:b] [:c-walker] [:c-semantic]]}))))


(deftest test-kernel-acceptance-counting
  (let [inventory [{:symbol 'latte-kernel.syntax/subst :arities [2 3] :disposition :admitted}
                   {:symbol 'latte-kernel.syntax/term? :arities [1] :disposition :admitted}]
        fixture {:id :direct-subst :call '(latte-kernel.syntax/subst x y)}
        calls '[[gate-syntax-subst 2 1]]
        rows [{:id :direct-subst :status :pass
               :c-walker {:executed-calls calls} :c-semantic {:executed-calls calls}}
              {:id :check-assume-have-discharge :status :pass
               :included-functions ['gate-syntax-subst]
               :c-walker {:executed-calls calls} :c-semantic {:executed-calls calls}}]
        entries (kernel-exercise-entries inventory [fixture] rows)]
    (is (= [:direct :unexercised :unexercised] (mapv :exercise entries))
        "An admitted but uncalled arity stays uncredited despite static membership")
    (is (empty? (:integration-fixture-ids (second entries))))
    (is (= [:unexercised :unexercised :unexercised]
           (mapv :exercise (kernel-exercise-entries inventory [fixture]
                                                    (mapv #(assoc % :status :mismatch) rows)))))
    (let [observed (mapv #(-> %
                              (assoc-in [:c-walker :executed-calls] '[[gate-syntax-subst 2 1] [gate-syntax-subst 3 2]])
                              (assoc-in [:c-semantic :executed-calls] '[[gate-syntax-subst 2 1] [gate-syntax-subst 3 2]])) rows)]
      (is (= [:direct :named-integration :unexercised]
             (mapv :exercise (kernel-exercise-entries inventory [fixture] observed))))
      (is (= [:direct :named-integration :unexercised]
             (mapv :exercise (kernel-exercise-entries inventory [fixture]
                                                      (mapv #(dissoc % :included-functions) observed)))))
      (is (= [:direct :unexercised :unexercised]
             (mapv :exercise (kernel-exercise-entries inventory [fixture]
                                                      (assoc-in observed [1 :id] :unnamed-integration))))))
    (doseq [lane [:c-walker :c-semantic]]
      (is (= [:unexercised :unexercised :unexercised]
             (mapv :exercise (kernel-exercise-entries inventory [fixture]
                                                      (mapv #(dissoc % lane) rows))))
          "Both C lanes must demonstrate the exact arity")))
  (let [script (vector [:qed '(lambda [A :type] (lambda [x A] x)) {}] [:qed 'missing {}])
        declared-call (get-in kernel-declarations [:portability :elab-after-qed :call])
        fixture {:id :elab-after-qed :call declared-call}]
    (is (= (vec (:script (get-in kernel-declarations [:portability :elab-after-qed]))) (vec script))
        "The controls model the declared script exactly")
    (let [a {:status :completed
             :outcome {:thrown {:message "Wrong proof script: proof steps after qed."
                                :data {:script script
                                       :meta {:nonportable-host-value (str (class clojure.core/meta))}}}}}
          descriptor {:yang.call/entry (values/closure nil {})
                      :clj/type :function
                      :clj/admission (values/closure nil {})}
          c-with (fn [meta]
                   {:status :completed
                    :outcome [:yin.k/error {:yin.k/message "Wrong proof script: proof steps after qed."
                                            :yin.k/data {:script script :meta meta}}]})]
      ;; Both representations the reconciled pipeline actually produces.
      (is (some? (kernel-host-value-deviation fixture a a
                                              (c-with (values/closure nil {})) (c-with (values/closure nil {})))))
      (is (some? (kernel-host-value-deviation fixture a a (c-with descriptor) (c-with descriptor)))
          "The admitted §8.10 descriptor classifies as the declared deviation")
      (is (nil? (kernel-host-value-deviation (assoc fixture :id :other) a a (c-with descriptor) (c-with descriptor))))
      ;; The declared-call pin: a mutated call refuses.
      (is (nil? (kernel-host-value-deviation (assoc fixture :call '(latte-kernel.proof/elab-proof env [] (quote [[:qed :wrong]])))
                                             a a (c-with descriptor) (c-with descriptor)))
          "a mutated call fails the declared-call pin")
      ;; Per-lane incompleteness: each of the four executions must be complete.
      (is (nil? (kernel-host-value-deviation fixture (assoc a :status :blocked) a (c-with descriptor) (c-with descriptor))))
      (is (nil? (kernel-host-value-deviation fixture a (assoc a :status :blocked) (c-with descriptor) (c-with descriptor))))
      (is (nil? (kernel-host-value-deviation fixture a a (assoc (c-with descriptor) :status :blocked) (c-with descriptor))))
      (is (nil? (kernel-host-value-deviation fixture a a (c-with descriptor) (assoc (c-with descriptor) :status :blocked))))
      ;; Payload and shape mutations refuse.
      (is (nil? (kernel-host-value-deviation fixture a a
                                             (assoc-in (c-with descriptor) [:outcome 1 :yin.k/data :script] [:new-mismatch])
                                             (c-with descriptor))))
      (is (nil? (kernel-host-value-deviation fixture a a
                                             (assoc-in (c-with descriptor) [:outcome 1 :yin.k/data :script] '())
                                             (c-with descriptor))))
      ;; Shape-only mutation: identical contents delivered as a list in A
      ;; while the declaration (and C) keep the vector - the vec-normalized
      ;; declaration guard alone would accept it, and the refusal lands on
      ;; the A=B outcome equality (and, had B agreed, the raw shape
      ;; comparison) - both layers hold.
      (is (nil? (kernel-host-value-deviation fixture
                                             (assoc-in a [:outcome :thrown :data :script]
                                                       (apply list script))
                                             a
                                             (c-with descriptor)
                                             (c-with descriptor))))
      ;; Descriptor forgeries refuse.
      (is (nil? (kernel-host-value-deviation fixture a a
                                             (c-with (dissoc descriptor :clj/admission))
                                             (c-with (dissoc descriptor :clj/admission))))
          "a descriptor without its admission witness fails")
      (is (nil? (kernel-host-value-deviation fixture a a
                                             (c-with (assoc descriptor :yang.call/entry :not-a-closure))
                                             (c-with (assoc descriptor :yang.call/entry :not-a-closure))))
          "a descriptor whose entry is not a minted closure fails")
      (is (nil? (kernel-host-value-deviation fixture a a
                                             (c-with (assoc descriptor :clj/type :data))
                                             (c-with (assoc descriptor :clj/type :data))))
          "a descriptor with a wrong type marker fails")
      (is (nil? (kernel-host-value-deviation fixture a a (c-with {:type :closure}) (c-with {:type :closure}))))
      ;; A descriptor copied across calls: the structurally valid
      ;; check-after-qed descriptor submitted under the elab-after-qed
      ;; fixture refuses through the declared-call pin - the copy carries
      ;; another call's identity. (Two descriptors of the SAME call with
      ;; identical payloads are the same deviation claim; their witness
      ;; authenticity is the compiler's admission check.)
      (is (nil? (let [other (get-in kernel-declarations [:portability :check-after-qed])
                      other-descriptor (assoc descriptor :yang.call/entry (values/closure nil {})
                                              :clj/admission (values/closure nil {}))]
                  (kernel-host-value-deviation (assoc fixture :call (:call other))
                                               a a (c-with other-descriptor) (c-with other-descriptor))))
          "a descriptor copied across calls fails the declared-call pin")
      ;; A mutated declaration (declared message != live failure) fails the guard.
      (with-redefs [kernel-declarations (assoc-in kernel-declarations
                                                  [:portability :elab-after-qed :message]
                                                  "A different declared failure")]
        (is (nil? (kernel-host-value-deviation fixture a a (c-with descriptor) (c-with descriptor))))
        "carrying a declaration forward means revalidating it, not copying it"))))


(defn- kernel-row
  "One fixture row, admitted under an explicit coordinate: the
   admitting run composition supplies `run-scope` and the fixture's
   distinct `admission-ordinal` within that run (see
   `fresh-admission-run` / `kernel-row-in-run`). The retained
   admission origin is recorded on the row so caller-path tests can
   pin per-fixture distinctness within one run."
  [fixture run-scope admission-ordinal]
  (let [a (kernel-a fixture)
        b (kernel-b fixture)
        compiled (kernel-attempt #(kernel-compile fixture run-scope admission-ordinal))
        compiled (if (and (= :blocked (:status compiled)) (not (:blocked-by compiled)))
                   (assoc compiled :phase :compile
                          :blocked-by (if-let [n (get-in compiled [:data :name])] [n] [:compiler-exception])
                          :minimal-source (get kernel-sources (get-in compiled [:data :name]))) compiled)
        walker (kernel-c compiled :walker)
        semantic (kernel-c compiled :semantic)
        match? (and (= :completed (:status a) (:status b) (:status walker) (:status semantic))
                    (= (:outcome a) (:outcome b))
                    (agrees? (get a :outcome) (get walker :outcome))
                    (= (:outcome walker) (:outcome semantic))
                    (= (result-shape (:outcome a)) (result-shape (:outcome b)))
                    (if (contains? (:outcome a) :value)
                      (= (result-shape (:value (:outcome a)))
                         (result-shape (second (:outcome walker))))
                      (= (result-shape (get-in a [:outcome :thrown :data]))
                         (result-shape (get-in walker [:outcome 1 :yin.k/data])))))
        deviation (kernel-host-value-deviation fixture a b walker semantic)
        upstream-failure (kernel-upstream-failure fixture a b walker semantic)
        status (cond match? :pass
                     upstream-failure :recorded-upstream-failure
                     (some #(= :blocked (:status %)) [a b walker semantic]) :blocked
                     deviation :recorded-deviation
                     :else :mismatch)]
    {:id (:id fixture) :mode (:mode fixture) :negative? (boolean (:negative? fixture)) :status status
     :admission-origin (:admission-origin compiled)
     :admission-scope run-scope :admission-ordinal admission-ordinal
     :recorded-deviation deviation
     :recorded-upstream-failure upstream-failure
     :dependency-of (:dependency-of fixture)
     :call (or (:call fixture) (kernel-c-program fixture))
     :adapted-call (:guest-call fixture)
     :site-table (:site-table compiled)
     :site-table-hash (:site-table-hash compiled)
     :canonical-source-hash (:canonical-source-hash compiled)
     :agreement {:a-b (and (= :completed (:status a) (:status b)) (= (:outcome a) (:outcome b)))
                 :a-c-walker (and (= :completed (:status a) (:status walker))
                                  (agrees? (:outcome a) (:outcome walker)))
                 :a-c-semantic (and (= :completed (:status a) (:status semantic))
                                    (agrees? (:outcome a) (:outcome semantic)))
                 :c-lanes (and (= :completed (:status walker) (:status semantic))
                               (= (:outcome walker) (:outcome semantic)))}
     :a a :b b :c-walker walker :c-semantic semantic
     :included-functions (mapv second (kernel-closure (kernel-c-program fixture)))}))


(defn- kernel-row-in-run
  "The next fixture row of `run`: the run's scope with a distinct
   ordinal assigned by the run composition."
  [run fixture]
  (kernel-row fixture (:scope run) (admission-run-ordinal run)))


(deftest test-kernel-unused-arity-execution
  (let [fixture (first (filter #(= :parse-to-normalize-to-type (:id %)) (:fixtures kernel-gate-corpus)))
        fname 'gate-presyntax-parse
        source (get kernel-sources fname)
        ;; Preserve parse/2; poison the uncalled parse/1 body in memory.
        poisoned (list* 'defn fname
                        (map (fn [arity]
                               (if (= 1 (count (first arity)))
                                 (list (first arity) '(throw (ex-info "Uncalled parse/1 entered" {})))
                                 arity)) (drop 2 source)))
        row (with-redefs [kernel-sources (assoc kernel-sources fname poisoned)]
              (kernel-row-in-run (fresh-admission-run :kernel-unused-arity) fixture))
        inventory [{:symbol 'latte-kernel.presyntax/parse :arities [1 2] :disposition :admitted}]
        entries (kernel-exercise-entries inventory [fixture] [row])]
    (is (= :pass (:status row)))
    (is (some #{fname} (:included-functions row)) "Both arities remain in the static closure")
    (doseq [lane [:c-walker :c-semantic]]
      (is (some #(= [fname 2] (butlast %)) (get-in row [lane :executed-calls])))
      (is (not-any? #(= [fname 1] (butlast %)) (get-in row [lane :executed-calls]))))
    (is (= [:unexercised :named-integration] (mapv :exercise entries)))
    (is (empty? (:integration-fixture-ids (first entries))))))


(deftest test-h3-2-proof-elaboration-family
  (testing "registration: completions are named integrations, refusals are negatives"
    (is (contains? kernel-named-integrations :h3-2-elab-inferred-anonymous))
    (is (contains? kernel-named-integrations :h3-2-elab-discharge-states))
    (doseq [id [:h3-2-have-duplicate :h3-2-declare-invalid :h3-2-have-mismatched-type
                :h3-2-discharge-incorrect :h3-2-discharge-empty-context]]
      (is (:negative? (first (filter #(= id (:id %)) (:fixtures kernel-gate-corpus)))) id)))
  (testing "A-side F3 discharge states (upstream-asserted, conservative deps)"
    (let [empty-env @(upstream-fn 'latte-kernel.defenv/empty-env)
          declare (upstream-fn 'latte-kernel.proof/elab-declare)
          have (upstream-fn 'latte-kernel.proof/elab-have)
          discharge (upstream-fn 'latte-kernel.proof/elab-discharge)
          parsed (fn [env expr] (second ((upstream-fn 'latte-kernel.presyntax/parse-term) env expr)))
          fetched (fn [env dname]
                    (select-keys (second ((upstream-fn 'latte-kernel.defenv/fetch-definition) env dname true))
                                 [:name :params :arity :parsed-term :term-free-vars :type]))
          st (fn [f & args] (second (apply f args)))
          s1 (st declare empty-env [] [] {} 'A '✳ {})
          s2 (st declare empty-env (nth s1 1) (nth s1 2) {} 'B '✳ {})
          s3 (st declare empty-env (nth s2 1) (nth s2 2) {} 'f (parsed empty-env '(==> A B)) {})
          s4 (st declare empty-env (nth s3 1) (nth s3 2) {} 'x 'A {})
          sa (st have empty-env (nth s4 1) (nth s4 2) {} '<a> (parsed empty-env '(==> A B)) 'f {})
          sb (st have (nth sa 0) (nth sa 1) (nth sa 2) (nth sa 3)
                 '<b> 'B (parsed (nth sa 0) '(<a> x)) {})]
      (is (= '([x A] [f (Π [⇧ A] B)] [B ✳] [A ✳]) (nth s4 1)))
      ;; Conservative upstream update: every live entry gains the have name.
      (is (= '[[x #{<a> <b>}] [f #{<a> <b>}] [B #{<a> <b>}] [A #{<a> <b>}]] (nth sb 2)))
      (is (= '{<a> #{<b>} <b> #{}} (nth sb 3)))
      (let [s5 (discharge (nth sb 0) (nth sb 1) (nth sb 2) (nth sb 3) 'x {})]
        ;; <a> is abstracted over x although its term never mentions x.
        (is (= {:name '<a> :params '[[x A]] :arity 1 :parsed-term 'f
                :term-free-vars '#{f} :type '(Π [⇧ A] B)} (fetched (nth s5 0) '<a>)))
        (is (= '[(<a> x) x] (:parsed-term (fetched (nth s5 0) '<b>)))))))
  (testing "A-side F2: inferred type recorded, anonymous have registers nothing"
    (let [empty-env @(upstream-fn 'latte-kernel.defenv/empty-env)
          [_ [def-env term ty]] ((upstream-fn 'latte-kernel.proof/elab-proof)
                                 empty-env []
                                 '[[:declare A :type {}] [:declare x A {}]
                                   [:have <a> _ x {}] [:have _ A x {}] [:qed <a> {}]])]
      (is (= ['(<a>) 'A] [term ty]))
      (is (= '#{<a>} (set (keys (second def-env)))))))
  (testing "both C lanes record the predicted arities of the passing integrations"
    (let [run (fresh-admission-run :h3-2-proof-elaboration)
          by-id (fn [id] (first (filter #(= id (:id %)) (:fixtures kernel-gate-corpus))))
          f3 (kernel-row-in-run run (by-id :h3-2-elab-discharge-states))
          f2 (kernel-row-in-run run (by-id :h3-2-elab-inferred-anonymous))]
      (is (= :pass (:status f3)))
      (is (= :pass (:status f2)))
      (doseq [[sym n] '[[latte-kernel.proof/elab-declare 7] [latte-kernel.proof/elab-have 8]
                        [latte-kernel.proof/elab-have-impl 8] [latte-kernel.proof/elab-discharge 6]
                        [latte-kernel.proof/elab-discharge-impl 6] [latte-kernel.proof/abstract-local-def 4]
                        [latte-kernel.proof/abstract-local-calls 4] [latte-kernel.proof/gen-local-calls 3]
                        [latte-kernel.proof/update-var-deps 3] [latte-kernel.proof/update-def-uses 3]
                        [latte-kernel.proof/ref-uses-in-term 1]]]
        (is (kernel-executed-arity? f3 sym n) (str "F3 witnesses " sym "/" n)))
      (doseq [[sym n] '[[latte-kernel.proof/elab-declare 7] [latte-kernel.proof/elab-have 8]
                        [latte-kernel.proof/elab-have-impl 8] [latte-kernel.proof/update-var-deps 3]
                        [latte-kernel.proof/update-def-uses 3] [latte-kernel.proof/ref-uses-in-term 1]]]
        (is (kernel-executed-arity? f2 sym n) (str "F2 witnesses " sym "/" n)))
      ;; Discharge credit belongs to F1/F3 only: F2 has no discharge step.
      (is (not (kernel-executed-arity? f2 'latte-kernel.proof/elab-discharge 6))))))


(deftest test-h3-3-twenty-arity-family
  (testing "registration: private/side-effecting primaries are named integrations, refusals are negatives"
    (is (contains? kernel-named-integrations :h3-3-readable-term-2))
    (is (contains? kernel-named-integrations :h3-3-readable-term-3))
    (is (contains? kernel-named-integrations :h3-3-unregister-unparser))
    ;; F2 credits as a direct ambient fixture, never as a named integration.
    (is (not (contains? kernel-named-integrations :h3-3-registered-definition-ambient)))
    (doseq [id [:h3-3-axiom-definition :h3-3-axiom-map
                :h3-3-registered-definition-nonsymbol :h3-3-nbe-evaluation-missing
                :h3-3-nbe-instantiate-def-too-many :h3-3-redex-application
                :h3-3-redex-variable :h3-3-beta-reduction-nonredex
                :h3-3-norm-instantiate-def-too-many :h3-3-delta-step-missing
                :h3-3-parse-one-empty :h3-3-parse-one-reserved
                :h3-3-unregister-unparser-absent]]
      (is (:negative? (first (filter #(= id (:id %)) (:fixtures kernel-gate-corpus)))) id)))
  (testing "each credited primary passes and records its exact arity in both C lanes"
    (let [run (fresh-admission-run :h3-3-twenty-arity)
          by-id (fn [id] (first (filter #(= id (:id %)) (:fixtures kernel-gate-corpus))))]
      (doseq [[id sym n] [[:h3-3-axiom-true 'latte-kernel.defenv/axiom? 1]
                          [:h3-3-nbe-evaluation-one 'latte-kernel.nbe/evaluation 1]
                          [:h3-3-nbe-instantiate-def 'latte-kernel.nbe/instantiate-def 3]
                          [:h3-3-redex-true 'latte-kernel.norm/redex? 1]
                          [:h3-3-beta-reduction 'latte-kernel.norm/beta-reduction 1]
                          [:h3-3-beta-step-one 'latte-kernel.norm/beta-step 1]
                          [:h3-3-beta-step-two 'latte-kernel.norm/beta-step 2]
                          [:h3-3-beta-step-args 'latte-kernel.norm/beta-step-args 2]
                          [:h3-3-norm-instantiate-def 'latte-kernel.norm/instantiate-def 3]
                          [:h3-3-delta-step-four-local 'latte-kernel.norm/delta-step 4]
                          [:h3-3-delta-step-args 'latte-kernel.norm/delta-step-args 5]
                          [:h3-3-parse-one 'latte-kernel.presyntax/parse 1]
                          [:h3-3-binderify 'latte-kernel.syntax/binderify 3]
                          [:h3-3-mk-fresh-two 'latte-kernel.syntax/mk-fresh 2]
                          [:h3-3-mk-fresh-three 'latte-kernel.syntax/mk-fresh 3]
                          [:h3-3-readable-term 'latte-kernel.syntax/readable-term 1]
                          [:h3-3-readable-term-2 'latte-kernel.syntax/readable-term- 2]
                          [:h3-3-readable-term-3 'latte-kernel.syntax/readable-term- 3]
                          [:h3-3-unregister-unparser 'latte-kernel.unparser/unregister-unparser! 1]]
              :let [row (kernel-row-in-run run (by-id id))]]
        (is (= :pass (:status row)) (str id " passes"))
        (is (kernel-executed-arity? row sym n) (str id " witnesses " sym "/" n)))))
  (testing "F2's ambient /1 credits through the ruled query; negative credit follows the accepted rule"
    (let [run (fresh-admission-run :h3-3-ambient-credit)
          by-id (fn [id] (first (filter #(= id (:id %)) (:fixtures kernel-gate-corpus))))
          f2 (kernel-row-in-run run (by-id :h3-3-registered-definition-ambient))
          r2 (kernel-row-in-run run (by-id :h3-3-registered-definition-nonsymbol))
          r10 (kernel-row-in-run run (by-id :h3-3-unregister-unparser-absent))
          ambient-site (fn [row]
                         (some (fn [[k s]]
                                 (when (and (= 'registered-definition? (:callee s))
                                            (= :ambient-literal (first (:arg-shapes s))))
                                   k))
                               (:site-table row)))]
      (is (= :pass (:status f2)))
      ;; R5's re-credit on the record: the pre-ruling pin ("no mapping turns
      ;; F2's bare traces into upstream /1 credit") pinned the superseded
      ;; callee-side rule; the ambient-shape execution now credits /1.
      (is (kernel-executed-arity? f2 'latte-kernel.defenv/registered-definition? 1)
          "F2's ambient-shape execution credits registered-definition?/1")
      (doseq [lane [:c-walker :c-semantic]]
        (is (contains? (set (get-in f2 [lane :executed-calls]))
                       ['registered-definition? 3 (ambient-site f2)])
            (str "F2 traces the ambient-tagged context entry at its stamped site: " lane)))
      (is (= :pass (:status r2)))
      ;; D1(a) accepted for R2 too: the negative's ambient /1 shape still
      ;; enters the adapted body before its refusal.
      (is (kernel-executed-arity? r2 'latte-kernel.defenv/registered-definition? 1)
          "R2's ambient /1 call credits the same arity")
      ;; D1(a) accepted: a passing negative traces its head and credits directly.
      (is (= :pass (:status r10)))
      (is (kernel-executed-arity? r10 'latte-kernel.unparser/unregister-unparser! 1)
          "R10's refusal still enters unregister-unparser! at the credited arity"))))


(deftest test-attribution-battery
  "The ruling's seven-case refusal battery plus the stale-table
   diagnostic (R2b), each case a real executed check of the ruled
   attribution over caller-site-tagged traces."
  (let [run (fresh-admission-run :attribution-battery)
        by-id (fn [id] (first (filter #(= id (:id %)) (:fixtures kernel-gate-corpus))))
        f2 (kernel-row-in-run run (by-id :h3-3-registered-definition-ambient))
        explicit-2 (kernel-row-in-run run {:id :battery-explicit-2
                                           :call '(latte-kernel.defenv/registered-definition?
                                                    env (quote gate-no-such-definition))})
        unexecuted-1 (kernel-row-in-run run {:id :battery-unexecuted-ambient
                                             :call '(if false
                                                      (latte-kernel.defenv/registered-definition?
                                                        (quote gate-no-such-definition))
                                                      (latte-kernel.defenv/registered-definition?
                                                        env (quote gate-no-such-definition)))})]
    (testing "case 1: an explicit /2 call supplying an empty environment does not credit /1"
      (is (= :pass (:status explicit-2)))
      ;; the supplied context VALUE is the empty environment — value-identical
      ;; to the ambient initialization; the shapes are disjoint
      (is (kernel-executed-arity? explicit-2 'latte-kernel.defenv/registered-definition? 2)
          "the explicit context shape credits /2")
      (is (not (kernel-executed-arity? explicit-2 'latte-kernel.defenv/registered-definition? 1))
          "no value equality borrows /1 credit"))
    (testing "case 2: an unexecuted ambient /1 beside an executed explicit /2 borrows nothing"
      (is (= :pass (:status unexecuted-1)))
      (is (kernel-executed-arity? unexecuted-1 'latte-kernel.defenv/registered-definition? 2)
          "the executed explicit call credits /2")
      (is (not (kernel-executed-arity? unexecuted-1 'latte-kernel.defenv/registered-definition? 1))
          "the ambient site exists in the table but was never entered"))
    (testing "case 3: mismatched program/adaptation evidence refuses"
      (let [r2 (kernel-row-in-run run (by-id :h3-3-registered-definition-nonsymbol))
            swapped (assoc f2 :canonical-source-hash (:canonical-source-hash r2))]
        (is (not (kernel-executed-arity? swapped 'latte-kernel.defenv/registered-definition? 1))
            "a trace read against another fixture's canonical-source-hash refuses")))
    (testing "case 4: missing-lane evidence refuses"
      (let [one-lane (assoc-in f2 [:c-semantic :executed-calls] [])]
        (is (kernel-executed-arity? f2 'latte-kernel.defenv/registered-definition? 1))
        (is (not (kernel-executed-arity? one-lane 'latte-kernel.defenv/registered-definition? 1))
            "both C lanes must record the entry")))
    (testing "case 5: a site declared ambient whose compiled argument is not the ambient literal refuses"
      (let [table (:site-table f2)
            ambient (some (fn [[k s]]
                            (when (and (= 'registered-definition? (:callee s))
                                       (= :ambient-literal (first (:arg-shapes s))))
                              k))
                          table)
            ;; the explicit fixture's adapted /2 threads the caller's
            ;; context argument as a parameter expression — statically
            ;; NOT the ambient literal, whatever value it holds
            variable-site (some (fn [[k s]]
                                  (when (and (= 'registered-definition? (:callee s))
                                             (seq (:arg-shapes s))
                                             (not= :ambient-literal (first (:arg-shapes s))))
                                    k))
                                (:site-table explicit-2))]
        (is (some? ambient) "the fixture really derives an ambient site")
        (is (kernel-ambient-site? table ambient))
        (is (some? variable-site) "the fixture really derives parameter-shaped sites")
        (is (not (kernel-ambient-site? (:site-table explicit-2) variable-site))
            "the compile-time verification refuses a non-literal argument")))
    (testing "case 6: indirect-origin body entries refuse"
      (let [indirect (kernel-row-in-run run (by-id :nbe-beta))
            table (:site-table indirect)
            dynamic (some (fn [[k s]]
                            (when (and (= :dynamic (:callee s))
                                       (every? (fn [lane]
                                                 (some #(= k (nth % 2 nil))
                                                       (get-in indirect [lane :executed-calls])))
                                               [:c-walker :c-semantic]))
                              k))
                          table)
            none-site ['gate-defenv-registered-definition? 1 :gate-site/none]]
        (is (= :pass (:status indirect)))
        (is (some? dynamic) "both lanes execute an indirect call in this fixture")
        (is (not (kernel-record-credits? none-site table
                                         'latte-kernel.defenv/registered-definition? 1))
            "an entry without a stamped caller site never credits")
        (is (not (kernel-record-credits? ['registered-definition? 3 dynamic] table
                                         'latte-kernel.defenv/registered-definition? 1))
            "an entry through an indirect site refuses unless site-linked")))
    (testing "case 7: a variable context argument is /2-only, never ambient"
      (let [table (:site-table explicit-2)
            variable-site (some (fn [[k s]]
                                  (when (and (= 'registered-definition? (:callee s))
                                             (seq (:arg-shapes s))
                                             (not= :ambient-literal (first (:arg-shapes s))))
                                    k))
                                table)]
        (is (some? variable-site)
            "the explicit fixture really derives a variable context argument")
        (is (not (kernel-ambient-site? table variable-site)))
        (is (not (kernel-record-credits? ['registered-definition? 3 variable-site] table
                                         'latte-kernel.defenv/registered-definition? 1))
            "the statically unclassifiable shape defaults to refusal")))
    (testing "case 8: a stale table is a recorded diagnostic, not silent non-credit"
      (let [table (:site-table f2)
            stale-table (-> table
                            (dissoc 5)
                            (assoc 999 {:site 999 :callee 'registered-definition?
                                        :arg-shapes [:ambient-literal] :def nil
                                        :delegation? false}))
            stale (kernel-stale-sites stale-table {:c-walker (:c-walker f2)
                                                   :c-semantic (:c-semantic f2)})]
        (is (contains? (set stale) 999)
            "the mistyped site id is reported, never silently ignored")
        (is (vector? stale) "the diagnostic is recorded data")))))


(defn kernel-gate-evidence
  "Run every fixture in A and independently in both C lanes. Compilation or
   VM errors are blockers, never successful LaTTe rejections. Exact arities
   require recorded body entry in both C lanes of a passing fixture."
  []
  ;; The evidence run is the run composition: it allocates its scope
  ;; ONCE here and threads it through every fixture row, each taking
  ;; a distinct ordinal within this run.
  (let [run (fresh-admission-run :kernel-gate-evidence)
        results (mapv (fn [fixture]
                        (let [row (kernel-row-in-run run fixture)]
                          (println "KERNEL-FIXTURE" (:id row) (:status row))
                          row)) (:fixtures kernel-gate-corpus))
        inventory (mapv #(select-keys % [:symbol :kind :arities :disposition :admission-reason]) kernel-entries)
        exercise-entries (kernel-exercise-entries inventory (:fixtures kernel-gate-corpus) results)
        direct (set (map :function-arity (filter #(= :direct (:exercise %)) exercise-entries)))
        exercised (set (map :function-arity (remove #(= :unexercised (:exercise %)) exercise-entries)))
        all-arities (set (for [{:keys [symbol arities]} inventory n arities] [symbol n]))
        admitted-arities (set (for [{:keys [symbol arities disposition]} inventory
                                    :when (= :admitted disposition) n arities] [symbol n]))
        unexercised (vec (sort-by pr-str (remove exercised admitted-arities)))]
    {:slice 9 :gate-status (if (and (every? #(contains? #{:pass :recorded-deviation :recorded-upstream-failure} (:status %)) results)
                                    (empty? unexercised)) :pass :blocked)
     :baseline {:revision "7dc18478" :fixture-count 54 :counts {:pass 4 :mismatch 2 :blocked 48}}
     :generated-at (str (java.time.Instant/now))
     :adaptation-version {:context 2 :kernel-assembly 4}
     :executions {:a :original-upstream-clojure :b :context-source-clojure
                  :c [:walker :semantic]}
     :upstream-revision "86fca0d1d054af005cb37395eae04500d45e6279"
     :compiler-vm-revision {:base "7dc18478" :working-tree true}
     :compiler-source-sha256
     (apply str (map #(format "%02x" (bit-and 255 %))
                     (.digest (java.security.MessageDigest/getInstance "SHA-256")
                              (.getBytes (slurp "src/cljc/yang/clojure.cljc") "UTF-8"))))
     :integration-context-identities kernel-context-integration-names
     :portability-refusals
     [{:source '(.getStackTrace exc) :disposition :excluded
       :reason "Host stack traces have no portable error-data representation; the compiler refuses the accessor."}]
     :representation-seams
     {'kind/var? "JVM Clojure Var predicate; explicitly declared, with no namespace lookup."
      'kind/with-meta "Pure metadata attachment on the existing host value representation; the data ABI has no metadata-write export."}
     :context-fields {'latte-kernel.proof/+opacity-fn+ :opacity-policy}
     :fresh-supply-convention
     {:input {:counter 0} :allocation '(gensym supply prefix)
      :result '[symbol next-supply]
      :rebuild-type-result '[original-result next-context]
      :ambient-prefix-only :refused}
     :runtime {:clojure (clojure-version) :java (System/getProperty "java.version")}
     :instruction-budget-per-lane 4000000
     :instruction-budget-note "The original uninstrumented fixtures used 1,000,000 instructions. Body-entry stream writes add guest instructions; traced fixtures have a 2,000,000 instruction bound. The harness drains only after halt. Under the D2 record lane the binding fixture's walker cell needs 2,024,912 steps (1.2% over 2M; semantic 1,105,792); the architect's 2026-11-04 ruling approves a uniform 4,000,000 acceptance budget (~1.98x headroom on the binding lane, measurement-supported, no per-fixture table). The attribution round's caller-site stamping adds one dispatcher call and one cell write per stamped guest call; every fixture in this evidence completed under the same 4,000,000 bound with the stamping active, and the trace stream bound stays stream/make 1000000 (one added field per body-entry record, no added records)."
     :manifest-bindings (count manifest) :function-arity-candidates (count all-arities)
     :manifest-functions (count (filter :arities kernel-entries))
     :admitted-function-arity-count (count admitted-arities)
     :excluded-function-arity-count (- (count all-arities) (count admitted-arities))
     :binding-dispositions (frequencies (map :disposition manifest))
     :admission-note "Manifest dispositions define the denominator independently of fixture success; excluded bindings retain explicit portability reasons."
     :program-status (if (empty? unexercised) :fixture-closure-complete :execution-evidence-incomplete)
     :residual-closure kernel-fixture-closure
     :coverage {:proven-direct-function-arities (vec (sort-by pr-str direct))
                :exercised-count (count exercised) :unexercised unexercised
                :entries exercise-entries
                :named-integration-fixture-ids (vec (sort kernel-named-integrations))
                :transitive-execution-coverage :recorded-function-arity-body-entry
                :attribution-at-rule kernel-ambient-correspondence
                :trace {:record-shape '[guest-function arity caller-site]
                        :transport :explicit-guest-stream :lanes [:c-walker :c-semantic]
                        :point :selected-arity-body-entry
                        :site-tagging :caller-side-compiled-ast-application
                        :refusal-site :gate-site/none}
                :basis "Passing direct calls or named integrations must record the exact function/arity executing in both C lanes. Static closure membership never counts; full branch coverage is not required."
                :inventory inventory}
     :recorded-portability-boundaries
     (mapv #(select-keys % [:id :status :recorded-deviation])
           (filter #(= :recorded-deviation (:status %)) results))
     :recorded-upstream-failures
     (mapv #(select-keys % [:id :status :recorded-upstream-failure])
           (filter #(= :recorded-upstream-failure (:status %)) results))
     :attribution
     {:version 1
      :rule "caller-side site ids at compiled-AST application expressions (post-macroexpansion); records [guest-function arity caller-site]; ambient arities credit only through adapter-declared ambient-literal sites; delegations and the :gate-site/none entry site never credit"
      :ambient-correspondence kernel-ambient-correspondence
      :site-tables-file "slice9_site_tables.edn"
      :stale-site-diagnostics
      (into (sorted-map)
            (for [row results
                  :let [stale (kernel-stale-sites (:site-table row)
                                                  {:c-walker (:c-walker row)
                                                   :c-semantic (:c-semantic row)})]
                  :when (and (seq stale) (= :pass (:status row)))]
              [(:id row) stale]))}
     :counts (frequencies (map :status results)) :fixture-count (count results)
     :negative-fixture-count (count (filter :negative? results))
     :dependency-graph
     (into (sorted-map)
           (for [[fname source] kernel-sources]
             [fname (vec (sort (filter (set (keys kernel-sources)) (walk-symbols source))))]))
     ;; the derived site tables stay on the rows (the query re-derives
     ;; credit from them); the evidence writer omits them from the
     ;; inline rows and lands them in the versioned companion file
     :fixtures results}))


(defn write-kernel-gate-evidence!
  "Explicit evidence/coverage refresh; normal tests never update these files.
   With `dir`, writes into that directory (the round-trip test's temp dir);
   without it, the checked-in locations."
  ([] (write-kernel-gate-evidence! "test/yang/latte_oracle"))
  ([dir]
   (let [evidence (kernel-gate-evidence)]
     (spit (str dir "/slice9_unexercised.edn")
           (str ";; GENERATED from exact execution evidence in both C lanes.\n"
                (pr-str (into (sorted-map-by #(compare (pr-str %1) (pr-str %2)))
                              (for [entry (get-in evidence [:coverage :entries])
                                    :when (= :unexercised (:exercise entry))]
                                [(:function-arity entry) (:missing-dependency entry)]))) "\n"))
     ;; The versioned site tables (R1b/R7): one derived table and one
     ;; canonical-source-hash per fixture, stable across refreshes so the
     ;; later durable correspondence record lands additively.
     (spit (str dir "/slice9_site_tables.edn")
           (str ";; GENERATED from the compiled AST of each fixture's C program.\n"
                (pr-str {:version 1
                         :fixtures
                         (into (sorted-map)
                               (for [row (:fixtures evidence)]
                                 [(:id row)
                                  {:canonical-source-hash (:canonical-source-hash row)
                                   :site-table (:site-table row)}]))})
                "\n"))
     (with-open [w (io/writer (str dir "/slice9_evidence.edn"))]
       ;; prn keeps (quote ...) in valid EDN rather than pprint's reader
       ;; shorthand. One fixture per line keeps refreshes independently diffable.
       (binding [*out* w *print-length* nil *print-level* nil]
         (print "{")
         (doseq [[k v] (dissoc evidence :fixtures)] (pr k) (print " ") (prn v))
         (println ":fixtures [")
         (doseq [row (:fixtures evidence)]
           ;; the per-run admission coordinate (fresh scope per run) is
           ;; retained on the in-memory row for caller-path pins, not
           ;; landed in the versioned evidence file
           (prn (dissoc row :site-table :admission-origin
                        :admission-scope :admission-ordinal)))
         (println "]}")))
     (select-keys evidence [:gate-status :counts :fixture-count :manifest-bindings :function-arity-candidates :coverage :attribution]))))


(deftest test-kernel-differential-gate
  (let [evidence (kernel-gate-evidence)]
    (is (= (count (:fixtures kernel-gate-corpus)) (count (set (map :id (:fixtures evidence)))))
        "Every named fixture runs exactly once, including negatives")
    (doseq [row (:fixtures evidence)
            :when (and (:negative? row) (= :completed (get-in row [:a :status])))
            :let [outcome (get-in row [:a :outcome]) value (:value outcome)
                  fixture (first (filter #(= (:id row) (:id %)) (:fixtures kernel-gate-corpus)))]]
      (is (or (:thrown outcome) (false? value) (and (sequential? value) (= :ko (first value)))
              (and (:refusal-path fixture) (false? (get-in outcome (:refusal-path fixture)))))
          (str "A must actually reject the named negative: " (:id row))))
    (is (= (:function-arity-candidates evidence)
           (+ (:admitted-function-arity-count evidence) (:excluded-function-arity-count evidence)))
        "Admitted and excluded arities partition the candidate inventory")
    (is (every? #(every? true? (vals (:agreement %)))
                (filter #(= :pass (:status %)) (:fixtures evidence)))
        "Every passing fixture records independent A/B/C agreement")
    (let [entries (get-in evidence [:coverage :entries])
          unexercised (filter #(= :unexercised (:exercise %)) entries)
          deviations (filter #(= :recorded-deviation (:status %)) (:fixtures evidence))]
      (is (= (:admitted-function-arity-count evidence) (count entries)
             (count (set (map :function-arity entries)))))
      (is (= (set (get-in evidence [:coverage :unexercised]))
             (set (map :function-arity unexercised))
             (set (keys kernel-unexercised-reasons))))
      (is (every? #(seq (:missing-dependency %)) unexercised))
      (is (every? #(case (:exercise %)
                     :direct (seq (:direct-fixture-ids %))
                     :named-integration (seq (:integration-fixture-ids %))
                     :unexercised (and (empty? (:direct-fixture-ids %))
                                       (empty? (:integration-fixture-ids %)))) entries))
      (doseq [{[symbol n] :function-arity :keys [direct-fixture-ids integration-fixture-ids]} entries
              id (concat direct-fixture-ids integration-fixture-ids)
              :let [row (first (filter #(= id (:id %)) (:fixtures evidence)))]]
        (is (= :pass (:status row)))
        (is (kernel-executed-arity? row symbol n)
            (str "Every credited path re-derives from both execution traces: " [symbol n id])))
      (is (= #{:elab-after-qed :check-after-qed} (set (map :id deviations))))
      (is (every? #(and (:recorded-deviation %)
                        (not (every? true? (vals (:agreement %))))) deviations)))
    (testing "Previously declared residual consumers must still prove their exact arities"
      (is (= 20 (count kernel-fixture-closure)))
      (doseq [[arity {:keys [fixture expected] :as closure}] kernel-fixture-closure
              :let [row (first (filter #(= fixture (:id %)) (:fixtures evidence)))
                    entry (first (filter #(= arity (:function-arity %)) (get-in evidence [:coverage :entries])))]]
        (is (= :pass (:status row)) (str arity " via " fixture))
        (is (some #{fixture} (concat (:direct-fixture-ids entry) (:integration-fixture-ids entry)))
            (str "Primary consumer must contribute to arity evidence: " arity))
        (is (contains? closure :expected))
        (is (= {:value expected} (get-in row [:a :outcome])) (str fixture " has the intended result"))))
    (is (= #{:typing-ascription :compile-malformed}
           (set (map :id (:recorded-upstream-failures evidence)))))
    (doseq [row (:fixtures evidence)
            :when (= :recorded-upstream-failure (:status row))
            :let [fixture (first (filter #(= (:id row) (:id %)) (:fixtures kernel-gate-corpus)))]]
      (is (:recorded-upstream-failure row))
      (is (not (every? true? (vals (:agreement row)))))
      (is (nil? (kernel-upstream-failure (assoc fixture :id :unexpected)
                                         (:a row) (:b row) (:c-walker row) (:c-semantic row))))
      (is (nil? (kernel-upstream-failure (assoc fixture :call '(changed-input))
                                         (:a row) (:b row) (:c-walker row) (:c-semantic row))))
      (is (nil? (kernel-upstream-failure fixture (:a row) (:b row)
                                         (assoc-in (:c-walker row) [:outcome 1 :yin.k/data] {:changed true})
                                         (:c-semantic row)))))
    (println "KERNEL-GATE" (pr-str (select-keys evidence [:gate-status :counts :fixture-count :manifest-bindings :function-arity-candidates
                                                          :recorded-portability-boundaries :recorded-upstream-failures])))
    (doseq [row (:fixtures evidence) :when (not= :pass (:status row))]
      (println (case (:status row)
                 :recorded-deviation "KERNEL-RECORDED-BOUNDARY"
                 :recorded-upstream-failure "KERNEL-RECORDED-UPSTREAM-FAILURE"
                 "KERNEL-BLOCKER")
               (pr-str {:id (:id row) :status (:status row)
                        :blocked-by (get-in row [:c-walker :blocked-by])
                        :a (get-in row [:a :status]) :b (get-in row [:b :status])
                        :c-walker (get-in row [:c-walker :status])
                        :c-semantic (get-in row [:c-semantic :status])})))
    (is (= :pass (:gate-status evidence))
        (str "Kernel gate remains blocked: " (:counts evidence)
             "; admitted/candidate but unexercised function/arities=" (count (get-in evidence [:coverage :unexercised]))
             ". See slice9_evidence.edn; blocked fixtures are not accepted negatives."))))


(deftest test-kernel-gate-evidence-writer-round-trip
  "Rewriting the checked-in evidence through the writer into a temporary
   directory reproduces it exactly: the statuses (including the declared
   deviations), the raw comparisons and the coverage survive. No fixtures
   re-run; the checked-in file is the fixture."
  (let [dir (str (System/getProperty "java.io.tmpdir") "/slice9-roundtrip-" (System/currentTimeMillis))
        committed (edn/read-string (slurp "test/yang/latte_oracle/slice9_evidence.edn"))]
    (try
      (.mkdirs (java.io.File. dir))
      (with-redefs [kernel-gate-evidence (constantly committed)]
        (write-kernel-gate-evidence! dir))
      (let [written (edn/read-string (slurp (str dir "/slice9_evidence.edn")))]
        (is (= committed written) "the writer's output re-reads as the same evidence")
        (is (= #{:elab-after-qed :check-after-qed}
               (set (map :id (filter #(= :recorded-deviation (:status %)) (:fixtures written)))))
            "the declared departures survive as recorded deviations")
        (is (every? (fn [row]
                      (and (contains? row :a) (contains? row :b)
                           (contains? row :c-walker) (contains? row :c-semantic)))
                    (:fixtures written))
            "every fixture row keeps its raw A/B/C comparisons")
        (let [unexercised (edn/read-string (slurp (str dir "/slice9_unexercised.edn")))]
          (is (= (count (get-in written [:coverage :unexercised])) (count unexercised))
              "the companion file lists every unexercised arity with its dependency"))
        (let [tables (edn/read-string (slurp (str dir "/slice9_site_tables.edn")))]
          (is (= 1 (:version tables)) "the site tables land in the versioned format")
          (is (= (count (:fixtures written)) (count (:fixtures tables)))
              "every fixture row carries its derived site table and canonical-source-hash")))
      (finally
        (run! #(let [f (java.io.File. (str dir "/" %))] (when (.exists f) (.delete f)))
              ["slice9_evidence.edn" "slice9_unexercised.edn" "slice9_site_tables.edn"])
        (let [d (java.io.File. dir)] (when (.exists d) (.delete d)))))))


;; =============================================================================
;; cc3: oracle integer sites bound to post-expansion C1 occurrence keys
;; =============================================================================

(defn- cc3-walk
  "[[row-path node] ...] over a map AST, paths in the row grammar's
   positions (the same convention as `vm/occurrences`)."
  [node path acc]
  (if (and (map? node) (:type node))
    (reduce (fn [acc [j [field kind]]]
              (let [pos (+ j 2)
                    value (get node field)]
                (case kind
                  :node (if (map? value)
                          (cc3-walk value (conj path pos) acc)
                          acc)
                  :nodes (reduce (fn [a [i child]]
                                   (cc3-walk child (conj path [pos i]) a))
                                 acc
                                 (map-indexed vector (or value [])))
                  acc)))
            (conj acc [path node])
            (map-indexed vector
                         (get vm/semantic-bytecode-grammar (:type node))))
    acc))


(defn- cc3-assoc-at
  "Replace the node at grammar `path` in `node` with `(f node-at-path)`."
  [node path f]
  (if (empty? path)
    (f node)
    (let [step (first path)
          grammar (get vm/semantic-bytecode-grammar (:type node))
          field-at (fn [pos] (first (nth grammar (- pos 2))))]
      (if (vector? step)
        (let [field (field-at (first step))
              i (second step)]
          (update node field
                  (fn [children]
                    (assoc (vec children) i
                           (cc3-assoc-at (nth children i)
                                         (rest path) f)))))
        (let [field (field-at step)]
          (update node field
                  (fn [child]
                    (cc3-assoc-at child (rest path) f))))))))


(defn- cc3-stamp-site-id
  "The integer site a stamping wrapper carries, or nil."
  [wrapper]
  (when (kernel-stamped-args? wrapper)
    (let [k (get-in wrapper [:operands 1 :operands 1 :value])]
      (when (integer? k) k))))


(defn- cc3-correspond
  "The explicit stamped→pre-stamp path correspondence (cc3 r2 item 1):
   `{stamped-path pre-stamp-path}` over the grammar positions of both
   trees, built by traversing them together and removing the
   recognized stamping wrappers (`kernel-stamped-args?` — the two
   applications a stamp inserts around an argument node). A stamped
   site inside a stamped argument therefore maps to the path it held
   before stamping, never to the unrelated application its stamped
   path would name in the pre-stamp tree. A type mismatch, a child
   count mismatch, or a wrapper where the pre-stamp tree already
   carries one refuses with `:rule :cc3-stamp-correspondence` —
   mismatched or ambiguous correspondence is never guessed."
  [pre stamped pre-path stamped-path acc]
  (cond
    (kernel-stamped-args? stamped)
    (do (when (kernel-stamped-args? pre)
          (throw (ex-info "Stamped wrapper over a pre-stamp wrapper is ambiguous"
                          {:rule :cc3-stamp-correspondence
                           :pre-stamp-path pre-path
                           :stamped-path stamped-path})))
        ;; the wrapped argument node sits at operands[1]/operands[2] of
        ;; the wrapper — application operands are grammar position 3
        (cc3-correspond pre (kernel-stamped-args? stamped)
                        pre-path (conj stamped-path [3 1] [3 2])
                        acc))

    (and (map? pre) (:type pre) (map? stamped) (:type stamped))
    (do (when (not= (:type pre) (:type stamped))
          (throw (ex-info "Stamped tree diverges from the compiled tree"
                          {:rule :cc3-stamp-correspondence
                           :pre-stamp-path pre-path
                           :stamped-path stamped-path
                           :pre-type (:type pre)
                           :stamped-type (:type stamped)})))
        (reduce (fn [acc [j [field kind]]]
                  (let [pos (+ j 2)
                        pv (get pre field)
                        sv (get stamped field)]
                    (case kind
                      :node (if (or (map? pv) (map? sv))
                              (cc3-correspond pv sv
                                              (conj pre-path pos)
                                              (conj stamped-path pos)
                                              acc)
                              acc)
                      :nodes (do (when (not= (count (or pv []))
                                             (count (or sv [])))
                                   (throw (ex-info
                                            "Stamped tree diverges from the compiled tree"
                                            {:rule :cc3-stamp-correspondence
                                             :pre-stamp-path pre-path
                                             :stamped-path stamped-path})))
                                 (reduce (fn [a i]
                                           (cc3-correspond (nth pv i)
                                                           (nth sv i)
                                                           (conj pre-path [pos i])
                                                           (conj stamped-path [pos i])
                                                           a))
                                         acc
                                         (range (count (or pv [])))))
                      ;; scalar grammar data (:data/:sym/:syms/:bool/
                      ;; :kw/:key/:str/:int) must be unchanged at the
                      ;; translated position — a same-shape mutation
                      ;; (callee name, literal value, lambda params)
                      ;; is no correspondence (r3 F1)
                      (if (= pv sv)
                        acc
                        (throw (ex-info
                                 "Stamped tree diverges from the compiled tree"
                                 {:rule :cc3-stamp-correspondence
                                  :pre-stamp-path pre-path
                                  :stamped-path stamped-path
                                  :field field
                                  :pre-value pv
                                  :stamped-value sv}))))))
                (assoc acc stamped-path pre-path)
                (map-indexed vector
                             (get vm/semantic-bytecode-grammar
                                  (:type pre)))))

    (or (map? pre) (map? stamped))
    (throw (ex-info "Stamped tree diverges from the compiled tree"
                    {:rule :cc3-stamp-correspondence
                     :pre-stamp-path pre-path
                     :stamped-path stamped-path}))

    :else (if (= pre stamped)
            acc
            (throw (ex-info "Stamped tree diverges from the compiled tree"
                            {:rule :cc3-stamp-correspondence
                             :pre-stamp-path pre-path
                             :stamped-path stamped-path
                             :pre-value pre
                             :stamped-value stamped})))))


(defn- cc3-oracle-binding
  "Bind each stamped oracle site id to its post-expansion C1 key
   K = [origin root path] in the COMPILED (pre-stamp) tree.

   Chronology (mob condition 2): `kernel-compile` compiles FIRST and
   stamps by insertion afterward, so the stamped tree is a NEW tree.
   Stamped paths are NOT pre-stamp paths (cc3 r2 item 1): the binding
   translates each stamped site path through the explicit
   `cc3-correspond` transformation mapping before combining it with
   the pre-stamp root, and refuses a site whose mapped key is no
   validated C1 occurrence. The origin is the admission coordinate
   `kernel-compile` recorded WHEN ADMITTING the fixture and retained
   with the compilation (`:admission-origin`, cc3 r2 item 2) — never
   synthesized from content after compilation; a compilation without
   a retained record refuses with `:rule :cc3-admission-record`, and
   [:oracle/fixture <id>] is never minted."
  [compiled]
  (let [admission (:admission-origin compiled)]
    (when-not (vm/occurrence-origin? admission)
      (throw (ex-info "Oracle binding requires the compilation's retained admission record"
                      {:rule :cc3-admission-record
                       :admission admission})))
    (let [ast (:compiled-ast compiled)
          token (nth admission 2)
          origin admission
          projected (fn [tree-ast]
                      (encoder/project
                        (encoder/source-envelope :program token [tree-ast])))
          {:keys [tree rows marked eligible]}
          (let [p (projected ast)
                t (first (:yin/batch p))
                r (:yin/frontend-metadata p)]
            {:tree t :rows r
             :marked (into #{}
                           (comp (filter #(= :yang/call-site (nth % 3)))
                                 (map (fn [[o rr pth _ _]] [o rr pth])))
                           r)
             :eligible (into #{}
                             (map (fn [[rr pth]] [origin rr pth]))
                             (vm/application-occurrences t))})
          ;; the POST-transformation tree verified separately (item 5)
          {:keys [stamped-tree stamped-rows stamped-marked stamped-eligible]}
          (let [p (projected (:ast compiled))
                t (first (:yin/batch p))
                r (:yin/frontend-metadata p)]
            {:stamped-tree t :stamped-rows r
             :stamped-marked (into #{}
                                   (comp (filter #(= :yang/call-site (nth % 3)))
                                         (map (fn [[o rr pth _ _]] [o rr pth])))
                                   r)
             :stamped-eligible (into #{}
                                     (map (fn [[rr pth]] [origin rr pth]))
                                     (vm/application-occurrences t))})
          correspondence (cc3-correspond ast (:ast compiled) [] [] {})
          ;; site entries are collected BEFORE any map is built: a
          ;; duplicate site id refuses here, never overwrites an
          ;; earlier occurrence in a map under construction (r3 F1)
          site-entries (into []
                             (comp (filter (fn [[_ node]]
                                             (= :application (:type node))))
                                   (keep (fn [[path node]]
                                           (let [direct (cc3-stamp-site-id
                                                          (second (:operands node)))
                                                 inner (kernel-routed-call? node)
                                                 routed (when inner
                                                          (cc3-stamp-site-id
                                                            (first (:operands inner))))]
                                             (when-let [k (or direct routed)]
                                               (let [pre-path (get correspondence
                                                                   path
                                                                   ::unmapped)]
                                                 (when (= ::unmapped pre-path)
                                                   (throw (ex-info
                                                            "Stamped site has no pre-stamp correspondence"
                                                            {:rule :cc3-stamp-correspondence
                                                             :site k
                                                             :stamped-path path})))
                                                 (let [key [origin (:root tree) pre-path]]
                                                   (when-not (contains? marked key)
                                                     (throw (ex-info
                                                              "Stamped site maps to no validated C1 occurrence"
                                                              {:rule :cc3-stamp-correspondence
                                                               :site k
                                                               :key key})))
                                                   [k path key])))))))
                             (cc3-walk (:ast compiled) [] []))
          _ (when (not= (count site-entries)
                        (count (into #{} (map first site-entries))))
              (throw (ex-info "Duplicate stamped site id"
                              {:rule :cc3-duplicate-stamp-site
                               :sites (vec (sort (into []
                                                       (comp (filter (fn [[_ n]]
                                                                       (< 1 n)))
                                                             (map first))
                                                       (frequencies
                                                         (map first
                                                              site-entries)))))})))
          stamped-paths (into {}
                              (map (fn [[k path _key]] [k path]))
                              site-entries)
          stamped-sites (into {}
                              (map (fn [[k _path key]] [k key]))
                              site-entries)]
      (when (not= (count stamped-sites)
                  (count (into #{} (vals stamped-sites))))
        (throw (ex-info "Two stamped sites map to one pre-stamp occurrence"
                        {:rule :cc3-ambiguous-stamp-correspondence
                         :binding stamped-sites})))
      {:origin origin :tree tree :rows rows :marked marked
       :eligible eligible :binding stamped-sites
       :correspondence correspondence
       :stamped-paths stamped-paths
       :stamped-tree stamped-tree :stamped-rows stamped-rows
       :stamped-marked stamped-marked
       :stamped-eligible stamped-eligible})))


(deftest test-cc3-oracle-site-c1-binding
  ;; Bounds for this tagged run are recorded in the cc3 findings'
  ;; PREFLIGHT BOUNDS section before this test was first run:
  ;; 4,000,000 guest instructions and 1,000,000 trace records per C
  ;; lane, restated and not raised.
  (let [;; this binding run allocates its enclosing run identity ONCE
        ;; here; the compilation is its ordinal-0 admission
        run (fresh-admission-run :cc3-binding-run)
        fixture {:id :cc3-binding :call '(latte-kernel.utils/vectorn? [1 2 3] 2)}
        compiled (kernel-compile-in-run run fixture)]
    (is (= :compiled (:status compiled)))
    (is (= [(:scope run) 0] (nth (:admission-origin compiled) 2))
        "the binding run's scope is the enclosing run identity, threaded in")
    (let [{:keys [origin tree rows marked eligible binding correspondence
                  stamped-paths stamped-tree stamped-marked stamped-eligible]}
          (cc3-oracle-binding compiled)
          sites (:sites compiled)]
      (testing "the origin is the retained admission record, not content synthesis"
        (is (= (:admission-origin compiled) origin)
            "the binding consumes the coordinate recorded when admitting")
        (is (true? (vm/occurrence-origin? origin)))
        (is (= :program (nth origin 1)))
        (is (not= [:source :program (:canonical-source-hash compiled) 0]
                  origin)
            "identical content alone no longer constructs the origin")
        (is (false? (vm/occurrence-origin? [:oracle/fixture :cc3-binding]))
            "a fixture id alone is not a persisted origin")
        (is (= :cc3-admission-record
               (try (cc3-oracle-binding (dissoc compiled :admission-origin))
                    ::no-failure
                    (catch Exception e (:rule (ex-data e)))))
            "a compilation with no retained admission record refuses"))
      (testing "replay: rebinding one retained compilation is the same history"
        (is (= binding (:binding (cc3-oracle-binding compiled)))))
      (testing "coverage separation: the full C1 set is verified independently"
        (is (= eligible marked)
            "every final application occurrence carries exactly its marker")
        (is (< (count binding) (count marked))
            "uncredited helpers and generated applications are C1 occurrences but NOT oracle sites"))
      (testing "post-transformation coverage: the stamped tree as executed (item 5)"
        ;; independently derived inserted helpers: each stamped site
        ;; wraps its argument node in two new applications
        ;; (dsg__intra_call, conj) — applications of the
        ;; stamped tree that are not C1 occurrences of the compiled
        ;; program, accounted for here as their own set
        (let [wrapper-paths (into #{}
                                  (comp (filter (fn [[_ node]]
                                                  (kernel-stamped-args? node)))
                                        (map first))
                                  (cc3-walk (:ast compiled) [] []))
              inserted (into #{}
                             (mapcat (fn [p]
                                       [(conj p) (conj p [3 1])]))
                             wrapper-paths)
              inserted-keys (into #{}
                                  (map (fn [p]
                                         [origin
                                          (:root stamped-tree) p]))
                                  inserted)]
          (is (= (count sites) (count wrapper-paths))
              "one stamping wrapper per oracle site")
          (is (empty? (set/intersection stamped-marked inserted-keys))
              "inserted helpers are not marked occurrences")
          (is (= stamped-eligible
                 (set/union stamped-marked inserted-keys))
              "the stamped application set is exactly the marked occurrences plus the inserted helpers")
          (is (= (+ (count marked) (* 2 (count sites)))
                 (count stamped-eligible))
              "stamping inserted two applications per site and nothing else")))
      (testing "stamped paths are translated, never reused as pre-stamp paths (item 1)"
        (is (every? (fn [[k [_ _ pre-path]]]
                      (= pre-path (get correspondence (get stamped-paths k))))
                    binding)
            "every bound key's path is its stamped path through the correspondence"))
      (testing "bijection: stamped guest sites <-> selected compiled occurrences"
        (is (= (set (map :site sites)) (set (keys binding))))
        (is (= (count binding) (count (into #{} (vals binding))))
            "no two sites share an occurrence (duplicate content keeps distinct paths)")
        (is (every? marked (vals binding))
            "every bound key is a validated post-expansion C1 occurrence"))
      (testing "Rule R: yin/def occurrences are marked but emit no call event"
        (let [sem (linearize/project-rows tree nil rows)
              define-paths (into #{}
                                 (keep (fn [[pc _o _r p]]
                                         (when (= :define (first (nth (:vector sem) pc)))
                                           p)))
                                 (:provenance sem))
              call-keys (set (map (fn [[_ o r p]] [o r p])
                                  (:call-provenance sem)))]
          (is (seq define-paths))
          (is (every? (fn [k] (not (contains? call-keys k)))
                      (filter (fn [[_ _ p]] (contains? define-paths p)) marked))
              "a :define occurrence never appears as a call-provenance key")))
      (testing "lowering: exact call-PC coverage and multiplicities (item 5)"
        (let [sem (linearize/project-rows tree nil rows)
              cp (:call-provenance sem)
              stack (derive/stack-image
                      (:vector sem) {:segment-address :seg/stack
                                     :call-provenance cp})
              register (derive/register-image
                         (:vector sem) {:segment-address :seg/register
                                        :call-provenance cp})
              ;; independently derived expected occurrences: every
              ;; marked application of the compiled tree except the
              ;; Rule-R `yin/def` applications (they lower to :define)
              define-paths (into #{}
                                 (comp (filter (fn [[_ node]]
                                                 (and (= :application (:type node))
                                                      (= :variable (:type (:operator node)))
                                                      (= 'yin/def (:name (:operator node))))))
                                       (map first))
                                 (cc3-walk (:compiled-ast compiled) [] []))
              expected (frequencies
                         (map (fn [[o r p]] [o r p])
                              (remove (fn [[_ _ p]] (contains? define-paths p))
                                      marked)))
              keys-of (fn [prov] (map (fn [[_ _ o r p]] [o r p]) prov))
              call-pcs (fn [instructions]
                         (into []
                               (keep-indexed (fn [pc ins]
                                               (when (= :call (first ins)) pc)))
                               instructions))]
          (is (seq define-paths) "the fixture carries Rule-R definitions")
          (is (= expected (frequencies (map (fn [[_ o r p]] [o r p]) cp)))
              "semantic call provenance is exactly the expected occurrence multiset")
          (is (= expected (frequencies (keys-of (:call-provenance stack)))))
          (is (= expected (frequencies (keys-of (:call-provenance register)))))
          (is (= (call-pcs (:vector sem)) (mapv first cp))
              "every semantic :call pc carries provenance, in pc order")
          (is (= (call-pcs (:image stack))
                 (mapv second (:call-provenance stack)))
              "every stack :call pc carries provenance, in pc order")
          (is (= (call-pcs (:instructions register))
                 (mapv second (:call-provenance register)))
              "every register :call pc carries provenance, in pc order")))
      (testing "both C lanes resolve each executed call to the identical K"
        (let [lane-keys (fn [lane]
                          (let [row (kernel-c compiled lane)]
                            (is (= :completed (:status row)) (str lane))
                            (into #{}
                                  (comp (map #(nth % 2 nil))
                                        (filter integer?)
                                        (map (fn [k]
                                               (is (contains? binding k)
                                                   (str "executed site " k " is bound"))
                                               (get binding k))))
                                  (:executed-calls row))))]
          (is (= (lane-keys :c-walker) (lane-keys :c-semantic))))))))


(deftest test-cc3-stamp-correspondence-nested
  ;; cc3 r2 item 1, synthetic and fast: a guest call nested inside
  ;; another stamped call's argument vector moves under stamping; the
  ;; correspondence must map its stamped path back to the pre-stamp
  ;; path of THAT application, and mismatched trees must refuse.
  (let [lit (fn [v] {:type :literal :value v})
        var* (fn [n] {:type :variable :name n})
        app (fn [op ops] {:type :application :operator op :operands ops})
        intra (fn [f args] (app (var* 'dsg__intra_call) [(var* f) args]))
        conj-args (fn [& xs] (app (var* 'conj) (into [(lit [])] xs)))
        inner (intra 'f (conj-args (lit 41)))
        outer (intra 'g (conj-args inner (lit 7)))
        {stamped :ast sites :sites} (kernel-stamp-sites outer #{'f 'g})
        by-path (fn [tree] (into {} (cc3-walk tree [] [])))
        correspondence (cc3-correspond outer stamped [] [] {})
        ;; the inner call's two addresses, derived by walking each tree
        site-path (fn [entries]
                    (some (fn [[p node]]
                            (when (and (= :application (:type node))
                                       (= 'f (get-in node [:operands 0 :name])))
                              p))
                          entries))
        pre-path (site-path (by-path outer))
        stamped-path (site-path (by-path stamped))]
    (is (= 2 (count sites)))
    (is (some? pre-path) (str "pre-stamp inner path " pre-path))
    (is (some? stamped-path) (str "stamped inner path " stamped-path))
    (is (not= pre-path stamped-path)
        "precondition: stamping really moved the nested argument site")
    (is (= pre-path (get correspondence stamped-path))
        "the nested site resolves to its own pre-stamp application")
    (is (= :cc3-stamp-correspondence
           (try (cc3-correspond outer (assoc stamped :type :lambda) [] [] {})
                ::no-failure
                (catch Exception e (:rule (ex-data e)))))
        "a divergent tree refuses instead of guessing a correspondence")))


(deftest test-cc3-correspondence-verifies-grammar-data
  ;; cc3 r3 item F1: correspondence must verify scalar grammar data at
  ;; translated positions, the wrapper recognizer must verify the
  ;; helper is gate-site!, and duplicate site ids must refuse before
  ;; any site map is constructed.
  (let [lit (fn [v] {:type :literal :value v})
        var* (fn [n] {:type :variable :name n})
        app (fn [op ops] {:type :application :operator op :operands ops})
        intra (fn [f args] (app (var* 'dsg__intra_call) [(var* f) args]))
        conj-args (fn [& xs] (app (var* 'conj) (into [(lit [])] xs)))
        inner (intra 'f (conj-args (lit 41)))
        outer (intra 'g (conj-args inner (lit 7)))
        {stamped :ast} (kernel-stamp-sites outer #{'f 'g})
        refusal (fn [pre stamped-tree]
                  (try (cc3-correspond pre stamped-tree [] [] {})
                       ::no-failure
                       (catch Exception e (:rule (ex-data e)))))]
    (testing "same-shape scalar mutations refuse"
      (is (= :cc3-stamp-correspondence
             (refusal (var* 'f) (var* 'h)))
          "a changed callee variable name preserves shape but not grammar data")
      (is (= :cc3-stamp-correspondence
             (refusal (lit 41) (lit 42)))
          "a changed literal value preserves shape but not grammar data")
      (is (= :cc3-stamp-correspondence
             (refusal {:type :lambda :params '[x] :body (lit 1)}
                      {:type :lambda :params '[y] :body (lit 1)}))
          "changed lambda parameters preserve shape but not grammar data")
      (let [entries (cc3-walk stamped [] [])
            callee-path (some (fn [[p node]]
                                (when (and (= :application (:type node))
                                           (= 'f (get-in node [:operands 0 :name])))
                                  p))
                              entries)
            mutated (cc3-assoc-at stamped callee-path
                                  (fn [node]
                                    (assoc-in node [:operands 0 :name] 'h)))]
        (is (some? callee-path))
        (is (= :cc3-stamp-correspondence (refusal outer mutated))
            "a renamed nested callee refuses through the stamped tree")))
    (testing "the wrapper recognizer verifies the gate-site! helper"
      (let [wrapper (some (fn [[_ node]]
                            (when (kernel-stamped-args? node) node))
                          (cc3-walk stamped [] []))
            mutated (assoc-in wrapper [:operands 0 :name]
                              'not-gate-site!)]
        (is (some? wrapper) "precondition: a real stamping wrapper exists")
        (is (nil? (kernel-stamped-args? mutated))
            "a 2-arg application around another helper is no wrapper")
        (is (= :cc3-stamp-correspondence (refusal outer
                                                  (cc3-assoc-at
                                                    stamped
                                                    (some (fn [[p node]]
                                                            (when (identical? node wrapper) p))
                                                          (cc3-walk stamped [] []))
                                                    (fn [_] mutated))))
            "correspondence refuses when the wrapper helper is not gate-site!")))
    (testing "duplicate site ids refuse before map construction"
      (let [fixture {:id :cc3-binding
                     :call '(latte-kernel.utils/vectorn? [1 2 3] 2)}
            compiled (kernel-compile-in-run
                       (fresh-admission-run :cc3-duplicate-run) fixture)
            wrappers (into []
                           (filter (fn [[_ node]]
                                     (kernel-stamped-args? node)))
                           (cc3-walk (:ast compiled) [] []))
            [p2 _] (second wrappers)
            k (cc3-stamp-site-id (second (first wrappers)))
            duplicated (cc3-assoc-at (:ast compiled) p2
                                     (fn [w]
                                       (assoc-in w [:operands 1 :operands 1 :value]
                                                 k)))]
        (is (= :compiled (:status compiled)))
        (is (<= 2 (count wrappers)) "precondition: at least two stamped sites")
        (is (some? k))
        (is (= :cc3-duplicate-stamp-site
               (try (cc3-oracle-binding (assoc compiled :ast duplicated))
                    ::no-failure
                    (catch Exception e (:rule (ex-data e)))))
            "two wrappers carrying one site id refuse, never overwrite")))))


(deftest test-cc3-distinct-admissions-distinct-origins
  ;; cc3 r3 item F2 + r4 residue: the admission coordinate is an
  ;; explicit, composition-supplied batch token
  ;; `[run-scope admission-ordinal]` with member index zero, retained
  ;; with the compilation as plain data — no per-JVM global counter
  ;; and no default admission that stamps every admission alike.
  ;; Distinct scopes with restarted ordinals AND distinct ordinals
  ;; within one scope, admitting identical content, mint distinct
  ;; origins; every ordinal (including a nonzero one) binds
  ;; end-to-end, projected and searched keys agreeing; replaying one
  ;; retained admission keeps its one origin.
  (let [fixture {:id :cc3-binding
                 :call '(latte-kernel.utils/vectorn? [1 2 3] 2)}
        a (kernel-compile fixture "run-a" 0)
        b (kernel-compile fixture "run-b" 0)
        a1 (kernel-compile fixture "run-a" 1)]
    (is (= :compiled (:status a)))
    (is (= :compiled (:status b)))
    (is (= :compiled (:status a1)))
    (is (= (:canonical-source-hash a) (:canonical-source-hash b)
           (:canonical-source-hash a1))
        "precondition: the content really is identical")
    (is (= ["run-a" 0] (nth (:admission-origin a) 2))
        "the batch token carries both the scope and the ordinal")
    (is (= ["run-b" 0] (nth (:admission-origin b) 2)))
    (is (= ["run-a" 1] (nth (:admission-origin a1) 2))
        "a nonzero ordinal rides the token, not the member index")
    (is (= 0 (nth (:admission-origin a) 3)
           (nth (:admission-origin b) 3)
           (nth (:admission-origin a1) 3))
        "separately admitted single-member batches keep member index 0")
    (is (not= (:admission-origin a) (:admission-origin b))
        "distinct scopes with restarted ordinals mint distinct origins")
    (is (not= (:admission-origin a) (:admission-origin a1))
        "same scope, different ordinal mints a distinct origin")
    (let [ba (cc3-oracle-binding a)
          bb (cc3-oracle-binding b)
          ba1 (cc3-oracle-binding a1)]
      (is (= (:admission-origin a) (:origin ba)))
      (is (= (:admission-origin b) (:origin bb)))
      (is (= (:admission-origin a1) (:origin ba1))
          "a nonzero ordinal binds end-to-end: projected and searched keys agree")
      (is (= (:binding ba) (:binding (cc3-oracle-binding a)))
          "replay reuses the recorded coordinate of scope run-a")
      (is (= (set (keys (:binding ba))) (set (keys (:binding bb)))
             (set (keys (:binding ba1)))))
      (is (not= (set (vals (:binding ba))) (set (vals (:binding bb))))
          "the same sites bind under each admission's own origin")
      (is (not= (set (vals (:binding ba))) (set (vals (:binding ba1))))
          "the same sites bind under each ordinal's own origin"))))


(deftest test-cc3-caller-run-threads-distinct-admissions
  ;; cc3 r5 F2 residue: pin through the ACTUAL caller path. One
  ;; evidence run allocates its scope ONCE; `kernel-row` (the caller
  ;; every fixture row goes through) assigns DISTINCT ordinals within
  ;; that run, so identical content admitted twice in one run mints
  ;; distinct origins. A fresh run supplies a distinct scope (ordinal
  ;; restarting at 0 still mints a new origin), and replay explicitly
  ;; re-supplies the retained run's scope+ordinal.
  (let [fixture {:id :cc3-binding
                 :call '(latte-kernel.utils/vectorn? [1 2 3] 2)}
        run (fresh-admission-run :caller-evidence-run)
        row-0 (kernel-row-in-run run fixture)
        row-1 (kernel-row-in-run run fixture)
        fresh-row (kernel-row-in-run
                    (fresh-admission-run :caller-evidence-run) fixture)
        replay-row (kernel-row fixture (:scope run) 0)]
    (is (= :pass (:status row-0)))
    (is (= :pass (:status row-1)))
    (is (= [(:scope run) 0] (nth (:admission-origin row-0) 2))
        "the first fixture in the run takes ordinal 0 under the run scope")
    (is (= [(:scope run) 1] (nth (:admission-origin row-1) 2))
        "the second fixture in the same run takes a distinct ordinal")
    (is (not= (:admission-origin row-0) (:admission-origin row-1))
        "identical content admitted twice in one run mints distinct origins")
    (is (not= (:scope run) (nth (:admission-origin fresh-row) 2))
        "a fresh run supplies a distinct scope")
    (is (= 0 (nth (nth (:admission-origin fresh-row) 2) 1))
        "the fresh run restarts its ordinals")
    (is (not= (:admission-origin row-0) (:admission-origin fresh-row))
        "a restarted ordinal under a fresh scope still mints a new origin")
    (is (= (:admission-origin row-0) (:admission-origin replay-row))
        "replay explicitly re-supplies the retained scope+ordinal")))

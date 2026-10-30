(ns yang.latte-oracle-test
  "Pinned upstream LaTTe differential harness, slices 1-8 of the Yang gap
   program. A executes the original library on Clojure. B executes explicit
   portability source on Clojure; C compiles it through Yang for the walker
   and semantic VMs. The legacy classifier records the remaining blockers;
   the context module additionally checks free-symbol admission before C.
   Adaptations, operation sequences and scope departures live beside the
   manifest in yang/latte_oracle/."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [dao.jing.cbor :as cbor]
            [clojure.tools.reader :as r]
            [clojure.tools.reader.reader-types :as rt]
            [yang.clojure :as yang]
            [yang.clojure-test :as compiler-test]
            [yang.core.call :as call]
            [yin.vm :as vm]
            [yin.vm.data :as data]
            [yin.vm.module :as module]
            [yin.vm.encoder :as encoder]
            [yin.vm.ucf.rows :as rows]
            [yin.vm.ucf.rows-support :as rows-support]
            [yin.vm.values :as values]
            [yin.vm.semantic :as semantic]
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
        pr (rt/source-logging-push-back-reader (java.io.PushbackReader. (io/reader f)) 1 (str f))]
    (let [ns-form (r/read {:read-cond :allow :features #{:clj} :eof ::eof} pr)
          declared-name (second ns-form)
          aliases (ns-declaration-aliases ns-form)]
      (binding [r/*alias-map* aliases
                *ns* (the-ns (create-ns declared-name))]
        (loop [acc []]
          (let [form (r/read {:read-cond :allow :features #{:clj} :eof ::eof} pr)]
            (if (= form ::eof) acc (recur (conj acc form)))))))))


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
  (let [[kind name & body-ops] form
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
  (let [globals (into #{'yin/def}
                      (concat (keys vm/primitive-profiles)
                              (map first call/definitions)
                              (for [form program :when (#{'def 'defn 'defn-} (first form))]
                                (second form))
                              (for [[module exports] {'kind yang/kind-module
                                                      'cell module/cell-module
                                                      'data data/data-module
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
                (throw (ex-info "Unexpected AST in context admission" {:type (:type node)}))))]
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
    (testing "failure: error outcome consumed, data overrides :msg, result is [:ko ... nil]"
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
  "B is ordinary Clojure over the checked-in adaptation. The sole guest
   outcome intrinsic becomes the equivalent host catch; data primitives and
   deterministic gensym retain exactly their portable contracts."
  [form]
  (walk/postwalk
    (fn [x]
      (cond
        (and (seq? x) (= 'yin.k/outcome-of (first x)))
        `(try [:yin.k/ok ~(second x)]
              (catch clojure.lang.ExceptionInfo e#
                [:yin.k/error {:yin.k/message (ex-message e#) :yin.k/data (ex-data e#)}]))
        (and (seq? x) (= 'gensym (first x)))
        (list 'symbol (list 'str (second x) (nth x 2)))
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
  (let [program [(list 'let ['checkpoint
                             (list 'let ['base initial-context 'offset 4]
                                   '(register-unparser base :captured
                                                       (fn [term] [(+ offset term) true])))]
                       '(unparse checkpoint 3))]
        forms (into (context-for program) program)
        ast (yang/compile-program (into (yang/library-for forms) forms) true arg-vector-opts)
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
            _ (assert-machine-data-within-budget! k 100000)
            wire (rows/lift-task checkpoint (rows-support/serve-table) {:source-index source-index})
            _ (is (vm/plain-data? wire) "serialized context contains code references, not host closures")
            {:keys [attach!]} (rows-support/attacher)
            restored (rows/lower-task (rows-support/new-machine {:modules modules})
                                      (cbor/decode (cbor/encode wire)) attach!)
            k' (get-in restored [:env 'checkpoint])]
        (is (values/closure? (get-in k' [:unparser-registry :unparsers :captured
                                         :yang.call/entry])))
        (assert-machine-data-within-budget! k' 100000)
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
  (testing "context modules reject host namespace and Var operations at admission"
    (doseq [call ['(resolve 'clojure.core/map) '(var? 5)]]
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
  [script]
  (let [ns-sym (gensym "latte-proof-a-")
        sandbox (create-ns ns-sym)
        ddef ((upstream-fn 'latte-kernel.defenv/->Definition) 'd [] 0 'body #{} 'T {})]
    (try
      (intern sandbox 'd ddef)
      (binding [*ns* sandbox]
        (with-redefs-fn {(upstream-fn 'latte-kernel.proof/+opacity-fn+)
                         (atom (fn [v flag] (alter-var-root v assoc-in [:opts :opaque] flag)))}
          #(a-leg (upstream-fn 'latte-kernel.proof/elab-proof) [[{} {}] [] script])))
      (finally (remove-ns ns-sym)))))


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

(ns yang.latte-oracle-test
  "Slice 1 of the architect-designed yang.clojure gap-closure program
   (collab/1793128583916-architect-yang-latte-gap-plan.gpt-6-astra.findings.md).

   The oracle is UPSTREAM latte-kernel (the pinned git dep), never the
   adapted copy: A-leg calls the original on the host, C-leg compiles the
   same upstream source through yang.clojure and runs it on the VM. A
   feature classifier records which corpus functions are blocked by which
   missing compiler/stdlib feature — that is the narrowing instrument this
   slice ships. Structure-only features (:loop, :destructuring, ...) are
   static; unresolved free symbols surface as :blocked-run at C-leg time."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [clojure.tools.reader :as r]
            [clojure.tools.reader.reader-types :as rt]
            [yang.clojure :as yang]
            [yang.clojure-test :as compiler-test]
            [yin.vm :as vm]
            [yin.vm.data :as data]
            [yin.vm.module :as module]))


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
  "The guest library's whole host surface: the stream and data modules, the
   `kind` module, and `portable`, the outcome adapters of the data exports the
   collection predicates read."
  []
  (let [portable (into {}
                       (map (fn [[source _]]
                              (let [op (symbol (name source))]
                                [op (yang/outcome-adapter data/refusal-message
                                                          (get data/data-module op))])))
                       yang/library-adapters)]
    (-> (module/default-registry)
        module/register-stream-module
        data/register-data-module
        (host-module 'kind kind-fns yang/kind-arities)
        (host-module 'portable portable
                     (into {} (map (fn [op] [op (:yin.k/arities (get data/data-profiles op))]))
                           (keys portable))))))


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


(defn- run-outcome
  "Compile `forms` plus `call-form` (with the library definitions they need)
   and return the RAW outcome of the call: the value channel carries
   [tag payload]. Every fixture runs on walker and semantic lanes under the
   same finite instruction budget."
  [forms call-form]
  (let [program (into [] (concat forms [call-form]))
        ast (yang/compile-program (into (yang/library-for program) program) true arg-vector-opts)
        modules (oracle-registry)
        walker (compiler-test/run-guest-ast ast modules :walker 1000000)
        semantic (compiler-test/run-guest-ast ast modules :semantic 1000000)]
    (is (= walker semantic) (str "oracle lanes agree: " (pr-str call-form)))
    (is (= (result-shape walker) (result-shape semantic))
        (str "oracle lane shapes and metadata agree: " (pr-str call-form)))
    walker))


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
  "Drop corpus namespace aliases for guest calls. Only finite utils/zip
   admits eager lazy-seq adaptation; cond and if-let are compiler forms."
  [form]
  (let [finite-zip? (and (seq? form) (= 'defn (first form)) (= 'zip (second form)))]
    (walk/postwalk
      (fn [f]
        (cond
          (and (seq? f) (= 'lazy-seq (first f)))
          (if finite-zip? (second f)
              (throw (ex-info "Only finite utils/zip admits eager lazy-seq adaptation" {})))
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

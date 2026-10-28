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
            [clojure.tools.reader :as r]
            [clojure.tools.reader.reader-types :as rt]
            [yang.clojure :as yang]
            [yin.vm :as vm]
            [yin.vm.data :as data]
            [yin.vm.module :as module]
            [yin.vm.test-utils :as tu]
            [yin.vm.values :as values]))


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


(defn- stdlib-module
  []
  ;; slice-1 scaffolding: pure host fns standing in for the guest library the
  ;; later slices build. Replaced per the architect's slices 2 and 7.
  (let [pf {:yin.k/profile :yin.k.pp/latte-spike
            :yin.k/class :pure
            :yin.k/arities [1 2 3 :variadic]
            :yin.k/effects #{}
            :yin.k/host-state :none}]
    (module/register-host-module
      (module/default-registry)
      'latte.lib
      {'seq seq, 'count count, 'vector? vector?, 'vector vector, 'rem rem
       'name name, 'keyword? keyword?, 'symbol? symbol?, 'list? list?
       'map? map?, 'set? set?, 'fn? fn?, 'coll? coll?, 'nil? nil?
       'identity identity, 'boolean? boolean?, 'int? int?}
      (zipmap (keys {'seq seq, 'count count, 'vector? vector?, 'vector vector
                     'rem rem, 'name name, 'keyword? keyword?, 'symbol? symbol?
                     'list? list?, 'map? map?, 'set? set?, 'fn? fn?, 'coll? coll?
                     'nil? nil?, 'identity identity, 'boolean? boolean?, 'int? int?})
              (repeat pf)))))


(def ^:private arg-vector-opts
  "The argument-vector convention (slice 3b): primitives, plus the scaffold
   `seq?`, are the statically native names."
  {:convention :arg-vector
   :native (conj (set (keys vm/primitives)) 'seq?)})


(defn- run-on-vm
  "Compile `forms` plus `call-form` through yang.clojure and run the result;
   `opts` opts the program into a compile-program convention."
  ([forms call-form] (run-on-vm forms call-form nil))
  ([forms call-form opts]
   ;; `seq?` is a scaffold primitive until the guest collection library
   ;; lands (slice 7); the data module (count/nth) serves the convention's
   ;; arity dispatch
   (let [registry (-> (stdlib-module)
                      module/register-stream-module
                      data/register-data-module)]
     (-> (tu/make-observer-session
           (tu/create-vm {:modules registry
                          :primitives (assoc vm/primitives 'seq? seq?)}))
         (tu/queue-ast! (yang/compile-program
                          (into [] (concat forms [call-form])) true opts))
         tu/run-session
         :consumer
         vm/value))))


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
                                  :value (run-on-vm (vec clean-forms)
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
      ;; vectorn?: classified blocked (host stdlib fns vector/count/rem), so
      ;; its C-leg must FAIL with an unresolved symbol — the recorded gap,
      ;; never a silent pass
      (let [v? (first (filter #(= 'latte-kernel.utils/vectorn? (:qsym %)) results))]
        (is (seq (classify-form (get top-forms ['latte_kernel.utils 'vectorn?]))))
        (is (= :error (get-in v? [:c :status])))
        (is (re-find #"resolve" (get-in v? [:c :msg] ""))))))
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
    (run-on-vm forms (list* fname (map #(list 'quote %) args)) arg-vector-opts)))


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

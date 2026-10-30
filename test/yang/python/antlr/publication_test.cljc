(ns yang.python.antlr.publication-test
  "I5's portable SCC graph and canonical construction contract."
  (:require [clojure.test :refer [deftest is]]
            [dao.jing :as jing]
            [dao.jing.mem :as mem]
            [dao.test-slow :as slow]
            [yang.python.antlr.import-programs :as programs]
            [yang.python.antlr.linked-harness :as h]
            [yang.python.antlr.lower :as lower]
            [yang.python.antlr.lower-portable-test :as portable]
            [yang.python.antlr.publication :as publication]
            [yang.python.antlr.uast :as u]
            [yin.vm :as vm]
            [yin.vm.module :as module]
            [yin.vm.linker.interpreter :as link]
            [yin.vm.linker.publish :as publish]))


(defn import-packet
  "A portable CST for a sequence of absolute imports."
  [names]
  (portable/packet
    (into [:file_input]
          (concat
            (map (fn [n]
                   [:stmt [:simple_stmts
                           [:simple_stmt
                            [:import_stmt [:import_name ["IMPORT" "import"]
                                           [:dotted_as_names
                                            [:dotted_as_name [:dotted_name [:name ["NAME" n]]]]]]]]
                           ["NEWLINE" "\n"]]]) names)
            [["EOF" "<EOF>"]]))))


(defn unit
  [n deps]
  {:name n, :packet (import-packet deps)})


(defn nodes
  [ast]
  (tree-seq map? #(filter map? (mapcat (fn [v] (if (vector? v) v [v])) (vals %))) ast))


(deftest components-and-dependency-order-test
  (is (= {:status :ok, :order ["k" "t" "m"]}
         (publication/publication-order [(unit "t" ["k"]) (unit "k" []) (unit "m" [])] {})))
  (doseq [units [[(unit "a" ["b"]) (unit "b" ["a"])]
                 [(unit "b" ["a"]) (unit "a" ["b"])]]]
    (is (= {:status :ok, :order [["a" "b"]]}
           (publication/publication-order units {}))))
  (is (= {:status :ok, :order ["out" ["a" "b" "c"] "main"]}
         (publication/publication-order
           [(unit "main" ["a"]) (unit "a" ["b" "out"])
            (unit "b" ["c"]) (unit "c" ["a"]) (unit "out" [])] {})))
  (is (= {:status :ok, :order [["a" "b"] ["c" "d"]]}
         (publication/publication-order
           [(unit "a" ["b"]) (unit "b" ["a"])
            (unit "c" ["d"]) (unit "d" ["c"])] {})))
  (is (= {:status :ok, :order ["self"]}
         (publication/publication-order [(unit "self" ["self"])] {})))
  (is (= {:status :refused, :reason :yang.python.antlr/unpublished-import,
          :module "missing", :imported-by "a"}
         (publication/publication-order [(unit "a" ["b" "missing"]) (unit "b" ["a"])] {}))))


(deftest canonical-bundle-and-alias-contract-test
  (let [units [{:name "ca", :packet programs/ca-packet}
               {:name "cb", :packet programs/cb-packet}]
        [bundle a b :as specs] (lower/scc-module-specs (h/registry) units :test/py {})
        vars (set (keep :name (nodes (:ast bundle))))]
    (is (= specs (lower/scc-module-specs (h/registry) (reverse units) :test/py {})))
    (is (= 'pyscc.ca$$cb (:name bundle)))
    (is (= '#{m0-spec m0-body m1-spec m1-body} (:exports bundle)))
    (is (= {'py :test/py} (:requires bundle)))
    (is (= '[pym.ca pym.cb] (mapv :name [a b])))
    (is (empty? (filter #(and (symbol? %) (contains? #{"pym.ca" "pym.cb"} (namespace %))) vars)))
    (is (not (contains? vars 'try-require)))
    ;; Bundle installation evaluates only require and definitions. Mutable
    ;; Python module state is allocated later, when a member body runs.
    (is (= 5 (count (get-in bundle [:ast :operands]))))
    (is (every? #(= 'yin/def (get-in % [:operator :name]))
                (butlast (get-in bundle [:ast :operands]))))
    (is (= 'require (get-in (last (get-in bundle [:ast :operands])) [:operator :name])))
    (is (= '#{m0-body m1-body}
           (set (filter '#{m0-body m1-body} vars))))
    (doseq [alias [a b]]
      (is (= lower/module-exports (:exports alias)))
      (is (= {'require (vm/profile-of vm/primitives 'require)} (:primitives alias)))
      (is (= (if (= alias a) '#{pyscc.ca$$cb/m0-spec pyscc.ca$$cb/m0-body}
                 '#{pyscc.ca$$cb/m1-spec pyscc.ca$$cb/m1-body})
             (set (keep #(when (= :variable (:type %))
                           (when (= "pyscc.ca$$cb" (namespace (:name %))) (:name %)))
                        (nodes (:ast alias))))))
      (is (= #{"pyscc.ca$$cb"}
             (set (keep #(when (and (= :variable (:type %))
                                    (not= "yin" (namespace (:name %))))
                           (namespace (:name %)))
                        (nodes (:ast alias)))))))
    (doseq [spec specs]
      (is (nil? (vm/ast-reserved-defect (:ast spec)))))))


(deftest bundle-outgoing-pins-test
  (let [[bundle] (lower/scc-module-specs (h/registry)
                                         [(unit "a" ["b" "out"]) (unit "b" ["a"])]
                                         :test/py {"out" :test/out})]
    (is (= {'py :test/py, 'pym.out :test/out} (:requires bundle)))
    (is (some #(= 'try-require (:name %)) (nodes (:ast bundle))))))


(deftest canonical-code-address-fixtures-test
  (let [specs (lower/scc-module-specs (h/registry)
                                      [{:name "ca", :packet programs/ca-packet}
                                       {:name "cb", :packet programs/cb-packet}] :test/py {})
        code (mapv #(vm/ast->semantic-bytecode (:ast %)) specs)]
    (is (= [:segment/blake3-c3b97dc9100586138a7540249739095a75cd76e747d0cc55859386d74954d978
            :segment/blake3-6ac5f971328799b6b3aa13a8e52f9ff386661f1883ab26934e749dee7cae9943
            :segment/blake3-942c8045d94be038305e7f53f3de86c3d8215b3dcceb76224445917709134a7f]
           (mapv :root code)))
    (is (= [103 13 13] (mapv #(count (:rows %)) code)))))


(deftest ^:slow deterministic-published-scc-addresses-test
  (slow/guard "deterministic-published-scc-addresses"
              (fn []
                (let [units [{:name "ca", :packet programs/ca-packet}
                             {:name "cb", :packet programs/cb-packet}]
                      store (:store @h/published)
                      py (:address @h/published)
                      publish (fn [us] (publication/publish-units! store (h/registry) py us))
                      first-result (publish units)
                      second-result (publish (reverse units))]
                  (is (= :ok (:status first-result)) (pr-str first-result))
                  (is (= first-result second-result))
                  (is (= #{"ca" "cb"} (set (keys (:addresses first-result)))))
                  (let [aliases (mapv #(jing/get store % nil) (vals (:addresses first-result)))
                        requirements (mapv :yin.module/requires aliases)
                        bundle-address (get (first requirements) 'pyscc.ca$$cb)
                        bundle (jing/get store bundle-address nil)]
                    (is (= 2 (count aliases)))
                    (is (every? #(= {'pyscc.ca$$cb bundle-address} %) requirements))
                    (is (= {'py py} (:yin.module/requires bundle)))
                    (is (= '#{m0-spec m0-body m1-spec m1-body} (:yin.module/exports bundle))))))))


(deftest publication-bundles-before-aliases-test
  (let [seen (atom [])
        units [(unit "a" ["b" "out"]) (unit "b" ["a"]) (unit "out" [])]
        publish-fn (fn [_ spec]
                     (swap! seen conj (select-keys spec [:name :requires]))
                     {:status :ok, :address (keyword "test" (str (:name spec)))})]
    (is (= {:status :ok, :addresses {"out" :test/pym.out, "a" :test/pym.a, "b" :test/pym.b},
            :bundles {'pyscc.a$$b :test/pyscc.a$$b}}
           (publication/publish-units! nil (h/registry) :test/py units {} publish-fn)))
    (is (= [{:name 'pym.out, :requires {'py :test/py}}
            {:name 'pyscc.a$$b, :requires {'py :test/py, 'pym.out :test/pym.out}}
            {:name 'pym.a, :requires {'pyscc.a$$b :test/pyscc.a$$b}}
            {:name 'pym.b, :requires {'pyscc.a$$b :test/pyscc.a$$b}}]
           @seen))))


(deftest alias-refusal-stops-publication-test
  (let [seen (atom [])
        publish-fn (fn [_ spec]
                     (swap! seen conj (:name spec))
                     (if (= 'pym.a (:name spec))
                       {:status :refused, :reason :test/refused}
                       {:status :ok, :address :test/address}))]
    (is (= {:status :refused, :reason :test/refused, :unit "a"}
           (publication/publish-units! nil (h/registry) :test/py
                                       [(unit "a" ["b"]) (unit "b" ["a"])
                                        (unit "later" [])] {} publish-fn)))
    (is (= '[pyscc.a$$b pym.a] @seen))))


(deftest malformed-scc-member-names-test
  (doseq [n ["" ".a" "a." "a..b" "a$b"]]
    (let [diagnostic (fn [f]
                       (try
                         (f)
                         nil
                         (catch #?(:cljd Object :clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
                           (ex-data e))))
          expected {:yang.python.antlr/diagnostic :yang.python.antlr/unsupported,
                    :construct "malformed module name", :name n}]
      (is (= expected (diagnostic #(lower/module-packet (:packet (unit n [])) {:name n}))))
      ;; No packet is supplied: name validation must precede body lowering.
      (doseq [units [[{:name n} {:name "valid"}]
                     [{:name "valid"} {:name n}]]]
        (is (= expected (diagnostic #(lower/scc-module-specs (h/registry) units :test/py {}))))))))


(deftest bundle-refusal-stops-publication-test
  (let [seen (atom [])
        publish-fn (fn [_ spec]
                     (swap! seen conj (:name spec))
                     {:status :refused, :reason :test/refused})]
    (is (= {:status :refused, :reason :test/refused, :unit "pyscc.a$$b"}
           (publication/publish-units! nil (h/registry) :test/py
                                       [(unit "a" ["b"]) (unit "b" ["a"])
                                        (unit "later" [])] {} publish-fn)))
    (is (= '[pyscc.a$$b] @seen))))


(deftest dotted-sibling-delivery-test
  (let [[bundle] (lower/scc-module-specs (h/registry)
                                         [(unit "pkg.a" ["pkg.b"]) (unit "pkg.b" ["pkg.a"])]
                                         :test/py {"pkg" :test/pkg})
        ast-nodes (nodes (:ast bundle))
        names (set (keep #(when (= :variable (:type %)) (:name %)) ast-nodes))]
    (is (= 'pyscc.pkg$a$$pkg$b (:name bundle)))
    (is (= '#{m0-body m1-body} (set (filter '#{m0-body m1-body} names))))
    (is (every? #(symbol? (:name %)) (filter #(= :variable (:type %)) ast-nodes)))
    (is (every? #(contains? names %) '[pym.pkg/spec pym.pkg/body]))
    (is (every? #(some (fn [node] (= % (:value node))) ast-nodes)
                [{:name "pkg.a", :package? false} {:name "pkg.b", :package? false}]))))


(defn- scc-source
  [store py result]
  (link/composition
    {:content-store store,
     :derivation :trusted,
     :name-env (merge {'py py, 'yang.call (:call-address @h/published)} (:bundles result)
                      (into {} (map (fn [[n a]] [(lower/module-symbol n) a])) (:addresses result)))}))


(defn- bundle-entry
  [task bundle-name]
  (module/module-entry (:modules (:modules task)) bundle-name))


(deftest bundle-closures-lift-without-cells-test
  (let [store (mem/create-content-mem)
        ;; Installation never calls py. A tiny pure dependency keeps this
        ;; test fast while retaining the real require, admission, and lift.
        py (publish/publish-module! store {:name 'py, :ast (u/lit nil), :exports #{},
                                           :requires {}, :primitives {}})
        result (publication/publish-units! store (h/registry) (:address py)
                                           [(unit "a" ["b"]) (unit "b" ["a"])])
        source (scc-source store (:address py) result)]
    (is (keyword? (:address py)) (pr-str (select-keys py [:status :reason :address])))
    (is (= :ok (:status result)) (pr-str result))
    (doseq [backend (keys h/backends)]
      (let [task (h/run-linked backend (u/app (u/v 'try-require) (u/lit 'pym.a))
                               {:link-source source})
            entry (bundle-entry task 'pyscc.a$$b)]
        (is (vm/halted? task) (str backend))
        (is (= :ok (:status (vm/value task))) (str backend " " (pr-str (vm/value task))))
        (is (= '#{m0-spec m1-spec m0-body m1-body} (set (keys (:slice entry)))) (str backend))
        (doseq [body-key '[m0-body m1-body]]
          (let [marker (get-in entry [:slice body-key])
                env-key (if (= :semantic backend) :yin.k/env :yin.k/frames)]
            (is (contains? marker env-key) (str backend " " body-key))
            (is (empty? (get marker env-key)) (str backend " " body-key))))
        (is (not-any? #(= :cell-ref (:type %)) (tree-seq coll? seq entry)) (str backend))))))


(deftest ^:slow bundle-alias-delivery-and-lift-test
  (slow/guard "bundle-alias-delivery-and-lift"
              (fn []
                (let [units [{:name "ca", :packet programs/ca-packet}
                             {:name "cb", :packet programs/cb-packet}]
                      {:keys [store address]} @h/published
                      result (publication/publish-units! store (h/registry) address units)
                      source (scc-source store address result)]
                  (is (= :ok (:status result)) (pr-str result))
                  (doseq [backend (keys h/backends)]
                    ;; The real prelude and both aliases traverse the linker,
                    ;; so inspect the bundle slice it actually lifted.
                    (doseq [alias '[pym.ca pym.cb]]
                      (let [task (h/run-linked backend
                                               (u/app (u/v 'try-require) (u/lit alias))
                                               {:link-source source})
                            answer (vm/value task)
                            entry (bundle-entry task 'pyscc.ca$$cb)]
                        (is (= :ok (:status answer)) (str backend " " alias " " (pr-str answer)))
                        (is (= '#{m0-spec m1-spec m0-body m1-body} (set (keys (:slice entry)))) (str backend))
                        (is (not-any? #(= :cell-ref (:type %)) (tree-seq coll? seq entry)) (str backend)))))))))

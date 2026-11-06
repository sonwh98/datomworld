(ns yang.clojure-callsite-test
  "Contract C1 for production yang.clojure (Addendum A, design §2): the
   `:yang/call-site 1` frontend-metadata annotation of every final
   `:application` occurrence, and the complete-coverage validation a
   consumer runs against canonical rows."
  (:require [clojure.test :refer [deftest is testing]]
            [yang.clojure :as yang]
            [yang.clojure.prelude]
            [yang.safepoint :as safepoint]
            [yin.vm :as vm]
            [yin.vm.encoder :as encoder]))


(def ^:private origin [:source :program "batch-1" 0])


(defn- project
  [ast]
  (let [projected (encoder/project
                    (encoder/source-envelope :program "batch-1" [ast]))]
    {:tree (first (:yin/batch projected))
     :metadata (:yin/frontend-metadata projected)}))


(defn- eligible
  "The eligible set, derived here directly from canonical rows and the
   occurrence relation, independently of the producer's own helpers."
  [tree]
  (into #{}
        (comp (filter (fn [[_ _ id]]
                        (= :application (nth (get (:rows tree) id) 1))))
              (map (fn [[_ path _]] [origin (:root tree) path])))
        (vm/occurrences tree)))


(defn- marked
  [metadata]
  (into #{}
        (comp (filter #(= :yang/call-site (nth % 3)))
              (map (fn [[o r path _ _]] [o r path])))
        metadata))


(defn- failure-rule
  [f]
  (try (f) ::no-failure
       (catch #?(:cljd Object :clj Exception :cljs :default) e
         (:rule (ex-data e)))))


(deftest equality-gate-test
  (testing "native compile: eligible set equals marker set"
    (let [ast (yang/compile '(+ (* 2 3) 4))
          {:keys [tree metadata]} (project ast)]
      (is (= #{[origin (:root tree) []]
               [origin (:root tree) [[3 0]]]}
             (marked metadata)))
      (is (= (eligible tree) (marked metadata)))
      (is (true? (yang/validate-call-site-coverage! tree metadata origin)))
      (is (true? (yang/validate-ast-call-sites! ast origin)))
      (is (every? (fn [[o r _ k v]]
                    (and (= origin o) (= (:root tree) r)
                         (= :yang/call-site k) (= 1 v)))
                  (filter #(= :yang/call-site (nth % 3)) metadata)))))
  (testing "compile-program, native and argument-vector/outcome paths"
    (doseq [ast [(yang/compile-program
                   '[(defn f [x] (+ x 1)) (defn g [x] (f (* x 2))) (g 21)])
                 (yang/compile-program
                   '[(defn f [x] (+ x 1)) (f 2)] true yang/unit-opts)]]
      (let [{:keys [tree metadata]} (project ast)]
        (is (= (eligible tree) (marked metadata)))
        (is (true? (yang/validate-call-site-coverage! tree metadata origin))))))
  (testing "lower-unit: the linked production path, library included"
    (let [ast (yang/lower-unit '[(defn f [x] (+ x 1)) (f 2)])
          {:keys [tree metadata]} (project ast)]
      (is (< 10 (count (marked metadata))))
      (is (= (eligible tree) (marked metadata)))
      (is (true? (yang/validate-call-site-coverage! tree metadata origin)))))
  (testing "the clj prelude module tree is finalized"
    (let [ast yang.clojure.prelude/module-uast
          {:keys [tree metadata]} (project ast)]
      (is (= (eligible tree) (marked metadata)))
      (is (true? (yang/validate-call-site-coverage! tree metadata origin))))))


(deftest boundary-test
  (testing "yin/def gets an occurrence annotation"
    (let [ast (yang/compile '(def x (fn [a] (+ a 1))))]
      (is (= 1 (:yang/call-site ast)))
      (is (= 1 (get-in ast [:operands 1 :body :yang/call-site])))
      (is (true? (yang/validate-ast-call-sites! ast origin)))))
  (testing "a dedicated stream operation is not an application site"
    (let [ast (yang/compile '(dao.stream.apply/call :op/echo (+ 1 2)))
          {:keys [tree metadata]} (project ast)]
      (is (= :dao.stream.apply/call (:type ast)))
      (is (not (contains? ast :yang/call-site)))
      ;; its operand application is an ordinary occurrence and is marked
      (is (= #{[origin (:root tree) [[3 0]]]} (marked metadata)))
      (is (true? (yang/validate-call-site-coverage! tree metadata origin)))))
  (testing "stream and continuation nodes are never marked"
    (let [ast (yang/annotate-call-sites
                {:type :application
                 :operator {:type :variable :name 'f}
                 :operands [{:type :stream/make :buffer 1}
                            {:type :vm/current-continuation}]})]
      (is (= 1 (:yang/call-site ast)))
      (is (not (contains? (first (:operands ast)) :yang/call-site)))
      (is (not (contains? (second (:operands ast)) :yang/call-site)))))
  (testing "generated trees carry an expansion origin honestly"
    (let [ast (yang/compile-program '[(defn f [x] (+ x 1)) (f 2)]
                                    true yang/unit-opts)
          tree (vm/ast->semantic-bytecode ast)
          expansion-origin [:expansion (:root tree)]]
      (is (true? (yang/validate-ast-call-sites! ast expansion-origin))))))


(deftest validation-failure-test
  (let [ast (yang/compile '(+ (* 2 3) 4))
        {:keys [tree metadata]} (project ast)
        markers (filterv #(= :yang/call-site (nth % 3)) metadata)
        site (vec (take 3 (first markers)))]
    (testing "duplicate identical deliveries dedupe"
      (is (true? (yang/validate-call-site-coverage!
                   tree (into metadata markers) origin))))
    (testing "a conflicting value at one site fails"
      (is (= :yang/call-site-conflict
             (failure-rule
               #(yang/validate-call-site-coverage!
                  tree (conj metadata (conj site :yang/call-site 2))
                  origin)))))
    (testing "an unsupported version fails"
      (is (= :yang/call-site-unsupported-version
             (failure-rule
               #(yang/validate-call-site-coverage!
                  tree (mapv (fn [row]
                               (if (= :yang/call-site (nth row 3))
                                 (assoc row 4 2) row))
                             metadata)
                  origin)))))
    (testing "a nonexistent path fails"
      (is (= :yang/call-site-nonexistent-path
             (failure-rule
               #(yang/validate-call-site-coverage!
                  tree (conj metadata [origin (:root tree) [99]
                                       :yang/call-site 1])
                  origin)))))
    (testing "a non-application target fails"
      ;; [[3 0] [3 0]] is the literal `2` inside `(* 2 3)`
      (is (= :yang/call-site-non-application
             (failure-rule
               #(yang/validate-call-site-coverage!
                  tree (conj metadata [origin (:root tree) [[3 0] [3 0]]
                                       :yang/call-site 1])
                  origin)))))
    (testing "a missing marker under a complete-coverage claim fails"
      (is (= :yang/call-site-incomplete-coverage
             (failure-rule
               #(yang/validate-call-site-coverage!
                  tree (vec (remove (set markers) metadata)) origin)))))))


(deftest safepoint-independence-test
  (testing ":yang/site marks and :yang/call-site markers are distinct"
    (let [ast (yang/annotate-call-sites
                (vary-meta (yang/compile '(fn [x] (inc x)))
                           assoc :yang/site :call))
          tree (vm/ast->semantic-bytecode ast)
          tables (vm/ast-side-tables ast tree origin)]
      (is (= {[] :call}
             (safepoint/sites (:frontend-metadata tables) origin (:root tree))))
      (is (true? (yang/validate-call-site-coverage!
                   tree (:frontend-metadata tables) origin)))
      (is (not-any? (fn [[_ _ _ k _]] (= :yang/site k))
                    (filter #(= :yang/call-site (nth % 3))
                            (:frontend-metadata tables)))))))


(deftest canonical-invariance-test
  (testing "marking changes no canonical row, root, or outcome input"
    (let [form '(defn f [x] (+ (* x 2) 1))
          marked (yang/compile-program [form '(f 21)])
          strip (fn strip [node]
                  (let [slots (get vm/semantic-bytecode-grammar (:type node))]
                    (reduce (fn [n [field kind]]
                              (case kind
                                :node (update n field strip)
                                :nodes (update n field
                                               #(mapv strip %))
                                n))
                            (dissoc node :yang/call-site)
                            slots)))]
      (is (= (vm/ast->semantic-bytecode (strip marked))
             (vm/ast->semantic-bytecode marked))))))

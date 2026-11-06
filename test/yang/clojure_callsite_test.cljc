(ns yang.clojure-callsite-test
  "Contract C1 for production yang.clojure (Addendum A, design §2): the
   `:yang/call-site 1` frontend-metadata annotation of every final
   `:application` occurrence, and the complete-coverage validation a
   consumer runs against canonical rows."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [dao.jing :as jing]
            [dao.jing.mem :as mem]
            [yang.clojure :as yang]
            [yang.clojure.prelude :as clj-prelude]
            [yang.core.call :as call]
            [yang.safepoint :as safepoint]
            [yin.vm :as vm]
            [yin.vm.data :as data]
            [yin.vm.encoder :as encoder]
            [yin.vm.linker.closure :as closure]
            [yin.vm.linker.publish :as publish]
            [yin.vm.macro :as macro]
            [yin.vm.module :as module]))


(def ^:private origin [:source :program "batch-1" 0])


(defn- project
  [ast]
  (let [projected (encoder/project
                    (encoder/source-envelope :program "batch-1" [ast]))]
    {:tree (first (:yin/batch projected))
     :metadata (:yin/frontend-metadata projected)}))


(defn- eligible-under
  "The eligible set of canonical `tree` under `origin`, derived here
   directly from canonical rows and the occurrence relation,
   independently of the producer's own helpers."
  [tree origin]
  (into #{}
        (comp (filter (fn [[_ _ id]]
                        (= :application (nth (get (:rows tree) id) 1))))
              (map (fn [[_ path _]] [origin (:root tree) path])))
        (vm/occurrences tree)))


(defn- eligible
  [tree]
  (eligible-under tree origin))


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
      (is (not (contains? (second (:operands ast)) :yang/call-site))))))


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
                  tree (vec (remove (set markers) metadata)) origin)))))
    (testing "a malformed marker row is rejected, not silently filtered"
      (is (= :yang/call-site-malformed
             (failure-rule
               #(yang/validate-call-site-coverage!
                  tree (conj metadata [origin (:root tree) []
                                       :yang/call-site])
                  origin))))
      (is (= :yang/call-site-malformed
             (failure-rule
               #(yang/validate-call-site-coverage!
                  tree (conj metadata [origin (:root tree) []
                                       :yang/call-site 1 :extra])
                  origin)))))
    (testing "a duplicate entry in a declared table is rejected"
      (is (= :yang/call-site-duplicate-entry
             (failure-rule
               #(yang/validate-call-site-coverage!
                  tree (into metadata markers) origin {:declared? true})))))))


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


(deftest encoder-claim-test
  (testing "canonical-row production refuses a broken C1 claim instead of dropping it"
    (let [ast (yang/compile '(+ (* 2 3) 4))]
      (is (= :yang/call-site-incomplete-coverage
             (failure-rule
               #(encoder/program-batch
                  (update-in ast [:operands 0] dissoc :yang/call-site)))))
      (is (= :yang/call-site-unsupported-version
             (failure-rule
               #(encoder/program-batch (assoc ast :yang/call-site 2))))))))


(defn- expand-like-the-repl
  "Expand compiled `ast` through the REPL pipeline's production stages:
   `encoder/program-batch` (what `yin.repl`'s eval-program appends to
   program-in) into the row-native expander, seeded as the REPL seeds
   its session expander — the standard forms folded through a throwaway
   context, then a fresh session context holding that store. Answers
   `{:ctx ctx :result expansion}`, where `ctx` is the admitting
   context (its `:t` is the batch's source coordinate)."
  [ast]
  (let [store (macro/seed-store
                (macro/make-ctx {:token :callsite-test/standard-forms
                                 :source-medium :callsite-test/standard-forms})
                [macro/stdlib-forms])
        ctx (macro/make-ctx {:token "callsite-test"
                             :source-medium :program-in
                             :store store})]
    {:ctx ctx
     :result (macro/expand-batch (encoder/program-batch ast) ctx)}))


(deftest post-expansion-coverage-test
  (testing "the marker set covers the tree the REPL pipeline actually executes"
    (let [ast (yang/compile-program
                '[(defmacro unless [c a b] (yin/if c b a))
                  (unless false (+ 1 2) 0)])
          {:keys [ctx result]} (expand-like-the-repl ast)
          {:keys [status tree frontend-metadata]} result
          ;; the row set the evaluator loaders consume from program-out
          final (macro/packet->row-set tree)
          origin [:source :program-in (:t ctx) 0]
          input-tree (vm/ast->semantic-bytecode ast)
          input-tables (vm/ast-side-tables ast input-tree origin)]
      (is (= :ok status))
      (testing "expansion, tail marking and stand-in lowering made a new final tree"
        (is (not= (:root input-tree) (:root final))))
      (testing "the retained table covers exactly the executed tree"
        (is (seq frontend-metadata))
        (is (= (eligible-under final origin) (marked frontend-metadata)))
        (is (true? (yang/validate-call-site-coverage!
                     final frontend-metadata origin)))
        (is (true? (yang/validate-call-site-coverage!
                     final frontend-metadata origin {:declared? true})))
        (is (every? (fn [[o r _ _ _]]
                      (and (= origin o) (= (:root final) r)))
                    frontend-metadata)))
      (testing "the frontend's pre-expansion markers name a tree that no longer exists"
        (is (= :yang/call-site-nonexistent-path
               (failure-rule
                 #(yang/validate-call-site-coverage!
                    final (:frontend-metadata input-tables) origin)))))))
  (testing "the expander consumer the REPL threads retains the executed tree's table"
    (let [store (macro/seed-store
                  (macro/make-ctx {:token :callsite-test/standard-forms
                                   :source-medium :callsite-test/standard-forms})
                  [macro/stdlib-forms])
          ctx (macro/make-ctx {:token "callsite-test"
                               :source-medium :program-in
                               :store store})
          ast (yang/compile-program '[(defn f [x] (+ x 1)) (f 2)])
          consumer (macro/load (macro/make-expander ctx nil)
                               (encoder/program-batch ast))
          final (macro/packet->row-set (:out-staged consumer))
          origin [:source :program-in (:t ctx) 0]
          rows (macro/call-site-table consumer (:out-staged consumer))]
      (is (seq rows))
      (is (= (eligible-under final origin) (marked rows)))
      (is (true? (yang/validate-call-site-coverage!
                   final rows origin
                   {:declared? true}))))))


(deftest successive-expansions-test
  (testing "two loads over different programs: both trees' tables survive"
    (let [store (macro/seed-store
                  (macro/make-ctx {:token :callsite-test/standard-forms
                                   :source-medium :callsite-test/standard-forms})
                  [macro/stdlib-forms])
          ctx (macro/make-ctx {:token "callsite-test"
                               :source-medium :program-in
                               :store store})
          first-origin [:source :program-in (:t ctx) 0]
          consumer (macro/make-expander ctx nil)
          after-first (macro/load consumer
                                  (encoder/program-batch
                                    (yang/compile-program
                                      '[(defn f [x] (+ x 1)) (f 2)])))
          first-tree (:out-staged after-first)
          second-origin [:source :program-in (:t (:ctx after-first)) 0]
          consumer (macro/load after-first
                               (encoder/program-batch
                                 (yang/compile-program
                                   '[(defmacro unless [c a b] (yin/if c b a))
                                     (unless false (+ 1 2) 0)])))
          second-tree (:out-staged consumer)]
      (is (not= (first first-tree) (first second-tree))
          "the two expansions produced different final trees")
      (testing "the first load's table resolves for its tree after the second load"
        (let [rows (macro/call-site-table consumer first-tree)]
          (is (seq rows))
          (is (= (eligible-under (macro/packet->row-set first-tree)
                                 first-origin)
                 (marked rows)))
          (is (true? (yang/validate-call-site-coverage!
                       (macro/packet->row-set first-tree) rows
                       first-origin {:declared? true})))))
      (testing "the second load's table resolves for its tree"
        (let [rows (macro/call-site-table consumer second-tree)]
          (is (seq rows))
          (is (= (eligible-under (macro/packet->row-set second-tree)
                                 second-origin)
                 (marked rows)))
          (is (true? (yang/validate-call-site-coverage!
                       (macro/packet->row-set second-tree) rows
                       second-origin {:declared? true}))))))))


(deftest unclaimed-batch-test
  (testing "a batch whose frontend makes no C1 claim creates no metadata"
    (let [ctx (macro/make-ctx {:token :callsite-test/unclaimed
                               :source-medium :program-in})
          unmarked {:type :application
                    :operator {:type :variable :name 'f}
                    :operands [{:type :literal :value 1}
                               {:type :literal :value 2}]}
          {:keys [status frontend-metadata]}
          (macro/expand-batch (encoder/program-batch unmarked) ctx)
          consumer (macro/load (macro/make-expander ctx nil)
                               (encoder/program-batch unmarked))]
      (is (= :ok status))
      (is (nil? frontend-metadata)
          "no table, never an empty one, for an unclaimed batch")
      (is (empty? (:call-site-tables consumer))
          "the consumer holds no tables")
      (is (nil? (macro/call-site-table consumer (:out-staged consumer)))))))


(def ^:private publish-registry
  "The host registry the published-module test publishes over: the
   `cell` and `data` host modules the `clj` and `yang.call` module
   trees read."
  (-> (module/default-registry)
      module/register-cell-module
      (data/register-data-module {::data/max-items 1048576})))


(deftest published-module-test
  (testing "the markers are retrievable from the published module"
    (let [store (mem/create-content-mem)
          call-result (publish/publish-module!
                        store (call/module-spec publish-registry))
          call-address (:address call-result)
          clj-result (publish/publish-module!
                       store (clj-prelude/module-spec publish-registry
                                                       call-address))
          clj-address (:address clj-result)]
      (is (some? call-address))
      (is (some? clj-address))
      (doseq [[label result address]
              [["yang.call" call-result call-address]
               ["clj" clj-result clj-address]]]
        (testing label
          (is (some? (get-in result [:call-sites :record]))
              "the publication retains its side table beside the manifest")
          (let [{:keys [tree origin rows]}
                (publish/published-call-site-table store address)]
            (is (some? rows)
                "the markers are retrievable from the published module")
            (is (pos? (count rows)))
            (is (= (eligible-under tree origin) (marked rows)))
            (is (true? (yang/validate-call-site-coverage!
                         tree rows origin {:declared? true}))))))))
  (testing "an unannotated module publishes no table and claims none"
    (let [store (mem/create-content-mem)
          plain (publish/publish-module!
                  store {:name 'plain
                         :ast {:type :application
                               :operator {:type :variable :name 'yin/def}
                               :operands [{:type :literal :value 'f}
                                          {:type :literal :value 42}]}
                         :exports #{'f} :requires {} :primitives {}})]
      (is (some? (:address plain)))
      (is (not (contains? plain :call-sites)))
      (is (nil? (publish/published-call-site-table store
                                                    (:address plain))))))
  (testing "the manifest references the side table, so the closure carries it"
    (let [store (mem/create-content-mem)
          result (publish/publish-module!
                   store (clj-prelude/module-spec
                           publish-registry
                           (:address (publish/publish-module!
                                       store
                                       (call/module-spec publish-registry)))))
          address (:address result)
          manifest (jing/get store address nil)
          reference (:yin.module/call-sites manifest)]
      (is (map? reference)
          "the manifest names the side table and its reverse record")
      (is (every? jing/segment-address? ((juxt :table :record) reference)))
      (is (= (:table (:call-sites result)) (:table reference)))
      (is (some? (jing/get store (:table reference) nil)))
      (is (some? (jing/get store (:record reference) nil)))
      ;; transfer: fetch exactly the closure the walk answers missing, as
      ;; a dao.space.dht load does, into a fresh store
      (let [fresh (mem/create-content-mem)]
        (loop [n 0]
          (let [w (closure/walk fresh address)]
            (if (= :missing (:yin.link.closure/outcome w))
              (do (jing/materialize!
                    fresh (jing/get store (:address w) ::absent))
                  (recur (inc n)))
              (do (is (= :complete (:yin.link.closure/outcome w)))
                  (is (pos? n) "the walk fetched the module's blobs")
                  (let [{:keys [tree origin rows]}
                        (publish/published-call-site-table fresh address)]
                    (is (some? rows)
                        "the side table is retrievable from the transfer")
                    (is (= (:rows (publish/published-call-site-table
                                    store address))
                           rows))
                    (is (= (eligible-under tree origin) (marked rows)))
                    (is (true? (yang/validate-call-site-coverage!
                                 tree rows origin {:declared? true})))))))))))
  (testing "a version-2 table is a claim rejection, not a silent nil"
    (let [store (mem/create-content-mem)
          bump (fn bump [node]
                 (if (and (map? node) (= 1 (:yang/call-site node)))
                   (assoc node :yang/call-site 2)
                   node))
          ast (clojure.walk/postwalk bump
                (yang/compile-program '[(defn f [x] (inc x)) (f 2)]))
          res (publish/publish-module!
                store {:name 'bad-version
                       :ast ast
                       :exports #{'f}
                       :requires {}
                       :primitives {'inc {:yin.k/profile :yin.k/pure
                                          :yin.k/effects #{}}}})]
      (is (= :refused (:status res)))
      (is (= :yin.link.publish/unsupported-call-site-version (:reason res)))
      (is (= 2 (:unsupported-version res)))
      (is (nil? (:address res))
          "a claim rejection publishes nothing, so no table exists"))))

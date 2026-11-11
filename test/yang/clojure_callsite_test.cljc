(ns yang.clojure-callsite-test
  "Contract C1 for production yang.clojure (Addendum A, design §2): the
   `:yang/call-site 1` frontend-metadata annotation of every final
   `:application` occurrence, and the complete-coverage validation a
   consumer runs against canonical rows."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [dao.jing.mem :as mem]
            [dao.space.index :as space.index]
            [dao.space.query :as query]
            #?@(:clj [[dao.stream :as stream]
                       [dao.stream.ringbuffer :as ring]])
            [yang.clojure :as yang]
            [yang.clojure.prelude :as clj-prelude]
            [yang.core.call :as call]
            [yang.frontend :as frontend]
            [yang.safepoint :as safepoint]
            [yang.tails :as tails]
            [yin.repl :as repl]
            [yin.repl.frontends :as repl-frontends]
            [yin.repl.store]
            [yin.vm :as vm]
            [yin.vm.data :as data]
            [yin.vm.encoder :as encoder]
            [yin.vm.linker.closure :as closure]
            [dao.stream.apply :as apply2]
            [yin.repl.query :as repl-query]
            [yin.vm.linker.dht :as linker-dht]
            #?(:clj [yin.vm.linker.interpreter :as link])
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


(defn- occurrence-pairs
  "The `[root-address path]` of every `:application` occurrence of
   canonical `tree` — the projection a consumer derives from canonical
   rows alone, origin-free (design §2.2: the origin values of a
   per-occurrence table are the producer's correspondence claim)."
  [tree]
  (into #{}
        (comp (filter (fn [[_ _ id]]
                        (= :application (nth (get (:rows tree) id) 1))))
              (map (fn [[_ path _]] [(:root tree) path])))
        (vm/occurrences tree)))


(defn- marked-pairs
  "The `[root-address path]` projection of a C1 marker relation."
  [metadata]
  (into #{}
        (comp (filter #(= :yang/call-site (nth % 3)))
              (map (fn [[_ r path _ _]] [r path])))
        metadata))


(defn- row-origins
  "The distinct occurrence origins a C1 marker relation carries."
  [metadata]
  (into #{} (map first) (filter #(= :yang/call-site (nth % 3)) metadata)))


(defn- expansion-origin?
  [o]
  (and (vector? o) (= :expansion (first o))))


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
                  tree (into metadata markers) origin {:declared? true})))))
    (testing "a second origin claim for one occurrence is rejected"
      ;; one occurrence has exactly one origin (design §2.1); a second
      ;; version-1 marker with a different, equally well-formed origin
      ;; for an already-covered [root path] is a second claim to one
      ;; fact — the coverage projection on [root path] must not fold it
      (let [other-origin [:source :other-publisher "batch-9" 0]
            double-marked (conj metadata
                                (assoc (first markers)
                                       0 other-origin))]
        (is (= :yang/call-site-multi-claim
               (failure-rule
                 #(yang/validate-call-site-coverage!
                    tree double-marked origin {:declared? true}))))
        (is (= :yang/call-site-multi-claim
               (failure-rule
                 #(yang/validate-call-site-coverage!
                    tree double-marked origin
                    {:per-occurrence-origins? true}))))
        (is (= :yang/call-site-multi-claim
               (failure-rule
                 #(yang/validate-call-site-coverage!
                    tree double-marked origin)))
            "the default mode rejects the second claim with the same
             diagnostic, not a downstream nonexistent-path error")
        (try (yang/validate-call-site-coverage!
               tree double-marked origin {:declared? true})
             (is false "the double marking must fail")
             (catch #?(:cljd Object :clj Exception :cljs :default) e
               (let [d (ex-data e)]
                 (is (= [(:root tree) (nth (first markers) 2)] (:site d))
                     "the diagnostic names the double-claimed occurrence")
                 (is (= #{origin other-origin} (set (:origins d)))
                     "the diagnostic names both origins"))))))))


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
          {:keys [status tree frontend-metadata log]} result
          ;; the row set the evaluator loaders consume from program-out
          final (macro/packet->row-set tree)
          ;; the ADMISSION origin of this expansion — the table's key
          ;; coordinate, never a blanket stamp on the rows (design §2.1:
          ;; generated post-expansion syntax must not be mislabeled as
          ;; original source)
          admission [:source :program-in (:t ctx) 0]
          input-tree (vm/ast->semantic-bytecode ast)
          input-tables (vm/ast-side-tables ast input-tree admission)
          ;; the expansion record addresses the log actually holds: the
          ;; only legitimate `[:expansion a]` origins are these
          event-addrs (into #{} (map first) (nth log 1))
          origins (row-origins frontend-metadata)]
      (is (= :ok status))
      (testing "expansion changed the tree: the `(unless …)` call expanded"
        (is (not= (:root input-tree) (:root final))))
      (testing "the changed root carries no fabricated expansion origin
                (design §2.1: post-expansion names the pipeline
                boundary, not proof that a generating event occurred)"
        (is (seq frontend-metadata))
        (is (every? (fn [o]
                      (or (= o admission)
                          (and (expansion-origin? o)
                               (contains? event-addrs (second o)))))
                    origins)
            "every origin is the admission's source origin or names a
             recorded expansion event — nothing fabricated")
        ;; the `(unless …)` output's generated node is an `:if`, not an
        ;; application, and its `(+ 1 2)` operand is the admitted
        ;; operand passed through — so every APPLICATION here is
        ;; retained admitted syntax and genuinely keeps the source
        ;; origin; the source origin is not a blanket stamp, it is the
        ;; real provenance of each marked occurrence
        (is (= #{admission} origins)
            "no generating event produced an application here, so every
             marked occurrence is admitted"))
      (testing "the retained table covers exactly the executed tree"
        (is (= (occurrence-pairs final) (marked-pairs frontend-metadata)))
        (is (every? (fn [[_ r _ _ _]] (= (:root final) r))
                    frontend-metadata))
        (is (true? (yang/validate-call-site-coverage!
                     final frontend-metadata admission
                     {:declared? true :per-occurrence-origins? true}))))
      (testing "the frontend's pre-expansion markers name a tree that no longer exists"
        (is (= :yang/call-site-nonexistent-path
               (failure-rule
                 #(yang/validate-call-site-coverage!
                    final (:frontend-metadata input-tables) admission))))))
    (testing "a generating expansion marks its generated applications
              with the recorded expansion record's origin (design §2.1,
              §2.3: the correspondence is explicit, never fabricated)"
      (let [ast (yang/compile-program
                  '[(defmacro make-call [x]
                      (yin/application (yin/variable 'f) [x]))
                    (defn f [y] (+ y 1))
                    (make-call 2)])
            {:keys [ctx result]} (expand-like-the-repl ast)
            {:keys [status tree frontend-metadata log]} result
            final (macro/packet->row-set tree)
            admission [:source :program-in (:t ctx) 0]
            event-addrs (into #{} (map first) (nth log 1))
            origins (row-origins frontend-metadata)
            generated (into #{}
                            (comp (filter #(expansion-origin? (first %)))
                                  (map #(vec (take 3 %))))
                            frontend-metadata)]
        (is (= :ok status))
        (is (seq frontend-metadata))
        (is (seq generated)
            "the macro generated an application, and it is marked")
        (is (contains? origins admission)
            "the admitted applications keep the source origin")
        (is (every? (fn [[o _ _]] (contains? event-addrs (second o))) generated)
            "each generated occurrence names a recorded expansion event")
        (is (= (occurrence-pairs final) (marked-pairs frontend-metadata)))
        (is (true? (yang/validate-call-site-coverage!
                     final frontend-metadata admission
                     {:declared? true :per-occurrence-origins? true})))))
    (testing "a no-op expansion retains the admitted source origin"
      ;; no macro call at all: the pipeline boundary alone (tail
      ;; marking) changes the root without a generating event, so the
      ;; final rows legitimately keep the admission's source origin
      (let [ast (yang/compile-program '[(defn f [x] (+ x 1)) (f 2)])
            {:keys [ctx result]} (expand-like-the-repl ast)
            {:keys [status tree frontend-metadata]} result
            final (macro/packet->row-set tree)
            admission [:source :program-in (:t ctx) 0]]
        (is (= :ok status))
        (is (seq frontend-metadata))
        (is (= #{admission} (row-origins frontend-metadata))
            "no generating event occurred, so the source origin remains
             valid for every occurrence (design §2.1)")
        (is (= (occurrence-pairs final) (marked-pairs frontend-metadata)))
        (is (true? (yang/validate-call-site-coverage!
                     final frontend-metadata admission
                     {:declared? true :per-occurrence-origins? true})))))
    (testing "a nested pass-through keeps the operand's own origin"
      ;; design §2.1: a retained occurrence preserves ITS OWN incoming
      ;; provenance. `wrap` generates `(pass x)` retaining the admitted
      ;; `(+ 1 2)`; `pass` is a no-op returning `x` — the `+`
      ;; application must still carry the source origin, never the
      ;; enclosing expansion's origin
      (let [ast (yang/compile-program
                  '[(defmacro pass [x] x)
                    (defmacro wrap [x]
                      (yin/application (yin/variable 'pass) [x]))
                    (wrap (+ 1 2))])
            {:keys [ctx result]} (expand-like-the-repl ast)
            {:keys [status tree frontend-metadata]} result
            final (macro/packet->row-set tree)
            admission [:source :program-in (:t ctx) 0]]
        (is (= :ok status))
        (is (seq frontend-metadata))
        (is (= #{admission} (row-origins frontend-metadata))
            "the admitted `(+ 1 2)` application passed through two
             expansions keeps its source origin — it must not be
             relabeled with any enclosing expansion's origin")
        (is (= (occurrence-pairs final) (marked-pairs frontend-metadata)))
        (is (true? (yang/validate-call-site-coverage!
                     final frontend-metadata admission
                     {:declared? true :per-occurrence-origins? true}))))))
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
          rows (macro/call-site-table consumer (:out-staged consumer) origin)]
      (is (seq rows))
      (is (= (occurrence-pairs final) (marked-pairs rows)))
      (is (true? (yang/validate-call-site-coverage!
                   final rows origin
                   {:declared? true :per-occurrence-origins? true}))))))


(deftest twin-identical-content-distinct-origin-test
  (testing "identical-content applications with different histories are
            distinguishable by query (ruling U1: an occurrence is
            (content address, origin); provenance is read from retained
            history, never inferred from content): an expansion whose
            output contains an admitted `(f 1)` operand AND a freshly
            constructed identical `(f 1)` yields TWO site rows — the
            admitted one with its incoming source origin, the
            constructed one naming its expansion record"
    (let [ast (yang/compile-program
                '[(defmacro twin [x]
                    (yin/if (yin/literal true)
                            x
                            (yin/application (yin/variable 'f) [(yin/literal 1)])))
                  (defn f [y] y)
                  (twin (f 1))])
          {:keys [ctx result]} (expand-like-the-repl ast)
          {:keys [status tree frontend-metadata log]} result
          final (macro/packet->row-set tree)
          admission [:source :program-in (:t ctx) 0]
          event-addrs (into #{} (map first) (nth log 1))
          rows (filterv #(= :yang/call-site (nth % 3)) frontend-metadata)
          ;; the expansion's `:if` output (the run tree's root may carry
          ;; the expanded form as an operand of the program wrapper)
          if-row (some #(when (= :if (nth % 1)) %) (vals (:rows final)))]
      (is (= :ok status))
      (testing "the admitted and the constructed twin share one content address"
        (let [a1 (nth if-row 3)
              a2 (nth if-row 4)]
          (is (= a1 a2)
              "the `if` branches hold the SAME content address — an
               address-set classification could never separate them")
          (is (= (get (:rows final) a1) (get (:rows final) a2)))))
      (let [twin-app (get (:rows final) (nth if-row 3))
            twin-paths (into #{} (comp (filter (fn [[_ _ id]] (= twin-app (get (:rows final) id))))
                                       (map (fn [[_ path _]] path)))
                              (vm/occurrences final))
            twin-origins (into #{} (comp (filter #(contains? twin-paths (nth % 2)))
                                         (map #(nth % 0)))
                               rows)]
        (is (= 2 (count twin-paths))
            "both application occurrences are marked — two occurrences,
             one content address")
        (testing "the site table yields two distinct origins"
          (is (= 2 (count twin-origins)))
          (is (contains? twin-origins admission)
              "the admitted twin keeps its incoming source origin")
          (let [expansion-origins (filterv expansion-origin? (vec twin-origins))]
            (is (= 1 (count expansion-origins))
                "the constructed twin carries exactly one expansion origin")
            (is (contains? event-addrs (second (first expansion-origins)))
                "and it names an expansion event the log actually recorded"))))
      (testing "the expansion record retains the per-occurrence correspondence"
        (let [corr (nth (first (nth log 1)) 11)]
          (is (= {[3] [:retained admission], [4] :constructed} corr)
              "path [3]: the retained operand with its incoming origin;
               path [4]: the expansion's own construction")))
      (is (= (occurrence-pairs final) (marked-pairs frontend-metadata)))
      (is (true? (yang/validate-call-site-coverage!
                   final frontend-metadata admission
                   {:declared? true :per-occurrence-origins? true}))))))


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
        (let [rows (macro/call-site-table consumer first-tree first-origin)]
          (is (seq rows))
          (is (= (occurrence-pairs (macro/packet->row-set first-tree))
                 (marked-pairs rows)))
          (is (true? (yang/validate-call-site-coverage!
                       (macro/packet->row-set first-tree) rows
                       first-origin
                       {:declared? true :per-occurrence-origins? true})))))
      (testing "the second load's table resolves for its tree"
        (let [rows (macro/call-site-table consumer second-tree second-origin)]
          (is (seq rows))
          (is (= (occurrence-pairs (macro/packet->row-set second-tree))
                 (marked-pairs rows)))
          (is (true? (yang/validate-call-site-coverage!
                       (macro/packet->row-set second-tree) rows
                       second-origin
                       {:declared? true :per-occurrence-origins? true}))))))))


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
      (is (nil? (macro/call-site-table consumer (:out-staged consumer)
                                       [:source :program-in 0 0]))))))


(defn- strip-call-sites
  "The map AST with every C1 marker removed — canonical rows are
   invariant under this (canonical-invariance-test), so stripping a
   compiled tree and re-encoding produces the same final tree under a
   no-claim batch."
  [ast]
  (clojure.walk/postwalk #(cond-> % (map? %) (dissoc :yang/call-site)) ast))


(deftest same-root-coexistence-test
  (testing "two loads landing on the SAME final root from different
            admissions: both occurrences' tables coexist, retrievable
            by their origin+root pair (design §2.1, K = [origin root
            path])"
    (let [store (macro/seed-store
                  (macro/make-ctx {:token :callsite-test/standard-forms
                                   :source-medium :callsite-test/standard-forms})
                  [macro/stdlib-forms])
          ctx (macro/make-ctx {:token "callsite-test"
                               :source-medium :program-in
                               :store store})
          ast (yang/compile-program '[(defn f [x] (+ x 1)) (f 2)])
          first-origin [:source :program-in (:t ctx) 0]
          consumer (macro/make-expander ctx nil)
          consumer (macro/load consumer (encoder/program-batch ast))
          first-tree (:out-staged consumer)
          second-origin [:source :program-in (:t (:ctx consumer)) 0]
          consumer (macro/load consumer (encoder/program-batch ast))
          second-tree (:out-staged consumer)]
      ;; the load that exposes the defect: identical programs, so the
      ;; final trees are content-identical — same root
      (is (= (first first-tree) (first second-tree))
          "two admissions produced the same root address")
      (is (not= first-origin second-origin)
          "the two admissions are different occurrence origins")
      (testing "each occurrence's table resolves under its own origin"
        (doseq [[label tree admission]
                [["first" first-tree first-origin]
                 ["second" second-tree second-origin]]]
          (let [rows (macro/call-site-table consumer tree admission)]
            (is (seq rows) (str label " load's table resolves"))
            (is (= (occurrence-pairs (macro/packet->row-set tree))
                   (marked-pairs rows)))
            (is (true? (yang/validate-call-site-coverage!
                         (macro/packet->row-set tree) rows admission
                         {:declared? true :per-occurrence-origins? true})))))
        (is (= 2 (count (:call-site-tables consumer)))
            "two tables, one per occurrence — neither overwrote the other")))
    (testing "a generating program: the two admissions' rows carry
              GENUINELY distinct origins, not the input's source origin"
      (let [store (macro/seed-store
                    (macro/make-ctx {:token :callsite-test/standard-forms
                                     :source-medium :callsite-test/standard-forms})
                    [macro/stdlib-forms])
            ctx (macro/make-ctx {:token "callsite-test"
                                 :source-medium :program-in
                                 :store store})
            ast (yang/compile-program
                  '[(defmacro make-call [x]
                      (yin/application (yin/variable 'f) [x]))
                    (defn f [y] (+ y 1))
                    (make-call 2)])
            first-origin [:source :program-in (:t ctx) 0]
            consumer (macro/make-expander ctx nil)
            consumer (macro/load consumer (encoder/program-batch ast))
            first-tree (:out-staged consumer)
            second-origin [:source :program-in (:t (:ctx consumer)) 0]
            consumer (macro/load consumer (encoder/program-batch ast))
            second-tree (:out-staged consumer)
            first-origins (row-origins
                            (macro/call-site-table consumer first-tree
                                                   first-origin))
            second-origins (row-origins
                             (macro/call-site-table consumer second-tree
                                                    second-origin))]
        (is (= (first first-tree) (first second-tree))
            "two admissions produced the same root address")
        (is (some expansion-origin? first-origins)
            "the generated `(f …)` application carries an expansion origin")
        (is (contains? first-origins first-origin)
            "retained admitted syntax keeps its own admission's source origin")
        (is (empty? (filter second-origins first-origins))
            "the two admissions' occurrence origins are disjoint — the
             expansion records and the source admissions all differ")
        (is (not= first-origins second-origins)
            "not the input's source origin: each admission's rows carry
             that admission's own origins")
        (doseq [[label tree admission]
                [["first" first-tree first-origin]
                 ["second" second-tree second-origin]]]
          (let [rows (macro/call-site-table consumer tree admission)
                final (macro/packet->row-set tree)]
            (is (= (occurrence-pairs final) (marked-pairs rows)))
            (is (true? (yang/validate-call-site-coverage!
                         final rows admission
                         {:declared? true :per-occurrence-origins? true}))
                (str label " load's rows cover its occurrences"))))))))


(deftest unclaimed-after-claimed-test
  (testing "an unclaimed load of a root previously loaded under a claim
            retrieves no stale table"
    (let [store (macro/seed-store
                  (macro/make-ctx {:token :callsite-test/standard-forms
                                   :source-medium :callsite-test/standard-forms})
                  [macro/stdlib-forms])
          ctx (macro/make-ctx {:token "callsite-test"
                               :source-medium :program-in
                               :store store})
          claimed (yang/compile-program '[(defn f [x] (+ x 1)) (f 2)])
          unmarked (strip-call-sites claimed)
          first-origin [:source :program-in (:t ctx) 0]
          consumer (macro/make-expander ctx nil)
          consumer (macro/load consumer (encoder/program-batch claimed))
          first-tree (:out-staged consumer)
          second-origin [:source :program-in (:t (:ctx consumer)) 0]
          consumer (macro/load consumer (encoder/program-batch unmarked))
          second-tree (:out-staged consumer)]
      (is (= (first first-tree) (first second-tree))
          "the unmarked program lands on the claimed load's root")
      (is (seq (macro/call-site-table consumer first-tree first-origin))
          "the claimed occurrence's table is still held under its origin")
      (is (nil? (macro/call-site-table consumer second-tree second-origin))
          "the unclaimed occurrence retrieves no table — not the stale one")
      (is (= 1 (count (:call-site-tables consumer)))))))


(deftest per-member-claim-test
  (testing "every member is validated: a defect in any member refuses
            the batch, however valid its siblings"
    (let [ast (yang/compile '(+ (* 2 3) 4))]
      (testing "a valid member does not short-circuit a later defective one"
        (is (= :yang/call-site-unsupported-version
               (failure-rule
                 #(encoder/program-batch
                    [ast (assoc ast :yang/call-site 2)] 0))))
        (is (= :yang/call-site-incomplete-coverage
               (failure-rule
                 #(encoder/program-batch
                    [ast (update-in ast [:operands 0]
                                    dissoc :yang/call-site)] 0)))))
      (testing "a defective first member is caught too"
        (is (= :yang/call-site-unsupported-version
               (failure-rule
                 #(encoder/program-batch
                    [(assoc ast :yang/call-site 2) ast] 0)))))))
  (testing "the verdicts ride the batch per member"
    (let [ast (yang/compile '(+ (* 2 3) 4))]
      (is (= [true false] (nth (encoder/program-batch
                                 [ast (strip-call-sites ast)] 0) 5))
          "one verdict per member: the marked member claims, the
           unmarked member does not")
      (is (= 5 (count (encoder/program-batch
                        [(strip-call-sites ast) (strip-call-sites ast)] 0)))
          "no member claims: the batch keeps the five-element shape"))))


(deftest per-member-metadata-test
  (testing "a claimed sibling never manufactures a table for an
            unmarked selected member — the selected member's own
            verdict decides its metadata"
    (let [store (macro/seed-store
                  (macro/make-ctx {:token :callsite-test/standard-forms
                                   :source-medium :callsite-test/standard-forms})
                  [macro/stdlib-forms])
          ctx (macro/make-ctx {:token "callsite-test"
                               :source-medium :program-in
                               :store store})
          marked (yang/compile '(+ (* 2 3) 4))
          unmarked (strip-call-sites marked)
          ;; tree 0 claims, tree 1 (the run tree) does not
          batch (encoder/program-batch [marked unmarked] 1)]
      (is (= [true false] (nth batch 5)))
      (let [{:keys [status frontend-metadata]}
            (macro/expand-batch batch ctx)]
        (is (= :ok status))
        (is (nil? frontend-metadata)
            "the unmarked run member's own verdict made no table"))
      (testing "the same batch with the claimed member selected finalizes its table"
        (let [batch (encoder/program-batch [marked unmarked] 0)
              {:keys [status frontend-metadata]}
              (macro/expand-batch batch ctx)]
          (is (= :ok status))
          (is (seq frontend-metadata)))))))


(deftest publish-version-reason-test
  (testing "a present-but-wrong marker version refuses with the
            unsupported-version reason, never incomplete coverage"
    (doseq [bad-value [false nil 0 "1" true]]
      (testing (pr-str bad-value)
        (let [store (mem/create-content-mem)
              ast (assoc (yang/compile-program '[(defn f [x] (inc x)) (f 2)])
                         :yang/call-site bad-value)
              res (publish/publish-module!
                    store {:name 'bad-version-value
                           :ast ast
                           :exports #{'f}
                           :requires {}
                           :primitives {'inc {:yin.k/profile :yin.k/pure
                                              :yin.k/effects #{}}}})]
          (is (= :refused (:status res)))
          (is (= :yin.link.publish/unsupported-call-site-version
                 (:reason res)))
          (is (= bad-value (:unsupported-version res)))
          (is (nil? (:address res))))))))


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
      (doseq [[label result]
              [["yang.call" call-result]
               ["clj" clj-result]]]
        (testing label
          (is (some? (get-in result [:call-sites :record]))
              "the publication retains its side table beside the manifest")
          (let [{:keys [tree origin rows]}
                (publish/published-call-site-table store result)]
            (is (some? rows)
                "the markers are retrievable from the published module")
            (is (pos? (count rows)))
            (is (= (eligible-under tree origin) (marked rows)))
            (is (true? (yang/validate-call-site-coverage!
                         tree rows origin {:declared? true}))))))))
  (testing "an unscoped re-publication of the same specification is
            the same recorded admission (the default admission identity
            is derived from the specification's own durable inputs,
            never a hidden counter), and a retrieval replays the one
            recorded admission it names"
    (let [store (mem/create-content-mem)
          ast (yang/compile-program '[(defn f [x] (inc x)) (f 2)])
          spec {:name 'twin-admissions
                :ast ast
                :exports #{'f}
                :requires {}
                :primitives {'inc {:yin.k/profile :yin.k/pure
                                   :yin.k/effects #{}}}}
          first-result (publish/publish-module! store spec)
          second-result (publish/publish-module! store spec)
          first-address (:address first-result)
          second-address (:address second-result)]
      (is (some? first-address))
      (is (some? second-address))
      (is (= first-address second-address)
          "the idempotent re-publication is the same manifest")
      (testing "identical content, one admission identity"
        (is (= (get-in first-result [:call-sites :root])
               (get-in second-result [:call-sites :root]))
            "the published trees are content-identical")
        (is (= (get-in first-result [:call-sites :origin])
               (get-in second-result [:call-sites :origin]))
            "the default admission identity is a function of the
             specification's durable identity, not of process-local
             mutable state, so no two publishers can unknowingly share
             or split one"))
      (testing "separate admissions of identical trees take explicit
                scopes and stay distinct (design §2.1)"
        (let [a (publish/admission-scope "publisher-a")
              b (publish/admission-scope "publisher-b")
              spec' (assoc spec :name 'twin-admissions-scoped)
              via-a (publish/publish-module!
                      store spec' {:yin.link.publish/scope a})
              via-a-2 (publish/publish-module!
                        store spec' {:yin.link.publish/scope a})
              via-b (publish/publish-module!
                      store spec' {:yin.link.publish/scope b})]
          (is (some? (:address via-a)))
          (is (= (:address via-a) (:address via-b))
              "identical specifications under different admission
               scopes produce the same manifest address: the side
               table is excluded from the hashed manifest")
          (is (not= (get-in via-a [:call-sites :record])
                    (get-in via-b [:call-sites :record]))
              "each admission keeps its own publication record")
          (is (not= (get-in via-a [:call-sites :origin])
                    (get-in via-b [:call-sites :origin]))
              "two independent publisher scopes producing identical
               specs yield distinct admission identities")
          (is (not= (get-in via-a [:call-sites :origin])
                    (get-in via-a-2 [:call-sites :origin]))
              "one scope's two publications are two admissions")
          (is (not= (get-in via-a [:call-sites :table])
                    (get-in via-b [:call-sites :table]))
              "distinct admissions reference distinct tables")))
      (testing "retrieval reads each record's recorded admission"
        (doseq [[label result]
                [["first" first-result]
                 ["second" second-result]]]
          (let [retrieved (publish/published-call-site-table store result)]
            (is (some? retrieved) (str label " table retrieves"))
            (is (= (get-in result [:call-sites :origin]) (:origin retrieved))
                (str label " retrieval answers the recorded admission, "
                     "never a reconstructed one"))
            (is (= (:origin retrieved)
                   (:origin (publish/published-call-site-table store result)))
                (str label " replaying the retrieval replays the same "
                     "recorded admission")))
          (let [{:keys [tree origin rows]}
                (publish/published-call-site-table store result)]
            (is (= (eligible-under tree origin) (marked rows)))
            (is (true? (yang/validate-call-site-coverage!
                         tree rows origin {:declared? true}))))))
      (testing "a composition may pin the admission identity explicitly"
        (let [pinned (publish/publish-module!
                       store spec
                       {:yin.link.publish/admission "pinned-admission"})]
          (is (some? (:address pinned)))
          (is (= [:source :yin.vm.linker.publish/publish
                  "pinned-admission" 0]
                 (get-in pinned [:call-sites :origin]))
              "the pinned token is the admission identity, persisted in
               the record and retrieved from it")
          (is (= [:source :yin.vm.linker.publish/publish
                  "pinned-admission" 0]
                 (:origin (publish/published-call-site-table
                            store pinned))))))))
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
      (is (nil? (publish/published-call-site-table store plain)))))
  (testing "the manifest excludes the side table; the record names the manifest"
    (let [store (mem/create-content-mem)
          result (publish/publish-module!
                   store (clj-prelude/module-spec
                           publish-registry
                           (:address (publish/publish-module!
                                       store
                                       (call/module-spec publish-registry)))))
          address (:address result)
          manifest (jing/get store address nil)
          record-address (get-in result [:call-sites :record])
          record (jing/get store record-address nil)]
      (is (not (contains? manifest :yin.module/call-sites))
          "the hashed manifest keeps the closed schema-1 key set")
      (is (every? jing/segment-address?
                  ((juxt :table :record) (:call-sites result))))
      (is (= address (:yang.call-site/manifest record))
          "the publication record names the manifest")
      (is (= address (get-in result [:call-sites :manifest])))
      (is (= (:yin.module/tree manifest) (:yang.call-site/root record)))
      (is (some? (jing/get store (get-in result [:call-sites :table]) nil)))
      (is (some? record))
      (is (some? (publish/published-call-site-table store record-address))
          "the record address alone retrieves the table")
      (is (nil? (publish/published-call-site-table
                  store (:yin.module/tree manifest) record-address))
          "a record held to a manifest it does not name does not verify")
      ;; transfer: fetch exactly the closure the walk answers missing, as
      ;; a dao.space.dht load does, into a fresh store; the side table
      ;; travels only when its record and table are carried explicitly,
      ;; since the closure no longer follows them
      (let [fresh (mem/create-content-mem)]
        (loop [n 0]
          (let [w (closure/walk fresh address)]
            (if (= :missing (:yin.link.closure/outcome w))
              (do (jing/materialize!
                    fresh (jing/get store (:address w) ::absent))
                  (recur (inc n)))
              (do (is (= :complete (:yin.link.closure/outcome w)))
                  (is (pos? n) "the walk fetched the module's blobs")
                  (is (nil? (publish/published-call-site-table
                              fresh record-address))
                      "the closure alone does not carry the side table")
                  (jing/materialize!
                    fresh (jing/get store (get-in result [:call-sites :table])
                                    ::absent))
                  (jing/materialize! fresh (jing/get store record-address
                                                     ::absent))
                  (let [{:keys [tree origin rows]}
                        (publish/published-call-site-table fresh record-address)]
                    (is (some? rows)
                        "the side table is retrievable once carried")
                    (is (= (:rows (publish/published-call-site-table
                                    store result))
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


(deftest publisher-scope-admission-identity-test
  (testing "two independent publisher scopes producing identical
            specifications still yield distinct admission identities
            (design §2.1): the admission token is drawn from the
            caller-owned scope object — no counter lives in the
            publisher namespace, so two fresh publisher processes (or
            restarts of one) publishing the same specification never
            unknowingly share or split one admission identity"
    (let [ast (yang/compile-program '[(defn f [x] (inc x)) (f 2)])
          spec {:name 'scoped-twin
                :ast ast
                :exports #{'f}
                :requires {}
                :primitives {'inc {:yin.k/profile :yin.k/pure
                                   :yin.k/effects #{}}}}
          ;; two independent publisher processes: two stores, two scopes
          store-a (mem/create-content-mem)
          store-b (mem/create-content-mem)
          scope-a (publish/admission-scope "publisher-a")
          scope-b (publish/admission-scope "publisher-b")
          via-a (publish/publish-module!
                  store-a spec {:yin.link.publish/scope scope-a})
          via-b (publish/publish-module!
                  store-b spec {:yin.link.publish/scope scope-b})]
      (is (some? (:address via-a)))
      (is (some? (:address via-b)))
      (is (= (get-in via-a [:call-sites :root])
             (get-in via-b [:call-sites :root]))
          "the published trees are content-identical")
      (is (not= (get-in via-a [:call-sites :origin])
                (get-in via-b [:call-sites :origin]))
          "identical specs, independent scopes: distinct admissions")
      (doseq [[label result store]
              [["scope-a" via-a store-a] ["scope-b" via-b store-b]]]
        (let [{:keys [tree origin rows]}
              (publish/published-call-site-table store result)]
          (is (some? rows) (str label " table retrieves"))
          (is (= (eligible-under tree origin) (marked rows)))
          (is (true? (yang/validate-call-site-coverage!
                       tree rows origin {:declared? true}))))))))

(defn- correspondences
  "Every expansion correspondence the log recorded."
  [log]
  (map #(nth % 11) (nth log 1)))


(deftest p1-packet-provenance-test
  (testing "packet reassembly retains the incoming origin (P1-1)"
    (let [ast (yang/compile-program
                '[(defmacro repack [x]
                    (conj [] (first x) (first (rest x))))
                  (repack (+ 1 2))])
          {:keys [ctx result]} (expand-like-the-repl ast)
          {:keys [status tree frontend-metadata log]} result
          final (macro/packet->row-set tree)
          admission [:source :program-in (:t ctx) 0]]
      (is (= :ok status))
      (is (contains? (set (correspondences log))
                     {[] [:retained admission]})
          "the reassembled packet records the incoming origin as
           retained, never fabricated construction")
      (is (= #{admission} (row-origins frontend-metadata))
          "the retained application keeps its source origin")
      (is (= (occurrence-pairs final) (marked-pairs frontend-metadata)))
      (is (true? (yang/validate-call-site-coverage!
                   final frontend-metadata admission
                   {:declared? true :per-occurrence-origins? true})))))
  (testing "a reassembled packet inserted as a child retains its origin (P1-1)"
    (let [ast (yang/compile-program
                '[(defmacro repack-call [x]
                    (yin/application (yin/variable 'f)
                                     [(conj [] (first x)
                                            (first (rest x)))]))
                  (defn f [y] y)
                  (repack-call (+ 1 2))])
          {:keys [ctx result]} (expand-like-the-repl ast)
          {:keys [status tree frontend-metadata log]} result
          final (macro/packet->row-set tree)
          admission [:source :program-in (:t ctx) 0]
          event-addrs (into #{} (map first) (nth log 1))]
      (is (= :ok status))
      (is (contains? (set (correspondences log))
                     {[] :constructed, [[3 0]] [:retained admission]})
          "the built call is constructed; the reassembled child keeps
           its incoming origin")
      (is (contains? (row-origins frontend-metadata) admission))
      (is (some (fn [o]
                  (and (expansion-origin? o)
                       (contains? event-addrs (second o))))
                (row-origins frontend-metadata))
          "the built call names its recorded expansion event")
      (is (= (occurrence-pairs final) (marked-pairs frontend-metadata)))
      (is (true? (yang/validate-call-site-coverage!
                   final frontend-metadata admission
                   {:declared? true :per-occurrence-origins? true})))))
  (testing "equal-content operands: reassembly recovers its own occurrence (P1-1)"
    ;; `a` and `b` hold identical row objects (one shared index), but
    ;; `a` retains the source admission while `b` belongs to the
    ;; `feed` expansion; recovering `a`'s origin for `b` is the
    ;; ambiguous row-level match this pin refuses.
    (let [ast (yang/compile-program
                '[(defmacro pick-second [a b]
                    (conj [] (first b) (first (rest b))))
                  (defmacro feed [x]
                    (yin/application (yin/variable 'pick-second)
                      [x (yin/application (yin/variable 'f)
                                           [(yin/literal 1)])]))
                  (defn f [y] y)
                  (feed (f 1))])
          {:keys [ctx result]} (expand-like-the-repl ast)
          {:keys [status tree frontend-metadata log]} result
          final (macro/packet->row-set tree)
          admission [:source :program-in (:t ctx) 0]
          event-addrs (into #{} (map first) (nth log 1))
          corrs (correspondences log)]
      (is (= :ok status))
      (is (some (fn [corr]
                  (let [o (get corr [])]
                    (and (vector? o) (= :retained (first o))
                         (expansion-origin? (second o))
                         (contains? event-addrs (second (second o))))))
                corrs)
          "reassembling b recovers the feed-expansion origin")
      (is (not (contains? (set corrs) {[] [:retained admission]}))
          "never the other occurrence's source admission")
      (is (= (occurrence-pairs final) (marked-pairs frontend-metadata)))
      (is (true? (yang/validate-call-site-coverage!
                   final frontend-metadata admission
                   {:declared? true :per-occurrence-origins? true})))))
  (testing "packet content replacement drops stale tracking metadata (P1-4)"
    ;; `assoc` on packet `a` replaces its contents with `b`'s while the
    ;; host operation retains `a`'s vector metadata; trusting that
    ;; `::origin-of` would record `a`'s source origin for `b`'s
    ;; occurrence. The rebuilt packet must instead recover the feed
    ;; expansion's origin from its actual (root, rows) lineage.
    (let [ast (yang/compile-program
                '[(defmacro pick-second [a b]
                    (assoc a 0 (first b) 1 (first (rest b))))
                  (defmacro feed [x]
                    (yin/application (yin/variable 'pick-second)
                      [x (yin/application (yin/variable 'g)
                                           [(yin/literal 2)])]))
                  (defn f [y] y)
                  (defn g [y] y)
                  (feed (f 1))])
          {:keys [ctx result]} (expand-like-the-repl ast)
          {:keys [status tree frontend-metadata log]} result
          final (macro/packet->row-set tree)
          admission [:source :program-in (:t ctx) 0]
          event-addrs (into #{} (map first) (nth log 1))
          corrs (correspondences log)]
      (is (= :ok status))
      (is (some (fn [corr]
                  (let [o (get corr [])]
                    (and (vector? o) (= :retained (first o))
                         (expansion-origin? (second o))
                         (contains? event-addrs (second (second o))))))
                corrs)
          "replacing a's contents with b's recovers the feed-expansion origin")
      (is (not (contains? (set corrs) {[] [:retained admission]}))
          "never a's stale source admission, carried by retained metadata")
      (is (= (occurrence-pairs final) (marked-pairs frontend-metadata)))
      (is (true? (yang/validate-call-site-coverage!
                   final frontend-metadata admission
                   {:declared? true :per-occurrence-origins? true})))))
  (testing "a packet entering a literal slot encodes cleanly (P1-2)"
    (let [ast (yang/compile-program
                '[(defmacro packet-data [x] (yin/literal x))
                  (packet-data (+ 1 2))])
          {:keys [result]} (expand-like-the-repl ast)
          {:keys [status tree]} result
          payload (some (fn [row]
                          (when (and (= :literal (nth row 1))
                                     (vector? (nth row 2)))
                            (nth row 2)))
                        (second tree))]
      (is (= :ok status)
          "private tracking metadata never reaches the encoder")
      (is (some? payload) "the operand survives as literal data")
      (is (not (contains? (or (meta payload) {})
                          :yin.vm.macro/origin-of))
          "only the private tracking entry is removed")
      (is (nil? (macro/valid-tree? tree))))))


(deftest publisher-scope-id-identity-test
  (testing "scope ids keep their full identity: a keyword's namespace
            and a string's type are part of the id, so :a/publisher,
            :b/publisher and \"publisher\" mint distinct tokens even at
            the same ordinal"
    (let [ast (yang/compile-program '[(defn f [x] (inc x)) (f 2)])
          spec {:name 'scoped-ids
                :ast ast
                :exports #{'f}
                :requires {}
                :primitives {'inc {:yin.k/profile :yin.k/pure
                                   :yin.k/effects #{}}}}
          publish-with-scope
          (fn [id]
            (let [store (mem/create-content-mem)]
              [store (publish/publish-module!
                       store spec {:yin.link.publish/scope
                                   (publish/admission-scope id)})]))
          [_ res-a] (publish-with-scope :a/publisher)
          [_ res-b] (publish-with-scope :b/publisher)
          [_ res-s] (publish-with-scope "publisher")]
      (is (not= (get-in res-a [:call-sites :origin])
                (get-in res-b [:call-sites :origin]))
          ":a/publisher and :b/publisher stay distinct (namespace kept)")
      (is (not= (get-in res-a [:call-sites :origin])
                (get-in res-s [:call-sites :origin]))
          "the keyword and the string of the same name stay distinct"))))


;; =============================================================================
;; cc2: transform & session C1 preservation
;; =============================================================================

(defn- sp-var
  [s]
  {:type :variable :name s})


(defn- sp-app
  [op & args]
  {:type :application :operator op :operands (vec args)})


(defn- sp-lam
  [params body]
  {:type :lambda :params params :body body})


(def ^:private sp-ast
  "`((fn [w] (w w)) (fn [w] (f w)))`, both lambdas site-marked (`:call`,
   `:loop`) and every application C1-marked, tails marked as a frontend
   would."
  (yang/annotate-call-sites
    (tails/mark-tails
      (sp-app (vary-meta (sp-lam '[w] (sp-app (sp-var 'w) (sp-var 'w)))
                         assoc :yang/site :call)
              (vary-meta (sp-lam '[w] (sp-app (sp-var 'f) (sp-var 'w)))
                         assoc :yang/site :loop)))))


(def ^:private sp-profile {:loop 'h/loop :call 'h/call})
(def ^:private sp-origin (vm/source-origin :m [:t 0] 0))
(def ^:private sp-injected-origin [:source :test/publication "safepoint-c1" 0])


(defn- sp-projected
  [ast]
  (encoder/project (encoder/source-envelope :m [:t 0] [ast])))


(defn- entry-set
  [correspondence kind]
  (into #{} (filter #(= kind (:kind %))) (:entries correspondence)))


(deftest safepoint-c1-preservation-test
  (let [input (sp-projected sp-ast)
        input-tree (first (:yin/batch input))
        input-rows (filterv #(= :yang/call-site (nth % 3))
                            (:yin/frontend-metadata input))]
    (is (seq input-rows) "the input envelope carries a C1 claim")
    (testing "without a supplied injected origin, surviving occurrences
              keep their own origin at their shifted paths and injected
              occurrences are identified but unclaimed"
      (let [{:keys [envelope correspondence]}
            (safepoint/derive-envelope input sp-profile :m')
            derived-tree (first (:yin/batch envelope))
            md (:yin/frontend-metadata envelope)
            surviving (entry-set correspondence :surviving)
            injected (entry-set correspondence :safepoint/injected)]
        (is (some? correspondence))
        (is (= (count input-rows) (count surviving))
            "every claimed input occurrence survives (wrapping never drops one)")
        (is (= (into #{} (map :input) surviving)
               (into #{} (map #(vec (take 3 %))) input-rows))
            "the correspondence names every claimed input occurrence")
        (is (= (into #{} (map :output) surviving) (marked md))
            "the derived rows are exactly the surviving occurrences")
        (is (= #{sp-origin} (row-origins md))
            "surviving rows keep the input rows' own origin, none fabricated")
        (is (seq injected) "the hook insertions are recorded as injected")
        (is (every? #(not (contains? % :origin)) injected)
            "with no origin supplied by the caller, none is invented")
        (is (= (into #{} (map (fn [e] [(:root derived-tree) (:path e)])) injected)
               (into #{} (remove (into #{}
                                       (map (fn [[_ r p]] [r p])
                                            (map :output surviving)))
                                 (occurrence-pairs derived-tree))))
            "the unclaimed derived occurrences are exactly the injected ones")
        (testing "canonical invariance: C1 carriage changes no row or root"
          (let [plain (safepoint/derive-envelope
                        (sp-projected (strip-call-sites sp-ast))
                        sp-profile :m')]
            (is (= (:root derived-tree)
                   (:root (first (:yin/batch (:envelope plain))))))
            (is (= derived-tree (first (:yin/batch (:envelope plain)))))))))
    (testing "with a caller-supplied admission origin, the derived table
              covers the derived tree completely and the injected rows
              carry that origin alone"
      (let [{:keys [envelope correspondence]}
            (safepoint/derive-envelope input sp-profile :m'
                                       {:injected-origin sp-injected-origin})
            derived-tree (first (:yin/batch envelope))
            md (:yin/frontend-metadata envelope)
            surviving (entry-set correspondence :surviving)
            injected (entry-set correspondence :safepoint/injected)]
        (is (= (occurrence-pairs derived-tree) (marked-pairs md))
            "the recomputed table covers every derived application")
        (is (true? (yang/validate-call-site-coverage!
                     derived-tree md sp-injected-origin
                     {:declared? true :per-occurrence-origins? true})))
        (is (every? #(= sp-injected-origin (:origin %)) injected))
        (is (= (into #{} (map (fn [e] [(:origin e) (:root derived-tree) (:path e)]))
                     injected)
               (into #{} (filter #(= sp-injected-origin (first %))) (marked md)))
            "the injected rows are exactly the injected correspondence entries")
        (is (= (into #{} (map :output) surviving)
               (into #{} (filter #(= sp-origin (first %))) (marked md)))
            "the surviving rows keep the input origin")))
    (testing "an ambiguous or stale input row is refused attribution,
              never guessed"
      (let [victim (first input-rows)
            other-origin [:source :other-publisher "batch-9" 0]
            bad (assoc input :yin/frontend-metadata
                       (into (:yin/frontend-metadata input)
                             [[other-origin (nth victim 1) (nth victim 2)
                               :yang/call-site 1]
                              [sp-origin (:root input-tree) [99]
                               :yang/call-site 1]]))
            {:keys [envelope correspondence]}
            (safepoint/derive-envelope bad sp-profile :m'
                                       {:injected-origin sp-injected-origin})
            unmapped (entry-set correspondence :unmapped)]
        (is (= #{(vec (take 3 victim))
                 [other-origin (nth victim 1) (nth victim 2)]
                 [sp-origin (:root input-tree) [99]]}
               (into #{} (map :input) unmapped))
            "both claims on the double-claimed occurrence and the stale
             occurrence are unmapped")
        (is (not (contains? (marked (:yin/frontend-metadata envelope))
                            (some #(when (= (vec (take 3 victim)) (:input %))
                                     (:output %))
                                  (entry-set correspondence :surviving))))
            "no surviving row is emitted for the refused occurrence")))))


(deftest encoder-projected-member-test
  (testing "an already-projected {:root :rows} member keeps the
            frontend-metadata its caller supplied, validated like a
            map-AST member's claim"
    (let [ast (yang/compile '(+ (* 2 3) 4))
          tree (vm/ast->semantic-bytecode ast)
          tables (vm/ast-side-tables ast tree origin)
          member (assoc tree :frontend-metadata (:frontend-metadata tables)
                             :source-positions (:source-positions tables))
          projected (encoder/project
                      (encoder/source-envelope :program "batch-1" [member]))]
      (is (= (:frontend-metadata tables) (:yin/frontend-metadata projected))
          "the supplied rows survive projection verbatim")
      (is (= (:root tree) (:root (first (:yin/batch projected))))
          "the canonical root is untouched by the carriage")
      (is (= (:rows tree) (:rows (first (:yin/batch projected))))
          "the canonical rows are untouched by the carriage")))
  (testing "a broken claim on a projected member refuses projection"
    (let [ast (yang/compile '(+ (* 2 3) 4))
          tree (vm/ast->semantic-bytecode ast)
          tables (vm/ast-side-tables ast tree origin)
          rows (:frontend-metadata tables)]
      (is (= :yang/call-site-incomplete-coverage
             (failure-rule
               #(encoder/project
                  (encoder/source-envelope
                    :program "batch-1"
                    [(assoc tree :frontend-metadata (pop rows))])))))
      (is (= :yang/call-site-unsupported-version
             (failure-rule
               #(encoder/project
                  (encoder/source-envelope
                    :program "batch-1"
                    [(assoc tree :frontend-metadata
                            (assoc-in rows [0 4] 2))])))))))
  (testing "a projected member carrying no metadata stays legacy"
    (let [tree (vm/ast->semantic-bytecode (yang/compile '(+ 1 2)))
          projected (encoder/project
                      (encoder/source-envelope :program "batch-1" [tree]))]
      (is (= [] (:yin/frontend-metadata projected)))
      (is (= tree (first (:yin/batch projected)))))))


(defn- session-index
  "Evaluate `lines` in a fresh REPL session; answer `{:state s :db db}`
   with the published index relation (as `publish_test` builds it)."
  [lines]
  (let [state (reduce (fn [s line] (first (repl/eval-input s line)))
                      (repl-frontends/create-state)
                      lines)
        ix (:indexer state)]
    {:state state
     :db (query/relation
           (vec (space.index/read-datoms (:content-store ix)
                                         (:manifest-address ix))))}))


(deftest module-from-index-c1-test
  (let [{:keys [state db]} (session-index ["(def k (fn [] 1))"
                                           "(def g (fn [] (+ (k) 41)))"])
        tables (repl/call-site-tables state)]
    (is (seq tables) "the session expander retains the executed tables")
    (is (= tables (:call-site-tables (:expander state))))
    (testing "with the retained tables, the recovered module claims C1
              and publishes its side table"
      (let [spec (publish/module-from-index
                   db {:name 'closed :exports '[g] :primitives vm/primitives
                       :call-site-tables tables
                       :call-site-admissions (repl/call-site-admissions state)})
            store (mem/create-content-mem)
            res (publish/publish-module! store spec)]
        (is (not= :refused (:status spec)))
        (is (true? (yang/validate-ast-call-sites! (:ast spec) origin))
            "the recovered tree's markers exactly cover its applications")
        (is (jing/segment-address? (:address res)) (pr-str res))
        (is (some? (get-in res [:call-sites :record]))
            "the publication retains the recovered claim's side table")
        (is (seq (:call-site-rows spec))
            "assembly preserves the recovered per-occurrence rows")
        (is (every? (fn [row] (contains? (into #{} (map first)
                                                 (mapcat val tables))
                                           (first row)))
                      (:call-site-rows spec))
            "recovered rows keep their retained origins, none restamped")
        (let [{:keys [tree origin rows]}
              (publish/published-call-site-table store res)
              retained-origins (into #{} (map first) (mapcat val tables))
              wrapper-rows (filterv #(= origin (first %)) rows)
              recovered-rows (filterv #(not= origin (first %)) rows)]
          (is (= (occurrence-pairs tree) (marked-pairs rows))
              "the published table covers the module tree exactly")
          (is (true? (yang/validate-call-site-coverage!
                       tree rows origin {:declared? true
                                         :per-occurrence-origins? true})))
          (is (seq wrapper-rows)
              "the synthesized sequenced wrappers are claimed separately")
          (is (every? #(contains? retained-origins (first %)) recovered-rows)
              "recovered occurrences keep their own admission history")
          (is (= (reduce + (map count (vals tables)))
                 (count recovered-rows))
              "every retained occurrence survives assembly"))))
    (testing "without retained proof, the recovered tree carries no
              minted markers and publishes without a C1 claim"
      (doseq [opts [{:name 'closed :exports '[g] :primitives vm/primitives}
                    {:name 'closed :exports '[g] :primitives vm/primitives
                     :call-site-tables {}}]]
        (let [spec (publish/module-from-index db opts)
              tree (vm/ast->semantic-bytecode (:ast spec))
              tables (vm/ast-side-tables (:ast spec) tree origin)
              res (publish/publish-module! (mem/create-content-mem) spec)]
          (is (empty? (filter #(= :yang/call-site (nth % 3))
                              (:frontend-metadata tables)))
              "no marker is invented for an unproven recovery")
          (is (jing/segment-address? (:address res)))
          (is (not (contains? res :call-sites))
              "the publication makes no C1 claim"))))))


(deftest repl-call-site-surface-test
  (testing "render-compile-output surfaces the expansion's call sites
            when non-empty, and only then"
    (let [store (macro/seed-store
                  (macro/make-ctx {:token :callsite-test/standard-forms
                                   :source-medium :callsite-test/standard-forms})
                  [macro/stdlib-forms])
          ctx (macro/make-ctx {:token "callsite-test"
                               :source-medium :program-in
                               :store store})
          state {:expander {:ctx ctx}}
          ast (yang/compile '(+ (* 2 3) 4))]
      (is (str/includes? (#'repl/render-compile-output state ast)
                         "Call sites")
          "a claimed expansion renders its side table")
      (is (not (str/includes?
                 (#'repl/render-compile-output state (strip-call-sites ast))
                 "Call sites"))
          "an unclaimed expansion renders none")))
  (testing "the link context carries the expander's retained tables"
    (let [tables {[origin :segment/root] []}]
      (is (= tables (:call-site-tables
                      (#'repl/link-context
                        {:expander {:call-site-tables tables}}
                        {:primitives {} :modules {}})))))))

;; =============================================================================
;; cc2 fix round r2: adversarial cases (astra review)
;; =============================================================================

(defn- tag-at
  "The row tag at `path` of canonical `tree`, derived from the occurrence
   relation alone — independent of any producer's correspondence claim."
  [tree path]
  (some (fn [[r p id]]
          (when (and (= r (:root tree)) (= p path))
            (nth (get (:rows tree) id) 1)))
        (vm/occurrences tree)))


(def ^:private exit-site-ast
  "A root lambda marked `[:call :tag]` whose body is `(f)`."
  (yang/annotate-call-sites
    (vary-meta (sp-lam '[x] (sp-app (sp-var 'f)))
               assoc :yang/site [:call :tag])))


(def ^:private nested-exit-site-ast
  "An exit-marked lambda whose body applies an exit-marked inner lambda:
   `(fn [x] ((fn [y] (g))))`, both marked `[:call :tag]`."
  (yang/annotate-call-sites
    (vary-meta (sp-lam '[x] (sp-app (vary-meta (sp-lam '[y] (sp-app (sp-var 'g)))
                                               assoc :yang/site [:call :tag])))
               assoc :yang/site [:call :tag])))


(deftest safepoint-exit-hook-c1-test
  (testing "exit-only: hook construction prepends the mark's literals, so
            the body application sits at operand 1, not operand 0"
    (let [input (sp-projected exit-site-ast)
          {:keys [envelope correspondence]}
          (safepoint/derive-envelope input {:return {:hook 'h/return
                                                      :operands true}}
                                     :m' {:injected-origin sp-injected-origin})
          derived-tree (first (:yin/batch envelope))
          md (:yin/frontend-metadata envelope)
          surviving (entry-set correspondence :surviving)
          wrong [3 [3 0]]
          right [3 [3 1]]]
      (is (= :literal (tag-at derived-tree wrong))
          "the independently computed operand-0 coordinate is the literal")
      (is (= :application (tag-at derived-tree right))
          "the body application is at operand 1")
      (is (= #{[sp-origin (:root (first (:yin/batch input))) [3]]}
             (into #{} (map :input) surviving)))
      (is (= #{[sp-origin (:root derived-tree) right]}
             (into #{} (map :output) surviving))
          "the surviving occurrence is tracked to the body application")
      (is (not (contains? (marked-pairs md) [(:root derived-tree) wrong]))
          "no row claims the literal's place as the surviving call")
      (is (= (occurrence-pairs derived-tree) (marked-pairs md))
          "the derived table covers the derived tree exactly")))
  (testing "entry+exit: the body shifts under both insertions"
    (let [input (sp-projected exit-site-ast)
          {:keys [envelope correspondence]}
          (safepoint/derive-envelope
            input {:call {:hook 'h/enter :operands true}
                   :return {:hook 'h/return :operands true}}
            :m' {:injected-origin sp-injected-origin})
          derived-tree (first (:yin/batch envelope))
          surviving (entry-set correspondence :surviving)
          injected (entry-set correspondence :safepoint/injected)]
      (is (= #{[sp-origin (:root derived-tree) [3 2 3 [3 1]]]}
             (into #{} (map :output) surviving)))
      (is (= #{[3] [3 [3 0]] [3 2 3]}
             (into #{} (map :path) injected))
          "sequencing, entry hook and exit hook are the injected apps")
      (is (= (occurrence-pairs derived-tree)
             (marked-pairs (:yin/frontend-metadata envelope))))))
  (testing "nested marks: each lambda's exit shift composes"
    (let [input (sp-projected nested-exit-site-ast)
          {:keys [envelope correspondence]}
          (safepoint/derive-envelope input {:return {:hook 'h/return
                                                      :operands true}}
                                     :m' {:injected-origin sp-injected-origin})
          derived-tree (first (:yin/batch envelope))
          surviving (entry-set correspondence :surviving)]
      (is (= {[3] [3 [3 1]]
              [3 2 3] [3 [3 1] 2 3 [3 1]]}
             (into {} (map (fn [e] [(nth (:input e) 2) (nth (:output e) 2)]))
                     surviving))
          "the outer application and the inner (g) track through both wraps")
      (is (= (occurrence-pairs derived-tree)
             (marked-pairs (:yin/frontend-metadata envelope)))))))


(deftest safepoint-correspondence-refusal-test
  (testing "a mapped output place that is not an application is refused
            as :unmapped — asserted by the positive :unmapped entry"
    (let [input (sp-projected exit-site-ast)
          tree (first (:yin/batch input))
          hooks (#'safepoint/selected-hooks
                  (safepoint/sites (:yin/frontend-metadata input)
                                   sp-origin (:root tree))
                  {:return {:hook 'h/return :operands true}})
          ;; the derived tree handed to derivation is the UNTRANSFORMED
          ;; tree: tracking maps the body to [3 [3 1]], which names no
          ;; application there, so the correspondence must be refused
          {:keys [metadata correspondence]}
          (#'safepoint/derive-call-sites (:yin/frontend-metadata input)
                                         tree tree hooks
                                         sp-injected-origin)
          unmapped (entry-set correspondence :unmapped)]
      (is (= #{{:kind :unmapped
                :input [sp-origin (:root tree) [3]]
                :reason :output-not-an-application}}
             unmapped)
          "the refusal itself is present, not merely a missing row")
      (is (empty? (filter #(= sp-origin (first %)) metadata))
          "and no surviving row is emitted for the refused occurrence")))
  (testing "an invalid injected origin is refused"
    (is (= :origin
           (failure-rule
             #(safepoint/derive-envelope
                (sp-projected exit-site-ast)
                {:return {:hook 'h/return :operands true}} :m'
                {:injected-origin [:bad 1]}))))))


(deftest encoder-projected-claim-shape-test
  (let [ast (yang/compile '(+ (* 2 3) 4))
        tree (vm/ast->semantic-bytecode ast)
        rows (:frontend-metadata (vm/ast-side-tables ast tree origin))
        project-with (fn [rs]
                       #(encoder/project
                          (encoder/source-envelope
                            :program "batch-1"
                            [(assoc tree :frontend-metadata rs)])))]
    (testing "a nil origin is refused (the review's sole-application row)"
      (is (= :yang/call-site-invalid-origin
             (failure-rule (project-with (assoc-in rows [0 0] nil))))))
    (testing "a duplicate row is refused"
      (is (= :yang/call-site-duplicate-entry
             (failure-rule (project-with (conj rows (first rows)))))))
    (testing "two origins claiming one application are refused"
      (is (= :yang/call-site-multi-claim
             (failure-rule
               (project-with
                 (conj rows (assoc (first rows)
                                   0 [:source :other "batch-9" 0])))))))
    (testing "an extra field is refused"
      (is (= :yang/call-site-malformed
             (failure-rule (project-with (conj rows (conj (first rows)
                                                          :extra)))))))))


(deftest module-from-index-c1-adversarial-test
  (let [{:keys [state db]} (session-index ["(def k (fn [] 1))"
                                           "(def g (fn [] (+ (k) 41)))"])
        tables (repl/call-site-tables state)
        claim-free? (fn [spec]
                      (let [tree (vm/ast->semantic-bytecode (:ast spec))
                            side (vm/ast-side-tables (:ast spec) tree origin)
                            res (publish/publish-module!
                                  (mem/create-content-mem) spec)]
                        (and (empty? (filter #(= :yang/call-site (nth % 3))
                                             (:frontend-metadata side)))
                             (not (contains? spec :call-site-rows))
                             (jing/segment-address? (:address res))
                             (not (contains? res :call-sites)))))]
    (testing "stale-admission tables (rekeyed under another admission)
              prove nothing for the indexed programs"
      (let [stale (into {}
                        (map (fn [[[_o root] rs]]
                               [[[:source "stale-admission" 99 0] root] rs]))
                        tables)
            spec (publish/module-from-index
                   db {:name 'closed :exports '[g] :primitives vm/primitives
                       :call-site-tables stale
                       :call-site-admissions (repl/call-site-admissions state)})]
        (is (claim-free? spec)
            "identical content under a different admission is not borrowed")))
    (testing "a wrong-version retained table proves nothing"
      (let [bad (into {}
                      (map-indexed (fn [i [k rs]]
                                     [k (if (zero? i)
                                          (assoc-in rs [0 4] 2)
                                          rs)])
                      tables))
            spec (publish/module-from-index
                   db {:name 'closed :exports '[g] :primitives vm/primitives
                       :call-site-tables bad
                       :call-site-admissions (repl/call-site-admissions state)})]
        (is (claim-free? spec))))
    (testing "an incomplete retained table proves nothing"
      (let [bad (into {}
                      (map-indexed (fn [i [k rs]]
                                     [k (if (zero? i) (pop rs) rs)])
                      tables))
            spec (publish/module-from-index
                   db {:name 'closed :exports '[g] :primitives vm/primitives
                       :call-site-tables bad
                       :call-site-admissions (repl/call-site-admissions state)})]
        (is (claim-free? spec))))))


(deftest repl-query-link-forwarding-test
  (testing "yin.repl.query/link-answer forwards the link context's
            :call-site-tables to publish-name!"
    (let [{:keys [state]} (session-index ["(def k (fn [] 1))"])
          tables (repl/call-site-tables state)
          captured (atom nil)]
      (with-redefs [linker-dht/publish-name!
                    (fn [_node _db opts]
                      (reset! captured opts)
                      {:status :ok :address :segment/test
                       :links {} :envelopes []})]
        (let [[_ _ response]
              (#'repl-query/link-answer
                {} (:indexer state)
                {:authority nil :key {} :primitives vm/primitives
                 :modules {} :call-site-tables tables :round 1}
                (apply2/request 1 (get repl-query/link-ops 'publish)
                                ['closed '[k]]))]
          (is (some? response))
          (is (= tables (:call-site-tables @captured))
              "ordinary yin.link/publish now carries the retained tables"))))))

;; =============================================================================
;; cc2 fix round r3: admission identity + explicit wrappers (astra r2 confirm)
;; =============================================================================

(defn- r3-claim-free?
  "True when `spec` carries no C1 claim and publishes with no side table."
  [spec]
  (let [tree (vm/ast->semantic-bytecode (:ast spec))
        side (vm/ast-side-tables (:ast spec) tree origin)
        res (publish/publish-module! (mem/create-content-mem) spec)]
    (and (empty? (filter #(= :yang/call-site (nth % 3))
                         (:frontend-metadata side)))
         (not (contains? spec :call-site-rows))
         (jing/segment-address? (:address res))
         (not (contains? res :call-sites)))))

(defn- r3-programs
  "The indexed programs of `db` as `[m root t]` rows."
  [db]
  (query/collect
    (query/q '[:find ?m ?r ?t :where [?m :yin.repl/root ?r ?t _]] db)))

(deftest module-from-index-c1-r3-test
  (let [{:keys [state db]} (session-index ["(def k (fn [] 1))"
                                           "(def g (fn [] (+ (k) 41)))"])
        tables (repl/call-site-tables state)
        admissions (repl/call-site-admissions state)
        programs (r3-programs db)
        spec-of (fn [ts ads]
                  (publish/module-from-index
                    db {:name 'closed :exports '[g] :primitives vm/primitives
                        :call-site-tables ts :call-site-admissions ads}))]
    (is (seq admissions)
        "the session records an explicit indexed-program admission mapping")
    (is (every? (fn [[m o]] (and (contains? (set (map first programs)) m)
                                 (vm/occurrence-origin? o)))
                  admissions)
        "the mapping is keyed by indexed program, valued by full origin")
    (testing "(a) a sole foreign table with the same root and batch but a
              different medium/member is not accepted"
      (let [foreign (into {}
                          (map (fn [[[o root] rs]]
                                 [[[:source "foreign-medium" (nth o 2) 99]
                                   root] rs]))
                          tables)]
        (is (r3-claim-free? (spec-of foreign admissions))
            "counter equality with a foreign admission proves nothing")))
    (testing "(b) the drift collision publishes claim-free, not borrowed:
              a second admission of one root at batch+1, whose indexed
              program carries the first admission's batch as its t, and
              for which no explicit mapping is supplied"
      (let [[[o root] rows] (first tables)
            m (some (fn [[m r _t]] (when (= r root) m)) programs)
            drifted (assoc tables [[:source (nth o 1) (inc (nth o 2))
                                    (nth o 3)] root] rows)
            ads (dissoc admissions m)]
        (is (some? m))
        (is (r3-claim-free? (spec-of drifted ads))
            "the drifted program's table is never borrowed from batch t")))
    (testing "(b2) an explicit mapping resolves exactly despite drift:
              the mapped origin's batch differs from the program's t"
      (let [[[o root] rows] (first tables)
            [m _r t] (some (fn [[m r t]] (when (= r root) [m r t])) programs)
            b-origin [:source (nth o 1) (inc (nth o 2)) (nth o 3)]
            b-rows (assoc-in (vec rows) [0 0] b-origin)
            drifted (assoc tables [b-origin root] b-rows)
            spec (spec-of drifted (assoc admissions m b-origin))]
        (is (not= (inc (nth o 2)) t) "precondition: mapped batch is not t")
        (is (seq (:call-site-rows spec)))
        (is (some #(= b-origin (first %)) (:call-site-rows spec))
            "resolution followed the explicit [origin root] key, not t")))
    (testing "(c) a deleted retained row is refused at publication, not
              reassigned to the publication origin"
      (let [spec (spec-of tables admissions)
            deleted (first (:call-site-rows spec))
            tampered (update spec :call-site-rows (fn [rs] (vec (rest rs))))
            res (publish/publish-module! (mem/create-content-mem) tampered)]
        (is (seq (:call-site-rows spec)))
        (is (= :refused (:status res)) (pr-str res))
        (is (= :yin.link.publish/incomplete-call-site-coverage (:reason res)))
        (is (contains? (set (:missing res)) [(nth deleted 1) (nth deleted 2)])
            "the refusal names the deleted occurrence as unmapped")))
    (testing "a tampered wrapper set is refused: the wrapper applications
              are the assembly's explicit set, not every uncovered site"
      (let [spec (spec-of tables admissions)
            tampered (update spec :call-site-wrappers pop)
            res (publish/publish-module! (mem/create-content-mem) tampered)]
        (is (seq (:call-site-wrappers spec))
            "assembly emits the explicit wrapper occurrence set")
        (is (= :refused (:status res)) (pr-str res))))
    (testing "(d) []/nil supplied rows publish claim-free, not fully claimed"
      (let [spec (spec-of tables admissions)]
        (doseq [empty-rows [[] nil]]
          (let [res (publish/publish-module!
                      (mem/create-content-mem)
                      (assoc spec :call-site-rows empty-rows))]
            (is (jing/segment-address? (:address res)) (pr-str res))
            (is (not (contains? res :call-sites))
                "an empty supplied table claims no application")))))
    (testing "(d2) []/nil supplied tables publish claim-free from the index"
      (doseq [ts [[] nil {}]]
        (is (r3-claim-free? (spec-of ts admissions)))))))


;; =============================================================================
;; cc2 fix round r4: commit-evidence recorder + wrapper provenance (astra r3)
;; =============================================================================

(defn- r4-record
  "Run the session recorder over a crafted state: `ix` is the indexer's
   post-round value, `evidence` the commit evidence the round captured
   before indexing (`:programs-before`, `:indexer-before`)."
  [ix evidence crafted-tables]
  (#'repl/record-call-site-admissions
    {}
    {:expander {:call-site-tables crafted-tables}
     :call-site-admissions {}
     :indexer ix
     :call-site-commit-evidence evidence}))

(deftest record-call-site-admissions-r4-test
  (let [{:keys [state db]} (session-index ["(def k (fn [] 1))"])
        tables (repl/call-site-tables state)
        [[o root] rows] (first tables)
        ix (:indexer state)
        [m-a] (some (fn [[m r _t]] (when (= r root) [m])) (r3-programs db))
        b-origin [:source (nth o 1) 999 (nth o 3)]
        crafted {[b-origin root] rows}
        ok-before (assoc ix :transactions 0 :published 0)]
    (is (some? m-a) "precondition: the session indexed one program")
    (testing "(r4-1) a stale published snapshot after a failed index round
              is not paired: publication failure leaves the old manifest,
              and recording is gated on the round having succeeded"
      (let [failed-ix (assoc ix :failure {:round 99 :stage :publish
                                          :message "simulated failure"})
            result (r4-record failed-ix
                              {:programs-before [] :indexer-before ok-before}
                              crafted)]
        (is (= {} (:call-site-admissions result))
            "no admission is recorded against the stale snapshot")
        (is (r3-claim-free?
              (publish/module-from-index
                db {:name 'closed :exports '[k] :primitives vm/primitives
                    :call-site-tables crafted
                    :call-site-admissions (:call-site-admissions result)}))
            "the unpaired admission publishes claim-free")))
    (testing "(r4-3) a rehydrated unmapped program is pre-existing index
              evidence, not this round's commit: no pairing, claim-free"
      (let [result (r4-record ix
                              {:programs-before [[m-a root]]
                               :indexer-before ix}
                              crafted)]
        (is (= {} (:call-site-admissions result))
            "the rehydrated program is never claimed by a new admission")
        (is (r3-claim-free?
              (publish/module-from-index
                db {:name 'closed :exports '[k] :primitives vm/primitives
                    :call-site-tables crafted
                    :call-site-admissions (:call-site-admissions result)}))
            "a rehydrated session recovers claim-free, never by borrowing")))
    (testing "(r4-2) multiple new admissions competing for one newly
              committed candidate refuse all of them, never first-wins"
      (let [c-origin [:source (nth o 1) 1000 (nth o 3)]
            competing (assoc crafted [c-origin root] rows)
            result (r4-record ix
                              {:programs-before [] :indexer-before ok-before}
                              competing)]
        (is (= {} (:call-site-admissions result))
            "neither competing admission claims the sole candidate")))
    (testing "(r4-2b) multiple newly committed candidates for one new
              admission refuse it: uniqueness is required on both sides"
      (let [{state2 :state db2 :db}
            (session-index ["(def k (fn [] 1))" "(def k (fn [] 1))"])
            roots2 (into #{} (map second) (r3-programs db2))
            result (r4-record (:indexer state2)
                              {:programs-before []
                               :indexer-before (assoc (:indexer state2)
                                                      :transactions 0
                                                      :published 0)}
                              {[b-origin (first roots2)] rows})]
        (is (= 1 (count roots2)) "precondition: both rounds share one root")
        (is (= {} (:call-site-admissions result))
            "an admission with two committed candidates stays unmapped")))))

(deftest wrapper-provenance-r4-test
  (let [{:keys [state db]} (session-index ["(def k (fn [] 1))"
                                           "(def g (fn [] (+ (k) 41)))"])
        spec (publish/module-from-index
               db {:name 'closed :exports '[g] :primitives vm/primitives
                   :call-site-tables (repl/call-site-tables state)
                   :call-site-admissions (repl/call-site-admissions state)})]
    (is (seq (:call-site-rows spec)) "precondition: assembly recovered rows")
    (is (some? (:call-site-wrappers-evidence spec))
        "assembly retains its computed wrapper set as spec evidence")
    (testing "(r4-4) a recovered user occurrence moved into the wrapper
              set is refused as wrapper provenance, not reassigned to the
              publication origin"
      (let [deleted (first (:call-site-rows spec))
            moved (-> spec
                      (update :call-site-rows (fn [rs] (vec (rest rs))))
                      (update :call-site-wrappers
                              conj [(nth deleted 1) (nth deleted 2)]))
            res (publish/publish-module! (mem/create-content-mem) moved)]
        (is (= :refused (:status res)) (pr-str res))
        (is (= :yin.link.publish/wrapper-provenance (:reason res))
            "the refusal names wrapper provenance as the defect")))
    (testing "(r4-4b) a caller-supplied wrapper set without retained
              assembly evidence is never accepted"
      (let [res (publish/publish-module!
                  (mem/create-content-mem)
                  (dissoc spec :call-site-wrappers-evidence))]
        (is (= :refused (:status res)) (pr-str res))
        (is (= :yin.link.publish/wrapper-provenance (:reason res)))))))


(deftest published-instruction-provenance-test
  ;; cc3 item 3: when a publication claims C1, the instruction
  ;; provenance of all three images is persisted as a side artifact
  ;; OUTSIDE the schema-1 manifest hash, retrievable alongside the
  ;; images; an unclaimed publication persists none.
  (let [store (mem/create-content-mem)
        ast (yang/compile-program '[(defn f [x] (inc x)) (f 2)])
        spec {:name 'cc3-provenance
              :ast ast
              :exports #{'f}
              :requires {}
              :primitives {'inc {:yin.k/profile :yin.k/pure
                                 :yin.k/effects #{}}}}
        result (publish/publish-module! store spec)
        table (publish/published-call-site-table store result)
        prov (publish/published-instruction-provenance store result)]
    (is (some? (:address result)))
    (is (some? prov) "the claimed publication persists instruction provenance")
    (is (= (:root table) (:root prov)))
    (testing "every persisted row resolves to a claimed occurrence key"
      (doseq [lane [:semantic :stack :register]
              [_seg _pc o r p] (get prov lane)]
        (is (contains? (marked (:rows table)) [o r p])
            (str lane " row names a claimed C1 occurrence"))))
    (testing "all three lanes are present and non-empty where calls exist"
      (is (seq (:semantic prov)))
      (is (seq (:stack prov)))
      (is (seq (:register prov))))
    (testing "the manifest stays the closed schema-1 key set"
      (is (not (contains? (:manifest result) :yang.call-site/provenance)))
      (is (= 1 (:yin.module/schema (:manifest result))))))
  (testing "legacy unannotated packets remain fully loadable"
    (let [store (mem/create-content-mem)
          result (publish/publish-module!
                   store {:name 'cc3-legacy
                          :ast {:type :lambda :params '[x]
                                :body {:type :variable :name 'x}}
                          :exports #{}
                          :requires {}
                          :primitives {}})]
      (is (some? (:address result)))
      (is (nil? (publish/published-instruction-provenance store result))))))


(deftest repl-packet-call-provenance-test
  ;; cc3 r2 item 3 (repl lane): provenance is selected by the packet's
  ;; retained admission identity `[admission root]`, never by root
  ;; alone; an admission with no claim for the root yields absence even
  ;; when another admission claimed identical canonical content.
  (let [{:keys [ctx result]} (expand-like-the-repl
                               (yang/compile-program
                                 '[(defn f [x] (+ x 1)) (f 2)]))
        {:keys [status tree frontend-metadata]} result
        packet tree
        admission [:source :program-in (:t ctx) 0]
        other [:source :program-in :other-admission 0]
        state {:expander {:call-site-tables {[admission (first packet)]
                                             frontend-metadata}}}]
    (is (= :ok status))
    (is (seq frontend-metadata) "precondition: the expansion claimed C1")
    (let [prov (repl/packet-call-provenance state packet admission)]
      (is (some? prov))
      (is (seq (:semantic prov)))
      (doseq [[_seg _pc o r _p] (:semantic prov)]
        (is (= (first packet) r))
        (is (= admission o))))
    (is (nil? (repl/packet-call-provenance {:expander {}} packet admission))
        "no retained table: absence, never invention")
    (testing "claimed-A / unclaimed-B with an identical root: B gets absence"
      (is (nil? (repl/packet-call-provenance state packet other))
          "B never borrows A's table through the shared root"))
    (testing "two retained admissions for one root each resolve their own"
      (let [other-rows (mapv (fn [row] (assoc row 0 other)) frontend-metadata)
            both (assoc-in state [:expander :call-site-tables
                                  [other (first packet)]]
                           other-rows)
            prov-b (repl/packet-call-provenance both packet other)]
        (is (some? prov-b))
        (is (every? (fn [[_seg _pc o _r _p]] (= other o)) (:semantic prov-b)))))
    (testing "a malformed admission identity refuses"
      (is (= :call-site-admission
             (failure-rule
               #(repl/packet-call-provenance state packet [:oracle/fixture 1])))))))


#?(:clj
   (deftest repl-packet-provenance-survives-failure-test
     ;; cc3 r3 item F4: a claimed program that throws after loading
     ;; has already persisted its provenance relation; the lookup
     ;; handle completed before the throw must survive the
     ;; exception/recovery path and stay retrievable after reopening.
     (let [dir (str "target/test-cc3-provenance-fail-" (random-uuid))
           cleanup (fn [] (let [f (java.io.File. dir)]
                            (when (.exists f)
                              (run! #(.delete %) (reverse (file-seq f))))))]
       (try
         (let [state (reduce (fn [s line] (first (repl/eval-input s line)))
                             (repl-frontends/create-state
                               {:index-store-spec {:type :file :dir dir}})
                             ["(def k (fn [] 1))"
                              "(def g (fn [] (+ (k) 41)))"])
               handles-before (repl/call-provenance-handles state)
               [state' failed] (repl/eval-input state "(+ (g) (/ 1 0))")
               handles (repl/call-provenance-handles state')]
           (is (seq handles-before))
           (is (str/includes? failed "Error: ")
               "precondition: the claimed program really threw")
           (is (< (count handles-before) (count handles))
               "the throwing round's completed retention is not dropped")
           (let [new-handles (into {}
                                   (remove (fn [[k _]]
                                             (contains? handles-before k)))
                                   handles)
                 [[admission root] handle before]
                 (first (keep (fn [[[a r] h]]
                                (let [rel (repl/retained-packet-call-provenance
                                            (:index-store state') h)]
                                  (when (seq (:semantic rel))
                                    [[a r] h rel])))
                              new-handles))]
             (is (some? before)
                 "the throwing round retained a retrievable relation")
             (is (= root (:root before)))
             (is (= admission (:admission before)))
             (yin.repl.store/close! (:index-store state'))
             (let [reopened (yin.repl.store/open {:type :file :dir dir})
                   after (repl/retained-packet-call-provenance
                           reopened handle)]
               (is (= before after)
                   "the throwing round's handle retrieves after reopening")
               (yin.repl.store/close! reopened))))
         (finally (cleanup))))))


#?(:clj
   (deftest repl-packet-provenance-survives-link-resume-failure-test
     ;; cc3 r4 item F4: a claimed program that returns from the
     ;; evaluator stage waiting on a query, then THROWS when
     ;; drive-links resumes it, has already persisted its provenance
     ;; relation; the lookup handle completed before the resume must
     ;; survive the link-raise recovery path and stay retrievable
     ;; after reopening.
     (let [dir (str "target/test-cc3-provenance-resume-" (random-uuid))
           cleanup (fn [] (let [f (java.io.File. dir)]
                            (when (.exists f)
                              (run! #(.delete %) (reverse (file-seq f))))))]
       (try
         (let [state (reduce (fn [s line] (first (repl/eval-input s line)))
                             (repl-frontends/create-state
                               {:index-store-spec {:type :file :dir dir}})
                             ["(def k (fn [] 1))"
                              "(def g (fn [] (+ (k) 41)))"
                              "(require (quote dao.space.query))"])
               handles-before (repl/call-provenance-handles state)
               [state' failed]
               (repl/eval-input
                 state
                 (str "(let [r (dao.space.query/q "
                      "(quote [:find (count ?e) . "
                      ":where [?e :yin/name ?n]]))] (/ 1 0))"))
               handles (repl/call-provenance-handles state')]
           (is (seq handles-before))
           (is (str/includes? failed "Error: ")
               "precondition: the resumed program really threw")
           (is (< (count handles-before) (count handles))
               "the resume-failing round's completed retention is not dropped")
           (let [new-handles (into {}
                                   (remove (fn [[k _]]
                                             (contains? handles-before k)))
                                   handles)
                 [[admission root] handle before]
                 (first (keep (fn [[[a r] h]]
                                (let [rel (repl/retained-packet-call-provenance
                                            (:index-store state') h)]
                                  (when (seq (:semantic rel))
                                    [[a r] h rel])))
                              new-handles))]
             (is (some? before)
                 "the resume-failing round retained a retrievable relation")
             (is (= root (:root before)))
             (is (= admission (:admission before)))
             (yin.repl.store/close! (:index-store state'))
             (let [reopened (yin.repl.store/open {:type :file :dir dir})
                   after (repl/retained-packet-call-provenance
                           reopened handle)]
               (is (= before after)
                   "the resume-failing round's handle retrieves after reopening")
               (yin.repl.store/close! reopened))))
         (finally (cleanup))))))


#?(:clj
   (deftest repl-packet-provenance-durable-test
     ;; cc3 r2 item 4: ordinary REPL loading retains admission-scoped
     ;; provenance in the session's durable store under a lookup handle
     ;; the session state carries; the relation is retrievable from a
     ;; freshly reopened store by that handle alone.
     (let [dir (str "target/test-cc3-provenance-" (random-uuid))
           cleanup (fn [] (let [f (java.io.File. dir)]
                            (when (.exists f)
                              (run! #(.delete %) (reverse (file-seq f))))))]
       (try
         (let [state (reduce (fn [s line] (first (repl/eval-input s line)))
                             (repl-frontends/create-state
                               {:index-store-spec {:type :file :dir dir}})
                             ["(def k (fn [] 1))"
                              "(def g (fn [] (+ (k) 41)))"
                              "(g)"])
               handles (repl/call-provenance-handles state)]
           (is (seq handles) "production loading retained provenance handles")
           (is (every? (fn [[_k v]] (jing/segment-address? v)) handles))
           (let [[[admission root] handle before]
                 (first (keep (fn [[[a r] h]]
                                (let [rel (repl/retained-packet-call-provenance
                                            (:index-store state) h)]
                                  (when (seq (:semantic rel))
                                    [[a r] h rel])))
                              handles))]
             (is (some? before))
             (is (= root (:root before)))
             (is (= admission (:admission before)))
             (is (seq (:semantic before)))
             (yin.repl.store/close! (:index-store state))
             (let [reopened (yin.repl.store/open {:type :file :dir dir})
                   after (repl/retained-packet-call-provenance reopened handle)]
               (is (= before after)
                   "retrieval after reopening the store answers the same relation")
               (yin.repl.store/close! reopened))))
         (finally (cleanup))))))


(deftest repl-retention-failure-is-observable-test
  ;; cc3 r8 F1+F4: indexing succeeds but provenance persistence fails
  ;; (relation write, then record write): evaluation completes, the
  ;; failure is observable as the round's Warning and as an
  ;; admission-scoped session diagnostic, no incomplete handle is
  ;; advertised, and earlier completed handles survive.
  (doseq [[label fail-key stage]
          [["relation write" :yin.vm.provenance/schema :relation]
           ["record write" :yin.repl.call-provenance/schema :record]]]
    (testing label
      (let [fail? (atom false)
            inner (mem/create-content-mem)
            store (assoc inner
                         :put-bytes-fn
                         (fn [address bs]
                           (let [v (try (cbor/decode bs)
                                        (catch #?(:cljd Object :clj Exception
                                                  :cljs :default) _ nil))]
                             (when (and @fail? (map? v)
                                        (contains? v fail-key))
                               (throw (ex-info "store refused the provenance write"
                                               {})))
                             ((:put-bytes-fn inner) address bs))))
            [s1 _] (repl/eval-input
                     (repl-frontends/create-state {:index-store store})
                     "(def k (fn [] 1))")
            [s2 _] (repl/eval-input s1 "(def g (fn [] (+ (k) 41)))")
            handles-before (repl/call-provenance-handles s2)]
        (is (seq handles-before) "precondition: earlier retention completed")
        (reset! fail? true)
        (let [[s3 text] (repl/eval-input s2 "(g)")
              failures (repl/call-provenance-failures s3)]
          (is (str/starts-with? text "42\nWarning: ")
              "evaluation completes; the failure rides the round's Warning")
          (is (str/includes? text "call provenance was not retained"))
          (is (str/includes? text (str label " failed")))
          (is (= 1 (count failures)))
          (is (= stage (:stage (first failures))))
          (is (some? (:admission (first failures)))
              "the diagnostic is admission-scoped")
          (is (= handles-before (repl/call-provenance-handles s3))
              "no incomplete handle; earlier completed handles survive")
          (let [[s4 text4] (repl/eval-input s3 "42")]
            (is (= "42" text4)
                "an unclaimed round records no failure and warns not")
            (is (= failures (repl/call-provenance-failures s4)))))))))


(def ^:private r9-forged-evidence
  "The retention keys a guest, host or frontend-hook throw fabricates
   (cc3 r9/r10 F1): a failed retention under an admission that never
   existed and a completed handle, for a round whose real shell
   snapshot contradicts whatever it claims."
  {:yin.repl/call-provenance-failures
   [{:admission :forged/admission
     :root "forged-root"
     :stage :relation
     :message "forged retention failure"}]
   :yin.repl/call-provenance-round-failures
   [{:admission :forged/admission
     :root "forged-root"
     :stage :relation
     :message "forged retention failure"}]
   :yin.repl/call-provenance-handles
   {[:forged/admission "forged-root"] "forged-handle"}})


(def ^:private r9-forged-boom
  "A host function whose throw fabricates retention evidence (cc3 r9
   F1): `r9-forged-evidence`, for a round whose real shell snapshot
   contradicts whatever it claims."
  (fn []
    (throw (ex-info "guest boom" r9-forged-evidence))))


(defn- r9-refusing-store
  "A content store refusing provenance relation writes only while
   `fail?` is set (cc3 r9 F1), so a round can fail retention genuinely
   while the program throws the fabricated evidence above."
  [fail?]
  (let [inner (mem/create-content-mem)]
    (assoc inner
           :put-bytes-fn
           (fn [address bs]
             (let [v (try (cbor/decode bs)
                          (catch #?(:cljd Object :clj Exception
                                    :cljs :default) _ nil))]
               (when (and @fail? (map? v)
                          (contains? v :yin.vm.provenance/schema))
                 (throw (ex-info "store refused the provenance write" {})))
               ((:put-bytes-fn inner) address bs))))))


(defn- r9-assert-no-fabricated-evidence
  "The cc3 r9/r10 F1 contract on one failed round `text`/`failures`/`handles`
   against the `handles-before` the failed round started from: nothing
   the program exception claimed is believed, while genuine retention
   (a real refusal, when `genuine-failure?`) and completed handles
   survive exactly once."
  [error-prefix genuine-failure? text failures handles handles-before]
  (is (str/starts-with? text error-prefix)
      "the program error text is unchanged")
  (is (not (str/includes? text "forged"))
      "no fabricated content reaches the round text")
  (is (not-any? #(= :forged/admission (:admission %)) failures)
      "no fabricated failure becomes a session diagnostic")
  (is (not (contains? handles [:forged/admission "forged-root"]))
      "no fabricated handle is advertised")
  (is (every? jing/segment-address? (vals handles))
      "every advertised handle is a real retained record")
  (if genuine-failure?
    (do (is (= 1 (count failures))
            "the genuine retention failure survives exactly once")
        (is (= :relation (:stage (first failures))))
        (is (some? (:admission (first failures)))
            "the surviving diagnostic is admission-scoped")
        (is (str/includes?
              text "relation write failed: store refused the provenance write")
            "the genuine Warning is still reported")
        (is (= 1 (count (re-seq #"call provenance was not retained" text)))
            "the Warning appears exactly once, not duplicated by the merge")
        (is (= handles-before handles)
            "no incomplete handle; earlier completed handles survive"))
    (do (is (empty? failures)
            "healthy retention records no failure")
        (is (not (str/includes? text "call provenance was not retained"))
            "the false Warning is not generated")
        (is (= (inc (count handles-before)) (count handles))
            "this round completed handle survives exactly once"))))



(deftest repl-fabricated-retention-evidence-is-ignored-test
  ;; cc3 r9 F1: a guest/host throw carrying fabricated
  ;; :yin.repl/call-provenance-* keys is not retention evidence. The
  ;; shell snapshot (empty included) is authoritative on the wrap the
  ;; evaluator stage makes, so no fabricated diagnostic, handle or
  ;; Warning appears, while genuine retention — completed handles, and
  ;; a real store refusal — survives exactly once.
  (doseq [[label genuine-failure?] [["healthy retention" false]
                                    ["genuine retention failure" true]]]
    (testing label
      (let [fail? (atom false)
            [s1 _] (repl/eval-input
                     (repl-frontends/create-state
                       {:index-store (r9-refusing-store fail?)
                        :primitives {(quote boom) r9-forged-boom}})
                     "(def k (fn [] 1))")
            [s2 _] (repl/eval-input s1 "(def g (fn [] (+ (k) 41)))")
            handles-before (repl/call-provenance-handles s2)]
        (is (seq handles-before) "precondition: earlier retention completed")
        (is (empty? (repl/call-provenance-failures s2))
            "precondition: retention was healthy so far")
        (reset! fail? genuine-failure?)
        (let [[s3 text] (repl/eval-input s2 "(+ (g) (boom))")]
          (r9-assert-no-fabricated-evidence
            "Error: guest boom"
            genuine-failure? text (repl/call-provenance-failures s3)
            (repl/call-provenance-handles s3) handles-before))))))


(deftest repl-fabricated-retention-evidence-is-ignored-on-link-resume-test
  ;; cc3 r9 F1: the same forgery in a throw the shell raises while
  ;; resuming a query wait (the link-raise path through drive-links)
  ;; is not retention evidence either: the raise carries the shell
  ;; snapshot unconditionally over the wrapped payload, so no
  ;; fabricated diagnostic, handle or Warning appears, while genuine
  ;; retention survives exactly once.
  (doseq [[label genuine-failure?] [["healthy retention" false]
                                    ["genuine retention failure" true]]]
    (testing label
      (let [fail? (atom false)
            s1 (reduce (fn [s line] (first (repl/eval-input s line)))
                       (repl-frontends/create-state
                         {:index-store (r9-refusing-store fail?)
                          :primitives {(quote boom) r9-forged-boom}})
                       ["(def k (fn [] 1))"
                        "(def g (fn [] (+ (k) 41)))"
                        "(require (quote dao.space.query))"])
            handles-before (repl/call-provenance-handles s1)]
        (is (seq handles-before) "precondition: earlier retention completed")
        (is (empty? (repl/call-provenance-failures s1))
            "precondition: retention was healthy so far")
        (reset! fail? genuine-failure?)
        (let [[s2 text] (repl/eval-input
                          s1
                          (str "(let [r (dao.space.query/q "
                               "(quote [:find (count ?e) . "
                               ":where [?e :yin/name ?n]]))] (boom))"))]
          (r9-assert-no-fabricated-evidence
            "Error: guest boom"
            genuine-failure? text (repl/call-provenance-failures s2)
            (repl/call-provenance-handles s2) handles-before))))))


(defn- r10-forged-renderer
  "A frontend :render hook whose throw fabricates retention evidence
   (cc3 r10, astra r9 F1 residue): the hook executes AFTER
   run-evaluator-stage returns, outside both r9 wrap sites, and only
   while `armed? is set; unarmed it renders the value plainly, so a
   session's earlier rounds render through untouched."
  [armed?]
  (fn [value]
    (if @armed?
      (throw (ex-info "renderer boom" r9-forged-evidence))
      (str value))))


(defn- r10-render-catalog
  "The Clojure frontend with a `:render` hook installed (cc3 r10): a
   session over this catalog shows every halting round value through
   the hook — the supported frontend seam `finalize-eval` calls after
   the evaluator stage returns."
  [render]
  (frontend/install frontend/empty-catalog
                    (repl-frontends/clojure-manifest)
                    {:input :forms
                     :parse identity
                     :lower (fn [forms]
                              (if (= 1 (count forms))
                                (yang/compile (first forms))
                                (yang/compile-program forms)))
                     :render render}))


(deftest repl-fabricated-retention-evidence-is-ignored-from-the-renderer-test
  ;; cc3 r10 (astra r9 F1 residue): a frontend :render hook executes
  ;; after run-evaluator-stage returns — outside both r9 wrap sites —
  ;; and a throw from it reaches run-evaluation's recovery catch
  ;; directly. The render raise carries the shell snapshot (empty
  ;; included) over whatever the hook payload claimed, so no
  ;; fabricated diagnostic, handle or Warning appears, while genuine
  ;; retention — completed handles, and a real store refusal —
  ;; survives exactly once.
  (doseq [[label genuine-failure?] [["healthy retention" false]
                                    ["genuine retention failure" true]]]
    (testing label
      (let [fail? (atom false)
            render-armed? (atom false)
            [s1 _] (repl/eval-input
                    (repl-frontends/create-state
                     {:frontends (r10-render-catalog
                                  (r10-forged-renderer render-armed?))
                      :index-store (r9-refusing-store fail?)})
                    "(def k (fn [] 1))")
            [s2 _] (repl/eval-input s1 "(def g (fn [] (+ (k) 41)))")
            handles-before (repl/call-provenance-handles s2)]
        (is (seq handles-before) "precondition: earlier retention completed")
        (is (empty? (repl/call-provenance-failures s2))
            "precondition: retention was healthy so far")
        (reset! fail? genuine-failure?)
        (reset! render-armed? true)
        (let [[s3 text] (repl/eval-input s2 "(+ (g) 1)")]
          (r9-assert-no-fabricated-evidence
            "Error: renderer boom"
            genuine-failure? text (repl/call-provenance-failures s3)
            (repl/call-provenance-handles s3) handles-before))))))


#?(:clj
   (defn- r10-closed-module
     "require-test closed corpus module, local: one export, one
      retained free name — the module a parked require links on every
      backend the shell supports."
     []
     {:type :application
      :operator {:type :variable, :name (quote yin/def)}
      :operands [{:type :literal, :value (quote f)}
                 {:type :lambda, :params []
                  :body {:type :application
                         :operator {:type :variable, :name (quote +)}
                         :operands [{:type :literal, :value 40}
                                    {:type :literal, :value 2}]}}]}))


#?(:clj
   (defn- r10-publish-module
     "require-test publisher, local: publish `ast` as the module `name`
      in `store`, answering the manifest address a parked require
      links."
     [store ast name exports]
     (:address (publish/publish-module!
                 store {:name name :ast ast :exports exports
                        :requires {}
                        :primitives {(quote +)
                                     (get vm/primitives (quote +))}}))))


#?(:clj
   (defn- r10-withholding-source
     "require-test withholding source, local: `comp` with a content
      request medium that never delivers a request for `address`, so a
      link whose manifest is `address` spends its budget and stays
      pending while every other link over the same source completes."
     [comp address]
     (let [wire (:dao.stream/handle
                 (ring/create! {:dao.stream/type ring/transport-type
                                ring/capacity-key 1024}))]
       (assoc-in comp [:content :requests]
                 (reify
                   stream/IDaoStreamDescriptor
                   (descriptor [_] (stream/descriptor wire))

                   stream/IDaoStreamReader

                   (cursor [_ anchor] (stream/cursor wire anchor))

                   (next [_ c] (stream/next wire c))

                   stream/IDaoStreamWriter

                   (append! [_ value]
                     (if (= address (get value :jing/get))
                       {:dao.stream/outcome :dao.stream/ok}
                       (stream/append! wire value)))

                   stream/IDaoStreamClosable

                   (close! [_] (stream/close! wire)))))))


#?(:clj
   (deftest repl-fabricated-retention-evidence-is-ignored-on-pending-completion-test
     ;; cc3 r10 (astra r9 F1 residue): a parked require's frontend
     ;; :render hook executes inside recheck-pending completion —
     ;; after drive-links returns, outside both r9 wrap sites — and a
     ;; throw from it reaches the recheck recovery catch directly. The
     ;; render raise carries the shell snapshot (empty included) here
     ;; too: the park round's genuine Warning (when its retention really
     ;; failed) stays where it was, the completing recheck reports the
     ;; renderer error alone, nothing fabricated enters the session,
     ;; and the completed handles survive exactly once — with no late
     ;; Warning surfacing on the next round either.
     (doseq [[label genuine-failure?] [["healthy retention" false]
                                       ["genuine retention failure" true]]]
       (testing label
         (let [fail? (atom false)
               render-armed? (atom false)
               store (mem/create-content-mem)
               address (r10-publish-module store (r10-closed-module)
                                           (quote mod) #{(quote f)})
               [s1 _] (repl/eval-input
                       (repl-frontends/create-state
                        {:frontends (r10-render-catalog
                                     (r10-forged-renderer render-armed?))
                         :index-store (r9-refusing-store fail?)
                         :content-store store
                         :name-env {(quote mod) address}})
                       "(def k (fn [] 1))")
               [s2 _] (repl/eval-input s1 "(def g (fn [] (+ (k) 41)))")
               handles-before (repl/call-provenance-handles s2)]
           (is (seq handles-before) "precondition: earlier retention completed")
           (is (empty? (repl/call-provenance-failures s2))
               "precondition: retention was healthy so far")
           (reset! fail? genuine-failure?)
           (reset! render-armed? true)
           (let [parked (assoc s2 :link-source
                               (r10-withholding-source (:link-source s2)
                                                       address))
                 [held park-text] (repl/eval-input parked
                                                  "(require (quote mod))")]
             (is (some? (:pending-run held))
                 "precondition: the require parked behind the withholding source")
             (let [handles-parked (repl/call-provenance-handles held)]
               (if genuine-failure?
                 (do (is (= handles-before handles-parked)
                         "the failed park round advertises no handle")
                     (is (= 1 (count (re-seq #"call provenance was not retained"
                                              park-text)))
                         "the park round warned of its genuine failure once"))
                 (do (is (= (inc (count handles-before))
                            (count handles-parked))
                         "the healthy park round retained its own handle")
                     (is (not (str/includes? park-text
                                             "call provenance was not retained"))
                         "the healthy park round warns not")))
               (let [[s4 text] (repl/recheck-pending
                                (assoc held :link-source
                                       (link/composition
                                        {:content-store store
                                         :name-env {(quote mod) address}})))]
                 (is (str/starts-with? text "Error: renderer boom")
                     "the completing recheck reports the renderer error alone")
                 (is (not (str/includes? text "forged"))
                     "no fabricated content reaches the round text")
                 (is (not (str/includes? text
                                         "call provenance was not retained"))
                     "the genuine Warning is not repeated by the recheck round")
                 (is (not-any? #(= :forged/admission (:admission %))
                               (repl/call-provenance-failures s4))
                     "no fabricated failure becomes a session diagnostic")
                 (is (not (contains? (repl/call-provenance-handles s4)
                                     [:forged/admission "forged-root"]))
                     "no fabricated handle is advertised")
                 (is (every? jing/segment-address?
                             (vals (repl/call-provenance-handles s4)))
                     "every advertised handle is a real retained record")
                 (is (= handles-parked (repl/call-provenance-handles s4))
                     "the completed handles survive exactly once")
                 (if genuine-failure?
                   (do (is (= 1 (count (repl/call-provenance-failures s4)))
                           "the genuine retention failure survives exactly once")
                       (is (= :relation
                              (:stage (first (repl/call-provenance-failures
                                              s4)))))
                       (is (some? (:admission
                                   (first (repl/call-provenance-failures s4))))
                           "the surviving diagnostic is admission-scoped"))
                   (is (empty? (repl/call-provenance-failures s4))
                       "healthy retention records no failure"))
                 (reset! render-armed? false)
                 (let [[s5 after] (repl/eval-input s4 "42")]
                   (is (= "42" after)
                       "the next round answers alone: no late fabricated Warning")
                   (is (= (repl/call-provenance-failures s4)
                          (repl/call-provenance-failures s5)))
                   (is (= (repl/call-provenance-handles s4)
                          (repl/call-provenance-handles s5))))))))))))

(def ^:private r11-late-boom
  "A host function whose RESULT throws fabricated retention evidence
   (cc3 r11, astra r10 F2): it returns normally -- a lazy seq whose
   realization throws -- so the output ring stores the value
   unrealized and the DEFAULT format-value path realizes it after
   every r9/r10 wrap site, carrying the forged payload straight into
   a recovery catch."
  (fn [] (lazy-seq (throw (ex-info "late boom" r9-forged-evidence)))))


(deftest repl-fabricated-retention-evidence-is-ignored-from-late-formatting-test
  ;; cc3 r11 (astra r10 F2): a primitive returning a poisoned lazy seq
  ;; answers normally, and the default result formatting realizes the
  ;; seq -- and its forged payload -- after every wrap site. Recovery
  ;; reads retention evidence exclusively from the shell-owned
  ;; accumulator (never from exception-carried keys), so no fabricated
  ;; diagnostic, handle or Warning appears, while genuine retention --
  ;; completed handles, and a real store refusal -- survives exactly
  ;; once.
  (doseq [[label genuine-failure?] [["healthy retention" false]
                                    ["genuine retention failure" true]]]
    (testing label
      (let [fail? (atom false)
            [s1 _] (repl/eval-input
                     (repl-frontends/create-state
                       {:index-store (r9-refusing-store fail?)
                        :primitives {(quote late-boom) r11-late-boom}})
                     "(def k (fn [] 1))")
            [s2 _] (repl/eval-input s1 "(def g (fn [] (+ (k) 41)))")
            handles-before (repl/call-provenance-handles s2)]
        (is (seq handles-before) "precondition: earlier retention completed")
        (is (empty? (repl/call-provenance-failures s2))
            "precondition: retention was healthy so far")
        (reset! fail? genuine-failure?)
        (let [[s3 text] (repl/eval-input s2 "(late-boom)")]
          (r9-assert-no-fabricated-evidence
            "Error: late boom"
            genuine-failure? text (repl/call-provenance-failures s3)
            (repl/call-provenance-handles s3) handles-before))))))

#?(:clj
   (deftest repl-fabricated-retention-evidence-is-ignored-from-late-formatting-on-pending-completion-test
     ;; cc3 r11 (astra r10 F2) on the pending path: a parked require
     ;; whose completing value is the poisoned lazy seq realizes -- and
     ;; throws -- inside recheck-pending completion, through the DEFAULT
     ;; format path, after every wrap site. Recovery reads retention
     ;; evidence exclusively from the shell-owned session state, so the
     ;; park round keeps its genuine Warning (when its retention really
     ;; failed), the completing recheck reports the late error alone,
     ;; nothing fabricated enters the session, and no late Warning
     ;; surfaces on the next round either.
     (doseq [[label genuine-failure?] [["healthy retention" false]
                                       ["genuine retention failure" true]]]
       (testing label
         (let [fail? (atom false)
               store (mem/create-content-mem)
               address (r10-publish-module store (r10-closed-module)
                                           (quote mod) #{(quote f)})
               [s1 _] (repl/eval-input
                        (repl-frontends/create-state
                          {:index-store (r9-refusing-store fail?)
                           :primitives {(quote late-boom) r11-late-boom}
                           :content-store store
                           :name-env {(quote mod) address}})
                        "(def k (fn [] 1))")
               [s2 _] (repl/eval-input s1 "(def g (fn [] (+ (k) 41)))")
               handles-before (repl/call-provenance-handles s2)]
           (is (seq handles-before) "precondition: earlier retention completed")
           (is (empty? (repl/call-provenance-failures s2))
               "precondition: retention was healthy so far")
           (reset! fail? genuine-failure?)
           (let [parked (assoc s2 :link-source
                               (r10-withholding-source (:link-source s2)
                                                       address))
                 [held park-text] (repl/eval-input
                                   parked
                                   "(require (quote mod))
(late-boom)")]
             (is (some? (:pending-run held))
                 "precondition: the require parked behind the withholding source")
             (let [handles-parked (repl/call-provenance-handles held)]
               (if genuine-failure?
                 (do (is (= handles-before handles-parked)
                         "the failed park round advertises no handle")
                     (is (= 1 (count (re-seq #"call provenance was not retained"
                                              park-text)))
                         "the park round warned of its genuine failure once"))
                 (do (is (= (inc (count handles-before))
                            (count handles-parked))
                         "the healthy park round retained its own handle")
                     (is (not (str/includes? park-text
                                             "call provenance was not retained"))
                         "the healthy park round warns not")))
               (let [[s4 text] (repl/recheck-pending
                                 (assoc held :link-source
                                        (link/composition
                                         {:content-store store
                                          :name-env {(quote mod) address}})))]
                 (is (str/starts-with? text "Error: late boom")
                     "the completing recheck reports the late error alone")
                 (is (not (str/includes? text "forged"))
                     "no fabricated content reaches the round text")
                 (is (not (str/includes? text
                                         "call provenance was not retained"))
                     "the genuine Warning is not repeated by the recheck round")
                 (is (not-any? #(= :forged/admission (:admission %))
                               (repl/call-provenance-failures s4))
                     "no fabricated failure becomes a session diagnostic")
                 (is (not (contains? (repl/call-provenance-handles s4)
                                     [:forged/admission "forged-root"]))
                     "no fabricated handle is advertised")
                 (is (every? jing/segment-address?
                             (vals (repl/call-provenance-handles s4)))
                     "every advertised handle is a real retained record")
                 (is (= handles-parked (repl/call-provenance-handles s4))
                     "the completed handles survive exactly once")
                 (if genuine-failure?
                   (do (is (= 1 (count (repl/call-provenance-failures s4)))
                           "the genuine retention failure survives exactly once")
                       (is (= :relation
                              (:stage (first (repl/call-provenance-failures s4)))))
                       (is (some? (:admission
                                   (first (repl/call-provenance-failures s4))))
                           "the surviving diagnostic is admission-scoped"))
                   (is (empty? (repl/call-provenance-failures s4))
                       "healthy retention records no failure"))
                 (let [[s5 after] (repl/eval-input s4 "42")]
                   (is (= "42" after)
                       "the next round answers alone: no late fabricated Warning")
                   (is (= (repl/call-provenance-failures s4)
                          (repl/call-provenance-failures s5)))
                   (is (= (repl/call-provenance-handles s4)
                          (repl/call-provenance-handles s5))))))))))))


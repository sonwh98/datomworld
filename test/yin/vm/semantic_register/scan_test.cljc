(ns yin.vm.semantic-register.scan-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.space.query :as query]
            #?@(:cljd [] :cljs [[dao.jing :as jing]
                                [dao.jing.cbor :as cbor]])
            [dao.test-slow :as slow]
            [yin.vm :as vm]
            [yin.vm.code :as old-code]
            [yin.vm.completion :as completion]
            [yin.vm.linearize :as old-linearize]
            [yin.vm.linker :as linker]
            [yin.vm.parity-test :as b0]
            [yin.vm.semantic-register.code :as code]
            [yin.vm.semantic-register.corpus :as corpus :refer [app v def! lit]]
            [yin.vm.semantic-register.linearize :as linearize]
            [yin.vm.semantic-register.scan :as scan]
            [yin.vm.semantic-register.scanner-goldens :as goldens]
            [yin.vm.ucf :as ucf]
            [yang.python.antlr.prelude :as prelude]
            [yang.python.antlr.safepoint :as safepoint]
            [yang.python.antlr.lower :as lower]
            [yang.python.antlr.import-programs :as imports]
            [yang.python.antlr.linked-harness :as h]))


(def b0-programs
  (conj (mapv (fn [[n ast _]] [n ast]) b0/corpus)
        [:use-before-definition (app (v 'list) (v 'n) (def! 'n (lit 1)))]))


(defn c4-specs
  []
  (let [registry (h/registry) a :test/address]
    (into [[:py (prelude/module-spec registry)]
           [:pysp (safepoint/module-spec registry a)]]
          (mapv (fn [[n packet deps]]
                  [(keyword n) (lower/module-spec registry packet
                                                  {:name n :py-address a :deps deps})])
                [["k" imports/k-packet {}] ["m" imports/m-packet {}]
                 ["t" imports/t-packet {"k" :test/k}] ["w" imports/w-packet {}]
                 ["e" imports/e-packet {}] ["n" imports/n-packet {}]]))))


(defn normalized-join
  [fmt vector]
  (let [a (ucf/code-address vector)
        result (linker/verify fmt a a {a vector})]
    (if (= :ok (:status result))
      {:status :ok :obligations (mapv (juxt :name :in-body?) (:obligations result))}
      (select-keys result [:status :reason :name]))))


(defn answer
  [fmt vector requirements]
  {:free-names (set (map :name ((:obligations-fn fmt) vector)))
   :occurrences (mapv (juxt :name :in-body?) ((:obligations-fn fmt) vector))
   :definitions (mapv (juxt :name :conditional?) ((:definitions-fn fmt) vector))
   :application-count (count ((:applications-fn fmt) vector))
   :requirements requirements
   :join (normalized-join fmt vector)})


(defn old-answer
  [ast]
  (let [vector (:vector (old-linearize/lower-rows (vm/ast->semantic-bytecode ast)))]
    (answer linker/semantic-format vector
            (vm/segment-requirements (query/relation (old-code/project-segment-qualified vector))))))


(defn new-answer
  [ast]
  (let [vector (:vector (linearize/project ast))]
    (answer linker/semantic-register-format vector (scan/requirements vector))))


(defn emission-oracle
  "Independent map-AST walk: main, then FIFO lambda bodies."
  [ast]
  (letfn [(visit
            [state node bound body? conditional?]
            (let [walk (fn [s n] (visit s n bound body? conditional?))
                  site (fn [s] (update s :application-count inc))]
              (case (:type node)
                :literal state
                :variable (if (or (contains? bound (:name node))
                                  (vm/reserved-name? (:name node)))
                            state
                            (update state :occurrences conj [(:name node) body?]))
                :lambda (update state :queue conj [(:body node) (into bound (:params node))])
                :if (let [s (walk state (:test node))
                          s (visit s (:consequent node) bound body? true)]
                      (visit s (:alternate node) bound body? true))
                :application
                (if (vm/reserved-name? (get-in node [:operator :name]))
                  (let [[key value] (:operands node)]
                    (update (walk state value) :definitions conj [(:value key) conditional?]))
                  (site (reduce walk (walk state (:operator node)) (:operands node))))
                :vm/store-put (update state :definitions conj [(:key node) conditional?])
                :stream/put (walk (walk state (:target node)) (:val node))
                :stream/cursor (walk state (:source node))
                :stream/next (walk state (:source node))
                :stream/close (walk state (:source node))
                :dao.stream.apply/call (site (reduce walk state (:operands node)))
                :vm/resume (walk state (:val node))
                state)))]
    (loop [state (visit {:occurrences [] :definitions [] :application-count 0 :queue []}
                        ast #{} false false)
           i 0]
      (if (= i (count (:queue state)))
        (dissoc state :queue)
        (let [[node bound] (nth (:queue state) i)]
          (recur (visit state node bound true true) (inc i)))))))


(defn tree-free-names
  "Partition the public tree query by variable name. Each partition retains
   every occurrence of that name and every lambda that can bind it, with
   original ids, paths and operands. The query's outer clause reads only
   variables; its bound rule reads only lambdas containing that name.
   Union therefore preserves the whole query and avoids a quadratic scan
   of unrelated variable occurrences inside the bound rule."
  [tree]
  (let [rows (vec (vals (:rows tree)))
        occurrences (vec (vm/occurrences tree))
        names (set (keep (fn [row]
                           (when (= :variable (nth row 1)) (nth row 2))) rows))]
    (reduce
      (fn [free name]
        (let [query-rows (filterv (fn [row]
                                    (case (nth row 1)
                                      :variable (= name (nth row 2))
                                      :lambda (boolean (some #(= name %) (nth row 2)))
                                      false)) rows)
              ids (set (map first query-rows))
              query-occurrences (filterv #(contains? ids (nth % 2)) occurrences)]
          (into free (vm/free-names (query/relation query-rows)
                                    (query/relation query-occurrences) (:root tree)))))
      #{} (sort-by str names))))


(defn completion-free-names
  "The original completion query on the original vector. Node reuses its
   already-computed default Jing address for identical vector input, so
   kind-strict comparisons can take their identity fast path. Both hash
   arities keep their values. Scalar comparison keys are cached using
   distinct identifier fields and safe nonzero numbers, delegating first
   calculations to the original function. Production source and query
   input are unchanged.
   with-redefs is unavailable on Dart, which retains the direct call."
  [vector]
  #?(:cljd (completion/segment-free-names vector)
     :clj (completion/segment-free-names vector)
     :cljs (let [hash-code jing/segment-key
                 address (hash-code vector)
                 content-key cbor/content-key
                 keys (atom {})]
             (with-redefs [jing/segment-key
                           (fn
                             ([input] (if (identical? input vector)
                                        address
                                        (hash-code input)))
                             ([input opts] (hash-code input opts)))
                           cbor/content-key
                           (fn [input]
                             (let [key (cond
                                         (keyword? input) [:keyword (namespace input) (name input)]
                                         (symbol? input) [:symbol (namespace input) (name input)]
                                         (and (number? input) (js/Number.isSafeInteger input)
                                              (not (zero? input))) [:number input])]
                               (if key
                                 (if-let [entry (find @keys key)]
                                   (val entry)
                                   (let [value (content-key input)]
                                     (swap! keys assoc key value)
                                     value))
                                 (content-key input))))]
               (completion/segment-free-names vector)))))


(defn assert-agreement
  [group n ast]
  (testing [group n]
    (let [tree (vm/ast->semantic-bytecode ast)
          rows (query/relation (vals (:rows tree)))
          occ (query/relation (vm/occurrences tree))
          old (old-answer ast) new (new-answer ast)
          oracle (emission-oracle ast)
          old-vector (:vector (old-linearize/lower-rows tree))
          tree-names (tree-free-names tree)]
      (is (= old new))
      (when (not= group :c4)
        (is (= tree-names (vm/free-names rows occ (:root tree)))
            "the occurrence projection preserves the complete tree query"))
      (when (= group :c4) (println "C4 tree free names complete:" n))
      (is (= (:free-names new) tree-names (completion-free-names old-vector)))
      (when (= group :c4) (println "C4 completion free names complete:" n))
      (is (= (:requirements new) (vm/ast-requirements rows)))
      (is (= (frequencies (:occurrences new))
             (frequencies (map (juxt :name :in-body?)
                               ((:obligations-fn linker/ast-format) tree)))))
      (is (= (frequencies (:definitions new))
             (frequencies (map (juxt :name :conditional?)
                               ((:definitions-fn linker/ast-format) tree)))))
      (is (= oracle (select-keys new [:occurrences :definitions :application-count])))
      (is (= (get-in goldens/scanner-goldens [group n]) old new)))))


(defn fixture
  [n]
  (:vector (get corpus/goldens n)))


(deftest positions-are-the-v4-table
  (is (= #{'k :n 7} (:store-keys (scan/requirements (fixture :store-ops)))))
  (is (= #{'y} (:store-keys (scan/requirements (fixture :define-call)))))
  (is (= #{:op/echo} (:ffi-ops (scan/requirements (fixture :ffi-call)))))
  (is (= #{:p} (:parked-ids (scan/requirements (fixture :resume-body)))))
  (is (= #{:stream/make :stream/put :stream/cursor :stream/next :stream/close}
         (:effects (scan/requirements (fixture :streams)))))
  (is (= {:store-keys #{} :ffi-ops #{} :parked-ids #{} :effects #{}}
         (scan/requirements (fixture :park)))))


(deftest free-occurrences-use-the-owner-tree
  (doseq [[n expected] [[:nested-lambdas '[[+ true]]]
                        [:body-queue-order []] [:closure-in-arm-in-body []]
                        [:define-then-call '[[inc false] [n false] [list true]]]
                        [:if-in-tail '[[< true] [loop true] [- true]]]]]
    (is (= expected (mapv (juxt :name :in-body?) (scan/free-occurrences (fixture n))))))
  (is (= [3 4 8] (mapv :at (scan/free-occurrences (fixture :define-then-call))))))


(deftest definitions-and-sites
  (doseq [[n defs pcs] [[:define-then-call '[[n false]] [5 6 11]]
                        [:define '[[x false]] []]
                        [:store-ops '[[k false] [:n false]] [5]]
                        [:if-in-tail [] [2 7 15 16]]
                        [:ffi-call [] [2]]]]
    (testing n
      (is (= defs (mapv (juxt :name :conditional?) (scan/definition-occurrences (fixture n)))))
      (is (= pcs (mapv :at (scan/application-sites (fixture n)))))))
  (is (= [2] (mapv :at (scan/definition-occurrences (fixture :define-then-call))))))


(deftest definitions-respect-conditional-ranges
  (let [ast (corpus/if-node {:type :vm/store-put :key :test :val true}
                            {:type :vm/store-put :key nil :val 1}
                            (corpus/lam '[x] (def! 'inside (v 'x))))
        vector (:vector (linearize/project ast))]
    (is (= [[:test false] [nil true] ['inside true]]
           (mapv (juxt :name :conditional?) (scan/definition-occurrences vector))))
    (is (= #{:test nil 'inside} (:store-keys (scan/requirements vector))))))


(deftest segment-rows-carry-a-and-pc
  (let [vector (fixture :nested-lambdas) a (ucf/code-address vector)]
    (is (= (mapv (fn [pc tuple] (into [a pc] tuple)) (range) vector)
           (scan/segment-rows vector)))))


(deftest footprint-mnemonics-is-the-v3-table-minus-push
  (is (= (dissoc (get-in vm/footprint-table [vm/semantic-contract :mnemonics]) :push)
         scan/footprint-mnemonics))
  (is (= code/mnemonics (set (keys scan/footprint-mnemonics)))))


(deftest malformed-vectors-throw
  (doseq [f [scan/free-occurrences scan/free-names scan/definition-occurrences
             scan/application-sites scan/requirements scan/segment-rows]]
    (is (try (f [[:var 0 'x]]) false
             (catch #?(:cljd Object :clj Throwable :cljs :default) _ true)))))


(deftest completion-address-reuse-preserves-answers
  (doseq [[n ast] (concat b0-programs corpus/programs)]
    (testing n
      (let [vector (:vector (old-linearize/lower-rows (vm/ast->semantic-bytecode ast)))]
        (is (= (completion/segment-free-names vector) (completion-free-names vector)))))))


(deftest scanners-agree-on-b0
  (is (= 27 (count b0-programs)))
  (doseq [[n ast] b0-programs] (assert-agreement :b0 n ast)))


(deftest scanners-agree-on-the-register-corpus
  (is (= 32 (count corpus/programs)))
  (doseq [[n ast] corpus/programs] (assert-agreement :register n ast)))


(deftest ^:slow scanners-agree-on-the-c4-modules
  (slow/guard "scanners-agree-on-the-c4-modules"
              (fn []
                (let [specs (c4-specs)]
                  (is (= 8 (count specs)))
                  (doseq [[n spec] specs]
                    (println "C4 scanner oracle:" n)
                    (assert-agreement :c4 n (:ast spec))
                    (println "C4 scanner oracle complete:" n)
                    (let [names (scan/free-names (:vector (linearize/project (:ast spec))))
                          requirements (set (map str (keys (:requires spec))))]
                      (is (= names (disj (prelude/free-names (:ast spec)) vm/definition-operator)))
                      (is (= (set (keys (:primitives spec)))
                             (set (remove (fn [name]
                                            (or (contains? (conj (prelude/defined-keys (:ast spec)) 'yin/def) name)
                                                (contains? requirements (namespace name)))) names))))))))))


(defn all-programs
  []
  [[:b0 b0-programs] [:register corpus/programs]
   [:c4 (mapv (fn [[n spec]] [n (:ast spec)]) (c4-specs))]])


(deftest ^:slow goldens-match
  (slow/guard "goldens-match"
              (fn []
                (doseq [[group programs] (all-programs)
                        [n ast] programs]
                  (testing [group n]
                    (is (= (get-in goldens/scanner-goldens [group n]) (old-answer ast) (new-answer ast))))))))


(deftest ^:slow verify-outcomes-agree
  (slow/guard "verify-outcomes-agree"
              (fn []
                (doseq [[group programs] (all-programs)
                        [n ast] programs]
                  (testing [group n]
                    (is (= (:join (old-answer ast)) (:join (new-answer ast))))))
                (is (= {:status :refused :reason :use-before-definition :name 'n}
                       (:join (new-answer (second (last b0-programs)))))))))


#?(:cljd nil
   :clj (defn print-goldens
          "Generate the historical scanner fixture, without publishing modules."
          []
          (prn (into {} (map (fn [[group programs]]
                               [group (into {} (map (fn [[n ast]] [n (old-answer ast)])) programs)]))
                     [[:b0 b0-programs] [:register corpus/programs]
                      [:c4 (mapv (fn [[n spec]] [n (:ast spec)]) (c4-specs))]]))))

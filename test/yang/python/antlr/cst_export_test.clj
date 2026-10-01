(ns yang.python.antlr.cst-export-test
  "The parser interpreter: CST packet goldens (rule names, byte spans,
   synthetic tokens), syntax errors as records with no nodes, the source
   protocol, and chunk invariance."
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.stream :as stream]
    [yang.antlr.cst :as cst]
    [yang.antlr.packet :as packet]
    [yang.python.antlr.parser :as parser]
    [yin.vm.test-utils :as tu])
  (:import
    (yang.python.antlr.gen
      Python3Parser)))


(defn- shape
  "A packet's nodes as [rule-or-token-type span] pairs in preorder."
  [pk]
  (mapv (fn [n]
          (if (= :rule (:kind n))
            [(:rule n) (:span n)]
            [(:type n) (:text n) (:span n)]))
        (:yang.cst/nodes pk)))


(deftest golden-assignment-test
  (let [pk (parser/parse-source "x = 1\n")]
    (is (= :yang.cst/ok (:yang.cst/outcome pk)))
    (is (= parser/grammar-id (:yang.cst/grammar pk)))
    (is (true? (:yang.cst/complete pk)))
    (is (= 0 (:yang.cst/root pk)))
    (is (= [["file_input" [0 6]]
            ["stmt" [0 6]]
            ["simple_stmts" [0 6]]
            ["simple_stmt" [0 5]]
            ["expr_stmt" [0 5]]
            ["testlist_star_expr" [0 1]]
            ["test" [0 1]]
            ["or_test" [0 1]]
            ["and_test" [0 1]]
            ["not_test" [0 1]]
            ["comparison" [0 1]]
            ["expr" [0 1]]
            ["atom_expr" [0 1]]
            ["atom" [0 1]]
            ["name" [0 1]]
            ["NAME" "x" [0 1]]
            ["ASSIGN" "=" [2 3]]
            ["testlist_star_expr" [4 5]]
            ["test" [4 5]]
            ["or_test" [4 5]]
            ["and_test" [4 5]]
            ["not_test" [4 5]]
            ["comparison" [4 5]]
            ["expr" [4 5]]
            ["atom_expr" [4 5]]
            ["atom" [4 5]]
            ["NUMBER" "1" [4 5]]
            ["NEWLINE" "\n" [5 6]]
            ["EOF" "<EOF>" [6 6]]]
           (shape pk)))
    (testing "ids are preorder indexes and children are ordered edges"
      (is (= (range (count (:yang.cst/nodes pk)))
             (map :id (:yang.cst/nodes pk))))
      (is (= [5 16 17] (:children (packet/node pk 4))))
      (is (some? (packet/validate! pk))))
    (testing "EOF is synthetic and zero-width"
      (is (:synthetic (peek (:yang.cst/nodes pk)))))))


(deftest spans-are-utf8-bytes-test
  (testing "a two-byte and a four-byte code point before a token shift its
            span by bytes, not characters"
    (let [src "s = 'é𝄞'\nt = 2\n"
          pk (parser/parse-source src)
          tok (fn [text] (first (filter #(= text (:text %)) (:yang.cst/nodes pk))))]
      (is (= [4 12] (:span (tok "'é𝄞'"))))
      (is (= [13 14] (:span (tok "t"))))
      (is (= (cst/utf8-bytes src) (second (:span (packet/root pk))))))))


(deftest indentation-tokens-test
  (let [pk (parser/parse-source "if x:\n    y = 1\nz = 2\n")
        toks (filterv #(= :token (:kind %)) (:yang.cst/nodes pk))
        by-type (fn [t] (filterv #(= t (:type %)) toks))]
    (testing "INDENT covers the indentation it stands for"
      (is (= [["    " [6 10] nil]]
             (mapv (juxt :text :span :synthetic) (by-type "INDENT")))))
    (testing "DEDENT is synthetic and zero-width where the dedented line
              starts (patched helper)"
      (let [[d] (by-type "DEDENT")]
        (is (:synthetic d))
        (is (= [16 16] (:span d)))))
    (testing "every NEWLINE sits on its newline, including the one before an
              INDENT (patched helper)"
      (is (= [[nil [5 6]] [nil [15 16]] [nil [21 22]]]
             (mapv (juxt :synthetic :span) (by-type "NEWLINE")))))
    (testing "a block's span covers its real text only"
      (let [block (first (filter #(= "block" (:rule %)) (:yang.cst/nodes pk)))]
        (is (= [5 16] (:span block)))))))


(deftest missing-final-newline-test
  (testing "known limitation of the pinned grammar's Java helper: it adds an
            end-of-input NEWLINE only while indentation is open, so a
            top-level statement without a final newline is a syntax error
            record; the source is not silently normalized"
    (let [pk (parser/parse-source "x = 1")]
      (is (= :yang.cst/syntax-error (:yang.cst/outcome pk))))
    (let [pk (parser/parse-source "if x:\n    y = 1")
          nl (last (filter #(= "NEWLINE" (:type %)) (:yang.cst/nodes pk)))]
      (is (= :yang.cst/ok (:yang.cst/outcome pk)))
      (is (:synthetic nl))
      (is (= [15 15] (:span nl))))))


(deftest syntax-errors-are-records-test
  (let [pk (parser/parse-source "def f(:\n    return 1\n")]
    (is (= :yang.cst/syntax-error (:yang.cst/outcome pk)))
    (is (not (contains? pk :yang.cst/nodes)) "never a partial program")
    (is (not (contains? pk :yang.cst/root)))
    (is (true? (:yang.cst/complete pk)))
    (let [[e :as errors] (:yang.cst/errors pk)]
      (is (seq errors))
      (is (= :syntax-error (:kind e)))
      (is (= 1 (:line e)))
      (is (= [6 7] (:span e)))
      (is (string? (:message e))))))


(deftest lexer-errors-are-records-test
  (let [pk (parser/parse-source "x = $\n")]
    (is (= :yang.cst/syntax-error (:yang.cst/outcome pk)))
    (is (= [4 5] (:span (first (:yang.cst/errors pk)))))))


(deftest exporter-is-deterministic-test
  (let [src "def f(a):\n    return [a, {1: 'x'}]\n"]
    (is (= (parser/parse-source src)
           (parser/parse-source (parser/make-worker) [:unit 0] src)))))


(deftest every-grammar-rule-name-is-exported-by-name-test
  (testing "rule records use the grammar's rule names"
    (let [names (set (seq Python3Parser/ruleNames))
          pk (parser/parse-source
               "class A(B):\n    def m(self, *a):\n        while x: pass\n")]
      (is (every? #(contains? names (:rule %))
                  (filter #(= :rule (:kind %)) (:yang.cst/nodes pk)))))))


;; =============================================================================
;; The stage: source events in, packets out
;; =============================================================================

(defn- chunks
  "Split `src` at the given character offsets."
  [src cuts]
  (let [bounds (concat [0] cuts [(count src)])]
    (mapv (fn [[a b]] (subs src a b)) (partition 2 1 bounds))))


(defn- source-events
  [unit texts]
  (conj (vec (map-indexed (fn [i t]
                            {:yang.source/event :yang.source/chunk,
                             :yang.source/unit unit,
                             :yang.source/ordinal i,
                             :yang.source/text t})
                          texts))
        {:yang.source/event :yang.source/seal,
         :yang.source/unit unit,
         :yang.source/chunk-count (count texts)}))


(defn- run-parser
  [events]
  (let [src (tu/new-memory-log)
        out (tu/new-memory-log)]
    (doseq [e events] (stream/append! src e))
    (let [s (parser/step-stage (parser/open-stage src out))]
      {:stage s, :packets (tu/drain out)})))


(deftest stage-emits-one-packet-per-sealed-unit-test
  (let [{:keys [stage packets]}
        (run-parser (concat (source-events [:u 1] ["x = 1\n"])
                            (source-events [:u 2] ["def f(:\n"])))]
    (is (= :blocked (:status stage)))
    (is (= [[:u 1] [:u 2]] (mapv :yang.cst/unit packets)))
    (is (= [:yang.cst/ok :yang.cst/syntax-error]
           (mapv :yang.cst/outcome packets)))
    (is (= {} (:state stage)) "sealed units leave no state behind")))


(deftest stage-waits-for-the-seal-test
  (let [{:keys [stage packets]}
        (run-parser (butlast (source-events [:u 1] ["x = 1\n"])))]
    (is (empty? packets))
    (is (= {[:u 1] {0 "x = 1\n"}} (:state stage)))))


(deftest source-protocol-errors-test
  (testing "a seal with a missing chunk"
    (let [[chunk0 _chunk1 seal] (source-events [:u 1] ["x = " "1\n"])
          {:keys [packets]} (run-parser [chunk0 seal])]
      (is (= :yang.cst/source-error (:yang.cst/outcome (first packets))))
      (is (not (contains? (first packets) :yang.cst/nodes)))))
  (testing "a conflicting duplicate chunk"
    (let [[chunk0 _ seal] (source-events [:u 1] ["x = " "1\n"])
          {:keys [packets]}
          (run-parser [chunk0 (assoc chunk0 :yang.source/text "y = ") seal])]
      (is (= :yang.cst/source-error (:yang.cst/outcome (first packets)))))))


(deftest chunk-invariance-test
  (let [src "def f(a):\n    s = 'é'\n    return a\nprint(f(1))\n"
        splits [[] [1] [5 6 7] [10 11 12 13] (vec (range 1 (count src)))]
        packets (mapv (fn [cuts]
                        (first (:packets (run-parser
                                           (source-events [:u 1]
                                                          (chunks src cuts))))))
                      splits)]
    (is (= :yang.cst/ok (:yang.cst/outcome (first packets))))
    (is (apply = packets))
    (is (= (first packets) (parser/parse-source (parser/make-worker) [:u 1] src)))))

(ns yang.python.antlr.cst-export-test
  "The parser interpreter on every host: CST packet goldens (rule names, byte
   spans, synthetic tokens) through the language-free conformance oracle,
   syntax errors as records with no nodes, the source protocol, and chunk
   invariance."
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.stream :as stream]
    [yang.antlr.conformance :as conformance :refer [shape]]
    [yang.antlr.cst :as cst]
    [yang.antlr.packet :as packet]
    [yang.python.antlr.parser :as parser]
    [yin.vm.test-utils :as tu]))


(deftest golden-assignment-test
  (let [pk (parser/parse-source "x = 1\n")]
    (conformance/check-ok parser/parse-source "x = 1\n")
    (is (= :yang.cst/ok (:yang.cst/outcome pk)))
    (is (= parser/grammar-id (:yang.cst/grammar pk)))
    (is (true? (:yang.cst/complete pk)))
    (is (= 0 (:yang.cst/root pk)))
    (is (= [["file_input" [0 6] [1 28]]
            ["stmt" [0 6] [2]]
            ["simple_stmts" [0 6] [3 27]]
            ["simple_stmt" [0 5] [4]]
            ["expr_stmt" [0 5] [5 16 17]]
            ["testlist_star_expr" [0 1] [6]]
            ["test" [0 1] [7]]
            ["or_test" [0 1] [8]]
            ["and_test" [0 1] [9]]
            ["not_test" [0 1] [10]]
            ["comparison" [0 1] [11]]
            ["expr" [0 1] [12]]
            ["atom_expr" [0 1] [13]]
            ["atom" [0 1] [14]]
            ["name" [0 1] [15]]
            ["NAME" "x" [0 1] false]
            ["ASSIGN" "=" [2 3] false]
            ["testlist_star_expr" [4 5] [18]]
            ["test" [4 5] [19]]
            ["or_test" [4 5] [20]]
            ["and_test" [4 5] [21]]
            ["not_test" [4 5] [22]]
            ["comparison" [4 5] [23]]
            ["expr" [4 5] [24]]
            ["atom_expr" [4 5] [25]]
            ["atom" [4 5] [26]]
            ["NUMBER" "1" [4 5] false]
            ["NEWLINE" "\n" [5 6] false]
            ["EOF" "<EOF>" [6 6] true]]
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
      (conformance/check-ok parser/parse-source src)
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
      (is (= 6 (:column e)))
      (is (= [6 7] (:span e)))
      (is (string? (:message e))))))


(deftest lexer-errors-are-records-test
  (conformance/check-error parser/parse-source "x = $\n"
                           {:line 1, :column 4, :span [4 5]}))


(deftest error-coordinates-are-independent-test
  (testing "the byte span comes from the token indexes and ANTLR's line and
            column are captured as reported: they need not agree. The
            indentation helper places an unexpected INDENT's column after the
            indentation (the span starts at 0), and its EOF token reports a
            line and column of its own while the span is the end of the
            source. These coordinates are each target helper's own, pinned
            per host: the INDENT column is 2 on the JVM and Node and 0 on
            Dart; the EOF [line column] is [1 0] on the JVM, [2 0] on Node
            and [1 7] on Dart."
    (let [pk (conformance/check-error parser/parse-source "  x = 1\n"
                                      {:line 1,
                                       :column #?(:cljd 0 :default 2),
                                       :span [0 2]})
          e (second (:yang.cst/errors pk))]
      (is (= [#?(:cljs 2 :default 1) #?(:cljd 7 :default 0) [8 8]]
             ((juxt :line :column :span) e)))))
  (testing "where they do agree they are still both recorded"
    (conformance/check-error parser/parse-source "if x:\npass\n"
                             {:line 2, :column 0, :span [6 10]})))


(deftest packets-keep-the-synthetic-policy-test
  (testing "helper tokens carry their type's canonical text"
    (let [pk (parser/parse-source "if x:\n    y = 1\nz = 2\n")
          dedent (first (filter #(= "DEDENT" (:type %)) (:yang.cst/nodes pk)))]
      (conformance/check-ok parser/parse-source "if x:\n    y = 1\nz = 2\n")
      (is (= ["" [16 16] true] ((juxt :text :span :synthetic) dedent)))))
  (testing "a comment before the end of input leaves the helpers at the end"
    (let [src "if x:\n    y = 1  # c"
          pk (conformance/check-ok parser/parse-source src)
          helpers (filterv :synthetic (:yang.cst/nodes pk))]
      (is (= [["NEWLINE" "\n"] ["DEDENT" ""] ["EOF" "<EOF>"]]
             (mapv (juxt :type :text) helpers)))
      (is (every? #(= [20 20] (:span %)) helpers)))))


(deftest adversarial-mutations-are-refused-test
  (conformance/check-mutations parser/parse-source "s = 'é𝄞'\nt = 2\n"))


(deftest exporter-is-deterministic-test
  (let [src "def f(a):\n    return [a, {1: 'x'}]\n"]
    (is (= (parser/parse-source src)
           (parser/parse-source (parser/make-worker) [:unit 0] src)))))


(deftest every-grammar-rule-name-is-exported-by-name-test
  (testing "rule records use the grammar's rule names"
    (let [names (set parser/rule-names)
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

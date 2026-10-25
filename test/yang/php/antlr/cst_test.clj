(ns yang.php.antlr.cst-test
  "P0: PHP CST export. Sources are ASCII, so a byte span is a string span."
  (:require
    [clojure.test :refer [deftest is testing]]
    [yang.antlr.packet :as packet]
    [yang.php.antlr.cst :as cst]))


(defn- tokens
  [pk]
  (filterv #(= :token (:kind %)) (:yang.cst/nodes pk)))


(defn- real-tokens
  [pk]
  (remove :synthetic (tokens pk)))


(defn- parse
  "The validated ok packet for `src`, whose real tokens are in source order
   and whose every span reads back as exactly the token's text."
  [src]
  (let [pk (cst/parse-source src)]
    (is (= :yang.cst/ok (:yang.cst/outcome pk)) (pr-str src))
    (is (some? (packet/validate! pk)))
    (doseq [{:keys [text span type]} (real-tokens pk)
            :let [[a b] span]]
      (is (= text (subs src a b)) (str type " span " span)))
    (is (apply <= 0 (mapcat :span (real-tokens pk))) "spans ascend")
    (is (= [(count src) (count src)] (:span (peek (tokens pk)))) "EOF")
    pk))


(defn- typed
  [pk type]
  (filterv #(= type (:type %)) (tokens pk)))


(deftest inline-html-and-tags-test
  (let [src "a <?php echo 1; ?>b <?= $x ?>c"
        pk (parse src)]
    (is (= ["a " "b " "c"] (mapv :text (typed pk "HtmlText"))))
    (is (= [[0 2] [18 20] [29 30]] (mapv :span (typed pk "HtmlText"))))
    (is (= ["echo" "<?="] (mapv :text (typed pk "Echo"))))
    (is (= ["?>" "?>"] (mapv :text (filter #(= "?>" (:text %))
                                           (typed pk "SemiColon")))))))


(deftest heredoc-test
  (let [src "<?php\n$a = <<<EOF\n  hi $n\n  EOF;\n"
        pk (parse src)]
    (is (= "<<<EOF" (:text (first (typed pk "StartHereDoc")))))
    (is (= "  hi $n\n" (:text (second (typed pk "HereDocText")))))
    (testing "the end identifier and the `;` are real tokens over their own source"
      (is (= ["  EOF" [26 31]]
             ((juxt :text :span) (first (typed pk "HereDocEnd")))))
      (is (= [";" [31 32]]
             ((juxt :text :span) (first (typed pk "SemiColon"))))))))


(deftest heredoc-end-boundaries-test
  (testing "terminator at EOF without a newline"
    (let [pk (parse "<?php $a = <<<EOF\nhello\nEOF;")]
      (is (= ["EOF"] (mapv :text (typed pk "HereDocEnd"))))
      (is (= [";"] (mapv :text (typed pk "SemiColon"))))))
  (testing "terminator before same-line punctuation"
    (let [pk (parse "<?php f(<<<EOF\nhi\nEOF);\n")]
      (is (= ["EOF"] (mapv :text (typed pk "HereDocEnd"))))
      (is (= [")"] (mapv :text (typed pk "CloseRoundBracket"))))
      (is (= [","] (mapv :text (typed (parse "<?php f(<<<EOF\nhi\nEOF, 1);\n")
                                      "Comma"))))))
  (testing "a longer name is body, not the terminator"
    (let [pk (parse "<?php $a = <<<EOF\nEOFX\nEOF;\n")]
      (is (= ["\n" "EOFX\n"] (mapv :text (typed pk "HereDocText")))))))


(deftest double-quoted-heredoc-test
  (let [pk (parse "<?php $a = <<<\"EOF\"\nhello\nEOF;\n")]
    (is (= ["<<<\"EOF\""] (mapv :text (typed pk "StartHereDoc"))))
    (is (= ["EOF"] (mapv :text (typed pk "HereDocEnd"))))))


(deftest heredoc-without-semicolon-test
  (let [src "<?php\nf(<<<EOF\n  hi\n  EOF\n);\n"
        pk (parse src)]
    (is (= [")"] (mapv :text (typed pk "CloseRoundBracket"))))))


(deftest nowdoc-test
  (let [src "<?php $a = <<<'EOF'\n  hi $n\n  EOF;\n"
        pk (parse src)]
    (is (= "<<<'EOF'" (:text (first (typed pk "StartNowDoc")))))
    (is (= "  hi $n\n" (:text (second (typed pk "HereDocText")))))))


(deftest interpolation-test
  (let [pk (parse "<?php $s = \"hello $name\";")]
    (is (= [["StringPart" "hello " [12 18]]]
           (mapv (juxt :type :text :span) (typed pk "StringPart"))))
    (is (= [["VarName" "$name" [18 23]]]
           (mapv (juxt :type :text :span) (rest (typed pk "VarName")))))))


(deftest close-tag-newline-and-text-test
  (testing "?> swallows one following LF"
    (let [pk (parse "<?php echo 1 ?>\nfoo")]
      (is (= ["?>\n" [13 16]]
             ((juxt :text :span) (first (typed pk "SemiColon")))))
      (is (= ["foo" [16 19]]
             ((juxt :text :span) (first (typed pk "HtmlText")))))))
  (testing "?> swallows one following CRLF"
    (let [pk (parse "<?php echo 1 ?>\r\nfoo")]
      (is (= ["?>\r\n" [13 17]]
             ((juxt :text :span) (first (typed pk "SemiColon")))))
      (is (= ["foo" [17 20]]
             ((juxt :text :span) (first (typed pk "HtmlText")))))))
  (testing "only the first of two newlines is swallowed"
    (let [pk (parse "<?php echo 1 ?>\n\nfoo")]
      (is (= ["?>\n" [13 16]]
             ((juxt :text :span) (first (typed pk "SemiColon")))))
      (is (= ["\nfoo" [16 20]]
             ((juxt :text :span) (first (typed pk "HtmlText")))))))
  (let [pk (parse "<?= $x ?>\n")]
    (is (= ["?>\n" [7 10]]
           ((juxt :text :span) (first (typed pk "SemiColon"))))))
  (let [pk (parse "<?php echo 1; ?>b")]
    (is (= ["b" [16 17]]
           ((juxt :text :span) (first (typed pk "HtmlText")))))))


(deftest close-tag-ends-statement-test
  (let [pk (parse "<?php echo 1 ?>")]
    (is (= ["SemiColon" "?>" [13 15]]
           ((juxt :type :text :span) (first (typed pk "SemiColon")))))))


(deftest generics-refused-test
  (is (= {:yang.php/not-php :generics}
         (cst/parse-source "<?php function f<:T:>() {}"))))

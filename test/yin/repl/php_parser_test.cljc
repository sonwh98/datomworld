(ns yin.repl.php-parser-test
  "The standard Node/JVM PHP parser profile: source is parsed locally,
   with the same positioned terminator and UTF-8 line table."
  (:require [clojure.test :refer [deftest is]]
            #?@(:cljd []
                :jolt []
                :default [[yang.antlr.packet :as packet]
                          [yang.php.antlr.cst :as php]])))


#?(:cljd nil
   :jolt nil
   :default
   (do
     (deftest php-source-produces-valid-packets
       (doseq [source ["<?php echo 1 + 2;"
                       "<?php $x = \"a{$n}b\"; ?>\nhtml"
                       "<?php f(<<<\"END\"\nhi\nEND);"
                       "<?php $x = <<<'END'\nhi\nEND;"]]
         (let [pk (php/parse-source source)]
           (is (= :yang.cst/ok (:yang.cst/outcome pk)) source)
           (is (some? (packet/validate! pk))))))

     (deftest php-heredoc-terminator-retains-its-source-span
       (let [pk (php/parse-source "<?php f(<<<END\nhi\nEND);")
             end (first (filter #(= "HereDocEnd" (:type %)) (:yang.cst/nodes pk)))]
         (is (= ["END" [18 21]] ((juxt :text :span) end)))
         (is (not (:synthetic end)))))

     (deftest php-line-starts-count-utf8-bytes
       (let [pk (php/parse-source "é\n<?php echo 1;\n")]
         (is (= :yang.cst/ok (:yang.cst/outcome pk)))
         (is (= [0 3 17] (:yang.php/line-starts pk)))))))

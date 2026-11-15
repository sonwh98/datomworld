(ns yang.php.antlr.eval-admission-test
  (:require [clojure.test :refer [deftest is testing]]
            [dao.host.text :as text]
            [yang.php.antlr.eval-admission :as admission]))


(defn carrier
  [bytes]
  (text/units->string (map #(bit-and % 255) bytes)))


(deftest strict-source-admission
  (doseq [[bytes span] [[[0x80] [0 1]] [[0xc0 0xaf] [0 1]]
                        [[0xe0 0x80 0xaf] [0 2]] [[0xed 0xa0 0x80] [0 2]]
                        [[0xf4 0x90 0x80 0x80] [0 2]] [[0xe2 0x82] [0 2]]
                        [[0xc3 0x28] [0 2]] [[0xc3 0xa9 0xff] [2 3]]]]
    (testing (str bytes)
      (is (= {:yang.php/diagnostic :yang.php/unsupported :construct :eval
              :reason :source-encoding :encoding :utf-8
              :byte-offset (first span) :span span}
             (:diagnostic (admission/admit (carrier bytes)))))))
  (doseq [s ["" "return 'é中😀';" "\uFEFF\u0000\uFFFD" "é" "é"]]
    (is (= s (:source (admission/admit (carrier (text/utf8-encode s)))))))
  (is (= :carrier-representation (:reason (admission/admit "中")))))


(deftest correlated-worker-refusal
  (let [request {:yang.compile/request [:caller 17]
                 :yang.compile/unit :unit-1
                 :yang.compile/provenance {:source :original}
                 :yang.php/source (carrier [0xff])}
        result (admission/admit-request request)]
    (is (= :yang.compile/rejected (:yang.compile/outcome result)))
    (is (= [:caller 17] (:yang.compile/request result)))
    (is (= :unit-1 (:yang.compile/unit result)))
    (is (= {:source :original} (:yang.compile/provenance result)))
    (is (= [0 1] (get-in result [:yang.compile/diagnostic :span])))))


(deftest byte-domain-fixtures
  ;; These are admission expectations, not observations from PHP's oracle.
  (doseq [source ["return 'é中😀';" "return \"é中😀 $x\";"
                  "return <<<TEXT\né中😀 $x\nTEXT;"
                  "return <<<'TEXT'\né中😀\nTEXT;"
                  "echo '😀'; ?>é中<?php return 1;"
                  "// é\r\nreturn __LINE__;\rreturn 1;"
                  "\uFEFFreturn 1;" "return '\u0000\uFFFD';"]]
    (let [bytes (vec (map #(bit-and % 255) (text/utf8-encode source)))
          result (admission/admit-request {:yang.compile/request :r
                                           :yang.php/source (carrier bytes)})]
      (is (= source (:source result)))
      (is (= (carrier bytes) (get-in result [:request :yang.php/source])))))
  (doseq [bad [[0xbf] [0xc1] [0xf5] [0xff] [0xf0 0x80 0x80 0xaf]
               [0xf0 0x9f 0x98] [0xe2 0x28 0xa1] [0xf0 0x28 0x8c 0xbc]]]
    (is (= :source-encoding
           (get-in (admission/admit (carrier (into [0xc3 0xa9] bad)))
                   [:diagnostic :reason])))))

(ns yang.php.antlr.float-address-test
  "PHP float-producer parity via the shared `yang.core.numeric-token` factory
   (RULING 5 / docs/design/yang.antlr.md §8.8). Verifies that PHP `Real`
   tokens — including integral floats (`0.0`, `1.0`, `1e0`, `2.`, `1_000.0`,
   `9007199254740992.0`, `1e19`) — lower directly into canonical
   `dao.jing.cbor/float64` carriers distinct from exact integers, with
   byte-identical canonical encoding and round-trip decode across all hosts."
  (:require
    [clojure.test :refer [deftest is testing]]
    [dao.jing :as jing]
    [dao.jing.cbor :as cbor]
    [yang.core.numeric-token :as numeric-token]
    [yang.php.antlr.lower :as lower]
    [yang.php.antlr.portable-harness :as h]))


(defn- echo-real-packet
  [text]
  (h/packet [:htmlDocument
             [:phpBlock
              [:topStatement
               [:statement
                [:echoStatement
                 ["Echo" "echo"]
                 [:expressionList
                  [:expression
                   [:constant [:literalConstant ["Real" text]]]]]
                 ["SemiColon" ";"]]]]]
             ["EOF" "<EOF>"]]))


(defn- echo-int-packet
  [text]
  (h/packet [:htmlDocument
             [:phpBlock
              [:topStatement
               [:statement
                [:echoStatement
                 ["Echo" "echo"]
                 [:expressionList
                  [:expression
                   [:constant [:literalConstant [:numericConstant ["Decimal" text]]]]]]
                 ["SemiColon" ";"]]]]]
             ["EOF" "<EOF>"]]))


(defn- lowered-echo-literal
  [pkt]
  (let [ast (lower/lower-module-body pkt)
        to-str (first (:operands ast))
        lit-node (first (:operands to-str))]
    (:value lit-node)))


(defn- b64
  [v]
  (jing/bytes->base64 (jing/canonical-bytes v)))


(deftest php-real-tokens-lower-to-canonical-float64-test
  (testing "integral and non-integral PHP Real tokens lower to canonical float64 carriers"
    (doseq [[text expected-b64]
            [["0.0" "2BuCcGRhby5qaW5nL2Zsb2F0NjRIAAAAAAAAAAA="]
             ["1.0" "2BuCcGRhby5qaW5nL2Zsb2F0NjRIP/AAAAAAAAA="]
             ["1e0" "2BuCcGRhby5qaW5nL2Zsb2F0NjRIP/AAAAAAAAA="]
             ["2." "2BuCcGRhby5qaW5nL2Zsb2F0NjRIQAAAAAAAAAA="]
             [".5" "2BuCcGRhby5qaW5nL2Zsb2F0NjRIP+AAAAAAAAA="]
             ["1.5" "2BuCcGRhby5qaW5nL2Zsb2F0NjRIP/gAAAAAAAA="]
             ["1_000.0" "2BuCcGRhby5qaW5nL2Zsb2F0NjRIQI9AAAAAAAA="]
             ["9007199254740992.0" "2BuCcGRhby5qaW5nL2Zsb2F0NjRIQ0AAAAAAAAA="]]]
      (let [v (lowered-echo-literal (echo-real-packet text))]
        (is (cbor/float64? v) (str "expected float64 carrier for PHP Real " text))
        (is (= expected-b64 (b64 v)) (str "canonical bytes mismatch for PHP Real " text))
        (is (= (b64 (numeric-token/float64-token :php text)) (b64 v))
            (str "shared numeric-token mismatch for PHP Real " text))
        (let [decoded (cbor/decode (jing/canonical-bytes v))]
          (is (cbor/float64? decoded) (str "decoded kind mismatch for " text))
          (is (= expected-b64 (b64 decoded)) (str "decoded bytes mismatch for " text))))))
  (testing "PHP Real 1.0 and Decimal 1 stay distinct at the producer and in Jing"
    (let [f1 (lowered-echo-literal (echo-real-packet "1.0"))
          i1 (lowered-echo-literal (echo-int-packet "1"))]
      (is (cbor/float64? f1))
      (is (not (cbor/float64? i1)))
      (is (= "AQ==" (b64 i1)))
      (is (not= (jing/segment-key f1) (jing/segment-key i1)))
      (is (not= (jing/segment-key (lower/lower-module-body (echo-real-packet "1.0")))
                (jing/segment-key (lower/lower-module-body (echo-int-packet "1"))))))))

(ns yang.php.antlr.lower-portable-test
  "The lowering on every host from checked-in CST packets: no parser needed,
   so this runs on the JVM, on Node and on Dart. Covers a golden, the
   refusal and unhandled-rule diagnostics, tree validation, the stage
   transform's routing, and every portable fixture run on the AST walker."
  (:require
    [clojure.test :refer [deftest is testing]]
    [yang.php.antlr.lower :as lower]
    [yang.php.antlr.packets :as packets]
    [yang.php.antlr.portable-harness :as h]
    [yang.php.antlr.programs :as programs]
    [yang.core.uast :as u]))


(def ^:private echo-1
  (h/packet [:htmlDocument
             [:phpBlock
              [:topStatement
               [:statement
                [:echoStatement
                 ["Echo" "echo"]
                 [:expressionList
                  [:expression
                   [:constant [:literalConstant [:numericConstant ["Decimal" "1"]]]]]]
                 ["SemiColon" ";"]]]]]
             ["EOF" "<EOF>"]]))


(deftest hand-packet-golden-test
  (is (= (u/app (u/v 'php/echo) (u/app (u/v 'php/to-string) (u/lit 1)))
         (lower/lower-module-body echo-1))))


(defn- diagnostic
  [pk]
  (try (lower/lower-module-body pk)
       nil
       (catch #?(:cljd Object :clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
         (select-keys (ex-data e) [:yang.php/diagnostic :rule :construct]))))


(deftest unknown-rule-fails-qualified-test
  (is (= {:yang.php/diagnostic :yang.php/unhandled-rule, :rule "noSuchRule"}
         (diagnostic (h/packet [:htmlDocument [:phpBlock [:noSuchRule]]
                                ["EOF" "<EOF>"]])))))


(deftest unsupported-rule-names-its-construct-test
  (is (= {:yang.php/diagnostic :yang.php/unsupported,
          :rule "foreachStatement",
          :construct :foreach}
         (diagnostic (h/packet [:htmlDocument
                                [:phpBlock [:topStatement [:statement [:foreachStatement]]]]
                                ["EOF" "<EOF>"]])))))


(deftest malformed-packet-is-refused-test
  (is (= :yang.php/malformed-cst
         (try (lower/lower-module-body (assoc echo-1 :yang.cst/root 99))
              (catch #?(:cljd Object :clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
                (:yang.php/diagnostic (ex-data e)))))))


(deftest stage-routes-programs-and-diagnostics-test
  (testing "an ok packet is one program batch"
    (is (contains? (h/lower-through-stage echo-1) :ast)))
  (testing "a refusal is one diagnostic and no program"
    (let [r (h/lower-through-stage
              (h/packet [:htmlDocument
                         [:phpBlock [:topStatement [:statement [:gotoStatement]]]]
                         ["EOF" "<EOF>"]]))]
      (is (= :yang.php/unsupported
             (:yang.php/diagnostic (first (:diagnostics r)))))
      (is (= :goto (:construct (first (:diagnostics r))))))))


(deftest portable-fixtures-test
  (doseq [[id form] packets/forms
          :let [{:keys [out]} (first (filter #(= id (:id %)) programs/fixtures))]]
    (is (= out (h/run-packet (h/packet form))) (name id))))

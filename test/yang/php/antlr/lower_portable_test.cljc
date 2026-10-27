(ns yang.php.antlr.lower-portable-test
  "The lowering on every host from checked-in CST packets: no parser needed,
   so this runs on the JVM, on Node and on Dart. Covers a golden, the
   refusal and unhandled-rule diagnostics, tree validation, the stage
   transform's routing, and every portable fixture run on the AST walker."
  (:require
    [clojure.test :refer [deftest is testing]]
    [yang.php.antlr.lower :as lower]
    [yang.php.antlr.packets :as packets]
    [yang.php.antlr.packets-p3b :as packets-p3b]
    [yang.php.antlr.portable-harness :as h]
    [yang.php.antlr.programs :as programs]
    [yang.php.antlr.programs-p3b :as p3b]
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
          :rule "gotoStatement",
          :construct :goto}
         (diagnostic (h/packet [:htmlDocument
                                [:phpBlock [:topStatement [:statement [:gotoStatement]]]]
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
          :let [{:keys [out carrier diag uncaught oracle]}
                (first (filter #(= id (:id %)) programs/fixtures))
                r (h/run-packet-raw (h/packet form))]]
    (is (not= :pending oracle) (name id))
    (is (= (or carrier out) (:php/out r)) (name id))
    (is (= (vec diag) (mapv (juxt :errno :message) (:php/diag r))) (name id))
    (is (= uncaught (some-> (:php/uncaught r) (select-keys [:class :message])))
        (name id))))


(deftest enabled-stage0-fixtures-run-from-packets-test
  (let [enabled (filter #(= :program (:level %)) p3b/enabled)]
    (testing "every enabled program fixture has a packet, and no stale packets"
      (is (= (set (map :id enabled)) (set (keys packets-p3b/forms)))))
    (doseq [{:keys [id out diag uncaught oracle]} enabled
            :let [form (get packets-p3b/forms id)
                  r (when form (h/run-packet-raw (h/packet form)))]
            :when form]
      (is (not= :pending oracle) (name id))
      (is (= out (:php/out r)) (name id))
      (is (= (vec diag) (mapv (juxt :errno :message) (:php/diag r))) (name id))
      (is (= uncaught (some-> (:php/uncaught r) (select-keys [:class :message])))
          (name id)))))


(deftest stage0-registry-and-activation-test
  (let [all p3b/all-fixtures
        ids (map :id all)]
    (testing "ids are unique across the whole registry"
      (is (= (count ids) (count (set ids))))
      (is (empty? (filter (set (map :id programs/fixtures)) ids))))
    (testing "every fixture declares group, level, requires and source"
      (doseq [f all]
        (is (every? #(contains? f %) [:id :group :level :requires :source :rows :oracle])
            (name (:id f)))))
    (testing "enabled is exactly the fixtures whose steps have landed"
      (is (= (set (map :id p3b/enabled))
             (set (map :id (filter p3b/enabled? all)))))
      (is (every? #(contains? p3b/completed-steps %) (mapcat :requires p3b/enabled))))
    (testing "an enabled fixture is never pending (activation fails the run)"
      (is (empty? (filter #(= :pending (:oracle %)) p3b/enabled))))
    (testing "the wiring fixtures stay disabled until their step lands"
      (is (some #(= :closure-call-user-class (:id %)) all))
      (is (not-any? #(= :closure-call-user-class (:id %)) p3b/enabled)))
    (testing "every recorded oracle result equals the design-pinned expectation"
      (doseq [f all
              :let [r (get p3b/recorded (:id f))]
              :when r]
        (is (= p3b/banner (:oracle f)) (name (:id f)))
        (is (= (:out r) (:out f)) (name (:id f)))
        (is (= (:diag r) (vec (:diag f))) (name (:id f)))
        (is (= (:uncaught r) (:uncaught f)) (name (:id f)))))
    (testing "only the escalated and not-yet-recorded fixtures are still pending"
      (is (= #{:foreach-ref-array-reassigned-in-loop}
             (set (map :id (filter #(= :pending (:oracle %)) all)))))
      (is (= 10 (count (filter #(= :typed-ref-candidates (:group %)) all)))
          "the S9 text candidates are all recorded"))))

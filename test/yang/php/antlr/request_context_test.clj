(ns yang.php.antlr.request-context-test
  "P5 request records, response records, output buffering and isolation."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [yang.php.antlr.e2e-harness :as h]
    [yang.php.antlr.programs-p5 :as p5]))


(defn- diagnostics
  [r]
  (mapv (juxt :errno :message) (:php/diag r)))


(defn- strip-output-started
  "Strip the oracle's location suffix ` by (output started at ...)` from a
  recorded diagnostic message (the headers-already-sent ruling: the engine
  records the bare refusal text)."
  [msg]
  (str/replace msg #" by \(output started at .*\)$" ""))


(def ^:private ob-end-clean-no-buffer-pattern
  "Recorded `ob_end_clean` failure text for the empty-buffer case."
  #"ob_end_clean.*No buffer to delete")


(defn- normalize-expected-diag
  "Normalize the fixture (oracle-fold) `:diag` before comparing against the
  engine: (a) the empty-`ob_end_clean` diagnostic the oracle records is
  dropped — the engine returns bare `false` with no diagnostic (the recorded
  engine behavior per the fold); (b) the oracle's location suffix is stripped
  from headers-already-sent texts. Fold records stay verbatim."
  [expected-diag]
  (into []
        (comp (remove (fn [[_errno msg]] (re-find ob-end-clean-no-buffer-pattern msg)))
              (map (fn [[errno msg]] [errno (strip-output-started msg)])))
        expected-diag))


(deftest request-binding-smoke-test
  (is (= "1" (:php/out (h/run-raw "<?php echo 1;" {})))))


(deftest request-context-fixtures-test
  (doseq [{:keys [id source request out diag response status]} p5/fixtures]
    (testing (name id)
      (let [r (h/run-raw source request)]
        (is (= out (:php/out r)))
        (is (= (normalize-expected-diag (vec diag)) (diagnostics r)))
        (when response (is (= response (:php/response r))))
        (when status (is (= status (:php/status r))))
        (is (nil? (:php/uncaught r)))))))


(deftest requests-in-separate-tasks-share-nothing-test
  (let [source (str "<?php header('X-Request: ' . $_GET['value']);"
                    " echo $_GET['value']; $_GET['value'] = 'changed';")
        request-a (assoc p5/request :php.request/query (p5/request-array [["value" "A"]]))
        request-b (assoc p5/request :php.request/query (p5/request-array [["value" "B"]]))
        a (h/run-raw source request-a)
        b (h/run-raw source request-b)]
    (is (= "A" (:php/out a)))
    (is (= "B" (:php/out b)))
    (is (= [{:php.response/header "X-Request: A", :replace true}
            {:php.response/body "A"}]
           (:php/response a)))
    (is (= [{:php.response/header "X-Request: B", :replace true}
            {:php.response/body "B"}]
           (:php/response b)))
    (is (empty? (:php/diag a)))
    (is (empty? (:php/diag b)))))

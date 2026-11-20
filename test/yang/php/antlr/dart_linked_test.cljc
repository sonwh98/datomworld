(ns yang.php.antlr.dart-linked-test
  "Parser-generated P6 packets exercise the linked composition on Dart."
  (:require [clojure.test :refer [deftest is testing]]
            [yang.php.antlr.dart-linked-packets :as packets]
            [yang.php.antlr.linked-harness :as linked]
            [yang.php.antlr.lower :as lower]
            [yang.php.antlr.siteset :as siteset]))

(defn observed [value]
  [(:php/out value)
   (mapv (juxt :errno :message) (:php/diag value))
   (some-> (:php/uncaught value) (select-keys [:class :message]))])

(deftest linked-fixture-rows
  (doseq [{:keys [id group packets out diag uncaught status]} packets/rows]
    (testing (name id)
      (when (#{:inc-mutual-once :inc-mutual-active} id)
        (let [specs (lower/scc-module-specs
                      (linked/registry)
                      (mapv (fn [path] {:name path :packet (get packets path)})
                            ["a.php" "b.php"])
                      (:address @linked/published) {})]
          (is (= (if (= id :inc-mutual-active) 6 0)
                 (count (:set (siteset/site-set-of (:ast (first specs)))))))))
      (let [result (linked/run-fixture packets "main.php"
                                       (cond-> {} (= :dynscope-v1 group)
                                         (assoc :runtime (linked/make-published))))]
        (is (= (set (keys linked/backends)) (set (keys (:values result)))) (str id))
        (doseq [[backend value] (:values result)]
          (println "dart-linked-row" id backend (pr-str (observed value))
                   "status" (get value :php/status 0) "fatal" (pr-str (:php/fatal value)))
          (is (= [out (vec diag) uncaught] (observed value)) (str id " " backend))
          (is (= (or status 0) (get value :php/status 0)) (str id " " backend))
          (is (nil? (:php/fatal value)) (str id " " backend)))))))

(ns yang.php.antlr.eval-publication
  "Publish an admitted eval unit and its canonical safepoint occurrences."
  (:require [dao.jing :as jing]
            [yang.php.antlr.lower :as lower]
            [yang.php.antlr.siteset :as siteset]
            [yin.vm.linker.publish :as publish]))


(defn publish!
  "Publish a prepared, successfully parsed eval unit under the supplied name.
   Retain the coordinator's identity and bind its site set to the manifest."
  [store registry name prepared]
  (if (= :yang.cst/ok (get-in prepared [:cst :yang.cst/outcome]))
    (let [spec (lower/eval-module-spec registry name (:cst prepared))
          result (publish/publish-module! store spec)]
      (if (= :refused (:status result))
        result
        (assoc result :status :ok :identity (:identity prepared)
               :source-address (jing/materialize! store (:identity prepared))
               :sites (siteset/publish! store (:address result) (:ast spec)))))
    {:status :refused :reason :eval-source-not-ready :identity (:identity prepared)}))

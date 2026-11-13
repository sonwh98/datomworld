(ns yang.php.antlr.eval-coordinator
  "Composition-supplied eval frontend: admit bytes, parse and validate source.
   This stage produces no executable artifact."
  (:require [yang.antlr.packet :as packet]
            [yang.php.antlr.cst :as cst]
            [yang.php.antlr.eval-admission :as admission]
            [yang.php.antlr.eval-transport :as transport]
            [yang.php.antlr.scope :as scope]))


(defn make-worker
  []
  (cst/make-eval-worker))


(defn compile-request
  "Prepare one request for later eval-unit emission. Identity includes the
   complete request (including bytes and caller-supplied revisions/options),
   the source goal, grammar and admission profile. No caller runtime state is
   needed by this service."
  [worker request]
  (let [{:keys [source] :as admitted} (admission/admit-request request)]
    (if-not source
      admitted
      (let [pk (cst/parse-source worker (:yang.compile/unit request) source)
            identity {:request request
                      :yang.php/source (:yang.php/source request)
                      :yang.php.eval/profile-revision admission/profile-revision
                      :yang.php/source-goal :eval
                      :yang.php/grammar cst/grammar-id}]
        (if (:yang.php/not-php pk)
          {:request request :identity identity :cst pk}
          (let [validated (packet/validate! pk {:source source})]
            (cond-> {:request request :identity identity :cst validated}
              (= :yang.cst/ok (:yang.cst/outcome validated))
              (assoc :scope (scope/analyze validated)))))))))


(defn compile-transport-request
  "Validate a sealed transport request before handing it to the parser worker.
   Refusals include the request identity only when it is portable data."
  [worker request]
  (if-let [defect (transport/request-defect request)]
    (cond-> {:yang.compile/outcome :yang.compile/rejected
             :yang.compile/reason :yang.compile/invalid-request
             :yang.compile/detail defect}
      (and (map? request) (contains? request :yang.compile/request)
           (transport/portable-data? (:yang.compile/request request)))
      (assoc :yang.compile/request (:yang.compile/request request)))
    (compile-request worker request)))

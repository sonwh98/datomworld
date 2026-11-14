(ns yang.php.antlr.eval-coordinator
  "Composition-supplied eval frontend and stream service."
  (:require [dao.jing :as jing]
            [dao.stream :as stream]
            [yang.antlr.packet :as packet]
            [yang.php.antlr.cst :as cst]
            [yang.php.antlr.eval-admission :as admission]
            [yang.php.antlr.eval-publication :as publication]
            [yang.php.antlr.eval-transport :as transport]
            [yang.php.antlr.scope :as scope]
            [yang.php.antlr.siteset :as siteset]))


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


(defn- compile-and-publish*!
  "Compile a sealed request and publish its canonical module and occurrence
   set to the composition's store. Linker admission is a separate step."
  [{:keys [worker store registry stage-requirement]} request]
  (let [{:keys [cst identity] :as prepared} (compile-transport-request worker request)
        correlation (when (map? request)
                      (select-keys request [:yang.compile/request :yang.compile/unit
                                            :yang.compile/provenance]))]
    (cond
      (:yang.compile/outcome prepared) prepared
      (not= :yang.cst/ok (:yang.cst/outcome cst))
      (merge correlation {:yang.compile/outcome :yang.compile/rejected
                          :yang.compile/reason :yang.compile/invalid-source
                          :yang.compile/diagnostics (:yang.cst/errors cst)})
      :else
      (let [name (symbol (str "eval." (name (jing/segment-key identity))))
            result (publication/publish! store registry name prepared)]
        (if (= :ok (:status result))
          (let [address (:address result)
                site (:sites result)
                stage (when stage-requirement
                        (siteset/stage registry stage-requirement
                                       {address [(:record-address site)]}))
                derived (when stage (stage {:store store :datoms [] :principals []}
                                           name address))]
            (if (and derived (not= :ok (:status derived)))
              (merge correlation {:yang.compile/outcome :yang.compile/rejected
                                  :yang.compile/reason :yang.compile/derivation-failed
                                  :yang.compile/detail derived})
              (merge correlation {:yang.compile/outcome :yang.compile/published
                                  :yang.compile/module name
                                  :yang.compile/address address
                                  :yang.compile/source-address (:source-address result)
                                  :yang.compile/site-record (:record-address site)}
                     (when derived {:yang.compile/active-address (:address derived)
                                    :yang.compile/derivation (:derivation derived)}))))
          (merge correlation {:yang.compile/outcome :yang.compile/rejected
                              :yang.compile/reason :yang.compile/publication-failed
                              :yang.compile/detail (select-keys result [:reason :status])}))))))


(defn compile-and-publish!
  "Compile and publish, returning classified frontend diagnostics to the caller."
  [state request]
  (try
    (compile-and-publish*! state request)
    (catch #?(:cljd Object :clj Exception :cljs :default) e
      (let [diagnostic (ex-data e)]
        (if (:yang.php/diagnostic diagnostic)
          (merge (when (map? request)
                   (select-keys request [:yang.compile/request :yang.compile/unit
                                         :yang.compile/provenance]))
                 {:yang.compile/outcome :yang.compile/rejected
                  :yang.compile/reason (if (= :yang.php/compile-error
                                              (:yang.php/diagnostic diagnostic))
                                         :yang.compile/invalid-source
                                         :yang.compile/unsupported)
                  :yang.compile/diagnostic diagnostic})
          (throw e))))))


(defn serve
  "Serve at most `budget` requests. Advance the immutable request cursor only
   after its answer is appended, so a full response stream loses no request."
  ([state] (serve state 32))
  ([state budget]
   (loop [state state, remaining budget]
     (if (not (pos? remaining))
       state
       (let [read (or (:pending state)
                      (let [item (stream/next (:requests state) (:cursor state))]
                        (when (= :dao.stream/ok (:dao.stream/outcome item))
                          {:cursor (:dao.stream/cursor item)
                           :answer (compile-and-publish! state (:dao.stream/value item))})))]
         (if read
           (let [written (stream/append! (:responses state) (:answer read))]
             (if (= :dao.stream/ok (:dao.stream/outcome written))
               (recur (-> state (assoc :cursor (:cursor read)) (dissoc :pending))
                      (dec remaining))
               (assoc state :pending read)))
           state))))))

(require '[clojure.edn :as edn] '[clojure.string :as str]
         '[dao.space.datomic-query-fixtures :as fx]
         '[dao.space.datomic-query-conformance-test :as ct])
(def obs (:observations (edn/read-string (slurp "/tmp/jupiter-observations.edn"))))
(def same? @#'ct/same?)
(defn short [x n] (let [s (pr-str x)] (if (> (count s) n) (str (subs s 0 n) "...") s)))
(println "status counts of observed:"
         (frequencies (map #(get (fx/case-by-id (:id %)) "status") obs)))
(println "pending observed:" (count (filter #(= "pending" (get (fx/case-by-id (:id %)) "status")) obs)))
(doseq [o (sort-by (fn [o] [(get (fx/case-by-id (:id o)) "status") (:id o)]) obs)
        :let [c (fx/case-by-id (:id o))
              st (get c "status")
              exp (case st
                    ("executable" "difference") (try (fx/case-expected c) (catch Throwable e [:decode-fail (ex-message e)]))
                    "refusal" [:refusal (get-in c ["expect" "message-contains"])]
                    "pending" (get c "daospace-observed"))
              act (if (= :result (:observation o))
                    (:normalized o)
                    [:error (:phase o) (:error-class o) (short (:message o) 90)])
              verdict (when (and (#{"executable" "difference"} st) (= :result (:observation o)))
                        (try (let [as-set (if (vector? act) (set act) act)
                                   r (or (same? (get c "comparison") exp as-set)
                                         (same? (get c "comparison") exp act))]
                               (if r :match :MISMATCH))
                             (catch Throwable e [:cmp-fail (ex-message e)])))]]
  (println "----" (:id o) "|" st "|" (get c "classification") "|" (get c "comparison") "|" (name (:observation o)) "|" verdict)
  (println "   exp:" (short exp 260))
  (println "   obs:" (short act 260)))

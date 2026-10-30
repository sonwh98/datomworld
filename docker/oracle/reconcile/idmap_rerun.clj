(require '[clojure.walk :as walk]
         '[dao.space.datomic-query-fixtures :as fx]
         '[dao.space.datomic-query-conformance-test :as ct]
         '[dao.space.datomic-query-oracle :as o])


(def same? @#'ct/same?)
(def load-fixture! @#'o/load-fixture!)
(def normalize @#'o/normalize)
(def cfg (assoc o/datomic-local-backend :ref-attrs #{:e :person/friend :note/session}))


(defn client-fn
  [s]
  (requiring-resolve (symbol "datomic.client.api" (name s))))


(def cl ((client-fn 'client) (:client cfg)))


(defn load-graph!
  "Graph nodes that only appear as values need to exist as entities: give
   every node a :node/id so the tempid is asserted as an entity too."
  [cl db-name fixture-id]
  (let [facts (fx/fixture-facts fixture-id)
        nodes (distinct (mapcat (fn [[a _ b]] [a b]) facts))
        tid (fn [n] (str "n" n))]
    ((client-fn 'create-database) cl {:db-name db-name})
    (let [conn ((client-fn 'connect) cl {:db-name db-name})]
      ((client-fn 'transact) conn
                             {:tx-data [{:db/ident :e :db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
                                        {:db/ident :node/id :db/valueType :db.type/long :db/cardinality :db.cardinality/one}]})
      (let [{:keys [tempids]} ((client-fn 'transact) conn
                                                     {:tx-data (into (mapv (fn [n] [:db/add (tid n) :node/id n]) nodes)
                                                                     (map (fn [[a _ b]] [:db/add (tid a) :e (tid b)]) facts))})]
        {:conn conn
         :logical->real (into {} (map (fn [n] [n (get tempids (tid n))])) nodes)}))))


(defn run-case
  [id]
  (let [c (fx/case-by-id id)
        fids (vec (distinct (keep #(get % "db") (get c "inputs"))))
        names (into {} (map-indexed (fn [i f] [f (str "recon-" id "-" i)])) fids)
        dbs (into {} (map (fn [f]
                            [f (if (= f "graph")
                                 (load-graph! cl (get names f) f)
                                 (load-fixture! cl (get names f) f (:ref-attrs cfg)))])) fids)
        l->r (apply merge (map :logical->real (vals dbs)))
        r->l (into {} (map (fn [[l r]] [r l])) l->r)
        sub (fn [x] (walk/postwalk #(if (and (integer? %) (contains? l->r %)) (get l->r %) %) x))
        args (mapv (fn [in]
                     (if (contains? in "db")
                       ((client-fn 'db) (get-in dbs [(get in "db") :conn]))
                       (sub (fx/decode (get in "value")))))
                   (get c "inputs"))
        res (try (let [raw ((client-fn 'q) {:query (sub (fx/case-query c)) :args args})]
                   [:result (normalize r->l raw)])
                 (catch Throwable e [:error (str (class e)) (ex-message e)]))]
    (doseq [f fids] (try ((client-fn 'delete-database) cl {:db-name (get names f)}) (catch Throwable _ nil)))
    (let [exp (fx/case-expected c)
          verdict (when (= :result (first res))
                    (if (same? (get c "comparison") exp (set (second res))) :AGREE :DIFFER))]
      (println id verdict)
      (when (not= verdict :AGREE)
        (println "  exp" (pr-str exp))
        (println "  obs" (pr-str res))))))


(doseq [id *command-line-args*] (run-case id))

(ns bench.dao-db-bench
  (:require
    [clojure.string :as str]
    [dao.db :as dao-db]
    [dao.db.datascript :as datascript-db]
    [dao.db.in-memory :as in-memory-db]))


;; =============================================================================
;; Benchmark Fixture
;; =============================================================================

(def ^:private person-start-eid 10000)
(def ^:private city-start-eid 2000)
(def ^:private org-start-eid 3000)


(def ^:private statuses
  [:person.status/active
   :person.status/inactive
   :person.status/pending
   :person.status/archived])


(def ^:private tags
  [:tag/clojure
   :tag/datomic
   :tag/stream
   :tag/agent
   :tag/query
   :tag/benchmark
   :tag/jfr
   :tag/datascript])


(def ^:private schema
  {:city/name     {:db/unique :db.unique/identity
                   :db/index true}
   :city/region   {:db/index true}
   :org/name      {:db/unique :db.unique/identity
                   :db/index true}
   :person/email  {:db/unique :db.unique/identity
                   :db/index true}
   :person/name   {:db/index true}
   :person/age    {:db/index true}
   :person/status {:db/index true}
   :person/city   {:db/valueType :db.type/ref
                   :db/index true}
   :person/org    {:db/valueType :db.type/ref
                   :db/index true}
   :person/tags   {:db/cardinality :db.cardinality/many
                   :db/index true}})


(defn- person-eid
  [i]
  (+ person-start-eid i))


(defn- city-eid
  [i]
  (+ city-start-eid i))


(defn- org-eid
  [i]
  (+ org-start-eid i))


(defn- person-email
  [i]
  (str "person-" i "@example.test"))


(defn- city-name
  [i]
  (str "city-" i))


(defn- org-name
  [i]
  (str "org-" i))


(defn- region
  [i]
  (keyword "region" (str "r" (mod i 8))))


(defn- city-count
  [entity-count]
  (max 1 (min 256 (long (Math/ceil (/ entity-count 32.0))))))


(defn- org-count
  [entity-count]
  (max 1 (min 64 (long (Math/ceil (/ entity-count 128.0))))))


(defn- dimension-tx
  [n-cities n-orgs]
  (vec
    (concat
      (mapcat (fn [i]
                [[:db/add (city-eid i) :city/name (city-name i)]
                 [:db/add (city-eid i) :city/region (region i)]])
              (range n-cities))
      (mapcat (fn [i]
                [[:db/add (org-eid i) :org/name (org-name i)]])
              (range n-orgs)))))


(defn- person-tx
  [entity-count n-cities n-orgs]
  (vec
    (mapcat (fn [i]
              (let [eid (person-eid i)]
                [[:db/add eid :person/email (person-email i)]
                 [:db/add eid :person/name (str "Person " i)]
                 [:db/add eid :person/age (+ 18 (mod i 67))]
                 [:db/add eid :person/status (nth statuses (mod i (count statuses)))]
                 [:db/add eid :person/city (city-eid (mod i n-cities))]
                 [:db/add eid :person/org (org-eid (mod i n-orgs))]
                 [:db/add eid :person/tags (nth tags (mod i (count tags)))]
                 [:db/add eid :person/tags (nth tags (mod (+ i 3) (count tags)))]]))
            (range entity-count))))


(defn- fixture
  [entity-count batch-size]
  (let [n-cities (city-count entity-count)
        n-orgs (org-count entity-count)
        dims (dimension-tx n-cities n-orgs)
        people (person-tx entity-count n-cities n-orgs)]
    {:entity-count entity-count
     :city-count n-cities
     :org-count n-orgs
     :dimension-tx dims
     :person-tx people
     :load-tx (into dims people)
     :person-batches (vec (partition-all (* 8 batch-size) people))}))


(defn- mixed-tx
  [{:keys [entity-count city-count org-count]} sample-count]
  (let [n (min sample-count entity-count)]
    (vec
      (mapcat (fn [i]
                (let [eid (person-eid i)
                      old-status (nth statuses (mod i (count statuses)))
                      new-status (nth statuses (mod (inc i) (count statuses)))
                      old-tag (nth tags (mod i (count tags)))]
                  [[:db/retract eid :person/status old-status]
                   [:db/add eid :person/status new-status]
                   [:db/add eid :person/age (+ 1000 i)]
                   [:db/add eid :person/city (city-eid (mod (+ i 7) city-count))]
                   [:db/add eid :person/org (org-eid (mod (+ i 3) org-count))]
                   [:db/retract eid :person/tags old-tag]
                   [:db/add eid :person/tags :tag/updated]]))
              (range n)))))


;; =============================================================================
;; Options
;; =============================================================================

(def ^:private default-opts
  {:entities 2500
   :batch-size 250
   :tx-sample 500
   :warmup 2
   :samples 5
   :tx-iters 1
   :batch-iters 1
   :query-iters 500})


(def ^:private quick-opts
  {:entities 1000
   :batch-size 100
   :tx-sample 200
   :warmup 1
   :samples 3
   :tx-iters 1
   :batch-iters 1
   :query-iters 100})


(defn- parse-long-arg
  [s]
  (Long/parseLong s))


(defn- parse-opts
  [args]
  (loop [opts default-opts
         remaining (seq args)]
    (if (empty? remaining)
      opts
      (let [arg (first remaining)
            value (second remaining)
            rest (nnext remaining)]
        (case arg
          "--quick" (recur (merge opts quick-opts) (next remaining))
          "--entities" (recur (assoc opts :entities (parse-long-arg value)) rest)
          "--batch-size" (recur (assoc opts :batch-size (parse-long-arg value)) rest)
          "--tx-sample" (recur (assoc opts :tx-sample (parse-long-arg value)) rest)
          "--warmup" (recur (assoc opts :warmup (parse-long-arg value)) rest)
          "--samples" (recur (assoc opts :samples (parse-long-arg value)) rest)
          "--tx-iters" (recur (assoc opts :tx-iters (parse-long-arg value)) rest)
          "--batch-iters" (recur (assoc opts :batch-iters (parse-long-arg value)) rest)
          "--query-iters" (recur (assoc opts :query-iters (parse-long-arg value)) rest)
          "--help" (assoc opts :help? true)
          (throw (ex-info "Unknown benchmark option" {:arg arg})))))))


(defn- usage
  []
  (str/join
    "\n"
    ["DaoDB benchmark"
     ""
     "Run:"
     "  clj -M:bench-dao-db"
     "  clj -M:profile-dao-db"
     "  clj -M:profile-dao-db-quick"
     ""
     "Options:"
     "  --quick                Smaller fixture for smoke tests and short JFR captures."
     "  --entities N           Person entities in the fixture."
     "  --batch-size N         Person entities per small transaction batch."
     "  --tx-sample N          Person entities touched by mixed update/retract tx."
     "  --warmup N             Warmup samples per benchmark case."
     "  --samples N            Measured samples per benchmark case."
     "  --tx-iters N           Bulk/mixed transaction invocations per sample."
     "  --batch-iters N        Small-batch transaction invocations per sample."
     "  --query-iters N        Query invocations per sample."
     "  --help                 Print this help."]))


;; =============================================================================
;; Timing Harness
;; =============================================================================

(defn- run-repeated
  [f n]
  (loop [i 0
         result nil]
    (if (< i n)
      (recur (inc i) (f))
      result)))


(defn- elapsed-nanos
  [f invocations]
  (let [start (System/nanoTime)
        result (run-repeated f invocations)
        end (System/nanoTime)]
    {:elapsed (- end start)
     :result result}))


(defn- median
  [xs]
  (let [xs (vec (sort xs))
        n (count xs)]
    (if (odd? n)
      (xs (quot n 2))
      (/ (+ (xs (dec (quot n 2))) (xs (quot n 2))) 2.0))))


(defn- nanos->seconds
  [nanos]
  (/ (double nanos) 1000000000.0))


(defn- nanos->millis
  [nanos]
  (/ (double nanos) 1000000.0))


(defn- format-rate
  [n]
  (format "%,.2f" (double n)))


(def ^:private bench-row-format
  "%-11s  %-28s  %12s  %14s  %18s  %10s  %10s%n")


(defn- ruler
  [n]
  (apply str (repeat n "-")))


(defn- print-bench-header!
  []
  (printf bench-row-format
          "backend"
          "scenario"
          "ms/sample"
          "inv/s"
          "throughput"
          "min ms"
          "max ms")
  (printf bench-row-format
          (ruler 11)
          (ruler 28)
          (ruler 12)
          (ruler 14)
          (ruler 18)
          (ruler 10)
          (ruler 10)))


(defn- print-bench!
  [backend label f {:keys [warmup samples invocations units-per-invocation unit-label]}]
  (dotimes [_ warmup]
    (elapsed-nanos f invocations))
  (let [measurements (vec (repeatedly samples #(elapsed-nanos f invocations)))
        elapsed (mapv :elapsed measurements)
        result (:result (peek measurements))
        med (median elapsed)
        min-ns (apply min elapsed)
        max-ns (apply max elapsed)
        invocations-per-sec (/ invocations (nanos->seconds med))
        units-per-sec (/ (* invocations units-per-invocation) (nanos->seconds med))]
    ;; Keep the measured value in the local causal path.
    (when (= ::unreachable result)
      (throw (ex-info "Unreachable benchmark result" {:label label})))
    (printf bench-row-format
            backend
            label
            (format "%.3f" (nanos->millis med))
            (format-rate invocations-per-sec)
            (str (format-rate units-per-sec) " " unit-label "/s")
            (format "%.3f" (nanos->millis min-ns))
            (format "%.3f" (nanos->millis max-ns)))))


;; =============================================================================
;; Backend Workloads
;; =============================================================================

(defn- create-loaded-db
  [create-db fixture]
  (:db-after (dao-db/transact (create-db schema) (:load-tx fixture))))


(defn- transact-small-batches
  [create-db {:keys [dimension-tx person-batches]}]
  (let [dimension-db (:db-after (dao-db/transact (create-db schema) dimension-tx))]
    (reduce (fn [db batch]
              (:db-after (dao-db/transact db batch)))
            dimension-db
            person-batches)))


(defn- expect-count!
  [backend label expected actual]
  (let [actual-count (count actual)]
    (when-not (= expected actual-count)
      (throw (ex-info "Benchmark query returned unexpected cardinality"
                      {:backend backend
                       :label label
                       :expected expected
                       :actual actual-count
                       :sample actual})))))


(defn- matching-count
  [entity-count pred]
  (count (filter pred (range entity-count))))


(defn- validate-query-contracts!
  [backend db {:keys [entity-count city-count]}]
  (let [target-i (quot entity-count 2)
        target-email (person-email target-i)
        target-city (city-eid (mod target-i city-count))
        target-status :person.status/active
        target-tag :tag/clojure
        status-count (matching-count entity-count #(= target-status (nth statuses (mod % (count statuses)))))
        city-count-result (matching-count entity-count #(= target-city (city-eid (mod % city-count))))
        status-city-count (matching-count entity-count
                                          #(and (= target-status (nth statuses (mod % (count statuses))))
                                                (= target-city (city-eid (mod % city-count)))))
        tag-count (matching-count entity-count
                                  #(or (= target-tag (nth tags (mod % (count tags))))
                                       (= target-tag (nth tags (mod (+ % 3) (count tags))))))]
    (expect-count! backend "q indexed email" 1
                   (dao-db/q '[:find ?e :in $ ?email :where [?e :person/email ?email]]
                             db target-email))
    (expect-count! backend "q indexed status" status-count
                   (dao-db/q '[:find ?e :in $ ?status :where [?e :person/status ?status]]
                             db target-status))
    (expect-count! backend "q ref by city" city-count-result
                   (dao-db/q '[:find ?e :in $ ?city :where [?e :person/city ?city]]
                             db target-city))
    (expect-count! backend "q status+city join" status-city-count
                   (dao-db/q '[:find ?e :in $ ?status ?city
                               :where [?e :person/status ?status]
                               [?e :person/city ?city]]
                             db target-status target-city))
    (expect-count! backend "q card-many tag" tag-count
                   (dao-db/q '[:find ?e :in $ ?tag :where [?e :person/tags ?tag]]
                             db target-tag))
    (expect-count! backend "datoms eavt entity" 8
                   (dao-db/datoms db :eavt (person-eid target-i)))
    (expect-count! backend "datoms avet email" 1
                   (dao-db/datoms db :avet :person/email target-email))
    (when-not (= target-email (:person/email (dao-db/entity-attrs db (person-eid target-i))))
      (throw (ex-info "entity-attrs returned unexpected entity"
                      {:backend backend :eid (person-eid target-i)})))
    (when-not (= target-email (:person/email (dao-db/pull db [:person/email] (person-eid target-i))))
      (throw (ex-info "pull returned unexpected entity"
                      {:backend backend :eid (person-eid target-i)})))))


(defn- bench-backend!
  [{:keys [name create-db]} fixture opts]
  (let [{:keys [load-tx person-batches entity-count city-count]} fixture
        {:keys [warmup samples tx-iters batch-iters query-iters tx-sample]} opts
        empty-db (create-db schema)
        loaded-db (create-loaded-db create-db fixture)
        update-tx (mixed-tx fixture tx-sample)
        target-i (quot entity-count 2)
        target-eid (person-eid target-i)
        target-email (person-email target-i)
        target-city (city-eid (mod target-i city-count))
        target-status :person.status/active
        target-tag :tag/clojure
        tx-opts {:warmup warmup
                 :samples samples
                 :invocations tx-iters
                 :units-per-invocation (count load-tx)
                 :unit-label "datom"}
        mixed-opts {:warmup warmup
                    :samples samples
                    :invocations tx-iters
                    :units-per-invocation (count update-tx)
                    :unit-label "datom"}
        batch-opts {:warmup warmup
                    :samples samples
                    :invocations batch-iters
                    :units-per-invocation (count person-batches)
                    :unit-label "tx"}
        query-opts {:warmup warmup
                    :samples samples
                    :invocations query-iters
                    :units-per-invocation 1
                    :unit-label "query"}]
    (println)
    (println (str "Backend: " name))
    (validate-query-contracts! name loaded-db fixture)
    (print-bench-header!)
    (print-bench! name "transact bulk load"
                  #(:db-after (dao-db/transact empty-db load-tx))
                  tx-opts)
    (print-bench! name "with bulk load"
                  #(:db-after (dao-db/with empty-db load-tx))
                  tx-opts)
    (print-bench! name "transact small batches"
                  #(transact-small-batches create-db fixture)
                  batch-opts)
    (print-bench! name "transact mixed update"
                  #(:db-after (dao-db/transact loaded-db update-tx))
                  mixed-opts)
    (print-bench! name "q indexed email"
                  #(dao-db/q '[:find ?e :in $ ?email :where [?e :person/email ?email]]
                             loaded-db target-email)
                  query-opts)
    (print-bench! name "q indexed status"
                  #(dao-db/q '[:find ?e :in $ ?status :where [?e :person/status ?status]]
                             loaded-db target-status)
                  query-opts)
    (print-bench! name "q ref by city"
                  #(dao-db/q '[:find ?e :in $ ?city :where [?e :person/city ?city]]
                             loaded-db target-city)
                  query-opts)
    (print-bench! name "q status+city join"
                  #(dao-db/q '[:find ?e :in $ ?status ?city
                               :where [?e :person/status ?status]
                               [?e :person/city ?city]]
                             loaded-db target-status target-city)
                  query-opts)
    (print-bench! name "q card-many tag"
                  #(dao-db/q '[:find ?e :in $ ?tag :where [?e :person/tags ?tag]]
                             loaded-db target-tag)
                  query-opts)
    (print-bench! name "datoms eavt entity"
                  #(count (dao-db/datoms loaded-db :eavt target-eid))
                  query-opts)
    (print-bench! name "datoms avet email"
                  #(count (dao-db/datoms loaded-db :avet :person/email target-email))
                  query-opts)
    (print-bench! name "entity attrs"
                  #(dao-db/entity-attrs loaded-db target-eid)
                  query-opts)
    (print-bench! name "pull profile"
                  #(dao-db/pull loaded-db [:db/id :person/email :person/name
                                           {:person/city [:city/name]}]
                                target-eid)
                  query-opts)))


(defn -main
  [& args]
  (let [opts (parse-opts args)]
    (if (:help? opts)
      (println (usage))
      (let [fixture (fixture (:entities opts) (:batch-size opts))
            backends [{:name "in-memory"
                       :create-db in-memory-db/create}
                      {:name "datascript"
                       :create-db datascript-db/create}]]
        (println "DaoDB query and transaction benchmark")
        (println (str "entities=" (:entities opts)
                      ", cities=" (:city-count fixture)
                      ", orgs=" (:org-count fixture)
                      ", load-datoms=" (count (:load-tx fixture))
                      ", batches=" (count (:person-batches fixture))))
        (println (str "samples=" (:samples opts)
                      ", warmup=" (:warmup opts)
                      ", tx-iters=" (:tx-iters opts)
                      ", batch-iters=" (:batch-iters opts)
                      ", query-iters=" (:query-iters opts)))
        (println "Tip: use clj -M:profile-dao-db or clj -M:profile-dao-db-quick to emit a JFR recording.")
        (doseq [backend backends]
          (bench-backend! backend fixture opts))
        (println)
        (println "Benchmark complete.")
        (println "JFR profile aliases write to /tmp/datomworld-dao-db-bench.jfr or /tmp/datomworld-dao-db-bench-quick.jfr.")))))

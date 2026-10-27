(ns dao.space.datomic-query-oracle
  "JVM-only oracle runner for the datomic-q-v1 corpus.

   Executes the public `datomic.client.api/q` for oracle-eligible cases and
   writes CANDIDATE observations to a separate output file. It never reads
   the frozen corpus for write, never rewrites expectations, and refuses an
   output path under test/resources. Promotion of an observation into the
   corpus is a reviewed human step (datomic-q-v1.README.md).

   The Datomic Client backend is NOT yet selected. The backend is an explicit
   parameter: `--backend unselected` (the default) reports every eligible case
   as a pending observation without touching any Datomic code; `--backend
   <edn-map>` supplies a Client configuration:

     {:client {:server-type :dev-local :system \"datomic-q-v1\"}
      :db-name \"datomic-q-v1-oracle\"
      :ref-attrs #{:person/friend :note/session}}

   :client is passed to datomic.client.api/client. Each fixture gets its own
   disposable database (created, loaded, queried, deleted). A Peer runner, a
   mock or a DaoSpace-generated answer is never a substitute for this oracle.

   Fixture loading here is independent of the DaoSpace loader. Schema is
   derived from the logical facts: value type from the values, cardinality
   many when one entity repeats an attribute, and :db.type/ref only for
   attributes listed in :ref-attrs (the corpus records no schema).

   `--backend datomic-local` selects Datomic Local (:server-type
   :datomic-local, :storage-dir :mem), the owner-chosen oracle backend; the
   observation document records the Datomic jar versions actually loaded.

   Run: clj -M:oracle -m dao.space.datomic-query-oracle --backend unselected
        clj -M:oracle -m dao.space.datomic-query-oracle --backend datomic-local"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [dao.space.datomic-query-fixtures :as fx]))


(def default-output "target/datomic-q-v1-observations.edn")


(def ^:private ineligible-points
  "Points with no Client q equivalent to execute: the internal db-first
   arity, raw DaoSpace sources, and DaoSpace d5 views."
  #{"api.db-first-refused" "source.raw-refused" "source.daospace-values"})


(defn oracle-eligible?
  [c]
  (and (not (contains? ineligible-points (get c "point")))
       (every? #(= "facts" (get-in (fx/fixtures) [(get % "db") "view"] "facts"))
               (filter #(contains? % "db") (get c "inputs")))))


(defn- safe-output!
  [path]
  (let [p (.getPath (.getCanonicalFile (io/file path)))]
    (when (or (str/includes? p "/test/resources/")
              (str/ends-with? p "datomic-q-v1.json"))
      (throw (ex-info "refusing to write observations into the frozen corpus"
                      {:path path})))
    path))


;; ---------------------------------------------------------------------------
;; Unselected backend: honest pending observations
;; ---------------------------------------------------------------------------

(defn- pending-observation
  [c reason]
  {:id (get c "id")
   :observation :pending
   :reason reason
   :api "datomic.client.api/q"
   :api-version "1.0.69"
   :backend :unselected})


;; ---------------------------------------------------------------------------
;; Selected backend: independent Datomic loader and execution
;; ---------------------------------------------------------------------------

(defn- client-fn
  [sym]
  (or (requiring-resolve (symbol "datomic.client.api" (name sym)))
      (throw (ex-info "datomic.client.api is unavailable; use the :oracle alias"
                      {:fn sym}))))


(defn- value-type
  [v]
  (cond (keyword? v) :db.type/keyword
        (string? v) :db.type/string
        (integer? v) :db.type/long
        (float? v) :db.type/double
        (decimal? v) :db.type/bigdec
        (instance? clojure.lang.BigInt v) :db.type/bigint
        :else (throw (ex-info "no Datomic type for fixture value" {:value v}))))


(defn- schema-for
  [facts ref-attrs]
  (->> facts
       (group-by second)
       (map (fn [[a rows]]
              (let [many? (some (fn [[_ n]] (> n 1))
                                (frequencies (map first rows)))]
                {:db/ident a
                 :db/valueType (if (contains? ref-attrs a)
                                 :db.type/ref
                                 (value-type (nth (first rows) 2)))
                 :db/cardinality (if many? :db.cardinality/many :db.cardinality/one)})))
       vec))


(defn- load-fixture!
  "Create a disposable database and load fixture facts under tempids.
   Returns {:conn .. :logical->real {..}}."
  [client db-name fixture-id ref-attrs]
  (let [create (client-fn 'create-database)
        connect (client-fn 'connect)
        transact (client-fn 'transact)
        facts (fx/fixture-facts fixture-id)
        _ (create client {:db-name db-name})
        conn (connect client {:db-name db-name})
        _ (transact conn {:tx-data (schema-for facts ref-attrs)})
        tempid (fn [e] (str "e" e))
        data (mapv (fn [[e a v]]
                     [:db/add (tempid e) a (if (and (contains? ref-attrs a) (integer? v))
                                             (tempid v)
                                             v)])
                   facts)
        {:keys [tempids]} (transact conn {:tx-data data})]
    {:conn conn
     :logical->real (into {} (map (fn [e] [e (get tempids (tempid e))]))
                          (distinct (map first facts)))}))


(defn- normalize
  "Map real entity ids back to logical ids anywhere in a result (pull
   :db/id, reference values, tuple cells). Real ids are large allocated
   numbers, so a plain value is never mistaken for one."
  [real->logical x]
  (cond
    (and (integer? x) (contains? real->logical x)) (get real->logical x)
    (map? x) (into {} (map (fn [[k v]] [k (normalize real->logical v)])) x)
    (vector? x) (mapv #(normalize real->logical %) x)
    (set? x) (set (map #(normalize real->logical %) x))
    (sequential? x) (mapv #(normalize real->logical %) x)
    :else x))


(defn- input-value
  "The Client-side value of one case input: a Datomic db for a fixture, else
   the decoded datum."
  [dbs in]
  (if (contains? in "db")
    ((client-fn 'db) (get-in dbs [(get in "db") :conn]))
    (fx/decode (get in "value"))))


(defn- observe-selected
  "Execute one case through datomic.client.api/q against the configured
   backend. Every fixture the case names gets its own disposable database,
   deleted afterwards."
  [{:keys [client db-name ref-attrs] :or {ref-attrs #{}}} c]
  (let [cl ((client-fn 'client) client)
        delete (client-fn 'delete-database)
        fixture-ids (vec (distinct (keep #(get % "db") (get c "inputs"))))
        names (into {} (map-indexed (fn [i fid] [fid (str db-name "-" i)])) fixture-ids)
        base {:id (get c "id")
              :api "datomic.client.api/q"
              :api-version "1.0.69"
              :backend client
              :arg-map-options (fx/case-options c)}
        error (fn [phase e]
                (assoc base
                       :observation :error
                       :phase phase
                       :error-class (.getName (class e))
                       :message (ex-message e)
                       :data (ex-data e)))]
    (try
      ;; Throwable throughout: a backend StackOverflowError is an observation
      ;; too. A fixture Datomic cannot hold (for example one attribute with
      ;; both long and double values) is a :fixture-load observation for
      ;; every case that names it, not a runner failure.
      (try
        (let [dbs (into {} (map (fn [fid]
                                  [fid (load-fixture! cl (get names fid) fid ref-attrs)]))
                        fixture-ids)
              real->logical (into {} (for [{:keys [logical->real]} (vals dbs)
                                           [l r] logical->real]
                                       [r l]))
              arg-map (merge {:query (fx/case-query c)
                              :args (mapv #(input-value dbs %) (get c "inputs"))}
                             (fx/case-options c))]
          (try
            (let [raw ((client-fn 'q) arg-map)]
              (assoc base
                     :observation :result
                     :raw raw
                     :normalized (normalize real->logical raw)))
            (catch Throwable e (error :query e))))
        (catch Throwable e (error :fixture-load e)))
      (finally
        (doseq [fid fixture-ids]
          (try (delete cl {:db-name (get names fid)})
               (catch Exception _ nil)))))))


(defn- jar-versions
  "Versions of the Datomic jars actually on the classpath, so an observation
   file records what ran rather than what was requested."
  []
  (into (sorted-map)
        (keep (fn [p]
                (when-let [[_ a v] (re-find #"/com/datomic/([^/]+)/([^/]+)/[^/]+\.jar$" p)]
                  [a v])))
        (str/split (System/getProperty "java.class.path") #":")))


(defn run
  "Observe every eligible case (or the one named by :only). Returns the
   candidate-observation document. Never writes."
  [{:keys [backend only]}]
  (let [cases (cond->> (fx/cases) only (filter #(= only (get % "id"))))
        eligible (filter oracle-eligible? cases)
        skipped (remove oracle-eligible? cases)]
    {:corpus "datomic-q-v1"
     :candidate? true
     :note "candidate observations only; never promoted automatically"
     :backend (if (= :unselected backend) :unselected (:client backend))
     :datomic-jars (jar-versions)
     :observations
     (mapv (fn [c]
             (if (= :unselected backend)
               (pending-observation c "Datomic Client backend is not selected")
               (observe-selected backend c)))
           eligible)
     :not-eligible (mapv #(get % "id") skipped)}))


(def datomic-local-backend
  "`--backend datomic-local`: Datomic Local (com.datomic/local, in-process)
   through the public client API. :storage-dir :mem is the documented CI
   mode; nothing touches disk and no FileChannel is involved."
  {:client {:server-type :datomic-local
            :storage-dir :mem
            :system "datomic-q-v1"}
   :db-name "datomic-q-v1-oracle"
   :ref-attrs #{:person/friend :note/session}})


(defn- parse-args
  [args]
  (loop [m {:backend :unselected :out default-output}
         [a v & more :as all] args]
    (cond
      (empty? all) m
      (= "--backend" a) (recur (assoc m :backend (case v
                                                   "unselected" :unselected
                                                   "datomic-local" datomic-local-backend
                                                   (edn/read-string v)))
                               more)
      (= "--out" a) (recur (assoc m :out v) more)
      (= "--case" a) (recur (assoc m :only v) more)
      :else (throw (ex-info "unknown argument" {:arg a})))))


(defn -main
  [& args]
  (let [{:keys [out] :as opts} (parse-args args)
        path (safe-output! out)
        doc (run opts)
        counts (frequencies (map :observation (:observations doc)))]
    (io/make-parents path)
    (spit path (pr-str doc))
    (println "datomic-q-v1 oracle:"
             (if (= :unselected (:backend doc))
               "backend UNSELECTED; observations are pending"
               "backend selected"))
    (println "eligible cases:" (count (:observations doc))
             "not eligible:" (count (:not-eligible doc)))
    (println "observations by kind:" counts)
    (println "candidate observations written to" path
             "(the frozen corpus is untouched)")))

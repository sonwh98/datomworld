(ns dao.wordpress.corpus
  "W0a converter: raw corpus-run output -> frozen fixtures
   (docs/design/dao.wordpress.md section 11, slice W0a).

   Run once, on the laptop, never in a test, after docker/wp-corpus/run-corpus.sh
   and the copy-back of its OUT dir:

     clj -M:dev -m dao.wordpress.corpus RAW-DIR TAG TARBALL-SHA256

   writes test/fixtures/wordpress/TAG/ (pin.edn, session.edn, escape.edn,
   reconciliation.edn, manifest.edn, corpus/<tier>/<nn>-<name>.edn,
   schema/after-<tier>.sql) and refuses to overwrite an existing TAG: a frozen
   corpus is only replaced by a deliberate new TAG. The fixture schema is
   checked by test/dao/wordpress/corpus_fixtures.cljc."
  (:require
    [clojure.data.json :as json]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.pprint :as pp]
    [clojure.string :as str])
  (:import
    (java.security
      MessageDigest)))


(def fixtures-root "test/fixtures/wordpress/")


(defn- sha-256
  [^bytes bs]
  (apply str
         (map #(format "%02x" (bit-and % 0xff))
              (.digest (MessageDigest/getInstance "SHA-256") bs))))


(defn- resolve-hex
  "JSON {\"hex\" h} (a non-UTF-8 string) -> the fixture marker; other values pass."
  [v]
  (cond
    (and (map? v) (= #{"hex"} (set (keys v)))) {:fixture/bytes (get v "hex")}
    (map? v) (update-vals v resolve-hex)
    (sequential? v) (mapv resolve-hex v)
    :else v))


(defn sql-kind
  [sql]
  (let [w (some-> (re-find #"(?i)^\s*(?:/\*.*?\*/\s*)?([a-z]+)" (or sql ""))
                  second
                  str/lower-case)]
    (cond
      (#{"select" "show" "describe" "desc" "explain"} w) :select
      (#{"insert" "update" "delete" "replace"} w) :dml
      (#{"create" "alter" "drop" "truncate" "rename"} w) :ddl
      :else :other)))


(defn- read-jsonl
  [^java.io.File f]
  (with-open [r (io/reader f)]
    (->> (line-seq r)
         (remove str/blank?)
         (mapv #(resolve-hex (json/read-str %)))
         (sort-by #(get % "seq"))
         vec)))


(defn- column
  [c]
  {:name (get c "name"), :table (get c "table"), :type (get c "type")
   :flags (get c "flags"), :length (get c "length"), :decimals (get c "decimals")
   :charsetnr (get c "charsetnr")})


(defn- query-event
  [i e]
  (let [r (get e "result")]
    (cond-> {:seq i
             :kind (sql-kind (get e "sql"))
             :caller (get e "caller")
             :sql (get e "sql")}
      (get e "prepare")
      (assoc :prepare {:template (get-in e ["prepare" "template"])
                       :args (vec (get-in e ["prepare" "args"]))})
      (get e "error")
      (assoc :error {:errno (get-in e ["error" "errno"])
                     :text (get-in e ["error" "text"])})
      r
      (assoc :result
             (cond-> {:rows-affected (get r "rows-affected")
                      :insert-id (get r "insert-id")
                      :num-rows (get r "num-rows")}
               (get r "columns")
               (assoc :columns (mapv column (get r "columns"))
                      :rows (vec (get r "rows"))))))))


(defn- exactly-one-outcome
  "wpdb sometimes records neither (no mysqli error and no result map); the schema
   requires exactly one, so a bare event becomes an empty result."
  [e]
  (if (or (:result e) (:error e))
    e
    (assoc e :result {:rows-affected 0 :insert-id 0 :num-rows 0})))


(defn- request-file
  [id]
  (let [[_ tier nn nm] (re-matches #"(t\d)-(\d+)-(.*)" id)]
    (str "corpus/" tier "/" nn "-" nm ".edn")))


(defn- write-edn!
  [path v]
  (io/make-parents path)
  (spit path (with-out-str (binding [*print-length* nil *print-level* nil] (pp/pprint v)))))


(defn- parse-general-log
  "MySQL general log text -> vector of whitespace-normalised Query statements."
  [text]
  (let [start #"^\d{4}-\d\d-\d\dT[\d:.]+Z\s+\d+\s+(\w+)\s*(.*)$"]
    (loop [ls (str/split-lines text), cur nil, out []]
      (if-let [l (first ls)]
        (if-let [[_ cmd rest*] (re-matches start l)]
          (recur (next ls)
                 (when (= cmd "Query") [rest*])
                 (cond-> out cur (conj cur)))
          (recur (next ls) (when cur (conj cur l)) out))
        (->> (cond-> out cur (conj cur))
             (mapv #(str/trim (str/replace (str/join " " %) #"\s+" " "))))))))


(defn- norm
  [s]
  (str/trim (str/replace (or s "") #"\s+" " ")))


(defn- reconcile
  "Match every logged Query to a recorder event; the remainder is frozen."
  [logged recorded]
  (let [rec (frequencies (map norm recorded))
        log (frequencies logged)
        unrecorded (into (sorted-map)
                         (for [[s n] log
                               :let [d (- n (get rec s 0))]
                               :when (pos? d)]
                           [s d]))
        unlogged (into (sorted-map)
                       (for [[s n] rec
                             :let [d (- n (get log s 0))]
                             :when (pos? d)]
                         [s d]))]
    {:logged (count logged)
     :recorded (count recorded)
     :unrecorded unrecorded
     :recorded-not-logged unlogged}))


(defn- pin
  [raw tag tarball-sha]
  (let [lines (str/split-lines (slurp (io/file raw "pin-raw.txt")))
        sect (fn [h] (->> lines (drop-while #(not= % (str "# " h))) rest (take-while #(not (str/starts-with? % "#")))))
        [mysql-v sql-mode coll tz] (str/split (or (first (sect "mysql")) "") #"\t")
        [php-v exts] (sect "php")]
    {:tag tag
     :wordpress {:version (second (re-find #"'([^']+)'" (slurp (io/file raw "wp-version"))))
                 :tarball-sha256 tarball-sha}
     :images {:mysql (first (sect "images")) :php (second (sect "images"))}
     :mysql {:version mysql-v :sql-mode sql-mode :collation-connection coll :time-zone tz}
     :php {:version php-v :extensions (vec (sort (str/split (or exts "") #",")))}}))


(defn convert!
  [raw tag tarball-sha]
  (let [root (str fixtures-root tag "/")]
    (when (.exists (io/file root))
      (throw (ex-info "frozen corpus exists; use a new TAG" {:root root})))
    (let [jsonl (->> (.listFiles (io/file raw "raw"))
                     (filter #(str/ends-with? (.getName %) ".jsonl"))
                     (sort-by #(.getName %)))
          by-id (into {} (for [f jsonl
                               :let [evs (read-jsonl f)
                                     h (first (filter #(= "request" (get % "kind")) evs))]]
                           [(or (get h "id") (str/replace (.getName f) #"\.jsonl$" "")) {:header h :events evs}]))
          reqs (->> ["t0" "t1" "t2"]
                    (mapcat #(:requests (edn/read-string (slurp (io/file raw (str "requests-" % ".edn"))))))
                    vec)
          tagged (set (map :id reqs))
          escapes (atom (sorted-set))
          sessions (atom (sorted-map))
          recorded (atom [])
          manifest (atom [])]
      (doseq [{:keys [id method uri status response-sha256]} reqs
              :let [{:keys [header events]} (get by-id id)
                    qs (->> events (filter #(= "query" (get % "kind"))) (map-indexed (fn [i e] (exactly-one-outcome (query-event (inc i) e)))) vec)
                    file (request-file id)]]
        (doseq [e events]
          (case (get e "kind")
            "escape" (swap! escapes conj [(get e "in") (get e "out")])
            "session" (swap! sessions update id (fnil conj []) (dissoc e "seq"))
            "query" (swap! recorded conj (get e "sql"))
            nil))
        (write-edn! (str root file)
                    {:request {:id id :method method :uri uri :status status
                               :response-sha256 response-sha256
                               :server (some-> (get header "server") (update-keys keyword))}
                     :events qs})
        (swap! manifest conj {:file file :id id :events (count qs)}))
      (let [untagged (->> by-id (remove (fn [[id _]] (tagged id)))
                          (map (fn [[id {:keys [events]}]]
                                 {:id id :queries (count (filter #(= "query" (get % "kind")) events))}))
                          (sort-by :id) vec)
            _ (doseq [[id {:keys [events]}] by-id :when (not (tagged id))
                      e events :when (= "query" (get e "kind"))]
                (swap! recorded conj (get e "sql")))
            gl (io/file raw "raw" "mysql-general.log")
            logged (if (.exists gl) (parse-general-log (slurp gl)) [])]
        (write-edn! (str root "reconciliation.edn")
                    (assoc (reconcile logged @recorded) :untagged-requests untagged))
        (write-edn! (str root "escape.edn") (mapv (fn [[i o]] {:in i :out o}) @escapes))
        (write-edn! (str root "session.edn") @sessions)
        (write-edn! (str root "pin.edn") (pin raw tag tarball-sha))
        (doseq [f (.listFiles (io/file raw "schema"))]
          (io/make-parents (str root "schema/x"))
          (io/copy f (io/file root "schema" (.getName f))))
        (write-edn! (str root "manifest.edn")
                    {:tag tag
                     :requests (vec @manifest)
                     :totals {:requests (count @manifest)
                              :events (reduce + (map :events @manifest))}})
        (let [files (->> (file-seq (io/file root)) (filter #(.isFile %)) (sort-by str))]
          (write-edn! (str root "provenance.edn")
                      {:algorithm "SHA-256"
                       :files (into (sorted-map)
                                    (for [f files
                                          :let [p (subs (str f) (count root))]
                                          :when (not= p "provenance.edn")]
                                      [p (sha-256 (java.nio.file.Files/readAllBytes (.toPath f)))]))}))))))


(defn -main
  [& [raw tag sha]]
  (when-not (and raw tag sha)
    (println "usage: dao.wordpress.corpus RAW-DIR TAG TARBALL-SHA256")
    (System/exit 2))
  (convert! raw tag sha)
  (println "wrote" (str fixtures-root tag "/") "- now write README.md and errata.md by hand")
  (shutdown-agents))

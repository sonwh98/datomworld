(ns dao.db.file
  "FileDaoDB: file-persisted DaoDB. Immutable database-as-value with the transaction
   log persisted to a file. Indexes are reconstructed in memory on open.

   File format: append-only EDN, one segment per line:
     [t [datom-vectors...] [datom-vectors...]]
   Datom-vectors are [e a v t m] for EDN portability.

   Cross-platform file I/O:
     JVM:       clojure.java.io + spit/slurp + edn/read-string
     Node.js:   fs module (sync methods)
     Dart:      dart:io File class (sync methods)
     Browser:   localStorage (sync key-value store, path as key prefix)

   Values must be EDN-round-trippable: strings, keywords, numbers, booleans, and
   homogeneous vectors/maps thereof. Custom types require reader tags."
  (:require
    #?@(:cljd [["dart:io" :as dart-io]]
        :cljs []
        :clj [[clojure.java.io :as io]])
    #?@(:cljs [[cljs.reader :as edn]]
        :default [[clojure.edn :as edn]])
    [clojure.string :as str]
    [dao.db :as dao-db :refer
     [IDaoStorage IDaoTransactor IDaoQueryEngine IDaoDB]]
    [dao.db.in-memory :as in-m]))


;; =============================================================================
;; Datom serialization — vectors for EDN portability
;; =============================================================================

(defn- datom->vec
  [d]
  [(:e d) (:a d) (:v d) (:t d) (:m d)])


(defn- vec->datom
  [[e a v t m]]
  (dao-db/->datom e a v t m))


(defn- datoms->vecs
  [datoms]
  (mapv datom->vec datoms))


(defn- vecs->datoms
  [vecs]
  (mapv vec->datom vecs))


(defn- segment->file-form
  [[t added retracted]]
  [t (datoms->vecs added) (datoms->vecs retracted)])


(defn- segment-from-file-form
  [[t added-vecs retracted-vecs]]
  [t (vecs->datoms added-vecs) (vecs->datoms retracted-vecs)])


;; =============================================================================
;; Platform-specific file I/O
;; =============================================================================

#?(:clj (do (defn- file-exists?
              [path]
              (.exists (io/file path)))
            (defn- read-file-str
              [path]
              (when (file-exists? path) (slurp path)))
            (defn- append-file-str
              [path s]
              (spit path s :append true))
            (defn- write-file-str
              [path s]
              (spit path s))))


#?(:cljs (let [node-fs (when (exists? js/process) (js/require "fs"))]
           (defn- file-exists?
             [path]
             (if node-fs
               (.existsSync node-fs path)
               (not (nil? (.getItem js/localStorage (str "dao:file:" path))))))
           (defn- read-file-str
             [path]
             (if node-fs
               (when (.existsSync node-fs path)
                 (.readFileSync node-fs path "utf8"))
               (.getItem js/localStorage (str "dao:file:" path))))
           (defn- append-file-str
             [path s]
             (if node-fs
               (.appendFileSync node-fs path s "utf8")
               (let [key (str "dao:file:" path)
                     existing (.getItem js/localStorage key)]
                 (.setItem js/localStorage key (str (or existing "") s)))))
           (defn- write-file-str
             [path s]
             (if node-fs
               (.writeFileSync node-fs path s "utf8")
               (.setItem js/localStorage (str "dao:file:" path) s)))))


#?(:cljd (do (defn- file-exists?
               [path]
               (.existsSync (dart-io/File. path)))
             (defn- read-file-str
               [path]
               (when (file-exists? path)
                 (.readAsStringSync (dart-io/File. path))))
             (defn- append-file-str
               [path s]
               (.writeAsStringSync (dart-io/File. path)
                                   s
                                   .mode
                                   dart-io/FileMode.append))
             (defn- write-file-str
               [path s]
               (.writeAsStringSync (dart-io/File. path) s))))


;; =============================================================================
;; Log file operations
;; =============================================================================

(defn- read-log-from-file
  [path]
  (when (file-exists? path)
    (let [content (read-file-str path)]
      (when (seq content)
        (->> (str/split-lines content)
             (remove str/blank?)
             (mapv (comp segment-from-file-form edn/read-string)))))))


(defn- init-file-log
  [path log]
  (let [non-bootstrap (rest log)]
    (when (seq non-bootstrap)
      (let [content (str/join "\n"
                              (map (comp pr-str segment->file-form)
                                   non-bootstrap))]
        (write-file-str path (str content "\n"))))))


(defn- append-segment-to-file
  [path segment]
  (append-file-str path (str (pr-str (segment->file-form segment)) "\n")))


(defn- replay-file-log
  [file-log]
  (when (seq file-log)
    (reduce (fn [db [t added retracted]]
              (-> db
                  (update :log conj [t (vec added) (vec retracted)])
                  (as-> d (reduce in-m/add-datom-to-indexes d added))
                  (as-> d (reduce in-m/retract-datom-from-indexes d retracted))
                  (update :next-t #(max % (inc t)))))
            (in-m/empty-db)
            file-log)))


(defn- full-caches-from-file-log
  [db file-log]
  (let [{:keys [schema ref-attrs card-many unique-attrs indexed-attrs]}
        (in-m/build-schema-cache (:eavt db))
        max-imported-eid
        (reduce (fn [mx [_t added retracted]]
                  (reduce
                    (fn [mx d]
                      (let [e (:e d)
                            v (:v d)
                            a (:a d)
                            m (:m d)
                            mx (if (and (integer? e) (pos? e)) (max mx e) mx)
                            mx (if (and (contains? ref-attrs a)
                                        (integer? v)
                                        (pos? v))
                                 (max mx v)
                                 mx)
                            mx (if (and (integer? m) (pos? m)) (max mx m) mx)]
                        mx))
                    mx
                    (concat added retracted)))
                1024
                file-log)]
    (-> db
        (assoc :schema schema
               :ref-attrs ref-attrs
               :card-many card-many
               :unique-attrs unique-attrs
               :indexed-attrs indexed-attrs
               :next-eid (max (:next-eid db) (inc max-imported-eid)))
        in-m/rebuild-secondary-indexes)))


;; =============================================================================
;; FileDaoDB record
;; =============================================================================

(defrecord FileDaoDB
  [eavt aevt avet vaet meat log schema next-t next-eid
   ref-attrs card-many unique-attrs indexed-attrs file-path])


;; =============================================================================
;; Factory
;; =============================================================================

(defn create
  ([file-path] (create file-path {}))
  ([file-path schema]
   (if (file-exists? file-path)
     (let [file-log (read-log-from-file file-path)
           replayed (replay-file-log file-log)
           base-db (if replayed
                     (full-caches-from-file-log replayed file-log)
                     (in-m/empty-db))]
       (map->FileDaoDB (assoc base-db :file-path file-path)))
     (let [in-m-db (in-m/create schema)]
       (init-file-log file-path (:log in-m-db))
       (map->FileDaoDB (assoc in-m-db :file-path file-path))))))


;; =============================================================================
;; Protocol Implementations
;; =============================================================================

(extend-type FileDaoDB
  IDaoStorage
  (write-segment! [this [t added retracted]]
    (let [segment [t (vec added) (vec retracted)]]
      (append-segment-to-file (:file-path this) segment)
      (let [db' (-> this
                    (update :log conj segment)
                    (as-> db (reduce in-m/add-datom-to-indexes db added))
                    (as-> db
                      (reduce in-m/retract-datom-from-indexes db retracted))
                    (update :next-t #(max % (inc t))))
            {:keys [schema ref-attrs card-many unique-attrs indexed-attrs]}
            (in-m/build-schema-cache (:eavt db'))
            max-imported-eid
            (reduce
              (fn [mx d]
                (let [e (:e d)
                      v (:v d)
                      a (:a d)
                      m (:m d)
                      mx (if (and (integer? e) (pos? e)) (max mx e) mx)
                      mx (if (and (contains? ref-attrs a)
                                  (integer? v)
                                  (pos? v))
                           (max mx v)
                           mx)
                      mx (if (and (integer? m) (pos? m)) (max mx m) mx)]
                  mx))
              1024
              (concat added retracted))]
        (-> (assoc db' :schema
                   schema :ref-attrs
                   ref-attrs :card-many
                   card-many :unique-attrs
                   unique-attrs :indexed-attrs
                   indexed-attrs :next-eid
                   (max (:next-eid db') (inc max-imported-eid)))
            in-m/rebuild-secondary-indexes))))
  (read-segments [this t-min t-max]
    (->> (:log this)
         (filter (fn [[t]] (and (>= t t-min) (<= t t-max))))))
  (latest-t [this] (dec (:next-t this)))
  IDaoTransactor
  (transact! [this tx-data]
    (let [result (in-m/run-tx this tx-data)
          new-segment (last (:log (:db-after result)))]
      (append-segment-to-file (:file-path this) new-segment)
      result))
  (with [this tx-data] (in-m/native-with this tx-data))
  IDaoQueryEngine
  (run-q [this query inputs] (in-m/native-q this query inputs))
  (index-datoms [this index components]
    (in-m/native-datoms this index components))
  IDaoDB
  (entity [this eid] (in-m/native-entity this eid))
  (pull [this pattern eid] (in-m/native-pull this pattern eid))
  (pull-many [this pattern eids] (in-m/native-pull-many this pattern eids))
  (basis-t [this] (in-m/native-basis-t this))
  (as-of [this t]
    (in-m/map->InMemoryDaoDB (dissoc (in-m/as-of this t) :file-path)))
  (since [this t]
    (in-m/map->InMemoryDaoDB (dissoc (in-m/since this t) :file-path)))
  (entity-attrs [this eid] (in-m/native-entity-attrs this eid))
  (find-eids-by-av [this a v] (in-m/native-find-eids-by-av this a v)))

(ns jolt-cbor-override-check
  "Babashka-only review gate for the whole-file dao.stream.cbor override.
   Reads source as data, never loads jolt, codec namespaces or native libraries."
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.reader :as reader]
            [clojure.tools.reader.reader-types :as reader-types])
  (:import [java.nio.file Files Paths]
           [java.security MessageDigest]))


(def paths
  {:shared "src/cljc/dao/stream/cbor.cljc"
   :jolt "src/jolt/dao/stream/cbor.jolt"
   :reviewed "src/jolt/dao/stream/cbor.reviewed.edn"})


(defn digest
  "SHA-256 of exact file bytes (no text decoding or newline normalization)."
  [path]
  (let [bytes (Files/readAllBytes (Paths/get (str path) (make-array String 0)))]
    (apply str (map #(format "%02x" (bit-and % 255))
                    (.digest (MessageDigest/getInstance "SHA-256") bytes)))))


(defn- public-definition
  [form]
  (when (and (seq? form) (contains? #{'defn 'def} (first form))
             (not (:private (meta (second form)))))
    (let [[op name & tail] form
          tail (if (string? (first tail)) (next tail) tail)
          attrs (when (and (= op 'defn) (map? (first tail))) (first tail))
          tail (if attrs (next tail) tail)]
      (when-not (:private attrs)
        [name (cond-> {:kind op
                       :arglists (when (= op 'defn)
                                   (if (vector? (first tail))
                                     [(first tail)]
                                     (mapv first (filter seq? tail))))}
                (= op 'def) (assoc :value (first tail)))]))))


(defn public-api
  "Extract top-level public defn/def names and literal argument vectors.
   Read each of :clj/:cljs/:cljd/:jolt separately, selecting reader conditionals.
   Limits: no macro expansion, nested/generated definitions, alias arity resolution
   (def aliases have nil arglists), or semantic equivalence of argument bindings;
   vectors compare literally. Private defs are ignored. Every public def initializer is
   compared as data, not evaluated. Custom reader tags are unsupported; reader-eval forms are never executed."
  [source feature]
  (binding [*read-eval* false]
    (let [input (reader-types/string-push-back-reader source)]
      (loop [api (sorted-map)]
        (let [form (reader/read {:eof ::eof :read-cond :allow :features #{feature}} input)]
          (if (= ::eof form)
            api
            (recur (if-let [[name signature] (public-definition form)]
                     (do
                       (when (contains? api name)
                         (throw (ex-info (str "Duplicate public definition: " name) {})))
                       (assoc api name signature))
                     api))))))))


(defn check-api!
  "Allowlist keys are public symbols; entries require :reason plus exact
   :shared/:jolt signatures (nil means absent). Stale exceptions fail closed."
  [shared jolt allowlist]
  (doseq [feature [:clj :cljs :cljd :jolt]]
    (let [a (public-api shared feature)
          b (public-api jolt feature)
          names (set/union (set (keys a)) (set (keys b)) (set (keys allowlist)))]
      (doseq [name (sort names)
              :let [left (get a name) right (get b name)
                    exception (get allowlist name)]]
        (when-not (if exception
                    (and (not= left right)
                         (string? (:reason exception))
                         (not (str/blank? (:reason exception)))
                         (contains? exception :shared) (contains? exception :jolt)
                         (= left (:shared exception)) (= right (:jolt exception)))
                    (= left right))
          (throw (ex-info
                   (str "dao.stream.cbor public API/profile mismatch: " name " (" feature ")"
                        "\nshared: " (pr-str left) "\njolt: " (pr-str right)
                        "\nReview the override or document an exact difference in cbor.reviewed.edn :api-allowlist.")
                   {:var name :feature feature :shared left :jolt right}))))))
  true)


(def drift-message
  "the shared dao.stream.cbor changed since the jolt override was reviewed; diff it against src/jolt/dao/stream/cbor.jolt, re-run the cbor parity corpus (bb test:jolt-cbor-parity) and dao.stream.cbor-test on jolt, then update cbor.reviewed.edn with `bb test:jolt-cbor-override --update \"<who, date>\"`")


(defn check!
  "Check digest, API and documented parity exceptions; --update requires a fresh
   reviewer note and writes it with the new digest after checks pass.
   Optional paths support isolated tests."
  ([update?] (check! paths update? nil))
  ([paths update?] (check! paths update? nil))
  ([{:keys [shared jolt reviewed]} update? reviewer-note]
   (when (and update? (or (not (string? reviewer-note)) (str/blank? reviewer-note)))
     (throw (ex-info "--update requires a non-blank reviewer note: bb test:jolt-cbor-override --update \"<who, date>\"" {})))
   (let [record (edn/read-string
                  (try (slurp reviewed)
                       (catch java.io.FileNotFoundException _
                         (throw (ex-info (str "Missing CBOR review record: " reviewed) {})))))
         actual (digest shared)]
     (when-not (map? (:api-allowlist record))
       (throw (ex-info "cbor.reviewed.edn requires an explicit :api-allowlist map" {})))
     (when-not (and (vector? (:parity-exceptions record)) (seq (:parity-exceptions record)))
       (throw (ex-info "cbor.reviewed.edn requires a non-empty :parity-exceptions vector" {})))
     (when (and (not update?) (not= actual (:sha256 record)))
       (throw (ex-info drift-message {:expected (:sha256 record) :actual actual})))
     (check-api! (slurp shared) (slurp jolt) (:api-allowlist record))
     (when update?
       (spit reviewed (str (pr-str (assoc record :sha256 actual :reviewer-note reviewer-note)) "\n")))
     {:sha256 actual :status (if update? :updated :reviewed)})))


(defn -main
  [& args]
  (try
    (when-not (or (empty? args) (and (= "--update" (first args)) (<= (count args) 2)))
      (throw (ex-info "Usage: bb test:jolt-cbor-override [--update \"<who, date>\"]" {})))
    (println "Jolt CBOR override OK:"
             (pr-str (check! paths (boolean (seq args)) (second args))))
    (catch Exception e
      (binding [*out* *err*] (println (ex-message e)))
      (System/exit 1))))


(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))

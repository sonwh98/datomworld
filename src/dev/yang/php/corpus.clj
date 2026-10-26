(ns yang.php.corpus
  "Records the PHP 8.3 oracle's output for the permutation corpus
   (`yang.php.antlr.permutations`, docs/design/yang.php.md section 4.6).

   clj -M:test -m yang.php.corpus quick   writes the checked-in
     test/yang/php/antlr/permutation_oracle_quick.edn
   clj -M:test -m yang.php.corpus full    writes
     build/yang-php/permutation_oracle_full.edn (not checked in)

   Runs on shiva (docker `php:8.3-cli`, via `yang.php.oracle/run-php-file`);
   the entry for a program is `{:out :diag :uncaught}` exactly as the oracle
   parser returns it."
  (:require
    [clojure.java.io :as io]
    [clojure.pprint :as pp]
    [yang.php.antlr.permutations :as perms]
    [yang.php.oracle :as oracle])
  (:import
    (java.nio.charset
      StandardCharsets)
    (java.nio.file
      Files)
    (java.nio.file.attribute
      FileAttribute)))


(defn- record
  "`{id result}` for every program of `programs`, run in one temp directory."
  [programs]
  (let [dir (.toFile (Files/createTempDirectory "yang-php-corpus-" (make-array FileAttribute 0)))]
    (try
      (into (sorted-map)
            (map (fn [{:keys [id source]}]
                   (let [file "p.php"]
                     (Files/write (.toPath (io/file dir file))
                                  (.getBytes ^String source StandardCharsets/UTF_8)
                                  (make-array java.nio.file.OpenOption 0))
                     [id (select-keys (oracle/run-php-file dir file) [:out :diag :uncaught])])))
            programs)
      (finally
        (doseq [f (reverse (file-seq dir))] (.delete ^java.io.File f))))))


(defn- write!
  [path data]
  (io/make-parents path)
  (spit path (with-out-str (binding [*print-length* nil] (pp/pprint data)))))


(defn -main
  [& [tier]]
  (case tier
    "quick" (write! "test/yang/php/antlr/permutation_oracle_quick.edn" (record (perms/quick)))
    "full" (write! "build/yang-php/permutation_oracle_full.edn" (record (perms/full)))
    (println "usage: yang.php.corpus quick|full"))
  (shutdown-agents))

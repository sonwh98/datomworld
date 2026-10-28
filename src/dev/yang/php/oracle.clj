(ns yang.php.oracle
  "PHP 8.3 CLI oracle runner for `yang.php.antlr.programs`
   (docs/design/yang.php.md sections 14, 17.3).

   For every fixture in `programs/fixtures` (plus `matrix-source` and
   `float-cases`), writes the PHP source to a temporary file and runs:
     docker run --rm -v <dir>:/w -w /w php:8.3-cli php \\
       -d display_errors=1 -d html_errors=0 -d error_reporting=E_ALL \\
       -d log_errors=0 <file>
   Parses stdout into `:out`, `:diag` (`[[errno message] ...]`) and `:uncaught`
   (`{:class c :message m}`), and compares against each fixture's expectation
   (or `:departure/:zend` for departure fixtures)."
  (:require
    [clojure.java.io :as io]
    [clojure.java.shell :as sh]
    [clojure.string :as str]
    [yang.php.antlr.programs :as programs]
    [yang.php.antlr.programs-p3b :as p3b]
    [yang.php.antlr.programs-p4 :as p4])
  (:import
    (java.nio.charset
      StandardCharsets)
    (java.nio.file
      Files)
    (java.nio.file.attribute
      FileAttribute)))


(def banner
  "The pinned PHP 8.3 CLI oracle provenance banner."
  p3b/banner)


(def ^:private errno-of
  {"Warning" 2,
   "Notice" 8,
   "Deprecated" 8192,
   "Fatal error" 1})


(defn- strip-location
  [s file]
  (str/replace s
               (re-pattern (str " in /w/" (java.util.regex.Pattern/quote file) " on line \\d+"))
               ""))


(defn parse-stdout
  "Parse PHP CLI stdout for `/w/<file>` into
   `{:out <string> :diag [[errno msg] ...] :uncaught {:class c :message m}-or-nil}`."
  [raw file]
  (let [qfile (java.util.regex.Pattern/quote file)
        ;; The uncaught block ends at PHP's own terminator, so output written
        ;; after it (shutdown functions, destructors) stays in :out.
        uncaught-re (re-pattern
                      (str "(?s)\\nFatal error: Uncaught ([^:]+): ([^\\r\\n]*) in /w/"
                           qfile
                           ":\\d+\\nStack trace:\\n.*?\\n  thrown in /w/"
                           qfile
                           " on line \\d+\\n"))
        fatal-re (re-pattern
                   (str "\\nFatal error: ([^\\r\\n]*) in /w/"
                        qfile
                        " on line \\d+\\n"))
        diag-re (re-pattern
                  (str "\\n(Warning|Notice|Deprecated): ([^\\r\\n]*?) in /w/"
                       qfile
                       " on line \\d+\\n"))
        [without-fatal uncaught]
        (if-let [[_ cls msg] (re-find uncaught-re raw)]
          [(str/replace raw uncaught-re "")
           {:class cls, :message (strip-location msg file)}]
          (if-let [[_ msg] (re-find fatal-re raw)]
            [(str/replace raw fatal-re "")
             {:class "Error", :message (strip-location msg file)}]
            [raw nil]))
        diags (volatile! [])
        out (str/replace without-fatal
                         diag-re
                         (fn [[_ kind msg]]
                           (vswap! diags conj [(get errno-of kind) (strip-location msg file)])
                           ""))]
    {:out out,
     :diag @diags,
     :uncaught uncaught}))


(defn run-php-file
  "Run `<file>` inside `<dir>` via Docker `php:8.3-cli` and return the parsed
   `{:out :diag :uncaught :exit}`."
  [dir file]
  (let [{:keys [out err exit]}
        (sh/sh "docker" "run" "--rm"
               "-v" (str (.toAbsolutePath (.toPath (io/file dir))) ":/w")
               "-w" "/w"
               "php:8.3-cli" "php"
               "-d" "display_errors=1"
               "-d" "html_errors=0"
               "-d" "error_reporting=E_ALL"
               "-d" "log_errors=0"
               file
               :out-enc "UTF-8")]
    (assoc (parse-stdout out file) :err err :exit exit)))


(defn- write-source!
  [dir file ^String source]
  (Files/write (.toPath (io/file dir file))
               (.getBytes source StandardCharsets/UTF_8)
               (make-array java.nio.file.OpenOption 0)))


(defn check-fixture
  "Run `fixture` in temporary directory `dir` and return a check record."
  [dir {:keys [id source out diag uncaught departure gap oracle]}]
  (let [file (str (name id) ".php")]
    (write-source! dir file source)
    (let [actual (run-php-file dir file)
          expected-out (cond departure (:zend departure)
                             gap ""
                             :else out)
          expected-diag (vec (cond departure (:zend-diag departure)
                                   gap []
                                   :else (or diag [])))
          expected-uncaught (cond departure (:zend-uncaught departure)
                                  gap nil
                                  :else uncaught)
          pass? (and (not= :pending oracle)
                     (= expected-out (:out actual))
                     (= expected-diag (:diag actual))
                     (= expected-uncaught (:uncaught actual)))]
      {:id id,
       :pass? pass?,
       :oracle oracle,
       :gap gap,
       :departure departure,
       :expected {:out expected-out, :diag expected-diag, :uncaught expected-uncaught},
       :actual (select-keys actual [:out :diag :uncaught])})))


(defn- check-matrix
  [dir]
  (let [file "scalar-comparison-matrix.php"
        _ (write-source! dir file programs/matrix-source)
        actual (run-php-file dir file)
        [eq sp] (str/split-lines (:out actual))
        w (count programs/matrix-values)
        actual-eq (mapv #(apply str %) (partition w eq))
        actual-sp (mapv #(apply str (map {\0 "<", \1 "=", \2 ">"} %)) (partition w sp))]
    {:id :scalar-comparison-matrix,
     :pass? (and (= programs/equal-matrix actual-eq)
                 (= programs/spaceship-matrix actual-sp)
                 (empty? (:diag actual))
                 (nil? (:uncaught actual))),
     :expected {:eq programs/equal-matrix, :sp programs/spaceship-matrix},
     :actual {:eq actual-eq, :sp actual-sp, :diag (:diag actual), :uncaught (:uncaught actual)}}))


(defn- check-floats
  [dir]
  (let [file "float-cases.php"
        src (str "<?php\n"
                 (apply str (for [[lit] programs/float-cases]
                              (str "echo " lit ", \"|\"; var_dump(" lit ");\n"))))
        expected (apply str (for [[_ echo dump] programs/float-cases]
                              (str echo "|float(" dump ")\n")))
        _ (write-source! dir file src)
        actual (run-php-file dir file)]
    {:id :float-cases,
     :pass? (and (= expected (:out actual))
                 (empty? (:diag actual))
                 (nil? (:uncaught actual))),
     :expected {:out expected, :diag [], :uncaught nil},
     :actual (select-keys actual [:out :diag :uncaught])}))


(defn run-all
  "Run all `programs/fixtures`, `matrix-source` and `float-cases` against
   `php:8.3-cli`."
  []
  (let [dir (.toFile (Files/createTempDirectory "yang-php-oracle-"
                                                (make-array FileAttribute 0)))]
    (try
      (-> (mapv #(check-fixture dir %) (concat programs/fixtures p3b/all-fixtures p4/all-fixtures))
          (conj (check-matrix dir))
          (conj (check-floats dir)))
      (finally
        (doseq [f (reverse (file-seq dir))]
          (.delete ^java.io.File f))))))


(defn -main
  [& _args]
  (let [results (run-all)
        recorded (filter #(= :pending (:oracle %)) results)
        failures (remove #(or (:pass? %) (= :pending (:oracle %))) results)]
    (println "Banner:" banner)
    (println "Checked:" (count results) "targets; failures:" (count failures))
    (doseq [f recorded]
      (println "RECORD" (:id f) (pr-str (:actual f)) "matches-pinned-out:" (boolean (:pass? f))))
    (doseq [f failures]
      (println "FAIL" (:id f))
      (println "  expected:" (pr-str (:expected f)))
      (println "  actual:  " (pr-str (:actual f))))
    (shutdown-agents)
    (when (seq failures)
      (System/exit 1))))

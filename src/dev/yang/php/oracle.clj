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
    [yang.php.antlr.programs-p4 :as p4]
    [yang.php.antlr.programs-p5 :as p5]
    [yang.php.antlr.programs-p6 :as p6])
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


(def p5-banner
  "The P5 request-context run's own provenance banner (version/host/date of
  the P5 recording pass, not P3-b's)."
  "php-8.3.35 shiva 2026-10-25")


(def ^:private errno-of
  {"Warning" 2,
   "Notice" 8,
   "Deprecated" 8192,
   "Fatal error" 1})


(defn class-derived?
  "Whether the recorded diag entry carries a class-derived errno: the runner
  parses only the `Warning|Notice|...` kind line, so the recorded number is
  derived from the message class, not the true constant (e.g. `Notice` parses
  to 8 where the engine's semantic truth for `E_USER_NOTICE` is 1024)."
  [diag-entry]
  (boolean (:yang.php.oracle/class-derived (meta diag-entry))))


(defn- mark-class-derived
  "Mark a recorded `[errno message]` pair as class-derived (see [[class-derived?]])."
  [pair]
  (with-meta pair {:yang.php.oracle/class-derived true}))


(defn errno-match?
  "Whether expected errno `e` matches recorded diag entry `a`: identical
  numbers match, and a 1024-vs-8 mismatch counts as equal ONLY when the oracle
  record is marked class-derived."
  [e a]
  (let [[actual-errno] a]
    (or (= e actual-errno)
        (and (= 1024 e) (= 8 actual-errno) (class-derived? a)))))


(defn diag-match?
  "Whether the expected diag vector matches the recorded one entry-wise under
  [[errno-match?]] (messages compare literally)."
  [expected-diag actual-diag]
  (and (= (count expected-diag) (count actual-diag))
       (every? true? (map (fn [[e-errno e-msg] a]
                            (and (errno-match? e-errno a)
                                 (= e-msg (second a))))
                          expected-diag actual-diag))))


(defn- location-pattern
  "The regex source of a PHP source location below `/w/`: the entry `file`
   itself, or, for a multi-unit fixture run in `/w/<unit-dir>`, any unit file
   below the directory."
  [file unit-dir]
  (if unit-dir
    (str (java.util.regex.Pattern/quote unit-dir) "/[^\\s:]+")
    (java.util.regex.Pattern/quote file)))


(defn- strip-location
  ([s file] (strip-location s file nil))
  ([s file unit-dir]
   (str/replace s
                (re-pattern (str " in /w/" (location-pattern file unit-dir) " on line \\d+"))
                "")))


(defn parse-stdout
  "Parse PHP CLI stdout for `/w/<file>` into
   `{:out <string> :diag [[errno msg] ...] :uncaught {:class c :message m}-or-nil}`.
   With `unit-dir`, locations in any file below `/w/<unit-dir>` are stripped
   (a multi-unit fixture)."
  ([raw file] (parse-stdout raw file nil))
  ([raw file unit-dir]
   (let [qfile (location-pattern file unit-dir)
         strip-location (fn [s file] (strip-location s file unit-dir))
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
                            (vswap! diags conj (mark-class-derived [(get errno-of kind) (strip-location msg file)]))
                            ""))]
     {:out out,
      :diag @diags,
      :uncaught uncaught})))


(defn run-php-file
  "Run `<file>` inside `<dir>` via Docker `php:8.3-cli` and return the parsed
   `{:out :diag :uncaught :exit}`. With `unit-dir`, the working directory is
   `/w/<unit-dir>` and `file` is relative to it (a multi-unit fixture)."
  ([dir file] (run-php-file dir file nil))
  ([dir file unit-dir]
   (let [{:keys [out err exit]}
         (sh/sh "docker" "run" "--rm"
                "-v" (str (.toAbsolutePath (.toPath (io/file dir))) ":/w")
                "-w" (if unit-dir (str "/w/" unit-dir) "/w")
                "php:8.3-cli" "php"
                "-d" "display_errors=1"
                "-d" "html_errors=0"
                "-d" "error_reporting=E_ALL"
                "-d" "log_errors=0"
                file
                :out-enc "UTF-8")]
     (assoc (parse-stdout out (if unit-dir (str unit-dir "/" file) file) unit-dir)
            :err err
            :exit exit))))


(defn- write-source!
  [dir file ^String source]
  (Files/write (.toPath (io/file dir file))
               (.getBytes source StandardCharsets/UTF_8)
               (make-array java.nio.file.OpenOption 0)))


(defn- unit-actual
  "Run the multi-unit `fixture`: every unit is written below `<dir>/<id>/`,
   `main.php` is run with that directory as the working directory, and
   `/w/<id>` is replaced by `.` in everything the program printed (the
   comparison's definition, plan P6 2.5 rule 2; corner C37)."
  [dir {:keys [id units main], :or {main "main.php"}}]
  (let [unit-dir (name id)
        norm (fn [s] (str/replace s (str "/w/" unit-dir) "."))]
    (doseq [[path source] units]
      (let [f (io/file dir unit-dir path)]
        (io/make-parents f)
        (write-source! dir (str unit-dir "/" path) source)))
    (let [actual (run-php-file dir main unit-dir)]
      (-> actual
          (update :out norm)
          (update :diag (fn [ds] (mapv (fn [[errno msg]] [errno (norm msg)]) ds)))
          (update :uncaught #(some-> % (update :message norm)))))))


(defn check-fixture
  "Run `fixture` in temporary directory `dir` and return a check record."
  [dir {:keys [id source units out diag uncaught departure gap oracle], :as fixture}]
  (let [file (str (name id) ".php")]
    (when-not units (write-source! dir file source))
    (let [actual (if units (unit-actual dir fixture) (run-php-file dir file))
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


(defn- php-literal
  "The PHP literal of request-record scalar `v`: strings single-quoted
   (escaping backslash and quote, the only two escapes there), ints bare.
   Key coercion (numeric strings to ints) is left to PHP itself, exactly as
   the engine's `php/array-key` performs it."
  [v]
  (cond (string? v) (str "'" (str/replace v #"\\|'" (fn [[c]] (str "\\" c))) "'")
        (integer? v) (str v)
        :else (throw (ex-info "p5 oracle: non-scalar in request record" {:value v}))))


(defn- request-pairs
  "Ordered `[k v]` pairs of request-array `arr`: the engine's ordered entry
   vectors are accepted as-is; otherwise pairs follow the array's `:order`
   (the engine's insertion order)."
  [arr]
  (when arr
    (if (sequential? arr)
      (mapv vec arr)
      (mapv (fn [k] [k (get (:index arr) k)]) (:order arr)))))


(defn- php-array-literal
  "The PHP `array(...)` literal of request-array `arr`."
  [arr]
  (if (empty? (request-pairs arr))
    "array()"
    (str "array("
         (str/join ", " (map (fn [[k v]]
                               (str (php-literal k) " => " (php-literal v)))
                             (request-pairs arr)))
         ")")))


(defn p5-prologue
  "The `auto_prepend_file` prologue assigning the superglobals from the
   fixture `:request` record, mirroring `php/bind-request!` (prelude
   section `request and output`): `$_GET` is the query, `$_POST` the body,
   `$_COOKIE` the cookies, `$_SERVER` the server fields with
   `REQUEST_METHOD`/`REQUEST_URI` appended, `$_ENV` the env, `$_FILES` the
   files, and `$_REQUEST` is `array_merge($_GET, $_POST)` (POST wins, the
   engine's `php/merge-loop` over `[get post]`)."
  [request]
  (let [method (or (:php.request/method request) "")
        uri (or (:php.request/uri request) "")]
    (str "<?php\n"
         "$_GET = " (php-array-literal (:php.request/query request)) ";\n"
         "$_POST = " (php-array-literal (:php.request/body request)) ";\n"
         "$_COOKIE = " (php-array-literal (:php.request/cookies request)) ";\n"
         "$_SERVER = " (php-array-literal (:php.request/server request)) ";\n"
         "$_SERVER['REQUEST_METHOD'] = " (php-literal method) ";\n"
         "$_SERVER['REQUEST_URI'] = " (php-literal uri) ";\n"
         "$_ENV = " (php-array-literal (:php.request/env request)) ";\n"
         "$_FILES = " (php-array-literal (:php.request/files request)) ";\n"
         "$_REQUEST = array_merge($_GET, $_POST);\n")))


(defn run-php-file-with-prepend
  "Run `<file>` inside `<dir>` via Docker `php:8.3-cli` with `prepend-file`
   (a path relative to `<dir>`) as `auto_prepend_file`, and return the
   parsed `{:out :diag :uncaught :exit}`."
  [dir file prepend-file]
  (let [{:keys [out err exit]}
        (sh/sh "docker" "run" "--rm"
               "-v" (str (.toAbsolutePath (.toPath (io/file dir))) ":/w")
               "-w" "/w"
               "php:8.3-cli" "php"
               "-d" "display_errors=1"
               "-d" "html_errors=0"
               "-d" "error_reporting=E_ALL"
               "-d" "log_errors=0"
               "-d" (str "auto_prepend_file=/w/" prepend-file)
               file
               :out-enc "UTF-8")]
    (assoc (parse-stdout out file nil)
           :err err
           :exit exit)))


(defn check-p5-fixture
  "Run P5 `fixture` in temporary directory `dir`: the fixture source plus,
   for `:request`-bearing fixtures, the [[p5-prologue]] as
   `auto_prepend_file`. Returns a check record like [[check-fixture]]."
  [dir {:keys [id source request out diag uncaught status oracle]}]
  (let [file (str (name id) ".php")
        pre (str (name id) "-pre.php")]
    (write-source! dir file source)
    (when request (write-source! dir pre (p5-prologue request)))
    (let [actual (if request
                   (run-php-file-with-prepend dir file pre)
                   (run-php-file dir file))
          expected-out (or out "")
          expected-diag (vec (or diag []))
          pass? (and (not= :pending oracle)
                     (= expected-out (:out actual))
                     (diag-match? expected-diag (:diag actual))
                     (= uncaught (:uncaught actual))
                     (or (nil? status) (= status (:exit actual))))]
      {:id id,
       :pass? pass?,
       :oracle oracle,
       :expected {:out expected-out, :diag expected-diag, :uncaught uncaught, :status status},
       :actual (assoc (select-keys actual [:out :diag :uncaught]) :exit (:exit actual))})))


(defn run-p5
  "Run all `p5/fixtures` (the request-context fixtures) against
   `php:8.3-cli`."
  []
  (let [dir (.toFile (Files/createTempDirectory "yang-php-oracle-p5-"
                                                (make-array FileAttribute 0)))]
    (try
      (mapv #(check-p5-fixture dir %) p5/fixtures)
      (finally
        (doseq [f (reverse (file-seq dir))]
          (.delete ^java.io.File f))))))


(defn print-fold
  "Print the fold lines for `results`: one paste-ready record per fixture
   in the P4 [[p4/pin]] shape `{:oracle p5-banner :out :diag :uncaught}`."
  [results]
  (doseq [{:keys [id actual]} results]
    (println "FOLD" (name id)
             (pr-str (assoc (select-keys actual [:out :diag :uncaught])
                            :oracle p5-banner)))))


(defn run-all
  "Run all `programs/fixtures`, `matrix-source` and `float-cases` against
   `php:8.3-cli`."
  []
  (let [dir (.toFile (Files/createTempDirectory "yang-php-oracle-"
                                                (make-array FileAttribute 0)))]
    (try
      (-> (mapv #(check-fixture dir %) (concat programs/fixtures p3b/all-fixtures p4/all-fixtures p6/all-fixtures))
          (conj (check-matrix dir))
          (conj (check-floats dir)))
      (finally
        (doseq [f (reverse (file-seq dir))]
          (.delete ^java.io.File f))))))


(defn -main
  [& args]
  (when (some #{"--print-prologue"} args)
    (let [id (keyword (or (second (drop-while #(not= "--print-prologue" %) args))
                          "superglobals-from-request"))
          fixture (first (filter #(= id (:id %)) p5/fixtures))]
      (when-not fixture
        (println "Unknown P5 fixture:" id)
        (System/exit 2))
      ;; Dry run: no docker, no oracle. Print the generated prologue for
      ;; eyeball comparison against the engine's expected superglobal
      ;; values (the fixture's hand-derived :out is the reference).
      (print (p5-prologue (:request fixture)))
      (flush)
      (shutdown-agents)
      (System/exit 0)))
  (when (some #{"--p5"} args)
    (let [results (run-p5)
          recorded (filter #(= :pending (:oracle %)) results)
          failures (remove #(or (:pass? %) (= :pending (:oracle %))) results)]
      (println "Banner:" p5-banner)
      (println "Checked:" (count results) "P5 targets; failures:" (count failures))
      (print-fold recorded)
      (doseq [f failures]
        (println "FAIL" (:id f))
        (println "  expected:" (pr-str (:expected f)))
        (println "  actual:  " (pr-str (:actual f))))
      (shutdown-agents)
      (when (seq failures)
        (System/exit 1))
      (System/exit 0)))
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

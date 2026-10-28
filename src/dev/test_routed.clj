(ns test-routed
  "Explicit policy dispatch; wide changes always execute the full tri-host gate."
  (:require
    [affected :as a]
    [babashka.process :as p]
    [clojure.string :as str]
    [quick-sharded :as quick]))


(defn docs-only?
  [path]
  ;; Executable documentation and test resources are ordinary affected input.
  (and (not (str/starts-with? path "test/resources/"))
       (boolean (re-find #"\.(?:md|txt|rst)$" path))))


(defn toolchain?
  [path]
  (or (= :wide (a/classify path))
      (boolean (re-find #"(?:^|/)(?:[^/]*toolchain[^/]*|Dockerfile[^/]*|Makefile)$" path))
      (str/starts-with? path ".github/workflows/")
      (str/starts-with? path "scripts/")))


(defn foundation?
  [index cfg path]
  (let [ns-sym (or (:ns (some #(when (= path (:path %)) %) index))
                   (a/path->ns path))
        sub (when ns-sym (a/match-subsystem cfg ns-sym))]
    (and (#{:code} (a/classify path))
         (or (= 0 (:layer sub))
             (and ns-sym
                  (some #(let [n (str ns-sym)]
                           (or (= n %) (str/starts-with? n (str % "."))
                               (str/starts-with? n (str % "-"))))
                        ["yang.clojure" "yang.php" "yang.typescript" "yang.python"]))))))


(defn route
  [index cfg paths]
  (cond
    (some toolchain? paths) {:class :toolchain :command ["bb" "test"]}
    (and (seq paths) (every? docs-only? paths))
    {:class :docs :command (into ["git" "diff" "--check" "--"] paths)}
    (some #(foundation? index cfg %) paths)
    {:class :foundation
     :command (into ["bb" "test:quick-sharded" "--changed"] paths)}
    ;; A non-doc change with no statically selected tests is not a green gate.
    (empty? (:tests (a/select index paths)))
    {:class :unselected :command ["bb" "test"]}
    :else {:class :leaf :command (into ["bb" "test:changed" "--changed"] paths)}))


(defn dispatch!
  [{:keys [class command]} dry?]
  (println (str "Dispatch [" (name class) "]: " (str/join " " command)
                (when (#{:toolchain :unselected} class)
                  " (full quick tri-host; success required)")
                (when dry? " (DRY RUN; no gate executed)")))
  (when (= class :foundation)
    (println "JVM feedback only: G2 still needs test:changed:cljs and test:changed:cljd for these files; G3/G4 remain required."))
  (if dry?
    0
    (let [exit (:exit @(p/process command {:inherit true}))]
      (if (and (= class :leaf) (= a/wide-exit exit))
        (do (println "Dispatch [wide exit 2]: bb test (full quick tri-host; success required)")
            (:exit @(p/process ["bb" "test"] {:inherit true})))
        exit))))


(defn -main
  [& args]
  (try
    (let [dry? (boolean (some #{"--dry-run" "--list"} args))
          paths (vec (remove #{"--dry-run" "--list"} args))
          _ (when (some #(str/starts-with? % "--") paths)
              (throw (ex-info "usage: bb test:routed [--dry-run] <changed-files...>" {})))
          index (a/scan-index)
          ;; No positional args means the same git change set as test:changed.
          paths (quick/changed-paths index (when (seq paths) {:changed paths}))
          _ (when (empty? paths)
              (throw (ex-info "no changed files: no gate dispatched" {})))
          cfg (or (a/load-subsystems-config)
                  (throw (ex-info "missing src/dev/subsystems.edn" {})))]
      (System/exit (dispatch! (route index cfg paths) dry?)))
    (catch Exception ex
      (binding [*out* *err*] (println (ex-message ex)))
      (System/exit 1))))


(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))

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
    {:class :docs :paths paths :command (into ["git" "diff" "--check" "HEAD" "--"] paths)}
    (some #(foundation? index cfg %) paths)
    {:class :foundation
     :command (into ["bb" "test:quick-sharded" "--changed"] paths)}
    ;; A non-doc change with no statically selected tests is not a green gate.
    (empty? (:tests (a/select index paths)))
    {:class :unselected :command ["bb" "test"]}
    :else {:class :leaf :command (into ["bb" "test:changed" "--changed"] paths)}))


(defn dispatch!
  [{:keys [class command paths]} dry?]
  (println (str "Dispatch [" (name class) "]: " (str/join " " command)
                (when (#{:toolchain :unselected} class)
                  " (full quick tri-host; success required)")
                (when dry? " (DRY RUN; no gate executed)")))
  (when (= class :foundation)
    (println "JVM feedback only: G2 still needs test:changed:cljs and test:changed:cljd for these files; G3/G4 remain required."))
  (let [scope (case class
                :docs :docs-whitespace
                :foundation :jvm-feedback
                :leaf :selected-quick-tri-host
                :full-quick-tri-host)
        report! (fn [scope exit details]
                  (quick/print-scope! (quick/verification-scope :test-routed scope exit dry? details)))]
    (try
      (if dry?
        (report! scope 0 {})
        (let [unchecked (when (= class :docs)
                          (let [{:keys [exit out]}
                                @(p/process (into ["git" "ls-files" "-z" "--"] paths)
                                            {:out :string :err :string})]
                            (when-not (zero? exit)
                              (throw (ex-info "git ls-files failed: docs scope unknown" {:exit exit})))
                            (let [tracked (set (str/split out #"\u0000"))]
                              (vec (remove tracked paths)))))
              _ (doseq [path unchecked] (println (str path ": unchecked (untracked)")))
              exit (:exit @(p/process command {:inherit true}))]
          (if (and (= class :leaf) (= a/wide-exit exit))
            (do (println "Dispatch [wide exit 2]: bb test (full quick tri-host; success required)")
                (report! :full-quick-tri-host
                         (:exit @(p/process ["bb" "test"] {:inherit true})) {}))
            (report! scope exit (if (= class :docs) {:unchecked unchecked} {})))))
      (catch Exception ex
        (report! scope 1 {})
        (throw ex)))))


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

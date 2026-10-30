(ns build-cache
  "Content-checked, worktree-local caches for expensive test prerequisites."
  (:require
    [babashka.fs :as fs]
    [babashka.process :as p]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str])
  (:import
    [java.security MessageDigest]))


(defn digest
  [value]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (.update md (.getBytes (pr-str value) "UTF-8"))
    (format "%064x" (java.math.BigInteger. 1 (.digest md)))))


(defn file-digest
  [path]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (with-open [in (io/input-stream (fs/file path))]
      (let [buf (byte-array 65536)]
        (loop []
          (let [n (.read in buf)]
            (when (pos? n)
              (.update md buf 0 n)
              (recur))))))
    (format "%064x" (java.math.BigInteger. 1 (.digest md)))))


(defn snapshot
  "Names and bytes, including missing roots; detects removed/corrupt outputs."
  [roots]
  (let [file-state (fn [f]
                     {:sha256 (file-digest f)
                      :executable? (.canExecute (fs/file f))})]
    (into (sorted-map)
          (mapcat (fn [root]
                    (if (fs/directory? root)
                      (cons [(str root) :directory]
                            (for [f (sort (fs/glob root "**" {:hidden true}))
                                  :when (fs/regular-file? f)]
                              [(str f) (file-state f)]))
                      [[(str root) (when (fs/regular-file? root)
                                     (file-state root))]])))
          roots)))


(defn local-path!
  "Refuse shared mutable output directories, including symlinked parents."
  [path]
  (let [root (.getCanonicalFile (fs/file "."))
        dest (.getCanonicalFile (fs/file path))]
    (when-not (.startsWith (.toPath dest) (.toPath root))
      (throw (ex-info (str "cache output must stay in this worktree: " path) {})))))


(defn checked-output
  [& command]
  (let [{:keys [exit out err]} (apply p/sh command)]
    (when-not (zero? exit)
      (throw (ex-info (str "failed: " (str/join " " command) "\n" err)
                      {:exit exit})))
    (str/trim (str out err))))


(defn cached!
  "Hold a worktree-local writer lock; record inputs/outputs only after success."
  [id inputs outputs command]
  (doseq [path (concat ["target/prereq-cache"] outputs)] (local-path! path))
  (fs/create-dirs "target/prereq-cache")
  (let [stamp (str "target/prereq-cache/" id ".edn")
        lock (str "target/prereq-cache/" id ".lock")
        key (digest [(str (fs/canonicalize ".")) inputs])]
    (try (fs/create-dir lock)
         (catch Exception _
           (throw (ex-info (str id " cache is locked: " lock
                                "; serialize builds in this worktree. If its owner died, remove the lock.")
                           {}))))
    (try
      (let [previous (when (fs/exists? stamp)
                       (try (edn/read-string (slurp stamp))
                            (catch Exception _ nil)))
            present (snapshot outputs)]
        (if (and (= key (:key previous))
                 (seq present) (every? some? (vals present))
                 (= present (:outputs previous)))
          (do (println id "cache hit (inputs and outputs verified)") 0)
          (do
            (println id "cache miss; running" (str/join " " command))
            (fs/delete-if-exists stamp)
            (let [exit (:exit @(p/process command {:inherit true}))]
              (when (zero? exit)
                (spit stamp (pr-str {:key key :outputs (snapshot outputs)})))
              exit))))
      (finally (fs/delete lock)))))


(defn antlr!
  [args]
  ;; Filtered/discovery invocations retain the generator's exact CLI behavior;
  ;; only the complete default build can establish the complete-output cache.
  (let [manifests (sort (fs/glob "antlr" "*/manifest.edn"))
        outputs (vec (mapcat
                       (fn [path]
                         (let [m (edn/read-string (slurp (str path)))
                               grammar (str (fs/file-name (fs/parent path)))]
                           (concat (map :out (vals (:targets m)))
                                   [(str "build/antlr/" grammar "/classes")
                                    (str "build/antlr/" grammar "/upstream")])))
                       manifests))
        inputs {:files (snapshot ["antlr" "src/dev/yang_antlr_gen.clj"
                                  "src/dev/build_cache.clj" "deps.edn" "mise.toml"])
                :java (checked-output "java" "-version")
                :javac (checked-output "javac" "-version")}]
    (if (seq args)
      ;; Still use the same lock and invalidate the complete-build stamp.
      (do (fs/create-dirs "target/prereq-cache")
          (let [exit (cached! "antlr" (assoc inputs :args (vec args)) outputs
                              (into ["clojure" "-M:antlr-gen"] args))]
            (fs/delete-if-exists "target/prereq-cache/antlr.edn")
            exit))
      (cached! "antlr" inputs outputs ["clojure" "-M:antlr-gen"]))))


(defn npm!
  []
  (cached! "npm-ci"
           {:files (snapshot ["package.json" "package-lock.json" ".npmrc"
                              (str (fs/home) "/.npmrc") "mise.toml"
                              "src/dev/build_cache.clj"])
            :node (checked-output "node" "--version")
            :npm (checked-output "npm" "--version")
            :config (checked-output "npm" "config" "list" "--json")
            :platform [(System/getProperty "os.name") (System/getProperty "os.arch")]
            :env (into (sorted-map)
                       (filter (fn [[k _]]
                                 (or (str/starts-with? (str/lower-case k) "npm_config_")
                                     (#{"NODE_ENV" "NODE_OPTIONS"} k))))
                       (System/getenv))}
           ["node_modules"] ["npm" "ci"]))


(defn -main
  [mode & args]
  (try
    (System/exit (case mode "antlr" (antlr! args) "npm" (npm!)
                       (throw (ex-info "expected antlr or npm" {}))))
    (catch Exception ex
      (binding [*out* *err*] (println (ex-message ex)))
      (System/exit 1))))


(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))

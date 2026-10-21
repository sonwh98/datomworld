(ns jolt-bootstrap
  "bb bootstrap:jolt — materialize the jolt toolchain declared in
   jolt-toolchain.edn (docs/design/yin.repl-jolt-implementation.md §4.3, §6.1).

   * Chez and CMake come from mise at the exact pinned versions; this task
     checks them and never installs or upgrades them (`mise install` does).
   * jolt is cloned from the declared origin into build/tools/jolt and checked
     out at the declared full SHA with its recursive submodules. An existing
     checkout is verified, never pulled: a HEAD, submodule or dirty-tree
     mismatch fails the task. Advancing the pin is the upgrade gate's job.
   * build/tools/jolt-env.sh is written with JOLT_CHEZ set explicitly, so jolt
     never searches PATH for a Chez.
   * First of all, on a Debian-family host, the :os-packages :debian
     :required packages are checked with dpkg-query; a missing one fails the
     task before mise is asked for anything, naming the exact
     `sudo apt-get install -y ...` command. Nothing here runs sudo or apt.
     Test seam: JOLT_BOOTSTRAP_TEST_DEBIAN_INSTALLED=pkg,pkg,... treats the
     host as Debian and those packages as the installed set (no dpkg-query).

   Exit status is nonzero on any mismatch."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]))


(defn- fail!
  [& msg]
  (binding [*out* *err*]
    (println "bootstrap:jolt FAILED:" (apply str msg)))
  (System/exit 1))


(defn- sh
  "Run a command, answer trimmed stdout; fail the task on a nonzero exit."
  [& args]
  (let [opts (if (map? (first args)) (first args) {})
        cmd (if (map? (first args)) (rest args) args)
        r (apply p/shell (merge {:out :string :err :string :continue true} opts) cmd)]
    (when-not (zero? (:exit r))
      (fail! (str/join " " cmd) " exited " (:exit r) "\n" (:err r)))
    (str/trim (:out r))))


(defn- mise-bin
  "The path of executable `exe` from mise tool `tool` at exactly `version`
   (mise's own resolution: the layout differs per platform)."
  [tool version exe]
  (let [r (p/shell {:out :string :err :string :continue true}
                   "mise" "exec" (str tool "@" version) "--" "which" exe)]
    (when-not (zero? (:exit r))
      (fail! "mise has no " tool "@" version " installed; run `mise install " tool "`"))
    (str/trim (:out r))))


(defn- check-chez
  [{:keys [version binary threaded]}]
  (let [scheme (mise-bin "chezscheme" version (str (fs/file-name binary)))
        v (sh scheme "--version")
        t (sh {:in "(display (threaded?))"} scheme "-q")]
    (when-not (= version v) (fail! "Chez at " scheme " reports " v ", pinned " version))
    (when (and threaded (not= "#t" t)) (fail! "Chez at " scheme " is not threaded"))
    (println "chez" v "threaded" t scheme)
    scheme))


(defn- check-cmake
  [{:keys [version]}]
  (let [cmake (mise-bin "cmake" version "cmake")
        v (first (str/split-lines (sh cmake "--version")))]
    (when-not (= (str "cmake version " version) v)
      (fail! "CMake at " cmake " reports " v ", pinned " version))
    (println v cmake)
    cmake))


(defn- submodule-shas
  [dir]
  ;; Not through `sh`: its trim would eat the status column of the first line
  ;; (" " checked out at the recorded commit, "-" uninitialized, "+" differs).
  (let [r (p/shell {:out :string :err :string :continue true}
                   "git" "-C" dir "submodule" "status" "--recursive")]
    (when-not (zero? (:exit r)) (fail! "git submodule status: " (:err r)))
    (into {}
          (map (fn [line]
                 (let [[sha path] (str/split (str/trim (subs line 1)) #"\s+")]
                   (when (not= \space (first line))
                     (fail! "submodule " path " is not checked out at its recorded commit: " line))
                   [path sha])))
          (remove str/blank? (str/split-lines (:out r))))))


(defn- materialize!
  [{:keys [origin sha dir]}]
  (when-not (fs/exists? dir)
    (fs/create-dirs (fs/parent dir))
    (println "cloning" origin "into" dir)
    (sh "git" "clone" "--no-checkout" origin dir)
    (sh "git" "-C" dir "checkout" "--detach" sha)
    (sh "git" "-C" dir "submodule" "update" "--init" "--recursive")))


(defn- verify!
  [{:keys [origin sha dir submodules patches]}]
  (let [head (sh "git" "-C" dir "rev-parse" "HEAD")
        remote (sh "git" "-C" dir "remote" "get-url" "origin")]
    (when-not (= origin remote)
      (fail! dir " has origin " remote ", declared " origin))
    (when-not (= sha head)
      (fail! dir " is at " head ", declared " sha
             "; ordinary runs never pull — advance the pin through qualify:jolt-upgrade"))
    (when (seq patches)
      (fail! "patches are declared but this task does not apply them yet"))
    ;; Known gap (input to the §6.4 gate hardening): `status --porcelain`
    ;; does not see changes to gitignored files inside the clone.
    (let [dirty (sh "git" "-C" dir "status" "--porcelain")]
      (when-not (str/blank? dirty) (fail! dir " has local changes:\n" dirty)))
    (let [actual (submodule-shas dir)]
      (when-not (= submodules actual)
        (fail! "submodule SHAs differ from jolt-toolchain.edn: " (pr-str actual))))
    (println "jolt" head "with" (count submodules) "submodules verified")))


(defn- debian-family?
  "/etc/os-release names debian as its ID or in ID_LIKE."
  []
  (let [f "/etc/os-release"]
    (when (fs/exists? f)
      (let [kv (into {}
                     (keep (fn [line]
                             (when-let [[_ k v] (re-matches #"([A-Z_]+)=\"?([^\"]*)\"?" line)]
                               [k v])))
                     (str/split-lines (slurp f)))
            words (set (str/split (str (get kv "ID") " " (get kv "ID_LIKE")) #"\s+"))]
        (contains? words "debian")))))


(defn- dpkg-installed?
  [pkg]
  (let [r (p/shell {:out :string :err :string :continue true}
                   "dpkg-query" "-W" "-f=${Status}" pkg)]
    (and (zero? (:exit r)) (= "install ok installed" (str/trim (:out r))))))


(defn- check-os-packages
  "Fail, before any mise build, when a declared Debian package is missing."
  [{:keys [debian]}]
  (let [seam (System/getenv "JOLT_BOOTSTRAP_TEST_DEBIAN_INSTALLED")
        required (sort (keys (:required debian)))
        installed? (cond
                     seam (let [pkgs (set (remove str/blank? (str/split seam #",")))]
                            (println "os-packages: [test seam] Debian path forced; installed ="
                                     (str/join " " (sort pkgs)))
                            pkgs)
                     (debian-family?) dpkg-installed?
                     :else nil)]
    (if-not installed?
      (println "os-packages: not a Debian-family host; package check skipped")
      (let [missing (remove installed? required)]
        (when (seq missing)
          (fail! "missing Debian packages: " (str/join " " missing)
                 "\n  needed by: "
                 (str/join "; " (map #(str % " (" (get-in debian [:required %]) ")") missing))
                 "\n  install them with:\n    sudo apt-get install -y " (str/join " " missing)))
        (println "os-packages: all" (count required) "required Debian packages installed:"
                 (str/join " " required))))))


(defn -main
  [& _]
  (let [{:keys [jolt chez cmake os-packages]} (edn/read-string (slurp "jolt-toolchain.edn"))
        _ (check-os-packages os-packages)
        scheme (check-chez chez)
        _ (check-cmake cmake)
        dir (str (fs/absolutize (:dir jolt)))
        jolt (assoc jolt :dir dir)
        env-file (str (fs/path (fs/parent dir) "jolt-env.sh"))]
    (materialize! jolt)
    (verify! jolt)
    (spit env-file
          (str "# written by bb bootstrap:jolt from jolt-toolchain.edn; do not edit\n"
               "export JOLT_CHEZ='" scheme "'\n"
               "export JOLT_HOME='" dir "'\n"))
    (println "wrote" env-file)
    (let [r (p/shell {:out :string :err :string :continue true
                      :extra-env {"JOLT_CHEZ" scheme}}
                     (str (fs/path dir "bin" "jolt")) "-e" "(+ 1 2)")]
      (when-not (= "3" (str/trim (:out r)))
        (fail! "jolt smoke (+ 1 2) answered " (pr-str (:out r)) " " (:err r)))
      (println "jolt smoke (+ 1 2) => 3"))))


(apply -main *command-line-args*)

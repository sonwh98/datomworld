(ns jolt-bootstrap
  "bb bootstrap:jolt — materialize the jolt toolchain declared in
   jolt-toolchain.edn (docs/design/yin.repl-jolt-implementation.md §4.3, §6.1).

   * Chez and CMake come from mise at the exact pinned versions; this task
     checks them and never installs or upgrades them (`mise install` does).
   * jolt is cloned from the declared origin into build/tools/jolt and checked
     out at the declared full SHA with its recursive submodules. An existing
     checkout is verified, never pulled: a HEAD, submodule or dirty-tree
     mismatch fails the task. Declared patches and re-minted seeds are accepted
     only at their recorded digests and after behavioural smoke tests.
     The one-time nil-platform acceptance is remembered in
     build/tools/jolt-patches.edn; deleting that record resets it.
     Advancing the pin is the upgrade gate's job.
   * build/tools/jolt-env.sh is written with JOLT_CHEZ and JOLT_GAMBIT set
     explicitly, so jolt never searches PATH for a Chez or a gsi (bare gsc on
     PATH is Ghostscript). JOLT_GUILE is written when mise has the pinned
     guile; while jolt-toolchain.edn :guile :warning-only is true (Guile port
     Phase 1) a missing or mismatched guile only warns and leaves JOLT_GUILE
     unset, so hosts without the 15-25 min from-source build bootstrap fine.
   * First of all, on a Debian-family host, the :os-packages :debian
     :required packages (plus :guile-required once :guile :warning-only is
     false) are checked with dpkg-query; a missing one fails the task before
     mise is asked for anything, naming the exact `sudo apt-get install -y ...`
     command. Nothing here runs sudo or apt.
     Test seam: JOLT_BOOTSTRAP_TEST_DEBIAN_INSTALLED=pkg,pkg,... treats the
     host as Debian and those packages as the installed set (no dpkg-query).

   Exit status is nonzero on any mismatch."
  (:require [clojure.java.io]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.set :as set]
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
      (fail! (str/join " " cmd) " exited " (:exit r) "\n" (:err r)
             "\nRemedy: git -C build/tools/jolt checkout -- . && bb bootstrap:jolt"))
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


(defn- check-gambit
  "Analogous to check-chez: resolve gsi through mise at the exact pin, require
   the reported version (system-version-string, e.g. \"v4.9.8\") to equal the
   pin, and smoke the interpreter. Required, not warned: mise.toml pins gambit
   for every host, so `mise install` provisions it before this runs, and the
   jolt Gambit gates (Makefile GAMBIT_PREFIX targets) are part of the lane —
   bootstrapping without it would silently leave JOLT_GAMBIT unset."
  [{:keys [version binary]}]
  (let [gsi (mise-bin "gambit" version (str (fs/file-name binary)))
        v (sh gsi "-e" "(display (system-version-string))")
        t (sh gsi "-e" "(display (+ 1 2))")]
    (when-not (= (str "v" version) v)
      (fail! "Gambit at " gsi " reports " v ", pinned " version))
    (when-not (= "3" t) (fail! "Gambit at " gsi " failed the interpreter smoke"))
    (println "gambit" v gsi)
    gsi))


(defn- check-guile
  "Guile port Phase 1 (owner ruling 2026-11-27): warning-only. Resolve the
   install DIRECTORY with `mise where guile@<version>` — a query that never
   auto-installs and never consults PATH (`mise exec ... -- which guile`
   does both: with mise's exec auto-install on it would trigger the plugin's
   15-25 min build, and with it off it falls back to a bare PATH guile, e.g.
   Homebrew's — review 1795792000000 P1). Then require the declared
   :binary under that directory to exist and be executable, report the
   pinned version and smoke the interpreter, answering it for JOLT_GUILE.
   Missing, mismatched or not executable: print a warning and answer nil —
   JOLT_GUILE stays unset and the Chez/Gambit lanes are unaffected. Nothing
   here installs anything. Removing :warning-only from jolt-toolchain.edn
   (the Phase 2 gates) turns the same checks into bootstrap failures."
  [{:keys [version binary warning-only]}]
  (let [bail! (fn [& msg]
                (if warning-only
                  (binding [*out* *err*]
                    (println "bootstrap:jolt WARNING:" (apply str msg)))
                  (apply fail! msg)))
        run-safe (fn [& args]
                   (try
                     (apply p/shell {:out :string :err :string :continue true} args)
                     (catch Exception e
                       {:exit -1
                        :out ""
                        :err (or (.getMessage e) (str e))
                        :exception e})))
        r (run-safe "mise" "where" (str "guile@" version))
        dir (when (zero? (:exit r)) (str/trim (:out r)))
        guile (when (and (not (str/blank? dir))
                         (fs/exists? dir)
                         (fs/directory? dir))
                (str (fs/path dir binary)))
        guile (when (and guile
                         (fs/regular-file? guile)
                         (fs/executable? guile))
                guile)]
    (if-not guile
      (do (bail! "guile@" version " is not installed (or its declared binary "
                 (pr-str binary) " is missing/not executable"
                 (when (:exception r) (str "; " (:err r)))
                 "); JOLT_GUILE left unset"
                 (when warning-only
                   (str " (warning-only, Guile port Phase 1; provisioning it is"
                        " a 15-25 min from-source `mise install guile@" version
                        "`)")))
          nil)
      (let [v (run-safe guile "--version")
            t (when-not (:exception v)
                (run-safe guile "-c" "(display (+ 1 2))"))]
        (if (or (:exception v) (:exception t))
          (do (bail! "guile at " guile " failed to launch ("
                     (or (:err v) (:err t)) "); JOLT_GUILE left unset")
              nil)
          (let [;; first line: "guile (GNU Guile) 3.0.11"
                reported (last (str/split (first (str/split-lines
                                                   (str/trim (:out v))))
                                          #"\s+"))
                smoke (str/trim (:out t))]
            (if (and (zero? (:exit v)) (= version reported)
                     (zero? (:exit t)) (= "3" smoke))
              (do (println "guile" reported guile) guile)
              (do (bail! "guile at " guile " reported " (pr-str reported)
                         " / smoke " (pr-str smoke) ", pinned " version
                         "; JOLT_GUILE left unset")
                  nil))))))))


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
  [{:keys [origin sha dir submodules]}]
  (let [head (sh "git" "-C" dir "rev-parse" "HEAD")
        remote (sh "git" "-C" dir "remote" "get-url" "origin")
        remedy (str "\nRemedy: git -C build/tools/jolt checkout -- ."
                    " && git -C build/tools/jolt remote set-url origin " origin
                    " && git -C build/tools/jolt fetch origin " sha
                    " && git -C build/tools/jolt checkout --detach " sha
                    " && git -C build/tools/jolt submodule update --init --recursive"
                    " && bb bootstrap:jolt"
                    "\nAlternatively: rm -rf build/tools/jolt && bb bootstrap:jolt"
                    "\nPin changes go through qualify:jolt-upgrade.")]
    (when-not (= origin remote)
      (fail! dir " has origin " remote ", declared " origin remedy))
    (when-not (= sha head)
      (fail! dir " is at " head ", declared " sha remedy))
    (let [actual (submodule-shas dir)]
      (when-not (= submodules actual)
        (fail! "submodule SHAs differ from jolt-toolchain.edn: " (pr-str actual))))
    (println "jolt" head "with" (count submodules) "submodules verified")))


(defn- patch-fail!
  [& msg]
  (apply fail! (concat msg ["\nRemedy: git -C build/tools/jolt checkout -- . && bb bootstrap:jolt"])))


(defn- sha256
  [path]
  (when (fs/regular-file? path)
    (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
      (with-open [in (clojure.java.io/input-stream (str path))]
        (let [buf (byte-array 65536)]
          (loop []
            (let [n (.read in buf)]
              (when (pos? n) (.update digest buf 0 n) (recur))))))
      (apply str (map #(format "%02x" (bit-and 255 %)) (.digest digest))))))


(defn- platform
  []
  (case [(System/getProperty "os.name") (System/getProperty "os.arch")]
    ["Mac OS X" "aarch64"] :darwin-arm64
    ["Linux" "amd64"] :linux-x86_64
    (patch-fail! "unsupported patch platform")))


(defn- check-digests!
  [dir files phase]
  (doseq [[path hashes] files]
    (when-not (= (phase hashes) (sha256 (fs/path dir path)))
      (patch-fail! path " sha256 mismatch (" (name phase) ")"))))


(defn- smoke!
  [dir scheme form expect message]
  ;; Fail-closed target identity (Lane B C5): the child is a Chez-host jolt
  ;; bin, so it must itself report "jolt-target: chez" before any smoke
  ;; expectation is trusted. Absent or mismatching line fails the task.
  (let [id (p/shell {:out :string :err :string :continue true
                     :extra-env {"JOLT_CHEZ" scheme "JOLT_HOME" dir
                                 "JOLT_TARGET" "chez"}}
                    (str (fs/path dir "bin" "jolt")) "-e"
                    ;; bin/jolt -e evaluates a Clojure form and prints the
                    ;; result via prn; the identity line is the first line.
                    "(println (str \"jolt-target: \" (or (System/getenv \"JOLT_TARGET\") \"chez\")))")
        id-out (first (str/split-lines (str/trim (str (:out id) (:err id)))))]
    (when-not (and (zero? (:exit id)) (= "jolt-target: chez" id-out))
      (patch-fail! "jolt child target identity mismatch: expected \"jolt-target: chez\", observed "
                   (pr-str id-out) " (" (:err id) ")"))
    (println "jolt-target: chez (child identity verified)"))
  (let [r (p/shell {:out :string :err :string :continue true
                    :extra-env {"JOLT_CHEZ" scheme "JOLT_HOME" dir}}
                   (str (fs/path dir "bin" "jolt")) "-e" form)]
    (when-not (and (zero? (:exit r)) (= expect (str/trim (:out r))))
      (patch-fail! message ": " (pr-str (:out r)) " " (:err r)))
    (println "jolt smoke" form "=>" expect)))


(defn- apply-patches!
  [{:keys [sha dir patches]} scheme]
  (let [plat (platform)
        record-file (str (fs/path (fs/parent dir) "jolt-patches.edn"))
        prior (when (fs/exists? record-file) (edn/read-string (slurp record-file)))
        ;; -z preserves the status column, spaces and rename paths. Renames
        ;; cannot be a declared patch state and fail below as unknown paths.
        ;; Gitignored undeclared files in the clone are invisible to git status.
        status-result (p/shell {:out :string :err :string :continue true}
                               "git" "-C" dir "status" "--porcelain=v1" "-z")
        _ (when-not (zero? (:exit status-result))
            (patch-fail! "git status failed: " (:err status-result)))
        status (:out status-result)
        entries (remove str/blank? (str/split status #"\u0000"))
        _ (doseq [entry entries]
            (when (or (< (count entry) 3)
                      (not= \space (nth entry 2))
                      (some #{\R \C} (take 2 entry)))
              (patch-fail! "unsupported dirty/renamed path: " (pr-str entry))))
        dirty (set (map #(subs % 3) entries))
        allowed (set (mapcat #(concat (keys (:files %))
                                      (mapcat keys (vals (:generated %)))) patches))
        unknown (set/difference dirty allowed)]
    (when (seq unknown) (patch-fail! "unknown dirty paths: " (pr-str (sort unknown))))
    (doseq [{:keys [id base path] expected :sha256} patches]
      (when-not (= sha base) (patch-fail! id " patch base " base " differs from pin " sha))
      (when-not (= expected (sha256 path)) (patch-fail! path " sha256 mismatch")))
    (let [skip-requested? (= "1" (System/getenv "JOLT_BOOTSTRAP_TEST_NO_APPLY"))
          test-dir (System/getenv "JOLT_BOOTSTRAP_TEST_DIR")
          _ (when (and skip-requested? (nil? test-dir))
              (binding [*out* *err*]
                (println "WARNING: JOLT_BOOTSTRAP_TEST_NO_APPLY ignored without JOLT_BOOTSTRAP_TEST_DIR")))
          no-apply? (and skip-requested? (some? test-dir))
          live? (and (seq patches)
                     (every? (fn [patch]
                               (every? (fn [[path hashes]]
                                         (= (:after hashes) (sha256 (fs/path dir path))))
                                       (:files patch))) patches))
          applied
          (cond
            no-apply? [] ; deliberately reach the behavioural gate on the old seed
            live?
            (do
              (doseq [patch patches]
                (check-digests! dir (get-in patch [:generated plat]) :after))
              (println "patches live")
              (mapv (fn [patch]
                      (or (when (and (= sha (:base prior)) (= plat (:platform prior)))
                            (some #(when (and (= (:id patch) (:id %))
                                              (= (:sha256 patch) (:sha256 %))) %)
                                  (:applied prior)))
                          {:remint-passes 0})) patches))
            :else
            (do
              (when (seq dirty) (patch-fail! "partial patch state: " (pr-str (sort dirty))))
              (mapv
                (fn [{:keys [id path files remint generated] :as patch}]
                  (check-digests! dir files :before)
                  (check-digests! dir (get generated plat) :before)
                  (let [r (p/shell {:out :string :err :string :continue true}
                                   "git" "-C" dir "apply" "--check" (str (fs/absolutize path)))]
                    (when-not (zero? (:exit r)) (patch-fail! path " patch rejected: " (:err r))))
                  (sh "git" "-C" dir "apply" (str (fs/absolutize path)))
                  (let [output (when remint
                                 (let [tmp (str (fs/path dir ".git" "bootstrap-tmp"))]
                                   (fs/create-dirs tmp)
                                   (sh {:dir dir :extra-env {"JOLT_CHEZ" scheme "TMPDIR" tmp}}
                                       "sh" "host/chez/remint.sh")))
                        passes (if remint
                                 (some-> (re-find #"converged after ([0-9]+) pass" output) second parse-long)
                                 0)]
                    (when remint (println output))
                    (when-not (and passes (<= 0 passes 8))
                      (patch-fail! id " remint did not report convergence within 8 passes"))
                    (check-digests! dir files :after)
                    (check-digests! dir (get generated plat) :after)
                    {:id id :sha256 (:sha256 patch) :remint-passes passes})) patches)))]
      (smoke! dir scheme "(+ 1 2)" "3" "jolt smoke failed")
      (doseq [{:keys [smoke]} patches]
        (smoke! dir scheme (:form smoke) (:expect smoke) "patch declared, not live"))
      (let [applied (mapv
                      (fn [patch entry]
                        (let [known (get-in patch [:generated plat])
                              paths (keys (or known (some identity (vals (:generated patch)))))]
                          (when (and (:remint patch) (nil? known))
                            (println (:id patch) plat "generated digests nil: :requalify; record and fill jolt-toolchain.edn")
                            (when (some #(and (= (:id patch) (:id %)) (:requalify %)) (:applied prior))
                              (patch-fail! (:id patch) " nil platform entry cannot be accepted twice; fill :generated " plat)))
                          (cond-> (assoc (dissoc entry :requalify) :id (:id patch) :sha256 (:sha256 patch)
                                         :generated
                                         (into {} (map (fn [path]
                                                         (let [digest (sha256 (fs/path dir path))]
                                                           (when-not digest (patch-fail! path " generated file missing"))
                                                           [path {:before (get-in (or known (some identity (vals (:generated patch)))) [path :before])
                                                                  :after digest}])) paths)))
                            (and (:remint patch) (nil? known)) (assoc :requalify true))))
                      patches applied)]
        (spit record-file (str (pr-str {:base sha :applied applied :platform plat
                                        :chez scheme :at (str (java.time.Instant/now))}) "\n"))
        record-file))))


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
  "Fail, before any mise build, when a declared Debian package is missing.
   :guile-required packages are enforced only when (:warning-only guile) is false."
  [{:keys [debian]} {:keys [warning-only]}]
  (let [seam (System/getenv "JOLT_BOOTSTRAP_TEST_DEBIAN_INSTALLED")
        pkgs-map (cond-> (:required debian)
                   (not warning-only) (merge (:guile-required debian)))
        required (sort (keys pkgs-map))
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
                 (str/join "; " (map #(str % " (" (get pkgs-map %) ")") missing))
                 "\n  install them with:\n    sudo apt-get install -y " (str/join " " missing)))
        (println "os-packages: all" (count required) "required Debian packages installed:"
                 (str/join " " required))))))


(defn -main
  [& _]
  (let [{:keys [jolt chez gambit guile cmake os-packages]} (edn/read-string (slurp "jolt-toolchain.edn"))
        _ (check-os-packages os-packages guile)
        scheme (check-chez chez)
        gsi (check-gambit gambit)
        guile-bin (check-guile guile)
        _ (check-cmake cmake)
        dir (str (fs/absolutize (or (System/getenv "JOLT_BOOTSTRAP_TEST_DIR") (:dir jolt))))
        jolt (assoc jolt :dir dir)
        env-file (str (fs/path (fs/parent dir) "jolt-env.sh"))]
    (materialize! jolt)
    (verify! jolt)
    (let [record (apply-patches! jolt scheme)]
      (spit env-file
            (str "# written by bb bootstrap:jolt from jolt-toolchain.edn; do not edit\n"
                 "export JOLT_CHEZ='" scheme "'\n"
                 "export JOLT_GAMBIT='" gsi "'\n"
                 (if guile-bin
                   (str "export JOLT_GUILE='" guile-bin "'\n")
                   (str "# JOLT_GUILE unset: guile missing or mismatched"
                        " (warning-only, Guile port Phase 1)\n"
                        "unset JOLT_GUILE\n"))
                 "export JOLT_HOME='" dir "'\n"
                 "export JOLT_PATCHES='" record "'\n"))
      (println "wrote" env-file))))


(defn- test-negative!
  []
  (let [toolchain (edn/read-string (slurp "jolt-toolchain.edn"))
        source (or (System/getenv "JOLT_BOOTSTRAP_TEST_DIR") (get-in toolchain [:jolt :dir]))
        script (str (fs/absolutize "src/dev/jolt_bootstrap.clj"))
        root (fs/create-temp-dir {:dir (doto "build/bootstrap-neg" fs/create-dirs)
                                  :prefix "neg-"})
        clone (str (fs/absolutize (fs/path root "jolt")))]
    (println "negative gate temporary directory (retained on failure):" (str root))
    (fs/copy-tree source clone)
    (sh "git" "-C" clone "checkout" "--" ".")
    ;; Independent of the live pin's patches: a harmless tracked comment,
    ;; with a behavioural probe that reads exactly the patched file. No mint.
    (let [path ".gitmodules"
          target (fs/path clone path)
          before (sha256 target)
          contents (str (slurp (str target)) "\n# bootstrap negative gate synthetic patch\n")
          patch-path "synthetic.patch"
          patch-file (fs/path root patch-path)
          _ (spit (str target) contents)
          after (sha256 target)
          patch-text (str (sh "git" "-C" clone "diff" "--" path) "\n")
          _ (spit (str patch-file) patch-text)
          patch {:id "bootstrap-negative-synthetic"
                 :path patch-path :sha256 (sha256 patch-file)
                 :base (get-in toolchain [:jolt :sha])
                 :files {path {:before before :after after}}
                 :remint false
                 :smoke {:form (str "(= " (pr-str contents) " (slurp "
                                    (pr-str (str target)) "))")
                         :expect "true"}}
          test-toolchain (assoc-in toolchain [:jolt :patches] [patch])]
      (sh "git" "-C" clone "checkout" "--" path)
      (spit (str (fs/path root "jolt-toolchain.edn")) (pr-str test-toolchain))
      (println "synthetic patch digests:" (pr-str (select-keys patch [:sha256 :files])))
      (doseq [[label no-apply expected-exit expected]
              [["unpatched" "1" 1 "patch declared, not live"]
               ["corrupt" "" 1 (str patch-path " sha256 mismatch")]
               ["applied" "" 0 "=> true"]]]
        (spit (str patch-file) (str patch-text (when (= label "corrupt") "\ncorrupt patch\n")))
        (let [r (p/shell {:dir (str root) :out :string :err :string :continue true
                          :extra-env {"JOLT_BOOTSTRAP_TEST_DIR" clone
                                      "JOLT_BOOTSTRAP_TEST_NO_APPLY" no-apply}}
                         "bb" script)
              output (str (:out r) (:err r))]
          (spit (str (fs/path root (str label ".log"))) output)
          (when-not (and (= expected-exit (:exit r)) (str/includes? output expected))
            (fail! "negative gate " label " expected exit " expected-exit " / " expected
                   ", got " (:exit r) "\n" output))
          (println "PASS" label (str "exit " expected-exit ":") expected))))
    (fs/delete-tree root)
    (println "negative gates passed; deleted temporary directory:" (str root))))


(defn- test-guile-absent!
  "Negative gate for the warning-only guile check (reviews 1795792000000 P1
   and guile-toolchain-r4):
   1. Absent pin (9.9.9) with :warning-only true and ONLY the 6 base Debian
      packages installed: exits 0 with warning, passes check-os-packages
      without :guile-required packages, emits `unset JOLT_GUILE` in
      jolt-env.sh (verified by sourcing with JOLT_GUILE pre-set), and never
      runs mise install/network/exec against guile.
   2. :warning-only false with only the 6 base Debian packages installed:
      fails preflight naming the 5 missing :guile-required packages before
      mise runs.
   3. Unlaunchable binary (bad shebang -> IOException in p/shell) under
      :warning-only true: caught cleanly, routes through bail! warning, exits
      0 with `unset JOLT_GUILE`."
  []
  (let [toolchain (edn/read-string (slurp "jolt-toolchain.edn"))
        script (str (fs/absolutize "src/dev/jolt_bootstrap.clj"))
        root (str (fs/absolutize
                    (fs/create-temp-dir {:dir (doto "build/bootstrap-neg" fs/create-dirs)
                                         :prefix "guile-absent-"})))
        source (str (fs/absolutize (get-in toolchain [:jolt :dir])))
        clone (str (fs/absolutize (fs/path root "jolt")))
        shim-dir (str (fs/path root "mise-shim"))
        log (str (fs/path root "mise-calls.log"))
        fake-guile-dir (str (fs/path root "fake-guile"))
        fake-guile-bin (str (fs/path fake-guile-dir "bin" "guile"))
        real-mise (str/trim (:out (p/shell {:out :string} "which" "mise")))
        base-debian "libncurses-dev,libx11-dev,gcc,make,git,libssl-dev"
        guile-pkgs ["libffi-dev" "libgc-dev" "libgmp-dev" "libunistring-dev" "pkg-config"]
        installs (fs/path (System/getenv "HOME")
                          ".local" "share" "mise" "installs" "guile" "9.9.9")]
    (println "guile-absent gate temporary directory (retained on failure):" (str root))
    (fs/create-dirs shim-dir)
    (fs/create-dirs (fs/parent fake-guile-bin))
    (spit fake-guile-bin "#!/nonexistent/guile-test-interp\n")
    (p/shell {:continue true} "chmod" "+x" fake-guile-bin)
    (spit (str (fs/path shim-dir "mise"))
          (str "#!/bin/sh\n"
               "echo \"$@\" >> " log "\n"
               "if [ \"$1\" = \"where\" ] && [ \"$2\" = \"guile@9.9.8\" ]; then\n"
               "  echo " fake-guile-dir "\n"
               "  exit 0\n"
               "fi\n"
               "exec " real-mise " \"$@\"\n"))
    (p/shell {:continue true} "chmod" "+x" (str (fs/path shim-dir "mise")))
    (fs/copy-tree source clone)
    (when (fs/exists? (str installs))
      (fail! "guile-absent gate: " (str installs) " already exists; refusing to run"))
    (let [run-case (fn [tc extra-env]
                     (spit log "")
                     (spit (str (fs/path root "jolt-toolchain.edn")) (pr-str tc))
                     (let [r (p/shell {:dir (str root) :out :string :err :string :continue true
                                       :extra-env (merge {"PATH" (str shim-dir fs/path-separator
                                                                      (System/getenv "PATH"))}
                                                         extra-env)}
                                      "bb" script)
                           calls (if (fs/exists? log)
                                   (remove str/blank? (str/split-lines (slurp log)))
                                   [])]
                       {:exit (:exit r)
                        :output (str (:out r) (:err r))
                        :calls calls}))
          tc-absent (-> toolchain
                        (assoc-in [:guile :version] "9.9.9")
                        (assoc-in [:jolt :dir] clone))
          r1 (run-case tc-absent {"JOLT_BOOTSTRAP_TEST_DEBIAN_INSTALLED" base-debian})
          env-file (str (fs/path root "jolt-env.sh"))
          env-text (slurp env-file)
          src-check (p/shell {:out :string :err :string :continue true}
                             "sh" "-c"
                             "export JOLT_GUILE=/stale/guile; . \"$1\"; test -z \"${JOLT_GUILE+x}\""
                             "sh" env-file)
          r2 (run-case (assoc-in tc-absent [:guile :warning-only] false)
                       {"JOLT_BOOTSTRAP_TEST_DEBIAN_INSTALLED" base-debian})
          tc-launch (-> toolchain
                        (assoc-in [:guile :version] "9.9.8")
                        (assoc-in [:jolt :dir] clone))
          r3 (run-case tc-launch {})
          env-text3 (slurp env-file)
          checks
          [["absent: exit 0 with warning-only and 6 base Debian pkgs"
            (and (zero? (:exit r1))
                 (str/includes? (:output r1) "WARNING: guile@9.9.9")
                 (str/includes? (:output r1) "all 6 required Debian packages installed"))]
           ["absent: jolt-env.sh emits unset JOLT_GUILE"
            (and (str/includes? env-text "unset JOLT_GUILE\n")
                 (not (re-find #"(?m)^export JOLT_GUILE=" env-text)))]
           ["absent: sourcing jolt-env.sh clears pre-set JOLT_GUILE"
            (zero? (:exit src-check))]
           ["absent: mise where called for guile@9.9.9"
            (some #(= "where guile@9.9.9" %) (:calls r1))]
           ["absent: no mise install or network command attempted"
            (not (some #(re-matches #"(install|ls-remote|sync|upgrade|up)\b.*" %)
                       (:calls r1)))]
           ["absent: no mise exec against guile"
            (not (some #(str/includes? % "guile@9.9.9")
                       (filter #(str/starts-with? % "exec") (:calls r1))))]
           ["absent: guile@9.9.9 not materialized" (not (fs/exists? (str installs)))]
           ["required: warning-only false fails preflight on missing guile-required pkgs"
            (and (= 1 (:exit r2))
                 (empty? (:calls r2))
                 (every? #(str/includes? (:output r2) %) guile-pkgs))]
           ["launch-error: IOException caught and routed via warning-only bail!"
            (and (zero? (:exit r3))
                 (str/includes? (:output r3) "WARNING: guile at ")
                 (str/includes? (:output r3) "failed to launch")
                 (str/includes? env-text3 "unset JOLT_GUILE\n"))]]
          failed (remove second checks)]
      (println "mise calls (absent case):" (pr-str (:calls r1)))
      (println "mise calls (warning-only=false Debian preflight):" (pr-str (:calls r2)))
      (println "mise calls (launch-error case):" (pr-str (:calls r3)))
      (doseq [[label _] checks]
        (println (if (some #(= label (first %)) failed) "FAIL" "PASS") label))
      (when (seq failed)
        (fail! "guile-absent negative gate failed: " (pr-str (map first failed))
               "\n--- r1 ---\n" (:output r1)
               "\n--- r2 ---\n" (:output r2)
               "\n--- r3 ---\n" (:output r3)))
      (fs/delete-tree root)
      (println "guile-absent negative gate passed; deleted temporary directory:"
               (str root)))))


(if (= ["--test-negative"] (vec *command-line-args*))
  (test-negative!)
  (if (= ["--test-negative-guile"] (vec *command-line-args*))
    (test-guile-absent!)
    (apply -main *command-line-args*)))

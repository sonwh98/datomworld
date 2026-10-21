;; bb probe:jolt-crypto — the J1b provenance and collect-safety gates
;; (docs/design/yin.repl-jolt-implementation.md §6.3), each in a child jolt
;; process so a refusal or an abort cannot erase the report:
;;
;;   1. qualified load: dao.host.hash and dao.host.sign load, and the wrapper
;;      reports exactly the :qualified libcrypto (canonical path, version,
;;      file SHA-256);
;;   2. missing library: a root with no build/native fails the load, nonzero;
;;   3. undeclared library: the :qualified record names a byte-identical
;;      copy at another path; refused, nonzero;
;;   4. upgrade: the :qualified version differs (patch + 1); refused;
;;   5. repoint: the declared :path is a link resolving to the copy; refused;
;;   6. same-path replacement: the :qualified digest differs; refused;
;;   7. version floor: :min-version 99.0.0; refused;
;;   8-9. bb build:jolt-native, in a fake root, refuses a repoint and a
;;      replacement before building anything;
;;   10. bb qualify:jolt-libcrypto observes exactly the :qualified entry;
;;   11. wrong library first: the copy is loaded into the process before
;;      dao.host.hash; the load either keeps the qualified library or
;;      refuses, never answers from the copy;
;;   12. (Linux) LD_PRELOAD of the copy: refused, nonzero;
;;   13. fault injection (probes/faults.clj): every native acquisition
;;      released when a later allocation, copy, free or cleanse fails; seed
;;      memory zeroed; nothing allocated while cleansing;
;;   14. collect-safety: probes/collect.clj.
;;
;; The fake-root gates (3-7) change the toolchain record, not the system
;; library: they show each field is enforced, not a real upgrade.
;;
;; Needs bb bootstrap:jolt and bb build:jolt-native. Prints PASS/FAIL per
;; gate and RECORD per observation; exits nonzero on any FAIL. Fake roots and
;; the copy live under build/j1b-probe/ and are rebuilt each run.
(ns run
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]))


(def failures (atom 0))


(defn- check
  [label ok? detail]
  (when-not ok? (swap! failures inc))
  (println (if ok? "PASS" "FAIL") label (pr-str detail)))


(def root (str (fs/absolutize ".")))
(def probes (str (fs/path root "native/jolt-crypto/probes")))
(def toolchain (edn/read-string (slurp "jolt-toolchain.edn")))


(def plat
  (let [os (str/lower-case (System/getProperty "os.name"))
        arch (System/getProperty "os.arch")]
    (str (if (str/includes? os "mac") "darwin" "linux") "-"
         (if (#{"aarch64" "arm64"} arch) "arm64" "x86_64"))))


(def linux? (str/starts-with? plat "linux"))
(def declared (get-in toolchain [:native :libcrypto :path (keyword plat)]))
(def declared-canonical (str (fs/real-path declared)))


(defn- jolt
  "Run jolt -Srepro -M:jolt in `dir` with `args`; answers exit and output."
  [dir env & args]
  (let [r (apply p/shell {:dir dir :out :string :err :string :continue true
                          :extra-env env}
                 "sh" "-c"
                 ". build/tools/jolt-env.sh && exec \"$JOLT_HOME/bin/jolt\" -Srepro -M:jolt \"$@\""
                 "jolt" args)]
    {:exit (:exit r) :out (str (:out r) (:err r))}))


(defn- line
  [out prefix]
  (some #(when (str/starts-with? % prefix) %) (str/split-lines out)))


(defn- fake-root
  "A root sharing this checkout's sources and jolt, with `toolchain-edn` as
   its jolt-toolchain.edn and build/native only when `native?`."
  [nm toolchain-edn native?]
  (let [dir (fs/path root "build/j1b-probe" nm)]
    (fs/delete-tree dir)
    (fs/create-dirs (fs/path dir "build"))
    (doseq [f ["deps.edn" "src"]]
      (fs/create-sym-link (fs/path dir f) (fs/path root f)))
    (fs/create-sym-link (fs/path dir "build/tools") (fs/real-path (fs/path root "build/tools")))
    (when native?
      (fs/create-sym-link (fs/path dir "build/native") (fs/real-path (fs/path root "build/native"))))
    (spit (str (fs/path dir "jolt-toolchain.edn")) (pr-str toolchain-edn))
    (str dir)))


;; a byte-identical copy of the declared libcrypto at another path
(def copy
  (let [dir (fs/path root "build/j1b-probe/libcrypto-copy")
        dest (fs/path dir (fs/file-name declared))]
    (fs/delete-tree dir)
    (fs/create-dirs dir)
    (fs/copy declared-canonical dest)
    (str dest)))


(def qualified (get-in toolchain [:native :libcrypto :qualified (keyword plat)]))

(def provenance-re #"PROVENANCE (\d+)\t([^\t]*)\t([^\t]*)\t([0-9a-f]{64})")


(defn- refuses
  "A gate whose child must exit nonzero with `message` in its output."
  [label {:keys [exit out]} message]
  (check label
         (and (not (zero? exit)) (str/includes? out message) (not (line out "LOADED")))
         {:exit exit :out (first (str/split-lines out))}))


(defn- with-qualified
  "The toolchain with this platform's :qualified entry changed by `f`."
  [f]
  (update-in toolchain [:native :libcrypto :qualified (keyword plat)] f))


;; 1. qualified load
(let [{:keys [exit out]} (jolt root {} (str probes "/provenance.clj"))
      prov (line out "PROVENANCE")
      [_ num version path digest] (when prov (re-matches provenance-re prov))]
  (println "RECORD" prov)
  (run! println (filter #(str/starts-with? % "RECORD") (str/split-lines out)))
  (check "qualified load: hash and sign load; EVP resolved to the qualified libcrypto"
         (and (zero? exit) (line out "LOADED") path
              (= (:canonical-path qualified) (str (fs/real-path path)))
              (= (:version-num qualified) (some-> num Long/parseLong))
              (= (:sha256 qualified) digest))
         {:exit exit :declared declared :qualified qualified
          :resolved path :version version :version-num num :sha256 digest
          :out (when-not (zero? exit) out)}))


;; 2. missing library
(let [dir (fake-root "missing" toolchain false)]
  (refuses "missing library: the load fails, nonzero, naming the wrapper"
           (jolt dir {} (str probes "/provenance.clj")) "libjolt_crypto"))


;; 3. undeclared library: the qualified record names another file
(let [dir (fake-root "undeclared" (with-qualified #(assoc % :canonical-path copy)) true)]
  (refuses "undeclared library: refused, nonzero"
           (jolt dir {} (str probes "/provenance.clj"))
           "EVP resolved to an undeclared libcrypto"))


;; 4. upgrade: the library's version is not the qualified one (patch + 1)
(let [dir (fake-root "upgrade" (with-qualified #(update % :version-num + 16)) true)]
  (refuses "upgrade (version differs from the qualified): refused, nonzero"
           (jolt dir {} (str probes "/provenance.clj"))
           "libcrypto version differs from the qualified one"))


;; 5. repoint: the declared path is a link that now resolves to another file
(let [link-dir (fs/path root "build/j1b-probe/libcrypto-link")
      link (fs/path link-dir (fs/file-name declared))
      _ (do (fs/delete-tree link-dir)
            (fs/create-dirs link-dir)
            (fs/create-sym-link link copy))
      dir (fake-root "repoint"
                     (assoc-in toolchain [:native :libcrypto :path (keyword plat)] (str link))
                     true)]
  (refuses "repoint (declared path resolves elsewhere): refused, nonzero"
           (jolt dir {} (str probes "/provenance.clj"))
           "the declared libcrypto path resolves to another file than the qualified one"))


;; 6. same-path replacement: same path and version, another file digest
(let [dir (fake-root "replaced" (with-qualified #(assoc % :sha256 (apply str (repeat 64 "0")))) true)]
  (refuses "same-path replacement (digest differs): refused, nonzero"
           (jolt dir {} (str probes "/provenance.clj"))
           "libcrypto file digest differs from the qualified one"))


;; 7. version floor
(let [dir (fake-root "min-version"
                     (assoc-in toolchain [:native :libcrypto :min-version] "99.0.0")
                     true)]
  (refuses "version floor: refused, nonzero"
           (jolt dir {} (str probes "/provenance.clj"))
           "older than the declared minimum"))


;; 8-9. bb build:jolt-native refuses the same changes before building
(doseq [[nm label edn message]
        [["build-repoint" "build: repoint (canonical path differs)"
          (with-qualified #(assoc % :canonical-path copy))
          "differs from the qualified"]
         ["build-replaced" "build: same-path replacement (digest differs)"
          (with-qualified #(assoc % :sha256 (apply str (repeat 64 "0"))))
          "the qualified library"]]]
  (let [dir (fake-root nm edn false)
        r (p/shell {:dir dir :out :string :err :string :continue true}
                   "bb" (str (fs/path root "src/dev/jolt_native.clj")))
        out (str (:out r) (:err r))]
    (check (str label ": refused before any build, nonzero")
           (and (not (zero? (:exit r))) (str/includes? out "build:jolt-native FAILED")
                (str/includes? out message) (not (fs/exists? (fs/path dir "build/native"))))
           {:exit (:exit r) :out (last (str/split-lines (str/trim out)))})))


;; 10. requalification reports the current library as equal to the record
(let [r (p/shell {:dir root :out :string :err :string :continue true}
                 "bb" "native/jolt-crypto/qualify.clj")
      out (str (:out r) (:err r))]
  (check "qualify:jolt-libcrypto observes exactly the :qualified entry"
         (and (zero? (:exit r)) (str/includes? out "equal to the current :qualified entry"))
         {:exit (:exit r) :out (when-not (zero? (:exit r)) out)}))


;; 11. wrong library first
(let [{:keys [exit out]} (jolt root {} (str probes "/provenance.clj") "wrong-first" copy)
      path (some->> (line out "PROVENANCE") (re-matches provenance-re) (drop 3) first)
      outcome (cond (and (zero? exit) path (= (:canonical-path qualified) (str (fs/real-path path))))
                    :kept-declared
                    (and (not (zero? exit)) (str/includes? out "EVP resolved to an undeclared libcrypto"))
                    :refused
                    :else :other)]
  (check "wrong library first: the qualified library is kept or the load refuses"
         (not= :other outcome)
         {:outcome outcome :exit exit :resolved path :out (when (= :other outcome) out)}))


;; 12. LD_PRELOAD (Linux only)
(if linux?
  (refuses "LD_PRELOAD of a copy: refused, nonzero"
           (jolt root {"LD_PRELOAD" copy} (str probes "/provenance.clj"))
           "EVP resolved to an undeclared libcrypto")
  (println "RECORD LD_PRELOAD gate: Linux only, not run on" plat))


;; 13. fault injection over every native acquisition
(let [{:keys [exit out]} (jolt root {} (str probes "/faults.clj"))]
  (run! println (map #(str "  " %) (filter #(re-find #"^(PASS|FAIL)" %) (str/split-lines out))))
  (check "fault injection: every acquisition released, seeds zeroed, no allocation while cleansing"
         (and (zero? exit) (line out "SUMMARY ok"))
         {:exit exit :out (when-not (zero? exit) (last (str/split-lines out)))}))


;; 14. collect-safety
(let [{:keys [exit out]} (jolt root {} (str probes "/collect.clj"))]
  (run! println (filter #(re-find #"^(RECORD|PASS|FAIL)" %) (str/split-lines out)))
  (check "collect-safety probe exits 0" (zero? exit)
         {:exit exit :out (when-not (zero? exit) out)}))


(println "SUMMARY" (if (zero? @failures) "ok" (str @failures " failed")))
(System/exit (if (zero? @failures) 0 1))

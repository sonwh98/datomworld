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
;; J6c (layout 4, J6 plan v2 A7):
;;   J6c-1. the build record: exactly ten exports, the layout-3 nine plus
;;      jolt_crypto_sha1;
;;   J6c-2. twenty provenance entries with EVP_sha1 among them; an EVP_sha1
;;      outside libcrypto answers JC_SPLIT (probes/sha1_split.c, with a
;;      control compiled the same way);
;;   J6c-3. dao.stream.ws.crypto: RFC 3174 and handshake SHA-1 KATs,
;;      masking keys (probes/ws_crypto.clj);
;;   J6c-4. a layout-3 wrapper built from the J2 source: refused at load
;;      before guest work;
;;   J6c-5. a layout-4 wrapper missing jolt_crypto_sha1: refused at load;
;;   J6c-6. wrong library first, through dao.stream.ws.crypto;
;;   J6c-7. no masking-key draw before the gate (unqualified library; on
;;      Linux a RAND-only preload with a marker).
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


;; Compile the real C wrapper with RAND_bytes substituted only in this probe.
(let [prefix (str (fs/path root "build/native" plat))
      executable (str (fs/path root "build/j1b-probe/random-failure"))
      cc (p/shell {:out :string :err :string :continue true}
                  "cc" "-Wall" "-Wextra" "-Werror"
                  (str "-I" prefix "/blake3/include")
                  (str "-I" (fs/parent (fs/parent declared)) "/include")
                  (str probes "/random_failure.c")
                  (str prefix "/blake3/lib/libblake3.a") declared
                  "-ldl" "-o" executable)
      result (when (zero? (:exit cc))
               (p/shell {:out :string :err :string :continue true} executable))]
  (check "failed native draw zeroes output"
         (and result (zero? (:exit result)))
         {:compile-exit (:exit cc) :exit (:exit result)
          :out (str (:out cc) (:err cc) (:out result) (:err result))}))


;; RAND-only interposition must refuse before even invoking RAND_bytes.
(if linux?
  (let [so (str (fs/path root "build/j1b-probe/rand-only.so"))
        marker (str (fs/path root "build/j1b-probe/rand-called"))
        cc (p/shell {:out :string :err :string :continue true}
                    "cc" "-shared" "-fPIC" "-Wall" "-Wextra" "-Werror"
                    "-o" so (str probes "/rand_only.c"))]
    (check "RAND-only interposer builds" (zero? (:exit cc)) {:exit (:exit cc)})
    (when (zero? (:exit cc))
      (fs/delete-if-exists marker)
      (let [{:keys [exit out]} (jolt root {"LD_PRELOAD" so "JOLT_RAND_MARKER" marker}
                                     (str probes "/random_load.clj"))]
        (check "RAND-only preload refused before any seed draw"
               (and (not (zero? exit))
                    (str/includes? out "libcrypto provenance unavailable")
                    (str/includes? out ":status -4")
                    (not (str/includes? out "SEED-RETURNED"))
                    (not (fs/exists? marker)))
               {:exit exit :out out}))))
  (println "RECORD RAND-only preload gate: not applicable on macOS; LD_PRELOAD is a Linux loader mechanism. Linux execution UNRUN."))


;; =============================================================================
;; J6c (layout 4): jolt_crypto_sha1, EVP_sha1 in the provenance audit,
;; dao.stream.ws.crypto behind the gate
;; =============================================================================

(def ext (if linux? ".so" ".dylib"))
(def j6c-scratch (fs/path root "build/j1b-probe/j6c"))
(fs/delete-tree j6c-scratch)
(fs/create-dirs j6c-scratch)


(def j2-wrapper-commit
  ;; the last layout-3 jolt_crypto.c (J2), compiled below as the stale
  ;; prebuilt wrapper a host keeps until it reruns bb build:jolt-native
  "86f36bc62215156ebc1cd753e950a000a9a8f64d")


(defn- compile-wrapper
  "A shared library built from C `source` with the same headers, BLAKE3 and
   declared libcrypto as the real wrapper, plus `flags`; answers its path or
   nil (the compiler output printed)."
  [nm source & flags]
  (let [prefix (str (fs/path root "build/native" plat))
        lib (str (fs/path j6c-scratch (str nm ext)))
        r (apply p/shell {:out :string :err :string :continue true}
                 "cc" "-shared" "-fPIC"
                 (str "-I" prefix "/blake3/include")
                 (str "-I" (fs/parent (fs/parent declared)) "/include")
                 (concat flags
                         [source (str prefix "/blake3/lib/libblake3.a") declared]
                         (when linux? ["-ldl" (str "-Wl,-rpath," (fs/parent declared))])
                         ["-o" lib]))]
    (if (zero? (:exit r)) lib (do (println "RECORD compile" nm (:out r) (:err r)) nil))))


(defn- wrapper-root
  "A root sharing this checkout's sources, toolchain record, jolt and every
   other native library, with `wrapper` installed as the jolt-crypto library."
  [nm wrapper]
  (let [dir (fs/path j6c-scratch nm)
        native (fs/path dir "build/native" plat)]
    (fs/create-dirs native)
    (doseq [f ["deps.edn" "src" "jolt-toolchain.edn"]]
      (fs/create-sym-link (fs/path dir f) (fs/path root f)))
    (fs/create-sym-link (fs/path dir "build/tools") (fs/real-path (fs/path root "build/tools")))
    (doseq [entry (fs/list-dir (fs/path root "build/native" plat))
            :when (not (#{"jolt-crypto" "jolt-crypto-build"} (str (fs/file-name entry))))]
      (fs/create-sym-link (fs/path native (fs/file-name entry)) (fs/real-path entry)))
    (fs/create-dirs (fs/path native "jolt-crypto/lib"))
    (fs/copy wrapper (fs/path native "jolt-crypto/lib" (str "libjolt_crypto" ext)))
    (str dir)))


(defn- refused-before-guest-work
  "The child exits nonzero with `message` in its output, never loaded the
   probe namespace's guest code and never digested or drew."
  [label {:keys [exit out]} message]
  (check label
         (and (not (zero? exit)) (str/includes? out message)
              (not (line out "LOADED")) (not (line out "GUEST-WORK")))
         {:exit exit :out (first (filter #(str/includes? % "jolt") (str/split-lines out)))}))


;; J6c-1. the build record: exactly the ten-entry allowlist, one more than
;; layout 3 (jolt_crypto_sha1), and the record is this build
(let [record (edn/read-string (slurp (str (fs/path root "build/native" plat "build-record.edn"))))
      {:keys [allowlist exported outputs]} (:jolt-crypto record)
      lib (first (keys outputs))
      layout-3 #{"jolt_crypto_layout_version" "jolt_crypto_cleanse" "jolt_crypto_random"
                 "jolt_crypto_blake3" "jolt_crypto_sha256" "jolt_crypto_ed25519_public"
                 "jolt_crypto_ed25519_sign" "jolt_crypto_ed25519_verify" "jolt_crypto_provenance"}
      digest (let [md (java.security.MessageDigest/getInstance "SHA-256")]
               (.update md (fs/read-all-bytes lib))
               (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md))))]
  (println "RECORD jolt-crypto exports" (pr-str (get exported lib)))
  (check "build record: jolt-crypto exports exactly its allowlist, the layout-3 nine plus jolt_crypto_sha1, and is the recorded build"
         (and (= allowlist (get exported lib))
              (= (conj layout-3 "jolt_crypto_sha1") (set allowlist))
              (= 10 (count allowlist))
              (= digest (get outputs lib)))
         {:allowlist allowlist :exported (get exported lib) :digest-matches (= digest (get outputs lib))}))


;; J6c-2. EVP_sha1 is audited: the provenance list is exactly the set of
;; libcrypto functions the wrapper calls (twenty), and an EVP_sha1 that
;; resolves outside libcrypto splits the audit (the control, compiled the
;; same way without the substitution, reports one origin)
(let [src (str/replace (slurp (str (fs/path root "native/jolt-crypto/jolt_crypto.c")))
                       #"(?s)/\*.*?\*/" "")
      entries (second (re-find #"(?s)void \*entries\[\] = \{(.*?)\};" src))
      audited (map second (re-seq #"\(void \*\)&(\w+)" (or entries "")))
      ;; every libcrypto function the wrapper calls: a name in the OpenSSL
      ;; public prefixes followed by an argument list (types such as
      ;; EVP_MD_CTX and macros such as EVP_PKEY_ED25519 are never called)
      called (set (map second (re-seq #"\b((?:EVP|OPENSSL|RAND|OpenSSL)_\w+)\s*\(" src)))]
  (println "RECORD provenance entries" (count audited) (pr-str audited))
  (check "provenance audits exactly the twenty libcrypto functions the wrapper calls, EVP_sha1 among them"
         (and (= 20 (count audited) (count (distinct audited)))
              (= (set audited) called)
              (contains? called "EVP_sha1"))
         {:count (count audited)
          :called-not-audited (sort (remove (set audited) called))
          :audited-not-called (sort (remove called audited))}))


(doseq [[label flags]
        [["provenance control: the probe route reports one origin for all twenty entries" []]
         ["EVP_sha1 outside libcrypto: provenance answers JC_SPLIT, writes nothing" ["-DSPLIT_SHA1"]]]]
  (let [prefix (str (fs/path root "build/native" plat))
        exe (str (fs/path j6c-scratch (if (seq flags) "sha1-split" "sha1-control")))
        cc (apply p/shell {:out :string :err :string :continue true}
                  "cc" "-Wall" "-Wextra" "-Werror"
                  (str "-I" prefix "/blake3/include")
                  (str "-I" (fs/parent (fs/parent declared)) "/include")
                  (concat flags
                          [(str probes "/sha1_split.c")
                           (str prefix "/blake3/lib/libblake3.a") declared]
                          (when linux? ["-ldl"])
                          ["-o" exe]))
        result (when (zero? (:exit cc))
                 (p/shell {:out :string :err :string :continue true} exe))]
    (check label (and result (zero? (:exit result)))
           {:compile-exit (:exit cc)
            :out (str/trim (str (:out cc) (:err cc) (:out result) (:err result)))})))


;; J6c-3. SHA-1 known answers and masking keys through the qualified wrapper
(let [{:keys [exit out]} (jolt root {} (str probes "/ws_crypto.clj"))]
  (run! println (map #(str "  " %) (filter #(re-find #"^(PASS|FAIL)" %) (str/split-lines out))))
  (check "dao.stream.ws.crypto: RFC 3174 and handshake SHA-1 KATs, four-byte masking keys"
         (and (zero? exit) (line out "SUMMARY ok"))
         {:exit exit :out (when-not (zero? exit) out)}))


;; J6c-4. a layout-3 (J2-built) wrapper: dao.host.hash refuses at load, so
;; neither dao.stream.ws.crypto nor the hash/sign probe does guest work
(let [source (str (fs/path j6c-scratch "jolt_crypto_layout3.c"))
      shown (p/shell {:dir root :out :string :err :string :continue true}
                     "git" "show" (str j2-wrapper-commit ":native/jolt-crypto/jolt_crypto.c"))
      _ (spit source (:out shown))
      lib (when (and (zero? (:exit shown)) (str/includes? (:out shown) "#define JOLT_CRYPTO_LAYOUT 3"))
            (compile-wrapper "layout3" source))]
  (check "a layout-3 wrapper builds from the J2 source" (some? lib)
         {:git-exit (:exit shown) :err (:err shown)})
  (when lib
    (let [dir (wrapper-root "layout3" lib)]
      (refused-before-guest-work
        "layout-3 wrapper: dao.stream.ws.crypto refused at load, nonzero, before guest work"
        (jolt dir {} (str probes "/ws_crypto.clj")) "the library writes another layout")
      (refused-before-guest-work
        "layout-3 wrapper: dao.host.hash refused at load, nonzero, before any digest"
        (jolt dir {} (str probes "/provenance.clj")) "the library writes another layout"))))


;; J6c-5. a layout-4 wrapper without jolt_crypto_sha1 (the entry renamed at
;; compile time): the gate passes, and dao.stream.ws.crypto's entry check
;; refuses the load before guest work (defcfn alone binds lazily)
(let [lib (compile-wrapper "missing-sha1" (str (fs/path root "native/jolt-crypto/jolt_crypto.c"))
                           "-Djolt_crypto_sha1=jolt_crypto_sha1_absent")]
  (check "a layout-4 wrapper without jolt_crypto_sha1 builds" (some? lib) {})
  (when lib
    (refused-before-guest-work
      "missing jolt_crypto_sha1: dao.stream.ws.crypto refused at load, nonzero, before guest work"
      (jolt (wrapper-root "missing-sha1" lib) {} (str probes "/ws_crypto.clj"))
      "jolt-crypto refused: the library lacks jolt_crypto_sha1")))


;; J6c-6. wrong library first: the copy is resident before
;; dao.stream.ws.crypto loads; the qualified library is kept (and the KATs
;; pass) or the load refuses, never answers from the copy
(let [{:keys [exit out]} (jolt root {} (str probes "/ws_crypto.clj") "wrong-first" copy)
      outcome (cond (and (zero? exit) (line out "SUMMARY ok")) :kept-declared
                    (and (not (zero? exit)) (str/includes? out "EVP resolved to an undeclared libcrypto")
                         (not (line out "GUEST-WORK")))
                    :refused
                    :else :other)]
  (check "wrong library first: dao.stream.ws.crypto keeps the qualified library or refuses"
         (not= :other outcome)
         {:outcome outcome :exit exit :out (when (= :other outcome) out)}))


;; J6c-7. no masking-key draw before the gate: an unqualified library
;; (digest differs) refuses dao.stream.ws.crypto before guest work; on Linux a
;; RAND-only preload is refused before RAND_bytes is ever called
(refused-before-guest-work
  "unqualified libcrypto: dao.stream.ws.crypto refused at load, nonzero, before any draw"
  (jolt (fake-root "j6c-replaced" (with-qualified #(assoc % :sha256 (apply str (repeat 64 "0")))) true)
        {} (str probes "/ws_crypto.clj"))
  "libcrypto file digest differs from the qualified one")


(if linux?
  (let [so (str (fs/path j6c-scratch "rand-only.so"))
        marker (str (fs/path j6c-scratch "rand-called"))
        cc (p/shell {:out :string :err :string :continue true}
                    "cc" "-shared" "-fPIC" "-Wall" "-Wextra" "-Werror"
                    "-o" so (str probes "/rand_only.c"))]
    (check "RAND-only interposer builds (J6c)" (zero? (:exit cc)) {:exit (:exit cc)})
    (when (zero? (:exit cc))
      (fs/delete-if-exists marker)
      (let [r (jolt root {"LD_PRELOAD" so "JOLT_RAND_MARKER" marker} (str probes "/ws_crypto.clj"))]
        (refused-before-guest-work
          "RAND-only preload: dao.stream.ws.crypto refused before any masking-key draw"
          r "libcrypto provenance unavailable")
        (check "RAND-only preload: RAND_bytes never called" (not (fs/exists? marker)) {}))))
  (println "RECORD J6c RAND-only preload gate: not applicable on macOS; LD_PRELOAD is a Linux loader mechanism. Linux execution UNRUN."))


(let [{:keys [exit out]} (jolt root {} (str probes "/random_faults.clj"))]
  (run! println (filter #(re-find #"^(PASS|FAIL)" %) (str/split-lines out)))
  (check "random fault injection: freed once, zero before free, no cleanup allocation"
         (and (zero? exit) (line out "SUMMARY ok")) {:exit exit :out (when-not (zero? exit) out)}))


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

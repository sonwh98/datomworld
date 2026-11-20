;; bb qualify:jolt-libcrypto — print this platform's libcrypto identity for
;; jolt-toolchain.edn :native :libcrypto :qualified (procedure in the comment
;; above that entry). Changes nothing.
;;
;; The wrapper reports the library its EVP entries resolved to (path,
;; OpenSSL_version_num, version text, file SHA-256) through
;; probes/identity.clj in a child jolt process; this script independently
;; computes the canonical path and SHA-256 of the declared :path and refuses
;; to print an entry unless they agree and the version is at least
;; :min-version. Exits nonzero on any disagreement.
;;
;; Invoked both as `bb -cp src/dev native/jolt-crypto/qualify.clj` (bb.edn)
;; and bare (`bb native/jolt-crypto/qualify.clj`, e.g.
;; native/jolt-crypto/probes/run.clj:228), so it puts src/dev on bb's
;; classpath itself before the seam namespace loads — one seam, no divergent copy.
(when-not (resolve 'jolt-launch/run-child)
  ((requiring-resolve 'babashka.classpath/add-classpath) "src/dev"))


(ns qualify
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            ;; the target-aware launch seam (src/dev, on bb's root classpath)
            [jolt-launch :as jlt])
  (:import (java.security MessageDigest)))


(defn- fail!
  [& msg]
  (binding [*out* *err*] (println "qualify:jolt-libcrypto FAILED:" (apply str msg)))
  (System/exit 1))


(def plat
  (let [os (str/lower-case (System/getProperty "os.name"))
        arch (System/getProperty "os.arch")]
    (str (if (str/includes? os "mac") "darwin" "linux") "-"
         (if (#{"aarch64" "arm64"} arch) "arm64" "x86_64"))))


(defn- file-sha256
  [path]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (.update md (fs/read-all-bytes path))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md)))))


(defn- version-number
  [text]
  (let [[major minor patch] (map #(Long/parseLong %) (str/split text #"\."))]
    (+ (bit-shift-left major 28) (bit-shift-left minor 20) (bit-shift-left patch 4))))


(let [[target args] (jlt/parse-target-arg *command-line-args*)
      dry-run (boolean (some #{"--dry-run"} args))
      _ (when (seq (remove #{"--dry-run"} args))
          (fail! "unknown arguments:" (pr-str args) " (usage: qualify.clj [--target chez|gambit] [--dry-run])"))
      _ (when (and (= :gambit target) (not dry-run))
          (fail! "target gambit: the libcrypto wrapper qualification is a Chez-tier"
                 " protocol (the fork's Gambit FFI tier is gated by its own make"
                 " targets — gambitffi, gambitcheck, ... — and P2/P3 consumers"
                 " remain Chez-only, host/gambit/ffi/README.md)."
                 " The seam exists; run qualify.clj --target gambit --dry-run"
                 " to print the Gambit launch path without claiming anything."))
      _ (when (and (= :gambit target) dry-run)
          ;; Dry-run: print the seam, exercise a trivial Gambit child with its
          ;; identity line, and exit WITHOUT a qualification record.
          (let [r (jlt/run-child {:target :gambit} "-e"
                                 "(begin (display \"jolt-target: gambit\")(newline)(display (+ 1 2))(newline))")]
            (println (str (:out r)))
            (jlt/assert-child-target! {:target :gambit
                                       :output (str (:out r) (:err r))
                                       :context "qualify.clj --target gambit --dry-run"})
            (println "DRY-RUN gambit launch seam via $JOLT_GAMBIT (gsi, as the fork's host/gambit gates invoke it); no qualification claimed for the libcrypto wrapper on gambit")
            (System/exit 0)))
      decl (get-in (edn/read-string (slurp "jolt-toolchain.edn")) [:native :libcrypto])
      declared (get-in decl [:path (keyword plat)])
      _ (when-not (and declared (fs/exists? declared))
          (fail! "declared libcrypto " declared " is absent"))
      real (str (fs/real-path declared))
      digest (file-sha256 real)
      ;; Fail-closed identity gate first: a trivial child through the same
      ;; launch seam must report the requested target before the provenance
      ;; child's PROVENANCE line is trusted at all.
      ident (jlt/run-child {:target target} "-e"
                           "(do (println (str \"jolt-target: \" (or (System/getenv \"JOLT_TARGET\") \"chez\"))))")
      _ (jlt/assert-child-target! {:target target
                                   :output (str (:out ident) (:err ident))
                                   :context "qualify.clj identity gate"})
      r (p/shell {:out :string :err :string :continue true}
                 "sh" "-c"
                 (if (= :chez target)
                   ". build/tools/jolt-env.sh && export JOLT_TARGET=chez && exec \"$JOLT_HOME/bin/jolt\" -Srepro -M:jolt native/jolt-crypto/probes/identity.clj"
                   (str "$JOLT_HOME/bin/jolt is a " (name target) "-host child; unreachable")))
      out (str (:out r) (:err r))
      prov (some #(when (str/starts-with? % "PROVENANCE ") %) (str/split-lines out))
      [_ num version path wrapper-digest]
      (when prov (re-matches #"PROVENANCE (\d+)\t([^\t]*)\t([^\t]*)\t([0-9a-f]{64})" prov))]
  (when-not (and (zero? (:exit r)) num)
    (fail! "the wrapper reported no provenance (build it first: bb build:jolt-native"
           " [--requalify])\n" out))
  (when-not (= real (str (fs/real-path path)))
    (fail! "EVP resolved to " path ", not the declared " declared " (" real ")"))
  (when-not (= digest wrapper-digest)
    (fail! "SHA-256 disagrees: bb " digest ", library " wrapper-digest))
  (when (< (Long/parseLong num) (version-number (:min-version decl)))
    (fail! version " is older than :min-version " (:min-version decl)))
  (let [entry {:target (name target)
               :canonical-path real
               :version-num (Long/parseLong num)
               :version version
               :sha256 digest}]
    (println "jolt-target:" (name target))
    (println "observed libcrypto for" plat "under jolt-target" (name target)
             "(paste as :native :libcrypto :qualified" (keyword plat) "):")
    (pprint/pprint {(keyword plat) entry})
    ;; Equality against the tracked entry ignores the new :target field, so
    ;; the chez default answers exactly as before.
    (println (if (= (dissoc entry :target) (get-in decl [:qualified (keyword plat)]))
               "equal to the current :qualified entry"
               "DIFFERS from the current :qualified entry"))))

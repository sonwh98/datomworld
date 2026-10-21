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
(ns qualify
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str])
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


(let [decl (get-in (edn/read-string (slurp "jolt-toolchain.edn")) [:native :libcrypto])
      declared (get-in decl [:path (keyword plat)])
      _ (when-not (and declared (fs/exists? declared))
          (fail! "declared libcrypto " declared " is absent"))
      real (str (fs/real-path declared))
      digest (file-sha256 real)
      r (p/shell {:out :string :err :string :continue true}
                 "sh" "-c"
                 ". build/tools/jolt-env.sh && exec \"$JOLT_HOME/bin/jolt\" -Srepro -M:jolt native/jolt-crypto/probes/identity.clj")
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
  (let [entry {:canonical-path real
               :version-num (Long/parseLong num)
               :version version
               :sha256 digest}]
    (println "observed libcrypto for" plat "(paste as :native :libcrypto :qualified" (keyword plat) "):")
    (pprint/pprint {(keyword plat) entry})
    (println (if (= entry (get-in decl [:qualified (keyword plat)]))
               "equal to the current :qualified entry"
               "DIFFERS from the current :qualified entry"))))

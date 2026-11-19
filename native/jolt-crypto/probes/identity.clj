;; Run under jolt -Srepro -M:jolt by native/jolt-crypto/qualify.clj. Loads the
;; wrapper DIRECTLY, without dao.host.hash and so without its qualification
;; gate, and prints the wrapper's provenance report for the library its EVP
;; entries resolved to. Requalification only; it computes no digest of data.
(ns identity
  (:require [clojure.string :as str]
            [jolt.ffi :as ffi]))


;; Lane B: the child reports which host scheme launched it (silent when
;; JOLT_TARGET is unset, so legacy invocations keep their output
;; byte-identical, and dual-host scripts do not misreport on the JVM).
(when-let [t (System/getenv "JOLT_TARGET")] (println (str "jolt-target: " t)))


(def library
  (let [os (str/lower-case (or (System/getProperty "os.name") ""))
        arch (System/getProperty "os.arch")
        darwin? (str/includes? os "mac")]
    (str (System/getProperty "user.dir") "/build/native/"
         (if darwin? "darwin" "linux") "-"
         (if (#{"aarch64" "arm64"} arch) "arm64" "x86_64")
         "/jolt-crypto/lib/libjolt_crypto" (if darwin? ".dylib" ".so"))))


(ffi/load-library library)
(ffi/defcfn c-layout-version "jolt_crypto_layout_version" [] :int)
(ffi/defcfn c-provenance "jolt_crypto_provenance" [:pointer :size_t] :int)

(println "LAYOUT" (c-layout-version))


(let [buf (ffi/alloc 4096)]
  (try
    (let [n (c-provenance buf 4096)]
      (println "PROVENANCE" (if (neg? n) (str "status " n) (ffi/read-bytes buf n))))
    (finally (ffi/free buf))))

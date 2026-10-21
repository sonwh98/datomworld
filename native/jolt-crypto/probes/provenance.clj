;; Run under jolt -Srepro -M:jolt by probes/run.clj. Loads dao.host.hash and
;; dao.host.sign (the provenance gate runs at load), then prints the
;; wrapper's own provenance report and one digest per entry.
;;
;; With the argument `wrong-first <path>` it first loads another libcrypto
;; image at <path>, so the gate meets a process where a second copy of the
;; library is already resident.
(ns provenance
  (:require [clojure.string :as str]
            [jolt.ffi :as ffi]))


(let [[mode path] *command-line-args*]
  (when (= "wrong-first" mode)
    (ffi/load-library path)
    (println "RECORD loaded first" path)))


(require '[dao.host.hash :as hash] '[dao.host.sign :as sign] '[dao.host.bytes :as bytes])


(def library
  (let [os (str/lower-case (or (System/getProperty "os.name") ""))
        arch (System/getProperty "os.arch")
        darwin? (str/includes? os "mac")]
    (str (System/getProperty "user.dir") "/build/native/"
         (if darwin? "darwin" "linux") "-"
         (if (#{"aarch64" "arm64"} arch) "arm64" "x86_64")
         "/jolt-crypto/lib/libjolt_crypto" (if darwin? ".dylib" ".so"))))


(ffi/load-library library)
(ffi/defcfn c-provenance "jolt_crypto_provenance" [:pointer :size_t] :int)


(let [buf (ffi/alloc 4096)
      n (c-provenance buf 4096)]
  (println "PROVENANCE" (if (neg? n) (str "status " n) (ffi/read-bytes buf n)))
  (ffi/free buf))


(defn- hex
  [bs]
  (apply str (map #(str (nth "0123456789abcdef" (quot % 16)) (nth "0123456789abcdef" (rem % 16)))
                  (bytes/to-vector bs))))


(println "RECORD blake3(abc)" (hex (hash/blake3-bytes (bytes/from-vector [97 98 99]))))
(println "RECORD sha256(abc)" (hex (hash/sha256-bytes (bytes/from-vector [97 98 99]))))


(println "RECORD ed25519 public(seed 0x01*32)"
         (hex (:public (sign/keypair-from-seed (bytes/from-vector (vec (repeat 32 1)))))))


(println "LOADED")

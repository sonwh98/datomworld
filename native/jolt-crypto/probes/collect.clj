;; Run under jolt -Srepro -M:jolt by probes/run.clj: the §6.3 collect-safety
;; gate (the J0a leftover in src/dev/jolt_smoke.clj). One thread runs a
;; 256 MiB :blocking jolt_crypto_blake3 call; once that call is under way
;; this thread runs a full collection. The gate holds when the collection
;; both starts and completes while the call is still running. The same input
;; then goes through the public dao.host.hash/blake3-bytes, whose digest
;; must equal the direct call's.
(ns collect
  (:require [clojure.string :as str]
            [jolt.ffi :as ffi]))


(require '[dao.host.hash :as hash])


(def library
  (let [os (str/lower-case (or (System/getProperty "os.name") ""))
        arch (System/getProperty "os.arch")
        darwin? (str/includes? os "mac")]
    (str (System/getProperty "user.dir") "/build/native/"
         (if darwin? "darwin" "linux") "-"
         (if (#{"aarch64" "arm64"} arch) "arm64" "x86_64")
         "/jolt-crypto/lib/libjolt_crypto" (if darwin? ".dylib" ".so"))))


(ffi/load-library library)
(ffi/defcfn c-blake3 "jolt_crypto_blake3" [:pointer :size_t :pointer] :int :blocking)


(defn- ms
  [t0]
  (long (/ (- (System/nanoTime) t0) 1e6)))


(def n (* 256 1024 1024))
(def input (byte-array n))
(dotimes [i 4096] (aset input (* i 65536) (unchecked-byte i)))

(def in (ffi/alloc n))
(def out (ffi/alloc 32))
(ffi/write-array in input)

(def t0 (System/nanoTime))
(def log (atom {}))


(def th
  (Thread. (fn []
             (swap! log assoc :call-start (ms t0))
             (swap! log assoc :status (c-blake3 in n out))
             (swap! log assoc :call-end (ms t0)))))


(.start th)
(loop [] (when-not (:call-start @log) (Thread/sleep 1) (recur)))
(Thread/sleep 20)
(swap! log assoc :collect-start (ms t0))
(jolt.host/gc-full!)
(swap! log assoc :collect-end (ms t0))
(.join th)

(def direct (vec (ffi/read-array out 32)))
(ffi/free in)
(ffi/free out)
(def via-entry (vec (hash/blake3-bytes input)))


(let [{:keys [call-start call-end collect-start collect-end status]} @log
      ok? (and (= 0 status)
               (< call-start collect-start)
               (< collect-end call-end)
               (= direct via-entry))]
  (println "RECORD collect timeline, :blocking jolt_crypto_blake3 256 MiB" (pr-str @log))
  (println "RECORD digest equal through dao.host.hash/blake3-bytes" (= direct via-entry))
  (println (if ok? "PASS" "FAIL")
           "a full collection starts and completes while the 256 MiB :blocking hash runs")
  (System/exit (if ok? 0 1)))

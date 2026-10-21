;; J0c round 4, step D: the jolt parser wrapper and the ANTLR4 C++ runtime
;; under AddressSanitizer + UBSan, and under macOS `leaks`, driven by the
;; standalone C++ harness native/jolt-parser/sanitize/harness.cpp (never
;; loaded into jolt).
;;
;;   bb src/dev/jolt_j0c_sanitize.clj [cycles]
;;
;; Needs the pinned runtime archive (bb build:jolt-native fetches it) and the
;; generated Cpp target. Builds, under build/j0c/r4/sanitize/:
;;
;;   runtime-asan/   the pinned runtime, its toolchain flags plus
;;                   -fsanitize=address,undefined
;;   harness-asan/   harness + wrapper + generated parser, sanitized; run with
;;                   ASAN_OPTIONS=detect_leaks=1 where the platform has LSan
;;   harness-plain/  the same unsanitized against build/native's runtime; on
;;                   macOS run under `leaks --atExit` (Apple's LSan is absent)
;;
;; Exits nonzero when any build or run fails or reports.
(ns jolt-j0c-sanitize
  (:refer-clojure :exclude [run!])
  (:require
    [babashka.fs :as fs]
    [babashka.process :as p]
    [clojure.edn :as edn]
    [clojure.string :as str])
  (:import
    (java.security
      MessageDigest)))


(defn- fail!
  [& msg]
  (println "sanitize FAILED:" (apply str msg))
  (System/exit 1))


(defn- run!
  [opts & cmd]
  (let [r (apply p/shell (merge {:continue true} opts) cmd)]
    (when-not (zero? (:exit r)) (fail! (str/join " " cmd) " exited " (:exit r)))
    r))


(defn- sha256
  [path]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (.update md (fs/read-all-bytes path))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md)))))


(defn- build-runtime!
  [cmake-bin src root san cmake-flags]
  (apply run! {} cmake-bin "-S" src "-B" (str (fs/path root "runtime-asan-build"))
         (str "-DCMAKE_INSTALL_PREFIX=" (fs/path root "runtime-asan"))
         (str "-DCMAKE_CXX_FLAGS=" san)
         (str "-DCMAKE_EXE_LINKER_FLAGS=" san)
         (str "-DCMAKE_SHARED_LINKER_FLAGS=" san)
         cmake-flags)
  (run! {} cmake-bin "--build" (str (fs/path root "runtime-asan-build")) "--parallel")
  (run! {} cmake-bin "--install" (str (fs/path root "runtime-asan-build"))))


(def mac? (str/includes? (str/lower-case (System/getProperty "os.name")) "mac"))


(def plat
  (str (if mac? "darwin" "linux") "-"
       (if (#{"aarch64" "arm64"} (System/getProperty "os.arch")) "arm64" "x86_64")))


(let [cycles (or (first *command-line-args*) "200")
      {:keys [native cmake]} (edn/read-string (slurp "jolt-toolchain.edn"))
      {:keys [url cmake-flags], digest :sha256} (:antlr4-cpp-runtime native)
      archive (str "build/native/src/" (last (str/split url #"/")))
      cmake-bin (str/trim (:out (run! {:out :string} "mise" "exec" (str "cmake@" (:version cmake))
                                      "--" "which" "cmake")))
      root (fs/absolutize "build/j0c/r4/sanitize")
      src (str (fs/path root "src"))
      gen (str (fs/absolutize "build/antlr/python3/cpp"))
      san "-fsanitize=address,undefined -fno-sanitize-recover=all -fno-omit-frame-pointer -g -O1"]
  (when-not (fs/exists? archive) (fail! "no runtime archive " archive "; run bb build:jolt-native"))
  (when-not (= digest (sha256 archive)) (fail! archive " does not have the pinned SHA-256"))
  ;; the sanitized runtime is reused when already built from this archive
  (when-not (fs/exists? (fs/path root "runtime-asan" "lib" "libantlr4-runtime.a"))
    (fs/delete-tree root)
    (fs/create-dirs src)
    (run! {} "unzip" "-q" archive "-d" src)
    (build-runtime! cmake-bin src root san cmake-flags))
  (doseq [d ["harness-asan" "harness-plain"]] (fs/delete-tree (fs/path root d)))
  (doseq [[dir prefix sanitize] [["harness-asan" (fs/path root "runtime-asan") "ON"]
                                 ["harness-plain" (fs/absolutize (fs/path "build/native" plat "antlr4-runtime")) "OFF"]]]
    (run! {} cmake-bin "-S" "native/jolt-parser/sanitize" "-B" (str (fs/path root dir))
          "-DCMAKE_BUILD_TYPE=RelWithDebInfo"
          (str "-DANTLR4_PREFIX=" prefix) (str "-DGENERATED_DIR=" gen)
          (str "-DSANITIZE=" sanitize))
    (run! {} cmake-bin "--build" (str (fs/path root dir)) "--parallel"))
  (let [asan-bin (str (fs/path root "harness-asan" "jolt_parser_harness"))
        plain-bin (str (fs/path root "harness-plain" "jolt_parser_harness"))
        env {"ASAN_OPTIONS" (str "halt_on_error=1:abort_on_error=0:detect_stack_use_after_return=1"
                                 (when-not mac? ":detect_leaks=1"))
             "UBSAN_OPTIONS" "print_stacktrace=1:halt_on_error=1"}
        asan (p/shell {:continue true :out :string :err :string :extra-env env} asan-bin cycles)
        leaks (when mac?
                (p/shell {:continue true :out :string :err :string
                          :extra-env {"MallocStackLogging" "1"}}
                         "leaks" "--atExit" "--" plain-bin cycles))]
    (println "ASAN+UBSAN exit" (:exit asan))
    (println (:out asan))
    (when (seq (:err asan)) (println "ASAN+UBSAN stderr:\n" (:err asan)))
    (when leaks
      (println "LEAKS exit" (:exit leaks))
      (println (str/join "\n" (filter #(re-find #"leaks for|Process \d+:|total leaked|HARNESS|cycles|LEAK:" %)
                                      (str/split-lines (:out leaks))))))
    (System/exit (if (and (zero? (:exit asan)) (or (nil? leaks) (zero? (:exit leaks)))) 0 1))))

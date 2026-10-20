(ns jolt-native
  "bb build:jolt-native — the jolt lane's vendored native builds
   (docs/design/yin.repl-jolt-implementation.md §6.1, §7.2).

   For each source declared under :native in jolt-toolchain.edn:

   * fetch it into build/native/src only when absent, and accept it only at
     the pinned SHA-256 (a mismatch deletes the download and fails);
   * extract it fresh and build it with the mise-pinned CMake into
     build/native/<platform>/;
   * write build/native/<platform>/build-record.edn: tool versions, flags and
     the SHA-256 of every output library.

   Nothing here runs at namespace load; jolt only ever loads what this task
   built. Exit status is nonzero on any failure."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str])
  (:import (java.security MessageDigest)))


(defn- fail!
  [& msg]
  (binding [*out* *err*]
    (println "build:jolt-native FAILED:" (apply str msg)))
  (System/exit 1))


(defn- sh
  "Run a command, answer trimmed stdout; fail the task on a nonzero exit.
   An opts map first is passed to babashka.process."
  [& args]
  (let [opts (if (map? (first args)) (first args) {})
        cmd (if (map? (first args)) (rest args) args)
        r (apply p/shell (merge {:out :string :err :string :continue true} opts) cmd)]
    (when-not (zero? (:exit r))
      (fail! (str/join " " cmd) " exited " (:exit r) "\n" (:out r) "\n" (:err r)))
    (str/trim (or (:out r) ""))))


(defn- run!
  "Run a command with inherited output (long builds); fail on nonzero exit."
  [& cmd]
  (let [r (apply p/shell {:continue true} cmd)]
    (when-not (zero? (:exit r))
      (fail! (str/join " " cmd) " exited " (:exit r)))))


(defn- file-sha256
  [path]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (.update md (fs/read-all-bytes path))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md)))))


(defn platform
  "build/native/<platform> name for this host."
  []
  (let [os (str/lower-case (System/getProperty "os.name"))
        arch (System/getProperty "os.arch")
        os (cond (str/includes? os "mac") "darwin"
                 (str/includes? os "linux") "linux"
                 :else (fail! "unsupported OS " os))
        arch (case arch
               ("aarch64" "arm64") "arm64"
               ("x86_64" "amd64") "x86_64"
               (fail! "unsupported arch " arch))]
    (str os "-" arch)))


(defn- mise-bin
  [tool version exe]
  (let [r (p/shell {:out :string :err :string :continue true}
                   "mise" "exec" (str tool "@" version) "--" "which" exe)]
    (when-not (zero? (:exit r))
      (fail! "mise has no " tool "@" version " installed; run `mise install " tool "`"))
    (str/trim (:out r))))


(defn- fetch!
  "The pinned source archive under build/native/src, downloaded only when
   absent; its SHA-256 must equal the declaration."
  [{:keys [url sha256] :as decl}]
  (let [dest (fs/path "build/native/src" (last (str/split url #"/")))]
    (fs/create-dirs (fs/parent dest))
    (when-not (fs/exists? dest)
      (println "fetching" url)
      (let [resp (http/get url {:as :bytes :throw false})]
        (when-not (= 200 (:status resp))
          (fail! "fetch " url " answered " (:status resp)))
        (fs/write-bytes dest (:body resp))))
    (let [actual (file-sha256 dest)]
      (when-not (= sha256 actual)
        (fs/delete dest)
        (fail! dest " has SHA-256 " actual ", declared " sha256 "; deleted"))
      (println "verified" (str dest) "sha256" actual))
    (assoc decl :archive (str dest))))


(defn- build-antlr4-runtime!
  [{:keys [version archive cmake-flags]} cmake plat]
  (let [src (str (fs/path "build/native/src" (str "antlr4-cpp-runtime-" version)))
        out (fs/path "build/native" plat)
        bld (str (fs/path out "antlr4-runtime-build"))
        prefix (str (fs/absolutize (fs/path out "antlr4-runtime")))]
    (fs/delete-tree src)
    (fs/create-dirs src)
    (sh "unzip" "-q" archive "-d" src)
    (fs/delete-tree bld)
    (apply run! cmake "-S" src "-B" bld
           (str "-DCMAKE_INSTALL_PREFIX=" prefix)
           cmake-flags)
    (run! cmake "--build" bld "--parallel" "--config" "Release")
    (run! cmake "--install" bld "--config" "Release")
    (let [libs (sort (map str (fs/glob prefix "lib/*antlr4*")))]
      (when (empty? libs) (fail! "no antlr4 library installed under " prefix))
      {:prefix prefix
       :include (str (fs/path prefix "include" "antlr4-runtime"))
       :outputs (into (sorted-map) (map (fn [l] [l (file-sha256 l)])) libs)})))


(defn- build-parser!
  "native/jolt-parser over the generated Cpp target and the pinned runtime.
   The generated sources come from `clj -M:antlr-gen --target cpp` (the JVM
   build tool); their absence fails the task rather than skipping it."
  [{:keys [prefix]} cmake plat]
  (let [gen "build/antlr/python3/cpp"
        out (fs/path "build/native" plat)
        bld (str (fs/path out "jolt-parser-build"))
        dest (str (fs/absolutize (fs/path out "jolt-parser")))]
    (when-not (seq (fs/glob gen "*.cpp"))
      (fail! "no generated parser in " gen "; run `clj -M:antlr-gen --target cpp` first"))
    (fs/delete-tree bld)
    (run! cmake "-S" "native/jolt-parser" "-B" bld
          "-DCMAKE_BUILD_TYPE=Release"
          (str "-DANTLR4_PREFIX=" prefix)
          (str "-DGENERATED_DIR=" (fs/absolutize gen))
          (str "-DCMAKE_INSTALL_PREFIX=" dest))
    (run! cmake "--build" bld "--parallel" "--config" "Release")
    (run! cmake "--install" bld "--config" "Release")
    (let [libs (sort (map str (fs/glob dest "lib/*jolt_parser*")))]
      (when (empty? libs) (fail! "no jolt_parser library installed under " dest))
      {:prefix dest
       :generated (str (fs/absolutize gen))
       :outputs (into (sorted-map) (map (fn [l] [l (file-sha256 l)])) libs)})))


(defn -main
  [& _]
  (let [{:keys [native cmake]} (edn/read-string (slurp "jolt-toolchain.edn"))
        cmake-bin (mise-bin "cmake" (:version cmake) "cmake")
        plat (platform)
        cxx (sh "c++" "--version")
        runtime (fetch! (:antlr4-cpp-runtime native))
        built (build-antlr4-runtime! runtime cmake-bin plat)
        parser (build-parser! built cmake-bin plat)
        record {:platform plat
                :cmake {:path cmake-bin :version (first (str/split-lines (sh cmake-bin "--version")))}
                :cxx (first (str/split-lines cxx))
                :antlr4-cpp-runtime (merge (select-keys runtime [:version :url :sha256 :cmake-flags])
                                           built)
                :jolt-parser parser}
        record-file (str (fs/path "build/native" plat "build-record.edn"))]
    (spit record-file (with-out-str (pprint/pprint record)))
    (pprint/pprint record)
    (println "wrote" record-file)))


(apply -main *command-line-args*)

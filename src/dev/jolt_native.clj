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

   :libcrypto is the one system entry (§6.3): never fetched; the task fails
   unless the declared path for this platform exists, on a Debian-family host
   one of the declared runtime packages is installed (this task, not
   bootstrap:jolt, checks them), and its canonical path and SHA-256 equal
   :libcrypto :qualified; it records both. native/jolt-crypto links it by the
   declared path and, since J6c (layout 4), must export exactly the allowlist
   in its exports.{darwin,linux}. `--requalify` skips only the :qualified comparison, so a
   library that changed can be built against and then qualified (`bb
   qualify:jolt-libcrypto`); the record says so.

   native/jolt-file (J4a) needs no :native declaration: it links system libc
   only, and the task fails unless it exports exactly its allowlist and links
   nothing else.

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
  (let [dest (fs/path "build/native/src" (or (:archive-name decl) (last (str/split url #"/"))))]
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


(defn- exported-symbols
  "The library's exported (defined, external) symbols, from nm."
  [lib]
  (let [flags (if (str/starts-with? (platform) "darwin") ["-gU"] ["-D" "--defined-only"])]
    (->> (str/split-lines (apply sh "nm" (concat flags [lib])))
         (keep #(last (str/split (str/trim %) #"\s+")))
         (remove str/blank?)
         sort
         vec)))


(defn- build-parser!
  "native/jolt-parser over the generated Cpp target and the pinned runtime,
   in one of two variants: :test (-DJOLT_PARSER_TEST_SEAMS=ON, installed at
   jolt-parser/, the library the probes and harnesses load) and :release (the
   CMake default, seams OFF, at jolt-parser-release/). The generated sources
   come from `clj -M:antlr-gen --target cpp` (the JVM build tool); their
   absence fails the task rather than skipping it."
  [{:keys [prefix]} cmake plat variant]
  (let [gen "build/antlr/python3/cpp"
        out (fs/path "build/native" plat)
        dir (if (= :test variant) "jolt-parser" "jolt-parser-release")
        bld (str (fs/path out (str dir "-build")))
        dest (str (fs/absolutize (fs/path out dir)))]
    (when-not (seq (fs/glob gen "*.cpp"))
      (fail! "no generated parser in " gen "; run `clj -M:antlr-gen --target cpp` first"))
    (fs/delete-tree bld)
    (run! cmake "-S" "native/jolt-parser" "-B" bld
          "-DCMAKE_BUILD_TYPE=Release"
          (str "-DANTLR4_PREFIX=" prefix)
          (str "-DGENERATED_DIR=" (fs/absolutize gen))
          (str "-DCMAKE_INSTALL_PREFIX=" dest)
          (str "-DJOLT_PARSER_TEST_SEAMS=" (if (= :test variant) "ON" "OFF")))
    (run! cmake "--build" bld "--parallel" "--config" "Release")
    (run! cmake "--install" bld "--config" "Release")
    (let [libs (sort (map str (fs/glob dest "lib/*jolt_parser*")))]
      (when (empty? libs) (fail! "no jolt_parser library installed under " dest))
      {:prefix dest
       :variant variant
       :test-seams (= :test variant)
       :generated (str (fs/absolutize gen))
       :outputs (into (sorted-map) (map (fn [l] [l (file-sha256 l)])) libs)
       :exported (into (sorted-map) (map (fn [l] [l (exported-symbols l)])) libs)})))


(defn- build-blake3!
  "The BLAKE3 C reference (the release's c/ directory), static and PIC."
  [{:keys [version archive cmake-flags]} cmake plat]
  (let [src (str (fs/path "build/native/src" (str "blake3-" version)))
        out (fs/path "build/native" plat)
        bld (str (fs/path out "blake3-build"))
        prefix (str (fs/absolutize (fs/path out "blake3")))]
    (fs/delete-tree src)
    (fs/create-dirs src)
    (sh "tar" "-xzf" archive "-C" src)
    (let [c-dir (str (fs/path src (str "BLAKE3-" version) "c"))]
      (when-not (fs/exists? (fs/path c-dir "CMakeLists.txt"))
        (fail! "no c/CMakeLists.txt in " archive))
      (fs/delete-tree bld)
      (fs/delete-tree prefix)
      (apply run! cmake "-S" c-dir "-B" bld
             (str "-DCMAKE_INSTALL_PREFIX=" prefix)
             cmake-flags))
    (run! cmake "--build" bld "--parallel" "--config" "Release")
    (run! cmake "--install" bld "--config" "Release")
    (let [lib (str (fs/path prefix "lib" "libblake3.a"))]
      (when-not (fs/exists? lib) (fail! "no libblake3.a installed under " prefix))
      {:prefix prefix
       :outputs (sorted-map lib (file-sha256 lib))})))


(defn- debian-family?
  []
  (let [f "/etc/os-release"]
    (and (fs/exists? f)
         (boolean (re-find #"(?m)^(ID|ID_LIKE)=.*\bdebian\b" (slurp f))))))


(defn- dpkg-installed?
  [pkg]
  (let [r (p/shell {:out :string :err :string :continue true}
                   "dpkg-query" "-W" "-f=${Status}" pkg)]
    (and (zero? (:exit r)) (str/includes? (:out r) "install ok installed"))))


(defn- check-libcrypto!
  "The declared system libcrypto for `plat`: present, on a Debian-family host
   owned by one installed runtime package, and (unless `requalify?`) the
   :qualified library by canonical path and file SHA-256; never fetched or
   searched. Its version needs the library loaded, so dao.host.hash checks
   that one at load."
  [{:keys [path include min-version debian-runtime-any-of] :as decl} plat requalify?]
  (let [k (keyword plat)
        lib (get path k)
        inc-dir (get include k)]
    (when-not lib (fail! ":native :libcrypto declares no path for " plat))
    (when-not (fs/exists? lib)
      (fail! "declared libcrypto " lib " is absent"
             (when (str/starts-with? plat "darwin") " (brew install openssl@3)")))
    (when-not (fs/exists? (fs/path inc-dir "openssl" "evp.h"))
      (fail! "no openssl/evp.h under " inc-dir
             (if (str/starts-with? plat "darwin")
               " (brew install openssl@3)"
               " (sudo apt-get install -y libssl-dev)")))
    (let [owners (when (debian-family?) (filterv dpkg-installed? debian-runtime-any-of))
          real (str (fs/real-path lib))
          digest (file-sha256 real)
          qualified (get-in decl [:qualified k])]
      (when (and (debian-family?) (empty? owners))
        (fail! "none of the libcrypto runtime packages " debian-runtime-any-of " is installed"))
      (when-not requalify?
        (when-not qualified
          (fail! ":native :libcrypto :qualified has no entry for " plat
                 "; requalify (see jolt-toolchain.edn)"))
        (when-not (= (:canonical-path qualified) real)
          (fail! "libcrypto canonical path " real " differs from the qualified "
                 (:canonical-path qualified) "; requalify (see jolt-toolchain.edn)"))
        (when-not (= (:sha256 qualified) digest)
          (fail! "libcrypto " real " has SHA-256 " digest ", the qualified library "
                 (:sha256 qualified) "; requalify (see jolt-toolchain.edn)")))
      {:path lib
       :real-path real
       :sha256 digest
       :qualified (if requalify? :skipped-for-requalification qualified)
       :include inc-dir
       :min-version min-version
       :soname (get (:soname decl) k)
       :debian-runtime-installed owners})))


(defn- linked-libraries
  "What the dynamic loader records for `lib`: otool -L on macOS, the
   DT_NEEDED and RUNPATH entries elsewhere."
  [lib]
  (if (str/starts-with? (platform) "darwin")
    (vec (rest (map str/trim (str/split-lines (sh "otool" "-L" lib)))))
    (->> (str/split-lines (sh "readelf" "-d" lib))
         (filter #(re-find #"\((NEEDED|RUNPATH|RPATH)\)" %))
         (mapv str/trim))))


(defn- allowlist
  "The exported-symbol allowlist of a native/<dir> wrapper for this
   platform, without the Mach-O leading underscore."
  [dir]
  (if (str/starts-with? (platform) "darwin")
    (->> (str/split-lines (slurp (str (fs/path dir "exports.darwin"))))
         (map str/trim) (remove str/blank?) (map #(str/replace % #"^_" ""))
         sort vec)
    (->> (re-seq #"(?m)^\s*(jolt_\w+);" (slurp (str (fs/path dir "exports.linux"))))
         (map second) sort vec)))


(defn- build-jolt-crypto!
  "native/jolt-crypto over the built BLAKE3 and the declared libcrypto."
  [blake3 libcrypto cmake plat]
  (let [out (fs/path "build/native" plat)
        bld (str (fs/path out "jolt-crypto-build"))
        dest (str (fs/absolutize (fs/path out "jolt-crypto")))]
    (fs/delete-tree bld)
    (fs/delete-tree dest)
    (run! cmake "-S" "native/jolt-crypto" "-B" bld
          "-DCMAKE_BUILD_TYPE=Release"
          (str "-DBLAKE3_PREFIX=" (:prefix blake3))
          (str "-DLIBCRYPTO_PATH=" (:path libcrypto))
          (str "-DOPENSSL_INCLUDE=" (:include libcrypto))
          (str "-DCMAKE_INSTALL_PREFIX=" dest))
    (run! cmake "--build" bld "--parallel" "--config" "Release")
    (run! cmake "--install" bld "--config" "Release")
    (let [libs (sort (map str (fs/glob dest "lib/*jolt_crypto*")))
          allowed (allowlist "native/jolt-crypto")]
      (when (empty? libs) (fail! "no jolt_crypto library installed under " dest))
      (let [exported (into (sorted-map)
                           (map (fn [l]
                                  [l (vec (sort (map #(str/replace % #"^_" "")
                                                     (exported-symbols l))))]))
                           libs)]
        ;; J6c: exactly the named allowlist (layout 4 added jolt_crypto_sha1)
        (doseq [[l syms] exported]
          (when-not (= allowed syms)
            (fail! l " exports " syms ", the allowlist is " allowed)))
        {:prefix dest
         :allowlist allowed
         :outputs (into (sorted-map) (map (fn [l] [l (file-sha256 l)])) libs)
         :exported exported
         :linked (into (sorted-map) (map (fn [l] [l (linked-libraries l)])) libs)}))))


(defn- build-jolt-file!
  "native/jolt-file (J4a): system libc only. The library must export exactly
   its allowlist, and link nothing but the C library."
  [cmake plat]
  (let [out (fs/path "build/native" plat)
        bld (str (fs/path out "jolt-file-build"))
        dest (str (fs/absolutize (fs/path out "jolt-file")))
        allowed (allowlist "native/jolt-file")]
    (fs/delete-tree bld)
    (fs/delete-tree dest)
    (run! cmake "-S" "native/jolt-file" "-B" bld
          "-DCMAKE_BUILD_TYPE=Release"
          (str "-DCMAKE_INSTALL_PREFIX=" dest))
    (run! cmake "--build" bld "--parallel" "--config" "Release")
    (run! cmake "--install" bld "--config" "Release")
    (let [libs (sort (map str (fs/glob dest "lib/*jolt_file*")))]
      (when (empty? libs) (fail! "no jolt_file library installed under " dest))
      (let [exported (into (sorted-map)
                           (map (fn [l]
                                  [l (vec (sort (map #(str/replace % #"^_" "")
                                                     (exported-symbols l))))]))
                           libs)
            linked (into (sorted-map) (map (fn [l] [l (linked-libraries l)])) libs)]
        (doseq [[l syms] exported]
          (when-not (= allowed syms)
            (fail! l " exports " syms ", the allowlist is " allowed)))
        (doseq [[l deps] linked
                d deps
                :when (not (str/includes? d "jolt_file"))]
          (when-not (re-find #"libSystem\.B\.dylib|\(NEEDED\).*\[libc\.so\.6\]" d)
            (fail! l " links " d "; jolt-file is system libc only")))
        {:prefix dest
         :allowlist allowed
         :outputs (into (sorted-map) (map (fn [l] [l (file-sha256 l)])) libs)
         :exported exported
         :linked linked}))))


(defn -main
  [& args]
  (let [{:keys [native cmake]} (edn/read-string (slurp "jolt-toolchain.edn"))
        cmake-bin (mise-bin "cmake" (:version cmake) "cmake")
        plat (platform)
        cxx (sh "c++" "--version")
        cc (sh "cc" "--version")
        libcrypto (check-libcrypto! (:libcrypto native) plat
                                    (boolean (some #{"--requalify"} args)))
        runtime (fetch! (:antlr4-cpp-runtime native))
        built (build-antlr4-runtime! runtime cmake-bin plat)
        parser (build-parser! built cmake-bin plat :test)
        release (build-parser! built cmake-bin plat :release)
        blake3-src (fetch! (:blake3 native))
        blake3 (build-blake3! blake3-src cmake-bin plat)
        crypto (build-jolt-crypto! blake3 libcrypto cmake-bin plat)
        file (build-jolt-file! cmake-bin plat)
        record {:platform plat
                :cmake {:path cmake-bin :version (first (str/split-lines (sh cmake-bin "--version")))}
                :cxx (first (str/split-lines cxx))
                :cc (first (str/split-lines cc))
                :antlr4-cpp-runtime (merge (select-keys runtime [:version :url :sha256 :cmake-flags])
                                           built)
                :jolt-parser parser
                :jolt-parser-release release
                :blake3 (merge (select-keys blake3-src [:version :url :sha256 :cmake-flags :upstream])
                               blake3)
                :libcrypto libcrypto
                :jolt-crypto crypto
                :jolt-file file}
        record-file (str (fs/path "build/native" plat "build-record.edn"))]
    (spit record-file (with-out-str (pprint/pprint record)))
    (pprint/pprint record)
    (println "wrote" record-file)))


(apply -main *command-line-args*)

(ns gambit-ffi-cache
  "Build and verify the worktree-local FFI artifacts independently of Yin's boot."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [build-cache :as cache]
            [clojure.edn :as edn]))


(def ^:private ffi-dir "host/gambit/ffi")
(def ^:private artifact-dir "build/gambit-ffi")
(def ^:private bridge "joltffi-bridge.o1")


(def ^:private artifacts
  [bridge "libjoltffi-fixture.dylib" "libjoltffi-fixture2.dylib"
   "libjoltffi-unloadable.dylib"])


(defn- with-lock
  [dir f]
  (cache/local-path! dir)
  (fs/create-dirs dir)
  (with-open [file (java.io.RandomAccessFile. (fs/file dir ".ffi.lock") "rw")
              channel (.getChannel file)
              lock (.lock channel)]
    (f)))


(defn- hashes
  [dir]
  (into (sorted-map)
        (for [name artifacts
              :let [file (fs/path dir name)]
              :when (fs/regular-file? file)]
          [name (cache/file-digest file)])))


(defn- read-stamp
  [entry]
  (let [stamp (fs/path entry "artifacts.edn")]
    (when (fs/regular-file? stamp)
      (try (edn/read-string (slurp (str stamp)))
           (catch Exception _ nil)))))


(defn- key-for
  [jolt-home prefix]
  (let [ffi (fs/path jolt-home ffi-dir)
        toolchain ["bin/gsi" "bin/gsc" "bin/gambuild-C" "include/gambit.h"
                   "lib/libgambit.a" "lib/libgambitgsi.a" "lib/libgambitgsc.a"]]
    (cache/digest
      {:recipe (cache/file-digest "src/dev/gambit_ffi_cache.clj")
       :platform [(System/getProperty "os.name") (System/getProperty "os.arch")]
       :c-compilers (mapv #(cache/checked-output % "--version") (keep fs/which ["cc" "gcc"]))
       :compiler-flags (mapv #(System/getenv %) ["CORCXXFLAGS_GAMBUILD" "LDFLAGS_GAMBUILD"])
       :ffi (into (sorted-map)
                  (map (fn [[path digest]] [(str (fs/relativize ffi path)) digest]))
                  (cache/snapshot [ffi]))
       :gambit (mapv #(cache/file-digest (fs/path prefix %)) toolchain)})))


(defn- stage!
  [opts watchdog message & command]
  (.println System/err (str "jolt-gambit: " message " [600 s watchdog]"))
  (apply p/shell (assoc opts :shutdown nil) "sh" (str watchdog) "600" command))


(defn- build-bridge!
  [stage jolt-home prefix]
  (let [ffi (fs/path stage ffi-dir)
        dir (fs/path stage artifact-dir)
        opts {:dir dir}
        watchdog (fs/path ffi "watchdog.sh")
        gsc (str (fs/path prefix "bin/gsc"))
        shared-flags (case (System/getProperty "os.name")
                       "Mac OS X" ["-dynamiclib"]
                       "Linux" ["-shared" "-fPIC"])
        fixture! (fn [output input & libraries]
                   (apply stage! opts watchdog (str "Building " output " (usually seconds)")
                          "cc" "-O1" (concat shared-flags ["-o" output (str (fs/path ffi input))]
                                             libraries)))]
    (fs/create-dirs (fs/parent ffi))
    (fs/copy-tree (fs/path jolt-home ffi-dir) ffi)
    (fs/create-dirs dir)
    (.println System/err "jolt-gambit: Verifying pinned FFI sources and toolchain")
    (doseq [script ["record-toolchain.sh" "verify-digests.sh"]]
      (p/shell {:dir stage :shutdown nil} "sh" (str (fs/path ffi script)) prefix))
    (doseq [source ["joltffi-bridge.scm" "generated-shims.scm"]]
      (fs/copy (fs/path ffi source) (fs/path dir source)))
    ;; Gambit looks up a dynamic module by its .o1 name. Carry that name
    ;; into the generated C before compiling and linking under separate deadlines.
    (stage! opts watchdog "Translating the FFI bridge from Scheme to C (can take minutes)"
            gsc "-c" "-module-ref" bridge "joltffi-bridge.scm")
    (stage! opts watchdog "Compiling the FFI bridge C object (can take minutes)"
            gsc "-obj" "-cc-options" "-D___DYNAMIC" "joltffi-bridge.c")
    (stage! (assoc opts :extra-env {"GAMBITDIR_INCLUDE" (str (fs/path prefix "include"))
                                    "GAMBITDIR_LIB" (str (fs/path prefix "lib"))
                                    "BUILD_DYN_INPUT_FILENAMES_PARAM" "joltffi-bridge.o"
                                    "BUILD_DYN_OUTPUT_FILENAME_PARAM" bridge})
            watchdog "Linking the FFI bridge module (usually seconds)"
            (str (fs/path prefix "bin/gambuild-C")) "dyn")
    (fixture! "libjoltffi-fixture.dylib" "joltffi-fixture.c")
    (fixture! "libjoltffi-fixture2.dylib" "joltffi-fixture2.c")
    (fixture! "libjoltffi-dep.dylib" "joltffi-dep.c")
    (fixture! "libjoltffi-unloadable.dylib" "joltffi-unloadable.c" "libjoltffi-dep.dylib")
    dir))


(defn ensure!
  "Return a verified bridge cache entry built from the pinned FFI inputs."
  [jolt-home prefix]
  (let [cache-dir (fs/absolutize artifact-dir)
        key (key-for jolt-home prefix)
        entry (fs/path cache-dir key)]
    (with-lock cache-dir
      (fn []
        (let [stamp (read-stamp entry)]
          (if (and (map? stamp) (= (set artifacts) (set (keys stamp))) (= stamp (hashes entry)))
            (do (.println System/err "jolt-gambit: FFI bridge cache hit (digests verified)")
                {:entry entry :hashes stamp})
            (let [stage (fs/create-temp-dir {:dir cache-dir :prefix "build-"})
                  publish (fs/path stage "publish")]
              (try
                (let [compiled (build-bridge! stage jolt-home prefix)]
                  (fs/create-dir publish)
                  (doseq [name artifacts]
                    (fs/copy (fs/path compiled name) (fs/path publish name)))
                  (let [stamp (hashes publish)]
                    (spit (str (fs/path publish "artifacts.edn")) (pr-str stamp))
                    (when (fs/exists? entry) (fs/delete-tree entry))
                    (fs/move publish entry {:atomic-move true})
                    {:entry entry :hashes stamp}))
                (finally (fs/delete-tree stage))))))))))


(defn stage-runtime!
  "Place verified artifacts at the relative path used by Jolt's native driver."
  [cache-dir jolt-home boot {entry :entry expected :hashes}]
  (let [runtime (fs/path cache-dir (str "runtime-" (cache/digest [boot jolt-home (str entry)])))
        dst (fs/path runtime artifact-dir)]
    (cache/local-path! dst)
    (with-lock cache-dir
      (fn []
        (when-not (= expected (hashes dst))
          (let [stage (fs/create-temp-dir {:dir cache-dir :prefix "copy-"})]
            (try
              (doseq [name artifacts]
                (fs/copy (fs/path entry name) (fs/path stage name)))
              (when-not (= expected (hashes stage))
                (throw (ex-info "Gambit FFI artifact digest mismatch while staging" {})))
              (fs/create-dirs (fs/path runtime "host/gambit"))
              (doseq [path ["host/gambit/yin" "stdlib" "jolt-core"]
                      :let [link (fs/path runtime path)]
                      :when (not (fs/exists? link))]
                (fs/create-sym-link link (fs/path jolt-home path)))
              (fs/create-dirs dst)
              (doseq [name artifacts]
                (fs/move (fs/path stage name) (fs/path dst name)
                         {:atomic-move true :replace-existing true}))
              (.println System/err "jolt-gambit: Staged digest-verified FFI artifacts for this boot")
              (finally (fs/delete-tree stage)))))))
    (str runtime)))

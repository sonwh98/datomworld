;; bb probe:jolt-file — the J4a gates for native/jolt-file and the jolt branch
;; of dao.jing.file (collab/1792769713135-architect-j4-file-seams-design §5,
;; J4a row; review F2), each in a child process so a refusal or an abort
;; cannot erase the report:
;;
;;   1. exact frame bytes: probes/frames.clj writes the same puts (both hash
;;      algorithms, embedded NUL, non-ASCII, a frame over 64 KiB) on the JVM
;;      and under jolt; the files are byte-identical, and every frame's
;;      prefix, read here, is the s32BE length of the bytes that follow;
;;   2. in-process gates (probes/gates.clj): F2 prefixes, strict reads,
;;      injected short/EINTR/zero-progress/error transfers, corrupt
;;      complete-frame open byte-identical, idempotent close, checked open,
;;      no descriptor or native-memory leak;
;;   3. collect-safety (probes/collect.clj): a full collection completes
;;      during a 1 GiB :blocking pwrite;
;;   4. missing wrapper: a root whose build/native lacks jolt-file (jolt-crypto
;;      present) refuses at load, nonzero, before guest work;
;;   5. wrong wrapper: the same root with a library answering layout 99
;;      refuses at load, nonzero, before guest work;
;;   6. exported symbols: the built library exports exactly the allowlist and
;;      links only the C library (re-checked from the build record).
;;
;; Copy isolation, torn-tail recovery and contention through one handle are
;; dao.jing.file-test's own suites, run on the jolt lane.
;;
;; Needs bb bootstrap:jolt and bb build:jolt-native. Prints PASS/FAIL per
;; gate and RECORD per observation; exits nonzero on any FAIL. Scratch roots
;; live under build/j4a-probe/ and are rebuilt each run.
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


(def root (str (fs/canonicalize ".")))
(def probes (str (fs/path root "native/jolt-file/probes")))
(def scratch (fs/path root "build/j4a-probe"))


(def plat
  (let [os (str/lower-case (System/getProperty "os.name"))
        arch (System/getProperty "os.arch")]
    (str (if (str/includes? os "mac") "darwin" "linux") "-"
         (if (#{"aarch64" "arm64"} arch) "arm64" "x86_64"))))


(def ext (if (str/starts-with? plat "darwin") ".dylib" ".so"))


(defn- jolt
  "Run jolt -Srepro -M:jolt in `dir` with `args`; answers exit and output."
  [dir & args]
  (let [r (apply p/shell {:dir dir :out :string :err :string :continue true}
                 "sh" "-c"
                 ". build/tools/jolt-env.sh && exec \"$JOLT_HOME/bin/jolt\" -Srepro -M:jolt \"$@\""
                 "jolt" args)]
    {:exit (:exit r) :out (str (:out r) (:err r))}))


(defn- lines
  [out prefix]
  (filter #(str/starts-with? % prefix) (str/split-lines out)))


(defn- relay
  "Print a child's RECORD/PASS/FAIL lines, counting its FAILs."
  [{:keys [out]}]
  (doseq [l (str/split-lines out)
          :when (re-find #"^(RECORD|PASS|FAIL) " l)]
    (when (str/starts-with? l "FAIL ") (swap! failures inc))
    (println l)))


(fs/delete-tree scratch)
(fs/create-dirs scratch)


;; 1. exact frame bytes, JVM canonical
(let [jvm-file (str (fs/path scratch "frames-jvm.log"))
      jolt-file (str (fs/path scratch "frames-jolt.log"))
      jvm (p/shell {:dir root :out :string :err :string :continue true}
                   "clojure" "-M" (str probes "/frames.clj") jvm-file)
      jl (jolt root (str probes "/frames.clj") jolt-file)
      a (when (fs/exists? jvm-file) (vec (fs/read-all-bytes jvm-file)))
      b (when (fs/exists? jolt-file) (vec (fs/read-all-bytes jolt-file)))
      frames (when b
               (loop [off 0, acc []]
                 (if (>= off (count b))
                   acc
                   (let [len (.getInt (java.nio.ByteBuffer/wrap
                                        (byte-array (subvec b off (+ off 4)))))]
                     (recur (+ off 4 len) (conj acc len))))))
      addresses (fn [out] (vec (lines out "ADDRESS")))]
  (println "RECORD frames: jvm exit" (:exit jvm) "bytes" (count a) "| jolt exit" (:exit jl)
           "bytes" (count b) "| frame lengths" (pr-str frames))
  (doseq [l (addresses (:out jl))] (println "RECORD jolt" l))
  (check "exact frame bytes: the jolt file is byte-identical to the JVM's (both hash algorithms)"
         (and (zero? (:exit jvm)) (zero? (:exit jl)) (seq a) (= a b)
              (= (addresses (str (:out jvm))) (addresses (:out jl))))
         {:jvm-exit (:exit jvm), :jolt-exit (:exit jl), :jvm-bytes (count a),
          :jolt-bytes (count b),
          :out (when-not (zero? (:exit jl)) (:out jl))
          :jvm-err (when-not (zero? (:exit jvm)) (:err jvm))})
  (check "exact frame bytes: 8 frames, each prefix the s32BE length of what follows, ending at end of file"
         (and (= 8 (count frames)) (every? pos? frames)
              (= (count b) (reduce + (* 4 (count frames)) frames)))
         frames))


;; 2. in-process gates
(let [r (jolt root (str probes "/gates.clj"))]
  (relay r)
  (check "in-process gates: child exited 0" (zero? (:exit r))
         {:exit (:exit r), :out (when-not (zero? (:exit r)) (last (str/split-lines (:out r))))}))


;; 3. collect-safety
(let [r (jolt root (str probes "/collect.clj"))]
  (relay r)
  (check "collect-safety: child exited 0" (zero? (:exit r)) {:exit (:exit r)}))


;; 4-5. missing and wrong wrapper: a root sharing the sources, jolt and every
;; other native library, with jolt-file absent or replaced
(defn- fake-root
  [nm wrapper]
  (let [dir (fs/path scratch nm)
        native (fs/path dir "build/native" plat)]
    (fs/create-dirs native)
    (doseq [f ["deps.edn" "src" "jolt-toolchain.edn"]]
      (fs/create-sym-link (fs/path dir f) (fs/path root f)))
    (fs/create-sym-link (fs/path dir "build/tools") (fs/real-path (fs/path root "build/tools")))
    (doseq [entry (fs/list-dir (fs/path root "build/native" plat))
            :when (not (#{"jolt-file" "jolt-file-build"} (str (fs/file-name entry))))]
      (fs/create-sym-link (fs/path native (fs/file-name entry)) (fs/real-path entry)))
    (when wrapper
      (fs/create-dirs (fs/path native "jolt-file/lib"))
      (fs/copy wrapper (fs/path native "jolt-file/lib" (str "libjolt_file" ext))))
    (str dir)))


(defn- refuses
  [label dir message]
  (let [{:keys [exit out]} (jolt dir (str probes "/guest.clj"))
        guest-file (fs/path dir "build/j4a-probe/guest.log")]
    (check label
           (and (not (zero? exit)) (str/includes? out message)
                (empty? (lines out "GUEST-WORK")) (not (fs/exists? guest-file)))
           {:exit exit, :out (first (filter #(str/includes? % "jolt-file") (str/split-lines out)))})))


(refuses "missing wrapper: dao.jing.file refuses at load, nonzero, before guest work"
         (fake-root "missing" nil) "the wrapper is not built")


(let [lib (str (fs/path scratch (str "wrong_layout" ext)))]
  (p/shell {:out :string :err :string} "cc" "-shared" "-fPIC" "-o" lib (str probes "/wrong_layout.c"))
  (refuses "wrong wrapper (layout 99): dao.jing.file refuses at load, nonzero, before guest work"
           (fake-root "wrong" lib) "the library writes another layout"))


;; 6. the build record's export and linkage checks, re-read
(let [record (edn/read-string (slurp (str (fs/path root "build/native" plat "build-record.edn"))))
      {:keys [allowlist exported linked outputs]} (:jolt-file record)
      lib (first (keys outputs))
      digest (let [md (java.security.MessageDigest/getInstance "SHA-256")]
               (.update md (fs/read-all-bytes lib))
               (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md))))]
  (println "RECORD jolt-file build" (pr-str {:sha256 (get outputs lib), :linked (get linked lib)}))
  (check "build record: the library exports exactly its allowlist, links only the C library, and is the recorded build"
         (and (seq allowlist) (= allowlist (get exported lib)) (= digest (get outputs lib))
              (every? #(or (str/includes? % "jolt_file")
                           (re-find #"libSystem\.B\.dylib|\[libc\.so\.6\]" %))
                      (get linked lib)))
         {:allowlist (count allowlist), :exported (count (get exported lib)),
          :digest-matches (= digest (get outputs lib))}))


(println (if (zero? @failures) "ALL PASS" (str @failures " FAIL")))
(System/exit (if (zero? @failures) 0 1))

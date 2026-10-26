;; bb probe:jolt-file — the J4a, J4b and J4c gates for native/jolt-file, the jolt
;; branch of dao.jing.file and dao.space.store.fs (collab/1792769713135-architect-j4-file-seams-design §5,
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
;; J4b (layout 2; commissioning collab/1792948716498 gates 1-6, probes/j4b/):
;;   - a J4a-built (layout 1) wrapper and a missing one refused at load of
;;     dao.space.store.fs, before any directory or lock file is made;
;;   - gate 1: jolt <-> jolt winner/loser through dao.space.store/open, the
;;     loser refused with the existing locked verdict and mutating nothing,
;;     the holder publishing on, a third contender refused, the last HEAD
;;     reopened;
;;   - gate 2: JVM holder vs jolt contender and the reverse (mandatory), Dart
;;     both ways where `dart` exists (a missing toolchain prints PARTIAL,
;;     never a pass);
;;   - gate 3: SIGKILL the holder, wait for its exit (137), reopen without
;;     deleting the lock file;
;;   - gate 4: O_CLOEXEC observed in a child exec'ing a fresh jolt
;;     (dev+inode, positive control, child refused, parent still holds);
;;   - gates 5-6 and the hygiene re-run in process (probes/j4b/fs_gates.clj),
;;     and the HEAD's mode checked against 0666 & ~umask (review F4).
;;
;; J4c (probes/j4c/fail_closed.clj): after a torn append or a failed fsync
;; the content-log handle refuses every later append naming the path and the
;; operation, appends nothing more, keeps serving get, closes once, and a
;; reopen recovers the acknowledged prefix; the file journal poisons, reopens
;; to its dense acknowledged prefix and continues with the same identity.
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
  ([label dir message] (refuses label dir message "/guest.clj" "build/j4a-probe/guest.log"))
  ([label dir message guest artifact]
   (let [{:keys [exit out]} (jolt dir (str probes guest))
         guest-file (fs/path dir artifact)]
     (check label
            (and (not (zero? exit)) (str/includes? out message)
                 (empty? (lines out "GUEST-WORK")) (not (fs/exists? guest-file)))
            {:exit exit, :out (first (filter #(str/includes? % "jolt-file") (str/split-lines out)))}))))


(refuses "missing wrapper: dao.jing.file refuses at load, nonzero, before guest work"
         (fake-root "missing" nil) "the wrapper is not built")


(let [lib (str (fs/path scratch (str "wrong_layout" ext)))]
  (p/shell {:out :string :err :string} "cc" "-shared" "-fPIC" "-o" lib (str probes "/wrong_layout.c"))
  (refuses "wrong wrapper (layout 99): dao.jing.file refuses at load, nonzero, before guest work"
           (fake-root "wrong" lib) "the library writes another layout"))


;; J4b: a J4a-built wrapper (layout 1) against the layout-2 facade, and the
;; directory-store entry point (dao.space.store.fs) behind a missing wrapper
(let [lib (str (fs/path scratch (str "j4a_layout" ext)))]
  (p/shell {:out :string :err :string} "cc" "-shared" "-fPIC" "-DLAYOUT=1" "-o" lib
           (str probes "/wrong_layout.c"))
  (refuses "J4a-built wrapper (layout 1): refused at load by the layout-2 facade, before guest work"
           (fake-root "j4a-layout" lib) "the library writes another layout"))


(refuses "missing wrapper: dao.space.store.fs refuses at load, nonzero, before any directory or lock file"
         (fake-root "missing-fs" nil) "the wrapper is not built"
         "/j4b/guest_fs.clj" "build/j4a-probe/guest-dir")


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


;; =============================================================================
;; J4b: directory ownership, cross-family locks, kill recovery, O_CLOEXEC,
;; HEAD cuts, bounded reads (commissioning collab/1792948716498 gates 1-6)
;; =============================================================================

(def j4b (str probes "/j4b"))
(def j4b-scratch (fs/path root "build/j4b-probe"))
(fs/delete-tree j4b-scratch)
(fs/create-dirs j4b-scratch)
(def partials (atom []))
(def live (atom []))


(defn- partial!
  [label detail]
  (swap! partials conj label)
  (println "PARTIAL" label (pr-str detail)))


(def dart?
  (zero? (:exit (p/shell {:out :string :err :string :continue true} "dart" "--version"))))


(println "RECORD dart toolchain" (if dart?
                                   (let [v (p/shell {:out :string :err :string :continue true} "dart" "--version")]
                                     (str/trim (str (:out v) (:err v))))
                                   "absent"))


(defn- fresh
  [nm]
  (let [d (str (fs/path j4b-scratch nm))]
    (fs/create-dirs d)
    d))


(defn- launch
  "Start a long-running child for `host` (:jolt, :jvm, :dart) with output to
   <sig>/out.log; answers the process."
  [host sig & args]
  (let [opts {:dir root :out :write :out-file (fs/file sig "out.log") :err :out}
        proc (case host
               :jolt (apply p/process opts "sh" "-c"
                            ". build/tools/jolt-env.sh && exec \"$JOLT_HOME/bin/jolt\" -Srepro -M:jolt \"$@\""
                            "jolt" args)
               :jvm (apply p/process opts "clojure" "-M" args)
               :dart (apply p/process opts "dart" args))]
    (swap! live conj proc)
    proc))


(defn- await-file
  [path ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (cond (fs/exists? path) true
            (> (System/currentTimeMillis) deadline) false
            :else (do (Thread/sleep 50) (recur))))))


(defn- owner!
  "Start an owner (owner.clj on :jolt/:jvm, lock_hold.dart on :dart) of dir;
   answers {:proc :sig :acquired?}."
  [host dir nm]
  (let [sig (fresh (str nm "-sig"))
        proc (if (= :dart host)
               (launch :dart sig (str j4b "/lock_hold.dart") dir sig)
               (launch host sig (str j4b "/owner.clj") dir sig))]
    {:proc proc, :sig sig, :host host,
     :acquired? (await-file (fs/path sig "acquired") 240000)}))


(def cmd-seq (atom 0))


(defn- command!
  "Send owner.clj a command and wait for its ack: the ack text, or nil."
  [{:keys [sig]} & words]
  (let [n (swap! cmd-seq inc)
        tmp (fs/path sig ".cmd.tmp")]
    (spit (str tmp) (str/join " " (cons n words)))
    (fs/move tmp (fs/path sig "cmd") {:replace-existing true :atomic-move true})
    (when (await-file (fs/path sig (str "ack-" n)) 600000)
      (slurp (str (fs/path sig (str "ack-" n)))))))


(defn- stop!
  [{:keys [proc sig host] :as o}]
  (if (= :dart host)
    (spit (str (fs/path sig "stop")) "stop")
    (command! o "stop"))
  (let [r (deref proc 120000 ::timeout)]
    (if (= ::timeout r) ::timeout (:exit r))))


(defn- contend
  "One contender on dir: the RESULT line, :jolt / :jvm through contend.clj,
   :dart through lock_contend.dart."
  [host dir]
  (let [r (case host
            :jolt (jolt root (str j4b "/contend.clj") dir)
            :jvm (let [x (p/shell {:dir root :out :string :err :string :continue true}
                                  "clojure" "-M" (str j4b "/contend.clj") dir)]
                   {:exit (:exit x) :out (str (:out x) (:err x))})
            :dart (let [x (p/shell {:dir root :out :string :err :string :continue true}
                                   "dart" (str j4b "/lock_contend.dart") dir)]
                    {:exit (:exit x) :out (str (:out x) (:err x))}))]
    (or (first (lines (:out r) "RESULT")) (str "NO RESULT exit " (:exit r) " " (:out r)))))


(defn- verify
  [host dir]
  (let [r (if (= :jolt host)
            (jolt root (str j4b "/verify.clj") dir)
            (let [x (p/shell {:dir root :out :string :err :string :continue true}
                             "clojure" "-M" (str j4b "/verify.clj") dir)]
              {:out (str (:out x) (:err x))}))]
    (first (lines (:out r) "RECOVERY"))))


(defn- snapshot
  [dir]
  (into (sorted-map)
        (map (fn [f] [(str (fs/file-name f)) (vec (fs/read-all-bytes f))]))
        (filter fs/regular-file? (fs/list-dir dir))))


(def refused? #(and % (str/starts-with? % "RESULT refused")))


(try

  ;; ---------------------------------------------------------------------------
  ;; Gate 1: jolt <-> jolt winner/loser, holder keeps writing, third loses
  ;; ---------------------------------------------------------------------------
  (let [dir (fresh "g1")
        o (owner! :jolt dir "g1")
        m0 (when (:acquired? o) (command! o "write" "5"))
        before (snapshot dir)
        c2 (contend :jolt dir)
        after (snapshot dir)
        m1 (command! o "write" "8")
        c3 (contend :jolt dir)
        exit (stop! o)
        rec (verify :jolt dir)]
    (println "RECORD gate1" (pr-str {:second c2, :third c3, :holder-writes [m0 m1], :stop exit, :reopen rec}))
    (check "gate 1: jolt holder acquired; a jolt contender is refused with the existing locked verdict and mutates nothing; the holder keeps publishing; a third contender is refused; the holder's last HEAD reopens"
           (and (:acquired? o) (refused? c2) (str/includes? c2 "is locked by another process")
                (= before after) m1 (refused? c3) (= 0 exit)
                (= (str "RECOVERY " m1 " 8") rec))
           {:second c2, :third c3, :reopen rec}))

  ;; ---------------------------------------------------------------------------
  ;; Gate 2: cross-family, both directions; JVM mandatory, Dart where present
  ;; ---------------------------------------------------------------------------
  (let [dir (fresh "g2-jolt-holds")
        o (owner! :jolt dir "g2a")
        m (when (:acquired? o) (command! o "write" "3"))
        c (contend :jvm dir)
        exit (stop! o)
        after (contend :jvm dir)]
    (println "RECORD gate2 jolt holder / JVM contender" (pr-str {:while-held c, :after-release after}))
    (check "gate 2: jolt holder vs JVM contender: FileChannel.tryLock is refused while jolt holds the fcntl lock, and acquires after release"
           (and (:acquired? o) m (refused? c) (= 0 exit) (= "RESULT acquired" after))
           {:while-held c, :after after}))

  (let [dir (fresh "g2-jvm-holds")
        o (owner! :jvm dir "g2b")
        m (when (:acquired? o) (command! o "write" "4"))
        c (contend :jolt dir)
        exit (stop! o)
        rec (verify :jolt dir)]
    (println "RECORD gate2 JVM holder / jolt contender" (pr-str {:while-held c, :reopen rec}))
    (check "gate 2: JVM holder vs jolt contender: jolt's F_SETLK is refused while the JVM holds; after release jolt reopens the JVM's published snapshot"
           (and (:acquired? o) m (refused? c) (= 0 exit) (= (str "RECOVERY " m " 4") rec))
           {:while-held c, :reopen rec}))

  (if-not dart?
    (partial! "gate 2: Dart both directions (no dart toolchain on this host)" {:host plat})
    (do
      (let [dir (fresh "g2-dart-holds")
            o (owner! :dart dir "g2c")
            c (contend :jolt dir)
            exit (stop! o)
            after (contend :jolt dir)]
        (println "RECORD gate2 Dart holder / jolt contender" (pr-str {:while-held c, :after after}))
        (check "gate 2: Dart holder (lockSync) vs jolt contender: refused while held, acquires after release"
               (and (:acquired? o) (refused? c) (= 0 exit) (= "RESULT acquired" after))
               {:while-held c, :after after}))
      (let [dir (fresh "g2-jolt-holds-dart")
            o (owner! :jolt dir "g2d")
            c (contend :dart dir)
            exit (stop! o)
            after (contend :dart dir)]
        (println "RECORD gate2 jolt holder / Dart contender" (pr-str {:while-held c, :after after}))
        (check "gate 2: jolt holder vs Dart contender (lockSync): refused while held, acquires after release"
               (and (:acquired? o) (refused? c) (= 0 exit) (= "RESULT acquired" after))
               {:while-held c, :after after}))))

  ;; ---------------------------------------------------------------------------
  ;; Gate 3: SIGKILL the holder, wait for its exit, acquire without deleting
  ;; ---------------------------------------------------------------------------
  (let [dir (fresh "g3")
        o (owner! :jolt dir "g3")
        m (when (:acquired? o) (command! o "write" "6"))
        pid (str/trim (slurp (str (fs/path (:sig o) "pid"))))
        lock-before (fs/exists? (fs/path dir "lock"))
        _ (p/shell "kill" "-9" pid)
        r (deref (:proc o) 60000 ::timeout)
        exited (when-not (= ::timeout r) (:exit r))
        lock-after (fs/exists? (fs/path dir "lock"))
        rec (verify :jolt dir)]
    (println "RECORD gate3" (pr-str {:pid pid, :exit exited, :lock-kept lock-after, :reopen rec}))
    (check "gate 3: SIGKILL the holder, wait for its real exit; the lock file is kept (never deleted) and a fresh process acquires and recovers content and HEAD"
           (and m lock-before lock-after (= 137 exited) (= (str "RECOVERY " m " 6") rec))
           {:exit exited, :reopen rec}))

  ;; ---------------------------------------------------------------------------
  ;; Gate 4: O_CLOEXEC, observed in a child that execs a fresh jolt
  ;; ---------------------------------------------------------------------------
  (let [dir (fresh "g4")
        o (owner! :jolt dir "g4")
        m (when (:acquired? o) (command! o "write" "3"))
        out (command! o "spawn" (str j4b "/cloexec_child.clj") dir)
        control (first (lines (or out "") "CONTROL"))
        matches (first (lines (or out "") "MATCHES"))
        child-lock (first (lines (or out "") "LOCK"))
        after (contend :jolt dir)
        exit (stop! o)]
    (println "RECORD gate4 child" (pr-str (filter #(re-find #"^(IDS|FDS|CONTROL|MATCHES|LOCK|exit)" %)
                                                  (str/split-lines (or out "")))))
    (check "gate 4: a child exec'ing a fresh jolt inherits no descriptor on the lock or content inode (dev+ino, positive control found), its own lock attempt is refused, and after it exits the parent still holds"
           (and m (str/starts-with? (str out) "exit 0") (str/ends-with? (str control) "found")
                (str/starts-with? (str matches) "MATCHES 0 ")
                (str/starts-with? (str child-lock) "LOCK refused") (refused? after) (= 0 exit))
           {:control control, :matches matches, :child-lock child-lock, :after after}))

  ;; ---------------------------------------------------------------------------
  ;; Gates 5-6 and the hygiene re-run, in process (probes/j4b/fs_gates.clj)
  ;; ---------------------------------------------------------------------------
  (let [r (jolt root (str j4b "/fs_gates.clj"))
        umask (str/trim (:out (p/shell {:out :string} "sh" "-c" "umask")))
        expected (bit-and 0666 (bit-not (Long/parseLong umask 8)))]
    (relay r)
    (check "in-process J4b gates: child exited 0" (zero? (:exit r)) {:exit (:exit r)})
    (doseq [l (lines (:out r) "MODE ")]
      (let [path (subs l 5)
            perms (fs/posix-file-permissions path)
            mode (reduce (fn [acc [bit k]] (if (contains? perms k) (bit-or acc bit) acc))
                         0
                         [[0400 java.nio.file.attribute.PosixFilePermission/OWNER_READ]
                          [0200 java.nio.file.attribute.PosixFilePermission/OWNER_WRITE]
                          [0100 java.nio.file.attribute.PosixFilePermission/OWNER_EXECUTE]
                          [040 java.nio.file.attribute.PosixFilePermission/GROUP_READ]
                          [020 java.nio.file.attribute.PosixFilePermission/GROUP_WRITE]
                          [010 java.nio.file.attribute.PosixFilePermission/GROUP_EXECUTE]
                          [04 java.nio.file.attribute.PosixFilePermission/OTHERS_READ]
                          [02 java.nio.file.attribute.PosixFilePermission/OTHERS_WRITE]
                          [01 java.nio.file.attribute.PosixFilePermission/OTHERS_EXECUTE]])]
        (check "HEAD mode is 0666 & ~umask (review F4: not private)"
               (= expected mode) {:umask umask, :mode (Long/toOctalString mode),
                                  :expected (Long/toOctalString expected)}))))

  (finally
    (doseq [proc @live]
      (when (.isAlive ^java.lang.Process (:proc proc))
        (.destroyForcibly ^java.lang.Process (:proc proc))))))


;; =============================================================================
;; J4c: the fail-closed handle state (review F3), in process
;; (probes/j4c/fail_closed.clj); the real kill-mid-append of a jolt journal
;; writer is dao.stream.journal.file-test's own jolt leg, on the lane
;; =============================================================================

(fs/delete-tree (fs/path root "build/j4c-probe"))


(let [r (jolt root (str probes "/j4c/fail_closed.clj"))]
  (relay r)
  (check "J4c fail-closed gates: child exited 0" (zero? (:exit r)) {:exit (:exit r)}))


(when (seq @partials)
  (println "PARTIAL legs:" (pr-str @partials)))


(println (cond (pos? @failures) (str @failures " FAIL")
               (seq @partials) "ALL PASS (with PARTIAL legs)"
               :else "ALL PASS"))


(System/exit (if (zero? @failures) 0 1))

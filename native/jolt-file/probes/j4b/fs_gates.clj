;; Run under jolt -Srepro -M:jolt by probes/run.clj: the in-process J4b
;; gates over dao.space.store.fs's :jolt arms and dao.posix.file
;; (collab/1792769713135 §3-§5; commissioning collab/1792948716498 gates 5,
;; 6 and the hygiene re-run, plus the §3 single-process negatives). The
;; test-only indirection is dao.posix.file's single-call vars (lock-once,
;; open-excl-once, pwrite-once, fsync-once, close-once, rename-once,
;; sync-dir-once) and its public classify; jolt.ffi/alloc and free are
;; wrapped to account for every native buffer, and the process's descriptors
;; are counted before and after.
;;
;; Prints PASS/FAIL per gate, RECORD per observation, and "MODE <path>" for
;; run.clj to check the published HEAD's permission bits; exits nonzero on
;; any FAIL. Scratch directories live under build/j4b-probe/fs/.
(ns fs-gates
  (:require [clojure.string :as str]
            [jolt.ffi :as ffi]))


(load-file "native/jolt-file/probes/j4b/common.clj")


(require '[dao.posix.file :as pf] '[dao.space.store :as store]
         '[dao.space.store.fs :as fs])


(def failures (atom 0))


(defn- check
  [label ok? detail]
  (when-not ok? (swap! failures inc))
  (println (if ok? "PASS" "FAIL") label (pr-str detail)))


(def root (str (System/getProperty "user.dir") "/build/j4b-probe/fs"))
(def counter (atom 0))


(defn- scratch-dir
  [nm]
  (let [d (str root "/" nm "-" (swap! counter inc))]
    (.mkdirs (java.io.File. ^String d))
    d))


(defn- sh!
  [& args]
  (let [p (.start (ProcessBuilder. ^java.util.List (vec args)))]
    [(.waitFor p) (slurp (.getInputStream p))]))


(defn- spit-bytes!
  [path ints]
  (.delete (java.io.File. ^String path))
  (with-open [r (java.io.RandomAccessFile. (java.io.File. ^String path) "rw")]
    (.write r (byte-array (map unchecked-byte ints)))))


(defn- text-of
  [path]
  (when (.exists (java.io.File. ^String path)) (slurp path)))


(defn- thrown
  [f]
  (try (f) nil (catch Throwable e e)))


(defn- message
  [e]
  (when e (or (ex-message e) (str e))))


(def eintr @(ns-resolve 'dao.posix.file 'eintr))
(def EIO 5)


(def vars
  (into {} (map (fn [s] [s (ns-resolve 'dao.posix.file s)]))
        '[lock-once open-excl-once pwrite-once fsync-once close-once rename-once
          sync-dir-once]))


(def orig (into {} (map (fn [[k v]] [k @v])) vars))
(def held-var (ns-resolve 'dao.space.store.fs 'held))


(defn- traced
  "Run (f) with every single-call var recording [name] into a trace, each
   still calling through `overrides` (name -> (fn [orig & args])) or the
   original. Answers [result-or-throwable trace]."
  [overrides f]
  (let [trace (atom [])
        redefs (into {}
                     (map (fn [[k v]]
                            [v (fn [& args]
                                 (swap! trace conj k)
                                 (if-let [o (get overrides k)]
                                   (apply o (orig k) args)
                                   (apply (orig k) args)))]))
                     vars)
        r (with-redefs-fn redefs #(try (f) (catch Throwable t t)))]
    [r @trace]))


(defn- collapse
  [trace]
  (mapv first (partition-by identity trace)))


;; =============================================================================
;; Accounting around everything below
;; =============================================================================

(def ledger (atom {:allocs 0, :frees 0, :live #{}, :double-free 0}))
(def orig-alloc ffi/alloc)
(def orig-free ffi/free)

(def fd-dir (if (.exists (java.io.File. "/proc/self/fd")) "/proc/self/fd" "/dev/fd"))


(defn- fd-count
  []
  (count (.list (java.io.File. ^String fd-dir))))


;; jolt's first random-uuid opens /dev/urandom and keeps it for the life of
;; the runtime (one descriptor, identified by inode during development): warm
;; it before the baseline so the count measures this code, not the runtime.
(random-uuid)
(def fds-before (fd-count))


(defn- fd-mark
  [label]
  (println "RECORD descriptors after" label
           (pr-str (sort (keep parse-long (seq (.list (java.io.File. ^String fd-dir))))))))


(with-redefs-fn
  {#'ffi/alloc (fn [& args]
                 (let [p (apply orig-alloc args)]
                   (swap! ledger #(-> % (update :allocs inc) (update :live conj (ffi/address p))))
                   p))
   #'ffi/free (fn [p]
                (let [a (ffi/address p)]
                  (swap! ledger #(if (contains? (:live %) a)
                                   (-> % (update :frees inc) (update :live disj a))
                                   (update % :double-free inc))))
                (orig-free p))}
  (fn []

    ;; =========================================================================
    ;; §3 single-process negatives
    ;; =========================================================================
    (let [dir (scratch-dir "own")
          l1 (fs/lock! dir)
          again (thrown #(fs/lock! dir))
          dot (thrown #(fs/lock! (str dir "/.")))
          _ (.mkdirs (java.io.File. (str dir "/sub")))
          dotdot (thrown #(fs/lock! (str dir "/sub/..")))
          link (str root "/own-link-" (swap! counter inc))
          _ (sh! "ln" "-s" dir link)
          via-link (thrown #(fs/lock! link))]
      (check "same process: a second owner, by the same spelling, '.', '..' or a directory symlink, is refused"
             (every? #(re-find #"is locked by another process" (str (message %)))
                     [again dot dotdot via-link])
             (mapv message [again dot dotdot via-link]))
      (fs/unlock! l1)
      (let [l2 (fs/lock! dir)]
        (fs/unlock! l1)
        (let [still (thrown #(fs/lock! dir))]
          (check "an old release after a newer acquisition releases nothing: the newer owner still refuses"
                 (some? still) (message still)))
        (fs/unlock! l2)
        (fs/unlock! l2)
        (let [l3 (fs/lock! dir)]
          (fs/unlock! l3)
          (check "release is idempotent and the directory reopens afterwards" true nil))))

    (let [dir (scratch-dir "race")
          results (mapv deref (doall (repeatedly 16 #(future (try (fs/lock! dir) (catch Throwable _ :refused))))))
          winners (remove #{:refused} results)]
      (check "16 threads race one directory: exactly one acquires" (= 1 (count winners))
             {:winners (count winners)})
      (run! fs/unlock! winners))

    (let [dir (scratch-dir "fail")
          [r1 _] (traced {'lock-once (fn [_ _] (- EIO))} #(fs/lock! dir))
          clean1 (not (contains? @@held-var (pf/realpath dir)))
          [r2 _] (traced {'lock-once (fn [_ _] 1)} #(fs/lock! dir))
          clean2 (not (contains? @@held-var (pf/realpath dir)))
          l (fs/lock! dir)]
      (fs/unlock! l)
      (check "an injected fcntl error throws (not a refusal) and leaves the registry clean"
             (and (instance? Throwable r1) (not (re-find #"is locked" (message r1))) clean1)
             (message r1))
      (check "an injected EAGAIN/EACCES answer is the existing refuse-locked verdict, registry clean"
             (and (instance? Throwable r2) (re-find #"is locked by another process" (message r2)) clean2)
             (message r2)))

    (let [dir (scratch-dir "lockdir")
          _ (.mkdirs (java.io.File. (str dir "/lock")))
          e (thrown #(fs/lock! dir))
          clean (not (contains? @@held-var (pf/realpath dir)))]
      (check "a lock file that cannot be opened (a directory) fails the acquisition, registry clean"
             (and e (not (re-find #"is locked" (message e))) clean) (message e)))

    (let [uid (str/trim (second (sh! "id" "-u")))
          dir (scratch-dir "perm")
          _ (spit-bytes! (str dir "/lock") [])
          _ (sh! "chmod" "000" (str dir "/lock"))
          e (thrown #(fs/lock! dir))]
      (sh! "chmod" "644" (str dir "/lock"))
      (println "RECORD permission gate uid" uid)
      (check (str "permission denied on the lock file is a failure, never ownership"
                  (when (= "0" uid) " (SKIPPED MEANING: running as root)"))
             (or (= "0" uid) (and e (not (re-find #"is locked" (message e)))))
             (message e)))

    (fd-mark "§3 negatives")
    ;; =========================================================================
    ;; Gate 5: HEAD replacement order and cuts
    ;; =========================================================================
    (let [dir (scratch-dir "trace")
          _ (fs/atomic-replace! dir "HEAD" "{:version 1, :manifest :segment/a}")
          [r trace] (traced {} #(fs/atomic-replace! dir "HEAD" "{:version 1, :manifest :segment/b}"
                                                    {:before-rename (fn [_] nil)}))
          ;; the hook is not a var; mark it in the trace by a second run
          hook-trace (atom [])
          [_ trace2] (traced {} #(fs/atomic-replace!
                                   dir "HEAD" "{:version 1, :manifest :segment/c}"
                                   {:before-rename (fn [_] (swap! hook-trace conj :seen))}))
          marked (let [i (first (keep-indexed #(when (= 'rename-once %2) %1) trace2))]
                   (collapse (concat (take i trace2) ['before-rename] (drop i trace2))))]
      (println "RECORD HEAD replacement trace" (pr-str marked))
      (check "HEAD order: temp O_EXCL -> write-all -> fsync -> close -> before-rename -> rename -> directory open+fsync+close"
             (and (not (instance? Throwable r))
                  (= '[open-excl-once pwrite-once fsync-once close-once before-rename rename-once sync-dir-once]
                     marked)
                  (= [:seen] @hook-trace)
                  (= "{:version 1, :manifest :segment/c}" (text-of (str dir "/HEAD"))))
             marked)
      (println "MODE" (str dir "/HEAD")))

    (fd-mark "HEAD trace")
    ;; cuts over a real published store, each reopened through dao.space.store
    (let [cut-store
          (fn [label overrides hook expect]
            (let [dir (scratch-dir (str "cut-" label))
                  st (store/open dir)
                  m1 (common/publish! st 7)
                  m2 (common/publish-content! st 9)
                  head-before (text-of (str dir "/HEAD"))
                  [r trace] (traced overrides
                                    #(fs/atomic-replace!
                                       dir "HEAD"
                                       (str (pr-str {:version 1 :manifest m2}) "\n")
                                       {:before-rename hook}))
                  head-after (text-of (str dir "/HEAD"))
                  temps (vec (filter #(str/starts-with? % "HEAD.tmp-")
                                     (seq (.list (java.io.File. ^String dir)))))]
              (store/close! st)
              (let [re (store/open dir)
                    recovered (get-in re [:recovery :manifest])
                    n (count (get-in re [:recovery :datoms]))]
                (store/close! re)
                (expect {:r r, :trace (collapse trace), :before head-before,
                         :after head-after, :temps temps, :recovered recovered,
                         :m1 m1, :m2 m2, :datoms n, :dir dir}))))]

      (cut-store "writes"
                 {'pwrite-once (let [n (atom 0)]
                                 (fn [o fd off buf at len]
                                   (if (= 1 (swap! n inc))
                                     (o fd off buf at (min len 3))
                                     (- EIO))))}
                 nil
                 (fn [{:keys [r trace before after temps recovered m1 datoms]}]
                   (check "cut during the temp writes (3 bytes, then EIO): throws, no rename, HEAD byte-identical, reopen recovers the previous snapshot"
                          (and (instance? Throwable r) (re-find #"cannot replace" (message r))
                               (not (some #{'rename-once} trace)) (= before after)
                               (= 1 (count temps)) (= m1 recovered) (= 7 datoms))
                          {:trace trace, :temps temps, :error (message r)})))

      (cut-store "fsync"
                 {'fsync-once (fn [o fd] (- EIO))}
                 nil
                 (fn [{:keys [r trace before after recovered m1]}]
                   (check "temp fsync fails (EIO): throws, never renamed, HEAD unchanged, previous snapshot reopens"
                          (and (instance? Throwable r) (not (some #{'rename-once} trace))
                               (= before after) (= m1 recovered))
                          {:trace trace})))

      (cut-store "close"
                 {'close-once (let [n (atom 0)]
                                (fn [o fd] (o fd) (if (= 1 (swap! n inc)) (- EIO) 0)))}
                 nil
                 (fn [{:keys [r trace before after recovered m1]}]
                   (check "temp close fails (checked): throws, never renamed, HEAD unchanged"
                          (and (instance? Throwable r) (not (some #{'rename-once} trace))
                               (= before after) (= m1 recovered))
                          {:trace trace, :error (message r)})))

      (cut-store "before-rename"
                 {}
                 (fn [temp] (throw (ex-info "crash" {:temp temp})))
                 (fn [{:keys [r trace before after temps recovered m1]}]
                   (check "cut after the temp sync, before the rename: throws, HEAD unchanged, the synced temp left, previous snapshot reopens"
                          (and (instance? Throwable r) (not (some #{'rename-once} trace))
                               (= before after) (= 1 (count temps)) (= m1 recovered))
                          {:trace trace, :temps temps})))

      (cut-store "dir-sync"
                 {'sync-dir-once (fn [_o _dir] (- EIO))}
                 nil
                 (fn [{:keys [r trace before after recovered m2 datoms]}]
                   (check "cut after the rename, before the directory sync (injected EIO): the replacement FAILS naming the sync; HEAD is the whole new record (reopen: the new snapshot, distinguishable, not reported published); directory-sync-supported? stays true"
                          (and (instance? Throwable r) (re-find #"sync" (message r))
                               (some #{'rename-once} trace)
                               (not= before after) (str/includes? after (str m2))
                               (true? (fs/directory-sync-supported?)) (= m2 recovered) (= 9 datoms))
                          {:trace trace, :error (message r)})))

      (cut-store "acked"
                 {}
                 nil
                 (fn [{:keys [r after recovered m2 datoms]}]
                   (check "after the acknowledgement: the new HEAD reopens with its snapshot"
                          (and (not (instance? Throwable r)) (str/includes? after (str m2))
                               (= m2 recovered) (= 9 datoms))
                          {:recovered recovered}))))

    (fd-mark "HEAD cuts")
    ;; store_test.cljc:916-1029 semantics
    (let [dir (scratch-dir "store-test")]
      (spit (str dir "/HEAD.tmp-crashed") "{:version 1, :man")
      (fs/atomic-replace! dir "HEAD" "{:version 1, :manifest :segment/a}")
      (sh! "chmod" "444" (str dir "/HEAD"))
      (fs/atomic-replace! dir "HEAD" "{:version 1, :manifest :segment/b}")
      (let [ok-ro (= "{:version 1, :manifest :segment/b}" (text-of (str dir "/HEAD")))
            [r _] (traced {} #(fs/atomic-replace!
                                dir "HEAD" "{:version 1, :manifest :segment/c}"
                                {:before-rename (fn [temp]
                                                  (spit temp "{:version 1, :mani")
                                                  (throw (ex-info "crash" {})))}))
            [_ short-trace] (traced {'pwrite-once (fn [o fd off buf at len] (o fd off buf at (min len 3)))}
                                    #(fs/atomic-replace! dir "HEAD" "{:version 1, :manifest :segment/d}"))]
        (check "store_test semantics: a leftover temp is ignored, a read-only HEAD is replaced by rename, a torn temp never becomes HEAD, 3-byte writes never publish a partial HEAD"
               (and ok-ro (instance? Throwable r)
                    (= "{:version 1, :man" (text-of (str dir "/HEAD.tmp-crashed")))
                    (= "{:version 1, :manifest :segment/d}" (text-of (str dir "/HEAD")))
                    (< 5 (count (filter #{'pwrite-once} short-trace))))
               {:pwrites (count (filter #{'pwrite-once} short-trace))})))

    (let [uid (str/trim (second (sh! "id" "-u")))
          dir (scratch-dir "chmod-sync")]
      (fs/atomic-replace! dir "HEAD" "{:version 1, :manifest :segment/a}")
      (sh! "chmod" "300" dir)
      (let [e (thrown #(fs/atomic-replace! dir "HEAD" "{:version 1, :manifest :segment/b}"))]
        (sh! "chmod" "755" dir)
        (check (str "chmod-based directory sync failure (non-root precondition, uid " uid ")")
               (or (= "0" uid) (and e (re-find #"sync" (message e))))
               (message e))))

    (fd-mark "store_test semantics")
    ;; =========================================================================
    ;; Gate 6: bounded reads and decoding
    ;; =========================================================================
    (let [dir (scratch-dir "bounded")
          f (str dir "/f")
          big (str dir "/big")
          fifo (str dir "/fifo")
          _ (spit-bytes! f [104 105])
          _ (spit-bytes! big (repeat 100 120))
          _ (sh! "ln" "-s" "f" (str dir "/to-f"))
          _ (sh! "ln" "-s" "missing" (str dir "/dangling"))
          _ (.mkdirs (java.io.File. (str dir "/d")))
          _ (sh! "mkfifo" fifo)
          not-regular? #(= "it is not a regular file" (message (thrown %)))
          timed (fn [g] (deref (future (try (g) (catch Throwable t t))) 5000 ::blocked))
          fifo-r (timed #(fs/read-bounded fifo 8))
          swapped (timed #(with-redefs-fn {#'pf/classify (fn [_ _] :regular)}
                            (fn [] (fs/read-bounded fifo 8))))]
      (check "absence is nil" (nil? (fs/read-bounded (str dir "/absent") 8)) nil)
      (check "a dangling symlink is refused: it is not a regular file"
             (not-regular? #(fs/read-bounded (str dir "/dangling") 8)) nil)
      (check "a symlink to a regular file is followed"
             (= "hi" (fs/decode-utf8 (fs/read-bounded (str dir "/to-f") 8))) nil)
      (check "a directory is refused: it is not a regular file"
             (not-regular? #(fs/read-bounded (str dir "/d") 8)) nil)
      (check "a FIFO is refused without blocking (classify)"
             (and (not= ::blocked fifo-r) (= "it is not a regular file" (message fifo-r)))
             (message fifo-r))
      (check "a FIFO swapped in after classify: the non-blocking open does not block, the fstat recheck refuses"
             (and (not= ::blocked swapped) (= "it is not a regular file" (message swapped)))
             (if (= ::blocked swapped) :blocked (message swapped)))
      (check "the bound: 100 bytes read with limit 8 answer 9; a file within it whole; empty is no bytes, not absence"
             (and (= 9 (fs/byte-length (fs/read-bounded big 8)))
                  (= 2 (fs/byte-length (fs/read-bounded f 8)))
                  (= 8 (fs/byte-length (fs/read-bounded big 7)))
                  (let [e (str dir "/empty")] (spit-bytes! e []) (zero? (fs/byte-length (fs/read-bounded e 8)))))
             nil)
      (check "a limit whose limit+1 does not fit 32 bits is refused before any allocation"
             (some? (thrown #(fs/read-bounded f 2147483647))) nil)
      (let [bad (fn [codes]
                  (spit-bytes! (str dir "/u") codes)
                  (message (thrown #(fs/decode-utf8 (fs/read-bounded (str dir "/u") 16)))))]
        (check "strict UTF-8: invalid, truncated, overlong and surrogate encodings throw; multi-byte decodes"
               (and (= "aéb" (fs/decode-utf8 (do (spit-bytes! (str dir "/u") [97 0xc3 0xa9 98])
                                                 (fs/read-bounded (str dir "/u") 16))))
                    (every? some? (map bad [[97 0xff 98] [97 0xc3] [97 0xc0 0xaf 98] [97 0xed 0xa0 0x80 98]])))
               nil))
      ;; read-file-text (HEAD's read)
      (spit (str dir "/HEAD") "  {:version 1}\n")
      (spit-bytes! (str dir "/BAD") [0xc0 0xaf])
      (let [uid (str/trim (second (sh! "id" "-u")))
            _ (spit (str dir "/SECRET") "x")
            _ (sh! "chmod" "000" (str dir "/SECRET"))
            denied (thrown #(fs/read-file-text dir "SECRET"))]
        (sh! "chmod" "644" (str dir "/SECRET"))
        (check "read-file-text: absence nil, text trimmed, invalid UTF-8 throws, a directory throws, permission denied throws (never absence)"
               (and (nil? (fs/read-file-text dir "NOPE"))
                    (= "{:version 1}" (fs/read-file-text dir "HEAD"))
                    (some? (thrown #(fs/read-file-text dir "BAD")))
                    (some? (thrown #(fs/read-file-text dir "d")))
                    (or (= "0" uid) (some? denied)))
               {:uid uid, :denied (message denied)})))))


(let [{:keys [allocs frees live double-free]} @ledger
      fds-after (fd-count)]
  (println "RECORD native buffers" (pr-str {:allocs allocs, :frees frees, :live (count live),
                                            :double-free double-free}))
  (println "RECORD descriptors" fd-dir "before" fds-before "after" fds-after)
  (check "no native-memory leak over every J4b gate and injected failure"
         (and (pos? allocs) (= allocs frees) (empty? live) (zero? double-free))
         {:allocs allocs, :frees frees})
  (check "no descriptor leak over every J4b gate (locks released, temps and reads closed)"
         (= fds-before fds-after) {:before fds-before, :after fds-after}))


(System/exit (if (zero? @failures) 0 1))

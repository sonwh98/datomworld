(ns dao.space.store.fs
  "Explicit host file operations for the durable index store directory
   (dao.space.store; docs/design/yin.repl.dao.space-index.md, the
   durable-store design's host section): an exclusive directory lock held by the one process
   that owns the store, and an atomic write-temp, sync, rename
   replacement for HEAD. CLJ, Node, and Dart each implement them with
   their own primitives; nothing here falls back silently.

   Lock ownership is an operating-system fact on the JVM — an exclusive
   `FileChannel` lock on `<dir>/lock`, released when the holding process
   dies — and on Dart, `RandomAccessFile.lockSync`. Node has no flock in
   its core, so each contender creates its own uniquely named claim
   entry, `lock.<pid>.<nonce>`, and then reads the others: a live one
   refuses, and a dead one — an owner or a contender that crashed at any
   point — is removed without a race, because a unique name is never
   created again.  A crashed process never bricks the directory, and two
   contenders never both own it (`node-lock!`) — two worker threads of
   one process included, since a claim naming this pid is live unless it
   is this call's own entry.

   Contract, pid reuse (Node only): a claim's liveness is its pid's, so a
   crashed owner's claim whose pid the OS has since given to an
   unrelated live process reads as live, and startup refuses — the safe
   direction; it is never read as dead while alive.  The refusal names
   the claim entries it saw.  Operator remedy: confirm that no REPL uses
   the directory, then delete the named `<dir>/lock.<pid>.<nonce>`
   entries (or wait until the process holding that pid exits); the next
   start opens normally.  The JVM and Dart locks are held by the
   operating system and have no pid-reuse case.

   HEAD replacement writes the whole record to a temp file in the same
   directory — every byte of it, however many writes the host takes —
   syncs it, and renames it over HEAD — rename is the atomicity, so a
   torn, short, or interrupted write is never observed as HEAD.
   The directory itself is then synced where the host can
   (`directory-sync-supported?`: Node and the JVM on POSIX systems), and
   there a failed sync fails the replacement; a host that cannot sync a
   directory at all (Dart's core, Windows) is not a failed sync.

   The operating-system locks are per process — a POSIX `fcntl` lock
   never refuses its own process — so this process's own holdings are
   kept here too, in `held`: a second owner inside the one process is
   refused by that record before any second handle on the lock file is
   opened.  `held` is process-global mutable state, an explicit
   exception to the no-hidden-global-state invariant: it mirrors a fact
   the host itself keeps per process (which files this process has
   locked), so it can be no narrower than the process, and it holds
   nothing but the canonical paths of the directories this process
   currently owns.  On Node each worker thread has its own `held`; across
   workers the claim entries decide."
  (:require #?@(:cljd [["dart:convert" :as convert]
                       ["dart:io" :as dart-io]])
            [clojure.string :as str])
  #?@(:cljd [(:import ["dart:typed_data" Uint8List])]))


(def lock-name
  "The lock file's own name inside the durable store directory, and the
   prefix of Node's claim entries (`lock.<pid>.<nonce>`).  Its presence
   is bookkeeping, never authority: the JVM and Dart locks live on open
   handles, and each Node entry names a pid that is checked for life — a
   reused pid keeps a dead claim live until an operator removes the
   entry (see the namespace docstring's pid-reuse contract)."
  "lock")


(defn- refuse-locked
  [dir]
  (throw (ex-info (str dir " is locked by another process")
                  {:dir dir})))


(defn- read-text!
  "The file at `path` as text — trimmed, for one-record files read whole —
   or nil when absent."
  [path]
  #?(:cljd (let [f (dart-io/File. path)]
             (when (.existsSync f)
               (str/trim (.readAsStringSync f))))
     :clj (let [f (java.io.File. ^String path)]
            (when (.exists f) (str/trim (slurp f))))
     :cljs (let [fs (js/require "fs")]
             (when (.existsSync ^js fs path)
               (str/trim (.readFileSync ^js fs path "utf8"))))))


#?(:cljs
   (defn- live-pid?
     "True when a pid names a live process — the lock file's owner is
      alive.  A process this one may not signal (`EPERM`) is alive too."
     [pid]
     (try (.kill js/process pid 0)
          true
          (catch :default e
            (= "EPERM" (.-code e))))))


#?(:cljs
   (defn- claim-entries
     "Every Node claim entry in the directory, as `[file-name pid]`: the
      files `lock.<pid>.<nonce>`, each created by the one process whose
      pid it names."
     [fs dir]
     (keep (fn [file-name]
             (when-some [[_ pid] (re-matches #"lock\.(\d+)\.[0-9a-f-]+"
                                             file-name)]
               [file-name (js/parseInt pid 10)]))
           (.readdirSync ^js fs dir))))


#?(:cljs
   (defn- node-lock!
     "Claim the directory on Node, answering the release, or refuse.

      Each contender creates its own uniquely named claim entry,
      `lock.<pid>.<nonce>`, and only then reads the others.  An entry
      naming a live process is an owner or a rival contender: the claim
      is withdrawn and refused.  An entry naming a dead process — an
      owner that crashed, or a contender that crashed midway through its
      own claim — is removed, and that removal is race-free because the
      name is unique: no later process ever creates it again, so it can
      never be a newer live claim.  Of two contenders, each creates
      before it reads, so at most one reads no live rival; at worst two
      simultaneous starts both refuse, never both own.

      An entry naming this very pid is live like any other: worker
      threads share the process's pid but each has its own `held`, so
      another worker's claim looks exactly like that — only this call's
      own entry, by its exact name, is this contender's.  Liveness is by
      pid, so a dead claim whose pid was reused by an unrelated live
      process is read as live and refuses; the refusal names the claim
      entries it saw, for the operator remedy in the namespace
      docstring."
     [dir claimed]
     (let [fs (js/require "fs")
           own-pid (.-pid js/process)
           own (str lock-name "." own-pid "." (random-uuid))
           own-path (str dir "/" own)
           release! (fn []
                      (try (.unlinkSync ^js fs own-path)
                           (catch :default _ nil)))]
       (.closeSync ^js fs (.openSync ^js fs own-path "wx"))
       (try
         (when claimed (claimed))
         (let [live (into []
                          (remove
                            (fn [[file-name pid]]
                              (when-not (live-pid? pid)
                                (try (.unlinkSync ^js fs (str dir "/" file-name))
                                     (catch :default _ nil))
                                true)))
                          (remove #(= own (first %)) (claim-entries fs dir)))]
           (when (seq live)
             (throw (ex-info (str dir " is locked by another process"
                                  " (claims: "
                                  (str/join ", " (map first live)) ")")
                             {:dir dir :claims (mapv first live)})))
           release!)
         (catch :default e
           (release!)
           (throw e))))))


(defonce ^:private held
  ;; The host-ownership exception to the no-hidden-global-state invariant
  ;; (see the namespace docstring): the canonical paths of the durable
  ;; directories this process holds locked.
  (atom #{}))


(defn- canonical-dir
  "The directory's one canonical path: the key of this process's own
   holdings, so two spellings of one directory are one owner."
  [dir]
  #?(:cljd (.resolveSymbolicLinksSync (dart-io/Directory. dir))
     :clj (.getCanonicalPath (java.io.File. ^String dir))
     :cljs (.realpathSync ^js (js/require "fs") dir)))


(defn- claim!
  "Record `key` as held by this process, answering false when it already
   was — a second owner inside the one process, refused before it opens
   any handle on the lock file (closing such a handle would drop this
   process's `fcntl` lock)."
  [key]
  (let [claimed? (volatile! false)]
    (swap! held (fn [keys]
                  (vreset! claimed? (not (contains? keys key)))
                  (conj keys key)))
    @claimed?))


(defn- host-lock!
  "Take the host's exclusive lock on `path`, answering its release
   function, or refuse naming the directory."
  #_{:clj-kondo/ignore [:unused-binding]}
  [dir path claimed]
  #?(:cljd (let [raf (.openSync (dart-io/File. path)
                                .mode dart-io/FileMode.append)]
             (try
               (.lockSync raf)
               (fn []
                 (try (.unlockSync raf) (catch Object _ nil))
                 (try (.closeSync raf) (catch Object _ nil)))
               (catch Object _
                 (try (.closeSync raf) (catch Object _ nil))
                 (refuse-locked dir))))
     :clj (let [f (java.io.RandomAccessFile. ^String path "rw")]
            (try
              (let [fl (try (.tryLock (.getChannel f))
                            (catch java.nio.channels.OverlappingFileLockException _
                              nil))]
                (if (nil? fl)
                  (do (.close f)
                      (refuse-locked dir))
                  (fn []
                    (try (.release ^java.nio.channels.FileLock fl)
                         (catch Exception _ nil))
                    (try (.close f)
                         (catch Exception _ nil)))))
              (catch Throwable t
                (try (.close f) (catch Exception _ nil))
                (throw t))))
     :cljs (node-lock! dir claimed)))


(defn lock!
  "Acquire the exclusive lock of the durable store directory, or refuse
   naming the directory — whether the other owner is another process or
   this one. The answer is a plain release; closing whatever the host
   needs closed is all releasing takes.

   `claimed`, `(fn [])`, runs on Node once this process's claim entry
   exists and before it reads the others — the window a concurrent
   contender can race — so the tests can interleave one there."
  ([dir] (lock! dir {}))
  ([dir {:keys [claimed]}]
   (let [key (canonical-dir dir)]
     (when-not (claim! key)
       (refuse-locked dir))
     (let [release! (try (host-lock! dir (str dir "/" lock-name) claimed)
                         (catch #?(:cljd Object
                                   :clj Throwable
                                   :cljs :default)
                                e
                           (swap! held disj key)
                           (throw e)))
           released? (volatile! false)]
       {:release! (fn []
                    (when-not @released?
                      (vreset! released? true)
                      (release!)
                      (swap! held disj key)))}))))


(defn unlock!
  "Release a lock `lock!` answered. Idempotent: a released lock releases
   again as a no-op, never a later owner's lock of the same directory."
  [lock]
  (when-let [release! (:release! lock)]
    (release!)))


(defn directory-sync-supported?
  "True when this host can open a directory and fsync it, so a HEAD
   replacement's rename can be made durable, not merely visible: Node
   and the JVM on POSIX systems can; Windows cannot open a directory for
   sync, and Dart's core has no directory sync at all."
  []
  #?(:cljd false
     :clj (not (str/starts-with? (str (System/getProperty "os.name"))
                                 "Windows"))
     :cljs (not= "win32" (.-platform js/process))))


(defn- sync-failure
  [dir e]
  (ex-info (str "cannot sync the directory " dir " after the rename: " e)
           {:dir dir}
           e))


#?(:cljs
   (defn- sync-directory!
     "Sync the directory entry itself where the host supports it — the
      rename is durable, not merely visible.  There, a failure throws: a
      replacement whose directory entry is not known durable is not
      reported done."
     [fs dir]
     (when (directory-sync-supported?)
       (try (let [fd (.openSync ^js fs dir "r")]
              (try (.fsyncSync ^js fs fd)
                   (finally
                     (.closeSync ^js fs fd))))
            (catch :default e
              (throw (sync-failure dir e)))))))


#?(:cljd nil
   :clj
   (defn- sync-directory!
     "Sync the directory entry itself where the host supports it — the
      rename is durable, not merely visible.  There, a failure throws: a
      replacement whose directory entry is not known durable is not
      reported done."
     [dir]
     (when (directory-sync-supported?)
       (try (with-open [ch (java.nio.channels.FileChannel/open
                             (java.nio.file.Paths/get ^String dir
                                                      (make-array String 0))
                             ^"[Ljava.nio.file.OpenOption;"
                             (into-array java.nio.file.OpenOption
                                         [java.nio.file.StandardOpenOption/READ]))]
              (.force ch true))
            (catch Exception e
              (throw (sync-failure dir e)))))))


#?(:cljs
   (defn- write-fully!
     "Write every byte of `buf` to `fd`.  `writeSync` may store fewer
      bytes than asked and says how many in its answer, so the rest is
      written from there until none remain; a write that stores nothing
      fails rather than looping.  (The JVM's `OutputStream.write(byte[])`
      and Dart's `writeAsStringSync` write the whole buffer or throw.)"
     [fs fd buf]
     (loop [offset 0]
       (when (< offset (.-length buf))
         (let [written (.writeSync ^js fs fd buf offset
                                   (- (.-length buf) offset))]
           (when-not (pos? written)
             (throw (ex-info "the write stored no bytes"
                             {:offset offset :length (.-length buf)})))
           (recur (+ offset written)))))))


(defn atomic-replace!
  "Replace `<dir>/<name>` with `text` atomically: write the whole record
   to a temp file beside it, sync the temp, rename it over the target,
   and sync the directory where the host supports it — a failed
   directory sync fails the replacement. A reader observes either the
   whole previous record or the whole new one, never a partial write.

   `before-rename`, `(fn [temp-path])`, runs in the one window a crash
   can interrupt — after the temp is synced, before the rename — so a
   test can interrupt the replacement there; a throw leaves the target
   exactly as it was."
  ([dir name text] (atomic-replace! dir name text {}))
  ([dir name text {:keys [before-rename]}]
   (let [target (str dir "/" name)
         temp (str dir "/" name ".tmp-" (random-uuid))]
     #?(:cljd (try (.writeAsStringSync (dart-io/File. temp) text .flush true)
                   (when before-rename (before-rename temp))
                   (.renameSync (dart-io/File. temp) target)
                   (catch Object e
                     (throw (ex-info (str "cannot replace " target ": " e)
                                     {:dir dir :target target}
                                     e))))
        :clj (try (with-open [out (java.io.FileOutputStream. ^String temp)]
                    (.write out (.getBytes ^String text "UTF-8"))
                    (.flush out)
                    (.sync (.getFD out)))
                  (when before-rename (before-rename temp))
                  (java.nio.file.Files/move
                    (java.nio.file.Paths/get temp (make-array String 0))
                    (java.nio.file.Paths/get target (make-array String 0))
                    (into-array java.nio.file.StandardCopyOption
                                [java.nio.file.StandardCopyOption/ATOMIC_MOVE
                                 java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
                  (sync-directory! dir)
                  (catch Throwable e
                    (throw (ex-info (str "cannot replace " target ": " e)
                                    {:dir dir :target target}
                                    e))))
        :cljs (let [fs (js/require "fs")]
                (try (let [fd (.openSync ^js fs temp "w")]
                       (try (write-fully! fs fd (js/Buffer.from text "utf8"))
                            (.fsyncSync ^js fs fd)
                            (finally
                              (.closeSync ^js fs fd))))
                     (when before-rename (before-rename temp))
                     (.renameSync ^js fs temp target)
                     (sync-directory! fs dir)
                     (catch :default e
                       (throw (ex-info (str "cannot replace " target ": " e)
                                       {:dir dir :target target}
                                       e)))))))))


(defn read-file-text
  "The file `<dir>/<name>` as text, or nil when absent — the public read
   the store's HEAD parsing uses."
  [dir name]
  (read-text! (str dir "/" name)))


(defn- not-regular
  []
  (ex-info "it is not a regular file" {}))


(defn read-bounded
  "At most `limit` bytes of the file at `path`, one more when it holds
   more, or nil when no entry exists there.  Never reads the whole of a
   larger file into memory, and never opens what is not a regular file
   (a directory, a FIFO, a device), which would block or fail: that is
   refused first.  A symlink is followed when its target is a regular
   file; a dangling one is refused.  The bytes are the host's own: a
   `Uint8List` on Dart, a `byte[]` on the JVM, a `Buffer` on Node
   (`byte-length` counts them, `decode-utf8` reads them)."
  [path limit]
  ;; On Dart and the JVM the type is checked, then the file is opened:
  ;; a regular file swapped for a FIFO between the two would still block
  ;; the open.  Accepted (Architect, H3 round 3): the swap needs write
  ;; access to this node's own locked store directory, which can already
  ;; replace HEAD, and the worst outcome is a startup that blocks, never
  ;; a wrong head.  Node opens non-blocking and checks the opened
  ;; descriptor, so it is not exposed.
  #?(:cljd (let [t (.-type (.statSync (dart-io/File. path)))]
             (cond
               (= t dart-io/FileSystemEntityType.notFound)
               ;; a dangling link is an entry, not absence
               (when (.existsSync (dart-io/Link. path))
                 (throw (not-regular)))

               (not= t dart-io/FileSystemEntityType.file)
               (throw (not-regular))

               :else
               (let [raf (.openSync (dart-io/File. path))]
                 (try (.readSync raf (inc limit))
                      (finally (.closeSync raf))))))
     :clj (let [p (java.nio.file.Paths/get path (make-array String 0))]
            (cond
              (java.nio.file.Files/notExists
                p (into-array java.nio.file.LinkOption
                              [java.nio.file.LinkOption/NOFOLLOW_LINKS]))
              nil

              (not (java.nio.file.Files/isRegularFile
                     p (make-array java.nio.file.LinkOption 0)))
              (throw (not-regular))

              :else
              (with-open [in (java.nio.file.Files/newInputStream
                               p (make-array java.nio.file.OpenOption 0))]
                (.readNBytes in (int (inc limit))))))
     :cljs (let [fs (js/require "fs")
                 entry? (try (.lstatSync fs path) true
                             (catch :default _ false))]
             (when entry?
               (when-not (try (.isFile (.statSync fs path))
                              (catch :default _ false))
                 (throw (not-regular)))
               ;; non-blocking, then the opened resource is checked again
               (let [fd (.openSync fs path (bit-or (.. fs -constants -O_RDONLY)
                                                   (.. fs -constants -O_NONBLOCK)))
                     buf (js/Buffer.alloc (inc limit))]
                 (try
                   (when-not (.isFile (.fstatSync fs fd))
                     (throw (not-regular)))
                   (loop [off 0]
                     (let [n (.readSync fs fd buf off (- (inc limit) off) nil)
                           off' (+ off n)]
                       (if (and (pos? n) (< off' (inc limit)))
                         (recur off')
                         (.subarray buf 0 off'))))
                   (finally (.closeSync fs fd))))))))


(defn byte-length
  "The number of bytes `bs`, as `read-bounded` answers them, holds."
  [bs]
  #?(:cljd (.-length ^Uint8List bs)
     :clj (alength ^bytes bs)
     :cljs (.-length bs)))


(defn decode-utf8
  "`bs`, as `read-bounded` answers them, as text, strictly: invalid UTF-8
   throws, never decodes to a replacement character.  A leading byte
   order mark is the host's to keep or drop: a caller that ignores one
   strips it from the text."
  [bs]
  ;; Dart's `utf8` codec does not allow malformed input: it throws a
  ;; FormatException
  #?(:cljd (.decode convert/utf8 ^Uint8List bs)
     :clj (str (.decode (doto (.newDecoder java.nio.charset.StandardCharsets/UTF_8)
                          (.onMalformedInput
                            java.nio.charset.CodingErrorAction/REPORT)
                          (.onUnmappableCharacter
                            java.nio.charset.CodingErrorAction/REPORT))
                        (java.nio.ByteBuffer/wrap ^bytes bs)))
     :cljs (.decode (js/TextDecoder. "utf-8" #js {:fatal true}) bs)))

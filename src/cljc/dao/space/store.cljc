(ns dao.space.store
  "The durable directory store of a covered code index
   (docs/design/yin.repl.dao.space-index.md, \"Durable store\"): one
   directory holding `content.jing` — the `dao.jing.file` content log a
   publisher's blobs materialize into — `HEAD`, the versioned record
   naming the latest published manifest, and `lock`, the exclusive lock.

   `open` is exclusive and validating: the lock (dao.space.store.fs) is
   acquired before anything else in the directory is touched and held
   until `close!`; HEAD is read and the snapshot it names walked in full
   through the opened store (`dao.space.index/read-manifest`, every index
   root), so an absent HEAD is an empty index while a malformed HEAD, a
   missing manifest, or an unreadable index node refuses rather than
   opening empty.  A publisher moves HEAD with `:head-fn` only after its
   manifest reads back, and what the open recovered is `:recovery`.

   It lives in `dao.space` because HEAD names a covered-index manifest and
   opening validates that index; `dao.jing` stays payload-agnostic.  Its
   consumers are `dao.space.dht/join` (`:dir`) and the REPL's `file:<dir>`
   and `dht:<dir>` stores (yin.repl.store)."
  (:require #?@(:cljd [["dart:io" :as dart-io]
                       [clojure.edn :as edn]])
            #?(:cljd nil
               :clj [clojure.edn :as edn]
               :cljs [cljs.reader :as reader])
            [clojure.string :as str]
            [dao.jing :as jing]
            [dao.jing.file :as jing.file]
            [dao.space.index :as dao.index]
            [dao.space.store.fs :as fs]))


(def content-name
  "The content log's own file name inside a durable store directory."
  "content.jing")


(def head-name
  "The durable root pointer's own file name inside a durable store
   directory: the versioned record naming the latest published
   manifest."
  "HEAD")


(def head-version
  "The HEAD record version this store writes and accepts."
  1)


(def unsupported-host-text
  "The refusal for a durable directory on a host build that cannot open
   files.  Refusing is the contract: a build without a filesystem is never
   answered with an in-memory store its caller did not choose."
  (str "a durable store directory is not supported on this host: opening "
       "the content log needs a filesystem, and this build has none; use "
       "an in-memory store, or run on a host with file support (the JVM, "
       "Node, or Dart)"))


(defn host-file-support
  "True when this build can open a file-backed store: the JVM, Node, and
   Dart can; a CLJS build without `js/require` cannot."
  []
  #?(:cljd true
     :cljs (exists? js/require)
     :clj true))


(defn- without-trailing-separator
  "The directory path with any trailing `/` removed, so the content log's
   path is joined exactly once.  A path of only separators names the root,
   which is preserved."
  [dir]
  (loop [s dir]
    (if (and (pos? (count s)) (= "/" (subs s (dec (count s)))))
      (recur (subs s 0 (dec (count s))))
      s)))


(defn content-path
  "The content log's own path inside the store directory:
   `<dir>/content.jing`."
  [dir]
  (let [base (without-trailing-separator dir)]
    (if (str/blank? base)
      (str "/" content-name)
      (str base "/" content-name))))


(defn- error-text
  "The host error's own message, for the tail of a refusal."
  [e]
  #?(:cljd (str e)
     :clj (or (ex-message e) (str e))
     :cljs (or (ex-message e) (str e))))


(defn- dir-refusal
  "Why `dir` cannot hold the durable content log, or nil when it can: a
   path occupied by something that is not a directory, a directory this
   process cannot write, or a directory that cannot be created.  Nothing
   is opened here — the content log itself is opened later, and its open
   failure is refused with its own reason."
  [dir]
  #?(:cljd (let [d (dart-io/Directory. dir)
                 f (dart-io/File. dir)]
             (cond
               (.existsSync d) nil
               (.existsSync f) (str dir " is not a directory")
               :else (try (.createSync d .recursive true)
                          nil
                          (catch Object e
                            (str "cannot create " dir ": " (error-text e))))))
     :clj (let [f (java.io.File. dir)]
            (cond
              (and (.exists f) (not (.isDirectory f)))
              (str dir " is not a directory")

              (and (.isDirectory f) (not (.canWrite f)))
              (str dir " is not writable")

              :else
              (try (when (and (not (.isDirectory f)) (not (.mkdirs f)))
                     (str "cannot create " dir))
                   (catch Exception e
                     (str "cannot create " dir ": " (error-text e))))))
     :cljs (let [fs (js/require "fs")]
             (if (.existsSync fs dir)
               (when-not (.isDirectory (.statSync fs dir))
                 (str dir " is not a directory"))
               (try (.mkdirSync fs dir #js {:recursive true})
                    nil
                    (catch :default e
                      (str "cannot create " dir ": " (error-text e))))))))


(defn file-refusal
  "Why a `file:<dir>` store cannot open, or nil when it can: a host without
   file support is named first — never answered with a memory fallback —
   then the directory's own refusal.  The one-argument arity asks this
   build's own capability; the two-argument one answers for the capability
   it is handed, which is how the unsupported-host refusal is tested on
   hosts that do have file support."
  ([dir] (file-refusal (host-file-support) dir))
  ([capable? dir]
   (or (when-not capable? unsupported-host-text)
       (dir-refusal dir))))


(defn- head-record
  "The text of one HEAD record: the version and the manifest address,
   written whole and replaced whole."
  [manifest-address]
  (str (pr-str {:version head-version :manifest manifest-address}) "\n"))


(defn- read-edn
  [text]
  #?(:cljd (edn/read-string text)
     :clj (edn/read-string text)
     :cljs (reader/read-string text)))


(defn- recovered-head
  "The manifest address HEAD names, or nil for an empty index.  A missing
   file is an empty index; a file that is not exactly the versioned
   record naming a content address is a corrupt pointer, and a corrupt
   pointer refuses startup rather than pretending there was no index."
  [dir]
  (if-some [text (fs/read-file-text dir head-name)]
    (let [head (try (read-edn text)
                    (catch #?(:cljd Object
                              :clj Exception
                              :cljs :default)
                           e
                      (throw (ex-info (str "the HEAD at " dir
                                           " is not a readable record: "
                                           (error-text e))
                                      {:dir dir :head text}
                                      e))))]
      (when-not (and (map? head)
                     (= head-version (:version head))
                     (jing/segment-address? (:manifest head)))
        (throw (ex-info (str "the HEAD at " dir " is not a version-"
                             head-version
                             " record naming a manifest address")
                        {:dir dir :head head})))
      (:manifest head))
    nil))


(defn- validated-snapshot
  "The datoms of the snapshot HEAD names, walked eagerly through the
   opened store — the design's full read-manifest and read-datoms
   traversal, over every index root the manifest names, not only EAVT:
   each of the four trees must read back whole and cover exactly the
   manifest's `:count` datoms.  A missing or invalid manifest, or any
   unreadable index node, refuses startup here; never a silent empty
   index."
  [store dir manifest-address]
  (try
    (let [manifest (dao.index/read-manifest store manifest-address)
          walked (into {}
                       (map (fn [[index root]]
                              [index (vec (dao.index/walk-index-datoms
                                            store root))]))
                       (:indexes manifest))]
      (doseq [[index datoms] walked]
        (when-not (= (:count manifest) (count datoms))
          (throw (ex-info (str "the " (name index) " index covers "
                               (count datoms) " datoms, not the manifest's "
                               (:count manifest))
                          {:index index}))))
      (:eavt walked))
    (catch #?(:cljd Object
              :clj Throwable
              :cljs :default)
           e
      (throw (ex-info (str "the durable index at " dir " is corrupt: "
                           (error-text e))
                      {:dir dir :manifest manifest-address}
                      e)))))


(defn- open-locked-dir
  "Open the durable directory as its one owner: take the lock before
   anything else in the directory is touched, then open the content log,
   read HEAD, and walk the snapshot it names.  Every failure after the
   lock closes the content log it opened and releases the lock again, so
   a refused startup never leaves this process holding a directory it
   did not open."
  [dir]
  (let [lock (fs/lock! dir)
        opened (volatile! nil)]
    (try
      (let [handle (try (jing.file/create-content-file (content-path dir))
                        (catch #?(:cljd Object
                                  :clj Exception
                                  :cljs :default)
                               e
                          (throw (ex-info (str "cannot open the index store at "
                                               (content-path dir) ": "
                                               (error-text e))
                                          {:dir dir}
                                          e))))
            _ (vreset! opened handle)
            manifest (recovered-head dir)
            datoms (when manifest
                     (validated-snapshot handle dir manifest))]
        {:handle handle
         :lock lock
         :recovery {:manifest manifest
                    :datoms datoms}})
      (catch #?(:cljd Object
                :clj Throwable
                :cljs :default)
             e
        (when-some [close-content! (:close-fn @opened)]
          (try (close-content!)
               (catch #?(:cljd Object
                         :clj Throwable
                         :cljs :default)
                      _
                 nil)))
        (fs/unlock! lock)
        (throw e)))))


(defn lock-releasing-close
  "The durable handle's `:close-fn`: close the content log, then release
   the directory lock `lock` — released even when the close throws, so a
   failed close never keeps a live process holding the directory."
  [close-content! lock]
  (fn []
    (try
      (close-content!)
      (finally
        (fs/unlock! lock)))))


(defn open
  "Open `dir` as a durable directory store, exclusively and validating:
   the directory lock is acquired before the content log or HEAD is
   touched — a second owner, in this process or another, is refused
   naming the directory — and every failure after it releases the lock
   again.  HEAD is read and the snapshot it names walked in full: an
   absent HEAD is an empty index; a malformed HEAD or a corrupt snapshot
   refuses rather than opening empty.  A directory this host cannot open
   is refused with its reason.

   The handle is the content log's plain byte-store fns plus what its
   lifecycle owns: `:close-fn`, which releases the lock with the content
   log; `:head-fn`, `(fn [manifest-address])`, the atomic HEAD write a
   publisher performs after its manifest reads back; `:recovery`,
   `{:manifest <address or nil> :datoms <the walked snapshot or nil>}`;
   and `:durable-dir`."
  [dir]
  (if-some [refusal (file-refusal dir)]
    (throw (ex-info refusal {:dir dir}))
    (let [{:keys [handle lock recovery]} (open-locked-dir dir)]
      (assoc handle
             :close-fn (lock-releasing-close (:close-fn handle) lock)
             :head-fn (fn publish-head!
                        [manifest-address]
                        (fs/atomic-replace!
                          dir head-name
                          (head-record manifest-address)))
             :recovery recovery
             :durable-dir dir))))


(defn durable?
  "True when `store` is a durable directory store `open` answered (or a
   handle composed over one, carrying its `:durable-dir`): its published
   index outlives the process."
  [store]
  (some? (:durable-dir store)))


(defn close!
  "Release a store's lifecycle resources — its content log handle and,
   for a durable directory, the directory lock.  Nothing further can be read or
   written through the handle afterwards.  Idempotent."
  [store]
  (when-let [close! (:close-fn store)]
    (close!)))

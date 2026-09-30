(ns yin.repl.store
  "Startup selection of the code index's store
   (docs/design/yin.repl.dao.space-index.md; the durable-store startup
   contract, slice 1).

   The store is chosen once, before the shell composes: `mem` — a fresh
   in-memory `dao.jing` store, today's behaviour — or `file:<dir>`, the
   durable `dao.jing.file` content log at `<dir>/content.jing`.  This
   namespace parses the `--index-store` value, validates the
   `:index-store-spec` an embedder may hand `yin.repl/create-state`, and
   opens the store the resolved spec names.  Every bad spec — a missing
   value, an unknown scheme, an empty directory, a directory that cannot
   be opened, a host build without file support — is refused with its
   reason; nothing falls back to memory silently.

   Restart recovery is not this slice.  A file store opened here receives
   a session's publications exactly as the memory store does, and a later
   process reopening the same directory appends to the same log, but no
   HEAD pointer, directory lock, or index rehydration exists yet, so
   nothing answers `q` from a previous run's facts (slices 2-3)."
  (:require #?@(:cljd [["dart:io" :as dart-io]])
            [clojure.string :as str]
            [dao.jing.file :as jing.file]
            [dao.jing.mem :as jing.mem]))


(def file-scheme
  "The `--index-store` scheme prefix naming a durable file store."
  "file:")


(def content-name
  "The content log's own file name inside a durable store directory."
  "content.jing")


(def unsupported-host-text
  "The refusal for `file:<dir>` on a host build that cannot open files.
   Refusing is the contract: a build without a filesystem must never be
   answered with an in-memory store the operator did not choose."
  (str "--index-store file:<dir> is not supported on this host: opening "
       "the content log needs a filesystem, and this build has none.  "
       "Start without the flag — the in-memory store is the default — or "
       "run on a host with file support (the JVM, Node, or Dart)."))


(defn parse-arg
  "The `--index-store` value as a spec: `:mem`, or `{:type :file :dir dir}`
   for `file:<dir>`.  A missing value, an unknown scheme, and an empty
   directory are refused here — with the supported forms named — so the
   arguments parser can refuse a bad flag before anything composes."
  [value]
  (cond
    (nil? value)
    (throw (ex-info "--index-store needs a value: mem or file:<dir>"
                    {:supported ["mem" (str file-scheme "<dir>")]}))

    (= value "mem")
    :mem

    (str/starts-with? value file-scheme)
    (let [dir (subs value (count file-scheme))]
      (if (str/blank? dir)
        (throw (ex-info "--index-store file:<dir> needs a directory"
                        {:value value
                         :supported ["mem" (str file-scheme "<dir>")]}))
        {:type :file :dir dir}))

    :else
    (throw (ex-info (str "Unknown --index-store " (pr-str value)
                         "; supported: mem, file:<dir>")
                    {:value value
                     :supported ["mem" (str file-scheme "<dir>")]}))))


(defn checked-spec
  "Validate an `:index-store-spec` as `yin.repl/create-state` receives it:
   nil means the `mem` default, `:mem` names it, and a file store is the
   map `{:type :file :dir dir}` with a non-empty directory string.
   Anything else is refused with the supported forms named."
  [spec]
  (cond
    (nil? spec) :mem
    (= :mem spec) :mem
    (and (map? spec)
         (= :file (:type spec))
         (string? (:dir spec))
         (not (str/blank? (:dir spec))))
    spec
    :else
    (throw (ex-info (str "Unknown :index-store-spec " (pr-str spec)
                         "; supported: :mem, {:type :file :dir <dir>}")
                    {:spec spec
                     :supported [:mem {:type :file :dir "<dir>"}]}))))


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


(defn open
  "Open the store `spec` names and keep it plain: `:mem` (or nil) is a
   fresh in-memory `dao.jing` store; `{:type :file :dir dir}` is the
   durable content log at `<dir>/content.jing`, opened once here and
   carried by the shell for its lifetime.  A spec this build cannot open
   is refused with its reason."
  [spec]
  (let [checked (checked-spec spec)]
    (if (= :mem checked)
      (jing.mem/create-content-mem)
      (let [dir (:dir checked)]
        (if-some [refusal (file-refusal dir)]
          (throw (ex-info refusal {:dir dir}))
          (try (jing.file/create-content-file (content-path dir))
               (catch #?(:cljd Object
                         :clj Exception
                         :cljs :default)
                      e
                 (throw (ex-info (str "cannot open the index store at "
                                      (content-path dir) ": " (error-text e))
                                 {:dir dir}
                                 e)))))))))

(ns yin.repl.state
  "The node's saved configuration: the command line a node was last started
   with, kept as EDN in `<node dir>/state.edn` so a bare `yin-repl` starts
   the same node again.

   The file holds flags, not evaluation state: the index store's durable
   directory already recovers what was evaluated.  A run resolves
   `saved flags < command-line flags` (a repeated flag's values replace the
   saved ones whole), starts from the result, and saves it.

   Which flags a state file keeps is a table, not a list this namespace
   hardcodes: `flag-table` answers the engine's own, and a composition
   extends it with the flags it parses.  A composition's one-shot flags —
   the ones it reads and never persists — travel in the same table.  This
   namespace therefore knows the shape of a saved flag and how to read,
   resolve, describe and write one, and knows nothing about what any
   particular flag means (docs/design/yin.repl.dht-composition.md section
   6: saved configuration moves to composition, and the engine's trust
   commands must not import CLI state)."
  (:require #?(:cljd [clojure.edn :as edn]
               :clj [clojure.edn :as edn]
               :cljs [cljs.reader :as reader])
            #?@(:cljd [["dart:io" :as dart-io]]
                :jolt [[dao.host.text :as text]
                       [dao.posix.file :as posix-file]])
            [clojure.string :as str]
            [dao.space.store.fs :as fs]
            [yin.vm.linker.sign :as sign]))


(defn parse-trust
  "A trust-configuration value: a principal whose modules this node
   admits, `ed25519:<64 lowercase hex>` (the tag optional), as the
   principal id.  Trust is apart from declaring a publisher: honoring a
   publisher's signed names does not admit its modules.

   This is the neutral principal parse the signing and admission layer
   owns, apart from any command line, so the engine's trust commands
   reach it without importing CLI state."
  [text]
  (if-some [[_ hex] (and (string? text)
                         (re-matches #"^(?:ed25519:)?([0-9a-f]{64})$" text))]
    (sign/principal hex)
    (throw (ex-info (str "invalid principal id: expected ed25519:<64 lowercase "
                         "hexadecimal characters>, not " (pr-str text))
                    {:value text}))))


(def file-name "state.edn")


(def version 1)


(def ^:private engine-saved-flags
  "The flags a state file keeps for the engine's own command line, in the
   order they are re-emitted, and how each takes its value: one value, a
   repeated value, or a bare switch."
  [["--index-store" :value]
   ["--vm" :value]
   ["--port" :value]
   ["--codec" :value]
   ["--headless" :switch]])


(def ^:private engine-kinds (into {} engine-saved-flags))


(def ^:private engine-one-shot-flags
  "The engine's own flags that take a value but are never saved: none."
  #{})


(defn flag-table
  "The saved-flag table the reader, resolver, describer and writer share:
   `{:saved-flags [[flag kind] ...] :kinds {flag kind} :one-shot-flags
   #{flag ...}}`, the flags in the order they are re-emitted.

   `extra` is a composition's own contribution, `{:saved-flags [[flag
   kind] ...] :one-shot-flags #{flag ...}}`; its flags are appended to the
   engine's, so a composition's flags are saved and resumed beside them
   and a run of the engine alone is unchanged.  A flag named in both keeps
   the engine's kind.

   The no-argument table is the engine's own and nothing else.  This
   namespace resolves no composition — not by require, not by symbol — so
   the table it answers cannot vary with what else a build happens to
   carry, and a composition hands its table in (yin.repl.main/startup's
   `:flag-table`, yin.shell.cli/flag-table) instead of being discovered
   from here."
  ([] (flag-table nil))
  ([{:keys [saved-flags one-shot-flags]}]
   (let [saved (into engine-saved-flags
                     (remove #(contains? engine-kinds (first %)))
                     saved-flags)]
     {:saved-flags saved
      :kinds (into {} saved)
      :one-shot-flags (into engine-one-shot-flags one-shot-flags)})))


(defn- value-of
  "The value after `flag`, or a refusal: a missing value (or another flag
   in its place) must never fall back to the saved one."
  [flag more]
  (let [value (first more)]
    (if (and (some? value) (not (str/starts-with? value "--")))
      value
      (throw (ex-info (str flag " needs a value") {:flag flag})))))


(defn split-args
  "Split `args` into `{:flags {flag value} :rest [arg ...]}`: the flags a
   state file keeps (a switch is `true`, a repeated flag a vector) and
   everything else, in order, for the arguments parser.  A saved flag or a
   one-shot flag with no value is refused here, before it can be resolved
   against the saved value.

   `table` is the `flag-table` the flags are read against; the default is
   the engine's own."
  ([args] (split-args args nil))
  ([args table]
   (let [{:keys [kinds one-shot-flags]} (or table (flag-table))]
     (loop [args (seq args) flags {} rest []]
       (if-let [arg (first args)]
         (let [kind (kinds arg)
               more (next args)]
           (cond
             (= :switch kind) (recur more (assoc flags arg true) rest)

             (#{:value :many} kind)
             (let [value (value-of arg more)]
               (recur (next more)
                      (if (= :many kind)
                        (update flags arg (fnil conj []) value)
                        (assoc flags arg value))
                      rest))

             (one-shot-flags arg)
             (recur (next more) flags (conj rest arg (value-of arg more)))

             :else (recur more flags (conj rest arg))))
         {:flags flags :rest rest})))))


(defn flags->args
  "The argument vector that says `flags`, in the table's fixed order."
  ([flags] (flags->args flags nil))
  ([flags table]
   (into []
         (mapcat (fn [[flag kind]]
                   (when-some [v (get flags flag)]
                     (case kind
                       :switch (when (true? v) [flag])
                       :value [flag v]
                       :many (mapcat (fn [x] [flag x]) v)))))
         (:saved-flags (or table (flag-table))))))


(defn resolve-flags
  "The flags this run starts with: `saved` overridden by `explicit`.  The
   `unset` flags a subcommand clears are dropped from `saved` only, so a
   flag the command line names explicitly always stands."
  [saved explicit unset]
  (merge (apply dissoc saved unset) explicit))


(defn changed
  "The flags, sorted, whose value in `resolved` differs from `saved`."
  [saved resolved]
  (->> (concat (keys saved) (keys resolved))
       distinct
       (remove #(= (get saved %) (get resolved %)))
       sort
       vec))


(defn describe
  "`flags` as one line of `--flag value` text, in the table's order."
  ([flags] (describe flags nil))
  ([flags table]
   (str/join ", "
             (for [[flag _] (:saved-flags (or table (flag-table)))
                   :let [v (get flags flag)]
                   :when (some? v)]
               (cond
                 (true? v) flag
                 (vector? v) (str/join ", " (map #(str flag " " %) v))
                 :else (str flag " " v))))))


(defn- config-key
  [flag]
  (keyword (subs flag 2)))


(defn- refuse
  [path why]
  (ex-info (str "state file " path ": " why "; --reset replaces it")
           {:path path}))


(defn- text->config
  "The flags a state file's text holds, or a refusal naming `path`.  A
   setting the table does not name is refused, so a state file written by
   a composition this build does not carry is never silently dropped."
  [path text table]
  (let [{:keys [kinds]} table
        data (try #?(:cljd (edn/read-string text)
                     :clj (edn/read-string text)
                     :cljs (reader/read-string text))
                  (catch #?(:cljd Object
                            :clj Throwable
                            :cljs :default)
                         _
                    (throw (refuse path "it is not readable EDN"))))
        config (when (map? data) (:config data))]
    (cond
      (not (and (map? data) (= version (:version data))))
      (throw (refuse path (str "it is not a version-" version " state file")))

      (not (map? config))
      (throw (refuse path "it has no :config map"))

      :else
      (reduce (fn [flags [k v]]
                (let [kind (when (keyword? k) (kinds (str "--" (name k))))
                      flag (when kind (str "--" (name k)))]
                  (cond
                    (nil? kind)
                    (throw (refuse path (str "unknown setting " (pr-str k))))

                    (and (= :switch kind) (not (true? v)))
                    (throw (refuse path (str (pr-str k) " must be true")))

                    (and (= :value kind) (not (string? v)))
                    (throw (refuse path (str (pr-str k) " must be a string")))

                    (and (= :many kind)
                         (not (and (vector? v) (every? string? v))))
                    (throw (refuse path (str (pr-str k) " must be a vector of"
                                             " strings")))

                    :else (assoc flags flag v))))
              {}
              config))))


(defn path
  "The state file's path in the node directory `dir`."
  [dir]
  (str dir "/" file-name))


(defn- read-text
  "The file at `path` as text, or nil only when no directory entry exists
   there.  Unlike `fs/read-file-text`, which answers nil for anything it
   cannot stat, a directory, an unreadable file and a dangling symlink all
   throw here, so a state file is never skipped for being unreadable.  Each
   host tells absence from an access failure by the error it gets on a
   direct read, not by an existence test."
  [path]
  #?(:cljd (try (.readAsStringSync (dart-io/File. path))
                (catch dart-io/PathNotFoundException e
                  ;; Dart also raises this class for Windows errors that are
                  ;; not absence (bad drive, bad name, name too long): only
                  ;; OS error 2 (ENOENT, file not found) and 3 (path not
                  ;; found) are.  A link whose target is missing is an entry,
                  ;; not absence.
                  (let [code (some-> (.-osError e) .-errorCode)]
                    (when (or (not (contains? #{2 3} code))
                              (.existsSync (dart-io/Link. path)))
                      (throw e)))))
     ;; lstat decides absence; a dangling link (an entry whose target is
     ;; missing), a non-regular entry, an unreadable file or invalid UTF-8
     ;; all throw
     :jolt (when-not (= :absent (posix-file/classify path false))
             (let [bs (posix-file/read-all path)]
               (case bs
                 :absent (throw (ex-info (str path " is a dangling symlink")
                                         {:path path}))
                 :not-regular (throw (ex-info (str path " is not a regular file")
                                              {:path path}))
                 (text/utf8-decode bs))))
     :clj (when-not (java.nio.file.Files/notExists
                      (java.nio.file.Paths/get path (make-array String 0))
                      (into-array java.nio.file.LinkOption
                                  [java.nio.file.LinkOption/NOFOLLOW_LINKS]))
            (slurp path))
     :cljs (let [fs (js/require "fs")
                 entry? (fn []
                          (try (.lstatSync ^js fs path) true
                               (catch :default e
                                 ;; only "no such entry" means no entry; an
                                 ;; access or I/O failure must not read as one
                                 (if (= "ENOENT" (.-code e)) false (throw e)))))]
             (try (.readFileSync ^js fs path "utf8")
                  (catch :default e
                    (when-not (and (= "ENOENT" (.-code e)) (not (entry?)))
                      (throw e)))))))


(defn load-flags
  "The saved flags in the node directory `dir`, or nil when there is no
   state file.  A file that cannot be understood or read is refused, never
   skipped.  `table` is the `flag-table` the settings are read against."
  ([dir] (load-flags dir nil))
  ([dir table]
   (when-some [text (try (read-text (path dir))
                         (catch #?(:cljd Object
                                   :clj Throwable
                                   :cljs :default)
                                e
                           (throw (refuse (path dir)
                                          (str "it cannot be read ("
                                               (or (ex-message e) (str e))
                                               ")")))))]
     (text->config (path dir) text (or table (flag-table))))))


(defn- mkdirs!
  [dir]
  #?(:cljd (.createSync (dart-io/Directory. dir) .recursive true)
     :clj (.mkdirs (java.io.File. ^String dir))
     :cljs (.mkdirSync (js/require "fs") dir #js {:recursive true})))


(defn- render
  "The state file's text, one setting to a line, with no space before a
   closing bracket (ClojureDart's EDN reader refuses one)."
  [flags table]
  (let [entries (for [[flag _] (:saved-flags table)
                      :let [v (get flags flag)]
                      :when (some? v)]
                  (str (config-key flag) " " (pr-str v)))]
    (str "{:version " version "\n :config {"
         (str/join "\n          " entries)
         "}}\n")))


(defn save!
  "Write `flags` as the state file of the node directory `dir`, creating the
   directory and replacing any earlier file atomically.  `table` is the
   `flag-table` whose order the settings are written in."
  ([dir flags] (save! dir flags nil))
  ([dir flags table]
   (mkdirs! dir)
   (fs/atomic-replace! dir file-name (render flags (or table (flag-table))))))

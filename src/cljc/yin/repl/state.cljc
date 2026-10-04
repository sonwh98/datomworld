(ns yin.repl.state
  "The node's saved configuration: the command line a node was last started
   with, kept as EDN in `<node dir>/state.edn` so a bare `yin-repl` starts
   the same node again.

   The file holds flags, not evaluation state: the index store's durable
   directory already recovers what was evaluated.  A run resolves
   `saved flags < command-line flags` (a repeated flag's values replace the
   saved ones whole), starts from the result, and saves it.  One-shot flags
   (`--dht-manifest`, `--dht-keygen`) are never saved."
  (:require #?(:cljd [clojure.edn :as edn]
               :clj [clojure.edn :as edn]
               :cljs [cljs.reader :as reader])
            #?@(:cljd [["dart:io" :as dart-io]])
            [clojure.string :as str]
            [dao.space.store.fs :as fs]))


(def file-name "state.edn")


(def version 1)


(def ^:private saved-flags
  "The flags a state file keeps, in the order they are re-emitted, and how
   each takes its value: one value, a repeated value, or a bare switch."
  [["--index-store" :value]
   ["--vm" :value]
   ["--port" :value]
   ["--headless" :switch]
   ["--dht-peer" :many]
   ["--dht-publish" :switch]
   ["--dht-bind" :value]
   ["--dht-port" :value]
   ["--dht-max-inbound-bytes" :value]
   ["--dht-key" :value]
   ["--dht-principal" :many]])


(def ^:private kinds (into {} saved-flags))


(def ^:private one-shot-flags
  "Flags that take a value but are never saved."
  #{"--dht-manifest" "--dht-keygen"})


(defn split-args
  "Split `args` into `{:flags {flag value} :rest [arg ...]}`: the flags a
   state file keeps (a switch is `true`, a repeated flag a vector) and
   everything else, in order, for the arguments parser.  A valued flag with
   no value stays in `:rest` so the parser refuses it as it always has."
  [args]
  (loop [args (seq args) flags {} rest []]
    (if-let [arg (first args)]
      (let [kind (kinds arg)
            more (next args)]
        (cond
          (= :switch kind) (recur more (assoc flags arg true) rest)

          (and (#{:value :many} kind) (seq more))
          (let [value (first more)]
            (recur (next more)
                   (if (= :many kind)
                     (update flags arg (fnil conj []) value)
                     (assoc flags arg value))
                   rest))

          (and (one-shot-flags arg) (seq more))
          (recur (next more) flags (conj rest arg (first more)))

          :else (recur more flags (conj rest arg))))
      {:flags flags :rest rest})))


(defn flags->args
  "The argument vector that says `flags`, in a fixed order."
  [flags]
  (into []
        (mapcat (fn [[flag kind]]
                  (when-some [v (get flags flag)]
                    (case kind
                      :switch (when (true? v) [flag])
                      :value [flag v]
                      :many (mapcat (fn [x] [flag x]) v)))))
        saved-flags))


(defn resolve-flags
  "The flags this run starts with: `saved` overridden by `explicit`, minus
   the `unset` flags a subcommand clears (`dht serve` is never publishing,
   whatever was saved)."
  [saved explicit unset]
  (apply dissoc (merge saved explicit) unset))


(defn changed
  "The flags, sorted, whose value in `resolved` differs from `saved`."
  [saved resolved]
  (->> (concat (keys saved) (keys resolved))
       distinct
       (remove #(= (get saved %) (get resolved %)))
       sort
       vec))


(defn describe
  "`flags` as one line of `--flag value` text."
  [flags]
  (str/join ", "
            (for [[flag _] saved-flags
                  :let [v (get flags flag)]
                  :when (some? v)]
              (cond
                (true? v) flag
                (vector? v) (str/join ", " (map #(str flag " " %) v))
                :else (str flag " " v)))))


(defn- config-key
  [flag]
  (keyword (subs flag 2)))


(defn- refuse
  [path why]
  (ex-info (str "state file " path ": " why "; --reset replaces it")
           {:path path}))


(defn- text->config
  "The flags a state file's text holds, or a refusal naming `path`."
  [path text]
  (let [data (try #?(:cljd (edn/read-string text)
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
                (let [flag (str "--" (name k))
                      kind (kinds flag)]
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


(defn load-flags
  "The saved flags in the node directory `dir`, or nil when there is no
   state file.  A file that cannot be understood is refused, never
   skipped."
  [dir]
  (when-some [text (fs/read-file-text dir file-name)]
    (text->config (path dir) text)))


(defn- mkdirs!
  [dir]
  #?(:cljd (.createSync (dart-io/Directory. dir) .recursive true)
     :clj (.mkdirs (java.io.File. ^String dir))
     :cljs (.mkdirSync (js/require "fs") dir #js {:recursive true})))


(defn- render
  "The state file's text, one setting to a line, with no space before a
   closing bracket (ClojureDart's EDN reader refuses one)."
  [flags]
  (let [entries (for [[flag _] saved-flags
                      :let [v (get flags flag)]
                      :when (some? v)]
                  (str (config-key flag) " " (pr-str v)))]
    (str "{:version " version "\n :config {"
         (str/join "\n          " entries)
         "}}\n")))


(defn save!
  "Write `flags` as the state file of the node directory `dir`, creating the
   directory and replacing any earlier file atomically."
  [dir flags]
  (mkdirs! dir)
  (fs/atomic-replace! dir file-name (render flags)))

(ns yin.repl.store
  "Startup selection of the code index's store
   (docs/design/yin.repl.dao.space-index.md; the durable-store startup
   contract, slices 1-3, and the DHT store of epic S5).

   The store is chosen once, before the shell composes: `mem` — a fresh
   in-memory `dao.jing` store, today's behaviour — `file:<dir>`, the
   durable directory store (dao.space.store), or `dht:<dir>`, that store
   with a DHT node composed over it (yin.repl.dht).  This namespace parses
   the `--index-store` value, validates the `:index-store-spec` an
   embedder may hand `yin.repl/create-state`, and opens the store the
   resolved spec names.  Every bad spec — a missing value, an unknown
   scheme, an empty directory, a directory that cannot be opened, a host
   build without file support — is refused with its reason; nothing falls
   back to memory silently.

   The durable directory itself — the exclusive lock, HEAD, the validated
   snapshot, `:recovery` — is dao.space.store's; this namespace is one of
   its consumers.  The shell installs the recovery before it admits any
   evaluation (`yin.repl.index/rehydrate`), and a durable store
   (`durable?`) also carries the index across `(reset)` and VM
   selection; the memory store keeps today's empty rebuild."
  (:require [clojure.string :as str]
            [dao.jing :as jing]
            [dao.jing.mem :as jing.mem]
            [dao.space.store :as durable]
            [dao.stream.datagram :as datagram]))


(def file-scheme
  "The `--index-store` scheme prefix naming a durable file store."
  "file:")


(def dht-scheme
  "The `--index-store` scheme prefix naming the DHT store: the durable
   directory store of `file:<dir>` composed under a `dao.jing.dht` node
   (yin.repl.dht)."
  "dht:")


(def ^:private supported-args
  ["mem" (str file-scheme "<dir>") (str dht-scheme "<dir>")])


(defn parse-arg
  "The `--index-store` value as a spec: `:mem`, or `{:type :file :dir dir}`
   for `file:<dir>`.  A missing value, an unknown scheme, and an empty
   directory are refused here — with the supported forms named — so the
   arguments parser can refuse a bad flag before anything composes."
  [value]
  (cond
    (nil? value)
    (throw (ex-info "--index-store needs a value: mem, file:<dir> or dht:<dir>"
                    {:supported supported-args}))

    (= value "mem")
    :mem

    (str/starts-with? value file-scheme)
    (let [dir (subs value (count file-scheme))]
      (if (str/blank? dir)
        (throw (ex-info "--index-store file:<dir> needs a directory"
                        {:value value
                         :supported supported-args}))
        {:type :file :dir dir}))

    (str/starts-with? value dht-scheme)
    (let [dir (subs value (count dht-scheme))]
      (if (str/blank? dir)
        (throw (ex-info "--index-store dht:<dir> needs a directory"
                        {:value value
                         :supported supported-args}))
        {:type :dht :dir dir}))

    :else
    (throw (ex-info (str "Unknown --index-store " (pr-str value)
                         "; supported: mem, file:<dir>, dht:<dir>")
                    {:value value
                     :supported supported-args}))))


(defn- dht-refusal
  "Why a `{:type :dht ...}` spec is not a DHT store composition, or nil.
   A spec with no peers is solo: it opens no socket, so a manifest to
   fetch has nothing to fetch through and is refused rather than
   ignored."
  [{:keys [dir peers publish? bind-host bind-port max-inbound-bytes manifest
           bind!]}]
  (cond
    (not (and (string? dir) (not (str/blank? dir))))
    "--index-store dht:<dir> needs a directory"

    (not (and (vector? peers)
              (every? #(and (map? %)
                            (datagram/ip-literal? (:host %))
                            (datagram/valid-port? (:port %)))
                      peers)))
    "DHT peers must be {:host <IP literal> :port <1 to 65535>}"

    (not (boolean? publish?))
    "the DHT publication declaration must be true or false"

    (not (datagram/ip-literal? bind-host))
    "the DHT bind host must be an IP literal"

    (not (and (integer? bind-port) (<= 0 bind-port 65535)))
    "the DHT bind port must be 0 (ephemeral) or 1 to 65535"

    (not (and (integer? max-inbound-bytes) (<= 0 max-inbound-bytes)))
    "--dht-max-inbound-bytes must be a nonnegative integer"

    (and (empty? peers) (some? manifest))
    (str "--dht-manifest needs a --dht-peer to fetch from: with no peers "
         "the DHT store is solo and opens no socket")

    (not (or (nil? manifest) (jing/segment-address? manifest)))
    "--dht-manifest must be a segment manifest address"

    (not (or (nil? bind!) (fn? bind!)))
    ":bind! must be a dao.stream.datagram host seam function"))


(def default-max-inbound-bytes
  "The CLI default of the DHT store's inbound storage bound
   (`:dao.jing.dht/max-inbound-bytes`): 64 MiB of payload a publishing
   node accepts from peers' `:store` requests before it refuses them
   explicitly.  `--dht-max-inbound-bytes` overrides it."
  (* 64 1024 1024))


(def dht-defaults
  "The DHT store spec's defaults: solo (no peers), publishing off, bound
   to loopback on an ephemeral port, the default inbound bound, and no
   manifest to hydrate."
  {:peers []
   :publish? false
   :bind-host "127.0.0.1"
   :bind-port 0
   :max-inbound-bytes default-max-inbound-bytes
   :manifest nil})


(defn checked-spec
  "Validate an `:index-store-spec` as `yin.repl/create-state` receives it:
   nil means the `mem` default, `:mem` names it, and a file store is the
   map `{:type :file :dir dir}` with a non-empty directory string.  A DHT
   store is `{:type :dht :dir dir}` plus its options, `dht-defaults`
   filled in; see yin.repl.dht.  Anything else is refused with the
   supported forms named."
  [spec]
  (cond
    (nil? spec) :mem
    (= :mem spec) :mem
    (and (map? spec)
         (= :file (:type spec))
         (string? (:dir spec))
         (not (str/blank? (:dir spec))))
    spec

    (and (map? spec) (= :dht (:type spec)))
    (let [spec (merge dht-defaults spec)]
      (if-some [refusal (dht-refusal spec)]
        (throw (ex-info refusal {:spec (dissoc spec :bind!)}))
        spec))

    :else
    (throw (ex-info (str "Unknown :index-store-spec " (pr-str spec)
                         "; supported: :mem, {:type :file :dir <dir>}, "
                         "{:type :dht :dir <dir> ...}")
                    {:spec spec
                     :supported [:mem {:type :file :dir "<dir>"}
                                 {:type :dht :dir "<dir>"}]}))))


(defn open
  "Open the store `spec` names and keep it plain: `:mem` (or nil) is a
   fresh in-memory `dao.jing` store; `{:type :file :dir dir}` is the
   durable directory store at `dir` (dao.space.store/open): exclusive,
   validating, its HEAD writer, recovery and lock on the handle.  A spec
   this build cannot open is refused with its reason; a `dht:<dir>` spec
   is opened by yin.repl.dht, which composes its node over the same
   durable store."
  [spec]
  (let [checked (checked-spec spec)]
    (case (if (map? checked) (:type checked) checked)
      :mem (jing.mem/create-content-mem)
      :file (durable/open (:dir checked))
      :dht (throw (ex-info (str "a dht:<dir> store is opened by "
                                "yin.repl.dht/open, which composes its node")
                           {:spec (dissoc checked :bind!)})))))


(defn durable?
  "True when `store` is a durable directory store: its published index
   outlives the process, so a session rebuild continues it rather than
   starting an empty one (dao.space.store/durable?)."
  [store]
  (durable/durable? store))


(defn close!
  "Release the store's lifecycle resources (dao.space.store/close!): the
   content log and, in durable mode, the directory lock.  Idempotent."
  [store]
  (durable/close! store))

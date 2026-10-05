(ns yin.repl.main
  "Host entry points for the DaoStream Yin REPL.

   The one thing this namespace adds to `yin.repl.driver` is cadence, which
   the DaoStream contract leaves to the runtime.  Per host, a line producer
   appends what was typed to the input medium, nudges the step owner's wake
   source, and returns; one ticker owns the state value and calls
   `repl-step` serially at the interval `cadence-step` computes.  There is
   no second state owner, and no line handler evaluates, requests, polls,
   or prints."
  (:require #?@(:cljd [["dart:async" :as async]
                       ["dart:convert" :as convert]
                       ["dart:core" :as dart-core]
                       ["dart:io" :as io]])
            [clojure.string :as str]
            [dao.space.store.fs :as fs]
            [dao.stream.datagram :as datagram]
            [dao.stream.waitset.cadence :as cadence]
            [dao.stream.waitset.driver :as wake]
            [yin.repl :as shell]
            [yin.repl.dht :as repl.dht]
            [yin.repl.driver :as driver]
            [yin.repl.host :as host]
            [yin.repl.serve :as serve]
            [yin.repl.state :as state]
            [yin.repl.store :as store]
            [yin.vm.linker.head.ws :as head.ws]
            [yin.vm.linker.sign :as sign]))


(def tick-millis
  "The base cadence of the single step owner.  Slow enough to cost nothing
   while anything moves, fast enough that a remote result prints without
   waiting for a keystroke."
  25)


(def default-cadence
  "The tick owner's idle curve.  `tick-millis` is the base interval, what a
   wake resets to and what a pending write holds, and an idle composition
   backs off doubling to 200 ms.  Local input and the explicit stop trigger
   nudge the wake source, so operator actions never wait the curve out; a
   lost nudge costs at most the armed interval."
  {:poll-ms tick-millis
   :backoff {:factor 2 :ceiling-ms 200}})


(def prompt "yin> ")


(def telemetry-text
  (str "--telemetry and --telemetry-stream are not part of the DaoStream "
       "REPL slice: the telemetry emit path is built (yin.vm.telemetry, opt-in "
       "via the :telemetry {:stream ...} construction option ("
       "yin.vm.telemetry.implementation-plan.md), but composing a sink into "
       "this shell is not, so the flags are rejected rather than ignored"))


(defn- parse-int
  "The decimal integer `text` names, or nil: total on every host.  At
   most 15 digits, so the value is exact on every host (below 2^53) and
   no host parser can overflow; a longer number is nil, which each flag
   refuses as data."
  [text]
  (when (and (string? text) (re-matches #"-?\d{1,15}" text))
    #?(:cljd (int/parse text)
       :cljs (js/parseInt text 10)
       :clj (Long/parseLong text))))


(defn parse-peer
  "A `--dht-peer` value, `host:port` or `[v6-host]:port`, as the DHT's
   bootstrap contact `{:host h :port p}`.  The host must be an IP
   literal: the DHT resolves no names."
  [text]
  (let [[_ v6 v4 port] (re-matches #"(?:\[([^\]]+)\]|([^:\[\]]+)):(\d+)"
                                   (str text))
        host (or v6 v4)
        port (parse-int port)]
    (cond
      (not (datagram/valid-port? port))
      (throw (ex-info (str "--dht-peer takes host:port with a port from 1 "
                           "to 65535, not " (pr-str text))
                      {:value text}))

      (not (datagram/ip-literal? host))
      (throw (ex-info (str "--dht-peer host must be an IP literal, not "
                           (pr-str host))
                      {:value text}))

      :else {:host host :port port})))


(defn- parse-manifest
  "A `--dht-manifest` value (the address as printed, with or without its
   leading colon) as the segment address keyword."
  [text]
  (when (string? text)
    (keyword (if (str/starts-with? text ":") (subs text 1) text))))


(def ^:private dht-flags
  #{"--dht-peer" "--dht-publish" "--dht-bind" "--dht-port"
    "--dht-max-inbound-bytes" "--dht-manifest" "--dht-key" "--dht-principal"
    "--dht-follow"})


(def max-follow
  "The most principals one node follows (yin.vm.linker.dht.head.md 7)."
  64)


(defn parse-follow
  "A `--dht-follow` value, `<64 hex>@<host:port>`, as `{:principal hex
   :host h :port p}`: the principal whose published head is followed, at
   the one address its board is dialed at.  The host must be a loopback
   IP literal (`localhost` is 127.0.0.1): following off loopback is
   not in this release (yin.vm.linker.dht.head.md 5.1, 8.3)."
  [text]
  (let [[_ principal v6 v4 port]
        (re-matches #"(?:ed25519:)?([0-9a-f]{64})@(?:\[([^\]]+)\]|([^:\[\]@]+)):(\d+)"
                    (str/replace-first (str text) #"@localhost:" "@127.0.0.1:"))
        host (or v6 v4)
        port (parse-int port)]
    (cond
      (nil? principal)
      (throw (ex-info (str "--dht-follow takes <64 lowercase hex>@<host:port>,"
                           " not " (pr-str text))
                      {:value text}))

      (not (datagram/valid-port? port))
      (throw (ex-info (str "--dht-follow takes a port from 1 to 65535, not "
                           (pr-str text))
                      {:value text}))

      (not (head.ws/loopback? host))
      (throw (ex-info (str "--dht-follow " (pr-str text) ": a head board is "
                           "followed on loopback only (127.0.0.1 or ::1)")
                      {:value text}))

      :else {:principal principal :host host :port port})))


(defn parse-principal
  "A `--dht-principal` value: a declared publisher's public key, exactly
   64 lowercase hexadecimal characters (yin.vm.linker.dht.md 6.2)."
  [text]
  (if (and (string? text) (re-matches #"^[0-9a-f]{64}$" text))
    text
    (throw (ex-info (str "--dht-principal takes a public key of 64 lowercase "
                         "hexadecimal characters, not " (pr-str text))
                    {:value text}))))


;; =============================================================================
;; The publisher's key file (yin.vm.linker.dht.md 6.5)
;; =============================================================================

(defn- split-path
  "`[dir name]` of a file path."
  [path]
  (if-let [i (str/last-index-of path "/")]
    [(subs path 0 i) (subs path (inc i))]
    ["." path]))


(defn load-key
  "The publisher key `{:seed :public}` the key file at `path` holds, or a
   refusal of startup with the reason: a file that does not exist, or one
   `yin.vm.linker.sign/key-from-text` refuses.  Nothing falls back to a
   fresh key, and no refusal carries the file's content."
  [path]
  (let [[dir name] (split-path path)
        text (fs/read-file-text dir name)
        key (when text (sign/key-from-text text))]
    (cond
      (nil? text)
      (throw (ex-info (str "--dht-key " path ": the key file does not exist; "
                           "nothing falls back to a fresh key (create one with "
                           "--dht-keygen " path ")")
                      {:path path}))

      (= :refused (:status key))
      (throw (ex-info (str "--dht-key " path ": the key file is refused ("
                           (:reason key) ")")
                      {:path path :reason (:reason key)}))

      :else key)))


(defn- ensure-parent!
  "Create the missing directories above `path`."
  [path]
  (let [[dir _] (split-path path)]
    #?(:cljd (.createSync (io/Directory. dir) .recursive true)
       :clj (.mkdirs (java.io.File. ^String dir))
       :cljs (.mkdirSync (js/require "fs") dir #js {:recursive true}))))


(defn- write-new-file!
  "Create `path` holding `text`, readable by its owner only where the host
   can set that, refusing an existing file: a key file is never
   overwritten.  The directories above it are created."
  [path text]
  (ensure-parent! path)
  (let [exists (fn []
                 (ex-info (str "--dht-keygen " path ": the file exists; "
                               "a key file is never overwritten")
                          {:path path}))]
    #?(:cljd (let [f (io/File. path)]
               (when (.existsSync f) (throw (exists)))
               (try (.createSync f .exclusive true)
                    (catch Object _ (throw (exists))))
               (.writeAsStringSync f text))
       :clj (let [p (.toPath (java.io.File. ^String path))
                  owner-only
                  (java.nio.file.attribute.PosixFilePermissions/asFileAttribute
                    (java.nio.file.attribute.PosixFilePermissions/fromString
                      "rw-------"))]
              (try
                (try (java.nio.file.Files/createFile
                       p (into-array java.nio.file.attribute.FileAttribute
                                     [owner-only]))
                     (catch UnsupportedOperationException _
                       (java.nio.file.Files/createFile
                         p (make-array java.nio.file.attribute.FileAttribute
                                       0))))
                (catch java.nio.file.FileAlreadyExistsException _
                  (throw (exists))))
              (spit path text))
       :cljs (let [fs-module (js/require "fs")]
               (try (.writeFileSync fs-module path text #js {:flag "wx" :mode
                                                             384})
                    (catch :default e
                      (if (= "EEXIST" (.-code e))
                        (throw (exists))
                        (throw (ex-info (str "--dht-keygen " path ": "
                                             (.-message e))
                                        {:path path})))))))))


(defn keygen!
  "Write a new key, from the host CSPRNG, to a new key file at `path`
   (`--dht-keygen`), and answer the lines to print and the exit status.
   The seed is never printed."
  [path]
  (let [key (sign/generate)]
    (write-new-file! path (str (sign/key-text key) "\n"))
    {:lines (cond-> [(str "dht: wrote a new Ed25519 key to " path
                          "; its principal is "
                          (sign/principal (:public key))
                          ". Keep the file: a lost key "
                          "can never sign again, and a new key is a new "
                          "principal.")]
              ;; dart:io sets no file permissions (yin.vm.linker.dht.md 6.5)
              #?(:cljd true :default false)
              (conj (str "dht: WARNING: this host cannot restrict the k"
                         "ey file's "
                         "permissions; it is readable as the process um"
                         "ask allows. "
                         "Restrict it now: chmod 600 " path)))
     :exit 0}))


(defn- dht-spec
  "Fold the `--dht-*` flags into the `dht:<dir>` spec, checked by
   `yin.repl.store/checked-spec`.  They mean nothing to any other store,
   and a bind address means nothing to a solo node, so each such use is
   refused rather than ignored."
  [spec {:keys [flags peers publish? bind-host bind-port max-inbound-bytes
                manifest follow principals]}]
  (cond
    (and (seq flags) (not= :dht (:type spec)))
    (throw (ex-info (str (str/join ", " (sort flags))
                         " need --index-store dht:<dir>")
                    {:flags (vec (sort flags))}))

    (not= :dht (:type spec)) spec

    (and (empty? peers) (or bind-host bind-port))
    (throw (ex-info (str "--dht-bind and --dht-port need a --dht-peer: "
                         "with no peers the DHT store is solo and opens "
                         "no socket")
                    {}))

    (and bind-host (not (datagram/ip-literal? bind-host)))
    (throw (ex-info (str "--dht-bind must be an IP literal, not "
                         (pr-str bind-host))
                    {:value bind-host}))

    (and (seq follow) (empty? peers))
    (throw (ex-info (str "--dht-follow needs a --dht-peer: a followed head's "
                         "index is fetched over the DHT")
                    {}))

    (some #(not (contains? (set principals) (:principal %))) follow)
    (let [p (:principal (first (remove #(contains? (set principals)
                                                   (:principal %))
                                       follow)))]
      (throw (ex-info (str "--dht-follow " p " follows a principal that is not"
                           " declared: add --dht-principal " p)
                      {:principal p})))

    (not= (count follow) (count (distinct (map :principal follow))))
    (throw (ex-info "--dht-follow names each principal once: one source each"
                    {}))

    (> (count follow) max-follow)
    (throw (ex-info (str "at most " max-follow " principals may be followed")
                    {:count (count follow)}))

    :else
    (store/checked-spec
      (cond-> (assoc spec :peers (vec peers) :publish? (boolean publish?))
        bind-host (assoc :bind-host bind-host)
        bind-port (assoc :bind-port bind-port)
        max-inbound-bytes (assoc :max-inbound-bytes max-inbound-bytes)
        manifest (assoc :manifest manifest)
        ;; a pin wins for the run: it follows nothing (5.6)
        (and (seq follow) (not manifest)) (assoc :follow (vec follow))
        (and (seq follow) manifest) (assoc :follow-suspended true)))))


(defn- dht-number
  [flag text]
  (or (parse-int text)
      (throw (ex-info (str flag " takes an integer of at most 15 digits, not "
                           (pr-str text))
                      {:value text}))))


;; =============================================================================
;; The subcommands: `keygen` and `dht init|serve|join`
;; =============================================================================

(defn- home-dir*
  "The user's home directory, or nil where the host has none (ClojureDart)."
  []
  #?(:cljd nil
     :clj (System/getProperty "user.home")
     :cljs (some-> js/process .-env .-HOME)))


(defn- home-dir
  []
  (or (home-dir*)
      (throw (ex-info (str "no home directory on this host: give --dir and "
                           "--key explicitly")
                      {}))))


(defn- state-path
  "`~/.yin/<name><suffix>`."
  [name suffix]
  (str (home-dir) "/.yin/" name suffix))


(defn- peer-text
  "A peer as the DHT takes it: `localhost` is the loopback IP literal."
  [text]
  (str/replace-first (str text) #"^localhost:" "127.0.0.1:"))


(defn parse-token
  "A join token `yin:<host:port>/<principal>` as `[peer principal]` flag
   values; the principal accepts an `ed25519:` prefix.  A token with a
   third part is refused: a manifest is a pin and belongs to
   `--dht-manifest` (yin.vm.linker.dht.head.md 5.8)."
  [text]
  (let [[_ peer principal more]
        (re-matches #"yin:([^/]+)/(?:ed25519:)?([^/]+)(/.*)?" (str text))]
    (cond
      (and peer more)
      (throw (ex-info (str "a join token has two parts, yin:<host:port>/"
                           "<principal>; " (pr-str text) " has a third: a "
                           "manifest is a pin and belongs to --dht-manifest")
                      {:value text}))

      (not peer)
      (throw (ex-info (str "a join token looks like yin:<host:port>/"
                           "<principal>, as `dht: join token:` prints it, "
                           "not " (pr-str text))
                      {:value text}))

      :else [peer principal])))


(defn- keygen-args
  [args]
  (loop [args (seq args) name nil file nil]
    (if-let [arg (first args)]
      (if (= "--name" arg)
        (recur (nnext args) (second args) file)
        (recur (next args) name arg))
      ["--dht-keygen" (or file (state-path (or name "publisher") ".key"))])))


(defn- dht-args
  "The legacy flags of `dht init|serve|join`.  `init` publishes (its key is
   made on first use); `serve` stores for others; `join <token>` reads,
   declaring and following the token's principal at its address.
   Every node needs a `--peer` to open a socket (the two-node start in
   yin.repl.md); `--listen [ip:]port` is the node's own address.  Returns
   `[args {:new-key path}]`."
  [[verb & args]]
  (when-not (#{"init" "serve" "join"} verb)
    (throw (ex-info "usage: yin-repl dht init|serve|join [token] [options]"
                    {})))
  (loop [args (seq args)
         o {:name "node" :peers [] :rest []}]
    (if-let [arg (first args)]
      (let [v (second args)]
        (case arg
          "--name" (recur (nnext args) (assoc o :name v))
          "--dir" (recur (nnext args) (assoc o :dir v))
          "--key" (recur (nnext args) (assoc o :key v))
          "--peer" (recur (nnext args) (update o :peers conj (peer-text v)))
          "--listen" (recur (nnext args) (assoc o :listen v))
          (if (and (= "join" verb) (not (:token o))
                   (not (str/starts-with? arg "-")))
            (recur (next args) (assoc o :token arg))
            (recur (next args) (update o :rest conj arg)))))
      (let [{:keys [name dir key peers listen token rest]} o
            [tpeer principal] (when (= "join" verb)
                                (parse-token token))
            tpeer (some-> tpeer peer-text)
            peers (cond-> peers tpeer (conj tpeer))
            [_ lhost lport] (when listen
                              (re-matches #"(?:(.+):)?(\d+)" listen))
            key (or key (when (= "init" verb) (state-path name ".key")))]
        [(cond-> ["--index-store" (str "dht:" (or dir (state-path name "")))]
           (= "init" verb) (conj "--dht-publish")
           key (into ["--dht-key" key])
           lhost (into ["--dht-bind" lhost])
           lport (into ["--dht-port" lport])
           ;; join follows the principal's head at the token's address and
           ;; passes no manifest: its first manifest is the first head it
           ;; installs (yin.vm.linker.dht.head.md 5.8)
           principal (into ["--dht-principal" principal
                            "--dht-follow" (str principal "@" tpeer)])
           true (into (mapcat #(vector "--dht-peer" %)) peers)
           true (into rest))
         {:new-key (when (= "init" verb) key)
          :name name
          :dir (or dir (state-path name ""))
          ;; only `init` publishes and signs by default: a saved --dht-publish
          ;; or --dht-key must not survive `serve` or `join` (explicit flags
          ;; named on that command line still stand)
          :unset (when-not (= "init" verb) #{"--dht-publish" "--dht-key"})}]))))


(defn- plain-args
  "The plain form's `--name n` and `--dir d`, which name the node directory
   its state file lives in (default `~/.yin/node`), removed from `args`.
   Answers `[args extra]`."
  [args]
  (loop [args (seq args) rest [] node-name "node" dir nil]
    (if-let [arg (first args)]
      (case arg
        "--name" (if (next args)
                   (recur (nnext args) rest (second args) dir)
                   (recur (next args) (conj rest arg) node-name dir))
        "--dir" (if (next args)
                  (recur (nnext args) rest node-name (second args))
                  (recur (next args) (conj rest arg) node-name dir))
        (recur (next args) (conj rest arg) node-name dir))
      [rest {:name node-name :dir dir}])))


(defn expand-args
  "Turn the subcommand surface into the flags `parse-args` reads:
   `keygen [--name n | file]` and `dht init|serve|join`.  Anything else
   passes through unchanged.  Answers `[args extra]`, `extra` carrying what
   the arguments say beyond flags: `:new-key` (a key file `init` makes),
   `:name` and `:dir` (the node directory), and `:unset` (saved flags the
   subcommand clears)."
  [args]
  (case (first args)
    "keygen" [(keygen-args (rest args)) nil]
    "dht" (dht-args (rest args))
    (plain-args args)))


(defn- parse-vm
  "A `--vm` value as the evaluator keyword the shell takes."
  [text]
  (let [vm-type (some-> text keyword)]
    (if (contains? shell/vm-labels vm-type)
      vm-type
      (throw (ex-info (str "--vm takes one of "
                           (str/join ", " (map name (sort (keys shell/vm-labels))))
                           ", not " (pr-str text))
                      {:value text})))))


(defn parse-args
  "Parse the host arguments.  `--telemetry` and `--telemetry-stream` are
   rejected rather than ignored.  `--index-store` is parsed into
   `:index-store-spec`: `:mem` (also what omission means),
   `{:type :file :dir dir}`, or the DHT store `{:type :dht :dir dir ...}`;
   and a missing value, an unknown scheme, or an empty directory is
   refused here, by `yin.repl.store/parse-arg`, before any host composes.

   The DHT store's options are their own flags (yin.repl.dht):
   `--dht-peer host:port`, repeatable, the bootstrap contacts; none
   means solo, with no socket; `--dht-publish`, the separate declaration
   that shares the store; `--dht-bind ip` and `--dht-port p`, the
   socket's address, loopback and ephemeral unless given;
   `--dht-max-inbound-bytes n`, the inbound storage bound; and
   `--dht-manifest address`, a remote index to hydrate before the first
   evaluation; `--dht-key file`, the publisher's stable key file
   (yin.vm.linker.dht.md 6.5); `--dht-principal hex`, repeatable, a
   publisher whose signed names this node honors (7.1); and
   `--dht-follow hex@host:port`, repeatable, a declared publisher whose
   published head this node follows at that loopback address
   (yin.vm.linker.dht.head.md 5.5, 5.8).  Any of them
   without `dht:<dir>` is refused.  `--dht-keygen file` writes a new key
   file and exits."
  [args]
  (loop [args (seq args)
         opts {:headless? false :host nil :port nil :rejected []
               :index-store-spec :mem}
         dht {:flags #{} :peers []}]
    (if-let [arg (first args)]
      (let [dht (cond-> dht (dht-flags arg) (update :flags conj arg))
            value (second args)]
        (case arg
          "--port" (recur (nnext args)
                          (assoc opts :port (when-some [p (second args)]
                                              (let [n (parse-int p)]
                                                (if (and n (<= 0 n 65535))
                                                  n
                                                  (throw (ex-info
                                                           (str "--port takes a "
                                                                "port from 0 to "
                                                                "65535, not "
                                                                (pr-str p))
                                                           {:value p}))))))
                          dht)
          "--host" (throw (ex-info (str "--host is gone: --port serves on all "
                                        "interfaces, so it answers on "
                                        "localhost and on this machine's IP")
                                   {}))
          "--headless" (recur (next args) (assoc opts :headless? true) dht)
          "--vm" (recur (nnext args)
                        (assoc opts :vm-type (parse-vm (second args)))
                        dht)
          "--index-store" (recur (nnext args)
                                 (assoc opts
                                        :index-store-spec
                                        (store/parse-arg (second args)))
                                 dht)
          "--dht-peer" (recur (nnext args) opts
                              (update dht :peers conj (parse-peer value)))
          "--dht-publish" (recur (next args) opts (assoc dht :publish? true))
          "--dht-bind" (recur (nnext args) opts
                              (assoc dht :bind-host (str value)))
          "--dht-port" (recur (nnext args) opts
                              (assoc dht :bind-port (dht-number arg value)))
          "--dht-max-inbound-bytes" (recur (nnext args) opts
                                           (assoc dht :max-inbound-bytes
                                                  (dht-number arg value)))
          "--dht-manifest" (recur (nnext args) opts
                                  (assoc dht :manifest (parse-manifest value)))
          "--dht-key" (recur (nnext args)
                             (assoc opts :dht-key-file
                                    (or value
                                        (throw (ex-info
                                                 "--dht-key needs a key file"
                                                 {}))))
                             dht)
          "--dht-principal" (recur (nnext args)
                                   (update opts :principals (fnil conj [])
                                           (parse-principal value))
                                   dht)
          "--dht-follow" (recur (nnext args) opts
                                (update dht :follow (fnil conj [])
                                        (parse-follow value)))
          "--dht-keygen" (recur (nnext args)
                                (assoc opts :dht-keygen
                                       (or value
                                           (throw (ex-info
                                                    "--dht-keygen needs a file"
                                                    {}))))
                                dht)
          "--telemetry" (recur (next args) (update opts :rejected conj arg) dht)
          "--telemetry-stream" (recur (nnext args)
                                      (update opts :rejected conj arg) dht)
          (recur (next args) (update opts :extra (fnil conj []) arg) dht)))
      (update opts :index-store-spec dht-spec
              (assoc dht :principals (:principals opts))))))


(defn boot
  "Create the composition: one shell, one input medium, one cursor held only by
   the step owner, and the host WebSocket adapter `(connect ...)` attaches
   through.  The parsed `:index-store-spec` reaches the shell's store
   selection, which resolves and opens it once at construction."
  ([] (boot {}))
  ([opts] (driver/create-state
            {:host (or (:adapter opts) (host/websocket))
             :repl (shell/create-state
                     (cond-> {:index-store-spec (:index-store-spec opts)
                              :dht-key (:dht-key opts)
                              :principals (:principals opts)
                              :ws-host (:ws-host opts)
                              :write-heads! (:write-heads! opts)}
                       (:vm-type opts) (assoc :vm-type (:vm-type opts))))})))


(def bind-all-host
  "`--port` listens on every interface: loopback and the machine's own IP."
  "0.0.0.0")


(defn pick-ip
  "The address to advertise among a machine's non-loopback IPv4 addresses:
   the first private-network one (10/8, 172.16/12, 192.168/16), since a
   tunnel or VPN interface can list ahead of the LAN's; else the first;
   else loopback."
  [ips]
  (or (first (filter #(re-matches #"10\..*|192\.168\..*|172\.(1[6-9]|2\d|3[01])\..*"
                                  %)
                     ips))
      (first ips)
      "127.0.0.1"))


(defn local-ip
  "This machine's address for `--port`'s banner and advertised host.
   ClojureDart can only list interfaces asynchronously, so it advertises
   loopback."
  []
  (pick-ip
    #?(:cljd nil
       :clj (try (doall
                   (for [^java.net.NetworkInterface ni
                         (enumeration-seq
                           (java.net.NetworkInterface/getNetworkInterfaces))
                         :when (and (.isUp ni) (not (.isLoopback ni)))
                         ^java.net.InetAddress a (enumeration-seq
                                                   (.getInetAddresses ni))
                         :when (instance? java.net.Inet4Address a)]
                     (.getHostAddress a)))
                 (catch Exception _ nil))
       :cljs (try (doall
                    (for [addrs (array-seq (js/Object.values
                                             (.networkInterfaces
                                               (js/require "os"))))
                          ^js a (array-seq addrs)
                          :when (and (contains? #{"IPv4" 4} (.-family a))
                                     (not (.-internal a)))]
                      (.-address a)))
                  (catch :default _ nil)))))


(defn boot-server
  "Compose the served endpoint for `--port`, or nil when no port was asked for.

   `serve!` returns immediately.  Whether a listener bound is a fact the
   endpoint's lifecycle medium reports, and the ticker prints it."
  [opts]
  (when (:port opts)
    (serve/serve! {:bind-port (:port opts)
                   :bind-host bind-all-host
                   :advertised-host (local-ip)
                   :host (or (:adapter opts) (host/websocket))})))


(def help-lines
  "What `--help` prints: every subcommand and flag `parse-args` and
   `expand-args` read."
  ["yin-repl: the interface to datom.world"
   ""
   "usage: yin-repl [flags]"
   "       yin-repl dht init|serve|join [token] [options]"
   "       yin-repl keygen [--name n | file]"
   ""
   "subcommands:"
   "  dht init          publish: share the store, make the key on first run"
   "  dht serve         storing peer: fetch only, no key"
   "  dht join <token>  reader: follow the head of the token's principal;"
   "                    the token is yin:<host:port>/<principal>"
   "  keygen            write a new Ed25519 key file and exit"
   ""
   "dht options:"
   "  --name n          state under ~/.yin/<n> (default: node)"
   "  --dir d           state directory, instead of ~/.yin/<name>"
   "  --key file        key file (init: default ~/.yin/<name>.key)"
   "  --listen [ip:]port  this node's DHT socket"
   "  --peer host:port  a peer to contact; repeatable; localhost or an IP"
   ""
   "flags:"
   "  --port n          serve this shell to other shells over WebSockets, on"
   "                    all interfaces: localhost and this machine's IP"
   "  --headless        no prompt, endpoint only; needs --port"
   "  --index-store s   mem (default), file:<dir> or dht:<dir>"
   "  --vm type         ast-walker, semantic (default), stack or register"
   "  --name n, --dir d the node directory holding the saved state"
   "                    (default ~/.yin/node)"
   "  --reset           forget the saved state; start from the command line"
   "  --no-state        neither read nor write the saved state"
   "  --help, -h        print this and exit"
   ""
   "saved state: the flags a node starts with are kept in <node dir>/state.edn,"
   "and a bare `yin-repl` starts that node again. Flags change it: a repeated"
   "flag (--dht-peer, --dht-principal, --dht-follow) replaces its saved"
   "values, and the result is saved. --dht-manifest and --dht-keygen are"
   "never saved. A followed head is kept in <dht dir>/heads.edn."
   ""
   "dht flags (need --index-store dht:<dir>, except --dht-keygen):"
   "  --dht-peer host:port        bootstrap contact (IP literal); repeatable"
   "  --dht-publish               share the store's content with peers"
   "  --dht-bind ip               socket address; needs a peer"
   "  --dht-port p                socket port; needs a peer"
   "  --dht-max-inbound-bytes n   inbound payload bound (default 64 MiB)"
   "  --dht-manifest :segment/... remote index to hydrate first; needs a peer;"
   "                              a pin: this run follows nothing"
   "  --dht-key file              the publisher's key file"
   "  --dht-principal hex         a publisher to trust; repeatable"
   "  --dht-follow hex@host:port  follow a declared publisher's head at its"
   "                              loopback address; repeatable"
   "  --dht-keygen file           write a new key file and exit"
   ""
   "--telemetry and --telemetry-stream are rejected."
   "Unrecognized arguments are ignored."
   "See src/cljc/yin/vm/docs/yin.repl.md for the walk-through."])


(defn- save-state!
  "Save the flags this run resolved to the node directory's state file when
   they differ from what was saved (nothing is written for a bare run with
   nothing saved), and answer the banner's lines about it.  A file that
   cannot be written is a warning, never a refusal: the node still starts."
  [dir saved resolved reset?]
  (let [path (state/path dir)
        changed (state/changed (or saved {}) resolved)]
    (try
      (cond
        (and (nil? saved) (empty? resolved) (not reset?))
        []

        (nil? saved)
        (do (state/save! dir resolved)
            [(str "state: " (if reset? "reset, " "") "saved to " path
                  "; a bare yin-repl starts this node again")])

        (seq changed)
        (do (state/save! dir resolved)
            [(str "state: resumed from " path " (" (state/describe saved)
                  "); the command line changed " (str/join ", " changed)
                  "; saved")])

        :else
        [(str "state: resumed from " path " (" (state/describe resolved) ")")])
      (catch #?(:cljd Object
                :clj Throwable
                :cljs :default)
             e
        [(str "state: WARNING: could not save " path ": "
              (or (ex-message e) (str e)))]))))


(defn startup
  "Parse the arguments and compose the whole shell (the store the parsed
   `:index-store-spec` names included), or answer the refusal text.  This
   is the one gate every host's `-main` passes through before it prints a
   banner or starts a loop, so an invalid `--index-store`, an unsupported
   host for it, or a directory that cannot be opened refuses startup with
   its reason rather than composing a shell around a fallback store.

   Only a designed refusal is answered: an error carrying no ex-info
   keeps its stack trace, because it is a defect, not an operator error.

   `--dht-keygen` composes nothing: it writes the key file and answers
   `{:lines [...] :exit 0}` for the host to print and exit with.  A
   `--dht-key` file is loaded here (`load-key`); the key reaches the
   shell, and only its principal reaches the options the banner reads."
  ([args] (startup args nil))
  ([args {:keys [persist? home]}]
   (try
     (if (some #{"--help" "-h"} args)
       {:lines help-lines :exit 0}
       (let [reset? (boolean (some #{"--reset"} args))
             no-state? (boolean (some #{"--no-state"} args))
             [args {:keys [new-key unset] node-name :name dir :dir}]
             (expand-args (remove #{"--reset" "--no-state"} args))
             node-dir (when (and persist? (not no-state?)
                                 (not (some #{"--dht-keygen"} args)))
                        (or dir
                            (some-> (or home (home-dir*))
                                    (str "/.yin/" node-name))))
             saved (when (and node-dir (not reset?)) (state/load-flags node-dir))
             {:keys [flags rest]} (state/split-args args)
             resolved (state/resolve-flags saved flags unset)
             args (into (state/flags->args resolved) rest)
             made (when (and new-key (nil? (fs/read-file-text
                                             (first (split-path new-key))
                                             (second (split-path new-key)))))
                    (:lines (keygen! new-key)))
             opts (cond-> (try (parse-args args)
                               (catch #?(:cljd Object
                                         :clj Throwable
                                         :cljs :default)
                                      e
                                 (throw (if (and saved (ex-data e))
                                          (ex-info (str (ex-message e)
                                                        " (with the saved state in "
                                                        (state/path node-dir)
                                                        "; --reset forgets it)")
                                                   (ex-data e))
                                          e))))
                    made (assoc :startup-lines made))]
         (if-some [path (:dht-keygen opts)]
           (keygen! path)
           (let [key (some-> (:dht-key-file opts) load-key)
                 opts (cond-> opts
                        key (assoc :publisher (sign/principal (:public key))))
                 ;; the head board is served and dialed over the host's
                 ;; WebSocket seam (yin.vm.linker.dht.head.md 5.1)
                 state' (boot (assoc opts
                                     :dht-key key
                                     :ws-host (or (:adapter opts)
                                                  (host/websocket))))
                 server (boot-server opts)
                 lines (into (vec (repl.dht/follow-lines (:repl state')))
                             (when node-dir
                               (save-state! node-dir saved resolved reset?)))]
             {:opts (cond-> opts
                      (seq lines) (update :startup-lines
                                          #(into (vec %) lines)))
              :state state'
              :server server}))))
     (catch #?(:cljd Object
               :clj Throwable
               :cljs :default)
            e
       (if (ex-data e)
         {:refusal (or (ex-message e) (str e))}
         (throw e))))))


(defn- refuse!
  "Print why the composition refused to start and end the process with a
   failing status, so a refused startup never looks like a working shell."
  [refusal]
  (println refusal)
  #?(:cljd (io/exit 1)
     :clj (.halt (Runtime/getRuntime) 1)
     :cljs (.exit js/process 1)))


(defn close-index-store!
  "Release the index store's lifecycle resources before the host exits
   (in durable mode, the exclusive directory lock).  The shell has already
   stopped when a host calls this; the memory store has nothing to
   release.  A DHT store's head board listener and dials are released
   first (yin.repl.dht/close!)."
  [state]
  (repl.dht/close! (:repl state))
  (store/close! (get-in state [:repl :index-store])))


(defn- entry-text
  [entry]
  (or (get entry driver/text-key) (get entry serve/text-key)))


(defn banner
  "Text the composition prints before the first prompt, given parsed
   options.  A DHT store states here (before its node steps once, so
   before anything is shared) what it will share: the whole store when
   `--dht-publish` is given, nothing otherwise (yin.repl.dht/banner)."
  [opts]
  (let [spec (:index-store-spec opts)
        dht? (= :dht (:type spec))]
    (cond-> (vec (:startup-lines opts))
      (seq (:rejected opts)) (conj telemetry-text)
      (:port opts)
      (conj (str "serving on all interfaces: ws://127.0.0.1:" (:port opts)
                 (let [ip (local-ip)]
                   (if (= "127.0.0.1" ip)
                     " and ws://<this machine's IP>:"
                     (str " and ws://" ip ":")))
                 (:port opts)
                 ". Anyone who can reach this port can evaluate code in this"
                 " shell; there is no authentication."))
      (and (:headless? opts) (not (:port opts)))
      (conj "--headless has nothing to attend without a served endpoint")
      dht? (into (repl.dht/banner spec))
      (and dht? (:publish? spec))
      (conj (str "dht: the code index and the code itself are shared: every"
                 " evaluated program's rows are written to the index store"
                 " each round"))
      (and dht? (:publisher opts))
      (conj (str "dht: names published here are signed by principal "
                 (:publisher opts) " (key from " (:dht-key-file opts) ")"))
      (and dht? (not (:publisher opts)))
      (conj (str "dht: no --dht-key: (yin.link/publish ...) is refused; names"
                 " are still read, resolved, loaded and linked")))))


(defn- exit-with!
  "Print a composition that ends at startup -- `--dht-keygen` -- and end
   the process with its status."
  [{:keys [lines exit]}]
  (doseq [line lines]
    (println line))
  #?(:cljd (io/exit exit)
     :clj (.halt (Runtime/getRuntime) (int exit))
     :cljs (.exit js/process exit)))


(defn step-all
  "One tick of the single step owner: the local shell first, then the served
   endpoint, against the same shell value.  A DHT index store's node is stepped
   first (yin.repl.dht/step): its lines print before the shell's, a
   hydration still outstanding leaves every typed line waiting in the
   input medium, and a refused one stops the shell.  A `--port` process
   serves one shared shell, as v1's atom made it: the driver evaluates this
   tick's local
   lines first, so a definition typed at the local prompt is already in the
   shell the endpoint evaluates remote requests against in the same tick, and
   the endpoint's shell (remote definitions included) is threaded back
   before the next tick.  A require parked on a closure load the node ended this
   tick is re-checked once, before any typed line, with no line of its
   own (yin.repl/recheck-on-load-events, yin.vm.linker.dht.md 8.2): its
   text prints after the node's lines.  Returns `[state server lines]`;
   the caller only prints."
  [state server now]
  (let [[repl dht-lines events] (repl.dht/step (:repl state) now)
        state (assoc state :repl repl)]
    (cond
      (repl.dht/refusal repl)
      [(assoc state :running? false) server dht-lines]

      (not (repl.dht/admitting? repl))
      [state server dht-lines]

      :else
      (let [[repl rechecked] (shell/recheck-on-load-events repl events)
            dht-lines (cond-> dht-lines
                        (seq rechecked) (conj rechecked))
            state (assoc state :repl repl)
            stepped (driver/repl-step state now)
            [entries state'] (driver/take-outbox stepped)
            server' (when server
                      (serve/step (assoc-in server [:repl] (:repl state'))
                                  now))
            [server-entries server''] (if server'
                                        (serve/take-outbox server')
                                        [[] nil])
            state'' (if server'' (assoc state' :repl (:repl server'')) state')]
        [state'' server'' (into dht-lines
                                (map entry-text)
                                (into (vec entries) server-entries))]))))


(defn moved?
  "The tick owner's cadence bit, computed from this tick's own results: true
   when there are lines to print, when the endpoint reports movement (a
   woken probe, a published notice), or when either composition still owes a
   write.  A pending write keeps cadence at the base interval; it must never
   wait out a backoff ceiling."
  [state server lines]
  (boolean (or (seq lines)
               (driver/pending-write? state)
               (repl.dht/busy? (:repl state))
               (and server (serve/moved? server)))))


(defn exit-status
  "The process status a host exits with once the shell stops: 1 when the
   DHT store refused after startup (a failed bind, a hydration that could
   not complete), else 0."
  [state]
  (if (repl.dht/refusal (:repl state)) 1 0))


;; =============================================================================
;; Shutdown
;; =============================================================================

(def stop-ticks
  "How many ticks a host steps a stopping endpoint before exiting anyway.  The
   endpoint's `:stopped` fact comes from the host's own close completion, which
   a wedged listener may never deposit; the shell says so and exits rather than
   hanging on it."
  200)


(def stop-timeout-text
  ";; the endpoint did not report :stopped; exiting anyway")


(def stop-join-millis
  "How long a host thread may wait for the step owner to finish shutdown.

   The drain performs `stop-ticks + 1` steps with a sleep between them, so it
   needs strictly more than `tick-millis x stop-ticks`; a join of exactly that
   expires mid-drain and the announced timeout never prints."
  (* tick-millis (+ stop-ticks 2)))


(defn request-stop!
  "The explicit stop trigger: append the shell's own quit line to the input
   medium.

   This is the whole of what a host signal handler, a headless supervisor, or a
   test does to stop the composition.  It is a line producer like any other, so
   the one step owner still performs the shutdown: `(quit)` stops the shell,
   and the shell stopping is what stops the endpoint.  The wake source, when
   one is supplied, is nudged so the step owner runs the quit line at once
   rather than at the idle interval it had armed."
  ([state]
   (driver/submit-line! (:input state) "(quit)"))
  ([state w]
   (let [result (request-stop! state)]
     (when w (wake/nudge! w))
     result)))


(defn stop-tick
  "One shutdown tick for an endpoint that has already been asked to stop.
   Returns `[server lines stopped?]`.

   `serve/stopped?` is the whole of the exit condition: an endpoint that never
   bound is done on the first tick, because no host exists to report a
   `:stopped` fact for it."
  [server now]
  (let [server' (serve/step server now)
        [entries server''] (serve/take-outbox server')]
    [server'' (mapv entry-text entries) (serve/stopped? server'')]))


;; =============================================================================
;; clj: a reader thread that only appends, and one owned poller thread
;; =============================================================================

#?(:cljd
   nil
   :clj
   (do
     (defn- print-prompt!
       []
       (print prompt)
       (flush))

     (defn- drain-server!
       "The shell has quit, so the endpoint stops before the host exits: ask it
        once, then keep stepping until it reports `:stopped` or the bounded
        budget runs out.  A connected client must observe `:ws/ended`, not the
        `:ws/closed` a process exit would leave behind.  The bounded drain
        sleeps the base interval; it is a budget, not a cadence."
       [server w]
       (when server
         (loop [server (serve/stop! server)
                remaining stop-ticks]
           (let [[server' lines stopped?]
                 (stop-tick server (System/currentTimeMillis))]
             (doseq [line lines]
               (println line))
             (cond
               stopped? nil
               (zero? remaining) (println stop-timeout-text)
               :else (do (wake/sleep! w tick-millis)
                         (recur server' (dec remaining))))))))

     (defn poll-loop!
       "The sole owner of REPL and endpoint state on the JVM.  It carries both
        values through serial steps; nothing is shared with the reader.

        Its cadence is `cadence/cadence-step` over `default-cadence`, slept
        through the wake source `w` (one is made when the caller supplied
        none): any line the reader deposits, and the explicit stop trigger,
        nudges it, so operator actions never wait out the idle curve.

        It also owns the exit.  The reader is parked in `read-line` and cannot
        observe that the shell has quit, so a typed `(quit)` would otherwise
        stop the endpoint and then wait for end-of-input that only Ctrl-D
        produces.  `exit!` is called once, by this thread, after the endpoint
        has drained."
       ([state server headless?]
        (poll-loop! state server headless? #(.halt (Runtime/getRuntime) 0)))
       ([state server headless? exit!]
        (poll-loop! state server headless? exit! (wake/make-wake)))
       ([state server headless? exit! w]
        (loop [state state
               server server
               cadence (cadence/init default-cadence)]
          (let [[state' server' lines] (step-all state server
                                                 (System/currentTimeMillis))]
            (doseq [line lines]
              (println line))
            (if (:running? state')
              (do (when (and (seq lines) (not headless?))
                    (print-prompt!))
                  (let [{:keys [cadence-state sleep-ms]}
                        (cadence/cadence-step cadence
                                              (moved? state' server' lines))]
                    (wake/sleep! w sleep-ms)
                    (recur state' server' cadence-state)))
              (do (drain-server! server' w)
                  (println)
                  (close-index-store! state')
                  (let [status (exit-status state')]
                    (if (zero? status)
                      (exit!)
                      (.halt (Runtime/getRuntime) (int status))))))))))

     (defn- read-loop!
       "The reader parks in `read-line` and appends, nudging the step owner's
        wake: a typed line ends the idle sleep at once.  It reads no state."
       [input w]
       (loop []
         (if-let [line (read-line)]
           (do (driver/submit-line! input line)
               (wake/nudge! w)
               (recur))
           (do (driver/submit-line! input "(quit)")
               (wake/nudge! w)))))

     (defn -main
       [& args]
       (let
         [started (startup args {:persist? true})]
         (when (contains? started :exit) (exit-with! started))
         (if-some
           [refusal (:refusal started)]
           (refuse! refusal)
           (let
             [{:keys [opts state server]} started
              headless? (boolean (:headless? opts))
              w (wake/make-wake)]
             (doseq [line (banner opts)]
               (println line))
             (let
               [poller (Thread.
                         ^Runnable (fn []
                                     (poll-loop!
                                       state
                                       server
                                       headless?
                                       #(.halt
                                          (Runtime/getRuntime) 0)
                                       w)))]
               (.setDaemon poller true)
               (.start poller)
               ;; Headless attends the endpoint only: there is no reader, so the
               ;; step owner is joined until it stops, and the explicit stop
               ;; trigger
               ;; is the host signal a shutdown hook observes.  The hook appends
               ;; a
               ;; line like any producer, nudges, and then waits for the one
               ;; step
               ;; owner.
               (if headless?
                 (do (.addShutdownHook
                       (Runtime/getRuntime)
                       (Thread. ^Runnable (fn []
                                            (request-stop! state w)
                                            (.join poller ^long
                                                   stop-join-millis))))
                     (.join poller))
                 (do (print-prompt!)
                     ;; End-of-input is one way to stop; a typed `(quit)` is the
                     ;; other, and the step owner has already exited the process
                     ;; by
                     ;; the time this join is reached in that case.
                     (read-loop! (:input state) w)
                     (.join poller ^long stop-join-millis))))
             (.halt (Runtime/getRuntime) 0)))))))


;; =============================================================================
;; cljs (Node): a readline handler that only appends, and one interval owner
;; =============================================================================

#?(:cljs
   (do
     (defn- run-node!
       "The tick owner on Node: one wake source arms exactly one timer per
        round, at the interval `cadence-step` computes over
        `default-cadence`.  Returns the wake so the composition can wire its
        line producers (the readline handlers, the stop signals) as
        `nudge!` callers.

        `repl-step` is synchronous and the Node event loop is single
        threaded, so a tick cannot overlap itself.  The box is host cadence
        plumbing: the tick is the only reader and writer of it.  `:stopping`
        is nil while the shell runs and a tick budget afterwards: the
        endpoint is asked to stop once and stepped at the base interval (a
        bounded drain, not a curve) until it reports it."
       [state server rl]
       (let [box (atom {:state state :server server :stopping nil
                        :cadence (cadence/init default-cadence)})
             wake-ref (volatile! nil)
             finish! (fn []
                       (wake/disarm! @wake-ref)
                       (when rl (.close rl))
                       (close-index-store! (:state @box))
                       (js/process.exit (exit-status (:state @box))))
             tick (fn []
                    ;; The interval timer this namespace replaced fired
                    ;; again whatever happened, so a tick whose body throws
                    ;; must not kill the owner: arm the fallback first and
                    ;; let the body's own `arm!` replace it. `finish!`
                    ;; disarms, so a finished owner parks nothing.
                    (wake/arm! @wake-ref tick-millis)
                    (let [{:keys [state server stopping cadence]} @box]
                      (if (nil? stopping)
                        (let [[state' server' lines] (step-all state server
                                                               (js/Date.now))]
                          (reset! box {:state state'
                                       :server server'
                                       :stopping nil
                                       :cadence cadence})
                          (doseq [line lines]
                            (js/console.log line))
                          (cond
                            (:running? state')
                            (let [{:keys [cadence-state sleep-ms]}
                                  (cadence/cadence-step
                                    cadence (moved? state' server' lines))]
                              (swap! box assoc :cadence cadence-state)
                              (when (and (seq lines) rl) (.prompt rl))
                              (wake/arm! @wake-ref sleep-ms))

                            server'
                            (do (swap! box assoc
                                       :server (serve/stop! server')
                                       :stopping stop-ticks)
                                (wake/arm! @wake-ref tick-millis))

                            :else (finish!)))
                        (let [[server' lines stopped?]
                              (stop-tick server (js/Date.now))]
                          (doseq [line lines]
                            (js/console.log line))
                          (cond
                            stopped? (finish!)
                            (zero? stopping)
                            (do (js/console.log stop-timeout-text) (finish!))
                            :else (do (swap! box assoc
                                             :server server'
                                             :stopping (dec stopping))
                                      (wake/arm! @wake-ref tick-millis)))))))]
         (vreset! wake-ref (wake/make-wake tick))
         (wake/arm! @wake-ref tick-millis)
         @wake-ref))

     (defn -main
       [& args]
       (let [started (startup args {:persist? true})]
         (when (contains? started :exit) (exit-with! started))
         (if-some [refusal (:refusal started)]
           (refuse! refusal)
           (let [{:keys [opts state server]} started
                 readline (when-not (:headless? opts) (js/require "readline"))
                 rl (when readline
                      (.createInterface readline
                                        #js {:input (.-stdin js/process)
                                             :output (.-stdout js/process)
                                             :prompt prompt}))]
             (doseq [line (banner opts)]
               (js/console.log line))
             ;; The tick owner arms its first round, and the composition wires
             ;; the
             ;; deposit it hands each line producer with the nudge: a typed line
             ;; ends the idle sleep at once.
             (let [w (run-node! state server rl)]
               (if rl
                 (do (.on rl "line" (fn [line]
                                      (driver/submit-line! (:input state) line)
                                      (wake/nudge! w)))
                     (.on rl "close" (fn []
                                       (driver/submit-line! (:input state)
                                                            "(quit)")
                                       (wake/nudge! w)))
                     (.prompt rl))
                 ;; Headless has no reader, so the explicit stop trigger is the
                 ;; host
                 ;; signal: it appends a line, nudges, and returns, like any
                 ;; producer.
                 (doseq [signal ["SIGINT" "SIGTERM"]]
                   (.on js/process signal (fn []
                                            (request-stop! state
                                                           w))))))))))))


;; =============================================================================
;; cljd: a stdin listener that only appends, and one periodic timer owner
;; =============================================================================

#?(:cljd
   (do
     (defn- write-line!
       [message]
       (println message))

     (defn- print-prompt!
       []
       (.write io/stdout prompt)
       (.flush io/stdout))

     (defn- run-dart!
       "One wake source owns the tick: exactly one `Timer` armed per round, at
        the interval `cadence-step` computes over `default-cadence`, because a
        synchronous poll loop would deadlock the Dart event loop: IO never
        progresses, so `blocked` never clears.  Returns the wake so the
        composition can wire its line producers (the stdin listener, the
        stop signals) as `nudge!` callers.

        `:stopping` is nil while the shell runs and a tick budget afterwards:
        the endpoint is asked to stop once and stepped at the base interval
        (a bounded drain, not a curve) until it reports it, so the host does
        not exit with a live listener."
       [state server headless?]
       (let [box (atom {:state state :server server :stopping nil
                        :cadence (cadence/init default-cadence)})
             wake-ref (volatile! nil)
             finish! (fn []
                       (wake/disarm! @wake-ref)
                       (close-index-store! (:state @box))
                       (io/exit (exit-status (:state @box)))
                       nil)
             tick (fn []
                    ;; The periodic timer this namespace replaced fired
                    ;; again whatever happened, so a tick whose body throws
                    ;; must not kill the owner: arm the fallback first and
                    ;; let the body's own `arm!` replace it. `finish!`
                    ;; disarms, so a finished owner parks nothing.
                    (wake/arm! @wake-ref tick-millis)
                    (let [{:keys [state server stopping cadence]} @box
                          now (.-millisecondsSinceEpoch
                                (dart-core/DateTime.now))]
                      (if (nil? stopping)
                        (let [[state' server' lines]
                              (step-all state server now)]
                          (reset! box {:state state'
                                       :server server'
                                       :stopping nil
                                       :cadence cadence})
                          (doseq [line lines]
                            (write-line! line))
                          (cond
                            (:running? state')
                            (let [{:keys [cadence-state sleep-ms]}
                                  (cadence/cadence-step
                                    cadence (moved? state' server' lines))]
                              (swap! box assoc :cadence cadence-state)
                              (when (and (seq lines) (not headless?))
                                (print-prompt!))
                              (wake/arm! @wake-ref sleep-ms))

                            server'
                            (do (swap! box assoc
                                       :server (serve/stop! server')
                                       :stopping stop-ticks)
                                (wake/arm! @wake-ref tick-millis)
                                nil)

                            :else (finish!)))
                        (let [[server' lines stopped?] (stop-tick server now)]
                          (doseq [line lines]
                            (write-line! line))
                          (cond
                            stopped? (finish!)
                            (zero? stopping) (do (write-line! stop-timeout-text)
                                                 (finish!))
                            :else (do (swap! box assoc
                                             :server server'
                                             :stopping (dec stopping))
                                      (wake/arm! @wake-ref tick-millis)
                                      nil))))))]
         (vreset! wake-ref (wake/make-wake tick))
         (wake/arm! @wake-ref tick-millis)
         @wake-ref))

     (defn -main
       [& args]
       (let [started (startup args {:persist? true})]
         (when (contains? started :exit) (exit-with! started))
         (if-some [refusal (:refusal started)]
           (refuse! refusal)
           (let [{:keys [opts state server]} started
                 headless? (boolean (:headless? opts))]
             (doseq [line (banner opts)]
               (write-line! line))
             ;; The tick owner arms its first round, and the composition wires
             ;; the
             ;; deposit it hands each line producer with the nudge: a typed line
             ;; ends the idle sleep at once.  A timer fires only on the event
             ;; loop, so arming before the producers are wired races nothing.
             (let [w (run-dart! state server headless?)]
               (if headless?
                 ;; Headless has no reader, so the explicit stop trigger is the
                 ;; host signal: it appends a line, nudges, and returns, like
                 ;; any
                 ;; producer.
                 (-> (.watch io/ProcessSignal.sigint)
                     (.listen (fn [_signal] (request-stop! state w))))
                 (do (-> io/stdin
                         (.transform (.-decoder convert/utf8))
                         (.transform (convert/LineSplitter.))
                         (.listen (fn [line]
                                    (driver/submit-line! (:input state) line)
                                    (wake/nudge! w))
                                  .onDone (fn []
                                            (driver/submit-line! (:input state)
                                                                 "(quit)")
                                            (wake/nudge! w))))
                     (print-prompt!))))))))

     (defn ^{:dart/name main} run-main
       [args]
       (apply -main args))))

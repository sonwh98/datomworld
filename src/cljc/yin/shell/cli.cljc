(ns yin.shell.cli
  "The command line of the DHT composition
   (docs/design/yin.repl.dht-composition.md section 6).

   Every `--dht-*` flag, the `keygen` and `dht init|serve|join`
   subcommands, the publisher's key file, the composition's help text and
   its startup banner live here and nowhere in the engine tree.  The
   engine's host driver (`yin.repl.main`) parses the flags it owns, drives
   one ticker, and takes this namespace's contribution as plain data — a
   `composition` — so it never names a peer, a principal, a key file or a
   transport.

   This namespace owns no socket, atom, promise, callback, clock or
   namespace global either.  Parsing answers data or throws a refusal
   naming the flag; the key file helpers are the only I/O, and each is an
   explicit `--dht-key` read or `--dht-keygen` write the operator asked
   for."
  (:require #?@(:cljd [["dart:io" :as io]]
                :jolt [[dao.host.text :as text]
                       [dao.posix.file :as posix-file]])
            [clojure.string :as str]
            [dao.space.store.fs :as fs]
            [dao.stream.datagram :as datagram]
            [dao.stream.remote-channel :as remote-channel]
            [yin.repl.main :as repl.main]
            [yin.repl.state :as state]
            [yin.shell.dht :as shell.dht]
            [yin.shell.store :as shell.store]
            [yin.vm.linker.sign :as sign]))


(defn parse-peer
  "A `--dht-peer` value, `host:port` or `[v6-host]:port`, as the DHT's
   bootstrap contact `{:host h :port p}`.  The host must be an IP
   literal: the DHT resolves no names."
  [text]
  (let [[_ v6 v4 port] (re-matches #"(?:\[([^\]]+)\]|([^:\[\]]+)):(\d+)"
                                   (str text))
        host (or v6 v4)
        port (repl.main/parse-int port)]
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


(def dht-flags
  "The flags this composition parses.  The engine's driver hands each of
   them to `parse-flag` and never reads one itself."
  #{"--dht-peer" "--dht-publish" "--dht-bind" "--dht-port"
    "--dht-max-inbound-bytes" "--dht-manifest" "--dht-key" "--dht-principal"
    "--dht-trust" "--dht-follow"})


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
        port (repl.main/parse-int port)]
    (cond
      (nil? principal)
      (throw (ex-info (str "--dht-follow takes <64 lowercase hex>@<host:port>,"
                           " not " (pr-str text))
                      {:value text}))

      (not (datagram/valid-port? port))
      (throw (ex-info (str "--dht-follow takes a port from 1 to 65535, not "
                           (pr-str text))
                      {:value text}))

      (not (remote-channel/loopback-literal? host))
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


(defn parse-trust
  "Parse the shared CLI/session trust configuration principal."
  [text]
  (state/parse-trust text))


;; =============================================================================
;; The publisher's key file (yin.vm.linker.dht.md 6.5)
;; =============================================================================

(defn split-path
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
       ;; O_CREAT | O_EXCL at 0600 in one call: owner-only from creation,
       ;; never an existing file; written whole, fsynced, closed checked
       :jolt (when (= :exists (posix-file/write-new-private! path (text/utf8-encode text)))
               (throw (exists)))
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
   `yin.shell.dht/checked-spec`.  They mean nothing to any other store,
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
    (shell.dht/checked-spec
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
  (or (repl.main/parse-int text)
      (throw (ex-info (str flag " takes an integer of at most 15 digits, not "
                           (pr-str text))
                      {:value text}))))


;; =============================================================================
;; The subcommands: `keygen` and `dht init|serve|join`
;; =============================================================================

(defn home-dir*
  "The user's home directory, or nil where the host has none (ClojureDart)."
  []
  #?(:cljd nil
     :clj (System/getProperty "user.home")
     :cljs (some-> js/process .-env .-HOME)))


(defn home-dir
  []
  (or (home-dir*)
      (throw (ex-info (str "no home directory on this host: give --dir and "
                           "--key explicitly")
                      {}))))


(defn state-path
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


(defn expand-args
  "Turn the subcommand surface into the flags `parse-args` reads:
   `keygen [--name n | file]` and `dht init|serve|join`.  Anything else
   passes to the engine's own plain form (`--name n` and `--dir d`, which
   name the node directory its state file lives in).  Answers
   `[args extra]`, `extra` carrying what the arguments say beyond flags:
   `:new-key` (a key file `init` makes), `:name` and `:dir` (the node
   directory), and `:unset` (saved flags the subcommand clears)."
  [args]
  (case (first args)
    "keygen" [(keygen-args (rest args)) nil]
    "dht" (dht-args (rest args))
    (repl.main/plain-args args)))


(defn parse-flag
  "The engine driver's `:flag-parser` hook: parse one flag this composition
   owns out of `args`, answering `{:opts opts' :extra extra' :advance n}`,
   `n` the arguments consumed including the flag itself — or nil for a flag
   this composition does not own, which the driver then reads itself.

   `extra` is the driver's opaque parse accumulator, handed back on every
   answer: this composition gathers the flags it reads into it, and
   `finalize-opts` folds them into the resolved `:index-store-spec` once
   every argument has been read.  Two option keys cross into the driver's
   own map, both neutral and both read back by it: `:publisher-key-file`,
   the key file `--dht-key` named, and `:keygen`, the file `--dht-keygen`
   named, which composes nothing and ends the run."
  [arg value args opts extra]
  (let [extra (cond-> extra (dht-flags arg) (update :flags conj arg))]
    (case arg
      "--dht-peer" {:extra (update extra :peers conj (parse-peer value))
                    :advance 2}
      "--dht-publish" {:extra (assoc extra :publish? true) :advance 1}
      "--dht-bind" {:extra (assoc extra :bind-host (str value)) :advance 2}
      "--dht-port" {:extra (assoc extra :bind-port (dht-number arg value))
                    :advance 2}
      "--dht-max-inbound-bytes"
      {:extra (assoc extra :max-inbound-bytes (dht-number arg value))
       :advance 2}
      "--dht-manifest" {:extra (assoc extra :manifest (parse-manifest value))
                        :advance 2}
      "--dht-key"
      {:opts (assoc opts :publisher-key-file
                    (or value
                        (throw (ex-info "--dht-key needs a key file" {})))
                    :dht-key-file
                    (or value
                        (throw (ex-info "--dht-key needs a key file" {}))))
       :extra extra
       :advance 2}
      "--dht-principal"
      {:opts (update opts :principals (fnil conj []) (parse-principal value))
       :extra extra
       :advance 2}
      "--dht-trust"
      {:opts (update-in opts [:admission :trusted] (fnil conj #{})
                        (parse-trust value))
       :extra extra
       :advance 2}
      "--dht-follow" {:extra (update extra :follow (fnil conj [])
                                     (parse-follow value))
                      :advance 2}
      "--dht-keygen"
      {:opts (assoc opts :keygen
                    (or value
                        (throw (ex-info "--dht-keygen needs a file" {}))))
       :extra extra
       :advance 2}
      nil)))


(defn finalize-opts
  "The engine driver's `:finalize` hook: fold the flags the parse gathered
   into `extra` into the resolved `:index-store-spec`.  A composition's
   flags mean nothing to a store it does not name, so `dht-spec` refuses
   rather than ignores them."
  [opts extra]
  (update opts :index-store-spec dht-spec
          (assoc extra :principals (:principals opts))))


(defn parse-store-arg
  "The `--index-store` value as a spec, over the engine's two schemes and
   this composition's `dht:` (yin.shell.store/parse-arg)."
  [value]
  (shell.store/parse-arg value))


(def state-table
  "The saved-flag table this composition adds to the engine's own: every
   `--dht-*` flag a node restarts with, and the two one-shot flags that
   are read and never persisted."
  {:saved-flags [["--dht-peer" :many]
                 ["--dht-publish" :switch]
                 ["--dht-bind" :value]
                 ["--dht-port" :value]
                 ["--dht-max-inbound-bytes" :value]
                 ["--dht-key" :value]
                 ["--dht-principal" :many]
                 ["--dht-trust" :many]
                 ["--dht-follow" :many]]
   :one-shot-flags #{"--dht-manifest" "--dht-keygen"}})


(def flag-table
  "`state-table` resolved against the engine's own flags: the table every
   read, resolve, describe and write of `<node dir>/state.edn` shares, so
   a saved DHT flag is resumed by a bare `yin-repl` and a saved engine
   flag is resumed beside it."
  (state/flag-table state-table))


(def help-lines
  "What `--help` prints for a host that composes this namespace: every
   subcommand and flag the engine's driver and this composition read."
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
   "  --codec c         wire codec served: cbor (default) or transit"
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
   "flag (--dht-peer, --dht-principal, --dht-trust, --dht-follow) replaces"
   "its saved values, and the result is saved. --dht-manifest and"
   "--dht-keygen are never saved. A followed head is kept in"
   "<dht dir>/heads.edn."
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
   "  --dht-principal hex         a publisher whose signed names to honor;"
   "                              repeatable"
   "  --dht-trust ed25519:hex     a principal whose modules to admit (not"
   "                              implied by --dht-principal); repeatable"
   "  (trust p), (untrust p), (trusted) edit/show session trust; reset"
   "                              restores the configured --dht-trust set"
   "  --dht-follow hex@host:port  follow a declared publisher's head at its"
   "                              loopback address; repeatable"
   "  --dht-keygen file           write a new key file and exit"
   ""
   "--telemetry and --telemetry-stream are rejected."
   "Unrecognized arguments are ignored."
   "See src/cljc/yin/vm/docs/yin.repl.md for the walk-through."])


(defn ensure-key!
  "Write a new key file at `path` when nothing is there yet, answering the
   lines to print, or nil when the file already exists.  This is what a
   subcommand's `:new-key` asks for: `dht init` makes its key on first use
   and reuses it afterwards, so a restart is still the same principal."
  [path]
  (let [[dir name] (split-path path)]
    (when (nil? (fs/read-file-text dir name))
      (:lines (keygen! path)))))


(defn banner-lines
  "What the composition states before the first prompt, given parsed
   options.  A DHT store states here — before its node steps once, so
   before anything is shared — what it will share: the whole store when
   `--dht-publish` is given, nothing otherwise (yin.shell.dht/banner).  The
   publisher's own principal is read off the loaded `:publisher-key`, so
   this namespace and not the engine's driver is the one that knows a key
   has a principal."
  [opts]
  (let [spec (:index-store-spec opts)
        dht? (= :dht (:type spec))
        publisher (some-> (:publisher-key opts) :public sign/principal)]
    (if-not dht?
      []
      (cond-> (vec (shell.dht/banner spec))
        (:publish? spec)
        (conj (str "dht: the code index and the code itself are shared: every"
                   " evaluated program's rows are written to the index store"
                   " each round"))
        publisher
        (conj (str "dht: names published here are signed by principal "
                   publisher " (key from " (:publisher-key-file opts) ")"))
        (not publisher)
        (conj (str "dht: no --dht-key: (yin.link/publish ...) is refused; names"
                   " are still read, resolved, loaded and linked"))))))


(def keygen-flag
  "The one argument that composes nothing at all: the engine driver reads
   no state file for such a run, and hands the path it named to `keygen!`."
  "--dht-keygen")


(defn hooks
  "The command-line half of the plain-data bundle `yin.repl.main` takes as
   its `:composition`: every way this composition widens the engine
   driver's argument surface, and nothing else.  The store, the session and
   the transport lifecycle keys beside these are the outer host entry
   point's to add (`yin.shell.main/composition`), so this namespace stays a
   parser and never opens a socket."
  []
  {:flag-table flag-table
   :expand-args expand-args
   :flag-parser parse-flag
   :parse-store-arg parse-store-arg
   :initial-extra {:flags #{} :peers []}
   :finalize finalize-opts
   :keygen-flag keygen-flag
   :keygen! keygen!
   :ensure-key! ensure-key!
   :load-key load-key
   :banner-lines banner-lines
   :help-lines help-lines})

(ns yin.repl.main
  "Host entry points for the DaoStream Yin REPL.

   The one thing this namespace adds to `yin.repl.driver` is cadence, which
   the DaoStream contract leaves to the runtime.  Per host, a line producer
   appends what was typed to the input medium, nudges the step owner's wake
   source, and returns; one ticker owns the state value and calls
   `repl-step` serially at the interval `cadence-step` computes.  There is
   no second state owner, and no line handler evaluates, requests, polls,
   or prints.

   A storage composition past the engine's own two stores reaches this
   driver as plain data — the `composition` of `startup`'s options — and is
   never named here: no transport, no peer, no publisher key file, no
   `--index-store` scheme of its own past `mem` and `file:<dir>`, and no
   flag of a composition's it parses itself.  The host entry point that
   carries a composition supplies it (`yin.shell.main`); a host that
   carries none drives the engine alone.  Every key of that value is
   optional, and an absent one is the engine's own behaviour, so this
   namespace has exactly one dependency direction: a composition may
   require it, and it requires no composition."
  (:require #?@(:cljd [["dart:async" :as async]
                       ["dart:convert" :as convert]
                       ["dart:core" :as dart-core]
                       ["dart:io" :as io]]
                :jolt [[yang.python.antlr.parser :as python.antlr.parser]
                       [yin.repl.host.common :as host-common]])
            [clojure.string :as str]
            [dao.stream.waitset.cadence :as cadence]
            [dao.stream.waitset.driver :as wake]
            [yin.repl :as shell]
            [yin.repl.connect :as connect]
            [yin.repl.frontends :as repl.frontends]
            [yin.repl.driver :as driver]
            [yin.repl.host :as host]
            [yin.repl.serve :as serve]
            [yin.repl.state :as state]
            [yin.repl.store :as store]
            [yin.vm.ucf.compose :as compose]))


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


(defn parse-int
  "The decimal integer `text` names, or nil: total on every host.  At
   most 15 digits, so the value is exact on every host (below 2^53) and
   no host parser can overflow; a longer number is nil, which each flag
   refuses as data.

   A composition's flag parser reads this too (`yin.shell.cli`): one
   integer grammar on every host, engine and composition alike."
  [text]
  (when (and (string? text) (re-matches #"-?\d{1,15}" text))
    #?(:cljd (int/parse text)
       :cljs (js/parseInt text 10)
       :clj (Long/parseLong text))))


(defn- home-dir*
  "The user's home directory, or nil where the host has none (ClojureDart).
   The node directory the saved state lives in is under it; a composition's
   own subcommand paths are its own to derive (`yin.shell.cli`)."
  []
  #?(:cljd nil
     :clj (System/getProperty "user.home")
     :cljs (some-> js/process .-env .-HOME)))


(defn plain-args
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
  "Turn a subcommand surface into the flags `parse-args` reads, answering
   `[args extra]`: `extra` carries what the arguments say beyond flags —
   `:new-key` (a key file a subcommand makes), `:name` and `:dir` (the node
   directory), and `:unset` (saved flags the subcommand clears).

   The engine's own surface is the plain form alone (`plain-args`: `--name`
   and `--dir`, which name the node directory the saved state lives in).  A
   composition's subcommands are its own, and reach this driver as its
   `:expand-args` hook — one that falls back to the plain form for anything
   it does not own.  So a host carrying no composition has no subcommand
   surface at all, and one carrying a composition never has this namespace
   parse a word of it."
  ([args] (expand-args args nil))
  ([args composition]
   ((or (:expand-args composition) plain-args) args)))


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


(defn- parse-codec
  "A `--codec` value, `cbor` or `transit`, as the keyword `connect/codec-profiles`
   names."
  [value]
  (let [k (some-> value keyword)]
    (if (contains? connect/codec-profiles k)
      k
      (throw (ex-info (str #?(:cljd "--codec takes cbor or transit, not "
                              :jolt "--codec takes cbor, not "
                              :default "--codec takes cbor or transit, not ") (pr-str value))
                      {:value value})))))


(defn parse-args
  "Parse the host arguments this driver owns.  `--telemetry` and
   `--telemetry-stream` are rejected rather than ignored.  `--index-store`
   is parsed into `:index-store-spec`: `:mem` (also what omission means),
   or `{:type :file :dir dir}`; a missing value, an unknown scheme, or an
   empty directory is refused here, by `yin.repl.store/parse-arg`, before
   any host composes.

   `composition` is the plain-data contribution of a storage composition
   (`startup`'s option of the same name), and every way one widens this
   surface arrives through it — statically, as data, on every host:

   * `:parse-store-arg` parses an `--index-store` value over the engine's
     schemes and the composition's own, so an operator's refusal names
     every store this build can open;
   * `:flag-parser` reads one flag this driver does not own:
     `(f arg value args opts extra)` answers `{:opts opts' :extra extra'
     :advance n}`, `n` the arguments consumed including the flag itself, or
     nil for a flag it does not own, which this driver then reads itself;
   * `:finalize` folds what the parse gathered into the resolved options,
     `(f opts extra)`, once every argument has been read;
   * `:initial-extra` is the accumulator `extra` starts as.

   A host carrying no composition parses the engine's own flags and nothing
   else, and an argument nobody owns is collected under `:extra` exactly as
   it always was."
  ([args] (parse-args args nil))
  ([args composition]
   (let [flag-parser (:flag-parser composition)
         parse-store-arg (or (:parse-store-arg composition) store/parse-arg)
         finalize (:finalize composition)]
     (loop [args (seq args)
            opts {:headless? false :host nil :port nil :rejected []
                  :index-store-spec :mem}
            extra (:initial-extra composition)]
       (if-let [arg (first args)]
         (let [value (second args)
               hooked (when flag-parser
                        (flag-parser arg value args opts extra))]
           (if (some? hooked)
             (recur (drop (:advance hooked 1) args)
                    (or (:opts hooked) opts)
                    (get hooked :extra extra))
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
                               extra)
               "--host" (throw (ex-info (str "--host is gone: --port serves on all "
                                             "interfaces, so it answers on "
                                             "localhost and on this machine's IP")
                                        {}))
               "--codec" (recur (nnext args)
                                (assoc opts :codec (parse-codec value))
                                extra)
               "--headless" (recur (next args) (assoc opts :headless? true) extra)
               "--vm" (recur (nnext args)
                             (assoc opts :vm-type (parse-vm (second args)))
                             extra)
               "--index-store" (recur (nnext args)
                                      (assoc opts
                                             :index-store-spec
                                             (parse-store-arg (second args)))
                                      extra)
               "--telemetry" (recur (next args) (update opts :rejected conj arg)
                                    extra)
               "--telemetry-stream" (recur (nnext args)
                                           (update opts :rejected conj arg)
                                           extra)
               (recur (next args) (update opts :extra (fnil conj []) arg)
                      extra))))
         (if finalize (finalize opts extra) opts))))))


(defn- release-store!
  "Release what a failed construction had already acquired: the
   composition's transport state first (its boards, dials and socket), then
   the store handle (a durable directory's exclusive lock), each exactly
   once.  A cleanup never throws over the refusal that caused it, and never
   replaces it."
  [composition opened]
  (let [close (:close! composition)
        session (:session opened)]
    (when (and close session)
      (try (close session)
           (catch #?(:cljd Object :clj Throwable :cljs :default) _ nil)))
    (try ((or (:close-store! composition) store/close!)
          (:index-store opened))
         (catch #?(:cljd Object :clj Throwable :cljs :default) _ nil))))


(defn boot
  "Create the composition: one shell, one input medium, one cursor held only by
   the step owner, and the host WebSocket adapter `(connect ...)` attaches
   through.  A custody composition (:custody, yin.vm.ucf.compose) rides the
   shell as data; the step owner below is the only thing that steps it.

   `composition` is the plain-data contribution of a storage composition
   past the engine's own two stores.  When it names the parsed
   `:index-store-spec` as one of its own (`:composition-spec?`), `:open`
   opens it and answers the two values of the ownership split: `:index-store`,
   the generic `dao.jing` handle the engine is constructed with, and
   `:session`, the transport state the composition keeps and steps beside
   the engine — never inside it.  What the engine additionally needs to
   drive that store arrives as `:engine-inputs` of the same answer (a
   prepared `:link-source`, the composition's `:host-modules`), so a
   `(reset)` or a `(vm ...)` rebuild reconstructs a session that still
   resolves signed names and still serves the composition's modules.

   The engine value lands at `:repl`, where the driver and the endpoint
   read it, and the composition's transport state at `:session`; there is
   no third place a node could hide.  A construction that fails after the
   store was opened releases what it acquired (`release-store!`) and
   answers its own refusal.

   `:admission` is the session's admission policy, by default `{}`: the
   publisher key's own principal only.  It is apart from `:principals`: a
   declared publisher is not a trusted one."
  ([opts] (boot opts nil))
  ([opts composition]
   (let [spec (:index-store-spec opts)
         open (:open composition)
         composition-spec? (:composition-spec? composition)
         comp-store (when (and open composition-spec?
                               (composition-spec? spec))
                      (open spec opts))
         repl-inputs (cond-> (if comp-store
                               (merge {:index-store (:index-store comp-store)
                                       :recovery (:recovery comp-store)
                                       :publication-policy
                                       (:publication-policy comp-store)}
                                      (:engine-inputs comp-store))
                               {:index-store-spec spec})
                       true (assoc :publisher-key (:publisher-key opts)
                                   :principals (:principals opts)
                                   :admission (:admission opts {})
                                   :custody (:custody opts))
                       (:vm-type opts) (assoc :vm-type (:vm-type opts)))
         state (try
                 (driver/create-state
                   {:host (or (:adapter opts) (host/websocket))
                    :repl (repl.frontends/create-state repl-inputs)})
                 (catch #?(:cljd Object :clj Throwable :cljs :default) e
                   (when comp-store (release-store! composition comp-store))
                   (throw e)))]
     (cond-> (assoc state :composition composition)
       (:session comp-store) (assoc :session (:session comp-store))))))


;; =============================================================================
;; The composition session
;; =============================================================================

(defn session-of
  "The composition session this host state carries: the engine at `:repl`
   beside the transport state its composition owns at `:session`, joined as
   the one value every composition function takes
   (`yin.shell.session/make`'s shape).  Nil when the host drives no
   composition.

   The two halves live apart in the host state because the driver and the
   served endpoint read the engine at `:repl` and know nothing of a
   session, while the composition reads both.  A node is never inside the
   engine value, so nothing engine-side can step one, and nothing
   composition-side has to guess which key a session hides its node under."
  [state]
  (when-some [transport (:session state)]
    (assoc transport :engine (:repl state))))


(defn with-session
  "The host state after one composition round: the engine back at `:repl`,
   where the driver reads it, and the transport state carried beside it at
   `:session`."
  [state session]
  (-> state
      (assoc :repl (:engine session))
      (assoc :session (dissoc session :engine))))


(defn- composition-step
  "One transport step of the composition this host carries, at the host's
   clock reading: `[state lines events]`, the events in the composition's
   own vocabulary.  A host with no composition answers itself unchanged and
   no events, so the engine's own tick path is the one that runs."
  [state now]
  (if-some [step (:step (:composition state))]
    (if-some [session (session-of state)]
      (let [[session' lines events] (step session now)]
        [(with-session state session') lines events])
      [state [] []])
    [state [] []]))


(defn- composition-recheck
  "One re-check of a parked run after this tick's transport events:
   `[state text]`.

   A composition drives its own re-check (`yin.shell.session`): it serves
   the link and call media the engine parks on and translates its events
   before they cross inward, so the engine re-checks only the generic
   completions correlated to the work it parked and never decodes a
   transport keyword.  A host with no composition calls the engine's own
   tick re-check, which advances the one linker round the engine serves
   itself and matches the same two generic event shapes."
  [state events]
  (if-some [recheck (:recheck-on-tick (:composition state))]
    (if-some [session (session-of state)]
      (let [[session' text] (recheck session events)]
        [(with-session state session') text])
      [state nil])
    (let [[repl text] (shell/recheck-on-tick (:repl state) events)]
      [(assoc state :repl repl) text])))


(defn- composition-stop
  "Ask the composition's transport state to stop, once: its board server,
   its dials and its socket are asked here and completed by `step`, and a
   host never reaches past the composition to any of them."
  [state]
  (if-some [stop! (:stop! (:composition state))]
    (if-some [session (session-of state)]
      (with-session state (stop! session))
      state)
    state))


(defn- composition-stopped?
  "True when the composition owes nothing at exit — no transport state, or
   transport state whose own exit condition is met.  A host with no
   composition owes nothing."
  [state]
  (if-some [stopped? (:stopped? (:composition state))]
    (boolean (stopped? (session-of state)))
    true))


(defn- composition-refusal
  "The text that refused the composition's store after startup (a socket
   that could not bind, a hydration that could not complete), or nil."
  [state]
  (when-some [refusal (:refusal (:composition state))]
    (refusal (session-of state))))


(defn- composition-admitting?
  "Whether the shell may evaluate: a composition with a hydration still
   outstanding, or one whose hydration was refused, admits nothing, and
   every typed line waits in the input medium.  A host with no composition
   always admits."
  [state]
  (if-some [admitting? (:admitting? (:composition state))]
    (boolean (admitting? (session-of state)))
    true))


(defn- composition-busy?
  "True while the composition owes the shell a line, which keeps the tick
   owner at its base cadence."
  [state]
  (boolean (when-some [busy? (:busy? (:composition state))]
             (busy? (session-of state)))))


(defn- composition-close!
  "The bounded drain's last resort: whatever the composition still owes
   after `stop!` and its budget of `step`s is asked for once more and not
   awaited."
  [state]
  (when-some [close! (:close! (:composition state))]
    (close! (session-of state))))


(defn- composition-startup-lines
  "What the composition states at startup about the session it opened —
   where it follows a published head, and whether one is installed yet —
   or nothing for a host that carries no composition."
  [state]
  (if-some [lines (:startup-lines (:composition state))]
    (vec (lines (session-of state)))
    []))


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
                   :host (or (:adapter opts) (host/websocket))
                   :codec (get connect/codec-profiles (:codec opts :cbor))})))


(def help-lines
  "What `--help` prints for a host that carries no storage composition:
   every flag `parse-args` and `expand-args` read.  A composition states
   its own subcommands, its own flags and its own stores through its
   `:help-lines` hook, so a build that composes one prints that instead."
  ["yin-repl: the interface to datom.world"
   ""
   "usage: yin-repl [flags]"
   ""
   "flags:"
   "  --port n          serve this shell to other shells over WebSockets, on"
   "                    all interfaces: localhost and this machine's IP"
   "  --codec c         wire codec served: cbor (default) or transit"
   "  --headless        no prompt, endpoint only; needs --port"
   "  --index-store s   mem (default), file:<dir>, or a store the host's"
   "                    storage composition registers"
   "  --vm type         ast-walker, semantic (default), stack or register"
   "  --name n, --dir d the node directory holding the saved state"
   "                    (default ~/.yin/node)"
   "  --reset           forget the saved state; start from the command line"
   "  --no-state        neither read nor write the saved state"
   "  --help, -h        print this and exit"
   ""
   "saved state: the flags a node starts with are kept in <node dir>/state.edn,"
   "and a bare `yin-repl` starts that node again. Flags change it: a repeated"
   "flag replaces its saved values, and the result is saved."
   ""
   "--telemetry and --telemetry-stream are rejected."
   "Unrecognized arguments are ignored."
   "See src/cljc/yin/vm/docs/yin.repl.md for the walk-through."])


(defn- composition-flag-table
  "The saved-flag table this run reads, splits, describes and writes its
   `state.edn` by: the composition's own when it declares one, so a saved
   flag of a composition's is resumed beside the engine's, and the
   engine's own table otherwise.

   `yin.repl.state` resolves nothing and reaches for no composition, so the
   table is handed to it from here — and `startup`'s `:flag-table` option
   hands one in explicitly, which is what a test or a second composition
   does."
  [composition]
  (or (:flag-table composition) (state/flag-table)))


(defn- save-state!
  "Save the flags this run resolved to the node directory's state file when
   they differ from what was saved (nothing is written for a bare run with
   nothing saved), and answer the banner's lines about it.  A file that
   cannot be written is a warning, never a refusal: the node still starts.
   `table` is the `state/flag-table` the file is described and written by."
  [dir saved resolved reset? table]
  (let [path (state/path dir)
        changed (state/changed (or saved {}) resolved)]
    (try
      (cond
        (and (nil? saved) (empty? resolved) (not reset?))
        []

        (nil? saved)
        (do (state/save! dir resolved table)
            [(str "state: " (if reset? "reset, " "") "saved to " path
                  "; a bare yin-repl starts this node again")])

        (seq changed)
        (do (state/save! dir resolved table)
            [(str "state: resumed from " path " (" (state/describe saved table)
                  "); the command line changed " (str/join ", " changed)
                  "; saved")])

        :else
        [(str "state: resumed from " path " (" (state/describe resolved table) ")")])
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

   `:composition` is the plain-data contribution of a storage composition
   (`parse-args`' and `boot`'s argument of the same name): its flags, its
   subcommands, its key file, its help and banner text, its store and its
   transport lifecycle.  Every one of those reaches this gate through that
   value and none of them is named here, so a host that carries no
   composition has the engine's own surface and nothing more.  A
   composition's `:keygen-flag` names the one argument that composes
   nothing at all: it writes a key file through `:keygen!` and answers
   `{:lines [...] :exit 0}` for the host to print and exit with, and no
   state file is read or written for such a run.  A key file named on the
   command line is loaded by `:load-key` here, and reaches `boot` as
   `:publisher-key`.

   `:adapter` is the host WebSocket adapter the shell, the composition's
   own endpoints and the served endpoint compose over (else
   `host/websocket`).  `:flag-table` is the `state/flag-table` the saved
   state is read, split, described and written by: an explicit table when
   the caller hands one in, else the composition's, else the engine's own."
  ([args] (startup args nil))
  ([args {:keys [persist? home adapter flag-table composition]}]
   (try
     (if (some #{"--help" "-h"} args)
       {:lines (or (:help-lines composition) help-lines) :exit 0}
       (let [reset? (boolean (some #{"--reset"} args))
             no-state? (boolean (some #{"--no-state"} args))
             table (or flag-table (composition-flag-table composition))
             keygen-flag (:keygen-flag composition)
             [args {:keys [new-key unset] node-name :name dir :dir}]
             (expand-args (remove #{"--reset" "--no-state"} args) composition)
             node-dir (when (and persist? (not no-state?)
                                 (not (and keygen-flag
                                           (some #{keygen-flag} args))))
                        (or dir
                            (some-> (or home (home-dir*))
                                    (str "/.yin/" node-name))))
             saved (when (and node-dir (not reset?))
                     (state/load-flags node-dir table))
             {:keys [flags rest]} (state/split-args args table)
             resolved (state/resolve-flags saved flags unset)
             args (into (state/flags->args resolved table) rest)
             made (when (and new-key (:ensure-key! composition))
                    ((:ensure-key! composition) new-key))
             opts (cond-> (try (parse-args args composition)
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
                    made (assoc :startup-lines made)
                    adapter (assoc :adapter adapter))]
         (if-some [path (:keygen opts)]
           ((:keygen! composition) path)
           (let [key (when-some [path (:publisher-key-file opts)]
                       ((:load-key composition) path))
                 opts (cond-> opts key (assoc :publisher-key key))
                 state' (boot (assoc opts
                                     :ws-host (or (:adapter opts)
                                                  (host/websocket)))
                              composition)
                 server (boot-server opts)
                 lines (into (composition-startup-lines state')
                             (when node-dir
                               (save-state! node-dir saved resolved reset?
                                            table)))]
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
     :jolt (do (flush) (System/exit 1))
     :clj (.halt (Runtime/getRuntime) 1)
     :cljs (.exit js/process 1)))


(defn- custody-of
  [state]
  (get-in state [:repl :custody]))


(defn close-index-store!
  "Release the composition's lifecycle resources before the host exits:
   the custody composition's journals first (compose/close! -- the
   authority and every journal backend the composition opened; it never
   throws, so a failed close never blocks the exit), then whatever a
   storage composition still owes, then the index store's own resources
   (in durable mode, the exclusive directory lock).  The shell has already
   stopped when a host calls this; the memory store has nothing to
   release.  A composition's endpoints have been asked to stop and drained
   by then (`stop-tick`), and its own `:close!` is the last resort for
   whatever the budget left — one resource owner, one close each."
  [state]
  (when-some [c (custody-of state)]
    (compose/close! c))
  (composition-close! state)
  ((or (:close-store! (:composition state)) store/close!)
   (get-in state [:repl :index-store])))


(defn- entry-text
  [entry]
  (or (get entry driver/text-key) (get entry serve/text-key)))


(defn banner
  "Text the composition prints before the first prompt, given parsed
   options.  A storage composition states its own share of it through its
   `:banner-lines` hook — what it will share, and what it will refuse —
   before its transport steps once, so before anything leaves the process;
   this namespace states only the engine's own lines and never names a
   store."
  ([opts] (banner opts nil))
  ([opts composition]
   (cond-> (vec (:startup-lines opts))
     (seq (:rejected opts)) (conj telemetry-text)
     (and (:port opts) (zero? (:port opts)))
     (conj (str "serving on all interfaces on an ephemeral port; the Serving"
                " line names it once bound. Anyone who can reach this port"
                " can evaluate code in this shell; there is no"
                " authentication."))

     (and (:port opts) (not (zero? (:port opts))))
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
     (:banner-lines composition)
     (into ((:banner-lines composition) opts))
     #?(:cljd false :jolt true :default false)
     (into [shell/host-text
            ;; J5: the native parser is disclosed; with no usable library
            ;; Python is left out of the catalog and the reason is printed
            #?(:jolt (if (python.antlr.parser/available?)
                       (str "python: the native C++ ANTLR4 parser"
                            " (native/jolt-parser), in process; no process"
                            " crash isolation")
                       "python: unavailable-parser (the native parser library is missing or incompatible; bb build:jolt-native)")
               :default nil)]))))


(defn- exit-with!
  "Print a composition that ends at startup -- a key file written and
   nothing composed -- and end the process with its status."
  [{:keys [lines exit]}]
  (doseq [line lines]
    (println line))
  #?(:cljd (io/exit exit)
     :jolt (do (flush) (System/exit exit))
     :clj (.halt (Runtime/getRuntime) (int exit))
     :cljs (.exit js/process exit)))


(defn- custody-control-step
  "The custody control plane's tick (r3 1.12): the renewals, pending
   releases and request retries of every holder, the judge step and the
   front steps of whatever custody composition the shell carries.  It
   runs immediately after the DHT step and before the refusal and
   admission branches, so custody progress and cleanup do not wait for
   a hydration to finish and survive a refused store.  No program work
   happens here whatever the shell's state."
  [state]
  (if-some [c (custody-of state)]
    (assoc-in state [:repl :custody] (compose/control-step c))
    state))


(defn- custody-program-step
  "The custody program step's tick: activation, execution and export
   preparation under the split driver's own revalidated tenure.  It runs
   after the shell's own step and before the endpoint's, and never once
   the shell has stopped -- the stop latch is set in the shell's place."
  [state]
  (if-some [c (custody-of state)]
    (assoc-in state [:repl :custody] (compose/program-step c))
    state))


(defn- custody-stop
  "Latch the local stop on every holder of the shell's custody
   composition (compose/stop): program work ends, control cleanup
   continues through the bounded shutdown drain.  Idempotent, and a
   no-op with no composition."
  [state]
  (if-some [c (custody-of state)]
    (assoc-in state [:repl :custody] (compose/stop c))
    state))


(defn- custody-owed?
  "True while the shell's custody composition owes a control-plane
   write (compose/owed-control-write?): a pending write keeps the tick
   owner's cadence at the base interval, like the driver's own."
  [state]
  (if-some [c (custody-of state)]
    (compose/owed-control-write? c)
    false))


(defn step-all
  "One tick of the single step owner: the local shell first, then the served
   endpoint, against the same shell value.  A storage composition's
   transport state is stepped first (`composition-step`): its lines print
   before the shell's, a hydration still outstanding leaves every typed
   line waiting in the input medium, and a refused one stops the shell.
   The custody control plane is stepped next, before the refusal and
   admission branches, so renewals, pending releases and request retries
   continue during hydration and past a refusal.  A `--port` process
   serves one shared shell, as v1's atom made it: the driver evaluates this
   tick's local
   lines first, so a definition typed at the local prompt is already in the
   shell the endpoint evaluates remote requests against in the same tick, and
   the endpoint's shell (remote definitions included) is threaded back
   before the next tick.  The custody program step follows the driver's own
   step and precedes the endpoint's; once the shell has stopped it is
   replaced by the stop latch, so no custody program call runs after
   `:running?` turns false.  A run parked on a completion this tick's
   transport step produced is re-checked once, before any typed line and
   with no line of its own, by the composition's own re-check — which
   serves the link and call media the engine parks on and translates the
   events before they cross inward, so linker progress resumes a parked run
   and its text prints after the composition's lines.  Returns
   `[state server lines]`; the caller only prints."
  [state server now]
  (let [[state comp-lines events] (composition-step state now)
        state (custody-control-step state)]
    (cond
      (composition-refusal state)
      [(custody-stop (assoc state :running? false)) server comp-lines]

      (not (composition-admitting? state))
      [state server comp-lines]

      :else
      (let [[state rechecked] (composition-recheck state events)
            comp-lines (cond-> comp-lines
                         (seq rechecked) (conj rechecked))
            stepped (driver/repl-step state now)
            stepped (if (:running? stepped)
                      (custody-program-step stepped)
                      (custody-stop stepped))
            [entries state'] (driver/take-outbox stepped)
            server' (when server
                      (serve/step (assoc-in server [:repl] (:repl state'))
                                  now))
            [server-entries server''] (if server'
                                        (serve/take-outbox server')
                                        [[] nil])
            state'' (if server'' (assoc state' :repl (:repl server'')) state')]
        [state'' server'' (into comp-lines
                                (map entry-text)
                                (into (vec entries) server-entries))]))))


(defn moved?
  "The tick owner's cadence bit, computed from this tick's own results: true
   when there are lines to print, when the endpoint reports movement (a
   woken probe, a published notice), or when either composition still owes a
   write -- the shell's own, a storage composition's, or a custody
   control-plane one.  A pending write
   keeps cadence at the base interval; it must never wait out a backoff
   ceiling."
  [state server lines]
  (boolean (or (seq lines)
               (driver/pending-write? state)
               (composition-busy? state)
               (custody-owed? state)
               (and server (serve/moved? server)))))


(defn prompt-owed?
  "True when the operator is owed a fresh prompt after a tick: the tick
   printed lines, or it consumed input that printed nothing (a silent
   assignment), which only the input cursor's advance reveals."
  [state state' lines]
  (boolean (or (seq lines)
               (not= (:input-cursor state) (:input-cursor state')))))


(defn exit-status
  "The process status a host exits with once the shell stops: 1 when a
   storage composition's store refused after startup (a socket that could
   not bind, a hydration that could not complete), else 0."
  [state]
  (if (composition-refusal state) 1 0))


;; =============================================================================
;; Shutdown
;; =============================================================================

(def stop-ticks
  "How many ticks a host steps a stopping endpoint before exiting anyway; each
   tick lasts at least `tick-millis`.  The
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
  "One shutdown tick for a storage composition's transport state and the
   served endpoint, both of which have already been asked to stop
   (`composition-stop`, `serve/stop!`).  Returns `[state server lines
   stopped?]`, the composition's lines before the endpoint's.

   `serve/stopped?` is the whole of the endpoint's exit condition, and the
   composition's own `:stopped?` is the whole of its transport's: an
   endpoint that never bound is done on the first tick, because no host
   exists to report a `:stopped` fact for it, an absent endpoint -- a shell
   run without `--port` -- never had one, and so is a shell whose
   composition has no endpoint of its own.  The custody
   composition is not stepped here: `shutdown-tick` is the drain tick that
   wraps this one with the custody control plane."
  [state server now]
  (let [[state comp-lines _] (composition-step state now)
        [server' entries] (if server
                            (let [[entries server'] (serve/take-outbox
                                                      (serve/step server now))]
                              [server' entries])
                            [nil []])]
    [state server'
     (into (vec comp-lines) (map entry-text) entries)
     (and (or (nil? server') (serve/stopped? server'))
          (composition-stopped? state))]))


(defn shutdown-enter?
  "Whether a shell that just stopped enters the bounded shutdown drain at
   all (r3 1.12 as amended by residual 4 and the D15 gate's shutdown row):
   there is an endpoint to stop, a storage composition's transport state
   that still owes its stop-and-drain, or a custody composition that still
   owes a control-plane write.  A REPL run without --port enters the drain
   on its owed custody writes alone -- a release owed at quit is sent, never
   dropped by the process exit."
  [state server]
  (boolean (or server
               (:session state)
               (custody-owed? state))))


(defn shutdown-drained?
  "The bounded shutdown drain's exit condition, the one every host loop
   tests: the endpoint has stopped -- an absent endpoint owes nothing --
   AND the storage composition's transport state owes nothing at exit, AND
   the custody composition owes no further control-plane write.  A pending
   release, renewal or undelivered reply still owed keeps the drain
   stepping within its `stop-ticks` budget; the budget, not this predicate,
   is what bounds a write that can never complete."
  [state server]
  (boolean (and (serve/stopped? server)
                (composition-stopped? state)
                (not (custody-owed? state)))))


(defn shutdown-tick
  "One tick of the bounded shutdown drain (r3 1.12, residual 4), the tick
   every host's drain loop calls: the custody control plane keeps running --
   the holders' local stop latch is set here, at drain entry, on every host,
   including paths that reached the drain without another normal `step-all`
   -- and the head board and the endpoint drain (an absent endpoint drains
   nothing and answers stopped).  No custody program step runs: program
   execution stays stopped once the shell has.  Returns
   `[state server lines stopped?]`; the budget (`stop-ticks`) and the exit
   condition (`shutdown-enter?`, `shutdown-drained?`) are the caller's."
  [state server now]
  (let [state (custody-control-step (custody-stop state))]
    (stop-tick state server now)))


;; =============================================================================
;; clj: a reader thread that only appends, and one owned poller thread
;; =============================================================================

(defn- drain-owner!
  "The shell has quit, so the head board, the endpoint and the custody
        control plane stop before the host exits: ask the board and the
        endpoint once, then keep stepping all three until the endpoint has
        stopped AND the board owes nothing AND custody owes no further
        control write (`shutdown-drained?`), or the bounded budget runs
        out.  The drain is entered at all only when one of them owes
        something (`shutdown-enter?` -- a shell with no endpoint enters it
        on owed custody writes alone).  A connected client must observe the
        ended answer, not the bare close a process exit would leave behind.
        Each drain round lasts at least the base interval of wall time,
        however many early wakes readiness causes; the budget counts rounds,
        so the drain is at least `tick-millis x stop-ticks` of wall time.
        The custody control plane steps on every one of its ticks
        (`shutdown-tick`); the state it reaches is durable in the progress
        journal, so what the budget leaves outstanding is a restart's to
        resend.  Answers the drained state."
  [state server now sleep!]
  (if (shutdown-enter? state server)
    (loop [state (composition-stop state)
           server (some-> server serve/stop!)
           remaining stop-ticks]
      (let [[state' server' lines _stopped?]
            (shutdown-tick state server (now))]
        (doseq [line lines]
          (println line))
        (flush)
        (cond
          (shutdown-drained? state' server') state'
          (zero? remaining) (do (println stop-timeout-text) state')
          :else (let [round-end (+ (now) tick-millis)]
                  ;; a sleep readiness ends early pumps, then sleeps the
                  ;; rest: an early wake cannot spend a round (R17)
                  (loop []
                    (sleep! (max 0 (- round-end (now))))
                    (when (< (now) round-end) (recur)))
                  (recur state' server' (dec remaining))))))
    state))


(defn run-owner!
  "Serial shell owner shared by the JVM and jolt. Host policy supplies
   clock, interruptible sleep and status-aware exit. No producer evaluates."
  [state server headless? now sleep! exit!]
  (loop [state state
         server server
         cadence (cadence/init default-cadence)]
    (let [[state' server' lines] (step-all state server
                                           (now))]
      (doseq [line lines]
        (println line))
      (flush)
      (if (:running? state')
        (do (when (and (not headless?) (prompt-owed? state state' lines))
              (do (print prompt) (flush)))
            (let [{:keys [cadence-state sleep-ms]}
                  (cadence/cadence-step cadence
                                        (moved? state' server' lines))]
              (sleep! sleep-ms)
              (recur state' server' cadence-state)))
        (let [state' (drain-owner! state' server' now sleep!)]
          (println)
          (close-index-store! state')
          (flush)
          (exit! (exit-status state')))))))


#?(:cljd
   nil
   :jolt nil
   :clj
   (do
     (defn- print-prompt!
       []
       (print prompt)
       (flush))

     (defn poll-loop!
       "JVM policy wrapper around the portable serial owner."
       ([state server headless?]
        (poll-loop! state server headless? #(.halt (Runtime/getRuntime) 0)))
       ([state server headless? exit!]
        (poll-loop! state server headless? exit! (wake/make-wake)))
       ([state server headless? exit! w]
        (run-owner! state server headless? #(System/currentTimeMillis)
                    #(wake/sleep! w %)
                    (fn [status]
                      (if (zero? status) (exit!)
                          (.halt (Runtime/getRuntime) (int status)))))))

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

     (defn main!
       "The JVM entry point body: compose from `args` and this host's
        `composition`, print the banner, and run the reader thread beside
        the one owned poller thread until the poller exits the process.

        `composition` is the plain-data contribution of a storage
        composition, nil for a host that carries none.  `yin.shell.main`
        supplies the one this project ships; `-main` here drives the
        engine alone."
       [args composition]
       (let [started (startup args {:persist? true
                                    :composition composition})]
         (when (contains? started :exit) (exit-with! started))
         (if-some [refusal (:refusal started)]
           (refuse! refusal)
           (let [{:keys [opts state server]} started
                 headless? (boolean (:headless? opts))
                 w (wake/make-wake)]
             (doseq [line (banner opts composition)]
               (println line))
             (let [poller (Thread.
                            ^Runnable (fn []
                                        (poll-loop!
                                          state
                                          server
                                          headless?
                                          #(.halt (Runtime/getRuntime) 0)
                                          w)))]
               (.setDaemon poller true)
               (.start poller)
               ;; Headless attends the endpoint only: there is no reader, so the
               ;; step owner is joined until it stops, and the explicit stop
               ;; trigger is the host signal a shutdown hook observes.  The hook
               ;; appends a line like any producer, nudges, and then waits for
               ;; the one step owner.
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
                     ;; by the time this join is reached in that case.  The join
                     ;; is unbounded: lines read before end-of-input still
                     ;; evaluate ahead of its `(quit)`, and the step owner bounds
                     ;; its own shutdown drain (`stop-ticks`) and exits the
                     ;; process itself.
                     (read-loop! (:input state) w)
                     (.join poller))))
             (.halt (Runtime/getRuntime) 0)))))

     (defn -main
       "The engine's own JVM entry point: the `mem` and `file:<dir>` stores
        and no storage composition.  The shipped product entry point is
        `yin.shell.main/-main`, which supplies one."
       [& args]
       (main! args nil))))


#?(:jolt
   (do
     (defn main!
       "One plain-thread owner. Stdin deposits only; EOF deposits (quit).
        One host WebSocket instance serves the shell, a storage
        composition's own endpoints and --port; every owner round polls its
        sockets (headless included) and the instance is closed before exit,
        however the drain ended.  Saved flags persist in the node
        directory, as on the JVM (J4d): --reset forgets them and --no-state
        neither reads nor writes them.

        `composition` is the plain-data contribution of a storage
        composition, nil for a host that carries none."
       [args composition]
       (let [adapter (host/websocket)
             started (startup args {:persist? true :adapter adapter
                                    :composition composition})]
         (when (contains? started :exit) (exit-with! started))
         (if-some [refusal (:refusal started)]
           (refuse! refusal)
           (let [{:keys [opts state server]} started
                 headless? (boolean (:headless? opts))
                 eof? (atom false)
                 w (wake/make-wake)
                 line! (fn [line]
                         (if (nil? line)
                           (do (reset! eof? true)
                               (driver/submit-line! (:input state) "(quit)"))
                           (driver/submit-line! (:input state) line)))
                 sleep! (fn [ms]
                          (wake/sleep-fds! w ms (:pump adapter)
                                           (when-not (or headless? @eof?) line!)))]
             (doseq [line (banner opts composition)] (println line))
             (when-not headless? (print prompt))
             (flush)
             ;; the exit status leaves through System/exit, which skips the
             ;; finally, so the exit path closes the instance itself too
             (try
               (run-owner! state server headless? #(System/currentTimeMillis)
                           sleep! (fn [status]
                                    ((:close-host! adapter))
                                    (wake/close! w)
                                    (flush)
                                    (System/exit status)))
               (finally
                 ((:close-host! adapter))
                 (wake/close! w)))))))

     (defn -main
       "The engine's own jolt entry point: the `mem` and `file:<dir>` stores
        and no storage composition.  The shipped product entry point is
        `yin.shell.main/-main`, which supplies one."
       [& args]
       (main! args nil))))


;; =============================================================================
;; cljs (Node): a readline handler that only appends, and one interval owner
;; =============================================================================

#?(:cljs
   (do
     (defn run-node!
       "The tick owner on Node: one wake source arms exactly one timer per
        round, at the interval `cadence-step` computes over
        `default-cadence`.  Returns the wake so the composition can wire its
        line producers (the readline handlers, the stop signals) as
        `nudge!` callers.

        `repl-step` is synchronous and the Node event loop is single
        threaded, so a tick cannot overlap itself.  The box is host cadence
        plumbing: the tick is the only reader and writer of it.  `:stopping`
        is nil while the shell runs and a tick budget afterwards: the drain
        is entered when there is an endpoint to stop, a head board that
        still owes its stop-and-drain, or custody owes a control write
        (`shutdown-enter?` -- a REPL without --port drains its owed custody
        writes too), the head board and the endpoint are asked to stop
        once, and the drain steps at the base interval until the endpoint
        has stopped AND the board owes nothing AND custody owes nothing
        (`shutdown-drained?`) or the budget ends.  `exit!`, the process
        exit by default, is the seam a test injects to drive the owner to
        its end."
       ([state server rl] (run-node! state server rl #(js/process.exit %)))
       ([state server rl exit!]
        (let [box (atom {:state state :server server :stopping nil
                         :cadence (cadence/init default-cadence)})
              wake-ref (volatile! nil)
              finish! (fn []
                        (wake/disarm! @wake-ref)
                        (when rl (.close rl))
                        (close-index-store! (:state @box))
                        (exit! (exit-status (:state @box))))
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
                               (when (and rl (prompt-owed? state state' lines))
                                 (.prompt rl))
                               (wake/arm! @wake-ref sleep-ms))

                             (shutdown-enter? state' server')
                             (do (swap! box assoc
                                        :state (composition-stop state')
                                        :server (when server'
                                                  (serve/stop! server'))
                                        :stopping stop-ticks)
                                 (wake/arm! @wake-ref tick-millis))

                             :else (finish!)))
                         (let [[state' server' lines _stopped?]
                               (shutdown-tick state server (js/Date.now))]
                           (swap! box assoc :state state' :server server')
                           (doseq [line lines]
                             (js/console.log line))
                           (cond
                             (shutdown-drained? state' server') (finish!)
                             (zero? stopping)
                             (do (js/console.log stop-timeout-text) (finish!))
                             :else (do (swap! box assoc :stopping (dec stopping))
                                       (wake/arm! @wake-ref tick-millis)))))))]
          (vreset! wake-ref (wake/make-wake tick))
          (wake/arm! @wake-ref tick-millis)
          @wake-ref)))

     (defn main!
       "The Node entry point body: compose from `args` and this host's
        `composition`, print the banner, and arm the one tick owner, wiring
        each line producer to the nudge it hands back.

        `composition` is the plain-data contribution of a storage
        composition, nil for a host that carries none.  `yin.shell.main`
        supplies the one this project ships; `-main` here drives the engine
        alone."
       [args composition]
       (let [started (startup args {:persist? true
                                    :composition composition})]
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
             (doseq [line (banner opts composition)]
               (js/console.log line))
             ;; The tick owner arms its first round, and the composition wires
             ;; the deposit it hands each line producer with the nudge: a typed
             ;; line ends the idle sleep at once.
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
                 ;; host signal: it appends a line, nudges, and returns, like
                 ;; any producer.
                 (doseq [signal ["SIGINT" "SIGTERM"]]
                   (.on js/process signal (fn []
                                            (request-stop! state
                                                           w))))))))))

     (defn -main
       "The engine's own Node entry point: the `mem` and `file:<dir>` stores
        and no storage composition.  The shipped product entry point is
        `yin.shell.main/-main`, which supplies one."
       [& args]
       (main! args nil))))


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

     (defn run-dart!
       "One wake source owns the tick: exactly one `Timer` armed per round, at
        the interval `cadence-step` computes over `default-cadence`, because a
        synchronous poll loop would deadlock the Dart event loop: IO never
        progresses, so `blocked` never clears.  Returns the wake so the
        composition can wire its line producers (the stdin listener, the
        stop signals) as `nudge!` callers.

        `:stopping` is nil while the shell runs and a tick budget afterwards:
        the drain is entered when there is an endpoint to stop, a head
        board that still owes its stop-and-drain, or custody owes a
        control write (`shutdown-enter?` -- a REPL without --port drains
        its owed custody writes too), the head board and the endpoint are
        asked to stop once, and the drain steps at the base interval
        until the endpoint has stopped AND the board owes nothing AND
        custody owes nothing (`shutdown-drained?`) or the budget ends, so
        the host does not exit with a live listener or a dropped
        release.  `exit!`, the process exit by default, is the seam
        a test injects to drive the owner to its end."
       ([state server headless?]
        (run-dart! state server headless? #(io/exit %)))
       ([state server headless? exit!]
        (let [box (atom {:state state :server server :stopping nil
                         :cadence (cadence/init default-cadence)})
              wake-ref (volatile! nil)
              finish! (fn []
                        (wake/disarm! @wake-ref)
                        (close-index-store! (:state @box))
                        (exit! (exit-status (:state @box)))
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
                               (when (and (not headless?)
                                          (prompt-owed? state state' lines))
                                 (print-prompt!))
                               (wake/arm! @wake-ref sleep-ms))

                             (shutdown-enter? state' server')
                             (do (swap! box assoc
                                        :state (composition-stop state')
                                        :server (when server'
                                                  (serve/stop! server'))
                                        :stopping stop-ticks)
                                 (wake/arm! @wake-ref tick-millis)
                                 nil)

                             :else (finish!)))
                         (let [[state' server' lines _stopped?]
                               (shutdown-tick state server now)]
                           (swap! box assoc :state state' :server server')
                           (doseq [line lines]
                             (write-line! line))
                           (cond
                             (shutdown-drained? state' server') (finish!)
                             (zero? stopping) (do (write-line! stop-timeout-text)
                                                  (finish!))
                             :else (do (swap! box assoc :stopping (dec stopping))
                                       (wake/arm! @wake-ref tick-millis)
                                       nil))))))]
          (vreset! wake-ref (wake/make-wake tick))
          (wake/arm! @wake-ref tick-millis)
          @wake-ref)))

     (defn main!
       "The Dart entry point body: compose from `args` and this host's
        `composition`, print the banner, and arm the one timer owner,
        wiring each line producer to the nudge it hands back.

        `composition` is the plain-data contribution of a storage
        composition, nil for a host that carries none.  `yin.shell.main`
        supplies the one this project ships; `-main` here drives the engine
        alone."
       [args composition]
       (let [started (startup args {:persist? true
                                    :composition composition})]
         (when (contains? started :exit) (exit-with! started))
         (if-some [refusal (:refusal started)]
           (refuse! refusal)
           (let [{:keys [opts state server]} started
                 headless? (boolean (:headless? opts))]
             (doseq [line (banner opts composition)]
               (write-line! line))
             ;; The tick owner arms its first round, and the composition wires
             ;; the deposit it hands each line producer with the nudge: a typed
             ;; line ends the idle sleep at once.  A timer fires only on the
             ;; event loop, so arming before the producers are wired races
             ;; nothing.
             (let [w (run-dart! state server headless?)]
               (if headless?
                 ;; Headless has no reader, so the explicit stop trigger is the
                 ;; host signal: it appends a line, nudges, and returns, like
                 ;; any producer.
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

     (defn -main
       "The engine's own Dart entry point: the `mem` and `file:<dir>` stores
        and no storage composition.  The shipped product entry point is
        `yin.shell.main/-main`, which supplies one."
       [& args]
       (main! args nil))

     (defn ^{:dart/name main} run-main
       [args]
       (apply -main args))))

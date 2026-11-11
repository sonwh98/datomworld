
(ns yin.shell.dht
  "The DHT index-store composition, `--index-store dht:<dir>` (DHT epic
   slice S5: docs/design/dao.jing.dht.md section 10; docs/design/
   yin.repl.dao.space-index.md, \"DHT store\"), relocated out of the
   engine tree by docs/design/yin.repl.dht-composition.md sections 2, 6
   and 7.

   This namespace is the storage implementation the engine never names.
   It owns store selection and opening, HEAD persistence, announce,
   signed-head deposit and following, hydration, transport stepping,
   scheduling, banners, refusal rendering and shutdown — the whole of
   section 3's \"Composition ownership\" row — and it crosses inward only
   what section 4 allows: a generic `dao.jing` content handle, validated
   recovery data, an immutable publication policy, and correlated generic
   completions.

   It is composed with the plain Clojure path, `dao.space.dht`, and adds
   only what the shell owns: the durable directory, the lines it prints,
   and when it admits evaluation.

   `open` opens `<dir>` exactly as `file:<dir>` does: the exclusive lock,
   the content log, HEAD, the validated snapshot (dao.space.store), and
   joins a `dao.space.dht` node with that locked store as its `:local`.
   The indexer publishes through the node's store: each put inserts
   locally and appends one replicate request, and the manifest reads back
   locally, so a round never waits on the network.  The HEAD write then
   announces the publication to the node.  Section 3's ordering contract
   is what that write preserves — materialize/read-back, then durable HEAD
   success, then announce and deposit — and nothing here restates or
   reorders it: `open` answers the engine's handle and the composition's
   node as two separate values, so the engine is never handed something
   whose transport state it could step.

   `step` is the node's one step owner, called by the host's single
   ticker (yin.shell.main/step-all) with the host's clock reading; it
   renders the node's events as the lines the REPL prints: where the
   socket bound, and for each publication its result (acknowledged,
   sent to N peers, or partial or not acknowledged with the count of
   blobs not sent, the first reason, and whether the node is retrying)
   and each later change of it (yin.vm.linker.dht.md 5.5.6).  A load's
   failure reason is data; it is rendered as text here.

   A reader handed a manifest address (`:manifest`, a pin) loads it
   through `dao.space.dht/load-index` before the shell admits any
   evaluation, as a restart installs its recovery first, then writes it
   to HEAD and rehydrates the indexer from the loaded datoms, so `q`
   answers the remote run's facts.  A load that cannot complete, or a
   socket that cannot bind, refuses the shell (`refusal`); it never
   starts over an empty index.  Remote indexes are also reachable at the
   prompt, through the `dao.space.dht` host module (yin.shell.query),
   over the same node.  Hydration gates evaluation input
   (`admitting?`); head following never does — following updates the
   resolver, and only an explicit pinned hydration installs recovery
   before evaluation is admitted (section 3).

   The published head trace (docs/design/yin.vm.linker.dht.head.md,
   slice H3) is composed here too, the shell owning the file, the lines
   and the dial:
   * A publisher (a key, a socket and `:publish?`) deposits its HEAD's
     trace on its board (`yin.vm.linker.head/deposit!`) at every HEAD
     write, after `announce!`, at startup for a recovered HEAD, and once
     when a hydration completes (5.4).  Once its socket is bound on a
     loopback literal it serves the board at the same port number
     (`yin.vm.linker.head.board/serve`) and prints the join
     token `yin:<host:port>/<principal>` when that endpoint is bound.
   * A reader (`:follow`, each `{:principal hex :host h :port p}` with a
     loopback host) restores `<dir>/heads.edn` before any connection
     (5.7), dials each principal's board at the address it was given and
     nowhere else, steps the follower after the node, and installs a
     confirmed head only after `heads.edn` holds it.

   Shutdown closes each of those resources once, under this namespace's
   ownership: `stop!` asks, `step` completes, `stopped?` reports, and
   `close!` is the bounded drain's last resort.  A host never reaches
   past this seam to a board, a dial or a socket."
  (:require #?(:cljd [clojure.edn :as edn]
               :clj [clojure.edn :as edn]
               :cljs [cljs.reader :as reader])
            [clojure.string :as str]
            [dao.jing :as jing]
            [dao.space.dht :as dht]
            [dao.space.index :as space.index]
            [dao.space.store :as durable]
            [dao.space.store.fs :as fs]
            [dao.stream :as stream]
            [dao.stream.datagram :as datagram]
            [dao.stream.remote-channel :as remote-channel]
            [dao.stream.ringbuffer :as ringbuffer]
            [yin.repl :as repl]
            [yin.repl.connect :as connect]
            [yin.shell.registry :as shell.registry]
            [yin.vm.linker.dht :as ld]
            [yin.vm.linker.head :as head]
            [yin.vm.linker.head.board :as head.board]
            [yin.vm.linker.interpreter :as link]
            [yin.vm.linker.pair :as link.pair]
            [yin.vm.linker.sign :as sign]))


(def default-max-inbound-bytes
  "The inbound storage bound `dao.space.dht` itself defaults to."
  dht/default-max-inbound-bytes)


(def cli-max-inbound-bytes
  "The CLI default of the inbound storage bound: 64 MiB of payload a
   publishing node accepts from peers' `:store` requests before it
   refuses them explicitly.  `--dht-max-inbound-bytes` overrides it."
  (* 64 1024 1024))


(def default-repair-slots
  "The repair writes this store's node holds outstanding at once: three
   times `dao.space.dht`'s default and still below its pending-write
   bound, so the node draws no `/busy` and fresh writes keep a bound of
   their own while a repair is queued.  This store publishes 64-key index
   pages (`compose`), so a round carries about twice the addresses the
   generic 512-key page did, and every live publication repairs its own:
   a write no peer accepts holds its slot for the DHT's whole `ack-ticks`,
   so at 16 slots one cycle of a full `:max-repairing` set outlasts the
   cycle's own period, the node's queues never empty, and the host ticker
   never leaves its base cadence (`busy?`, yin.repl.main/moved?).  One
   cycle drains inside the shortest period again at three times the
   slots.

   Repair capacity stays measurement-driven
   (docs/design/yin.repl.dht-composition.md sections 6 and 12, R4/R6):
   no entitlement attaches to this number, and step 5 of that sequence
   re-measures it in this composition."
  48)


(def heads-file
  "The follower's durable state in the store directory (5.7)."
  "heads.edn")


(def heads-max-bytes
  "The most bytes a `heads.edn` may hold: 64 followed principals take a
   few tens of KiB, so a larger file is refused before it is decoded."
  (* 1024 1024))


(defn- refused-head
  "Why a reader cannot hydrate `manifest` into a directory whose own HEAD
   already names `recovered`, or nil.  Hydration never replaces an index
   the directory holds."
  [dir manifest recovered]
  (when (and manifest recovered (not= manifest recovered))
    (str dir " already holds an index (HEAD names " recovered "); hydrate "
         manifest " into an empty directory")))


;; =============================================================================
;; The store spec (docs/design/yin.repl.dht-composition.md section 6: the
;; scheme parsing and the checked spec move out of the engine's store
;; selection into the composition's own factory)
;; =============================================================================

(def dht-scheme
  "The `--index-store` scheme prefix naming this composition's store: the
   durable directory store of `file:<dir>` composed under a
   `dao.jing.dht` node."
  "dht:")


(def ^:private supported-args
  [(str dht-scheme "<dir>")])


(defn parse-arg
  "A `dht:<dir>` `--index-store` value as the spec `{:type :dht :dir
   dir}`, or nil when `value` does not name this composition's store.  An
   empty directory is refused here — with the supported form named — so
   the arguments parser refuses a bad flag before anything composes."
  [value]
  (when (and (string? value) (str/starts-with? value dht-scheme))
    (let [dir (subs value (count dht-scheme))]
      (if (str/blank? dir)
        (throw (ex-info "--index-store dht:<dir> needs a directory"
                        {:value value
                         :supported supported-args}))
        {:type :dht :dir dir}))))


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


(def dht-defaults
  "The DHT store spec's defaults: solo (no peers), publishing off, bound
   to loopback on an ephemeral port, the default inbound bound, and no
   manifest to hydrate."
  {:peers []
   :publish? false
   :bind-host "127.0.0.1"
   :bind-port 0
   :max-inbound-bytes cli-max-inbound-bytes
   :manifest nil})


(defn checked-spec
  "Validate a `{:type :dht :dir dir}` spec as this composition takes it,
   `dht-defaults` filled in.  Anything else — a spec of another store's
   shape, or one this build cannot compose — is refused with the reason.

   The engine's own store selection (`yin.repl.store/checked-spec`) knows
   `:mem` and `{:type :file :dir dir}` and nothing else: a transport
   composition checks its own spec here, so the two never disagree about
   what a `dht:` store is
   (docs/design/yin.repl.dht-composition.md section 6)."
  [spec]
  ;; jolt: a :file store opens since J4; this composition needs J7's UDP
  ;; transport, so it is refused here, where a spec is checked for
  ;; composition, after every flag has been parsed and validated as on
  ;; the other hosts
  #?(:jolt
     (throw (ex-info "the dht: index store arrives at J7 on jolt"
                     {:spec spec})))
  (if (and (map? spec) (= :dht (:type spec)))
    (let [spec (merge dht-defaults spec)]
      (if-some [refusal (dht-refusal spec)]
        (throw (ex-info refusal {:spec (dissoc spec :bind!)}))
        spec))
    (throw (ex-info (str "not a dht store spec: " (pr-str spec)
                         "; supported: {:type :dht :dir <dir> ...}")
                    {:spec spec
                     :supported [{:type :dht :dir "<dir>"}]}))))


;; =============================================================================
;; heads.edn (5.7)
;; =============================================================================

(defn- read-edn
  [text]
  #?(:cljd (edn/read-string text)
     :clj (edn/read-string text)
     :cljs (reader/read-string text)))


(defn- read-one
  "The one EDN form `text` holds, or ::unreadable: a trailing form, a
   truncated suffix and an empty text are refused, as on every host's
   `read-string` alone they are not.  The text is read inside a vector
   closed by a sentinel no file can predict, so the first form that
   reads is the whole vector only when the text is exactly one complete
   form (whitespace and comments around it allowed)."
  [text]
  (let [end (keyword "yin.shell.dht" (str "end-" (random-uuid)))
        ;; a newline ends a trailing comment; no whitespace precedes the
        ;; closer (ClojureDart's reader refuses one)
        forms (read-edn (str "[" text "\n" end "]"))]
    (if (and (vector? forms) (= 2 (count forms)) (= end (second forms)))
      (first forms)
      ::unreadable)))


(defn parse-heads
  "The records `text`, a `heads.edn` read from `path`, holds:
   `{:records {:version 1 :heads {principal trace}}}`, or `{:refusal
   text}` when it is not exactly one readable EDN form or not a version-1
   heads file.
   Never throws.  The records themselves are verified by
   `yin.vm.linker.head/follow`."
  [path text]
  (let [data (try (read-one text)
                  (catch #?(:cljd Object :clj Throwable :cljs :default) _
                    ::unreadable))]
    (cond
      (= ::unreadable data)
      {:refusal (str "heads file " path ": it is not readable EDN")}

      (not (and (map? data) (= 1 (:version data)) (map? (:heads data))
                (= #{:version :heads} (set (keys data)))))
      {:refusal (str "heads file " path ": it is not a version-1 heads file")}

      :else {:records data})))


(defn render-heads
  "The text of `records`: one EDN map, with no whitespace before a
   closing bracket (ClojureDart's reader refuses one)."
  [records]
  (str (pr-str records) "\n"))


(defn- without-boms
  "`text` without its leading byte order marks, every one of them: one
   policy on every host, whose decoders differ (Node's drops one, the
   JVM's keeps U+FEFF).  The run is scanned by index and cut once, so a
   file of marks costs one pass, not one copy per mark."
  [text]
  (let [n (count text)
        i (loop [i 0]
            (if (and (< i n) (= "\uFEFF" (subs text i (inc i))))
              (recur (inc i))
              i))]
    (if (zero? i) text (subs text i))))


(defn read-heads
  "The persisted head records of the store directory `dir`: `{:records
   r}`, an empty version-1 record when no `heads.edn` exists, or
   `{:refusal text}`: a file that cannot be read, holds more than
   `heads-max-bytes`, is not valid UTF-8, holds a byte order mark
   anywhere but at its start, or is not exactly one version-1 record.
   Never throws."
  [dir]
  (let [path (str dir "/" heads-file)
        bs (try (fs/read-bounded path heads-max-bytes)
                (catch #?(:cljd Object :clj Throwable :cljs :default) e
                  {::failed (or (ex-message e) (str e))}))
        text (when (and (some? bs) (not (map? bs))
                        (<= (fs/byte-length bs) heads-max-bytes))
               (try (fs/decode-utf8 bs)
                    (catch #?(:cljd Object :clj Throwable :cljs :default) _
                      ::invalid)))
        body (when (string? text) (without-boms text))]
    (cond
      (nil? bs) {:records {:version 1 :heads {}}}
      (map? bs) {:refusal (str "heads file " path ": it cannot be read ("
                               (::failed bs) ")")}
      (nil? text) {:refusal (str "heads file " path ": it holds more than "
                                 heads-max-bytes " bytes")}
      (= ::invalid text) {:refusal (str "heads file " path
                                        ": it is not valid UTF-8")}
      ;; a mark past the leading run is refused before the reader sees
      ;; it: one host's reader takes it for whitespace, another's for a
      ;; token, and a record never holds one
      (str/includes? body "\uFEFF")
      {:refusal (str "heads file " path ": it is not valid: a byte order "
                     "mark (U+FEFF) follows its start")}

      :else (parse-heads path body))))


;; =============================================================================
;; The board and the deposit (5.4)
;; =============================================================================

(defn- ring
  [n]
  (:dao.stream/handle
    (ringbuffer/create! {:dao.stream/type ringbuffer/transport-type
                         ringbuffer/capacity-key n})))


(defn- deposit-head!
  "Deposit the trace of `manifest`, read from `store`, on `board`; a
   refusal is appended to `notes` for the step to print."
  [{:keys [board key dht-store notes]} manifest]
  (let [r (try (head/deposit! board key manifest
                              (space.index/read-datoms dht-store manifest))
               (catch #?(:cljd Object :clj Throwable :cljs :default) e
                 {:status :refused :reason :yin.head/unreadable
                  :manifest manifest :detail (or (ex-message e) (str e))}))]
    (when (= :refused (:status r))
      (stream/append! notes r))
    r))


(defn- publisher
  "The board composition of a publishing node: a key, a socket (peers)
   and `:publish?` (5.4), or nil."
  [{:keys [peers publish?]} key base]
  (when (and (map? key) (:seed key) (:public key) publish? (seq peers))
    (let [notes (ring 64)]
      {:board (head/board!)
       :key key
       :principal (sign/principal (:public key))
       :dht-store base
       :notes notes
       :notes-cursor (:dao.stream/cursor
                       (stream/cursor notes stream/anchor-newest))})))


;; =============================================================================
;; Opening the store
;; =============================================================================

(defn followed-principals
  "The principal ids, `ed25519:<hex>`, a DHT store spec follows."
  [spec]
  (mapv #(sign/principal (:principal %)) (:follow spec)))


(def default-branching-factor
  "The count cap this composition declares: `dao.space.index`'s own
   default.  The hardcoded 64 the coupled composition carried as a
   message-size workaround is retired and not relocated as permanent
   policy — the byte ceiling is what bounds a publication now, and a count
   cap is an index construction policy, not a transport's capacity
   (docs/design/yin.repl.dht-composition.md sections 5 and 12, R3)."
  space.index/default-branching-factor)


(defn publication-policy
  "The immutable generic publication policy this composition declares over
   `node` (docs/design/yin.repl.dht-composition.md section 5):
   `{:branching-factor n :max-payload-bytes b}`.

   Both halves come from the transport composition rather than from a
   constant here.  The count cap is storage's own default; the byte
   ceiling is the payload capability of the message bound `node` actually
   composes under (`dao.space.dht/payload-budget`), so the bound the
   transport enforces, the store front's oversize refusal and the ceiling
   storage cuts pages inside are one configuration's and cannot disagree.
   A publication too large for that ceiling is cut into smaller pages
   inside storage (`dao.space.index`'s bounded build), which is where §5
   puts the decision: the transport states how many bytes a message may
   carry, and storage decides where the pages are cut."
  [node]
  {:branching-factor default-branching-factor
   :max-payload-bytes (dht/payload-budget node)})


(defn link-source
  "The signed-name link source this composition drives, prepared for the
   engine's `:link-source` input
   (docs/design/yin.repl.dht-composition.md sections 3 and 7).  A
   `(require ...)` of a name this source resolves starts a closure load on
   the node its composition is handed each round, so the engine is given no
   content store to read through and never names a node: it holds this
   value as plain data, and `yin.shell.linker/serve-links` owns the round
   that supplies the transport.

   `spec` is the store spec (`checked-spec` validates and fills it), and
   `opts` carries the session's own link inputs — `:key`, the publisher's
   key; `:principals`, the declared publishers; `:admission`, the admission
   policy; `:name-env`, a bare name environment."
  [spec {:keys [key principals admission name-env]}]
  (link.pair/composition
    {:dht? true
     :name-env name-env
     :principals principals
     :key key
     :follow (followed-principals (checked-spec spec))
     :admission (when-not (= :external admission) admission)}))


(defn- checked-follow
  "The `:follow` entries of `spec`, each `{:principal hex :host h :port
   p}` with a loopback literal host, one per principal; throws a refusal
   otherwise."
  [{:keys [follow peers]}]
  (let [refuse #(throw (ex-info % {:follow follow}))]
    (when-not (or (nil? follow) (vector? follow))
      (refuse "the followed principals must be a vector"))
    (doseq [{:keys [principal host port] :as f} follow]
      (when-not (and (map? f) (string? principal)
                     (re-matches #"[0-9a-f]{64}" principal))
        (refuse (str "--dht-follow names a principal by 64 lowercase "
                     "hexadecimal characters, not " (pr-str principal))))
      (when-not (remote-channel/loopback-literal? host)
        (refuse (str "--dht-follow " principal "@" host ":" port
                     ": the head board is followed on loopback only")))
      (when-not (and (integer? port) (<= 1 port 65535))
        (refuse (str "--dht-follow " principal ": the port must be 1 to "
                     "65535, not " (pr-str port)))))
    (when (and (seq follow) (empty? peers))
      (refuse (str "--dht-follow needs a --dht-peer: a followed head's "
                   "index is fetched over the DHT")))
    (when-not (= (count follow) (count (distinct (map :principal follow))))
      (refuse "--dht-follow names each principal once: one source each"))
    (vec follow)))


(defn- heads-records
  "The records of `<dir>/heads.edn` when `follow` is not empty, read
   before the node binds anything; a file that cannot be read as one
   version-1 record refuses startup naming it."
  [dir follow]
  (when (seq follow)
    (let [{:keys [records refusal]} (read-heads dir)]
      (when refusal
        (throw (ex-info (str refusal "; move it aside to follow every "
                             "principal from no head, dropping each one's "
                             "rollback floor until a head is installed")
                        {:dir dir})))
      records)))


(defn- follower-of
  "The follower of `follow` on `node`, restored from the persisted
   `records` before any connection: `[follower node]`, or `[nil node]`
   when nothing is followed.  A record that fails refuses startup naming
   it."
  [node dir follow records]
  (if (empty? follow)
    [nil node]
    (let [path (str dir "/" heads-file)]
      (try
        (head/follow node {:follow (mapv #(sign/principal (:principal %))
                                         follow)
                           :heads records})
        (catch #?(:cljd Object :clj Throwable :cljs :default) e
          (if-some [reason (:yin.head/refused (ex-data e))]
            (throw (ex-info (str "heads file " path ": " (ex-message e)
                                 " (" reason "); remove that record to "
                                 "follow it from no head, dropping its "
                                 "rollback floor until a head is installed")
                            {:dir dir :reason reason}))
            (throw e)))))))


(defn- compose
  "The two values `open` answers, over the joined `node`: `[handle node]`,
   the generic `dao.jing` content handle the engine is handed and the
   transport step-owner value this composition keeps.  The publisher's
   board, the follower restored from `records`, a pinned hydration and the
   HEAD write that announces and deposits are all on the node's side of
   that split; only the handle, the recovery it read back and the
   publication policy cross inward
   (docs/design/yin.repl.dht-composition.md sections 3 and 6)."
  [node base {:keys [dir manifest] :as checked} follow records
   {:keys [key ws write-heads!]}]
  (let [pub (publisher checked key base)
        [follower node] (follower-of node dir follow records)
        node (cond-> (assoc node ::dir dir ::ws ws)
               pub (assoc ::publisher pub)
               follower (assoc ::follower follower
                               ::follow
                               {:sources (into {}
                                               (map (fn [f]
                                                      [(sign/principal
                                                         (:principal f))
                                                       f]))
                                               follow)
                                :links {}
                                :waiting {}
                                :write! (or write-heads! fs/atomic-replace!)})
               (and manifest (not= manifest (get-in base [:recovery
                                                          :manifest])))
               (-> (dht/load-index manifest)
                   (assoc ::hydrating manifest)))]
    ;; a recovered HEAD's trace, so a reader's first contact needs no new
    ;; round (5.4)
    (when-some [recovered (and pub (get-in base [:recovery :manifest]))]
      (deposit-head! pub recovered))
    [(assoc (dht/store node)
            ;; The generic publication policy this composition declares
            ;; (docs/design/yin.repl.dht-composition.md section 5), read
            ;; off the injected handle by the engine so nothing
            ;; downstream hardcodes a transport's page size.  Both halves
            ;; are the transport's own answer (`publication-policy`): the
            ;; count cap is storage's default, never this composition's
            ;; message-size override, and the byte ceiling is derived from
            ;; the bound the node actually composes under.  The store's own
            ;; full-message refusal stays the last resort, exactly as §5
            ;; preserves it.
            :publication-policy (publication-policy node)
            :head-fn (fn [manifest-address]
                       ((:head-fn base) manifest-address)
                       (dht/announce! node manifest-address)
                       (when pub (deposit-head! pub manifest-address)))
            :recovery (:recovery base)
            ;; the reset-retention policy stated explicitly, so the engine
            ;; never derives it from this composition's directory
            ;; (docs/design/yin.repl.dht-composition.md section 6)
            :retain-on-reset? true)
     node]))


(defn- quietly!
  "Run the cleanup `f`, swallowing what it throws: a cleanup never
   replaces the refusal that caused it."
  [f]
  (try (f) nil
       (catch #?(:cljd Object :clj Throwable :cljs :default) _ nil)))


(defn open
  "Open a `dht:<dir>` store from its spec (`checked-spec`): the durable
   directory store, and the `dao.space.dht` node joined over it.

   Answers the two values of section 3's ownership split, and never one
   value carrying both:

   ```clojure
   {:index-store <the generic dao.jing handle the engine is constructed
                  with, carrying :head-fn, :recovery, :publication-policy
                  and :retain-on-reset?>
    :node <this composition's transport step-owner value>
    :recovery <what the open validated, {:manifest :datoms}>
    :publication-policy <the immutable generic policy of section 5>
    :retain-index-on-reset? true}
   ```

   `opts`: `:key`, the publisher key `{:seed :public}`, which makes a
   publishing node deposit its head; `:ws`, the host WebSocket seam
   `{:connect! :bind! :unbind!}` (yin.repl.host) the board is served and
   dialed over; `:write-heads!`, `(fn [dir name text])`, the atomic file
   replace `heads.edn` is written with (`dao.space.store.fs` by
   default); `:open-store`, `(fn [dir])`, the durable directory open
   (`dao.space.store/open` by default).

   A refusal releases everything it opened, exactly once, and is
   answered as itself whatever the release throws: before the join the
   directory store is closed; once joined the node owns it, and closing
   the node closes the socket and then the store."
  ([spec] (open spec {}))
  ([spec {:keys [key ws write-heads! open-store]}]
   (let [{:keys [dir peers publish? bind-host bind-port max-inbound-bytes
                 manifest bind!]
          :as checked} (checked-spec spec)
         follow (checked-follow checked)
         base ((or open-store durable/open) dir)
         [node records]
         (try
           (when-some [why (refused-head dir manifest
                                         (get-in base [:recovery :manifest]))]
             (throw (ex-info why {:dir dir :manifest manifest})))
           (let [records (heads-records dir follow)]
             [(dht/join {:local base
                         :peers peers
                         :publish? publish?
                         :bind-host bind-host
                         :bind-port bind-port
                         :max-inbound-bytes max-inbound-bytes
                         :repair-slots default-repair-slots
                         :bind! (or bind! (dht/default-bind))})
              records])
           (catch #?(:cljd Object :clj Throwable :cljs :default) e
             (quietly! #(durable/close! base))
             (throw e)))]
     ;; from here the node owns the store and holds a socket
     (try
       (let [[index-store node]
             (compose node base checked follow records
                      {:key key :ws ws :write-heads! write-heads!})]
         {:index-store index-store
          :node node
          :recovery (:recovery index-store)
          :publication-policy (:publication-policy index-store)
          :retain-index-on-reset? true})
       (catch #?(:cljd Object :clj Throwable :cljs :default) e
         (quietly! #(dht/close! node))
         (throw e))))))


(defn follower
  "The head follower a composition's `node` carries, or nil when it
   follows nothing."
  [node]
  (get node ::follower))


(defn stop!
  "Initiate the head composition's exit on `session`'s node: the board's
   server is asked to stop (`head.board/stop!`), every follow dial is
   closed and no dial is composed again, and the node is marked
   stopping.  Performs no other I/O; `step` completes the stop and
   `stopped?` reports it.  A session without a node, or one already
   stopping, is answered unchanged.  The board serves a read-only ring
   that stop does not end, so a reader has nothing to be told but the
   reattachable close its connection observes.

   This composition is the one resource owner: a host asks once, here,
   and never closes a board, a dial or the store itself
   (docs/design/yin.repl.dht-composition.md sections 3 and 6)."
  [session]
  (let [node (:node session)]
    (if (or (nil? node) (::stopping? node))
      session
      (do (doseq [[_ {:keys [dial]}] (get-in node [::follow :links])]
            (when dial (head.board/close! dial)))
          (assoc session :node
                 (cond-> (assoc node ::stopping? true)
                   (get-in node [::publisher :server])
                   (update-in [::publisher :server] head.board/stop!)

                   (::follow node)
                   (assoc-in [::follow :links] {})))))))


(defn stopped?
  "True when the head composition owes nothing at exit: no node, no
   board server, or a board server that is stopped or refused."
  [session]
  (let [server (get-in session [:node ::publisher :server])]
    (or (nil? server) (head.board/stopped? server))))


(defn close!
  "The last resort after the exit drain's budget
   (`yin.repl.main/stop-ticks`): whatever the board still owes is asked
   for once more and not awaited.  The ordinary exit is `stop!`, then
   `step` until `stopped?`.  A session never stopped is stopped first, so
   a direct caller (a test, an embedder without a drain) still closes the
   dials; the board's stopping tick runs at the node's last tick reading
   only when the board is not already `stopped?`.

   The store's own resources are the durable handle's `:close-fn`, which
   the composition's single resource owner releases after this
   (`yin.shell.main/close-composition!`), so nothing here is closed
   twice."
  [session]
  (let [session (stop! session)
        node (:node session)
        server (get-in node [::publisher :server])]
    (when (and server (not (head.board/stopped? server)))
      (head.board/serve-step server (or (::now node) 0)))
    nil))


;; =============================================================================
;; Lines
;; =============================================================================

(defn- address-text
  [host port]
  (if (str/includes? host ":") (str "[" host "]:" port) (str host ":" port)))


(defn- reason-text
  "One blob's failure reason as the REPL prints it."
  [node {:keys [reason peers]}]
  (case reason
    :dao.jing.dht/solo (str "solo: no --dht-peer is configured, so nothing "
                            "was sent; the index is in " (::dir node) " only")
    :dao.jing.dht/unpublished (str "publication is off: this node fetches "
                                   "only; start with --dht-publish to share")
    :dao.jing.dht/too-few-peers (str "too few peers, sent to " peers " of "
                                     (dht/ack-peers node) " before the "
                                     "acknowledgement deadline")
    :dao.jing.dht/oversize "a blob exceeds the DHT message bound"
    :dao.jing.dht/absent "a blob was no longer in the local store"
    :dao.jing.dht/busy "too many writes were pending"
    :dao.space.dht/backlog-full "the replicate backlog was full"
    :dao.space.dht/publications-full (str "too many publications awaited "
                                          "their first report")
    :dao.space.dht/cancelled "cancelled"
    (str reason)))


(def ^:private ended-text
  {:dao.space.dht/terminal "every remaining failure is final"
   :dao.space.dht/cancelled "cancelled"
   :dao.space.dht/displaced "displaced by newer publications"})


(defn- result-text
  "A publication's result (yin.vm.linker.dht.md 5.5.6): when it is not
   acknowledged, the count of failed blobs, the first reason, and whether
   the node is retrying.  Local durability is stated apart from the
   network's answer: a dissemination failure never reads back as a lost
   publication (docs/design/yin.repl.dht-composition.md section 3)."
  [node {:keys [result blobs peers failed repairing? ended]}]
  (if (= :acknowledged result)
    (str "acknowledged: sent to " peers " peers")
    (str (if (= :partial result)
           "PARTIAL: the manifest was sent; "
           "NOT acknowledged: ")
         (reason-text node (first failed))
         "; " (count failed) " of " blobs " blobs not sent"
         (cond
           repairing? "; retrying while the node is open"
           ended (str "; not retrying (" (get ended-text ended (str ended)) ")")
           :else "; not retrying")
         "; the local copy is durable")))


(defn- failure-text
  "A load's failure reason, data (dao.space.dht 4.3), as text."
  [{:dao.space.dht/keys [failure] :keys [address cause defect outcome]
    :as reason}]
  (case failure
    :miss (str "no peer produced " address " (" (name cause) ")")
    :invalid (str "the content " (when address (str "at " address " "))
                  "is invalid (" (:code defect) ")"
                  (when-let [text (:text defect)] (str ": " text)))
    :unaskable (str "could not ask for " address " (" (name outcome) ")")
    (pr-str (if (nil? failure) reason failure))))


(defn- event-line
  [node {:keys [manifest blobs host port id reason datoms fetched], :as event}]
  (case (::dht/event event)
    :bound (str "dht: node " (subs id 0 16) " listening on "
                (address-text host port) "; peers: "
                (str/join ", " (map #(address-text (:host %) (:port %))
                                    (:peers node)))
                (if (:publish? node) "; publishing" "; fetch-only"))
    :bind-failed (str "dht: refused: the socket could not bind: " reason)
    (:published :republished)
    (str "dht: " (name (::dht/event event)) " " manifest " (" blobs " blobs), "
         (result-text node event))
    :publication-unknown (str "dht: published " manifest
                              ", acknowledgement unknown: facts were lost")
    :loaded (str (if (= manifest (::hydrating node)) "dht: hydrated "
                     "dht: loaded ")
                 manifest ", "
                 (when datoms (str datoms " datoms, "))
                 fetched " blobs fetched"
                 (when (= manifest (::hydrating node)) "; evaluation admitted"))
    :load-failed (str "dht: " (if (= manifest (::hydrating node)) "refused: "
                                  "")
                      "loading " manifest " failed: " (failure-text reason)
                      (when (= manifest (::hydrating node))
                        "; the remote index was not hydrated"))
    (pr-str event)))


(defn join-token
  "The one string a reader hands `yin-repl dht join`: the publishing
   node's address and its principal's 64 hex digits, as
   `yin:<host:port>/<principal>` (5.8).  It names no manifest: a reader
   follows the head the principal publishes."
  [host port principal]
  (str "yin:" (address-text host port) "/" principal))


(defn- trace-text
  [trace]
  (let [{:yin.head/keys [manifest] n :yin.head/seq} (:yin.head/envelope trace)]
    (str manifest " (seq " n ")")))


(defn- head-line
  "A follower event (`yin.vm.linker.head`) as the line the REPL prints,
   or nil."
  [{:keys [principal trace reason failure outcome detail delay] :as e}]
  (case (:yin.head/event e)
    :installed (str "dht: installed the head of " principal ": "
                    (trace-text trace))
    :refused (str "dht: refused a head of " principal ": " reason
                  (when (map? trace) (str ", " (trace-text trace))))
    :unloadable (str "dht: the head of " principal " " (trace-text trace)
                     " cannot load: " (failure-text failure)
                     "; trying again after the repair delay")
    :source-lost (str "dht: lost the head board of " principal " ("
                      (pr-str outcome) "); dialing it again")
    :unpersisted (str "dht: the head of " principal " " (trace-text trace)
                      " is not installed: :yin.head/unpersisted (" detail
                      "); writing " heads-file " again in " delay " ticks")
    nil))


;; =============================================================================
;; The board endpoint and the token (5.1, 5.8)
;; =============================================================================

(def ^:private board-codec
  "The head board's wire profile."
  (:transit connect/codec-profiles))


(defn- serve-board
  "Compose the board's endpoint once the node's socket is bound at
   `host`:`port`: `[pub lines]`."
  [pub ws host port]
  (let [server (head.board/serve {:board (:board pub)
                                  :principal (:principal pub)
                                  :spec {:host host :port port}
                                  :host ws
                                  :codecs [board-codec]})]
    [(assoc pub :server server :listen [host port])
     (when (= :refused (:status server))
       [(case (:reason server)
          :yin.head/not-loopback
          (str "dht: the head board is served on loopback only; this node "
               "binds " host ", so it serves no board and prints no token")
          :dao.stream.remote-channel/no-transport
          "dht: this host has no WebSocket listener: no head board, no token"
          (str "dht: the head board could not bind TCP "
               (address-text host port) ": no board, no join token; "
               "choose another --dht-port"))])]))


(defn- stopped-line
  "The board's end, printed once on its transition to stopped."
  [outcome]
  (if (= :confirmed outcome)
    "dht: the head board stopped"
    (str "dht: the head board stopped without host completion ("
         (name outcome) ")")))


(defn- step-board
  "Advance the board's endpoint, print the token once when it is bound,
   the board's end once when it stops, and each deposit refusal:
   `[pub lines]`.  A stopping node composes no board."
  [pub node events now]
  (let [bound (some #(when (= :bound (::dht/event %)) %) events)
        [pub lines] (if (and bound (nil? (:server pub)) (not (::stopping? node)))
                      (serve-board pub (::ws node) (:host bound) (:port bound))
                      [pub nil])
        before (get-in pub [:server :status])
        server (when (:server pub) (head.board/serve-step (:server pub) now))
        pub (cond-> pub server (assoc :server server))
        [host port] (:listen pub)
        lines (cond-> (vec lines)
                (and (= :serving (:status server)) (not (:token? pub)))
                (conj (str "dht: join token: "
                           (join-token host port (:public (:key pub)))))

                (and (= :refused (:status server)) (not= :refused before))
                (conj (str "dht: the head board could not bind TCP "
                           (address-text host port) ": no board, no join "
                           "token; choose another --dht-port"))

                (and (= :stopped (:status server)) (not= :stopped before))
                (conj (stopped-line (get-in server [:stop :outcome]))))
        pub (cond-> pub (= :serving (:status server)) (assoc :token? true))
        ;; a gap means refusals were evicted unread: adopt its recovery
        ;; cursor, say so, and drain what the ring still holds
        [notes lost? cursor] (loop [notes [] lost? false
                                    cursor (:notes-cursor pub)]
                               (let [r (stream/next (:notes pub) cursor)]
                                 (case (:dao.stream/outcome r)
                                   :dao.stream/ok (recur (conj notes
                                                               (:dao.stream/value r))
                                                         lost?
                                                         (:dao.stream/cursor r))
                                   :dao.stream/gap (recur notes true
                                                          (:dao.stream/cursor r))
                                   [notes lost? cursor])))]
    [(assoc pub :notes-cursor cursor)
     (-> lines
         (cond-> lost? (conj (str "dht: more head trace refusals arrived "
                                  "than could be kept; the oldest were not "
                                  "reported")))
         (into (map (fn [{:keys [reason manifest]}]
                      (str "dht: the head trace of " manifest " was not "
                           "deposited: " reason)))
               notes))]))


(defn- hydrated
  "Install the loaded remote index as this directory's: HEAD first, then
   the indexer, exactly as a restart's recovery is installed.  A
   publisher deposits the hydrated HEAD's trace once (5.4).

   The HEAD write precedes the installation, so a crash between the two
   restarts from a HEAD that names content the store already holds; and
   the installation is the engine's own generic recovery operation
   (`yin.repl/install-recovery`, docs/design/yin.repl.dht-composition.md
   section 4), never a direct write into the engine's index state.  Only
   the generic content handle and the validated recovery data cross
   inward."
  [session node]
  (let [manifest (::hydrating node)
        recovery {:manifest manifest :datoms (dht/loaded-datoms node manifest)}]
    ((:head-fn (dht/local node)) manifest)
    (when-some [pub (::publisher node)]
      (deposit-head! pub manifest))
    (-> session
        (update :engine repl/install-recovery recovery)
        (assoc :node (dissoc node ::hydrating)))))


;; =============================================================================
;; Following (5.3, 5.5, 5.7)
;; =============================================================================

(defn- next-delay
  [follower delay]
  (if delay
    (min (* 2 delay) (:repair-max-ticks follower))
    (:repair-ticks follower)))


(defn- drop-dial
  "Close the dial of a link and schedule the next one after its delay;
   the last failure reported is kept."
  [follower link now]
  (when-some [d (:dial link)] (head.board/close! d))
  (let [delay (next-delay follower (:delay link))]
    {:due (+ now delay) :delay delay :failed (:failed link)}))


(defn- lost-reason
  "What ended a lost dial, as one keyword: the remote's reason, or the
   outcome."
  [d]
  (let [o (:outcome d)]
    (or (:dao.stream.remote/reason o) (:dao.stream/outcome o) :lost)))


(defn- dial-line
  "The line a dial failure prints when it differs from the last one."
  [principal {:keys [host port]} failure]
  (str "dht: cannot follow " principal " at " (address-text host port) ": "
       (case failure
         :dao.stream.remote/not-found
         "that endpoint serves no head board for this principal"
         :dao.stream.remote/channel-gone
         "the connection was refused, closed, or stopped answering"
         :dao.stream.remote-channel/no-transport
         "this host has no WebSocket dialer"
         (str failure))
       "; dialing it again"))


(defn- step-link
  "Advance the dial of `principal`'s board at `now`: compose one when
   due, step it, hand a fresh reflection to the follower, and redial a
   dial that is lost.  Liveness is the channel's: a resolve or a read
   left unanswered for the link's `give-up-after` is lost as
   channel-gone.  Only the address the principal was given is dialed.
   Answers `[follower link failure]`, `failure` the keyword that ended a
   dial this step, or nil."
  [follower ws source link now]
  (cond
    (and (nil? (:dial link)) (some? (:due link)) (< now (:due link)))
    [follower link nil]

    (nil? (:dial link))
    (let [d (head.board/dial {:principal (sign/principal (:principal source))
                              :spec {:host (:host source)
                                     :port (:port source)}
                              :host ws
                              :codec board-codec})]
      (if (= :refused (:status d))
        [follower (drop-dial follower link now) (:reason d)]
        [follower (assoc link :dial d :handle nil) nil]))

    :else
    (let [d (head.board/dial-step (:dial link) now)
          link (assoc link :dial d)
          h (head.board/handle d)]
      (cond
        (and h (not (identical? h (:handle link))))
        [(head/attach follower (sign/principal (:principal source)) h)
         (assoc link :handle h :delay nil :failed nil)
         nil]

        (= :lost (:status d))
        [follower (drop-dial follower link now) (lost-reason d)]

        :else [follower link nil]))))


(defn- step-links
  "Step every principal's dial: `[follower links lines]`, one line per
   change of a dial's failure, never one per attempt."
  [follower node now]
  (let [{:keys [sources links]} (::follow node)]
    (reduce (fn [[follower links lines] [p source]]
              (let [[follower link failure] (step-link follower (::ws node)
                                                       source
                                                       ;; a map from the
                                                       ;; start: on
                                                       ;; ClojureDart an
                                                       ;; assoc of two or more
                                                       ;; keyword pairs is
                                                       ;; inlined to -conj,
                                                       ;; and -conj on nil
                                                       ;; conses a list; the
                                                       ;; JVM and Node make
                                                       ;; a map
                                                       (get links p {})
                                                       now)
                    fresh? (and failure (not= failure (:failed (get links p))))]
                [follower
                 (assoc links p (cond-> link failure (assoc :failed failure)))
                 (cond-> lines fresh? (conj (dial-line p source failure)))]))
            [follower links []]
            sources)))


(defn- persist
  "5.7 steps 2 and 3 for `principal`'s confirmed `trace`: replace
   `heads.edn` with it in its place, and only when that returned,
   install it.  A write that fails installs nothing and waits for the
   repair delay.  Answers `[follower node waiting events]`."
  [follower node waiting principal trace now]
  (let [{:keys [write!]} (::follow node)
        dir (::dir node)
        failed (try (write! dir heads-file
                            (render-heads (head/records follower principal
                                                        trace)))
                    nil
                    (catch #?(:cljd Object :clj Throwable :cljs :default) e
                      (or (ex-message e) (str e))))]
    (if failed
      (let [delay (next-delay follower (get-in waiting [principal :delay]))]
        [follower node
         (assoc waiting principal {:trace trace :due (+ now delay)
                                   :delay delay})
         [{:yin.head/event :unpersisted :principal principal :trace trace
           :detail failed :delay delay}]])
      (let [[follower node events] (head/install follower node principal
                                                 trace)]
        [follower node (dissoc waiting principal) events]))))


(defn- retry-waiting
  "Write again each confirmed head whose write failed, once its delay
   has passed and while it is still the principal's confirmed candidate."
  [follower node waiting now]
  (reduce (fn [[follower node waiting events] [p {:keys [trace due]}]]
            (let [cand (get-in (head/heads follower) [p :candidate])]
              (cond
                (not (and (= :confirmed (:state cand)) (= trace (:trace cand))))
                [follower node (dissoc waiting p) events]

                (< now due) [follower node waiting events]

                :else
                (let [[follower node waiting more]
                      (persist follower node waiting p trace now)]
                  [follower node waiting (into events more)]))))
          [follower node waiting []]
          waiting))


(defn linked-registry
  "`{name manifest-address}` of the modules a VM module `registry` holds
   linked through the linker: a linked entry carries its manifest's
   address, a host module none.  The projection itself is the neutral
   helper's (`yin.shell.registry`); it is restated here only as this
   composition's own name for it, so neither side reaches it through a
   storage implementation."
  [registry]
  (shell.registry/linked-registry registry))


(defn moved
  "The linked names of the session's engine whose resolved address
   differs now (`yin.vm.linker.head/moved`): derived, stored nowhere."
  [engine node]
  (head/moved (ld/names node (link/authority (:link-source engine)))
              (linked-registry (get-in engine [:vm :modules]))))


(defn- movers
  "Of `installed`, this step's install events, those whose install moved
   each name of `moves` to where it resolves now in `node`, `{name
   [event]}`: an install moved a name when, with only that principal's
   head put back where it stood in `before`, the name resolves elsewhere
   or not at all.  Derived from the two nodes, stored nowhere.  One
   install moved every name; a name no single install accounts for is
   given every install."
  [engine before node installed moves]
  (if (< (count installed) 2)
    (into {} (map (fn [m] [(:name m) installed])) moves)
    (let [authority (link/authority (:link-source engine))
          without (fn [{:keys [principal]}]
                    (let [put-back (get-in before [ld/installed-key principal])]
                      (:names (ld/names
                                (if (some? put-back)
                                  (assoc-in node [ld/installed-key principal]
                                            put-back)
                                  (update node ld/installed-key dissoc
                                          principal))
                                authority))))
          envs (mapv without installed)]
      (into {}
            (map (fn [{n :name :keys [resolved]}]
                   (let [mine (into []
                                    (keep-indexed
                                      (fn [i e]
                                        (let [entry (get (nth envs i) n)]
                                          (when-not (and (= :ok (:status entry))
                                                         (= resolved
                                                            (:address entry)))
                                            e))))
                                    installed)]
                     [n (if (seq mine) mine installed)])))
            moves))))


(defn moved-lines
  "The line of 5.7 for each linked name an installed head moved, naming
   the head of the principal whose install moved it (`movers`): only
   moves that were not already standing before this step's installs
   (`before`, the node then), so a name is reported once per new
   address, and nothing is stored.  `installed` is the step's
   `:installed` events."
  [engine before node installed]
  (when (seq installed)
    (let [standing (set (map (juxt :name :resolved) (moved engine before)))
          moves (into []
                      (remove #(contains? standing [(:name %) (:resolved %)]))
                      (moved engine node))
          by-name (movers engine before node installed moves)]
      (mapv (fn [{n :name :keys [linked resolved]}]
              (str "dht: " n " moved: linked " linked
                   ", now resolves to " resolved " (head of "
                   (str/join ", "
                             (map (fn [{:keys [principal trace]}]
                                    (str principal " seq "
                                         (get-in trace [:yin.head/envelope
                                                        :yin.head/seq])))
                                  (get by-name n)))
                   "); (reset) then (require '" n ") links it"))
            moves))))


(defn- step-follow
  "One pass of the follower after the node: dials, `head/step`, and for
   each confirmed head the write that precedes its install.  Answers
   `[node lines events]`."
  [session node now]
  (if-some [follower (::follower node)]
    (let [engine (:engine session)
          before node
          ;; a stopping node composes and steps no dial
          [follower links dial-lines] (if (::stopping? node)
                                        [follower (get-in node [::follow :links]) []]
                                        (step-links follower node now))
          [follower node events] (head/step follower node now)
          ;; a lost source: drop its dial; the next is composed after the
          ;; delay, and the follower mints :oldest on its handle.  A
          ;; stopping node schedules none.
          links (if (::stopping? node)
                  links
                  (reduce (fn [links {:keys [principal]}]
                            (update links principal
                                    #(drop-dial follower % now)))
                          links
                          (filter #(= :source-lost (:yin.head/event %)) events)))
          waiting (get-in node [::follow :waiting])
          [follower node waiting retried] (retry-waiting follower node waiting
                                                         now)
          [follower node waiting persisted]
          (reduce (fn [[follower node waiting more] {:keys [principal trace]}]
                    (let [[follower node waiting es]
                          (persist follower node waiting principal trace now)]
                      [follower node waiting (into more es)]))
                  [follower node waiting []]
                  (filter #(= :confirmed (:yin.head/event %)) events))
          events (-> events (into retried) (into persisted))
          node (-> node
                   (assoc ::follower follower)
                   (update ::follow assoc :links links :waiting waiting))
          installed (filterv #(= :installed (:yin.head/event %)) events)]
      [node
       (-> dial-lines
           (into (keep head-line) events)
           (into (moved-lines engine before node installed)))
       events])
    [node [] []]))


;; =============================================================================
;; The step
;; =============================================================================

(defn step
  "Advance the composition's node once at the host's clock reading `now`
   (dao.space.dht/step) and answer `[session' lines events]`: its events
   as lines, and as the data the ticker reads to re-check a pending
   require, a completed hydration installed, a failed one or a failed
   bind recorded as the session's refusal.  The head composition steps
   after the node: the board's endpoint (and the join token once it is
   bound), then the follower, whose events join the node's.  A session
   without a transport is answered unchanged.

   `events` stay in this composition's own vocabulary: the host translates
   them before anything crosses inward
   (`yin.shell.completion/completion-events`), so the engine re-checks
   only correlated generic completions and never decodes a transport
   keyword (docs/design/yin.repl.dht-composition.md section 7)."
  [session now]
  (if-let
    [node (:node session)]
    (let
      [[node events] (dht/step node now)
       node (assoc node ::now now)
       node (reduce (fn [node {:keys [host port], :as event}]
                      (cond-> node
                        (= :bound (::dht/event event))
                        (assoc ::listen [host port])))
                    node
                    events)
       lines (mapv #(event-line node %) events)
       [node board-lines] (if-some [pub (::publisher node)]
                            (let [[pub ls] (step-board pub node events now)]
                              [(assoc node ::publisher pub) ls])
                            [node []])
       [node follow-lines head-events] (step-follow session node now)
       lines (-> lines (into board-lines) (into follow-lines))
       hydrating (::hydrating node)
       status (when hydrating (:status (dht/load-status node hydrating)))
       node (cond->
              node
              (and hydrating (= :failed status))
              (assoc
                ::refusal (str/join
                            "\n" (filter
                                   #(str/includes?
                                      %
                                      "refused")
                                   lines)))
              (dht/refusal node)
              (assoc ::refusal (dht/refusal node)))
       session (assoc session :node node)]
      [(if (= :loaded status) (hydrated session node) session)
       lines
       (into (vec events) head-events)])
    [session [] []]))


(defn refusal
  "The text that refused the composition's store after startup (a failed
   bind or a hydration that could not complete), or nil.

   `session` is the composition session this namespace's every function
   takes — `{:engine … :node … :key …}`, `yin.shell.session/make`'s shape —
   and the node is read off its `:node` key and nowhere else: one contract
   for `step`, `stop!`, `stopped?`, `close!`, `busy?`, `follow-lines`,
   `refusal` and `admitting?`, so a host cannot step one of them into
   silence by storing the node under a name only another of them reads."
  [session]
  (get (:node session) ::refusal))


(defn admitting?
  "True when the session may evaluate: no hydration is outstanding and
   none was refused.  Following never holds evaluation back (5.8) — head
   following updates the external resolver, and only an explicit pinned
   hydration installs recovery before evaluation is admitted
   (docs/design/yin.repl.dht-composition.md section 3)."
  [session]
  (let [node (:node session)]
    (or (nil? node)
        (and (nil? (::hydrating node)) (nil? (::refusal node))))))


(defn busy?
  "True while the node owes the REPL a line; the ticker keeps its base
   cadence then.  Following alone is never busy (section 7): only a
   candidate's load is, as a load of the node."
  [session]
  (boolean (some-> (:node session) dht/busy?)))


(defn follow-lines
  "What the shell states at startup per followed principal: where it is
   followed, and whether a head is installed (5.8)."
  [session]
  (let [node (:node session)]
    (when-some [follower (::follower node)]
      (mapv (fn [[p {:keys [manifest floor]}]]
              (let [{:keys [host port]} (get-in node [::follow :sources p])]
                (str "dht: following " p " at " (address-text host port) "; "
                     (if manifest
                       (str "installed head " manifest " (seq " floor ")")
                       (str "no head installed yet: a (require ...) of its "
                            "names waits for the first")))))
            (head/heads follower)))))


(defn banner
  "What the REPL states at startup about a DHT store spec, before the
   node steps once, and so before anything is shared.  This text is the
   composition's, never the engine's
   (docs/design/yin.repl.dht-composition.md section 6)."
  [{:keys [dir peers publish? bind-host bind-port follow-suspended]}]
  (let [content (durable/content-path dir)
        peers-text (str/join ", " (map #(address-text (:host %) (:port %))
                                       peers))]
    (cond-> []
      (empty? peers)
      (conj (str "dht: solo, no --dht-peer, so no socket is opened; "
                 "publications are durable in " dir
                 " and acknowledged by no one"))

      (seq peers)
      (conj (str "dht: binding " (address-text bind-host bind-port)
                 (when (zero? bind-port) " (ephemeral)")
                 "; peers " peers-text))

      (and publish? (seq peers))
      (conj (str "dht: publishing is ON. Everything in " content
                 " will be shared: the whole code index this directory holds"
                 ", every program recovered from HEAD and every program"
                 " evaluated from now on, with any peer that asks for its"
                 " address. Omit --dht-publish to fetch only."))

      (and publish? (empty? peers))
      (conj (str "dht: publishing is on, but solo: nothing in " content
                 " leaves this process"))

      (and (not publish?) (seq peers))
      (conj (str
              "dht: fetch-only, nothing this node holds is "
              "shared (--dht-publish shares it)"))

      follow-suspended
      (conj (str "dht: --dht-manifest pins this run: following is suspended "
                 "for this run (the saved --dht-follow is kept)"))

      true
      (conj (str "dht: a (require ...) of a module this node does not hold "
                 "may fetch its code from peers; it stays pending until the "
                 "load ends, and (abandon) gives it up")))))

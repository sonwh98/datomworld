Created-GMT: 2026-10-08 05:45:00 GMT
Created-Local: 2026-10-08 12:45:00 ICT

# Track B Slice S4: beyond loopback, terminal causes, and ephemeral listeners

Architectural specification and implementation brief.
Lead System Architect: claude-fable-5-1. Date: 2026-10-08.
Tree: branch `stream-crossmachine-s4` at master `66756d20`, worktree
`/Users/sto/workspace/datomworld-stream-s3a`.

Reference: `docs/design/dao.stream.remote.md` (2.4 Expiry, 3.0, 3.1),
`docs/design/dao.stream.ws.md` (Serving, Ending a served stream, Elements
and Serialization), the S3b specification
(`archive/1791398000000-architect-stream-s3b-spec.claude-fable-5-1.findings.md`,
section 6) and the S3b sign-off
(`archive/1791400500000-architect-stream-s3b-signoff.codex.findings.md`,
finding 4).

Everything below was read against the code as it stands on `66756d20`:
`ws.cljc`, `ws_project.cljc` (665 lines), `remote_channel.cljc` (904),
`remote.cljc` (link-step!, links), `rpc.cljc` (transport-error-reason,
terminal-lost, rebind), `connect.cljc` (501), `serve.cljc` (542),
`dht.cljc` (1080), `head/board.cljc`, `driver.cljc` (remote-routed?,
connect-command, observe-connection, repl-step), `main.cljc` (parse-args,
boot-server, banner, step-all, stop-tick, the three host stop loops,
close-index-store!), `embed.cljc`, the three host adapters' bind seams
(`ws/jvm.clj`, `yin/repl/host.cljs`, `ws/dart.cljd`), `loopback_net.cljc`,
`net_fixture.cljc`, and the tests named in section 4.

---

## 0. Decisions

+-----+-------------------------------------------------------------------------------------------------------------------------------------+----------+
| #   | Decision                                                                                                                            | Where    |
+=====+=====================================================================================================================================+==========+
| D1  | The projection records the fact that closed its ring: `:cause` is the terminal `:ws/event` kind (`:ws/closed`, `:ws/ended`,        | 2.1      |
|     | `:ws/not-found`, `:ws/transport-error`) or `:dao.stream/end` for the traffic medium's own end, set once, never rewritten. It also  |          |
|     | records `:opened?` when the attachment's `:ws/opened` passes. Two accessors, `cause` and `opened?`. Nothing else in `ws-project`    |          |
|     | changes.                                                                                                                            |          |
+-----+-------------------------------------------------------------------------------------------------------------------------------------+----------+
| D2  | A `:lost` dial carries a neutral `:cause` in `#{:ended :dropped :not-served :unreachable :expired}` and `:opened?`, computed once at | 2.2      |
|     | the transition to `:lost`: a link that expired is `:expired` whatever the projection later reads; otherwise the projection's cause  |          |
|     | maps one to one (`:ws/ended` to `:ended`, `:ws/closed` and medium end to `:dropped`, `:ws/not-found` to `:not-served`,              |          |
|     | `:ws/transport-error` to `:unreachable`). A dial lost inside `dial` by a failed attach is `:unreachable` for a transport-error      |          |
|     | outcome, else `:cause nil`. `:outcome` stays `channel-gone` exactly as today. Accessors `cause` and `opened?`.                      |          |
+-----+-------------------------------------------------------------------------------------------------------------------------------------+----------+
| D3  | `connect/observe-terminal` refines an RPC `:dao.stream.rpc/detached`, and only that terminal, by the dial's cause: `:ended` becomes  | 2.3      |
|     | status `:ended`; `:unreachable`, or `:expired` with `:opened?` false, becomes `:transport-error`; everything else stays `:detached`. |          |
|     | The RPC client's `:terminal` remains the only trigger: no cause creates a terminal, and no cause is read before the RPC client has  |          |
|     | one. `reattachable?` becomes `(reattachable? connection client)`: the RPC terminal is `detached` and the connection's status is not |          |
|     | a non-reattachable terminal. The driver's `queueable-terminals` is replaced by that predicate.                                      |          |
+-----+-------------------------------------------------------------------------------------------------------------------------------------+----------+
| D4  | An endpoint specification may name `:port 0`: ephemeral. `remote-channel/serve` composes the endpoint on the provisional             | 2.4      |
|     | descriptor (port 0), binds on `(or bind-port port)`, and finalizes `:spec`, `:descriptor` and the ws endpoint's own descriptor on   |          |
|     | the host's `:bind-succeeded` fact, whose `:port` every host adapter already reports. A `:bind-succeeded` that reports no positive  |          |
|     | port to an ephemeral endpoint is the refusal `::port-unreported`, after releasing and unbinding. `:port 0` beside a positive         |          |
|     | `:bind-port` is `::no-port`.                                                                                                        |          |
+-----+-------------------------------------------------------------------------------------------------------------------------------------+----------+
| D5  | `dao.stream.ws/make-endpoint` admits a served descriptor whose port is 0 (`servable-descriptor?`); the endpoint keeps its descriptor | 2.4      |
|     | in its state atom, `accept-connection!` mints session handles from it, and `endpoint-bound!` records the bound port. `descriptor?`, |          |
|     | the dial gate, is unchanged: port 0 stays undialable. This is the one change to `ws.cljc`, eleven lines or fewer.                   |          |
+-----+-------------------------------------------------------------------------------------------------------------------------------------+----------+
| D6  | `yin.repl.serve` drops the `ephemeral-port-unsupported` pre-check and its code; `url` answers nil while the advertised port is 0;    | 2.5      |
|     | the endpoint adopts the channel server's bound port on the `:starting` to `:serving` transition, before it publishes               |          |
|     | `Serving <url>`. `yin.repl.main` keeps accepting `--port 0` and its banner names the ephemeral bind instead of printing port 0.      |          |
+-----+-------------------------------------------------------------------------------------------------------------------------------------+----------+
| D7  | The DHT board's exit becomes driver-paced like the REPL endpoint's: `yin.repl.dht/stop!` initiates (board `stop!`, every follow     | 2.6      |
|     | dial closed, no redial), `step` keeps stepping the stopping board, `stopped?` reports completion, and `main/stop-tick` drains the   |          |
|     | shell's board and the `--port` endpoint together under the existing `stop-ticks` budget on all three hosts. `close!` stays as the   |          |
|     | last resort after the budget, no longer the ordinary path.                                                                          |          |
+-----+-------------------------------------------------------------------------------------------------------------------------------------+----------+
| D8  | `loopback-net/listen-on` allocates a port for a bind to 0 and reports it in `:bind-succeeded`, so the ephemeral path runs on every   | 4.1      |
|     | host in process. No test binds a real port for this slice.                                                                          |          |
+-----+-------------------------------------------------------------------------------------------------------------------------------------+----------+
| D9  | Zero `:ws/` tokens, zero requires of `dao.stream.ws` or `dao.stream.ws-project`, in `serve.cljc`, `connect.cljc`, `dht.cljc`,       | 1.3, 4.7 |
|     | `driver.cljc`, `main.cljc`, `board.cljc` and every test under `test/yin/repl/` except `test/yin/repl/host/`. The S3b gate, widened  |          |
|     | to the files this slice touches.                                                                                                    |          |
+-----+-------------------------------------------------------------------------------------------------------------------------------------+----------+

---

## 1. Foundations, invariants and layering

### 1.1 Why this slice exists

S3b put the REPL on `dao.stream.remote-channel` and left three honest
gaps, each recorded in its section 6. First, the projection collapses
`:ws/ended`, `:ws/closed` and `:ws/transport-error` into one closed ring,
so a client that misses the ended answer during the finite drain reads a
reattachable `:detached` for a stream that is gone, and a client whose
host is unreachable reads the same word for a connection that never
opened. On loopback the words were good enough; off loopback, where
unreachable hosts and dropped connections are common, an operator needs to
know whether to `(connect)` again. Second, `--port 0` is refused by REPL
policy because the descriptor is formatted before the bind; a machine
that cannot know a free port in advance (every process-level test, every
container) cannot serve. Third, `yin.repl.dht/close!` stops the head board
in one tick and never observes the host's `:stopped`, the only
composition in the shell whose exit is not driver-paced.

### 1.2 Invariants this slice is bound by

1. **`dao.stream` is the sole boundary for cross-machine communication**
   (`dao.stream.remote.md` 3.0). Above `dao.stream.remote-channel` no
   namespace names a transport, a frame, a socket, a close code or a
   `:ws/*` key. The five neutral causes of D2 are the words that cross
   the boundary; the four `:ws/` event kinds and the code 4000 stay below
   it.
2. **Driver-paced, clock-free.** Nothing in `ws-project`,
   `remote-channel`, `serve`, `connect` or `dht` reads a clock or
   schedules itself. The DHT exit of D7 exists because the alternative, a
   synchronous loop waiting for the host's `:stopped`, would have to
   sleep.
3. **Refusals are data.** `::port-unreported` and `::no-port` are maps
   with `:status :refused`; the connection's refined status is a keyword
   on a value.
4. **The RPC client's `:terminal` is the single source of terminal
   truth** (S3b D9, 5.5). D3 does not weaken it: the dial's cause never
   produces a terminal, is consulted only once the RPC client has
   reported `detached`, and refines the word, not the fact. The S3b
   prohibition "the dial's `:lost` must not override the RPC client's
   `:terminal`" stands and is restated in 5.4.
5. **No server/client privilege** (owner invariant 2026-09-25). An
   ephemeral bind is an establishment detail of the accepting role; once
   established nothing remembers which port was chosen by whom.
6. **The mirror never rewrites a cursor, anchor or outcome.** A refined
   status changes no cursor and no outcome; `channel-gone` stays the
   dial's `:outcome`.
7. **No backward compatibility.** Dev-only repo; the
   `ephemeral-port-unsupported` code, the `queueable-terminals` set and
   the 1-arity `reattachable?` are removed, not shimmed.
8. **Minimal diff.** The changes are the ones listed here. `rpc.cljc`,
   `apply.cljc`, `remote.cljc` do not change. `ws.cljc` changes by D5
   alone.

### 1.3 Layering after S4

```
yin.repl.main / yin.repl.embed / yin.repl.driver        (cadence, printing, shell, exit drain of both compositions)
        |                              |                         |
yin.repl.serve                   yin.repl.connect          yin.repl.dht  +  yin.vm.linker.head.board
  (adopts the bound port)        (refines :detached           (stop!/step/stopped?)
        |                         by neutral cause)              |
        +------------- dao.stream.remote-channel -----------------+
                 serve/serve-step/stop!  (::port-unreported, finalized descriptor)
                 dial/dial-step/detach!/close!  (:cause :opened? on a lost dial)
                                 |
          dao.stream.ws-project  (projection :cause, :opened?)
          dao.stream.ws          (servable-descriptor?, endpoint-bound!)
          dao.stream.remote      (unchanged)
                                 |
                  yin.repl.host  {:connect! :bind! :unbind!}  (unchanged; :bind-succeeded already carries :port)
```

What stays where it is: the REPL's notice texts and status vocabulary,
the two identities, `rpc`'s terminal vocabulary, the board's loopback
gate and name map, the host adapters.

---

## 2. Semantics of the changes

### 2.1 Projection cause recording (D1), `src/cljc/dao/stream/ws_project.cljc`

**Today.** `project!` closes the ring on any kind in `terminal-events`
and answers true; `step!` closes the ring on the traffic medium's
`:dao.stream/end`; both set `:closed? true` and nothing else. A
`:ws/opened` event falls into the `:else nil` branch.

**Specified.** The projection atom gains two keys, `:cause nil` and
`:opened? false`, at `projection`.

- `project!` on a terminal kind: `(swap! project assoc :closed? true
  :cause kind)`; the swap happens where `step!` today sets `:closed?`
  (so `project!` answers the kind instead of true, and `step!` writes
  both keys in one `assoc`). The first terminal event wins; a projection
  already closed is never stepped again (`step!`'s `(:closed? s)` guard),
  so the cause cannot be rewritten.
- `step!` on `:dao.stream/end`: `:closed? true :cause :dao.stream/end`.
- `project!` on `:ws/opened` for this attachment: `(swap! project assoc
  :opened? true)`, answers nil (non-terminal).
- Accessors:

```clojure
(defn cause
  "What closed the projection: the terminal :ws/event kind, or
   :dao.stream/end for the traffic medium's own end; nil while open."
  [project] (:cause @project))

(defn opened?
  "True once the attachment's :ws/opened passed through this projection.
   Only a dialing end's adapter deposits :ws/opened (dao.stream.ws
   `opened!`); an accepted connection is open at adoption and its
   projection never sees one."
  [project] (:opened? @project))
```

The ordering facts this relies on, verified in `ws.cljc`:

- `closed!` on a connection whose `:resolution?` is false (never opened,
  or refused) emits `:ws/transport-error` and then the terminal
  `:ws/closed`. The projection closes on the first, so a connection that
  never opened has cause `:ws/transport-error`, and `:opened?` false.
- A close with code 4000 is `:ws/ended`; every other code is
  `:ws/closed`.
- Nothing deposits `:ws/not-found` on the current wire (the mirror
  answers not-found per identity). It stays in `terminal-events` and in
  the mapping for totality.

**Docstring.** The namespace docstring's sentence "A terminal lifecycle
event (...) makes the projection close the ring" gains "and record which
one as its cause, read by the composition above through `cause`".

### 2.2 Neutral cause on the lost dial (D2), `src/cljc/dao/stream/remote_channel.cljc`

**Today.** `dial-step` for an `:attached` dial answers `(assoc d :status
:lost :outcome channel-gone)` when `channel-lost?` is true, which is
`(or (:gone? ch) (ws-project/closed? (:project ch)))`. The resolving
branch does the same on `resolve-expired?`. `attach-identities` marks
`:lost` with the attacher's outcome.

**Specified.** One private function and two accessors.

```clojure
(def ^:private projection-causes
  {:ws/ended :ended
   :ws/closed :dropped
   :dao.stream/end :dropped
   :ws/not-found :not-served
   :ws/transport-error :unreachable})

(defn- lost
  "The dial `d` lost at this step, its neutral cause and whether its
   connection ever opened.  A link that expired (the channel map's
   :gone?) is :expired whatever the projection later reads: the dial
   closed the handle itself, so the host's :ws/transport-error or
   :ws/closed that follows is the consequence, not the cause."
  [d]
  (let [ch (ws-project/channel (:dial d))
        p (:project ch)]
    (assoc d :status :lost :outcome channel-gone
           :cause (if (:gone? ch)
                    :expired
                    (get projection-causes (some-> p ws-project/cause)))
           :opened? (boolean (some-> p ws-project/opened?)))))
```

- `dial-step`, attached branch: `(if (channel-lost? d) (lost d) d)`.
- `dial-step`, resolving branch on `resolve-expired?`: `(assoc (lost d)
  :cause :expired)`. The resolving expiry is the connect half of the
  same liveness (3.1), so it is `:expired` even though the link never
  stamped a deadline and `:gone?` is false.
- `attach-identities`, the non-ok attach: `(assoc d :status :lost
  :outcome r :handles handles :cause (when (= :dao.stream/transport-error
  (:dao.stream/outcome r)) :unreachable) :opened? false)`.
- Accessors:

```clojure
(defn cause
  "Why a :lost dial was lost, one of :ended (the peer closed with the
   ended signal), :dropped (the connection closed with any other code,
   or the traffic medium ended), :not-served (the peer disclaimed),
   :unreachable (the connection never opened: refused, or torn down
   before it opened), :expired (a request passed give-up-after); nil
   for a dial that is not lost, or lost by a non-transport attach
   failure."
  [d] (:cause d))

(defn opened?
  "True when the dialed connection opened at some point before the
   dial was lost.  With :expired it separates a peer that stopped
   answering (true, reattach) from one that never answered (false)."
  [d] (:opened? d))
```

**What the five words mean to a consumer, in one table.**

+--------------+----------------------------------------------------------------+------------------------------------------+
| Cause        | Wire fact below the boundary                                   | What a consumer may conclude             |
+==============+================================================================+==========================================+
| :ended       | close code 4000 (`ws/close-ended!`, an `:ended?` stop)         | the served media ended; do not reattach  |
+--------------+----------------------------------------------------------------+------------------------------------------+
| :dropped     | any other close code, or the deposit medium ended              | the connection went; the source is       |
|              |                                                                | untouched; reattach                      |
+--------------+----------------------------------------------------------------+------------------------------------------+
| :not-served  | `:ws/not-found` (dead on the current wire)                     | disclaimed; do not retry                 |
+--------------+----------------------------------------------------------------+------------------------------------------+
| :unreachable | `:ws/transport-error`: the connection never opened              | reachability; a fresh connect may        |
|              |                                                                | succeed                                  |
+--------------+----------------------------------------------------------------+------------------------------------------+
| :expired     | a request past give-up-after (2.4 Expiry), link or resolve     | with :opened? false, as :unreachable;    |
|              |                                                                | with :opened? true, the peer stopped     |
|              |                                                                | answering: reattach                      |
+--------------+----------------------------------------------------------------+------------------------------------------+

**Doc amendment.** `dao.stream.remote.md` 3.1, after "channel loss is
then the link's `end` observation (2.4)": "`ws-project` records which
event closed the ring, and the stepped composition answers it on a lost
dial as one of five neutral causes, `:ended`, `:dropped`, `:not-served`,
`:unreachable`, `:expired`, with whether the connection ever opened; a
consumer refines its own word from them and never from a `:ws/` kind."

### 2.3 Refinement in `yin.repl.connect` (D3), `src/cljc/yin/repl/connect.cljc`

**Today.** `terminal-transition` maps the four RPC terminals to four
statuses and texts; `observe-terminal` applies the first one.
`reattachable?` is `(= :dao.stream.rpc/detached (:terminal client))`.

**Specified.**

```clojure
(defn- refined-terminal
  "The RPC terminal, refined when it is /detached by what the dial
   knows: the peer's ended signal missed during the drain is the ended
   terminal; a connection that never opened is a reachability failure.
   Every other cause, and no cause, keeps the reattachable detach."
  [connection terminal]
  (if-not (= :dao.stream.rpc/detached terminal)
    terminal
    (let [d (:dial connection)]
      (case (remote-channel/cause d)
        :ended :dao.stream.rpc/ended
        :unreachable :dao.stream.rpc/transport-error
        :expired (if (remote-channel/opened? d)
                   terminal
                   :dao.stream.rpc/transport-error)
        terminal))))
```

`observe-terminal` calls `(terminal-transition connection
(refined-terminal connection terminal))`. The texts do not change: the
`:ended` text ("The stream served at ... ended; there is nothing to
reattach to") and the `:transport-error` text ("Could not reach ...; this
is a reachability failure and may succeed on retry") are already right
for the refined cases. `terminal-statuses` is unchanged.

```clojure
(defn reattachable?
  "True when the binding may be reattached: the RPC client's terminal is
   the one reconnectable one and the connection's observed status did
   not refine it to a permanent conclusion."
  [connection client]
  (and (= :dao.stream.rpc/detached (:terminal client))
       (not (contains? #{:ended :not-found :transport-error}
                       (:status connection)))))
```

`reattach` gates on `(reattachable? connection client)`. The 1-arity is
removed so the compiler finds every caller: `driver/connect-command`
(one call) and the `reattachable-is-true-for-a-detached-client-only`
test.

**Why `reattachable?` must see the connection.** `rpc/rebind` accepts any
client whose terminal is `detached`; it cannot know the dial's cause and
must not (`rpc` is transport-free). Without the connection check an
operator could `(connect)` a connection the shell has just reported as
ended, and the fresh dial would then answer `not-found` or refuse: a
wasted round trip and two contradicting notices.

**Driver.** `queueable-terminals` is deleted. `remote-routed?` becomes:

```clojure
(and (:adapter state)
     (not (connect/operator-detached? (:connection state)))
     (or (nil? (remote-terminal state))
         (connect/reattachable? (:connection state) (rpc-client state))))
```

The one subtlety is ordering inside `repl-step`: `poll-remote` steps
the connection (the dial becomes `:lost` with its cause inside
`dial-step`, because the ring is closed only by the projection and the
link is stepped only there) and then polls the RPC client, which reads
`channel-gone` and sets `detached`; `observe-connection` runs after both.
So the cause is always on the dial by the time `observe-terminal` reads
it. `release-queue` and `retry-unsent` keep their `remote-routed?` gate,
so queued lines return to the local shell on a refined `:ended` or
`:transport-error` exactly as they do today for an RPC `:ended`.

**Docstring.** `connect`'s namespace docstring, last paragraph: after
"the single source of terminal truth" add "; a `/detached` is refined by
the dial's neutral cause to `:ended` or `:transport-error` when the
channel composition knows better, and only then".

### 2.4 Ephemeral port binding (D4, D5), `remote_channel.cljc` and `ws.cljc`

**Today.** `serve` refuses `::no-port` unless `(:port spec)` is a
positive integer; `descriptor-of` formats the descriptor before the
bind; `ws/make-endpoint` validates it with `descriptor?`, which requires
a positive port; `accept-connection!` mints every session handle with
`(:descriptor (:config endpoint))`. `observe` on `:bind-succeeded` only
flips `:starting` to `:serving`. Every host adapter's `:bind-succeeded`
value carries the bound port: `http/server-port` on the JVM,
`address.port` on Node (`bound-address`), `.-port server` on Dart,
`bind-port` on `loopback-net`.

**Specified, `ws.cljc` (D5).**

```clojure
(defn servable-descriptor?
  "The served descriptor gate: `descriptor?`, except that the port may be
   0, an endpoint bound ephemerally that names its port once the host
   reports it (`endpoint-bound!`).  A dialed descriptor never names port
   0; `descriptor?` stays the attacher's gate."
  ([x] (servable-descriptor? x transit/profile))
  ([x codec]
   (or (descriptor? x codec)
       (and (= 0 (:ws/port x))
            (descriptor? (assoc x :ws/port 1) codec)))))
```

- `make-endpoint` validates with `servable-descriptor?` and seeds its
  state atom with `:descriptor descriptor`.
- `accept-connection!` reads `(:descriptor (endpoint-state endpoint))`.
- New:

```clojure
(defn endpoint-bound!
  "Record the port the host bound, for an endpoint composed on port 0:
   session handles minted from here name it.  Answers the endpoint."
  [endpoint port]
  (swap! (:state endpoint) update :descriptor
         #(assoc % :ws/port port
                 :dao.stream/identity (str "ws://" (:ws/host %) ":" port (:ws/path %))))
  endpoint)
```

Nothing reads a session handle's descriptor today (checked:
`remote.cljc` reads table entries' and the ring's, never the ws
handle's); `endpoint-bound!` exists so that the data is true, not because
a reader needs it. The identity format mirrors `remote-channel/descriptor-of`.

**Specified, `remote_channel.cljc` (D4).**

- `serve`'s port check becomes: `::no-port` when `(:port spec)` is not a
  non-negative integer, and also when it is 0 while `(:bind-port spec)`
  is a positive integer (advertising "whatever binds" while binding a
  known port is a configuration error, not a wish).
- The server value gains `:ephemeral? (zero? (:port spec))`.
- `observe`, the `(and (= :starting status) (= :bind-succeeded kind))`
  branch:

```clojure
(let [port (:port value)]
  (cond
    (not (:ephemeral? server))
    (assoc server :status :serving)

    (and (integer? port) (pos? port))
    (let [spec (assoc (:spec server) :port port)]
      (ws/endpoint-bound! (:endpoint server) port)
      (assoc server :status :serving :spec spec
             :descriptor (descriptor-of spec) :ephemeral? false))

    :else
    (do (release! server)
        (unbind! server)
        (refused server ::port-unreported {:detail value}))))
```

The `::port-unreported` branch releases and unbinds because, unlike
`::bind-failed`, the listener is bound: it mirrors `lifecycle-lost`'s
`:starting` branch. `descriptor-of` is unchanged and total over port 0
(it formats "ws://h:0/p", the provisional identity).

- `serve`'s docstring: "`:port` 0 is an ephemeral bind: the descriptor is
  provisional until the host's `:bind-succeeded` reports the bound port,
  which `:spec` and `:descriptor` then name; a host that reports none is
  `::port-unreported`."
- `dial` is unchanged: a spec with port 0 formats a descriptor the
  attacher refuses as `invalid-descriptor`, which is correct.

**Doc amendments.** `dao.stream.remote.md` 3.1, the sentence "(port 0 is
the host's ephemeral bind behind an explicit advertised port)" becomes
"(port 0 is the host's ephemeral bind; with an advertised port 0 the
specification is ephemeral, and the bound port the host reports under
`bind-succeeded` becomes the advertised one)". 3.0 "Lifecycle
observation": after "`bind-succeeded` serves" add "and, for an ephemeral
specification, names the bound port, without which the bind is refused
as port-unreported after releasing the listener". `dao.stream.ws.md`
Serving, after "The composition mints the offer cursor ... before
starting the listener.": "An endpoint may be composed on a descriptor
naming port 0 and told the bound port once the host reports it
(`endpoint-bound!`); session handles minted from then on name it. A
dialed descriptor never names port 0."

### 2.5 `yin.repl.serve` and `yin.repl.main` (D6)

**`serve.cljc`.**

- Delete the `(= 0 advertised-port)` pre-check, the
  `:yin.repl.endpoint/ephemeral-port-unsupported` code and the comment
  above it. `advertised-port (or advertised-port bind-port)` already
  yields 0 for `--port 0`.
- `url`: `(when (pos? port) (str ...))`. Before the bind there is nothing
  an operator can type; nil is the honest answer, and `summary`'s `:url`
  follows.
- `observe-server`: before the `Serving` notice, when `changed?` and the
  server is `:serving`, `(assoc-in endpoint [:spec :port] (:port (:spec
  server)))`. For a non-ephemeral endpoint this is a no-op (same port).
  The notice then reads "Serving daostream:ws://h:<bound>/repl".
- `refused` (synchronous) and `refused-text` (observed): render
  `::remote-channel/port-unreported` as ";; endpoint bind failed: the host
  reported no bound port for an ephemeral bind" with the fact's `:detail`
  summarized, through `bind-failed-text`.
- Namespace docstring: one sentence, "A `--port 0` bind advertises the
  port the host reports once bound; until then the endpoint has no URL."

**`main.cljc`.**

- `parse-args` already admits 0 to 65535. No change.
- `banner`: when `(zero? (:port opts))`, the serving line reads "serving
  on all interfaces on an ephemeral port; the Serving line names it once
  bound. Anyone who can reach this port can evaluate code in this shell;
  there is no authentication." The positive-port text is unchanged.
- The docstring words at `main.cljc` about `:ws/ended`/`:ws/closed`, if
  still present (S3b made them optional), are reworded to "the ended
  answer, not a bare close". One line, no behaviour.
- `free-port!` in `main_test.cljc` (two hosts) keeps working; its
  docstring sentence "serve! fixes its descriptor at composition, so an
  ephemeral bind cannot serve" is deleted. Moving the process-level tests
  onto `--port 0` is deferred (section 6).

**`embed.cljc`.** No code change: `start` passes `:bind-port port`,
`status` reads `:url` (nil until bound), `status-text` reads status.

### 2.6 Driver-paced DHT exit (D7), `dht.cljc`, `board.cljc`, `main.cljc`

**Today.** `main/close-index-store!` calls `repl.dht/close!`, which
stops the board and runs exactly one `serve-step` at the node's last
`::now`, then closes every follow dial. With the board's drain grace 0
that tick releases sessions and asks the host to unbind, but the host's
`:stopped` confirmation is never observed, nothing is printed, and a
host whose unbind completes asynchronously (http-kit's `stop`, Node's
`close`) is exited under. The REPL endpoint, by contrast, is drained by
`stop-tick` under `stop-ticks` on every host.

**Specified.**

`head/board.cljc`:

```clojure
(defn stopped?
  "True once the board's server owes nothing: stopped, or refused."
  [server]
  (contains? #{:stopped :refused} (:status server)))
```

`dht.cljc`:

```clojure
(defn stop!
  "Initiate the head composition's exit on the shell's node: the board's
   server is asked to stop (`head.board/stop!`), every follow dial is
   closed and no dial is composed again, and the node is marked
   stopping.  Performs no other I/O; `step` completes the stop and
   `stopped?` reports it.  A shell without a node, or already stopping,
   is answered unchanged."
  [shell])

(defn stopped?
  "True when the head composition owes nothing at exit: no node, no
   board server, or a board server that is stopped or refused."
  [shell])
```

- `stop!` marks `::stopping? true` on the node, `(update-in node
  [::publisher :server] head.board/stop!)` when a server exists, closes
  each `(:dial link)` under `::follow :links` and replaces the links map
  with `{}`.
- `step`: unchanged in structure. `step-board` already steps whatever
  server exists, so a `:stopping` server progresses to `:stopped`;
  `step-links` composes no dial when `(::stopping? node)` (one `cond`
  guard at the top of `step-link`: a stopping node answers `[follower
  link nil]` for every source). `head/step` keeps running: a follower
  with no handles is idle.
- `step-board` publishes the board's end once: on the transition to
  `:stopped`, the line "dht: the head board stopped" (outcome
  `:confirmed`) or "dht: the head board stopped without host completion
  (<outcome name>)" for `::remote-channel/unconfirmed`,
  `::unbind-failed`, `::host-stopped`. Derived from `before` versus the
  stepped status, as the token line is.
- `close!` keeps its body but documents its place: "The last resort
  after the exit drain's budget (`yin.repl.main/stop-ticks`): whatever
  the board still owes is asked for once more and not awaited. The
  ordinary exit is `stop!`, then `step` until `stopped?`." It first calls
  `stop!` on the shell's node value so that a direct caller (a test, an
  embedder without a drain) still closes the dials, then runs the one
  `serve-step` only when the server is not already `stopped?`.

`main.cljc`:

- `stop-tick` becomes `(stop-tick state server now)` answering `[state'
  server' lines stopped?]`: `(repl.dht/step (:repl state) now)` gives
  `[repl dht-lines _]`; `serve/step` and `take-outbox` as today when
  `server` is non-nil; `lines` is `dht-lines` followed by the endpoint's
  texts; `stopped?` is `(and (or (nil? server') (serve/stopped? server'))
  (repl.dht/stopped? repl))`. Its docstring keeps the sentence about
  `serve/stopped?` and adds "and `yin.repl.dht/stopped?` for the shell's
  head board".
- The stop is initiated in one place per host, where `serve/stop!` is
  called today: JVM `drain-server!` becomes `(drain! state server w)`
  answering `state'`, called as `(close-index-store! (drain! state'
  server' w))`; it starts with `(update state :repl repl.dht/stop!)` and
  `(some-> server serve/stop!)` and loops `stop-tick` under `stop-ticks`.
  Node and Dart: the `:running?` false branch sets `:state (update state'
  :repl repl.dht/stop!)`, `:server (some-> server' serve/stop!)`,
  `:stopping stop-ticks`, dropping the `:else (finish!)` shortcut (with
  neither composition `stop-tick` answers stopped on its first tick, as
  `stopping-an-endpoint-that-never-bound-claims-nothing` already
  requires of the endpoint). The `:stopping` branch calls the 3-arity
  `stop-tick` and keeps both values in the box.
- `close-index-store!`'s docstring: "A DHT store's head board and dials
  have been asked to stop and drained by then (`stop-tick`);
  `yin.repl.dht/close!` is the last resort for whatever the budget left."
- `stop-join-millis` is unchanged: the drain is still `stop-ticks + 1`
  steps.
- `flutter.cljd` uses `embed` and has no DHT; unchanged.

---

## 3. Step-by-step implementation (Implementation Engineer, Claude Opus 5.5)

### 3.1 Before writing code

1. Read `docs/agents/build-n-test.md` and `docs/agents/format.md`. Run
   `mise trust` and `npm ci` in the worktree.
2. Read, in this order: this brief; `ws_project.cljc` (`projection`,
   `project!`, `step!`, `dial-step!`, `channel`); `ws.cljc` lines 55 to
   110 and 540 to 670 (`descriptor?`, `make-endpoint`,
   `accept-connection!`, `closed!`); `remote_channel.cljc` (`serve`,
   `observe`, `lifecycle-lost`, `attach-identities`, `dial-step`,
   `channel-lost?`); `connect.cljc` (`observe-terminal`,
   `terminal-transition`, `reattachable?`, `reattach`); `driver.cljc`
   (`remote-routed?`, `connect-command`, `observe-connection`,
   `repl-step`); `serve.cljc` (`serve!`, `url`, `observe-server`,
   `refused`); `dht.cljc` (`close!`, `step-board`, `step-link`, `step`);
   `main.cljc` (`banner`, `stop-tick`, `drain-server!`, the Node and
   Dart tick functions, `close-index-store!`); `loopback_net.cljc`.
3. Run `bb test:clj` once on the untouched tree for the baseline. Note
   any pre-existing failure in the report rather than fixing it.

### 3.2 Order of work (each step leaves `bb test:clj` green)

1. **`ws_project.cljc`** per 2.1, with the `ws_project_test` additions of
   4.2.
2. **`ws.cljc`** per D5 (`servable-descriptor?`, the state-held
   descriptor, `endpoint-bound!`), with the two `ws_test` cases of 4.3.
   Run `clojure -M:test -n dao.stream.ws-test -n dao.stream.ws-project-test`
   and the JVM host test `yin.repl.host.jvm-test` before moving on; this
   is the protocol boundary.
3. **`remote_channel.cljc`, dialing side** per 2.2 (`lost`, `cause`,
   `opened?`), with 4.1 items 1 to 5.
4. **`remote_channel.cljc`, serving side** per 2.4 (`:ephemeral?`, the
   `observe` branch, `::port-unreported`, the `::no-port` rule), with the
   `loopback_net` allocation of D8 and 4.1 items 6 to 9.
5. **Docstrings and docs**: 2.1, 2.2, 2.4 amendments to
   `dao.stream.remote.md` and `dao.stream.ws.md`.
6. **`connect.cljc`** per 2.3, `driver.cljc` per 2.3, then
   `connect_test` per 4.4. `bb test:clj`.
7. **`serve.cljc`** and **`main.cljc`** per 2.5; `serve_test` per 4.5;
   `main_test` per 4.5; `embed_test` unchanged. `bb test:clj`.
8. **`board.cljc`**, **`dht.cljc`**, **`main.cljc`** per 2.6;
   `dht_head_test` per 4.6. `bb test:clj`.
9. **Gate.** 4.7 grep; `clj -M:kondo --lint src test` 0/0; `cljstyle
   check`; one full `bb test` in the foreground, one lane set at a time;
   `clojure -M:test -i :slow -n yin.repl.main-test` once.
10. **Report** to
    `collab/1791403500000-engineer-stream-s4.claude-opus-5-5.findings.md`
    inside this worktree: what changed per file, each acceptance item of
    section 4 with its evidence (test name, lane, counts), every
    deviation from this brief with its reason, every pre-existing
    failure found at the baseline, and open questions. Do not stage
    `collab/` (the pre-commit hook enforces it).

### 3.3 Commit

On green with the project's format, one commit:
`feat(dao.stream): terminal causes above the projection, ephemeral binds,
driver-paced board exit (cross-machine stream slice S4)`, a body listing
the `ws`, `ws-project` and `remote-channel` additions and the
`connect`/`serve`/`dht`/`main` consequences, and no `Co-Authored-By`
line (format.md governs; it overrides the harness reminder). Do not
fast-forward master or push; the orchestrator lands after Architect
sign-off.

---

## 4. Acceptance criteria and test obligations

### 4.1 `test/dao/stream/remote_channel_test.cljc` (additions)

Use the existing `world`, `tick!`, `run-until`, `host-of` fixtures; the
S3b two-entry world of `connect_test` is not needed here. New deftests:

1. `a-dropped-connection-is-lost-dropped`: attached name dial; close
   every loopback connection with code 1006 (`net/close-conn!`); tick;
   the dial is `:lost`, `(rc/cause d)` is `:dropped`, `(rc/opened? d)`
   true, `:outcome` still `channel-gone`.
2. `an-ended-stop-is-lost-ended-when-the-end-is-missed`: `:drain-grace-ms
   0` (the default) and `(rc/stop! server {:ended? true})` with one
   attached reader holding no outstanding `next`; tick; the reader's dial
   is `:lost` with cause `:ended`. Assert the loopback connection's
   recorded close code is 4000 (`:close-code` on the conn, already
   recorded by `close-conn!`).
3. `a-refused-connection-is-lost-unreachable`: no listener on the port;
   an identities dial with `:now`; two ticks; `:lost`, cause
   `:unreachable`, `opened?` false. A `testing` block: a name dial on
   the same world resolves to `:lost` with the same cause.
4. `an-expired-link-is-lost-expired`: the S3a `liveness` bounds; attached
   dial; `net/blackhole!` toward the client; ask; tick past
   `give-up-after`; `:lost`, cause `:expired`, `opened?` true.
5. `a-never-opening-connection-is-lost-expired-unopened`: the
   never-acknowledging listener of
   `a-connection-that-never-opens-is-lost-at-give-up-after`; an
   identities dial with `:now 1000`; at 1150 `:lost`, cause `:expired`,
   `opened?` false. A `testing` block repeats it for the name dial
   (resolving expiry): cause `:expired`.
6. `an-ephemeral-serve-advertises-the-bound-port`: `(rc/serve {:spec
   (assoc spec :port 0) ...})` is `:starting` with `:ephemeral? true` and
   `:descriptor` naming port 0; after one tick (the fixture allocates a
   port and deposits it) the server is `:serving`, `(:port (:spec s))`
   is the allocated port, `:descriptor` names it, `:ephemeral?` false;
   a name dial to `{:host h :port allocated :path p}` attaches and reads
   the toy. Assert the session handle's `stream/descriptor` identity
   names the allocated port (this is what `endpoint-bound!` buys).
7. `an-ephemeral-bind-that-reports-no-port-is-refused`: a `:bind!` that
   deposits `:bind-succeeded {:host h}` without `:port`; after one tick
   the server is `:refused` with `::rc/port-unreported`, `:detail` the
   fact's value, the unbind called once (counting unbind), no session.
8. `port-zero-beside-a-positive-bind-port-is-no-port`: `{:port 0
   :bind-port 9}` is `::rc/no-port` synchronously; `{:port 9 :bind-port
   0}` still binds (the S3b case, unchanged).
9. `dialing-port-zero-is-lost-invalid-descriptor`: an identities dial
   toward `(assoc spec :port 0)` is `:lost` with outcome
   `:dao.stream/invalid-descriptor` and `:cause nil`.

`loopback-net/listen-on` amendment (D8): when `bind-port` is 0, allocate
`(+ 49152 (count (:listeners @net)))` skipping ports in use, register
the listener under the allocated port, deposit `:bind-succeeded {:host
bind-host :port allocated}` and answer `{:dao.stream/outcome
:dao.stream/ok :port allocated}` so `unbind-on` and `unlisten!` find it.
A positive `bind-port` behaves as today.

### 4.2 `test/dao/stream/ws_project_test.cljc` (additions)

Using `composed`, `deposit!`, `event`, `payload`:

- `the-cause-is-the-first-terminal-event`: one deftest, a `testing` block
  per kind in `#{:ws/closed :ws/ended :ws/not-found :ws/transport-error}`:
  deposit the event; step; `closed?` true and `(project/cause p)` is the
  kind. A further block: `:ws/transport-error` followed by `:ws/closed`
  in one step leaves the cause `:ws/transport-error`.
- `the-medium-end-is-its-own-cause`: close the traffic medium; step;
  cause `:dao.stream/end`.
- `opened-is-recorded-and-another-attachments-opened-is-not`: deposit
  `:ws/opened` for `me`, step, `opened?` true; a fresh composition with
  `:ws/opened` for `"other"`, step, `opened?` false and the cursor
  advanced.

### 4.3 `test/dao/stream/ws_test.clj*` (additions, two cases)

- `a-served-descriptor-may-name-port-zero-and-a-dialed-one-may-not`:
  `(ws/servable-descriptor? d0)` true, `(ws/descriptor? d0)` false for a
  port-0 descriptor; `make-endpoint` over it does not throw; a
  `make-attacher` attach! of it answers `:dao.stream/invalid-descriptor`.
- `endpoint-bound-renames-later-session-handles`: `make-endpoint` on
  port 0; `endpoint-bound!` with 4567; `accept-connection!` with a fake
  socket; the offered handle's `stream/descriptor` identity ends in
  ":4567/<path>" and `:ws/port` is 4567.

Find the existing ws test namespace by `ls test/dao/stream/ | grep ws_`
and add there; if there are several, the one that already composes
`make-endpoint`.

### 4.4 `test/yin/repl/connect_test.cljc`

- `a-connection-that-never-opens-is-detached-at-give-up-after` is renamed
  `a-connection-that-never-opens-is-a-transport-error-at-give-up-after`:
  the RPC terminal stays `:dao.stream.rpc/detached` (unchanged, asserted
  so the layering is visible), `(rc/cause (:dial c))` is `:expired`,
  `(rc/opened? (:dial c))` false, and `observe-terminal` answers status
  `:transport-error` with the "reachability failure" text. The `testing`
  block for the refused connection asserts cause `:unreachable` and the
  same refined status.
- Add `an-ended-signal-missed-during-the-drain-is-ended-not-detached`:
  the two-entry world with `:bounds {:drain-grace-ms 0}` passed to
  `remote-channel/serve`; open; tick until the cursor is minted; close
  the answers ring then `(remote-channel/stop! server {:ended? true})`;
  tick; the RPC terminal is `detached` (the client held no outstanding
  `next`), the dial's cause is `:ended`, `observe-terminal` answers
  `:ended` with the "nothing to reattach" text, and `(connect/reattachable?
  connection client)` is false while `(rpc/...)` would still say
  detached.
- Add `a-dropped-connection-stays-detached-and-reattachable`: the
  existing reattach test's drop (operator `close!`) asserts
  `(rc/cause ...)` `:dropped` and `reattachable?` true, then reattaches
  as today.
- `reattachable-is-true-for-a-detached-client-only` becomes the 2-arity:
  `(connect/reattachable? {:status :detached} {:terminal detached})` true;
  `{:status :ended}` with the same client false; `{:status :detached}`
  with terminal `ended` false.
- `every-terminal-reason-maps-to-its-own-status-and-notice` is unchanged
  (its connection has no lost dial, so no refinement applies; assert
  additionally that a `detached` terminal on it stays `:detached`).

### 4.5 `test/yin/repl/serve_test.cljc` and `test/yin/repl/main_test.cljc`

- `an-ephemeral-bind-names-the-limit-instead-of-a-descriptor-refusal` is
  replaced by `an-ephemeral-bind-advertises-the-bound-port`: `(endpoint!
  {:bind-port 0})` is `:starting`, `(serve/url endpoint)` nil,
  `(:url (serve/summary endpoint))` nil; after `tick` the status is
  `:running`, `(serve/url endpoint)` names the fixture's allocated port
  (read it from `(:bound host)`'s deposit or from `(:port (:spec (:server
  endpoint)))`), and the outbox holds "Serving daostream:ws://127.0.0.1:
  <allocated>/repl". Then `connect!` over that URL round-trips one
  request, as `stop-ends-the-served-media-before-it-closes-the-session`
  does.
- Add `an-ephemeral-bind-whose-host-reports-no-port-fails`: a fixture
  `:bind!` whose deposit omits `:port`; after one step `:failed`, the
  notice contains "reported no bound port", `(:released host)` has one
  entry.
- `an-ephemeral-bind-with-an-advertised-port-binds-port-zero` is
  unchanged.
- `main_test`: `an-ephemeral-bind-is-refused-with-its-reason` becomes
  `an-ephemeral-bind-serves-and-the-serving-line-names-the-port`: the
  injected `host-adapter` deposits `:bind-succeeded` with `:port 43210`
  when asked for 0; after `step-all` the server is `:running` and the
  lines contain "Serving daostream:ws://" and "43210". Add to
  `arguments-behave-as-they-do-in-v1-except-telemetry` a `testing` block
  that `(banner {:port 0})` contains "ephemeral" and not ":0".

### 4.6 `test/yin/repl/dht_head_test.cljc`

- Add `the-board-exit-is-driver-paced`: a publisher shell with its board
  bound (reuse `the-token-prints-once-when-the-board-is-bound`'s setup
  and a reader attached through `follower`); `(update-in w [:a :repl]
  repl.dht/stop!)`; `repl.dht/stopped?` false; step the shell with the
  net pumped until `stopped?` (at most 5 ticks); the net has no listener;
  the lines contain "dht: the head board stopped"; the reader's next
  step prints its `source-lost` line and composes no dial on the
  publisher's side (`(get-in w [:a :repl :dht ::repl.dht/follow :links])`
  empty).
- Add `close-is-the-last-resort-and-idempotent`: `repl.dht/close!` on a
  shell never stopped closes the dials and leaves nothing listening;
  `close!` again is nil without throwing; `close!` on a shell already
  `stopped?` runs no step (count `serve-step` calls through a wrapped
  `unbind!` that must not be called twice).
- The `close!` helper in this test keeps calling `main/close-index-store!`.
- `main_test`'s stop tests (JVM, the `^:slow` fact 4 included) run
  unchanged; the Node and Dart stop branches are exercised by the fast
  `main_test` facts that quit a shell with a served endpoint, which now
  pass through the 3-arity `stop-tick`.

### 4.7 The boundary gate

Run from the worktree root; both must print nothing:

```sh
grep -n ":ws/\|dao.stream.ws\b\|ws-project" \
  src/cljc/yin/repl/serve.cljc src/cljc/yin/repl/connect.cljc src/cljc/yin/repl/dht.cljc \
  src/cljc/yin/repl/driver.cljc src/cljc/yin/repl/main.cljc src/cljc/yin/vm/linker/head/board.cljc
grep -rln ":ws/\|dao.stream.ws\b\|ws-project" test/yin/repl/ | grep -v "test/yin/repl/host/"
```

A `remote-channel/cause` of `:ended` or `:unreachable` is not a `:ws/`
token; that is the point.

### 4.8 Lanes

Per `docs/agents/build-n-test.md`: iterate with `bb test:clj` or
`clojure -M:test -n <ns>`; land with one full `bb test` (JVM, Node,
Dart), one lane set at a time, in the foreground. `clj -M:kondo --lint
src test` 0/0; `cljstyle check` clean. Confirm "Testing
dao.stream.ws-project-test", "Testing dao.stream.remote-channel-test",
"Testing yin.repl.connect-test", "Testing yin.repl.serve-test" and
"Testing yin.repl.dht-head-test" appear in the Node output.

Cross-host traps that apply here (project memory, all verified):

- `#?(:clj ...)` does not exclude code from the Dart build; use
  `#?(:cljd nil :clj ...)` with `:cljd` first (the `main.cljc` stop loops
  are host-conditional).
- `(assoc nil :a 1 :b 2)` on ClojureDart is a one-element list; `lost`
  and the `observe` branch start from an existing map, as written.
- ClojureDart `for` over a seq longer than 32 may hand the body nil; use
  `mapv`/`keep`.
- A protocol method with `[_ _]` params fails on ClojureDart; irrelevant
  here unless a test fixture reifies a handle.
- `ex-info` data on ClojureDart: use `ex-message`/`ex-data`, never
  `.getMessage`.

### 4.9 Definition of done

All of: 4.1 to 4.6 green on JVM, Node and Dart; 4.7 prints nothing; the
existing JVM wire test and the process-level `main_test` facts (the
`^:slow` fact 4 run once) green; the three doc amendments (2.2, 2.4) in
the same commit; every S3a and S3b test green with only the amendments
named in 4.4 and 4.5; `ws.cljc`'s diff is D5 and nothing else;
`rpc.cljc`, `apply.cljc`, `remote.cljc` unchanged.

---

## 5. Rules and judgement calls

### 5.1 Rules

- Do not touch `rpc.cljc`, `apply.cljc`, `remote.cljc`. `ws.cljc`
  changes by D5 alone. If a further change there seems necessary, stop,
  write the reason in the report under "Blocked", and finish everything
  that does not depend on it.
- No `:ws/` token, close code, or `dao.stream.ws*` require above
  `dao.stream.remote-channel` (4.7).
- Do not background test lanes; a `claude -p` delegate that backgrounds
  a lane exits with the turn and the lane dies.
- Keep the REPL's notice texts byte-identical where a test asserts them;
  the only new texts are the port-unreported bind failure (2.5), the
  ephemeral banner line (2.5) and the board stop lines (2.6).
- Commit per 3.3; do not fast-forward master or push.

### 5.2 Judgement calls permitted without asking

- Exact helper names and private function boundaries.
- Whether `lost` takes the channel map or the dial.
- The loopback-net port allocation scheme, provided a bind to 0 never
  collides with a listener already registered.
- Whether the board stop lines live in `step-board` or a sibling helper.
- The fixture layout of the new `dht_head_test` cases.
- Whether `servable-descriptor?` is implemented by delegation (as
  written) or by inlining `descriptor?`'s checks.

### 5.3 Judgement calls prohibited

- Making the dial's `:lost`, or its cause, produce or overwrite an RPC
  terminal, or be consulted before the RPC client has one.
- Refining any RPC terminal other than `detached`.
- Mapping `:expired` with `:opened?` true to anything but `:detached`.
- Letting `descriptor?` (the dial gate) accept port 0.
- Closing a table handle from inside `remote-channel`.
- Replacing the driver-paced DHT drain with a sleep or a synchronous
  loop; shortening `stop-ticks`.
- Reading `:bind-succeeded`'s host (only its port) into the advertised
  spec: the advertised host is the operator's (`local-ip`,
  `--dht-bind`), and a wildcard bind reports `0.0.0.0`.

---

## 6. Deferred, with reasons

- **Process-level tests on `--port 0`.** `main_test`'s `free-port!`
  (JVM and Node) can be replaced by reading the port from the child's
  "Serving" line once this slice lands. Not here: the fact bodies spawn
  real processes and parsing their stdout is its own fixture work; this
  slice proves the path in process on three hosts.
- **`wss://`.** Unchanged refusal in `parse-url`; the descriptor form is
  the ws design's open item.
- **`:not-served` on the wire.** No code path deposits `:ws/not-found`
  today; the word is mapped for totality. If a future disclaimer frame
  (ws.md, code 4004) returns, the mapping is already in place and
  `connect` would refine `detached` to `:not-found` by one more `case`
  line.
- **A UDP listener at the WebSocket's port, or the reverse.** The
  ephemeral descriptor now finalizes on `:bind-succeeded`, which is the
  shape that composition needs; composing the two sockets is the DHT's
  own slice.
- **Refining the head follower's `source-lost` line by cause.**
  `dht/lost-reason` reads `:outcome` only; a `:ended` board stop could
  say "the publisher stopped" instead of "stopped answering". One line in
  `dial-line`, but the follower redials by design on every loss
  (head.md 5.1), so the distinction changes no behaviour. Left for a
  slice that changes the redial policy.
- **Node and Dart `endpoint-bound!` parity for handles minted before the
  bind completes.** On Node the bind is asynchronous and a connection
  cannot arrive before `on-listening`; on the JVM `run-server` binds
  synchronously before `:bind-succeeded` is deposited, so a connection
  accepted between the bind and the next `serve-step` is minted on the
  provisional descriptor. Unobserved by any reader (2.4); recorded so
  that a future reader of session descriptors knows the one-tick window.

---

## 7. Risks found while specifying

- **The refinement is read-once.** `observe-terminal` applies the first
  terminal and never revisits. If a dial's cause arrived one tick after
  the RPC terminal, the refinement would be missed. Section 2.3 shows it
  cannot: both are produced inside the same `dial-step`, before
  `rpc/poll!`. The `connect_test` additions pin it on every host. Should
  a future transport project the ring from outside `dial-step`, the
  ordering argument must be re-made.
- **`reattachable?` by status.** A connection whose status was refined to
  `:transport-error` keeps an RPC client whose terminal is `detached`.
  `rpc/rebind` would accept it; `connect/reattach` refuses it by the
  2-arity gate, and the driver's `open-connection` path (a fresh dial for
  the same URL after a non-reattachable terminal) is what `(connect url)`
  then takes, which is right: a reachability failure wants a fresh
  binding, not an inherited queue (driver docstring, `queueable-terminals`
  rationale, preserved in `remote-routed?`).
- **Ephemeral binds on the JVM deposit synchronously.** `listen!`
  deposits `:bind-succeeded` inside `:bind!`, so `remote-channel/serve`
  answers `:starting` with the fact already on the lifecycle medium and
  the first `serve-step` finalizes. No consumer may read `:spec`'s port
  before that step; `serve/url` answering nil until then is the guard.
- **The DHT drain shares the endpoint's budget.** `stop-ticks` (200) at
  `tick-millis` covers the board's `stop-grace-ms` (2000 ms) with room;
  a host whose unbind never completes prints `stop-timeout-text` once,
  as for the endpoint, and `close!` runs as the last resort.

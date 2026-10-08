# Track B Slice S3b: the Remote REPL over `dao.stream.remote-channel`

Architectural specification and implementation brief.
Lead System Architect: claude-fable-5-1. Date: 2026-10-08.
Tree: branch `stream-crossmachine-s3b` at master `802d9ee2`, worktree
`/Users/sto/workspace/datomworld-stream-s3a`.

Reference: `docs/design/dao.stream.remote.md` (2.1 to 2.5, 3.0, 3.1, 5),
`docs/design/dao.stream.ws.md` (Serving, Ending a served stream, Operations),
`docs/design/yin.vm.linker.dht.head.md` (8.3), the S3a-2 sign-off
(`archive/1791394000000-architect-stream-s3a-2-signoff.claude-fable-5-1.findings.md`).

Everything below was read against the code as it stands on `802d9ee2`:
`remote_channel.cljc` (646 lines), `ws_project.cljc`, `remote.cljc`,
`rpc.cljc`, `serve.cljc` (733), `connect.cljc` (584), `head/board.cljc`, the
five consumers of serve/connect (`main`, `driver`, `embed`, `dht`,
`slice_peer`) and their tests.

---

## 0. Summary of decisions

| # | Decision | Where |
|---|----------|-------|
| D1 | A table entry may declare any non-empty subset of `#{:reader :writer}`; `remote-channel/serve` validates that the declared surface is within the handle's own natures and refuses `::invalid-table` otherwise. | 2.1 |
| D2 | `remote-channel/dial` gains `:identities [id ...]` as the alternative to `:name`, with `:now` taken at dial time: the channel is established and every identity attached at once inside `dial` (deferred confirmation), answering `:status :attached` with `:handles {identity handle}` and `:handle nil`. `(handle d identity)` is the 2-arity accessor. `:now` is what gives the kept probes their `give-up-after` deadline. | 2.2 |
| D3 | The writable-append ambiguity is resolved by contract, not by new machinery: a reflection's `append!` outcome is the channel writer's acceptance only; the REPL confirms evaluation solely by the correlated RPC answer; the dropped-answer case is covered by `give-up-after` and reads as `:detached`. No event writer is composed by `connect`. | 2.3 |
| D4 | `remote-channel/stop!` takes an options map `{:ended? bool}`; a new bound `:drain-grace-ms` (production default `0`) keeps accepted sessions open and answering for that long after the first stopping tick so that a reader's outstanding `next` on an ended medium is answered `end` before its connection closes. An `:ended?` stop closes sessions with the ws ended code through `ws/close-ended!`. | 2.4 |
| D5 | `remote-channel/detach!` closes the dialed connection only and leaves the dial steppable until it observes the loss (`:lost`, channel-gone). `close!` stays the full close. `connect/close!` uses `detach!`; `connect/reattach` uses `close!` on the old dial. | 2.5 |
| D6 | The endpoint spec gains optional `:bind-host`: the listener binds there, the descriptor still names `:host`. | 2.6 |
| D7 | The server value gains a monotonic `:diagnostic-count`; `remote-channel/attachment` answers the dialed attachment id. | 2.7 |
| D8 | `yin.repl.serve` becomes a thin interpreter over `remote-channel/serve` and `serve-step`; it keeps its own status vocabulary (`:new :starting :running :stopping :stopped :failed`) and its outbox notices, derives them from status transitions, and stops advancing requests once stopping. | 3.1 |
| D9 | `yin.repl.connect` parses a URL to a portable spec `{:host :port :path}`, dials by `:identities`, steps by value with the driver's `now`, and keeps the RPC client's `:terminal` as the single source of terminal truth. | 3.2 |
| D10 | Zero `:ws/` tokens, zero requires of `dao.stream.ws` or `dao.stream.ws-project`, in `serve.cljc` and `connect.cljc`; the same for every test under `test/yin/repl/` except the JVM host-adapter tests. | 1.3, 4.5 |

---

## 1. Foundations, invariants and layering

### 1.1 Why this slice exists

S3a landed the stepped channel composition (`dao.stream.remote-channel`) and
proved it with the head board: a one-entry read-only table, one named
dial, one reflection. The REPL is the second consumer, and the first that
writes: its table has a writer entry, its client holds two reflections over
one link, it must distinguish "the served stream ended" from "my connection
dropped", and it must reconnect. S3b makes `remote-channel` carry those
four things and moves the REPL onto it, so that `yin.repl.serve` and
`yin.repl.connect` know no transport, exactly as `head/board.cljc` already
does not.

### 1.2 Invariants this slice is bound by

1. **`dao.stream` is the sole boundary for cross-machine communication**
   (`dao.stream.remote.md` 3.0). Above `dao.stream.remote-channel` no
   namespace names a transport, a frame, a socket, or a `:ws/*` key.
2. **Driver-paced, clock-free.** Nothing in `remote-channel`, `serve` or
   `connect` reads a clock or schedules itself. Every bound is measured
   against the `now` the driver hands a step. (Owner stance on `fetch`:
   liveness is the driver's and the lease's, not the composition's.)
3. **Refusals are data.** Composition errors throw at composition (an
   invalid bound); everything after that is a map with `:status`/`:reason`
   or an outcome map.
4. **No server/client privilege** (owner invariant 2026-09-25). "Serve" and
   "dial" are establishment roles because TCP has them. Once established
   the channel is symmetric; the REPL's "server" is only the peer whose
   table holds the two entries and whose interpreter has the eval policy
   (`dao.stream.remote.md` 5, "Request and response service").
5. **The mirror never rewrites a cursor, anchor or outcome**; the served
   stream is the original. A network drop is never a source gap.
6. **`dao.stream.apply` and `dao.stream.rpc` stay transport-free.** The
   REPL's request/answer envelopes (`dao.stream.rpc` over `apply`) do not
   change, and no `:dao.stream.remote*` key appears in them.
7. **A name is lookup data, never an identity.** The REPL's two entries are
   reached by stable identities (`"yin.repl/requests"`, `"yin.repl/answers"`)
   and need no name map; the board is reached by name because its identity
   is new per start. Both are legitimate uses of section 2.
8. **No backward compatibility.** The repo is dev-only; clean breaks are
   preferred over shims. Tests that reach into removed internals are
   amended, not preserved.
9. **Minimal diff.** The S3b changes are the ones listed here. Nothing in
   `yin.repl.driver`, `yin.repl.main`, `yin.repl.embed` changes beyond what
   the new `connect/step!` arity and the removed internals force.

### 1.3 Layering after S3b

```
yin.repl.main / yin.repl.embed / yin.repl.driver        (cadence, printing, shell)
        |                              |
yin.repl.serve                   yin.repl.connect        (REPL policy: table, interpreter,
        |                              |                  URL grammar, notices, terminal words)
        +------------- dao.stream.remote-channel --------+   serve/serve-step/stop!  dial/dial-step/detach!/close!
                                 |
          dao.stream.ws-project  +  dao.stream.ws  +  dao.stream.remote   (unchanged in S3b)
                                 |
                  yin.repl.host  {:connect! :bind! :unbind!}  (host seam, unchanged)
```

What `yin.repl.serve` keeps: the two media and their capacities, the table,
the interpreter over the local ends (`evaluate`, `deliver-answer`,
`advance-requests`), the REPL-policy pre-checks (wildcard bind needs an
advertised host, port 0 is unsupported, a host without a binder), the
notice outbox and its texts, `summary`, `moved?`, `stopped?`.

What `yin.repl.connect` keeps: the URL grammar and canonicalisation, the
two identities, `open`/`reattach`/`close!`/`step!`/`observe-terminal`/
`summary`, the terminal words and their notices, `operator-detached?`,
`reattachable?`.

What both lose: every `ws/make-endpoint`, `ws/make-attacher`,
`ws/accept-connection!`, `ws/descriptor?`, `ws-project/*` call, every
`:ws/*` key, every slot, control medium and lifecycle medium composition,
`stop-grace-ms`, `finish-stop`, and the per-connection `make-media`.

---

## 2. Semantics of the `remote-channel` additions

All additions live in `src/cljc/dao/stream/remote_channel.cljc` unless
stated. Each is small, additive and keeps every S3a behaviour when its new
option is absent or zero. The S3a tests continue to pass with two
assertions amended (2.4, the `:stop` map shape).

### 2.1 Writer-surface table entries (D1)

**Today.** `valid-table?` checks only that each entry is a map with a
`:handle`. The table is passed to `ws-project/make-acceptor` untouched. The
mirror already answers `append!` when the entry declares `:writer`
(`remote.cljc` `required-surface`, `answer!`), so a writer entry works
today without validation.

**Specified.** `valid-table?` becomes:

```clojure
(defn- valid-entry?
  [{:keys [handle surface]}]
  (and (some? handle)
       (set? surface) (seq surface)
       (every? #{:reader :writer} surface)
       (or (not (contains? surface :reader)) (stream/reader? handle))
       (or (not (contains? surface :writer)) (stream/writer? handle))))
```

A table whose entry declares a surface its handle lacks is refused
`::invalid-table` with `:detail {:identity id :surface S}`. A declared
surface narrower than the handle's natures is correct and common: the REPL
enters a ring that is both reader and writer as `#{:writer}` so that no
remote party can read other callers' requests, and the answers ring as
`#{:reader}` so that no remote party can forge an answer. `#{:reader
:writer}` is permitted (a shared blackboard would use it) and is not what
the REPL uses.

Nothing else changes on the serving side: `accept-step!` already runs the
mirror per session over the same table, and `apply-request` already runs
`append!` through the entry's middleware chain.

**Doc amendment.** `dao.stream.remote.md` 3.1, after the paragraph that
introduces `dao.stream.remote-channel`: one sentence stating that the table
is validated at `serve` against the handles' natures and may declare any
non-empty subset of `#{:reader :writer}` per entry.

### 2.2 Dialing by identities (D2)

**Today.** `dial` takes `:name`, answers `:status :resolving`, and
`dial-step` resolves the name then attaches the one descriptor answered,
giving one `:handle`. The REPL needs two reflections, by identity, both at
once, and it needs them before any step so that `open` can return an RPC
client (`yin.repl.driver/open-connection` installs the adapter from the
`open` result synchronously).

**Specified.** `dial` accepts exactly one of `:name` or `:identities`.

- `:identities` is a non-empty vector of distinct identities. Both keys
  present, neither present, or an empty/duplicate vector is the refusal
  `{:status :refused :reason ::invalid-target}`.
- With `:identities`, `dial` also takes `:now`, the driver's clock reading
  at the moment of dialing. `dial` composes as today, calls
  `(ws-project/dial-step! dial now)` once on the still-unattached dial when
  `:now` is given (this records `now` on the dial so that `establish!`
  steps the new link at it before any probe exists), and then, still
  inside `dial`, calls `ws-project/dial-attach!` with the remote descriptor
  of the first identity (this establishes the channel) and
  `ws-project/dial-reflect!` for each further identity, in order. The
  remote descriptor is formatted here: `{:dao.stream/type :dao.stream/remote
  :dao.stream/identity id :dao.stream/channel (descriptor-of spec)}`.
  (`:dao.stream/remote` is a `dao.stream` type, not a transport key; it is
  allowed here and must not appear above.) `:since` is set to `:now` when
  given, else at the first step as today.
- Every attach `ok` answers `{:status :attached :handles {id handle ...}
  :handle nil :identity nil :outcome nil :since nil ...}`. This is the
  contract's deferred confirmation (2.4): the probes are sent now, and a
  reflection whose identity the table lacks turns `gone` when its probe is
  answered `not-found`.
- An attach that is not `ok` answers `{:status :lost :outcome r :handles
  {...partial...}}` with the attacher's own outcome (`:dao.stream/transport-error`
  for a local reachability failure, `:dao.stream/invalid-descriptor`), the
  partial handles closed. A `:lost` dial is never stepped, as today.
- For a `:name` dial nothing changes, except that on attach `:handles`
  is also populated with the one entry `{identity handle}`.

Accessors:

```clojure
(defn handle
  ([d] (:handle d))                 ; the named dial's one reflection; nil for an identities dial
  ([d identity] (get (:handles d) identity)))

(defn handles [d] (:handles d))
```

`:handle nil` for an identities dial is deliberate: with a writer among the
reflections there is no "the" handle, and a caller that forgets to say
which one it means gets nil, not the wrong direction.

`dial-step` for an `:attached` identities dial is exactly today's attached
branch: `ws-project/dial-step!` at `now` (projection, link expiry, this
end's empty mirror), then `channel-lost?` to `:lost` with `channel-gone`.
`resolve-expired?` applies to `:resolving` only; an identities dial is never
resolving. Liveness for the connect half comes from the link, and this is
why `:now` is taken at `dial`. Verified against `remote.cljc`: a probe the
ws writer refuses with `full` while the connection is establishing is kept
on the link with `(new-deadline link)`, which is nil unless the link has
already been stepped at a `now`; a kept probe's deadline is carried into
its later accepted send and is never re-stamped (`send-request!`), so a
probe kept before the link's first `now` never expires. With `:now`
recorded first, `establish!` steps the fresh link at it, the probes are
kept with deadline `now + give-up-after`, and a connection that never opens
is lost by `link-step!` at that deadline: the dial closes its ws handle, the
projection closes the ring, and every reflection answers `channel-gone`.
Without `:now` nothing expires, which is `ws-project`'s own rule for a
clock-less dial and is acceptable only for tests. A refused connection
(the host answers at once) needs no deadline: its `:ws/transport-error`
resolution closes the ring on the next step.

`close!` closes every handle in `:handles` that is closable, then the
connection, as today for the one handle.

### 2.3 The writable-append ambiguity (D3)

The ambiguity is this. A REPL client appends an eval request through its
requests reflection. Four distinct facts hide behind that one call's
outcome and a fifth behind its silence:

1. `append!` answered `:dao.stream/ok`: the channel writer accepted the
   value for the wire. Nothing says the peer received it, appended it, or
   evaluated it.
2. `append!` answered `:dao.stream/full`: not sent. Three causes share this
   word: the attachment is still establishing (ws.md Operations), the
   outbound buffer is at its high-water mark, or the link is at
   `max-outstanding` (2.4). All three mean "retry the identical value
   later"; none means loss.
3. `append!` answered `:dao.stream/closed`: the connection is down; nothing
   crossed.
4. `append!` answered `:dao.stream/transport-error` with a reason:
   `not-found` (the reflection is gone) or `no-surface` (the entry does not
   take writes).
5. The value crossed and the source append ran, but the mirror's answer to
   the append was refused `full` by the session's channel writer. Per 2.3
   that answer is dropped, not rewound, "the asker's append stays unknown".
   The link still holds the append outstanding with a deadline.

**Resolution, as contract.** The REPL never consults the append's own wire
answer. It composes no event writer. Evaluation is confirmed by one thing
only: the correlated answer on the answers reflection (`dao.stream.rpc`
`poll!` matching `:dao.stream.apply/id`). Case 1 is therefore "in flight",
case 2 is `rpc`'s `:unsent` retained envelope retried by the driver
(`retry-unsent`, unchanged), cases 3 and 4 are `rpc`'s
`request-undeliverable` completion (unchanged), and case 5 is covered by
liveness: the outstanding append reaches its `give-up-after` deadline, the
link loses the channel as `channel-gone`, the dial is `:lost`, `poll!`
translates the next read to `:dao.stream.rpc/detached`, `lose-outstanding`
completes the eval as lost, and the driver offers reattachment. That loss
may be false (the eval may have run); the owner's model already accepts
this ("under such a flood the loss may be false, which costs a
reattachment and invalidates nothing", 2.4), and the REPL's shared shell
means a re-sent `(def x 1)` is idempotent while a re-sent `(swap! ...)` is
the operator's to judge, as it is at any REPL after a dropped connection.

**What is written down.** The `remote-channel` namespace docstring gains a
paragraph "Writing through a reflection" stating the five cases and the
rule. `dao.stream.remote.md` 3.1 gains the same paragraph in two
sentences after the `give-up-after` sentence. `yin.repl.connect`'s
docstring points to it. No code implements the resolution because the
code already behaves this way; the slice's obligation is the test in 4.2
that pins each case.

### 2.4 Ended-media stop grace (D4)

**Today, in `serve.cljc`.** `stop!` closes the shared requests and
answers media first. A connected client's outstanding `next` on answers
is then answered with the medium's own `:dao.stream/end` by the next
mirror pass, which `rpc` translates to `:dao.stream.rpc/ended`: permanent,
not reattachable. Only after `stop-grace-ms` (500 ms of the driver's
clock) does `finish-stop*` close each session's socket. Closing earlier
would race that answer: a socket that closes first deposits `:ws/closed`,
the projection closes the ring, the link reports `channel-gone`, and the
client reads a reattachable detach for a stream that is gone.

**Today, in `remote-channel`.** The first stopping tick runs one answering
pass and then closes every session at once (`begin-stop`). The
`:stop-grace-ms` bound (2000) is the wait for the host's `:stopped`
confirmation, a different thing. The board needs no drain: its stop is
observed as a lost source and redialed by design (head.md 5.1).

**Specified.** Two additions and one amendment.

(a) A new bound `:drain-grace-ms`, in `production-bounds` with value `0`,
validated as a non-negative integer (a composition error otherwise). The
REPL passes `500`. The board passes nothing.

(b) `stop!` gains a 2-arity `(stop! server {:ended? bool})`; the 1-arity
is `(stop! server {})`. `:ended?` is recorded on the stop. It means: the
consumer has closed, or will have closed before the next tick, the table
handles whose end the clients should observe. `remote-channel` never
closes a table handle; which handles end is the consumer's decision.

(c) The stopping state machine. `:stop` becomes
`{:since now-of-first-stopping-tick :released now-of-release-or-nil
:outcome ... :ended? bool}`.

| Tick | Condition | Action |
|------|-----------|--------|
| first stopping tick | always | `accept-step!` (last bounded answering pass, offers rejected since `stop!`); `ws/endpoint-stop!` (connections pending acknowledgement hold no reflection and close now); `:since now`. |
| | `drain-grace-ms` = 0, or no sessions | release (below), `:released now`. Identical to S3a `begin-stop`. |
| | otherwise | enter draining; return. |
| draining tick | always | drain-lifecycle; `accept-step!` (answering pass); `ws/endpoint-stop!` (stragglers). |
| | sessions empty, or `now - since >= drain-grace-ms` | release, `:released now`. |
| released, later ticks | as S3a `continue-stop` | `accept-step!` (reap), `endpoint-stop!`, drain-lifecycle; `:confirmed` on the host's `:stopped`, `::unconfirmed` on a gap or when `now - released >= stop-grace-ms`. |

"Release" is S3a's `release!` then `unbind!`, with one change: when
`:ended?` is true, each session's handle is first closed with
`ws/close-ended!` (code 4000, `dao.stream.ws.md` Ending a served stream),
then `ws-project/close-sessions!` runs as today (the second close is the
idempotent no-op `ws.cljc` already guarantees). The client's adapter
deposits `:ws/ended` rather than `:ws/closed`. In S3b nothing above the
projection reads that distinction (the projection collapses both to a
closed ring); it is made honest now because it costs one line and because
S4's cause-recording (section 6) will read it.

The host's `:stopped` fact while draining (the host stopped under us)
completes the stop as `::host-stopped` after release, as it does while
serving; the `observe` branch `(and (= :stopping status) (= :stopped kind))`
answers `:confirmed` only when `:released` is set.

**Why real time, not ticks, and why not shorter.** The drain exists for a
reader whose poll cadence and round trip are not this driver's. The REPL
client's idle backoff ceiling is 200 ms (`yin.repl.main/default-cadence`),
so 500 ms covers two polls plus a loopback round trip. A deployment with a
slower client raises `:drain-grace-ms` in its profile; nothing else moves.

**Why no sessions means no wait.** With nobody attached there is no
`next` to answer; `serve_test`'s existing "stopping an endpoint that never
bound claims nothing" and the embed tests depend on a prompt stop.

**S3a test amendments.** `remote_channel_test` asserts `(= {:since 10
:outcome :confirmed} (:stop ...))` in `stop-is-explicit-and-driver-paced`
and `(= {:since 1000 :outcome ::rc/unconfirmed} (:stop s))` in
`stop-completes-without-a-close-callback`. Both become assertions on
`(select-keys (:stop s) [:since :outcome])`, and each adds `(is (= since
(:released ...)))` since with the default drain the two coincide.

**Doc amendment.** `dao.stream.remote.md` 3.0, "Explicit stop" bullet: one
sentence after "Completion is the host's `stopped` fact...": "A serving
composition may hold its sessions open and answering for a composed drain
grace after the first stopping tick, so that readers of a medium the
consumer ended observe `end` before their connection closes; the board
composes none, the REPL composes 500 ms."

### 2.5 `detach!` versus `close!` (D5)

**Today.** `connect/close!` closes only the ws channel handle through
`ws-project/channel`. The reflections stay open on purpose: the host's
close completion deposits `:ws/closed`, the projection closes the ring on
the next step, the link observes `end`, and the client's next `poll!` reads
`channel-gone`, which `rpc` translates to `:dao.stream.rpc/detached`, the
one reattachable terminal. `remote-channel/close!` instead closes the
reflection too and marks the dial `:closed`, after which `dial-step` is
identity: the projection would never run again and the loss would never be
observed. Using it for the REPL would turn every operator `(disconnect)`
into either `:ended` (if the reflection's `next` answered its filed
outcomes then `end`) or silence. Both are wrong.

**Specified.**

```clojure
(defn detach!
  "Close the dialed connection and nothing else: every reflection stays
   open so that the loss reaches it as channel-gone on its next
   operation, and the dial stays steppable until `dial-step` observes
   the projection closed and answers `:lost`.  Idempotent; identity on a
   dial that has no connection or is already lost or closed."
  [d])
```

Marks `:detaching? true` on the dial for observability. `close!` is
unchanged (connection and every handle, `:closed`, never stepped again) and
remains the board's exit operation and the REPL's cleanup of an old dial
before a reattach.

### 2.6 `:bind-host` in the endpoint spec (D6)

`main.cljc` binds `0.0.0.0` and advertises the LAN address; `embed.cljc`
does the same. `descriptor-of` must name the advertised host and the
listener must bind the wildcard. The spec gains optional `:bind-host`;
`serve` passes `(or (:bind-host spec) (:host spec))` as the `:bind-host`
of the `:bind!` request. `descriptor-of` ignores `:bind-host`. The REPL's
rule that a wildcard bind needs an explicit advertised host stays in
`serve.cljc` as a pre-check (it is REPL policy, and the test
`a-wildcard-bind-needs-an-explicit-advertised-host` asserts its text).

### 2.7 Observability accessors (D7)

- The server value gains `:diagnostic-count`, incremented by `diagnose` on
  every kept fact (the vector itself stays bounded at 8). A consumer that
  publishes diagnostics compares the count it last saw and takes the
  tail. Without it a consumer cannot tell a new `:upgrade-failed` from the
  one it already printed.
- `(attachment d)` answers the dialed attachment id (from
  `ws-project/channel`), nil before a connection exists and after `close!`.
  `connect/summary` reports it, and `slice_peer` uses it to prove that a
  reattachment is a fresh attachment.
- `:refused` servers produced by `observe` on `:bind-failed` carry
  `:detail value` already; the synchronous `::bind-failed` from `serve`
  gains `:detail nil` for shape uniformity.

### 2.8 What is explicitly not added

- No events writer in `connect` (2.3).
- No reading of `:ws/ended` versus `:ws/closed` above the projection
  (deferred to S4, section 6).
- No change to `ws-project`, `ws`, `remote`, `rpc`.
- No name map for the REPL.
- No per-session request media: S3a already gives each session its own
  traffic medium and ring (`media-maker`), which is what `serve.cljc`'s
  `make-media` did; the REPL's shared requests/answers pair is the table's,
  shared by design (section 5 of the remote design).

---

## 3. Migration plans

### 3.1 `yin.repl.serve`

**Shape of the endpoint value after S3b.**

```clojure
{:status      :new | :starting | :running | :stopping | :stopped | :failed
 :spec        {:host advertised :port advertised :path p :bind-host bind}
 :path        p
 :server      <remote-channel server value, or nil when never composed>
 :server-seen {:status s :diagnostic-count n :sessions #{...}}   ; last observed, for notices
 :requests    h  :answers h  :requests-cursor c
 :pending-answer v :pending-successor c
 :repl        state
 :bind-note   text-or-nil
 :outbox      [...]
 :step-moved? bool}
```

Gone: `:control`, `:control-cursor`, `:lifecycle`, `:lifecycle-cursor`,
`:lifecycle-ledger`, `:slots`, `:descriptor`, `:bind-host`, `:bind-port`,
`:resolution`, `:resources`, `:ws-endpoint`, `:acceptor`, `:deposit!`,
`:stop-initiated?`, `:stop-requested-at`, `:host`.

**`serve!`.** Same argument map. Computes `path` via `connect/repl-target`,
`advertised-host`/`advertised-port` as today, then the REPL pre-checks in
the same order with the same codes and texts (`advertised-host-required`,
`ephemeral-port-unsupported`, `no-websocket-package` via
`host-common/binder?`). The `invalid-descriptor` pre-check (which called
`ws/descriptor?`) is replaced by `remote-channel/serve`'s own `::no-port`
refusal, rendered with the same "cannot serve ... on port ..." text. On
success:

```clojure
(remote-channel/serve
  {:spec {:host advertised-host :port advertised-port :path path :bind-host bind-host}
   :host host
   :table {connect/requests-identity {:handle requests :surface #{:writer}}
           connect/answers-identity  {:handle answers  :surface #{:reader}}}
   :bounds repl-bounds})
```

with

```clojure
(def repl-bounds
  "The REPL's overrides of dao.stream.remote-channel/production-bounds:
   a 500 ms drain so a connected client reads the ended answers medium
   before its connection closes (two polls at the shell's 200 ms backoff
   ceiling plus a round trip)."
  {:drain-grace-ms 500})
```

A `:refused` answer becomes `:status :failed` with a notice built from
`:reason` (`::no-transport` renders the host-common missing message,
`::no-port` the "cannot serve" text, `::bind-failed` "endpoint bind
failed: ..." with `:detail`, `::invalid-table` is a programming error and
renders as such). A `:starting` answer gives `:status :starting`.

The inert path (`inert`) no longer deposits onto a lifecycle medium (there
is none above the boundary); it sets `:status :failed` and publishes the
notice directly into the outbox, so the first `step` prints it exactly as
the tests expect (`texts` after one step).

**`step`.**

```
1. nil endpoint -> nil.
2. no :server (never composed) -> endpoint unchanged (its notice is already in the outbox).
3. server' = (remote-channel/serve-step server now)
4. notices from the transition server-seen -> server':
     :starting -> :serving          "Serving <url>"             status :running
     :starting -> :refused           ";; endpoint bind failed: <detail>"   status :failed
     any -> :stopped                 "Endpoint stopped: <outcome>"         status :stopped
     diagnostic-count grew           one ";; upgrade refused: ..." or ";; listener error: ..." per new fact
                                     (the fact's :kind selects the text; keep both texts)
     session departed                ";; attachment <id> left"
     lifecycle-gaps grew             ";; endpoint lifecycle gap"  (replaces today's "lifecycle lost ... stopping":
                                     the composition counts a serving gap and continues, per 3.0)
5. if status is :running: advance-requests (unchanged interpreter), else skip.
   (Media are closed by stop!, and a closed ring's next answers end every tick;
    advancing while stopping would publish ";; requests end" on every tick.)
6. :step-moved? as today (pending answer, cursor moved, outbox grew).
```

The status map is a pure function:

```clojure
(defn- repl-status [channel-status]
  (case channel-status
    :starting :starting  :serving :running  :stopping :stopping
    :stopped :stopped    :refused :failed))
```

**`stop!`.** Unchanged in meaning. If `:server` is nil or status is
`:stopping`/`:stopped`/`:failed`, identity. Otherwise close answers then
requests (as today; the order matters for a client whose `next` is on
answers), then `(update endpoint :server remote-channel/stop! {:ended? true})`,
status `:stopping`. No I/O beyond the two closes.

**`stopped?`.** `(or (nil? endpoint) (contains? #{:stopped :failed} status) (nil? (:server endpoint)))`.
A `:refused` channel server observed after bind (`::bind-failed` by
lifecycle) has already run `release!`; nothing is owed, so `:failed` is
final, exactly as today's "stopping after bind threw never calls unbind or
waits".

**`url`.** From `:spec`: `(str connect/url-prefix connect/ws-scheme host ":" port path)`.
No `:ws/*` key is read.

**`summary`.** `:status`, `:url`, `:path`, `:serving?` = status in
`#{:starting :running :stopping}`, `:sessions` = sorted keys of
`(remote-channel/sessions server)`, `:lifecycle` becomes `{:gaps n
:diagnostics n}` from the server value. The `:identity` key is dropped
(the channel descriptor's identity is below the boundary; the two stream
identities are constants).

**`moved?`, `take-outbox`, `evaluate`, `deliver-answer`, `advance-requests`,
the codes, the capacities.** Unchanged.

**Removed definitions.** `control-capacity`, `lifecycle-capacity`,
`default-slot-count`, `lifecycle-budget`, `event-key`, `value-key`,
`event-kinds`, `portable-admission`, `handoff-admission`, `make-slots`,
`endpoint-slots`, `serving-slots`, `make-media`, `deposit-fn`,
`lifecycle-transition`, `drain-lifecycle`, `stop-grace-ms`, `finish-stop*`,
`finish-stop`. `request-capacity`, `request-budget`, `request-admission`
stay (they size the two shared media).

**Requires after S3b.** `dao.data`, `dao.stream`, `dao.stream.ringbuffer`,
`dao.stream.rpc`, `dao.stream.remote-channel`, `yin.repl`,
`yin.repl.connect`, `yin.repl.host.common`.

### 3.2 `yin.repl.connect`

**`parse-url`.** Same grammar, same refusals, same texts. The success value
is `{outcome-key :yin.repl.connect/parsed spec-key {:host h :port p :path
(repl-target raw-path)}}`. `descriptor-key` is replaced by `spec-key`
(`:yin.repl.connect/spec`). The `ws/descriptor?` gate and its "does not
name a servable stream" refusal go: `remote-channel/dial` owns transport
validity. `service-identity` is deleted; `connect/parse-url`'s `:identity`
option goes with it (no caller passes it: checked `main`, `driver`,
`slice_peer`, tests).

**Connection value.**

```clojure
{:url url  :spec spec  :host host
 :dial <remote-channel dial value>
 :status :established | :closing | :detached | :ended | :not-found | :transport-error
 :detached-by nil | :operator | ...}
```

**`open`.** Takes `{:url :host :now}`; `:now` is the driver's clock
reading and is passed through:

```clojure
(remote-channel/dial {:spec spec :host host :now now
                      :identities [requests-identity answers-identity]})
```

`:status :refused` (`::no-transport`) renders the host-common message as
today's `no-host-adapter` refusal (keep the `host-common/adapter?`
pre-check so the text is identical). `:status :lost` renders
`attach-failed` with `attach-outcome-message` over `(:outcome d)` (same
three texts). `:status :attached` answers `:yin.repl.connect/attached` with
the connection `:established` and the client
`(rpc/client-state (remote-channel/handle d requests-identity)
(remote-channel/handle d answers-identity) stream/anchor-newest)`. The
`fresh-dial`/`attacher`/`attach-pair`/`remote-descriptor`/`mint` helpers and
`traffic-capacity`/`traffic-admission` are deleted; the dial's media are
the composition's (`production-bounds` `:capacity`, 64 per direction,
sized in `remote-channel`'s docstring cross-checks).

**`step!`.** Becomes `(step! connection now)`, returning the connection
with `:dial (remote-channel/dial-step dial now)`. The dial is a value now;
a caller that drops the return value stops stepping. The 1-arity is
removed, so the compiler finds every caller. `driver/poll-remote` is the
one production caller: it becomes `(cond-> state (:connection state)
(update :connection connect/step! now))`, with `now` threaded from
`repl-step` (it already receives it). `yin.repl.serve-connect-wire-test`,
`slice_peer` and `main_test` call sites pass their clock reading. `nil`
`now` is permitted and means nothing expires (ws-project's own rule);
production callers pass a real reading.

**`close!`.** `(update connection :dial remote-channel/detach!)`, then
`:detached-by` and the `:closing` rule exactly as today.

**`reattach`.** Becomes `(reattach connection client now)`; otherwise
unchanged in contract (`reattachable?` gate, same refusals, `rpc/rebind`
with the two new handles, `:established`, `:detached-by nil`). It first
runs `remote-channel/close!` on the old dial
(its reflections already observed the loss; closing them releases link
state and emits nothing because no event writer is composed), then composes
a fresh identities dial exactly as `open` does.

**`observe-terminal`, `terminal-transition`, `terminal-statuses`,
`reattachable?`, `operator-detached?`.** Unchanged. The RPC client's
`:terminal` stays the single source of terminal truth. The dial's `:lost`
is the same fact seen one layer down and is not consulted for status; it
is reported by `summary` as `:dial-status` for diagnosis. The not-found
text reads the path from `(:path (:spec connection))`.

**`summary`.** `{:connected? :url :path (:path spec) :status
:dial-status (:status dial) :attachment (remote-channel/attachment dial)}`.

**Requires after S3b.** `clojure.string`, `dao.data`, `dao.stream`,
`dao.stream.rpc`, `dao.stream.remote-channel`, `yin.repl.host.common`.

### 3.3 Consumers that must change (minimal)

| File | Change |
|------|--------|
| `src/cljc/yin/repl/driver.cljc` | `poll-remote`: keep the stepped connection, pass `now`. Thread `now` into `poll-remote` from `repl-step` (one parameter). `open-connection` and `reattach` pass `(:last-tick state)` as `:now` (`repl-step` already records it at line 579). Nothing else. |
| `src/cljc/yin/repl/main.cljc` | None required. `boot-server` already passes `bind-host`/`advertised-host`. The docstring words `:ws/ended`/`:ws/closed` at lines 1052–1053 may be reworded to "the ended answer, not a bare close" since they predate the boundary (optional, one line). |
| `src/cljc/yin/repl/embed.cljc` | None. `status-text` keeps working because `serve` keeps its status vocabulary. |
| `test/yin/repl/slice_peer.cljc` | Drop the `ws-project` require. `channel-handle`/`:handle-outcome` probe becomes an append through the RPC client's `:writer` (a reflection whose channel is down answers `closed`, 2.4 Channel loss). `record-first-traffic!` compares `(remote-channel/attachment (:dial connection))` via `connect/summary`'s `:attachment`. Pass `now` to `connect/step!`. |
| `test/yin/repl/main_test.cljc` | Replace the two captured-socket connections (`ws/accept-connection! (:ws-endpoint server) ...`, lines ~430 and ~1178) with a `dao.stream.loopback-net` host for both ends and `connect/open` as the client; assert adoption through `(:sessions (serve/summary server))`. Drop the `ws` and `ws-project` requires. The process-level facts (3, 4, cross-host) change only in passing `now` to `connect/step!`. |
| `test/yin/repl/embed_test.cljc` | Same replacement for `a-host-primitive-is-served-and-survives-reset`; drop the `ws` require. |
| `test/yin/repl/serve_connect_wire_test.clj` | Pass `(System/currentTimeMillis)` to `connect/step!`; store the stepped connection. |

---

## 4. Acceptance criteria and test obligations

### 4.1 `test/dao/stream/remote_channel_test.cljc` (additions)

Use the existing `loopback-net` world. New deftests:

1. `a-writer-entry-is-validated-against-its-handle`: a table entry
   `#{:writer}` over a read-only handle (a `dao.stream` handle that is not
   `writer?`; the simplest is a reflection or a closed-nature test handle,
   else construct via `reify` of the reader protocol only) is
   `::invalid-table`; `#{:reader :writer}` over a ring is accepted; an
   empty or non-set surface is refused.
2. `an-identities-dial-attaches-both-at-once`: serve `{req #{:writer} ans
   #{:reader}}`; `(rc/dial {... :identities [req ans] :now 0})` is `:attached`
   before any step, `(rc/handle d)` is nil, `(rc/handle d req)` is a
   writer, `(rc/handle d ans)` a reader. Appending through the writer
   reflection, after ticks, lands the value on the served ring; appending a
   value on the served answers ring is read through the reader reflection.
3. `an-identities-dial-for-an-absent-identity-is-gone-not-found`: table
   lacks `ans`; after ticks the reader reflection's `cursor` answers
   `transport-error` with reason `not-found`; the writer still works.
4. `dial-target-refusals`: both keys, neither, empty vector, duplicate
   identities are `::invalid-target`.
5. `detach-leaves-the-reflections-to-observe-channel-gone`: attached
   identities dial; `rc/detach!`; keep ticking; the dial becomes `:lost`
   with `channel-gone`; the reader reflection's `next` answers
   `transport-error` reason `channel-gone`; the writer's `append!` answers
   `closed`. `rc/detach!` twice is identity; `rc/close!` after is `:closed`.
6. `an-ended-stop-drains-before-it-closes`: `:drain-grace-ms 500`, one
   attached reader with an outstanding `next` on the served ring; the
   consumer closes the ring, `(rc/stop! server {:ended? true})`; tick at
   `t0`: status `:stopping`, `:stop {:since t0 :released nil}`, the
   reader's connection is still open (`append!` through the ws handle ok,
   as `stop-is-explicit-and-driver-paced` checks); tick `t0+1`: the
   reader's `next` answers `:dao.stream/end` (the medium's own, not
   channel-gone); tick `t0+499`: still open; tick `t0+500`: released,
   `:released t0+500`, every session closed, `unbind!` called once;
   confirmation then completes as in S3a. Also assert that the closed
   connection's recorded close code is 4000 if `loopback-net`'s
   `close-conn!` records it (read `test/dao/stream/loopback_net.cljc`; if
   it does not, add the code to the conn map, a two-line fixture change).
7. `a-drain-ends-early-when-the-last-session-leaves`: same setup; the
   reader detaches at `t0+10`; the tick at `t0+11` reaps it and releases
   at once; `:released t0+11`.
8. `no-sessions-means-no-drain`: `:drain-grace-ms 500` with no sessions
   releases on the first stopping tick (`:released = :since`).
9. `the-host-stopping-under-a-drain-is-host-stopped`: during draining, the
   host deposits `:stopped`; the server is `:stopped` with
   `::host-stopped`, sessions closed.
10. `bind-host-binds-while-host-is-advertised`: spec `{:host "10.0.0.5"
    :port 9 :path "/x" :bind-host "0.0.0.0"}`; `descriptor-of` names
    `10.0.0.5`; the `:bind!` request's `:bind-host` is `"0.0.0.0"`.
11. `diagnostic-count-is-monotonic`: deposit 10 `:upgrade-failed` facts;
    `:diagnostics` has 8, `:diagnostic-count` is 10.

Amend the two `:stop` equality assertions as 2.4 states.

### 4.2 The writable-append contract pin (`remote_channel_test`)

`writing-through-a-reflection-answers-acceptance-only`, one deftest with
`testing` blocks for the five cases of 2.3:

- establishing: append before the net is pumped answers `full`; the same
  value appended after pumping answers `ok` and later appears on the ring.
- `ok` is not evaluation: the value is on the served ring only after the
  server's tick, not when `append!` answered.
- `closed` after `detach!`.
- `no-surface`: append through a reflection of a `#{:reader}` entry answers
  `transport-error` reason `no-surface` once the probe is answered.
- dropped answer expires the channel: blackhole the server-to-client side
  (`net/blackhole! lnet :client`) after the append is accepted; tick past
  `give-up-after`; the dial is `:lost` channel-gone and the served ring
  holds the value. This is the honest statement that the loss is false
  and the value crossed.

### 4.3 `test/yin/repl/serve_test.cljc` (migrated)

Keep every existing deftest name and assertion that does not touch
internals; migrate the fixture:

- `host` fixture becomes `dao.stream.loopback-net` (`listen-on`,
  `unbind-on`, `connect-on`) so a real client can connect; keep a
  "counting" variant for the bind/unbind assertions.
- `connect!` composes `connect/open` over `(net/connect-on lnet)`, pumps,
  steps the server twice, and answers `[endpoint connection client]`.
- `stop-closes-every-accepted-sessions-socket-handle` becomes
  `stop-ends-the-served-media-before-it-closes-the-session`: a connected
  client that polls (`rpc/poll!`) each tick observes `:dao.stream.rpc/ended`
  during the drain, never `:detached`; the session closes at `+500`; the
  host is released once; "Endpoint stopped" is published; the final
  status is `:stopped` and `stopped?` is true.
- `the-summary-is-plain-data` asserts the new `:lifecycle` shape and that
  `:sessions` lists the one connected attachment after a connect.
- Add `a-serving-gap-is-counted-and-serving-continues`: deposit a
  lifecycle burst past the ring's capacity through the fixture's
  `deposit!`; the endpoint publishes one gap notice and stays `:running`.
- Add `attachment-departure-is-noticed`: client `close!`, ticks, the
  outbox carries ";; attachment <id> left".
- The six interpreter tests (`a-request-evaluates...` through
  `an-uncorrelatable-value...`) are unchanged: they append to
  `(:requests endpoint)` directly, which stays a public key.
- Invariant assertion in this file: `(is (nil? (:ws-endpoint endpoint)))`
  and `(is (nil? (:acceptor endpoint)))` after `serve!`, so a regression
  that leaks internals is caught here as well as by the grep gate.

### 4.4 `test/yin/repl/connect_test.cljc` (migrated)

- URL tests read `(:path (connect/spec-key ...))` and `(:host ...)`,
  `(:port ...)`; the identity assertion is removed.
- `open-attaches-both-reflections-and-reports-established-at-once`:
  over the captured-socket fake host as today (the fake `:connect!` still
  satisfies `make-attacher`); assert `:established`, `(:dial connection)`
  is `:attached`, the client's `:writer` is a writer and `:reader` a
  reader, cursor is `:dao.stream/newest`.
- `close-ends-the-dialed-channel-and-records-who-asked`: additionally
  assert the dial is not `:closed` (it is `:attached` with `:detaching?`)
  and that one `close!` reached the fake socket.
- Add, over `loopback-net` with a `remote-channel/serve` of the two-entry
  table: `a-detached-connection-reattaches-with-a-fresh-attachment-and-the-same-cursor`
  (open, warm up until the cursor is minted, `close!`, tick until the RPC
  client's `:terminal` is `:detached`, `reattach`, assert
  `:yin.repl.connect/reattached`, a different `:attachment`, the same
  `:cursor` value on the client, and a round trip succeeds).
- Add `an-absent-answers-entry-is-not-found`: serve only `requests`;
  open; poll until `:terminal` is `:dao.stream.rpc/not-found`;
  `observe-terminal` yields `:not-found` with the "not retried" text.
- Add `a-connection-that-never-opens-is-detached-at-give-up-after`: a
  listener whose `accept!` answers `{}` and never acknowledges (the S3a
  fixture in `a-connection-that-never-opens-is-lost-at-give-up-after`);
  `open` with `:now 1000` succeeds (`:established`, the deferred
  confirmation); the RPC client's `request!` answers `cursor-pending` and
  `poll!` stays idle; `step!` at `1149` leaves the dial `:attached`; at
  `1150` the dial is `:lost` channel-gone and the next `poll!` makes the
  terminal `:detached`. Also, in a `testing` block, a refused connection
  (no listener at all) is `:detached` within two ticks, no deadline
  involved. This pins the S3a semantics for the REPL and is the case S4's
  cause-recording will refine to `:transport-error` (section 6).
- Add `open-without-now-never-expires`: the same never-opening listener,
  `open` without `:now`, stepped with `now` far past `give-up-after`, stays
  `:attached` and idle. This documents why production callers pass `:now`.
- The existing terminal-mapping tests are unchanged.

### 4.5 The boundary gate

Run from the worktree root; both must print nothing:

```sh
grep -n ":ws/\|dao.stream.ws\|ws-project" src/cljc/yin/repl/serve.cljc src/cljc/yin/repl/connect.cljc
grep -ln ":ws/\|dao.stream.ws\b\|ws-project" test/yin/repl/*.clj* | grep -v "host/"
```

`test/yin/repl/host/jvm_test.clj` and the host adapters under
`src/*/yin/repl/host/` are below the seam and are exempt.

### 4.6 Lanes

Per `docs/agents/build-n-test.md`: iterate with `bb test:clj` or
`clojure -M:test -n <ns>`; land with one full `bb test` (JVM, Node, Dart),
one lane set at a time, run in the foreground. A fresh worktree needs
`mise trust` and `npm ci` first. `clj -M:kondo --lint src test` must be
0/0; `cljstyle check` clean. The Node and Dart lanes are the point of this
slice's portability claim: `remote-channel`, `serve` and `connect` are
`.cljc`, and the loopback-net tests run identically on all three. Confirm
"Testing dao.stream.remote-channel-test", "Testing yin.repl.serve-test"
and "Testing yin.repl.connect-test" appear in the Node output.

Cross-host traps that apply here (from project memory, all verified
patterns):

- `#?(:clj ...)` does not exclude code from the Dart build; use
  `#?(:cljd nil :clj ...)` with `:cljd` first.
- ClojureDart `for` over a seq longer than 32 may hand the body nil; use
  `mapv`/`keep` in portable code (the identities vector is short, but the
  diagnostics tail and session maps are not guaranteed to be).
- `(assoc nil :a 1 :b 2)` on ClojureDart is a one-element list; start from
  `{}` when building `:server-seen` and `:stop`.
- A protocol method with `[_ _]` params fails on ClojureDart; name the
  second.
- ClojureDart EDN reader throws on whitespace before a closer; irrelevant
  to code, relevant to any test fixture that reads EDN.

### 4.7 Definition of done

All of: 4.1 to 4.4 green on JVM, Node and Dart; 4.5 prints nothing; the
existing JVM wire test and the process-level `main_test` facts (including
`^:slow` fact 4, run once with `clojure -M:test -i :slow -n yin.repl.main-test`)
green; the two design-doc amendments (2.1, 2.3, 2.4) in the same commit;
the S3a `head_board_test` and `remote_channel_test` green with only the two
amended assertions.

---

## 5. Instructions for the Implementation Engineer (Claude Opus 5.5)

### 5.1 Before writing code

1. Read `docs/agents/build-n-test.md` and `docs/agents/format.md`. Run
   `mise trust` and `npm ci` in the worktree.
2. Read, in this order: `src/cljc/dao/stream/remote_channel.cljc`,
   `src/cljc/yin/vm/linker/head/board.cljc`, `src/cljc/yin/repl/serve.cljc`,
   `src/cljc/yin/repl/connect.cljc`, `test/dao/stream/remote_channel_test.cljc`,
   `test/dao/stream/loopback_net.cljc`, `src/cljc/dao/stream/ws_project.cljc`
   (`dial`, `dial-attach!`, `dial-reflect!`, `dial-step!`, `channel`,
   `stop!`, `close-sessions!`), `src/cljc/dao/stream/ws.cljc`
   (`close-ended!`, `closed!`), `src/cljc/dao/stream/rpc.cljc`
   (`attempt-unsent`, `terminal-lost`, `rebind`).
3. Run `bb test:clj` once on the untouched tree to know the baseline.

### 5.2 Order of work (each step leaves `bb test:clj` green)

1. **`remote_channel.cljc`, serving side.** `valid-entry?`/`valid-table?`
   (2.1), `:bind-host` (2.6), `:diagnostic-count` (2.7), `:drain-grace-ms`
   in `production-bounds` with validation, the stopping state machine and
   `stop!` 2-arity (2.4), `ws/close-ended!` on an ended release. Amend the
   two S3a assertions. Add tests 1, 6 to 11 of 4.1.
2. **`remote_channel.cljc`, dialing side.** `:identities` (2.2),
   `handle`/`handles`, `detach!` (2.5), `attachment` (2.7). Add tests 2 to
   5 of 4.1 and the 4.2 pin.
3. **Docstrings and docs.** The namespace docstring paragraph (2.3), the
   two sentences in `dao.stream.remote.md` 3.0 and 3.1.
4. **`connect.cljc`** per 3.2, then `connect_test` per 4.4. Update the
   `driver.cljc` call site and the wire test; `bb test:clj`.
5. **`serve.cljc`** per 3.1, then `serve_test` per 4.3; `embed_test`,
   `main_test`, `slice_peer` per 3.3; `bb test:clj`.
6. **Gate.** 4.5 grep; kondo; cljstyle; one full `bb test` in the
   foreground, one lane set at a time; the `^:slow` main_test fact.
7. **Report** to
   `collab/1791398000000-engineer-stream-s3b.claude-opus-5-5.findings.md`
   inside this worktree (a sibling-worktree delegate cannot write the main
   checkout's `collab/`): what changed per file, each acceptance item with
   its evidence (test name, lane, counts), every deviation from this
   specification with its reason, and open questions. Do not stage
   `collab/` (pre-commit hook enforces it).

### 5.3 Rules

- Do not touch `ws.cljc`, `ws_project.cljc`, `remote.cljc`, `rpc.cljc`,
  `apply.cljc`. If a change there seems necessary, stop, write the reason
  in the report under "Blocked", and continue with everything that does
  not depend on it.
- Do not add an event writer to `connect`, a name map to the REPL, or a
  reading of `:ws/ended` above the projection.
- Do not background test lanes. A `claude -p` delegate that backgrounds
  a lane exits with the turn and the lane dies.
- Keep the REPL's notice texts byte-identical where a test asserts them
  (`"Serving daostream:ws://..."`, `"advertised-host-required"`,
  `"ephemeral-port-unsupported"`, `"no-websocket-package"`,
  `"unbind-threw"` is replaced by the channel's `::unbind-failed` word,
  rendered as `"Endpoint stopped without host completion: unbind-failed"`,
  and `a-synchronous-unbind-failure-resolves-stop-locally` is amended to
  that text; `"Endpoint stopped"`, `"malformed request dropped"`,
  `"attachment ... left"`).
- Commit on green with the project's format: `feat(yin.repl): serve and
  connect over dao.stream.remote-channel (cross-machine stream slice S3b)`
  as the summary, a body listing the `remote-channel` additions, and no
  `Co-Authored-By` line (format.md governs; it overrides the harness
  reminder). Do not fast-forward master or push; the orchestrator lands
  after Architect sign-off.

### 5.4 Judgement calls you may make without asking

- Exact helper names and private function boundaries inside the three
  files.
- Whether `:server-seen` is one map or three keys.
- The fixture layout of the migrated `serve_test` (a shared
  `test/yin/repl/net_fixture.cljc` is acceptable if `main_test`,
  `embed_test` and `serve_test` all use it).
- Whether `loopback-net` records the close code (4.1 item 6); if you add
  it, it is a two-line fixture change, noted in the report.

### 5.5 Judgement calls you must not make

- Changing the terminal words or their reattachability.
- Making the dial's `:lost` override the RPC client's `:terminal` in
  `observe-terminal`.
- Shortening or removing the 500 ms drain, or making it a tick count.
- Closing a table handle from inside `remote-channel`.

---

## 6. Deferred, with reasons

- **Terminal cause above the projection.** `ws-project`'s projection could
  record which event closed the ring (`:ws/ended`, `:ws/closed`,
  `:ws/not-found`, `:ws/transport-error`), `remote-channel` could map it to
  a neutral cause (`:ended :dropped :not-served :unreachable :expired`) on
  the lost dial, and `connect` could refine an RPC `:detached` to `:ended`
  when the race of 2.4 is lost, or to `:transport-error` when the
  connection never opened. Parity does not need it (today's code has the
  same race and the same `:detached`-for-unreachable reading), it touches
  `ws_project.cljc`, and S4 (off loopback, where unreachable hosts are
  common) is where the distinction earns its cost. `close-ended!` is wired
  in S3b so that the wire already carries the fact S4 will read.
- **`yin.repl.dht/close!` single-tick exit** (S3a-2 sign-off, D2 note):
  `close!` runs one stopping tick and discards the stepped server. It is a
  process-exit path with no later step, so it is safe; it is not the REPL
  and not S3b. Recorded for the S4 brief.
- **`wss://`.** Unchanged refusal in `parse-url`; the descriptor form is
  the ws design's open item.
- **Ephemeral port (`--port 0`).** Still refused by REPL policy; serving
  it means composing the descriptor after `:bind-succeeded`, a change to
  `remote-channel/serve`'s shape (the descriptor is formatted before bind)
  that S4's "TCP listener at the UDP socket's number" item will need
  anyway.

---

## 7. Risks found while specifying

- **Idle reaping meets a paused client.** `production-bounds`
  `:idle-timeout` is 60 s. A REPL client polls at most every 200 ms, so a
  live client is never reaped; a client whose process is suspended for a
  minute is reaped and, on resume, observes `:detached` and may `(connect)`
  again. Acceptable and now stated in `serve.cljc`'s docstring.
- **Per-session capacity drops from 8192 to 64.** The REPL driver holds at
  most one outstanding request and `rpc`'s `max-outstanding` bounds the
  rest; a client that floods past 64 values between two server ticks
  loses wire values (projection gap) and then its channel at
  `give-up-after`. This is the production profile's stated behaviour and
  the correct one for a public endpoint; the old 8192 was never a
  considered bound.
- **`connect/step!` by value.** The dial is no longer an atom. Any caller
  that discards the stepped connection silently stops the projection and
  the client never reads an answer. The removed 1-arity makes the compiler
  find every caller; the driver change is the only production one.
- **Notice derivation by diff.** `serve` no longer sees lifecycle facts,
  only the channel server's state. Everything the tests assert is
  recoverable from status, `:diagnostic-count`, `:lifecycle-gaps` and the
  session set; the bind detail is on the `:refused` server. If a future
  consumer needs the facts themselves, the right move is an optional
  `:observe` writer handed to `remote-channel/serve`, not a return to a
  lifecycle medium above the boundary.

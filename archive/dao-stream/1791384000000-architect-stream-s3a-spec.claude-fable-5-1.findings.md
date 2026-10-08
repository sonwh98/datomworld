Completed-GMT: 2026-10-07 16:42:00 GMT
Completed-Local: 2026-10-07 23:42:00 Asia/Ho_Chi_Minh
Coding-Agent: claude (claude-fable-5-1)

# Lead System Architect: Slice S3a specification and implementation recipe

Read-only pass on `master` @ `fbad1705` (S2d landed). No code, design or test file was modified. Only this file was written.

Read firsthand: the mob consensus (`archive/1791304310868-...gpt-6-astra.findings.md` D1, D3, D5), the S0/S1 sign-off (`archive/1791308475375-...glm-5.3.findings.md` §3, the S3a input row), the S2 spec (`archive/1791353752491-...claude-fable-5-1.findings.md` §4, §5), `docs/design/dao.stream.remote.md` §2.3–2.5 and §3.0–3.1, `docs/design/dao.stream.ws.md` (Serving, Ending a served stream, Deposit Admission, Deferred), `docs/design/yin.vm.linker.dht.head.md` 5.1, 5.5, 6, 7, 8.1–8.4, 10, 13, `src/cljc/dao/stream/ws_project.cljc` (whole), `src/cljc/dao/stream/ws.cljc` (`make-attacher`, `make-endpoint`, `accept-connection!`, `endpoint-step`, `release-slot!`), `src/cljc/dao/stream/remote.cljc` (`link-step!`, `links`, `refl-next`, `refl-append`, `emit!`, `new-deadline`), `src/cljc/yin/vm/linker/head/ws.cljc` (whole), `src/cljc/yin/vm/linker/head.cljc` (`read-source`, `poll`, `heads`), `src/cljc/yin/repl/dht.cljc` (`close!`, `serve-board`, `step-board`, `drop-dial`, `step-link`, `step`), `src/cljc/yin/repl/host.cljc`, `src/clj/yin/repl/host/jvm.clj`, `src/clj/dao/stream/ws/jvm.clj` (`listen!`, `stop-listening!`), `src/cljs/dao/stream/ws/node.cljs` (`stop-listening!`), `src/cljc/yin/repl/serve.cljc` (`stop!`, `finish-stop*`, `stop-grace-ms`), and the fixtures of `test/yin/vm/linker/head_ws_test.cljc` (the in-process loopback net, `world`, `tick`, the real-socket JVM case).

Scope is exactly D5's S3a line: "neutral board attachment/acceptance, stream-side liveness, explicit stop and lifecycle-gap recovery or terminal failure." S3b (REPL serve/connect/host migration) and S4 (advertisement, loopback lift) are out of scope and are named where S3a leaves a seam for them. Everything here keeps D1/D2/S0: no new registry, no new core stream operation, no public `closed?` on handles, no ambient clock or scheduler; dao.stream remains the sole boundary and yin stays transport-ignorant.

## 0. Design stance in six rules

1. **The yin board composition keeps three things only** (D1): the board's name, its one-entry read-only exposure table, and domain repair (redial with backoff on `:source-lost`). Everything else that `yin.vm.linker.head.ws` does today — endpoint, slots, media, listener lifecycle, dial, resolve, attach, close — moves below dao.stream into one stepped channel composition, `dao.stream.remote-channel`, that `yin.repl` (S3b) will reuse.
2. **The endpoint specification is portable data passed opaquely.** yin hands `{:host h :port p :path "/head"}` down; the host assembly below dao.stream formats the concrete `:ws/...` descriptor. No `:ws/` key is written or read in `yin.*` after S3a.
3. **Liveness is the stream's; yin sees only loss.** The dial composes `give-up-after` and steps its link at the driver's `now`. Expiry is `channel-gone` through the existing loss path; the follower observes a non-retryable `transport-error` and emits `:source-lost`; the shell redials. The follower inspects no transport fact and no `:dao.stream.remote/*` key.
4. **`:answered` claims only what a handle can prove.** A `blocked` from a reflection is local when nothing is filed and relayed when the source's correlated `blocked` was filed; the handle cannot tell them apart and the follower must not pretend to. The follower records `:polled` (it asked) and `:answered` (the source yielded a positioned fact: cursor `ok`, next `ok` or `gap`). Remote acknowledgement of an idle board is the link's deadline bookkeeping and is reported upward only negatively, as loss (D3: "no new head value").
5. **Stop is explicit, driver-paced and bounded.** `stop!` initiates; `serve-step` completes it: no new adoptions, one last bounded answering pass, every session closed, every pending pre-acknowledgement connection closed, the listener released; `:stopped` when the host's close completion is observed or, failing a callback, when a composed grace elapses. Release bookkeeping never depends on a callback (D3).
6. **A lifecycle gap is recoverable while serving and terminal while starting or stopping.** While serving, the only facts the lifecycle medium carries are diagnostics; the recovery cursor is adopted and the gap counted. While starting, a lost `bind-failed` cannot be told from a lost `bind-succeeded`, so the composition refuses and releases. While stopping, a lost `stopped` is taken as stopped, unconfirmed. A channel drop on either side is never a source gap (D2).

## 1. Production bounds profile

One value, `dao.stream.remote-channel/production-bounds`, composition data the yin board composition passes down and tests override. Each key is validated by the layer that already validates it (nothing is re-validated here); the profile only sets non-nil values where S1/S2 left nil defaults.

| key | layer that enforces it | value | where it is applied |
|---|---|---|---|
| `:step-budget` | ws-project `step!` (S1) | 64 | acceptor per session per tick; dial per tick |
| `:mirror-budget` | `remote/mirror-step` (S2a) | 64 | acceptor per session; dial |
| `:chase-budget` | `remote/answer!` (S2a) | 32 | acceptor per session; dial |
| `:max-sessions` | ws-project `adopt!` (S1) | 64 | acceptor |
| `:idle-timeout` | ws-project `reaped` (S1) | 60000 ms | acceptor |
| `:dao.stream.remote/budget` | link (prefetch stamp) | 8 | dial link policy |
| `:dao.stream.remote/resend-after` | link | nil | ordered reliable channel: never resend (2.5) |
| `:dao.stream.remote/drain-budget` | link `drain!` (S2b) | 256 | dial link policy |
| `:dao.stream.remote/max-outstanding` | link (S2b) | 256 | dial link policy |
| `:dao.stream.remote/max-filed` | link (S2b) | 1024 | dial link policy |
| `:dao.stream.remote/give-up-after` | link `:step` (S2c) | 15000 ms | dial link policy |
| `:ws/max-frame-bytes` | `deliver!`, host reassembly (S2d) | 4 MiB (4194304) | `make-endpoint` and `make-attacher` |
| `:ws/max-pending-frames` | `deliver!` pending branch (S2d) | 64 | `make-endpoint` |
| `:ws/max-pending-bytes` | same | 1 MiB (1048576) | `make-endpoint` |
| `:ws/outbound-high-water` | `WsHandle.append!` (S2d) | 4 MiB | both |
| `:ws/max-outbound-bytes` | same | 16 MiB (16777216) | both |
| `:expiry-ms` | `endpoint-step` admission expiry | 15000 ms | `make-endpoint` (today nil in head.ws) |
| `:slot-count` | composition | 8 | handoff slots (head.ws today) |
| `:capacity` | composition | 64 | traffic medium, channel ring, control medium, lifecycle medium (head.ws today) |
| `:stop-grace-ms` | remote-channel `serve-step` (§4) | 2000 ms | stop completion without a host callback |

Sizing cross-checks the implementer records in the docstring:

- `idle-timeout` (60 s) > follower poll (5 s, `head/defaults :poll-ticks` in the shell's ms clock) + `give-up-after` (15 s): a healthy reader advances the session's mirror cursor at every poll and is never reaped; only a reader that stopped asking is.
- `give-up-after` (15 s) ≥ 2 × poll (5 s): one missed answer is loss, never a slow tick. The dial's link is stepped on every shell tick (`tick-millis`), so expiry is observed within one tick of the deadline.
- `step-budget` = `mirror-budget` = `capacity` (64): one tick can drain a full ring; a continuous producer gets exactly one ring's worth per tick and the next session is visited.
- One session's tick is at most `mirror-budget × min(chase-budget, link budget)` = 64 × 8 handle operations on the board profile; the aggregate across 64 sessions is 32 768 operations, which S2 §5 recorded as acceptable until a shared pool is needed. On a capacity-1 board the chase stops at the first `blocked`.
- `max-pending-bytes` (1 MiB) < `max-frame-bytes` (4 MiB): a single oversize pre-acknowledgement frame fails the frame bound first (1009), not the pending bound (1013); both close before acceptance.
- `max-outbound-bytes` (16 MiB) is the cap on what a request-but-never-read peer costs this host; the head trace is under 1 KiB so the board never approaches it, and the REPL (S3b) inherits the same number.

Hosts gated by the S2d table (http-kit server outbound, Dart, browser) stay gated by S4; the profile applies the fallback cumulative quota there, and the lift decision is S4's.

## 2. Neutral board composition

### 2.1 New namespace `dao.stream.remote-channel` (`src/cljc/dao/stream/remote_channel.cljc`)

The generic portion of today's `yin.vm.linker.head.ws`, moved down and generalised: serving a table over a stepped channel, dialing a channel to resolve a name and attach what it answers, explicit stop, lifecycle observation. It composes `dao.stream.ws`, `dao.stream.ws-project` and `dao.stream.remote`; it knows no yin vocabulary.

```clojure
;; portable endpoint specification, opaque to callers above dao.stream
spec ::= {:host "127.0.0.1" :port 9090 :path "/head"}   ; :transport :ws is the only value and the default

;; host assembly: the existing yin.repl.host seam shape, now a dao.stream input
host ::= {:connect! f :bind! g :unbind! h}            ; any of the three may be absent

(descriptor-of spec)            ; -> the concrete ws descriptor of `spec`, formatted here, never above
(loopback-literal? host-string) ; moved from head.ws/loopback?; address data belongs below dao.stream

(serve {:spec spec :host host :table t :names n :bounds b})  -> server
(serve-step server now)  -> server
(stop! server)           -> server          ; initiates, §4
(sessions server)        -> ws-project/sessions of the acceptor, or nil

(dial {:spec spec :host host :name n :bounds b :events w})   -> dial
(dial-step d now)        -> dial
(handle d)               -> the reflection once attached, else nil
(close! d)               -> dial                      ; §4
```

`serve`'s value is a map with `:status` one of `:starting :serving :stopping :stopped :refused`, plus `:spec`, `:descriptor` (the formatted ws descriptor, for the token below the boundary in S4), `:endpoint`, `:acceptor`, `:listener`, `:lifecycle`, `:lifecycle-cursor`, `:lifecycle-gaps` (count), `:stop` (`{:since now :outcome o}` once initiated), `:reason` and detail on refusal. Refusals (data, never throws): `:dao.stream.remote-channel/no-transport` (a `:transport` the host assembly lacks, or no `:bind!`), `:dao.stream.remote-channel/no-port` (port not a positive integer), `:dao.stream.remote-channel/bind-failed` (`bind!` threw, answered non-ok, or deposited `:bind-failed`), `:dao.stream.remote-channel/lifecycle-lost` (§5), `:dao.stream.remote-channel/invalid-table` (any table entry's cursors hold host objects, i.e. `make-acceptor` throws). `serve` validates `bounds` by passing them to `make-endpoint`, `make-acceptor` and `links`; a composition error from any of them is rethrown as the composition error it is, before anything listens.

What `serve` composes, in order, is exactly today's `head.ws/serve` lines 189–243 with the bounds threaded: `make-endpoint` with `:expiry-ms`, the five `:ws/*` bounds and the slot pool; `make-acceptor` with `:max-sessions`, `:idle-timeout`, `:step-budget`, `:mirror-budget`, `:chase-budget`; a lifecycle ring of `:capacity`; the `deposit!` closure; then `bind!`. The table and the name map are the caller's and pass through untouched.

`dial`'s value is `{:status (:resolving | :attached | :lost | :closed | :refused) :spec :descriptor :name :dial :handle :identity :outcome}`. `dial` composes `make-attacher` with the five `:ws/*` bounds and `:connect!`, and `ws-project/dial` with the three step bounds and the seven `:dao.stream.remote/*` policy keys (`:events` from `:events`). Refusal: `:dao.stream.remote-channel/no-transport` when `:connect!` is absent.

`dial-step d now`: `(ws-project/dial-step! (:dial d) now)` first — projection, link step at `now`, mirror. Then:

- `:resolving`: `dial-resolve!` of `:name`; `ok` → `dial-reflect!` of the descriptor answered → `:attached` with `:handle`, `:identity`; `transport-error` with `:dao.stream/retry? true` → stay, keep `:outcome`; anything else → `:lost` with `:outcome`. This is today's `head.ws/dial-step` unchanged except for `now`.
- `:attached`: when the channel's projection is closed (`ws-project/closed?` of `(:project (ws-project/channel dial))`) → `:lost` with `:outcome {:dao.stream/outcome :dao.stream/transport-error :dao.stream.remote/reason :dao.stream.remote/channel-gone}`. The composition that owns the connection reports its loss as data; the follower reaches the same conclusion on its next poll, and the shell may act on whichever comes first.
- `:lost`, `:closed`, `:refused`: answered unchanged.

### 2.2 `yin.vm.linker.head.board` replaces `yin.vm.linker.head.ws` (`src/cljc/yin/vm/linker/head/board.cljc`)

A clean break, per the no-compat rule: the old namespace and its test file are deleted, not shimmed. What remains is the D1 residue:

```clojure
(board-name principal)                                  ; unchanged
(serve {:board b :principal p :spec spec :host host})   ; -> remote-channel server, status as-is
(serve-step server now)                                 ; delegates
(stop! server)                                          ; delegates
(dial {:principal p :spec spec :host host})             ; -> remote-channel dial
(dial-step d now) (handle d) (close! d)                 ; delegate
```

`serve` builds the table `{identity {:handle board :surface #{:reader}}}` and the name map `{(board-name principal) identity}`, passes `remote-channel/production-bounds`, and the spec's `:path` is the constant `"/head"` (the board's path is domain naming, kept here). The loopback gate of 5.1 stays in this namespace until S4 lifts it: `serve` refuses `:yin.head/not-loopback` unless `(remote-channel/loopback-literal? (:host spec))`. Nothing in this file requires `dao.stream.ws` or `dao.stream.ws-project`; `clj-kondo` and a grep for `:ws/` under `src/cljc/yin/` are the proof.

### 2.3 Shell wiring (`yin.repl.dht`), the minimum S3a needs

- `serve-board` calls `head.board/serve` with `:spec {:host host :port port}` and `:host (::ws node)` (the existing `yin.repl.host/websocket` value; moving that namespace is S3b).
- `step-link` calls `(head.board/dial-step (:dial link) now)` and treats `:lost` as today (`drop-dial`, `lost-reason`). The `:yin.head/no-answer` branch (dht.cljc:747–750, a dial still resolving past the repair delay) is deleted with its line: a resolve that is never answered now expires on the link as `channel-gone` and arrives as `:lost`. Its print text moves under `:dao.stream.remote/channel-gone`, "the connection was refused, closed, or stopped answering".
- `close!` (dht.cljc:430–445) calls `head.board/stop!` on the server and `head.board/close!` on each dial instead of reaching for `:unbind!` and `:listener` itself; the shell's exit loop drives `serve-step` until `:stopped` or `stop-grace-ms`, as `yin.repl.main` already does for the REPL endpoint.
- `head/step` and `head/follow` are untouched. The shell's `now` is already the host's ms clock (`yin.repl.dht/step` docstring; `yin.repl.main` passes `System/currentTimeMillis`), so `give-up-after` and `idle-timeout` are in the right domain with no conversion.

## 3. Liveness and `:answered`

### 3.1 Stream side

The chain exists after S2c/S2d and S3a only composes it: `dial` passes `give-up-after`; `dial-step d now` steps the link; an outstanding `next` or named `descriptor` unanswered for 15 s emits `channel-expired` on `:events`, runs `channel-loss!`, and `dial-step!` closes the ws handle; the host deposits `:ws/closed`; the projection closes the ring; the reflection's `next` answers `transport-error` reason `channel-gone`, not retryable; `read-source` reports `:lost`; `poll` emits `:source-lost`; the shell's `drop-dial` closes the dial and schedules the redial with the doubling delay it already has. Idle healthy board: the mirror answers each `next` with the source's correlated `blocked`, the drain files it, the deadline is cleared, nothing is lost. The server side needs no deadline: it holds no reflection, and its idle expiry (S1) is the separate server-side bound D3 names.

Two consequences the implementer must preserve: the follower's poll is the idle probe (`dao.stream.ws.md` Deferred, "a `descriptor` request is the natural probe"; here it is the follower's `next`, which costs nothing extra); and a false loss under flood costs one redial and invalidates no installed head, because `:source-lost` never touches `:installed` (head.cljc `poll` only clears `:reader`, `:cursor`, `:due`).

### 3.2 `yin.vm.linker.head` `:answered`

`read-source` (head.cljc:520–556) today sets `answered? true` on `blocked`, so `heads` reports a reflection's local `blocked` as the source answering. The fix, in `read-source` and `poll` only:

- `answered?` becomes true on cursor `ok`, next `ok` and next `gap` only; `blocked` and the retry case leave it as it was.
- `poll` sets `:polled now` on every poll it runs and `:answered now` only when `answered?` is true.
- `new-principal` gains `:polled nil`; `heads` reports `:polled` beside `:answered`.
- `attach` clears `:polled` and `:answered` with `:cursor` and `:due`: a fresh handle has proven nothing.

Nothing else in the follower changes; it still calls only `cursor` and `next`, still reads no `:dao.stream.remote/*` key, and no transport-health accessor is added (D3). Over a plain local ring `:answered` now advances only when the ring yields something, which is the honest reading of "the source answered": a quiet ring and a quiet reflection look the same, and the design's own section 10 row says silence and "no new head" are indistinguishable.

## 4. Explicit stop

### 4.1 `dao.stream.ws/endpoint-stop!` (new)

`(endpoint-stop! endpoint)` closes every connection the endpoint still owns before acknowledgement: for each slot in `:pending`, set the handle phase `:closed`, `invoke-close!` 1001 `"dao.stream/endpoint-stopped"`, `terminal!` `:ws/closed` on the control medium, `release-slot!`. Answers the endpoint. Idempotent; a later `endpoint-step` finds nothing pending; a later `accept-connection!` still works unless the host listener is gone, which is the host's `unbind!`. This is the "endpoint stop" the ws design already lists as a terminal cause (Serving, ownership bullets) without an operation to invoke it.

### 4.2 `dao.stream.ws-project` stop (new, two functions)

- `(stop! acceptor)` marks `:stopping? true`. From then on `adopt!` rejects every offer the way the cap does: the offered handle is closed, no acknowledgement is appended, no media composed.
- `(close-sessions! acceptor)` runs `close-session-resources!` on every session and marks each `:closed?`; the next `accept-step!` reaps them. Outside `swap!`, driver only, as `reap-sessions!` is.

`accept-step!` is otherwise unchanged: while stopping it still runs the per-session projection and mirror pass, which is the one last bounded answering pass of §0 rule 5.

### 4.3 `dao.stream.remote-channel/stop!` and completion in `serve-step`

`stop!` on a `:starting` or `:serving` server: `ws-project/stop!` on the acceptor, `:status :stopping`, `:stop {:since nil :outcome nil}`. On any other status: unchanged (a refused server holds nothing; a stopping one is already stopping). `stop!` performs no I/O; everything observable happens in the driver's `serve-step`, so a test can assert each stage.

`serve-step server now` while `:stopping`, the first tick after `stop!` (`:since` nil → set to `now`):

1. `accept-step!` once: offers rejected, the last bounded mirror pass answers what sessions already asked (a reader's outstanding `next` gets its `blocked` or value rather than a bare drop where the budgets allow).
2. `ws-project/close-sessions!`, then `ws/endpoint-stop!`.
3. `unbind!` with the server's `deposit!`; a throw or non-ok answer records `:stop {:outcome :dao.stream.remote-channel/unbind-failed}` and the server is `:stopped` at once (no later completion can arrive).

Every later tick while `:stopping`: drain the lifecycle medium; `:stopped` fact → `:status :stopped`, `:stop {:outcome :confirmed}`; else if `(>= (- now since) stop-grace-ms)` → `:stopped` with `:stop {:outcome :dao.stream.remote-channel/unconfirmed}`. The grace is driver `now`, never a sleep. A `:listener-error` fact while stopping is recorded, not fatal.

No `stop-grace` wait precedes closing the sessions, unlike `yin.repl.serve/finish-stop`: the board serves a read-only ring that is not ended, so a reader has nothing to be told but "the connection closed", which is exactly the reattachable `:ws/closed` the ws design assigns to it. S3b, which ends served media, keeps its grace and reads the S3a stop as the generic half it builds on.

### 4.4 `dao.stream.remote-channel/close!` on a dial

`close!` closes the ws handle when a channel exists (today's `head.ws/close!`), closes the reflection handle when attached (local: the follower's next read answers `end` or `channel-gone`, both `:lost`), and answers `(assoc d :status :closed)`. Idempotent. A closed dial is never stepped again; a fresh dial is composed for the redial, as the ws design composes one medium per attachment.

## 5. Lifecycle gap and channel drop

### 5.1 The lifecycle medium (`serve-step`, every status that reads it)

| status | `ok` fact | `gap` | `end` |
|---|---|---|---|
| `:starting` | `:bind-succeeded` → `:serving`; `:bind-failed` → refused `bind-failed` | refused `:dao.stream.remote-channel/lifecycle-lost`, then `unbind!` best-effort (the bind may have succeeded unseen) and `endpoint-stop!` | same as gap |
| `:serving` | `:listener-error`, `:upgrade-failed` recorded under `:diagnostics` (bounded: last 8); `:stopped` without `stop!` → `:stopped` with `:stop {:outcome :dao.stream.remote-channel/host-stopped}` (the host went away under us) | adopt the recovery cursor, `(update :lifecycle-gaps inc)`, keep serving | `:stopped`, outcome `host-stopped`, after `close-sessions!` and `endpoint-stop!` |
| `:stopping` | `:stopped` → `:stopped` confirmed | `:stopped` with outcome `unconfirmed` | same as gap |

The lifecycle medium is a ring of `:capacity` 64 written only by the host listener; a gap while serving means at most 64 diagnostics were lost, none of which changes state. The REPL's rule that a lifecycle gap is fatal (`serve.cljc` `drain-lifecycle`, R4) is preserved for the REPL in S3b; for a read-only board the honest statement is that nothing observable was lost, and the count says it happened.

### 5.2 Channel drop

| side | event | observed as | recovery or terminal |
|---|---|---|---|
| dial, resolving | `:ws/transport-error` resolution | projection closes ring → resolve answers `channel-gone` → `:lost` | shell redials with backoff (recoverable) |
| dial, resolving | `:ws/not-found` resolution | `:lost`, reason `not-found` | the endpoint disclaimed; the shell keeps its backoff and the line says the endpoint serves no board |
| dial, attached | `:ws/closed` | projection closes ring; `dial-step` → `:lost` `channel-gone`; follower `:source-lost` | redial; the reader resolves the name again and may learn a new identity (restart) |
| dial, attached | traffic medium `gap` that evicted `:ws/closed` | the ring stays open; the next `next` request expires after `give-up-after` → `channel-gone` | same as above, 15 s later: the deadline is the recovery for a lost lifecycle event (§0 rule 6) |
| dial, attached | peer alive, answers never arrive | `channel-expired` on `:events`, then as above | same |
| server, session | `:ws/closed` on the session's traffic medium | projection closes the ring; `reaped` drops the session | nothing to recover: the reader reattaches |
| server, session | traffic medium `gap` evicting `:ws/closed` | the session idles; `idle-timeout` reaps it at 60 s, closing handle and ring | the idle bound is the recovery |
| server, pending | peer drops before acknowledgement | `endpoint-step` releases the slot (S1) | — |
| either | restart with a new ring identity | the reflection's kept cursor answers the source's own `invalid-cursor`; the resolve answers the new identity | covered by `a-restarted-publisher-is-a-lost-source-then-a-new-identity` |

No row invents a `gap` on the board, and no row touches `:installed` (D2, D3).

## 6. Design amendments (land with the code)

- `dao.stream.remote.md` §3.0: two new bullets, "Explicit stop" (acceptor `stop!`/`close-sessions!`, endpoint `endpoint-stop!`, the stopping order of §4.3, completion by host fact or composed grace) and "Lifecycle observation" (§5.1's table in one paragraph); §3.1 gains one sentence naming `dao.stream.remote-channel` as the stepped composition over this channel and the production profile as its composition data.
- `dao.stream.ws.md` Serving: the ownership bullet "the endpoint continues to own its host connection for endpoint-wide stop" gains `endpoint-stop!` by name and the 1001 close. Deferred, Liveness bullet: rewritten to say the idle probe is the consumer's own periodic read and its deadline, cadence the composition's; the open question is closed for channels used through `dao.stream.remote`.
- `yin.vm.linker.dht.head.md` 5.1: the exposure paragraph names `dao.stream.remote-channel` and the opaque spec; "Loopback only" stays with a pointer to S4. 5.5 (the "silent publisher" paragraph): `:polled` and `:answered` as §3.2 defines them, and liveness assigned to the channel (2.4 Expiry). 6: the last table row becomes `yin.vm.linker.head.board/serve, serve-step, stop!, dial, dial-step, close!` over `dao.stream.remote-channel`. 7: `:head-poll-ticks` row notes the ms domain and the 60 s / 15 s relation. 8.3 "Unverified": the first, second and fifth items become verified with the S1/S2/S3a citations; the third (listener seams compose outside `yin.repl.serve` on all three hosts) is verified for JVM and Node by §7 and stays open for Dart until S4; the fourth (TCP at the UDP number) stays S4's. 8.2 table: the off-loopback row's "a bound on concurrent sessions" is now landed. 10: the "publisher restarts, or the connection drops" row gains "or stops answering for `give-up-after`". 13: unchanged. Remove the sentence in 8.3 "Off loopback a half-open connection is real and the follower would see retryable errors until TCP gives up" (D5: remove claims that half-opens necessarily produce retryable errors).

## 7. Acceptance evidence and test plan

Per-iteration: `clojure -M:test -n <ns>` then `bb test:clj`; once per sub-slice before commit: `clj -M:kondo` and the full `bb test`, one lane set at a time, foreground (`docs/agents/build-n-test.md`). A fresh worktree needs `mise trust` and `npm ci`.

**Fixture move.** The in-process loopback net of `head_ws_test.cljc` lines 40–140 (`loopback-net`, `enqueue!`, `pump!`, `listen-on`, `connect-on`, `close-conn!`, `unlisten!`) moves to `test/dao/stream/loopback_net.cljc` as public fixtures, since the generic tests need it below yin. The net gains two knobs: `blackhole!` (frames toward one side are accepted by `send!` and dropped) and `flood!` (the server side enqueues `n` junk `:ws/payload` frames toward the client), both pure additions.

**`test/dao/stream/remote_channel_test.cljc`** (new; every case over the net with small bounds via `:bounds`):

- `serve-formats-the-descriptor-below-the-boundary`: `(descriptor-of {:host "127.0.0.1" :port 9 :path "/x"})` is the ws descriptor; `serve` with the spec answers `:descriptor` equal to it; no `:ws/` key in the spec.
- `a-reader-resolves-and-attaches-over-the-stepped-composition`: the section-5 toy (a one-value ring) served under a name; `dial` → `:resolving` → `:attached`; the handle reads the value; identity is the ring's.
- `production-bounds-reach-every-layer`: serve and dial with `production-bounds`; assert `make-acceptor`'s atom carries 64/60000/64/64/32, the endpoint's `:bounds` the five `:ws/*` values and `:expiry-ms`, and the link policy (via a `next` past `max-outstanding` with a `counting-writer`-style seam, or by reading the dial's `:policy`) the four link keys and `give-up-after`.
- `idle-healthy-board-versus-blackholed-request` (D5 evidence): two worlds, poll every 50 ms, `give-up-after` 150. Healthy: the server is stepped; after 20 polls the dial is `:attached`, `:events` carries no `channel-expired`. Blackholed: `blackhole!` server→client after attach; at `now` ≥ send + 150 the dial is `:lost` `channel-gone`, `:events` carries one `channel-expired` naming the `next` op, the ws handle is closed.
- `unrelated-traffic-flood-cannot-defer-expiry` (D5): as blackholed, plus `flood!` 500 junk values per tick with `drain-budget` 4; expiry still lands at the deadline tick.
- `a-lost-close-event-is-recovered-by-the-deadline`: after attach, close the connection with the client's traffic medium already full of 64 unread events so `:ws/closed` is evicted (`gap`); the ring stays open; the next poll's `next` expires; `:lost` within `give-up-after`.
- `stop-is-explicit-and-driver-paced`: a serving board with one attached reader and one pending pre-acknowledgement connection (offer made, acceptor not yet ticked: compose the pending by calling `accept-connection!` directly between ticks); `stop!` → `:stopping` and no I/O yet (the reader's handle still open); first `serve-step`: the reader's outstanding `next` was answered (its ring holds the value), then the session handle and the pending handle are closed (both `:ws/closed` deposited), slots free, `unbind!` called once; the net's listener deposits `:stopped`; next `serve-step` → `:stopped` confirmed.
- `stop-completes-without-a-close-callback` (D5 "missing close callback"): an `unbind!` that never deposits; `:stopped` at `since + stop-grace-ms` with outcome `unconfirmed`, never earlier.
- `stop-while-starting-and-stop-twice-are-idempotent`.
- `a-lifecycle-gap-while-serving-is-counted-and-serving-continues`: push 70 `:listener-error` facts through `deposit!` before a tick; `:lifecycle-gaps` 1; a reader still attaches afterwards.
- `a-lifecycle-gap-while-starting-is-terminal`: same before `:bind-succeeded` is read; `:refused` `lifecycle-lost`; `unbind!` called; endpoint has no pending slot.
- `a-host-that-stops-under-us-is-stopped-host-stopped`: the net's `unlisten!` deposits `:stopped` with no `stop!`; sessions closed.
- `session-cap-and-idle-expiry-hold-under-the-profile` (D5): `:max-sessions 2`, `:idle-timeout 100`; a third dial resolves `transport-error`; a reader that stops polling is reaped at 100 ms and its next read is `channel-gone`.
- `a-newcomer-during-stop-is-rejected`: dial after `stop!`, before the listener is gone: resolution `:ws/transport-error`, no session.
- `no-transport-and-no-port-are-refusals-as-data`.

**`test/dao/stream/ws_test.cljc`**: `endpoint-stop-closes-pending-connections-and-frees-slots` (two pending offers, `endpoint-stop!`, both seams saw 1001 `dao.stream/endpoint-stopped`, both `:ws/closed` on the control medium, slots `:free`, a late acknowledgement is stale, a second `endpoint-stop!` is a no-op).

**`test/dao/stream/ws_project_test.cljc`**: `a-stopping-acceptor-rejects-offers-and-still-answers-sessions` (one session asks, `stop!`, one tick: the answer is on the wire, the new offer's handle closed, no ack); `close-sessions-closes-handles-and-rings-and-the-next-tick-reaps`.

**`test/yin/vm/linker/head_follow_test.cljc`**: `blocked-is-polled-not-answered`: a follower over a reflection whose link has nothing filed; after one poll `:polled` is `now` and `:answered` nil; after the server answers, `:answered` is the poll that read the value; a later empty poll advances `:polled` only. Over a plain ring: `:answered` advances on the value, not on the quiet polls. `a-lost-source-is-reported-once-and-a-fresh-handle-reads-again` asserts `:polled`/`:answered` are cleared by `attach`.

**`test/yin/vm/linker/head_board_test.cljc`** (renamed from `head_ws_test.cljc`): every existing case over `head.board` with `(dial-step d now)` and the moved net; `the-table-holds-the-board-and-nothing-else` and `no-wire-value-carries-the-name-as-an-identity` unchanged in substance; `a-bind-host-that-is-not-a-loopback-literal-composes-no-endpoint` stays (the gate is still here until S4); `a-stopped-board-is-a-lost-source-then-reattachable`: `stop!`, the follower emits `:source-lost` once, a new `serve` on the same port and a fresh dial reattach and read the same identity; `a-blackholed-board-is-source-lost-after-give-up-after` through the follower and the shell's own `yin.repl.dht` redial (the `dht_head_test` world). The real-socket JVM case (`the-board-crosses-a-real-loopback-socket`) is kept and extended: stop through `head.board/stop!`, `:stopped` confirmed by http-kit's completion within `stop-grace-ms`. A Node twin of that case in `test/dao/stream/ws_project_cross_node_test.cljs` style, over `dao.stream.ws.node/listen!` and `stop-listening!`, is the "real JVM/Node sockets" evidence; Dart dials a JVM board through the existing peer harness (`transfer_peer.cljd`) and asserts attach, read, and `channel-gone` after the JVM board stops. IPv6 real-socket binding is S4's with the advertisement work.

**`test/yin/repl/dht_head_test.cljc`**: the `:yin.head/no-answer` case becomes a `channel-gone` case with the new line text; `close!` leaves no listener and no dial (the net's listeners map empty, every connection closed).

D5 evidence covered here: idle healthy board versus blackholed request; unrelated-traffic flood cannot defer expiry (end to end, over S2c's primitive); session cap and expiry under the production profile; missing close callback; lifecycle gap; restart identity mismatch (existing); real JVM and Node sockets; explicit stop. Left to S4: IPv6, advertisement, the Dart listener, the loopback lift. Left to S3b: writable REPL append ambiguity and the REPL's ended-media stop grace.

## 8. Sub-slicing and landing order

Two commits, each reviewed, each with green three-lane `bb test` and clean kondo, each landable alone.

| commit | code | tests | design |
|---|---|---|---|
| **S3a-1** `feat(stream): stepped channel composition, explicit stop and lifecycle observation (cross-machine stream slice S3a-1)` | `ws.cljc` `endpoint-stop!`; `ws_project.cljc` `stop!`, `close-sessions!`, the stopping branch in `adopt!`; new `remote_channel.cljc` with `production-bounds`, `descriptor-of`, `loopback-literal?`, `serve`, `serve-step`, `stop!`, `sessions`, `dial`, `dial-step`, `handle`, `close!` | `loopback_net.cljc` fixture, `remote_channel_test`, the `ws_test` and `ws_project_test` cases | `dao.stream.remote.md` §3.0/§3.1, `dao.stream.ws.md` Serving and Deferred |
| **S3a-2** `feat(yin.vm.linker.head): neutral board over dao.stream.remote-channel, honest :answered, stream-side liveness (cross-machine stream slice S3a-2)` | `head/board.cljc` replacing `head/ws.cljc`; `head.cljc` `read-source`, `poll`, `new-principal`, `attach`, `heads`; `yin.repl.dht` wiring of §2.3 | `head_board_test` (renamed, extended), `head_follow_test` additions, `dht_head_test` changes | `yin.vm.linker.dht.head.md` 5.1, 5.5, 6, 7, 8.2, 8.3, 10 |

S3a-1 has no yin consumer until S3a-2 and changes no existing consumer's behaviour (new functions, nil-default stop flag), so it is zero-breakage by construction. S3a-2 is the slice that deletes a namespace and changes a reported field; its reviewer should hold the D1 residue test (no `:ws/` under `src/cljc/yin/`, `head/board.cljc` requires neither `dao.stream.ws` nor `dao.stream.ws-project`) and the `:answered` semantics as blocking items. S3b starts from `remote-channel/serve` and `dial` by adding the writer-surface table entries and the ended-media grace; S4 starts from `descriptor-of` by adding advertised host selection.

## 9. Recorded for the Architect record

- **Why the dial reports `:lost` on channel close itself, when the follower would find it anyway.** The composition that owns the connection is the only party that knows it closed the handle; reporting it as data costs one `closed?` read and lets the shell redial on the same tick rather than one poll interval (5 s) later. The follower's path stays the authoritative one for the design's semantics (`:source-lost` is still the only event yin acts on for repair); the dial's status is the earlier of two consistent signals.
- **Why `:answered` is not fixed by annotating a filed `blocked`.** The reflection could add a transport key to a relayed `blocked`; the follower would then read a `:dao.stream.remote/*` key, which is exactly the transport-health inspection D3 forbids, and it would make the follower's meaning of a field depend on the handle's kind. Two honest fields (`:polled`, `:answered`) keep the follower handle-agnostic and say less, truthfully.
- **Why no stop grace before closing sessions on the board.** The grace in `yin.repl.serve` exists to deliver an `end` of a closed served medium ahead of the socket's `:ws/closed`. The board's ring is not closed by stop; there is no `end` to deliver and nothing a reader should conclude but "reattach". Adding a grace here would delay every shell exit by it for no observable benefit.
- **What S3a does not do.** It does not lift loopback, choose an advertised address or format a token (S4); it does not move `yin.repl.host` or touch `yin.repl.serve`/`connect` (S3b); it adds no shared per-tick pool across sessions (S2 §5 left it until the profile shows need); it does not probe an idle connection with protocol pings (the poll is the probe).

## 10. Verification of this pass

No runtime tests were run; this is a specification. `git status` after writing shows only untracked `collab/` files; `git diff --stat` is empty.

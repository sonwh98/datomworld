# dao.jing.remote on DaoStream v2

Status: implementation plan, subordinate to [`dao.stream.md`](./dao.stream.md)
(the contract) and [`dao.jing.md`](./dao.jing.md) (the storage boundary).
Verified against `3228d0e`. Authored by the Architect and revised through five
rounds against a routine review (`gpt-6-astra`) and an adversarial review
(`deepseek-v4-pro`), neither of which shares a family with the author.
**Phase 0 is committed** (`3228d0e`); Phases 1 and 2 are unbuilt. This document
is transient: it is deleted when nothing in it is still owed, and §9 names
where each thing it carries must be written first.

Method as before: the invariants list (§2) is the contract, grouped by the
test that exercises each and marked `[D]` stated in the design, `[T]` pinned
only by a test, `[T→D]` test-pinned and promoted, `[T✗]` an accident of the v1
dressing dropped with its reason, `[D]` *new* for a clause this plan adds to
the design. The implementation and tests have no authority beyond the
invariants they pin. Nothing is in production.

**What r2 changed.** Both reviews found the same three things and were right
about all three. The JVM transport parked inside `append!` (`ws/jvm.clj:158`
joined the send future), so D1's claim was false *for the shipped transport*;
that became **Phase 0**, a prerequisite repair of a live defect that also
affects `yin.repl.v2`, committed on its own as `3228d0e`. The prescribed
timeout test could not pass under the plan's own S4; it was rewritten with an
explicit release. Retaining timed-out requests leaked payloads for nothing;
they are retired.

**What r3 changed.** Two failure-path gaps: immediate request refusals
bypassed the outbox drain, so `call!` now drains at every exit (N11); and
§5.2 #6 asserted a connect-timeout cleanup the transport does not perform.
r3 answered the second with a Phase 0b that would have made `close!` cancel
the establishment.

**What r5 does, and why.** Phase 0b is dropped, by the owner's decision, with
this architect's agreement. Across three review rounds it grew from a
narrowing question into a second transport project inside a migration plan —
a guarded `onOpen` transition, a `send!` change, cancellation ordering, a
deliberately racy test, a shipped test migrated — for a residual that is
unbounded whatever the seam does, because the JDK offers no handle to bound a
peer that stalls after TCP. Every finding in it was real. A migration plan is
still the wrong place to rebuild the JVM establishment lifecycle, and Phase 0
already fixed the defect that actually blocked this migration. So: **N2 and
D2 now state what the shipped transport does and does not do, as a limit and
not as a promise deferred**; §5.2 #6 asserts only what is assertable and
cleans up what it can reach; the gap itself — live for `yin.repl.v2`
disconnecting while connecting — is recorded as owed by
`dao.stream.v2.ws.jvm` in §8, and its earned substance is carried in §9 to a
named home. That is the §9 rule doing its job. **One cost is worth saying
plainly, and it is recorded in N2**: a caller that opens `:dao.jing/remote`
coordinates against an endpoint that accepts TCP and never completes the
upgrade leaks one JDK connection per attempt, for the process lifetime, and
nothing in `dao.jing.remote` can prevent it. Everything else is unchanged.

---

## 0. Corrections to the brief

The brief's measurements are right in every particular I could check. Eight
sharpenings, three of which change the plan's shape:

1. **cljd is not "nothing to do", and the brief's warning is exactly right
   about the mechanism.** ClojureDart's host-eval pass reads with `:clj`
   active, so a `#?(:clj …)` branch is *evaluated on the JVM host* during
   the cljd build even though the emit pass skips it. Measured in the
   generated Dart at `62ed336`: `lib/cljd-out/dao/jing/remote.dart` imports
   `../stream/rpc/ws.dart` and `../stream/rpc/client.dart` — the two
   `#?(:clj …)` requires at `remote.cljc:11-12` **become Dart imports** —
   while it contains no `connect_content`: the `#?(:clj (defn …))` body at
   `:115` was host-evaluated and not emitted. The same split holds for
   `remote-test_test.dart` (imports `rpc/ws.dart` from the test's `:15`
   require; no `with_server`) and `coordinate.dart` (no `validate_remote`).
   That works today only because v1 `dao.stream.rpc.*` has Dart twins.
   `dao.stream.v2.ws.jvm` is a `.clj` file with none, so cljd owes this plan
   two spellings: the JVM glue require must be spliced
   `#?@(:cljd [] :clj [[dao.stream.v2.ws.jvm :as jvm]])` (`yin.repl.v2.host:16`),
   and the new JVM deftests must keep the unconditional-`deftest`, conditional-
   body form the existing `network-*` tests use — a `#?(:clj (deftest …))`
   would be registered by the host pass and missing from the emitted Dart.
   Every new JVM `defn`, including test fixture helpers, is spelled
   `#?(:cljd nil :clj (defn …))` as `dht/node.cljc` does. This rule is
   recorded, with the measurement, in the project memory
   `project-cljd-clj-reader-conditional` (§9).
2. **v1 `call!` never blocked in the stream either.** `rpc.client:96-97` is a
   `Thread/sleep 10` poll loop over v1 `next-response`, bounded by
   `max-attempts`; `connect!` is `await-connected`, another poll. v1 was
   already "waiting in the host". What v2 changes is the state under the loop
   (a value with explicit outcomes instead of a cursor atom scanned by
   concurrent threads), not where the waiting is. This decides Q1 and Q2 —
   **once Phase 0 makes the JVM transport keep its own promise** (D1).
3. **`yin.repl.v2` is a twin, not an in-place migration.** v1 `yin.repl`
   still requires `dao.stream.rpc.client` and `.ws` (`repl.cljc:12-13`) and is
   deleted with v1. The orchestrator log (2026-09-07) records the user
   refusing the twin shape for `dao.jing`. This plan migrates `dao.jing.remote`
   **in place**, which is why `content-client` and `default-handlers` staying
   put matters more than it would for a twin.
4. **The v1 surface is five call sites in prose too.** Besides the four code
   sites, the ns docstring (`:2`, `:5`) and `remote_test`'s ns docstring
   (`:4-6`) name `dao.stream.rpc`; `docs/dao.space.stigmergy.md:9` and
   `dao.jing.md:320` do as well. All are in the edit set.
5. **The test split is 13 + 6.** Thirteen deftests drive `content-client`
   through fake `call-fn`s and touch no transport; six (`network-*`) go through
   `with-server` or their own `rpc-ws/start!`. Only the six move (§7).
6. **`stigmergy_test.clj` has four v1 sites, not two**: the require `:32`,
   `start!` `:70`, `stop!` `:76` (via `(:stop! srv)`), and `*url*` built from
   `(:port srv)` at `:73`. `:87`'s `connect-content!` and the two remote
   coordinates (`:171`, `:432`) are the *surface* and do not change.
7. **`public/demo.html` does not reach `dao.jing.remote`.** Checked rather
   than assumed: `datomworld.demo` → `demo.compilation-pipeline` →
   `dao.space.query` → `dao.jing.coordinate`, whose `dao.jing.remote` require
   is `#?(:clj …)` (`coordinate.cljc:6`), so the cljs build never loads it.
   The demo is compiled each phase anyway as the standing regression check.
8. **`dao.jing.dht.node`'s v1 reach is a codec, not a stream**: `transit/
   encode` and `transit/decode` at `node.cljc:59,65` and `node_test:139`,
   nothing else. That bounds the second plan (D8) — but the v2 codec is not
   a drop-in for it, as D8 now says.

Three measurements the brief did not make and the plan depends on. **`default-
handlers`'s return value is already the v2 handler shape**: `apply/dispatch-
request` does `(get handlers op)` and `(apply handler args)` — exactly what
`rpc.server` did — so the server side changes nothing in `default-handlers`
and its four tests do not move. **The shipped JVM WebSocket edge waited
inside `append!`** (at `62ed336`; repaired in `3228d0e`): `ws/jvm.clj:158` was
`(.join (.sendText ^WebSocket socket message true))`, reached through
`rpc/request!` → `stream/append!` → `WsHandle.append!` → `send-result`, in
contradiction of its own docstring ("a send accepted by the host is therefore
`:dao.stream/ok`") and of `dao.stream.md` *Writing* (`ok` "implies neither
local readability nor remote delivery"). Neither the Node edge
(`node.cljs:154`, `.send`) nor the Dart edge (`dart.cljd:118`, `.add`) had it.
And **the JVM edge does not cancel an establishment it is asked to close**
(at `3228d0e`, and after this plan): `client-socket`'s `:close!` before
`onOpen` stores `:close-request` (`jvm.clj:168-173`) and returns; the
`buildAsync` future (`:231`) runs on, and the stored request is acted on only
by an `onOpen` (`:184-189`) that a stalled peer never triggers. That is a
transport limit N2 states and §8/§9 carry; this plan does not repair it.

---

## 1. What the namespace is

`dao.jing.remote` is a content-store handle that lives on the other side of a
wire. Its server half, `default-handlers`, turns any local `dao.jing` handle
into a two-op map — `:jing/put-content` answering `:inserted`/`:present`
after validating address-against-payload at the door, and `:jing/get-content`
answering the exact presence envelope `{:found? b :value v}` so a stored `nil`
is distinguishable from absence. Its client half, `content-client`, is the
mirror: a plain-data `dao.jing` handle whose `:put-content-fn` and
`:get-content-fn` are one injected `call-fn` each, with a once-only guarded
close. Both halves are values over injected functions and name no transport.
The only thing v1 about the namespace is the JVM constructor `connect-content!`,
which supplied v1's `rpc-ws/connect!` and `rpc-client/call!` as those
injections, and the `comment` block showing `rpc-ws/start!` as the server. The
migration is therefore a **new `connect-content!` and a new server
constructor** over `dao.stream.v2.rpc`, plus a small portable step the JVM
driver turns, with `content-client`, `default-handlers`, `dao.jing.coordinate`
and the thirteen contract tests untouched. That is small, and the plan says so
— with one prerequisite outside the namespace (Phase 0, done) that the
migration exposed rather than caused.

---

## 2. The invariants

Tests are current `remote_test` deftests unless named otherwise.

### 2.0 The JVM send seam — `dao.stream.v2.ws.jvm`, Phase 0 (done)

`test/dao/stream/v2/ws/jvm_test.clj` (seven deftests shipped in `3228d0e`),
and `yin/repl/v2/host/jvm_test.clj` unchanged.

| # | Invariant | |
|---|---|---|
| J1 | `:send!` returns as soon as the host has accepted the message; it never joins, parks or sleeps. `ok` means accepted, per `dao.stream.md` *Writing* and the namespace's own docstring | `[D]` — stated twice already, violated by `:158` |
| J2 | Sends are serialized in submission order without blocking the submitter: a send issued while a previous one is incomplete is chained behind it, because `java.net.http.WebSocket` completes a second `sendText` exceptionally while one is pending | `[D]` **new** |
| J3 | A send that fails after acceptance is reported as data on the boundary's stream — `:ws/error` through the adapter's `:error!`, then the socket aborted, then the terminal `:ws/closed` through `:closed!` — never as a return value and never swallowed | `[D]` — `dao.stream.ws.md` *Deposited events*, `dao.stream.md` *Surfaces* ("observably gone") |
| J4 | Before the socket exists `:send!` answers `false` (`full`), as today; `close!` answers `nil` whatever the connection's state, because closing something already gone is satisfied, not refused | `[T]` kept, extended |
| J5 | That report happens **once per connection** and **outside the submission monitor**. Every future in a chain completes exceptionally when its predecessor does, so without a once-only claim one lost socket reports once per queued send; and an already-exceptional future runs its observer inline, so registering it under the lock would hold the monitor across the adapter's deposits while other submitters block | `[D]` **new, forced by review** |
| J6 | A connection that has failed stays terminal: it accepts no further chaining, and `send!` answers `closed` — not the retryable `full` — so an append that read the handle's open phase a moment before the claim does not leave a request retained as unsent for a socket that is never coming back | `[D]` **new, forced by review** |

The table ends here. A close issued before the socket exists records a
`:close-request` and does nothing else (`jvm.clj:168-173`); that is not an
invariant this plan adds or repairs, it is the shipped behaviour N2 is
narrowed to, and §8/§9 carry what a successor needs to change it.

### 2.1 Server handlers — `default-handlers`, unchanged

`default-handlers-exact-test`, `server-put-rejects-non-keyword-address-test`,
`server-put-rejects-hash-mismatch-test`, `backend-invalid-put-result-test`.

| # | Invariant | |
|---|---|---|
| H1 | The handler map exposes exactly `:jing/put-content` and `:jing/get-content`; construction throws without a `:put-content-fn` | `[D]` `dao.jing.md` *Reads*, ns docstring |
| H2 | Put validates that the address is a segment address hashing to the payload before the backend is consulted; either failure throws and stores nothing | `[D]` *Materialization rule* |
| H3 | Put answers the backend's `:inserted`/`:present` verbatim; any other backend answer throws | `[D]` |
| H4 | Get answers exactly `{:found? boolean :value v}`; stored `nil` is `{:found? true :value nil}` | `[T→D]` — the wire vocabulary, pinned by tests and a docstring; §9 gives it a durable home |
| H5 | A handler that throws never escapes the server step: it becomes a `:dao.stream.v2.apply/handler-error` response, and an unknown op an `/unknown-operation` response | `[D]` **new** — v2 `apply/dispatch-request`'s rule replaces v1 `rpc.server`'s |

### 2.2 Client handle — `content-client`, unchanged

`put-automatic-materialize-test`, `get-nil-opaque-absent-test`,
`put-duplicate-reports-present-test`, `client-get-arbitrary-address-test`,
`close-idempotent-ops-throw-test`, `close-failure-retry-test`,
`two-clients-share-store-test`, `local-malformed-envelope-test`,
`local-present-then-absent-test`.

| # | Invariant | |
|---|---|---|
| C1 | The handle is `{:client :closed-atom :put-content-fn :get-content-fn :close-fn}` and `jing/materialize!`, `jing/get`, `jing/close!` dispatch through it | `[D]` *Implemented surface* |
| C2 | `call-fn` is invoked as `(call-fn client op args)`, `close-fn` as `(close-fn client)`; the handle names no transport | `[D]` docstring — the property that makes this plan small |
| C3 | A get whose response is not the exact presence envelope throws with `{:operation :jing/get-content :address a :response r}` and a message containing "malformed RPC response" | `[T→D]` |
| C4 | Ops after close throw; concurrent closes run `close-fn` exactly once; a `close-fn` that throws leaves the client open for retry | `[D]` docstring + `[T]` |
| C5 | `jing/get` rejects a non-segment address before the wire | `[D]` `dao.jing.md` *Reads* |
| C6 | `:present` over an absent address throws (`jing/materialize!`'s read-back rule, exercised through the client) | `[D]` |

### 2.3 The connection — `connect-content!`, JVM

`network-connect-test`, `network-materialize-and-get-test`,
`network-two-clients-share-test`, `network-invalid-url-test`,
`network-file-restart-test`, `network-presence-envelope-test`, plus the new
tests named in §5.2.

| # | Invariant | |
|---|---|---|
| N1 | `(connect-content! url)` / `(connect-content! url opts)` returns a `content-client` handle over a live attachment; JVM-only | `[D]` `dao.jing.md:240,302` |
| N2 | `connect-content!` returns only after the attachment reports `/established`; a terminal lifecycle before that throws with the reason, and `:connect-timeout-ms` without either throws `{:url :timeout-ms}`. In **all three** failure cases — the terminal lifecycle, the deadline, and an interruption of the establishment loop's sleep — **before the throw**, `stream/close!` has run on the handle, which flips its phase to `:closed` so no handle, medium or state escapes to the caller and no later call could be made through it. The third was found in Phase 2's review: an interrupt propagating raw left an attached handle and its socket live while the caller held nothing, which is a failure of this clause, not of N11 (no id is allocated yet). The traffic cursor is minted **before** `attach!`, so `/established` cannot land ahead of the position that will observe it. **The limit, in this plan's own words**: on the JVM, `stream/close!` before the socket exists records a `:close-request` and does nothing else (`jvm.clj:168-173`); nothing touches the pending `buildAsync` future, and the `HttpClient` that `connect!` builds carries no connect or request timeout. So against a peer that accepts TCP and never completes the upgrade, the client-side JDK connection is **not torn down**, the peer **need not observe EOF at any time**, and the connection persists for the **process lifetime**. Each such failed open leaks one JDK connection, and `dao.jing.remote` has no handle through which to prevent it. This is the shipped transport's behaviour and this plan does not change it (§8) | `[T→D]` — `network-invalid-url-test` pins only validation (§5.2 #5); §5.2 #5 and #6 pin the throw and that nothing escapes; nothing pins teardown, because there is none |
| N3 | A call returns the remote value to the caller; a duplicate put reports `:present` over the wire; a second client sees the first's writes; content survives a server restart on a file backend; `nil`, an envelope-shaped payload, and a caller sentinel round-trip | `[T]` — transport transparency, C1/H3/H4 through a real socket; kept verbatim |
| N4 | v1 opts `:timeout-ms`, `:capacity`, `:eviction-policy` | **`[T✗]`** — pinned by nothing; replaced by `:connect-timeout-ms`, `:request-timeout-ms` (name kept), `:poll-interval-ms` |
| N5 | Concurrent `call!`s on one client (v1's shared cursor atom, `rpc.client:29-42`) | **`[T✗]`** — no `remote_test` pins it. Calls on one handle serialize under the client's `:lock`: **one locally awaited call per client**. The lock covers call-versus-call only; `close!` is outside it (N10). A timeout does not cancel remote execution, so the server may still be running a call nobody awaits |
| N6 | A call with no completion within `:request-timeout-ms` throws `{:request-id id :timeout-ms ms}` and **retires its bookkeeping**: the id leaves `:outstanding`, and a still-`:unsent` envelope is abandoned with reason `:dao.jing.remote/timeout`. The allocator is untouched, so a late response for that id is classified `unsolicited-response` by `rpc/handle-event` (`rpc.cljc:363-364`) and dropped with the diagnostics; it can never be delivered to a later call | `[D]` **new** — r2 |
| N7 | A payload outside the v2 portable domain (`transit/portable-value?`) is refused at the writer as `invalid-value`; the call throws with reason `:dao.stream/invalid-value` and nothing was sent | `[D]` **new** — v1 Transit custom handlers are gone; `dao.jing.file` already stores `pr-str` EDN, so every payload the tests carry is portable |
| N8 | Once the attachment is terminal (`/detached`, `/ended`, `/not-found`, `/transport-error`), every call throws with that reason; the handle is never rebound. Reattachment is the caller's: open a new coordinate | `[D]` **new** — D4 |
| N9 | A remote error response throws `{:operation op :error {code message}}`; a lost completion throws `{:operation op :reason r}` | `[D]` **new** — replaces v1's "Remote error:" string throw, which nothing pins |
| N10 | `close!` during an in-flight call is safe and observable: `stream/close!` flips the handle's phase, the boundary deposits the terminal event, and the in-flight call throws N8's `/detached` (or N9's loss) on its next step; the underlying close still runs once (C4) | `[D]` **new** — r2; pinned by §5.2 #7 |
| N11 | **Every exit of `call!` leaves the client's RPC state with empty `:completed` and `:diagnostics` outboxes and with no retired id in `:outstanding`** — the returning exit, the timeout exit, the terminal exit, the immediate-refusal exits (`request-undeliverable`, `invalid-request`, `allocator-error`, `terminal`), **and the interrupt exit** alike. That last one is the eighth, found in Phase 2's review: `Thread/sleep` in the poll loop throws `InterruptedException`, and leaving by that raw exception stored nothing — while the request was already appended and the advanced allocator lived only in the loop's local state, so the next call reused the id and the interrupted call's late response could satisfy it. The fix retires from the loop's latest state through `settle!`, re-asserts the thread's flag, and throws. A tenth path was found in the same review and is **not** a ninth exit: invalid `:request-timeout-ms` or `:poll-interval-ms` threw *after* `request!` had appended, in the deadline arithmetic or in `Thread/sleep`, so nothing was stored and the id was reusable. It is fixed as an **entry gate** — the timing options are validated at `call!`'s entry, before the lock and before `request!` — so it throws while there is no state to settle and nothing on the wire. Argument defects must throw before the wire, not after it. The state stored back is bounded by the one call in flight, whatever the caller does next and however many calls are refused in a row | `[D]` **new, r3** — `rpc/attempt-unsent`'s undeliverable branch appends a completion carrying the request's `:args` (`rpc.cljc:216-224`, `:130-134`); `invalid-request` appends a diagnostic carrying `{:op :args}` (`:252-255`); `allocation-failure` loses every outstanding request into `:completed` (`:160-166`). None of those exits reached `call-step`, the only drain, so a `:jing/put-content` refused for a non-portable payload retained that payload |

### 2.4 The server — `serve-content!`, JVM, new

`with-server` (fixture), `network-file-restart-test`, `stigmergy_test`'s
`space-fixture`, plus §5.2 #4, #8.

| # | Invariant | |
|---|---|---|
| S1 | `(serve-content! handlers port)` / `(… port opts)` serves any `{op fn}` map at `content-path` over a v2 WebSocket endpoint; each `:ws/payload` request is answered by `apply/dispatch-request` and the response appended to that attachment's socket handle | `[D]` **new** |
| S2 | `stop!` detaches every session, releases the listener, stops the ticker; idempotent | `[D]` **new** |
| S3 | A bind failure throws from `serve-content!` and retains nothing | `[D]` **new** — `serving/start!` answers `transport-error` |
| S4 | The handler runs in the server's single driver thread; a handler that never returns stalls every session | `[D]` **new**, an accepted limit, the same one `yin.repl.v2.implementation-plan.md` records for R4 |
| S5 | A handler result outside the portable domain is never dropped silently: it is replaced by a correlated `:dao.jing.remote/non-portable-result` error response, so the client throws N9's error rather than timing out. Any non-`ok` outcome from the response append retires the attachment (`stream/close!` on its socket handle), so the client observes N8's terminal loss rather than silence | `[D]` **new** — r2 |

### 2.5 Coordinate — unchanged

`index_test:616 remote-coordinate-allows-an-explicit-nil-options-entry`,
`stigmergy_test:171,432`.

| # | Invariant | |
|---|---|---|
| K1 | `{:dao.jing/type :dao.jing/remote :url s}` with optional `:options` (nil allowed) opens through `connect-content!` on the JVM and fails closed elsewhere | `[D]` — byte-for-byte unchanged (D6) |

---

## 3. Decisions

### D1 — the waiting goes into the JVM host driver; that is honest only once the transport stops waiting too

Three options were on the table. **An asynchronous handle variant** changes
`{:put-content-fn :get-content-fn}` for `mem`, `file`, `dht` and every caller
up through `dao.data.btree.storage`, `index/read-datoms`, `query/
open-published!` and `schema/current` — that is `dao.data.btree.md` §5.4's
async hydration, a design of its own, and `dao.jing.md` already lists it as
open. **Declaring a synchronous remote handle impossible** is false on the
JVM: the JVM can park a thread, `stigmergy_test` proves the handle useful, and
the coordinate already says JVM-only. So the answer is the third: **a poll loop
in the host, inside the `#?(:clj …)` `connect-content!` composition, stepping
a portable non-waiting state machine.**

Whether that violates *no operation waits* turns on what an operation is.
`dao.stream.md` constrains DaoStream *operations*: each "returns what is true
at the moment it is called". It then says, in the same voice, where the rest
goes: "retry cadence is the concern of the interpreter or the runtime driving
it, not of the stream" (*The readiness extension*), "cadence belongs to the
runtime driving the interpreters" (*What it costs*), and the *Composition*
section's driver "repeatedly calls the step, yielding execution as needed by
the host runtime". A JVM thread that calls `rpc/poll!`, receives `idle`, and
sleeps is that driver. `yin.repl.v2/-main` on the JVM is exactly this loop with
a 25 ms tick (`v2.cljc:21`, `poll-loop!` at `:191`); the observer loop in
`stigmergy_test:150-160` is another. No operation is made to wait: each
`poll!` returns, each `request!` returns. The distinction is real, and
correction 2 shows v1 relied on it too.

**What r1 got wrong, and what now rules.** The argument holds for the
*contract's* operations and was false for the *shipped JVM transport*: the
path `request!` → `append!` → `send!` parked in `.join` until the host had
finished sending, before the host loop could look at its deadline, and the
retry of an `:unsent` envelope reached the same path. The plan's request
timeout could not bound that wait. This was a defect in `dao.stream.v2.ws.jvm`
against its own docstring and against `dao.stream.ws.md`'s `ok`, live for
`yin.repl.v2`; it was not something this migration introduced, and it was not
something this migration could build on either. **Phase 0 repaired it
(`3228d0e`, §4.0), and D1 is conditional on Phase 0 having landed.** After it,
every operation on the call path returns without parking, and the only
waiting anywhere is the host loop's `Thread/sleep` between completed
operations. The establishment path has a limit of a different kind — not an
operation that waits, but a close that does not reach the establishment it is
asked to end — which N2 states and D2 explains; it does not affect this
ruling, because no operation parks on it.

It stays honest under three conditions, each of which the plan enforces:

- **The loop lives only in the host composition.** The portable part of the
  namespace exposes a step (`call-step`, D3) that returns after one bounded
  advance. cljs and cljd get the step and not the loop, because they have
  nothing to wait with — which is the contract's own reason for the model.
- **The policy is named as policy.** `:request-timeout-ms`, `:connect-timeout-
  ms` and `:poll-interval-ms` are options of the JVM constructor, with v1's
  defaults (5000, 5000, 10). They are not constants of the step.
- **What the loop costs is stated, not hidden**: one parked thread per
  awaited call, one locally awaited call per handle (N5), a timeout that
  abandons the wait but not the remote execution (N6), bookkeeping bounded by
  the one call in flight (N11), JVM only. `dao.jing.md` already carries the
  deeper cost — "a synchronous handle that accepts a write and blocks until
  durable keeps the storage coupling" — under *Open items*, and this plan
  leaves it there rather than pretending v2 dissolved it.

### D2 — `connect-content!` waits for establishment, so an unreachable endpoint still throws at open; on failure it closes the handle, and it claims nothing about the peer

Not needed for correctness: before `:ws/opened` the handle's `append!` answers
`full` (`ws.cljc:171`), `rpc/request!` retains the envelope as `:unsent`, and
the call step retries it, so a call issued while connecting would simply
complete once the socket opens. It is kept for N2 — `dao.jing.md` says
coordinates "fail closed", and `query/open-published!` relies on
`jing-coordinate/open!` failing before a store is retained (its P5 cleanup
covers the other order too, but the earlier failure is the cheaper one to
reason about). The wait is the same host loop as D1: `rpc/poll!` with budget 1
until the outcome is `/established`, the state is `:terminal` (throw with the
reason, after `stream/close!` on the handle), or `:connect-timeout-ms` passes
(throw, close). Budget one, so an establishment event is never hidden behind
later events in one poll result.

**The cursor is minted on the traffic medium before `attach!`, and that
order is load-bearing.** `attach!` starts the connection, and the host may
deposit `:ws/opened` on its own thread at any moment after; a cursor minted
afterwards would be positioned *after* that event, the wait would never see
`/established`, and every connect would hang to `:connect-timeout-ms` on a
perfectly good server. It is the client twin of the rule `serving.cljc:62-64`
states for the server ("the factory must have minted the reader cursor before
acknowledgement") and of `dao.stream.md` *Cursors* ("mints its `:newest`
cursor **before** invoking that operation"). One cursor, owned by the RPC
state and advanced only there — the lifecycle events consumed during the wait
are the same elements the calls would otherwise skip past as `/established`.

**What "closed before the throw" means, and what it does not.** r2 asserted
that after a connect timeout "the accepted socket observes EOF"; r3 planned a
transport repair so that it would; r5 drops the repair and states the
transport as it is. `stream/close!` on a `WsHandle` flips the handle's phase
to `:closed` (`ws.cljc:182-192`) and invokes the seam's `close!`. Before the
socket exists, that seam records `:close-request` (`jvm.clj:168-173`) and
returns. Nothing cancels the pending `buildAsync` future; the stored request
is acted on only by an `onOpen` that a peer stalled mid-handshake never
triggers; and the `HttpClient` a `connect!` builds carries no connect or
request timeout, so the JDK waits for the 101 indefinitely once TCP is up.
**What `connect-content!` guarantees on a failed connect is therefore
exactly this**: the handle's phase is closed, nothing escapes to the caller,
and a socket that opens later meets a closed handle whose `append!` answers
`closed`. **What it does not guarantee is anything about the peer or the
JDK-held connection**: no teardown, no EOF at any time, and one JDK
connection held per stalled attempt for the process lifetime. `yin.repl.v2`
has the same limit today when an operator disconnects while connecting.
Repairing it is establishment-lifecycle work in `dao.stream.v2.ws.jvm`, not
in a migration plan; §8 records it as owed and §9 carries what the repair
needs to know. §5.2 #6 asserts the guaranteed facts, nothing more, and
closes the sockets it opened itself.

`network-invalid-url-test` (port 99999) does not pin any of this: `ws/
descriptor?` only requires a positive port, so whether it fails at URL
validation, in `URI/create`, or as a `/transport-error` lifecycle depends on
the JDK. It is kept as the validation pin it is.

### D3 — the portable core is one step; it is the seed of the blocking driver's loop, not of a stepped client

`call-step` is the only new portable logic, with two small companions:

```clojure
(defn call-step
  "One non-waiting advance of the call awaiting `id`. Retries a retained
   unsent envelope, polls at most `budget` response elements, takes and
   drains completions and diagnostics exactly once. Returns
   {:state s' :status :done|:pending|:terminal
    :completion c?          ; the completion for id, when :done
    :reason r?}             ; the terminal reason, when :terminal
   A completion for another id is discarded: it belongs to a call that
   timed out (N6). This is an interpreter step — it performs stream
   operations — under a single-owner, single-awaited-call precondition."
  [state id budget]
  (let [state (if (rpc/unsent? state)
                (:dao.stream.v2.rpc/state (rpc/request! state nil nil))
                state)
        state (:dao.stream.v2.rpc/state (rpc/poll! state budget))
        [completions state] (rpc/take-completed state)
        [_ state] (rpc/take-diagnostics state)
        mine (first (filter #(= id (:dao.stream.v2.rpc/id %)) completions))]
    (cond
      mine {:state state :status :done :completion mine}
      (:terminal state) {:state state :status :terminal :reason (:terminal state)}
      :else {:state state :status :pending})))


(defn drain-outboxes
  "Take and discard both RPC outboxes (N11). A blocking driver with one
   awaited call has no outlet for completions it did not ask for or for
   diagnostics; leaving them in the state retains their payloads. Every
   exit of the driver stores a drained state, including the exits that
   never reach call-step: a request refused at the writer completes at once
   with its :args attached, an invalid request appends a diagnostic with
   them, and an allocation failure loses every outstanding request into the
   completion outbox."
  [state]
  (let [[_ state] (rpc/take-completed state)
        [_ state] (rpc/take-diagnostics state)]
    state))


(defn retire-call
  "Give up on the call awaiting `id` (N6). The id leaves :outstanding so a
   late response is classified unsolicited and dropped; a still-unsent
   envelope for it is abandoned with `reason`, which completes it on the
   ordinary path — and that completion is drained here, not left for a next
   step that may never come. :next-id never moves."
  [state id reason]
  (drain-outboxes
    (cond-> (update state :outstanding dissoc id)
      (= id (get-in state [:unsent :id])) (rpc/abandon-unsent reason))))
```

and `completion-value` interprets one completion: an `ok` response yields its
value, an error response throws N9's error, a `:reason` completion throws
N9's loss. All four are host-neutral and tested on all three hosts over ring
buffers with `rpc/client-state` and a hand-turned `apply/dispatch-request`
(§4.1). `request!` with `nil nil` is the documented retry path: while
`:unsent` holds it "ignores the operation it is given" (`rpc.cljc:241-242`).

Diagnostics are drained and dropped. `rpc/take-diagnostics`' docstring is
explicit that an owner who never publishes grows the vector for the session's
lifetime; a blocking call has no outlet for them, so dropping is the honest
choice and is said in the docstring. `retire-call` exists for the same
reason: r1 retained timed-out entries "so the next step discards them", which
`rpc/handle-event` already does for any id not outstanding, and `:outstanding`
holds `{:op :args}` — for a put, the whole payload — per timeout until the
attachment died. Retention bought nothing and leaked. **`drain-outboxes` is
r3's sibling of that fix**: r2 drained only inside `call-step`, and the
immediate-refusal exits of `call!` never reach it, so `:completed` grew by one
`{:op :args}` per refused request. Same leak, other vector; the rule is now
N11 and holds at every exit rather than at the ones that happened to poll.

`call-step`'s per-id filter is the shape of a blocking loop with one awaited
call. A non-blocking remote handle — the stepped client the deleted
`dao.jing.implementation-plan.md`'s Decision 3 sketched (`request-put` /
`request-get` / `request-materialize` / `step` / `abandon`, `c3b606f^:467-530`)
— needs multi-id dispatch and per-materialization records, and would not
reuse this step as-is. That handle is owed to the async hydration work, not
to v1's deletion (§8); building it here would be the large plan the brief
warned against.

### D4 — no rebind; a remote handle is opened, used, and closed

`yin.repl.v2` rebinds because an operator's connection has a life beyond one
request. A `:dao.jing/remote` handle does not: `query/open-published!` opens
one per manifest and `close-published!` closes it. After a terminal reason
every call throws with that reason (N8), and the caller decides whether to
open a new coordinate. Reattachment policy and the retained-envelope
abandonment `driver.cljc:211-221` reasons about are therefore out.

### D5 — `default-handlers` does not change; the server is `dao.stream.v2.serving` with an inbound step that owns its response outcomes

Correction 8's measurement: the map is already what `apply/dispatch-request`
consumes. What serves it is `serving/make-serving` with the one hook it
provides for exactly this — `:inbound-step`, "an explicit application
interpreter run by this driver for non-terminal per-attachment traffic
events". **r1's step ignored every append outcome; it now handles all of
them (S5):**

```clojure
(def non-portable-result-code :dao.jing.remote/non-portable-result)

(defn- portable-response
  "The response as it will cross the wire. A handler result outside the
   portable domain cannot be encoded; the client must learn that as a
   correlated error rather than as a timeout."
  [response]
  (if (transit/portable-value? response)
    response
    (apply/error-response (apply/response-id response)
                          non-portable-result-code
                          "Handler result is outside the portable value domain")))

(defn- inbound-step
  [handlers]
  (fn [session envelope]
    (when (= rpc-ws/payload-event (get envelope rpc-ws/envelope-event-key))
      (when-let [response (apply/dispatch-request
                            handlers (get envelope rpc-ws/envelope-value-key))]
        (let [result (stream/append! (:socket-handle session)
                                     (portable-response response))]
          ;; ok: delivered. Anything else — closed, transport-error, a full
          ;; the JVM edge excludes by nature, or an invalid-value that the
          ;; substitution above makes impossible — means this attachment can
          ;; no longer be answered; retiring it makes the loss observable to
          ;; the client as a terminal lifecycle rather than as silence.
          (when-not (= :dao.stream/ok (:dao.stream/outcome result))
            (stream/close! (:socket-handle session))))))))
```

`dispatch-request` returns `nil` only for an uncorrelatable malformed request,
which is dropped; unknown ops and handler throws become error responses (H5).
The retirement is what `dao.stream.md` *Surfaces* prescribes for a boundary
that can no longer deliver: "its one permitted action is closing the host
resource it itself holds". The serving composition observes the terminal
event on its own traffic cursor and removes the session; the client's next
step sees `/detached` and throws N8. The portable domain therefore binds in
both directions — N7 outbound, S5 inbound — and `dao.jing.md` says so (§9).
This remains a composition **for the JVM host, and says so**: `dao.stream.md`
*Surfaces* permits an interpreter composed for a particular medium provided it
does not claim to be contract-generic. A host whose sends can answer a
transient `full` would need `yin.repl.v2.serve`'s `:pending-response`
retention; the JVM edge excludes `full` after establishment (`ws.jvm:8-10`),
so nothing here needs it.

The rest of the composition is `yin.repl.v2.serve`'s, minus the shell: a
capacity-1 service stream never appended to (it anchors the served identity),
a 1024-element portable control medium, eight capacity-1 host-value handoff
slots, a 256-element lifecycle medium for `listen!`'s deposits, a per-
attachment 8192-element portable traffic medium with its cursor minted before
the acknowledgement, and `dao.stream.v2.ws.jvm/listen!` as `:start-endpoint!`
with `ws/accept-connection!` as `accept!`. A daemon **ticker thread** calls
`serving/step!` every `:tick-ms` (default 1) until `stop!`. That thread is
host policy inside a `#?(:clj …)` constructor whose caller owns `stop!`; it is
required, because the JVM client blocks in the caller's thread and a same-
process test cannot step the server from there. The server's own send seam is
http-kit's `-send` (`ws.jvm:251`), which enqueues; Phase 0 concerned the
client edge.

### D6 — `dao.jing.coordinate` survives unchanged, byte for byte

Its contract is `(remote/connect-content! url (or options {}))`, and both
arities keep their signatures and meaning (K1). Correction 1 confirms the
cljd build already excludes the `:dao.jing/remote` branch and `validate-
remote!`, so no spelling changes either.

### D7 — the URL stays a URL, parsed by a public portable function, with a fixed identity and a default path

The coordinate's `:url` is a string, pinned by K1, `stigmergy_test` and
`dao.jing.md:240`. A v2 descriptor needs `:ws/host`, `:ws/port`, `:ws/path`
and a `:dao.stream/identity`. `content-descriptor` derives them:
`ws://host[:port][/path]`, default port 80 as the WebSocket scheme's, an
absent path → `content-path` `"/jing"` (an explicit `/` stays `/`, the rule
`yin.repl.v2.connect/repl-target` applies), identity the constant
`service-identity` `"dao.jing.remote/content"`. The identity is only gated
locally (`ws/descriptor?` requires a string); the wire carries host, port and
path, and the serving side checks its own descriptor against itself
(`serving.cljc:231-232`), so a fixed constant is correct and matches
`yin.repl.v2.connect/service-identity`'s precedent. `wss://`, a bracketed
IPv6 authority, a missing host and a non-positive port throw before any
socket, as `parse-url` reports them.

The function is **public**, so its portable test needs no `#'` seam; it is
`dao.jing.remote`'s own twenty lines, not a require of
`yin.repl.v2.connect`: `dao.*` does not depend on `yin.*`, and `parse-url`
carries REPL rules (`daostream:` prefix, `/repl` default) that do not belong
here. Lifting the generic part into `dao.stream.v2.ws` beside
`canonical-path?` would serve both and is owed nowhere (§8).

### D8 — `dao.jing.dht.node` is a separate plan, and its work is on the decode side

Two namespaces under `dao.jing` are listed together in `dao.stream.md` because
of their parent, not their transport. `dht.node` is a UDP peer with no
DaoStream in it; its only v1 dependency is `dao.stream.transit`'s codec
(correction 8), so splitting is right and this plan makes **no edit under
`dao/jing/dht`**.

r1 called the second plan "a require swap". It is not. v1 `dao.stream.transit`
delegates straight to cognitect; `dao.stream.v2.transit` runs
`ensure-portable!` on **both** `encode` and `decode` (`v2/transit.cljc:100,
121`), and the portable domain excludes the tagged values cognitect decodes
happily — uuid, bigint, bigdec, uri, quoted, link. The outbound direction is
the easy check (every map `dht.node` builds is keywords, strings, safe
integers and content payloads). **The inbound direction is the work**: a
datagram from any peer carrying one of those tags would newly throw inside
`decode`, in the receive path, and the plan that swaps the require has to
decide what the node does with a datagram it cannot decode — drop it as v1
drops an oversize one, most likely — and pin that with a hostile-datagram
test. That is a small plan, but it is a plan about a wire, not a require.

---

## 4. Phases 0 and 1

### 4.0 Phase 0 — repair the JVM send seam — **DONE, `3228d0e`**

Committed 2026-09-10 ahead of the migration, on its own, as this section
planned. What follows describes **what shipped**, not what was drafted: three
review rounds changed it in two respects, and a plan that misdescribes the
commit it produced is worse than no plan. The divergences are marked.

`src/clj/dao/stream/v2/ws/jvm.clj`, and a new
`test/dao/stream/v2/ws/jvm_test.clj`. No public signature changed and no
consumer moved.

**Built**

- The client socket view is extracted from `connect!` into a public
  `client-socket [connection adapter]` returning the `{:send! :close!}` map,
  so it can be driven against a controlled `java.net.http.WebSocket` without
  a network. `connect!` calls it.
- `:send!` (J1, J2): no socket yet → `false`. Otherwise the send is chained
  behind the connection's `:pending` future with `thenCompose` and the
  submitter returns at once. The tail is read, the successor built, and
  `:pending` installed under `(locking connection …)`. The `swap!` that
  installs it is inside that lock and does the storing only: atomicity comes
  from the monitor, not from `swap!`, because an unguarded `swap!` whose body
  built the successor would issue a second `sendText` on retry — the
  overlapping send `java.net.http.WebSocket` fails with
  `IllegalStateException`.
- Failure after acceptance (J3, J5): the completion observer is registered
  **after leaving the monitor**, and failure is a **once-only connection
  transition** claimed under the same lock that serializes submission. The
  first claimant deposits `:ws/error`, aborts the socket, then deposits the
  terminal `:ws/closed` 1006. `ws.cljc:285-294` guards the terminal deposit,
  so a racing `onClose` cannot duplicate it.

  > **Diverges from the draft, twice.** The draft registered `whenComplete`
  > inside the lock and reset `:pending` to a completed future afterwards.
  > Both were wrong. Every future in a chain completes exceptionally when its
  > predecessor does, so one lost socket reported once per queued send; a
  > deposit that itself fails re-enters through `close!` and reported again;
  > an already-exceptional future runs its observer inline, so registering
  > under the lock held the monitor across the adapter's deposits; and the
  > reset sat outside the submission lock where it could overwrite a newer
  > tail and resurrect a dead chain. A failed connection now stays terminal
  > and there is no reset.

- A failed connection accepts no further chaining, and `send!` answers
  `{:dao.stream/outcome :dao.stream/closed}` (J6).

  > **Diverges from the draft.** The draft left a late `send!` to be caught by
  > the handle's own `:closed` phase. That is usually true but not always: an
  > append can read the open phase before the failure claim and reach the seam
  > after it. Answering `false` there would mean `:dao.stream/full`
  > (`ws.cljc:101`) — retryable backpressure for a socket that is never coming
  > back, leaving RPC holding an unsent request. `send-result` accepts outcome
  > maps (`:102`), so the seam says `closed` itself.

- `:close!` chains `sendClose` behind `:pending` the same way, so a close
  cannot race an in-flight text send into an `IllegalStateException`, and
  answers `nil` whatever the state (J4).
- The namespace docstring says what the code does: acceptance is not
  delivery, and nothing here joins, parks or sleeps.

**Deleted** — the `.join` at `:158`.

**Proved** — `jvm_test.clj`, JVM only, no network and no clock: a reified
`java.net.http.WebSocket` whose futures the test completes by hand, with host
calls and adapter deposits recorded in **one ordered trace** so ordering is
asserted rather than inferred. Seven deftests, 21 assertions:

1. `send-returns-on-acceptance-and-serializes-behind-a-pending-future` (J1,
   J2) — two sends return `nil` at once; only the head reaches `sendText`;
   completing it releases the second in submission order.
2. `a-failed-send-reports-error-then-abort-then-closed-exactly-once` (J3, J5)
   — **three** sends queued, one exceptional completion, and the trace is
   exactly `[:send-text "one"] [:error!] [:abort] [:closed! 1006 …]`: once,
   though all three futures completed exceptionally, and the queued messages
   never reach a socket that is gone.
3. `a-failed-connection-accepts-no-further-sends` (J6) — a later `send!`
   answers the `closed` outcome map, a later `close!` answers `nil`, and
   neither touches the socket.
4. `close-waits-its-turn-behind-a-pending-send` (J2) — `sendClose` is chained
   behind a pending send on a *healthy* connection.
5. `an-inline-failure-reports-without-holding-the-submission-lock` (J5) — a
   `sendText` returning an already-exceptional future; the adapter records
   `(Thread/holdsLock connection)` inside both deposits and the test asserts
   **false** for each.
6. `teardown-that-re-enters-through-the-adapter-reports-once` (J5) — an
   `:error!` deposit that calls back into `close!` finds the connection
   already failed: no `sendClose`, no second report.
7. `before-open-send-answers-full` (J4) — **stays exactly as shipped**: no
   socket, `send!` answers `false`, `close!` answers `nil` and records
   `[1000 "bye"]` as `:close-request`, nothing is deposited. That is the
   behaviour N2 is narrowed to; nothing in this plan asks it to change.

Note that restoring the original `.join` would make these **hang**, not fail,
because the test deliberately holds the future open. That is why the proof is
a controllable incomplete future rather than a network timing test.

**Verified on the committed tree** — `clojure -M:test`: **1443 tests /
165370 assertions / 0 failures 0 errors**, which includes
`yin/repl/v2/host/jvm_test.clj`, `slice_test` and the `yin.repl.v2`
cross-host pair as the regression net for the consumers this repairs; none of
their assertions changed. Mutation-tested, one mutant per review finding:
dropping the once-only claim → 4 failures; chaining after failure → 1;
registering `whenComplete` inside the lock → 1; answering `false` instead of
`closed` → 1.

**Nothing in this section is owed.** J1–J6 are already carried by the commit
itself, distributed across three homes rather than one: the namespace
docstring states J1 and J3's shape (acceptance is not delivery; nothing joins,
parks or sleeps); `client-socket`'s own docstring states J2 and J5 with their
reasons (the `locking`-not-`swap!` discipline, the once-only claim, the
observer outside the monitor); and inline comments at the `send!` and `close!`
answers state J4 and J6. The seven tests pin all six. §9 records this rather
than asking for a further move. One line is still on the working tree,
uncommitted: the `jvm_test.clj` docstring note that a real `abort()` re-enters
`onError` and deposits a second `:ws/error`, which the scripted socket does
not model. It changes no behaviour and no assertion; it can land on its own
or with Phase 1.

### 4.1 Phase 1 — the portable core, v1 untouched

`src/cljc/dao/jing/remote.cljc`, `test/dao/jing/remote_test.cljc`. Behaviour-
neutral: nothing existing changes, three lanes stay green by construction.

**Build**

- Constants: `content-path`, `service-identity`, `traffic-capacity` 8192,
  `traffic-admission`, `default-connect-timeout-ms` 5000,
  `default-request-timeout-ms` 5000, `default-poll-interval-ms` 10,
  `non-portable-result-code`.
- Public `content-descriptor` (D7), `call-step`, `drain-outboxes`,
  `retire-call`, `completion-value` (D3), and an `await-established-step` —
  the D2 wait's body as one non-waiting advance returning `:established |
  :pending | :terminal`, so the wait is pinned without a socket. Requires
  added: `dao.stream.v2`, `dao.stream.v2.apply`, `dao.stream.v2.rpc`,
  `dao.stream.v2.rpc.ws`, `dao.stream.v2.transit`, `dao.stream.v2.ws` — all
  `.cljc`, all three hosts.

**Delete** — nothing.

**Prove** — new deftests in `remote_test`, unguarded, all hosts:

1. `content-descriptor-derives-a-servable-descriptor` — host, port, default
   and explicit path, fixed identity; `ws/descriptor?` holds; `wss://`, no
   host, bad port, IPv6, `http://` throw.
2. `call-step-completes-one-request-over-in-process-media` — `rpc/client-
   state` over two ring buffers with no decoder (bare responses are accepted,
   `rpc.cljc:299`); `request!`, one `call-step` → `:pending`; a hand-turned
   `apply/dispatch-request` over `default-handlers` on a memory store appends
   the response; `call-step` → `:done`, `completion-value` → the presence
   envelope. Then: an error response → throws N9's `{:operation :error}`;
   a bare `:dao.stream.v2.apply/detached` appended to the reader →
   `:terminal` with reason **when nothing is outstanding**; with an awaited
   call in flight the same event yields `:done` carrying a loss completion
   (N9), because the step reports the call's own fate before the
   attachment's. Both shapes are pinned. A completion for a foreign id →
   discarded, still `:pending`; a writer answering `full` once → `:unsent`
   retried on the next step.
3. `retire-call-drops-bookkeeping-and-a-late-response-is-unsolicited` —
   request id 0; `retire-call` with `:dao.jing.remote/timeout`; `:outstanding`
   is empty and `:next-id` is 1; append a response for id 0, request id 1,
   append its response; `call-step` for id 1 → `:done` with id 1's value, and
   id 0's response never surfaces. Repeat with the writer answering `full` so
   id 0 is retired while `:unsent`: the abandonment completion is drained by
   `retire-call` itself (`:completed` empty on return), and id 1 proceeds.
   This is N6's late-correlation pin, with scripted media and no clock.
4. `await-established-step-observes-only-lifecycle` — no event → `:pending`;
   a bare `/established` → `:established`; a bare `/detached` first →
   `:terminal` with reason; a response element before `/established` is
   consumed as a diagnostic and does not establish.
5. `immediate-refusals-leave-no-payload-behind` (N11) — a writer double
   answering `:dao.stream/invalid-value` on every append: three `request!`s
   with a `[address payload]` args vector each answer `request-undeliverable`,
   and after each `(drain-outboxes state)` the state's `:completed` and
   `:diagnostics` are empty and `:outstanding` is empty; the refused
   outcomes' `:dao.stream.v2.rpc/reason` is `:dao.stream/invalid-value` every
   time. Then an `invalid-request` (`op` not a keyword) → diagnostic appended
   → drained. Then swap the writer for a ring buffer: a fourth request
   completes normally through `call-step`. The assertion that matters is on
   the *stored* state: `(count (:completed state))` is `0` after every exit,
   never `n`.

Confirm `Testing dao.jing.remote-test` appears in the Node output.

## 5. Phase 2 — the swap

One phase: a v1 server cannot talk to a v2 client, so `connect-content!` and
the server constructor swap together with every fixture that pairs them.
**Requires Phase 0 (`3228d0e`, done) on the tree.**

### 5.1 `src/cljc/dao/jing/remote.cljc`

**Build**

- `#?@(:cljd [] :clj [[dao.stream.v2.ringbuffer :as ring] [dao.stream.v2.serving :as serving] [dao.stream.v2.ws.jvm :as jvm]])` — the
  JVM glue require in the spelling correction 1 requires. (`ringbuffer` and
  `serving` are `.cljc` and could go in the shared list; keeping the three
  together says which functions are the host composition.)
- Every JVM function below is spelled `#?(:cljd nil :clj (defn …))`
  (correction 1); `:clj` is written here for brevity only.
- `#?(:clj (defn call! [client op args] …))` — D1's loop over `call-step`,
  under `(locking (:lock client) …)`. **Every exit stores
  `(drain-outboxes state)` and nothing else** (N11): one private
  `settle!` that drains, `reset!`s the client's `:rpc`, and returns or
  throws, is the only way out of the function — **eight exits, not the seven
  first enumerated here**; the interrupt exit was found in review and is
  listed last. The exits, in order:
  `request!`'s immediate outcomes — `request-undeliverable` throws N9's loss
  with the append reason, `invalid-request` throws a defect map,
  `allocator-error` and `terminal` throw with their reason — each through
  `settle!`, so the completion `attempt-unsent` appended and the diagnostics
  `allocate-request`/`allocation-failure` appended leave with the throw
  rather than with the next call. `requested`/`pending-request` enter the
  loop with the allocated id. On the deadline: `retire-call` (which drains),
  `settle!`, throw N6. **On an `InterruptedException` from the loop's sleep:
  `retire-call` from the loop's latest state, `settle!`, re-assert the
  thread's interrupt flag, throw** — the eighth exit. On `:done`,
  `completion-value` after `settle!`; on
  `:terminal`, throw N9's loss after `settle!`. **The lock's scope is
  call-versus-call only** (N5); `close!` runs outside it and is safe by N10.
- `#?(:clj (defn close! [client] …))` — `stream/close!` on the handle. The
  once-only guard is `content-client`'s (C4).
- `#?(:clj (defn connect-content! …))` — D2 and D7: descriptor, traffic
  medium, cursor minted at `:dao.stream/newest` **before** `attach!`,
  `ws/make-attacher` with `jvm/connect!`, `attach!`, `rpc-ws/init-client` on
  the whole result, the establishment loop over `await-established-step`
  (budget 1, `:poll-interval-ms`, `:connect-timeout-ms`; on terminal or
  deadline `stream/close!` the handle, then throw), then
  `(content-client {:rpc (atom state) :handle h :attachment a :lock (Object.) :request-timeout-ms … :poll-interval-ms …} call! close!)`.
  A failed `attach!` outcome throws with the outcome map. The docstring
  states the mint-before-attach order and why, as `yin.repl.v2.connect/open`'s
  does ("the order is the point and is observable"), and states N2's limit
  in one sentence: a close before the socket opens does not tear down the
  JDK's establishment, so a stalled peer costs one held connection.
- `#?(:clj (defn serve-content! …))` — D5 with S5's inbound step. Returns
  `{:port p :stop! f :serving s :lifecycle l}`. `:bind-host` option, default
  `"127.0.0.1"` as `yin.repl.v2.serve/default-bind-host`; `:tick-ms` option.
- The `comment` block rewritten on `serve-content!` / `(:stop! server)`; the
  ns docstring rewritten (correction 4).

**Delete**

- `:11-12` the two v1 requires. `:115-122` the v1 `connect-content!` body.
  `:125-135` the v1 `comment` block. Every `rpc-ws/` and `rpc-client/` token.

### 5.2 `test/dao/jing/remote_test.cljc`

**Build** — `with-server` (`#?(:cljd nil :clj (defn- …))`) on
`remote/serve-content!` and `(:stop! server)`; the `Thread/sleep 100` goes
(D2's wait replaces it). URLs at `:38`, `:284`, `:295` become
`ws://127.0.0.1:` — loopback name resolution is host policy, and
`stigmergy_test:73` already pins the literal. New JVM deftests, each an
unconditional `deftest` whose body is
`#?(:clj … :cljd (is true "network tests are JVM-only") :cljs (is true …))`,
exactly as the six `network-*` tests are spelled (correction 1). Where a test
needs to hold the server's driver, it serves a handler map whose op blocks on
a `java.util.concurrent.CountDownLatch` the test owns; where it needs the raw
state, it derefs `(:rpc (:client handle))` — the test lives in the same
namespace and that is the seam.

3. `request-timeout-retires-the-call-and-the-client-recovers-once-released`
   — `serve-content!` over
   `{:gated/op (fn [] (.await latch) :late) :fast/op (fn [] :now)}`; a client
   with `:request-timeout-ms 50`. Three `call!`s in a row throw `:timeout-ms`
   (the first stalls the driver on the latch; the next two are deposited and
   never dispatched — S4 in action, which is the point). After each,
   `:outstanding`, `:completed` and `:diagnostics` of the client's RPC state
   are empty (N6, N11). Then `(.countDown latch)`, and a fourth `call!`
   `:fast/op` with the default 5000 ms deadline answers `:now`: the server
   drains the three stalled requests, their late responses are classified
   unsolicited and dropped, and the client is usable. No narrow timing
   window: the only bound is the generous fourth deadline.
4. `a-non-portable-handler-result-is-a-correlated-error-not-a-timeout` —
   serve `{:bad/op (fn [] 9007199254740992)}` (outside the safe-integer
   domain, `transit.cljc:15`); `call!` throws N9's error with code
   `:dao.jing.remote/non-portable-result` well inside the deadline (S5). Then
   a second op on the same attachment still answers, proving the socket was
   not retired for a refusal the step could correlate.
5. `connect-throws-on-a-refused-endpoint` — bind a `java.net.ServerSocket` on
   port 0, read its port, close it; `connect-content!` to that port with
   `:connect-timeout-ms 2000` throws with reason
   `:dao.stream.v2.apply/transport-error` (`jvm/connect!`'s `whenComplete`
   error → `closed! 1006` → `:ws/transport-error`), and no handle escaped.
   This is N2's real establishment-failure pin; `network-invalid-url-test`
   stays as the validation pin it always was.
6. `connect-times-out-against-a-peer-that-never-completes-the-handshake` —
   a `ServerSocket` that accepts and never writes; `:connect-timeout-ms 200`;
   `connect-content!` throws `{:url :timeout-ms}` within a bound comfortably
   above 200 ms, and no handle escaped. **That is all this test asserts,
   because that is all the transport guarantees (N2)**: no cleanup claim, no
   EOF claim, no statement about the JDK-held connection. The test closes
   its own accepted socket and its `ServerSocket` in a `finally`, since
   nothing else will; the client-side JDK connection it provoked cannot be
   closed by the test and persists for the process — the leak N2 names,
   paid once per run of this test and stated in its docstring. Returning a
   handle immediately still fails this test and #5.
7. `close-during-a-blocked-call-makes-the-call-throw-and-closes-once` — the
   latch server again; `call!` `:gated/op` in a `future` with a 5000 ms
   deadline; from the test thread `jing/close!` the handle; the future's call
   throws N8/N9 with a terminal reason (`/detached`), `@(:closed-atom
   handle)` is true, a second `jing/close!` is a no-op; release the latch.
   This pins N10 and states the lock's scope in code.
8. `serve-content-refuses-a-bound-port-and-stop-is-idempotent` — a second
   server on the first's port throws (S3); `(:stop! server)` twice returns
   without error (S2).
9. `repeated-refused-requests-retain-nothing-and-the-client-recovers` (N7,
   N11) — over a real server, three `call!`s of `:jing/put-content` with a
   payload outside the portable domain (a `java.lang.Object` — never
   encoded, never sent); each throws with reason
   `:dao.stream/invalid-value`, and the three ex-data maps are equal to one
   another apart from `:request-id`, which advances by one each time (the
   error information is unchanged by the leak fix). After each, the stored
   state's `:completed`, `:diagnostics` and `:outstanding` are empty. Then a
   portable put and a get round-trip normally.

**Delete** — the `:15` require and its `rpc-ws/` sites (`:40`, `:44`, `:285`,
`:292`, `:296`, `:302`); the `Thread/sleep`s at `:41`, `:286`, `:297`; the ns
docstring's v1 prose.

**Prove** — the six `network-*` deftests pass with **only** these edits: the
loopback literal in `with-server` and in `network-file-restart-test`'s two
URLs, and `network-file-restart-test`'s own server start/stop and sleep lines,
which are fixture code inlined into the test body. No assertion in any of the
six changes; if one must, the transport changed behaviour the contract tests
do not see, and the phase is wrong. The thirteen contract deftests and Phase
1's five are untouched.

### 5.3 `test/dao/space/stigmergy_test.clj`

`:32` require → none needed (`serve-content!` lives in `remote`, already
required at `:28`); `:70` → `(remote/serve-content! (remote/default-handlers store) (+ 10000 (rand-int 50000)))`;
`:73` and `:76` unchanged in text (`(:port srv)`, `(:stop! srv)` are the new
map's keys too). All five scenarios pass unmodified — this file is the
end-to-end proof: agents on memory-logs, publish, observer materialization
into a file store, and query over published manifests through both the file
coordinate and the remote one, agreeing (`transport-transparency`).

### 5.4 Docs, in-phase

- `docs/design/dao.jing.md` `:320` — replace "currently on v1, awaiting
  `dao.space`'s plan…" with a `dao.jing.remote` paragraph that carries, each
  in a sentence: the JVM constructor and server; D1's ruling (a blocking
  driver as host policy over a non-waiting step; cadence and deadline are
  options); D2's establishment wait, what a failed open guarantees (handle
  closed, nothing escapes) and N2's limit (no teardown of a stalled
  establishment; one JDK connection held per such attempt), and the
  cursor-before-`attach!` order with its reason; D7's URL form and its
  refusals; H4's wire envelope; N5's one-awaited-call scope and N10's close
  scope; N6 (a timeout retires the wait, not the remote execution); N11
  (bookkeeping bounded by the one call in flight, drained at every exit);
  N7 and S5 (the portable domain binds both directions); N8 (no rebind);
  D3's diagnostic-discard policy; S2's shutdown semantics; S4's
  single-threaded handler limit. *Open items* gains the stepped-client entry
  (§9).
- `docs/design/dao.stream.ws.md` *Deferred* — one new item, the
  establishment-cancel gap, in the words §9 gives it. It is a transport
  property, so it lives with the transport's other deferred items, not in
  `dao.jing.md`.
- `docs/design/dao.stream.md` `:803-805` — the consumer list drops "remote
  adapter", keeping "`dao.jing`'s DHT node".
- `docs/dao.space.stigmergy.md` `:9` — "served with `default-handlers` … RPC
  operations" → served by `serve-content!`; `:244-245` stays true.

### 5.5 Verification

```
bb test:clj
bb test:cljs      # confirm "Testing dao.jing.remote-test" in the node output
bb test:cljd
clj -M:cljs -m shadow.cljs.devtools.cli compile demo
```

Residue greps, the closure criterion:

| grep | files | before | after |
|---|---|---|---|
| `\.join` | `src/clj/dao/stream/v2/ws/jvm.clj` | 1 | 0 (Phase 0, done) |
| `rpc-ws/\|rpc-client/\|dao\.stream\.rpc` | `remote.cljc` | 7 | 0 |
| same | `remote_test.cljc` | 8 | 0 |
| same | `stigmergy_test.clj` | 3 | 0 |
| `dao\.stream\.v2\.ws\.jvm` outside a `#?@(:cljd []` form | `remote.cljc` | — | 0 |
| `reset! (:rpc` / `swap! (:rpc` outside `settle!` | `remote.cljc` | — | 0 (N11: one exit path) |

The cljd lane is the canary for correction 1: a `#?(:clj …)`-spelled JVM
require fails Dart compilation of `dao.jing.remote`, which every cljd test
file imports. Phase 2's #3 is **not** a reliable canary for Phase 0, and the
draft's claim that it was is withdrawn: completing a `sendText` does not
require the server to dispatch the handler, so a stalled driver does not by
itself hold the send future open. Phase 0's own controlled-incomplete-future
tests (`3228d0e`'s `jvm_test.clj`) are the regression pin for the join, and
restoring it would hang them rather than fail them. Phase 2's #6 passes
against the shipped seam and claims nothing the seam does not do.

---

## 6. Host matrix

| | clj | cljs (Node) | cljd |
|---|---|---|---|
| Phase 0, `dao.stream.v2.ws.jvm` send seam | repaired (`3228d0e`), tested without network | n/a — Node edge never joined | n/a — Dart edge never joined |
| `remote.cljc` portable core (Phase 1) | built, tested | built, tested | built, tested |
| `connect-content!`, `call!`, `close!`, `serve-content!` | built, tested | excluded (`#?(:cljd nil :clj …)`) | excluded; the glue require spelled `#?@(:cljd [] :clj …)` |
| `default-handlers`, `content-client` | unchanged | unchanged | unchanged |
| `remote_test` contract deftests (13 + 5 new) | run | run | run |
| `remote_test` network deftests (6 + 7 new) | run | `(is true)` | `(is true)` |
| `dao.jing.coordinate` | unchanged | unchanged | unchanged |
| `index_test:616` | unchanged | unchanged | unchanged |
| `stigmergy_test.clj` | fixture edited | n/a (`.clj`) | n/a |
| `public/demo.html` | — | compiled each phase; `dao.jing.remote` is not on its path | — |

---

## 7. Transport tests versus contract tests

**Contract tests** (thirteen, all hosts, untouched): every deftest that
builds `content-client` over a fake `call-fn` or calls a `default-handlers`
map directly. They pin H1–H4, C1–C6 and know nothing about a socket.

**Transport tests** (six + seven new, JVM): `network-connect`,
`network-materialize-and-get`, `network-two-clients-share`,
`network-invalid-url`, `network-file-restart`, `network-presence-envelope`,
plus §5.2 #3–#9. They pin N1–N3, N6, N7, N10, N11, S2, S3, S5 through
`dao.stream.v2.ws.jvm`, and they are the ones that move or arrive.

**Phase 1's five** sit between: they pin D3's step and D2's wait (N6's
retirement and late-correlation rule, N8's terminal rule, N9's decode, N11's
drain at the refusal exits, the `full` retry, establishment observed from
lifecycle alone) with no host at all. **Phase 0's seven** are transport tests
with a controlled socket and no network.

---

## 8. Boundary — built here, and what is left owing by namespace

Built here: Phase 0's repair of `dao.stream.v2.ws.jvm` (done);
`dao.jing.remote` on v2 (both halves), its tests, the two fixtures that pair
them, four documents.

| owed | by | where it is recorded |
|---|---|---|
| **The establishment-cancel gap in the JVM edge**: `close!` before `onOpen` records a `:close-request` and does not cancel or abort the pending establishment, so a disconnect while connecting leaves the JDK connection held. Live today for `yin.repl.v2`'s `(disconnect)` during `:connecting`; live for `dao.jing.remote` on a connect timeout against a stalled peer (N2). What the repair needs to know is in §9 | `dao.stream.v2.ws.jvm` — transport work, on its own ticket, with `yin.repl.v2` as its first consumer; **not** `dao.jing.remote`, which has no handle to reach it | `dao.stream.ws.md` *Deferred*, written in Phase 2's doc step (§5.4) |
| `dao.jing.dht.node` and `node_test` off `dao.stream.transit`, including the inbound decode policy for non-portable datagrams | its own plan (D8) | `dao.stream.md`'s consumer list keeps the DHT node |
| a non-blocking remote handle — the stepped client with multi-id dispatch, and the consumer change it forces on B-tree hydration | `dao.data.btree.md` §5.4 / `dao.jing.md` *Open items* | §9 moves Decision 3's sketch there |
| deletion of `dao.stream.rpc.*`, `dao.stream.ws`, `dao.stream.transit`, v1 `yin.repl` | the v1 deletion, per `dao.stream.md` | already recorded there |
| whether the Dart edge's close-before-connect (`dart.cljd:121-137`, a stored request applied on connect) shares the gap — it cancels nothing either, though Dart's `WebSocket.connect` future is its own object | nobody; noted for the owner beside the JVM row | not this plan's host; the JVM is the only host `dao.jing.remote` runs on |
| a generic URL→descriptor parser shared with `yin.repl.v2.connect` | nobody | optional; two private parsers is the cost |
| `dao.stream.v2.ws.jvm/listen!` deposits `:yin.repl.v2.endpoint/*` codes from a `dao.*` namespace | nobody; noted for the owner | a naming leak, harmless to this plan; Phase 0 did not touch `listen!` |
| `yin.repl.v2.implementation-plan.md:728` and `src/cljc/yin/vm/docs/yin.repl.v2.md:122` say `dao.stream.rpc.*` "keeps serving `dao.jing.remote`" | the owner — both describe their own slice's boundary at the time | left as written; they become historical the moment Phase 2 lands |

Explicitly not planned, per the brief: `yin.vm.*`, `dao.runtime`, `yin.io`,
the demo surfaces, the v1 transports — and, per the owner's r5 decision, the
JVM establishment lifecycle.

---

## 9. What this plan carries that no other document does

Every item has a home; each is moved before this file is deleted.

- **H4, the wire vocabulary** — `:jing/put-content` answering
  `:inserted`/`:present`, `:jing/get-content` answering exactly
  `{:found? boolean :value v}` — into `dao.jing.md`'s *Implemented surface*
  paragraph on `dao.jing.remote` (§5.4). Today it lives in two docstrings and
  a test.
- **The operational rules §5.4 enumerates** — D1's ruling and conditions,
  D2's wait, what a failed open guarantees and N2's limit, **D2's
  cursor-before-`attach!` order and the hang it prevents**, D7's URL rules,
  N5/N10's lock and close scope, N6's retire-not-cancel, **N11's
  drain-at-every-exit**, N7/S5's two-direction portable domain, N8's
  no-rebind, D3's diagnostic discard, S2's shutdown, S4's single-threaded
  handler — into the same paragraph, one sentence each. The cursor order and
  N2's limit are also stated in `connect-content!`'s docstring, where the
  next reader of the code will look first.
- **The establishment-cancel gap, and what three review rounds learned
  about closing it** — into `dao.stream.ws.md` *Deferred*, as one item,
  carrying the substance a successor needs and nothing that was only ever a
  plan:
  - On the JVM edge, `close!` before the socket exists records a
    `:close-request` and returns (`jvm.clj:168-173`); the pending
    `buildAsync` future is untouched, and the request is applied only by a
    later `onOpen`. The `HttpClient` that `connect!` builds carries no
    connect or request timeout, so a peer that accepts TCP and stalls the
    upgrade holds the client-side connection for the process lifetime.
  - Cancelling the `buildAsync` future would complete it exceptionally and
    run the observer `connect!` already registers — which deposits the
    terminal once through `ws/closed!`'s guard — but `CompletableFuture`
    cancellation does not propagate to the stage producing the socket, so
    the JDK exchange runs on regardless; the future must be captured and
    the connection marked terminal under the submission monitor and the
    cancel issued **after** releasing it, or a completed future's inline
    observer deposits under the lock, the hazard `3228d0e` removed.
  - The check for a prior close and the install of the socket in `onOpen`
    must be **one transition under the same monitor** as `close!`'s, with
    the abort of a rejected socket performed outside it; a check followed
    by an unguarded install (`jvm.clj:186` today) lets a `close!` land
    between them and leaves a socket installed on a failed connection that
    nothing ever aborts. A sequential close-then-open test cannot detect
    this; the pin has to hold the monitor from the test thread and prove
    the `onOpen` thread blocks on it.
  - `send!`'s no-socket branch returns `false` (`full`) unconditionally
    (`jvm.clj:167`); a repair that marks a never-opened connection failed
    must make that branch consult the failure flag, or a late append is
    told to retry against a socket that will never exist.
  - The shipped `before-open-send-answers-full` builds its connection with
    `:future nil`; a repair that cancels on close must give it a watched
    future.
  - Even repaired, the residual is **unbounded for a stalled peer and
    caller-owned**: a late `onOpen` can be aborted, but a peer that never
    completes the handshake never triggers one, and the JDK offers no
    handle to bound the wait.
  The home is the transport document because the property is the
  transport's; `yin.repl.v2` is its first consumer and `dao.jing.remote` its
  second, and neither can fix it from where it stands.
- **J1–J6, the JVM send seam's contract — already carried by `3228d0e`**,
  in three homes rather than one, as §4.0 records: the namespace docstring
  (J1, J3's shape), `client-socket`'s docstring (J2, J5 with reasons), and
  the inline comments at the `send!` and `close!` answers (J4, J6); the seven
  tests pin all six. Nothing further to move.
- **Decision 3's stepped-client sketch** from the deleted `dao.jing.
  implementation-plan.md` (`c3b606f^:467-530`) — the `request-put` /
  `request-get` / `request-materialize` / `step` / `abandon` shape, that it
  needs multi-id dispatch rather than `call-step`'s per-id filter, and the
  reason it exists (a remote store on a host that cannot wait is a client the
  caller steps) — into `dao.jing.md` *Open items* as one paragraph under
  *Async hydration*, pointing at `dao.data.btree.md` §5.4. The verify-hop
  lifecycle table is not moved; it is retrievable from history and belongs
  to the plan that builds it.
- **Correction 1's measured fact** — `#?(:clj …)` requires reach the Dart
  compiler as imports, bodies are host-evaluated and not emitted, and the
  `#?@(:cljd [] :clj [[…]])` splice is the remedy — **is already recorded**
  in the project memory `project-cljd-clj-reader-conditional`, with this
  plan's measurement appended on 2026-09-10. That is its home; nothing is
  left to the owner's call.

---

## 10. End condition

- **Phase 0 landed first, on its own commit (`3228d0e`)**: `dao.stream.v2.ws.jvm`
  has no `.join`, J1–J6 are pinned without a network, and `yin.repl.v2`'s
  existing JVM tests pass unchanged. It is the only transport prerequisite.
- `dao.jing.remote` requires `dao.jing`, `dao.stream.v2`,
  `dao.stream.v2.apply`, `dao.stream.v2.rpc`, `dao.stream.v2.rpc.ws`,
  `dao.stream.v2.transit`, `dao.stream.v2.ws`, and under
  `#?@(:cljd [] :clj …)` `dao.stream.v2.ringbuffer`, `dao.stream.v2.serving`,
  `dao.stream.v2.ws.jvm`. No `dao.stream.rpc.*`, no `dao.stream.ws`, no
  `dao.stream`.
- `content-client`, `default-handlers`, `dao.jing.coordinate`, the thirteen
  contract deftests, `index_test:616` and `before-open-send-answers-full`
  are unchanged.
- `call!` has one exit path, and it drains (N11); the stored RPC state after
  any sequence of calls, timeouts and refusals holds no completion, no
  diagnostic and no retired id.
- The six network deftests pass with fixture edits only; all five
  `stigmergy_test` scenarios pass unmodified.
- `docs/design/dao.jing.md` carries every §9 item that is `dao.jing.remote`'s;
  `docs/design/dao.stream.ws.md` *Deferred* carries the establishment-cancel
  gap; `docs/design/dao.stream.md`'s consumer list reads "`dao.jing`'s DHT
  node".
- Three lanes green, demo compiled, §5.5's greps at zero.
- Nothing is owed to a later plan except D8's DHT node, the non-blocking
  handle already listed under `dao.jing.md` *Open items*, and the
  establishment-cancel gap, which is owed by `dao.stream.v2.ws.jvm` and not
  by any plan of `dao.jing`'s.

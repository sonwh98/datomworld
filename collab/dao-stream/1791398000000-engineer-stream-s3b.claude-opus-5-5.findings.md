# Track B Slice S3b: Implementation Engineer completion report

Engineer: claude-opus-5-5. Date: 2026-10-08.
Tree: worktree `/Users/sto/workspace/datomworld-stream-s3a`, branch
`stream-crossmachine-s3b`, base master `802d9ee2`. Nothing committed
(per brief: await adversarial review and Architect sign-off). `collab/`
not staged.

Spec: `collab/1791398000000-architect-stream-s3b-spec.claude-fable-5-1.findings.md`.

---

## 1. What changed, per file

### `src/cljc/dao/stream/remote_channel.cljc` (D1, D2, D4, D5, D6, D7)

- **D1 table validation.** `valid-table?` replaced by `valid-entry?` +
  `table-refusal`. An entry must declare a non-empty set within
  `#{:reader :writer}` that the handle's own natures cover. Refusal is
  `::invalid-table` with `:detail {:identity id :surface S}` (or
  `{:table t}` / `{:names n}` for a non-map table / name map).
- **D2 identities dial.** `dial` takes exactly one of `:name` or
  `:identities` (non-empty vector of distinct ids); otherwise
  `{:status :refused :reason ::invalid-target}`. With `:identities` and
  `:now`, `dial` steps the unattached ws-project dial once at `now`
  (records `now` on the link before `establish!`), then `dial-attach!`
  the first identity and `dial-reflect!` the rest, all inside `dial`.
  Answers `:attached` with `:handles {id handle}`, `:handle nil`,
  `:since now`; or `:lost` with the attacher's outcome, partial
  handles and the connection closed. Name dials also populate
  `:handles` on attach. New accessors: `handle` (1- and 2-arity),
  `handles`. `close!` closes every handle in `:handles` (and `:handle`).
- **D4 drain.** `production-bounds` gains `:drain-grace-ms 0`
  (validated non-negative integer, composition error otherwise, before
  anything listens; docstring cross-check bullet added). `stop!` is
  2-arity `(stop! server {:ended? bool})`; `:stop` is `{:since :released
  :outcome :ended?}`. Stopping state machine: `begin-stop` (answering
  pass, `endpoint-stop!`, `:since`; releases at once when drain is 0 or
  no sessions), `drain-stop` (lifecycle, answering pass, stragglers;
  releases when sessions empty or `now - since >= drain-grace-ms`),
  `release-stop` (`release!` then `unbind!`, `:released now`),
  `continue-stop` (S3a, grace now measured from `:released`). `release!`
  closes every session with `ws/close-ended!` first when `:ended?`.
  `observe` answers `:confirmed` only once `:released` is set; the host's
  `:stopped` while draining is `::host-stopped` after release.
  `lifecycle-lost` while stopping and not yet released now releases
  (and unbinds) before completing `::unconfirmed` (see deviation 6).
- **D5** `detach!`: closes only the dialed ws handle, marks
  `:detaching? true`; identity on no connection / already detaching /
  lost / closed / refused.
- **D6** `serve` passes `(or (:bind-host spec) (:host spec))` as the
  `:bind!` request's `:bind-host`; `descriptor-of` unchanged.
- **D7** `:diagnostic-count` (init 0, incremented by `diagnose`);
  `attachment` accessor (nil before a connection and after `close!`);
  synchronous `::bind-failed` carries `:detail nil`.
- Namespace docstring: the "Writing through a reflection" paragraph
  (D3, five cases + rule); `dial`/`serve`/`serve-step`/`stop!`
  docstrings updated.

### `src/cljc/yin/repl/connect.cljc` (D2, D3, D5, D9)

- Requires now: `clojure.string`, `dao.data`, `dao.stream`,
  `dao.stream.remote-channel`, `dao.stream.rpc`, `yin.repl.host.common`
  (no `ws`, `ws-project`, `ringbuffer`).
- `parse-url` is 1-arity; success is `{outcome-key ::parsed spec-key
  {:host :port :path}}`. `descriptor-key` -> `spec-key`
  (`:yin.repl.connect/spec`). The `ws/descriptor?` gate and
  `service-identity` deleted. All other refusals and texts unchanged.
- `open {:url :host :now}` dials `remote-channel/dial` by
  `[requests-identity answers-identity]` at `now`. `:lost` renders
  `attach-failed` via `attach-outcome-message`; `:refused` renders the
  `no-host-adapter` text (the `host-common/adapter?` pre-check is kept).
  Connection value `{:url :spec :host :dial :status :detached-by}`.
- `step!` is `(step! connection now)` and returns the connection with
  the stepped dial (1-arity removed).
- `close!` = `remote-channel/detach!` on the dial, then `:detached-by`
  and the `:closing` rule as before.
- `reattach` is `(reattach connection client now)`: `remote-channel/close!`
  on the old dial, fresh identities dial, `rpc/rebind`.
- `summary`: `{:connected? :url :path :status :dial-status :attachment}`
  (`:identity` dropped). Not-found text reads `(:path (:spec connection))`.
- Removed: `traffic-capacity`, `traffic-admission`, `service-identity`,
  `mint`, `remote-descriptor`, `attacher`, `fresh-dial`, `attach-pair`.
  Docstring points to remote-channel's "Writing through a reflection".

### `src/cljc/yin/repl/serve.cljc` (D1, D4, D6, D7, D8)

- Requires now: `dao.data`, `dao.stream`, `dao.stream.remote-channel`,
  `dao.stream.ringbuffer`, `dao.stream.rpc`, `yin.repl`,
  `yin.repl.connect`, `yin.repl.host.common` (as spec 3.1 lists).
- Endpoint value: `:status :spec :path :server :server-seen :requests
  :answers :requests-cursor :pending-answer :pending-successor :repl
  :bind-note :outbox :step-moved?`. All listed internals gone.
- `serve!` keeps the REPL pre-checks in order (advertised-host-required,
  ephemeral-port-unsupported, no-websocket-package), then
  `remote-channel/serve` with the two-entry table (`#{:writer}` /
  `#{:reader}`), spec `{:host advertised :port advertised :path
  :bind-host}` and `repl-bounds {:drain-grace-ms 500}`. Synchronous
  refusals render: `::no-port` -> the old invalid-descriptor notice and
  "cannot serve ... on port ..." bind-note; `::bind-failed` -> the old
  `bind-threw` code when `:detail` is nil; `::no-transport` -> the host
  missing message; anything else (`::invalid-table`) -> a
  `composition-refused` programming-error notice. Inert endpoints
  publish their notice directly into the outbox.
- `step`: `serve-step`, then `observe-server` derives status
  (`repl-status`) and notices from `server-seen` -> server':
  "Serving <url>", diagnostics per new fact (`upgrade refused` /
  `listener error`), `;; endpoint lifecycle gap`, `;; attachment <id>
  left`, refusal, "Endpoint stopped: <outcome>" /
  "Endpoint stopped without host completion: unbind-failed". Advances
  requests only while `:running`.
- `stop!`: identity without a server or when stopping/stopped/failed;
  else closes answers then requests, `remote-channel/stop! {:ended? true}`.
- `stopped?`, `url` (from `:spec`), `summary` (`:lifecycle {:gaps
  :diagnostics}`, `:identity` dropped) per spec. Interpreter functions
  (`evaluate`, `deliver-answer`, `advance-requests`) byte-identical.
- Removed: every definition listed in spec 3.1 "Removed definitions"
  (plus `event-key`/`value-key`/`event-kinds`, `lifecycle-transition`,
  `drain-lifecycle`, `stop-grace-ms`, `finish-stop*`, `finish-stop`).
  `request-capacity`, `request-budget`, `request-admission` kept.
- Namespace docstring rewritten; states the idle-reaping risk (spec 7).

### `src/cljc/yin/repl/driver.cljc`

`poll-remote` takes `now`, keeps the stepped connection
(`(update :connection connect/step! now)`); `repl-step` passes `now`.
`open-connection` passes `:now (:last-tick state)`; `reattach` passes
`(:last-tick state)`. Nothing else.

### `src/cljc/yin/repl/main.cljc`

The optional one-line docstring reword in `drain-server!` (no `:ws/`
words above the boundary).

### `docs/design/dao.stream.remote.md`

- 3.0 "Explicit stop": the drain-grace sentence, verbatim from spec 2.4.
- 3.1: `detach!` added to the remote-channel operation list; after the
  `give-up-after` sentence: identities dials record `now` first; the
  table-validation sentence (2.1); the writing-through-a-reflection
  sentences (2.3).

### Tests

- `test/dao/stream/loopback_net.cljc`: conn map gains `:close-code
  (atom nil)`, set by the first `close-conn!` (the two-line fixture
  change of 4.1 item 6).
- `test/dao/stream/remote_channel_test.cljc`: amended assertions (see
  deviation 2); new deftests per 4.1 and 4.2 (section 2 below).
- `test/yin/repl/net_fixture.cljc` (new, spec 5.4): loopback host with
  recorded `:bound`/`:released`/`:deposit`, `pump!`, `open`,
  `client-step!`, `closed-conns`. Used by serve_test, embed_test,
  main_test.
- `test/yin/repl/connect_test.cljc`, `serve_test.cljc`: migrated per
  4.3/4.4.
- `test/yin/repl/embed_test.cljc`: `a-host-primitive-is-served-and-survives-reset`
  over the fixture; `ws` require dropped.
- `test/yin/repl/main_test.cljc`: both captured-socket connections
  replaced with fixture + `connect/open`; adoption asserted through
  `(:sessions (serve/summary server))`; `ws`/`ws-project` requires
  dropped; three `:ws/`-worded strings reworded; one `(:lifecycle
  server)` assertion -> `(:requests server)`.
- `test/yin/repl/slice_peer.cljc`: `ws-project` require dropped;
  `:handle-outcome` probes the RPC client's `:writer`;
  `:traffic-retained?` compares the first attachment id via
  `connect/summary`.
- `test/yin/repl/serve_connect_wire_test.clj`: `open`/`reattach` take
  `now`, `step!` and `close!` results stored with `swap!`.

---

## 2. Acceptance items and evidence

JVM namespace runs (`clojure -M:test -n ...`), all 0 failures 0 errors:

| Item | Test(s) | Result |
|------|---------|--------|
| 4.1.1 | `a-writer-entry-is-validated-against-its-handle` | pass |
| 4.1.2 | `an-identities-dial-attaches-both-at-once` | pass |
| 4.1.3 | `an-identities-dial-for-an-absent-identity-is-gone-not-found` | pass |
| 4.1.4 | `dial-target-refusals` (also a list, not a vector) | pass |
| 4.1.5 | `detach-leaves-the-reflections-to-observe-channel-gone` | pass |
| 4.1.6 | `an-ended-stop-drains-before-it-closes` (close code 4000 asserted) | pass |
| 4.1.7 | `a-drain-ends-early-when-the-last-session-leaves` (`:released t0+11`) | pass |
| 4.1.8 | `no-sessions-means-no-drain` | pass |
| 4.1.9 | `the-host-stopping-under-a-drain-is-host-stopped` | pass |
| 4.1.10 | `bind-host-binds-while-host-is-advertised` | pass |
| 4.1.11 | `diagnostic-count-is-monotonic` | pass |
| 4.2 | `writing-through-a-reflection-answers-acceptance-only` (5 testing blocks) | pass |
| 4.3 | serve_test: 20 tests, 77 assertions, incl. `stop-ends-the-served-media-before-it-closes-the-session`, `attachment-departure-is-noticed`, `a-serving-gap-is-counted-and-serving-continues`, `:ws-endpoint`/`:acceptor` nil invariant | pass |
| 4.4 | connect_test: 15 tests, 86 assertions, incl. reattach/fresh-attachment/same-cursor + round trip, absent answers -> not-found, never-opens -> detached at give-up-after (+ refused within two ticks), open-without-now-never-expires | pass |
| S3a | `remote-channel-test` 28 tests / `head-board-test` green | pass |
| main_test (fast) | 36 tests, 305 assertions | pass |
| main_test `^:slow` (fact 4 incl.) | `clojure -M:test -i :slow -n yin.repl.main-test`: 2 tests, 16 assertions, 35 s | pass |
| wire test | `yin.repl.serve-connect-wire-test` (JVM, real socket) | pass |
| Affected set | remote-channel, head-board, connect, serve, embed, driver, main, serve-connect-wire, dht-head, adapter: 158 tests, 1135 assertions | pass |

### Full lanes (foreground-equivalent, one lane at a time)

| Lane | Command | Result |
|------|---------|--------|
| JVM | `bb test:clj` | 3767 tests, 239274 assertions, **0 failures, 1 error**. The error is `yin.vm.ucf.handoff-v2-census-test/a-version-2-body-is-the-same-bytes-on-every-host`: `test/resources/yin/vm/ucf/handoff-v2.txt` does not exist. That file is not tracked on master `802d9ee2` either (`git ls-files test/resources/yin/vm/ucf/` lists only checkpoint-v1, ledger-v1, scalars-v1*), so it fails before S3b. Unrelated. |
| Dart | `bb test:cljd` | +3574 -1. The one failure is the same test with the same missing file (`PathNotFoundException ... handoff-v2.txt`). Every `dao.stream.remote-channel-test`, `yin.repl.serve-test`, `yin.repl.connect-test`, `yin.repl.embed-test` and `yin.repl.main-test` test ran and passed (74 entries in the log). |
| Node | `bb test:cljs` | **Not run.** The worktree has no `node_modules`, and `npm ci` (and `mise trust`) needed approval this session that a non-interactive run cannot get. The lane died with `MODULE_NOT_FOUND` and ran no tests, but still exited 0, so do not read that exit code as a pass. The orchestrator must run `npm ci` and then `bb test:cljs` before landing, and confirm that "Testing dao.stream.remote-channel-test", "Testing yin.repl.serve-test" and "Testing yin.repl.connect-test" appear in the output. |
| kondo | `clj -M:kondo --lint src test` | Zero findings in any file S3b touched (also linted per file: 0/0). The tree-wide total is 27 errors and 67 warnings, all in untouched files and all there before this slice, so the spec's "0/0 tree-wide" cannot hold on this base. |
| cljstyle | `cljstyle check` | **Not run**, because it needed approval this session. I fixed the one indentation shift I knew I had introduced (the `parse-url` body lost an arity level). The orchestrator should run `cljstyle check` (or `fix`) on the changed files. |
| Gate 4.5 (1) | `grep -n ":ws/\|dao.stream.ws\|ws-project" serve.cljc connect.cljc` | prints nothing |
| Gate 4.5 (2) | `grep -ln ... test/yin/repl/*.clj* \| grep -v host/` | prints `test/yin/repl/dht_head_test.cljc` and `test/yin/repl/host_node_test.cljs` (see deviation 9) |

---

## 3. Deviations from the specification, with reasons

1. **Four S3a assertions amended, not two.** The two the spec names
   now compare `(select-keys (:stop s) [:since :outcome])` and also
   assert `:released`. Two more had to change because of the spec's own
   decisions:
   - `production-bounds-reach-every-layer` counts the bounds
     (`(= 20 (count p))`), and adding `:drain-grace-ms` makes it 21.
   - `stop-is-explicit-and-driver-paced` asserts the initial `:stop`
     map `{:since nil :outcome nil}`, so it now uses select-keys too.

   I also added `{:drain-grace-ms -1}` and `{:drain-grace-ms nil}` to
   that test's invalid-bound list.
2. **The drain test reads `end` within the drain, not at exactly
   `t0+1`.** The reflection's first `next` files a `blocked` answer,
   so `end` arrives on the next ask. The test therefore polls one tick
   at a time from `t0+1` to `t0+10` and asserts `{:dao.stream/outcome
   :dao.stream/end}`. Everything else in the 4.1 item-6 sequence is
   asserted as specified: still open at `t0+499`, released at `t0+500`
   with close code 4000, unbind called once, then `:confirmed`.
3. **serve_test, `stopping-after-bind-threw-never-calls-unbind-or-waits`:
   the final status is `:failed`, not `:stopped`.** Spec 3.1 makes
   `stop!` the identity on a `:failed` endpoint, and a synchronous bind
   throw leaves `:server nil`. The test's actual claims still hold:
   `stopped?` is true and unbind is never called. I also assert the
   `bind-threw` notice text and dropped the `:resolution` assertions
   (an internal). In `a-synchronous-unbind-failure-resolves-stop-locally`,
   the text is now "Endpoint stopped without host completion:
   unbind-failed", as rule 5.3 directs.
4. **Two tests renamed, one added.** Renamed because their names
   described removed internals:
   - `serve-returns-immediately-with-its-lifecycle-medium-and-cursor`
     became `serve-returns-immediately-with-its-media-and-channel-server`.
   - connect_test's `a-descriptor-carries-reachability-and-one-logical-identity`
     became `a-spec-carries-reachability-and-no-transport-key`.

   Added `a-wildcard-bind-with-an-advertised-host-binds-the-wildcard`,
   which covers D6 at the REPL level.
5. **Changed notice texts.** No existing test asserted either old form.
   - "Serving <url>" no longer appends " (bound {...})", because the
     host's bind-succeeded value now lives below the boundary.
   - "Endpoint stopped: <x>" prints the outcome's name (`confirmed`,
     `host-stopped`, `unconfirmed`) instead of the host's `:stopped`
     value map.
   - A refusal after bind other than `::bind-failed` (for example
     `::lifecycle-lost` while starting) prints ";; endpoint refused:
     <reason>".
6. **Lifecycle gap while draining (the spec did not cover this).** Under
   S3a's rule, a gap or end while `:stopping` completes `::unconfirmed`
   at once. Applied during a drain, that would leave the sessions open
   and the listener bound. `lifecycle-lost` therefore runs `release!`
   and `unbind!` first when the stop has not yet released.
7. **connect_test never-opens deadlines.** `connect/open` takes no
   bounds, so the test uses the production `give-up-after` (15000).
   The dial is `:attached` at `1000+14999` and `:lost` at `1000+15000`;
   the spec's 1149/1150 assumed a 150 ms bound.
8. **Session departures.** `server-seen` counts only non-`:closed?`
   sessions, so the sessions a release closes are announced
   ";; attachment <id> left" on that tick. If a stop ends
   `::unbind-failed`, those sessions are closed but never reaped, and
   the notice still prints once because they are already marked closed.
9. **Gate 4.5's second grep is not empty.** It prints two files I did
   not change:
   - `dht_head_test.cljc` is S3a-2's head-board fake net. It records
     `:dialed` host and port from the ws descriptor, so it is not a
     REPL serve/connect test.
   - `host_node_test.cljs` is a Node host-adapter test that is not
     under `host/`, so the `grep -v host/` exemption misses it.

   I left both alone to keep the diff minimal. See question 1.
10. **Tooling not run in this session:** `npm ci`, `mise trust` and
    `cljstyle` (see section 2).

---

## 4. Blocked

None in code: no change to `ws.cljc`, `ws_project.cljc`, `remote.cljc`,
`rpc.cljc` or `apply.cljc` was needed.

---

## 5. Open questions for the Architect

1. Gate 4.5: should `host_node_test.cljs` be exempt by name, since it
   tests the Node host adapter? And should `dht_head_test.cljc`'s fake
   net move onto `loopback-net`? That would need loopback-net to record
   `:dialed`, a small fixture change.
2. Is deviation 6 (release, then `::unconfirmed`, when the lifecycle
   ring gaps during a drain) the behaviour you want, or should a gap
   while draining keep draining until the grace runs out?
3. `remote-channel/attachment` keeps the id after a dial is `:lost` and
   returns nil only after `close!`. `connect/summary` therefore still
   reports the old attachment after a detach. Is that right, given that
   slice_peer's `:traffic-retained?` relies on comparing it?
4. The handoff-v2 fixture (`test/resources/yin/vm/ucf/handoff-v2.txt`)
   is missing on master, so both the JVM and Dart lanes fail one test
   there. Someone outside S3b should fix this, but it blocks a fully
   green `bb test` for every slice.

---

## Remediation after Codex review (REVISE), 2026-10-08

Engineer: Claude Opus 5.5. Not committed; awaiting review.

### R1: bind port vs advertised port (fixed)

- `serve.cljc` `serve!`: the portable spec now carries
  `:bind-port bind-port` beside `:bind-host`; `:port` stays the
  advertised port (`advertised-port` falls back to `bind-port`, unchanged).
- `remote_channel.cljc` `serve`: the host bind request uses
  `:bind-port (or (:bind-port spec) (:port spec))`, mirroring `:bind-host`.
  `descriptor-of` still names `:port` (the advertised one). Validation
  contract: `serve` checks only the advertised `:port` (positive
  integer); `:bind-port` passes to the host unvalidated, as on the base
  revision, so `:bind-port 0` behind an explicit advertised port is the
  host's ephemeral bind. `serve!` still refuses an advertised port of 0.
- Docstring and `docs/design/dao.stream.remote.md` §3.1 state the contract.
- Tests: `remote-channel-test/bind-port-binds-while-port-is-advertised`
  (bind 8080, descriptor 9090);
  `serve-test/the-listener-binds-the-bind-port-while-the-url-names-the-advertised-port`
  (the reviewer's reproduction: bound 8080, URL `...:9090/repl`);
  `serve-test/an-ephemeral-bind-with-an-advertised-port-binds-port-zero`.

### R2: nil identity in an identities dial (fixed)

- `attach-identities` now loops on `(empty? remaining)`, so exhaustion no
  longer depends on the identity's value.
- `valid-target?` also requires `(every? some? identities)`: a nil identity
  is refused as `::invalid-target` before anything connects. A remote
  descriptor with a nil `:dao.stream/identity` is not a meaningful target,
  so refusing it is clearer than attaching it.
- Tests: `dial-target-refusals` gains `[nil]`, `[nil ans]`,
  `[req nil ans]`, `[req ans nil]`, each refused, and the existing
  assertion checks that no connection was made.
  `an-identities-dial-attaches-exactly-the-requested-identities`:
  a three-identity dial (the last one absent from the table) has exactly
  the requested handle keys and uses one shared connection.
- Not done: the reviewer's optional key-presence check for D2's "both keys
  present" rule (a nil-valued `:name` is still treated as absent).

### R3: D10 test-tree gate (fixed)

- `dht_head_test.cljc`: its private copy of the loopback net (~95 lines,
  including the `dao.stream.ws` require and `:ws/` descriptor plumbing) is
  replaced by `dao.stream.loopback-net`. `ws-host` now composes
  `net/listen-on`, `net/connect-on` and `net/unlisten!`.
  `loopback-net/connect-on` now records every `[host port]` under
  `:dialed`, which the DHT dial tests assert.
- `host_node_test.cljs` moved with `git mv` to
  `test/yin/repl/host/node_test.cljs` (ns `yin.repl.host.node-test`), next
  to `host/jvm_test.clj`. It is the Node host-adapter test, and its `:ws/`
  descriptors are the host seam's own contract. Host-adapter tests on any
  host now live under `test/yin/repl/host/`, which is the exemption the
  gate's `grep -v "host/"` already expresses. Nothing referenced the old ns
  name, and shadow `:node-test` discovers it by its `-test` suffix. The
  Architect should amend D10's wording from "except the JVM host-adapter
  tests" to "except the host-adapter tests under `test/yin/repl/host/`".
- Gate: `grep -ln ":ws/\|dao.stream.ws\b\|ws-project" test/yin/repl/*.clj* src/cljc/yin/repl/serve.cljc src/cljc/yin/repl/connect.cljc`
  matches nothing (exit 1).

### R4: cljstyle (NOT VERIFIED)

`cljstyle fix` and `cljstyle check` both needed permission that this
non-interactive session could not get, so neither ran. I indented my own
additions by hand to match the surrounding code. The pre-existing spots the
reviewer flagged in `remote_channel_test.cljc` (the invalid-bound assertion,
the inline `step!` fixture, the `read-only` reify) are untouched. Before
landing, run:
`cljstyle fix test/dao/stream/remote_channel_test.cljc src/cljc/dao/stream/remote_channel.cljc src/cljc/yin/repl/serve.cljc test/dao/stream/loopback_net.cljc test/yin/repl/serve_test.cljc test/yin/repl/dht_head_test.cljc`
and then `cljstyle check` on the changed files.

### Drain-gap teardown (deviation 6, pinned)

- `lifecycle-lost` (stopping, not yet released) now goes through
  `release-stop`. That closes every session, unbinds once and records
  `:stop :released now`. The stop then completes `::unconfirmed`, or
  `::unbind-failed` when the host refuses the unbind. `drain-lifecycle`
  and `lifecycle-lost` take `now` for this. `release-stop` moved above
  `lifecycle-lost`; its body is unchanged. The `serve-step` docstring and
  design §3.0 (lifecycle observation) state this.
- Tests: `a-lifecycle-gap-under-a-drain-releases-once` (`:released 101`,
  `::unconfirmed`, sessions closed, one unbind, later steps change nothing
  and do not unbind again); `a-lifecycle-end-under-a-drain-releases-once`
  (the lifecycle ring is closed);
  `an-unbind-refused-under-a-drain-gap-is-unbind-failed`.
  `served-reader-world` takes an optional unbind stand-in.

### Verification

- `clojure -M:test -n dao.stream.remote-channel-test -n yin.repl.connect-test -n yin.repl.serve-test -n yin.repl.serve-connect-wire-test -n yin.repl.dht-head-test`:
  **92 tests, 667 assertions, 0 failures, 0 errors.**
- `bb test:clj` (full JVM lane): **3774 tests, 239321 assertions,
  0 failures, 1 error**. The error is the missing
  `test/resources/yin/vm/ucf/handoff-v2.txt`, which is also missing on the
  base revision; this slice did not cause it.
- `clj -M:kondo --lint src test`: 27 errors and 67 warnings tree-wide, the
  same pre-existing baseline. Linting only the changed paths
  (`remote_channel.cljc`, `src/cljc/yin/repl`, the two `test/dao/stream`
  files, `test/yin/repl`) gives 1 error:
  `src/cljc/yin/repl/host.cljc:15` "no libs specified". That file is
  unmodified, so the error is pre-existing; the changed files have 0
  errors and 0 warnings.
- D10 gates: production and test-tree greps are both empty.
- cljstyle: not run (see R4).
- Node and Dart lanes: not run in this remediation pass. They are still
  owed at landing, including the renamed `yin.repl.host.node-test`.

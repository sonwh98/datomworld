Completed-GMT: 2026-09-10 03:08:10 GMT
Completed-Local: 2026-09-10 10:08:10 +0700 (Asia/Bangkok)
Coding-Agent: glm
Session-ID: 655d88ba-ab4f-4a94-b813-a980ef9e674f
Model: glm-5.3 | Status: complete | Branch dao.stream-redesign-v2 at 3228d0e, unstaged

# dao.jing.remote Phase 1 — the portable core: findings

## What was built

`src/cljc/dao/jing/remote.cljc` — additions only, one contiguous block between
`content-client` and the JVM-only `connect-content!`. Nothing deleted, nothing
existing edited; the ns docstring, `default-handlers`, `content-client`,
`connect-content!` and the `comment` block are untouched. No reader
conditionals were added to this file.

- Constants: `content-path` "/jing", `service-identity`
  "dao.jing.remote/content", `traffic-capacity` 8192, `traffic-admission`,
  `default-connect-timeout-ms` 5000, `default-request-timeout-ms` 5000,
  `default-poll-interval-ms` 10, `non-portable-result-code`
  `:dao.jing.remote/non-portable-result`; private `default-port` 80.
- Public `content-descriptor` (D7) with private parsing helpers
  (`parse-positive-port`, `authority-end`, `path-component`, `content-target`,
  `invalid-url!`): `ws://host[:port][/path]`, port defaulting to 80, absent
  path naming `content-path`, explicit path (including an explicit `/`) kept
  as written, query/fragment ignored, fixed identity, validated by
  `ws/descriptor?`. `wss://`, other schemes, a missing host, a port that is
  not a positive integer, a bracketed IPv6 authority, or a non-canonical
  path throw before any socket.
- Public `call-step`, `drain-outboxes`, `retire-call` — **the plan's D3 code
  block verbatim**, bodies and docstrings.
- Public `completion-value` (D3/N9): ok response → its value; error response
  → throws `{:operation op :error {code message}}`; `:reason` completion →
  throws `{:operation op :reason r}`.
- Public `await-established-step` (D2): one non-waiting advance of the
  establishment wait, **unary with budget 1 baked in** (D2's "budget one" is
  the wait's own rule — the response-before-`/established` pin is only
  observable at budget one), returning
  `{:state s' :status :established|:pending|:terminal :reason?}`. It observes
  lifecycle only and drains both outboxes each advance, for the same reason
  `call-step` drains them.

`test/dao/jing/remote_test.cljc` — new deftests only, appended under a Phase 1
banner with private helpers (`ring-handle`, `ring-cursor`, `ring-client`,
`serve-request!`), all following the `dao.stream.rpc-test` precedents
(ring-buffer media, no decoder, `reify` writer doubles, writer swap via
`(assoc state :writer …)`). The thirteen contract deftests and six network
deftests are byte-for-byte unchanged, as are their fixtures. Assertion counts
per new deftest: 17 / 24 / 17 / 8 / 29 = **95**.

1. `content-descriptor-derives-a-servable-descriptor` — the full descriptor
   map, `ws/descriptor?`, port/path defaults, explicit `/` and explicit path,
   query/fragment ignored, and nine refusal URLs (`wss://`, `http://`,
   `daostream:ws://` — the REPL prefix is deliberately *not* accepted here —
   bare authority, `ws:///`, `ws://:port`, port 0, non-numeric port, IPv6).
2. `call-step-completes-one-request-over-in-process-media` — happy path over
   a hand-turned `apply/dispatch-request` on `default-handlers` (pending →
   served → done → the presence envelope `{:found? true :value payload}`,
   outboxes empty); a throwing handler's error response → `completion-value`
   throws N9's `{:operation :error}`; bare `/detached` with nothing
   outstanding → `:terminal` with reason; the same lifecycle **with a call in
   flight** → `:done` with a `:reason` completion and `completion-value`
   throws N9's loss (see judgment call c); a foreign-id response → consumed
   as an unsolicited diagnostic, still `:pending`, call still outstanding; a
   writer answering `full` once → `:unsent` retained, retried unchanged by
   the next step, then done.
3. `retire-call-drops-bookkeeping-and-a-late-response-is-unsolicited` — N6's
   late-correlation pin, scripted media, no clock. Scenario A: id 0 retired
   (`:outstanding` empty, `:next-id` still 1, `:completed` empty), id 0's
   response appended anyway, id 1 requested and answered — one `call-step`
   (budget 8) consumes both elements and `:done` carries **id 1's** value,
   with id 0's response dropped as an unsolicited diagnostic and the stored
   state's outboxes empty. Scenario B: writer answers `full` so id 0 is
   retired while `:unsent` — the abandonment completion is drained **by
   `retire-call` itself** (`:completed` empty on return), and id 1 proceeds
   through a working writer.
4. `await-established-step-observes-only-lifecycle` — no event → `:pending`;
   bare `/established` → `:established`; bare `/detached` → `:terminal` with
   reason; a response element before `/established` → `:pending` with the
   diagnostic drained, and the `/established` behind it still establishes.
5. `immediate-refusals-leave-no-payload-behind` (N11) — a writer double
   answering `:dao.stream/invalid-value` on every append; three `request!`s
   with `[address payload]` args each answer `request-undeliverable` with
   reason `:dao.stream/invalid-value` **every time**; pre-drain the stored
   state holds exactly one completion **carrying the payload's args** (the
   leak, shown), and after each `(drain-outboxes state)` the assertion is on
   the stored state: `(count (:completed drained))` is `0`, diagnostics `0`,
   `:outstanding` empty. Then `invalid-request` (op not a keyword) appends a
   diagnostic carrying `{:op … :args …}` → drained. Then a working writer:
   the fourth request (id 3 — allocator untouched) completes normally through
   `call-step`.

## Verification — exact commands and counts

Baselines were captured at `3228d0e` (plus the pre-existing `jvm_test.clj`
docstring diff) before any edit.

| command | before | after |
|---|---|---|
| `clojure -M:test` | 1443 tests / 165370 assertions / 0 failures, 0 errors | **1448 tests / 165465 assertions / 0 failures, 0 errors** |
| `bb test:cljs` | 1345 tests / 34921 assertions / 0 failures, 0 errors; `Testing dao.jing.remote-test` present | **1350 tests / 35016 assertions / 0 failures, 0 errors; `Testing dao.jing.remote-test` confirmed in the Node output**; shadow build 0 warnings |
| `bb test:cljd` | `+1299: All tests passed!` | **`+1304: All tests passed!`**; the five new deftests appear by name in the Dart output |
| `clj -M:cljs -m shadow.cljs.devtools.cli compile demo` | — | `[:demo] Build completed. (212 files, 1 compiled, 0 warnings, 3.60s)` |
| `clj -M:kondo --lint src/cljc/dao/jing/remote.cljc` | 0 errors, 0 warnings | **0 errors, 0 warnings** (the test file lints 0/0 too) |

**Behaviour-neutrality, stated explicitly:** both counting lanes rose by
exactly +5 tests / +95 assertions, and 95 is the hand-counted assertion total
of the five new deftests (17+24+17+8+29). No existing test changed count,
behaviour, or outcome; there are 0 failures and 0 errors on every lane. The
Dart lane's +5 tests are exactly the five new deftests. Nothing was staged or
committed; `git status` shows only `src/cljc/dao/jing/remote.cljc`,
`test/dao/jing/remote_test.cljc`, and the pre-existing
`test/dao/stream/ws/jvm_test.clj` docstring diff, which I did not touch.

## Deviations and judgment calls

a. **Three of the plan's six new requires were not added; two were.** Added:
   `[clojure.string :as str]`, `[dao.stream.apply :as apply]`,
   `[dao.stream.rpc :as rpc]`, `[dao.stream.ws :as ws]` — every
   namespace Phase 1's code calls. Not added: `dao.stream`,
   `dao.stream.rpc.ws`, `dao.stream.transit`. Two reasons.
   `dao.stream.rpc.ws` is **mechanically impossible in Phase 1** under its
   natural alias: the v1 require `#?(:clj [dao.stream.rpc.ws :as rpc-ws])`
   still stands (Phase 1 must not touch it), and a second `:as rpc-ws`
   throws at load time — demonstrated:
   `IllegalStateException: Alias rpc-ws already exists in namespace user,
   aliasing dao.stream.rpc.ws`. `dao.stream` and `dao.stream.transit`
   have zero call sites in a behaviour-neutral Phase 1 (their first users are
   Phase 2's `connect-content!`/`serve-content!`), and the kondo lane's
   baseline is 0 warnings — unused requires would fail the plan's own
   verification command. All three belong to the §10 end-state require set
   and land with Phase 2's §5.1, when the v1 requires are deleted and the
   `rpc-ws` alias is freed.
b. **No reader conditionals in `remote.cljc`**, as the prompt required; the
   portable helpers (string-index parsing, `^:private` defs) follow the
   `yin.repl.connect` spellings that already compile on all three hosts.
   The **test** file uses the existing
   `#?(:clj Exception :cljd Object :cljs js/Error)` spelling inside
   `thrown?`/`catch` — the identical conditional the thirteen existing
   contract deftests already carry; there is no portable way to name an
   exception class without it. Flagging it here rather than leaving it
   unremarked, since the prompt asked for reasons rather than quiet
   additions.
c. **The plan's `:terminal` clause in test 2 is pinned with nothing
   outstanding, and the with-a-call-in-flight variant is pinned separately as
   `:done` + N9's loss.** With the plan's verbatim `call-step`, a `/detached`
   that arrives while the awaited call is outstanding is folded by
   `lose-outstanding` into a `:reason` completion *for that id*, which the
   step's `mine` check therefore returns as `:done`; `completion-value` then
   throws `{:operation op :reason :dao.stream.apply/detached}`. Both
   clauses are asserted, so the pin is faithful to the shipped step in both
   states; this matches N10's "N8's `/detached` (or N9's loss)".
d. **Test 2's "completion for a foreign id" clause is realized as a
   foreign-id success response** (id 7 while awaiting id 0): on that path the
   RPC core produces an unsolicited-response *diagnostic*, not a completion —
   a genuine foreign completion only arises from retirement, which test 3
   pins. The observable the plan names is unchanged: still `:pending`, the
   awaited call still outstanding, stored outboxes empty.
e. `await-established-step` is unary (budget 1 baked in, per D2) and returns
   the same `{:state :status (:reason)}` shape as `call-step`, so Phase 2's
   loop drives both the same way.

## Invariants — the five named risks

1. **N11** holds at every exit that exists in Phase 1: `drain-outboxes` is
   the one cure, test 5 pins it at three `request-undeliverable` exits and
   one `invalid-request` exit, and the assertion is `(count (:completed
   state)) = 0` on the **stored** state each time. The `allocator-error`
   exit is not separately pinned here — it is the same drain, and
   `rpc_test` already pins that the allocation failure loses outstanding
   requests into `:completed`; its Phase 2 exit is `settle!`, the same
   function. Nothing about it is weakened.
2. `retire-call` **drains its own abandonment completion** — test 3 scenario
   B asserts `:completed` is empty on return, and that the payload-bearing
   completion existed to be drained (asserted in test 5's pre-drain).
3. **The late-correlation pin is scripted, no clock** — test 3 scenario A:
   id 1 receives id 1's value; id 0's response surfaces nowhere except as a
   drained diagnostic.
4. `await-established-step` **observes lifecycle only** — a response element
   before `/established` is consumed as a diagnostic and does not establish;
   the `/established` behind it still does.
5. **No `send!` or transport change** — `dao.stream.ws.jvm` was not
   touched; `git status` proves the edit set is exactly the two owned files
   (plus the pre-existing docstring diff).

## Left owing

Nothing of Phase 1. Phase 2 inherits: the three deferred requires (see a),
the `rpc-ws` alias once v1's require is deleted, and all §5.1/§5.2 wiring —
none of which Phase 1 prejudges. `connect-content!`, `content-client`,
`default-handlers`, `coordinate.cljc`, `stigmergy_test.clj`, the plan and the
orchestrator log were not touched.

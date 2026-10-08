Completed-GMT: 2026-09-10 04:12:28 GMT
Completed-Local: 2026-09-10 11:12:28 +0700 (Asia/Bangkok)
Coding-Agent: glm
Session-ID: 26a798b9-cdc8-4a34-99b8-dbc27cd81708

# dao.jing.remote Phase 2 — the swap: completed

`dao.jing.remote` is on DaoStream v2, both halves, and requires no v1
stream namespace. One phase, as specified: `connect-content!` and the
server constructor swapped together with every fixture that pairs them.

## Built

`src/cljc/dao/jing/remote.cljc` (§5.1):

- `settle!` (private) — the one exit of `call!` (N11): drains both RPC
  outboxes, `reset!`s the client's `:rpc`, then hands the drained state to
  a `raise` thunk that returns the call's value or throws its error. It is
  the only writer of the client's stored state; `grep 'reset! (:rpc'`
  finds exactly one line, inside `settle!`.
- `call!` — D1's blocking driver over `call-step` under the client's lock.
  All seven exits from §5.1's enumeration route through `settle!`:
  `request-undeliverable` (N9's loss with the append reason **and
  `:request-id`**, so test 9's three ex-data maps differ only by an id
  that advances by one), `invalid-request` (defect map), `allocator-error`,
  `terminal` (N8's reason), the deadline (`retire-call`, N6, then
  `{:request-id id :timeout-ms ms}`), `:done` (`completion-value`, N9),
  `:terminal` (N9's loss). The lock is let-bound before `locking` (kondo's
  suspicious-lock heuristic rejects a keyword lookup in the lock
  position; the let is semantically identical).
- `close!` — `stream/close!` on the handle; the once-only guard is
  `content-client`'s (C4), and it runs outside `call!`'s lock (N10).
- `connect-content!` — D2/D7: `content-descriptor`, 8192-element traffic
  medium, cursor minted at `:dao.stream/newest` **before** `attach!`,
  `ws/make-attacher` over `jvm/connect!`, `rpc.ws/init-client` on the whole
  attach result, the establishment loop over `await-established-step`
  (budget 1, `:poll-interval-ms`, `:connect-timeout-ms`; terminal and
  deadline both `stream/close!` the handle before throwing, so nothing
  escapes), then `content-client` over
  `{:rpc :handle :attachment :lock :request-timeout-ms :poll-interval-ms}`.
  The docstring states the mint-before-attach order as observable and why
  (in `yin.repl.connect/open`'s words), and N2's limit in one sentence.
- `serve-content!` — D5 with S5's inbound step. yin.repl.serve's
  composition minus the shell: capacity-1 anchor stream, 1024 portable
  control medium, eight capacity-1 host-value handoff slots, 256-element
  lifecycle medium for `listen!`'s deposits, per-attachment 8192 portable
  traffic medium with the cursor minted before the acknowledgement,
  `jvm/listen!` as `:start-endpoint!`, `ws/accept-connection!` as
  `accept!`, and a daemon ticker thread calling `serving/step!` every
  `:tick-ms` (default 1) until `stop!`. Returns
  `{:port p :stop! f :serving s :lifecycle l}`; `:port` is the port the
  host reported bound (captured from `listen!`'s `:bind-succeeded`
  deposit), falling back to the requested port. A bind failure throws and
  starts no ticker (S3); `stop!` is CAS-guarded once-only over
  `serving/stop!` and returns nil (S2).
- `portable-response` / `inbound-step` — D5's sketch, gated
  `#?(:cljd nil :clj …)` like every other JVM defn (see deviation 2).
- New constants: `service-capacity` 1, `control-capacity` 1024,
  `lifecycle-capacity` 256, `handoff-slot-count` 8, `handoff-admission`,
  `control-admission`, `default-bind-host` "127.0.0.1", `default-tick-ms`
  1 (all public, yin.repl.serve's precedent), plus private
  `response-poll-budget` 32 — see deviation 5.
- ns docstring rewritten; the `comment` block rewritten on
  `serve-content!` / `((:stop! server))`.

`test/dao/jing/remote_test.cljc` (§5.2):

- `with-server` on `remote/serve-content!` + `((:stop! server))`, spelled
  `#?(:cljd nil :clj (defn- …))`, URL `ws://127.0.0.1:`, sleep deleted.
- `network-file-restart-test`: only its inlined fixture lines changed
  (serve-content! ×2, `((:stop! …))` ×2, URLs ×2, sleeps deleted). Both
  assertions byte-identical. **No assertion in any of the six `network-*`
  tests changed** — the neutrality proof holds.
- Seven new JVM deftests, each an unconditional `deftest` with body
  `#?(:clj … :cljd (is true …) :cljs (is true …))`: #3 latch server, three
  timeouts with per-exit empty `:outstanding`/`:completed`/`:diagnostics`,
  recovery on the fourth call; #4 non-portable handler result →
  `non-portable-result-code` error, second op still answers; #5 refused
  port → `{:url :reason :dao.stream.apply/transport-error}`; #6 stalled
  handshake → `{:url :timeout-ms 200}` within 2 s, own sockets closed in
  `finally`, leak stated in the body comment; #7 close-during-blocked-call
  → `/detached`, closed-atom true, second close a no-op; #8 second server
  on a bound port throws, `stop!` twice is nil; #9 three refused puts with
  non-portable payload — ex-data equal apart from `:request-id` = 0,1,2,
  stored state empty after each, portable put+get round-trip after.

`test/dao/space/stigmergy_test.clj` (§5.3): the require line deleted,
`:70` now `(remote/serve-content! (remote/default-handlers store) …)`;
`:73`/`:76` unchanged in text. Plus one docstring word — deviation 3.

Docs (§5.4): `dao.jing.md` `:320` replaced with the full `dao.jing.remote`
paragraph (every sentence §5.4 enumerates) and *Open items* gains the
stepped-client paragraph under *Async hydration*; `dao.stream.ws.md`
*Deferred* gains the establishment-cancel item carrying §9's substance;
`dao.stream.md` `:803-805` now reads "`dao.jing`'s DHT node";
`docs/dao.space.stigmergy.md:9` served by `serve-content!` (`:244-245`
untouched).

## Deleted

The two v1 requires (`rpc-client`, `rpc-ws`), the v1 `connect-content!`
body, the v1 `comment` block, every `rpc-ws/` and `rpc-client/` token; in
the test: the `:15` require, the three `Thread/sleep`s, the ns docstring's
v1 prose; in stigmergy_test: the `:32` require and `:70`'s `rpc-ws/start!`.

## Verification (final tree, frozen before all runs below)

| command | outcome |
|---|---|
| `clojure -M:test` | **1455 tests, 165505 assertions, 0 failures, 0 errors** (baseline 1448/165465; +7 tests, +40 assertions) |
| `bb test:cljs` | **1357 tests, 35023 assertions, 0 failures, 0 errors** (baseline 1350/35016); `Testing dao.jing.remote-test` appears in the Node output (count 1) |
| `bb test:cljd` | **+1311: All tests passed!** (baseline +1304; +7 = the seven `(is true)` bodies) |
| `clj -M:cljs -m shadow.cljs.devtools.cli compile demo` | **212 files, 0 warnings** |
| `clj -M:kondo --lint src/cljc/dao/jing/remote.cljc` | **0 errors, 0 warnings** |
| `clojure -M:test -n dao.jing.remote-test` (×4) | 31 tests, 204 assertions, 0 failures every run |
| `clojure -M:test -n dao.space.stigmergy-test` | 5 tests, 22 assertions, 0 failures — all five scenarios pass unmodified |

Closure greps, actual counts:

- `grep -c 'dao\.stream\.rpc\|dao\.stream\.ws\|rpc-ws/\|rpc-client/'` →
  `remote.cljc` **0**, `remote_test.cljc` **0**, `stigmergy_test.clj`
  **0**.
- `grep -rn '\[dao\.stream :as\|dao\.stream\.rpc' src/cljc/dao/jing/` →
  nothing.
- `grep -n 'dao\.stream\.v2\.ws\.jvm' remote.cljc` → three hits, all prose
  (two comments, one docstring); the only code occurrence is the require
  inside `#?@(:cljd [] :clj …)`. Alias uses (`jvm/…`) don't match the
  pattern.
- `grep -n 'reset! (:rpc\|swap! (:rpc'` → one hit, inside `settle!`.

cljd canary, measured in the emitted Dart: `lib/cljd-out/dao/jing/remote.dart`
imports `../stream/rpc/ws.dart` and `../stream/rpc/client.dart` **no
longer** (they were there at `f1babc8`), imports no
ringbuffer/serving/ws.jvm, and contains no `connect_content`/`serve_content`;
`test/cljd-out/dao/jing/remote-test_test.dart` imports `lib/…/jing/remote.dart`
and no v1 source twin. Its source-tree imports are exactly the portable v2
twins (`v2/ws`, `v2/apply`, `v2/rpc`, `jing`, `cljd/core`, `cljd/string`).

## Deviations from the plan's letter (all stated, none behavioral)

1. **The `#?@(:cljd [] :clj …)` splice carries six namespaces, not the
   plan's three.** `dao.stream`, `dao.stream.rpc.ws`, and
   `dao.stream.transit` moved in beside `ringbuffer`/`serving`/`ws.jvm`.
   Reason: clj-kondo's cljs pass flags an ungated require whose only usage
   is inside `#?(:clj …)`-gated defns (`unused-namespace`) and an ungated
   private var likewise — and §5.1's own rule gates every JVM defn, so
   everything those defns use must travel with them or the 0/0 kondo
   baseline breaks. The grouping the plan wanted ("keeping the three
   together says which functions are the host composition") is preserved
   and strengthened: all six aliases in the splice are used only by the
   host composition; the portable core's (`str jing apply rpc ws`) stay
   ungated. §10's require *set* is unchanged; only the grouping differs.
2. **`dao.stream.rpc.ws` is aliased `rpc.ws`, not `rpc-ws`.** D5's
   sketch writes `rpc-ws/` tokens, but the §5.5 closure grep
   (`rpc-ws/\|rpc-client/\|dao\.stream\.rpc` → nothing) would then
   false-positive on v2's namespace — the grep cannot distinguish the
   alias of `dao.stream.rpc.ws` from v1's `dao.stream.rpc.ws`. Aliasing
   by the namespace's own trailing segments keeps the grep a true v1
   detector. This is the one place I chose the verification criterion over
   the sketch's spelling, and it is cosmetic.
3. **stigmergy_test's ns docstring got one edit beyond §5.3's three
   lines**: "store served over dao.stream.rpc" → "store served by
   dao.jing.remote/serve-content!". The §5.5 residue table requires zero
   `dao\.stream\.rpc` hits in that file; the docstring at `:14` was the
   only remaining one. No deftest, fixture line, or assertion changed.
4. **`call!` let-binds the lock before `locking`.** kondo's
   suspicious-lock check rejects `(locking (:lock client))`; the let-bound
   form is semantically identical and reads the same.
5. **A new private constant `response-poll-budget` (32)** bounds one
   driver advance's poll burst. The plan names only the three timeout/
   cadence *options* (which I kept as options, per D1) and no budget; 32
   matches `yin.repl.connect`'s `lifecycle-budget` convention. It is
   gated with the JVM code because only `call!` reads it.
6. **Test #3's fourth call buys its 5000 ms deadline by `assoc`ing
   `:request-timeout-ms` onto the client value.** The plan asks for "a
   fourth `call!` `:fast/op` with the default 5000 ms deadline" on the
   same client the 50 ms timeouts exhausted; C2 fixes `call!`'s signature
   at `[client op args]`, so no per-call deadline exists, and the client
   is plain data — the same seam §5.2 sanctions for derefing `:rpc`. A
   second client would not have exercised "the late responses are
   classified unsolicited and dropped", which happens on the first
   client's medium.

## Not mine, present in the tree

`docs/agents/roles/orchestrator.md` (GLM off-peak pricing) and
`docs/agents/team.md` carry concurrent edits that I did not make and did
not touch; they appeared in the working tree during this session. My write
set is exactly the seven files above plus this report. Nothing is staged
or committed; HEAD is still `f1babc8`.

## Left owing

Nothing new from this phase. Carried, as the plan records: the
establishment-cancel gap is owed by `dao.stream.ws.jvm` on its own
ticket (now written into `dao.stream.ws.md` *Deferred*, with §9's repair
substance); the DHT node stays on v1 transit under D8's own plan; the
stepped client is under `dao.jing.md` *Open items*. Test #6 pays the N2
leak once per run — one JDK connection per run of the suite, for the
process lifetime, by design and stated in the test. The plan document
itself is now fully consumed as far as this phase's checklist goes; its
deletion is the owner's call, not mine.

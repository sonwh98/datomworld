Created-GMT: 2026-09-09 19:18:06 GMT
Created-Local: 2026-09-10 02:18:06 +0700 (Asia/Bangkok)
Coding-Agent: deepseek
Session-ID: 7bf6a403-74d3-4a94-91a7-4b7eb55a7e72 (resumed)
# Task: adversarial review of the dao.jing.remote v2 migration plan
Role: Adversarial Review

**Read-only. Print to stdout. Write nothing — not to the repo, not to a plan
file, not to your harness plan directory.** Last round you wrote your review
to `~/.claude-deepseek/plans/` and it had to be retrieved; stdout only this
time. A routine review runs in parallel; do not coordinate with it.

You reviewed `dao.space.schema`'s plan and cleared r2. That namespace is
committed and `dao.space.*` is entirely on v2. **This is the next consumer,
and it is the first one where the migration touches a *blocking* boundary.**

Plan: `collab/1788977648939-architect-jing-remote-v2-plan.claude-fable-5-1.findings.md` (634 lines)
Code: `src/cljc/dao/jing/remote.cljc`, `test/dao/jing/remote_test.cljc`,
`test/dao/space/stigmergy_test.clj`, `src/cljc/dao/jing/coordinate.cljc`
Contract: `docs/design/dao.stream.md`, `docs/design/dao.jing.md`
The v2 RPC core: `src/cljc/dao/stream/rpc.cljc`, `rpc/ws.cljc`,
`src/clj/dao/stream/ws/jvm.clj`; and `yin.repl` as the worked example.

## Verified by me — attack past these

v1 `call!` already polled (`rpc/client.cljc:96`, `Thread/sleep 10`), so the
waiting was always in the host; `#?(:clj …)` requires become Dart imports
while bodies do not (measured in `lib/cljd-out/dao/jing/remote.dart`);
`dao.stream.ws.jvm` is `.clj` with no Dart twin. All eight of the plan's
corrections to my brief are correct.

## Hunt these shapes

1. **A guarantee that survives only on the happy path.** `dao.jing`'s handle
   contract is synchronous: `jing/get` returns the value, `materialize!`
   returns the address only after durability. Under D1 that is a host poll
   loop over a non-waiting step. Construct the interleavings: what does a
   caller see when the socket drops mid-call, when the response arrives after
   the timeout fires, when `close!` runs while a call is in flight, when two
   threads share one handle? N5 says one call in flight per handle — is that
   *enforced*, or merely stated?
2. **A lost distinction.** v1's `get-content-fn` returns `not-found` for
   absence and throws for a malformed envelope; `content-client` validates the
   exact `{:found? :value}` shape so a stored `nil` is distinguishable from
   absence. Does anything in the v2 path — event vocabulary, decoder,
   `take-completed` — collapse absence, `nil`, timeout and transport error
   into one answer at any point?
3. **D2's establishment wait.** The plan mints a cursor on the traffic medium
   *before* `attach!` and polls until established. Can a lifecycle event be
   consumed during that wait that a later call needed? Can the wait succeed
   against a server that never becomes usable?
4. **The step that is claimed portable.** D3's `call-step` is the only new
   portable logic and cljs/cljd get it without the loop. Is it actually usable
   without a loop, or is it a step that only makes sense when driven by one —
   i.e. does the plan ship cljs/cljd an abstraction with no honest consumer?
5. **§9's completeness.** The plan lists what it carries that no other
   document does, because the previous plan's deletion nearly lost such
   knowledge. What is on that list that should not be, and what is missing?
6. **Anything D8 defers too cheaply.** `dao.jing.dht.node`'s v1 reach is
   claimed to be `transit/encode`/`decode` at two sites. Verify, and say
   whether splitting it is right or whether it hides coupling.

## Report

Print to stdout, ordered by severity, each finding with the interleaving,
input, or omission that makes it real. Say plainly where the plan is right.

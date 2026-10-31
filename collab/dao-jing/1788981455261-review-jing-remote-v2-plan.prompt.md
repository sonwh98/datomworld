Created-GMT: 2026-09-09 19:17:35 GMT
Created-Local: 2026-09-10 02:17:35 +0700 (Asia/Bangkok)
Coding-Agent: codex
Session-ID: 01a0868e-e9f2-7242-92e8-58d63e7f9574 (resumed)
# Task: routine review of the dao.jing.remote v2 migration plan
Role: Routine Review

**Read-only. Print to stdout; write no file.** You reviewed and cleared the
`dao.space.schema` plan and both its phases; that namespace is committed and
`dao.space.*` is entirely on v2. **This is the next consumer.** An adversarial
review runs in parallel; do not coordinate with it.

Plan: `collab/1788977648939-architect-jing-remote-v2-plan.claude-fable-5-1.findings.md` (634 lines)
Code: `src/cljc/dao/jing/remote.cljc` (135), `test/dao/jing/remote_test.cljc`
(381), `test/dao/space/stigmergy_test.clj`, `src/cljc/dao/jing/coordinate.cljc`
Contract: `docs/design/dao.stream.md` (*Explicitly Absent*, *The readiness
extension*, *Composition*), `docs/design/dao.jing.md`
Precedent: `src/cljc/yin/repl*` — the one v1 RPC consumer already on this core

## Verified by me — do not re-measure

v1 `call!` polls (`rpc/client.cljc:96`, `Thread/sleep 10` bounded by
`max-attempts`); `connect!` polls too. `lib/cljd-out/dao/jing/remote.dart`
imports `../stream/rpc/ws.dart` and `../stream/rpc/client.dart` and contains
no `connect_content` — `#?(:clj …)` **requires** reach the Dart compiler,
**bodies** do not. `src/clj/dao/stream/ws/jvm.clj` is `.clj` with no Dart
twin. `yin.repl` has a JVM `poll-loop!`. The plan's eight corrections to my
brief are all correct, including that my framing of its central question was
wrong.

## What to judge

1. **D1 — the host poll loop.** The plan argues a JVM thread calling
   `rpc/poll!`, receiving `idle`, and sleeping does not make an *operation*
   wait, citing "retry cadence is the concern of the interpreter or the
   runtime driving it". Is that the contract's meaning or a convenient
   reading? It binds itself to three conditions (loop only in the `#?(:clj …)`
   composition; cadence and deadline as options, not constants of the step;
   the cost stated). Are those conditions sufficient, and does the plan
   actually enforce them?
2. **D3's `call-step`** — the one new portable abstraction. Is it genuinely
   non-waiting, and is it the right seam, or does it smuggle policy into the
   portable half?
3. **D2 — establishment waiting.** The plan says it is not needed for
   correctness (an early call would be retained `:unsent` and retried) but is
   kept so an unreachable URL throws at open, for `dao.jing.md`'s "fail
   closed". Is keeping it right, and is the cursor discipline it describes
   (minted on the traffic medium *before* `attach!`) correct?
4. **N5 — one call in flight per handle.** Stated as a cost. Is it a
   regression from v1, and does anything in the tree need concurrency it
   removes?
5. **The cljd spellings.** Two are prescribed: `#?@(:cljd [] :clj [[…]])` for
   the JVM glue require, and unconditional `deftest` with conditional body.
   Are they sufficient for everything Phase 2 adds?
6. **Deletion and test accounting.** 13 contract deftests unchanged, 6 network
   ones moved, `stigmergy_test`'s four v1 sites edited. Does every property
   survive, and is §7's transport-versus-contract split right?
7. **§9 — what the plan carries that nothing else does.** It is written up
   front this time because the last plan's deletion had to repair such a loss
   by hand. Is each item's named home correct, and is anything missing from
   the list?

## Report

Ordered by severity, each finding with the concrete failure it would cause.
Distinguish blocking from improvement. If it is sound, say so plainly and say
it is implementable.

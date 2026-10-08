Created-GMT: 2026-09-10 04:21:42 GMT
Created-Local: 2026-09-10 11:21:42 +0700 (Asia/Bangkok)
Coding-Agent: codex
Session-ID: 01a0868e-e9f2-7242-92e8-58d63e7f9574 (resumed)
# Task: review dao.jing.remote Phase 2 — the swap
Role: Routine Review

**Read-only. Print to stdout; write no file.** Review `git diff` for these
files only: `src/cljc/dao/jing/remote.cljc`,
`test/dao/jing/remote_test.cljc`, `test/dao/space/stigmergy_test.clj`,
`docs/design/dao.jing.md`, `docs/design/dao.stream.ws.md`,
`docs/design/dao.stream.md`, `docs/dao.space.stigmergy.md`.
**Ignore `docs/agents/roles/orchestrator.md` and `docs/agents/team.md`** —
those carry the owner's own edits from outside this work and are not part of
it.

Implementer: `glm-5.3`, independent of you.
Plan: `docs/design/dao.jing.remote.implementation-plan.md` **§5**
Report: `collab/1789011069381-stream-jing-remote-phase2.glm-5.3.findings.md`

**This is the phase that ends `dao.jing.remote` on v1.**

## Verified by me — spend your budget on the code

All lanes, my runs: clj **1455 / 165505 / 0 failures 0 errors** (from
1448/165465); cljs **1357 / 35023 / 0 failures 0 errors** with
`Testing dao.jing.remote-test` present; cljd **all passed +1311** (from
+1304); demo 212 files 0 warnings. Closure greps: **0** v1 tokens in
`remote.cljc`, `remote_test.cljc` and `stigmergy_test.clj`. The emitted
`lib/cljd-out/dao/jing/remote.dart` imports **no** `rpc/ws.dart`,
`rpc/client.dart` or JVM glue — the cljd trap checked, not assumed.

## Three disclosed deviations — judge them

1. The `#?@(:cljd [] :clj …)` splice holds **six** aliases, not §5.1's three:
   kondo's cljs pass flags ungated requires used only inside `:clj`-gated
   `defn`s, so the gated code and its aliases travel together. §10's require
   set is unchanged. Right?
2. `dao.stream.rpc.ws` is aliased **`rpc.ws`**, not D5's `rpc-ws`, so the
   `rpc-ws/` closure grep does not false-positive on v2. The grep was chosen
   over the sketch's spelling. Right call?
3. One sentence in `stigmergy_test`'s ns docstring and one in
   `docs/dao.space.stigmergy.md` — both described the store as served over
   `dao.stream.rpc`, which Phase 2 makes false. (The second file was in the
   plan's edit set but not in my brief's ownership list; my brief was
   narrower than the plan.)

## What to judge

1. **N11, the invariant with the most history.** Is `settle!` genuinely the
   **only** way out of `call!` — all seven §5.1 exits? Does test 9 pin the
   stored state after every refusal, and that the ex-data maps are equal
   apart from an advancing `:request-id`?
2. **The cursor minted at `:dao.stream/newest` before `attach!`** — is the
   order right in code, and does the docstring say why?
3. **N2 as a limit, not a fix.** Test 6 must assert only the throw and that
   no handle escaped — no cleanup or EOF claim — and close its own sockets.
   Does it?
4. **S5's inbound step** — the correlated `non-portable-result` error, and
   the attachment retired on a non-ok append. Test 4 says a second op on the
   same attachment still answers; does that hold?
5. **The six `network-*` deftests** were the neutrality proof: **no
   assertion in any of them may have changed**. Verify that, not the claim.
6. **The docs (§5.4)** — do `dao.jing.md`, `dao.stream.ws.md` (the deferred
   establishment-cancel gap) and `dao.stream.md`'s consumer list say what
   the code now does?

## Report

Ordered by severity. If clean, say so plainly and say it is ready to commit.

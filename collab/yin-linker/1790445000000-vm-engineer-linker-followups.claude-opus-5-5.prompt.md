Created-GMT: 2026-09-26 16:40:00 GMT
Created-Local: 2026-09-26 23:40:00 +0700
Coding-Agent: claude
Session-ID: d6067df3-5127-409a-9aaa-f60932d6a1ed

# Task: yin.vm.linker small code follow-ups (post M1-M5)

Role: VM Runtime Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-09-26 23:40:00 +0700 | Status: active | Rationale: owner: "delegate the coding to a more capable LLM"; GLM frozen until 2026-09-27 01:26 +0700

Worktree /Users/sto/workspace/datomworld-followups (branch followups from master 768e62c2). Uncommitted work only; do NOT commit or push. Read docs/build-n-test.md for verification commands (kondo is `mise exec -- clojure -M:kondo`). CODE ONLY: the orchestrator is editing docs (linker.md, UCF, ucf-revisions) in the master checkout, so do NOT touch any docs/ file; if a doc change is implied, list it in the report instead.

## Items (each small; smallest diff; each with a test unless stated)
1. S4 P3 nil-equality hardening (Fable's gate, collab/1790430690001-architect-linker-m4-s4-gate.claude-fable-5-1.stdout.log and regate): in src/cljc/yin/vm/linker.cljc declared-discharge (~1001-1022) the :primitive and :module arms compare an obligation's :profile / :manifest to the receiver entry's, and two nils are equal. Add a `some?` guard per arm so a hand-crafted obligation with a nil profile/manifest is refused :unresolved-free. Test in test/yin/vm/linker_manifest_test.cljc.
2. Same test file: the host-only require uses a #?(:clj [...]) form (Fable P3); align with the house convention (#?@(:cljd [] :clj [...]) splice) so the cljd host pass behaves. Check other new test/src files from M4/M5 (git diff 9428c3d2 HEAD --name-only) for the same pattern and fix only genuine cases.
3. DeepSeek's S1 gate P3s (collab/1790415651912-architect-linker-m4-s1-gate.deepseek-v4-pro.findings.md lines 56-58): (a) attach-image on an EMPTY image against a NON-EMPTY VM appends a spurious zero-length row (stack.cljc ~157, register.cljc ~187): make it a true no-op (no row, no hash change) on both kernels, test it; (b) absolute-pc (stack.cljc, register.cljc ~232) has no bounds check on rel-pc: an out-of-range [identity rel-pc] must refuse (fail closed, use the existing refusal style) instead of returning a mis-placed pc; test it. Check the semantic and walker kernels for the same two gaps and fix if present.
4. Flaky JVM test: dao.stream.ws.jvm-test/transit-and-cbor-sessions-share-one-live-jvm-listener failed once under full-suite load (NullPointerException in accept-and-ack-slot!, test/dao/stream/ws/jvm_test.clj ~413: "Cannot invoke Character.charValue() because x is null" in RT.intCast); passes alone (3/3). Diagnose the race (a nil read from a stream/socket that closed or timed out) and make the test deterministic (bounded wait/retry on the test side, or fix the helper) WITHOUT hiding real failures. Run that namespace 10 times alone and the full JVM suite once. If the cause is in production code, fix it there and say so.
5. REPORT ONLY (no code): dao.pretty renders a quote form as `'mod` on JVM/cljs but `(quote mod)` on ClojureDart (found by the M5 tests). Say where it comes from, whether it is a small fix, and what it would touch.
Rules: Rule R; ClojureDart traps (:cljd first in reader conditionals, no bare type, no private var-quote cross-namespace); portable cljc; ASCII; 80 cols; cljstyle clean (mise exec -- cljstyle check <files>); no new kondo warnings. Run touched-namespace JVM tests; the orchestrator runs the three lanes.
Report: collab/1790445000000-vm-engineer-linker-followups.claude-opus-5-5.report.md (per item: what changed at file:line, test names, decisions, anything not done and why).

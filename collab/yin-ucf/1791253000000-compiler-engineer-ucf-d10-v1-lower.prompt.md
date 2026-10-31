Created-GMT: 2026-10-06 22:40:00 GMT
Created-Local: 2026-10-07 05:40:00 +0700
Coding-Agent: glm (glm-5.3)
Session-ID: d914ca29-1b2a-4c78-bccf-5c68da8b04c5

# Task: UCF M-next D10 — the version-1 lower
Role: Yang Compiler and Universal AST Engineer

Implementers:
- Status-Event: 2026-10-07 05:55 +0700 | Model: glm-5.3 | Status: superseded | Rationale: still out of credits until 7am; the owner authorized codex/agy implementation rounds (routing-status.md)
- Model: gpt-6-terra (codex) | Assigned: 2026-10-07 05:55:00 +0700 | Status: active | Rationale: the owner lifted the codex implementation reservation; terra is the balanced engineering model

Implement D10 in /Users/sto/workspace/datomworld-d10 (worktree, branch
ucf-d10-v1-lower, based on master af5dc88e with D1 to D9 and the doc
amendments landed). Read first, in the worktree's collab/: the D plan
r3 (1791194000000-architect-m-next-d-plan-r3.claude-fable-5-1
.findings.md — sections 1.1, 1.6, 1.7 and the D10 test contract), the
astra residual 3 (1791192700000-...r2-confirm... — the two-codec
split), the link-cursor ruling (1791203000000-... — D13's note that a
lowered `:link-request` entry carries its kept cursor from the body),
and the docs as amended on master (UCF 7.7.4, 7.7.8, linker-dht
14.2.2). Then src/cljc/yin/vm/ucf/handoff.cljc (the D7 version-aware
reader pipeline: decode-two, the version gate, the self-check path;
the version-0 lower and its restoration), and the landed lower's
tests.

The contract (r3's D10 row + the plan's version-aware reader):

Lower is the receiver side of the version-1 body: after the D7
pipeline's validation, restore the machine.

1. **The counter is restored exactly**: `:yin.k/next-op-seq` from the
   custody header becomes the receiver's operation counter.
2. **Carried ids survive** on `:put`, `:ffi-request` and
   `:link-request` pendings (the receiver's entries carry the body's
   op ids verbatim).
3. **The custody inputs are RULED** (collab/1791240000000-architect
   -d10-lower-inputs-ruling.gpt-6-astra.findings.md, staged — read it
   in full; it governs where this brief is silent). In brief:
   resume-task keeps its API and gains, for a blocked/parked v1 root,
   the opts `:address`, `:protection` (the composition's per-identity
   declaration, exactly the three classes), and `:grant` (checkpoint,
   lease, holder, evidence E, tenure {:now :bound :live}). D10 checks
   the seven grant-consistency rules, refuses :yin.k/awaiting-grant
   without grant evidence (no :vm, no attachment calls),
   :yin.k/not-holder on invalid binding, and :yin.k/unsatisfied on
   unavailable evidence or protection mismatch; restores the counter
   exactly from the body; and returns the root-only :yin.k/custody map
   with :yin.k/gate :running. Halted v1 roots restore gated :ended
   with no grant. Install children get the gate only. Lower performs
   no proposal, ledger IO, renewal, release or clock read — D13 owns
   those.
4. **Protection:** the declaration is keyed by portable stream
   identity with exactly the three class values; a missing
   declaration for a reachable identity is :yin.k/unsatisfied naming
   the stream; :enrolled must agree with E's enrolled set in both
   directions; for each retained write, enrolled-without-id refuses,
   an id on a non-enrolled target refuses, enrolled-with-id is
   preserved, non-enrolled-without-id preserves its declared class.
5. **A restored child continues from its saved state** without
   rerunning initialization or replaying its link request.
6. **Receiver-local store bindings do not change resolution**
   (14.2.4 row 8): the isolated store cannot inherit the receiver's
   guest bindings.
7. **D9's blocked/parked v1 restoration tests must now supply the
   explicit D10 grant inputs** — that is planned custody enforcement;
   their restoration assertions stay.

Test contract (r3's D10 row):
- Counter restored exactly (a body with counter n lowers to a machine
  whose next assigned id is n).
- Carried ids intact on the three variants.
- Protection mismatch in each direction.
- A restored child does not rerun initialization (an initialization
  effect counter stays at its carried value).
- Receiver-local store bindings do not change resolution.
- Both versions: a version-0 body still lowers as a fork (byte-for-
  byte the D7-pinned path), version 1 requires the custody inputs.

Acceptance criteria:
- Test-first per behavior; portable `.cljc`; JVM during iteration.
- `git diff` touches: src/cljc/yin/vm/ucf/handoff.cljc and its test
  files (handoff_v1_test.cljc and/or a new lower test file). Anything
  else: stop and report.
- The version-0 wire and fork semantics stay byte-frozen.

Constraints:
- No git writes. kondo and cljstyle may be sandbox-blocked; note it.
- `#?(:cljd nil :clj ...)` order for JVM-only test branches (:cljd
  first); a 0.0 literal is the integer 0 on JS.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report changed files, exact test/check outcomes with counts, the red
and green evidence, unresolved concerns, and any incomplete work. Do
not claim edits or tests that did not occur.

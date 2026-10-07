Created-GMT: 2026-10-05 14:25:00 GMT
Created-Local: 2026-10-05 21:25:00 +0700
Coding-Agent: claude (opus-5-5)
Session-ID: pending (provider-generated)

# Task: UCF M-next D7 — the version-aware reader and the version-1 grammar
Role: Yang Compiler and Universal AST Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-05 21:25:00 +0700 | Status: active | Rationale: implementation role, continuity with the C/D slice engineers

Implement D7 in /Users/sto/workspace/datomworld-d7 (worktree, branch
ucf-d7-v1-reader, based on master with D6 landed). Read first, in the
worktree's collab/: the D plan r3 (1791194000000-architect-m-next
-d-plan-r3.claude-fable-5-1.findings.md — sections 1.7 and the D7 test
contract), the astra review (1791191261015-architect-m-next-d-plan
-review.gpt-6-astra.findings.md, findings 7) and its confirmation
(1791192700000-architect-m-next-d-plan-r2-confirm.gpt-6-astra
.findings.md, residual 3 — the two-codec split), and the link-cursor
ruling's plan delta (1791203000000-architect-d6-link-cursor-ruling
.claude-fable-5-1.findings.md — the liftable-safepoint sentence for
cursorless link entries). Then src/cljc/yin/vm/ucf/handoff.cljc (the
version-0 lower path and its dao.stream.cbor decode), src/cljc/yin/vm
/ucf/checkpoint.cljc (`inspect`, the version-1 custody grammar), and
test/yin/vm/ucf/handoff_test.cljc.

The contract (r3 1.7 as amended by residual 3):

The reader pipeline, before any attachment, proposal or restoration, in
this order:

1. **Decode, two paths.** A body is version 1 only in
   `dao.jing.cbor` canonical bytes and version 0 only in the stage-1
   `dao.stream.cbor` codec (its tag-39 identifiers are refused by
   jing's decoder). Try the version-1 decode first (jing), then the
   version-0 decode (stream codec); retain which codec accepted the
   bytes. If neither decodes them, answer `:yin.k/undecodable`.
2. **Tag.**
3. **Version gate, before the address check.** A version the accepting
   codec does not speak, absent, or not of the integer kind is
   `:yin.k/profile-mismatch` (with found and supported versions).
4. **Version 1 only:** `checkpoint/inspect address bytes` — the
   body's address (`:yin.k/hash-mismatch`) and the custody grammar,
   including an entry for every install pending.
5. **Full recursive restoration validation:** code hashes, cells,
   registers, install responses, clause 5 (both versions' grammar:
   `:park` and `:call-effect` reasons, phases, halted forbids frames).

Version 0 skips step 4 and keeps its fork semantics: a version-0 body
still lowers as a fork on a reader that speaks both, and is refused
when exclusive is required. A version-0 body's bytes must decode with
the stream codec exactly as today (the v0 wire is frozen). Failure in
v1 structural validation must never trigger reinterpretation as v0.

`handoff-version` becomes the set `#{0 1}`.

Test contract (r3's D7 row + the rulings):
- Version fixtures on bytes: version 2, absent, a float `1.0`, a
  version-0 child in a version-1 root (`:yin.k/undecodable`), and
  unsupported-version-wins-over-a-wrong-address.
- Custody header fixtures: each required key omitted, a fork policy, a
  nil occurrence, an origin equal to the body's occurrence, header
  keys on a child, a halted child with an origin included refused and
  without one validating, header keys other than the origin on a
  halted root; `:yin.k/undecodable` with the path.
- Carried-id fixtures: an id on `:next`, a sequence at or above the
  counter, a duplicate id, an id in a body without an origin, an id
  naming the body's own occurrence; `:yin.k/undecodable`.
- Clause 5 on both versions: `:park` and `:call-effect` reasons and a
  missing install entry refuse at decode; a phase outside `:running`
  and `:parked` refuses; version 1 requires phase and parent and
  checks a child's carried ids in the root's context; a runnable child
  refuses export (the lift side is D9's; here the reader refuses what
  the grammar refuses).
- Every refusal asserts zero attach calls, zero proposals, and no
  machine.

Acceptance criteria:
- Test-first per fixture; portable `.cljc` (canonical byte fixtures
  over host-number semantics; the version-1 fixtures are built through
  the landed jing codec); JVM during iteration.
- `git diff` touches only src/cljc/yin/vm/ucf/handoff.cljc and its new
  test file test/yin/vm/ucf/handoff_v1_test.cljc (plus handoff_test.cljc
  only if a shared helper must move — say so if you do). Anything
  else: stop and report.
- The version-0 wire and lower path are unchanged; `handoff-version`
  is the only version-0-facing constant that changes.

Constraints:
- No git writes. kondo you may run; cljstyle is the orchestrator's.
- `#?(:cljd nil :clj ...)` order for JVM-only test branches (:cljd
  first); a 0.0 literal is the integer 0 on JS (use `(cbor/float64 0)`
  or 0.5).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report changed files, exact test/check outcomes with counts, the red and
green evidence, unresolved concerns, and any incomplete work. Do not claim
edits or tests that did not occur.

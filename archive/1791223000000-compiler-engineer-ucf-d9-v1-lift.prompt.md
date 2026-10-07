Created-GMT: 2026-10-06 17:25:00 GMT
Created-Local: 2026-10-06 00:25:00 +0700
Coding-Agent: claude (opus-5-5)
Session-ID: pending (provider-generated)

# Task: UCF M-next D9 — the version-1 lift as a pure encode, and the fixture regeneration
Role: Yang Compiler and Universal AST Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-06 00:25:00 +0700 | Status: active | Rationale: implementation role, continuity with the C/D slice engineers

Implement D9 in /Users/sto/workspace/datomworld-d9 (worktree, branch
ucf-d9-v1-lift, based on master with D8 landed). Read first, in the
worktree's collab/: the D plan r3 (1791194000000-architect-m-next
-d-plan-r3.claude-fable-5-1.findings.md — sections 1.8, 1.6 and the
D9 test contract), the astra review (1791191261015-..., finding 8 —
lift purity) and its confirmation (1791192700000-...), the rulings'
export-refusal deltas (1791195500000, 1791197000000, 1791198000000,
1791203000000), and fable's D8 sign-off carried-forwards
(1791221000000-architect-d8-signoff*.stdout.log): D9 must re-key the
served table by resource id with the retained descriptor (handles
cannot be journaled), and D9 owns the real prepare/encode split with
canonical-key ordering. Then src/cljc/yin/vm/ucf/handoff.cljc
(export-task, the D7 reader, lift-frame!), the D8 holder/export.cljc
(prepare/encode/abort), checkpoint.cljc and its fixtures
(test/yin/vm/ucf/checkpoint_fixtures.cljc, checkpoint-v1.txt), and
UCF 7.7.8's operation-sequence state.

The contract (r3 1.8 as amended + fable's carried-forwards):

1. **Prepare, then encode.** The export record retains every chosen
   resource identity, descriptor, occurrence, and remapping seed;
   encoding that prepared record performs no resource allocation or
   publication and produces canonical bytes deterministically. The
   served table is re-keyed by resource id (not host handle), carrying
   the retained descriptor, so a D14 journal can store the record.
2. **Determinism.** Two encodes of one prepared record give equal
   bytes on each host. Maps and sets, and anything derived from them
   such as cell numbering, are ordered by canonical encoded key bytes;
   wait order and alias identity are never reordered.
3. **The version-1 header on lift.** The custody header on blocked
   and parked roots; origin only on a halted root; none on children;
   version 1 with phase and parent on install entries (D7's reader is
   the normative grammar — lift and reader agree).
4. **The two new lift refusals.** `:unprotected-pending` for a first
   exclusive export over an attempted write to an enrolled stream,
   and `:op-seq-exhausted` at 2^52-1. NOTE the open design question:
   where lift reads the protection classes from on a first export
   (the composition declares them; the custody map exists only after
   a grant). If the r3 text does not determine the input, STOP and
   report — that is a ruling, not an engineering choice.
5. **A task holding a held observation or a cursorless link entry
   refuses export** (the D6/D8 refusals; keep them through the new
   split).
6. **Fixture regeneration.** The C4 accepted fixtures are regenerated
   through this lift and their pinned addresses change in this
   commit; the refusal fixtures stay mutations of those bases. D7's
   reader must accept every regenerated v1 fixture (round-trip).

Test contract (r3's D9 row + the rulings):
- Two encodes of one prepared record, on each host, give equal bytes
  (portable `.cljc`; the JVM leg during iteration, the three lanes at
  landing are the real cross-host proof).
- Fixtures include a float, signed zero, NaN, nested maps, shared
  cells and children.
- `:unprotected-pending` and `:op-seq-exhausted` (if the protection
  question resolves without a ruling).
- A task with a held observation, in the root or a child, refuses
  export.
- An envelope-shaped program value survives in the body as payload.
- The C4 accepted fixtures are regenerated here; their pinned
  addresses change in this commit; D7's reader accepts them.

Acceptance criteria:
- Test-first per behavior; portable `.cljc`; JVM during iteration.
- `git diff` touches: src/cljc/yin/vm/ucf/handoff.cljc,
  src/cljc/yin/vm/ucf/holder/export.cljc (the re-keying),
  test/yin/vm/ucf/checkpoint_fixtures.cljc, checkpoint-v1.txt, and
  their test files. Anything else: stop and report.
- The version-0 wire and emitted-version stay 0.

Constraints:
- No git writes. kondo and cljstyle may be sandbox-blocked; note it.
- `#?(:cljd nil :clj ...)` order for JVM-only test branches (:cljd
  first); floats in fixtures only through `(cbor/float64 ...)`.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report changed files, exact test/check outcomes with counts, the red
and green evidence, unresolved concerns, and any incomplete work. Do
not claim edits or tests that did not occur.

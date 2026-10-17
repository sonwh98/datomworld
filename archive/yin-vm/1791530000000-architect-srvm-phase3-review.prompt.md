Created-GMT: 2026-10-10 10:00:00 GMT
Created-Local: 2026-10-10 17:00:00 Asia/Bangkok

# Task: Architecture review of semantic-register-vm phase 3 (linearizer, validator, liveness) and the item-13 design ruling

Role: Lead System Architect (continuing thread 01a121d7-5ba4-71b1-a70b-f6b4cf3d056a)

Implementers:
- Model: gpt-6.1-sol | Assigned: 2026-10-10 17:00 +07 | Status: active | Rationale: the code was written by claude-opus-5-5 and verified by a Claude orchestrator; sign-off must come from a non-Claude family.

Perform a read-only review of the phase-3 implementation of the design
you froze (`docs/design/yin.vm.semantic-register-vm.md`, five rounds,
READY_TO_FREEZE), plus one design amendment made since the freeze.

You are in the design worktree. The implementation lives in a sibling
worktree you can read but must not edit:
`/Users/sto/workspace/datomworld-srvm-p3`, branch `srvm-phase3`, commit
`7a010962` (one commit on top of master 018d55fe). The design amendment
is commit `3e93b063` on branch `semantic-register-vm` in THIS worktree
(one commit on top of master): §3.4 item 13 now compares the whole
re-projected vector (ids and layout), ruled by the orchestrator acting
as Architect on the implementer's unresolved concern that an id-only
comparison accepts swapped bodies.

Read first:
- `docs/design/yin.vm.semantic-register-vm.md` as amended (`git show
  3e93b063` for the diff)
- `/Users/sto/workspace/datomworld-srvm-p3/collab/1791530000000-vm-engineer-srvm-phase3.report.md`
  (round-2 report: outcomes, the seven design ambiguities and the
  readings taken, the resolved concern)
- `/Users/sto/workspace/datomworld-srvm-p3/src/cljc/yin/vm/semantic_register/{code,linearize,analysis}.cljc`
- `/Users/sto/workspace/datomworld-srvm-p3/test/yin/vm/semantic_register/{corpus,code_test,linearize_test,load_paths_test,analysis_test}.cljc`
- For comparison: `src/cljc/yin/vm/code.cljc`, `src/cljc/yin/vm/linearize.cljc`
  (the stack-shaped originals, untouched)

Verified by the orchestrator before this review, in the sibling
worktree, foreground: JVM 33 tests / 1133 assertions / 0 failures / 0
errors; Node the same four namespaces listed with the same counts; Dart
all shards passed; `clj -M:kondo` 0 errors 0 warnings; `cljstyle check`
clean; `git diff --cached --check` clean. The old VM, linearizer, code
namespace and all existing tests are untouched (diff is 8 new files).

## Evaluate

1. **Fidelity to the frozen design.** §2.3 table (operand kinds,
   positions), §3.1 grammar and the result-or-terminal outcome
   relation, §3.2 exclusive definitions by structured paths including
   the empty set, §3.3 walk and minting (pre-order, body-local, arm and
   resume refinements, FIFO body queue, occurrence expansion,
   saturation), §3.4 rules 1-13 in order with first-defect-wins, §8.1
   CFG / def / use / L / saved. Name any divergence.
2. **The seven ambiguity readings** in the report: accept, or say which
   needs a design change. In particular: (a) rules 10/12 reduced to
   kind checks with layout syntax discharged by rule 6's parse; (c)
   minting read back from the vector for id-less expressions; (d) a
   tail call's syntactic `def`; (f) the load-paths reading given that
   vector->datoms projection was scrapped 2026-09-18, which leaves
   code-as-tuples §7.2's "malformed input, same refusal on both paths"
   unexercised -- is that acceptable for phase 3?
3. **The item-13 amendment and its implementation.** Is comparing the
   full re-projected vector the right rule, is `:noncanonical-layout`
   correctly distinguished from `:noncanonical-registers`, and does the
   implementer's note (label placement cannot differ without
   re-parsing as another tree; only body order can) hold?
4. **Tests as gates.** Are the §10 phase-3 gates met: deterministic
   vectors across three hosts (same inline goldens AND pinned
   addresses), a refusal row per rule, load-path agreement through one
   validator, L against an independent reference (confirm the reference
   is genuinely independent)? Any gap?
5. **Portability.** Anything in the code that will bite only on Dart or
   Node (the repo's known traps: reader conditionals, unary minus,
   `for` over long seqs, multi-key assoc on nil, protocol param names,
   EDN whitespace before closers)?
6. **Readiness for phase 4** (the evaluator): does the `load-vector`
   image `{:vector :address :bodies [{:start :end :owner :registers}]}`
   carry what phase 4 needs, or should phase 3 expose more (e.g. the
   parsed trees, per-pc outcome, saved sets)?

Distinguish defects (must fix before merge), gaps (fix before phase 4),
and notes. Do not edit files.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>

Then: severity | file:line | evidence | correction. Then items 1-6.
End with one line: VERDICT: READY_TO_MERGE | CHANGES_REQUESTED.

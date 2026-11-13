Created-GMT: 2026-11-13 02:50 GMT
Created-Local: 2026-11-13 09:50 +0700
Dispatched-By: master-green orchestrator seat (interactive)
Destination: qwen3 CLI --model qwen4-max-preview (first in-repo evaluation of this model)
Task-ID: 1794534600000-reviewer-vm-cljd-cell-ref-fix.prompt.md

# REVIEW: branch `vm-cljd-cell-ref-authenticity` @ `a3f7ab8d` (READ-ONLY; git + file reads only)

Independent review of a codex-implemented VM fix (implementer family: codex; you are
non-codex per the independence rule). Do not run test suites; inspect only.

## Inputs

- `git fetch origin vm-cljd-cell-ref-authenticity` — one commit off master: one line in
  `src/cljc/yin/vm/engine.cljc:2450` (`(assoc entry :value v, :seal seal)` →
  `(assoc (or entry {}) :value v, :seal seal)`).
- Findings: `collab/exception-track/1794532800000-engineer-vm-cljd-forged-cell-ref-fix.codex-gpt-6.1-terra.findings.md`
- Problem statement: `collab/exception-track/1794531000000-orchestrator-provenance-primitive-forged-cell-refs-on-cljd.md`
- Context code: `authentic-ref?` (engine.cljc:332-354), the primitive `git show ff8b980a`,
  the portability precedent `30f7aaf7`.

## Questions

1. Mechanism soundness: does "cljd compiles multi-pair `assoc` on nil via a conj-shaped
   fallback producing a non-map entry" explain BOTH failure modes (forged seal mismatch AND
   the `contains?`-missing ids seen in the nightly)? Check the findings' evidence for the
   red-phase repro and post-fix greens; is the claimed 266→0 story consistent with the
   per-scope results recorded?
2. Semantic neutrality on JVM/cljs: `(or entry {})` is identity for map entries — confirm the
   heap-entry lifecycle never stores a non-map, non-nil entry that this would replace
   (search :heap update sites). Confirm provenance-sibling preservation claims (subsequent
   heap-write path).
3. Scope discipline: one src line, no test expectations touched, no goldens touched.
4. The skipped slow guards on the frontend dart spot ns (the findings flag this honestly):
   decide whether the fix's dart verification is sufficient WITHOUT a slow-tier dart rerun,
   or whether landing must be preceded by one scoped slow dart run (name the exact command).
   The nightly will re-prove everything anyway; your call should reflect the 2026-11-07
   merge bar for fix branches (previously-failing tests green).
5. Anything in the findings that contradicts the branch or the rc claims (do not trust the
   summary; check the recorded command lines).

## Deliverable

Verdict `APPROVE` or `REQUEST CHANGES` (numbered items) appended to the findings file under a
`REVIEW (qwen4-max-preview)` heading, plus one sentence: strongest reason it is/isn't safe
to land as-is. Also state, in one line, your overall capability impression of this model
against the qwen3.8-max review precedent in the same file (this is the model's first in-repo
round; team.md records it as pending evaluation).

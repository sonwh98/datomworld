Created-GMT: 2026-09-15 21:25:00 GMT
Created-Local: 2026-09-16 04:25:00 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: glm
Session-ID: 541aa172-7582-4698-aee7-0ca0434052b3
# Task: dao.jing canonical encoder — fold in Architect's nonblocking r1 findings
Role: Storage & Indexing
Implementers:
- Model: glm-5.3 | Assigned: 2026-09-16 04:25:00 +07 | Status: active | Rationale: same implementer, resumed session, follow-up fixes to own work

## Context

Your P0 fix (`src/cljc/dao/jing.cljc`, `test/dao/jing_test.cljc`) passed
independent adversarial review (deepseek-v4-pro) and Lead Architect
sign-off (claude-fable-5-1): **APPROVE-WITH-FINDINGS (nonblocking)**. Full
review at `collab/1789522900000-architect-signoff-dao-jing-fix.claude-fable-5-1.stdout.log`.
The Architect explicitly asked for one finding to be folded into this same
commit since the lines are already touched. Read the full Architect
review first for exact wording; summary of what to fix:

## Task

**Finding 2 (fold in — the requested fix).** The map and set comparators
in `order-normalize` still sort by `#(compare (pr-str %1) (pr-str %2))`,
not by `canonical-print`. The Architect proved this is a real crack:
binding `*print-length*` to 1 changes the hash of a two-key map whose
keys are vectors, because tied `pr-str` output collapses distinct keys
inside the sorted map and one entry is silently dropped; binding
`*print-meta*` true reorders a metadata-bearing key; binding
`*print-readably*` false makes a string and identically-spelled symbol
collide. Your own `canonical-print` function already exists and is
metadata-aware and ambient-binding-independent — the comparators just
need to call it instead of raw `pr-str`. Change both comparators (map key
sort, set element sort) to `#(compare (canonical-print %1) (canonical-print %2))`.
Verify canonical-print's forward reference is available at that point in
the file (it's defined after order-normalize currently — you may need to
reorder or use a forward `declare`).

**Finding 5 (cheap, same file, fold in).** The record-rejection message
in `order-normalize` is a multi-line string literal with embedded
newlines and indentation runs (currently spans lines ~82-87). Make it a
single-line string.

**Finding 6 (cheap, same file, fold in).** Add one explicit test
assertion: a vector with `:line`/`:column` reader-position metadata
hashes identically to the bare vector (the docstring already claims this,
but only the quoted-list-vs-seq test exercises it indirectly).

## Do NOT do

- Do not attempt Finding 1 (backend/wire fail-closed checks in
  `dao/jing/file.cljc` and `dao/stream/transit.cljc`) — the Architect
  explicitly scoped that to a separate future unit, not this commit.
- Do not touch `docs/design/dao.jing.md` — the orchestrator is amending
  it directly to record Finding 1 as an Open Item and Finding 3's
  documentation gaps (both doc-only work, out of your scope).
- Do not touch anything outside `src/cljc/dao/jing.cljc` and
  `test/dao/jing_test.cljc`.

## Verification

Re-run `clojure -M:test -n dao.jing-test` and confirm 0 failures (assertion
count will grow by at least 1 from Finding 6's new test). Also re-run the
Architect's own reproduction: confirm that after your comparator fix,
binding `*print-length*` to 1 no longer changes the hash of a two-key map
with vector keys (construct that probe yourself and report the before/
after).

## Deliverable

Report exact diff, test results, and the before/after probe for Finding
2's fix. Write findings to
`collab/1789523100000-storage-jing-canonical-fix-r2.glm-5.3.findings.md`
with the same header block as this prompt. Nothing staged or committed.

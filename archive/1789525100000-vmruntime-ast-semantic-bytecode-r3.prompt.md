Created-GMT: 2026-09-15 21:58:20 GMT
Created-Local: 2026-09-16 04:58:20 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: claude
Session-ID: 206534e8-0432-4941-8631-0212c8f59132
# Task: ast->semantic-bytecode r3 — fix 2 real bugs, explicitly scope out 2 pathological findings
Role: VM Runtime
Implementers:
- Model: claude-opus-5 | Assigned: 2026-09-16 04:58:20 +07 | Status: active | Rationale: same implementer, resumed session

## Context

gpt-6-astra's r2 re-review
(`collab/1789524900000-review-ast-semantic-bytecode-r2.gpt-6-astra.stdout.log`)
found 5 findings against your r2 fix. The orchestrator has triaged them:
two are real, practically-reachable bugs to fix now; two are genuinely
pathological inputs no real frontend or reconstruction path would produce,
which the orchestrator judges belong in the same "disclosed, deferred
residual" category as `dao.jing`'s own already-accepted pathological-symbol
and scalar-metadata gaps — not fixed in this transitional-encoder-dependent
code, just accurately disclosed. This scoping decision will be checked by
the Architect alongside the code; you are not being asked to make it, just
to implement it as specified below. The fifth finding is folded into one
of the two real fixes.

## Fix these (real, reachable)

**Finding 2 (their numbering) — `strip-reader-positions` corrupts
MapEntry payloads.** `(first {:a 1})` produces a `clojure.lang.MapEntry`
(or host equivalent), which is `vector?` but `(empty entry)` returns
`nil`/throws rather than an empty vector, so `(into (empty x) ...)`
silently produces a reversed LIST instead of the original two-element
vector — both value and address change. Root cause: not every `vector?`
value has a vector-shaped `empty`. Fix: in `strip-reader-positions`'
vector branch, don't rely on `(empty x)` — build a fresh plain vector
explicitly (e.g. `(mapv strip-reader-positions x)` for the vector case,
keeping the existing `into (empty x)` approach only for map/set where
`empty` is reliable, or find another host-portable way to detect and
handle a MapEntry specifically). Verify: `(strip-reader-positions (first
{:a 1}))` must equal `[:a 1]`, not `(1 :a)`.

**Finding 5 (their numbering) — `:syms`/params vector metadata round-trip
asymmetry.** Projection (`mapv strip-symbol-meta v` in the `:syms` case)
always discards the params VECTOR's own metadata (only element/symbol
metadata was ever a target). But `dao.jing/segment-key` DOES hash vector
metadata (per the P0 fix now committed, `0cafb2d`), so a hand-constructed
row with a metadata-bearing params vector can still hash correctly and
pass reconstruction's `(and (vector? v) (every? symbol? v))` check,
creating a row that reconstructs fine but changes address on
re-projection — a `rows → map → rows` identity violation for that row
(the round-trip law is unqualified over all rows per §7.2, not just rows
produced by this encoder). Fix: in reconstruction's `:syms` validation,
also reject (throw `:slot-kind`) if the params vector itself carries any
metadata beyond what would survive projection (i.e., require `(nil?
(meta v))`, since projection's `mapv` always produces a metadata-free
vector). This makes reconstruction agree with what projection can ever
actually produce, closing the asymmetry without trying to preserve
metadata `:syms` was never meant to carry.

## Explicitly scope OUT of this unit (document, do not fix)

**Finding 1 (their numbering) — metadata nested inside metadata.**
`(with-meta [] {:note (with-meta 'x {:meaning 1})})` vs. `{:meaning 2}` —
`same-meta?` compares metadata with plain `=`, not recursively. This
requires metadata to be attached to a value that is ITSELF inside another
value's metadata map — not a shape any map-AST frontend or any part of
this codebase's own test corpus produces. Add one sentence to
`same-meta?`'s docstring (or `ast->semantic-bytecode`'s) stating this
scope boundary explicitly: metadata-on-metadata is not compared
recursively; a value carrying metadata that itself carries semantically
distinct nested metadata is out of scope for the collision guard,
matching the same class of pathological-input residual `dao.jing.md`
already documents for its own encoder (pathological symbols, scalar
metadata). Do not attempt a fix.

**Finding 4 (their numbering) — a custom-comparator sorted set holding
`=`-equal, metadata-distinct elements.** This requires the input AST to
contain a set built with a non-default comparator that admits two
`=`-equal-but-metadata-distinct elements — something no ordinary Clojure
set construction (`#{...}`, `set`, `into #{}`) can produce, and nothing in
this codebase constructs. Document the same way: `same-meta?`'s set-branch
element matching assumes ordinary set semantics (at most one occurrence
per `=`-equivalence-class); a set built with a comparator that violates
that assumption is out of scope. Do not attempt a fix.

## Already correctly handled (per the reviewer's own confirmation, no
action needed)

- Records still reach `dao.jing`'s rejection unchanged (verified by the
  reviewer).
- Reader-position stripping is address-neutral under default print
  bindings (the reviewer's caveat about `*print-meta*` binding reaching
  scalar symbol metadata is the same disclosed `dao.jing` residual
  already tracked in `dao.jing.md`'s Open Items, not new here).
- Byte arrays: `dao.jing.md` already documents identity-based hashing as
  an Open Item; no change needed in this file for that.

## Contract

Add tests reproducing Findings 2 and 5's failure scenarios (from the
reviewer's exact reproductions) and asserting they're now fixed. Re-run
`clojure -M:test -n yin.vm-test`, confirm 0 failures. Do NOT add tests
for Findings 1/4 (pathological, explicitly out of scope) — if you want to
note them, a comment is fine, but don't build test infrastructure for
inputs the codebase will never construct.

## Boundaries

Only `src/cljc/yin/vm.cljc` and `test/yin/vm_test.cljc`. Do not
touch `dao.jing.cljc` (already committed, `0cafb2d`) or `dao.jing.md`.
`bb test:cljd`: check `ps aux | grep cljd` or similar yourself to confirm
no other process holds the lane before running it; if you can't tell,
skip it and say so explicitly (do not just skip silently as before).

## Deliverable

Report exact diff, reproduction-then-fix evidence for Findings 2 and 5,
the exact disclosure wording you added for Findings 1 and 4, and test
results. Write findings to
`collab/1789525100000-vmruntime-ast-semantic-bytecode-r3.claude-opus-5.findings.md`
with the same header block as this prompt. Nothing staged or committed.

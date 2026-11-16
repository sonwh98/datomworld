Created-GMT: 2026-11-16 18:13:41 GMT
Created-Local: 2026-11-17 01:13:41 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: codex
Session-ID: pending (provider-generated)
# Task: yang-compilers-dedup-step7-review
Role: Reviewer (step-7, independent)

Implementers:
- Model: gpt-6.1-sol | Assigned: 2026-11-17 01:13:41 +0700 | Status: active | Rationale: independent of the gpt-6.1-terra implementer; review-only dispatch per the orchestrator workflow step 7

You are the independent step-7 reviewer for a mechanical deduplication of the yang.*
target-language compilers. The implementer (gpt-6.1-terra, separate thread) extracted five
blocks of duplicated logic into shared `yang.core.*` namespaces. Your job: review the diff
below for behavioral fidelity and craftsmanship. READ-ONLY — you edit nothing; your final
response is the deliverable.

## The change under review

Base: origin/master f32a4392 (post-landing master). 13 files, +152/−182 net, two new
namespaces. The full patch follows in the file referenced below (piped to your stdin after
this prompt text).

The five extractions:
1. `yang.core.siteset-store` (NEW): `load-tree` (parameterized by reader + absence
   predicate) and `overlay` (buffered write handle) — previously private near-copies in
   `yang.php.antlr.siteset` and `yang.python.antlr.siteset`.
2. `yang.core.siteset-publication` (NEW): `signed-entry`, `entry-datoms`, `signed-entries` —
   identical copies in both target sitesets. Discovery policy deliberately stays per-target.
3. `yang.core.prelude`: `balanced-definitions->uast` (from php prelude:6212 / ts prelude:4327)
   and `definitions->uast` (seq-nodes form). Python prelude's own seq-nodes usage unchanged.
4. `yang.core.prelude`: `definitions->uast` shared by all three safepoints.
5. `yang.core.lower`: `code-point->str` (py lower:344 / ts lower:245) and `build-vector`
   (vconj symbol as data). Hex-value diagnostics deliberately stay in callers.

## The invariants of record (verify each against the diff)

a. `load-tree`: first-missing traversal order (depth-first over `vm/semantic-bytecode-grammar`
   slots, `:node`/`:nodes` cases) and the `{:status :missing, :address a}` shape identical.
b. `overlay`: buffer-first reads, insert-order buffered puts, duplicate put → `:present`
   without re-buffering, flush writes exactly the buffer in put order, drop-writes-nothing;
   the `#?(:cljs (js-obj) :default (Object.))` identity sentinel construction unchanged.
c. Envelope construction byte-identical (content addresses depend on it): same key + manifest
   + record → same signed map. Independent evidence: a golden check (fixed key, fixed triple)
   recomputed pre/post-dedup came out BYTE-IDENTICAL, and focused siteset namespaces ran
   25 tests / 205 assertions / 0 failures on the patched tree.
d. Balanced `sequence-tree` shape unchanged: midpoint split, left-to-right effects,
   last-value behavior, empty-input behavior.
e. No behavior change in any wrapper: wrappers are thin call-throughs with the target's
   reader/predicate/symbol; nothing else moves.
f. Layering: `yang.core.*` requires only foundation (`yin.vm`, `yin.vm.linker.sign`,
   `yang.core.uast`, `yang.antlr.packet`); no target namespace is required by core; no
   require cycles introduced.
g. Scope discipline: nothing beyond the five extractions moved (no drive-by refactors, no
   fixture edits, no test edits).

## What to check

1. Read the full patch. For each extraction, compare the shared body against the deleted
   per-target bodies (visible as `−` lines) — flag ANY semantic difference, however small
   (ordering, default args, nil-handling, exception text).
2. Check the wrappers pass the right parameters (php vs python reader/absent-predicate
   differences; py/vconj vs js/vconj symbols).
3. Check require hygiene: no unused requires introduced, no cycles, cljc reader-conditionals
   preserved where the originals had them.
4. Flag anything you would NOT have factored, and any defect with a severity:
   [P0] behavior change / [P1] latent risk / [P2] craftsmanship.

## Report format (mandatory)

First two lines exactly:

```
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07>
```

Get the real time immediately before writing (date -u; TZ=Asia/Ho_Chi_Minh date).

Then findings per extraction, then the machine verdict as your LAST line, exactly one of:

```
APPROVED
CHANGES STILL REQUIRED — <one line>
PROOF FAILS — <one line>
```

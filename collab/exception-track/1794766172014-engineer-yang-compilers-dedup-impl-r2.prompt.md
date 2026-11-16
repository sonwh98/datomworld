# Brief (r2): implement the yang compiler dedup per the completed audit (DRY round)

Destination: codex exec -m gpt-6.1-terra on jupiter (POOL 2, `-c sandbox_mode="danger-full-access"`), session YANG-DEDUP-IMPL-R2
Worktree: `/tmp/yang-audit-wt` on jupiter, detached at `origin/master` `2382e9cf` (post-landing
master: the eval-bridge, the PHP rounds, and the callsite/DHT/T2 work have all landed; the
include-test red was resolved by `46bf50bd`).

**This round supersedes r1** (session YANG-DEDUP-IMPL, master `c401bf94`, killed before its
test phase when the base was found stale). Any `yang-dedup` WIP you may find is discarded —
do not look for it; work from this brief and the clean checkout only.

Binding context from the landing (yang.php orchestrator's coordination note
`collab/yang-php/1794759203000`): the 22-fixture oracle corpus (php:8.3.35) is parity evidence
of record — **preserve the fixtures (`programs_p7b.cljc`, the eval suite files) and their
expectations**; if your refactor changes observable behavior, stop and report rather than
adjusting the fixtures. The astra atomicity ruling (1794615000000) and byte-domain ruling
(1794605712000) are binding. The lower.cljc refusal paths that survive do so **by design**
(unconfigured hosts only) — do not remove or unify them; your deduplication targets the
supported bridge expression paths.

You are running on **jupiter** (Linux, x86_64). You may edit files in the worktree and run
tests there. **Do not commit, do not push, do not touch `collab/`** — the orchestrator owns
commits and pushes. Leave your work as uncommitted changes in the worktree; the orchestrator
will inspect, verify, and commit. Do NOT create scratch files inside the repo.

## Context: the audit you are implementing

A read-only audit (session YANG-DEDUP, same worktree, same master SHA) identified these
factoring opportunities, ranked. Implement **all five**, respecting each one's stated
invariants. The audit's evidence is quoted here; re-read the cited regions yourself before
editing.

1. **PHP/Python siteset store (~50–60 lines).** Identical `load-tree` bodies
   (`src/cljc/yang/php/antlr/siteset.cljc:166` and `src/cljc/yang/python/antlr/siteset.cljc:310`,
   ~27 lines each) and identical buffered `overlay` bodies (php:195, py:459, ~33 lines each).
   Factor into a shared namespace (e.g. `yang.core.siteset-store`), passing the address reader
   and its absence predicate in, or moving reader+sentinel with the code.
   **Invariants:** the `::absent` sentinel's namespace and its `#?(:cljs … :default …)`
   construction must remain unchanged; first-missing traversal order and duplicate-write
   behavior must be identical; the overlay's staged mutation stays scoped to a single derivation.

2. **Balanced prelude emitter (~10–15 lines).** The same ~14-line balanced `sequence-tree`
   implementation in `src/cljc/yang/php/antlr/prelude.cljc:6212` and
   `src/cljc/yang/typescript/antlr/prelude.cljc:4327` (maps `[key form]` to `u/def!`, splits at
   vector midpoint). Move to a pure helper in `yang.core.prelude`.
   **Invariants:** identical tree shape, left-to-right effects, last-value behavior, empty-input
   behavior — content addresses depend on the shape. **Do NOT change Python's** `u/seq-nodes`
   variant (py prelude:3684 — re-locate by grepping `seq-nodes` if the line moved) to balancing.

3. **Site-publication envelope (~20–30 lines).** `signed-entry` and `entry-datoms` bodies are
   identical (php siteset:81 / py siteset:144, ~17 lines each); `signed-entries` is the same
   map-and-sort (php:125 / py:541). Move the pure operations to a shared namespace
   (e.g. `yang.core.siteset-publication`); keep public aliases in the per-target namespaces if
   call sites read better.
   **Invariants:** do NOT unify discovery — PHP (php siteset:115) additionally asserts the
   envelope principal matches the verifying key; Python (py:185) does not. That divergence is
   deliberate policy; preserve it.

4. **Safepoint definition emitter (~5–10 lines).** The same `u/seq-nodes` conversion from
   definition pairs in php safepoint (~:40), py safepoint (~:361), ts safepoint (~:119) — re-locate by grepping `seq-nodes`. Named helper in
   `yang.core.prelude`, keeping `u/seq-nodes`' exact shape.

5. **Literal helpers in lower (~5–10 lines).** Identical `code-point->str`
   (py lower:344 / ts lower:245, including all three host reader branches) → expose directly
   from `yang.core.lower`. `build-vector` (py:234 / ts:177) differs only in the emitted
   `py/vconj` vs `js/vconj` symbol → share with that symbol as data.
   **Invariant:** keep hex-value validation and diagnostics in the callers (they genuinely differ).

## Hard constraints

- **Do not factor anything beyond the five items above.** In particular: leave guest preludes,
  rendering, scope walkers, `wrap-escape`, site admission/derivation, and publication wrappers
  untouched — the audit explicitly ruled them target-specific (semantics, content addresses, or
  real policy differences). If you notice additional duplication, note it in the report as a
  candidate; do not act on it.
- Preserve each shared function's exact behavior. The refactor is mechanical: same logic, moved
  and parameterized. No renames of public API beyond the aliases you introduce, no drive-by
  cleanups.
- Match surrounding code style (see `docs/design/datom.world.md`).
- **Never claim a run that did not happen.** Report exactly which tests you ran, with outcomes.

## Verification (merge bar)

On jupiter, run the focused tests covering the touched namespaces. Use the repo's runner
(`clojure -M:test -n <ns> [-v <ns>/<var>]`; see `docs/agents/` and `build-n-test` docs for the
canonical form). Run each affected test namespace individually, not the whole suite (suite-scale
runs are orchestrated separately). At minimum, run the siteset, prelude, safepoint, and lower
test namespaces for the affected targets — discover them (`ls test/`, grep for the namespaces)
and list them in your report. All must pass. If a test fails, fix your refactor (do not weaken
or edit tests; if a test's expectation must move, that means your refactor changed behavior —
fix the refactor).

## Report format (mandatory)

First two lines exactly:

```
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07>
```

Get the real time (`date -u '+%Y-%m-%d %H:%M:%S GMT'`; `TZ=Asia/Ho_Chi_Minh date '+%Y-%m-%d %H:%M:%S %Z'`)
immediately before writing the report.

Then:
1. For each of the five items: what moved where (file:line refs, before/after line counts),
   the parameterization seam you chose, and any aliases introduced.
2. Test evidence: exact commands and pass counts per namespace.
3. `git diff --stat` output for the whole change set.
4. Anything noticed but deliberately not done (candidates for a future round).
5. End with exactly one line:

```
VERDICT: DONE — <one line> | or | BLOCKED — <why>
```

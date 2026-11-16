# Brief: audit the yang.* compilers for redundant code that can be factored into a common namespace

Destination: codex exec -m gpt-6.1-terra on jupiter (POOL 2, `-s read-only`), session YANG-DEDUP
Worktree: `/tmp/yang-audit-wt` on jupiter (detached at origin/master `c401bf94`). Run codex with this worktree as its working directory.

You are running on **jupiter** (Linux, x86_64). The worktree at `/tmp/yang-audit-wt` is a
fresh detached checkout of datom.world `origin/master` at `c401bf94`. This is a **READ-ONLY
audit**: you edit nothing, commit nothing, push nothing, and do not touch `collab/`. Your
final response IS the deliverable.

## The question

The yang.* target-language compilers share large amounts of structurally similar code. Audit
them for redundancy that could be factored into a common namespace and reused. Concretely:

1. **Inventory the compiler surface.** Primary: `src/cljc/yang/{php,python,typescript,clojure}/antlr/`
   (`prelude.cljc`, `lower.cljc`, `scope.cljc`, `safepoint.cljc`, `siteset.cljc`, `render.cljc`,
   `builtins.cljc`, …) plus shared roots `src/cljc/yang/{core,antlr,frontend,stage,safepoint,tails}.cljc`.
   Also note `src/cljc/yang/php/antlr/prelude_p9.cljc` and `prelude_module.cljc` (what are they
   for; are they near-duplicates of `prelude.cljc`?). ~38k lines total.
2. **Find the redundancy.** For each pair/group of target compilers, identify functions or
   blocks that are copied or near-copied (same logic, only target-syntax differences). For each
   cluster report:
   - the namespaces and line ranges involved (exact `file:line` refs, with approximate line counts);
   - how similar (identical / trivially parameterized / structurally similar but genuinely
     target-specific);
   - what a shared factor would look like (common namespace name, the parameterization seam —
     e.g. data-driven emission, protocol/multimethod, or higher-order helper) and the estimated
     deduplicated line savings;
   - the risk: anything that makes factoring unsound (macro-heavy prelude generation, target
     ordering quirks, `.cljc` reader-conditionals vs runtime divergences).
3. **Prioritize.** Rank the top ~5 factoring opportunities by (savings × soundness) and flag
   any you would NOT factor and why. Be honest when near-identical code is better left
   duplicated (e.g. preludes are embedded strings/quoted forms whose duplication IS the
   contract).

Sanity-check similarity claims by actually diffing/reading the paired regions — do not judge
from file names or size alone. Cite evidence (a representative diff hunk or function pair) for
every major claim.

## Report format (mandatory)

First two lines exactly:

```
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07>
```

Get the real time (`date -u '+%Y-%m-%d %H:%M:%S GMT'`; `TZ=Asia/Ho_Chi_Minh date '+%Y-%m-%d %H:%M:%S %Z'`)
immediately before writing the report — do not guess.

Then the findings per the structure above, ending with one summary line:

```
VERDICT: <top opportunity in one line; or "NO ACTIONABLE REDUNDANCY" if none found>
```

If a read you need is impossible (missing tool, unreadable file), say so plainly rather than
inventing the result.

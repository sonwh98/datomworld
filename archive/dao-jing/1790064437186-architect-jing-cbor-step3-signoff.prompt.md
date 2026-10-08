Created-GMT: 2026-09-22 08:07:17 GMT
Created-Local: 2026-09-22 15:07:17 +07 (Indochina Time)
Coding-Agent: codex
Session-ID: 01a0c501-5311-71f0-94e5-033950e0473d (resumed: your J0-J3 DaoJing CBOR sign-off thread)
# Task: dao-space-comparators-signoff: architectural sign-off of step 3 (portable numeric wiring)
Role: Architect
Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-22 15:07:17 +07 | Status: active | Rationale: you own the sign-off for this section's comparator changes, per your original work package's routing table and your own "needs explicit sign-off from dao.space's owners... separate from Jing's own review" clause

Work in /Users/sto/workspace/worktree-dao-space-comparators (your launch
directory; branch dao-space-comparators, HEAD 92103d8b, committed). READ-ONLY:
edit nothing. BE ECONOMICAL. Give the complete answer now.

## What happened since J0-J3 (your last turn on this thread)

J0-J3 (the frozen CBOR fixture corpus, the JVM/Node codec, the Dart codec,
the three-host conformance gate) are all committed and signed off, matching
where you left this. Since then, entirely outside your prior involvement:

1. The owner ruled directly on the two open items from your original work
   package's "Numeric identity" section:
   - `dao.space.query`'s `=`/`not=` and Datalog unification stay
     KIND-STRICT, matching content addressing's full kind-strictness
     (kind, decimal scale, AND float zero sign) -- NOT the portable
     cross-kind `equiv`/`num=` your J0-J3 corpus already defines for other
     purposes. `(= 1 1.0)`, `(= 1.0M 1.00M)`, and `(= 0.0 -0.0)` must all
     be `false`. Discovered during implementation that host `=` cannot
     deliver this alone (`(= 0.0 -0.0)` is `true` on the JVM), so
     `dao.jing.cbor` gained a NEW `content=`/`content-key`/`content-hash`
     (kind-strict, distinct from the existing loose `equiv`/`num=`, which
     are unchanged and still used for encode/decode duplicate-collapse
     detection).
   - `min`/`max`'s tie-break on a numeric tie: (1) an exact operand
     (integer/decimal/rational) always beats float64; (2) otherwise the
     SHORTER canonical CBOR encoding wins; (3) otherwise canonical byte
     order (the profile's existing unsigned bytewise order) is the final
     tiebreak.
   - Both rulings are recorded in docs/design/dao.jing.cbor.md's Numeric
     identity section (read it in full now; it has changed substantially
     since your last read).
2. Step 3 itself was then implemented: `git show 92103d8b --stat` and
   `git show 92103d8b` (or read the files directly) -- `dao.jing.cbor.cljc`
   (additive: `content=`/`content-key`/`content-hash`/`encoded-compare`),
   `dao.space.index.cljc` (`type-rank`/`compare-vals` now bucket every
   Jing numeric carrier and route through `num-compare`), `dao.space.
   query.cljc` (the `=`/`not=`/ordering/`min`/`max` builtins, `unify`,
   `select-by-index`'s range scan, `content-distinct` replacing host
   `distinct` wherever a query dedupes rows that could carry a datom
   value).
3. Reviewed independently by deepseek-v4-pro (READY) and qwen3.8-max
   (READY WITH CHANGES, applied) -- both artifacts are in this worktree's
   collab/ if you want the detail, but you do not need to re-verify what
   they already checked; focus your budget on architectural soundness and
   design-doc fidelity, not re-tracing every line.
4. Two real bugs the implementation found and fixed along the way, worth
   your attention because they reveal something about the design, not
   just the code: `type-rank` never bucketed the JVM `Rational` carrier as
   numeric (fixed); `select-by-index`'s range scan lost rows tied across
   kinds because it stopped scanning at the first kind-distinct row
   instead of continuing through the numeric tie (fixed) -- is this
   symptomatic of anything the design should say more explicitly about
   how index range scans and kind-strict matching compose, or was it
   purely an implementation gap with no design-level lesson?
5. One OWNER-ACCEPTED, DOCUMENTED LIMIT, also in the design doc now: `q`'s
   returned relation is still a plain host `#{...}`, and a host set's own
   membership test uses host `=`/`hash` regardless of how carefully the
   rows were deduplicated beforehand -- so `[1.0M]` and `[1.00M]`, or
   `[0.0]` and `[-0.0]`, can still merge into one returned row on the JVM
   and Dart (not Node). Fixing this would require the return value to stop
   being a plain host set, which the owner declined for this step. Is this
   an acceptable temporary state given everything ELSE in the query
   pipeline (matching, unification, ordering, aggregation) is now genuinely
   kind-strict, or does it undermine the whole point badly enough that you
   would flag it for the owner regardless of their prior decision?

## Verify all lanes are as reported (do not re-run; take this as given)

kondo 0/0, cljstyle clean, full JVM Java 17 1743 tests / 174922 assertions,
CLJS 1660 tests / 44798 assertions, CLJD 1622 tests all passed.

## Deliver

1. **Sign-off**: SIGNED OFF or NOT SIGNED (with what is required).
2. Does the implementation match your original work package's intent for
   this section, and the owner's two rulings, faithfully?
3. Your answer to item 4 and item 5 above.
4. Anything that needs the owner's further attention before this can merge
   (the doc says merge/gates are owner-only regardless of your sign-off;
   name any remaining gate).
5. Confirm the additive boundary held: no edit outside `dao.jing.cbor.cljc`
   (additive only), `dao.space.index.cljc`, `dao.space.query.cljc`, and new
   test files; `equiv`/`num=`/`num-hash`/`num-compare`/`equiv-hash` and the
   frozen J0-J3 corpus/README/generator/errata are untouched.

Final response beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: 01a0c501-5311-71f0-94e5-033950e0473d

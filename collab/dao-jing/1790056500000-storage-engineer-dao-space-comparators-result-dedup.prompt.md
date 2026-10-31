Created-GMT: 2026-09-22 05:55:00 GMT
Created-Local: 2026-09-22 12:55:00 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: 51cbe9c5-542b-4efc-99ce-082ca4a1beb8 (resumed: your step-3 comparator turn)
# Task: dao-space-comparators-result-dedup — extend step 3 to the query's OWN output
Role: DaoSpace & DaoJing Storage Engineer
Implementers:
- Model: claude-opus-5 | Assigned: 2026-09-22 12:55:00 +07 | Status: active | Rationale: same engineer, same worktree, extending the just-verified comparator wiring to a residual gap it found and reported

Same worktree (/Users/sto/workspace/worktree-dao-space-comparators), same
rules. Do NOT stage or commit. Orchestrator verification of your prior
round: kondo 0/0, cljstyle clean, full JVM (Java 17) 1737 tests / 174900
assertions, CLJS 1654 tests / 44781 assertions, all matching your own
counts. CLJD is running now; you are not blocked on it for this round.

## The gap, in your own words from the last report

> Not changed, still host-equality-based on the JVM: `q` result relations
> are host sets, so result rows `[0.0]`/`[-0.0]` or `[1.0M]`/`[1.00M]`
> still merge. `group-by` for aggregates, `count-distinct`, and the
> `distinct` in or-join also still dedupe by host `=`. Fixing these would
> change the result value's type. I found no use of `equiv` inside
> `dao.space.*`.

The owner decided: fix it now, in this worktree, so the kind-strict
guarantee holds end to end from matching through to what a query returns.
The result value's outer TYPE should not need to change (still a host
`#{...}` of tuples, still `set?`, `contains?`, `count`-able the same way
callers use it today) -- only which elements are considered duplicates
before they go into it.

## Do

1. Add a private `content-distinct` helper (name it however fits the
   file's conventions) that, like `clojure.core/distinct`, keeps the first
   occurrence of each distinct element and preserves relative order, but
   collapses two elements only when `dao.jing.cbor/content-key` gives them
   the same key -- not host `=`. `dao.jing.cbor/content-key` already
   recurses through maps, vectors and sets generically (built for exactly
   this), so this one helper should work unmodified on a binding map
   `{sym val ...}`, a projected result tuple (a vector), or a bare scalar.
   Confirm this by reading `content-key`'s definition; do not re-derive
   per-shape logic that already exists there.
2. In `relation-result`: wherever a `#{...}` of rows or a `#{...}` of
   projected result tuples is built (`into #{} ...`), dedupe with
   `content-distinct` BEFORE the elements enter the set, so a pair like
   `0.0`/`-0.0` (as a bare scalar row) or `1.0M`/`1.00M` (nested in a
   tuple) never collapses into one entry.
3. The aggregate `group-by` (keyed by `(mapv #(get row %) grouping-vars)`):
   key by `(comp dao.jing.cbor/content-key grouping-fn)` instead, so two
   groups whose grouping-vars vectors differ only in decimal scale or zero
   sign stay separate groups. `group-by`'s own key order is not guaranteed
   and does not need to be -- the final result is a set.
4. `count-distinct` in `aggregate-fns`: `(count (content-distinct xs))`
   instead of `(count (distinct xs))`.
5. `eval-or`'s two `distinct` calls over binding rows/maps, and the rule
   evaluation's `distinct` over binding rows (you named these; find them
   again by reading `eval-or` and the rule-body evaluation function): route
   through `content-distinct`.
6. Leave EVERY OTHER `distinct` call alone -- the ones over query variable
   symbols or rule names (`not-required-vars`, `branch-free-vars`, the
   "Unknown rule" error's `(distinct (map ffirst rules))`, and `proj-vars`'s
   `distinct` over symbols in `relation-result`). Those operate on bare
   symbols, which host `=`/`distinct` already handles correctly; changing
   them would be scope creep. Confirm this scoping in your report rather
   than assuming it silently -- if you find one that DOES carry a datom
   value and I mis-scoped it, fix that one too and say so.
7. Tests, extending `test/dao/space/query_numeric_test.cljc`: a query whose
   `:find` produces rows differing only in decimal scale or float zero sign
   returns BOTH rows, not one; the same for an aggregate `count-distinct`
   and for `group-by`-based aggregation (two groups, not one, when the
   grouping value differs only in scale or sign); an or-join or a rule
   whose branches produce bindings differing only in scale or sign are not
   collapsed into one binding. Use the same mutation-proof method as your
   last round where it fits (show the old behavior wrongly merging, before
   your fix, then that the fix separates them).

## Never

Do not change the RETURN TYPE of `q`/`relation-result`/`collect` (still a
set of tuples, or whatever `apply-spec`/`apply-return-map` already produce
today for `:scalar`/`:coll`/`:tuple`/`:relation`). Do not touch the frozen
J0-J3 CBOR files. Do not change `equiv`/`num=`/`num-hash`/`num-compare`/
`equiv-hash` in `dao.jing.cbor.cljc`.

## Environment

Same as before: default PATH gives Java 21 and the mise clojure and bb; if
kondo, cljstyle, the Java 17 lane, or the CLJD lane are denied, say exactly
what was denied and stop, do not retry.

## Final report

Begin exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 51cbe9c5-542b-4efc-99ce-082ca4a1beb8
Then: files changed; the content-distinct design; every call site changed
and why, and every `distinct` call site you deliberately left unchanged and
why; new tests; what you ran with exact counts; what you could not run;
every deviation. Facts only; promise nothing.

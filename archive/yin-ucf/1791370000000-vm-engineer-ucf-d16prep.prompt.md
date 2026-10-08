Created-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Created-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude (opus-5-5)
Session-ID: 9f5d1b84-acf0-415e-b166-05f81d25fb6e

# Task: UCF M-next D16-prep — the safepoint harness and the non-composition 14.2.4 rows
Role: Yang Compiler and Universal AST Engineer

Implement D16-prep in /Users/sto/workspace/datomworld-d16prep (worktree,
branch ucf-d16-prep off master). D16 (the stage-D gate) splits: THIS
round authors every gate row that does NOT route through
`yin.vm.ucf.compose` — the compose-driven rows land in D16-final after
D15's composition lands (D15 is in flight in a sibling worktree; your
diff must stay disjoint from src/cljc/yin/vm/ucf/compose.cljc and
src/cljc/yin/repl/*). Read first: docs/design/yin.vm.universal-
continuation-format.md (7.4.1's safepoint table, 7.11.1's safepoint
harness rows, the version-1 block's clauses 1-5 and 9 with their stage
markers), docs/design/yin.vm.linker.dht.md 14.1.1/14.1.3 (the kept-
cursor rows and host matrix), and the landed v2 suites
(test/yin/vm/ucf/handoff_v2_*.cljc) plus checkpoint_fixtures.cljc —
your rows extend this body of fixtures.

The contract (the D16 rows that need no composition):

1. **The safepoint harness** (7.4.1): each corpus segment to each
   liftable row — explicit park, blocked stream read, blocked write,
   sent FFI, retained FFI, ordinary and tail effectful calls, halt —
   on both body versions (v0 fork and v2), lift then lower into a
   fresh VM, compare result and effect trace against the reference
   run. Explicit park is checked through its parked record. A held
   immediate (the D4 :observe entry) is asserted to refuse export.
   Distinct activation depths and captured environments at one pc; a
   nonempty ready queue; a kept response cursor shared by two waiters.
2. **The 14.2.4 rows marked stage D that need no composition**: the
   candidate/holder rows of 14.2.4 already landed with D13 (row 1);
   the retained-write rows (rows 2 and 4's fencing halves) through the
   landed writer; replay divergence (row 5) including the evicted
   kept-cursor value recovered with durable inputs and once without.
3. **The version-1 block's clauses 1-5 and 9 rows** that ride the
   lift/lower (already largely landed with D7/D9/D10 — audit for gaps
   and close any you find; the deepseek F3 audit's five gaps A-E are
   CLOSED by the parallel F3 round on another branch, so do not
   duplicate them).

Test contract: test-first per row; portable .cljc; JVM during
iteration, three lanes at landing. Reuse the corpus/fixture machinery
the v2 suites already built.

Acceptance criteria:
- The safepoint harness rows run on both body versions and match
  reference results/traces.
- The 14.2.4 fencing/retained-write rows are pinned through the
  landed writer/reader, not through compose.
- Permitted diff: test/yin/vm/ucf/* harness and row files only
  (plus shared test-support helpers). Production files, compose.cljc,
  and yin/repl/* are NOT in the permitted diff — a missing public
  seam: stop and report.

Constraints:
- No git writes. kondo/cljstyle may be sandbox-blocked; note it.
- `#?(:cljd nil :clj ...)` order for JVM-only test branches (:cljd
  first); a 0.0 literal is the integer 0 on JS.
- This worktree is shared with no other round; keep the diff in
  test/yin/vm/ucf/*.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report changed files, exact test/check outcomes with counts, the red
and green evidence, unresolved concerns, and any incomplete work. Do
not claim edits or tests that did not occur.

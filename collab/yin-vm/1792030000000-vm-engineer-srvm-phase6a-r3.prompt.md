Created-GMT: 2026-10-11 17:00:00 GMT
Created-Local: 2026-10-12 00:00:00 Asia/Bangkok

# Task: 6a round 3 -- finish the marker refusal, the false duplicate and the byte bound (fresh codex session)

You are a NEW engineer session (gpt-6.1-sol) with no memory of the prior work. Worktree `/Users/sto/workspace/datomworld-srvm-p6a`, branch `srvm-phase6a`, HEAD bb4a6558 (round 2 by claude-opus-5-5, on top of codex's round 1). Do NOT `git add`/commit. Source changes in `src/cljc/yin/vm/ucf/rows.cljc` (and `frame.cljc` only if needed) are AUTHORIZED; report each as a source change with evidence. Write each `-n ns` literally; foreground only; never run slow tests. Take a verified byte backup of each source file into `collab/` BEFORE any edit and BEFORE any mutant; restore mutants from it.

Read, in order: `collab/1792010000000-reviewer-srvm-phase6a-r2.gpt-6.1-sol.findings.md` (the independent review: 1 HIGH + 3 MEDIUM, your definition of done), `collab/1791940000000-reviewer-srvm-phase6a.claude-fable-5-1.findings.md` (round-1 review), `collab/1791980000000-vm-engineer-srvm-phase6a-r2.report.md` (what round 2 did), and design `docs/design/yin.vm.semantic-register-vm.continuation.md` sections 2 and 5.5.

Fix, each with a named test that FAILS before the fix and passes after, and a probe from the review as the regression test:
1. MEDIUM (rows.cljc ~564): a guest window value shaped like a marker, e.g. `{:yin.k/row (rows/node :prim ['guest/f])}`, must be REFUSED as `:yin.k/non-portable` on encode regardless of whether its payload is well-formed. Separate guest-value classification from explicit marker classification (the encoder already receives explicit marker nodes through its argument: use that distinction, not the value's shape).
2. MEDIUM (rows.cljc ~403): the same refusal must apply to task-result literals and to every literal context in the carrier, on encode AND decode: `(rows/decode (rows/emit (task-node [] [:lit {:yin.k/row 1}])))` and the `{:yin.k/cell :c}` variant must refuse.
3. MEDIUM (rows.cljc ~366): a task holding the cell `:x` and the distinct literal `[:identity :yin.k/c-1]` in one set must NOT refuse `:duplicate-key`; use an equality representation that cannot collide with guest literals (an injective tagged form). Add the positive decode test and keep the existing duplicate-key refusals working.
4. HIGH (rows.cljc ~290): `canonical-numbering` on the draft section 6 seven-cell directed triangle/square fixture exhausts the heap (probe with `-J-Xmx256m`): label bytes nest and grow geometrically while the work budget counts calls. Add a byte bound on intermediate labels/signatures/comparison representations checked BEFORE allocation, refusing `:yin.k/resource-limit` (reason `:label-bytes`), default large enough that every existing fixture (including the three-cell pin and ten-cell cases) still succeeds with unchanged canonical bytes (the pinned root ids must not move). The seven-cell fixture must now REFUSE with `:resource-limit` under default budgets without an OOM at `-Xmx256m`: add that as a test (run the check in a bounded way). State plainly in the report that whether the seven-cell fixture should instead canonicalize is an open Architect question (a hashed-label formulation would change the numbering), not decided here.
5. LOW (child budget): pass the caller's budget to install-child numbering if it is a one-token change now that you are in that code; otherwise leave it and say so.

Re-apply, for the NEW code, at least one mutant per fix (drop the guest-vs-marker distinction; skip the literal context; collapse the equality representation; remove the byte bound check) and confirm each is KILLED by a named test; restore byte for byte.

Verify (foreground): `clojure -M:test -n yin.vm.semantic-register.frame-test -n yin.vm.ucf.rows-test` and the semantic-register namespaces as in the round-1 report; Node for frame-test and rows-test; kondo and cljstyle on changed files. Dart: the orchestrator runs it. Also check no multi-key `assoc` on a vector or nil in anything you touch (ClojureDart).

Report to `collab/1792030000000-vm-engineer-srvm-phase6a-r3.report.md`, beginning exactly:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>
Per item: change (source or test), the failing-then-passing test, the mutant killed, exact lane counts. Do not claim runs that did not occur.

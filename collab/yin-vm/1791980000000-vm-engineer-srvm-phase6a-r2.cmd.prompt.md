NOTE (fresh session on a different model): you are NOT the model that wrote the existing work; the previous implementer (codex) ran out of credits. You have no memory of it. Everything it produced is on disk in this worktree (committed HEAD plus any uncommitted edits shown by `git status` / `git diff`) and in `collab/` (its report, its logs, the reviewer findings). Read them first, verify what is already done against the instructions below, and finish the rest. You have a shell and edit rights in this worktree: use them. Never commit or stage. If a command is refused, record the exact command and error and say so in the report.

Created-GMT: 2026-10-11 09:40:00 GMT
Created-Local: 2026-10-11 16:40:00 Asia/Bangkok

# Task: 6a round 2 -- kill the surviving mutants (continuing your thread)

Worktree `/Users/sto/workspace/datomworld-srvm-p6a`, branch `srvm-phase6a`, HEAD 4b0e735e. Do NOT `git add`/commit. Read the review in full: `collab/1791940000000-reviewer-srvm-phase6a.claude-fable-5-1.findings.md` (44 mutants, 29 killed, 15 survived; findings 1-5 and the "Required before merge" section are your definition of done). Its mutation script is `collab/6a-reviewer-mutants.py` with a table in `collab/6a-reviewer-mutants-main.json`; reuse the reviewer's mutants.

SOURCE CHANGES ARE AUTHORIZED for the defects below (report each as a source change with evidence), and TEST changes for the rest.

ORCHESTRATOR RULINGS on the reviewer's open points:
- Finding 1 (HIGH, Dart): fix `rows_test.cljc:238` by chaining single-key `assoc`s on the vector (cljd inlines multi-key assoc on a vector to a `-conj` of a map); search the test files for any other multi-key assoc on a vector or nil. The orchestrator re-runs Dart.
- Findings 2 and 3 (host exceptions escape as non-refusals): fix in `rows.cljc` as the reviewer proposes: validate census `:C` slot keys against `c-[1-9][0-9]*` in `structural?` (refuse `:slot-kind`) and parse digits with a guard, not the EDN reader; in `gate` require `(and (vector? installs) (every? keyword? installs))`, refusing `:task-shape`. Add a refusal test for each probe.
- Finding 6 (marker representation): RULING: keep plain-map markers and REFUSE a guest map whose shape collides with a marker (exactly the key `:yin.k/row` or `:yin.k/cell` as a sole key) as `:yin.k/non-portable` on encode and on decode; document the refusal in the namespace docstring. Test both directions.
- Finding 7: S4 refuses a present `:yin.k/store-of nil` (`:env-shape`); test it.
- Finding 8: RULING: the census is ordered numerically by n (design section 3's reading); record this reading in the namespace docstring and add a >=10-cell case (c-10 sorts after c-9).
- Findings 9 and 11: notes only (state them in the report as known limitations). Finding 10: pass the caller's `budget` to the install-child numbering if it is a one-token change; otherwise leave and say so. Finding 13(b)-(d): correct the wording of the acceptance-item comment/test names that overclaim.
 Take a verified byte backup of each source file into `collab/` BEFORE any mutant; restore from it (`cmp` and `git diff --quiet HEAD -- src/`).

For EVERY surviving mutant (R06, R07, R10, R12, R17, R21, R24, R25, R26, R29, R30, R33, R35, R45, R46) and for findings 1-5: add or strengthen a named test, re-apply the mutant, confirm it is KILLED by a named test, restore. Findings 4 and 5 specifically: (4) acceptance item 10's unordered-container permutation is vacuous: emit one task whose `:set`/`:map` slots are listed in reversed abstract order under the IDENTITY correspondence and assert equal roots (streams, not only cells, in the set); (5) pin at least one root id (the reviewer's probes: `(fixture :s1 :s2 :x :y)` -> `:segment/blake3-be82d50b14804bb7a69103631b9c45759b49b67dbb3cebe75a1259fed6b4a937`; `(frame->rows ft/worked-frame)` -> `:segment/blake3-c4a7c7e6de3b44400efec5930bc3d85d86b29153b4b2d548acba630bfbc3da7d`; recompute them yourself and report if they differ) and add a fixture with two refinement-equivalent but non-automorphic cells (the draft section 6 triangle/square case) whose chosen leaf is asserted. Anything you judge an EQUIVALENT mutant: argue it explicitly with evidence, do not just omit it.

Verify, foreground, literal `-n` flags: JVM for frame-test, rows-test and the semantic-register namespaces as in your first report; Node as in your first report; kondo and cljstyle. Dart: orchestrator runs it.

Report to `collab/1791980000000-vm-engineer-srvm-phase6a-r2.report.md`, beginning exactly:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>
A table: mutant | killed by which named test (or equivalent, with argument) ; per finding what changed; exact lane counts. Do not claim runs that did not occur.

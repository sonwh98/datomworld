Completed-GMT: 2026-10-10 13:02:00 GMT
Completed-Local: 2026-10-10 20:02:00 Asia/Bangkok

# Report: phase-4 evaluator design, revision 3 (two trace-oracle defects, one editorial)

Role: Lead System Architect (claude-fable-5-1), continuing session.
Edited only `docs/design/yin.vm.semantic-register-vm.evaluator.md`; the
frozen design untouched; nothing staged or committed. Read in full:
`collab/1791540000000-architect-srvm-phase4-design-review-r2.gpt-6.1-sol.findings.md`.

## Defect → sections changed → corpus rows added

| Codex defect | Sections / places changed | Result |
|---|---|---|
| 1. `:none` is both a value and the sentinel (Medium) | §4.3 register loop (`r := extract …`, `(:event? r)`), new paragraph after the algorithm defining the tagged result, the whole extraction table (every cell now `{:event? true :value v}` / `{:event? false}`), §4.4 (comparison reads `:event?` then normalized `:value`) | No `:none` sentinel remains anywhere in the document (grep: the only `:none` occurrences are the two sentences saying a produced `:none` is carried as a value, and the new corpus rows). The walker side never used a sentinel (its criterion is `:control` nil, not blocked) and is unchanged. |
| 2. Parking classified by result shape (Medium) | §4.1 walker paragraph (park recognised from the pre-state `(:control vm)` being a `:vm/park` node, `ast_walker.cljc:565-567`); §4.1 register table (`:park` row by `inst`, blocking row separated); §4.2 event list (`[:park id]` with `id` = `(:id (:value vm'))` after a detected park, `engine.cljc:2290-2293`) and `trace-normalize` (the parked-record arm removed; plain maps are data; the closure-map arm kept and justified); §4.3 both loops (`park?` read before the step on the walker; `(= :park (nth inst 0))` on the register side; the shape tests deleted); extraction table (`:park` row "not reached"); new paragraph "Parking programs" relating the trace's `[:park id]` to 4b items 9-10; §4.4 (`[:park id]`); §4.5 closing paragraph (the `:park`-bearing corpus rows error before parking; the new row is the one that parks) | Park and halt are now classified only from the executed node or instruction. Checked against 4b: items 9-10 are run-to-completion tests that compare the parked record itself, so they stay consistent with a trace that records only that a park happened and its id; blocked programs remain outside the step trace, unchanged. |
| 3. Stale quotation (Low) | §4 introduction (now cites the F5-amended production/delivery correspondence); §4.5 item 5 header sentence | The two remaining old-wording occurrences (lines in §6 F5, "Replace: … with: …") are the historical texts §6 explicitly identifies as replaced, and stay. |

## New corpus rows (§4.5 item 5, trace-only, pinned whole traces)

| Name | Program | Expected events (both machines) | Slice |
|---|---|---|---|
| `:literal-none` | `{:type :literal :value :none}` | `[[:value :none] [:halt :none]]` | 4a |
| `:parked-shaped-literal` | `{:type :literal :value {:type :parked-continuation :id :parked-0}}` | `[[:value {:type :parked-continuation :id :parked-0}] [:halt {…same map…}]]` | 4a |
| `:park-then-halt` | `{:type :vm/park}` | `[[:park :parked-0]]` | 4b |
| `:literal-none-in-call` | `((fn [x] x) :none)` | `[[:value {:type :closure :params [x]}] [:value :none] [:value :none] [:halt :none]]` | 4a |

§5.1's trace row and 4b item 25 name these rows. The fourth row was
added beyond the brief so that a produced `:none` is also exercised as
a call operand and as a body result crossing a `:return` relocation.

## Re-grep of the whole document (brief's closing instruction)

Patterns searched: `:none`, `parked record`, `is a parked`, `[:park …]`,
`value-producing`, `register writes`.

- (a) absent-event sentinel: none left; the three `:none` hits are the
  tagged-result paragraph, §4.4, and the corpus rows.
- (b) park or halt by value shape: none left in §4. The remaining
  "parked record" hits (§1.2 `:value` row, §1.6, §2.1, §3.4, the
  `:resume` extraction row) describe machine state or read the record
  from `:parked` by id before the step; none classifies by shape.
- (c) old wording: only the two quoted "Replace:" blocks of §6 F5.

## Disagreements

None. One addition beyond the brief: the fourth corpus row, stated above.

## Not grounded

- `engine/park-id` mints `:parked-0` on a fresh task (`engine.cljc:2273-2277`,
  `(or (:id-counter state) 0)`); the `:park-then-halt` expectation
  assumes no earlier `gensym` on the task, which holds for a fresh
  `create-vm` on both machines but was not run.
- No tests were run (docs-only brief).

## Files touched

- modified `docs/design/yin.vm.semantic-register-vm.evaluator.md`
- created `collab/1791540000000-architect-srvm-phase4-design-r3.report.md` (this file; `collab/` is never staged)

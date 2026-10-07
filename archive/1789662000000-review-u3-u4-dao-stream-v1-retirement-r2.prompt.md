Created-GMT: 2026-09-17 14:20:00 GMT
Created-Local: 2026-09-17 21:20:00 +0700 (Asia/Ho_Chi_Minh)
Coding-Agent: codex
Session-ID: 01a0af75-c0c4-7c30-9347-693a3d3f67dc
Role: Adversarial Review

Implementers:
- Model: gpt-6-astra | Assigned: 2026-09-17 21:20:00 +07 | Status: active | Rationale: same reviewer, follow-up confirmation on its own three P1 findings

# Task: Confirm the r2 fixes to your three findings

Your prior review
(`collab/1789660000000-review-u3-u4-dao-stream-v1-retirement.gpt-6-astra.findings.md`)
found three P1s and one P2. All four have been addressed in the working
tree now (uncommitted):

1. **Flutter dao.gui Prototype initial-frame loss**: the frame-stream
   widget (`src/cljd/dao/postgraphics/flutter.cljd`) gained an `:on-bind`
   hook that fires once per mount, right after the terminal binds;
   `src/cljd/datomworld/demo/dao_gui.cljd`'s picker now renders its sample
   from that hook instead of from the parent `LayoutBuilder` before the
   child exists. Read the diff and judge whether this actually closes the
   ordering gap you found, for both first mount and reopening.

2. **`:error/kind` vocabulary**: `docs/design/dao.postgraphics.md` now
   documents `:dao.terminal/transport-error` as a fourth allowed
   `:error/kind`, naming exactly which `:dao.stream/...` outcomes map to
   it (`end`, `transport-error`, `cursor-mismatch`, `invalid-cursor`) and
   defining `:frame-id` as the last *presented* frame's id (tracked via a
   new `:presented-frame-id` on the binding), `nil` if none presented yet
   — distinct from the submission counter. `terminal.cljc` implements this
   with a new `transport-error-signal` carrying the raw
   `:dao.stream/outcome` separately. Judge whether this is a coherent,
   complete resolution (spec + terminal doc + implementation + tests all
   updated together, as you required) or whether something is still
   inconsistent.

3. **U4 re-read after `:transport-error`**: `event.cljc` now persists an
   `:input-error?` flag on the binding, checked before any read in
   `advance`; a later `advance` on a stopped binding returns
   `:transport-error` without calling `stream/next` again.
   `bind_test.cljc:259-273` now asserts `@reads` stays `1` after a second
   `advance` (the implementer verified this assertion actually fails
   without the code fix, then passes with it). Confirm the fix is
   complete — check the mint-failure arm too (`:793-797`), which the
   implementer says also persists the flag for non-`closed` mint outcomes.

4. **Origin-cursor ambiguity (P2)**: documented in
   `dao.gui.event.md`'s Binding Contract as a timing discipline, with a
   new pinning test (`a-late-binding-observes-the-retained-suffix-silently`)
   modeling the exact scenario you described (capacity-1 stream, two
   appends before the first advance). No logic change, as you specified.
   Confirm the documentation is accurate and the test actually pins the
   behavior you found.

Verified independently by the orchestrator, not trusted from either
implementer's report: `clj -M:test` -> 1361 tests, 165707 assertions, 0
failures, 0 errors. `bb test:cljs` -> 1282 tests, 35263 assertions, 0
failures, 0 errors, 0 warnings. `bb test:cljd` (after `rm -rf
test/cljd-out`) -> 1245 tests, all pass. All three match both
implementers' own reports exactly.

**Still not verified by anyone**: the manual Flutter/browser smoke checks
from your original review remain an open gap — no simulator, device, or
headless browser is available in this environment. Do not treat the
passing suites as covering this.

## Task

Confirm each of the four fixes independently — re-derive from the diff,
don't just trust the implementers' or orchestrator's summaries above.
Deliver an explicit verdict: ready for Architect sign-off, or not (with
what remains). Do not edit any file.

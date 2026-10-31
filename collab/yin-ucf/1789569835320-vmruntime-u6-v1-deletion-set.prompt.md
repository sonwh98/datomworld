Created-GMT: 2026-09-16 14:43:55 GMT
Created-Local: 2026-09-16 21:43:55 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: claude
Session-ID: b20af95e-ca6f-4a72-9c16-6a7cbf65883b

# Task: U6 — the yin.vm v1 deletion set (one atomic commit)

Role: VM Runtime

Implementers:
- Model: claude-opus-5 | Assigned: 2026-09-16 21:43:55 +07 | Status: active | Rationale: the largest, most consequential unit in this plan; same model handled U2's multi-file composition carefully

## Context — read this whole section before touching anything

`docs/design/yin.vm.v1-retirement.implementation-plan.md`'s "### U6 — the
deletion set" section (currently starting around line 473 — read the
actual current text, don't trust a cached line number) is the full,
precise spec for this unit. Read it completely, along with "### D6 — one
deletion set, one change, after every twin has landed" (explains why this
must be exactly one commit, never split).

**This is the final unit.** U1 (dao.await v1), U2 (Flutter widget), U4
(browser REPL client), and U5 (test port + parity pin) are all already
committed. U3 needed no build (owner chose the default: delete v1
telemetry with no v2 twin, recorded in the plan's D2). This unit deletes
what those four made safe to delete.

The orchestrator re-ran all three of the plan's Phase 0 census sweeps
fresh, immediately before writing this brief, against the current tree
(after U1/U2/U4/U5 landed). The results confirm the plan's own file lists
are still completely accurate — nothing new needing deletion has
appeared, nothing in the delete list has already vanished unexpectedly.
Trust the plan's own **Delete**/**Edit**/**Prose** lists as the primary
source of truth for scope, but re-verify each item yourself as you go
(e.g. re-run the plan's own Phase 0 grep sweeps yourself before finishing,
not just at the start) rather than executing blind.

**Additional doc-drift items found in tonight's fresh census, not named
in the plan's own Prose list** — fold these into this same commit's prose
pass, same treatment (one-line status note, not a rewrite, unless the
file's whole purpose was describing v1):
- `src/cljc/yang/docs/{yang_implementation,architecture,README,python}.md`
  — each has a `(require '[yin.vm :as vm])` example; repoint to
  `yin.vm` or add a one-line note, whichever reads more naturally at
  each specific site (use judgment, these are usage examples not status
  pages).
- `src/cljc/yin/vm/docs/{ast_quickref,ast,state,yin-defmacro}.md` — same
  treatment; these are exactly what "Phase 0's doc sweep" already told you
  to check in `src/cljc/yin/vm/docs/`, the sweep just needed to actually
  be run, which the orchestrator has now done for you.
- `src/cljc/dao/await.cljc:8` — a comment describing v1's old module
  registration convention, in a live (not deleted) file. This is
  historical/explanatory context for why v2 does things differently, not
  a claim that v1 currently exists — probably fine to leave as-is, but
  confirm it doesn't read as misleadingly present-tense once v1 is
  actually gone; adjust only if it does.
- `src/cljc/dao/stream.cljc:206`, `src/cljc/dao/stream/rpc/client.cljc:9`
  — prose comments mentioning "yin.vm engine" / "yin.repl" as an existing
  pattern reference. Same judgment call as above — likely fine as
  historical/conceptual reference, adjust only if genuinely misleading
  post-deletion.
- `docs/handoff.md` — check whether it references anything v1-specific as
  currently valid (the plan's own criteria list doesn't name this file
  explicitly, but it showed up in tonight's sweep); if it only references
  v2 aliases, no action needed.

## Task

Execute U6 exactly as the plan's own **Delete**, **Edit**, **Local
hygiene**, and **Prose that describes the deleted code** sections specify,
plus the additional doc-drift items above. This is ONE commit — do not
create intermediate commits, and do not stage anything until every part
is done and verified (staging/committing itself is the orchestrator's
job, not yours — leave everything uncommitted when you report back, per
every other unit tonight).

Specific things to get right, called out because they're easy to get
wrong in a deletion this size:
1. **`test/cljd-out/` local hygiene** — delete the stale compiled `.dart`
   twins for every test file you delete (`repl_test`, the eight
   `yin/vm/*_test` files, and — already handled by U1, verify it's still
   clean — `await_test`) BEFORE running any CLJD verification, or a stale
   artifact will silently pass against dead code (this exact hazard is
   now documented in `docs/agents/build-n-test.md`; read it).
2. **The three `test/yin/repl_build_test.clj` inversions** — these
   currently assert v1 build entries *exist* ("the v1 entry stays", etc.);
   after deletion they must assert those entries are *absent*. Read the
   current test file, understand exactly what it asserts today, then
   invert precisely — don't just delete the assertions, flip them to
   prove the negative (a deleted alias/file/build target genuinely
   doesn't exist), matching the plan's own stated intent.
3. **`test/dao/test_utils.cljc` (one helper)** — find and remove/adjust
   whatever helper there depends on deleted v1 code; read the file to find
   it, the plan doesn't name the exact function.
4. **The two `telemetry-text` strings** (`src/cljc/yin/repl.cljc:30-32`,
   `core.cljc:95` per the plan — re-verify current line numbers) — both
   currently say something like "run yin.repl for telemetry"; reword to
   name the owed v2 telemetry plan (D2) instead, since there's no v1 REPL
   left to run.
5. **`dao.runtime.implementation-plan.md`'s R4 gate** — strike
   `yin.repl`, `dao.await`, `yin.vm.parity-test` from its gate
   condition and record that `runtime_adapter.cljc`,
   `runtime_adapter_test.cljc`, `runtime_regression_test.cljc` were
   deleted here. The plan explicitly says **R4 is open** after this — do
   not close it yourself, just record what changed.
6. Every other named doc gets exactly a one-line status note (not a
   rewrite) except `src/cljc/yin/vm/docs/yin.repl.md`, which per the plan
   should have its content replaced or its README link repointed —
   read the plan's own wording on this one carefully, it's more than a
   status note.

## Verify

Per the plan's own U6 criteria, exactly:
- Re-run all three Phase 0 sweeps yourself, fresh, after your deletions —
  every hit must be under `docs/`, `collab/`, `docs/orchestrator-log.md`,
  or a documented non-hit (the historical/conceptual comments and blog
  prose already identified as acceptable). Report the full sweep output.
- `clj -M:test` (full suite) — must pass.
- The shadow `:test` and `:demo` builds (`bb test:cljs`,
  `clj -M:cljs -m shadow.cljs.devtools.cli compile demo`) — must pass;
  `Testing yin.repl.embed-test`, `yin.vm.parity-test`,
  `yang.clojure-test`, `dao.stream.ws.browser-test` must all appear in
  the Node output.
- `clojure -M:cljd test` / `bb test:cljd` (full suite) — clear
  `test/cljd-out` first per the documented hazard, must pass.
- `bb test` (which builds the R5 Dart peer first) — run it, report the
  outcome.
- `clj -M:clj-yin-repl`, the `:yin-repl` node build, and
  `clj -M:cljd-yin-repl` — confirm they actually start (a quick
  headless start-then-stop check is fine, doesn't need a full session);
  `--telemetry` is rejected with text naming no v1 program — verify the
  actual rejection text.
- `dao.runtime` R4's gate condition ("v1 `yin.vm.engine` no longer
  requiring `dao.runtime`") is true because `engine.cljc` no longer
  exists — confirm by grep, not assumption.
- **The U2 Flutter smoke test and U4 browser check** — the plan lists
  these as criteria for U6 specifically ("pass against the tree with v1
  gone"). Neither could be run for U2/U4's own sign-offs (no environment
  here can run Flutter or a real browser) — this is still true now. State
  this explicitly as an accepted, unresolved gap carried forward from
  those units, do not claim to have verified what you cannot run.
- `clj -M:kondo --lint` on every file you edit (not the deleted ones,
  obviously).

## Deliverable

Report back: the complete diff (every deletion, every edit, every doc
change — this will be large, organize it clearly by category matching the
plan's own Delete/Edit/Prose structure), the exact verification commands
and output for every item in the Verify section above, explicit
confirmation of what could and could not be checked (the two manual
smoke-test gaps), and any deviation from the plan's exact text with your
reasoning — this unit has zero room for silent improvisation given it's
one atomic, final commit.

Do not stage or commit.

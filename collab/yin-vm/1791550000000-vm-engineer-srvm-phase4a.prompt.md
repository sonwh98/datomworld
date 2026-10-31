Created-GMT: 2026-10-10 14:30:00 GMT
Created-Local: 2026-10-10 21:30:00 Asia/Bangkok

# Task: semantic-register-vm phase 4a -- the core evaluator (no engine waits)

Role: VM Engineer (yin.vm)

Implementers:
- Model: gpt-6.1-sol (codex) | Assigned: 2026-10-10 21:35 +07 | Status: active | Rationale: owner instruction, verbatim: "dispatch the phase 4a brief to a codex model". This is an explicit owner exception to team.md's reservation of codex for Architect review (routing-status.md lists codex as available). Because codex implements, review and sign-off go to a NON-codex family (claude-fable-5-1), never to codex.

You are working in the git worktree `/Users/sto/workspace/datomworld-srvm-p3`,
now on branch `srvm-phase4a` (from master 0ff25b58, which contains
everything below). Work ONLY there. Do not `cd` to another checkout.
`mise trust` and `npm ci` were done earlier in this worktree.

## Governing design -- read in this order, do not amend it

1. `docs/design/yin.vm.semantic-register-vm.md` -- the FROZEN design.
   It was amended after the phase-4 design review (see its status header:
   F1-F7). §2.1-2.5 (machine, transitions, delivery, correspondence),
   §3.4 (rules; the image shape `load-vector` returns), §8.1 (analysis),
   §10 phase 4.
2. `docs/design/yin.vm.semantic-register-vm.evaluator.md` -- the
   reviewed companion design (three codex rounds, READY_TO_MERGE). This
   is your specification:
   - §1 concrete state shapes (the machine record, `ret` frame, `act`
     and `tail` entries, `deliver`, parked record, reified continuation,
     loaded image, every transition as a code path against the stack
     VM's arms) -- you need NO further shape decision;
   - §2.2 the `register-restore` helper (4a lands only steps 2-3, with
     the `:return` arm; step 1 and `:call-id` handling are 4b);
   - §4 the walker trace-test procedure, including the tagged
     `{:event? true :value v}` / `{:event? false}` results (NEVER a
     `:none` sentinel), park recognised from the executed node or
     instruction, never from a returned value, fuel bound 100000;
   - **§5.1 Slice 4a** -- the files to create, the shared eligibility
     predicate `slice-4a-eligible?`, the corpora and counts, the golden
     and parity rows BY NAME, and a NUMBERED ACCEPTANCE LIST. That list
     is your definition of done; each item must be checkable by a
     reviewer who has not read the design;
   - §8 portability notes keyed to the repo's host traps.
3. `docs/agents/build-n-test.md` -- verification commands and the Gate
   Ladder.
4. The phase-3 code (written by claude-opus-5-5, reviewed and landed;
   treat it as given) and the existing machine you mirror:
   `src/cljc/yin/vm/semantic_register/{code,linearize,analysis}.cljc`,
   `src/cljc/yin/vm/semantic.cljc` (cite the template line ranges the
   design gives), `src/cljc/yin/vm/engine.cljc`.

Owner context, verbatim: "The AST is the source of truth but the
semantic vm is the canonical projection of it. the ast-walker and the
semantic-vm are isopmorphic. we just need to rebuild the smantic-vm as
register-shaped vm instead of a stack-shaped vm".

## Scope: slice 4a ONLY

Create exactly the files §5.1 lists:

- `src/cljc/yin/vm/semantic_register.cljc` (ns `yin.vm.semantic-register`)
  with the record, `create-vm`, `load-vector`, `attach-image`, `load-ast`,
  `put-registers`, `vm-hot` with the 4a arms
  (`:const :var :closure :jump :branch-false :define :gensym :store-get
  :store-put :halt :return :call`, closure and host-fn VALUE operators
  only), `register-restore` (steps 2-3), and the `vm/IVM` / `vm/IVMState`
  extensions as §5.1 specifies. Every instruction outside the 4a set, and
  the effect and `:continuation` call arms, throws
  `{:reason :not-in-slice-4a :op mnemonic}` -- the gates never feed one.
- `test/yin/vm/semantic_register/vm_test.cljc`,
  `walker_trace_test.cljc`, `parity_test.cljc` (the namespaces §5.1 names;
  the shared predicate `slice-4a-eligible?` is defined ONCE in
  `yin.vm.semantic-register.parity-test` and required by the trace test).

**No existing source or test file changes in 4a.** If something there
must change, stop and request authorization in your report. Do not touch
`yin.vm.semantic`, the walker, `yin.vm.linearize`, `yin.vm.code`, any UCF
namespace, or any existing test. Do not edit any design document.

## Rules for this task

- **Do not change the design.** Where the design is ambiguous or you find
  it wrong against the code, STOP deciding silently: record the
  ambiguity or finding in your report with the evidence and the reading
  you took, and proceed with the smallest reading that satisfies the
  acceptance list. Same discipline as phase 3.
- **Verify the hand-derived numbers.** The design says the six-event
  count in 4a acceptance item 18 and the register numbers in items 5, 6
  and 12 were derived by hand and were independently re-derived by the
  reviewer; confirm them on the FIRST run. If one is wrong, say so
  plainly in the report; do not silently adjust the expectation.
- **The trace test must test itself.** The four new rows
  (`:literal-none`, `:parked-shaped-literal`, `:literal-none-in-call`,
  and the 4b-only `:park-then-halt`) pin whole traces; the first three
  are 4a. A trace oracle that can be fooled by a value shaped like a
  sentinel is the defect the review caught; do not reintroduce it.
- Portability traps recorded for this repo (they bite only on Dart or
  Node): `#?(:clj ...)` does not exclude code from the cljd build, write
  `#?(:cljd nil :clj ...)` with `:cljd` first; `(- x)` on a float is
  `0 - x` on Dart; `for` over a >32-element seq can hand the body nil on
  Dart (use `keep`/`mapv`); `(assoc nil :a 1 :b 2)` is a one-element list
  on Dart (start from `{}`); a protocol method's `[_ _]` params fail on
  Dart (use `[_ _x]`); the cljd EDN reader throws on whitespace before a
  closer. Windows are map-valued (preserve nil-valued keys). Trace
  extraction must use explicit destinations, never host map iteration
  order. See evaluator design §8.
- Foreground only: never background a test lane, never append `&`.
- Do NOT attempt `git add` or `git commit` (the orchestrator commits
  after verifying). Do not stage `collab/`.
- **Sandbox honesty.** You run in a workspace-write sandbox. If a command
  cannot run in it (for example the Dart lane needing a Flutter cache
  outside the workspace, or a network fetch), STOP that lane, record the
  exact command and the exact error in the report, and say that lane was
  NOT run. Never infer a pass. The orchestrator re-runs every lane
  independently anyway, so an honest "not run" costs nothing and a false
  "passed" is the one thing that cannot be recovered.
- You are a fresh session with no memory of phase 3. Everything you need
  is in the files named above; the design is deliberately sufficient.

## Verification (record exact counts per lane)

G0: iterate with `clojure -M:test -n <ns>`. Before reporting, run G1 for
your three namespaces plus the phase-3 namespaces on ALL THREE lanes:
JVM (`clojure -M:test -n ...`), Node (shadow-cljs compile test with
`--config-merge '{:ns-regexp "^yin\\.vm\\.semantic-register\\.(...)$"}'`,
then `node out/test.js`; confirm "Testing <ns>" appears for each), Dart
(`bb src/dev/cljd_agg.clj --only ns1,ns2,...` with a COMMA list). Also
`clj -M:kondo --lint` and `cljstyle check` on the new source and test
paths, and a trailing-whitespace scan of the new files. Do not run the
full `bb test`. Phase 3's 33 tests / 1133 assertions must still pass
unchanged.

## Report

Write `collab/1791550000000-vm-engineer-srvm-phase4a.report.md` in this
worktree. Begin it exactly with:

Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>

Then: changed files (all new); exact test and check outcomes per lane;
the acceptance list from §5.1 item by item with PASS/FAIL and the
evidence; the hand-derived numbers you re-checked and the result; every
design ambiguity or finding and what you did (design not amended); any
unresolved concern; incomplete work. Do not claim edits or tests that
did not occur.

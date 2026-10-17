Created-GMT: 2026-10-10 15:20:00 GMT
Created-Local: 2026-10-10 22:20:00 Asia/Bangkok

# Task: Adversarial review of semantic-register-vm slice 4a (authored by codex gpt-6.1-sol)

Role: Adversarial Reviewer / Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-10 22:20 +07 | Status: active | Rationale: the code under review was written by codex gpt-6.1-sol (an owner-authorized exception to team.md); sign-off must come from a different family, and Fable is the project's preferred Architect (owner 2026-09-30). Owner instruction: codex implements 4a.

You are in `/Users/sto/workspace/datomworld-srvm-p3` on branch
`srvm-phase4a`, commit `9a8e7915` (one commit on master 0ff25b58; four
new files, 760 lines, no existing file touched). Work ONLY there.

**This is a READ-ONLY review of source and tests.** Do NOT edit, create
or delete any file except your own findings file named below. Do not
`git add` or `git commit`. You MAY run read-only repo commands and the
test commands below (foreground, bounded; use explicit namespace lists and
beware shell word-splitting in zsh: never put a namespace list in an
unquoted variable; write each `-n ns` out literally).

Already verified by the orchestrator, independently of codex's report, on
this exact commit: JVM 57 tests / 1547 assertions / 0 failures; Node 62
tests / 1607 / 0 / 0; Dart 7 files in 7 shards, +57 all passed; kondo
0 errors 0 warnings; cljstyle clean; no trailing whitespace. So your job
is NOT to re-run lanes; it is to find what passing tests do not prove.

## What to review against

1. `docs/design/yin.vm.semantic-register-vm.evaluator.md` -- the reviewed
   companion design (three codex rounds, READY_TO_MERGE): §1 shapes,
   §2.2 restore, §4 trace procedure, **§5.1 slice 4a: the files, the
   shared predicate and the 21-item numbered acceptance list**.
2. `docs/design/yin.vm.semantic-register-vm.md` -- the frozen design as
   amended (F1-F7): §2.1, §2.4, §2.5, §3.4, §4.1, §8.1, §10 phase 4.
3. The code and its report:
   `src/cljc/yin/vm/semantic_register.cljc`,
   `test/yin/vm/semantic_register/{vm_test,walker_trace_test,parity_test}.cljc`,
   and `collab/1791550000000-vm-engineer-srvm-phase4a.report.md`.
4. The machines it mirrors: `src/cljc/yin/vm/semantic.cljc` (the
   stack-shaped VM, the template), `src/cljc/yin/vm/engine.cljc`,
   `src/cljc/yin/vm/ast_walker.cljc` (the oracle), and the landed
   phase-3 code `src/cljc/yin/vm/semantic_register/{code,linearize,analysis}.cljc`.

## Review adversarially. Specifically:

A. **Semantics against the design, transition by transition.** For each
   4a arm (`:const :var :closure :jump :branch-false :define :gensym
   :store-get :store-put :halt :return :call`): does the code implement
   the frozen §2.4 equation exactly? In particular: the saved window is
   `W restricted to (L(pc+1) minus {rd})` for a non-tail call; a callee
   starts with an EMPTY window; a tail call grows no frame and never
   writes `rd`; a tail primitive result goes through K (to the popped
   frame, or the halt), never into the current window; `:return`
   delivers once into the popped frame's `rd`; `:halt` leaves
   `(without-store-of env)`; `:define` routes through `put-active`;
   closure and host-fn operators only, everything else throws
   `{:reason :not-in-slice-4a ...}`; ownership refusal
   (`:foreign-value`) and `:not-applicable`.
B. **Do the tests actually gate?** For each of at least EIGHT mutations
   you choose (examples: the saved window includes `rd`; the saved window
   keeps all of W; a callee inherits the caller's window; a tail call
   pushes a frame; a tail primitive writes `rd`; `:return` writes the
   wrong frame's `rd` or writes twice; `:define` writes the task store
   instead of the module store; the trace oracle treats a returned
   `:none` as "no event"; the trace oracle classifies park from a
   value's shape; fuel exhaustion on both sides counted as a pass), state
   whether any test in the suite would FAIL, naming it, or whether the
   mutant survives. A surviving mutant is a finding. You may confirm a
   suspect mutant by reasoning from the code; do not edit files to run it.
C. **The oracle.** `walker_trace_test` against evaluator design §4:
   tagged `{:event? true :value v}` / `{:event? false}` results, explicit
   destinations, production vs delivery by semantic role, park by executed
   node, fuel bound 100000, equal exhaustion fails. Are the pinned
   expected traces (`:literal-none`, `:parked-shaped-literal`,
   `:literal-none-in-call`, the six-event lambda) derived from the
   machines, or merely copied from the design? Could the oracle pass two
   machines that disagree?
D. **The eligibility gate.** `slice-4a-eligible?` is defined once in
   `parity-test` and required elsewhere: verify the 25 / 21 / 5 counts
   from the corpora yourself, and that nothing eligible reaches an
   unsupported arm at run time (a `:not-in-slice-4a` throw inside a gated
   test would be silently swallowed if outcomes are compared by
   `ex-message`: check for that).
E. **Honesty of the report.** Anything it claims that the code or tests
   do not support; the acceptance items it marks PASS (1-20) judged
   independently from the code (item 21 is closed by the orchestrator's
   three-lane run); its reading of "restore steps 2-3" (it implemented
   plain delivery only, no call-id handling, reading "step 2 is the
   call-id removal"): check §2.2 and rule on it.
F. **Portability and repo rules.** The repo's Dart/Node traps (reader
   conditionals with `:cljd` first, float unary minus, `for` over long
   seqs, multi-key assoc on nil, protocol param names, EDN whitespace);
   that no existing file was modified; that nothing outside 4a's scope
   leaked in; that the namespaces follow the project's conventions.
G. **Anything the design should have said and did not**, for the 4b brief.

Distinguish DEFECTS (must fix before merge), GAPS (fix before 4b),
NOTES. Do not soften findings; a short honest list beats a long polite one.

## Findings file

Write `collab/1791560000000-reviewer-srvm-phase4a.claude-fable-5-1.findings.md`
in this worktree. Begin it exactly with:

Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>

Then: findings as `severity | file:line | evidence | recommended
correction`; the mutation table (mutation | caught by | or SURVIVES);
answers to A-G; and end with exactly one line:
VERDICT: READY_TO_MERGE | CHANGES_REQUESTED
with the changes in the order they should be applied. Do not claim reads,
runs or edits that did not occur.

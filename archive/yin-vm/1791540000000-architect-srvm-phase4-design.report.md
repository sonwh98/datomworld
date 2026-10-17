Completed-GMT: 2026-10-10 12:40:00 GMT
Completed-Local: 2026-10-10 19:40:00 Asia/Bangkok

# Report: phase-4 design, the register-shaped semantic evaluator

Role: Lead System Architect (claude-fable-5-1). Worktree
`/Users/sto/workspace/datomworld-srvm-p4-design`, branch
`srvm-phase4-design` at `d21ee43f`. Nothing staged, nothing committed;
no source, test or existing design file was edited.

## Deliverable

`docs/design/yin.vm.semantic-register-vm.evaluator.md` (1126 lines),
the companion design to the frozen `yin.vm.semantic-register-vm.md`,
to be cited from its §10 phase 4 by the orchestrator. Sections: §1
concrete in-VM state shapes (machine map, `ret` frame, `act` and `tail`
wait entries, `deliver` record, `:parked` record, reified continuation,
loaded image, every transition as a code path against the stack VM's
arms); §2 the `register-restore` helper (seam verified, algorithm,
per-reason table, immediate completion, exactly-once, empty-K halt);
§3 link and install interplay (who owns each transition, where the
delivery record is held, the `IModuleKernel` port, resuming a parked
child); §4 the walker trace-test procedure (value-producing step
definitions on both sides, events, algorithm, mismatch rule, corpus by
file); §5 slices 4a and 4b with files, test namespaces, named golden and
parity rows and numbered acceptance lists (21 and 26 items); §6 seven
findings with proposed amendments; §7 questions for the owner; §8
portability notes keyed to the repo's host traps. DECIDED and PROPOSED
are marked throughout.

## Summary

The frozen design's transitions hold against the engine; the four thin
spots close without new engine machinery. The register evaluator keeps
every engine key of `SemanticVM` except `:stack`, adds `:window`, and
makes `:value` an exit value rather than an accumulator. The `ret`
frame replaces `:stack-base` with `:window` (the saved window
`select-keys W (analysis/saved a (inc pc) rd)`) and `:rd`; the `act`
payload is `{:segment :pc :env :window :k :deliver}`, the `tail`
payload `{:segment :site :k :deliver}` with no `:pc`, `:env` or
`:window`, and the engine reads none of those three keys (checked by
grep), so both ride through `handle-effect`, the wait-set sweep, the
FFI router, the link poll and `settle` unchanged. The restore helper is
one function of twelve lines: FFI retained-request re-park, FFI
bookkeeping removal before `ffi/call-result` can throw, then a two-arm
`case` on `:deliver` writing exactly once into the entry's window or the
popped frame's window, or halting. The de Bruijn register kernel's
`write-back` `:return-result` path is the live precedent that a restore
with no activation may run a return transition and halt. Link and
install need only the two park-entry builders and an `IModuleKernel`
port; no linker format record is needed for the 4b install gate because
the stub responder supplies empty obligations and `module/link-module`
consults no format record. The walker trace test gets a precise rule: a
walker step is value-producing iff its post-state has `:control nil`
and is not blocked; a register step iff it writes a fresh value, with
`:return r` and `:halt r` administrative and `return(v)` of a fresh
value (tail primitive, tail effect) an event. Slice 4a (core, no waits)
is shippable alone with three new test namespaces and no edits to
existing files; 4b adds the effect arms, the kernel protocol, two test
namespaces and proposes one entry in each of four existing backend
maps.

## Findings against the frozen design

Seven, each with evidence, proposed text and the section amended (§6
of the deliverable):

- F1 — the restore seam is `engine/resume-from-run-queue` and
  `engine/resume-continuation`, not `handle-effect`, which never calls a
  restore function (`engine.cljc:2224-2256`, `:2299-2309`, `:2565-2683`;
  the walker's `:restore-fn` opt is forwarded only to module handlers,
  which ignore it). Amends §2.4 "Delivery" and §11 item 4. The
  substantive claim (no current activation needed) is verified true.
- F2 — the empty-K halt of a `tail` state must leave an `:env`: the
  blocked machine's own, cleared of the module-store key. Amends §2.4.
- F3 — `K` has one frame kind; `:dao.stream.apply/eval-call` and
  `:request-sent` are wait-entry markers (`:call-id`, `:request-sent`),
  not frames, in the semantic VM (`semantic.cljc:110-119`, `:498-508`).
  Amends §2.1 bullet K.
- F4 — in-VM reasons are `:next`/`:put` with markers; `:ffi` and
  `:ffi-request` are wire names only. Notational, amends §4.1 table.
- F5 — `:return r` is administrative in the §2.5 correspondence; a row
  is proposed, without which "administrative steps skipped" is ambiguous
  for returns and the trace test misaligns by one event per call.
- F6 — the phase-4 evaluator needs an interim link format keyword
  because `:yin.semantic/code` is bound to the "v3" stack vector until
  cutover (`linker.cljc:692-712`). Proposed
  `:yin.semantic-register/code` "v4", retired at phase 8. Amends §10
  phase 4. PROPOSED; the reviewer may prefer another spelling.
- F7 — `define` writes through `engine/put-active` (module-store
  routing), which calls `store-put`. Notational, amends §2.4.

Checked and consistent, no amendment: §2.4 `call` (all arms), `return`,
`stream-next` outcomes, `stream-put` retained value, `ffi-call`, `park`
as the no-wait shape, `resume` gate refusal, `current-continuation` as a
value, ownership refusal, §3.4 image shape, §8.1 `saved`, §10 phase-3
note.

## Questions for the owner

None. Two items are for the Architect reviewer: the F6 keyword, and
whether 4b may add one entry to each of the four existing backend maps
(`continuation_invoke_test`, `ffi_test`, `rule_r_test`,
`linker_require_test`) rather than duplicating those suites.

## What could not be grounded, and why

- The phase-3 implementation report
  `/Users/sto/workspace/datomworld-srvm-p3/collab/1791530000000-vm-engineer-srvm-phase3.report.md`
  (read-first item 11, "the seven ambiguity readings") could not be
  read: the sandbox blocks reads outside this worktree (Read, `cat` and
  `wc` all refused). I relied instead on the landed phase-3 code and
  tests, and on the frozen design's own record of the one phase-3
  concern it ruled on (§3.4 item 13, commit `3e93b063`). If any of the
  seven readings bears on the evaluator (for example a reading about
  `minted`'s nil fallback for a `:resume` whose parent names a
  non-register), the reviewer should check §1.7-1.8 against it; nothing
  in the evaluator re-decides a validator rule.
- No tests were run (the brief allowed read-only verification only).
  Every line reference was taken from files read in full at `d21ee43f`;
  the engine-key collision check (`:deliver` `:window` `:site`, and
  `(:pc entry)` reads) and the restore-fn call-site census were done by
  grep over `src/cljc/yin/vm/`.
- The six-event count in 4a acceptance item 18 and the register numbers
  in items 5-6 were derived by hand from the corpus goldens and
  `analysis_test.cljc:131-139`; an implementer should confirm them in
  the first run.

## Files touched

- created `docs/design/yin.vm.semantic-register-vm.evaluator.md`
- created `collab/1791540000000-architect-srvm-phase4-design.report.md` (this file; `collab/` is never staged)

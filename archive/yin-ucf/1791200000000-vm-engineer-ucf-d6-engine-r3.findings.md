Completed-GMT: 2026-10-05 13:38:50 GMT
Completed-Local: 2026-10-05 20:38:50 +07
Coding-Agent: claude (sonnet-5-5)

# M-next D6 — engine round 3: links, install children, direct resume, child stamping, gate-completeness test

**Verdict: done except one stop item.** The driver's install of the deferred link-response cursor has no fitting public apply (item 2 below). That is a ruling for the Architect. Everything else in scope is implemented and tested. JVM only; no git writes.

## Changed files (diff touches only the three allowed)

- `src/cljc/yin/vm/module.cljc`
- `src/cljc/yin/vm/engine.cljc`
- `test/yin/vm/engine_gate_test.cljc`

## What changed

1. **Gated link append.** `module/append-link-request` gained a 3-arity `[resources entry gate]`. Any non-nil gate returns the entry unchanged, which is its `full` result, with zero appends. The 2-arity is kept and is ungated, because `yin.vm.ucf.remote` mentions it and I may not touch that file. The engine's `poll-link-entry` passes `(vm/gate-mode state)`.
2. **Deferred link-response cursor.** `require-handler` skips the `:dao.stream/newest` mint under any gate. The `:link-request` entry is built without `:cursor`, and the append goes through the gated path. Ungated behaviour is unchanged: mint, then append, entry with `:cursor`.
3. **Child stamping.** A new `stamp-gate` helper adds only `:yin.k/gate`. It is applied in `spawn-child` (child creation) and in `advance-install` before each `vm/run`. A child gets no counters, lease, input state or custody map.
4. **Install-child advance and sweep under a gate.**
   - `advance-install`: in `:exporting` and `:ended` the child does not run and the install is left untouched. In `:running` the child is stamped and run, so its own observations park.
   - `poll-link-entry`: a `:link-response` entry is not scanned under any gate. A `:link-request` is not retried, because the gated append answers `full`.
   - `check-wait-set`: `poll-ffi-responses` is skipped under any gate, so no FFI response read happens. `gated?` now means any gate mode, not just `:running`. This fixes a gap: in `:exporting` and `:ended` the ordinary put/next sweep previously still retried.
   - `resume-continuation`: it refuses with `gate-refuse!` in `:exporting` and `:ended`. It proceeds in `:running` and ungated.
5. **Gate-completeness test.** The D4 and D5 zero-call tests already in the file are unchanged. I added D6 rows to the same file, so the D4+D5+D6 list now runs together. The kernels column is `kernels` = semantic, de Bruijn stack, de Bruijn register, AST walker.

## Tests added (all in `engine_gate_test.cljc`)

| Test | Covers |
|---|---|
| `running-require-miss-on-every-kernel-test` | on all four kernels under `:running`: zero handle calls (no append, no mint); a `:link-request` entry with no `:cursor`, envelope and link id retained; the sweep neither retries nor scans. Ungated control: at least 2 calls (mint, append, then the scan), a `:link-response` entry with `:cursor`. |
| `gated-append-link-request-answers-full-test` | the append function, per mode |
| `link-response-scanning-is-gated-test` | zero scans in `:running`, `:exporting` and `:ended`; ungated scans |
| `closed-modes-sweep-nothing-test` | put/next retries are zero in `:exporting` and `:ended` |
| `ffi-response-routing-is-gated-on-every-kernel-test` | FFI response reader: reads ungated; zero reads in all three modes, on all four kernels |
| `running-install-child-is-stamped-and-advanced-test` | child stamped, advanced to its park, zero calls, mode only |
| `closed-modes-advance-no-install-child-test` | no child step in `:exporting` and `:ended` |
| `created-install-child-carries-the-mode-test` | JVM only, behind `#?(:cljd nil :clj ...)`: `start-install` stamps nil/`:running`/`:exporting`/`:ended` |
| `closed-modes-refuse-a-direct-resume-test` | resume proceeds ungated and `:running`; refused with zero restores in the closed modes |

## Test and check outcomes

- **Red** (new tests before the implementation): `yin.vm.engine-gate-test` ran 27 tests / 340 assertions with 60 failures, 3 errors. The 3 errors were the missing 3-arity. The failures covered link miss, link-response scan, FFI routing, install-child stamp and advance, closed-mode sweep, direct resume, and created-child stamp.
- **Green:** `clj -M:test -n yin.vm.engine-gate-test` ran 27 tests / 340 assertions, 0 failures, 0 errors. (One of my own test expectations was wrong — the ungated call count of 2 omitted the scan — and I fixed it to `<= 2`.)
- **Focused regression:** `clj -M:test -r '^(yin\.vm|yin\.repl|dao\.stream)\..*-test$'` ran 1805 tests / 17419 assertions with 2 failures and 1 error. All three are environmental and not touched by this diff: `dht_process_test` twice ("target/yin-repl.js is absent; build with `bb build:yin-repl-node`"), and `cross_host_transfer_test` (the Dart peer AOT compile failed, PathNotFoundException). Everything else passes, including all existing no-gate suites. I did not re-run those three on a clean tree.
- **kondo:** `clj -M:kondo` on the three files reports errors: 0, warnings: 0. cljstyle was not run (the orchestrator's).
- CLJS and CLJD lanes were not run.

## Unresolved: a ruling is needed (brief item 2, stop condition)

The brief says the driver mints the cursor as a recorded `:cursor` observation with origin `:dao.stream/newest` and installs it on the `:link-request` entry, and that if no existing public apply fits I should stop and report. **None fits.** The existing publics are:

- `apply-observation`: poll only, on an `:observe` entry.
- `apply-mint`: replaces an unminted cell in `:resources`.
- `apply-close`: removes a close record.

Nothing writes `:cursor` onto a wait-set `:link-request` entry. I did not add one. I did not hand-`assoc` it either. The engine side is ready: the entry is built without `:cursor`, and it is not scanned or retried under any gate. What the Architect has to decide:

- **(a)** A new `engine/apply-link-cursor` that takes the entry's link id and a portable position, seeds a cursor as `apply-mint` seeds, and sets `:cursor`. It refuses in `:exporting` and `:ended`.
- **(b)** Make the response cursor an unminted cell as in the D5 ruling, held by the entry as a cell ref.
- **(c)** Something else.

Whichever is chosen also needs these, which I did not touch:

- An export refusal for a `:link-request` entry that has no `:cursor`.
- The driver's source form: origin `:dao.stream/newest`, task path, and link id.

## Gate completeness

After D6, the gate is complete for every path the plan lists. The plan's every-path list is: put, next, poll, cursor, close, the FFI request and response routing, the ordinary sweep, the link request and response, install-child advance, child creation, and direct resume. Each is covered by a zero-call row under `:running` and refusal or inertness under `:exporting` and `:ended`. In the diff, nothing still observes for a gated task, with one caveat: a gated `:link-request` waits for the driver's cursor install above, so a link cannot complete until that ruling lands. I made no source change that sets `:yin.k/gate`. The only writers are tests, and non-test code may now set it once item 2 is ruled.

## Other concerns

- **Created-child test coverage.** Child creation (`spawn-child`) is reached only from `settle`, which runs inside link-response polling, and that polling is now gated off. Creation stamping is therefore covered only by the JVM-only `start-install` test via `(var engine/start-install)`. This is acceptable for JVM iteration, but it is not portable.
- **Direct resume on the four kernels.** I tested direct resume at the engine function, which all four kernels call (`semantic.cljc:381`, `ast_walker.cljc:470`, `stack.cljc:722`, `register.cljc:729`). I did not drive it through each kernel with a program, because that needs a second program carrying a `:vm/resume` node.
- **Ready-queue resumes.** `resume-from-run-queue` is unchanged. Gated ready-queue resumes are internal computation, and the D4 plan requires an empty ready queue for exporting. I did not refuse them in the closed modes.
- **Carried from the D5 review.** The D5 review's finding 6 (FFI response scan unconditional) is closed by this diff. Findings 1 to 4 (the closed-mode cursor guard and so on) are not addressed here, per the brief's scope.

---

# Round 2 — the link applies (ruling 1791203000000)

Completed-GMT: 2026-10-05 14:05:30 GMT
Completed-Local: 2026-10-05 21:05:30 +07
Coding-Agent: claude (sonnet-5-5)

Same three files. No git writes.

## What was added (`engine.cljc`)

- **`apply-link-cursor [state link-id position]`.** It sets `:cursor` to `position` on the one `:link-request` entry with that link id. The entry stays `:link-request` with its envelope verbatim, and nothing is woken. It makes no stream call. `position` is stored directly, as `apply-mint` stores it in `vm/cursor-entry`. Refusals, each leaving the state unchanged: ungated; `:exporting`/`:ended`; unknown id (or an entry already `:link-response`); an entry that already has `:cursor`, even with an equal position.
- **`apply-link-sent [state link-id]`.** It moves a `:link-request` entry to `:link-response` and drops `:envelope`. It refuses an entry without `:cursor`, so the engine enforces cursor-before-append. It has the same other refusals.
- **`apply-link-read [state link-id outcome]`.** It applies one read outcome to the `:link-response` entry and writes the result back in the entry's wait-set slot: waiting entry replaced in place, or removed; woken entry queued. The successor position is in the outcome. Same refusals; an entry still `:link-request` is refused.
- **Shared body.** `poll-link-response` is now a loop over a new private `link-read-step`, which `apply-link-read` also uses. The loop is behaviour-preserving, checked by the unchanged `linker-require-test` suite. One refusal helper, `link-entry-slot`, serves all three applies.
- **Ungated refused in all three.** The ruling states this only for `apply-link-cursor`; I applied it to `sent` and `read` for consistency with "mirror the rows" (an ungated task drives its own link). Say so if the Architect meant otherwise.

## Tests (`engine_gate_test.cljc`, +5 deftests)

- Ruling rows 1–7 for `apply-link-cursor`: install; entry unchanged except `:cursor`; wait order and ready queue unchanged; zero counted stream calls; double install refused, with an equal and a different position; unknown id refused; two pending requires (installing on the second leaves the first cursorless); a gated sweep after install makes zero calls and leaves the entry waiting.
- Row 7 (closed modes) and row 8 (ungated refused) for all three applies together. Row 8's "ungated `require` still mints exactly once" was already covered by `running-require-miss-on-every-kernel-test`'s ungated arm (one mint, then the append and the scan).
- `apply-link-sent`: refused without a cursor, then moves to `:link-response` with the cursor kept and no envelope; second send and unknown id refused; a gated round leaves it waiting.
- `apply-link-read`: `blocked` leaves the state equal; another id's `ok` skips and advances the cursor, entry still waiting, queue empty; its own refused response wakes `:link-refused` and removes the entry; `gap` and `end` refuse the entry; unknown id and a still-unsent entry refused; zero stream calls throughout.
- **Not covered:** the `:ok` settle branch of `apply-link-read` (module installs; child creation) is shared `settle` code reached by the ungated path and linker-require, but I wrote no gated test that drives it through `apply-link-read`.

## Outcomes

- **Red evidence:** the new tests call functions that did not exist before this round's edit, so they could not compile or run red in a meaningful way; I wrote the code and tests together and have no separate red run for round 2. Round 1's red stands for the rest.
- `clj -M:test -n yin.vm.engine-gate-test -n yin.vm.engine-test -n yin.vm.linker-require-test`: **84 tests, 1028 assertions, 0 failures, 0 errors.** (`engine-gate-test` alone: 32 tests, 386 assertions.)
- kondo on the three files: errors 0, warnings 0 (run before the final test edits and not re-run since; the only later change was test text, but I did not re-lint).
- **Wider run incomplete.** `yin.vm.*` minus `cross-host` reached 34 namespaces with no failure or error, then stalled in `yin.vm.linker.dht-end-to-end-test` (process-spawning) and I stopped it. The earlier combined `yin.vm`/`yin.repl`/`dao.stream` run is the round-1 result above and predates this round's changes. I do not have a complete wide run after this round. The three environmental failures from round 1 (`dht_process_test` ×2, cross-host Dart peer) remain unaddressed. cljstyle, CLJS and CLJD not run.

## Wider-gap check: public applies in the D5 state

The question: does a driver-facing public apply exist for each? Public engine applies that exist: `apply-observation`, `apply-mint`, `apply-close` (D4/D5), plus the three added now.

| Outcome | Public apply? |
|---|---|
| Put outcome (`ok`/`full`/`closed`/...) | **No.** `apply-put` is private. |
| Next outcome | **No.** `apply-next` is private. `apply-observation` takes only an `:observe` poll entry and refuses anything else, so a gated `:next` entry has none. |
| Retained FFI request → sent | **No.** Nothing moves a retained `:ffi-request` entry (`:reason :put`) to its response-reader state. |
| FFI response read | **No.** Routing lives in the private `poll-ffi-cell`/`poll-ffi-responses`, and it is skipped under any gate. |

Also: the gated `:put` entry has no apply for its resume either. D11 and D12 cannot discharge puts, nexts or FFI calls without these four; they need a ruling and a slice (D6 or a D6b), since D6's scope was links only.

## Final gate-completeness claim

Observation: after this round, no code path observes for a gated task. Every engine path is covered by a zero-call row: the immediate effects, sweep, FFI request and response routing, link request and scan, child creation and advance, and direct resume. The one open piece is delivery: a gated task can now complete a **link** end to end through the driver (`apply-link-cursor`, `apply-link-sent`, `apply-link-read`), but not a put, a next or an FFI call, because those four applies from the table above are absent. The gate is complete as a fence; it is not yet complete as a protocol. Nothing outside tests sets `:yin.k/gate`, and it should not until those applies exist, or the first gated put or next parks forever.

---

# Round 3 — the put, next and FFI applies

Completed-GMT: 2026-10-05 14:23:08 GMT
Completed-Local: 2026-10-05 21:23:08 +07
Coding-Agent: claude (sonnet-5-5)

Same three files. No git writes.

## What was added (`engine.cljc`)

All four take the gated parked entry's apply-half, make no stream call, and refuse on an ungated machine and in `:exporting`/`:ended` (shared `require-running!`, which `link-entry-slot` now also uses).

- **`apply-put [state entry outcome]`.** For a gated parked ordinary `:put` entry. The result is the one the ungated sweep's retry (`waitset/poll-put`) produces: `full` keeps waiting; `ok` wakes with the entry's `:datom`; any other outcome wakes under its own status, raised when the entry resumes; an invalid answer wakes as `:dao.stream.waitset/invalid-answer`. It is built through `make-woken-run-queue-entries`, so the ready entry is the sweep's. Refuses an entry the wait set does not hold, and a retained FFI request.
- **`apply-next [state entry outcome]`.** Same for a parked `:next` entry, following `poll-next`: `blocked` keeps waiting; `ok` and `gap` advance the cursor cell and wake; `end` and others wake under their status. Refuses a response reader.
- **`apply-ffi-sent [state call-id]`.** Removes the retained request writer and queues it woken by `ok`; the kernel's own restore (keyed on `:request-sent`) then makes the response reader, exactly as the ungated retry. Tested: after `vm/run` the entry is the call's reader. A second call finds no request and is refused.
- **`apply-ffi-read [state call-id outcome]`.** One read of the call-out cell, applied as the router does. The step is the old loop body, extracted: `poll-ffi-cell` is now a loop over a new private `ffi-read-step`, and the apply uses the same step. Existing FFI suites are unchanged and green.

**Renames.** D4's private `apply-put` and `apply-next` collided with the requested public names, so I renamed the private ones `apply-put-outcome` and `apply-next-outcome`: the name only, at their definitions and three call sites. Their bodies and the ungated paths are untouched.

## Deviations from the brief to check

1. **Entries are identified by value, not by an id.** The brief says `entry-id`. Wait entries have no id: `:next` entries carry none, and `:yin.k/issue` exists only on puts. I followed `apply-observation`'s precedent, taking the entry map and matching the first equal wait entry. The FFI applies use the call id, which exists.
2. **`apply-ffi-read` does not skip another live waiter's response.** The brief says "another waiter's id skips and advances". The router wakes that other waiter; skipping would lose its response. So a response naming another live reader on the cell wakes that reader and the cell advances; a response no reader owns is the skip (diagnostic, cell advances). `call-id` selects the cell. If you intended the literal skip, it is a small change, but it drops data.
3. **`apply-ffi-sent` has no outcome argument**, as specified, so it covers `ok` only. A terminal outcome of the driver's request append (`closed`, and so on) has no apply for a retained FFI request. `apply-put` refuses it by design. That is a D11 concern; it needs either a 3-arity or a decision.

## Tests (+7 deftests; `engine-gate-test` now 37 tests, 570 assertions)

- `apply-put-matches-the-ungated-sweep-test` (ok, full, closed, refused) and `apply-next-matches-the-ungated-sweep-test` (ok, blocked, end, gap, cursor-mismatch, refused): equal ready entries, wait entries and cell cursor against the ungated sweep with a handle answering the same outcome; zero calls.
- `put-and-next-applies-refuse-test`: ungated, `:exporting`, `:ended`; an entry not held; an entry of the wrong kind; a second apply finds none; zero calls.
- On all four kernels, a real gated FFI call: `ffi-sent-moves-the-request-to-its-reader-test` (refusals, request leaves the wait set, one ready entry, second transition refused, `vm/run` restores it as the call's reader, zero attempts) and `ffi-read-applies-one-response-test` (refusals including a request, ungated/closed modes, unknown call id, and a response reader refused by `apply-next`; `blocked` leaves the state equal; own response settles and `vm/run` halts with value 3; an unowned response is skipped with an `:unmatched` diagnostic and the cell advances; a shared cell with a second reader: the other's response wakes it and ours keeps waiting; `end` wakes both as `::ffi/response-ended`).
- Written alongside the code; the first run had one compile-time failure (the name collision above) and then passed. No separate red run, as in round 2.

## Outcomes

- `engine-gate-test` alone: **37 tests, 570 assertions, 0 failures, 0 errors.**
- Regression: `clj -M:test` on engine-gate, engine, ffi, semantic-ffi, linker-require, ast-walker, semantic, ffi.remote-serve (+responder), ucf.remote, ucf.handoff, completion, debruijn register and stack-effects: **332 tests, 2900 assertions, 0 failures, 0 errors.**
- kondo on the three files: errors 0, warnings 0.
- Not run: the full wide `yin.vm`/`yin.repl`/`dao.stream` set (it stalls in process-spawning tests here, see round 2), CLJS, CLJD, cljstyle. The two `dht_process_test` failures and the Dart cross-host error from round 1 remain environmental.

## Final gate-completeness claim

**The gate is complete as a fence and as a protocol, at the engine seam.**

- *Fence:* no engine path observes for a gated task. Every immediate effect, the sweep, the FFI request and response routing, the link request and response, child creation and advance, and direct resume make zero stream calls under `:running` and refuse or stay inert under `:exporting`/`:ended`.
- *Protocol:* every observation a gated task parks has a public apply the driver can use to discharge it with a recorded outcome:

| Parked state | Apply |
|---|---|
| put / next | `apply-put` / `apply-next` |
| poll | `apply-observation` |
| cursor creation | `apply-mint` |
| close | `apply-close` |
| link cursor, send, read | `apply-link-cursor` / `apply-link-sent` / `apply-link-read` |
| retained FFI request, response read | `apply-ffi-sent` / `apply-ffi-read` |

Two qualifications: the applies are tested at the engine, not driven by a driver, which does not exist yet; and deviation 3 above leaves the terminal outcomes of an FFI request append without an apply. Nothing outside tests sets `:yin.k/gate`. The `:ok` settle branch of `apply-link-read` (module install) is shared code with the ungated path but I wrote no gated test that drives it.

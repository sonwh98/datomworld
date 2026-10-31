# Report: yin.vm.linker M5 — wire yin.repl to the linker

## fix2 — codex gate findings, all five verified and fixed

I verified every finding against the code before changing anything; I
agree with all five. No disagreements. Per-finding:

1. **P0, rolled-back child origins reused on the surviving pair --
   confirmed and fixed.** `spawn-child` mints the child origin from the
   parent's `:origins` counter (engine.cljc:921); my rollback carried
   only `:id-counter`, so a later install could mint the same child
   origin (and, through it, the same link ids) on the pair that
   survived the rollback. Fix: `carry-link-counter` is now
   `carry-link-identity` (repl.cljc:799) and carries `:id-counter` and
   `:origins` on every rollback path -- in-round refusal (identity
   rides the raise, read at `consume-failed-round`), pending-resume
   raise, and `(abandon)`. I chose identity carry over replacing the
   pair: the pair surviving a rollback is what lets a pending require
   continue later, and section 7.2's rule is that identity is never
   reused, not that the pair must be rebuilt. The dropped child's own
   ids are not explicitly retired (its VM is discarded whole); with
   origins carried they are never minted again, so a late response to
   them is skipped as `:unknown` rather than `:late` -- a diagnostic
   wording difference only, noted here.
2. **P1, pre-drive identity snapshot -- confirmed and fixed.**
   `run-evaluation` snapshotted `(:id-counter vm)` before driving and
   `resume-pending` carried the parked VM's stale fields; ids minted by
   runs *during* the drive would have been lost on rollback. Fix:
   `drive-links` attaches the interrupted VM's identity to the raise
   itself (`link-raise`, repl.cljc:818) -- the VM the raise interrupts
   already holds every id its earlier runs minted -- and both catch
   sites now carry from the raise instead of a snapshot. The
   `:pre-drive floor` wrapper in `run-evaluation` is gone.
3. **P1, `(abandon)` blind to installs -- confirmed and fixed.**
   `abandon-pending` retired only the ids recorded in
   `:pending-run :links`, which is empty when the budget ends a round
   with a response appended but un-resumed, and installs were never
   handled; the command then dropped the parked VM without the promised
   raise. Fix: engine.cljc gains public `abandon-installs` (:1224,
   reusing the engine's own `refuse-install`: every install dropped,
   nothing published, every `:install` waiter restored with the reason
   as the require's error); `abandon-pending` runs it first, then
   retires every remaining link entry from the parked VM's live
   `:wait-set`, and only then runs the raise -- derived from the VM's
   waits, not the serve report.
4. **P2, input silently consumed while pending -- confirmed; chose the
   retain design.** A line typed while a require is pending is now
   retained on `:pending-run` (`:pending-lines`, each with its parsed
   form) and evaluates exactly once, in typing order, when the link
   completes -- folded by `fold-queued` (repl.cljc:1164) right after
   the completing round's own value, before the line typed in the
   completing call. `(abandon)` drops retained lines and says so
   (`dropped-lines-text`); the pending notice now states the contract:
   "lines typed meanwhile run when it completes, (abandon) gives up".
   Smaller than a recheck command: no new command surface, and the
   retain path reuses the already-parsed form.
5. **P2, `:progress?` wrong on a refused response append -- confirmed
   and fixed.** `serve` (link.cljc:262) now treats a failed
   `respond!` exactly like an unanswered request: the request is
   reported `:pending`, its cursor stays, and `:progress?` is not
   claimed. The drive loop's no-progress stop then applies to it.

**Tests** (test/yin/repl/require_test.cljc, now 7 tests / 64
assertions): `a-late-response-after-abandon-does-not-settle-a-later-
require-test` -- a pending round is abandoned, the same pair then
starts answering, and the late answer to the abandoned request must be
skipped (`:unknown` diagnostic, ids differ) while the later require
links for real and its export applies; without the fix this staging
settles the stale response and fails with `:module-name-mismatch`.
`an-install-wait-abandons-explicitly-test` -- an `:install` wait with
empty pending links abandons with the raise, and the minted child
origin carries onto the base. `lines-typed-while-pending-run-once-
when-it-completes-test` -- over a never-answering `:content-client`
wire (exercising the remote-content kind), a typed line is retained,
then runs once when a source swap lets the link complete. The refusal
test now pins the carried `:id-counter`, and the pending test pins the
notice wording and the dropped-line report.

**Verification:** full JVM suite 2201 tests / 182774 assertions /
0 failures; kondo 0 errors 0 warnings on the four changed files;
cljstyle clean, 80 columns, ASCII; cljd compile of
`yin.repl.require-test` clear. Node and Dart execution lanes: the
orchestrator's.

---

## fix1 — three Dart failures in test/yin.repl.require-test

Cause: the three failing assertions compared the require round's
*rendered text* against `"'mod"`. The shell renders the answer's value
through `yin.repl/format-value` -> `dao.pretty`, and `dao.pretty` prints
a `(quote sym)` form as `'mod` on JVM/cljs but as `(quote mod)` on
ClojureDart. A test-printing difference, not a production defect: the
round's *value* is the symbol `mod` on every host, so I changed the
assertions to read the value and left production code untouched.

Change (test/yin/repl/require_test.cljc only):

- `require-and-apply` returns the require round's `:last-value` (the
  symbol `mod`) instead of the rendered text, with a docstring note
  naming the printer difference; the export result is unchanged
  (require_test.cljc:182-193).
- The three assertions now compare the value: `(= 'mod answered)` in
  the all-backends test and the H/R test (`stack-answered`/
  `register-answered`), and `(= 'mod (:last-value state''))` in the
  refusal test's second round. All other text assertions already match
  host-stable strings (`absent`, `pending`, `(abandon)`, `abandoned`,
  `"3"`) and were not flagged by the Dart lane.

Verification: yin.repl.require-test on JVM 4 tests / 44 assertions /
0 failures; kondo 0 errors 0 warnings; cljstyle clean. Dart was not run
here (no host lane in this worktree) -- the orchestrator reruns it.
The assertions are strictly stronger than before: `:last-value` is set
only by a completed round, so an errored require still fails them.

Observation for the owner (not changed): `dao.pretty`'s `(quote x)`
rendering differs per host. If the operator-facing REPL should print
identically on Dart, that is a dao.pretty slice of its own.

---

Implementer: glm-5.3-flash | Resumed run (session 134d70a7, r1 brief) |
Worktree: /Users/sto/workspace/datomworld-m5 (branch m5, master 78163e92).
Uncommitted; nothing committed, merged, or pushed.

## Answer to the orchestrator's question: why yin.vm.linker.cljc was edited

One additive opt, kept deliberately: `link-manifest` gains
`:defer-discharge` (linker.cljc:2098, 2141, threaded as `defer?` through
`linked-image` at 2057/2084 and both call sites at 2178/2189). The brief
confined code changes to `yin.repl` and its neighbors, so this needs
justification.

The design puts step 5b at the *receiving task*: section 7.2 step 7 --
"On a matching `:ok` the scheduler runs step 5b against its live state."
`link-manifest` (section 8.1's direct-composition host policy) hard-codes
a linker-side discharge. A *serving* composition -- what M5 makes the
REPL -- cannot run it honestly:

1. The receiver's `:free-env`/`:store` live in the receiving task; the
   interpreter can only guess them. A guessed receiver either rubber-
   stamps obligations or mis-refuses.
2. Module obligations (`:kind :module`, a transitive require) can never
   discharge linker-side: `module/resolve-module` on the interpreter's
   name environment is always nil, so every module-with-requires would
   refuse `:unresolved-free` before the response traveled.

Alternatives considered and rejected: (a) re-implement the manifest flow
in yin.repl from the public stepped pieces -- duplicates ~80 lines of
verified linker logic; (b) build the receiver from the response's own
obligations -- a rubber stamp that defeats the check; (c) pass the
shell's own primitives/registry -- fixes primitives, still mis-refuses
module obligations. The opt is fail-closed (default false = today's
behavior), no format branching, no contract stamp touched; the engine's
`discharge-defect` (engine.cljc:844) remains the only 5b. If the owner
prefers zero linker surface change, the flag can be reverted and (a)
done instead; I judge (a) the worse trade.

## What changed (all uncommitted, m5 worktree)

**src/cljc/yin/vm/linker.cljc** -- the `:defer-discharge` opt above.
Docstring order-of-steps note at 2120-2124. No behavior change for
existing callers (full suite green).

**src/cljc/yin/repl/link.cljc -- new (296 lines).** The interpreter box
of the four-stream topology (section 6.1): the shell composes the pair
per VM; this namespace answers it.

- `formats` (:71): the six format records (four execution formats a
  kernel's `link-format` names, plus manifest/record for the internal
  fetches).
- `make-pair` (:94): the session's request/response ring media and the
  interpreter's own request cursor.
- `composition` (:107): the content side from creation options --
  `:name-env` (module name -> manifest address snapshot), and exactly
  one of `:content-store` (a dao.jing byte-store handle served in
  process behind `dao.jing.remote/default-handlers` on its own ring
  pair -- the single-process and durable-local rows of the 6.1 table)
  or `:content-client` (a `dao.stream.rpc` client state -- the
  remote-content row, drive = bounded no-op per section 6.4's "nothing
  when a remote server runs elsewhere"), or neither (every link
  pends). Supplying both throws.
- `serve` (:262): one bounded round -- read each unseen link request,
  resolve by name (`:absent` refusal on a miss: a completion, not a
  pending), attempt, append the response under the request's id, and
  only then advance the cursor. The first request nothing can answer
  stops the round and is reported `:pending`; a later round re-reads
  and re-attempts it from scratch (fresh cursors at both pair ends,
  minted before the first append per the cursors rule).
- Attempt budget (`attempt-budget` 64 drive rounds x `serve-steps` 32
  server advances): exhaustion throws the pending signal -- no clock,
  no atom; the counts ride the linker state the drive is handed.

**src/cljc/yin/repl.cljc.**

- `make-vm` 4th arity passes the link pair as `:link-request`/
  `:link-response` construction opts (all four kernels seed them into
  the private `:resources`; install children inherit them through
  `spawn-module`). `make-session` composes a pair per session
  (`:link-pair`, :554); `create-state` threads `:link-source`
  (`:content-store`/`:content-client`/`:name-env`) and `:pending-run`;
  `rebuild-session` (`(reset)`, `(vm ...)`) clears `:pending-run` with
  the session.
- Evaluation: `link-waiting?` (:788) recognizes the three link wait
  reasons; `drive-links` (:811) alternates serve and run at most
  `link-round-budget` (:68, 40) rounds and stops when a round moves
  nothing; a halted VM finalizes through the ordinary `tokenize`/
  `finalize-eval` path, a still-parked one becomes `:pending-eval`
  (:860) -- `:pending-run {vm base links}` and the prompt returns.
- Failure policy surface: `pending-text` (:847) reports the link and
  says "(abandon) gives up"; `abandon-pending` (:1049) retires each
  entry via `engine/abandon-link` with `:yin.repl/abandoned`, runs the
  raise as the require's error, and returns the shell to the round the
  require began in; `eval-parsed` (:1164) re-checks a pending link
  before each ordinary line (`resume-pending` :1128), consumes the line
  with the notice while it stays pending -- the parked VM owns the
  store a new evaluation would fork -- and folds the line in once the
  link completes. Commands bypass the re-check; `(reset)`/`(vm ...)`
  drop the parked run with the session. `repl-state` reports `:pending`
  ({name link-id} per link).
- Link-id counter carry (`carry-link-counter` :799): the shell's
  rollback resets the VM to the round-start copy whose `:id-counter`
  is 0, so the next require would mint `[:t0 0]` again and settle on
  the previous round's stale response (section 7.2's never-reuse rule).
  The parked VM's counter now rides every rollback path -- in-round
  refusal (attached to the error as `::link-counter`, read at
  `consume-failed-round`), pending-resume raise, and `(abandon)`. Found
  by the new tests; a real defect, not a test artifact.

**test/yin/repl/require_test.cljc -- new, 4 tests, 44 assertions.**

1. `a-require-at-the-prompt-links-installs-and-resumes-test` --
   `(require (quote mod))` then `(mod/f)` at the prompt on all four
   backends (walker, semantic, stack, register): require answers the
   module name, the export applies to 42 (B0-normalized against a local
   evaluation), nothing pending or blocked.
2. `the-h-and-r-backends-link-one-manifest-b0-equal-test` -- the
   criterion clause: the same manifest (H != R asserted) linked by
   stack by H and register by R, each through its own derivation record
   under `:verifying`, produces B0-equal results; the module store
   crossed with the export and nothing entered the task's own store.
3. `a-name-the-environment-lacks-is-refused-test` -- an unknown name
   refuses `:absent` as the require's error, the shell survives, and
   the named module still links on the next line *without reusing the
   refused round's link id* (the counter-carry regression).
4. `a-link-that-stays-pending-does-not-wedge-the-shell-test` -- no
   content source: the prompt returns with the pending report,
   `repl-state` shows the link, ordinary input is consumed with the
   notice (not evaluated), `(abandon)` raises the error and frees the
   shell.

**test/yin/vm/store_write_audit_test.clj** -- one allowlist entry:
`link.cljc/composition`'s content descriptor carries a `:store` key
naming a dao.jing byte-store handle, never a VM store (reason in the
comment).

## Verification run

- JVM full suite (`clojure -M:test`): 2198 tests, 182742 assertions,
  0 failures, 0 errors.
- kondo: 0 errors, 0 warnings on the five changed files.
- cljstyle check: clean on the five changed files; no line over 80
  columns; ASCII only.
- CLJS test build compiled (`shadow-cljs compile test`, 0 warnings).
- cljd compile of `yin.repl.require-test` (pulls yin.repl, link,
  linker): "All clear".
- Node execution and the Dart solo lane: the orchestrator's per the
  brief. node_modules is not installed in this worktree and the
  symlink/`npm ci` step needs owner approval, so I did not execute the
  Node runner; the compile signal above is what I could take locally.

## Decisions recorded

- **By-name only.** The interpreter serves the by-name manifest
  requests the engine emits; any other request is refused
  `:invalid-request {:defect :yin.repl/name-required}`. Fail-closed;
  the engine never sends one.
- **Name environment at the shell.** Per section 7.2 step 5 the
  composition resolves the name itself; the host supplies the snapshot
  map (an authority fold reduces to it) -- no ambient registry, no
  global.
- **Pending is retry-from-scratch.** An attempt that exhausts its
  budget concluded nothing; the next attempt re-reads the request and
  re-requests content. No half-stepped linker state is held across
  rounds, so `fetch`/`link-manifest` stay the blocking host policies
  section 6.4 says they are.
- **Remote content: seam composed, handoff open.** `connect`'s RPC
  client is single-owner today: `yin.repl.adapter` polls it and
  `rpc/take-completed` clears completions, so a second stepper steals
  REPL responses, and two client id spaces on one wire misroute
  replies. A remote content path needs a dedicated content binding
  (second connection) or adapter-ownership changes, plus
  `dao.jing.remote/serve-content!` composed into the served endpoint
  (`yin.repl.serve` serves no content handlers today). M5 accepts
  `:content-client` and uses it as the link state's `:rpc`; the driver
  handoff is not silently claimed as done.
- **Corpora split by scanner conservatism.** The tree scanner retains
  an in-body read of a module binding (`n`) that the H/R joins
  discharge, and a manifest declares only primitives/requires -- so the
  store-carrying module links on stack/register and refuses
  `:undeclared-free` on the walker. The all-backend corpus is closed
  (`+` only, declared). Recorded as a section 4.1/4.2 fact (criterion
  15 territory), not an M5 defect.

## Failure policy (open owner decision, linker.md section 12 bullet 4)

Implemented (option 3, "own"): `:pending` is a first-class non-blocking
shell state -- one bounded serve per line, `(abandon)`, and
`(reset)`/`(vm ...)` as the session-level unwedge. No deadline, timer,
or lease was added to `fetch` or the engine; both stay clock-free.

Options for the owner:

1. **`dao.jing.remote` timing options.** Reuse the connection layer's
   backoff/retry cadence for links. Against: it couples link liveness
   to the RPC client's own retry state machine, which the driver owns
   and the linker must not; the vocabulary is per-binding, not per-link.
2. **`dao.lease`.** The design's named mechanism (section 6.3): a lease
   over the link, renewed by the drive on progress, lapsed by a judge on
   `:silence`, `abandon` on lapse. Correct long-term, but the judge
   needs a clock; the shell core owns none, so the lease would live in
   the driver/host tick -- machinery M5 did not need in order to not
   wedge.
3. **Own pending state (implemented).** Bounded per round; nothing
   accumulates.

**Recommendation:** keep the own pending state at the shell; adopt
`dao.lease` in the *driver* once it grows a tick clock, lapsing on
`:silence` and calling the same `abandon` path `(abandon)` uses today.
The shell's `:pending-run` is exactly a lease's subject (the parked
evaluation), so the migration is additive and no shell API changes.

## What is left

- Driver-side handoff for a content client on `(connect ...)` (needs
  the single-owner decision above) or a dedicated content binding.
- A tick hook in `repl-step` to re-check `:pending-run` without typed
  input (today any line re-checks; the hook is additive).
- Transitive requires through the prompt: engine-supported (deferred
  module obligations), not exercised by an M5 test; a two-manifest
  fixture would close it.
- The H/R B0 comparison uses a scalar result; a closure-valued export
  would exercise b0's closure normalization (stores and closures are
  already covered by the M4 suite).
- The owner's ruling on the `:defer-discharge` opt (kept, justified
  above) and on the failure policy (recommendation above).

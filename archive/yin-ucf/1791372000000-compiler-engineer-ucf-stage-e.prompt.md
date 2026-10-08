Created-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Created-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: assigned at dispatch
Session-ID: pending (provider-generated)

# Task: UCF M-next E — both host matrices and the crash/partition suite through the wired composition
Role: Yang Compiler and Universal AST Engineer

Implementers:
- Model: assigned at dispatch | Assigned: <at dispatch> | Status: pending | Rationale: the final linker M-next milestone; a test-and-evidence stage over the landed composition, dispatched only after D16's stage-D gate passes

Dispatch precondition. Stage E assumes D15's contract for
`src/cljc/yin/vm/ucf/compose.cljc`; that file was not yet on master at
drafting time (D15 was in flight). Dispatch only after D15a's split, D15's
composition and REPL wiring, and D16's gate have landed. If at dispatch the
entry point is absent or its public surface differs from the D15 brief,
stop and report; that is a ruling.

Implement E in /Users/sto/workspace/datomworld-e (fresh worktree, branch
`ucf-stage-e`, based on master with D15a, D15 and D16 landed). Read first:

- docs/design/yin.vm.linker.dht.md: 14.2.4 (the eight test contracts),
  14.2.1 (the acceptance invariant and `exclusive-capable?`), 14.1.1 and
  14.1.3 (the nine ordered pairs and their transport rules), 14.3 items
  4 and 5.
- docs/design/yin.vm.universal-continuation-format.md 7.11.1: the
  version-1 block, clauses 3, 8 and 10, and the C12 note that leaves
  clause 10 open ("with real kills, remain stage E").
- The D/E boundary: archive/1791194000000-architect-m-next-d-plan-r3
  .claude-fable-5-1.findings.md section 3 — D hands E `yin.vm.ucf.compose`
  as the only entry point, the regenerated fixtures, and the journal's
  record format.
- The contracts the crash cuts exercise:
  archive/1791320000000-architect-d14-seam-ruling-astra.gpt-6-astra
  .findings.md (the freeze/rehydrate recovery seam, the exit order,
  journal intent-before-attempt) and collab/1791374000000-architect-d15a
  -inbox-ruling-astra.gpt-6-astra.findings.md (the version-1 inbox
  descriptors and the four-state retention machine).
- The landed code you drive, never modify: src/cljc/yin/vm/ucf/compose.cljc,
  holder/driver.cljc (`control-step`/`program-step`/`stop`/
  `owed-control-write?`), holder/export.cljc, holder/writer.cljc,
  holder/reader.cljc, holder/evidence.cljc, authority/front.cljc,
  authority.cljc (`exclusive-capable?`), and the regenerated fixtures in
  test/yin/vm/ucf/checkpoint_fixtures.cljc.
- The process precedent: test/yin/repl/dht_process_test.clj — `spawn!`,
  `type!`, `await-line`, `stop!`, ephemeral ports (port 0), bounded-deadline
  output polling, destroy-after-budget, and the required-build failure
  (`bb build:yin-repl-node`) when a host binary is missing.

The contract:

1. **The wired composition is the only entry point.** Every stage-E row
   drives `yin.vm.ucf.compose`: the authority side (open, judge step, one
   front step per holder, target/outcome readers, diagnostic stream) and
   the holder side (`control-step`/`program-step` over the
   composition-supplied seams — inbound appender, reply and outcome
   inboxes, ledger reader, lease clock, progress journal, protection
   declarations). D's protocol tests drove the holder namespaces directly
   below compose; E does not. The exclusivity gate is compose's, never
   bypassed: exclusive only on a file-backed authority for which
   `authority/exclusive-capable?` holds for the required failure model
   (at least `:process-crash` for the kill rows); exclusive on a memory
   authority is refused `:yin.k/unsatisfied`, never downgraded; fork only
   when selected, labelled fork. The carrier is the node's existing
   content store and staged module-load path; no second loader, no second
   DHT step owner.
2. **Both host matrices, ordered.** "Both" means the two matrices of
   14.2.4 and 14.1.1 together:
   (a) the holder matrix — each of JVM, Node and Dart as holder and as
   authority/consumer, over the same portable transactional seam (a seam
   models atomic commitment and durable reopen, never separate mutable
   check/action stubs); and
   (b) the nine ordered source/receiver pairs of 14.1.1 — JVM/Node/Dart
   each way including the three same-host controls — actual canonical
   bytes between fresh runtimes; an in-process handle or shared registry
   fails the setup. JVM and Node pairs run in separate OS processes
   (separate `yin.repl.main`-style processes per the dht-process
   precedent, over durable file-backed media that survive a kill). Dart
   pairs run fresh isolated runtime tables over the portable mesh seam;
   Dart's process-transport coverage is recorded separately and is never
   inferred from mesh coverage.
3. **Clause 10 through the composition, with real process kills and crash
   cuts around completion and around result delivery:**
   - Around completion (14.2.4 row 7): kill after the successor append,
     after the resumed report, after the release append, and after
     authoritative closure; reopen and redeliver evidence. Before closure
     no successor is eligible; after closure O never grants again and the
     recorded successor is eligible once; a failure-lower release reoffers
     instead of completing; a recoverable authority restart advances the
     epoch before new admission; permanently lost authority fails stop.
   - Around result delivery (row 4): kill immediately before and after the
     atomic effect commitment and before the result append; reopen,
     regrant, retry; zero or one commit as appropriate, the recorded
     result redelivered, stable ids, no duplicate side effect. External IO
     outside the transaction earns no exactly-once assertion.
   - Partition (row 6): really partition the protected consumer, or a
     remote holder reaching the authority through its front; lose a lease
     fact or a reply, delay renewal, and advance time only by appended
     ticks. Protected admission suspends without the authority; absent
     lapse proves nothing; a resent request replays; a reply not
     attributed to the arbitration identity discharges nothing.
   - Reclaim (row 3): an effect delayed under epoch e across a reclaim to
     e+1 is stale; one commit and stored result for the current holder;
     concurrent reclaim/admission serializes either way, never both.
   - Candidates and export (rows 1 and 2): one admitted holder over two
     encodings of O and two candidates across hosts; the source emits
     nothing until its own grant; every carrier/offer append failure
     including unknown acceptance; kill/reopen the export driver after
     each phase; abort only before a proven unadmitted offer; duplicate
     evidence creates no second grant.
   - Replay divergence (row 5): evict a kept-cursor value after a real
     holder kill; recover once with durable inputs and once without;
     replay reproduces the old intent/result or fails closed; divergence
     at the same id is `:intent-conflict`, no commit.
4. **The composition halves of clauses 3 and 8.**
   - Clause 3's stage-E half — crash and regrant through the composition:
     a real holder kill between assign and discharge, then regrant,
     restores the sequence counter exactly, keeps carried ids on `:put`,
     `:ffi-request` and `:link-request`, and draws children from the
     root's sequence — all through wired compose and the journal's record
     format (an intent record precedes every external action; an
     uncertain append stalls the driver until reopen reconciles).
   - Clause 8's stage-E half: the admission fixtures run end to end
     through the composition under real cuts — the full check order
     (unreadable authority, wrong author, stale epoch, stale lease, closed
     occurrence, foreign id, inherited id from a closed ancestor, equal
     intent, different intent, fresh commit); the cross-target conflict
     that quarantines the occurrence; forged `:committed`/`:intent-conflict`
     that discharge nothing; the unknown-effect transport error cut before
     and after the remote commit (id retained, retry through the fenced
     boundary commits once or replays); the unreadable accepted checkpoint
     answering `:suspended` with tenure, quarantine and dedup unchanged.
     Assert the exact 7.9 outcome maps; compare outcomes as data on every
     host, never text.
5. **The inbox and journal cuts under real kills** (the D14/D15a contracts
   the cuts exist to exercise): kill after a source read but before
   retention (the fresh driver reproduces the same attributed record at
   the same position); after retention but before cursor advancement (one
   queue entry, not two); at an uncertain retention append (stall, then
   reconcile before advancing or dispatching); at each journal
   intent/acknowledgment bracket and around freeze/storage/fencing of the
   recovery object (rehydration reproduces the exact handoff bytes and
   stays fenced). A restarted holder with a journaled grant never resumes
   execution: it releases and re-enters candidacy.
6. **The per-host durability record and unsupported compositions.** Record
   per host and per matrix cell: transport mode (separate processes vs
   isolated-runtime mesh), test namespaces, exact counts with red/green
   evidence, the crash cuts actually executed, and Dart's process-transport
   line kept separate. Any unsupported composition is recorded as a named
   prerequisite citing the specific landed seam that cannot support it
   (per the D15a adapter rule) — never a weakened contract, never a
   silently skipped row. Enable exclusive only when the authority and the
   declared consumers pass their gates on that host; where a consumer
   cannot, the recorded refusal and its reason are the row's result.
   Absent evidence leaves the row open.
7. **What E does not claim.** Milestone completion is not UCF closure.
   Full acceptance waits for every row of 7.11.1, including the post-M5
   gates; 14.1 and 14.2 close different gates with independent evidence.
   E closes clause 10 and the composition halves of clauses 3 and 8; it
   neither re-closes nor re-opens the M4 kept-cursor gate, does not make
   unprotected external IO transactional (14.2.1's guarantee is admission,
   not effect atomicity beyond the enrolled boundary), and leaves
   governance recovery — after quarantine, exhaustion or lost authority
   state — outside the automatic protocol. Say exactly this in the final
   report.

Test contract (clause 10 and the clause 3/8 halves, per row; each row's
tests are written red first against the composition, then green):

- Every 14.2.4 row above, on every cell of both matrices as applicable,
  with the kill cuts at the named boundaries only — no invented cuts, no
  skipped phases.
- A fork row per host: fork runs when selected, is labelled fork, and an
  exclusive requirement over a memory authority answers `:yin.k/unsatisfied`.
- The sequence/regrant row and the admission-order row of clauses 3 and 8,
  each as one cross-host kill row and one same-host control.
- The inbox retention cuts and journal bracket cuts, at least once per
  host lane that supports processes (JVM, Node), and on Dart over the
  isolated-runtime mesh with its coverage recorded separately.

Acceptance criteria:

- Test-first per row; portable `.cljc` for everything that runs on the
  lanes; the process harness is JVM-hosted `.clj` per the dht-process
  precedent, spawning JVM and Node child processes; real kills are
  SIGKILL-equivalent (`destroyForcibly`/process kill), never a graceful
  quit. Every spawned process is stopped or destroyed in a `finally`; no
  reserved ports; no wall-clock sleeps — polls use bounded deadlines and
  partition rows advance time only by appended ticks.
- Three lanes at landing. Iterate JVM-focused; run `bb test:changed` /
  the full lanes once at landing, one lane set at a time, foreground,
  stdin closed.
- Permitted diff: new test files under test/ (the cross-host matrix, the
  crash/partition suite, shared test-support helpers), and nothing else.
  The composition, the holder namespaces, the engine and the authority
  are NOT in the permitted diff — a missing public seam or a needed
  product change is a stop-and-report ruling. Document amendments are
  reported, not written.

Constraints:

- No git writes. kondo/cljstyle may be sandbox-blocked; note it.
- `#?(:cljd nil :clj ...)` order for JVM-only test branches (:cljd first);
  a 0.0 literal is the integer 0 on JS. Sequence/epoch fixtures live at
  2^52-1: build such integers portably, never as bare JS-number literals.
- Compare admission outcomes, replies and journals as data on every host,
  never as printed text.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report changed files, the per-host/per-pair durability record of contract
item 6, exact test/check outcomes with counts, the red and green evidence
per row, any unsupported composition with its named prerequisite,
unresolved concerns, and any incomplete work. State explicitly that
milestone completion does not claim full UCF closure. Do not claim edits
or tests that did not occur.
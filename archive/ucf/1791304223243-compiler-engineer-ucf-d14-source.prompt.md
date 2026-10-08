Created-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Created-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: assigned at dispatch
Session-ID: pending (provider-generated)

# Task: UCF M-next D14 — the driver's source and exit half with the progress journal
Role: Yang Compiler and Universal AST Engineer

Implementers:
- Model: assigned at dispatch | Assigned: <local timestamp> | Status: active | Rationale: the holder-side driver; the source/exit half and the write-ahead journal over its engine room

Implement D14 in /Users/sto/workspace/datomworld-d14 (worktree, branch
ucf-d14-source, based on master with D13 landed; the branch rebases
onto master after D13 lands — D13 is in flight in the sibling worktree
/Users/sto/workspace/datomworld-d13 and both slices extend
holder/driver.cljc, which is why they serialize). Read first, in the
worktree's collab/: the D plan r3 (1791194000000-architect-m-next
-d-plan-r3.claude-fable-5-1.findings.md — sections 1.6 (ending a run),
1.8 and 1.9 (the source half: export, prepare, abort), 1.10 (the
progress journal contract), the slice table's D14 row and the D14 test
contract), the D9 header ruling (1791231000000-architect-d9-header
-ruling.claude-fable-5-1.findings.md — the header enters at prepare
and is stored in the record, the served table re-keyed by [task-path
resource-id] so the record is plain data a journal can store, and
prepare mints nothing: the occurrence is minted once by the driver and
persisted before prepare), the D10 inputs ruling (1791240000000
-architect-d10-lower-inputs-ruling.gpt-6-astra.findings.md — the
checkpoint must be an admitted variant of the granted occurrence), and
the abort rule (UCF 7.7.4 as amended). Then the landed namespaces:
holder/export.cljc (enter/prepare/encode/abort), holder/writer.cljc,
holder/reader.cljc, holder/evidence.cljc, authority/front.cljc (the
offer, resumed and release carriage), authority/completion.cljc (the
report, the closure, the successor chain), dao/stream/journal.cljc
(the journal the composition supplies), and UCF 7.7.7 (restart; its
C12 identity-discipline sentence is the read r3 1.10 defers to D14)
and 7.7.8 (the fence and the request dedup), with linker-dht 14.2.4
rows 2 and 7 as D14's evidence rows. The file you extend is
holder/driver.cljc, the candidate half D13 lands.

The contract (r3 1.10's journal over 1.8, 1.9 and 1.6, as amended by
the rulings):

The candidate half's step state — one explicit state, plain data,
stepped by the composition — carries; D14 adds the source and exit
half over it. Everything durable goes through the progress journal: a
`dao.stream.journal` the composition supplies, write-ahead, as a seam
beside the D13 seams. Its records — the `fenced` record, the intent
records (offer, proposal with its stable proposal id, resumed report,
release, enrollment request) and the acknowledgment records — are
plain data D14 defines; r3 hands the journal's record format to E.

1. **Enter exporting** through holder/export: `enter` sets the gate
   and moves the waits and parked records into the record; `prepare`
   serves each stream once, keyed by [task-path resource-id], and a
   refusal carries what was served so a retry never serves a stream
   twice; `encode` is pure. The header decision is the composition's,
   never the driver's: a nil header is a first-export fork (version 0,
   no occurrence, no offer, no journal bracket), a header is
   version-1 exclusive.
2. **Mint once, journal before prepare** (the D9 ruling: prepare
   mints nothing). For a first exclusive export the driver mints the
   occurrence — the composition names the arbitration medium, the
   enrolled set comes from the ledger reader's fold, next-op-seq 0,
   no origin; for a successor the driver builds the header from the
   custody map: a fresh occurrence, the same arbitration, an origin
   naming the predecessor occurrence and lease, the current counter.
   The minted occurrence is durable in the journal before `prepare`
   is called. Before `fenced`: the prepared export record and the
   body bytes go to the content store; the `fenced` record references
   them and carries the occurrence.
3. **Every external action is bracketed**: an intent record durable
   before the action, an acknowledgment record after authenticated
   evidence — `front/reply-evidence`: the author attributed to the
   stream equals the arbitration identity, identity equality,
   descriptors never compared. A resent proposal keeps its stable id
   (7.7.8: a proposal id is answered at most once); a refusal mints a
   fresh one.
4. **The offer intent precedes the send — the abort rule depends on
   it**: `export/abort`'s `attempts` are the journaled ones, one per
   attempt whose intent was durable before the send, `:append` the
   outcome its append answered. Abort restores local execution only
   with no persisted attempt, or when every attempt's append proves
   the value was not appended (full, invalid-value, closed, refused);
   a refusal of one request is not such evidence. Uncertain or
   admitted offers stay fenced: the source resends the offer, and the
   only way back is a grant to itself. A holder exporting a successor
   aborts only with tenure `{:now n :bound b :live true}` — the
   ledger reader's evidence and the bound; after the lease has ended
   its old local machine is never restored.
5. **The exit brackets, in order** (the landed completion contract):
   the successor append (the offer admitted), the resumed report —
   naming the successor's address; for a halt the report carries the
   result body and the closure's edge is terminal to the result's
   address — then the release of the origin lease, then the
   authoritative closure (the grantor's transition on the release
   lapse of a reported lease; a closed occurrence never grants
   again). An exit cut at any boundary: reopen, fold, rebuild, resend
   from the last intent — the identical request, idempotent through
   the ledger's dedup (`:replayed`), and never a second successor:
   the journaled occurrence and prepared record are reused, never
   reminted.
6. **Tenure holds through the exit** (D13's :safepoint hands off
   here): the renewal before half the duration and the tenure recheck
   continue while the exit half runs; all IO stops at the bound.
7. **Reopen reconciliation** (r3 1.10's recovery of a source): fold
   the journal; stay fenced; rebuild the waits, the retained ids and
   the descriptors from the stored prepared record; resend from the
   last intent. A crash at each intent and acknowledgment boundary
   reopens fenced with the same occurrence, waits and ids.
8. **An uncertain journal append stops everything** — program steps
   and sends alike — until the journal is reopened and reconciled
   (the journal's own rule: a failure between write and visibility
   poisons appends; only a reopen, which reads the frames, can say).
9. **Recovery of a holder**: a journaled grant never restores
   execution — the machine died with its process. The restarted
   driver sends the pending release and re-enters as a candidate; a
   regrant replays from the checkpoint (input from zero under the
   evidence's prefix). The candidate acceptance rules carry
   unchanged, the admitted-variant proof included: the checkpoint
   address must be among the ledger fold's admitted variants of the
   granted occurrence — equality of supplied addresses is not proof.
10. **Enrollment retries** follow UCF 7.7.7's identity discipline
    gained with C12 — the sentence r3 1.10 defers to D14: enrollment
    carries no request identity; after an uncertain answer reread the
    projection and enroll only when the derived target identity is
    absent; a blind retry enrolls a second target.
11. **A run end still ends** (r3 1.6, residual 2): :stale,
    :intent-conflict, an :input-conflict refusal, a replay divergence
    or the bound gates :ended, stops all program IO, publishes no
    successor, appends one diagnostic, and releases while the lease
    may still be live — cleanup, not recovery: a release never clears
    a quarantine and never completes without accepted completion
    evidence; an ordinary failed run stays regrantable.

Test contract (r3's D14 row — 14.2.4 rows 2 and 7):
- A crash at each intent and acknowledgment boundary reopens fenced
  with the same occurrence, waits and ids.
- A crash between send and acknowledgment resends, and no second
  grant results.
- An uncertain journal append stops the driver.
- A restarted holder with a journaled grant does not run; it releases
  and re-proposes.
- Exit cuts at successor append, report, release and closure; a halt
  completes with a result body.
- **(residual 2)** A release without accepted completion evidence
  never closes the occurrence; after a replay divergence the released
  occurrence is regrantable; after `:intent-conflict` the released
  occurrence stays quarantined and ungrantable.

Acceptance criteria:
- Test-first per behavior; portable `.cljc`; JVM during iteration.
- src/cljc/yin/vm/ucf/holder/driver.cljc — which D13 created; the
  source/exit steps extend it — and its test file. Anything else is
  NOT in the permitted diff: the landed namespaces, dao.stream.journal,
  the authority, the engine — if a public seam is missing, stop and
  report (that is a ruling).
- No transport vocabulary: the front streams, the ledger reader, the
  lease clock and the progress journal are composition-supplied
  seams; the driver appends and folds journal records and owns the
  records' meaning, never a backend.

Constraints:
- No git writes. kondo/cljstyle may be sandbox-blocked; note it.
- `#?(:cljd nil :clj ...)` order for JVM-only test branches (:cljd
  first); no float literals — the custody numbers are exact integers,
  and a 0.0 literal is the integer 0 on JS.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report changed files, exact test/check outcomes with counts, the red
and green evidence, unresolved concerns, and any incomplete work. Do
not claim edits or tests that did not occur.

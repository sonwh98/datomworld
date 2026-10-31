Created-GMT: 2026-10-06 08:35:00 GMT
Created-Local: 2026-10-07 15:35:00 +0700
Coding-Agent: glm (glm-5.3, plan review)

# Task: gate review, M-next D11 — the fenced writer (read-only; a verdict is the deliverable)
Role: Review (routine gate)

Review the uncommitted work in /Users/sto/workspace/datomworld-d11
(branch ucf-d11-writer, based on master 441b2b4c with D10 and the
ClojureDart var fix landed). The engineer's report:
/Users/sto/workspace/datomworld-d11/collab/1791265000000-compiler
-engineer-ucf-d11-writer-continue.findings.md (note: this is a
continuation round over a partial glm implementation — audit the diff
as a whole, not just the delta). Read it first, then `git -C
/Users/sto/workspace/datomworld-d11 status` and the diff (engine.cljc,
holder/writer.cljc, holder/writer_test.cljc).

The contract: D plan r3 sections 1.3 and 1.11 and the D11 test
contract (/Users/sto/workspace/datomworld-d11/collab/1791194000000
-architect-m-next-d-plan-r3.claude-fable-5-1.findings.md), the D10
inputs ruling's custody-map shape (1791240000000-architect-d10-lower
-inputs-ruling.gpt-6-astra.findings.md), and UCF 7.7.5/7.7.8. The
writer: assign (one step, the root counter, children draw from it,
2^52-1 assigns nothing and sends nothing), send (the five-key
envelope, the tagged-vector request id `[:yin.k/admit lease op-id]`
— correlation only, the authority's op-id namespace dedups), retain
(through full, unknown-effect, no outcome, suspended), discharge
(only an authenticated :committed/:replayed passing
front/reply-evidence and matching request id, op id and incarnation;
projected outcomes matched by op id and incarnation), the three
protection classes, and after :intent-conflict: no further fenced
emission, the release carried, the occurrence stays quarantined,
open and ungranted. Carried obligations: the apply-next unminted-cell
guard and the terminal-outcome FFI apply (apply-ffi-outcome).

## What to attack

1. Assign-before-send is truly one step and atomic per entry: no path
   sends without an id, no path assigns twice (idempotent re-emit),
   the counter increments exactly once per write, children draw from
   the root's counter, and the 2^52-1 bound assigns and sends
   nothing.
2. The envelope: five keys, canonical form, no envelope key leaking
   into any body; the request id is correlation only and the writer
   never treats a reply's request id as dedup.
3. Retention and discharge: every one of the four retention cases
   keeps the id and sends nothing further; the five reply-match
   fields (author, reply kind, request id, op id, incarnation) each
   refuse independently; a projected outcome of another incarnation
   is ignored; a duplicate outcome for a discharged wait is skipped.
4. :intent-conflict: no further fenced emission, the release is
   carried, the occurrence stays quarantined/open/ungranted, and an
   at-least-once write is not performed after it.
5. The protection classes on every write path; an enrolled write
   never goes bare; a fail-stop write ends the run.
6. The carried obligations: the unminted-cell guard on apply-next
   (and apply-observation), and apply-ffi-outcome's terminal
   dispositions matching the ungated sweep's rules.
7. The unknown-effect transport-error cut before and after the
   commit: one commit or a replay of the held result, the id
   retained.
8. Scope, portability and style: only the three named files; no
   transport vocabulary in the writer (the appender and outcome
   reader are composition-supplied functions); :cljd-first
   conditionals; no host-number traps; kondo/cljstyle clean (the
   orchestrator ran them).

Verdict first: READY or NOT READY (with what must change), then
numbered findings with file:line evidence. Read-only: edit nothing,
run no suite.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

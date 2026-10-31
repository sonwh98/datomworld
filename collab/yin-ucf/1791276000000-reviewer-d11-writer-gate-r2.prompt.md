Created-GMT: 2026-10-06 09:10:00 GMT
Created-Local: 2026-10-07 16:10:00 +0700
Coding-Agent: glm (glm-5.3, plan review)

# Task: gate review r2, M-next D11 — the fenced writer after the link/close round (read-only; a verdict is the deliverable)
Role: Review (routine gate)

This is the SECOND gate review of the work in
/Users/sto/workspace/datomworld-d11 (branch ucf-d11-writer). Your r1
reviewed the pre-fix diff and said READY on the put/FFI paths — that
verdict is superseded: the architect's sign-off then found two missing
D11 obligations (retained link-request writes; the close-ordering
obligation), which are now implemented. Review the CURRENT diff (git
-C /Users/sto/workspace/datomworld-d11 diff; writer.cljc, engine.cljc,
writer_test.cljc) as a whole. The architect's findings are at
/Users/sto/workspace/datomworld/collab/1791270000000-architect-d11
-signoff-astra.gpt-6-astra.findings.md; the fix-round report at
/Users/sto/workspace/datomworld-d11/collab/1791265000000-compiler
-engineer-ucf-d11-writer-continue.findings.md (its final round is the
link/close round; the orchestrator then repaired three defects the
landing lanes caught — see the notes below).

What changed since your r1:
1. **Link writes**: writer.cljc now recognizes `:link-request` entries
   (link-write?, target = the link-request resource, envelope value =
   the entry's :envelope), mints the response cursor before the send
   (mint-link-cursor via engine/apply-link-cursor), and discharges
   through engine/apply-link-sent (apply-effect's link arm).
2. **Close ordering**: emit interleaves pending :yin.k/closes with
   writes in :yin.k/issue order per stream — an unresolved earlier
   write blocks a later close, a later write cannot overtake a close
   (pending-closes, close-resolvable?, write-emittable?); the close
   classes follow the D5 close ruling (:at-least-once the driver
   closes idempotently; :enrolled diagnostic + resolved, never
   closed; :fail-stop ends the run), discharged through
   engine/apply-close.
3. **The orchestrator's three repairs after the lanes** (verify each):
   (a) a REAL defect: emit's close arms used `(assoc-in m path ...)`
       which with the root's empty path returns the applied state but
       fails to write it back into m — `(assoc-in m [] x)` adds a
       nil-keyed entry — so root closes never resolved and emit
       looped to OOM; both arms now use the seq-path guard
       apply-effect already had. My close-ordering test caught it
       (mutation evidence: the pre-fix code OOMs).
   (b) the out-of-range epoch row in an earlier slice — not here.
   (c) the two test sections were rewritten against the file's real
       helpers (the prior round had written them against a
       nonexistent yin.vm.test-utils/new-machine and left them
       uncompiling).

## What to attack

1. The two new obligations, end to end: link assign/send/retention/
   cursor-before-send/discharge-through-apply-link-sent (including
   install children where reachable); close ordering including the
   restored-put -> close -> later-put regression, children, and a
   write awaiting its admission outcome.
2. Your r1's eight attack points still hold on the final diff
   (assign-before-send atomicity, envelope, retention, the five match
   fields, :intent-conflict, protection classes, the carried
   obligations, the unknown-effect cuts).
3. The orchestrator's assoc-in repair: confirm the seq-path guard is
   correct for root and child closes, and sweep writer.cljc for any
   other empty-path assoc-in hazard.
4. The one-word hardening your r1 recommended (an explicit
   :enrolled arm so an unknown class value fails closed) — check
   whether the final diff has it; if not, re-state it as a landing
   condition or accept its absence explicitly.
5. Portability and style of the final files (kondo/cljstyle clean per
   the orchestrator's runs).

Verdict first: READY or NOT READY (with what must change), then
numbered findings with file:line evidence. Read-only: edit nothing,
run no suite.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

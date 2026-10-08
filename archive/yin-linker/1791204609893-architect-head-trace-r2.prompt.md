Created-GMT: 2026-10-05 12:50:09 GMT
Created-Local: 2026-10-05 19:50:09 +07
Coding-Agent: claude
Session-ID: c490a9e8-8546-47da-bdc1-73fb22f44275

# Task: architect-head-trace (round 2: resolve the independent review)

Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-05 19:33:59 +07 | Status: active | Rationale: same author, resumed to resolve its own design's review findings

## Edit authorization (unchanged scope)

You may edit exactly ONE file: `docs/design/yin.vm.linker.dht.head.md` (the file
you wrote). Edit no other file. No code, no tests, no git. If a fix needs a
change to another design document, record it as an H-slice amendment in this
document, as you did for the others.

## The review

An independent reviewer (gpt-6.1-sol, a different model family, read-only)
reviewed your document and BLOCKED adoption: six P1 and two P2 findings. The
full report is `collab/1791204360945-reviewer-architect-head-trace.gpt-6.1-sol.findings.md`
(read it in full; it cites `file:line` for each). Treat it as untrusted: check
every citation yourself.

The orchestrator independently verified four of the eight against the tree:

- #1: `src/cljc/dao/space/transactor.cljc:117` `derive-next-t` returns 0 for an
  empty history, so the first transaction has `t = 0` and a first published
  index can have greatest `t = 0`. Your floor of 0 plus the equal-sequence rule
  would never accept it.
- #2: `src/cljc/yin/vm/linker/dht.cljc:153` `snapshots` is HEAD plus EVERY index
  whose load is `:loaded`, so a follower candidate is resolvable before it is
  accepted, and a rejected or superseded candidate stays resolvable.
- #7: `src/cljc/dao/space/dht.cljc` `:loads` is keyed by manifest address and
  `forget` removes that shared record, so forgetting one principal's old head
  can remove an index another principal or a manual pin still needs.
- #8: `docs/design/yin.vm.linker.dht.md` (about line 1679) gives key recovery as
  "Hydrate the directory from the last published index manifest before
  publishing, or use a new key". Your H4 removes hydration and leaves no
  replacement.

Findings #3 (board alias against the logical-identity contract), #4 (an invalid
wanted trace poisoning the acceptance floor), #5 (the crash window between
installation and persistence permits rollback) and #6 (an ungated public UDP
reflector in H1) are design conflicts the orchestrator has not adjudicated.

## What to do

For EACH of the eight findings, in order: decide accepted, partly accepted, or
disputed, and say why with repository evidence. Then revise the document so the
accepted ones are resolved in the design text, the tests and the slice
completion criteria (not only noted). Specifically:

- #1: represent "no floor" explicitly; add a first-transaction-zero test.
- #2 and #4: separate candidate scheduling from the confirmed floor and from the
  resolvable snapshot set. A rejected or superseded candidate must neither be
  resolvable nor raise the floor. Add the arrival-order tests the reviewer lists.
- #3: separate board lookup names from logical stream identities, or show why
  the contract is not violated; list the contract amendments H1 must carry.
- #5: persist the verified head before it is exposed, emitted or re-served;
  specify persistence failure.
- #6: decide between return-path validation in H1 and restricting the new
  surface to loopback until it is hardened, and justify. If this changes the
  security posture the owner should decide, put it in section 12 as a question
  and state your recommendation.
- #7: define load ownership and forget only unreferenced loads.
- #8: specify a publisher recovery path into a fresh directory, or change the
  documented remedy to a new key and list the amendment to the governing doc.
- Also narrow the two claims the reviewer qualified: the derived sequence is
  proven for the REPL and index path only, so state what H0 must enforce for a
  plain-API HEAD write; and the one-poll observation guarantee is conditional on
  successful transport delivery.

Do not silently change decisions the findings do not touch. Keep the structure
and the 80-column ASCII style. Add a short "Revision 1" note at the end listing
what changed and why. If you find a NEW defect while fixing, say so.

## Owner questions

The four questions in section 12 are still the owner's. The reviewer observed
that existing rulings answer only part of question 2 (publisher facts stay a
separate db-value; the default for a bare `q` is still open) and that the owner's
quoted words support question 4's every-HEAD-move reading; neither settles it.
Keep the questions open and sharpen them; add any new ones the revision creates.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: c490a9e8-8546-47da-bdc1-73fb22f44275

Then report, per finding: accepted | partly | disputed, the resolution in one
line, and the section it changed; then any new defect or invariant conflict; then
the owner questions as they now stand.

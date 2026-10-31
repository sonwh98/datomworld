Created-GMT: 2026-10-07 06:15:00 GMT
Created-Local: 2026-10-07 13:15:00 +0700
Coding-Agent: assigned at dispatch
Session-ID: 2abd669b-64f2-4dfc-8ba7-de1511d2c8f7

# Task: UCF M-next D11 — the fenced writer
Role: Yang Compiler and Universal AST Engineer

Implementers:
- Model: assigned at dispatch | Assigned: 2026-10-07 13:15:00 +0700 | Status: active | Rationale: the holder-side writer; D6's applies are its discharge path

Implement D11 in /Users/sto/workspace/datomworld-d11 (worktree, branch
ucf-d11-writer, based on master 441b2b4c with D10 landed). Read first, in the
worktree's collab/: the D plan r3 (1791194000000-architect-m-next
-d-plan-r3.claude-fable-5-1.findings.md — sections 1.3, 1.11 and the
D11 test contract), the astra review's finding 4 (correlation vs
dedup) and its confirmation, and the landed rulings. Then
src/cljc/yin/vm/ucf/holder/ (export.cljc, evidence.cljc),
src/cljc/yin/vm/ucf/authority/front.cljc (reply-evidence, the request
kinds), src/cljc/yin/vm/engine.cljc (the public applies D6 landed),
and UCF 7.7.8's envelope and operation-sequence state.

The contract (r3 1.3 as amended):

1. **Assign**: for a retained write whose target is enrolled and
   which has no id: take n from the custody map's counter, store the
   op id on the entry, set the counter to n+1 — one step, before any
   request is sent. At 2^52-1 nothing is assigned and nothing is
   sent.
2. **Send**: build the five-key envelope from the entry and the
   custody map, append an `:yin.k/admit` request to the holder's
   inbound stream. The request id is the tagged vector
   `[:yin.k/admit lease op-id]` in canonical form — correlation
   only, never dedup; the authority's op-id namespace dedups.
3. **Retain**: the id stays through a `full` inbound stream, an
   unknown-effect append, no outcome yet, and `:suspended`.
4. **Discharge** on an authenticated `:committed` or `:replayed`:
   the reply must pass `front/reply-evidence`, match the request id,
   the op id and the incarnation; a projected outcome counts when
   read from the attributed outcome stream with matching op id and
   incarnation. Apply through the engine's public applies
   (`apply-put`/`apply-next` for the entry's continuation, the
   retained-request transitions).
5. **The three protection classes** (`:enrolled`, `:at-least-once`,
   `:fail-stop`) govern every write the driver performs; an enrolled
   write never goes bare; an at-least-once write carries no id; a
   fail-stop write ends the run.
6. **After `:intent-conflict`**: no further fenced emission, the
   release is carried, and the occurrence stays quarantined, open
   and ungranted (the quarantine rule; a D11 release cannot clear
   it).
7. **Unknown-effect transport error**, cut before and after the
   commit: the id is retained, the retry goes through the fenced
   boundary, and it commits once or replays the result already held.

Carried obligations for D11 (from earlier gates):
- The terminal-outcome apply for retained FFI requests (D6's
  apply-ffi-sent covers `:ok` only; a terminal append outcome needs
  its disposition — rule with the architect if it is not mechanical).
- The apply-next unminted-cell guard (D6 gate finding 1: apply-next
  must refuse an unminted cursor cell).
- The op-id obligation from D9's fixture helper: a real fenced-writer
  run must produce wait entries equal in shape to what the D9 helper
  sets; if the shape differs, D11 regenerates the fixtures.

Test contract (r3's D11 row):
- Assign before send; one increment; children draw from the root's
  counter.
- Retention across the four cases.
- A reply failing any one of the five match fields discharges
  nothing.
- A projected outcome of another incarnation is ignored.
- A resent request after a regrant has a new request id and the same
  op id.
- Unknown-effect transport error, cut before and after the commit.
- After `:intent-conflict`: an at-least-once write is not performed,
  the release is carried, and the occurrence stays quarantined, open
  and ungranted.

Acceptance criteria:
- Test-first per behavior; portable `.cljc`; JVM during iteration.
- New namespace src/cljc/yin/vm/ucf/holder/writer.cljc and its test
  file; handoff.cljc is NOT in the permitted diff (the counter and
  ids live on the entries/custody map D10 restored). Anything else:
  stop and report.
- No transport vocabulary: the writer takes the inbound-stream
  appender and the outcome reader as composition-supplied functions.

Constraints:
- No git writes. kondo and cljstyle may be sandbox-blocked; note it.
- `#?(:cljd nil :clj ...)` order for JVM-only test branches (:cljd
  first); a 0.0 literal is the integer 0 on JS.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report changed files, exact test/check outcomes with counts, the red
and green evidence, unresolved concerns, and any incomplete work. Do
not claim edits or tests that did not occur.

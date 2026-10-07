Created-GMT: 2026-10-05 16:03:00 GMT
Created-Local: 2026-10-05 23:03:00 +0700
Coding-Agent: claude (opus-5-5 if available, else glm-5.3)
Session-ID: pending (provider-generated)

# Task: UCF M-next D8 — the exporting state, the prepare/encode split, and the abort rule
Role: VM Runtime Engineer (holder side)

Implementers:
- Model: assigned at dispatch | Assigned: 2026-10-05 23:03:00 +0700 | Status: active | Rationale: implementation role; the engine seam rounds (D4 to D6) landed, this begins the holder namespaces

Implement D8 in /Users/sto/workspace/datomworld-d8 (worktree, branch
ucf-d8-exporting, based on master with D7 landed). Read first, in the
worktree's collab/: the D plan r3 (1791194000000-architect-m-next
-d-plan-r3.claude-fable-5-1.findings.md — sections 1.8, 1.9 and the
D8 test contract), the astra review (1791191261015-architect-m-next
-d-plan-review.gpt-6-astra.findings.md, finding 9 — abort safety) and
its confirmation (1791192700000-...r2-confirm..., residual 2 — the
release wording), the D4 seam ruling (1791195500000-...), the D5
cursor ruling (1791197000000-...), the close ruling
(1791198000000-...), and the link-cursor ruling
(1791203000000-... — the export refusals for cursorless link entries
and unminted cells). Then src/cljc/yin/vm/ucf/handoff.cljc (export-task,
the D7 version-aware pipeline), src/cljc/yin/vm/engine.cljc (the gate
modes, the public applies), and the UCF doc 7.7.4 (the exporting
transition).

The contract (r3 1.8 and 1.9 as amended):

1. **Entering exporting** (the mode): sets `:yin.k/gate :exporting`
   on the root, requires an empty ready queue (`:yin.k/not-quiescent`
   otherwise), and moves the wait set and reachable parked records
   into an export record.
2. **Export refusals at entering** — `:yin.k/non-portable`, kind
   `:reason-mismatch`, for a task that holds, in the root or any
   child: an `:observe` entry; any entry carrying `:yin.k/held`; a
   reachable unminted cursor cell; a pending close record; a
   `:link-request` entry whose response cursor is not yet installed.
3. **Prepare, then encode** (r3 1.8): the export record retains every
   chosen resource identity, descriptor, occurrence, and remapping
   seed — prepare calls `serve!` once per stream, recursively for
   children, and stores the results; encoding that prepared record
   performs no resource allocation or publication and produces
   canonical bytes deterministically. (D8 builds the prepare/encode
   split; D9 regenerates the C4 fixtures through it.)
4. **The abort rule** (the reviewer's replacement text, binding):
   abort is permitted only before any possibly accepted offer
   attempt, or upon authoritative evidence that this occurrence has
   never been admitted and cannot still become admitted from an
   outstanding attempt. A refusal of one request is not such
   evidence. In practice: legal when no offer-attempt intent has been
   persisted, or when every attempt's append answered an outcome that
   proves the value was not appended. Otherwise the source stays
   fenced and resends the offer; the only way back is a grant to
   itself. A holder exporting a successor may abort only with current
   valid tenure; after the lease has ended its old local machine is
   never restored.
5. **In :exporting**: nothing is observed, no child is advanced, a
   direct resume is refused, and every public apply refuses a late
   result (the engine's D4-D6 refusals; D8 drives them through the
   exporting entry and abort paths).

Test contract (r3's D8 row):
- After entering exporting, polling the source, a direct resume, and
  ticking its install child append nothing (the blocked-writer hazard
  of 7.7.4).
- Prepare calls `serve!` once per stream; encode calls nothing
  (counting handles).
- Abort: allowed with no persisted intent; allowed when every attempt
  was provably not appended; refused after an unknown append, and
  refused after a refusal that followed an unknown append.
- A holder past its lease bound cannot abort.
- A non-empty ready queue refuses `:yin.k/not-quiescent`.
- Each export refusal kind from item 2 fires on a real parked machine
  (one per kind).

Acceptance criteria:
- Test-first per behavior; portable `.cljc`; JVM during iteration.
- New namespace src/cljc/yin/vm/ucf/holder/export.cljc and its test
  file; `handoff.cljc` changes only to extract prepare from encode if
  needed (say so in the report). Anything else: stop and report.
- No driver namespaces yet (D13/D14 own them); the namespace takes
  the machine, the served-table functions, and data — no transport
  vocabulary.

Constraints:
- No git writes. kondo and cljstyle may be sandbox-blocked in your
  session; note it and the orchestrator runs them at landing.
- `#?(:cljd nil :clj ...)` order for JVM-only test branches (:cljd
  first); a 0.0 literal is the integer 0 on JS.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report changed files, exact test/check outcomes with counts, the red
and green evidence, unresolved concerns, and any incomplete work. Do
not claim edits or tests that did not occur.

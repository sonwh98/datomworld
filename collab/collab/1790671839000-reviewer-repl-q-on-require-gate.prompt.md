Created-GMT: 2026-09-29 08:50:39 GMT
Created-Local: 2026-09-29 15:50:39 +07 (+0700)
Coding-Agent: codex
Session-ID: 01a0ec5b-fe43-77e1-9534-4ab9f6458cfc (captured)
# Task: Gate — yin.repl "q on require" (dao.space.query/q over the code index)

Role: Adversarial Code Reviewer and Security Auditor

Implementers:
- Model: gpt-6-sol | Assigned: 2026-09-29 15:50:39 +07 (+0700) | Status: active | Rationale: standing gate route; Claude-authored (claude-opus-5-5); gpt-6-sol also wrote the design — review against it, and do not defend the design where the code shows it wrong

Read-only review in WORKTREE /Users/sto/workspace/datomworld-q-require (branch repl-q-require from 3cf3c6de,
uncommitted): git -C /Users/sto/workspace/datomworld-q-require diff (src/cljc/yin/repl.cljc) plus new
src/cljc/yin/repl/query.cljc and test/yin/repl/query_test.cljc. Do not edit.

Governing documents:
- design: /Users/sto/workspace/datomworld/collab/1790669186000-architect-repl-q-on-require.gpt-6-sol.findings.md
- brief with OWNER decisions quoted (qualified dao.space.query/q only, bare q not bound; limits proposed by the
  implementer, reviewed by you, shown to the owner before commit): /Users/sto/workspace/datomworld/collab/1790669559000-vm-engineer-repl-q-on-require.prompt.md
- implementer report (untrusted): /Users/sto/workspace/datomworld/collab/1790669559000-vm-engineer-repl-q-on-require.claude-opus-5-5.report.md
- docs/design/yin.repl.dao.space-index.md; datom.world.md; slice-3d composite FFI ids / :call-out-cursor on master

Orchestrator-verified in the worktree (do not rerun): kondo 0/0, cljstyle clean, no changes under yin/vm or dao/;
focused JVM (query, repl, index, ffi) 75 / 568 / 0; full JVM 2353 / 184231 / 1 failure = yin.repl.main-test
killing-the-connection (the driver race fixed on master by dce6282c AFTER this worktree's base; not this change);
Node 2259 / 50724 / 0 (query-test ran); CLJD +2221: All tests passed!.

Check correctness against the design and acceptance 1-7, invariants (yin.vm ignorant of dao.space; no callbacks; host
boundary as data), portability, and missing tests. Rule explicitly on the implementer's points:
Q1. A 4th error code :yin.repl.query/query-failed for queries dao.space.query itself rejects (bad Datalog, :in arity,
    unknown fn), separate from invalid-input. Accept, or fold? (The owner will see your answer.)
Q2. The require is served by a session :module/require effect handler that installs the host module into the
    requiring VM's registry and delegates every other name to module/require-handler; the link interpreter never sees
    dao.space.query. Consistent with the design's "activates the module through the existing require handler"?
Q3. The call handler depends on each VM's existing park-entry builder and chooses the walker's entry shape by
    :vm-model. Acceptable coupling for a yin.repl bridge, or a layering defect (should it use a generic yin.vm/ffi API)?
Q4. Behaviour change: a raw (dao.stream.apply/call :x/y ...) at the prompt now gets an unknown-operation FFI error
    (the session VM now has a served call pair) instead of wedging. Acceptable?
Q5. Rollback: a failed round's rolled-back VM re-reads that round's old responses and skips them; >64 q calls in one
    failed round -> next call fails with a response-gap error. Also a rollback double-answer bug was found and fixed
    (the error carries the query interpreter cursor; both rollback paths restore it). Correct and adequately tested?
Q6. Limits: query-row-limit 1000; query-byte-limit 256 KiB canonical CBOR (also the portability check);
    query-pair-capacity 64 (worst-case retention 16 MiB); query-serve-budget 64. Reasonable?
Q7. Other: maps cannot be :in inputs (a trailing map is always options); each q re-reads the whole index; a query sees
    its own (already indexed) program. Defects or documented behaviour?

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Findings as P0-P3 | file:line | evidence | concrete fix, or "No actionable findings". Answer Q1-Q7; mark owner decisions.
End with Verdict: READY / REQUEST CHANGES and Sign-off: GRANTED / WITHHELD.

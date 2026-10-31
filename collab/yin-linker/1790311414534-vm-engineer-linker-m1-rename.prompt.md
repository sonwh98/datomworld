Created-GMT: 2026-09-24 21:10:00 GMT
Created-Local: 2026-09-25 04:10:00 +0700
Coding-Agent: zcode (GLM-5.3-Flash subagent)
Session-ID: zcode-subagent (linker M1 rename)

# Task: yin.vm.linker Milestone M1 — Rename (UCF Phase 2 kickoff)

Role: VM Runtime Engineer (ZCode subagent, GLM-5.3-Flash)

Repository: the fresh worktree /Users/sto/workspace/datomworld-ucf-phase2
(branch ucf-phase2 @ 1f7990d5, which includes the master specification).

Implement Milestone M1 exactly as specified in
docs/design/yin.vm.linker.md — read section 9's M1 description (line
~1854), the section 10 file box, and section 2's governing invariants
first, then docs/design/datom.world.md's invariants.

M1 scope (from the spec):
- Move src/cljc/yin/vm/debruijn_linker.cljc to src/cljc/yin/vm/linker.cljc
  (use `git mv`).
- Move test/yin/vm/debruijn_linker_test.cljc to
  test/yin/vm/linker_test.cljc (use `git mv`).
- Rename the namespaces and every reference across the repository
  (grep for both debruijn-linker and debruijn_linker spellings: requires,
  callers, doc references in code docstrings). Code docstring references
  update; DO NOT touch docs/design/*.md prose (the spec's migration
  section 9 owns those references by design).
- Zero behavioral changes. The spec's Must-not-change list (section 10)
  is absolute: image-hash, register-hash, segment-key, the opcode tables,
  dao.stream, dao.jing; no kernel step function changes.

Constraints:
- Pure ASCII, <= 80 columns on every line you add or edit.
- Do NOT commit; do NOT stage. The orchestrator commits after review.
- JVM tooling under mise (mise exec -- <cmd>).
- Verify: the full JVM suite in the worktree (mise exec -- clojure -M:test)
  must be green with the same counts as master's merge state (2,027 tests,
  180,646 assertions, 0 failures) — a rename must not move any count.
  Also run cljstyle check on the touched files.
- Remove nothing else; the debruijn_linker name must no longer appear
  anywhere under src/ or test/ after M1 (grep-verify and report the
  remaining count, which must be 0).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>

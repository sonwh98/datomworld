Created-GMT: 2026-09-03 10:05:00 GMT
Created-Local: 2026-09-03 17:05:00 Asia/Ho_Chi_Minh
Coding-Agent: agy
Session-ID: 6805C0C2-A138-46D9-AB5D-E40296588FF2

# Task: Implement DaoStream v2 Phase 1

Role: DaoStream v2 implementer

Implementers:
- Model: gemini-3.8-flash-high via AGY | Assigned: 2026-09-03 17:05:00 Asia/Ho_Chi_Minh | Status: active | Rationale: abundant AGY quota; Phase 1 is the dependency root

Work only in the isolated worktree `/private/tmp/datom-phase1`. Read the
repository's `AGENTS.md` and these plans first:
- docs/design/datom.world.md
- docs/design/dao.stream.md
- docs/design/dao.stream.implementation-plan.md (Phase 1 and contract sections)

Implement Phase 1 completely, not a sketch:
- create `src/cljc/dao/stream.cljc` with the seven-operation v2 protocol/data
  surface, qualified result convention, open result maps, descriptor identity
  projection, and generic envelope validation;
- create focused cljc tests in the repository's established test layout for
  result shapes, exhaustive operation outcome declarations, descriptor/identity
  equality, malformed envelopes, and surface gating;
- implement the declaration-driven conformance harness promised by the plan,
  keeping transport entry functions host-composed and avoiding registries,
  load-time side effects, hidden globals, callbacks, waiters, `closed?`, `take!`,
  and legacy namespace edits;
- run the narrowest relevant tests and compilation checks available; do not
  rewrite unrelated tests or docs.

Use the existing Clojure style and inspect neighboring v1 protocols/tests before
choosing exact names. Preserve the committed design contract. If a plan detail
is genuinely under-specified, choose the smallest contract-compatible API and
record the decision in your report rather than broadening scope.

Do not stage, commit, or edit files outside the Phase 1 source/test scope. Keep
the worktree branch changes ready for the orchestrator to inspect and cherry-pick.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Ho_Chi_Minh>
Coding-Agent: agy
Session-ID: 6805C0C2-A138-46D9-AB5D-E40296588FF2

Then report files changed, API decisions, tests/commands and results, unresolved
risks, and whether Phase 1 is ready for integration.

Produce the complete implementation now; no human is listening inside the CLI.

Created-GMT: 2026-09-21 17:26:02 GMT
Created-Local: 2026-09-22 00:26:02 +07 (Indochina Time)
Coding-Agent: codex
Session-ID: pending (provider-generated)
# Task: architect work package and gate checklist — DaoJing CBOR epic, steps 1-2 only
Role: Architect
Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-22 00:26:02 +07 | Status: active | Rationale: fresh architect session for a new subsystem; owner opened the DaoJing CBOR epic and asked for it to run on its own worktree

Read-only. Work in /Users/sto/workspace/datomworld (master, 0dc06197). Give the
complete deliverable now as your final response; do not wait for approval and do
not promise one. Edit no file.

## Context

The owner opened the epic that implements docs/design/dao.jing.cbor.md
("implementation plan; not yet implemented", architecture-reviewed 2026-09-17,
no blocking findings, one medium ambiguity since corrected, and one flagged
cross-cutting change). It replaces DaoJing's transitional print-based hash and
EDN persistence with canonical CBOR bytes, retiring the residuals in
docs/design/dao.jing.md (about lines 411-448). A worktree exists for the
implementation at /Users/sto/workspace/worktree-jing-cbor (branch jing-cbor,
cut from master 0dc06197). Read the plan in full first, then
docs/design/dao.jing.md, docs/design/datom.world.md (axioms and non-negotiable
invariants), docs/agents/team.md and docs/agents/roles/orchestrator.md (how
work is routed), and the current sources src/cljc/dao/jing.cljc,
src/cljc/dao/jing/*.cljc and their tests.

Owner standing preferences you must honour: below the dao.stream complexity
boundary use maintained libraries for crypto/serialization rather than
hand-rolling (the plan already reuses Boring 0.1.30 on JVM/JS; for Dart prefer
a maintained CBOR package if one can meet the frozen-fixture conformance,
otherwise say precisely why hand-rolling is unavoidable); minimal diffs; every
phase reviewed by a model from a different family than its author.

Two GATES are recorded in the plan and must not be crossed without the owner:
(1) "dao.space.index/query comparator changes ... need explicit sign-off from
dao.space's owners before this plan is built" (step 3's numeric-arm routing);
(2) the clean break: no legacy reader, old EDN stores rejected, and content whose
intake stream has evicted it is UNRECOVERABLE, so "rebuild readiness should be
checked against retained intake history before this migration lands".

## Deliverable

1. **Scope of this first unit.** Steps 1 and 2 of "Implementation sequence and
   validation" ONLY: frozen encoding-contract tests and CBOR hex/SHA-256
   fixtures, the JVM/JavaScript codec wrapper over Boring, normalization,
   named extensions and numeric constructors, then the matching Dart codec and
   carriers, with all three hosts reading each other's fixtures and reproducing
   identical canonical bytes. Confirm this can land ADDITIVELY (new namespaces,
   no behaviour change to existing Jing, no backend, no dao.space, no
   remote/DHT change), and say precisely what in steps 1-2 would still force a
   gate or an existing-code edit if anything does.
2. **A bounded work package as phases**, in the style of the de Bruijn D0-D6
   plan: per phase the file box (new files, tests, fixtures, deps.edn /
   pubspec.yaml edits and pins), what MUST NOT change, the exact completion
   criteria (drawn from the plan's required scenarios and acceptance rules,
   including injectivity over the pathological-identifier class), and the
   verification lanes (JVM, Node, Dart; kondo; cljstyle). Suggest a phase
   split that keeps each phase reviewable (for example J1 fixtures + JVM/JS
   codec, J2 Dart codec, J3 cross-host conformance) and justify it.
3. **Decisions I need from you, resolved where you can**: the Dart CBOR route
   (package vs hand-rolled, with the evidence you can verify from the repo and
   the plan; mark anything needing external verification as UNVERIFIED); how the
   frozen fixtures are generated and stored so that no upgrade may regenerate
   them silently; how the "effective options" of Boring (`:canonical`,
   stringref off, shapes off, no index frame) are proven by fixtures rather than
   trusted, since option precedence is recorded as unverified in the plan; and
   which role from docs/agents/roles/ owns the work.
4. **Routing recommendation** under the owner's law (implementation on Claude
   models, review by a different family, gpt for architect sign-off; glm-5.3 is
   at about 68 percent usage and resets 2026-09-27; the Claude pool refreshes
   2026-09-22 04:00 +07): which model for each phase, which independent reviewer,
   and the sign-off points.
5. **The owner gate checklist.** For each of the two gates, the exact question
   the owner must answer, the evidence they need to look at, and the earliest
   phase that cannot start until they answer. For the rebuild-readiness gate,
   list precisely which address-bearing artifacts exist in this repository today
   (published indexes, AST addresses, continuations, stored files, fixtures)
   and how one would check retained intake history for them. Do not invent
   artifacts; say UNVERIFIED where the repo does not show.
6. **Risks and stale spots** in the plan since 2026-09-17: anything the repo has
   moved past (the de Bruijn work landed since; `dao.jing/materialize!` is now
   used by yin.vm.pipeline), and whether the pinned Boring version or the
   fixtures need a re-verification before implementation.

Final response beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: <the thread id of this session, if you can see it, else pending>
then the six numbered sections above. Findings and plan only; edit no file.

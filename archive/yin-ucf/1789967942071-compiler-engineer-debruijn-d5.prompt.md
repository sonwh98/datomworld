Created-GMT: 2026-09-21 05:19:02 GMT
Created-Local: 2026-09-21 12:19:02 +07 (Indochina Time)
Coding-Agent: glm
Session-ID: 923b8885-4549-4b46-ad11-0731ebb614ef (resumed — your D0-D4 session)
# Task: implement D5 of the de Bruijn projection — pipeline and storage integration
Role: Yang Compiler and Universal AST Engineer
Implementers:
- Model: glm-5.3 | Assigned: 2026-09-21 12:19:02 +07 | Status: active | Rationale: same implementer; D4 signed off (gpt-5.6-sol granted proceeding to D5)

You are in the git worktree at /Users/sto/workspace/worktree-debruijn-impl
(branch debruijn-impl, your D4 committed as 8ed66e3a). Same rules as before:
work only in this worktree; do not stage, commit, merge, or push; one simple
command per step; the mise environment block from your build brief applies.

Your contract for this phase is the architect's D5 PRE-CLEARANCE — read it in
full: collab/1789966144642-architect-debruijn-d5-clearance.prompt.md is your
brief file; the clearance itself is the architect's reply in
collab/1789966144642-architect-debruijn-d5-clearance.gpt-5.6-sol.stdout.log
(also copied to the main repo's collab/). Its decisions bind you:

- NEW src/cljc/yin/vm/pipeline.cljc: persist-compiled! taking
  {:ast :named-writer :projected-store :provenance}, calling
  yin.vm/ast->datoms-with-root, projecting the COMPLETE batch via
  debruijn/project-datoms, persisting named first, persisting the projected
  envelope {:yin.debruijn/fingerprint fp :yin.debruijn/datoms
  (projected->datoms …)} ONLY after named persistence succeeds, returning
  {:named {...} :projected {:outcome :ok/:diagnostic ...}} independently.
  A projection failure must never fail the named side; a named failure
  means projected persistence is not attempted.
- Dedupe: dao.jing/materialize! write-idempotence (:inserted/:present,
  read-back verified, never overwrites). Return both the physical DaoJing
  address and the semantic Merkle fingerprint. No pre-write lookup. Never
  dedupe named artifacts by fingerprint.
- Ordering: project only the complete root-framed batch, never a
  mid-emission prefix; per-batch framing/index state; partial graph or
  missing root rejects.
- Box: pipeline.cljc (new), test/yin/vm/pipeline_test.cljc (new), and
  debruijn.cljc ONLY if a small pure projected-envelope constructor is
  needed. NOTHING else — no yang.* edits, no yin.vm emission edits, no
  encoder/VM/linearizer/named-consumer/dao.stream/lease/waitset edits, no
  REPL edits (no current caller owns both sides, so none is forced).
- Tests (pipeline_test.cljc): named/projected separation; projection
  failure isolation (named still :ok); projected-write failure reporting;
  idempotent dedupe (second write :present with the same address and
  fingerprint); complete-batch ordering; partial/missing-root rejection.

Read also: docs/design/yin.vm.debruijn-projection.md §6-§7-D5 and §9, your
debruijn.cljc's public surface, dao.jing's materialize!/content-store
surface, and dao.space.transactor's append! for the named-writer shape.

Completion (§7-D5): the named path wires to projected persistence with
separate segments, fingerprint-only dedupe, named consumers and runtime
layers untouched.

Verification (report exact counts): focused JVM for BOTH namespaces
(yin.vm.debruijn-test and yin.vm.pipeline-test), kondo on all touched
files, cljstyle check. The orchestrator reruns the full three-host lanes.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

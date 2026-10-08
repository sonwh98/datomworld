Created-GMT: 2026-10-01 05:50:25 GMT
Created-Local: 2026-10-01 12:50:25 +07 (+0700)
Coding-Agent: claude
Session-ID: 2947d033-73d2-4318-8c54-54eab162cb00
# Task: Heap reclamation slice 1 — deterministic mark-sweep over the task :heap on all four VMs

Role: Yin.VM Runtime Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-01 12:50:25 +07 (+0700) | Status: active | Rationale: four-VM runtime change per Architect design

WORK TREE: work ONLY in /Users/sto/workspace/datomworld-heap-gc (branch vm-heap-reclamation from master c66809fa).
Another orchestrator's DHT work and a Python spike (new yang.python.antlr.* namespaces, not on master) are in flight
elsewhere: do not touch other worktrees. Do not stage or commit.

OWNER (verbatim): "let's go with your recommendation" (heap reclamation is plan item 2, ranked first after the spike);
on the design's four decisions, verbatim: "accept all recommendations".
Governing design (copy in this worktree's collab/; read in full): 1790800251738-architect-heap-reclamation.claude-fable-5-1.findings.md
Background: 1790773810605-architect-cell-primitive.claude-fable-5-1.findings.md; cell slice 1 is on master (5e790683).

Build (design "Q6 slice 1"):
1. :gc {:since :threshold :pinned} declared as a record field on all four VM records (the ASTWalkerVM positional-record
   trap: cesk-return rebuilds positionally — declare and thread it like heap); empty in every constructor and every
   spawn-module child. Base threshold 4096, then max(base, 2*live), as a composition parameter (owner decision 3).
2. Engine (shared cljc): trace, mark, sweep, collect (pure: vm -> vm'), snapshot-at-the-beginning (sweep spares ids
   allocated at/after the cycle's counter start), trigger in the :cell/new arm counting allocations, the allocating
   effect's :val traced as an extra root, adaptive threshold. Stop-the-world (owner decision 2), structured so marking
   could later be budgeted.
3. IModuleKernel: gc-roots and gc-children, implemented for the AST walker, semantic, de Bruijn stack and register VMs.
   Walker frames: trace the runtime keys (:evaluated, :fn) and skip operand subtrees. Closures contribute captured env
   only; continuations via gc-children. Never read continuation maps generically in the engine.
4. Roots per the design's Q1 table: kernel registers, :store and every module store, :parked, :wait-set, :ready-queue.
   Excluded: code, images, registry, primitives, :callable-effects, telemetry, :resources.
5. Pinning (owner decision 1): cell refs inside a value appended by :stream/put, and inside FFI request args at
   park-and-call, are added to :pinned for the task's life.
6. Ids are never reused (:id-counter monotonic, shared with gensym). An absent heap id is refused with the new reason
   :dead-or-forged-reference (owner decision 4); never silently reallocated.
7. Docs: ast.md/state docs heap section; mark the cell ruling's "reclamation can wait" as superseded where docs say it.

Acceptance tests (four VMs; each must fail when its part is reverted — prove by temporary mutation, then restore):
- a loop allocating N cells with one live at a time keeps (count :heap) <= threshold + live;
- cells reachable only through each root survive a collection: :parked; a wait-set entry's :datom; a ready-queue entry;
  the store; a module store; a closure's env; a nested value inside another cell; a walker frame's :evaluated operand;
  a register-VM live register; the allocating effect's :val;
- a ref to a swept cell is refused :dead-or-forged-reference; a new allocation never reuses the id;
- a ref put on an in-task stream and read back after a collection is still authentic (pinning);
- determinism: two runs give identical heap key sets and results;
- semantics: the existing cell tests (test/yin/vm/cell_test.cljc programs) and a few cell-heavy programs give identical
  results at threshold 1 and at a huge threshold.

Verify — run EVERY command in the FOREGROUND (never background; a -p session ends with the turn): clj -M:kondo --lint on
each changed file (separate arguments); cljstyle check per file (say if blocked); focused JVM; full clj -M:test;
bb test:cljs (confirm "Testing <ns>"). NOT bb test:cljd. Every reader conditional in .cljc needs a :cljd branch listed
FIRST.

Write the report to /Users/sto/workspace/datomworld-heap-gc/collab/1790833825612-vm-engineer-heap-reclamation.claude-opus-5-5.report.md
and give it as your final response, beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 2947d033-73d2-4318-8c54-54eab162cb00
Report changed files, exact test outcomes, the mutation proof, unresolved concerns, incomplete work.

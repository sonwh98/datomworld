Created-GMT: 2026-09-30 19:00:36 GMT
Created-Local: 2026-10-01 02:00:37 +07 (+0700)
Coding-Agent: claude
Session-ID: 82a33a9c-8ccb-4f4a-9aaa-40c4d57a1363
# Task: Cell slice 1 — task-heap cells (cell/new, cell/get, cell/set!) with sealed :cell-ref on all four VMs

Role: Yin.VM Runtime Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-01 02:00:37 +07 (+0700) | Status: active | Rationale: four-VM runtime change; Architect cell ruling + mob D1-D5

WORK TREE: work ONLY in /Users/sto/workspace/datomworld-cell-slice1 (branch vm-cell-slice1). Its base is a LOCAL snapshot
commit 77ad1697 of the not-yet-committed D4 change (host-typed effects: yin.vm.effect, module/make-effect kind params,
check-callee-effect!, :callable-effects). Build on D4's API. Do not modify D4's code except where slice 1 must extend it;
list any such touch explicitly. Do not touch any other worktree. Do not stage or commit.

OWNER (verbatim): "dispatch cell slice 1 now in parallel". Prior owner decisions (verbatim): "accept all recommendations";
"yes" to the mob's decisions.
Governing rulings (copies in this worktree's collab/; read in full):
- 1790773810605-architect-cell-primitive.claude-fable-5-1.findings.md (design; Q6 slice 1; F3)
- 1790776815400-architect-mob-outstanding-decisions.claude-fable-5-1.findings-r2.md and .gpt-6-astra.findings-r2.md (D1-D5)
- 1790778866412-architect-mutable-objects.claude-fable-5-1.findings.md (Q6: slice-1 tests)

Build (surface unchanged from the ruling; amendments marked):
1. yin.vm.module: a host module `cell` with exports cell/new, cell/get, cell/set!, registered via register-host-module
   like the stream module; each export is :effectful and returns a D4 effect (module/make-effect) declared in its profile.
2. Value: {:type :cell-ref :id <id from :id-counter> :seal <s>} via engine/issue-ref. Naming: state field :heap; never
   reuse :yin.k/cell / :yin.k/cells (cursor vocabulary).
3. State: :heap {id {:value v :seal s}} — AMENDMENT (mob): store the seal in the heap entry at allocation and compare on
   access; one hash per cell/new, none per get/set. Fresh empty :heap in every VM constructor and in spawn-module children.
4. engine/handle-effect: three arms. cell/new allocates; cell/get returns the content AS DATA (never re-interpreted);
   cell/set! writes and returns the value (state your choice). authentic-ref?/check-ref! extended to :cell-ref against
   :heap using contains? liveness (a cell may hold nil). A forged or foreign ref is refused with a qualified error.
5. Box semantics: the heap is VM state, not continuation state; invoking a captured continuation keeps the heap.
6. F3 (required): explicit fail-closed arms — the lift encoder (engine.cljc ~600-670) refuses a :cell-ref with
   :yin.k/non-portable kind :cell; completion/abstract-value refuses it the same way (no silent :complete). Slice 1
   refuses every cell-bearing lift (copy-on-lift is slice 2).
7. Four-VM parity via the shared engine path (the ruling claims all four VMs route primitive effects through
   handle-effect; the tests are the evidence, not that claim).
Out of scope: heap lift/lower, reclamation, dedicated tags/opcodes, cell/swap!, any AST grammar change, Rule R, yin/def,
frontend lowering rules.

Acceptance tests (all four VMs; each must fail when its part is reverted — prove by temporary mutation, then restore):
- counter shared by two closures over one cell;
- distinct cells per activation (two calls of one function do not share);
- a mutation survives continuation re-entry (multi-shot) and an abortive escape (invoke a captured continuation after a set!);
- forged {:type :cell-ref ...} refused (wrong seal; unknown id);
- a cell holding nil (get returns nil; the ref stays live);
- a cell holding an effect-shaped map returns the map as data;
- lift/encode of a closure over a cell is refused (:yin.k/non-portable :cell); completion does not report :complete;
- (mutable-objects ruling) = on two refs is true iff same cell; a ref works as a host map key and inside nested values;
  a cell whose content contains its own ref (get/set work; no host loop).

Verify — run EVERY command in the FOREGROUND (never background; a -p session ends with the turn): clj -M:kondo --lint on
each changed file (pass files as separate arguments); cljstyle check on each changed file (say if blocked); focused JVM
on touched namespaces; full clj -M:test; bb test:cljs (confirm "Testing <ns>" for new test ns). NOT bb test:cljd.

Write the report to /Users/sto/workspace/datomworld-cell-slice1/collab/1790794837027-vm-engineer-cell-slice1.claude-opus-5-5.report.md
and give it as your final response, beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 82a33a9c-8ccb-4f4a-9aaa-40c4d57a1363
Report changed files (and any D4 touch), exact test outcomes, the mutation proof, unresolved concerns, incomplete work.

Created-GMT: 2026-09-30 14:00:15 GMT
Created-Local: 2026-09-30 21:00:15 +0700
Coding-Agent: claude (fable seat, resumed) + codex (astra seat)
Session-ID: fable f8eef849-bc12-4f36-87ee-4ae5da8aaa8c (resumed) | astra 01a0f29e-7b04-7891-a38f-274da87b9ad8 (captured)
# Task: Architect mob — decide the ten outstanding owner decisions (cell primitive, continuation review, housekeeping, spike)

Role: Lead System Architect (mob of two seats)

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-30 21:00:15 +0700 | Status: active | Rationale: owner named; author of the cell ruling
- Model: gpt-6-astra | Assigned: 2026-09-30 21:00:15 +0700 | Status: active | Rationale: owner named; independent family

OWNER (verbatim): "mob with fable, gpt-6.0-astra on this and tell me the decision". "This" = the ten decisions below,
which the orchestrator listed as outstanding for the owner. The owner delegates them to this mob; the orchestrator
relays the mob's decision and does not adjudicate. Read-only: do not edit files. Work in /Users/sto/workspace/datomworld.

Read first:
- collab/1790773810605-architect-cell-primitive.claude-fable-5-1.findings.md (fable's cell-primitive ruling, F1-F7)
- collab/1790769087413-reviewer-continuation-invocation-gate.gpt-6-sol.findings.md (gate Q1-Q3 on continuation invocation)
- git show 8f9f90b0 (captured continuations invocable, on master, pushed)
- docs/design/datom.world.md (axioms, six invariants), docs/design/yang.antlr.md §8.1, §9
- src/cljc/yin/vm/engine.cljc (issue-ref, authentic-ref?, handle-effect, encoder ~600-670), src/cljc/yin/vm/module.cljc

Owner direction already given (verbatim, binding): "integrating antlr should be straight forward. antlr will construct an
AST that gets mapped to yin.vm universal AST." and "if yin.vm universal AST has continuations, all control flow can be
mapped to continuations".

Decisions (decide each: a concrete choice + one-paragraph rationale + risk):
D1. Copy-on-lift: a closure over a cell crossing a task boundary gets an independent copy. Confirm or alternative.
D2. Naming: cell/* surface with :heap / :yin.k/heap state, or box/* (collision with existing cursor "cell" vocabulary).
D3. Amend yang.antlr.md §8.1 from "threaded immutable heap" to a task heap of cells (F2).
D4. F1 (effect detection by result shape lets :pure primitives forge engine effects): fix before guest dicts flow
    through the ANTLR spike, fold into cell slice 1, or accept for the spike. If fixing, name the mechanism.
D5. Accept that under continuation-lowered control flow, locals assigned in try bodies / continuation-exited loops must
    be boxed (spike rule: box every reassigned local), vs explicit completion records.
D6. Malformed in-machine continuation values: qualified defect vs host error (gate Q1).
D7. Forged :reified-continuation maps (gate Q3): seal continuations like stream refs (and cells), now or later?
D8. Remove worktree ../datomworld-k-invoke and branch vm-continuation-invoke (merged; collab synced). Yes/no.
D9. 11 older collab/ files tracked on origin/master (committed before 2f030c66): git rm --cached going forward, leave,
    or history rewrite.
D10. Sequencing: cell slice 1 -> JVM-only Python ANTLR spike (grammars-v4 Python3, generic ParseTree->data walker,
    case-per-rule lowering, continuations for control flow, box-every-reassigned-local), or a different order/scope.
    Include which items (D4, D6, D7) must precede the spike.

ROUND 1: answer independently. The fable seat must not simply restate its earlier ruling: re-examine it against
astra-style challenge. The astra seat must challenge the cell ruling itself (not just the ten decisions) and cite
repository evidence (file:line) for disagreements.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Then a table: D# | decision | rationale | risk | confidence (high/med/low); then objections to the cell ruling (if any).

## Owner direction received during Round 1 (binding for Round 2; verbatim)
OWNER: "compilers have a pipeline of transformation. yang/yin.vm compilation pipeline is dynamic where interpreters read
from dao.stream and make transformation onto another dao.stream. any number of interpreters can attach to those
dao.stream to do more transformation of its own"
OWNER (on including it): "yes, include that in round 2"
Orchestrator's reading (a paraphrase, NOT the owner's words; challenge it): this bears at least on D5 (naive boxing in the
lowering, with un-boxing/liveness as a separately attached interpreter), D10 (spike as a stream topology: parse -> CST
stream -> lowering interpreter -> row stream; scope analysis/optimizations attach as own interpreters), and D4/D6/D7
(are these runtime-evaluator guarantees that cannot live in an optionally attached interpreter?).

## ROUND 2 (orchestrator; secretarial — positions summarized, not adjudicated)
Read BOTH round-1 answers in full (do not rely on this summary):
- collab/1790776815400-architect-mob-outstanding-decisions.claude-fable-5-1.findings.md
- collab/1790776815400-architect-mob-outstanding-decisions.gpt-6-astra.findings.md
Apply the owner direction above (verbatim, binding) to every D# it bears on.

Round-1 agreement (confirm, or reopen only with new evidence): D1 copy-on-lift; D2 cell/* + :heap/:yin.k/heap;
D3 amend §8.1 (fable keeps state threading as an allowed per-frontend choice); D8 remove worktree+branch (fable: the
differing gate prompt in the worktree is the OLDER copy; main's has the captured session id — astra please confirm);
D9 git rm --cached the 11 files, no history rewrite.
REPO RULE both seats' D9 missed (docs/agents/roles/orchestrator.md:110, verbatim): "`collab/` is append-only and never
staged or committed, and must never be added to `.gitignore` or `.git/info/exclude` so its files stay visible and
chronologically sorted in the user's Magit untracked view." Rule on D9 under that rule, or name it as an owner decision.

Differences to resolve (converge, or give a reasoned dissent the owner can pick between):
- D4 mechanism (both: fix before spike). astra: callee-profile-authorized dispatch carried through callable resolution.
  fable: effects are a host type minted only by module/make-effect; effect? becomes a type test (withdrew profile lookup
  as per-call reverse lookup cost).
- D5 scope. astra: box every function-local binding AND parameter (forward-capture example: def f(): return x; x = 1).
  fable: box every reassigned local. Also: under the owner direction, may un-boxing/liveness be a separately attached
  interpreter over the row stream, so the lowering stays naive?
- D6. astra: qualified defects before the spike. fable: host error now; D7 makes malformed in-machine continuations
  impossible, so structural validation is throwaway.
- D7. astra: seal continuations now via a private captured-payload table + sealed ref. fable: later, as host-typed
  in-machine values for closures AND continuations together; fable's execution shows forged closures (incl. a forged
  :yin.k/store-of) are accepted today, so continuation-only sealing leaves that open. astra: verify fable's execution
  claims by reading (ast_walker apply-function, engine.cljc:167-175).
- D10 order. astra: D4 -> D6/D7 -> cell slice 1 -> spike. fable: D4 -> cell slice 1 -> spike; D7 separate track before
  multi-author module linking. Owner direction: is the spike a stream topology (parse -> CST stream -> lowering ->
  row stream, analyses attached as own interpreters), and are D4/D6/D7 evaluator guarantees that cannot be optional
  attached interpreters?
- fable's objections 1 (seal hash per access; store seal in heap entry) and 5 (no reclamation) — astra respond.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Then: final table D# | decision | changed from round 1? | if still differing, your one-line reason; then any new
evidence (file:line). Read-only; do not edit.

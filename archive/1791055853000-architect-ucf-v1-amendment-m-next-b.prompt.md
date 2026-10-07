Created-GMT: 2026-10-03 19:30:53 GMT
Created-Local: 2026-10-04 02:30:53 +07 (+0700)
Coding-Agent: claude (fable-5-1, session a22aafbc-6499-4a8f-be89-f6ebe80ae496)
Session-ID: a22aafbc-6499-4a8f-be89-f6ebe80ae496

# Task: M-next B — publish the UCF version-1 amendment for fenced custody (linker-over-DHT hardening, stage B)

Role: Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-04 02:31 +07 | Status: active | Rationale: owner approved starting M-next B; architect-level design/reconciliation step; the second opinion (gpt-6-astra) and a gate follow

Work in /Users/sto/workspace/datomworld-ucf-b (branch linker-ucf-b, master a4efc99a; a NEW worktree; you may edit docs). You cannot run git write commands (stash, checkout, rebase, reset,
commit, stage): do not try; the orchestrator does git. Edit only docs. Do not touch collab/ in the main tree. This round is DESIGN and DOCUMENTATION only: no source code, no tests.

## The task (docs/design/yin.vm.linker.dht.md section 14.3, item 2, verbatim)

> "M-next B publishes the UCF amendment for sequence/pending operation state, fenced-envelope grammar, grant epoch binding, and admission outcomes. If the approved explicit-park or complete
> install-child shape is absent, amend UCF 7.4.3 first; never export the name-only install sketch as complete task state. Update 7.11's evidence pointers without reopening or silently
> reassigning the existing M4 kept-cursor gate."

and the closing paragraph of 14.3:

> "Sequence/pending fields change the accepted UCF envelope grammar: publish `:yin.k/version 1` for this amendment and refuse unsupported versions with profile-mismatch before restoration.
> Version 0 remains usable under its published contract for fork; it is not silently upgraded to fenced custody. Code stamps v3/b2/r2 and module manifest schema 1 stay unchanged because this
> amendment changes handoff data and composition admission, not opcode semantics."

## What exists (read all of it before writing)

- docs/design/yin.vm.linker.dht.md section 14 (about lines 2960-3374), especially 14.2.2 "Runtime, ledger, and wire contract", which contains a block introduced by "The following is a proposed
  UCF amendment for M-next, not an existing version-0 field. Publish it before implementation accepts it": `:yin.k/next-op-seq`, a retained effect's `:yin.k/op-id {:yin.k/occurrence O :yin.k/seq n}`,
  the composition's protected writer envelope `{:yin.k/envelope :yin.k/fenced-v1 :yin.k/incarnation lease-id :yin.k/epoch e :yin.k/op-id ... :yin.k/value payload}`, the closed admission-outcome
  set `:committed :replayed :stale :intent-conflict :suspended`, and the 2^52-1 bound with overflow suspending admission; plus 14.2.3 (exact handoff and admission steps), 14.2.4 and 14.1.2 (state,
  wire, lift/lower).
- docs/design/yin.vm.universal-continuation-format.md (1856 lines): 7.4.1 to 7.4.3 (pending waits travel as data; 7.4.3 already carries `:next`, `:put`, `:ffi`, `:ffi-request` and an `:install` variant at
  ~664 of which 14.3 says "the name-only install sketch" must never be exported as complete task state: decide whether the published shape is complete, and amend 7.4.3 first if it is not), 7.7.1 to 7.7.7
  (the race, custody facts, the authority, the exporting state, fencing effects: epochs, operation ids and what exactly-once costs, successors, restart: the amendment must reconcile with, not duplicate or
  contradict, these), 7.9 (the outcome algebra: the admission outcomes must live inside it consistently), and 7.11.1 (the blocker-closure acceptance matrix and its evidence pointers).
- The landed state: M-next A (kept-cursor proof across host pairs) is on master as 80b59233 (src/cljc/yin/vm/ucf/handoff.cljc and test/yin/vm/ucf/handoff_test.cljc). Do not reopen it.
- Governing invariants: docs/design/datom.world.md (read the non-negotiable invariants and axioms), docs/design/dao.stream.md, docs/design/dao.lease.md if present. In particular no privileged node and no
  server/client concept, below dao.stream is swappable plumbing, derive-don't-persist (do not add structure a query over existing rows can derive), and the yin.vm / dao.space symmetric ignorance of
  each other.

## Deliverables

1. The UCF text itself, edited in place in yin.vm.universal-continuation-format.md: a new subsection (or sections within 7.7 and 7.4.3, wherever the existing structure wants them) that
   publishes, as normative text with grammar: (a) the sequence/pending-operation state (`:yin.k/next-op-seq`, `:yin.k/op-id`) and how a pending effect carries an already-assigned id across park,
   lift, lower and retry; (b) the fenced-envelope grammar (`:yin.k/fenced-v1`, its fields, what is inside program data vs outside it); (c) grant epoch binding (the authority's attributed grant binding
   admitted in the same transaction as the grant; how a reader learns the epoch; epoch monotonicity, initial value, never reused after restart); (d) the admission outcomes as a closed dispatch key with
   exactly their values, each carrying its op id, placed inside the 7.9 outcome algebra; (e) the exact-integer bound (2^52-1) and overflow behavior; (f) the version bump: `:yin.k/version 1`, which
   envelopes it applies to, refusal of unsupported versions as `:yin.k/profile-mismatch` BEFORE restoration, and version 0 staying valid for fork only; (g) the 7.4.3 install/explicit-park shape:
   state whether the published variant is already complete; if not, amend it (what a foreign resumer needs to re-establish the install child) and say what "name-only" meant and why it must never be
   exported as complete state.
2. 7.11.1 evidence pointers: add rows or pointers for the new obligations (what test contracts prove each new clause) WITHOUT altering the existing M4 kept-cursor row's meaning or silently reassigning
   its evidence; say which rows the later stages (M-next C, D, E) must satisfy and which this amendment does not claim.
3. A short "reconciliation" list appended to your findings (not to the doc): every place the proposal in 14.2.2 and the existing UCF text disagreed or overlapped and how you resolved it; every
   ambiguity you could NOT resolve from the documents (owner decisions or second-architect questions), phrased as a question with your recommendation.
4. Update docs/design/yin.vm.linker.dht.md 14.2.2 so the "proposed amendment" block points to the published UCF section instead of restating it as a proposal (keep 14.2.2's runtime and ledger contract),
   and mark in 14.3 that M-next B is the published amendment (do NOT mark A..E as complete or claim full UCF closure; M-next A is landed, B is this document change, C to E remain).

## Rules

- Normative text must be implementable and testable by M-next C to E; where a clause cannot be tested on JVM, Node and Dart, say how it is. Every refusal is a data outcome. No new keys on the lease
  vocabulary or DaoStream outcome maps (14.2.2 says "Lease vocabulary and DaoStream outcome maps gain no keys"). Do not invent authority policy the owner has not decided: list it as an open question.
- ASCII only, lines <= 80 columns in the files you edit (both documents follow that convention; check with awk before you finish).
- Do not rewrite unrelated UCF text. Keep the diff reviewable. Do not renumber existing sections; add new subsection numbers at the end of their parent where possible.

Write findings (your reconciliation list, open questions with recommendations, and a summary of every edit with section numbers) to
/Users/sto/workspace/datomworld-ucf-b/collab/1791055853000-architect-ucf-v1-amendment-m-next-b.claude-fable-5-1.findings.md.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: <exact Session-ID>

Then give a one-line verdict, the list of edited sections, the unresolved questions, and whether the amendment is ready for the second architect's adversarial review. Do not claim edits that did not occur.

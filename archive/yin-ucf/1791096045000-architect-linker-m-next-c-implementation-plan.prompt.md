Created-GMT: 2026-10-04 06:42:12 GMT
Created-Local: 2026-10-04 13:42:12 +07 (+0700)
Coding-Agent: claude (fable-5-1, session ff5b8c32-5cb0-4d81-9476-0a88c6109319)
Session-ID: ff5b8c32-5cb0-4d81-9476-0a88c6109319

# Task: M-next C implementation plan (linker-over-DHT hardening, durable authority transactions)

Role: Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-04 13:42 +07 | Status: active | Rationale: owner asked to close the open engineering items; M-next B is published and C is next; an implementation plan comes before engineers

Read-only: do not edit, do not run suites. Your final response IS the deliverable (the orchestrator promotes it to a findings file).

## Read first (all in /Users/sto/workspace/datomworld)
- docs/design/yin.vm.linker.dht.md section 14 (14.1 to 14.3), especially 14.2 (exclusive custody and effect fencing), 14.2.2 (runtime, ledger, wire contract), 14.2.3 (exact handoff and admission steps), 14.2.4 (M-next test contracts)
  and 14.3 (M-next C: "implements durable authority transactions, attribution, reclaim epochs, op-id/intent/result records, input replay, and completion eligibility. Prove atomicity and reopen tests before enabling
  enrolled consumers. Availability and DHT name signatures do not satisfy this gate.").
- docs/design/yin.vm.universal-continuation-format.md as AMENDED and landed in 0c7ee4ee: 7.2.1, 7.4.3, 7.7.1 to 7.7.8, 7.9 (the admission family), and 7.11.1 (the ten clauses; the clauses owned by stage C in particular,
  with astra's stage-ownership table in docs/design/yin.vm.ucf-revisions.md section 6 and the review at /Users/sto/workspace/datomworld/archive/1791056670000-architect-ucf-v1-amendment-review.gpt-6-astra.final.md).
- The landed handoff code: src/cljc/yin/vm/ucf/handoff.cljc and test/yin/vm/ucf/handoff_test.cljc (M-next A, 80b59233); src/cljc/yin/vm/ucf.cljc; the linker over the DHT: src/cljc/yin/vm/linker/ and src/cljc/yin/repl/ (core, link, dht);
  src/cljc/dao/space/ (the covering-index tuple space and its dht/index/query/schema parts); src/cljc/dao/lease*.cljc; docs/design/dao.stream.md, dao.lease.md (if present), dao.space.md and docs/design/datom.world.md (the
  non-negotiable invariants: no privileged node, no server/client concept, dao.stream is the complexity boundary, derive-don't-persist, the peer observers of one stream, ShiBi is a tuple space).

## What to produce
1. The decisive architecture question first: what IS "a durable transactable arbitration space" with "an authority that possesses the grounded resource it arbitrates" (UCF 7.7.3), concretely, in THIS codebase, on JVM, Node
   and Dart, under the invariants? Candidates: dao.space (the covering-index tuple space) with an atomic transaction seam; a file-backed durable log with compare-and-append; the existing dao.lease facts plus a new authority
   store; or something else. For each: how atomic admission (effect, result and dedup record in one transition) is achieved portably, what "reopen" means on each host, how it avoids a privileged node, and what it costs.
   Recommend one, with the alternatives rejected and why. If the choice needs the owner, state the question in one sentence and a recommendation; do not stop the plan on it.
2. A slice plan C1..Cn: each slice independently landable by one engineer round (a few hundred lines of production code at most, plus tests), with: scope, exact files and namespaces (new and touched), the UCF 7.11.1 clauses it
   satisfies, the test contract on JVM, Node and Dart (what is portable, what is JVM-only and how Node/Dart are still covered), dependencies on earlier slices, and the order. Cover at least: attributed grant and binding facts and
   the reader-side evidence (UCF 7.7.8 epoch binding; astra Q7); reclaim epochs (monotone, never reused after restart, overflow behavior); the operation-id, intent and result records and the one logical dedup namespace per
   arbitration resource across enrolled targets; consumer enrollment as an attributed authority fact keyed by target stream identity (Q3); authenticated admission outcomes and the closed `:yin.k/admission` family;
   the defective-envelope diagnostic stream; durable input records and replay; completion eligibility and the single acyclic successor chain (the inherited-id scope rule); occurrence id form (Q9 invariants); and the atomicity
   and reopen proofs (crash cuts: before commit, after commit before result delivery, between completion and successor grant).
3. What C needs from D and E and what it must NOT build (the handoff driver, the REPL composition, the host matrices and the crash/partition suite are D and E; plain functions must stay usable without the REPL).
4. Risks, the riskiest slice first, and the cheapest first slice that proves the substrate choice end to end (a thin vertical).
5. Any further UCF or DHT-doc text that implementation will force you to amend (list it; do not write it).
Be specific and decisive. A survey is a failure. Begin with a one-line summary of the recommended substrate and slice count.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

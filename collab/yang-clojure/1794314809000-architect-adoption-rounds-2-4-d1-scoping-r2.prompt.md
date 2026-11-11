Created-GMT: 2026-11-10 12:52:30 GMT
Created-Local: 2026-11-10 19:52:30 +0700
Coding-Agent: agy
Session-ID: pending (provider-generated) — prior attempt: codex gpt-6-astra thread 01a1c580-6d91-73a3-aec6-5dcd6c423e44 hit usage limit before any output; this is a reroute, not a resume

# Task: Scope yang.clojure adoption rounds 2–4 + D1 (post-cc1)

Role: Lead System Architect

Implementers:
- Status-Event: 2026-11-10 12:50 GMT | Model: gpt-6-astra | Status: failed | Rationale: codex usage limit hit at dispatch (resets ~23:39 +07), zero output
- Model: gemini-4-argon-high | Assigned: 2026-11-10 19:52:30 +0700 | Status: active | Rationale: argon reserved for architecture review; live on local agy lane

Perform a read-only architecture scoping of the next work on the yang.clojure track. Do not edit files. Your output is a findings document only.

## Context

- Owner instruction (verbatim, today): the yang.clojure seat should proceed with **"Adoption rounds 2–4 + D1"**.
- The outgoing seat's description of that queue (its words, not a ruling): "Adoption rounds extend the C1 call-site attribution across the remaining yang.clojure surfaces (the three-kind correspondence: ambient literals, context-API carriers, delegation exclusion). Round 1 shipped with cc1; rounds 2–4 remain." "The cross-host occurrence-key equality gate is part of each frontend's own acceptance: the same source on different hosts → identical coordinates; never cross-frontend." "D1 is the durable store for occurrence records (deliberately out of cc1's scope)."
- cc1 (round 1) is CLOSED and on master. Do not reopen it. Master is `8dbe7031`.
- This scope is the **Clojure frontend only**. TypeScript/PHP/Python adoption belongs to other seats. `src/cljc/yang/core/prelude.cljc` is owned by the php/ts seat — flag any round that must touch it rather than scoping edits to it.
- R3 (exception uniformity, `exception-r3*` branches/worktrees) is another seat's. Out of scope; name any conflict with it.

## Read first

- docs/design/datom.world.md
- collab/yang-clojure/1794400000000-orchestrator-handoff-yang-clojure-seat-final.md (§2, §6)
- collab/yang-clojure/1794670000000-architect-callsite-generalization-design.gpt-6-astra.findings.md (C1 §2, A2 inventory §3, D1 §4, Clojure brief §5.3, gates §7, sequence §8)
- collab/yang-clojure/1795310000000-owner-ruling-occurrence-identity-unitarity.md (SIGNED ruling: an occurrence is (content address, origin))
- collab/yang-clojure/1794630000000-architect-attribution-addendum-a-RECORD.md
- test/yang/latte_oracle/slice9_gate.md (attribution section: the three correspondence kinds) and test/yang/latte_oracle/slice9_site_tables.edn
- test/yang/clojure_callsite_test.cljc (what round 1 actually pins)
- src/cljc/yang/clojure.cljc, src/cljc/yin/vm/macro.cljc (the origin carrier: `::origin-of`, `recover-rebuilt-packet`), src/cljc/yin/vm/linker/publish.cljc
- collab/yang-call/1795010000000-orchestrator-reply-equality-gate-per-frontend.md

## Questions to answer

1. **What round 1 delivered, measured against the design.** Against the design's Clojure work package (§5.3) and A2 inventory (§3.2), list which surfaces cc1 on master actually covers (cite file:line and the pinning test) and which remain. Do not trust the handoff's summary; derive from source.
2. **Rounds 2, 3, 4 defined.** Decompose the remaining Clojure adoption into exactly the rounds that should be dispatched, in order. State whether the handoff's "three-kind correspondence" reading (one kind per round) is the right decomposition or whether the design implies a different one; if they conflict, say so explicitly and recommend — that conflict is the owner's to rule on, so present it as a question, not a decision. For each round give: goal; the surfaces/files it may change (bounded ownership); the surfaces it must not change; acceptance criteria written as tests or equally precise checks; the gates from design §7 that apply; the cross-host occurrence-key equality gate shape for that round (hosts, fixture cohort); dependencies on earlier rounds.
3. **D1.** Is D1 dispatchable now? The design §1.1 and §8 make the oracle evidence schema pin a landing prerequisite. Determine from the tree whether that pin now exists (slice9_site_tables.edn `:version`, its validator, derivation entry point). State D1's prerequisites, which are met, and where D1 sits relative to rounds 2–4 (before, after, parallel). Scope D1 as one or more rounds with the same fields as question 2.
4. **Design prerequisites.** The design gates frontend adoption on the cell-ref sweeps and the J2 re-run landing, and on an inventory refresh. Determine from git history whether those have landed. State which design citations are now stale against master.
5. **Invariants each round must preserve.** In particular the two carrier invariants in macro.cljc (construction origins are content-invisible metadata dropped at the single choke point on rebuilt packets; they never reach CBOR/hashing), zero change to canonical code bytes/addresses, and unannotated legacy artifacts remaining loadable.
6. **Open decisions.** List every decision that is the owner's (scope, authorization, what "done" means) versus yours (architectural). For yours, decide. For the owner's, state the question and the options with consequences.

## Output

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then: the round-by-round scope (questions 1–6), each claim cited to file:line or commit. Mark anything you could not verify from the tree as unverified rather than asserting it. Run no test suites; reading and `git log`/`git grep` only.

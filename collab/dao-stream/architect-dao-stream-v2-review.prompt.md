Created-GMT: 2026-08-31 05:02:20 GMT
Created-Local: 2026-08-31 12:02:20 +07

# Role: Lead Systems Architecture Reviewer

Perform a read-only architecture review of the DaoStream v2 contract, its
WebSocket transport specification, and the implementation plan for the
WebSocket slice.

Read first:
- docs/design/datom.world.md
- docs/design/dao.stream.md            (the contract — authority)
- docs/design/dao.stream.ws.md         (subordinate transport spec)
- docs/design/dao.stream.implementation-plan.md   (subordinate migration plan)
- src/cljc/dao/stream/ws.cljc          (legacy implementation — evidence about
                                        behavior, NOT a constraint)

Context you should have before reviewing:

- The contract was previously reviewed adversarially by `gpt-5.6-sol` across
  six convergence rounds. A subsequent sol review of the implementation plan
  is at `collab/review-dao-stream-plan.gpt-5.6-sol.stdout.log` — read its
  final Verdict section (eight findings plus an adjudication of six claims).
  All three documents have since been revised against those findings. Do not
  simply restate them; assess whether the revisions are architecturally
  correct, and find what the previous review missed.
- Precedence: the contract wins over the ws spec and over the plan. The plan
  is transient and is consumed as its phases complete; the contract remains.
- Deliberate deferrals, already decided, are not findings: Shibi capability
  tokens, the readiness/waiter extension, ws resumption protocol, cursor
  serialization across hosts, live ring buffer resize, the exact descriptor
  key set (which the plan gates behind an explicit decision point), and
  migration of any consumer.

Evaluate:

1. **Foundational invariants.** The six non-negotiable invariants of
   datom.world.md, and the four axioms. The contract's Purpose section now
   claims to derive from them directly — verify the derivation is sound and
   that no clause elsewhere in the contract contradicts it.
2. **The IO Model section** (new). It asserts non-blocking polling as the
   only model expressible identically on clj, cljs, and cljd, and generalizes
   "no operation waits" from `next` to all seven operations. Assess whether
   that generalization is sound, complete, and consistent with every outcome
   table in both documents.
3. **Ownership boundaries.** The plan's Phase 4 now splits into three layers:
   4a transport (`v2/ws.cljc`), 4b forwarding (`v2/forward.cljc`, a generic
   `next`-to-`append!` interpreter), 4c serving composition. Assess whether
   that three-way split is the right decomposition, whether `forward.cljc`
   belongs in the DaoStream namespace at all, and whether anything in 4c is
   in fact generic and misplaced.
4. **Explicit state and control flow.** In particular: the deposit-admission
   rule (declared as data at assembly, never interrogated at runtime), and
   whether it is expressible without any handle introspection.
5. **Concurrency and linearization**, host isolation, and CLJ/CLJS/CLJD
   portability of everything the contract requires.
6. **Completion criteria and migration risk** for the slice as planned.
7. **Design contradictions** between the three documents, and gaps where a
   subordinate document assumes something the contract never states.

One specific question the authorities have not settled, which we would like
adjudicated: the contract gives each operation a *closed* outcome set, but
never states whether a given transport may produce only a **subset** of it.
Both subordinate documents rely on subsetting (a ring buffer never returns
`:dao.stream/full`; ws `attach!` never returns `:dao.stream/not-found`).
Should the contract state this explicitly, and if so, where and in what
terms — and what does it imply for a surface-aware conformance suite trying
to distinguish "this transport cannot produce that outcome" from "this
transport failed to implement that outcome"?

Distinguish architectural defects from implementation gaps or intentionally
deferred work. Do not edit files.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then report: severity | file:line | invariant/evidence | recommended correction.
Also confirm the requested properties that passed review.

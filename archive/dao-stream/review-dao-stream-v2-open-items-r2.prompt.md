Created-GMT: 2026-09-02 19:19:25 GMT
Created-Local: 2026-09-03 02:19:25 Asia/Ho_Chi_Minh

# Task: Cross-family review round 2 — final consensus on the amended proposal

Role: Cross-family Adversarial Reviewer (Routine Review seat, non-GPT routing per TEAM.md)

Implementers:
- Model: gemini-3.1-pro-high | Assigned: 2026-09-03 02:19:25 Asia/Ho_Chi_Minh | Status: active | Rationale: closing consensus round on the gpt-5.6-sol round-2 revisions; same reviewer family as round 1

Repository root: /Users/sto/workspace/datomworld. This is a ONE-SHOT read-only
review: do NOT stop to present a plan or ask for approval — no human is
listening. Produce the complete final deliverable in this single response.

## Assignment

You reviewed round 1 (`collab/review-dao-stream-open-items.gemini-3.1-pro-high.findings.md`)
and returned four non-ACCEPT verdicts. The architect conceded three and
defended one in
`collab/architect-dao-stream-open-items-r2.gpt-5.6-sol.findings.md`.
Read both files plus the documents they amend, and give the final verdicts.

## Questions

1. **A2 (your Critical stranding finding).** The revision replaces the
   deposit-result gate with bounded acceptance-handoff slots: per slot, a
   capacity-one evict-oldest offer medium (transport writes) and a
   capacity-one ack medium (composition writes); at most one outstanding
   offer per slot; no second offer before acknowledgement; slot exhaustion
   closes the new socket before logical acceptance (client resolves
   `:ws/transport-error`); wire `:ws/accept` is sent only after the matching
   ack. Verify the stranding hole is closed in every failure case the
   revision enumerates (offer-deposit failure, ack-deposit failure, peer
   loss, endpoint stop, admission expiry, composition lag before ack). Check
   boundedness (nothing unbounded, no waiters, no callbacks) and that no new
   hole opens — including: can a slot deadlock, can identities collide across
   reuse, and does the client-side story hold when the connection closes
   before any wire frame arrives?
2. **B3 (your REJECT).** The architect defends per-sequence linearizability
   with real-time edges only on harness-observable non-overlap: if A
   completed before B was invoked, A's "moment" already occurred, so letting
   B precede A lets an operation's claimed moment fall outside its
   invocation/return interval — contradicting "an operation returns what is
   true when called" — and no writer coordination is imposed because A and B
   did not overlap. Sequential consistency, the architect argues, would
   accept an invalid history (A ok → then B invoked and ok → readers observe
   B before A). Concede or re-reject with precision against the revision's
   actual claim (`dao.stream.md:580-586` and the IO Model's returns-what-is-true).
3. **A1.** The concession drafts a contract amendment for
   `dao.stream.md:254-261` authorizing transport-minted identities for
   handles no `attach!` produced, keeping key and correlation rule
   contractual, delegating the delivery mechanism to the transport spec.
   Faithful and sufficient?
4. **A3.** Wire frames renamed `:ws/accept` / `:ws/disclaim`; your
   `dao.stream.ws.md:202-204` wording adopted; new rule that a close before
   either first frame resolves the client as `:ws/transport-error`. Accept?

## Settled — do not re-litigate

The nine-item settled list from round 1, including the USER RULING approving
the two additive build-config exceptions.

## Constraints

Read-only; no file edits; no test suites; judge the revision as written —
notes on different preferences are notes, not findings, unless the choice is
unsound.

## Deliverable

Begin your final response exactly with:

Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Ho_Chi_Minh

Then: 1. Answers to questions 1-4, numbered. 2. Any residual findings
(severity | item | evidence | correction). 3. FINAL verdict table: A1, A2,
A3, B1, B2, B3, B4 → ACCEPT / ACCEPT-WITH-CHANGES / REJECT, one line each.
4. One line: is the amendment set ready for the orchestrator to apply?

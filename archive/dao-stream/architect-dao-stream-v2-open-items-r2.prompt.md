Created-GMT: 2026-09-02 19:14:29 GMT
Created-Local: 2026-09-03 02:14:29 Asia/Ho_Chi_Minh

# Task: dao.stream v2 open items — round 2, cross-family consensus

Role: Lead System Architect

Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-03 02:14:29 Asia/Ho_Chi_Minh | Status: active | Rationale: resume of session 01a0637c-39b3-7a22-b1a9-fb1c43b83f0e (round-1 author); reconcile its proposal against the Gemini cross-family review

Repository root: /Users/sto/workspace/datomworld. You authored
`collab/architect-dao-stream-open-items.gpt-5.6-sol.findings.md` in this
session. A cross-family review returned four non-ACCEPT verdicts in
`collab/review-dao-stream-open-items.gemini-3.1-pro-high.findings.md`.
Read both files, then produce round 2: CONCEDE or DEFEND each disputed item,
with revised amendment text wherever you concede.

## The orchestrator's adjudication (binding where marked)

1. **A2 (Gemini: Critical, REJECT — stranding).** ORCHESTRATOR RULES THE HOLE
   REAL: your acceptance gate tests the deposit *result* (`ok`), not the
   composition's *read*. On an evict-oldest medium a later burst can evict
   `:ws/accepted` — carrying the only writer handle — before a lagging
   composition reads it: connection accepted, client live, composition
   permanently unable to respond or close. Revise A2 so capability delivery
   cannot be lost to retention. Consider Gemini's two suggestions
   (pre-acceptance hold with explicit acknowledgement; a separate
   guaranteed-delivery channel) but you need not pick either — the revision
   must respect the spec's own Deposit Admission rules (declared evict-oldest;
   no unbounded in-memory retention) and the no-callbacks rule, and must state
   what evicting an acceptance event means under the revised design and who
   can still close the connection in every failure case.
2. **A3 rename (Gemini: wire frames `:ws/accept` / `:ws/disclaim`).**
   ORCHESTRATOR ACCEPTS THE RENAME unless you have a strictly better one.
   The serving-side deposited event stays `:ws/event :ws/accepted`; the
   client resolution event stays `:ws/opened`.
3. **A1 (Gemini: REJECT — contract amendment smuggled into a subordinate
   spec).** ORCHESTRATOR NOTES: `dao.stream.md:254-261` is silent on
   server-side minting rather than explicitly forbidding it, and the committed
   ws spec already binds server-side accepted-connection handles to
   `:dao.stream/attachment` (`dao.stream.ws.md:38-42`). Even so, identity
   rules are contract material. Choose one and do it fully:
   (a) draft the minimal contract amendment — exact wording and anchor in
   `dao.stream.md` — with A1 as the spec-level detail, or (b) defend
   spec-only, grounded on ws.md:38-42. Two sentences on why, either way.
4. **B3 (Gemini: REJECT — linearizability too strong; sequential
   consistency).** Genuine dispute; no orchestrator ruling. Concede or defend
   with precision: does real-time precedence in the oracle assert more than
   the contract's "every operation's outcome reflects that sequence at one
   moment. DaoStream guarantees nothing further about concurrent operations"
   (`dao.stream.md:580-586`)? If you concede, restate the oracle at exactly
   the contract's strength — name the model and what it can no longer catch.
   If you defend, show why real-time precedence is harness-observable fact
   rather than a demanded coordination.

## Also in this round

- Incorporate Gemini's drafted replacement for `dao.stream.ws.md:202-204`
  (its Section 4), adjusted if the rename changes it.
- Check whether the revised A2 ripples into B1-B4 or Phase 4a wording (a
  serving-side medium requirement is plausible); state any ripple as a
  further amendment.
- Re-run your invalidated-elsewhere discipline over every revised item.
- Do NOT touch or propose edits to `yin.repl.implementation-plan.md` — a
  fix round owns that file in parallel; list cross-document consequences
  only.

## Settled — unchanged from round 1, do not re-litigate

The nine-item settled list in your round-1 prompt, including the USER RULING
approving the two additive build-config exceptions (`shadow-cljs.edn` build +
the four new `deps.edn` aliases, 2026-09-03).

## Constraints

Read-only; no file edits; no heartbeat (orchestrator judges liveness by
process health); no test suites; do not stage or commit. The orchestrator
captures your stdout and promotes it to
`collab/architect-dao-stream-open-items-r2.gpt-5.6-sol.findings.md`.

## Deliverable

Begin your final response exactly with:

Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Ho_Chi_Minh>

Then, per disputed item (A1, A2, A3-rename, B3): VERDICT (CONCEDE / DEFEND),
revised amendment text where conceded (section anchor + exact wording), and
invalidated-elsewhere list. Then any B-item ripples. Then the updated summary
table: item | settles | blocks-until-applied.

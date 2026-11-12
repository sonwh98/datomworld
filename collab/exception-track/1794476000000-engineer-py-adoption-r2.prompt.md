Created-GMT: 2026-11-11 19:05:00 GMT
Created-Local: 2026-11-12 02:05:00 +07 (+0700)
Coding-Agent: deepseek
Session-ID: 78f45f2f-9c39-45d0-a635-0a292ef07b75
# Task: engineer — python adoption round (re-dispatch after two quota deaths on other lanes)
Role: Engineer
Implementers:
- Status-Event: 2026-11-12 02:30 +0700 | Model: gpt-6.1-sol | Status: failed | Rationale: codex POOL 1 quota (reset 04:20 +07); died pre-work.
- Status-Event: 2026-11-12 02:35 +0700 | Model: muse-wonder-1.0 | Status: failed | Rationale: muse quota exhausted (reset 2026-11-16); died pre-work.
- Model: deepseek-v4-pro | Assigned: 2026-11-12 02:40 +0700 | Status: active | Rationale: YOU built the provenance primitive — the doors, heap-write preservation, and GC semantics are your code; the python adoption wires onto exactly that. The spec is precise; your door knowledge de-risks the wiring.

## The task

Read `1794475000000-engineer-py-adoption.prompt.md` (the adoption brief of record — five points,
acceptance, worktree) and `1794451000000-consult-vm-provenance-design.gpt-6.1-sol.findings.md`
(the design you implemented). Work in `/Users/sto/workspace/datomworld-f12`, branch
`exception-py-r2r3` @ `0f0ab852` (rebased onto current master; the primitive `8c10c5b7` is in
your ancestry). Files: `src/cljc/yang/python/antlr/prelude.cljc` (+ tests). You are the only
writer.

## The five points (verbatim from the brief of record)

1. **Mint** through `cell/dsg__new-provenance [value provenance]` — your own door. Atomic; nil
   provenance refused.
2. **Read** via `cell/dsg__provenance [candidate]` — egress (the :917 region) authenticates
   through it; anything else → nil → the plain fresh `:yang.call/thrown` path.
3. **`:py/retained-condition` DIES as an authority source** — remove the key writes from
   `py/condition-exception` (:1043 region) and the slot read from egress. Guest-visible writes
   stay legal and cannot touch provenance.
4. **Transitive-helper closure**: `py/condition-exception` must not become a guest-reachable
   constructor — audit the export/projection/alias list so provenance helpers are outside guest
   reach (protected reserved names; the js/ts adoption notes in collab/ show the audit shape).
5. **Acceptance**: the three contract pins (`0326a044` / `0f0ab852`, RED) flip GREEN; the eight
   ledger fixtures + parity pins (`be87df74`) stay green; shared suites stay green; kondo 0/0.

## Verification

`clojure -M:test -n yang.call.python-exception-test` (the contract pins — focused, ALLOWED
locally: they are your round's own pins) + `yang.python.antlr.source-call-test` + kondo on
changed files. NEVER run `yang.call.exception-contract-test` or any suite-scale test locally.
Foreground only. Commit + push `exception-py-r2r3` (you are on the laptop; push should work —
if not, say so and the orchestrator fetches from your clone).

End with Completed-GMT/Completed-Local and: **ADOPTION LANDED: <sha>** or **BLOCKED: <what>**.

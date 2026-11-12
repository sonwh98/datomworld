Created-GMT: 2026-11-07 04:5x GMT
Created-Local: 2026-11-07 11:5x +07 (+0700)
Coding-Agent: codex
Session-ID: pending (provider-generated; capture thread.started.thread_id)

# Task: exception-track Round 0 — the retained-condition contract, the failing fixtures, and the kit exception-leg scaffolding

Role: Yang Compiler and Universal AST Engineer
Implementers:
- Model: codex gpt-6.1-sol | Assigned: 2026-11-07 11:50 +07 | Status: active | Rationale: design-heavy contract work feeding four frontend rounds

WORKTREE: /Users/sto/workspace/datomworld-f12 (at the current master merge; ANTLR parsers generated). Commit to a new branch `exception-r0`. You are the only writer.

## Authority

The OWNER RULING OF RECORD v2: `collab/exception-track/1794960000000-owner-ruling-exception-uniformity-v2.md` (commit 4879edef) — read it first; R1-R5, the scope bounds, and the acceptance gate are your spec. The baseline audit: `collab/yang-python/1794900000000-reviewer-exception-uniformity-audit.gpt-6.1-sol.stdout.log` (last agent message).

## What Round 0 delivers (per the ruling's sequencing step 0)

1. **The retained-condition provenance contract, as code.** In `test/yang/call/` (new namespace(s), not conformance.clj's public API unless needed): fixtures that construct, at the boundary, each §8.10.7 artifact —
   - a fresh thrown condition (donor source raises; caller's composition observes the envelope),
   - an unchanged rethrow of a generated protocol exception (construct `:yang.call/not-callable`, `:yang.call/invalid-invocation`, `:yang.call/unsupported-binding` via boundary fixtures — malformed invocations valid source cannot naturally build),
   - a retained `ForeignException` whose condition survives its creating activation (store it, finish the activation, rethrow from another).
   Each fixture pins the COMPLIANT behavior (v2's R2/R3) — these are the FAILING fixtures the ruling mandates: they are red today where the current code diverges, and each frontend round turns its own reds green. Mark each clearly with which requirement (R1-R5) and frontend(s) it gates, e.g. metadata `{:gates ::r2 :frontends #{:python :php}}`, so the acceptance ledger can be derived mechanically.
2. **The kit exception-leg scaffolding.** Extend `test/yang/call/conformance.clj` with the exception-leg composition (donor raises / caller catches patterns, the caller's `:render` extended or supplemented to expose exception outcomes — current renders show `:py/exception` etc.; make every admitted frontend's outcome observable uniformly). Do NOT change the successful-call legs' behavior; existing source_call tests must stay green.
3. **The Python-side legs you can run today** (your runnable lane is JVM): for every directed pair with python as donor or caller that the JVM kit can reach, wire the exception scenarios through the new scaffolding and report which are red-for-which-reason (expected: python R2 raw-foreign-payload reception and R3 generic-TypeError protocol loss are red; egress freshness per prelude.cljc:912 noted). JS/PHP/Clojure pairs' legs are authored but marked pending-their-rounds (they will fail until those rounds land — that is the point).

## Rules

- Files: `test/yang/call/**` and `test/yang/python/**` only. NO prelude/lower/runtime edits — those belong to the per-frontend rounds.
- No reader-conditional top-level requires (the a6c25106 lesson). The kit is a JVM `.clj`; if you add portable fixture data, keep it in `.cljc` data files, loaded not required.
- JVM lane only: `clj -M:test -n <ns>` (the `:test` alias carries `-Xss64m`). No slow tests, no Node/Dart, no shiva/jupiter (the hosts are busy).
- Real source on both sides of every leg (§8.10.11). No call-node rewrites, no foreign `:code` extraction.
- Commit in logical units: (a) the contract fixtures, (b) the kit scaffolding, (c) the python legs + the red report.

## Deliverable

Final agent message: the fixture inventory (name → gates → frontends → current red/green), the scaffolding API as landed, the python red report with file:line reasons, and exact test counts. End with the acceptance-ledger seed: the list of gates still open per frontend, so rounds 1-4 pick up from a written state.

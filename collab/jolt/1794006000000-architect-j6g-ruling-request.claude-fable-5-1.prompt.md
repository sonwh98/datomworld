Session-ID: eb3d92db-346b-43f6-a29d-77603374c3ca

# J6g ruling request: the workload W criterion failed — rule on the scheduling question before the traced run

Architect seat. Read-only on the adapter/composition; the deliverable is a
ruling.

## The measured facts

The J6g workload W run (j6ggate18, jupiter, master current) FAILED the
pre-registered criterion: peer A's 4 MiB message delivered once and the REPL
stepped every round, but peer B's round trips starved — 79 samples against
≥600 required, p99 ~7.9 s against ≤250 ms. The instrumented re-run
(collab/jolt/1793990000000-architect-j6g-diagnose.findings.md, codex
gpt-6.1-sol, filed) traced the causal chain: the harness submits a fresh
program every owner round and the REPL synchronously republishes the code
index over the full retained session history before returning to either peer
— a documented quadratic cost (yin.repl.index:21, the transactor watermark
scan at dao.space.transactor:117). Descriptor rotation is NOT broken.

## What the ruling must settle

1. **The scheduling principle**: rule explicitly whether A1's transfer-count
   bounds bound an owner round's synchronous interpretation (the diagnostic's
   proposal: they do not — a bounded number of pump visits does not imply a
   bounded service interval when the synchronous work between visits grows
   without bound). If the REPL's per-round interpretation is unbounded, the
   owner-thread contract needs the principle written down.
2. **The fix selection** — rule which candidate (or combination) is the
   intended architecture, and in what order the traced evidence should choose:
   (a) incremental index materialization (composition-owned successor state
   for the publication suffix; keeps the per-evaluation publication cadence);
   (b) bounded VM execution (make repl/make-runner resumable; requires
   ruling what queries observe while publication is pending);
   (c) payload-assembly throughput in the pump (rfc6455 take-payload /
   finish-frame / concat-chunks) — conditional on the trace showing the pump
   hot near A's last bytes.
3. **The traced-run protocol**: rule what the traced remote run must measure
   (JOLT_WORKLOAD_W_TRACE=1 is implemented: per-phase enter/leave, the code
   and AST index steps wrapped, the supervisor's independent phase/age
   reporting) and what outcome counts as J6g pass/fail once the ruled fix
   lands.

## Constraints

The adapter and parser are frozen (A1-A7 hold); the criterion (p99 ≤ 250 ms,
≥600 samples, delivered-once, step advance) is pre-registered and stands.
No code edits in this round.

## Deliverable

A ruling to stdout (the orchestrator files it into collab/jolt/ at write
time): the principle, the selected fix order, and the traced-run protocol.

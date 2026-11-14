Created-GMT: 2026-11-14 (dispatch time local 09:1x +0700)
Created-Local: 2026-11-14 09:1x +0700
Coding-Agent: codex
Session-ID: pending

# Task: eval-bridge part 8 — the remaining bridge units + the extract-ordering investigation

Role: Engineer. Branch eval-bridge @ 6876e789 (origin; your part-7 commits are pushed — the
host-key issue is resolved at the seat level, pushes work from the orchestrator; your own push
may still fail on the same environment issue: if so, commit locally and say so — the
orchestrator pushes). NOTE: your findings file naming: collab/yang-php/${TS}-engineer-eval-bridge-r8.gpt-6.1-terra.findings.md.

## Scope (from part 7's Remaining list, verbatim, plus one flagged investigation)
1. Compile request/response transport + service-backed admission/publication.
2. Actual installed accessor execution across backends (the manifest-pinned code/entry effect
   exercising real installed bindings, not the refusals).
3. php/eval-run in the caller task (caller cells/scope/budget).
4. Both expression paths (prefix + plain-call) converted to the supported bridge.
5. Atomic redeclaration via guest fatal (the collision lifecycle record binds: shutdown runs,
   dtors suppressed, 255; the guest compile-fatal terminal path is confirmed conforming).
6. Generator dictionary cleanup (part 7 flagged it).
7. **The extract-ordering investigation** (part 7 flagged: extract(['o' => new C]) prints "AD"
   vs hand-derived "DA" even after the dynamic-scope release fix — temporary array ownership in
   the destructor/eval interplay). Reproduce with a focused test, determine the correct PHP 8.3
   behavior (oracle evidence available from the orchestrator if needed — request it), fix or
   report.
8. Real-service fixtures + the full gate set (all part-7 named gates + the new units' tests),
   sequential with explicit timeouts.

## Rules
Design gap -> stop + record. Fixtures :oracle :pending where real PHP must adjudicate. Commit
locally per green; push if your environment permits (the orchestrator pushes otherwise).
Findings: collab/yang-php/${TS}-engineer-eval-bridge-r8.gpt-6.1-terra.findings.md.

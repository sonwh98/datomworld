Created-GMT: $(date -u '+%Y-%m-%d %H:%M:%S GMT')
Created-Local: $(date '+%Y-%m-%d %H:%M:%S %z') (+0700)
Coding-Agent: codex
Session-ID: pending

# Task: eval-bridge part 13-r3 — complete the redeclaration matrix + the remaining bridge units

Role: Engineer. Branch eval-bridge (pushed @ the r13-r2 WIP commit). Your predecessor's partial
implementation is COMMITTED — read its findings first: collab/yang-php/1794624122-engineer-eval-bridge-r13.gpt-6.1-terra.findings.md (in this worktree), plus the astra atomicity ruling
(collab/yang-php/1794615000000-architect-atomicity-ruling.findings.md — the 11-fixture matrix is
the spec) and the lifecycle record (collab/orch-lane/p7-lifecycle-probes.txt).

## Remaining scope (from the predecessor's own list)
1. The FULL 11-fixture matrix green per the ruling (the partial covered some rows; complete the
   rest: diagnostic locations, deferred-class visibility ordering, conditional collision,
   mixed class/function before top-level function collision, two same-name functions, class
   collision across two eval invocations, reinvoke-same-artifact, case variants/namespaces,
   syntax-error and runtime-exception controls, invalid site metadata/admission control).
2. The end-to-end expression paths through the service (prefix + plain-call converted; the
   bridge must be executable through the configured service — part 6/7 findings).
3. The existing eval suites (service/transport/admission/coordinator/emitter/publication/
   scope-cleanup/retention) stay green; php/eval-run in the caller task if budget allows,
   otherwise record precisely for the next slice.
4. Gates: sequential, explicit timeouts (600s lower-portable), kondo/cljstyle, python
   safepoint additivity. Push per green.

## Rules
Design gap -> stop + record. Fixtures :oracle :pending where real PHP must adjudicate. Findings:
collab/yang-php/${TS}-engineer-eval-bridge-r13r3.gpt-6.1-terra.findings.md.

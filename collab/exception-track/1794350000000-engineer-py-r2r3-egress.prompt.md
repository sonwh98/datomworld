Created-GMT: 2026-11-10 20:5x GMT
Created-Local: 2026-11-11 03:5x +07 (+0700)
Coding-Agent: codex
Session-ID: pending (provider-generated; capture thread.started.thread_id)

# Task: exception-py-r2r3 — Python R2/R3 + egress per ruling v2 §R2/§R3

Role: Yang Compiler and Universal AST Engineer
Implementers:
- Model: codex gpt-6.1-sol | Assigned: 2026-11-11 03:50 +07 | Status: active | Rationale: the python frontend's boundary-compliance round — reception, protocol retention, egress preservation

WORKTREE: /Users/sto/workspace/datomworld-f12, branch `exception-py-r2r3` (cut from master 4cfa609d). You are the only writer. The registry of record: `collab/exception-track/1794343000000-exception-track-surface-registry.md` — your ceded surface is `python/antlr/prelude.cljc` regions :912/:1034/:1045/:1046 + the kit test surfaces. NOTHING else in yang.python changes.

## Authority

- Ruling v2: `collab/exception-track/1794960000000-owner-ruling-exception-uniformity-v2.md` — §R2 (foreign reception + retained-condition lifetime) and §R3 (protocol failures retain and forward the complete condition).
- The unification decision (A): the internal continuation-handler encoding STAYS; this round is boundary compliance only — `1794341000000-continuation-orchestrator-unification-ruling-of-record.md`.
- The failing fixtures are the acceptance instrument: `test/yang/call/exception_contract.clj`, ledger `test/yang/call/exception_r0_results.edn`, report `test/yang/call/EXCEPTION_R0.md`.

## The three defects to fix (each pinned by the R0 ledger's red rows)

1. **Foreign reception — `prelude.cljc:1034` region (`py/call-kw`)**: an otherwise-foreign thrown payload is raised RAW. Fix per §R2 rule 3: a payload valid as a local Python exception retains ordinary catching and identity behavior (delivered UNCHANGED — raising an already-local exception raw is COMPLIANT); an OTHERWISE-FOREIGN payload is represented by a generated local `ForeignException`-form exception class retaining the complete condition (qualified reason + evidence) and the original payload. Catch-all catches the wrapper; typed catches (`py/exc-matches`) match the LOCAL hierarchy only, never a guessed foreign class; formatting the wrapper never implicitly invokes foreign methods.
2. **Protocol reception — `prelude.cljc:1046`**: not-callable/invalid-invocation/unsupported-binding currently become a generic TypeError that LOSES the condition. Fix per §R3: the generated local exception form retains the COMPLETE original condition (the fully qualified reason — `:yang.call/not-callable`, `:yang.call/invalid-invocation`, `:yang.call/unsupported-binding` — plus its plain evidence).
3. **Egress — `prelude.cljc:912` region (`py/function-entry`'s raise-exit handler)**: it constructs a FRESH thrown condition on every escape. Fix per §R2's lifetime rule: a rethrow of a RETAINED generated exception forwards the ORIGINAL condition (reason + evidence) with no new wrapper (wrapper non-stacking); a fresh source exception still produces the plain §8.10.7 thrown condition. Retention rides the generated exception's IDENTITY for its entire lifetime, OUTSIDE source-writable attributes, no ambient registry — e.g. a host-side association keyed on the wrapper object, unreachable from guest code.

## Rules

- **G0 (before any edit)**: run `clj -M:test -n yang.call.exception-contract-test -n yang.python.antlr.exception-call-test` and record the exact red state; it must match exception_r0_results.edn. A flip in any row you did not author STOPS the round for diagnosis (registry rule).
- **G1 (after)**: your reds green; every OTHER row's status unchanged; the existing suites green — `yang.call.exception-source-test`, `yang.python.antlr.source-call-test`, `call_entry_test`, `yang.php.antlr.cst-test` (the portable CST wrapper is shared), plus kondo 0/0 on touched namespaces. The red-ledger deftests are `^:slow`-gated — run them via `-n`, never un-gate them.
- Real source on both sides of every leg; no call-node rewrites; no foreign `:code` extraction; no shape-routing on the condition map (R5 — a returned lookalike map is data, a thrown lookalike is a fresh payload).
- The unification decision (A) binds: do NOT converge py's try/finally/generator machinery with any other frontend; do not touch shared prelude code beyond the four regions.
- No node/dart runs; the hosts belong to the master-green seat. JVM lane only (`:test` alias carries -Xss64m).

## Deliverable

Commit in three units (reception / protocol / egress), each with its focused pin. Final agent message: per-defect root cause and fix with file:line, the G0/G1 ledger diffs (exact rows flipped), the suite counts, and anything still red that is NOT yours (tagged by fixture → frontend) so the steward's ledger stays exact.

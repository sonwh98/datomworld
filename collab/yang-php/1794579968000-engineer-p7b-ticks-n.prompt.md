Created-GMT: $(date -u '+%Y-%m-%d %H:%M:%S GMT')
Created-Local: $(date '+%Y-%m-%d %H:%M:%S %z') (+0700)
Coding-Agent: codex
Session-ID: pending

# Task: P7-b part N — ADOPT the oracle records. Folds are truth; the engine conforms.

Role: Engineer. Branch p7b-ticks @ 1e97a649. THE RECORD: collab/yang-php/${TS}-oracle-p7-folds.txt — 22 FOLD lines from the shiva php:8.3.35 docker pass, all targets checked, zero execution problems. Read FIRST.

## Steps
1. PROMOTE every fold to recorded truth in test/yang/php/antlr/programs_p7b.cljc: replace :out (and :diag/:uncaught/:status where folds carry them) with the fold values; set :oracle "php-8.3.35 shiva 2026-11-13". Keep :source untouched.
2. ENGINE CONFORMS: run the full engine fixture suite; every red now means a REAL divergence between the engine and php — fix the ENGINE (lowering/site selection/dispatch) until the suite is fully green against the recorded values. Known-divergent from the pass, for orientation only (verify against the folds yourself): do-while/for/foreach/switch selection shapes, adjacent-marked, nested-zero-n2, dispatch-add, dispatch-remove, and NOTE unregister-executing — php RAISES "Registered tick function cannot be unregistered while it is being executed" (Error, status 255, uncaught) while the engine answered "ASB": implement the php error, not the engine's tolerance.
3. The decisions already settled by the astra table stand; the folds now fix the remaining selection questions empirically — do not re-derive, adopt.
4. Gates: FULL tick fixtures green; include gate green (fold says "LT" — matches); tick-fixtures + siteset + yang.safepoint 9/20 + python trace pairs green; kondo/cljstyle clean. Push per meaningful green.
Findings: collab/yang-php/${TS}-engineer-p7b-ticks-n.gpt-6.1-terra.findings.md — per-fixture adoption record: engine-before vs fold vs final, and which engine behaviors changed.

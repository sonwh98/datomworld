Created-GMT: $(date -u '+%Y-%m-%d %H:%M:%S GMT' 2>/dev/null || date '+%Y-%m-%d %H:%M:%S GMT')
Created-Local: staged $(date '+%Y-%m-%d %H:%M:%S %z') (+0700)
Coding-Agent: codex
Session-ID: pending

# Task: Eval-bridge — eval through a composition-supplied compiler service (P7-a/P7-b lineage follow-on; slice after the P7-b merge)

Role: Engineer (implementation, yang.php + yin.vm effect surface)

## Status: STAGED — dispatch gated on the P7-b merge landing (this round edits lower.cljc + prelude.cljc, which the ticks round rewrote; cut your branch from POST-merge master).

## THE SPEC (read first, verbatim authority)
collab/orch-lane/1794400000000-architect-eval-bridge-design.gpt-6.1-sol.stdout.log — final
message: the compilation path (php/eval-run via stream effects; never cst/parse-source or a
host evaluator in portable code), eval-unit emission mode (reuse context/unit-body/return
escape; map fall-through to :php/null, NOT 1), eval source goal (PHP-mode lexer start, no
tag repair, preserve byte spans/line starts), runtime imports (%runtime supplies caller's
php/ callables; bind free php/ names from the definition inventory; no second runtime), the
code/entry retrieval effect (engine effect-handler registry; no new VM opcode), compilation
request contents (source bytes, request identity, revisions, goal, provenance, options; NO
caller cells/values/continuation/host handles), Node/Dart policy (configured service or the
pinned refusal). Replace the two refusal paths (prefix-refusals + plain-call refuse).

## Scope
A. Engine: code/entry retrieval effect per design.
B. PHP lower: Eval prefix/plain-call refusals -> eval-unit emission (lexer PHP-mode start;
   preserve spans); context/unit-body/return-escape reuse with :php/null fall-through.
C. PHP prelude: php/eval-run stream-effect implementation; request shape; correlated outcome.
D. Coordinator/parser worker: eval source goal through the existing parser+lowering; canonical
   envelope; safepoint derivation; publication + linker admission (ordinary machinery).
E. Node/Dart: configured-service-or-pinned-refusal per design.
F. Fixtures TDD: eval fixtures (simple eval, caller-variable access, function defs visible to
   caller, nested eval, refusal shapes on Node/Dart without service) — hand-derived, then
   oracle-recorded for JVM-parity rows (orchestrator runs the shiva pass).
G. Gates: focused lanes sequential with explicit timeouts; kondo/cljstyle; suite-scale = orchestrator.

## Rules
Conventional commits on branch eval-bridge (cut from post-merge master); push as you go; design
gap -> STOP + record (the design is detailed but this is a deeper surface than ticks).
Findings: collab/yang-php/<ts>-engineer-eval-bridge.<model>.findings.md.

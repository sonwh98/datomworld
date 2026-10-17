Created-GMT: 2026-10-16 19:45:00 GMT
Created-Local: 2026-10-17 02:45:00 +07 (+0700)
Coding-Agent: codex
Session-ID: 7ee8fd86-b4ca-470c-8868-019d1803a105

# Task: Adversarial review — the yang.typescript and yang.php frontend designs (Fable, claude-cloud)

Role: Review (Architectural Review — your designated role). Read-only; do not edit any file.

Main tree (read-only): /Users/sto/workspace/datomworld at master (verify the sha; judge committed state).

THE DESIGN UNDER REVIEW: /Users/sto/workspace/datomworld/collab/1792197000000-architect-yang-ts-php-designs.claude-cloud-fable.findings.md
— two complete frontend designs by claude-fable-5-1 (via the claude-cloud channel): A = yang.typescript
(the typed superset pilot; JS runtime semantics presupposed and ruled, the type layer additive), B =
yang.php (the web/associative-array pilot; the legacy php.cljc migration). Read it whole.

CHECK (adversarial, with the codebase open — you are the second set of eyes on design claims):
1. Every claim about the LANDED code (file:line) is true: the seven binding defects D1-D7, the neutral
   seams (yang.frontend, yang.stage, yang/antlr/packet, yin.repl.frontends), the Python frontend
   structure, the emitter duplication (prelude.cljc:3483-3726 vs safepoint.cljc).
2. Ruling conformance: the designs must not contradict the landed rulings (8.5.1-8.5.6 — the six
   invariants, float64 carrier 8.5.5, jobs-to-completion safepoints 8.5.2, statement-time imports/SCC/
   reload, ruling 16's no-yin.repl-edit claim vs defect D2, the no-filesystem doctrine per ADR 0004).
3. The type-layer design (design A): is the parse-and-erase vs checking-profile boundary coherent and
   sliceable; is the enums-have-runtime-presence lowering ruled; null/undefined on the yin value model;
   does the .d.ts story hold against the linker's artifact model.
4. The PHP design (design B): ordered-array/reference/coercion rulings vs the cell substrate; the legacy
   php.cljc migration posture; the prelude-surface refusal scale is honest.
5. The lifts L1-L4 and the slice sequencings: are the acceptance contracts testable as written; is the
   L1-first ordering sound given D7; do the owner-decision lists actually capture every open decision.
6. Feasibility: the vendored grammar choices (grammars-v4 javascript/typescript/php), the pinned-runtime
   corpus plans, the three-host posture.

Verdict: end with exactly one line "Verdict: READY (sign-off granted)" or "Verdict: REQUEST CHANGES" with
severity-tagged findings (P1/P2/P3) and evidence. Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +0700>

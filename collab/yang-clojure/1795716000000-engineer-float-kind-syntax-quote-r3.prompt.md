Created-GMT: 2026-11-26 18:39:10 GMT
Created-Local: 2026-11-27 01:39:10 +0700
Coding-Agent: agy
Session-ID: pending (conversation_id)

# Task: fix/float-kind-portability r3 — the two remaining syntax-quote defects (sign-off r2 B-6a/B-6b)

Role: Compiler Engineer. Your r2 resolved B-1..B-5 and B-7 (sign-off confirms: fresh JVM gates 27/542, all requested counterexamples pass). TWO syntax-quote defects remain — the sign-off's findings collab/yang-clojure/1795714268000-architect-floatkind-signoff-r2.gpt-6-astra.findings.md carry live reproduction evidence. Read it first.

## B-6a — syntax-quote loses symbol qualification

reader.cljc:685-690 quotes ordinary symbols unchanged. Previous tools.reader behavior: backtick + resolves to (quote clojure.core/+) for core symbols, (quote user/x)-style ns resolution for others. Landed: bare symbols. This changes guest Data for integer-free source. Fix: preserve syntax-quote symbol resolution through an explicit portable reader context (core symbols + supported namespace/alias behavior), no global reader mutation. Add differential + actual-execution checks for QUOTED SYMBOLS (your 33-case corpus covered values and metadata but not symbol resolution).

## B-6b — auto-gensym capture + counter reset

reader.cljc:621-633 emits <base>__<counter>__auto__ without capture-avoidance; :718-723 resets the counter to 1 per syntax quote. Reproducer (in the findings): "`(~'let [x# 2] (~'+ x# ~'x__1__auto__))" — your expansion collides x# with the user symbol; upstream gives 12, yours gives 4. Also [x# x__1__auto__] expands both to the same symbol; nested quote scopes reuse. Fix: deterministic AND hygienic across user references, unquoted forms, and nested scopes (mirror the FnArgRef collect-used-syms + fresh-sym approach from B-5; upstream's exact counter values are NOT the golden — alpha-equivalence + capture-freedom are). Add collision/scope tests with semantic assertions, not just form-equality.

## Rules

Same as r2: RED counterexample tests first (both defects, saved to the evidence dir), then fix, then the FULL clean seven-gate + regression suite JVM + Node, kondo 0/0, then Dart + Jolt legs for affected namespaces. Bounds: RULING 5 file boundaries. Commit NOTHING. Deliverable: collab/yang-clojure/<new-ts>-engineer-float-kind-syntax-quote-r3.gemini-4-neon.findings.md. Do not end the turn with runnable work remaining.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

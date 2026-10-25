Completed-GMT: 2026-10-24 18:01:13 GMT
Completed-Local: 2026-10-25 01:01:13 Asia/Ho_Chi_Minh
Coding-Agent: codex
Session-ID: 01a16d38-d4fc-7253-8098-51e80bd692b9

**Ruling: Phase 4a requires stack-safe execution now. The 200-level guard is acceptable as a development stopgap, not as its landing substitute.**

1. **Do not qualify the guard.** A rule level is not a fixed number of host frames: clause structure, nested evaluation and continuation calls change stack consumption. Observing overflow around 400 levels on one JVM does not establish that 200 is safe across supported hosts or accepted queries. The guard also does not cover stack growth outside rule nesting.

   `:unsupported` is appropriate for an explicitly declared language/profile restriction; this would introduce a new restriction to compensate for an implementation resource failure. I do not approve that restriction or the constant as the Phase 4a contract. Do not relabel the guard as work-budget exhaustion.

2. **Implement a trampoline or explicit frames in the common kernel.** This directly satisfies r3’s “where necessary” requirement. A trampoline is sufficient if every potentially unbounded evaluator continuation chain returns control to its driver before proceeding. Merely trampolining rule entry while recursively invoking downstream continuations is insufficient.

   Keep the existing single evaluator, state-threading contract, first-witness behavior and scope restoration. This decision does **not** require Phase 3 tabling now.

3. **Phase 3 owns completeness, not this stack-safety repair.** Its tabling work must use the stack-safe kernel. It must neither inherit a hidden depth cutoff nor remove a guard and thereby restore stack overflow.

**Contract amendment:** append under “Normative execution bounds → Forcing discipline”:

> Phase 4a must make query-controlled, potentially unbounded evaluation chains stack-safe through a trampoline or explicit execution frames. This includes recursive rule expansion and continuation chains whose depth grows with processed bindings or nested evaluation. Increasing the work allowance must not replace structured exhaustion with host stack overflow. A fixed rule-depth guard is not a substitute for this requirement. Phase 3 tabling addresses recursive completeness and must preserve this execution property. Opaque caller functions retain the separately documented indivisible-operation limitation.

**Acceptance:** deterministic CLJ/CLJS/CLJD tests must show generous finite work caps producing structured exhaustion on fresh-value recursion beyond the former overflow workload. Also cover mutual recursion, many processed bindings, nested evaluation and first-witness termination. No real-time deadline is needed for these tests.

The implementer may retain the guard during development, but it must not remain the mechanism establishing stack safety at Phase 4a landing.
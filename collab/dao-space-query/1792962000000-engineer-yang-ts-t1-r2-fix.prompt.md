You are Engineer Sonnet (claude-sonnet-5-5).
Your task is to implement the R2-fix directives for Track 3 Slice T1 (`yang.typescript`) in `/Users/sto/workspace/datomworld-ts-t1` on branch `yang-ts-t1`.

Carefully follow the binding rulings and directives in `/Users/sto/workspace/datomworld/collab/1792960000000-architect-ts-t1-r2-adjudication.claude-fable-5-1.findings.md`:

1. **R2-01**: In `lower.cljc`, add `satisfies` to the typed refusal dispatcher alongside `as` (refuse as `:yang.ts/unsupported` with `:construct "type syntax"` and `:slice :T8`). Feed every T0 parse fixture to the lowering in acceptance test.
2. **R2-02**: In `lower.cljc`, implement direct syntactic refusals for bare identifier `eval` call and bare identifier `new Function` where `scope/resolve` resolves to `{:kind :global}`.
3. **R2-03**: In `lower.cljc`, copy `let` binding cells before entering the first `for (let ...)` iteration (not just in `next-iteration`).
4. **R2-04**: In `scope.cljc`, make lexical/var collision check order-independent across enclosing scopes (`:var-through`), raising `:yang.ts/early-error`.
5. **R2-05**: In `scope.cljc`, enforce missing initializer in `const` for C-style `for` loops (restrict `:for-head` exception).
6. **R2-10**: In `scope.cljc` / `lower.cljc`, ensure named function expression self-binding is read-only (const/immutable in strict mode).
7. **R2-11**: In `scope.cljc` / `lower.cljc`, support chained labels on loops.
8. **R2-12**: In `scope.cljc`, reject duplicate parameter names in strict mode with `:yang.ts/early-error`.
9. **Metadata**: In `lower.cljc`, ensure `enum` and `namespace` report as `:T4` / `:T5` (not `:T7`).
10. **Reclassified Items (R2-06, R2-07, R2-08, R2-09, string toString)**:
    - Adjudicated as known gaps (deferred to host export / T2 / T3 per adjudication table). Do not expand engine or host exports.

Run `cljstyle fix`, `clj -M:kondo`, and verify the JVM test suite passes (`clojure -M:test -e :slow -r 'yang.typescript.*'`).
Report all test and assertion counts.

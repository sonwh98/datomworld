Created-GMT: 2026-11-13 23:30:00 GMT
Created-Local: 2026-11-14 06:30:00 +07 (+0700)
Coding-Agent: codex
Session-ID: 75653345-2452-4797-b7af-3ec426252906 (RESUME of your blog fact-check session — you hold the full context)
# Task: REWRITE the TCO blog post — the fact-check corrected the architecture
Role: Architect + technical writer (your own fact-check rejected 3/7 claims; rewrite with the corrected architecture)
Implementers:
- Model: gpt-6-astra | Assigned: 2026-11-14 06:30 +0700 | Status: active | Rationale: you did the fact-check; you know what the corrected architecture is.

## What your fact-check found

Your prior session (75653345-2452-4797-b7af-3ec426252906) fact-checked the steward's 7 claims and found C2, C3, C5 FAIL:
- C2: the outcome model was RETIRED — the port replaced it with the continuation encoding
- C3: dynamic unwinding doesn't inherently preclude TCO (JVM Clojure is the counterexample)
- C5: source semantics don't dictate the error model — clojure now uses the same handler-stack family as py/ts/php

The supported thesis you identified: "the current private versus published call paths explain the depth difference" — NOT different error models alone.

## The corrected blog architecture

The blog should tell THIS story instead:

1. The VM is a CESK machine with native TCO (`:tail? true` replaces frames) — this is universal
2. The four yang compilers compile to the same UAST — the UAST format doesn't determine TCO
3. What determines TCO is the **CALL PATH**: intra-unit private tail lane (direct body application, no bridge) vs published entry bridge (handler-before-binding, cleanup-after)
4. The private lane keeps clojure recursion at constant depth (7 flat)
5. The published bridge adds linear frames for py/ts/php (57→657, 40→400, 28→268)
6. The convergence port unified clojure onto the published-bridge encoding — but the private tail lane survived, so intra-unit clojure recursion stays constant-depth
7. The architectural insight: TCO is a property of the CALL PATH (is a handler active during the body call?), not the error model or the VM or the UAST format
8. The exception track unified the boundary contract, not the internal call path — the private lane is per-frontend infrastructure

## What to write

Write `public/chp/blog/tco-call-path-not-error-model.blog` (EDN + Hiccup per format.md):
- EDN namespaced maps + Hiccup vectors
- Include `:$blog-title` and `:$blog-date`
- NEVER markdown syntax; NEVER em dashes
- Code samples: `[:pre [:code "..."]]`

The blog should cite actual code (file:line) and the depth-probe measurements. It should be accessible to a technical reader who understands compilers and VMs. It should be honest about the trade (the outcome model gave constant depth; the continuation encoding gives finally expressibility; the private lane preserves clojure's constant depth while the public bridge adds frames for cross-language calls).

### Commit

Write the `.blog` file, commit on `exception-kit-finally` branch (or a new `blog/tco` branch), push to origin.

End with Completed-GMT/Completed-Local and: **BLOG WRITTEN: <path>** or **BLOCKED: <what>**.

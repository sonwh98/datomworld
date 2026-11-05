Created-Local: 2026-11-06 1x:xx +0700
Coding-Agent: interactive (ZCode / glm-5.3-flash, yang.python orchestrator seat)

# The S11-python legs round: LANDED (1612f7a1) — Python's first §8.10.11 real-source conformance evidence

## What landed

`test/yang/python/antlr/source_call_test.clj` (58 lines, additive, test-only) — four legs through the shared `conformance/call` composition (publishes the donor's real source, projects its exports, runs the caller's real source, refuses unless every vector backend agrees):

1. **Python donor → TypeScript caller**: Python `py_apply` (stores, calls back, identity-guarded) called by unmodified TS source; answered `["22n"]`.
2. **TypeScript donor → Python caller**: TS `ts_apply` called by unmodified Python source; answered `["22"]`.
3. **Higher-order call-back**: Python receives a captured TS function, asserts `stored is f` and calls it; answered `["(True, 21)"]`.
4. **Opaque round-trip**: foreign object/function/string/float64/exact-integer through Python unchanged, identity-asserted (all True).

Round: codex gpt-6-luna, thread 01a1acdf, branch `s11-python-legs` (`e0ff224d` + the orchestrator's lane fix `e9a0b430`). Focused JVM result: 4 tests / 4 assertions / 0 failures.

## The orchestrator gate caught a lane break the round missed

The round ran JVM-only and committed the test as `.cljc` — but it requires `yang.call.conformance`, a `.clj`-only namespace, so the shadow-cljs build of `test/` refused with "Should this be a .cljs file?" (caught on the orchestrator's own compile check after the yang.clojure restore, under `mise exec java@21`). Fix: renamed to `.clj` — the repo's pattern for JVM-only tests (`cross_lang_test.clj`); shadow skips it. Both lanes re-verified: JVM green, cljs compiles clean (0 `ns*`/`not available` hits).

Architecture review: real source only (both sides parsed and lowered by their own frontends; no call-node rewrites, no foreign `:code` extraction, no wrappers); `conformance/call` verified to run and cross-agree on all vector backends; additive. Merged under the standing rule.

## Open items from the round's report

- Python builtins lack `type` (prelude.cljc ~:3440-3500) — an exploratory `type()` check raised NameError; legs assert identity instead. A builtin-growth decision for a future round.
- The written list of remaining §8.10.11 evidence rows for Python: broader direct-call matrix; higher-order return/store/recursion; callable admission; presence and binding; dynamic callability; reference behavior; exception propagation and identity; context lifetime/suspension; publication/custody across importing tasks; portability on Node and Dart; broader local-semantics evidence.
- The portability row is blocked until the Node/Dart lanes are healthy on master post-restore (the restore landed `acbf8e10`; the next nightly carries the first full run).

— yang.python orchestrator seat

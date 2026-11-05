Created-Local: 2026-11-05 04:3x +0700
Coding-Agent: interactive (ZCode / glm-5.3-flash, yang.python orchestrator seat)

# The F1/F2 diagnosis round: verified on shiva and MERGED (062c31b7); F1 remains open

## What landed

Branch `f12-diagnosis-fixes` (round dispatch 1794700000000, executed by gpt-6-luna plus the orchestrator's follow-up):

- `38b916e8` — the compile-contract hardening: `(compile ...)` now pins that the artifact contains the SUBMITTED form (`f12_contract_marker`/`f12_contract_value`), stays compact, and leaves the session unprimed; a host-neutral `:incomplete` probe for the unclosed parenthesis.
- `9dae3f07` — **F2 CLOSED**: the rebuild test pins the semantic VM on the parserless hosts (`#?(:clj :register :cljs :semantic :cljd :semantic)`) — the reported Node/Dart discrepancy was the register-VM rebuild being JVM-only, exactly the suspected path at yin.repl.cljc:1834; the design (yang.antlr.md:2492) requires the ANSWERS to match across VMs, which they do, not the rebuilt VM type.
- `a6f74280` — the orchestrator's packet fix (see below).

## The verification arc (the lesson of this round)

The engineer could not run the focused test (no `bb`, no generated parsers, no network in the sandbox) — the report said so honestly and it was accepted. First run on shiva (`~/ci/datomworld-python-s1`, parsers generated via `clj -M:antlr-gen`): **4 failures / 199 assertions** — all in the NEW compile-contract test, `:yang.python/cst` leg only.

Root cause: the `:yang.python/cst` frontend parses from PRECOMPILED PACKETS (`:parse (fn [source] (get packets source))`, python_antlr_test.cljc:124); the new test's source had no packet, so parse answered nil and the compile path surfaced the empty `SyntaxError`. The `:yang/python` leg (real generated parser) passed. Fix: one packet entry mirroring the existing shape — `(module (st (asg (tc (vr "f12_contract_marker")) (tc (vr "f12_contract_value")))))`.

Second run on shiva: **22 tests / 201 assertions / 0 failures / 0 errors** under the merged stack policy (the `:test` alias carries `-Xss64m`; a `-Xss2m` attempt was moot — the alias supersedes).

## Merge

Architecture review: test-only diff (17 insertions, 4 deletions in python_antlr_test.cljc), additive, no production surface. The dropped reader conditional around the `repl-probe` require is safe — `repl_probe.cljc` is a `.cljc` namespace and the probe test is deliberately every-host. Merged to master as **`062c31b7`** and pushed. Per the owner's standing rule (focused tests green + architecture.md review → merge without the full remote suite); the nightly at 03:00 owns the full run on master.

## Still open

**F1 — `dht-import :importers`** remains unresolved and unchanged: the reported Node-value behavior was never captured (no host runners in the sandbox; the JVM leg of that test could not load the parser class pre-generation). Next round on shiva: reproduce `dht-import :importers` with a real runner and capture the Node value before touching any fixture.

## Commands of record (shiva)

```
cd ~/ci/datomworld-python-s1
git checkout -B f12-diagnosis-fixes origin/f12-diagnosis-fixes
clj -M:antlr-gen
clj -M:test -n yin.repl.python-antlr-test        # the :test alias carries -Xss64m + the runner main-opts
```

— yang.python orchestrator seat

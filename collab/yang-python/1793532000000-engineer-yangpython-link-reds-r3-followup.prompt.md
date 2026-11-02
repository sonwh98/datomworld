Created-GMT: 2026-11-01 10:10:00 GMT
Created-Local: 2026-11-01 17:10:00 +07 (+0700)
Coding-Agent: codex (resume thread 01a1966d-3170-7c52-a2ba-f85db0aa1fa5)

# Task: follow-up — the verified family map for the remaining reds

Role: Yang Compiler and Universal AST Engineer (same round)

Your diagnosis matches mine independently: `yang.call` must be a runtime module for the hook stage, and
the linked require should be unconditional. Good. This follow-up adds evidence you do not have, for the
families your fix may not cover.

## Verified from jupiter:~/ci/nightly-results/20261101-0148/ (I read the artifacts directly)

| Namespace | Reds | Signature | Module |
|---|---|---|---|
| siteset-test | 4 | `missing-site-set`, profile `:py/hooks` | yang.call |
| yin.vm.semantic.derive-test | 1 | same | yang.call |
| dht-import-test | 84 | `:reason :absent` ×24 (24 name `yang.call`); `:yin.admit/untrusted-principal` ×6 | **yang.call** (24) |
| linked-prelude-test | 9 | install child `:blocked? true, :halted? false` at :484/:485 | (own root) |
| safepoint-test | 1 | `recursion-error-test`, PersistentArrayMap.java:461 | (not a link refusal) |
| linked-safepoint-test | 2 | UNCLASSIFIED — classify it | ? |
| e2e-test | 2 | UNCLASSIFIED — classify it | ? |

## What this means for your in-flight fix

- Your two changes (unconditional require; `yang.call` in the runtime list) should clear siteset (4) and
  derive (1) directly.
- **dht-import (84) is a second gap**: `:reason :absent` means `yang.call` was never PUBLISHED into the
  store the DHT reader links from — 24 refusals name it. Your require fix makes the dependency
  explicit; the publication side must also put `yang.call` in the store for that path. Check whether
  `publish-units!` (or the DHT fixture's publication helper) includes the runtime module. The 6
  `untrusted-principal` refusals are a separate admission question in the same test — report them, do
  not silently fix.
- **linked-prelude (9) may be the SAME root in disguise**: a failed require parks the install child,
  which is exactly the `:blocked? true` we see. After your fix lands, re-run this namespace FIRST and
  re-read the actual value before treating it as an independent defect.
- **safepoint-test (1)** is `recursion-error-test` — unrelated to linking; diagnose separately or
  report it as out of this round's scope with evidence.

## Scope reminder

`src/cljc/yang/python/**` and `test/yang/python/**` only. Do not modify the linker, the engine, or
dao.jing. If `dht-import`'s `:absent` requires a publication change outside your files, report it
precisely with the file:line and stop there rather than expanding.

## Report

Per-namespace results with counts, the family-to-root mapping, which of the seven you cleared, and the
explicit disposition of the unclassified and out-of-scope items. Do not claim a namespace green without
the count.

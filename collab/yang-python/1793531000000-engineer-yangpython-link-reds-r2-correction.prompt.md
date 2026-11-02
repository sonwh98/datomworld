Created-GMT: 2026-11-01 09:55:00 GMT
Created-Local: 2026-11-01 16:55:00 +07 (+0700)
Coding-Agent: codex (resume the same session)
Session-ID: <the thread id from the first round's JSONL>

# Task: correction to the brief — the verified signatures and the real root cause

Role: Yang Compiler and Universal AST Engineer (same round, corrected evidence)

I verified your brief's claims against jupiter's actual artifacts. Its signature table was WRONG on
the dominant reason, and the wrong lead may have sent you to the wrong file. Here is the verified
evidence — use this, not the earlier table.

## Verified from jupiter:~/ci/nightly-results/20261101-0148/

Counts per namespace are EXACT as the brief stated (84 / 9 / 4 / 2 / 2 / 1 / 1), and the total
`jvm-slow namespaces=52 red=7` is confirmed. But:

**Every one of the five link refusals names the SAME module: `yang.call`.**

```
{:name yang.call, :yin.link/id [:t0 2],
 :manifest :segment/blake3-466b53cc8bddc0008e276a8a757a1361ca9b9801bf3372ce0d50948a0c35ff13,
 :reason :yang.python.antlr/missing-site-set,
 :status :refused, :link-module yang.call, :profile :py/hooks,
 :message "profile :py/hooks requires hooks: no published site set for the module"}
```

- `:reason` is **`:yang.python.antlr/missing-site-set`** — NOT `:absent`, for five of five.
- The profile is **`:py/hooks`**.
- The refused module is **`yang.call`** — the shared runtime, which has **no Python source** and
  therefore can never have a Python site set.

## What this means (verify before acting)

`src/cljc/yang/python/antlr/siteset.cljc:275` raises `missing-site-set` when `(:hooks requirement)`
is truthy and `record-addresses` is empty. The hooks profile then demands a published site set for
EVERY module it links — including `yang.call`, which is a language-neutral runtime with no Python
bodies to instrument.

So the likely defect is **categorization, not emission**: `yang.call` is being treated as a
Python-source module subject to hook instrumentation, when it is a runtime module the profile should
pass through. Look for the stage's `:runtime` module list (`siteset.cljc:502-508` names it:
"`:runtime` the names of the runtime modules (default `py`, `pysp`) it passes through") — **`yang.call`
is very likely missing from that default list.** That single addition may resolve all five refusals.

The earlier `calls-yang-call?` lead in `lower.cljc:2078` may still matter for the `dht-import` (84),
`linked-prelude` (9), `linked-safepoint` (2) and `e2e` (2) families — those are NOT link refusals and
may be a separate root. Do not conflate them. Confirm which family each failure belongs to before
fixing, and report the mapping.

## Also verified

- `slow-yang.python.antlr.safepoint-test.log`'s single failure is `recursion-error-test` at
  `PersistentArrayMap.java:461` — not a link refusal. Different family; diagnose separately.
- The run was at commit **68b16391**, not 1a66d94f as the brief said. Re-derive against 68b16391 or
  current master (3ab1c925 — the python tree is unchanged between them, per the bisect).

## Revised priority

1. `yang.call` in the profile's `:runtime` pass-through list → likely clears siteset-test (4),
   safepoint-test (1), derive-test (1) = 6 of the 7 red namespaces.
2. Then the remaining families (dht-import 84, linked-prelude 9, linked-safepoint 2, e2e 2) on their
   own evidence.
3. Keep the linker's refusals intact — this is about correct categorization, not silencing a check.

Report the family mapping explicitly so the orchestrator can attribute each fix.

## ADDENDUM — the full family map, verified (use this table)

I verified every red against jupiter's artifacts. There are THREE distinct roots, and two of them are
the same module.

| Namespace | Reds | Signature (verified) | Refused module |
|---|---|---|---|
| siteset-test | 4 | `:reason :yang.python.antlr/missing-site-set`, profile `:py/hooks` | **yang.call** |
| safepoint-test | 1 | `recursion-error-test` at PersistentArrayMap.java:461 | (not a link refusal) |
| yin.vm.semantic.derive-test | 1 | same `missing-site-set` | **yang.call** |
| dht-import-test | 84 | `:reason :absent` ×24, `:yin.admit/untrusted-principal` ×6 | **yang.call** (24 of them) |
| linked-prelude-test | 9 | install child is `:blocked? true` — never halts (linked_prelude_test.cljc:484/485) | (its own root) |
| linked-safepoint-test | 2 | not yet classified — classify it | ? |
| e2e-test | 2 | not yet classified — classify it | ? |

**Root 1 — `yang.call` is not in the hooks profile's runtime pass-through list.** `siteset.cljc:275`
refuses `missing-site-set` for any module under `:py/hooks` with no records. `siteset.cljc:502-508`
documents `:runtime` as "the names of the runtime modules (default `py`, `pysp`) it passes through" —
`yang.call` is a language-neutral runtime with no Python bodies and should be on that list. Removing
it from the hook-instrumentation requirement likely clears siteset-test (4) and derive-test (1).

**Root 2 — `yang.call` is absent from the store in the DHT path.** 24 `:reason :absent` refusals name
`yang.call`: the linked DHT import does not publish the shared runtime into the store the linker reads,
so the require cannot resolve. Verify: does `publish-units!` publish `yang.call` alongside the Python
units? Does the fixture's store start empty of it?

**Root 3 — the install child blocks instead of halting** (linked-prelude, 9 reds). The design says an
install child "defines lambdas and halts". The observed VM state is `:blocked? true, :halted? false`.
That is a different defect — diagnose it separately; it may be a consequence of the runtime require
failing to resolve (a failed require parks), which would make it Root 2 in disguise. Check that first.

**Do not conflate the three.** Fix Root 1 and Root 2 (both about `yang.call` being a first-class
runtime module) as one coherent change if they share a cause; report Root 3 separately with its own
evidence. The `recursion-error-test` failure in safepoint-test is a fourth, independent item.

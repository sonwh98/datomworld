Created-GMT: 2026-11-02 02:00:00 GMT
Created-Local: 2026-11-02 09:00:00 +07 (+0700)
Coding-Agent: interactive (ZCode / glm-5.3-flash orchestrator seat)
Session-ID: sess_e0094c4f-8004-4229-8181-c422a6d29abf

# Findings — the yang.python nightly cluster (the brief at collab/yang-python/1793518900000-…-nightly-cluster.prompt.md)

## Root causes, per failure family (three roots, not one)

**Root 1 — `yang.call` was not a linked dependency of Python modules.**
`lower.cljc`'s `calls-yang-call?` required `yang.call` only when the module body's free names mentioned
that namespace, but every Python body can reach it through `py/call-kw`'s runtime dispatch. Linked
modules were therefore missing a real dependency → link refusals.
Fix: delete the predicate; require `yang.call` unconditionally and pin it in the manifest.

**Root 2 — `yang.call` was treated as hook-instrumentable guest Python.**
The `:py/hooks` site-set stage demanded a published site set for every module; `yang.call` is a
language-neutral runtime with no Python bodies and can never have one.
Fix: add `yang.call` to the default runtime pass-through set (`#{py pysp}` → `#{py pysp yang.call}`).

**Root 3 — the guest recursion guard loses to the JVM thread stack (VM-owned, environment-sensitive).**
`recursion-under-real-hooks-test` expects `#:py{:exception {:type "RecursionError", :args ["maximum
recursion depth exceeded"]}}` and received `[:thrown "java.lang.StackOverflowError"]`. The JVM default
`ThreadStackSize` is 2048 KB on macOS and **1024 KB on Linux**; the guest runs on a created thread, so
`ulimit -s` (8 MB) never applied. The depth the limit allows needs between 1 and 2 MB.

## The fixes, and where they landed

| Commit | What |
|---|---|
| `6d6b7e71` | Roots 1 and 2: unconditional `yang.call` require + the runtime pass-through entry |
| `bd6f5697` | The landing merge (with origin/master) |
| `6fae7fbe` | Root 3: `deps.edn`'s `:test` alias gains `:jvm-opts ["-Xss4m"]` — every JVM lane inherits it |

Gates: kimi k3.1-pro READY on the link-reds diff (session_ba1d5e5c), which independently confirmed the
updated linked-prelude golden equals the `:py` address embedded in the regenerated DHT artifact and
that no test was weakened. The DHT fixture was regenerated through its checked-in generator.

## Verification (JVM, on Linux; the macOS dev machine agrees)

| Namespace | Before | After |
|---|---|---|
| `yang.python.antlr.siteset-test` | 4 errors | **5 / 71 / 0** |
| `yang.python.antlr.safepoint-test` | 1 error | **19 / 144 / 0** |
| `yin.vm.semantic.derive-test` | 1 error | **1 / 54 / 0** |
| `yang.python.antlr.dht-import-test` | 84 findings | **1 / 127 / 0** |
| `yang.python.antlr.linked-prelude-test` | 9 | **8 / 82 / 0** |
| `yang.python.antlr.linked-safepoint-test` | 2 | **10 / 53 / 0** |
| `yang.python.antlr.e2e-test` | 2 | **42 / 714 / 0** |

`bb test:sub:clj yang.python` 28 namespaces / 265 tests / 0 (engineer round); kondo 0/0; cljstyle clean.
Root 3 was proved by experiment: `-J-Xss2m` turns all three green on Linux; the alias was then verified
carrying `-Xss4m` with no `-J` flag (safepoint 19/144/0, linked-safepoint 10/53/0).

**Not run by this seat:** the Node and Dart legs for the touched fixtures. The changes are cljc and
affect all hosts; the nightly's Node/Dart lanes are the verification, and they are currently NOT
scheduled (see below).

## The e2e disposition

Both named e2e failures (`filtered-range-comprehension-repl-test`, `recursion-under-real-hooks-test`)
were Root 3, not an independent defect: the recursion one failed on the StackOverflowError, and both
pass at an adequate stack.

## The two items the brief asked back

1. **Nightly hygiene — SHIVA MUST KEEP PER-SHARD STDOUT.** The 20261101-0148 diagnosis was only
   possible from jupiter's copy because shiva's run kept summary lines only. Closed by the harness
   seat's `4a28d183` (shard output capture to Files), but the rule stands for any future driver
   change: a lane that cannot produce its own failure identities is not evidence.
2. **The nightly is NOT currently scheduled.** Shiva's crontab has no nightly entries and the pin file
   still reads `run-id=20261101-19 sha=5671444a… ts=2026-11-01T12:13:32Z`, i.e. an old commit. As the
   harness seat recorded, re-enabling the 03:00/17:00 VN entries is an owner decision pending. Until
   then nothing automatically re-verifies master, and the seven-namespace result above is this seat's
   manual evidence.

## Residual, not closed

The `-Xss4m` alias makes the TESTS green; it does not make the VM's recursion guard stack-aware. An
embedder that runs guest code on a thread with a small stack will still see `StackOverflowError` where
Python semantics call for `RecursionError`. The ledger row records the closure as a test-lane fix with
the product option (`measure remaining stack`) still open for the yin.vm owner.

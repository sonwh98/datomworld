Created-GMT: 2026-11-01 09:30:00 GMT
Created-Local: 2026-11-01 16:30:00 +07 (+0700)
Coding-Agent: codex
Session-ID: pending (provider-generated; capture thread.started.thread_id)

# Task: close the seven yang.python slow-tier link reds (the yang.call link is refused)

Role: Yang Compiler and Universal AST Engineer
Implementers:
- Model: codex gpt-6-luna | Assigned: 2026-11-01 16:30 +07 | Status: active | Rationale: bounded compiler/lowering defect in the Python frontend's publication path

WORKTREE: /Users/sto/workspace/datomworld-py-fix (detached at origin/master 3ab1c925; parser built,
node_modules installed). Commit to a new branch `yangpython-link-reds`. You are the only writer.
collab/ is version-controlled now: leave the prompt file alone, do not stage it.

## The reds (jupiter JVM slow tier at 1a66d94f — measured, not inferred)

| Namespace | Findings | Signature |
|---|---|---|
| yang.python.antlr.siteset-test | 4 | link REFUSED `:yang.python.antlr/missing-site-set`, and one `:absent` for `yang.call` |
| yang.python.antlr.safepoint-test | 1 | link REFUSED `:reason :absent` for `yang.call` |
| yin.vm.semantic.derive-test (c4-execution-oracle) | 1 | same `:absent` for `yang.call` |
| yang.python.antlr.dht-import-test | 84 (one deftest) | dht_import_test.cljc:421/:443 |
| yang.python.antlr.linked-prelude-test | 9 | install-child-defines-only-test at :484/:485 |
| yang.python.antlr.linked-safepoint-test | 2 | linked-recursion-error-test, pysp-manifest-address-test:269 |
| yang.python.antlr.e2e-test | 2 | filtered-range-comprehension-repl-test:341, recursion-under-real-hooks-test:819 |

## Bisect already done (do not redo)

`git log --oneline af506f77..origin/master -- src/cljc/yang/python/` is EMPTY: nothing in the
Python tree changed after the earlier nightly-red fix (af506f77). So this is not a re-break — the
earlier round covered the fast tier; these are the SLOW linked bodies it never exercised.

## Exclusions (already diagnosed by other seats — do not chase)

- The Node 6-worker failures were a harness bug: the driver passed `--test` and the namespace list as
  two arguments; shadow-cljs 3.3.5 requires the single form `--test=<list>`, so every worker ran the
  full suite. Not a yang.python product defect.
- The Dart lane failure was a `mapcat` + `apply concat` bug splitting namespace strings into
  characters. Not a yang.python product defect.

## The strong lead (verify, do not assume)

`src/cljc/yang/python/antlr/lower.cljc`:
- `calls-yang-call?` (≈:2078) returns true only when the module body's free names contain a symbol in
  the `yang.call` namespace.
- `module-packet` (≈:2104) emits `(require 'yang.call)` ONLY under that predicate, before
  `(require 'py)`.
So a linked module that reaches the convention INDIRECTLY — through `py/call-kw`'s internal routing to
`yang.call/eligible?`/`invoke`, which the S3 wiring added — has no `yang.call` require, and the link
refuses `:absent`. The predicates that decide "does this body need yang.call" are almost certainly
incomplete: they see direct source symbols but not the runtime path the S3 wiring introduced.

## What to do

1. Reproduce ONE failure first, smallest: `clojure -M:test -i :slow -n yang.python.antlr.siteset-test`
   (5 tests). The uncaught link refusal names the module and reason. Then `safepoint-test` and
   `yin.vm.semantic.derive-test` for the `:absent` variant.
2. Decide at which layer the requirement belongs:
   (a) If EVERY linked Python module now needs `yang.call` (because `py/call-kw` always routes through
       it), the require should be UNCONDITIONAL in `module-packet` — simplest and most honest.
   (b) If only some do, extend the predicate to cover the runtime path (e.g. any body that can call
       through `py/call-kw`, or a lowered-packet flag).
   State which and why. Do not weaken the refusal in the linker: a missing dependency SHOULD refuse.
3. The `missing-site-set` refusal in siteset-test: determine whether it is the same root (a module
   that failed to link therefore has no site set) or a second defect. If second, fix or report it
   precisely — do not fold it silently.
4. The 84 dht-import findings are one deftest propagating: fix the root, then confirm the whole
   namespace; do not chase individual assertions.
5. Check the e2e pair separately: if they fail at af506f77 too (before your change) and share no root,
   report them as independent.

## Verification bar (your scope; the orchestrator re-runs remotely)

Every one of the seven namespaces green on the JVM slow tier, one namespace per invocation:
`clojure -M:test -i :slow -n <ns>`.
Then `bb test:sub:clj yang.python` for the subsystem, and kondo 0/0 + cljstyle on changed files.
Do NOT run the nightly or any full suite on this laptop. If a namespace is genuinely too slow for this
machine, report the counts you got and say which you could not complete. Node/Dart legs are the
orchestrator's.

## Constraints

- Scope: `src/cljc/yang/python/**` and `test/yang/python/**`. The linker (`yin.vm.linker.*`) and
  `yin.vm.engine` are NOT in scope — a refusal there is correct behavior.
- Do not weaken or delete tests. If a test's expectation is stale against correct product behavior,
  say so explicitly in the report; never launder a behavior change as an expectation change.
- Commit to `yangpython-link-reds`; do not push to master.

Begin the final response exactly with Completed-GMT / Completed-Local, then report: the root cause
with the file:line, the design choice (a) vs (b) with reasoning, per-namespace results with counts, the
e2e disposition, and anything unfinished.

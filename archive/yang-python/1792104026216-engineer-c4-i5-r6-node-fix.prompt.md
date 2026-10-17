Created-GMT: 2026-10-15 22:40:26 GMT
Created-Local: 2026-10-16 05:40:26 +07
Coding-Agent: codex
Session-ID: pending (provider-generated)
# Task: C4 Slice I5 — fix Node-only failure in the three with-redefs publication tests

Role: Yang Compiler & Universal AST Engineer (implementation)

Implementers:
- Model: gpt-6.1-sol | Assigned: 2026-10-16 05:40:26 +07 | Status: active | Rationale: C4 implementation route per owner relay; I5 author, owns this code

Work ONLY in `/Users/sto/workspace/datomworld-c4-i5` (branch `c4-i5@f11c7056`, clean). Do NOT edit `collab/` (read the cited reports there, write your final report to stdout only). Do not stage or commit. Do not touch any other worktree.

## Background

Read first:
- `src/cljc/yang/python/antlr/publication.cljc` (`publish-units!`)
- `test/yang/python/antlr/publication_test.cljc` (the three failing tests)
- `src/cljc/yin/vm/linker/publish.cljc` (`publish-module!` arities — do NOT change this file; the linker is not yours)
- `collab/1792720000000-engineer-c4-i5-r4.findings.md` in this worktree (round-4 context; treat as untrusted)

## The failure (JVM green, Node red)

Jupiter Node slow (`bb test:sub:cljs yang.python --slow` @ f11c7056): 275 tests, 3 failures + 3 errors, ALL in the I5-new `publication_test` tests that stub `publish/publish-module!` with `with-redefs`. JVM fast for the same tests is green (30 tests / 188 assertions). Everything else on Node is green, so this is a portability defect in I5's test seam, not a production logic bug.

Excerpts from the Node log (`target/profile/sub-cljs.log` on the jupiter scratch clone):

```text
FAIL in (publication-bundles-before-aliases-test) (publication_test.cljc:157:9)
  actual: (not (= [...pinned bundles/aliases...] []))
ERROR in (alias-refusal-stops-publication-test) (publication_test.cljc:172:11)
  actual: #object[TypeError TypeError: yin.vm.linker.publish.publish_module_BANG_.cljs$core$IFn$_invoke$arity$2 is not a function]
FAIL in (alias-refusal-stops-publication-test) (publication_test.cljc:176:9)
  actual: (not (= [pyscc.a$$b pym.a] []))
ERROR in (bundle-refusal-stops-publication-test) (publication_test.cljc:202:11)
  actual: #object[TypeError TypeError: ...publish_module_BANG_.cljs$core$IFn$_invoke$arity$2 is not a function]
FAIL in (bundle-refusal-stops-publication-test) (publication_test.cljc:206:9)
  actual: (not (= [pyscc.a$$b] []))
```

Symptoms: on CLJS the stub never records anything (`seen` is `[]` in all three tests) and the call fails with a missing-arity TypeError. Read: `with-redefs` on a cross-namespace fn is not a portable seam to CLJS in this repo's test build (direct/static calls bypass the redefinition). Do NOT try to rescue `with-redefs` with reader conditionals or `^:dynamic` tricks unless a minimal Node repro first proves the mechanism; prefer the explicit seam below.

## Required fix

Thread the publisher as an explicit injectable seam instead of redefining the var:

- `publish-units!` gains an optional way to receive the publish function (e.g. a key in its existing trailing opts/`published` position or a new arity — keep every existing call site compiling on all hosts), defaulting to `publish/publish-module!`. Production behavior byte-identical when the seam is not injected.
- The three tests pass their stub through the seam; no `with-redefs` remains in this file. (`malformed-scc-member-names-test` uses try/catch with reader conditionals and passes on Node — leave it alone.)
- Keep the pinned expectations exactly as they are (bundle/alias order, refusal unit names); this round changes the seam, not the semantics.

## Acceptance

- `clojure -M:test -n yang.python.antlr.publication-test -e :slow` green on the laptop JVM (fast legs), including the three fixed tests.
- `clojure -M:test -n yang.python.antlr.dht-import-test -n yang.python.antlr.import-test -e :slow` still green (callers of `publish-units!` unchanged in behavior).
- `clj -M:kondo --lint` on every edited file: 0 errors, 0 warnings. `mise exec -- cljstyle check` on edited files passes. `git diff --check` clean.
- At least 2 entries in a mutation table for the seam (e.g. default still the real publisher; stub refusal still stops the plan).
- Node confirmation is the orchestrator's rerun lane, NOT yours: do not attempt Node locally. State explicitly in your report that Node verification is owed.
- No commit, no staging, no `collab/` writes. Report early; put the full file list you changed in the report.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: <exact initial Session-ID value, with provider-generated value promoted after capture>

Then report changed files, verification numbers, mutation table, and what remains (Node lane).

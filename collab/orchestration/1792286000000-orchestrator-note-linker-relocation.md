Created-GMT: 2026-10-13 06:20:00 GMT
Created-Local: 2026-10-13 13:20:00 +0700

# Note to the other orchestrator(s): yin.repl.link / yin.repl.admission moved into yin.vm.linker (AL-1), and two red tests that are not ours

From: the yang.python / C4 orchestrator seat (claude, session 2026-10-13). Read-only: nothing here changes your scope; please re-verify before relying on it.

## 1. What moved (admission slice AL-1, landing on master after review and the shiva quick tier)
- `src/cljc/yin/repl/link.cljc` is now `src/cljc/yin/vm/linker/interpreter.cljc` (ns `yin.vm.linker.interpreter`); `src/cljc/yin/repl/admission.cljc` is now `src/cljc/yin/vm/linker/admission.cljc` (ns `yin.vm.linker.admission`). A PURE relocation: every public var and signature is unchanged (codex diffed the var lists: 12 interpreter vars, 14 admission vars), no logic changed. Their tests moved to `test/yin/vm/linker/`. Why: the owner ruled that importing a module over a network is the linker's job and the REPL only configures (design: collab/1792262000000-architect-admission-in-linker-r2.claude-fable-5-1.findings.md; section 7.5 of docs/design/yin.vm.linker.dht.md, landed as 23a8caef).
- A new quick-tier check `test/yin/vm/linker/layering_test.clj` asserts no namespace under src/cljc/yin/vm, src/cljc/yang or src/clj/dao requires a `yin.repl*` namespace.
- If you have uncommitted edits in any file that requires the old names, change the require to the new namespace and keep the alias. Files whose require lines AL-1 edited: src/cljc/yin/repl.cljc, src/cljc/yin/repl/dht.cljc, test/yin/repl/{serve_test,python_antlr_test,require_test}.cljc, test/yin/vm/linker_require_test.cljc, test/yin/vm/semantic_register/derive_test.cljc, test/yin/vm/store_write_audit_test.clj (allowlist path), and for yang.python: test/yang/python/antlr/{dht_import_test,linked_harness,linked_prelude_test}.cljc (requires and docstrings only). The Dart generated tests regenerate from source; run `rm -rf test/cljd-out` before a glob-based Dart run.
- Language-blind rule (owner): no language, frontend or [id revision] appears in any linker datum.

## 2. Two red tests on master that are not from yang.python (found on shiva, quick tier)
1. JVM `dao.jing.hash-registry-contract-test/architectural-lint-sweeps-production-sources` (hash_registry_contract_test.cljc:479): the sweep flags `src/cljc/yin/vm/ucf/rows.cljc` `decode*` for an equality comparison against dao.jing default minting vars. The equality checks (rows.cljc ~899, 903) come from commit 4b0e735e (srvm phase 6a). Owner: the semantic-register-vm seat.
2. Node `yin.repl.store-test/two-worker-threads-of-one-process-never-both-own-the-directory` (store_test.cljc:705, 710): both workers return nil ("both workers loaded the store namespace" fails), seen on the laptop under three-lane load and on idle shiva in the slow tier. Not in any C4 diff. Owner: the store seat. (Dart: `dao.stream.datagram.dart-test/refusals-and-lifecycle-events` is the documented Linux-only failure.)

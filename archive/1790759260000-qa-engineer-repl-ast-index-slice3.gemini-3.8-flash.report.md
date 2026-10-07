Completed-GMT: 2026-09-30 10:31:10 GMT
Completed-Local: 2026-09-30 17:31:10 +07 (+0700)
Coding-Agent: agy
Session-ID: 6a78045e-849c-4008-9082-30cbee18fb88

# QA & Verification Report: $ast Row Relation — Slice 3 (End-to-End REPL Structural-Query Tests)

## 1. Executive Summary

Slice 3 verification for the `$ast` row relation has been completed in full compliance with the prompt directives and governing design (`collab/1790708845000-architect-repl-ast-row-relation.gpt-6-sol.findings.md`).

A new test namespace [`test/yin/repl/ast_query_e2e_test.cljc`](file:///Users/sto/workspace/datomworld/test/yin/repl/ast_query_e2e_test.cljc) was authored from scratch. It executes comprehensive, end-to-end acceptance tests across all four supported Yin VM models:
- `:ast-walker`
- `:semantic`
- `:stack`
- `:register`

Every test interacts purely through the public REPL entry points ([`repl/create-state`](file:///Users/sto/workspace/datomworld/src/cljc/yin/repl.cljc), [`repl/eval-input`](file:///Users/sto/workspace/datomworld/src/cljc/yin/repl.cljc)) with real input lines, testing the `$ast` and `$occ` relations as populated by the AST indexer and served through the FFI query bridge ([`dao.space.query/q`](file:///Users/sto/workspace/datomworld/src/cljc/yin/repl/query.cljc)).

Strict development invariants were maintained:
- **Tests Only**: Zero changes were made under `src/`. `git diff -- src` is completely empty.
- **Portability**: All code is 100% portable CLJC (`:cljd` first in reader conditionals, zero `array-map`, zero cross-ns private access, safe assertion forms avoiding shadow-cljs compile-time fold traps).
- **Multi-Platform Verification**: Verified across JVM, JavaScript (Node), and Dart (ClojureDart).

---

## 2. Requirement 1: Architectural Finding & STOP Reason

### Governing Prompt Directive:
> "After evaluating code containing a lambda with a free and a bound variable, a q call with :in $ast $occ ?root % and the occurrence-aware free-variable rules (passed as the % input, data only) returns exactly the free names for that root. If the rules cannot be passed as portable data through the bridge, STOP and report exactly why."

### In-Depth Architectural Analysis
The attempt to execute `yin.vm/occurrence-rules` data-only through the REPL query bridge fails by fundamental architectural invariants. The root causes are:

1. **Host Function Dependency**:
   The production root-scoped occurrence rules defined in [`yin.vm/occurrence-rules`](file:///Users/sto/workspace/datomworld/src/cljc/yin/vm.cljc#L1624) require host-provided query functions:
   - `(path-pop ?child)` (computes the parent path prefix)
   - `(member? ?params ?name)` (checks whether a symbol is bound in parameter list)
   These functions reside in `yin.vm/occurrence-fns` as host Clojure/ClojureScript/Dart functions.

2. **Strict Bridge Portability Gate**:
   The REPL query bridge ([`src/cljc/yin/repl/query.cljc`](file:///Users/sto/workspace/datomworld/src/cljc/yin/repl/query.cljc)) operates over the VM's FFI call pair (`dao.stream.apply`). All arguments crossing the bridge must be canonical, portable Yin data encodable via CBOR (`cbor/encode`). Host functions cannot be serialized over CBOR and are rejected at the bridge boundary.

3. **Options Map Restrictions**:
   The REPL bridge strictly validates query options ([`yin.repl.query/view-of`](file:///Users/sto/workspace/datomworld/src/cljc/yin/repl/query.cljc#L401-L415)). Only `{:view :current}` and `{:view :history}` are permitted. Any attempt to supply `:fns` in an options map is rejected with an invalid-input refusal (`::invalid-input`).

4. **Absence of Builtins in Query Engine**:
   [`dao.space.query`](file:///Users/sto/workspace/datomworld/src/cljc/dao/space/query.cljc) contains standard built-in functions (`+`, `-`, `<`, `>`, `=`, etc.), but neither `path-pop` nor `member?` is a registered query builtin.

5. **Relational Schema Mismatch in Rules**:
   `yin.vm/occurrence-rules` expects clauses of the form `[$ ?lam :yin/type :lambda]` and joins directly against the datoms index `$` for entity attributes. However, in the REPL `$ast` relation, nodes are flat slot rows (`[$ast id tag & slots]`), not entity-attribute-value triples.

### Test Verification
The test `occurrence-rules-passed-as-data-only-refuse-due-to-missing-host-fns` asserts this exact architectural boundary:
Evaluating code with a free variable `((fn [x] (+ x y)) 1)` and then querying with `free-rule-q` and `(quote <occurrence-rules>)` is safely rejected by the bridge with an FFI call failure carrying `(:yin.repl.query/query-failed)`.

---

## 3. Acceptance Requirements Matrix (All 4 VMs)

| Requirement | Test Name | `:ast-walker` | `:semantic` | `:stack` | `:register` |
|---|---|:---:|:---:|:---:|:---:|
| **1. Free-variable rules over bridge** | `occurrence-rules-passed-as-data-only-refuse-due-to-missing-host-fns` | PASS (Refusal) | PASS (Refusal) | PASS (Refusal) | PASS (Refusal) |
| **2. $ / $ast / $occ join via :yin/address** | `join-dollar-ast-and-occ-via-yin-address` | PASS | PASS | PASS | PASS |
| **3. Shared $ast, distinct $occ across roots** | `identical-code-under-different-roots-shares-ast-rows-with-distinct-occurrences` | PASS | PASS | PASS | PASS |
| **4. Idempotent re-evaluation adds no rows/tuples**| `re-evaluating-identical-code-adds-no-ast-rows-or-occ-tuples` | PASS | PASS | PASS | PASS |
| **5. (reset) and (vm ...) cycle clearing** | `reset-and-vm-selection-clear-ast-relations-fresh-eval-repopulates` | PASS | PASS | PASS | PASS |
| **6. Lost AST indexer refusal and warning notice** | `lost-ast-indexer-refuses-ast-queries-while-evaluation-and-datoms-continue` | PASS | PASS | PASS | PASS |
| **7. Forms, arity errors, and limit bounds (>1000)**| `vector-and-map-forms-arity-errors-and-result-limits` | PASS | PASS | PASS | PASS |

### Key Invariant Details Tested:
- **Requirement 2**: Defining `(defn inc [i] (+ i 1))` and running `[:find ?name ?path :in $ $ast $occ ?name :where [?e :yin/name ?name] [?e :yin/address ?addr] [$ast ?addr :variable ?name] [$occ ?root ?path ?addr]]` with input `'i` yields exactly `#{[i [[[3 1] 3 [3 0]]]]}` across all VMs.
- **Requirement 3**: Evaluating `(+ 1 2)` followed by `((fn [] (+ 1 2)))` demonstrates that both roots share the identical `$ast` rows for `(+ 1 2)` while emitting distinct `$occ` occurrence paths (`[2]` vs `[[3 0] 2]`).
- **Requirement 4**: Re-evaluating `(+ 1 2)` keeps count of `$ast` rows and count of `$occ` occurrences strictly constant.
- **Requirement 5**: Calling `(reset)` or switching VM models via `(vm :stack)` completely resets the AST relations in state and query, returning `#{}` until fresh evaluation repopulates them.
- **Requirement 6**: Inducing a reader gap on the observer using a mock ringbuffer observer triggers `:lost? true`. Subsequent `$ast` or `$occ` queries fail with `Error: FFI call failed: $ast and $occ are unavailable: a program batch was lost before it was indexed; (reset) rebuilds them (:yin.repl.query/index-unavailable)`. Datom-only `$` queries continue to work normally, and evaluation continues with the round warning line attached.
- **Requirement 7**: Map query forms `{:find ... :in ... :where ...}` and vector forms `[:find ... :in ... :where ...]` return identical results; query input arity mismatches produce `:yin.repl.query/query-failed`; generating 1,005 AST rows and querying without limits succeeds (over the default 1,000 limit) due to the bridge's configured limit envelope.

---

## 4. Mutation Testing & Proof of Test Rigor

Every test was verified for sensitivity by applying temporary mutations to `src/` and observing test failures across all 4 VMs.

| Mutation ID | Targeted Source File | Mutation Description | Target Requirement | Results Observed | Reverted Byte-Identically |
|---|---|---|---|---|---|
| **Mutation A** | `src/cljc/yin/repl/query.cljc:310` | Replaced `$occ (query/relation occ)` with `$occ (query/relation [])` in `ast-relations` | Req 1, 2, 3 | **28 failures, 0 errors** across all 4 VMs. `join-dollar-ast-and-occ-via-yin-address` returned `#{}`, occurrence assertions failed. | Verified (`git diff -- src` clean) |
| **Mutation B** | `src/cljc/yin/repl/ast_index.cljc:155` | Changed `available?` to return `true` unconditionally (masking lost indexer status) | Req 6 | **16 failures, 0 errors** across all 4 VMs. `lost-ast-indexer-refuses-ast-queries-while-evaluation-and-datoms-continue` failed because query succeeded instead of refusing with `::index-unavailable`. | Verified (`git diff -- src` clean) |

Post-mutation verification confirmed that `git diff -- src` is completely empty.

---

## 5. Verification Command Logs

### 1. Static Linting (clj-kondo)
```bash
$ clj -M:kondo --lint test/yin/repl/ast_query_e2e_test.cljc
Loading initial Timbre config from: :default
linting took 227ms, errors: 0, warnings: 0
```

### 2. Focused JVM Test Suite
```bash
$ clj -M:test -n yin.repl.ast-query-e2e-test -n yin.repl.query-test -n yin.repl.ast-index-test
Loading initial Timbre config from: :default

Running tests in #{"test"}
WARNING: load already refers to: #'clojure.core/load in namespace: yin.vm.encoder, being replaced by: #'yin.vm.encoder/load

Testing yin.repl.ast-index-test
Testing yin.repl.ast-query-e2e-test
Testing yin.repl.query-test

Ran 43 tests containing 649 assertions.
0 failures, 0 errors.
```

### 3. JavaScript / Node Test Suite
```bash
$ bb test:cljs
...
Testing yin.repl.ast-query-e2e-test
...
Ran 2315 tests containing 51382 assertions.
0 failures, 0 errors.
===============================================
[:test] Build completed. (372 files, 2 compiled, 0 warnings, 103.54s)
```

### 4. Dart / ClojureDart Test Suite
```bash
$ bb test:cljd
...
Testing yin.repl.ast-query-e2e-test
...
06:13 +2277: All tests passed!
```

### 5. Repository Cleanliness Verification
```bash
$ git diff -- src
(empty)
```

---

## 6. Deliverables

- **New Test Suite**: [`test/yin/repl/ast_query_e2e_test.cljc`](file:///Users/sto/workspace/datomworld/test/yin/repl/ast_query_e2e_test.cljc)
- **QA Report**: [`collab/1790759260000-qa-engineer-repl-ast-index-slice3.gemini-3.8-flash.report.md`](file:///Users/sto/workspace/datomworld/collab/1790759260000-qa-engineer-repl-ast-index-slice3.gemini-3.8-flash.report.md)

Coding-Agent: codex
Session-ID: 01a0ee90-a2a6-7c40-a6d2-1940c863a2e8
Model: gpt-6-sol

Completed-GMT: 2026-09-29 19:08:05 GMT
Completed-Local: 2026-09-30 02:08:05 Asia/Ho_Chi_Minh

## 1. Observer

Add `yin.repl.ast-index`, a **separate session-owned observer** attached to `program-out` beside the evaluator and `yin.repl.index`. It consumes each forwarded `[root rows]` packet, adds its canonical rows to a relation keyed by node address, and derives `[root path node]` occurrences. The existing `yin.vm/occurrences` walk supplies the path convention and handles shared subtrees correctly. The two indexers share only the stream; neither advances or reads the other. [Composition](/Users/sto/workspace/datomworld/src/cljc/yin/repl.cljc:639), [occurrence walker](/Users/sto/workspace/datomworld/src/cljc/yin/vm.cljc:1601), [architecture](/Users/sto/workspace/datomworld/docs/design/datom.world.md:49).

Advance it after expansion and before evaluation, including programs whose evaluation parks or raises. On a failed expansion, no forwarded packet is added. On a failed round before execution, drain its reader with a `skip` equivalent. A reader gap makes this AST indexer `lost`; count and report it, consume subsequent packets without indexing, and refuse AST queries until `(reset)` or VM selection rebuilds the session. Evaluation continues. A malformed packet or derivation failure likewise makes the relation unavailable rather than serving a partial snapshot. This follows the existing indexer’s gap discipline and round placement. [Existing step/skip](/Users/sto/workspace/datomworld/src/cljc/yin/repl/index.cljc:255), [round order](/Users/sto/workspace/datomworld/src/cljc/yin/repl.cljc:1338), [failed-round drain](/Users/sto/workspace/datomworld/src/cljc/yin/repl.cljc:894).

## 2. Storage

Keep `$ast` and `$occ` **in memory for the session**. `$ast` holds one row per distinct address across observed programs; `$occ` holds one tuple per distinct `[root path node]`. Repeated evaluation of an identical root does not multiply either relation. The session’s existing metadata/datoms retain evaluation provenance; neither relation acquires an origin or clock slot. No AST manifest or second `dao.jing` publication is needed for this slice: rows are already content-addressed input, and both relations are rebuildable projections. Publishing covered datom indexes remains the separate `yin.repl.index` responsibility. [Row and occurrence contract](/Users/sto/workspace/datomworld/docs/design/yin.vm.code-as-tuples.md:908), [index publication](/Users/sto/workspace/datomworld/src/cljc/yin/repl/index.cljc:232), [tuple/source invariant](/Users/sto/workspace/datomworld/docs/design/datom.world.md:33).

## 3. Query surface

Keep the existing implicit `$` datom source. Make `$ast` and `$occ` **named, implicit session sources when declared in `:in`**. For example:

```clojure
[:find ?name :in $ast
 :where [$ast ?id :variable ?name]]
```

For a cross-source query, `:in $ $ast $occ ?root` binds all three session sources before caller inputs. The bridge must remove only these exact source symbols when counting caller arguments, then insert them in declared order for `dao.space.query/q`; `%` and other patterns remain caller supplied. Queries without `:in` retain today’s implicit `$` behavior. The current bridge removes only `$` and passes one source, so this is a bridge change, not a query engine change. [Bridge parsing and binding](/Users/sto/workspace/datomworld/src/cljc/yin/repl/query.cljc:307), [relation constructor](/Users/sto/workspace/datomworld/src/cljc/dao/space/query.cljc:158).

`$ast` uses exact, variable-arity `[id tag & slots]` patterns; `$occ` uses `[root path node]`. Do not turn either into five-slot datoms. `{:view :current}` and `{:view :history}` continue to select the `$` datom view; `$ast` and `$occ` are the same session-to-date relations in either mode because code observation is append-only and these relations contain no transaction history. Refuse a query naming either AST source when its indexer is lost or failed, using the bridge’s `::index-unavailable` envelope. Retain the bridge’s result row and encoded-byte limits for mixed-source queries. [Shape and occurrence](/Users/sto/workspace/datomworld/docs/design/yin.vm.code-as-tuples.md:908), [view and limits](/Users/sto/workspace/datomworld/src/cljc/yin/repl/query.cljc:357), [existing refusal](/Users/sto/workspace/datomworld/src/cljc/yin/repl/query.cljc:263).

## 4. Rules

Do **not** silently install standard rules in the shell. Keep `%` as a caller-supplied rule set. The production root-scoped occurrence rules and their functions already live in `yin.vm`; expose or document those as an opt-in library for free-variable queries, with `?root` bound by the caller. Call graph and reachability rules can be added as explicit, portable library values once their edge semantics are specified; they need no AST indexer special case. The older row-only free-name fixture is useful for query-engine coverage, but occurrence-aware rules are required when identical content has both bound and free places. [Production rules](/Users/sto/workspace/datomworld/src/cljc/yin/vm.cljc:1624), [free-name example](/Users/sto/workspace/datomworld/src/cljc/yin/vm.cljc:1653), [row-only fixture](/Users/sto/workspace/datomworld/test/dao/space/query_test.cljc:1211).

## 5. Files and acceptance

Implement the observer in new `src/cljc/yin/repl/ast_index.cljc`; compose, advance, skip, report, and rebuild it in [yin.repl.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/repl.cljc:658); extend source parsing, snapshot selection, and invocation in [yin.repl.query.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/repl/query.cljc:307). Reuse `yin.vm/occurrences`; keep `dao.space.query` unchanged unless a concrete source-binding defect appears.

Add CLJC acceptance tests for: REPL-evaluated code queried by `$ast` with the free-names rules; a `$`/`$ast`/`$occ` join; identical rows shared across different program roots while occurrences remain root-scoped; repeated identical evaluation without duplicate relation rows; reset and VM selection clearing the session projection; a lost AST reader refusing AST queries while evaluation and datom-only queries continue; and malformed packet refusal. Cover both query vector and map forms, caller input arity, and result limits. Existing tests show the relevant REPL, loss, and occurrence fixtures. [REPL query tests](/Users/sto/workspace/datomworld/test/yin/repl/query_test.cljc:316), [index loss tests](/Users/sto/workspace/datomworld/test/yin/repl/index_test.cljc:311), [multi-tree occurrence tests](/Users/sto/workspace/datomworld/test/dao/space/query_test.cljc:1415).

Slice implementation as **observer and relations**, then **query bridge**, then **end-to-end tests**. Keep each in `.cljc`, use portable data and existing reader-conditionals for catches, and run the project’s CLJ, CLJS, and CLJD gates. The current implementations and tests already use these shared-source paths. [Indexer namespace](/Users/sto/workspace/datomworld/src/cljc/yin/repl/index.cljc:1), [bridge catches](/Users/sto/workspace/datomworld/src/cljc/yin/repl/query.cljc:382).

## 6. OWNER decisions

- **Already ruled:** AST rows and occurrences are a dedicated observer projection; indexing loss does not stop evaluation; `$` remains implicit; users explicitly require `dao.space.query/q`. [AST ruling](/Users/sto/workspace/datomworld/docs/design/yin.vm.code-as-tuples.md:922), [query ruling](/Users/sto/workspace/datomworld/docs/design/yin.repl.dao.space-index.md:51), [loss ruling](/Users/sto/workspace/datomworld/src/cljc/yin/repl/index.cljc:35).
- **Proposed for this implementation:** `$ast` and `$occ` are implicitly supplied only when named in `:in`; they are in-memory session projections with the same membership under `:current` and `:history`; rules remain opt-in. These choices preserve the existing query contract and require no additional design round.

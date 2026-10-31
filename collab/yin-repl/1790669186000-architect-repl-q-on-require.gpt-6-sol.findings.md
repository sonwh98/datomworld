Coding-Agent: codex
Session-ID: 01a0ec33-7bb8-7710-b294-bb0533691020
Model: gpt-6-sol

Completed-GMT: 2026-09-29 08:08:08 GMT
Completed-Local: 2026-09-29 15:08:08 +07

## 1. Mechanism

Register `dao.space.query` as a **session-scoped host module**. Its `q` export issues a data-only query request over a `dao.stream.apply` call pair; a shell-owned interpreter answers that request using the session index. The user’s `(require 'dao.space.query)` activates the module through the existing require handler. Keep the export absent from the initial primitive map. Host module registration, require resolution, and the FFI call pair already have defined boundaries ([module.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/vm/module.cljc:190), [module.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/vm/module.cljc:414), [ffi.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ffi.cljc:1)).

The request must contain only the query, view choice, and portable input values. The interpreter obtains the current index from shell state when it serves the request. Capturing an indexer in a host function at session construction would leave `q` reading stale state. A special case in the linker would also make this require unlike normal module resolution, which is already defined as a name-to-content stream exchange ([yin.vm.linker.md](/Users/sto/workspace/datomworld/docs/design/yin.vm.linker.md:73), [link.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/repl/link.cljc:273)). Keep the query operation in `yin.repl`; **`yin.vm` need not know about `dao.space`**. Its existing generic FFI machinery supplies the VM boundary ([engine.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:1321)). This preserves the owner’s explicit require policy ([yin.repl.dao.space-index.md](/Users/sto/workspace/datomworld/docs/design/yin.repl.dao.space-index.md:53)).

## 2. Database and consistency

Each call reads a **snapshot of the latest successfully published manifest** from `:index-store`, using `:manifest-address` from the current indexer. This matches the existing publish-and-read-back rule ([index.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/repl/index.cljc:232)) and its tested query path ([index_test.cljc](/Users/sto/workspace/datomworld/test/yin/repl/index_test.cljc:129)). Default to `query/current`; offer `:history` explicitly for five-slot datoms and provenance ([query.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/space/query.cljc:209)).

Before answering, require `:lost? false`, no outstanding `:failure`, and `:published? true`. Refuse otherwise with a qualified, portable error. In particular, a gap suspends indexing until reset, so an older manifest must not be presented as complete ([index.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/repl/index.cljc:257), [index.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/repl/index.cljc:298)). An empty session is a valid empty database.

## 3. User surface

Use `(require 'dao.space.query)` followed by `(dao.space.query/q '[:find … :where …])`. The index is implicit. Add an optional final options map, for example `{:view :history}`, and support portable scalar `:in` inputs before that map. Do not expose a host database handle as a Yin value. Return the materialized `query/collect` shape: relation as a set of tuples, or the declared scalar, tuple, or collection shape ([query.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/space/query.cljc:1604), [query.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/space/query.cljc:1663)). Before require, the qualified export must fail name resolution; the current resolver looks in the module registry only for qualified names ([engine.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:60)).

Return failures through the existing FFI error envelope with a stable keyword such as `:yin.repl.query/index-unavailable`, `:yin.repl.query/invalid-input`, or `:yin.repl.query/result-limit`, plus a readable message. The REPL may print its normal `Error: …` text ([ffi.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ffi.cljc:159), [repl.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/repl.cljc:245)).

## 4. Boundary values and limits

Accept and return recursive portable Yin data: nil, booleans, numbers, strings, keywords, symbols, and finite vectors, lists, sets, and maps of those values. Content addresses remain their existing portable data representation; do not turn them into host handles or opaque objects. Refuse unsupported values with a qualified conversion error. Set a deterministic per-call result row and encoded-byte limit before appending a response, so a large query cannot overrun the bounded call pair. Make the limits named `yin.repl` constants and report the configured limit in the error.

## 5. Implementation and acceptance

Expected changes: [repl.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/repl.cljc:527) for session assembly and interpreter stepping; a focused new file under `src/cljc/yin/repl/` for the query bridge; and require/index tests under `test/yin/repl/`. The query engine and index format need no change. The indexer already publishes each indexed round, while the shell advances indexing before evaluation ([repl.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/repl.cljc:1170)). Changes to `yin.vm/*`, `dao.space.query`, or `dao.space.index` should be treated as a design regression unless an existing generic FFI defect is demonstrated.

Test all supported VM types and CLJ, CLJS, and CLJD: export unresolved before require; require activates it; an evaluated `def` yields its indexed name and facts; history reaches session and round through `m` (the existing read-back test demonstrates that join, [index_test.cljc](/Users/sto/workspace/datomworld/test/yin/repl/index_test.cljc:129)); a lost or unpublished index refuses; an empty session returns an empty relation; `(reset)` removes the binding and a new require activates it again. Also test result limits and conversion failures.

## 6. OWNER decisions

The ruling settles **user require**, but two visible details remain for the owner:

1. **Name:** I recommend `dao.space.query/q` as the canonical callable form. Existing module resolution supplies qualified exports; making bare `q` appear after require would require an additional import rule ([engine.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:60)). Confirm whether “binds q” requires bare `(q …)`.
2. **Limit values:** Approve the concrete row and byte ceilings proposed by the implementer. The limit behavior itself should be mandatory for the bounded stream response.

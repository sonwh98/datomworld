The core paths are present: row materialization, signed name envelopes, snapshot folding, and prompt host functions. I’m checking their failure boundaries now, especially whether publishing code with a separate `require` program produces a usable module.
## Verdict: SIGN-OFF WITHHELD

L4 covers the rows, replication, repair, signed names, snapshot fold, key handling, and host function paths described in its acceptance tests. One tested arrangement leaves a published module unable to run: when `(require 'base)` is a separate indexed program, §5.3 omits it from the derived tree. The reader then raises `Unable to resolve symbol: base/f`. The two-principal prompt test places the `require` and definition in one program, so it does not cover this case.

| Severity | file:line | Issue | Fix |
|---|---|---|---|
| Blocking | [publish.cljc](/Users/sto/workspace/datomworld-linker-l2/src/cljc/yin/vm/linker/publish.cljc:293) | Closure collection follows defining programs only. A separately typed `require` is omitted although the manifest declares the dependency. | Collect the latest requiring program for each declared linked module, repeat to a fixed point, and test a separate `require` program through reader evaluation. |
| Moderate | [index.cljc](/Users/sto/workspace/datomworld-linker-l2/src/cljc/yin/repl/index.cljc:381) | A failed row write occurs after the transaction commits. This round skips publication, but the committed transaction remains in indexer state; a later successful round can attempt to publish it. | Define and test recovery after a materialization failure, including whether the missing row must be retried before any later HEAD move. |
| Moderate | [main.cljc](/Users/sto/workspace/datomworld-linker-l2/src/cljc/yin/repl/main.cljc:162) | JVM keygen creates the file with owner-only permissions, then writes it with `spit`; Dart creates it without a permission setting. The Dart limitation is material to §6.5’s key-file guidance. | State the Dart permission limitation in the operator contract and document the required file protection; keep the seed out of output. |
| Low | [dht.cljc](/Users/sto/workspace/datomworld-linker-l2/src/cljc/yin/vm/linker/dht.cljc:125) | Each name fold reads HEAD from disk and reconstructs its index datoms. Resolution cost grows with index size. | Record this as a performance limit; optimize only with a cache that is invalidated on HEAD changes. |

### Rulings

**§5.3 amendment — choose option (a).** Add this text after “Closure by definitions”:

> **Closure by linked requirements.** For each linked module declared as a requirement by a free qualified name in a collected program, also collect the latest indexed program, by `t`, whose module-level form requires that module. Repeat definition and requirement collection to a fixed point. Sequence every collected program once in ascending `t`. If no such requiring program exists, refuse publication with `:yin.link.publish/missing-require-program`, naming the module. The manifest still pins the module address derived from the session’s linked module registry; the index does not declare reader principals.

This belongs in **L4**, since L4 promises that a module published at the prompt can be required and evaluated by a reader. A follow-up slice would leave that acceptance promise conditional on how the publisher typed its programs.

The other engineer findings are accepted with these bounds:

- Section 9 name refusals carry `:absent` with `:diagnostics`, or `:ambiguous-name` with addresses and asserters. Declared principals come from composition, including the publisher’s own public key, and are not inferred from an index.
- Rows are materialized every round. A write-refusing store fails at `:materialize`, and that round does not publish; the two changed test expectations match this behavior. The later-round recovery case in the table remains open.
- Publishing and signing use the plain linker functions and the L0 signing seam. `yin.link/publish`, `yin.link/names`, and `dao.space.dht/retry` are host wrappers over that path. The publish answer is `{:module :address :links}`; refusals are raised under their reason keyword.
- The fold considers HEAD and loaded indexes, deduplicates an envelope seen in two snapshots, and does not treat a locally held envelope blob as a snapshot. The key sequence is derived from the index after restart.
- `(reset)` drops host modules, so `yin.link` and `dao.space.dht` must be required again. The §5.1 banner line is in `yin.repl.main`. The seed is not printed or silently persisted outside the explicit key file.

This was a read-only diff review. I did not rerun the engineer’s reported JVM, Node, Dart, build, and lint lanes.

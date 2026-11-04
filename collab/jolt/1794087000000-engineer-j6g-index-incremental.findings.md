Completed-GMT: 2026-11-04 06:24 UTC
Coding-Agent: codex gpt-6.1-sol (implementation, edits only)
Session-ID: 0d3f8e24-7a51-4c93-8b26-1f9e6a3d5c80

# J6g incremental code-index materialization

Implemented the ruled composition change. This is an **ungated edits-only
delivery**: no runtime test, build, remote command, workload, commit, staging,
or push was performed. The orchestrator owns remote verification and kimi's
gate. No J6g performance pass is claimed.

## Checkout and scope

The supplied directory is on `jolt-orch2`, not `jolt-j6g`. The requested
`git pull --rebase` completed and answered `Already up to date`; the base is
`ff369c57db46efffee47f119fe68555b34b1f727`, which contains `1cddaa93`.
The first sandboxed pull could not write linked-worktree Git metadata; the
same authorized operation succeeded with sandbox escalation. Existing
untracked files were left alone.

Changed the three allowed production files, their index/transactor tests,
and the explicitly allowed W cursor observation. The harness's only semantic
change prints `index-cursor` in its existing telemetry. cljstyle also corrected
pre-existing whitespace in that file. The watchdog, pacing, submissions,
sample rules, transfer size, thresholds, and stop conditions are unchanged.
`src/jolt/**`, the parser, `dao.space.index`, and query code are unchanged.

The harness on this base still has the older first-round `W-PHASE` reporting;
it does not contain the diagnostic `W-PROGRESS`/nested trace implementation
described in the ruling. This round does not port or alter that instrumentation.
Remote attribution must use the orchestrator's qualified instrumented harness.

## State and advancement

`yin.repl.index/make-indexer` now has two explicit derived fields:

```clojure
{:allocator <live transactor authority, :intake-pool nil>
 :materialization {:cursor <verified local-log successor>
                   :transactions <covered transaction-record count>
                   :manifest-address <verified manifest address>
                   :manifest <manifest with all four covered root addresses>
                   :max-t <greatest committed transaction time>}}
```

The allocator is initially created by `transactor/create!`, using its existing
authoritative watermark scan. `transactor/with-intake-pool` reuses the same
allocation lock, next-t and closed-state cells while replacing or detaching
the publication intake. It is the same writer authority; the shell supplies
no transaction time. `transactor/watermark` exposes that authority's next
time as a read-only diagnostic, never a recovery input. An allocator bound
to another local handle is discarded in favor of `create!`.

Initial publication uses today's `transactor/publish!` full reconstruction.
Later publications lazily restore the previously verified roots with the
existing `dao.space.index/restore`, read from the retained opaque cursor,
and feed only the appended transaction records into `index/fold-batch` in
`:resolved` mode. `index/publish!` emits the dirty subgraph, children first,
then its manifest through the round's fresh complete-retention intake.
No full prefix scan, full sorting, or whole-tree rebuild occurs on the
normal incremental path. Temporary trees, recorder, intake, pool and staged
payloads are dropped at the round boundary; only the fields above survive.

Publication remains synchronous on every evaluation that commits code, and
`commit-names` uses the same path. Program rows still materialize before
publication. Each emitted node is now also hash-verified/read back through
`jing/get` as the intake drains; the manifest gets the existing validated
`read-manifest` check. Previously verified immutable retained subgraphs do
not need another whole-graph traversal. Only after all of this and the
applicable HEAD write succeed does the new cursor/manifest become published.
Failed publication leaves the previous frontier intact. A later committing
round replays the entire unpublished suffix from that frontier, using a
fresh temporary builder; successful retry clears a publication failure.
Queries keep their existing explicit refusal while the frontier is behind
or a failure/loss exists; no reader substitutes the old frontier as current.

`repl-state` exposes `:index-publication` containing only the cursor, covered
transaction count and manifest address. Existing `index/status` shape is
preserved. W observes the cursor directly from its owned shell state.

## Recovery and cursor validation

No process cursor or allocator is persisted or accepted on restart.
`rehydrate` reconstructs a fresh complete-retention log from authoritative
recovered datoms grouped by original transaction time, preserves those
times/provenance, resumes entity allocation above every recovered e/m, and
explicitly clears both derived fields. This also discards a stale field on
an indexer passed to `rehydrate`, not just fields supplied in recovery data.
The previous verified manifest continues to serve recovery queries as today.
The first subsequent committing round derives its allocator from this log
and uses full reconstruction before installing a newly derived tail cursor.

For a live frontier, manifest address, covered record count and branching
factor must match. The suffix must finish at blocked/end after **exactly**
`transactions - covered-transactions` valid, admitted transaction records.
An earlier cursor yields too many records; an ahead cursor yields too few;
a foreign/invalid cursor is rejected by the transport. None of those cases
can publish the uncertain suffix: all take today's full reconstruction path.
Malformed authoritative history still fails that path's validation. Cursor
validation neither inspects nor fabricates transport-specific cursor slots.
In-process durable reset/VM selection carry the live successors with the
same retained log; a worker restart always takes the derivation path above.

## Equivalence tests and review adjustment

Added portable tests (written, **not executed** in this edits-only round):

- Six rounds, three packets per round, repeated content and branching factor
  four: compare the complete ordered facts under **each** covered root and
  the manifest count with today's independent full-history publisher.
- Twelve rounds with branching factor sixteen: exact manifest/four-root
  **address equality** against a full insertion replay, including splits.
- A fresh evaluation after three publications must make no `:oldest`
  cursor request, read exactly one suffix record plus blocked, and preserve
  the transactor's allocation authority. An explicit reader/writer wrapper
  counts these operations; no host-specific `with-redefs` spy is used.
- Stale, ahead and foreign live cursors must trigger full reconstruction;
  its resulting manifest must exactly equal today's full publisher.
- Recovery discards stale live successors, reconstructs from history, and
  the first recovered publication exactly matches today's full publisher.
- Recovery of times 7 and 43 must allocate 44 next, rather than using the
  record count, and must advance entity allocation above restored e/m.
- Unreadable newly emitted content and failed HEAD writes must leave the
  previous cursor and HEAD intact; retry covers all unpublished transactions.
- Retargeted/detached transactors preserve atomic allocation, refusal/retry
  watermarks and close behavior. Existing retention assertions now require
  derived successors while still excluding prior intake/pool payloads.

**Review adjustment:** “identical covered roots” needs a distinction between
identical covered relations and identical Merkle addresses. Today's full
publisher uses `bt/from-sequential`, which sorts/deduplicates and bulk-packs
nodes (`from-sorted-array`/`split-ranges`); incremental `bt/conj` splits nodes
as insertions arrive. These valid trees can have different shapes and root
addresses for the same datoms. This delivery tests relation equivalence
against the unchanged bulk publisher, and exact address equivalence against
full insertion replay. It does **not** claim general address equality with
bulk packing. This interpretation was raised with the user during the round;
no reply had arrived when filing. If the brief requires byte-for-byte equality
to bulk-packed roots, that requirement remains unresolved and needs an
architecture decision; the reference publisher was not silently changed.

Recovery deliberately reconstructs fully once instead of introducing a new
durable checkpoint/sidecar format. Existing immutable manifests remain the
durable authority. No canonical tuple or manifest field was added.

## Checks and remaining gates

- `cljstyle fix`, then `cljstyle check`, all six changed Clojure files: exit 0.
- `git diff --check`: exit 0.
- No `collab/` references introduced into source/test comments.
- No adapter/parser changes, test execution, runtime performance evidence,
  publication yielding, owner scheduling change, or new query visibility rule.

Remote verification should run the existing `yin.repl.index-test` and
`dao.space.transactor-test` namespaces plus affected durable recovery/query
tests (`yin.repl.store-test`, `yin.repl.query-test`), and the applicable
portable lanes. Then repeat attributed W followed by the unchanged untraced
gate on the qualified checkout, preserving the gate's overlap and progress
requirements. The equivalence assertions above are pending runtime evidence.
Incrementality does not establish a 250 ms owner-service bound. Any residual
publication monopolization requiring yielding belongs to a follow-up owner
advancement/query-visibility ruling, not this implementation.

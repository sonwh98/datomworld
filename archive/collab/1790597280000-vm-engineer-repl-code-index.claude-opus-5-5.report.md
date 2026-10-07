Completed-GMT: 2026-09-28 12:25:46 GMT
Completed-Local: 2026-09-28 19:25:46 +07
Coding-Agent: claude
Session-ID: 7fcb4501-80b2-43a3-8511-32424dd6f0d4

# Report: yin.repl automatic code indexing (dao.space.index observer on program-out)

Worktree: /Users/sto/workspace/datomworld-repl-index (branch repl-code-index). Nothing staged or committed.

## Changed files
- `src/cljc/yin/repl/index.cljc` (new): the indexer value and its round step.
- `src/cljc/yin/repl.cljc` (modified, +53/-22): wires the indexer into the session and the eval round.
- `test/yin/repl/index_test.cljc` (new): 7 deftests.

No files outside the allowed set were touched. `dao.space.*`, `yin.vm` and `dao.jing` are unchanged.

## What was built
- **Composition** (`make-session`): `make-attachment` now also returns `:attach`, so a second observer can attach through the same attachment entry. `make-session` attaches that second observer to program-out and builds `:indexer` via `index/make-indexer`. The indexer holds:
  - that observer;
  - a `dao.stream.memory-log` local stream, as `transactor/create!` requires;
  - a `dao.space.transactor` over the local stream;
  - a ring intake (capacity 4096) with a `dao.jing` pool observer;
  - the shell's `:index-store`.
  
  `make-session` now takes `shell-token` and `index-store`. `create-state` mints the token before building the session and accepts a new `:index-store` option, which defaults to `dao.jing.mem/create-content-mem`. `rebuild-session` passes both back in, so reset and `(vm …)` rebuild the observer, log and transactor, while the dao.jing store outlives the session.
- **Round** (`eval-program`): when the expander forwarded a packet, `run-index-stage` → `index/step` runs after the expander stage and before the ingress-gap check and the evaluator. That ordering is what lets a program be indexed whether its VM returns, parks or raises. The evaluator and indexer never call each other; they meet only at program-out. The indexer is not placed in a loader, runner, frontend or the link pair.
- **Projection** (`packet->tx-data`):
  - Takes `packet->row-set` → `vm/semantic-bytecode->ast` and walks the tree alongside the rows through the public `vm/semantic-bytecode-grammar`.
  - Every node occurrence gets a fresh transactor-local id as `:eid`. `vm/ast->datoms` then emits exactly its usual projection with those ids; no tempid remapping is needed, and a stray negative e throws as a defect.
  - Each occurrence gets `[e :yin/address row-address]`. Shared rows get distinct e's that point at the same address, so the address is never the identity.
  - `m` is a per-program metadata entity (id ≥ 16) with `:db/op :db/assert`, `:yin.repl/session <shell token>`, `:yin.repl/root <program address>` and `:yin.repl/round <n>`. The metadata facts use m = `datom/default-op`.
  - All datoms go in with t = nil; one `transactor/transact!` stamps t and writes one atomic record.
- **Publish**: after a round that committed, `transactor/publish!` runs, and the intake is drained with `jing/observe-step!` into the store until it blocks. Only a fully materialized publication becomes `:manifest-address`.
- **Loss**:
  - A gap is counted on the index observer's `:ingress-gaps`. The shell's `ingress-gaps` now includes it, so the existing discipline applies: "Error: … (reset) is required", `:ingress-loss? true`, evaluation refused until reset.
  - A packet that fails to project or commit, or a failed publish/materialize, is consumed and recorded as `:failure`. It is never counted as indexed.
  - `consume-failed-round` calls `index/skip`, which advances the index observer without indexing (still counting gaps). In the evaluator-throw path the packet was already indexed, so skip does nothing there; in the expander-throw path nothing ran, so nothing is indexed.

## Tests: commands and exact outcomes
1. `clj -M:kondo --lint src/cljc/yin/repl.cljc src/cljc/yin/repl/index.cljc test/yin/repl/index_test.cljc` → `errors: 0, warnings: 0`.
2. Focused JVM: `clj -M:test -n yin.repl.index-test -n yin.repl-test -n dao.space.transactor-test -n yin.vm.store-write-audit-test -n yang.clojure.stream-eval-test -n yin.repl.adapter-test -n yin.repl.connect-test -n yin.repl.driver-test -n yin.repl.embed-test -n yin.repl.main-test -n yin.repl.require-test -n yin.repl.serve-test -n yin.repl.serve-connect-wire-test -n yin.repl.build-test` → **Ran 163 tests containing 1283 assertions. 1 failures, 0 errors.**
   - The one failure is `yin.repl.main-test/reattaching-resumes-the-served-stream-and-the-deposit-medium` (main_test.cljc:638): `(remote-value peer "(def x 10)")` returned nil. This is the known cross-process intermittent.
   - An earlier run of the same focused set without the non-yin.repl extras (`yin.repl.index-test yin.repl-test dao.space.transactor-test yin.vm.store-write-audit-test`) gave **Ran 61 tests containing 476 assertions. 0 failures, 0 errors.**
3. `clj -M:test -n yin.repl.main-test` alone → Ran 18 tests / 126 assertions, 1 failure, 0 errors. Same test, same remote-value nil.
4. Full JVM `clj -M:test`, run once before the final `repl-state` change → Ran 2295 tests / 183401 assertions, 2 failures, 0 errors:
   - `main_test.cljc:608` `killing-the-connection-is-observable-and-requests-are-lost`: remote-value nil (the known intermittent; a different test from the ones above).
   - `yang.clojure.stream-eval-test/every-vm-answers-the-same-stream-script`: this one was caused by my change. I had added an `:index` summary to `repl-state`, and its manifest address differs per shell because provenance embeds each shell's token. I fixed it by removing the `:index` key from `repl-state` (the test was not edited), and it passes in run 2.
   - The full suite was not re-run after that fix.
5. `bb test:cljs` → **Ran 2201 tests containing 50021 assertions. 0 failures, 0 errors.** `yin.repl.index-test` ran on Node, as the "Testing yin.repl.index-test" line shows.
6. `bb test:cljd` was not run, as instructed. CLJD care taken: the catch clauses use `#?(:cljd Object :clj Exception :cljs js/Error)`, there are no `#?(:clj …)`-only paths and no cross-ns `#'` access. CLJD compile is unverified.

About the main-test failure: an earlier accidental full-suite run also showed a first-remote-eval nil in main-test. I could not run a clean HEAD baseline, because extracting a scratch copy (`git archive`) needed approval I did not have. What I checked instead: a cold first `eval-input` including indexing takes 86 ms (the next one 57 ms), against the test's 15000 ms `event-ms`. The failure moves between tests across runs. So I attribute it to the known intermittent rather than to indexing latency, but that is inferred, not proven by a baseline.

## Acceptance mapping (test → criterion)
1. `one-program-is-one-transaction-of-its-expanded-projection`: exactly one record, AST datoms equal to `ast->datoms` of the expanded packet modulo ids (relabelled by first appearance, refs included), one `:yin/address` per occurrence with the root one equal to the packet root, transactor t on every datom, the m/provenance entity, no result value indexed.
2. `the-round-publishes-covered-indexes-readable-from-dao-jing`:
   - `index/read-datoms` from the store equals every committed datom;
   - each of `index/restored-indexes` eavt/aevt/avet/vaet holds them;
   - `dao.space.query/q` over the read-back datoms finds the variable names, and through the history view joins each root's m to its session token and round.
   
   `query/open-published!` was not used because it only opens file/remote store coordinates, not the in-memory store.
3. `failed-expansions-add-nothing-raising-and-parking-programs-are-indexed`: a failed macro expansion adds no transaction; a raising program `(nope 1)` and a parked `(require (quote mod))` on `:stack` are each committed and published.
4. Existing yin.repl tests pass untouched (runs 2 and 5); `every-evaluator-is-indexed-the-same-way` covers all four VMs.
5. `a-gap-on-the-index-reader-is-counted-never-indexed` (unit level, ring capacity 2 with 3 packets: 1 gap, 2 transactions, the evicted program absent). `the-shell-reports-an-index-gap-and-reset-rebuilds-the-indexer` (loss reported, evaluation refused; `(reset)` gives a fresh indexer, the same store, and indexing resumes). `vm-selection-rebuilds-the-indexer`.

## Design choices where the design left latitude
- **Placement**: the index step runs before the evaluator in the round. That makes "indexed whether it returns, parks or raises" hold structurally. It also means an index-reader gap stops that round's evaluation, because it uses the same gap discipline as the evaluator reader.
- **Store ownership**: the dao.jing store is shell-level (`:index-store`, outlives reset). The log, transactor, intake and observer are session-level and rebuilt on reset or VM selection. After a reset, t and e restart at 0 and 16 in the new log; earlier manifests stay addressable in the store.
- **Materialization**: the round drives the dao.jing pool observer itself, so publications land in the store rather than sitting in an evicting ring intake. Without this, "save it to dao.jing" would not hold in the local shell.
- **Metadata entity**: one per program, not per session, so each program is exactly one self-contained transaction. It also carries `:yin.repl/root` and `:yin.repl/round`, which keep the root/round association outside the content-hashed rows.
- **Operands**: `:yin/operands` keeps its vector value, exactly as `ast->datoms` emits it. It is not expanded to cardinality-many, so acceptance 1's equality holds.
- **Surfacing**: indexer progress is not in `repl-state`, to keep that summary identical across VMs and shells (see failure 4 above). Programmatic access is `yin.repl.index/summary`.

## Deferred (named, not implemented)
- The separate `$ast` row relation and its derived occurrence relation / dedicated AST indexer.
- Binding `q` on the user's `(require 'dao.space.query)`.
- Remote / content-service exposure of the index.

## Unresolved concerns
- **Cost**: `publish!` rebuilds the covered indexes over the whole local history every round, so per-round work is linear in session history and total work over a session is quadratic. This is the owner's chosen "every round" cadence; an incremental index session (`dao.space.index` session publish) would fix it later.
- **Large programs**: one publication larger than the 4096-payload intake would gap mid-drain. That is recorded as a `:materialize` failure and leaves the previous manifest in place.
- **CLJD**: compile of `yin.repl.index` (and `dao.jing.mem`, now required from `yin.repl`) is unverified; that lane is the orchestrator's.
- **main-test baseline**: see the note under Tests; no clean baseline run was possible.

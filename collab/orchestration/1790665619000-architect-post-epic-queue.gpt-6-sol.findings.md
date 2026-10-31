Coding-Agent: codex
Session-ID: 01a0ebfd-1cf8-7c82-a5d2-700e6a293c1e
Model: gpt-6-sol

Completed-GMT: 2026-09-29 07:07:52 GMT
Completed-Local: 2026-09-29 14:07:52 Asia/Ho_Chi_Minh

## 1. Incremental covered-index publication

**Ruling: DO LATER.** Keep `publish-index!` as a full rebuild for the current REPL. Its every-round cost is known and accepted; there is no measured threshold here that justifies changing publication semantics. The current function snapshots the complete local stream, bulk-builds four trees, and appends their blobs before the manifest ([index.cljc:564](/Users/sto/workspace/datomworld/src/cljc/dao/space/index.cljc:564)). The REPL uses a fresh, complete-retention intake each round to make that append sequence safe ([yin/repl/index.cljc:164](/Users/sto/workspace/datomworld/src/cljc/yin/repl/index.cljc:164)).

**Design if the cost warrants implementation:** Add an explicit incremental publication operation, with a previous manifest address, a readable content store containing that manifest’s nodes, and a caller-owned delta plus its source cursor boundary. Do not infer a delta by rescanning history. Reject a missing prior node, incompatible branching factor, or discontinuous cursor before emitting a manifest. Publish only newly materialized blobs, children before parents, and the manifest last. Preserve the existing full-rebuild API and manifest shape. Require equality of the indexed datom sets, count, and query results; **do not require equal manifest addresses**. The present full build sorts and bulk-packs trees ([btree.cljc:2054](/Users/sto/workspace/datomworld/src/cljc/dao/data/btree.cljc:2054)); insertion into restored trees can produce a different valid Merkle layout. Address equality would require a separately designed canonical tree algorithm.

**Files:** `dao.space.index`, its transactor entry point, and REPL indexer state only when the incremental route is adopted; B-tree changes only if evidence shows existing restore and store operations cannot preserve unchanged nodes. The current transactor merely forwards options ([transactor.cljc:273](/Users/sto/workspace/datomworld/src/cljc/dao/space/transactor.cljc:273)).

**Acceptance:** Seeded randomized histories on CLJ, CLJS, and CLJD comparing each incremental prefix with a fresh full rebuild for all four ordered indexes, distinct count, and query results. Include duplicates, transaction records, empty deltas, branching factors, restarts from a persisted manifest, missing nodes, interrupted publication, and retry. Assert unchanged nodes are not emitted again. A manifest address comparison is appropriate only for two runs of the *same* incremental algorithm with identical inputs.

**Risk:** A partial intake append is retry-safe only while the manifest remains last; losing or misidentifying the delta boundary can silently omit facts. Publication acknowledges append, not observer materialization ([dao.jing.md:140](/Users/sto/workspace/datomworld/docs/design/dao.jing.md:140)). Measure per-round publish time and history size before scheduling this optimization.

## 2. A judge’s `:self` as a fact-medium source

**Ruling: DON’T add a blanket `dao.lease` refusal.** A judge may read its own grantor-authored ledger through a medium attributed to `:self`. `dao.lease` explicitly gives only grantor-authored facts authority to establish terms and permits a holder to observe the grantor’s stream ([dao.lease.md:76](/Users/sto/workspace/datomworld/docs/design/dao.lease.md:76), [dao.lease.md:289](/Users/sto/workspace/datomworld/docs/design/dao.lease.md:289)). Source equality alone cannot establish that a medium is untrusted.

**Design:** Keep the refusal at the composition boundary that knows which media remote parties can write. `yin.vm.ffi.remote-serve` correctly rejects a *standing inbound* lease medium claiming its grantor source ([remote_serve.cljc:173](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ffi/remote_serve.cljc:173)). Its dynamically created renewal medium is attributed to its distinct holder identity ([remote_serve.cljc:613](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ffi/remote_serve.cljc:613)). If another composition exposes a writable medium to outsiders, it must apply the same source-authority check there. `make-judge` and `wire-declared-facts` should continue validating declarations and resolver compatibility without outlawing a legitimate self-authored stream ([lease.cljc:2128](/Users/sto/workspace/datomworld/src/cljc/dao/lease.cljc:2128), [lease.cljc:2227](/Users/sto/workspace/datomworld/src/cljc/dao/lease.cljc:2227)).

**Files:** No `dao.lease` production change. Retain the remote-serve guard.

**Acceptance:** Test that a judge can wire and read its own grantor-authored medium, that remote-serve refuses an inbound medium with `:source` equal to `::grantor`, and that renewal media remain attributed to their holder. The existing composition tests already use grantor-sourced fact media for holders ([lease_composition_test.cljc:115](/Users/sto/workspace/datomworld/test/dao/lease_composition_test.cljc:115)).

**Risk:** A blanket refusal would break legitimate own-ledger wiring; omitting the remote composition guard would allow inbound facts to gain grantor authority, the gate’s P1 finding ([findings.md:7](/Users/sto/workspace/datomworld/collab/1790608971000-reviewer-ffi-lease-wiring-gate.gpt-6-sol.findings.md:7)).

## 3. Serving lease proposals

**Ruling: DO LATER as a separate, proposal-driven composition; DON’T require it for the current remote-FFI export binding.** Section 6 defines the general proposal path: `lease-proposals` is a `#{:writer}` entry and `lease-grants` a `#{:reader}` entry ([dao.stream.remote.md:527](/Users/sto/workspace/datomworld/docs/design/dao.stream.remote.md:527)). The lease contract also expressly permits unsolicited grants ([dao.lease.md:84](/Users/sto/workspace/datomworld/docs/design/dao.lease.md:84)). Remote-FFI serves an identity and immediately authors such a grant, then the holder observes it and renews ([remote_serve.cljc:568](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ffi/remote_serve.cljc:568)). Its holder has no proposal flow. Adding a proposal writer to this binding would create an unneeded request path and a policy question about what resource a proposal may request.

**Design when a requesting composition needs proposals:** Publish one grantor-owned, unleased `lease-proposals` table entry with surface `#{:writer}` and a local reader cursor. A remote holder appends `lease/proposal` carrying its minted proposal ID and subject. Attribute the entry to the holder by a declared medium or an authenticated attachment identity; a shared anonymous writer cannot prove the proposer. Feed the proposal to the judge and let its `:answer` policy return a grant or refusal. Publish answers on `lease-grants`, echo the proposal ID, and create the renewal entry only for an accepted grant. Keep an unanswered proposal bounded by the holder’s own policy; `dao.lease` promises no answer deadline ([dao.lease.md:84](/Users/sto/workspace/datomworld/docs/design/dao.lease.md:84)). The judge already supports proposal answering ([lease.cljc:1261](/Users/sto/workspace/datomworld/src/cljc/dao/lease.cljc:1261)).

**Files:** A new proposal-driven composition and its holder path, plus its table wiring and tests. Do not add proposal handling to `remote_serve.cljc` or `remote_serve/holder.cljc` until that binding has a concrete request use case.

**Acceptance:** Remote proposal append reaches the judge with the correct attributed proposer; grant and refusal each echo the proposal ID; duplicate or contradictory answers are rejected; an unanswered proposal creates no lease or renewal entry; accepted leases renew and reclaim normally; retired proposal descriptors answer `not-found`. Preserve the existing unsolicited FFI export end-to-end tests. The 3c report likewise identifies proposals as a separate feature ([report.md:100](/Users/sto/workspace/datomworld/collab/1790610416000-vm-engineer-ffi-responder-e2e.claude-opus-5-5.report.md:100)).

**Risk:** A public proposal writer without reliable proposer attribution lets a caller request under another holder’s identity. The general section 6 mechanism should not be declared complete until a composition supplies that authority boundary.

## Implementation order

1. Keep the remote-serve grantor-source guard and add the focused authority regression tests.
2. Measure REPL publication growth; implement the incremental operation only if the measured cost warrants it.
3. Build a proposal-driven composition when a remote resource request requires one, with proposer attribution specified first.

## OWNER decisions

1. Set the REPL publication latency or history-size threshold that would move incremental indexing into the active queue.
2. Identify a concrete remote resource that parties must *request* before adding a proposal service.
3. Choose the authentication or gate policy for proposal and renewal writers. Architecture defines where attribution is required; it cannot supply that authority policy.

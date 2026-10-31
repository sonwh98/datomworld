# DHT epic S5, fix round 1: Engineer report r2 (claude-opus-5-5)

Worktree `/Users/sto/workspace/datomworld-dht-s5`, branch `dht-s5`. Nothing is staged or committed.
Findings: `collab/1790800000000-architect-dht-s5-signoff.gpt-6-sol.findings.md`. Rulings (a) and (d) are applied as given.

## Fix 1 (HIGH): the directory lock moves under `dao.*`; `join {:dir}` locks

**New namespaces**

- **`dao.space.store`**: the durable directory store of a covered code index. It holds the content log, HEAD, the exclusive lock, validated recovery and `:head-fn`. It exposes:
  - `open` (formerly `yin.repl.store/open-durable`)
  - `close!`
  - `durable?`
  - `content-path`
  - `file-refusal`
  - `host-file-support`
  - `lock-releasing-close`
  - `head-name`
- **`dao.space.store.fs`**: the host file operations. This is `yin.repl.store.fs` moved verbatim except for the namespace line and docstring: the lock (OS locks on the JVM and Dart, claim entries on Node, the in-process `held` registry) and the atomic HEAD replacement. The old file is deleted.
- Every top-level form was moved whole by a script (`target/s5/split_store.py`), so the lock and HEAD code is unchanged. The one rewritten text is the host-refusal message, which no longer names `--index-store`; it keeps the phrase "not supported on this host" that the test checks.

**Why `dao.space`:** HEAD names a covered-index manifest, and opening validates that index with `dao.space.index/read-manifest` over every root. Under `dao.jing` it would make the payload-agnostic well aware of index structure. Its consumers are `dao.space.dht` and `yin.repl`.

**Consumers**

- `dao.space.dht/join {:dir d}` opens `dao.space.store/open d`: locked, exclusive, and released by `close!`.
  - `:local` stays for callers that supply an already-locked store.
  - If composing the node throws after `join` opened the directory itself, `join` releases the store and its lock again.
- `yin.repl.store` keeps only spec parsing and validation and the `open` dispatch. `file:` goes to `dao.space.store/open`; `durable?` and `close!` delegate to it. There is no duplicated lock code.
- `yin.repl.dht` opens `dht:<dir>` through `dao.space.store/open`.

**Slice-2 lock tests carried over.** `test/yin/repl/store_test.cljc` now requires `dao.space.store.fs` and `dao.space.store`. Its Node worker-thread contender script imports `dao.space.store.fs.js`, and calls to the moved names now go through `dao.space.store`. It passes on the JVM, on Node (claim entries and two worker threads) and on Dart, as does `index_test`.

**Tests first.**

- Red: the new `dao.space.dht-test/a-joined-directory-is-locked-until-the-node-closes` and `yin.repl.dht-test/a-plain-join-and-the-repl-store-exclude-each-other` gave 7 failures before the fix (`target/s5/r2-red1.txt`).
- Green after the fix.
- Mutation L1 (`join {:dir}` opens `content.jing` unlocked) brings back exactly those 7 failures.

## Fix 2 (MEDIUM): competing opens asserted; the Node leg is a required, built gate

**Competing opens** (`yin.repl.dht-process-test`), both checked while publisher A holds its directory:

- A plain `dao.space.dht/join {:dir A's-dir}` in the test JVM is refused, naming the directory.
- A rival `yin.repl.main` JVM on A's directory exits with status 1, printing a refusal that names the directory and says "locked".
- Red: before the fix, the plain-join assertions failed (2 failures, `target/s5/r2-proc-red.txt`). The rival-process assertions already passed, because the REPL already locked.

**Node leg**

- New `bb build:yin-repl-node` compiles `target/yin-repl.js`. `bb test:clj` and `bb test` depend on it.
- It is documented in `docs/agents/build-n-test.md`: `clojure -M:test` without the build fails the test.
- The test now asserts that the file exists instead of skipping.
- Red: I moved the build aside and ran the test. It failed with "the JVM-to-Node leg is required: target/yin-repl.js is absent; build it with `bb build:yin-repl-node`" (`target/s5/r2-node-red.txt`). Rebuilding with the task made it pass.

## Fix 3 (MEDIUM): no reserved ports

**How the processes find each other**

- The first node is an in-test anchor: a `dao.jing.dht` core node over the JVM datagram seam, bound to port 0 and stepped by one owner thread. As in `socket_test`, it needs no bootstrap contact to hold a socket.
- Listeners B and C start with the anchor as their peer, on ephemeral ports. Each reports "listening on 127.0.0.1:PORT".
- A is handed B's and C's reported ports; the readers D and E are handed A's.
- `free-udp-port` and every `--dht-port` are gone from the test.

**Why an anchor:** under ruling (a), a REPL or `join` node with no peers opens no socket, so the very first node cannot be a REPL. There is no red evidence for this item: it removes a test race rather than changing behaviour.

## Lanes (all foreground, after the last source change)

- **kondo** on 17 changed and new files: 0 errors, 0 warnings.
- **`bb test:clj`**: the `yin-repl` build completed, then Ran 2539 tests containing 186171 assertions, 0 failures, 0 errors. `yin.repl.dht-process-test` ran with 33 assertions, including the Node reader and both competing opens. The only SKIPPED line is the pre-existing `ws-project-peer` notice.
- **`bb test:cljs`**: Ran 2453 tests containing 52420 assertions, 0 failures, 0 errors (`dao.space.dht-test`, `yin.repl.dht-test` and `yin.repl.store-test` all present).
- **`bb build:yin-repl-peer`**: built.
- **`bb test:cljd`** (after `rm -rf test/cljd-out`): "All tests passed!" at +2408. Running the compiled `dao.space.dht-test`, `yin.repl.dht-test` and `yin.repl.store-test` files directly: 48 tests passed.
- **`bb build:yin-repl-node`**: built.
- **`shadow compile demo`** (browser): builds.

## Files this round

- New: `src/cljc/dao/space/store.cljc` and `src/cljc/dao/space/store/fs.cljc`.
- Deleted: `src/cljc/yin/repl/store/fs.cljc`.
- Modified source: `src/cljc/yin/repl/store.cljc`, `src/cljc/yin/repl/dht.cljc`, `src/cljc/dao/space/dht.cljc`, and a comment in `src/cljc/dao/jing/dht.cljc`.
- Modified tests: `test/yin/repl/store_test.cljc`, `test/yin/repl/index_test.cljc`, `test/dao/space/dht_test.cljc`, `test/yin/repl/dht_test.cljc` and `test/yin/repl/dht_process_test.clj`.
- Modified build and docs: `bb.edn`, `docs/agents/build-n-test.md`, `docs/design/dao.jing.dht.md` and `docs/design/yin.repl.dao.space-index.md`.

## Fix round 2 (gpt-6-sol r2 finding, MEDIUM): the anchor's bind wait

Findings: `collab/1790800000000-architect-dht-s5-signoff-r2.gpt-6-sol.findings.md`. Only `test/yin/repl/dht_process_test.clj` changed.

- **The bounded wait.** The new `await-bound` replaces the unbounded loop.
  - It reads the anchor seam's lifecycle events with a cursor, until `bind-ms` (10 s) passes.
  - It returns the `:bound` event.
  - On a `:bind-failed` event (with the host's reason), or on no lifecycle event in time, it closes the seam and returns `{:failure "the anchor socket did not bind: …"}`.
- **The split.** `start-anchor!` answers that failure. The rest of the anchor moved to `start-bound-anchor!`, which runs only after a successful bind.
- **The assertion.** The deftest asserts `(is (nil? (:failure anchor)) (:failure anchor))` and starts no process unless the anchor bound.
- **The failure path, shown.** `target/s5/force_bind_fail.py` temporarily bound the anchor to a loopback port that another `DatagramSocket` already held, ran the focused test, and restored the file. The restore was checked byte-for-byte: `restored: True`.
  - The run failed at once, with no hang: `FAIL … the anchor socket did not bind: bind-failed: java.net.BindException: Address already in use` (1 assertion, 1 failure).
  - Output: `target/s5/r3-forced-bind-fail.txt`.
- **Lanes** (foreground, on the restored file):
  - kondo on the test: 0 errors, 0 warnings.
  - Focused `yin.repl.dht-process-test`: 1 test, 34 assertions (33 before, plus the bind assertion), 0 failures, 0 errors.
  - `bb test:clj`: the `yin-repl` build completed, then Ran 2539 tests containing 186172 assertions, 0 failures, 0 errors. The only SKIPPED line is the pre-existing `ws-project-peer` notice.
- The CLJS and Dart lanes were not re-run: this round changed only a JVM-only `.clj` test.

## Rebase onto D4

The branch `dht-s5` has the S5 commit `b86984d0` rebased onto master: D4 `9a69e58f` (unforgeable effects, callee profiles bound raised effects), cells `5e790683`, and the `data` module `fe8bce4a`.

**Cause.** The `dao.space.dht` host exports (`load-index`, `load-status`, `q`) returned the plain map `{:effect :yin.repl.query/call :op … :args …}`. Under D4, `effect?` is a type test, so that map became guest data: it printed instead of being performed.

**Red** (orchestrator's landing logs, `target/orch/land-*.log`):
- JVM: 7 failures.
- Node: 5 failures.
- Dart: 1 `[E]`.

They were all in `yin.repl.dht-test/the-repl-queries-a-remote-index-through-the-same-plain-path` and in `yin.repl.dht-process-test` at the host-function `load-index` and `q` lines.

**Fix.** One line in `src/cljc/yin/repl/query.cljc`, `dht-export`, mirroring D4's own change to the `q` export:
`(module/make-effect ::call {:op (get dht-ops sym), :args (vec args)})`.
- `call-handler`'s `(:op effect op)` still works: D4's `Effect` answers keyword lookup over its descriptor.
- The exports' profiles already declare `#{::call}`, so D4's kind check passes.
- No D4 code changed, and nothing in D4 blocked S5.

**Lanes** (all foreground):
- kondo on the 17 Clojure source and test files of the S5 commit: 0 errors, 0 warnings.
- Focused `yin.repl.dht-test`, `dao.space.dht-test`, `yin.repl.query-test` and `yin.vm.effect-forgery-test`: 53 tests, 557 assertions, 0 failures, 0 errors.
- `bb test:clj`: the `yin-repl` build completed, then Ran 2583 tests containing 186740 assertions, 0 failures, 0 errors. The only SKIPPED line is the pre-existing `ws-project-peer` notice.
- `bb test:cljs`: Ran 2497 tests containing 52931 assertions, 0 failures, 0 errors (both DHT test namespaces present).
- `bb build:yin-repl-peer`: built.
- `bb test:cljd` (after `rm -rf test/cljd-out`): "All tests passed!" at +2452.

**Amend: NOT done.** Every git write in this session (`git add`, `git commit --amend`, and even `git -C … log`) was refused by the permission gate ("This command requires approval"). The fix is an unstaged one-line change in the working tree on top of `b86984d0`; nothing else is modified. To land it, run:
`git add src/cljc/yin/repl/query.cljc && git commit --amend --no-edit`

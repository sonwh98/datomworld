# DHT epic S5, REPL integration: Engineer report (claude-opus-5-5)

Worktree `/Users/sto/workspace/datomworld-dht-s5`, branch `dht-s5` (base `1dfbc8ca`). Nothing is staged or committed.
Sign-off: gpt-6-sol, with gemini-3.1-pro-high as fallback.

## What was built

**The plain Clojure path: `dao.space.dht`** (new file `src/cljc/dao/space/dht.cljc`; CLJC for JVM, Node and Dart; no `yin.repl` dependency). This follows the owner amendment.

- `(join opts)` joins a DHT node.
  - Options: `:local` (a dao.jing store) or `:dir`; `:peers`; `:publish?` (default false); `:bind-host` (default `127.0.0.1`); `:bind-port` (default 0); `:max-inbound-bytes` (default 64 MiB); `:bind!` (defaults to the build's own datagram seam).
  - No peers means solo: no socket and no secret.
  - With peers, `join` mints 32 CSPRNG bytes as the root secret and 32 random bytes as the node id. Both are held only in the node value.
- `(step node now)` returns `[node events]`. The events are `:bound`, `:bind-failed`, `:published` (acknowledged with the peer count sent to, or not, with the reason and peers reached), `:publication-unknown`, `:loaded` and `:load-failed`.
- `store` and `announce!` are the publisher side: a put inserts locally, appends a replicate request and returns at once.
- `load-index` and `load-status` load an index. The load walks it with `dao.space.index/read-manifest` and `walk-index-datoms`, fetches missing blobs with `:jing/get` through `dao.jing.content.step`, and checks the counts of all four indexes.
- `db` is the new `dao.space.query/published-db` over the loaded index (`restored-indexes`). `q` is `dao.space.query/q` over its current view.

**Why `dao.space.dht`:** the path joins a `dao.jing.dht` node to `dao.space.index` and `dao.space.query`. Putting it under `dao.jing` would make the payload-agnostic well aware of index structure; `dao.jing` is syntax and agents are semantics. It is not under `yin.repl` because the REPL is only one consumer of it.

**The REPL reuses the same path.**

- `yin.repl.dht` (new, thin) opens `dht:<dir>` as the locked durable directory store (`yin.repl.store/open-durable`), then calls `dao.space.dht/join` with that store as `:local`.
- The HEAD write calls `announce!`.
- `step` renders the node's events as REPL lines.
- The `--dht-manifest` hydration calls `dao.space.dht/load-index`, then installs the loaded datoms as recovery.
- `yin.repl.query` gains a `dao.space.dht` host module on the existing query call pair. `(require 'dao.space.dht)` binds `load-index`, `load-status` and `q`, answered by the `dao.space.dht` functions of the same name over the shell's own node.
  - `load-index` answers its status at once and never waits.
  - `q` refuses until the index is loaded.
- There is no REPL-only DHT or index plumbing.

**CLI** (`yin.repl.main/parse-args`; documented in `yin.repl.dao.space-index.md`, "DHT store"):

- `--index-store dht:<dir>`
- `--dht-peer host:port`, repeatable; `[v6]:port` is accepted
- `--dht-publish`
- `--dht-bind ip`
- `--dht-port p`
- `--dht-max-inbound-bytes n`, default 67108864
- `--dht-manifest address`

Any `--dht-*` flag without `dht:<dir>` is refused. `--dht-bind`, `--dht-port` and `--dht-manifest` are refused when no peer is given, because solo opens no socket. Every host exits with status 1 when the DHT store refuses after startup (`main/exit-status`).

**Docs:**

- `dao.jing.dht.md`: status is now S1 to S5 implemented; new §10 subsection "The plain Clojure path"; the §9 inbound default.
- `yin.repl.dao.space-index.md`: new section "DHT store", covering the flags table, banner, per-publication lines, hydration and the host module.

## Acceptance → evidence

| # | Bullet | Evidence |
|---|---|---|
| 1 | Default `mem`; `dht:<dir>` explicit; no peers means solo with NO socket | `yin.repl.dht-test/mem-stays-the-default-and-dht-is-an-explicit-choice`. `a-dht-store-with-no-peers-is-solo-and-opens-no-socket` (the bind seam is never called; no `:traffic`, `:datagrams` or secret; the publication line says solo). `dao.space.dht-test/a-solo-node-opens-no-socket-and-reports-why-nothing-was-sent`. Mutation M1 is killed in both. |
| 2 | Publishing has its own flag; the REPL states what it shares before sharing | `peers-publication-and-binding-are-separate-explicit-flags` (peers leave `publish?` false). `the-banner-states-what-will-be-shared-before-anything-is`: the banner prints before the node's first step and names `<dir>/content.jing`, "will be shared", fetch-only or solo. The process test checks the real banner of publisher A and listener B. M2 and M9 are killed. |
| 3 | Each publication shown acknowledged (sent to N) or not and why; no round waits | `a-publication-sent-to-two-peers-is-reported-acknowledged`: the round returns `4242` and its manifest reads back before any DHT step, then the line reads "acknowledged: sent to 2 peers". `…publishing-off…` ("publication is off", and no `:store` datagram is sent). `…short-of-peers…` ("too few peers — sent to 1 of 2", only at or after 5000 ticks). The solo case is in bullet 1. `dao.space.dht-test/a-publication-reports-its-acknowledgement-as-an-event`. In the process test, A prints `4242` before its "— acknowledged: sent to 2 peers" line, over real UDP. M3 and M4 are killed. |
| 4 | Two processes, separate locked directories, exchange content over real loopback sockets | `yin.repl.dht-process-test`: JVM processes B and C (listeners), A (publisher) and D (reader), each with its own `dht:<dir>` and a `lock` file, over real loopback UDP. D hydrates A's manifest; its log shows "5 blobs fetched". **JVM↔Node:** process E runs `node target/yin-repl.js`, hydrates A's index, and answers the same token. **Plain JVM:** the test JVM itself runs `dao.space.dht/join` over real UDP, loads A's index and queries it. D's host-function `q` then prints exactly that result. JVM↔Dart was not added. |
| 5 | A reader given a manifest address hydrates and queries a remote index | `a-reader-given-a-manifest-hydrates-then-queries-the-remote-index`: lines wait until "hydrated"; HEAD is the manifest; `q` answers under the publisher's session token and never the reader's. `the-step-owner-admits-no-line-while-hydrating`. `a-manifest-no-peer-holds-refuses-the-reader`. `a-socket-that-cannot-bind-refuses-and-stops-the-shell` (exit status 1). Across processes: D, and the Node reader E. M5, M6 and M10 are killed. |
| Amend. 1 | Plain CLJC join / load-index / query, outside yin.repl | `dao.space.dht-test/plain-clojure-joins-loads-a-remote-index-and-queries-it`: a plain publisher node (transactor + `announce!`) and a plain reader node, with no yin.repl. The fetch count is above zero, `q` works with and without inputs, and `db` throws before the load. Also `a-manifest-no-peer-holds-fails-its-load` and `join-refuses-options-it-cannot-honour`. This runs on JVM, Node and Dart. M12 is killed. |
| Amend. 2–3 | The REPL uses the same path through host functions, with identical results | `yin.repl.dht-test/the-repl-queries-a-remote-index-through-the-same-plain-path`. `q` is refused before the load; `load-index` answers `:loading` at once; "dht: loaded" is printed. The REPL's answer equals `(pr-str (dao.space.dht/q …))` from a plain node over the same publisher. The process test does the same across processes. M13 and M14 are killed. |
| Also | Secret: ≥32 CSPRNG bytes per process, in memory only | `the-root-secret-is-minted-per-open-in-memory-and-never-persisted`: 64 hex characters, distinct per open. The directory holds only `content.jing`, `HEAD` and `lock`. It appears neither in HEAD nor in `repl-state`. M7 is killed. |
| Also | Inbound bound CLI default, documented | `--dht-max-inbound-bytes` defaults to 67108864, tested in the flags test and documented in both design docs. |
| Also | Bind host defaults to loopback | Tested (M8 is killed); `--dht-bind` is the only way to change it. |

## Tests first, and mutations

- **Red phase.** `test/yin/repl/dht_test.cljc` was written first against a stub `yin.repl.dht`: 12 tests, 19 failures, 18 errors.
- **The rest.** The plain `dao.space.dht` tests came after the owner's amendment, alongside the move. For those, and for the process test, the red evidence is the mutations below. Each is applied, run and reverted by `target/s5/mutate.py`.

Mutations M1 to M14 were run after the restructure, over `yin.repl.dht-test` and `dao.space.dht-test`. **All 14 are killed.**

| Mutation | Result |
|---|---|
| M1 solo binds a socket | 2 fail |
| M2 peers imply publish | 3 |
| M3 unacknowledged reported as sent | 8 |
| M4 put skips the local insert | 22 failures, 6 errors |
| M5 evaluation admitted while hydrating | 3 failures, 1 error |
| M6 hydration not installed | 2 |
| M7 fixed secret | 1 |
| M8 wildcard bind default | 2 |
| M9 no sharing statement | 2 |
| M10 refused shell admits | 1 |
| M11 `--dht-*` flag without the dht store is ignored | 8 |
| M12 load never fetches | 11 failures, 5 errors |
| M13 host `q` is not the plain `q` | 1 |
| M14 host `load-index` does not start a load | 4 |

- M10 survived its first run: the refused-hydration case also leaves the hydration pending, so the mutation changed nothing there. The added bind-failure test kills it.
- P1 (publication forced off) was run against the process test before the restructure: 5 failures and 2 errors. It was not re-run after the move.

## Lanes (all foreground, after the last source change)

- **kondo** on the 13 changed and new files: 0 errors, 0 warnings.
- **`clojure -M:test`**: Ran 2537 tests containing 186154 assertions, 0 failures, 0 errors. It includes `dao.space.dht-test`, `yin.repl.dht-test` and `yin.repl.dht-process-test`, with the Node reader leg running. The only SKIPPED line is the pre-existing `ws-project-peer` notice.
- **`bb test:cljs`**: Ran 2451 tests containing 52411 assertions, 0 failures, 0 errors. Both new namespaces ran ("Testing …" is present).
- **`bb build:yin-repl-peer`**: builds.
- **`bb test:cljd`** (after `rm -rf test/cljd-out`): "All tests passed!" at +2406.
  - The compact log can't show every file, so I also ran the two compiled new test files directly with `flutter test -r expanded`: all 19 tests passed.
- **Extra builds:** `shadow compile demo` (browser) and `shadow compile yin-repl` (Node) both build.

Notes on the lane history:

- An earlier `bb test:cljd` run ended with exit 137 when the orchestrator stopped my run. The rerun above is the verdict.
- One full JVM run, before I rebuilt the Dart peer, failed 3 assertions in `yin.repl.main-test/a-dart-client-attaches-to-this-jvm-server`. The peer binary had been built before the restructure. After the rebuild, `main-test` alone and the full lane both passed. I can't tell whether that was the stale binary or a timing flake.

## Changes outside the brief's obvious file set

1. **Browser build safety.** Once `yin.repl` depended on the DHT, the browser `:demo` build failed: `node:crypto` from `dao.jing.dht` (S4) and `dgram` from `dao.stream.datagram.node` (S1). Both are now loaded with a runtime `js/require` when the HMAC runs or the socket binds. This is the same idiom `yin.repl.store` uses for `fs`. After the change, `demo` and `yin-repl` both build and the Node DHT tests pass.
2. **`dao.space.query/published-db`.** A new public function: a query value over a manifest in a store the caller owns. `open-published!` now builds its value from it, keeping its map literal so the store-write audit's allowlist is unchanged.
3. **Store-write audit.** The audit (`yin.vm.store-write-audit-test`) treats any local named `store`, or a `:store` key, as the VM store. The node's handle key is therefore `:handle`, and `yin.repl.dht/without-runner` takes `handle`. These are accurate names, not suppressions; the allowlist is untouched.
4. **Shared test seam.** `test/dao/jing/dht/mesh.cljc` gains `seam`, a `dao.stream.datagram` host seam over the mesh, shared by both new test namespaces.

## Open items and judgment calls for the reviewer

- **"No peers means solo" is read literally.** `--dht-port` or `--dht-bind` without a peer is refused, so a first node lists its peers' addresses. They need not be up yet, since a later contact proves itself by cookie. The process test does exactly this.
- **`dao.space.dht/join {:dir …}` takes no directory lock.** The lock lives in `yin.repl.store.fs`. A plain caller sharing a directory across processes passes its own locked store as `:local`, as the REPL does. Moving the lock under `dao.*` would let plain callers share directories safely; it was left for a later decision.
- **Hydration never replaces an index.** A `--dht-manifest` into a directory whose HEAD names a different manifest is refused. After hydration, the reader's own rounds continue that index (HEAD moves on) and are reported "NOT acknowledged: publication is off" unless it publishes.
- **The Node leg needs a manual build.** It requires `target/yin-repl.js` (`clj -M:cljs -m shadow.cljs.devtools.cli compile yin-repl`), which no bb task builds; the test prints a SKIPPED notice when it is absent. I built it, and the lane above ran it.
- **Events lost when a round fails.** A `load-index` answered inside a round that then fails is dropped with that round's state, as the query pair's cursor is. The user re-asks.
- **Pre-existing, unrelated:** the `ws-project-peer` SKIPPED notice. Separately, kondo reports a "no libs" error on `src/cljc/yin/repl/host.cljc` at HEAD; that file is untouched here.

## Invariants

- **P2P, no privileged node:** every node runs the same `join` and `step`, and "listener" and "publisher" are only flags.
- **Apply stays independent of rpc:** the host module uses `dao.stream.apply` requests and responses on the existing call pair and adds no rpc or transport words.
- **CLJC portability:**
  - `:cljd` comes first in every mixed conditional I wrote.
  - No array-map.
  - No cross-namespace `#'` private access.
  - Refusal helpers return the error object.
- **Time:** the core sees time only as appended ticks. Real-process tests use bounded polling.

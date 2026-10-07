Completed-GMT: 2026-10-07 19:19:00 GMT
Completed-Local: 2026-10-08 02:19:00 Asia/Ho_Chi_Minh
Coding-Agent: claude (claude-fable-5-1)
Role: Lead System Architect

# Architectural sign-off: Track B Slice S3a-2

Verdict: **WITHHELD** on one finding, mine, found by running the Node lane (§0). Everything else is accepted as it stands: every architectural criterion of the spec's S3a-2 row holds, both engineer deviations are sound and accepted, the reviewer's ACCEPT stands, and all three lanes pass under my own runs. The fix is a mechanical rename in four files and two docs; once it lands with kondo clean and the Node build warning-free, this report's acceptance applies without a second architectural pass (a reviewer glance at the rename diff suffices).

## 0. Blocking: the namespace `yin.vm.linker.head.board` clashes with the var `yin.vm.linker.head/board`

Both shadow builds report it, `:yin-repl` (the Node REPL the shell ships) and `:test`:

```
------ WARNING #1 - :ns-var-clash ---------------------------------------------
 File: src/cljc/yin/vm/linker/head/board.cljc:1:1
 Namespace yin.vm.linker.head.board clashes with var yin.vm.linker.head/board
```

On the JS host a namespace and a var at the same dotted path are one property on one object: `yin.vm.linker.head.board` is both the module object of the new namespace and the board constructor `head/board` (head.cljc:185, 25 call sites). Shadow keeps both alive in a `:none` compile, which is why the Node lane and the Node REPL build pass today; under `:advanced` and in Closure's namespace flattening the two overwrite each other, and the warning exists because that breakage is silent. It is a cross-host naming hazard of exactly the kind the project's CLJD/CLJS trap list records, introduced by a name my own spec chose (§2.2); the old `head.ws` had no var `ws` to collide with, so this is new in S3a-2. The engineer did not run the Node lane (JVM-only per-iteration rule) and the reviewer did not rerun lanes, so neither could have seen it; the full three-lane gate is what caught it, as intended.

**Redirect.** Rename the namespace, not the var: the var has 25 callers across the follower and its tests, the namespace has four source files and two docs. New name: `yin.vm.linker.head.channel` (alias `head.channel`), "the board's channel composition", which collides with no public var in `head.cljc` (`trace verify seq-of judge board deposit! candidate-kind defaults follow attach step install heads records moved`). Concretely:

- `git mv src/cljc/yin/vm/linker/head/board.cljc src/cljc/yin/vm/linker/head/channel.cljc`; `ns` form and docstring's self-reference.
- `src/cljc/yin/repl/dht.cljc`: the require alias and every `head.board/` call (`serve`, `serve-step`, `stop!`, `dial`, `dial-step`, `handle`, `close!`), plus the docstring mention at line 40.
- `git mv test/yin/vm/linker/head_board_test.cljc test/yin/vm/linker/head_channel_test.cljc`, `ns` `yin.vm.linker.head-channel-test`, alias and calls; `test/yin/repl/dht_head_test.cljc` docstring line 12.
- `docs/design/yin.vm.linker.dht.head.md`: the 6 table row, the 8.1 sentence, the 8.2 row, the history line in 13; `docs/orchestrator-log.md` only if it names the file as a path to land.
- Keep `board-name`, `board-profile`, the `/head` path and every function name as they are; only the namespace moves.
- Gate: `clj -M:kondo` clean, `clojure -M:test -n yin.vm.linker.head-channel-test -n yin.repl.dht-head-test`, and `bb test:cljs` with **zero** shadow warnings in both the `:yin-repl` and `:test` builds (the `[:test] Build completed ... 0 warnings` line is the acceptance). JVM and Dart need not rerun for a rename; the orchestrator may run them at landing per the standing rule.

This spec's §2.2 and §8 naming (`head.board`) is superseded by this section; the rest of the spec stands.

Branch `stream-crossmachine-s3a`, worktree `datomworld-stream-s3a`, on master @ `469cd5cc`. Read firsthand: the S3a spec §0–§9, the engineer report, the codex review, the full working-tree diff (`git diff`, 10 files, +543/−238), `head/board.cljc` whole, `head.cljc` `read-source`/`poll`/`attach`/`new-principal`/`heads`, `remote_channel.cljc` `dial`/`dial-step`/`resolve-expired?`/`serve-step`/`begin-stop`/`continue-stop`/`release!`/`unbind!`, `yin.repl.dht` `close!`/`serve-board`/`step-link`/`step`, and every changed test. Verification I ran myself is in §5.

## 1. Invariants and boundaries: hold

- **No `:ws/` above `remote-channel`.** `grep -rn ":ws/" src/cljc/yin/` outside `yin.repl.serve` and `yin.repl.connect` (S3b's) finds only two docstring words in `yin.repl.main` line 1052–1053, predating this slice. `head/board.cljc` requires `dao.stream` and `dao.stream.remote-channel` only; `yin.repl.dht` and `yin.repl.main` require `dao.stream.remote-channel` only for `loopback-literal?`. `head/ws.cljc` is deleted (staged); no source or test under `src/`/`test/` references it (the `test/cljd-out/*head-ws-test*` leftovers are ignored generated Dart, regenerated by the Dart lane).
- **Spec passes down opaquely.** `board.cljc` hands `{:host h :port p :path "/head"}` and the host assembly `{:connect! :bind! :unbind!}` through untouched; `rc/descriptor-of` formats the descriptor below the boundary, and `the-port-is-named-from-the-start` now asserts the descriptor through `rc/descriptor-of` rather than `:ws/*` literals.
- **Follower inspects no `:dao.stream.remote/*` key.** `grep ":dao.stream.remote" src/cljc/yin/vm/linker/head.cljc` is empty. The follower still calls only `cursor` and `next`; the shell reads `:dao.stream.remote/reason` only in `lost-reason` for its line text, which is where the spec puts it (§2.3).
- **D1 residue is exactly the three things**: the board name, the one-entry `#{:reader}` table and name map, and the loopback gate (kept here until S4, as §2.2 says). `board-profile` merges caller bounds over `production-bounds`; nothing else lives in the namespace.

## 2. Honest `:answered`: as specified

`read-source` (head.cljc:523–562): `answered?` becomes true on cursor `ok`, next `ok`, next `gap` only; the `blocked` branch now carries `answered?` through instead of forcing true; the retry and lost branches were already carrying it. `poll` sets `:polled now` unconditionally and `:answered now` under `answered?`; `new-principal` seeds `:polled nil`; `attach` clears `:polled`/`:answered` with `:cursor`/`:due`; `heads` reports `:polled`. No transport-health accessor was added (D3).

Evidence is the right shape: `blocked-is-polled-not-answered` builds a reflection over a two-ring in-process channel whose mirror runs only on `serve!`, so the first poll's `blocked` is provably the handle's own: `:polled 0`, `:answered nil`; once served, `:answered` equals the reading poll; later empty polls move `:polled` only. The plain-ring half shows `:answered` advances on a new value and not on quiet polls. `a-lost-source-is-reported-once-and-a-fresh-handle-reads-again` asserts `attach` clears both. `a-blackholed-board-is-source-lost-after-give-up-after` confirms the end-to-end consequence: an idle healthy board moves `:polled` and not `:answered`, and blackholed polls answer nothing.

## 3. Deviations

**D1, dial-level resolve bound at `give-up-after`: ACCEPTED.** The engineer's diagnosis is correct and matters: a ws handle still `:connecting` answers `full` to `append!`, so `send-named!` registers no outstanding op and stamps no deadline; the link has nothing to expire and the shell, whose own `:yin.head/no-answer` fallback the spec deleted on the premise the link would expire it, would redial never. The fix lives in the layer that owns the connection (`remote-channel/dial-step`), reads its own `:policy`, uses the same `give-up-after` and the same `channel-gone` outcome, keeps `nil` as "never expires", starts the clock at the first `dial-step` (driver-paced, consistent with everything else in the composition), and is checked only while still `:resolving` after `resolve-step`, so an attach or a definite loss on the same tick wins. An opened-late connection is bounded by the earlier of the two deadlines, which is the conservative reading and is now written into `dao.stream.remote.md` §3.1. The alternative (stamping a deadline on a `full`-refused named send) would make the link account for an op it never put on the wire; I prefer this one. Regression `a-connection-that-never-opens-is-lost-at-give-up-after` holds at 1149/1150, and the rewritten `dht_head_test` silent-board case proves the shell redials with the new line text.

**D2, `yin.repl.dht/close!` runs one stopping tick: ACCEPTED for S3a, with a note.** `begin-stop` (remote_channel.cljc:433–443) does all of the release in that one tick: `ws-project/stop!`, `close-sessions!`, `endpoint-stop!`, then `unbind!`. Nothing a later tick does frees a resource; later ticks only reap, reject stragglers and observe the host's `:stopped` fact or the grace. The spec's own §4.3/§9 argue the board needs no grace. `close-leaves-no-listener-and-no-dial` proves the observable claim (listener gone, every connection closed). What is skipped is the `:confirmed` observation, which no caller of `close!` reads. On a `:refused` or already `:stopped` server, `stop!` and `serve-step` are both identity, so the exit path is safe on every status. Note for S3b: `close!` discards the stepped server, so a node stepped after `close!` would still carry `:status :serving` in its map while its acceptor is stopping; `close!` is an exit operation today and no caller steps afterwards, but S3b's exit-path work should either loop to `:stopped` or store the stepped server back.

**D3, D4, D5**: refusal shape as data, the one-line `main.cljc` relocation of `loopback?`, and the `-r` test regex: all fine.

## 4. Spec §7 coverage and what is owed

Delivered per §7: every existing `head_ws_test` case over `head.board` with `(dial-step d now)` and the shared loopback net; the two unchanged-in-substance cases; the loopback gate and port refusals; `a-host-without-a-listener-or-a-dialer-is-a-refusal`; `a-stopped-board-is-a-lost-source-then-reattachable` (stop! is I/O-free, `:stopped :confirmed`, one `:source-lost`, installed head kept, reattach reads the same identity); `a-blackholed-board-is-source-lost-after-give-up-after` (lost at or after since+150 and within 50 ms); the real-socket JVM case extended with `stop!` confirmed inside `stop-grace-ms` and the dial `:lost` `channel-gone`; `blocked-is-polled-not-answered`; `dht_head_test` `channel-gone` line and `close-leaves-no-listener-and-no-dial`; the S3a-1 sign-off's F1 probe folded into `a-lifecycle-gap-while-starting-is-terminal`.

Not delivered, and not in the engineer's brief: the spec's Node twin of the real-socket case (over `dao.stream.ws.node/listen!`/`stop-listening!`) and the Dart peer case (`transfer_peer.cljd` dialing a JVM board). Neither is a defect in this slice; both are real-socket evidence the spec wanted for 8.3's third item. I have narrowed the head doc's 8.3 claim accordingly (one bullet, §6 below) and record both as owed to S4 alongside the loopback lift, where the Dart listener lands anyway. The spec's "blackholed through the shell's own redial" is covered indirectly: the shell's `:lost` path is one branch and the never-opens case exercises it end to end.

## 5. Verification

| lane | result |
|---|---|
| `clj -M:kondo --lint` over the 5 sources and 4 test files | 0 errors, 0 warnings (mine) |
| `git diff --check` | clean (mine) |
| `clojure -M:test -n` head-board, head-follow, dht-head, remote-channel, main-test | 110 tests / 1499 assertions, 0 failures, 0 errors (mine) |
| `clojure -M:test -e :slow` (full JVM fast lane) | 3705 tests / 238015 assertions, 0 failures, 0 errors (mine; log `collab/s3a2-scratch/architect-jvm-lane.log`) |
| `bb test:cljd` (Dart lane, 218 files in 8 shards) | 3513 passed, 0 failed, "All tests passed!" (mine; log `collab/s3a2-scratch/architect-cljd-lane.log`). The Flutter compact reporter throttles its per-test lines, so the full-lane log names no board test; I reran `bb src/dev/cljd_agg.clj --only yin.vm.linker.head-board-test,yin.vm.linker.head-test,yin.repl.dht-head-test` (log `architect-cljd-only.log`): 40 entries, all passed, against 13 + 8 + 18 tests registered in the generated Dart, and the 13 are exactly the board file's 13 portable deftests (the real-socket case is JVM-only by its reader conditional). The new S3a-2 cases are in that set. |
| `bb test:cljs` (Node lane, after the bb build linked `node_modules` to the main checkout's install) | 3561 tests / 102413 assertions, 0 failures, 0 errors; the four S3a-2 namespaces appear under "Testing"; **1 shadow warning** in each of the `:yin-repl` and `:test` builds, the `:ns-var-clash` of §0 (mine; log `collab/s3a2-scratch/architect-cljs-lane.log`). The engineer's three Node-subprocess errors in `yin.repl.dht-process-test` were the missing `node_modules` and stale `target/yin-repl.js`; both are now in place in this worktree. |
| `clojure -M:test -n yin.repl.dht-process-test` (JVM spawning the rebuilt Node REPL, slow tests included) | 3 tests / 131 assertions, 0 failures, 0 errors, no skips (mine; log `collab/s3a2-scratch/architect-dht-process.log`). The JVM-to-Node head follow and the by-name require work across processes with the clash present, which confirms it is latent under the `:none` compile the Node REPL ships with today, not a runtime break. It stays blocking because the hazard is silent under `:advanced` and the fix is a rename. |
| `cljstyle check` | not run by the engineer or me (approval); the pre-commit hook covers it. |

## 6. One edit of mine

`docs/design/yin.vm.linker.dht.head.md` 8.3, third bullet: "Verified for the JVM and Node, open for Dart until S4" became "Verified on the JVM over a real loopback socket, and on Node and Dart over the in-process loopback net only (a real-socket Node twin and the Dart peer case are owed, S4)". The previous wording claimed Node real-socket evidence that does not exist yet. Nothing else touched.

## 7. Commit instructions for the orchestrator

Stage by name, exactly the engineer's list (§5 of the report), nothing under `collab/`; delete `collab/s3a2-scratch/` freely. Message per spec §8:

```
feat(yin.vm.linker.head): neutral board over dao.stream.remote-channel, honest :answered, stream-side liveness (cross-machine stream slice S3a-2)
```

Do not commit until the §0 rename has landed and its gate (zero shadow warnings) is met; the staged paths then carry the `channel` names instead of `board`. Include my one-line doc edit (§6) in the same commit; it is already in the working tree. `node_modules` in this worktree is an untracked symlink and is never staged. After that, fast-forward master and push, per the standing rule.

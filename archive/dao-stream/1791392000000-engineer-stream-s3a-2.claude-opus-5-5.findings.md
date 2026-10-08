Completed-GMT: 2026-10-07 19:40:00 GMT
Completed-Local: 2026-10-08 02:40:00 Asia/Ho_Chi_Minh
Coding-Agent: claude (claude-opus-5-5)

# Implementation Engineer: Slice S3a-2 completion report

Branch `stream-crossmachine-s3a`, worktree `datomworld-stream-s3a`, on master @ `469cd5cc`. Nothing committed. Scope: spec `collab/1791384000000-architect-stream-s3a-spec.claude-fable-5-1.findings.md` §2.2, §2.3, §3, §5.2, §6 (head doc), §7 (S3a-2 tests), §8 S3a-2 row; plus the S3a-1 sign-off's F1-probe fold.

## 1. What changed

| file | change |
|---|---|
| `src/cljc/yin/vm/linker/head/board.cljc` | **new**, replaces `head/ws.cljc`. D1 residue only: `path` "/head", `board-profile` (caller bounds merged over `remote-channel/production-bounds`), `board-name`, the loopback gate (`:yin.head/not-loopback`), the one-entry `#{:reader}` table and one-entry name map. `serve`/`serve-step`/`stop!`/`dial`/`dial-step`/`handle`/`close!` delegate to `dao.stream.remote-channel`. Requires only `dao.stream` and `dao.stream.remote-channel`. |
| `src/cljc/yin/vm/linker/head/ws.cljc` | **deleted** (`git rm`). |
| `src/cljc/yin/vm/linker/head.cljc` | §3.2: `read-source` sets `answered?` only on cursor `ok`, next `ok`, next `gap` (`blocked` keeps the prior value); `poll` sets `:polled now` on every poll and `:answered now` only when answered; `new-principal` gains `:polled nil`; `attach` clears `:polled`/`:answered`; `heads` reports `:polled`. The follower still reads no `:dao.stream.remote/*` key. |
| `src/cljc/yin/repl/dht.cljc` | §2.3: `serve-board` → `head.board/serve` with `:spec {:host :port}` and `:host (::ws node)`; `step-link` → `head.board/dial` / `(dial-step d now)`; the `:yin.head/no-answer` branch and the `:since` it used are deleted; `channel-gone` line text is now "the connection was refused, closed, or stopped answering"; refusal keys renamed (`:yin.head/not-loopback`, `:dao.stream.remote-channel/no-transport`); `close!` stops the board through `head.board/stop!` and closes each dial through `head.board/close!` (see deviation D2); `step` records `::now`. |
| `src/cljc/yin/repl/main.cljc` | `parse-follow`'s loopback check uses `remote-channel/loopback-literal?` (was `head.ws/loopback?`). |
| `src/cljc/dao/stream/remote_channel.cljc` | **outside the brief's file list, see D1**: the dial carries `:policy` and `:since`; `dial-step` bounds the `:resolving` phase by `give-up-after` from the first step. |
| `test/yin/vm/linker/head_board_test.cljc` | renamed from `head_ws_test.cljc` (`git mv`), every case moved to `head.board` with `(dial-step d now)` and the moved `dao.stream.loopback-net`; new: `a-host-without-a-listener-or-a-dialer-is-a-refusal`, `a-stopped-board-is-a-lost-source-then-reattachable`, `a-blackholed-board-is-source-lost-after-give-up-after` (idle healthy: `:polled` advances, `:answered` does not, nothing lost; blackholed: `:source-lost` at ≥ since+150 and within 50 ms of it, dial `:lost` `channel-gone`, installed head kept); the real-socket JVM case extended with `stop!` → `:stopped :confirmed` within `stop-grace-ms` and the dial `:lost` `channel-gone`. |
| `test/yin/vm/linker/head_follow_test.cljc` | new `blocked-is-polled-not-answered` (a reflection over an in-process two-ring channel whose mirror runs only when asked: first poll `:polled 0 :answered nil`; once served `:answered` is the reading poll; later empty polls advance `:polled` only; same over a plain ring, and a new value advances `:answered`); `a-lost-source-is-reported-once-and-a-fresh-handle-reads-again` asserts `attach` clears both. |
| `test/yin/repl/dht_head_test.cljc` | world gains a per-tick `:dt`; the silent-board case now runs at 1 s ticks and asserts the one `channel-gone` line with the new text; new `close-leaves-no-listener-and-no-dial` (net listeners empty, every connection closed). |
| `test/dao/stream/remote_channel_test.cljc` | F1 probe folded into `a-lifecycle-gap-while-starting-is-terminal` (an extra `serve-step` adopts a session while starting, then the gap; `every? :closed?` over the sessions); new `a-connection-that-never-opens-is-lost-at-give-up-after` (D1). |
| `docs/design/yin.vm.linker.dht.head.md` | §6 amendments: 5.1 exposure names `dao.stream.remote-channel` and the opaque spec, loopback points to S4, Availability says what times out; 5.5 `:polled`/`:answered` and channel-owned liveness; 6 table row is `head.board/serve, serve-step, stop!, dial, dial-step, close!`; 7 `:head-poll-ticks` notes the ms domain and the 60 s / 15 s relation; 8.1 transport sentence; 8.2 session bound landed; 8.3 "Unverified" rewritten as verified/open per spec, half-open sentence removed; 10 row gains "or stops answering for `give-up-after`"; the H2 history line notes its replacement. |
| `docs/design/dao.stream.remote.md` | §3.1 one sentence for D1. |

## 2. Deviations

- **D1. A connection that never opens never expired (S3a-1 gap, fixed in `remote_channel.cljc`).** Spec §2.3 deletes the shell's `:yin.head/no-answer` branch on the premise that "a resolve that is never answered now expires on the link as `channel-gone`". The rewritten `dht_head_test` silent-board case (listener accepts, never opens) proved that false: while the ws handle is `:connecting`, `WsHandle.append!` answers `:dao.stream/full`, so `remote/send-named!` registers nothing outstanding and stamps no deadline. `link-step!` has nothing to expire and the resolve retries forever. Without a fix the shell would never redial such a board. The smallest fix in the layer that owns the connection: `remote-channel/dial` keeps its link `:policy` and `dial-step` records `:since` (first step's `now`); a dial still `:resolving` at `since + give-up-after` is `:lost` with the same `channel-gone` outcome. No change to `dao.stream.remote` or `ws`; `give-up-after nil` keeps the "nothing expires" rule. An opened connection's resolve is still expired by the link at or after this bound, so the two agree. Regression: `remote_channel_test/a-connection-that-never-opens-is-lost-at-give-up-after` (still `:resolving` at 1149, `:lost` at 1150). **Architect: please accept or redirect.** The alternative is stamping a deadline on a `full`-refused named send in the link (S2c semantics), which is larger.
- **D2. `yin.repl.dht/close!` runs one stopping tick; it does not loop to `:stopped`.** Spec §2.3 says "the shell's exit loop drives `serve-step` until `:stopped` or `stop-grace-ms`, as `yin.repl.main` already does for the REPL endpoint". `close!` is called from `main/close-index-store!` at process exit on three hosts, and in ~40 test sites, with no clock and no loop. Adding a loop would mean an arity change across all of them and three host exit paths (S3b territory). Instead `close!` calls `head.board/stop!` and then one `serve-step` at the node's last tick reading (`::now`, recorded by `step`). That first stopping tick performs everything observable in spec §4.3: the last answering pass, every session and pending connection closed, `unbind!` requested. Spec §4.3/§9 already argue the board needs no grace, and release never depends on the callback. What is skipped is only observing the host's `:stopped` confirmation before exit. `close-leaves-no-listener-and-no-dial` asserts no listener and every connection closed. If the Architect wants the confirmation loop, it belongs with S3b's exit-path work.
- **D3. Refusal shape.** `head.board/serve` `assoc`s `:identity`/`:name` onto whatever `remote-channel/serve` answers, refusals included; the not-loopback refusal is `{:status :refused :reason :yin.head/not-loopback :spec spec}`. Tests assert `(juxt :status :reason)` for the channel's refusals.
- **D4. `main.cljc` touched** (one require, one call) because it imported the deleted namespace for `loopback?`. It now calls `dao.stream.remote-channel/loopback-literal?`, the predicate's new home.
- **D5. Test command.** The brief's `clojure -M:test -n "<regex>"` passes a regex to `-n` (namespace symbol); I ran `-r` with the regex instead, and added `yin.repl.main-test` because `main.cljc` changed.

## 3. Boundary checks (the reviewer's blocking items)

- `grep -rn ":ws/" src/cljc/yin/vm/ src/cljc/yin/repl/dht.cljc`: no match. The remaining `:ws/` under `src/cljc/yin/` are `yin.repl.serve`, `yin.repl.connect` and two docstring words in `yin.repl.main`, all S3b's REPL migration and untouched here.
- `head/board.cljc` requires `dao.stream` and `dao.stream.remote-channel` only; neither `dao.stream.ws` nor `dao.stream.ws-project`.
- `head.cljc` reads no `:dao.stream.remote/*` key; `:answered` is set only from cursor `ok` / next `ok` / next `gap`.

## 4. Verification

| lane | result |
|---|---|
| `clj -M:kondo --lint` over the 5 sources and 4 test files | 0 errors, 0 warnings |
| `clojure -M:test -n yin.vm.linker.head-board-test` | 14 tests / 122 assertions, 0 failures |
| `clojure -M:test -n yin.vm.linker.head-follow-test` | 23 tests / 739 assertions, 0 failures |
| `-n dao.stream.remote-channel-test -n yin.repl.dht-head-test -n yin.vm.linker.head-board-test` | 51 tests / 456 assertions, 0 failures |
| focused suite `-r "yin\.vm\.linker\.head.*\|dao\.stream.*\|yin\.repl\.dht.*\|yin\.repl\.main-test"` | 539 tests / 4818 assertions, **4 failures, 3 errors, all environmental**: every one is a Node-subprocess leg of `yin.repl.dht-process-test` (`separate-jvm-processes-exchange-content-over-loopback`, `a-reader-follows-the-publisher-s-head-across-processes`, `a-module-published-by-name-is-required-by-name-across-processes`). The spawned Node shell dies at startup with `SHADOW import error ... shadow.js.shim.module$$noble$hashes$blake3.js` (the `@noble/hashes` dependency is not resolvable from this worktree's `node_modules` symlink / stale `target/yin-repl.js`), and the follow-on errors are `IOException: Stream closed` writing to the dead process. No JVM leg and no other namespace failed. Fix before the lane run: `npm ci` in this worktree and rebuild `yin-repl` (not done here: environment changes are the orchestrator's). Full log: `collab/s3a2-scratch/focused.log`. |
| `cljstyle check` | **not run**: the command needs approval this session doesn't have. Kondo is clean; the new code follows the surrounding indentation by hand. The orchestrator should run it, or the pre-commit hook will if it covers formatting. |
| `bb test:cljd`, `bb test:cljs` | **not run** (JVM-only per-iteration rule). CLJD notes for the lane runner: `board.cljc` has no reader conditionals and no `(assoc m :a 1 :b 2)` on a possibly-nil map; the new `head_follow_test` reflection fixture and `dht_head_test` world use only portable code. |

Scratch edit scripts are left under `collab/s3a2-scratch/` (never committed; delete freely).

## 5. Commit staging (for the orchestrator, after sign-off)

Stage by name, never `git add -A` (`node_modules` symlink, `collab/`):

```
docs/design/dao.stream.remote.md
docs/design/yin.vm.linker.dht.head.md
src/cljc/dao/stream/remote_channel.cljc
src/cljc/yin/repl/dht.cljc
src/cljc/yin/repl/main.cljc
src/cljc/yin/vm/linker/head.cljc
src/cljc/yin/vm/linker/head/board.cljc
src/cljc/yin/vm/linker/head/ws.cljc            (deletion, already staged)
test/dao/stream/remote_channel_test.cljc
test/yin/repl/dht_head_test.cljc
test/yin/vm/linker/head_board_test.cljc        (rename, already staged)
test/yin/vm/linker/head_follow_test.cljc
```

Suggested message (spec §8): `feat(yin.vm.linker.head): neutral board over dao.stream.remote-channel, honest :answered, stream-side liveness (cross-machine stream slice S3a-2)`

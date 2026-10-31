Created-GMT: 2026-10-08 05:58:00 GMT
Created-Local: 2026-10-08 12:58:00 ICT

# Track B Slice S4: implementation report

Implementation Engineer: claude-opus-5-5. Branch `stream-crossmachine-s4`
(worktree `/Users/sto/workspace/datomworld-stream-s3a`), based on master
`66756d20`. Brief:
`collab/1791403500000-architect-stream-s4-spec.claude-fable-5-1.findings.md`.
Nothing is committed (owner instruction for this dispatch overrides brief 3.3);
the tree awaits adversarial review and Architect sign-off.

## 1. What changed, per file

+---------------------------------------------+---------------------------------------------------------------------------------------------+
| File                                        | Change                                                                                      |
+=============================================+=============================================================================================+
| src/cljc/dao/stream/ws_project.cljc         | D1. Projection atom gains `:cause nil :opened? false`; `project!` takes the atom, records   |
|                                             | `:ws/opened` for its attachment, answers the terminal kind; `step!` writes `:closed? true   |
|                                             | :cause kind` (or `:dao.stream/end`) in one assoc. Accessors `cause`, `opened?`. Docstring.  |
+---------------------------------------------+---------------------------------------------------------------------------------------------+
| src/cljc/dao/stream/ws.cljc                 | D5 only. `servable-descriptor?`; `make-endpoint` validates with it and seeds `:descriptor`  |
|                                             | in its state atom; `accept-connection!` reads the descriptor from state; `endpoint-bound!`. |
+---------------------------------------------+---------------------------------------------------------------------------------------------+
| src/cljc/dao/stream/remote_channel.cljc     | D2: `projection-causes`, private `lost` (1- and 2-arity), `cause`, `opened?`; every         |
|                                             | transition to `:lost` in `dial-step`/`resolve-step` goes through `lost`; resolving expiry   |
|                                             | forces `:expired`; `attach-identities` records `:cause`/`:opened? false` as specified.      |
|                                             | D4: `::no-port` admits port 0 unless beside a positive `:bind-port`; `:ephemeral?`;         |
|                                             | `observe` finalizes `:spec`, `:descriptor` and the ws endpoint on `:bind-succeeded`, or     |
|                                             | releases, unbinds and refuses `::port-unreported`. Docstrings.                              |
+---------------------------------------------+---------------------------------------------------------------------------------------------+
| src/cljc/yin/repl/connect.cljc              | D3. `refined-terminal`; `observe-terminal` refines `/detached` only; `reattachable?` is     |
|                                             | 2-arity `(connection client)`; `reattach` gates on it. Namespace docstring sentence.        |
+---------------------------------------------+---------------------------------------------------------------------------------------------+
| src/cljc/yin/repl/driver.cljc               | D3. `queueable-terminals` deleted (rationale moved into `remote-routed?`'s docstring);      |
|                                             | `remote-routed?` and `connect-command` use the 2-arity `reattachable?`.                     |
+---------------------------------------------+---------------------------------------------------------------------------------------------+
| src/cljc/yin/repl/serve.cljc                | D6. `ephemeral-port-unsupported` pre-check and code removed; `url` nil while the port is 0; |
|                                             | `observe-server` adopts the bound port before the `Serving` notice; `port-unreported`       |
|                                             | rendered in `refused` and `refused-text`. Namespace docstring sentence.                     |
+---------------------------------------------+---------------------------------------------------------------------------------------------+
| src/cljc/yin/repl/main.cljc                 | D6 banner for `--port 0`. D7: `stop-tick` is `(state server now)` -> `[state server lines   |
|                                             | stopped?]`; JVM `drain-server!` -> `drain!` answering state; Node and Dart stop branches    |
|                                             | initiate `repl.dht/stop!` + `serve/stop!` and drop the `:else (finish!)` shortcut;          |
|                                             | `close-index-store!` and host-loop docstrings.                                              |
+---------------------------------------------+---------------------------------------------------------------------------------------------+
| src/cljc/yin/repl/dht.cljc                  | D7. `stop!`, `stopped?`; `close!` is the last resort (stops first, steps only when not      |
|                                             | stopped); `step-board` prints the board stop line once and composes no board when          |
|                                             | stopping; `step-follow` steps no dial and schedules no redial when stopping.                |
+---------------------------------------------+---------------------------------------------------------------------------------------------+
| src/cljc/yin/vm/linker/head/board.cljc      | D7. `stopped?`.                                                                             |
+---------------------------------------------+---------------------------------------------------------------------------------------------+
| test/dao/stream/loopback_net.cljc           | D8. `listen-on` allocates a free port from 49152 for a bind to 0 and reports it.            |
+---------------------------------------------+---------------------------------------------------------------------------------------------+
| test/** (eight files)                       | 4.1 to 4.6 additions and amendments; see section 2. Also two amendments the brief did not  |
|                                             | name (section 3, items 5 and 6).                                                            |
+---------------------------------------------+---------------------------------------------------------------------------------------------+
| docs/design/dao.stream.remote.md            | 3.0 Lifecycle observation, 3.1 causes paragraph, 3.1 port 0 sentence (2.2, 2.4 amendments). |
+---------------------------------------------+---------------------------------------------------------------------------------------------+
| docs/design/dao.stream.ws.md                | Serving: the port-0 endpoint sentence (2.4 amendment).                                      |
+---------------------------------------------+---------------------------------------------------------------------------------------------+

`rpc.cljc`, `apply.cljc`, `remote.cljc` are unchanged. `embed.cljc` and
`flutter.cljd` are unchanged.

## 2. Acceptance items and evidence

### 2.1 Lanes

+------------------------------------------+-------------------------------------------------------------+-------------------------------------------+
| Lane                                     | Result                                                      | Status                                    |
+==========================================+=============================================================+===========================================+
| `bb test:clj` (full JVM, fast)           | 3818 tests, 241717 assertions, 2 failures, 1 error. Both    | green but for the pre-existing error;     |
|                                          | failures were `yin.vm.linker.head-board-test/a-bind-port-   | the head-board fix is verified by its own |
|                                          | that-is-not-positive-composes-no-endpoint` (fixed after the | namespace run, not by a second full run   |
|                                          | run, section 3 item 6; that namespace now 14 tests, 123     |                                           |
|                                          | assertions, 0/0). The error is pre-existing (section 4).    |                                           |
|                                          | Log: `target/s4-clj.log`.                                   |                                           |
+------------------------------------------+-------------------------------------------------------------+-------------------------------------------+
| `bb test:cljd` (full Dart, fast)         | +3625 -1. The one failure is the same pre-existing          | green but for the pre-existing failure    |
|                                          | `handoff-v2-census-test` (section 4). Log:                  |                                           |
|                                          | `target/s4-cljd.log`.                                       |                                           |
+------------------------------------------+-------------------------------------------------------------+-------------------------------------------+
| `bb test:cljs` (Node)                    | NOT RUN. The lane died with `MODULE_NOT_FOUND`: the fresh   | BLOCKED; the orchestrator must run        |
|                                          | worktree has no `node_modules`, and `npm ci` needs a        | `npm ci` then `bb test:cljs` before       |
|                                          | permission this session does not have. The "Testing <ns>"   | landing                                   |
|                                          | confirmations of 4.8 are therefore not available.           |                                           |
+------------------------------------------+-------------------------------------------------------------+-------------------------------------------+
| `clojure -M:test -i :slow -n             | 2 tests, 16 assertions, 0 failures, 0 errors (fact 4        | green                                     |
| yin.repl.main-test`                      | included). Log: `target/s4-main-slow.log`.                  |                                           |
+------------------------------------------+-------------------------------------------------------------+-------------------------------------------+
| JVM wire and host tests                  | `yin.repl.serve-connect-wire-test` and                      | green                                     |
|                                          | `yin.repl.host.jvm-test` ran inside the full JVM lane, no   |                                           |
|                                          | failure; `yin.repl.host.jvm-test` also alone, 3 tests, 24   |                                           |
|                                          | assertions, 0/0.                                            |                                           |
+------------------------------------------+-------------------------------------------------------------+-------------------------------------------+
| `clj -M:kondo` on the 18 changed `.clj*` | errors 0, warnings 0.                                       | green                                     |
| files                                    |                                                             |                                           |
+------------------------------------------+-------------------------------------------------------------+-------------------------------------------+
| `clj -M:kondo --lint src test`           | errors 27, warnings 67, every one in a file this slice did  | pre-existing; not 0/0                     |
|                                          | not touch (section 4).                                      |                                           |
+------------------------------------------+-------------------------------------------------------------+-------------------------------------------+
| `cljstyle check`                         | NOT RUN: the binary needs a permission this session does    | BLOCKED                                   |
|                                          | not have. One indentation the edits would have broken was   |                                           |
|                                          | avoided by structure (section 3 item 7); the rest of the    |                                           |
|                                          | edits follow the surrounding indentation by hand.           |                                           |
+------------------------------------------+-------------------------------------------------------------+-------------------------------------------+
| 4.7 boundary gate (both greps)           | Both print nothing. The second grep's only raw hits are     | green                                     |
|                                          | `test/yin/repl/host/jvm_test.clj` and `node_test.cljs`,     |                                           |
|                                          | which the gate excludes.                                    |                                           |
+------------------------------------------+-------------------------------------------------------------+-------------------------------------------+

Per-namespace JVM runs after the last edit to each: `dao.stream.ws-test` with
`dao.stream.ws-project-test` 56 tests/259 assertions; `dao.stream.remote-channel-test`
43/340; `yin.repl.connect-test` 17/107; `yin.repl.serve-test` with
`yin.repl.main-test` and `yin.repl.embed-test` 62/421; `yin.repl.dht-head-test`
23/223; `yin.vm.linker.head-board-test` 14/123; every one 0 failures, 0 errors.

### 2.2 Acceptance items (JVM evidence; Dart covered by the full lane)

+------+-----------------------------------------------------------------------------+--------------------------------------------------------------+
| Item | Test                                                                        | Note                                                         |
+======+=============================================================================+==============================================================+
| 4.1  | `remote-channel-test`: `a-dropped-connection-is-lost-dropped`,              | all nine, plus                                               |
|      | `an-ended-stop-is-lost-ended-when-the-end-is-missed` (asserts close code    | `a-name-the-peer-does-not-serve-is-lost-without-a-cause`     |
|      | 4000), `a-refused-connection-is-lost-unreachable` (identities and name),    | (section 3 item 2). Item 6 reads the session handle's        |
|      | `an-expired-link-is-lost-expired`,                                          | descriptor identity through `stream/descriptor`.             |
|      | `a-never-opening-connection-is-lost-expired-unopened` (identities and       |                                                              |
|      | name), `an-ephemeral-serve-advertises-the-bound-port`,                      |                                                              |
|      | `an-ephemeral-bind-that-reports-no-port-is-refused`,                        |                                                              |
|      | `port-zero-beside-a-positive-bind-port-is-no-port`,                         |                                                              |
|      | `dialing-port-zero-is-lost-invalid-descriptor`                              |                                                              |
+------+-----------------------------------------------------------------------------+--------------------------------------------------------------+
| D8   | `loopback_net/listen-on`                                                    | exercised by 4.1 item 6 and 4.5                              |
+------+-----------------------------------------------------------------------------+--------------------------------------------------------------+
| 4.2  | `ws-project-test`: `the-cause-is-the-first-terminal-event`,                 | as specified                                                 |
|      | `the-medium-end-is-its-own-cause`,                                          |                                                              |
|      | `opened-is-recorded-and-another-attachments-opened-is-not`                  |                                                              |
+------+-----------------------------------------------------------------------------+--------------------------------------------------------------+
| 4.3  | `ws-test`: `a-served-descriptor-may-name-port-zero-and-a-dialed-one-may-    | in `test/dao/stream/ws_test.cljc`, the namespace that        |
|      | not`, `endpoint-bound-renames-later-session-handles`                        | composes `make-endpoint`                                     |
+------+-----------------------------------------------------------------------------+--------------------------------------------------------------+
| 4.4  | `connect-test`: renamed `a-connection-that-never-opens-is-a-transport-      | see section 3 item 3 for the drain test's fixture            |
|      | error-at-give-up-after` (RPC terminal still `detached`, cause `:expired`,   |                                                              |
|      | `opened?` false, refined `:transport-error`, refused case `:unreachable`);  |                                                              |
|      | `an-ended-signal-missed-during-the-drain-is-ended-not-detached`;            |                                                              |
|      | `a-dropped-connection-stays-detached-and-reattachable`; 2-arity             |                                                              |
|      | `reattachable-is-true-for-a-detached-client-only`;                          |                                                              |
|      | `every-terminal-reason-maps-...` asserts an unrefined `detached`            |                                                              |
+------+-----------------------------------------------------------------------------+--------------------------------------------------------------+
| 4.5  | `serve-test`: `an-ephemeral-bind-advertises-the-bound-port` (url nil        | `an-ephemeral-bind-with-an-advertised-port-binds-port-zero`  |
|      | before the bind, Serving line names the allocated port, one round trip),    | unchanged and green                                          |
|      | `an-ephemeral-bind-whose-host-reports-no-port-fails`; `main-test`:          |                                                              |
|      | `an-ephemeral-bind-serves-and-the-serving-line-names-the-port` (43210),     |                                                              |
|      | banner `testing` block for `{:port 0}`                                      |                                                              |
+------+-----------------------------------------------------------------------------+--------------------------------------------------------------+
| 4.6  | `dht-head-test`: `the-board-exit-is-driver-paced`,                          | see section 3 item 4 for the reader's loss line              |
|      | `close-is-the-last-resort-and-idempotent`                                   |                                                              |
+------+-----------------------------------------------------------------------------+--------------------------------------------------------------+
| 4.7  | the two greps                                                               | both empty                                                   |
+------+-----------------------------------------------------------------------------+--------------------------------------------------------------+

## 3. Deviations from the brief, with reasons

1. **`lost` reads the projection before `:gone?` (substantive; needs
   Architect review).** The brief's `lost` checks `(:gone? ch)` first and
   calls that `:expired`. Implemented as written, every drop, ended stop
   and refused connection came out `:expired`: `dao.stream.remote`'s
   link sets `:channel-gone?` on *any* loss, including the ring end that
   follows a projection close (`remote.cljc` line 711), and
   `ws-project/dial-step!` copies it to `:gone?` in the same step. The
   implemented order is: a closed projection names the cause; otherwise
   `:gone?` is `:expired`; otherwise, with no channel at all, a
   transport-error outcome is `:unreachable`. This is exact on the
   dialing end because only the projection closes that ring, so the link
   can lose an *open* ring only at a deadline. At the expiry tick the
   projection is still open (the handle close the dial issues lands only
   after the next pump), so the expiry reads `:expired`. The cause is
   still computed once, at the transition. The alternative, reading
   `link-step!`'s `:dao.stream.remote/expired` answer, would have meant
   changing `ws-project` beyond D1. The brief's "nothing else in
   ws-project changes" ruled that out.
2. **Every `:lost` transition in `resolve-step` goes through `lost`.**
   The brief names only the attached branch, the resolving expiry and
   `attach-identities`. A name dial on a refused connection is lost
   inside `resolve-step` (the closed ring answers the resolve
   channel-gone, non-retryable), and 4.1 item 3's `testing` block needs
   `:unreachable` there. A resolve answered not-found on an open channel
   keeps `:cause nil`, pinned by
   `a-name-the-peer-does-not-serve-is-lost-without-a-cause`. The
   no-channel `:unreachable` clause is guarded by `(nil? ch)` so that
   not-found is never misread as reachability.
3. **The drain test blackholes the client.** In
   `an-ended-signal-missed-during-the-drain-is-ended-not-detached`,
   closing the answers ring and then stopping (the brief's fixture) lets
   the stop's last answering pass deliver `end`, so RPC itself reports
   `ended` and the refinement is never exercised. The test blackholes
   `:client` before `stop!`. The `end` answer is then genuinely missed,
   while the 4000 close still arrives, because loopback `close-conn!`
   bypasses the blackhole. That is the scenario 1.1 describes. The
   default `:drain-grace-ms` is already 0, so no `:bounds` is passed.
4. **The reader's loss line in `the-board-exit-is-driver-paced`.** The
   reader reports the publisher's stop through its dial line ("cannot
   follow ... refused, closed, or stopped answering"), not the head
   follower's `source-lost` line, because the dial is lost before the
   follower reads its handle. The test counts either line.
5. **`remote-channel-test/no-transport-and-no-port-are-refusals-as-data`
   amended**: 0 removed from its `::no-port` cases (D4 makes it
   ephemeral). The brief did not name this amendment; it is forced by D4.
6. **`yin.vm.linker.head-board-test/a-bind-port-that-is-not-positive-
   composes-no-endpoint` amended** for the same reason: 0 removed from
   the refused ports, and a `testing` block added asserting that port 0
   is `:starting` and binds once. The board's spec port is the DHT's
   bound UDP port, always positive in the shell, so no shell behaviour
   changes.
7. **Stopping guard placement.** The brief puts the dial guard at the top
   of `step-link`. It is placed in `step-follow` instead, around the
   `step-links` call, plus a guard on the `source-lost` redial
   scheduling, so that `step-links` keeps its original text and
   indentation (no `cljstyle` in this session). A stopping node keeps
   `::follow :links` at `{}`. `step-board` also composes no board for a
   stopping node, so a late `:bound` cannot start a listener after
   `stop!`.
8. **`serve/url` guards with `(when-not (= 0 port) ...)`**, not `(when
   (pos? port) ...)`. The `inert` endpoints built before validation can
   carry a nil port, and `pos?` would throw on nil.
9. **`stop-tick` callers in `main_test` changed.** The two unit tests
   `a-quit-shell-stops-the-endpoint-before-the-host-exits` and
   `an-endpoint-that-never-bound-is-not-waited-on` call the new 3-arity
   with a booted, DHT-free state. 4.6 says "main_test's stop tests run
   unchanged", but keeping a 2-arity alive would have been a
   compatibility shim (1.2 invariant 7). Their assertions are unchanged.
10. **`ws.cljc` diff size.** D5 is about 25 added lines including
    docstrings, more than the brief's "eleven lines or fewer", which the
    brief's own code for `servable-descriptor?` and `endpoint-bound!`
    already exceeds. The diff is D5 and nothing else.
11. **Port-unreported text.** The text is rendered through a sibling of
    `bind-failed-text` (`port-unreported-text`): ";; endpoint bind failed:
    the host reported no bound port for an ephemeral bind <detail>".
12. **No commit.** The dispatch instruction overrides brief 3.3. `collab/`
    is not staged.

## 4. Pre-existing findings

- No separate baseline run of `bb test:clj` was made on the untouched
  tree (3.1 step 3): edits began before it. Each failure found is
  attributed below by its cause.
- `yin.vm.ucf.handoff-v2-census-test/a-version-2-body-is-the-same-bytes-
  on-every-host` errors on the JVM (`FileNotFoundException:
  test/resources/yin/vm/ucf/handoff-v2.txt`) and fails on Dart. The
  resource is not tracked by git (`git ls-files test/resources/yin/vm/ucf`
  lists only the v1 files), and the namespace is untouched by this slice.
- `clj -M:kondo --lint src test` reports 27 errors and 67 warnings, all in
  untouched files (for example `src/cljc/yin/repl/host.cljc:15`,
  `src/cljd/dao/postgraphics/flutter.cljd`,
  `src/cljd/dao/stream/transit/cljd.cljd`,
  `test/dao/stream/waitset/driver_test.cljd`). The 0/0 gate of 4.8 holds
  for the changed files only.
- The worktree has no `node_modules` (project memory: npm ci in new
  worktrees).

## 5. Open questions

1. Deviation 1: is "closed projection first, then `:gone?`" the intended
   semantics, or should the expiry be recorded explicitly (for example
   `ws-project/dial-step!` keeping `:expired?` from `link-step!`'s
   `:dao.stream.remote/expired` answer)? The explicit form is more
   robust if a future transport closes the ring from outside the
   projection (brief section 7, first risk). It costs one more line in
   `ws-project` beyond D1.
2. A stopping DHT node's own follower may still print a `source-lost`
   line ending "dialing it again" for a source whose dial `stop!` closed,
   although no redial is composed. The line is accurate about the loss but
   not about the redial. Should a stopping node suppress follower lines?
   Nothing in this slice does.
3. Before landing: `npm ci`, `bb test:cljs` (confirm "Testing
   dao.stream.ws-project-test", "dao.stream.remote-channel-test",
   "yin.repl.connect-test", "yin.repl.serve-test",
   "yin.repl.dht-head-test"), `cljstyle check`, and one more full
   `bb test:clj` to cover the head-board amendment within a full run.

# Architect review of slice C2 (migrate) — claude-fable-5-1

Created: 2026-10-20 18:05 +07 (review run 18:05–19:00 +07)
Worktree: `/Users/sto/workspace/datomworld-codec-c2`, branch `codec-c2`, base `eacaed90` (C1), 26 uncommitted files, +387/−157.
Governing: `docs/design/dao.stream.codec.md` §6.4, §7.1 C2/C3, §7.2, §7.3, §10 OQ1; brief `collab/1792495000000-architect-c2-brief.md`; engineer report `collab/1792495500000-engineer-codec-c2.claude-sonnet-5-5.findings.md`.

## Verdict: SIGNED OFF WITH CHANGES

Two changes are required before commit (R1, R2 below); both are small and test-only or doc-only. Everything else the brief and the design row ask of C2 is present and verified by my own lane runs.

**Sign-off scope caveat.** §11 of the design: a Claude reviewer may review Claude-authored work but may not be the sole sign-off. The engineer is claude-sonnet-5-5 and I am claude-fable-5-1. The codex/astra review (`1792497600000-reviewer-codec-c2.gpt-6-astra.stdout.log`) ran out of credits mid-run after reproducing the `gen:python-antlr` finding and starting the lanes; it produced no verdict. A non-Claude sign-off is therefore still outstanding per the design's own rule; the orchestrator decides whether the standing commit-on-Architect-sign-off rule or §11 governs here.

## 1. Codec migration and configurability (brief §3.1)

| Check | Result |
|---|---|
| `yin.repl` wire default is `dao.stream.cbor/profile` | Yes. `connect/open` `:or {codec cbor/profile}` (`connect.cljc:343`), `serve/serve!` `:or {codec cbor/profile}` (`serve.cljc:208`); `serve!` threads `:codecs [codec]` to `remote-channel/serve` (`serve.cljc:250`). |
| CLI `--codec cbor|transit` | Yes. `main.cljc` `parse-codec` refuses other values with a message; `--codec` is parsed at `:567`, threaded into `boot-server` → `serve!` at `:707`; `help-lines` documents it at `:736`; `state.cljc` saves it beside `--port`. Tests: `codec-flag-selects-the-served-wire-profile`, `help-documents-every-flag-and-subcommand`. |
| `(connect url :codec :cbor|:transit)` at the prompt | Yes. `driver.cljc` `connect-command` accepts `(connect "url")` or exactly `[:codec k]` where `k` names a `connect/codec-profiles` entry; anything else answers `connect-usage`, which now documents the option. Reattach uses the named codec when given, else the connection's own (`connect/reattach` 4-arity). Test: `connect-with-an-unknown-codec-answers-with-its-usage`. |
| `serve`/`connect` pass explicit profiles | Yes; the default lives in `yin.repl`, and `remote-channel` receives an explicit `:codecs`/`:codec` on every yin.repl path. `serve-connect-wire-test` (JVM) now runs the whole REPL wire over CBOR, which is the slice's live CBOR proof for yin.repl. |
| `yin.repl.dht` / `head.board` | `head.board/serve` and `dial` take `:codecs`/`:codec` and pass them through; `dht.cljc` names Transit for the board via `(:transit connect/codec-profiles)`. The board wire is unchanged, which is the right call for a migrate slice (the owner's CBOR direction was scoped to the REPL wire). The indirection through `connect` is acceptable: it satisfies C3's `grep dao.stream.transit src/` gate literally and keeps one place where profiles are named. Not a blocker; see N3. |
| `host.cljs` `bind!` forwarding `:codecs` | Correct and a real latent bug fix: without it a Node REPL would have advertised CBOR and listened Transit-only. `node/listen!` reads `:codecs` (`node.cljs:246-251`); JVM passes options straight through; Dart `bind!` already read `:codecs` (`dart.cljd:223-226`). Verified in source. |
| Circular require (`dht` → `connect`) | None; `connect` does not reach `dht`. |

## 2. Call-site migration (§7.2)

- **One-arity gates.** `ws/descriptor?` and `ws/servable-descriptor?` are called two-arity at `ws_test.cljc:573-576` and `remote_channel_test.cljc:187`. No one-arity caller remains in `src/` or `test/`.
- **Attachers/endpoints/listeners.** Every `make-attacher` carries `:codec`, every `make-endpoint` `:codecs`, every `listen!`/`bind!` `:codecs`, every `rc/serve|dial` and `head.board/serve|dial` a profile, across `ws_test`, `ws_codec_test`, `ws/{jvm,node,dart,browser}_test`, `ws_project_test`, `ws_project_jvm_test`, `ws_project_cross_{jvm,node}_test`, `ws_project_peer`, `remote_channel_test`, `loopback_net`, `yin.repl.host.jvm_test`, `connect_test`, `head_board_test`. I re-ran the engineer's paren-aware scan (`/tmp/c2tool/gate.py`): 9 hits, all deliberate (the Transit-default assertion at `remote_channel_test.cljc:244` that C3 rewrites; `remote_route_test` dials with no ws transport; UDP `datagram`/`jing.dht` `bind!`).
- **Parameterized rerun.** `remote_channel_test.cljc` `the-suite-over-cbor` reruns 43 of the 46 deftests with every helper composing CBOR via the `current-profile` atom (reset in `finally`). The three left out are exactly the two that name their own profiles and the rerun itself. Correct and complete.
- **Seams (R1, required).** §7.2 lists the `ws_test` seams needing a name at `:224-234,243,272,304,336,427,532-533,583`. Four of those sites were **not** migrated: the sockets handed to `accept-connection!` at `ws_test.cljc:311`, `:343`, `:434` and `:536` (the `socket` fn used at `:538-539`) still carry no `:ws/subprotocol`. `dart_test.cljd:110` (`dart/socket-view`, which builds `{:send! :close!}` only) is in the same state. `accept-connection!` resolves a nil offer to `transit/profile` (`ws.cljc:641-644`), so these five tests still reach a default, which C2's acceptance forbids ("nothing reaches them") and C3 removes ("a seam without a name is refused"), at which point they would fail with `:ws/unsupported-subprotocol`. The engineer's grep gate scanned constructors but not seams, which is why this slipped.

## 3. Grep gate

Passes for the heads the design names (`make-attacher`/`make-endpoint`/`listen!`/`bind!`/serve/dial) with the deliberate exceptions above. It does not cover the no-offer seam default; R1 closes that and R3 asks C3's gate to include it.

## 4. §7.3 bidirectional old/new peers

Old peer = tag `f0-old-peer` (47f43405) in `../datomworld-codec-c2-old` (old library, new `ws_project_peer.cljc` harness copied in; the old `ws` API already took `:codec`/`:codecs`, so the wire code under test is the old one).

| lane | old serves / new dials | new serves / old dials | evidence |
|---|---|---|---|
| JVM↔JVM | transit PASS, cbor PASS | transit PASS, cbor PASS | engineer's `/tmp/c2tool/jvm_pair.py` driver; not re-run by me (the Node run below covers old-JVM in both roles) |
| Node↔JVM | transit PASS, cbor PASS | transit PASS, cbor PASS | **re-run by me**: `DAO_STREAM_PEER_DIR=../datomworld-codec-c2-old`, Node lane 1224/13339, 0 failures, no `SKIPPED` line in `target/profile/sub-cljs.log` |
| Dart | **absent** | **absent** | `build/ws-project-peer` not built; `a-dart-dialer-reads-a-jvm-server-over-{transit,cbor}` SKIP on the JVM lane. Reported as a gap, not as covered. |

Three-claim statement (§7.3), which the engineer's report does not give in this form:
- **Wire contract unchanged** (names, negotiation, envelope, lifecycle): holds on JVM and Node by the frozen negotiation fixtures plus the old/new runs over both profiles; Dart coverage absent.
- **CBOR bytes unchanged**: holds by `codec_fixtures_test` (decode and byte-exact re-encode) on all three lanes.
- **Transit interoperable**: holds on JVM and Node by the old/new runs; semantic, not byte-level, as the design states.

What the peer runs exchange is the existing toy (one value each way, probe, end), not the §7.3 "envelope corpus plus one Jing-wrapped payload". The corpus proof is the fixture test. I accept this for C2: the live old/new runs prove negotiation and lifecycle, the fixtures prove the corpus bytes, and a corpus-over-live-peers scenario is better added once at C4 where keys actually move (N4).

Note on what "wire unchanged" means for this slice: the dao.stream wire is unchanged, but by owner direction the **yin.repl composition value changed** from Transit to CBOR. That is a behaviour change at composition time, not on the wire, and it is intended; the slice report should say so in those words.

## 5. Verification run (mine, in this worktree, one lane at a time)

`bb test:sub:*` cannot run at HEAD: `src/dev/affected.clj:523,802,858` names `gen:python-antlr`, which `bb.edn` does not define (pre-existing, also hit by codex). I ran the same lane entry through a copy of `affected.clj` with that task name substituted by `gen:antlr`; no repo file was changed (`git status` shows only the slice's 26 files).

| lane | before (engineer, clean HEAD) | after (mine) | status |
|---|---|---|---|
| CLJ | 1049 / 16468 | 1097 tests / 16820 assertions | 0 failures |
| CLJS (old peer as JVM child) | 1175 / 12948 | 1224 / 13339 | 0 failures, no SKIPPED |
| CLJD | 1030 | 1034 tests (Dart prints no assertion count) | 0 failures, "All tests passed!" |

Counts are not unchanged; the growth is the named new tests (43 CBOR reruns, two CBOR/Transit variants in each cross-host file, three yin.repl tests). The design's "counts unchanged plus named tests" is satisfied in substance.

`dao.stream.codec-fixtures-test` is in the selection and passes on every lane I ran. `clj -M:kondo` on the 26 changed files: 0 errors, 6 warnings, all pre-existing (`host/websocket` unresolved-var on hosts, one http-kit alias).

A first JVM run failed in prerequisite `build:yin-repl-peer` (cljd compiler "Invalid token") because I had started the Node lane concurrently and both lanes built the same Dart artifact. The rerun alone passed. `bb test:sub` runs lanes in parallel by design; the shared `build:` prerequisite is a latent race worth a note in `build-n-test.md` (N5), not a slice defect.

## Required changes (before commit)

- **R1** (`test/dao/stream/ws_test.cljc:311,343,434,536`; `test/dao/stream/ws/dart_test.cljd:110`): put `:ws/subprotocol seam` on each socket (ws_test already defines `seam`); in dart_test, `(assoc raw :ws/subprotocol (codec/codec-name transit/profile))` or extend `socket-view`. Then re-run `bb test:sub:clj dao.stream` and the Dart lane for `dao.stream`.
- **R2** (`docs/design/dao.stream.codec.md`): record the owner's OQ1 decision in §10 (CBOR, 2026-10-20, from the C2 brief), amend §6.4's "until the owner records a choice, they pass Transit" and §11's "C2 passes Transit explicitly", and note in the C2 row that the yin.repl default moved to CBOR in C2 rather than C5 (C5 then shrinks to whatever remains, possibly nothing). The doc currently contradicts the code.

## Non-blocking notes

- **N1** `ws_project_peer -main`: `--codec` with an unknown value yields `codec nil` and silently falls to the host default; refusing it (print and exit non-zero) would make the C3 contract bite here too. Also `--codec` must follow the positional args, which is fine for the harness but undocumented.
- **N2** `the-suite-over-cbor` shares a mutable atom; acceptable because lanes run single-threaded, as the engineer notes. If a lane ever runs tests in parallel it breaks silently; a comment above `current-profile` says so.
- **N3** `yin.repl.dht` is a third production site that names a codec (via `connect/codec-profiles`). Fine for now; if the board is ever meant to follow `--codec`, thread it from `main` the way `serve!` does rather than pinning Transit.
- **N4** A corpus-over-live-peers scenario (one peer command that round-trips the fixture envelopes) would turn the §7.3 proof from "toy exchange" into the design's literal statement. Do it once, for C4.
- **N5** `gen:python-antlr` vs `gen:antlr` (pre-existing; blocks every `bb test:sub*` selection reaching `yin.repl`) and the `build:yin-repl-peer` race between lanes both belong in a small build fix outside this slice.
- **N6** `yin.vm.docs/yin.repl.md` lacks `--codec` and `(connect … :codec …)`; the engineer called it out of scope. It should go in the same commit as R2 or a docs commit right after.
- **N7** Cosmetic: a few migrated maps have `:codec transit/profile` prepended on the same line as the next key, and `ws_project_cross_node_test.cljs` has off-by-a-few-columns indentation after the `deftest`→`defn-` change.

## Scratch state left on disk

Engineer's: `../datomworld-codec-c2-base`, `../datomworld-codec-c2-old` (both untouched by me), `/tmp/c2tool/{gate,mig,jvm_pair}.py`. Mine: `/tmp/c2-review-affected.clj`, `/tmp/c2-review/*.log`. The worktree's `bb.edn` is unmodified.

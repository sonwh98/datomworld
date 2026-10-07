Slice 6 (UDP channel) implemented as new files only:

- `src/cljc/dao/stream/udp.cljc` — the channel: `descriptor?`, `address-key`, `make-port`/`send-value!`/`send-to!` (write side: single-datagram fast path, fragmentation over the 1200-byte budget, `:dao.stream/transport-error` + `:dao.stream.remote/oversize` refusal beyond `:dao.stream.udp/max-message-bytes`, default 64 KiB), `receive!` (reassembly + the deposit-event shape), `projection`/`step!` (this channel's `ws-project`, one channel ring per attachment), `make-attacher`/`UdpHandle`.
- `src/clj/dao/stream/udp/jvm.clj`, `src/cljs/dao/stream/udp/node.cljs`, `src/cljd/dao/stream/udp/dart.cljd` — thin socket adapters (DatagramSocket / dgram / RawDatagramSocket); no protocol logic.
- `test/dao/stream/udp_test.cljc` — 10 portable host-free tests: fragmentation/reassembly round trip, the 48 KiB toy inside the 64 KiB default, loss-of-any-part + resend recovery, oversize refusal (unsent), reassembly eviction (oldest first), source-address carriage, keying by [attachment, direction, id] with no cross-delivery, and per-attachment projection routing.

Minimal-reading notes (genuinely ambiguous points in 3.2):
- The design's reassembly key lists `[attachment identity, source address, direction, id]`, but the literal fragment-envelope shape it shows carries only 4 keys. For UDP, attachment identity *is* the remote host:port (stated later in 3.2), so identity and source collapse to one string; I added `:dao.stream.udp/direction` (`:request`/`:answer`, derived from `:dao.stream.remote/op`'s presence) to the envelope, since without it two fragmenting messages sharing an id and address (this peer as both asker and mirror) would corrupt each other's reassembly.
- `:dao.stream.udp/max-partial-messages` has no stated default (only max-message-bytes does); I chose 64 as this implementation's own bound and documented it as such.
- The design describes one deposit medium plus a `ws-project`-style per-attachment forwarding step; I implemented both (`receive!` deposits, `projection`/`step!` forwards), matching item 1's "deposit adapter shape" literally.

Verify, sequential and solo:
- JVM: `clj -M:test` → 2276 tests / 183262 assertions / 0 failures, 0 errors (baseline 2254/183121/0 plus other in-flight sessions' own added tests plus mine; no failures anywhere, `dao.stream.udp-test` included and green).
- Node: `bb test:cljs` → 2185/49891/0, `dao.stream.udp-test` ran and passed.
- Dart: `bb test:cljd` (after `rm -rf test/cljd-out`) → 2145 tests, "All tests passed!", `dao.stream.udp-test`'s 10 tests all green; fixed two DYNAMIC WARNINGs in `dart.cljd` (untyped `.then` callback params) by type-hinting `socket`/`datagram`.
- `clj -M:kondo --lint` clean (0 errors, 0 warnings) on every new file, including the `.cljd` one.
- Pure ASCII, ≤80 columns verified on every new file.
- `cljstyle` could not be run in this session (the tool required an approval this non-interactive session couldn't grant); formatting was instead hand-matched to `dao.stream.remote.cljc`/`dao.stream.ws-project.cljc`'s existing indentation conventions.
- No commit/stage/checkout/reset/stash; only the five listed new files plus the fix to `dart.cljd` were touched; a scratch debug script used to diagnose one test bug was deleted before finishing.

Status: COMPLETE

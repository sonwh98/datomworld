# DHT S3 sockets and chunks — engineering report

Worktree: `/Users/sto/workspace/datomworld-dht-s3` (`dht-s3`). No files staged or committed.

## Changes

- Added `dao.stream.chunks`, a transport-neutral byte split and bounded absorb utility. Both `dao.stream.udp` and `dao.jing.dht` call it; each retains its own envelope, identity, and source-qualified key. The declared fallback was **not** used because the two protocols share the byte-level mechanics cleanly.
- DHT messages above the datagram budget now use `:chunk` records. The encoded whole is rejected above `max-message-bytes` before any send. A store peer counts only after every chunk append answers `ok`. Reassembly checks the cookie or pending reply before allocation and bounds partial count, per-source share, bytes, and tick age.
- Added optional `:dao.jing.dht/bind-host` (loopback default) and reject hostname or opposite-family destinations before appending to the raw socket.
- Removed legacy `IDhtNet`, `lookup`, `create-content-dht`, `dao.jing.dht.node`, and its tests as required by S2 ruling (b).
- Jing Base64 functions now delegate to `dao.stream.base64`; updated the CBOR Remote and DHT design text.

## S3 acceptance evidence

| Acceptance item | Evidence and limit |
|---|---|
| Two and three peers, small queries and multi-datagram segments with loss and reordering | Existing S2 mesh tests cover small queries. `three-real-loopback-sockets-exchange-chunked-store` passes on JVM. `chunked-store-crosses-multiple-datagrams`, `reordered-request-chunks-reassemble`, and `lost-request-chunk-never-materializes-a-partial-store` pass in the mesh. The latter tests use four total mesh nodes; live mixed-host loss/reordering was not exercised. |
| Largest legal `:find` reply fits one datagram | `largest-find-reply-fits-one-datagram` encodes eight 39-character IPv6 literals, full ids and cookie, and the largest safe query id within 1200 bytes. |
| Oversize refuses before any send | Existing `every-at-once-unacknowledged-reason` checks no local insert; `send-datagram!` checks encoded whole before splitting and appending. |
| Malformed, duplicate, inconsistent chunks within bounds | `malformed-duplicate-inconsistent-and-overbound-pieces` covers invalid indexes, duplicates, count disagreement, byte limit, and oldest eviction. `chunk-partials-have-a-per-source-share` covers the quarter-share; tick age is implemented but has no direct new test. |
| Count a chunked peer only after every chunk `ok` | `a-refused-later-chunk-does-not-count-the-peer` refuses part 1 to one of two peers; no `sent` fact is emitted for that address. |
| One shared transport-neutral utility | `dao.stream.chunks/split` and `/absorb` are used by UDP and DHT; `dao.stream.udp-test`, `dao.stream.chunks-test`, and DHT tests pass. |
| Real covered-index nodes against `max-message-bytes` | A `dao.space.index/publish-index!` build with branching factor 512 and 512 distinct datoms emitted two covered-index node values plus a manifest. Largest canonical value: 26,497 bytes; maximal-cookie `:store` wire value: 26,720 bytes, under the 65,536-byte default. This is a measurement, not a bound for arbitrary application values. |
| JVM↔Node and JVM↔Dart same wire tests | The CLJC DHT suite and `canonical-chunk-wire-vector-is-identical-on-all-hosts` exercise the same JVM-minted canonical CBOR vector on JVM, Node, and Dart. Live cross-process JVM↔Node and JVM↔Dart sockets were **not** tested. |
| Update `dao.jing.cbor.md` | Remote and DHT section now describes CBOR byte-string wire fields, chunk records, cookie gate, 1200-byte datagrams and 65,536-byte message bound. |
| Repoint and prove Base64 | Jing delegates to `dao.stream.base64`; `dao.stream.base64-test` checks both implementations on every RFC 4648 vector and strict round trips on all three hosts. |
| Delete legacy surface | `rg` across `src` and `test` finds no `IDhtNet`, `create-content-dht`, `dao.jing.dht.node`, or `dht/lookup`; files deleted together. |

## Red phase and mutation probes

The new chunk utility test first failed to load because the namespace did not exist. Four deliberate source mutations were each caught by focused tests, then restored: invert the envelope-fit check (datagram size assertion fails); disable duplicate detection (held-byte assertion fails); report `ok` for a refused later store chunk (`sent` assertion fails); double the per-source partial share (bound assertion fails). The new socket, wire-vector, and other acceptance tests were not all observed red before implementation, so the brief's strict test-first rule is only partially satisfied.

## Checks

- `clj -M:kondo --lint` on changed source and tests: **0 errors, 0 warnings**.
- `clj -M:test`: **2464 tests, 185572 assertions, 0 failures, 0 errors**.
- `bb test:cljs`: **2375 tests, 51931 assertions, 0 failures, 0 errors**.
- `bb test:cljd`: **2334 tests, all passed**, after deleting `test/cljd-out`.
- Focused JVM suites (`dao.jing.dht-test`, `dao.jing.dht.socket-test`, `dao.stream.chunks-test`, `dao.stream.udp-test`, `dao.stream.base64-test`): 53 tests, 456 assertions, 0 failures, 0 errors before the final wire-vector addition; the wire-vector test then passed separately.
- `git diff --check`: clean.

The Dart runner emits a Babashka `sysctl failed` teardown stack trace under this sandbox. The lane verdict is taken from its test result and exit code, not that teardown message.

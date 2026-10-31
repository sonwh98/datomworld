Completed-GMT: 2026-09-27 10:57:46 GMT
Completed-Local: 2026-09-27 17:57:46 +07 (Asia/Ho_Chi_Minh)

All three gate findings fixed; both P1s verified by tests that fail on the pre-fix code, lanes green. Only `src/cljc/dao/stream/udp.cljc`, `src/clj/dao/stream/udp/jvm.clj`, `test/dao/stream/udp_test.cljc` edited, plus new `test/dao/stream/udp/jvm_test.clj`; `dart.cljd`/`node.cljs` untouched; no git operations; no leftover diagnostics (scratch size computation ran as a `clojure -M -e` one-liner).

**P1 — reassembly rejected valid near-limit messages (udp.cljc:309-354).** The old `parts × max-datagram` claim gate (old line 296) is gone. `absorb-fragment!` now tracks `:bytes-total` per partial entry (init udp.cljc:330-331, accumulated at :339), drops the whole partial entry the moment accumulated chunk bytes exceed `:dao.stream.udp/max-message-bytes` (:340-343 — it can never complete within the bound), and gates delivery on the completed payload's actual length (:353). Proof the case was real: a 64,500-char message encodes to 64,627 bytes (< 65,536, send side accepts) but fragments into 63 parts, so the old bound saw 63 × 1200 = 75,600 and would have dropped it. Duplicates are now ignored and a fragment disagreeing with the held parts count is dropped (:337-338), so the state is never torn; `forget-partial!` (:292-300) unifies removal, and `evict-oldest!` (:303-306) is behavior-identical on it.

**P1 — DatagramPacket length reuse truncation (jvm.clj:30).** The receiver resets `(.setLength packet (alength buf))` before every `.receive`; `.receive` leaves the packet's length at the last datagram's size, which truncated any longer successor.

**P2 — unvalidated fragment fields (udp.cljc:273-289, 385).** `well-formed-fragment?` validates `:part`/`:parts` as integers with `1 <= :parts`, `0 <= :part < :parts`, `:direction` one of the two the write side mints, and `:bytes` a host byte payload via portable `cbor/byte-payload?` (byte[] / Uint8Array / Uint8List). `receive!` applies it before `absorb-fragment!`, so no partial state is touched and nothing throws; malformed datagrams drop silently, same as malformed CBOR.

**Tests added.** Host-free in `test/dao/stream/udp_test.cljc`: `fragmentation-round-trip-near-limit` (line 141; 64,500-char message, asserts parts cross the budget and the message delivers) and `malformed-fragments-drop-before-partial-state` (line 179; 7 malformed shapes — parts 0, part -1, part >= parts, string part, nil parts, bogus direction, string bytes — each dropped nil, `:partial` state stays `{}`, nothing deposits, and a real message interleaved with the set still completes). JVM adapter in `test/dao/stream/udp/jvm_test.clj`: `short-datagram-then-long-datagram-arrive-whole` over a real loopback socket via `udp.jvm/bind!`, both datagrams whole.

**Verification (sequential, solo, all under mise).**
- JVM `clojure -M:test`: Ran 2,279 tests containing 183,279 assertions — 0 failures, 0 errors (baseline + exactly my 3 tests/15 assertions; +2 assertions sit in other sessions' untracked in-flight files, not mine).
- Node `bb test:cljs`: Ran 2,187 tests containing 49,903 assertions — 0 failures, 0 errors (baseline +2 tests/+12 assertions = exactly mine).
- Dart `bb test:cljd`: "All tests passed!" at +2148, exit 0 (baseline +my 2; `dao.stream.udp-test` shows exactly 12 entries, all green; +1 test elsewhere is other sessions' untracked work).
- `cljstyle check` clean on all four files; `clj -M:kondo --lint` 0 errors, 0 warnings; pure ASCII, <= 80 columns verified.

Status: COMPLETE
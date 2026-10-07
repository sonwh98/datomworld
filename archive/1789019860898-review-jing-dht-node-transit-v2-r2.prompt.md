Created-GMT: 2026-09-10 06:15:00 GMT
Created-Local: 2026-09-10 13:15:00 +0700 (Asia/Bangkok)
Coding-Agent: codex
Session-ID: 01a089e4-c4a4-7f62-a459-aad08544278b
# Task: dao.jing.dht.node off dao.stream.transit v1 (D8) — r2, confirm fixes
Role: Routine Review
Implementers:
- Model: interactive (claude sonnet 5) | Assigned: 2026-09-10 12:00:00 +0700 | Status: active | Rationale: same as r1

Both findings addressed:

- **P1** (`node_test.cljc` still required v1 `dao.stream.transit`): the
  ns require now uses `dao.stream.transit` for the general/portable
  encode path (used by the existing malformed-`:from` test — that payload
  is portable, so v2 encode does not throw), plus a new `[cognitect.transit
  :as raw-transit]` require and a local `raw-encode` helper that writes
  straight through cognitect.transit, bypassing v2's `ensure-portable!` on
  the outbound side too — needed because the hostile test's whole point is
  a peer sending a tag v2 would refuse to encode itself. No production or
  test code in this namespace requires v1 `dao.stream.transit` anymore.
- **P2** (test only proved liveness, not that the datagram was rejected):
  the new test now opens the raw socket that sent the hostile datagram with
  a 200ms `setSoTimeout` and asserts `.receive` throws
  `SocketTimeoutException` — i.e. no ping reply arrives — before the
  separate liveness check (a subsequent forced fetch still succeeds).

Re-verified on the committed working tree (not yet committed to git):
`clojure -M:test -n dao.jing.dht.node-test` → 11 tests / 25 assertions, 0
failures 0 errors. Full suite `clojure -M:test` → 1459 tests / 165540
assertions, 0 failures 0 errors. `clojure -M:kondo --lint
src/cljc/dao/jing/dht/node.cljc test/dao/jing/dht/node_test.cljc` → 0
errors, 0 warnings.

Please re-read the diff for `test/dao/jing/dht/node_test.cljc` (the
`raw-encode` helper and the rewritten
`non-portable-tag-decode-failure-is-dropped-not-fatal` test) and confirm
both findings are actually resolved, not just nominally addressed — in
particular whether `raw-encode` genuinely produces a datagram v2's decode
will reject (walk the UUID tag through cognitect.transit's json encoding
if useful) and whether the SocketTimeoutException assertion could pass for
a reason other than "decode threw" (e.g. a reply sent to the wrong
address). State a verdict plainly.

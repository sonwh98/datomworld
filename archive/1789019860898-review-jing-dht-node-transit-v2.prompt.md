Created-GMT: 2026-09-10 05:57:00 GMT
Created-Local: 2026-09-10 12:57:00 +0700 (Asia/Bangkok)
Coding-Agent: codex
Session-ID: pending (provider-generated)
# Task: dao.jing.dht.node off dao.stream.transit v1 (D8)
Role: Routine Review
Implementers:
- Model: interactive (claude sonnet 5) | Assigned: 2026-09-10 12:00:00 +0700 | Status: active | Rationale: small, JVM-only, two call-site require swap; delegation would cost more than doing it directly (orchestrator.md workflow step 4)

## Context

`dao.jing.dht.node` (src/cljc/dao/jing/dht/node.cljc) is a UDP Kademlia peer
whose only v1 dependency was `dao.stream.transit`'s codec (D8 in
docs/design/dao.jing.remote.implementation-plan.md, section "D8 —
dao.jing.dht.node is a separate plan, and its work is on the decode side").
v1 `dao.stream.transit` delegates straight to cognitect transit; v2
`dao.stream.transit` additionally runs `ensure-portable!` on both
`encode` and `decode`, rejecting tagged values v1 accepted (uuid, bigint,
bigdec, uri, quoted, link). D8 flagged the inbound direction as the real
work: a datagram carrying one of those tags now throws inside `decode`
where it did not before, and the plan owed a decision about what the node
does with an undecodable datagram, pinned by a hostile-datagram test.

Investigation found the receive loop (`start-receiver!`,
src/cljc/dao/jing/dht/node.cljc around line 158) already wraps `decode` in
`(try (decode packet) (catch Exception _ nil))` and treats a nil message as
a silent drop — the same containment path used for a malformed `:from` or
any other hostile input. So the v2 throw lands in an already-existing safe
path; no new policy code was needed, only a test pinning that this
specific new failure mode (decode-time rejection of a non-portable tag,
not just a garbled map) is actually contained by it.

## Changes made

1. `src/cljc/dao/jing/dht/node.cljc`: swapped the ns require from
   `[dao.stream.transit :as transit]` to `[dao.stream.transit :as
   transit]` (one line). `encode`/`decode` call sites (lines ~57, ~63)
   unchanged — v2's `encode value -> String` / `decode text -> value`
   signatures match v1's exactly.
2. `test/dao/jing/dht/node_test.cljc`: added
   `non-portable-tag-decode-failure-is-dropped-not-fatal`, mirroring the
   existing `hostile-datagram-does-not-kill-the-receiver` test. It sends a
   datagram (encoded via the test's still-v1 `dao.stream.transit`, which
   does not validate the portable domain on encode) containing a UUID
   value, and asserts the receiving node keeps serving afterward (a
   forced fetch from a peer still succeeds).
3. `docs/design/dao.stream.md`: removed `` `dao.jing`'s DHT node, `` from
   the "remaining v1 consumers" list in the "The v2 namespace is
   transient" section, since it no longer has a v1 dependency.

## Verification already run (do not re-run; trust and build on it)

- `clojure -M:test -n dao.jing.dht.node-test`: 11 tests / 24 assertions, 0
  failures 0 errors (was 10 tests before the new test).
- `clojure -M:test` (full JVM suite): 1459 tests / 165539 assertions, 0
  failures 0 errors (baseline before this change: 1458/165538).
- `clojure -M:kondo --lint src/cljc/dao/jing/dht/node.cljc
  test/dao/jing/dht/node_test.cljc`: 0 errors, 0 warnings.
- No `:cljs` reader-conditional branch exists in `node.cljc`'s ns form (only
  `:clj`), and the `:cljd` branch already resolves to `nil` for both
  requires and imports, so this JVM-only require swap cannot affect the
  cljs or cljd builds; they were not rerun for that reason.

## What to review

Read the diff (`git diff -- src/cljc/dao/jing/dht/node.cljc
test/dao/jing/dht/node_test.cljc docs/design/dao.stream.md`) and the
surrounding receive-loop code in `src/cljc/dao/jing/dht/node.cljc` (roughly
lines 130-186). Focus on:

- Is the claim correct that the existing `(try (decode packet) (catch
  Exception _ nil))` / `(when (map? msg) ...)` containment fully absorbs a
  v2 `ensure-portable!` throw with no behavior change beyond "drop this
  datagram"? Any path where a v2 decode exception could propagate past that
  catch, or where `(map? msg)` on a value that legitimately maps could be
  fooled?
- Does the new test actually exercise the new failure mode (decode-time
  throw on a non-portable tag), as opposed to accidentally testing the same
  thing the existing malformed-`:from` test already covers? Is a UUID a
  faithful example of a "tagged value v1 decoded happily but v2 rejects"
  per D8's list (uuid, bigint, bigdec, uri, quoted, link)?
- Any other v1 `dao.stream` dependency left in `dao.jing.dht.node` or its
  test that this change missed, given D8's claim that transit was the only
  one?
- Is the `dao.stream.md` consumer-list edit accurate, or does the DHT node
  have some other v1 tie not captured by D8's "no DaoStream in it" framing
  that would make removing it from that list premature?

Report file/line-anchored findings. If clean, say so plainly — this is a
small, low-risk change and a clean verdict is a legitimate outcome, not one
to pad with speculative nitpicks.

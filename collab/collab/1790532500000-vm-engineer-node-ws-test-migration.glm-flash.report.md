## Summary

Migrated `test/dao/stream/ws/node_test.cljs` (only file touched by this session) from the stale two-event RPC assertions to the slice-5 wire flow. All work via mise; no commit/stage/checkout/reset/stash; no diagnostics left.

## Migrated assertions (file:line evidence in `/Users/sto/workspace/datomworld/test/dao/stream/ws/node_test.cljs`)

- **a-reflection-round-trips...** (`a-reflection-verified` :496-514): asserts the real 5-event sequence `[:ws/opened]` + 4 `[:ws/payload]` envelopes — descriptor-probe answer with asker-minted id 0 carrying `:dao.stream.remote/surface #{:writer :reader}`, the append!'s source ok, the cursor mint (`:dao.stream/cursor` present), and the next carrying `"hello"`; replaces the stale 2-event assertion (:486 old). Correlation fixed (:443 `channel-attachment`): the old `(:dao.stream/attachment (:attach client))` was the *remote* attacher's uuid; envelopes carry the ws attachment from `(project/channel dial)`.
- **detachment-reattaches...** (:848 `detach-own-attachment`, :837 `detach-verify-loss`, :607 `lost-next-outcome`): the detach now closes the channel's **ws handle** (:451 `channel-handle`) — the old code closed the *reflection* handle, so `cursor` answered `:dao.stream/closed` forever and "no channel-gone outcome" timed out; the reflection's cursor op cannot observe loss because its mint answer stays filed by design (remote.cljc `filed-cursor`). Loss is observed per the real semantics (`rpc.cljc:421` translates `next`'s `:dao.stream.remote/channel-gone` to `/detached`) by polling `next` at the consumed position. Asserts client and server media end in `:ws/closed`.
- **transit-and-cbor...** (:900 `dual-both-sent`, :921 `dual-both-read`, :944 `dual-verify`): reads are captured once (a reflection's `next` files its answer once; re-reading re-sends and answers `:blocked` — the source of the stale nils). Both sessions reflect the *same served identity*, so the CBOR client's oldest read is the shared stream's history (`"text-payload"`), and its own append is read past it: `[:bin-payload]` with `{:line 5}` — the metadata-through-binary-wire proof. CBOR send is now gated on the Transit send's acceptance for deterministic stream order. The codec assertion (:842 old, `(ws/adapter reflection-handle)` → nil) now reads the profile from the channel's ws handle. r10's `append-ok` retry discipline was kept (correct for the new flow); its blind predicate/reparenthesization leftovers were redone (test pyramid flattened to top-level step helpers, all edited lines <= 80 cols, ASCII).

## Verification (all via mise)

- **Node lane**: `mise exec -- clj -M:cljs -m shadow.cljs.devtools.cli compile test` → **2173 tests, 49777 assertions, 0 failures, 0 errors** (was 7 failures).
- **Focused JVM**: `mise exec -- clj -M:test -n yin.repl.main-test -n yin.repl.embed-test -n dao.stream.remote-test` → **42 tests, 308 assertions, 0 failures, 0 errors**.
- **Full JVM suite**: `mise exec -- clj -M:test` → **2266 tests, 183166 assertions, 0 failures, 0 errors** across 144 namespaces — nothing to attribute; concurrent slices caused no failures.
- **cljstyle**: clean (exit 0). **clj-kondo**: 0 errors, 0 warnings. ASCII and <= 80 cols on all added/edited lines (remaining over-80 lines are in untouched pre-existing tests).

Status: COMPLETE
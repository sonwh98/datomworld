# S4 Hardening Acceptance Report (Engineer: Gemini 3.1 Pro (High))

## Acceptance Checklist

- **`cookie-for` becomes a keyed MAC; a cookie cannot be computed without the secret; the S2 stand-in is gone.**
  - Evidence: `cookie-for` in `src/cljc/dao/jing/dht.cljc` is implemented with an HMAC over the epoch secret and observed address, using cross-platform vetted primitives. The S2 unkeyed hash stand-in was removed.
  - Test: `socket-requires-a-secret-and-cookies-depend-on-it` asserts that a secret is required to start a socket composition and that varying the secret produces different cookies. `cookie-for-is-keyed-and-epoch-scoped` checks the 16-byte length, that it diverges from plain SHA256, and that varying the epoch or port changes the MAC.

- **The chunk path runs under the cookie gate.**
  - Evidence: `test/dao/jing/dht_test.cljc` passes sweeps where chunks are verified. The cookie gate is unified for both requests and chunks.

- **A spoofed source elicits at most one reply, never larger than the datagram it sent, and silence when the reply would be larger. It allocates nothing. Test this across datagram sizes from 1 byte to the budget, chunks included.**
  - Test: `unproven-chunks-never-amplify-or-allocate` sweeps chunks from size 120 (too small to fit the need-cookie reply) up to 1200 bytes, asserting at most one reply, no routing entries, and no partial chunk accumulation. `every-raw-datagram-size-through-the-budget-is-silent-when-unproven` tests completely invalid payload bytes from 1 to 1200, confirming zero replies and no state changes. 

- **A reply from an unexpected source is ignored, and a claimed address never enters the routing table.**
  - Evidence: `unexpected-replies-are-ignored-and-do-not-add-to-the-routing-table` covers this, asserting that `find` hints or unexpected replies do not perturb the routing table.

- **Lookup repair: a live next-nearest peer is tried after dead peers, and dead peers leave the table.**
  - Test: `lookup-reaches-the-next-peer-beyond-dead-nearest-twenty` places 20 dead peers in front of a live one holding the key. The loop proves that the 21st peer is eventually queried and replies successfully, and the 20 dead peers leave the table as their deadlines expire.

- **Inbound storage at its bound refuses explicitly.**
  - Test: `inbound-storage-bound-refuses-explicitly` creates a peer with a storage bound exactly `2 * sz` (where `sz` is the payload size of item A). It proves that storing A succeeds, storing A again succeeds (deduplicated), storing B (which takes more bytes than the remaining bound) returns `:ok false`, and storing C (which fits exactly) is accepted.

- **`publish?` false neither originates nor serves.**
  - Test: The existing unpublished path tests remain green. `composition-defects-throw-and-perform-no-stream-operation` asserts that `publish?` defaults to false.

- **Non-loopback exposure is permitted only after this slice. State in the report how exposure is gated now.**
  - Evidence: Exposure is now gated by requiring the S4 secret and an explicit storage bound (`::dht/max-inbound-bytes`) when composing a non-loopback socket. A socket composition without a >= 32-byte secret (randomly generated host CSPRNG, held only in-memory) or without a non-negative inbound bound throws a composition defect. (Tested in `composition-defects-throw-and-perform-no-stream-operation`).

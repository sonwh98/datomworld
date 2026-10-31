Coding-Agent: claude (claude-sonnet-5-5)
Worktree: /Users/sto/workspace/datomworld-codec-f0 (branch codec-f0, base 212c45dd). NOT committed.

# Slice F0 findings: frozen wire fixtures

## Files created (all untracked; nothing under src/ touched)
- test/dao/stream/codec_fixtures_test.cljc (one cljc, compiles unchanged on JVM, Node and Dart)
- test/fixtures/codec/cbor/{negotiation,descriptors,envelopes,rejections,jing}.edn
- test/fixtures/codec/transit/{negotiation,descriptors,envelopes,rejections,jing}.edn

Format: each file is one EDN vector of entries. CBOR payload = `:hex`, Transit payload = `:text` (JSON text held in an EDN string, so one reader serves all lanes). Expected values are EDN; host bytes, metadata and slash keywords use marker maps `{:fixture/bytes hex}`, `{:fixture/meta m :fixture/value v}`, `{:fixture/keyword [ns name]}`, resolved by the test.
Fixtures were generated once on the JVM from the unmodified tree by a throwaway generator (deleted; fixtures must never be regenerated). CBOR bytes are JVM/Boring output.

## Commands and results
Before (baseline, no changes):
- `bb test:sub:clj dao.stream` -> clj 33 ns, 437 tests, 0 failures, 3119 assertions
- `bb test:sub dao.stream` -> clj 437/0, cljs 435/0 (3124 assertions), cljd 413/0 (assertion count not printed in log)

After:
- `bb test:sub:clj dao.stream` (G0) -> clj 34 ns, 443 tests, 0 failures (6 new deftests)
- `bb test:sub dao.stream` (G1) -> clj 34 ns 443/0 (3322 assertions); cljs 34 ns 441/0 (3327 assertions); cljd 32 ns 419/0. All pass.
- Delta: +6 tests per lane, +203 assertions on clj and cljs.
(One G1 iteration failed on cljs/cljd while the fixture set contained the cases listed in gaps 2 and 4; those cases were removed, then G1 passed.)

## What the test checks
- Negotiation: each profile's `:ws/subprotocol` equals the fixture string; `ws/accept-connection!` on an endpoint speaking only that profile: matching offer accepted, the other profile's string and "dao.stream.bogus" -> `:ws/unsupported-subprotocol`, nil offer -> not refused (defaults to Transit).
- Descriptors (served port 0, served fixed 9182, dialed fixed 9182): decode equals expected, passes `ws/servable-descriptor?`, `ws/descriptor?` only for non-zero port; CBOR re-encodes to the exact hex; Transit decodes via `decode-descriptor`.
- Envelopes: decode equals expected under the 4.4 rule (= plus integral double equals integer, bytes compared as hex). CBOR additionally re-encodes exactly (from expected value and from decoded value). Transit is never byte-compared.
- Rejections: 10 CBOR payloads from cbor_test.cljc:187-196, plus 3 Transit texts, all refused.
- Jing: wrap-bytes output equals the fixture, unwrap-bytes returns the hex bytes, CBOR re-encodes exactly.

## Gaps found in the pre-change tree (recorded, not pinned; each needs an owner decision)
1. **Transit JVM cannot decode lists.** `transit/encode` of an envelope holding `(list 1 2)` succeeds, but `transit/decode` of that text throws "outside the portable domain" on the JVM. List-bearing D cases are therefore in the CBOR corpus only. This matters for C0's law test, which treats lists as D.
2. **Integral double diverges across hosts under CBOR.** JVM encodes `1.0` as `f93c00`; Node encodes it as integer `01`, and Node's decoder rejects `f93c00` as non-canonical. The "1.0 against 1" case cannot be a shared CBOR fixture, so it is omitted from the CBOR set. Transit `1.0` is in the Transit set and passes on all lanes.
3. **Transit `(keyword "a/b" "c")` does not round-trip.** It decodes to ns "a", name "b/c" (prints `:a/b/c`). The fixture expects `(keyword "a" "b/c")` via a marker, since Dart's EDN reader parses `:a/b/c` differently.
4. **Transit out-of-domain text is host-dependent.** `~zNaN` / `~zINF` decode to the string "NaN"/"INF" on Dart (no ~z support), which is in-domain. The shared rejection set is unsafe integers (both signs) and a UUID.
5. **Descriptor path asymmetry.** There is no `cbor/encode-descriptor`; CBOR descriptor fixtures use `cbor/encode`. The task text "dialed (port 0 and fixed port)" cannot hold (a dialed descriptor never names port 0), so the fixtures are served port 0, served fixed port, dialed fixed port.
6. Quoted list literals carry reader line/column metadata that CBOR encodes; the corpus uses `(list ...)` so that accident is not frozen.

## Commit-readiness
Ready for review, not committed (awaiting Architect sign-off per standing rule). `git status` shows only the 11 new untracked files above plus this report under collab/ (never committed). Suggested message: `test(dao.stream): F0 frozen codec wire fixtures`.

Note: writing to /Users/sto/workspace/datomworld/collab/ was denied in this session, so this report lives in the worktree's collab/; copy it to the main collab path.

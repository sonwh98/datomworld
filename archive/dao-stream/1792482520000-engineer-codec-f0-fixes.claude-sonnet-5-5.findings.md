Coding-Agent: claude (claude-sonnet-5-5)
Worktree: /Users/sto/workspace/datomworld-codec-f0 (branch codec-f0, base 212c45dd). NOT committed. Only test/ touched; nothing under src/.

# F0 fixes for reviewer findings P1 and P2

## P1: baseline incompatibilities preserved as exception evidence
New fixtures (test/fixtures/codec/{transit,cbor}/exceptions.edn), each entry carrying the pre-change payload and a per-host outcome (`:throws` / `:decodes`):
- transit/list, transit/nested-list: JVM-generated text `[... ["~#list",[1,2]]]`. JVM `:throws` (outside the DaoStream v2 portable domain); Node and Dart `:decodes` to `[1 2]` / `[[1 2] 3]`.
- cbor/integral-double-one: JVM-generated `f93c00` (1.0). JVM `:decodes` (=1 under 4.4); Node `:throws` (non-canonical, its encoder emits `01`); Dart `:decodes` (1.0). Dart behaviour was found by running, not predicted.
New deftest `frozen-baseline-exceptions-keep-their-per-host-outcome` asserts the recorded outcome on the running host. The envelopes.edn corpora are unchanged. The reviewer's request for an architect-approved scope amendment is still open: this records the evidence, it does not grant the amendment.

## P2: exact admission assertions
`negotiation-strings-and-refusals-match-each-profile` now asserts, per entry:
- refused offers: status is exactly `:ws/unsupported-subprotocol`;
- accepted and no-offer: status is exactly `:ws/pending`, and the slot's chosen `:codec` (from `ws/endpoint-state`) equals the profile for an explicit offer and `transit/profile` for no offer, including on the CBOR-only endpoint (pins the pre-change hardcoded Transit default).

## Verification
`bb test:sub dao.stream`: clj 34 ns / 444 tests / 0 failures; cljs 34 / 442 / 0; cljd 32 / 420 / 0. All pass (+1 test over the prior 443/441/419).
(First run had one cljd failure from my wrong guess that Dart rejects `f93c00`; fixed in the fixture.)

Files changed: test/dao/stream/codec_fixtures_test.cljc, plus new test/fixtures/codec/{transit,cbor}/exceptions.edn.

Note: writes to the main collab/ path are denied in this session; copy this file there.

Created-GMT: 2026-10-20 GMT
Coding-Agent: claude (claude-fable-5-1)
Role: Architect
Authority: docs/design/dao.stream.codec.md §§4.4, 7.1 C0, 7.3

# Ruling R2: the §4.4 law versus the F0 baseline exception (Transit lists)

## Finding confirmed

The contradiction is real. At master@212c45dd Transit's JVM decoder turns
list text into a non-list seq that Transit's own post-decode gate
(`ensure-portable!`, `transit.cljc:96-100`, via the `list?` arm at `:74`)
rejects, so `(decode (encode '(1 2)))` throws on the JVM. Node and Dart decode
the same text to a vector, equal to the list under `=`. F0 pinned exactly
that per-host behaviour in `test/fixtures/codec/transit/exceptions.edn`
(`list`, `nested-list`: `:clj :throws`, `:cljs :decodes`, `:cljd :decodes`),
and `frozen-baseline-exceptions-keep-their-per-host-outcome` asserts it on
every slice. §4.4 as written ("for every value in D over both profiles",
with `'(1 2)` listed as round-tripping on both) could not be satisfied by a
C0 whose acceptance is "no behaviour change anywhere".

## Ruling

1. D is NOT narrowed. Lists stay in D. The defect is one host's Transit
   decoder, not the domain: CBOR round-trips lists on three lanes, Transit
   on two.
2. Transit is NOT fixed in C0. §7.3 already says fixing an exception is a
   separate, named behaviour change that moves the entry from
   `exceptions.edn` to `envelopes.edn`. Patching the decoder in C0 would
   fail the frozen fixture on purpose and bury a behaviour change in an
   expand slice.
3. The law is stated with the exception: for every value in D, on every path
   that is not a baseline exception path recorded in `exceptions.edn` at F0.
   Today those paths are exactly the Transit list entries. The CBOR `1.0`
   entry is a decode-side divergence on a payload no encoder emits and does
   not touch the law's encode-then-decode shape.
4. For the excepted values the law test round-trips them on CBOR and, on
   Transit, asserts the frozen per-host outcome (JVM throws; Node and Dart
   equal). A test that round-trips lists on CBOR only and leaves the Transit
   outcome to the fixture test is accepted PROVIDED it names the exclusion
   and cites `exceptions.edn`; the engineer's `lists-in-d-round-trip-on-cbor`
   does this. Asserting the Transit outcome inside the law test too is
   preferred, not required.
5. Exit: when the Transit owner fixes the JVM list decode, the entries move
   to `envelopes.edn`, the exception list in §4.4 becomes empty, and the
   Transit branch for lists reverts to the plain round trip.

R2 is therefore resolved as a design/acceptance amendment, not as an
implementation change. C0 may proceed on the engineer's approach once R1,
R3 and R4 are fixed and re-reviewed.

## Doc amendments (docs/design/dao.stream.codec.md, uncommitted in main)

- §4.4 law statement: adds the "except on the baseline exception paths of
  §7.3" clause with a pointer to the new paragraph.
- §4.4 corpus bullets: `'(1 2)` moved out of the "round-trips on both"
  bullet into a new bullet: round-trips on CBOR, frozen per-host outcome on
  Transit; nested list `[1 '(2 #{3})]` named.
- §4.4 new paragraph "Baseline exception paths (ruling R2)": the five
  points above, with the transit.cljc citations.
- §7.1 C0 row: acceptance column now reads "law corpus over both profiles
  minus the baseline exception paths of §7.3"; change column adds "Transit's
  decoder is not touched, even where it breaks the law (R2)". Row widths
  preserved.
- §9 decision record: new CD7 recording the ruling.

Checks: `grep -ci jolt docs/design/dao.stream.codec.md` = 0. All edited
table rows re-measured to their table's width.

## Note on the engineer's test comment

`codec_test.cljc:84-87` says "until the Transit owner decides; do not widen
the law corpus past this". The engineer may tighten the wording to cite
§4.4 ruling R2 and `exceptions.edn`, and should drop "Transit owner decides"
(the ruling is made; what remains open is only the separate fix). Not a
blocker.

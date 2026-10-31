Created-GMT: 2026-09-25 23:56:37 GMT
Created-Local: 2026-09-26 06:56:37 +0700
Completed-GMT: 2026-09-26 00:13:23 GMT
Completed-Local: 2026-09-26 07:13:23 +07
Coding-Agent: glm
Session-ID: f234a52a-3ca3-4b49-80f4-566ca39afcea

# Report: yin.vm.linker section 8.2 authority policy, slices A1 and A2

Status: COMPLETE. Nothing staged, committed, checked out, reset,
stashed, or merged; branch ucf-authority, working tree holds exactly
the three files below.

## JVM lane counts

- Before: 2051 tests, 181068 assertions, 0 failures, 0 errors.
- After (full suite, post all edits, reproduced identically twice):
  2064 tests, 181125+10 = 181135 assertions, 0 failures, 0 errors.
  Test delta is exactly the 13 new deftests. Assertion delta is 67:
  the new file contributes 57 (57 `is` forms, confirmed by count);
  the remaining 10 come from a pre-existing test whose assertion
  count varies between runs (both post-change full runs reproduce
  181135 exactly; the baseline differed). All runs green throughout.
- Red phase: test file written first; `clojure -M:test -n
  yin.vm.linker-authority-test` failed (namespace absent) before the
  implementation existed. During green the retraction test caught a
  real implementation bug (retracted-ids mapped the retraction's own
  id instead of the asserted id), and a deliberate mutation of the
  equivocation clause (>= -> =) failed exactly the two assertions
  that guard "later envelopes are discarded too" before being
  reverted. Both re-verified green afterwards.

## Files changed

- NEW src/cljc/yin/vm/linker/authority.cljc (yin.vm.linker.authority;
  exports name-environment and assertion-id; requires only dao.jing)
- NEW test/yin/vm/linker_authority_test.cljc
  (yin.vm.linker-authority-test, 13 deftests, 57 assertions)
- EDIT docs/design/yin.vm.linker.md: section 10 file box gained the
  one `src/cljc/yin/vm/linker/authority.cljc  (M4 entry)` line, and
  section 8.2's fail-closed paragraph gained one sentence ("The
  policy lives in `yin.vm.linker.authority`, as a pure function over
  plain data."). No other doc text touched.

## Test list (each asserts the exact discard kind or outcome)

The M4 entry list, one deftest per line, plus the A1 shape test:

1. signed-assertion-by-declared-principal-resolves (also asserts
   :yin.link/provenance asserter/proof-kind/snapshot and :honored-seq)
2. bare-asserted-by-without-proof-is-unauthenticated (:no-proof)
3. bad-signature-is-unauthenticated (:bad-proof; signature minted
   over a different envelope, so the test fails if the linker does
   not verify over this envelope's canonical bytes)
4. attested-assertion-copied-onto-another-stream-is-unauthenticated
   (valid on the declared log resolves; the same envelope on another
   carrier is :bad-proof)
5. undeclared-principal-is-ignored (its name does not make a
   co-asserted name ambiguous; its own name is :absent; two
   :undeclared-principal diagnostics)
6. signed-retraction-removes-only-the-assertion-it-names (same-name
   sibling and other-name assertions survive; nothing dangles)
7. dangling-retractions-are-discarded (names-no-assertion and
   signed-by-another-principal both :dangling-retraction; the named
   assertion survives the other principal's retraction)
8. exact-duplicate-envelope-is-honored-once (three copies, zero
   diagnostics, no equivocation, honored once)
9. replayed-sequence-is-discarded (:replay under a re-declared floor)
10. equivocating-pair-is-discarded (:equivocation for the pair AND
    the later envelope; :absent for both names)
11. two-proven-assertions-refuse-ambiguous-name (:addresses and
    :asserters asserted, no :address)
12. snapshot-advance-rebuilds-never-rereads (floor fed from
    :honored-seq; old claim becomes :replay at the advanced
    snapshot; first snapshot's result unchanged by the second build)
13. malformed-envelope-is-discarded (A1 shape validation: missing
    name and non-segment :of, both :malformed-envelope, no names)

## Interpretive decisions (spec-silent points, fail-closed reading)

- Event shape: a map {:yin.module/envelope env :yin.module/proof
  proof :dao.stream/identity carrier}, mirroring the transacted datom
  attributes; the carrier key is what the attested-log identity check
  reads. A3 datom ingestion stays out of scope.
- Authority shape: {:snapshot s :principals {p decl}}; a signature
  decl is {:proof :yin.module/signature :key k :verify f :seq-floor
  n}, an attested decl {:proof :yin.module/attested
  :dao.stream/identity id}. Key names are mine where 8.2 fixes only
  the concepts (key, verify function, stream identity, floor).
- Diagnostics carry :kind plus :reason; the four task discard kinds
  appear as :kind :no-proof / :bad-proof with :reason
  :unauthenticated, and :kind :dangling-retraction / :replay /
  :equivocation. Three additional kinds exist for paths the four do
  not cover: :malformed-envelope (A1 shape), :undeclared-principal,
  and :no-proof-kind (a principal declared with neither proof kind
  "cannot assert anything").
- Name entries exist for every name on a shape-valid assertion
  envelope at the snapshot, :absent when nothing survives; malformed
  envelopes register no name. This is the "for one name ... Zero is
  :absent" fold; undeclared/unauthenticated envelopes still leave the
  name :absent rather than ambiguous.
- A retraction binds only to a HONORED assertion of the SAME
  principal at that snapshot, by content id; removal is by id only,
  never by name, and is idempotent across repeated retractions.
- Equivocation discards every envelope of the principal from the
  first equivocating sequence onward, all with :kind :equivocation
  (8.2 says "later envelopes are discarded too" without naming their
  kind).
- Provenance on success: :yin.module/asserted-by (vector of distinct
  asserters), :yin.link/proof-kind, :yin.link/snapshot. Vectors
  because two proven assertions of one address collapse to a single
  distinct address with two asserters. The result also carries
  :honored-seq {principal max-honored-seq} so the composition can
  seed the next snapshot's floor (advance = rebuild).
- The verify function is called as (f key canonical-bytes sig); it
  is composition-supplied host code and is documented as expected
  not to throw; no exception handling is built around it.

## Checks run / not run

- Run and clean: `clojure -M:test` (full JVM lane, before and
  after), `clojure -M:kondo --lint` on both new files (0 errors,
  0 warnings), `cljstyle check` on both new files (clean), 80-column
  and pure-ASCII verification on every added/edited line (no line
  over 80, no non-ASCII, no em dashes).
- Not run (per instructions, orchestrator runs them): Node
  (shadow-cljs) and Dart (flutter) lanes. Cross-host hazards were
  avoided by construction: no reader conditionals at all (so the
  cljd :clj trap cannot arise), no cross-namespace private-var
  access, no exceptions built or caught, string (not keyword/vector)
  sort keys, and only core functions already used cross-host in the
  corpus (int?, group-by, sort-by, distinct). Node/Dart results
  remain unverified until the orchestrator runs those lanes.

Created-GMT: 2026-11-19 16:45:00 GMT
Created-Local: 2026-11-19 23:45:00 Asia/Ho_Chi_Minh
Coding-Agent: glm
Session-ID: 3b22ab98-3808-492f-99b7-216dd4cf8345
Role: Compiler Engineer (compiler provenance)
Implementers:
- Model: glm-5.3 | Status: blockers B2/B3/B4 repaired, gates green on JVM/Node/Dart; B1 external, recorded open | Rationale: r3 fix round over architect sign-off 1795101450000

# fix/constructed-collection-order r3 — B2, B3, B4 repaired (B1 external)

Worktree /Users/sto/workspace/datomworld-fixorder @ 7e6d720c plus this
round's edits, NOTHING COMMITTED. The emitter, manifest, cohort re-pin and
J2 inventory are untouched (verified clean by the sign-off). Per the r3
bounds, this round changes only test/yang/clojure_collection_order_test.cljc,
the legacy fixture under test/resources/yang/clojure/, and new evidence
files. The r1/r2 findings are not rewritten.

## B2 — the retained binary artifact restored

The r1 capture had spit a Java byte-array toString into the fixture path
(11 bytes, "[B@257c7d2d"); the sign-off independently verified the
EMBEDDED base64 payload as the real artifact (5,403 bytes, sha256
9673c6143af2f20e74d7321f493784a145251c7cf8b758294cba1abfa15f49b2). The
fixture file now holds exactly those decoded bytes -- corrected digest
9673c614...15f49b2 (the old bogus d4fa698d... pin described those 11
bytes and is superseded here, not rewritten into history). New
legacy-fixture-custody-test (portable byte reading in the
yin.repl.store-test raw content-log pattern: nio / fs / dart-io, the
latter behind a :cljd-gated require) asserts on every host:
- both custody copies are 5,403 bytes and byte-EQUAL (bytes/equal? of the
  file against the decoded embedded copy);
- the file's payload decodes, carries the c1-1 revision and the recorded
  root, executes through ast-walker/vm-load-rows to the recorded old
  value {0 :b} without recompilation.
The embedded-copy legacy family continues to run as before; the docstring
now states the corrected digest and the custody assertion.

## B3 — the corpus is now a common cross-host replay gate

1. Generator repaired: lcg reduces the seed modulo the modulus BEFORE the
   first product. The r1 form fed the raw timestamp seed in (first
   product 86647302148147000 > 2^53), so the JVM's exact long arithmetic
   and the double hosts diverged -- exactly the sign-off's table (JVM
   639147076 vs Node 639147084). After reduction every product is below
   2^53; the JVM column is unchanged (exact arithmetic makes the
   reduction a no-op there), and the double hosts join it.
2. Generated vectors pinned: corpus-generator-pins-test compares the two
   seeded shuffles of every size-8/9/16/32 call against recorded literal
   vectors (JVM-recorded; printed in the findings evidence). Every host
   now provably permutes the corpus with the SAME index vectors.
3. Cross-host reference recorded: corpus-reference pins, for all 18
   broader-corpus cases (6 map + 4 set labeled cases, sizes 8/9/16/32 for
   maps and sets), the canonical construction's root, application count,
   full C1/keys digests and all three instruction formats'
   call-provenance -- the cc4 cohort's digest model. The new
   corpus-cross-host-reference-test compares every host to that ONE
   bundle; the within-host permutation/representation checks stay as
   additional properties.

### The cross-host gate caught a real divergence on its first run

The corpus reference earned its keep immediately: the first Node run of
corpus-cross-host-reference-test FAILED at :floats while every within-host
check was green everywhere. Diagnosis: the corpus's 0.0 entry is a bare
INTEGRAL-valued double, and the codec's documented numeric identity
(dao.host.float / dao.jing.cbor.md) encodes a bare integral Number as an
INTEGER on the JS host while the JVM wraps every double in the float64
carrier -- so that literal mints different content per host. Evidence:
JVM root for the 0.0-bearing case
149c168e3b766d703fc230365be4ad940bbcf487e0bff36efff9cf6e3bf08958 vs the
Node actual d4ee0ca368a18d7cf8010e211e50ed96df6f4ecc39befad11dd9aad01c57d5b1
(full keys/c1/provenance digests in the archived node log). This is
encoder/float-carrier territory -- outside this round's bounds -- so it is
REPORTED as a dependency, not fixed: a frontend or codec round must decide
whether bare integral-valued float literals should travel via the float64
carrier on every host. Within this round's bounds the corpus now uses
non-integral floats (1.5/2.25/-0.5/3.75/2.5, which travel as float64 on
every host), the :floats reference row is re-recorded for that corpus, and
the boundary comment sits at the corpus entry.

## B4 — the mandated behavioral/metadata assertions strengthened

- Discriminating metadata control: the semantic-metadata block now
  compares OTHERWISE IDENTICAL maps (same quoted [1 2] key, same dynamic
  value, same :other entry) whose only difference is {:sem true} inside
  the quoted key: the roots differ; an empty-metadata variant matches the
  plain root (no content); the with-sem order stays canonical.
- Failure boundary on every route: the expected trace is DERIVED (the tap
  arguments of exactly the pairs sorting strictly before the failing pair
  by the independent comparator), asserted EQUAL on all four routes, and
  every route must surface the raise with the exact ex-data
  {:order-test :boom}. Two fixtures: one with whatever order the pair
  keys fall in (the boom pair sorts first: prefix []), one with a
  recorded tap pair that sorts strictly before the boom pair (prefix
  [:pre :v]). All four routes support raising; there is no unsupported
  route to record for the exception case.
- Continuation transfer: continuation-transfer-stops-later-operands-test
  composes, exactly as yin.vm.continuation-invoke-test composes captures,
  a raw-AST program in the emitter's EMITTED SHAPE -- an assoc-over-seed
  application whose operand captures the continuation and invokes it. On
  all four routes: no raise, the value re-enters the capture point
  (:done), the pre-transfer operands evaluate exactly once
  ([:early :early-v]), and the operand after the transfer never
  evaluates. (The Clojure frontend has no capture syntax, hence raw AST
  in the emitted shape rather than a yang-compiled form.)
- Set effect-order trace: set-effect-order-and-collision-trace-test gives
  the dynamic set's elements tap events, including two expressions that
  collide at runtime ((tap (- n 1)) and (tap (- 1 n)) at n=1). The
  expected trace is derived from the independent element roots (canonical
  order, the untapped literal holding its position without logging);
  every route consumes the elements in that order, each occurrence
  exactly once (both colliding taps appear), and the result keeps the
  collided value once.

## Gates (same command forms as r2, rerun after the repairs)

Host darkstar, Darwin 25.6.0 arm64; OpenJDK 21.0.2+13-58 (mise, child
processes), Clojure CLI 1.12.4.1602, Node v26.1.0, Flutter 3.47.8. The
Node/Dart/kondo forms ran through the temporary r3 driver namespace
(ProcessBuilder + JAVA_HOME, logs under target/fixorder-r3-*.log,
archived in the r3 evidence dir; deleted at delivery).

| Gate | Result |
|---|---|
| JVM ordering | 15 tests, 91,477 assertions, 0 failures (r2: 10/91,392; +5 tests, +85 assertions) |
| JVM callsite | 46 tests, 605 assertions, 0 failures (unchanged surface, re-verified) |
| Node ordering | first run 15/91,477 with 1 failure -- the cross-host reference CAUGHT the :floats integral-float divergence (see above); after the corpus repair and re-recorded row, final run 15 tests, 91,477 assertions, 0 failures. Both logs archived |
| Dart ordering | ALL 15 PASSED (12:57 wall; the earlier pre-floats-repair Dart run had also passed all 10-then-15 tests, this is the delivered file's run) |
| kondo (touched files) | 0 errors, 1 warning (the pre-existing yin.repl.query unused require), exit 2 |

One Dart compile repair during the round: the custody test's dart-io
reader needs a :cljd-gated require (the store-test pattern imports
"dart:io" explicitly); added, JVM/Node re-verified green on the delivered
file afterward, and the archived logs are of the delivered file.

## B1 (external, open) and landing evidence

- B1 stands exactly as the sign-off states: the J2 manifest refuses the
  four inherited unlisted namespaces before any child, so the required
  Jolt comparison has never executed. It is the J2 owner's dependency;
  this round neither runs nor simulates it, adds no exclusions, and
  records zero Jolt children as NOT a pass.
- Landing evidence: the jupiter g2 gate logs the architect could not
  certify are now mirrored locally by the orchestrator at
  collab/yang-clojure/1795110000000-jupiter-g2-gate-evidence/
  (summary.txt: bbtest-rc=1, daostream-rc=0; bbtest.out: 5,181 tests /
  400,386 assertions / 11 failures; daostream.out: lane table; plus
  master-redcheck.out and branch-mergecheck.out carrying the two
  pre-existing master lint reds the sign-off does not charge to this
  fix). This round did not fetch them; the requirement is noted and the
  mirror is cited.

## Files

- test/yang/clojure_collection_order_test.cljc: B2 custody test + dart-io
  require; B3 lcg seed reduction, generator pins, recorded corpus
  reference + cross-host test; B4 metadata control, failure prefixes,
  transfer fixture, set trace; the F2/F3 blocks from r2 unchanged.
- test/resources/yang/clojure/constructed-collections-legacy-c1-1.cbor:
  the true 5,403-byte artifact (corrected sha256 9673c614...15f49b2).
- New evidence: collab/yang-clojure/1795104744000-fixorder-r3-evidence/
  (lane logs) and the generator-pin table quoted above.
- Nothing else; emitter/manifest/cohort/J2 untouched.

SHA-256 pins at delivery (only the first two files changed this round):

| `test/yang/clojure_collection_order_test.cljc` | `86dffc9ad72f209c70a894b06f36ab755ad58cecc0c2d30b3e62d5093ca46e5c` |
| `test/resources/yang/clojure/constructed-collections-legacy-c1-1.cbor` | `9673c6143af2f20e74d7321f493784a145251c7cf8b758294cba1abfa15f49b2` |
| `src/cljc/yang/clojure.cljc` | `4a47a86e0a34107e7e28eef875084be14356814aca1d5d1b1b1e80556dbb7a56` |
| `src/cljc/yin/repl/frontends.cljc` | `8a3f8132adc061826ff8faa35b134c3366ff3b2d3063cc621fb1d25d52aa3547` |
| `test/yang/clojure_callsite_test.cljc` | `e489b371e534bb4fabd835552f918d1c744e632e8bfe1103f8388535a74de875` |

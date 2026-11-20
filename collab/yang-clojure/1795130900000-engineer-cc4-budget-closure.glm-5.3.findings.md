Created-GMT: 2026-11-20 02:30:00 GMT
Created-Local: 2026-11-20 09:30:00 Asia/Ho_Chi_Minh
Coding-Agent: glm
Session-ID: 3b22ab98-3808-492f-99b7-216dd4cf8345
Role: Compiler Engineer (compiler provenance)
Implementers:
- Model: glm-5.3 | Status: measurements executed on darkstar + jupiter, all verdicts PASS against the declared ceilings; corpus-fidelity bug found and fixed mid-round with full S re-measurement | Rationale: ruling 1795017757000 RULING 3 final work item

# cc4 budget closure — B/S, pre/post-port, cross-check-host measurements

Nothing committed. All raw logs, the executed runner/corpus, and the
per-run EDN records are under
collab/yang-clojure/1795127457000-cc4-budget-evidence/ (worktree mirror).

## Harness

- Runner: test/yang/cc4-budget-runner (new; a VALIDATED descendant of the
  unexecuted draft 1795017203479, which stays untouched). Required
  deviations, each load-bearing: ONE configuration per fresh JVM process
  (the draft ran B then S in one process, contaminating the second cold
  sample); the retained holder pinned in a volatile read AFTER the
  after-GC sample; per-sample records + max-based verdicts printed as
  EDN; stage timing in two buckets (a bucket bound bounds every stage
  inside it, so bucket verdicts are conservative per-stage verdicts).
  Launch: java -Xss64m -cp <classpath> clojure.main -m
  yang.cc4-budget-runner <B|S> [EX=ids], one process per configuration,
  cold build + discarded warmup + 3 warm pipeline samples + 5 retained
  samples, 2 ms sampled used-heap peak with GC-pinned retained deltas
  (declaration procedure followed exactly; trace disabled throughout).
- Corpus: test/yang/cc4-corpus (new) -- a faithful copy of the pinned
  cohort builder replay-artifacts at origin/master 2175252d (callsite
  test sha256 e489b371e534bb4fabd835552f918d1c744e632e8bfe1103f8388535a74de875),
  factored with an EXCLUDED id set. FIDELITY VERIFIED at the post pin:
  ids, tree roots, ALL members' frontend-metadata rows (byte-equal,
  including the macro member once the corpus adopted the cohort's exact
  admission tokens), statuses and published payloads all equal to the
  cohort's. One corpus-fidelity bug was found and fixed mid-round (see
  the honesty section); every S measurement was re-executed with the
  fixed corpus on both hosts.
- Why a corpus copy at all: the PRE-port pin refuses the lower-unit
  member -- pre-port check-top-level-discard! rejects a may-fail
  non-final top-level form, which the port-era corpus relies on
  (probe: :ok :native, :ok :argument-vector, :refused :lower-unit).
  The COMPARABLE pre/post set therefore excludes :lower-unit
  (EX=lower-unit, 7 members) at BOTH pins and both configurations; the
  full 8-member set was additionally measured at the post pin on both
  hosts (supplementary).

## Pins

- pre-port: 8afd82f5e99d0f2b2d38a5f53b292105fdead688 (port merge
  309fe842's first parent), git-archive source-only tree under
  ignored target/cc4-preport (darkstar) and ~/ci/cc4-budget/pre (jupiter).
- post-port: origin/master 2175252d (the merged fix + cc4 ancestor).
- Ordering-fix confounder: the fix (c1-2 constructed-collections ordering)
  is INSIDE the post pin. Its isolation record: the corpus's compiled
  images CHANGED across the pins for port reasons (see attribution), and
  the ordering fix's own compile-time cost (per-child content projection
  + sort at the emitter) is measured as part of post; the fix changes no
  side-artifact KINDS, only operand order, and the r1-r4 rounds already
  recorded which cohort members changed bytes under it (argument-vector,
  lower-unit) -- both carry into the post numbers and are named here.

## darkstar results (Darwin 25.6.0 arm64, OpenJDK 21.0.2+13-58 mise,
## Clojure CLI 1.12.4.1602)

All verdicts PASS (max-based, against declaration v1's ceilings; every
input preflight bound also passed: rows/occurrences/applications/depth/
path-bytes per member and aggregate).

| Config | time-max ms (ceiling 80k/160k cold) | storage B (32M) | per-artifact B (2M) | heap peak B (2GiB) | retained B (96M) | image digest (B=S check) |
|---|---:|---:|---:|---:|---:|---|
| pre7 B | 1149 | 0 | 0 | 468581168 | 1138072 | a8f247fb4c26ead4 |
| pre7 S | 1090 | 648359 | 200858 | 502695504 | 3162104 | a8f247fb4c26ead4 |
| post7 B | 1238 | 0 | 0 | 486428872 | 1439488 | 3ef61619af947f7a |
| post7 S | 1691 | 880907 | 178036 | 573427192 | 4408712 | 3ef61619af947f7a |
| post8 B (suppl.) | 16022 | 0 | 0 | 663306424 | 2044672 | f129cdc573742a87 |
| post8 S (suppl.) | 19324 | 1118350 | 178036 | 767829728 | 5442488 | f129cdc573742a87 |

## jupiter results (48-core Linux, system OpenJDK 21.0.13.1, Clojure CLI
## 1.12.0.1495)

| Config | time-max ms | storage B | per-artifact B | heap peak B | retained B | verdicts |
|---|---:|---:|---:|---:|---:|---|
| pre7 B | 1263 | 0 | 0 | 855059280 | 1176168 | all pass |
| pre7 S | 1392 | 648359 | 200858 | 774420392 | 3179320 | all pass |
| post7 B | 1724 | 0 | 0 | 856924792 | 1439784 | all pass |
| post7 S | 1970 | 880907 | 178036 | 901329432 | 4361048 | all pass |
| post8 B (suppl.) | 1791 | 0 | 0 | 864689096 | 2221232 | all pass |
| post8 S (suppl.) | 2080 | 1118350 | 178036 | 860342952 | 5346728 | all pass |

Storage and per-artifact maxima are byte-identical to darkstar's per
configuration (content-addressed artifacts measured from identical
canonical bytes); heap/time differ by host as expected.

Cross-host: every configuration's image digest is IDENTICAL across
darkstar and jupiter (canonical agreement of the measured images on both
pins); every verdict PASS on both hosts.

## S minus B (the declaration's overhead record, with the B limitation)

The B limitation, stated per the declaration: annotations are COMMON
compile work (the production compiler always annotates), and B strips C1
markers only before projection/lowering/publication/persistence/load/
query. S-B therefore measures DOWNSTREAM static-artifact overhead and
cannot establish the annotation producer's incremental compile cost; no
zero-overhead claim is made.

| Quantity (7-member comparable set) | darkstar pre | darkstar post | jupiter pre | jupiter post | S-B ceiling |
|---|---:|---:|---:|---:|---:|
| pipeline time ms | -59 (noise) | +453 | +129 (noise) | +246 | 40000 |
| retained B | +2024056 | +2969224 | +2003152 | +2921264 | 96000000 |
| aggregate static bytes | +648359 | +880907 | +648359 | +880907 | 32000000 |

At this corpus scale the pipeline-time S-B delta is below run-to-run
noise (pre7 darkstar measured S FASTER than B); the memory and storage
deltas are the meaningful signal, and both are two-plus orders under
their ceilings.

## Attribution (port Phase 2 d60038ef vs the ordering fix)

- The compiled images CHANGED across the pins (digests a8f247fb pre vs
  3ef61619 post on the identical 7-member corpus): the port's Phase 2
  wrapper reduction (3 to 2 applications per wrapper, Phase 3r 8456c2e9
  lineage) changed the corpus's canonical code. Pre/post byte sizes are
  therefore measurements of DIFFERENT (both valid) compiled content.
- The heavyweight side artifact SHRANK across the port: pre7-S
  per-artifact max 200858 B (the argument-vector member's provenance
  relation) vs post7-S 178036 B -- consistent with fewer wrapper
  applications at post. Aggregate 7-member storage moved 648359 to
  880907 B (post larger in aggregate on darkstar; per-member tables in
  the logs) -- both far under the 32 MB ceiling.
- The ordering fix's own contribution to post: it is INSIDE the post pin
  and inseparable from it at the whole-pipeline level by design; what the
  fix round already pinned exactly is WHICH members changed bytes under
  it (argument-vector, lower-unit) and that its per-child cost is
  content projection + sort at the emitter. No artifact KINDS were added;
  no trace; the declaration's S-B row bounds its downstream effect
  together with the port's.
- Cold/warm and stage buckets: every stage-bucket maximum on both hosts
  is under the 80000 ms per-stage ceiling by two-plus orders (largest
  observed bucket ~16 s cold compile-expand-publish on the darkstar
  8-member supplementary run).

## Honesty section

- CORPUS-FIDELITY BUG (found by this round's own cross-check, fixed,
  re-measured): the corpus copy's first version applied the C1-stripping
  prepare unconditionally, so early "S" runs measured a B-shaped corpus
  (empty provenance relation, 4-byte payloads). Detected when jupiter's
  post8-S storage (7277 B) contradicted darkstar's cohort-based post8-S
  (1118350 B) with identical image digests. Fixed (annotated? gate +
  the cohort's exact admission tokens), fidelity re-verified member-by-
  member (rows byte-equal), and EVERY S configuration re-executed with
  the fixed corpus on both hosts. The B runs are unaffected (the B path
  strips identically in both versions); darkstar's post8 pair (cohort-
  based) was never affected.
- The lower-unit member is EXCLUDED from the comparable set by a genuine
  pre-port refusal (recorded above with the probe output); the
  declaration's 8-member ceilings are evaluated against BOTH the
  7-member comparable set and the 8-member supplementary runs -- both
  pass everywhere.
- Memory method: JVM GC-pinned used-heap deltas (retained) and 2 ms
  sampled used-heap peaks, per the declaration; no RSS substitution was
  needed (heap available on the JVM lane on both hosts). Comparability
  limit: sampled peaks are a lower bound on the true transient peak.
- D1 remains dissolved; no correspondence acceptance is claimed. No
  ceiling was raised; none was exceeded -- no FAILED bounds to report.

## Commands (exact forms)

- darkstar: java -Xss64m -cp $(clojure -Spath -A:test) clojure.main -m
  yang.cc4-budget-runner <B|S> [EX=lower-unit|EX=none], per configuration,
  in the worktree (post) and target/cc4-preport (pre), classpath captured
  per tree; all runs through the temporary driver namespace (deleted at
  delivery; logs under target/cc4-*.log, mirrored to the evidence dir).
- jupiter: bash ci/cc4-in/cc4-jupiter-driver.sh (archives of both pins
  from ~/ci/datomworld-fixorder, antlr-gen + classpath per tree, then the
  same runner invocations), redo pass ci/cc4-in/cc4-jupiter-redo.sh for
  the S configurations after the corpus fix; logs fetched to the evidence
  dir as jupiter-*.log.

---

## CORRECTION r2 (review 1795189757000, 2026-11-20 16:00:17 GMT / 2026-11-20 23:00:17 Asia/Ho_Chi_Minh)

Reviewer verdict: findings with dispositions -- all 84 emitted verdicts
verified against the raw EDN and unchanged; the corrections below come
from the committed evidence, no re-measurement. The original text above
stays as delivered; this section supersedes the affected cells and prose.

### F1 -- darkstar pre7 B cells and derived S-B entries corrected

The original table row quoted the pre7 B run's COLD sample for warm-time
and an earlier B run's heap/retained figures. Corrected from the samples
in darkstar-cc4-pre7-B.log (warm samples 1,106 / 1,085 / 1,023 ms):

| Cell (darkstar pre7 B) | Original | Corrected |
|---|---:|---:|
| warm pipeline max | 1149 ms | 1106 ms |
| sampled heap max | 468581168 B | 464122968 B |
| retained max | 1138072 B | 1139560 B |

Derived S-B entries (S maxima 1090 ms, 3162104 B verified by the
reviewer):

| Entry | Original | Corrected |
|---|---:|---:|
| darkstar pre7 pipeline S-B | -59 ms | -16 ms |
| darkstar pre7 retained S-B | +2024056 B | +2022544 B |

Every affected verdict still passes.

### F2 -- timing interpretation, sample phase and headroom corrected

- The blanket "below run-to-run noise" claim is withdrawn. The observed
  warm ranges are DISJOINT between B and S on both hosts (darkstar
  post7: B 1185-1238 ms vs S 1470-1691 ms; jupiter post7: B 1522-1724 ms
  vs S 1921-1970 ms). At pre7 the sign is mixed (darkstar S faster by
  16 ms on maxima; jupiter S slower by 129 ms), so three samples do not
  establish a noise floor; what they do establish is that every S-B
  pipeline delta is small against the 40,000 ms allowance -- the largest
  comparable-set delta is the supplementary post8 pair's +3302 ms, and
  the comparable-set maxima are +453 ms (darkstar post7) and +246 ms
  (jupiter post7).
- The largest recorded stage bucket, 15,538 ms, is a WARM
  compile-expand-publish sample in darkstar post8 S -- not a cold one as
  the original prose said. The largest COLD bucket is 12,836 ms
  (darkstar post8 B).
- Headroom corrected: the 80,000 ms stage ceiling is about 5.1x the
  largest observed bucket (15,538 ms); the largest S-B retained delta is
  about 32x below its 96,000,000 B ceiling; the largest S-B aggregate
  storage delta is about 36x below its 32,000,000 B ceiling. The
  original "two-plus orders" phrasing applies only to the memory and
  storage deltas, not to the stage/time margins.

### F3 -- attribution wording corrected

The pre/post shrink (the :argument-vector member's relation 200,858 B to
178,036 B, its application count 294 to 263; aggregate seven-member
storage rising 648,359 B to 880,907 B) is CONSISTENT WITH PHASE 2
(d60038ef, the same stable patch ID as landed 8e1dab6d; 8456c2e9 records
the kernel-gate stamping wrapper's three-to-two application change). The
c1-1 to c1-2 ordering re-pin is visible IN the post pin -- exactly
:argument-vector and :lower-unit change, with application counts fixed
at 263 and 256 -- but its byte and timing CONTRIBUTIONS within the post
pin are NOT quantified by these measurements; the two budget pins also
contain port-era changes to :prelude and :clj outside the ordering-only
change set. The within-pin S-B results describe downstream
static-artifact overhead for each compiled corpus, and the emitter's
per-child content projection and sort run in BOTH B and S (they are
common compile work; S-B does not measure them).

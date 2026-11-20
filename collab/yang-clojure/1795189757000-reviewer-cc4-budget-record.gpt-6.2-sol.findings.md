# cc4 budget closure record review

Reviewed commit `c43d1000` on `fix/constructed-collection-order` as an independent mechanical reviewer. Scope is the measurement record. The compiler fix landed in `b86bc0e8`.

**Verdict — findings with dispositions. Correct the closure record before folding it to master.** All 12 configurations pass the declared numerical bounds on the recorded samples. The corrections below can be made from the committed evidence.

## Findings and dispositions

### F1. Darkstar pre7 B and its S−B entries disagree with the raw log

| Quantity | Closure record | Recomputed from samples |
| --- | ---: | ---: |
| Darkstar pre7 B warm pipeline max | 1,149 ms | 1,106 ms |
| Darkstar pre7 B sampled heap max | 468,581,168 B | 464,122,968 B |
| Darkstar pre7 B retained max | 1,138,072 B | 1,139,560 B |
| Darkstar pre7 pipeline S−B | −59 ms | −16 ms |
| Darkstar pre7 retained S−B | +2,024,056 B | +2,022,544 B |

The pre7 B warm times are 1,106, 1,085 and 1,023 ms. The corrected S−B values use the verified S maxima of 1,090 ms and 3,162,104 B. The other host table cells and S−B entries agree with the evidence. **Disposition** — correct these five cells. Every affected verdict still passes. [Pre7 B log](1795127457000-cc4-budget-evidence/darkstar-cc4-pre7-B.log) · [Pre7 S log](1795127457000-cc4-budget-evidence/darkstar-cc4-pre7-S.log) · [Closure findings](1795130900000-engineer-cc4-budget-closure.glm-5.3.findings.md)

### F2. The timing interpretation and headroom prose overstate the evidence

The post7 warm samples span 1,185–1,238 ms for B and 1,470–1,691 ms for S on darkstar, and 1,522–1,724 ms for B and 1,921–1,970 ms for S on jupiter. The observed ranges are disjoint on both hosts. The three samples per configuration and the mixed sign at pre7 do not establish the blanket claim that S−B is below run-to-run noise. The largest comparable-set retained and storage deltas are about 32× and 36× below their ceilings, respectively.

The largest recorded stage bucket is 15,538 ms, a **warm** compile/expand/publish sample in darkstar post8 S. The largest cold bucket is 12,836 ms, in darkstar post8 B. The 80,000 ms stage ceiling is about 5.1× the largest observed bucket. **Disposition** — report the observed S−B deltas without the blanket noise claim, and correct the sample phase and headroom in the prose. The stage and S−B verdicts still pass. [Darkstar post7 B](1795127457000-cc4-budget-evidence/darkstar-cc4-post7-B.log) · [Darkstar post7 S](1795127457000-cc4-budget-evidence/darkstar-cc4-post7-S.log) · [Jupiter post7 B](1795127457000-cc4-budget-evidence/jupiter-cc4-post7-B.log) · [Jupiter post7 S](1795127457000-cc4-budget-evidence/jupiter-cc4-post7-S.log) · [Darkstar post8 B](1795127457000-cc4-budget-evidence/darkstar-cc4-post-B.log) · [Darkstar post8 S](1795127457000-cc4-budget-evidence/darkstar-cc4-post-S.log)

### F3. The attribution should distinguish the measured pre/post shift from the fix in the post pin

On both hosts, the `:argument-vector` relation goes from 200,858 to 178,036 B and its application count goes from 294 to 263. Aggregate seven-member storage rises from 648,359 to 880,907 B. The application reduction is consistent with the continuation port. Phase 2 `d60038ef` has the same stable patch ID as landed `8e1dab6d`, and `8456c2e9` records the three-to-two application change for the kernel-gate stamping wrapper.

I compared both pinned replay snapshot bundles at `2175252d`. Exactly `:argument-vector` and `:lower-unit` change across the ordering-only c1-1 → c1-2 re-pin, with their application counts fixed at 263 and 256. Their new roots occur in the post budget logs. `:argument-vector` is in the comparable and supplementary sets, while `:lower-unit` is in the supplementary set. The two budget pins also contain port-era changes to `:prelude` and `:clj` that are outside the ordering-only change set. **Disposition** — retain the measured shrink and the two-member ordering attribution. Describe the shrink as consistent with Phase 2, with the ordering change visible but its byte and timing contributions unquantified. The within-pin S−B results describe downstream artifact overhead for each compiled corpus. The emitter projection and sort run in both B and S. [Re-pin ledger](1795028800000-fixorder-evidence/constructed-collections-repin-ledger-v1.md) · [Pre-fix snapshots](../../test/resources/yang/clojure/cc4-replay-snapshots-prefix-c1-1.edn) · [Post-fix snapshots](../../test/resources/yang/clojure/cc4-replay-snapshots.edn)

## Verified against the raw record

I parsed the committed EDN for both hosts and all three B/S pairs per host. Darkstar's `post-B` and `post-S` filenames are the eight-member runs. For each configuration I recomputed the warm maxima from three pipeline samples, the retained maximum from five after-minus-before samples, and the cold and stage verdicts from the applicable recorded samples. All 84 emitted verdicts agree with the recomputation. The runner's `:maxima` covers warm samples for pipeline, storage, per-artifact size and heap. I also checked the recorded cold storage, artifact sizes and sampled heap against their respective ceilings. Logged per-member artifact lengths independently reproduce the per-artifact max. Aggregate storage is the runner's content-address-deduplicated `:storage` counter. The EDN does not include the payloads for independent re-encoding. [Raw evidence](1795127457000-cc4-budget-evidence/) · [Declaration v1](1795016601236-cc4-r2-budget-declaration-v1.md)

| Quantity | Largest recorded value across applicable configurations | Declaration v1 ceiling |
| --- | ---: | ---: |
| Aggregate static storage | 1,118,350 B | 32,000,000 B |
| Single static artifact | 200,858 B | 2,000,000 B |
| Retained compiled-inclusive heap delta | 5,442,488 B | 96,000,000 B |
| Absolute sampled used-heap peak | 901,329,432 B | 2,147,483,648 B |
| Warm pipeline | 19,324 ms | 80,000 ms |
| Cold pipeline | 14,486 ms | 160,000 ms |
| Stage bucket | 15,538 ms | 80,000 ms |
| S−B warm pipeline | +3,302 ms | 40,000 ms |
| S−B retained heap | +3,397,816 B | 96,000,000 B |
| S−B aggregate static storage | +1,118,350 B | 32,000,000 B |

All per-member and aggregate preflight values passed. The largest aggregate counts were 1,726 rows, 3,110 occurrences, 880 applications, depth 51 and 22,162 path bytes, below the respective limits of 50,000, 100,000, 10,000, 256 and 8,000,000 B. The declaration and r2 draft both have identical blob IDs at their introduction (`706d44f9`), the reviewed commit and in the worktree.

The hosts agree on `:storage`, `:largest-artifact` and every per-member size vector for each corresponding cold and warm record. The six B/S pairs agree on their full image digests and ordered roots in all recorded cold and warm samples. Each cohort's digest also agrees across hosts. The three digest prefixes are `a8f247fb4c26ead4` for pre7, `3ef61619af947f7a` for post7 and `f129cdc573742a87` for post8. [Raw evidence](1795127457000-cc4-budget-evidence/)

The closure states that compilation produces annotations for both paths and limits S−B to downstream static-artifact overhead. It makes no zero-overhead claim. The pre-port probe confirms `:lower-unit` refuses because `(replay 2)` may discard an error outcome as a nonfinal top-level form. Both seven-member pins omit that member, and the supplementary post8 pair on each host includes it. The fidelity log independently records equality and ordering of the frontend metadata rows for all eight post members. [Pre-port refusal](1795127457000-cc4-budget-evidence/darkstar-cc4-pre-members.log) · [Fidelity probe](1795127457000-cc4-budget-evidence/darkstar-cc4-fidelity.log)

The runner and corpus build measurement inputs and observations. They neither regenerate nor write replay expectations. The equality gate compares computed actual snapshots with the literal `replay-expectations` vector. Repository clj-kondo (`clojure -M:kondo --cache false --lint test/yang/cc4_budget_runner.clj test/yang/cc4_corpus.clj`) reported **0 errors and 0 warnings**. The two archived `.clj` evidence mirrors are byte-identical to the harnesses. Linting the mirrors and originals in a single invocation reports two archive filename/namespace errors and 14 duplicate-var warnings from the mirrored definitions. The clean lint result applies to the two executable harness paths. [Budget runner](../../test/yang/cc4_budget_runner.clj) · [Corpus](../../test/yang/cc4_corpus.clj) · [Equality gate](../../test/yang/clojure_callsite_test.cljc)

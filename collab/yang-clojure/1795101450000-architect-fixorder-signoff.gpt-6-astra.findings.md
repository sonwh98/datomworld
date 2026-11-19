Completed-GMT: 2026-11-19 15:24:07 GMT
Completed-Local: 2026-11-19 22:24:07 Asia/Ho_Chi_Minh
Role: Architect (compiler provenance)
Coding-Agent: codex

# Verdict: REMAINING-BLOCKERS

This is a review of the committed branch pair, not an approval of the intended implementation. **I do not sign off this pair for merge to master.** The emitter implements the ruled policy, and reviewer F1/F2/F3 are resolved, but the acceptance evidence and several required tests are incomplete. There is also a broken retained binary fixture that the green legacy test never reads.

Scope inspected:

- `fix/constructed-collection-order` = `7e6d720c8a745b6366643a7e1388b4e4380ead79`.
- `cc4-fourhost-equality` = `706d44f9c2f46d0959b88e68f82e8bb3e98cefd0`.
- Both local branches and origin-tracking refs match. A successful **live `git ls-remote origin`** independently confirmed both pushed tips and master `428486df6a92a9659e4053509dddfd93b225c460`.
- `7e6d720c` has parents `39562f7c` (the fix) and `657f01ff` (then-master). The fix's implementation/tests/fixtures are unchanged by that merge. Its worktree copies match the committed blobs examined below.

Authority: `collab/yang-clojure/1795017757000-architect-cc4-dart-map-order-ruling.gpt-6-astra.findings.md`, RULING 1–4, read in full. Repository architectural foundations and build/test rules were also read. The code-review-pr skill's automated diff analysis found no automated smells; the findings below come from direct source/artifact inspection and small arithmetic probes.

## Remaining blockers and dispositions

### B1 — Required Jolt comparison has never executed

Both committed logs under `collab/yang-clojure/1795028800000-fixorder-evidence/`, `fixorder-jolt-callsite.log` and `fixorder-jolt-order.log`, contain only the manifest refusal:

```text
UNLISTED yin.repl.php-parser-test
UNLISTED yin.vm.code-entry-test
UNLISTED yin.vm.machine-data-test
UNLISTED yin.vm.provenance-test
```

The r2 findings explicitly say Jolt was not rerun; the independent review's APPROVE explicitly retains this dependency. The merged branch still has the same lane file as the fix commit. There is no four-host result here. JVM/Node/Dart green cannot substitute for Jolt; the governing ruling expressly says zero Jolt children is not a pass and requires owner reconciliation before four-host acceptance.

**Disposition:** J2 owner reconciles the inventory through the authorized ownership route; execute both complete targeted namespaces on Jolt, against the common expectations and repaired tests, retaining commands, source/toolchain pins, actual child counts and results. Do not bypass inventory validation or relabel this as an accepted reader refusal.

### B2 — The landed legacy CBOR fixture is a Java array printer string

`test/resources/yang/clojure/constructed-collections-legacy-c1-1.cbor` at `7e6d720c` contains exactly **11 bytes**:

```text
[B@257c7d2d
```

Its SHA-256 really is the published `d4fa698d028e4830ae07168ea9269310f46ff51c7ce82cb15ffb1d99bade42b3`; a matching pin does not make these bytes the claimed artifact. This is not the retained canonical row-set CBOR.

The explanation for the green test is visible in `test/yang/clojure_collection_order_test.cljc:1003` and `:1087`: `legacy-payload` decodes `legacy-c1-1-payload-b64`, not the fixture file. Independently concatenating and base64-decoding the embedded strings produces **5,403 bytes**, SHA-256 `9673c6143af2f20e74d7321f493784a145251c7cf8b758294cba1abfa15f49b2`, different from the supposed binary original. The source docstring and findings incorrectly claim these are copies of one artifact.

**Disposition:** restore an actual binary retained artifact from the verified historical payload; validate its decoding, old root and old execution behavior; assert that its bytes equal the portable embedded copy. Record the corrected digest and the repair without rewriting the historical report. The existing embedded legacy-load test is useful, but does not discharge custody of the advertised binary artifact.

### B3 — The broader map/set corpus is not a common cross-host replay gate

Two independently verifiable gaps violate RULING 3's portable corpus contract:

1. At `test/yang/clojure_collection_order_test.cljc:260`, `lcg` claims all products are below `2^53`. The callers at `:436` and `:499` supply seeds `1795017757000` and `1795017757001`, without reducing the seed first. The very first product is too large. I executed the **actual extracted landed `lcg`/`lcg-shuffle` definitions** on Java 21 / Clojure 1.12.4 and checked Node 26.1.0 arithmetic against the generated ClojureScript implementation (`cljs.core.mod(48271 * x, 2147483646)`). For seed `1795017757000`:

   | Quantity | JVM | Node |
   | --- | --- | --- |
   | First state | `639147076` | `639147084` |
   | Next two states | `1518447160, 1198538734` | `1518833328, 511901448` |
   | Size-8 shuffle | `[3 0 1 2 5 7 6 4]` | `[1 7 3 6 2 0 5 4]` |

   The exact first product is `86647302148147000`, greater than `9007199254740992` (`2^53`). The adjacent seeds also diverge. Equal assertion counts therefore do not establish identical shuffled inputs.

2. At `:405–438` and `:470–501`, each host computes `snaps` from its own variants and asserts `(apply = snaps)`. That proves within-host representation/permutation consistency, not cross-host equality for this corpus. No shared recorded corpus snapshots are compared here or emitted for an external cross-host comparison. The separate eight-member cc4 cohort is correctly pinned, but does not contain these heterogeneous, nested, NaN, and size-threshold corpus cases. A host-wide deterministic content difference in those cases can pass this suite.

**Disposition:** use recorded permutation vectors or a generator with a safely normalized seed, and pin/check the generated vectors across hosts. Record one independently reviewed reference for the broader corpus's canonical bytes/content roots, full C1/key vectors and per-format provenance; compare every host to that same reference. Keep within-host permutation checks as additional properties. Rerun the affected four-host gates after repair.

### B4 — Several mandatory behavioral/metadata assertions are weaker than the ruling

These are concrete acceptance gaps, not a claim that the emitter currently violates these behaviors:

- `test/yang/clojure_collection_order_test.cljc:618` claims semantic literal metadata stays content, but compares a map containing a quoted metadata-bearing key and a dynamic value with `{:plain 2}`. Those programs differ even if all literal metadata is discarded. Compare otherwise identical literal payloads with and without the semantic metadata.
- `:825–839` accepts either `[]` or `[:early :early-v]` on each route and requires only **some** route to raise. It does not establish the independently expected canonical prefix or failure on every applicable route. There is no continuation-transfer fixture in the new suite, despite RULING 3 test 5 requiring the no-later-event boundary after failure/transfer.
- The set execution block at `:815` checks the final collision result, but its operands have no `tap` events. It cannot establish canonical set-element effect order or exactly-once evaluation of the colliding occurrences. The map trace checks do not substitute for this set requirement.

**Disposition:** add discriminating metadata controls, independently derived map/set traces, and failure/continuation-transfer assertions on every supported execution route. Record exact existing refusals for unsupported routes; do not silently accept missing comparisons. These are bounded changes to the already authorized test surface.

## What is verified clean

**RULING 1 implementation:** `src/cljc/yang/clojure.cljc:302–372` compiles each child once with the existing environments and `tail? false`, projects through `vm/ast->semantic-bytecode`, keeps map key/value ASTs paired, and sorts `[key-root value-root]` with `jing/canonical-compare`. Set records wrap their root in a one-element vector; the fixed identical wrapper preserves the ruled root ordering. Flattening retains all occurrences and original AST objects, with the seed first. Vectors retain written order. There is no runtime evaluation, persisted ordering table, global application sort, or post-expansion re-sort.

**RULING 2 implementation scope:** only the collection emitter changes. Literal/quote handling, explicit applications, VM transition semantics, constructor resolution and tail marking remain unchanged. The code and legacy example correctly acknowledge the observable ordering shift for newly compiled colliding/effectful operands. Test coverage limits are B4, rather than evidence of an illicit VM or optimizer change.

**RULING 4 manifest:** `src/cljc/yin/repl/frontends.cljc:43–59` advances `c1-1` to `c1-2` and sets `:yang.clojure/constructed-collections-v1`. C1 support/marker and shared execution grammar/lowerer were not changed. No loader/storage rewrite was introduced. The embedded legacy fixture is exercised without recompilation by the existing green test; the disk copy is B2.

**Bounds:** `706d44f9..39562f7c` changes 29 files, all inside RULING 3's table. The only production changes are the collection emitter and frontend manifest; J2 changes only include the new namespace. `git diff --check` passes. The cc4 commit's production-source prohibition is also preserved: its changes are tests, fixtures, lane inventory and evidence.

The literal claim that *all* of `706d44f9..7e6d720c` is just fix/tests/evidence is inaccurate: it also includes the merged master history. This is not an out-of-bounds implementation finding. Comparing the fix's own commit and the final tree against its master parent separates that history; the final delta against `657f01ff` contains only cc4 plus the bounded fix. No fix-owned source/test/fixture changed in the master merge.

**Reviewer dispositions:** the main-checkout review artifact contains CHANGES-REQUIRED followed by APPROVE. Its F1 resolution is genuinely present at collection test `:568–595`: a finalized `(into #{} [##NaN ##NaN])` pair plus a dynamic element, two occurrences of the independently projected NaN root, three total elements, four operands including seed, and non-descending order. F2 is the `strictly-ascending?` rename/documentation; F3 titles distinguish the genuine tie from distinct-element coverage. The probe record and r2 result embedded in `1795034000000-...stdout.log` support this disposition. Those closed findings do not close B1–B4.

**Controlled re-pin:** direct blob comparison confirms the archived pre-fix snapshot is byte-for-byte the cc4 active snapshot, SHA-256 `5f28913f56b1b001aa11d4ba8ef6216df334fcadaf7ba096b2992681007a959f`. Comparing all eight member records shows changes only in `:argument-vector` and `:lower-unit`; the other six match, including publication and macro-log pins. Counts 263 and 256 remain unchanged. The active bundle's SHA-256 is `093f903a62e780a665ee2da107a1630126385f53b028d14e236031d48a70e5c1`.

The 20 tracked cc4 r2 evidence files, `cc4-replay-dart-divergences.edn`, `cc4-replay-jvm.cbor` and `cc4-replay-jvm-affected.edn` are unchanged between cc4 and the landed fix. The ledger records the expected named arity constructor shift to `fn / reason / argc`, with new roots `4a9a1b91…` and `f4603726…`; it does not privilege the old JVM order. The broader cross-host corpus and four-host acceptance are still B1/B3.

**Large RED log:** the untracked raw log exists in the authoring worktree, is 132,667,579 bytes, and hashes to `d0b0fe59ab3279e94bc94901286fdb3dfe1df76832ce544174f90ed5a0164ad9`, exactly the committed digest's pin. Its retained summary is 10 tests, 91,387 assertions, 91,115 failures, zero errors. The raw log is not tracked; the largest added/changed blob in the branch pair's delta against `657f01ff` is the 1,747,861-byte cc4 capture. The digest substitution itself is verified.

## Gate evidence and its limits

I read the committed logs rather than inferring success from the reports:

| Gate | Verified saved result |
| --- | --- |
| JVM callsite r1/r2 | 46 tests / 605 assertions / zero failures/errors |
| JVM collection r2 | 10 / 91,392 / zero failures/errors |
| Node callsite | 41 / 516 / zero failures/errors |
| Node collection r2 | 10 / 91,392 / zero failures/errors |
| Dart callsite | 41 registered, all passed |
| Dart collection r2 | 10 registered, all passed |
| JVM compiler/manifest suites | 116 / 1,082 / zero failures/errors |
| Touched-file kondo | zero errors, one recorded unused-require warning |
| Jolt, both namespaces | manifest refusal, zero children |

The post-merge 56 / 91,997 claim equals the two JVM namespace totals, but arithmetic is not a run log. I did **not** independently recover the later post-format/post-merge run logs or the `fixorder-g2-20261119-043834` summary/lane logs from the main repository, authoring worktree, or searched temporary paths. The relay `collab/yang-php/1795098934000-from-yangclojure-master-red-store-write-allowlist.md` records the two pre-existing master lint failures and names Jupiter logs; it is an attribution report, not those logs. An optional read-only SSH attempt failed because `jupiter` did not resolve. No remote job was started.

**Landing evidence still needed:** make the saved gate summary and relevant lane/dao.stream logs locally reviewable, including their tested revision and the master-red comparison. I do not claim those tests were not run; I cannot independently certify them from the supplied local chain. The two reported master lint reds are not charged to this fix. A full quick run would not replace the ruling's missing targeted Jolt/corpus comparisons, and no new full branch suite is requested here.

The reviewer pins predate final formatting. Current committed test hashes are `dc111b72a8011b284aeeaa5e8a55a68fd88b8738d19915dac646f730b42b6b81` (collection) and `e489b371e534bb4fabd835552f918d1c744e632e8bfe1103f8388535a74de875` (callsite), not the review's `5e3c9edc…` / `0dc08f10…`. I inspected the landed tests directly; I do not describe the earlier review pins as the final file pins. Emitter `4a47a86e…` and frontend manifest `8a3f8132…` still match their reported pins.

## cc4 adoption and landing order

The budget declaration and draft runner are present, but measured B/S, pre/post-port, cross-check-host, retained/peak and elapsed-time tables are not. Both cc4 and fix findings explicitly leave that work open. **No cc4 adoption/budget closure is granted.** This is a separate remaining work package, not an assertion that every compiler-repair merge must finish unrelated measurements first. Resume it after the determinism gates pass; retain the declared finite ceilings, pre-port pin `8afd82f5…`, and ordering-fix confounder separate from Phase 2 `d60038ef`. D1 remains dissolved.

**No merge instruction is authorized by this verdict.** Once the blockers are repaired and the resulting descendant is reviewed, land the fix together with its cc4 ancestor; do not first expose the standalone Dart-red cc4 commit on master. A separate cc4 merge is unnecessary because the fix already contains it.

At inspection, master and the submitted fix diverge from `657f01ff` (5 master-only commits, 3 fix-side commits). Thus the submitted fix cannot be fast-forwarded onto current master. A future approved landing requires a **merge commit** (or a separately revalidated reconciliation whose resulting ancestry permits a fast-forward); recheck current master, conflicts, targeted gates and the dao.stream precondition then. This report approves neither a speculative merge result nor later revisions automatically.

No implementation, tests, fixtures, branches, index, or existing evidence were deliberately changed in this session. Only this findings file was authored. No suite was rerun; verification comprised Git/tree reads, evidence/hash comparisons and the small JVM/Node generator probe. Concurrent pre-existing worktree edits were left untouched.

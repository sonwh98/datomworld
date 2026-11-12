Created-GMT: 2026-11-11 13:40:00 GMT
Created-Local: 2026-11-11 20:40:00 +0700
Coding-Agent: muse
Session-ID: 7db9b5cc-3472-4c31-9657-1b41aaa76ae3

# cc3 bounded run declaration v2 — NEW versioned run (astra r4 ruling (c))

This declaration is written BEFORE the v2 measurement run, per design
§6.3 and astra's r4 exceedance ruling (c). It creates a NEW, explicitly
versioned bounded run. It does not amend, re-scope, or relabel the
superseded run (see §5).

## 1. Pinned corpus, revision, host, and JVM settings

| Item | Pinned value |
|---|---|
| Worktree / branch | `/Users/sto/workspace/datomworld-cc3`, branch `cc3-lowering-provenance` |
| Base revision | `ced9f2c2bdb43ab4a416b4ab408c76eb29245764` plus the uncommitted cc3 diff (ten tracked files, +1651/−98 at r6 delivery: the eight cc3 files plus the two F7 diagnostic scripts `test/d2_wedge_min_repro.clj` / `test/d2_wedge_probe2.clj`, which the measurement script does not load; latte oracle test file further changed in r5 for F2 caller threading — behaviour-neutral for this fixture's compiled content). Exact pin: see the digest rows below and §6. |
| Fixture (the whole corpus of this run) | `{:id :cc3-binding :call '(latte-kernel.utils/vectorn? [1 2 3] 2)}` admitted through `kernel-compile`, bound through `cc3-oracle-binding` |
| Upstream oracle revision | latte-kernel `86fca0d1d054af005cb37395eae04500d45e6279` (pinned in `deps.edn` `:test` alias) |
| Compiler base recorded by the evidence harness | `7dc18478` |
| Host | Darwin 25.6.0 arm64, Apple M1 Pro, 17,179,869,184 B RAM |
| JVM | OpenJDK 17.0.18+0 (Homebrew, 64-Bit Server VM, mixed mode) |
| Clojure | Clojure CLI 1.12.4.1602; no explicit `-Xmx` (JVM default max heap). JVM stack: **corrected in r6 (V2)** — the v2 (r5) measured command (§4 as originally written) activated ONLY the `:f6` alias, which supplied `:extra-paths ["test"]` and the latte-kernel `:extra-deps` and NO `:jvm-opts`; the project `:test` alias was NOT activated, so its `-Xss64m` (`deps.edn:52`) and its test-runner main-opts were NOT in effect for the v2 run. The v2 samples therefore stand as measured under the JVM default thread stack. The v2.1 declared command (§4, r6 revision) supplies `-Xss64m` explicitly through `:f6`'s own `:jvm-opts ["-Xss64m"]`, and the v2.1 results in §6 were measured under exactly that declared command. `-Xss64m` is declared for parity with the `:test` stack policy and headroom, not because the v2 ceilings depended on it: the v2 samples already met every ceiling without it. |
| Measurement script | `collab/yang-clojure/1794386683000-cc3-f6-measure.clj` (in-repo, r5 revision: maximum-based rule, compiled-inclusive holder) |
| Measured patch pin (SHA-256) | `git diff` (ten tracked files listed in §6) = `05076f51add1a5b5285c62c6b85c7eb8875d7c2780a7b33613e46742a250887d` (computed 2026-11-11 after the final r6 code edit; reproduce with `git diff \| shasum -a 256` in this worktree) |
| Measurement script pin (SHA-256) | `shasum -a 256 collab/yang-clojure/1794386683000-cc3-f6-measure.clj` = `345b432dace388ce20bb463d2241c650b400b644b5194cd0ddae1a3fb42b37c8` |

## 2. Retained object set and maximum simultaneous retention

The retained set for one fixture, all held simultaneously by one local
holder (never a global Var), is:

- the **compiled input**: the `kernel-compile` result (compiled AST,
  stamped AST, site table, retained `:admission-origin`);
- the **binding**: `cc3-oracle-binding` result (C1 tree, rows, marked
  and eligible occurrence sets, site→key binding);
- the **reconstruction evidence**: the binding's stamped tree/rows,
  stamped marked/eligible sets, site correspondence map, and stamped
  paths — i.e. everything needed to reconstruct and verify each bound
  key from the retained compilation;
- the lowered images: `linearize/project-rows` output plus
  `derive/stack-image` and `derive/register-image`;
- the provenance relation (schema-1 map over admission/root and the
  semantic, stack, and register call-provenance) and, in the pipeline
  variant, its durable record in a file-backed content store.

Two holder variants are measured: **binding-only** (binding, lowered
images, relation, reconstruction evidence — the compiled input is
released after binding) and **compiled-inclusive** (the same set plus
the retained compiled input). The compiled-inclusive variant is the
acceptance variant; the binding-only variant is reported for
comparison and is NOT the acceptance holder.

**Maximum simultaneously retained fixtures: 1.** Every retained and
peak measurement holds exactly one fixture's retained set at a time;
the previous holder is released (and GC-pinned) before the next
repetition builds a new one.

## 3. Ceilings (separate, finite, pilot-justified)

Pilot measurements (superseded run, r4, warm JVM, same host/JVM):
binding-only retained samples 7,960,192 / 7,921,728 / 8,044,024 B
(median 7,960,192 B, **maximum 8,044,024 B**); compiled-inclusive
samples 9,008,912 / 8,939,024 / 8,826,416 B (median 8,939,024 B,
**maximum 9,008,912 B**); serialized rows 293,924 B; serialized
relation 802,260 B; peak used heap 292,720,240 B (GC-pinned baseline
38,967,336 B); pipeline elapsed 1,727 ms.

| Ceiling | v2 bound | Justification / headroom over pilot |
|---|---|---|
| Storage, per artifact (serialized canonical bytes) | **2,000,000 B** | Kept from r2. Pilot rows 293,924 B (6.8× headroom), relation 802,260 B (2.5× headroom). |
| Storage, aggregate retained serialized set (rows + relation + durable record, 1 fixture) | **4,000,000 B** | Pilot rows+relation = 1,096,184 B; the durable record is a small map over the same data. 4,000,000 B is ≥3.6× the pilot partial sum, leaving room for the record while staying finite. |
| Retained memory, aggregate (1 fixture, compiled-inclusive holder) | **12,000,000 B** | Set ABOVE the pilot compiled-inclusive **maximum** 9,008,912 B by 2,991,088 B (**+33.2% headroom**; +34.2% over the 8,939,024 B median; +49.2% over the binding-only maximum 8,044,024 B). Pilot repetition spread was ~2% (8,826,416–9,008,912 B), so 33% covers GC-delta noise, the longer r5 run-scope strings (tens of bytes), and host jitter on this machine class, without being an order-of-magnitude loosening. The floor is the measured maximum, per ruling (c): a ceiling at or below 9,008,912 B would declare a run already known to exceed it. |
| Peak heap (absolute used heap during the pipeline) | **1,073,741,824 B (1 GiB)** | Kept from r2. Pilot peak 292,720,240 B is 27.3% of this ceiling (3.67× headroom); the figure is absolute used heap (baseline + harness + transients), so the margin absorbs sampler/baseline variance. |
| Time, per pipeline repetition (compile → persistence → both tagged lanes) | **10,000 ms** | Kept from r2. Pilot 1,727 ms gives 5.8× headroom; warm-JVM figure, cold-JVM limits are stated in §4. |
| Guest instructions / trace records per C lane | **4,000,000 / 1,000,000** | Restated from the r1 record, NOT raised. |

## 4. Exact reproducible measurement procedure

Script: `collab/yang-clojure/1794386683000-cc3-f6-measure.clj`
(r5 revision). Run from the worktree root:

```
clojure -Sdeps '{:aliases {:f6 {:extra-paths ["test"] :jvm-opts ["-Xss64m"] :extra-deps {latte-kernel/latte-kernel {:git/url "https://github.com/latte-central/latte-kernel" :git/sha "86fca0d1d054af005cb37395eae04500d45e6279"}}}}}' -M:f6 collab/yang-clojure/1794386683000-cc3-f6-measure.clj
```

(Settings actually in effect, r6-corrected: the `:f6` alias defined
inline by `-Sdeps` supplies everything — `:extra-paths ["test"]` puts
the oracle test namespace on the classpath, `:jvm-opts ["-Xss64m"]`
sets the thread stack (added in the r6 revision of this command; the
v2 run used the same command WITHOUT `:jvm-opts`, i.e. the JVM
default stack — see §1), and `:extra-deps` pins latte-kernel. The
project's `:test` alias is NOT activated: its `-Xss64m`
(`deps.edn:52`) and test-runner main opts play no part. The
latte-kernel git dependency is named explicitly because the `:test`
alias, which normally carries it, also carries the test-runner main
opts; the git checkout is already cached locally. Verdicts are read
from the script's printed `:under-ceilings-v2` map, computed by the
maximum rule below. The v2.1 results under this exact declared
command are recorded in §6.)

Procedure, in order:

1. **Warmup**: build one retained structure and one full pipeline
   pass, DISCARD both, GC-pin. Class loading / JIT are charged to the
   warmup, not to measured repetitions.
2. **Retained memory**: 5 repetitions per holder variant
   (binding-only, then compiled-inclusive). Each repetition builds a
   fresh holder held only by a local, samples GC-pinned used heap
   (`System/gc()`, 50 ms, `System/gc()`, then
   `Runtime.totalMemory − freeMemory`) before and after, uses
   `System/identityHashCode` on the holder AFTER the second sample so
   it cannot be collected early, and releases it on return. ALL 5
   samples per variant are recorded.
3. **Peak / pipeline**: 3 pipeline repetitions. A sampler thread
   records used heap every 2 ms across lowering → binding /
   projection / derives → actual serialization
   (`dao.jing/canonical-bytes`) → durable persistence
   (`dao.jing/materialize!` of relation and record into a file-backed
   `yin.repl.store`, closed) → tagged execution (both C lanes must
   reach `:completed`). Peak, per-repetition elapsed time, and
   serialized byte counts are recorded per repetition.
4. **Pass/fail is MAXIMUM-based** (r5 F6 fix): a ceiling is met only
   if the MAXIMUM observed value is at or under it — retained memory:
   max of the 5 compiled-inclusive samples ≤ 12,000,000 B (the
   binding-only samples are reported, and also max-checked against
   the same aggregate ceiling for information); storage: max per
   artifact and max aggregate ≤ their ceilings; peak heap: max peak
   over the 3 repetitions ≤ 1 GiB; time: max elapsed ≤ 10,000 ms.
   Any single sample over its ceiling = EXCEEDED. Medians are
   printed for information only and never establish compliance.

Sampling limitations (named, not hidden): retained and peak figures
are process-wide JVM used-heap values, not an object-graph census;
`System/gc()` is a hint the JVM honored observably here, not a
guarantee; 2 ms peak sampling can miss sub-2 ms transients; figures
are comparable only on this host/JVM class and only for the named
holder scope; times are warm-JVM (the discarded warmup precedes all
measured repetitions).

## 5. Superseded run: failed status preserved

The r2/r4 bounded run (8,000,000 B aggregate retained ceiling,
median-based accounting in its r4 record) **FAILED and remains
failed**. Its exceedances, restated with maximum-based accounting:

- compiled-inclusive holder: samples 9,008,912 / 8,939,024 /
  8,826,416 B — every sample over the 8,000,000 B ceiling.
  **EXCEEDED.**
- binding-only holder: samples 7,960,192 / 7,921,728 / 8,044,024 B —
  the 8,044,024 B sample is over the 8,000,000 B ceiling, so under
  the corrected maximum rule this holder is **EXCEEDED** too (its r4
  "under (99.5%)" median label is corrected in r5; the samples
  themselves are unchanged).

Nothing in this v2 declaration relabels that run successful, raises
its ceiling retroactively, or shrinks its fixture. The v2 run below
is a separate, new run under the ceilings in §3; its results are
recorded in the r5 findings, not back-written into the superseded
run's record.

## 6. Exact pin and v2.1 re-measurement under the corrected declared settings (r6, V2)

Pin (computed 2026-11-11 after the final r6 code edit; nothing in
the measured patch changed afterwards — the digest was re-verified
identical after the diagnostic-script runs and lint):

- Ten-file tracked diff (`git diff`, +1651/−98):
  `src/cljc/yin/repl.cljc`, `src/cljc/yin/vm/linker/publish.cljc`,
  `src/cljc/yin/vm/semantic/derive.cljc`,
  `src/cljc/yin/vm/semantic/linearize.cljc`,
  `test/d2_wedge_min_repro.clj`, `test/d2_wedge_probe2.clj`,
  `test/yang/clojure_callsite_test.cljc`,
  `test/yang/latte_oracle_test.clj`,
  `test/yin/vm/semantic/derive_test.cljc`,
  `test/yin/vm/semantic/linearize_test.cljc`.
  SHA-256 of `git diff` output:
  `05076f51add1a5b5285c62c6b85c7eb8875d7c2780a7b33613e46742a250887d`.
- Measurement script SHA-256:
  `345b432dace388ce20bb463d2241c650b400b644b5194cd0ddae1a3fb42b37c8`.
- This declaration and the findings are untracked collab files and
  are not part of the `git diff` digest. Nothing is committed.

V2.1 run: the §4 command exactly as now declared (with
`:jvm-opts ["-Xss64m"]` supplied by `:f6`), 2026-11-11, warm JVM,
this host/JVM; run scope
`cc3-f6-measure-v2-326ca2ee-2f81-4711-9377-42cd2c62e080`;
after-release GC-used heap 39,226,944 B; both C lanes `:completed`
in all 3 pipeline repetitions; the run's `/tmp/cc3-f6-store-*` file
stores were removed afterwards. All samples recorded; verdicts are
maximum-based (§4 rule):

| Quantity | V2.1 samples (all recorded) | Maximum | Ceiling (§3) | Verdict (max rule) |
|---|---|---|---|---|
| Retained: compiled-inclusive holder (acceptance) | 8,828,592 / 8,832,864 / 8,888,424 / 8,829,928 / 8,924,640 B | 8,924,640 B | 12,000,000 B | PASS (74.4% of ceiling) |
| Retained: binding-only holder (informational) | 8,203,464 / 7,920,344 / 7,918,160 / 8,014,944 / 7,923,464 B | 8,203,464 B | 12,000,000 B | under (informational; not the acceptance holder) |
| Storage: rows canonical bytes | 312,886 B ×3 | 312,886 B | 2,000,000 B | PASS |
| Storage: relation canonical bytes | 856,315 B ×3 | 856,315 B | 2,000,000 B | PASS |
| Storage: durable record canonical bytes | 544 B ×3 | 544 B | — (in aggregate) | — |
| Storage: aggregate retained set | 1,169,745 B ×3 | 1,169,745 B | 4,000,000 B | PASS |
| Peak used heap (2 ms sampling) | 251,948,768 / 272,026,880 / 263,341,656 B | 272,026,880 B | 1,073,741,824 B | PASS |
| Pipeline elapsed | 772 / 769 / 743 ms | 772 ms | 10,000 ms | PASS |

The script's printed `:under-ceilings-v2` map was all-true. The
v2.1 figures sit beside the v2 (r5) figures (compiled-inclusive max
8,931,952 B without `-Xss64m`; v2.1 max 8,924,640 B with it) — the
ceiling holds under both stack settings, which is why §1 records
`-Xss64m` as declared parity/headroom rather than a requirement.
Serialized sizes are unchanged from v2 (the measurement fixture's
admission scope is the script's own run scope in both runs).

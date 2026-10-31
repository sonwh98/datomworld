I’ll read the design, rulings, and review reports, then inspect the diff and reapply at least ten surviving mutants. I’ll keep the worktree unchanged and verify byte-for-byte restoration after each mutation run.
The marker fix appears narrower than the ruling: it still interprets a well-formed guest map with a marker’s shape as an internal marker. I’m checking that boundary alongside the mutation tests. The child-budget change was left out; the report explains that it requires threading the budget through several functions, which the ruling allowed the author to defer.
Ten survivors have been independently re-applied and killed by their named tests so far, with restoration verified after every run. R29 is not equivalent: removing the gate’s cycle check prevents the later graph check from returning the required `:cyclic` refusal. I also found that general task decoding can bypass the new marker refusal; I’ll verify that with a focused probe after the mutation campaign finishes.
All 15 survivors were independently killed. The focused probes confirmed three remaining issues: task-result literals can bypass marker refusal, a cell and the distinct literal `[:identity :yin.k/c-1]` are falsely judged duplicates, and the seven-cell fixture raises `OutOfMemoryError` with a 256 MiB heap under default budgets. Both Fable root probes recomputed exactly. The restored baseline is running now.
Completed-GMT: 2026-10-11 16:37:18 GMT
Completed-Local: 2026-10-11 23:37:18 Asia/Bangkok

| Severity | File:line | Evidence | Correction |
|---|---|---|---|
| HIGH | `src/cljc/yin/vm/ucf/rows.cljc:290` | Independently constructed the seven-cell directed triangle/square fixture. `canonical-numbering` with default budgets and `-J-Xmx256m` raised `java.lang.OutOfMemoryError`, without a structured refusal. Nested label bytes grow while work charges count operations. | Bound intermediate label/signature bytes **before allocation**, including comparison representations; refuse `:resource-limit`. |
| MEDIUM | `src/cljc/yin/vm/ucf/rows.cljc:403` | Both `(rows/decode (rows/emit (task-node [] [:lit {:yin.k/row 1}])))` and the corresponding `{:yin.k/cell :c}` probe succeed. The refusal in `decoded-value` does not cover task-result literals. | Apply marker-collision refusal during literal validation across the carrier. Add task-result tests. |
| MEDIUM | `src/cljc/yin/vm/ucf/rows.cljc:564` | A guest window value `{:yin.k/row (rows/node :prim ['guest/f])}` encodes successfully as a reference. Well-formed collisions remain interpreted as markers, contrary to the ruling to refuse colliding guest maps. | Separate guest-value classification from explicit marker classification, using the existing encoder argument; refuse colliding guest maps regardless of payload validity. |
| MEDIUM | `src/cljc/yin/vm/ucf/rows.cljc:366` | Independently confirmed the author’s suspected false duplicate: a task containing cell `:x` and literal `[:identity :yin.k/c-1]` in one set refuses `:duplicate-key` at `[15]`. These are distinct values. | Use an equality representation that cannot collide with guest literals. Add a positive decode test. |

**1. Survivors and findings 1–13**

I independently reapplied **all 15 survivors**, using the original mutation replacements. Every run reached its named test and was killed:

| Mutants | Named killing test | Independent result |
|---|---|---|
| R06, R07, R10, R12 | `frame-test/rule-boundaries-refuse-their-tamper` | Respectively 2 failures, 1 error, 1 failure, 2 failures |
| R17, R21 | `rows-test/canonical-roots-are-pinned` | 2 failures; 1 failure |
| R24, R29, R45, R46 | `rows-test/gates-and-graph-before-hashes` | 1 failure each |
| R25, R30 | `rows-test/task-root-checks-carried-code-and-frame` | 1 failure each |
| R26 | `rows-test/unordered-containers-are-order-free` | 2 failures |
| R33, R35 | `rows-test/schema-and-literal-canonicality` | 1 failure each |

None is equivalent. R07 exposes a host error instead of the prescribed refusal. R29’s gate check is necessary: removing it prevents the later graph check from delivering `:cyclic`.

Disposition of Fable’s findings:

- **1:** Vector fix is correct by inspection; Dart remains unverified.
- **2–3:** Census-key validation and installs guard address the reported host exceptions.
- **4–5:** Real container permutation and canonical pins now exercise the missing behavior.
- **6:** **Incomplete**, as the marker counterexamples above demonstrate.
- **7–8:** Present nil `store-of` is refused by S4; numeric census order is documented and tested with ten cells.
- **9, 11:** Recorded limitations, as ruled; neither was fixed.
- **10:** Budget propagation remains unchanged. Deferral complies with the ruling’s conditional authorization.
- **12:** All listed surviving-mutant gaps are closed.
- **13:** Report wording and test names are corrected or qualified; the marker interpretation still needs resolution.

**2. Source correctness and canonical bytes**

The census validation, overflow-free decimal ordering, installs guard, and S4 change are small and correct for their stated purposes. Numeric census ordering preserves the previous implementation’s ordering for valid keys; I found no changed canonical bytes for the established fixtures.

The marker changes are incomplete. The false duplicate also rejects valid distinct values, compromising the promised accepted-domain bijection. This is an existing defect confirmed during review, rather than a newly introduced line.

Child numbering still uses the default budget during emission; caller-budget pass-through was not implemented.

**3. ClojureDart vector association**

The former multi-key vector association now chains single-key `assoc`s at `rows_test.cljc:267–268`. I inspected both changed test files: remaining multi-key associations operate on maps; no further vector or nil case was found.

This establishes the source correction, but does not substitute for a Dart run.

**4. OOM and replacement fixture**

The author’s mechanism is correct, and I independently reproduced the OOM with a bounded JVM heap.

The three-cell loop plus two-cycle is a legitimate pin of **refinement-equivalent, non-automorphic cells**. It kills R17 and verifies the intended search choice. It is not a regression test for resource exhaustion.

Before 6b, explicitly require charging or bounding intermediate label bytes before allocation. The design already requires bounded refinement work; this needs an operational clarification and implementation fix, without changing canonical bytes.

**5. Recomputed pins**

Both Fable probes recomputed exactly:

```text
fixture:
:segment/blake3-be82d50b14804bb7a69103631b9c45759b49b67dbb3cebe75a1259fed6b4a937

worked-frame:
:segment/blake3-c4a7c7e6de3b44400efec5930bc3d85d86b29153b4b2d548acba630bfbc3da7d
```

The new three-cell pin also passed recomputation in `canonical-roots-are-pinned`:

```text
:segment/blake3-8899f95d330a174ee098e10f16ecf2d017d5aae67d4f50d8369222342e6d36e9
```

Final restored JVM baseline: **26 tests, 522 assertions, 0 failures, 0 errors**. Every temporary mutation and probe used a verified byte backup and was restored byte for byte. `src/` and `test/` are clean against HEAD. No files remain edited; nothing was staged or committed.

VERDICT: CHANGES_REQUESTED

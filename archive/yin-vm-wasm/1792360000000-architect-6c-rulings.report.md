Created-Local: 2026-10-13 +07
# Architect rulings for slice 6c (claude-fable-5-1)

Read: codex's review (F1-F6, probes, D1-D5), opus's report, the continuation design §§0.1, 4, 5, 6, 7, 8.3, 11, `layout.cljc` (HEAD 47ddb1d9), the harness lanes, the frame validator's E2, the carrier's `restore-task`, both de Bruijn kernels' `:request-sent` arms and `attach-image`, the semantic-register kernel's `admit-image` and `register-restore`, and `store_write_audit_test.clj`. I ran the audit test once (it fails on exactly the two layout sites the brief names). No doc, source or test was edited.

## Summary (12 lines)

1. Merge 6c after a fix slice of about 2.5 days on opus; no kernel is edited, no acceptance item is dropped silently.
2. R1: alpha-equivalent segments collide only in the de Bruijn kernels, whose `attach-image` dedupes by R/H; the semantic-register kernel keys code by A and has no collision. Refuse the collision before attach, document it as a phase-8-bounded limit, and test that the survivor is unaffected.
3. R2: make the 61/350 native matrix normative in §5.3 and §8.3; the reference lane keeps all 89/534. This changes acceptance counts, so it is an OWNER decision (Q1). The retained-FFI seam is cheap (half a day, both kernels) if the owner wants row 4 anyway (Q2); link and install on native lanes are not worth 2-3 days of code that dies at phase 8.
4. R3: a closure or continuation in the task-wide free environment has no finite canonical form on a de Bruijn kernel (its env would contain itself through the shared free environment); refuse it before attach in both directions on the native lanes only. The carrier and the reference lane keep admitting it.
5. R4: `canonical-under` may erase a stream marker's identity and descriptor; the harness must then prove descriptor equivalence separately, pin three distinct kernel kinds, assert an injective correspondence, and run four negative controls. `logical-positions` is accepted as is.
6. R5: adopt codex's D3 wording; an ambiguous upward tamper may be refused `:yin.k/non-portable :register-layout` (amend §8.3 item 12).
7. R6: D1, D3, D4, D5 accepted as codex wrote them; D2 accepted as a seam specification but moved out of 6c's gate (owner Q2); F2's identity amendment accepted with the provenance-seam sentence replaced (text below).
8. Store audit: allowlist the two sites with the same reason `rows/restore-task` has; do not route them through `store-put`.
9. What we give up: native-lane coverage of link, install and retained FFI, and closures in the free environment on native lanes, all until phase 8 deletes those kernels.
10. What we keep: every invariant (A the only identity, tree always available, sharing survives transport, BLAKE3, no kernel edits in phase 6).
11. Cost: about 2.5 days opus plus a mechanical doc pass and the three-host run; the optional D2 seam adds half a day as its own slice.
12. READY once the work list is green on three hosts, kondo clean, audit green, and the owner has answered Q1 (Q2 can wait).

## R1 (F2): `:ambiguous-image`

**Facts.** Both de Bruijn `attach-image`s refuse a second attach whose identity is already a row (`register.cljc:211`, the stack kernel likewise). So a native kernel holding `(fn [x] x)` and `(fn [y] y)` physically holds one image under one R or H; its `:images` table cannot name which A an activation came from, and nothing the layout reads can recover it. The semantic-register kernel's `admit-image` keys code by `jing/segment-key v`, which is A (`semantic_register.cljc:297-300, 431`), so two alpha-equivalent vectors are two code entries and the survivor has no collision at all.

**Ruling: (a).** Option (b) is an edit to both kernels' offset tables and both restores, forbidden in phase 6 and deleted at phase 8; not worth a slice. Refuse the collision as early as possible and document it as a limit bounded by phase 8:

- **Lower** into a de Bruijn kernel: `lower-defect` gains a first clause: when two carried A derive one image of the receiver's kind (`:register-id` or `:stack-id` over the carried `code` slot), refuse `:yin.k/unsupported :ambiguous-image {:yin.k/image ident :yin.k/segments #{A …}}` before any attach. Status `:unsupported` because it is the receiver's domain, like `:continuation-registers`; the task is a valid carrier.
- **Lift** from a de Bruijn kernel: the existing `:yin.k/non-portable :ambiguous-image` stays, but `canonical-task` must raise it before `lift-context` serves anything (it already runs before `rows/lift-task`, which is the only server; assert zero serves in the test).
- **Never choose an A.** Agreed with codex.
- **Harness defect to fix alongside:** `kernel-space` filters the global `code-index` by image checksum, so an alpha-equivalent A from an unrelated fixture widens the refusal. Scope it: `project` records into a `binding`-scoped atom `*projected*` when bound, and `kernel-space` uses that set when bound, the global index otherwise. The space is then the vectors this world projected, never the file's history.

**Wording, §4.1 (replacing codex's "Additional necessary identity amendment"):**

> A is the code identity even when distinct A derive identical H or R. Native-image hashes remain checksums and must not select a source segment. The de Bruijn kernels' offset tables carry only the image checksum and refuse a second attach of a known checksum, so a de Bruijn kernel cannot hold two segments of one image. Until phase 8 removes those kernels, a de Bruijn receiver refuses a carried task whose code holds two A of one image before any stream is attached (`:yin.k/unsupported :ambiguous-image`), and a de Bruijn source whose space resolves one image to several A refuses the lift (`:yin.k/non-portable :ambiguous-image`); no enumeration order selects an A. The semantic-register kernel keys code by A and holds alpha-equivalent segments side by side; this limit is the native kernels', not the carrier's.

**Tests.** `layout-test/alpha-equivalent-segments`: project `(fn [x] x)` and `(fn [y] y)`; assert distinct `:address`, equal `:register-id` and equal `:stack-id`; `layout/space` of both, then `image-segment` through `lift-state` refuses `:ambiguous-image` on both lanes with zero serves; lowering a reference-lane task carrying both closures as store values into each native lane refuses `:yin.k/unsupported :ambiguous-image` with zero attaches; the same task lowers into the reference lane, relifts and continues (laws 1 and 2 through `handed-off`), which is the survivor check.

Cost: 0.5 day.

## R2 (F3): the 89/534 matrix on the native lanes

**Facts.** Link rows need the harness to answer a `require` with the kernel's own format (H or R) while module responses carry A; install children are whole kernels the layout would have to lift and lower recursively, with a link receiver answering natively; the retained-FFI re-park in both kernels names the composition's own pair (`register.cljc:508-514`, `stack.cljc:564-570`), while the semantic-register kernel honours the carried pair in four lines (`semantic_register.cljc:105-111`).

**Ruling: (a), with the counts made normative; the D2 seam offered separately.** Cost of (b) for link and install is 2-3 days of harness and layout code for kernels deleted at phase 8; I do not recommend it. The D2 seam itself is half a day for both kernels (the four-line `cond->` the semantic-register kernel already has, plus a partial-pair refusal in `lower-defect` rather than in the kernel) but it is a kernel edit, so it needs its own slice (6c-post-D2) and an owner yes. This reduces the acceptance counts of §8.3 items 10 and 11 on the native lanes, so **the owner decides (Q1)**; my recommendation is to accept (a).

**Exact counts under (a).** Law 4: 61 cases (15 wrapped rows × 4 segments + row 20), every case comparing exactly three lanes; the reference lane's law 1/2 matrix stays 89. Law 5: 350 ordered transfers (61 × 6 − 4 × 4 for row 4's four transfers into a de Bruijn receiver); if 6c-post-D2 lands, 366 (61 × 6). Bare tier: law 4 16 cases, law 5 92 (96 with the seam). Tail acceptance (§5.6 item 8): rows 6 and 20 on the native lanes; rows 9 and 12 on the reference lane only.

**Wording, §5.3, append:**

> The de Bruijn lanes execute the shared fragment of the matrix: rows 1-6, 13-16, 18-19, 21-22 and 24 under the four wrappers, and row 20. Rows 7-12 and 17 are not built on them: a de Bruijn kernel links a native image while module responses carry A, and §4 defines no layout for install children. Row 4 is lifted from a de Bruijn source but not lowered into a de Bruijn receiver until the retained-FFI seam (§4, 6c-post-D2) lands: the kernel's re-park names its own call pair. These lanes are a cross-engine check of the canonical frame and are removed with their kernels at phase 8; the reference lane runs the whole matrix.

**Wording, §8.3 items 10 and 11, replace:**

> 10. `harness/cross-engine-canonical-equality`: law 4 for every case of the shared fragment under `canonical-under`: 61 cases, each over exactly three distinct kernel lanes.
> 11. `harness/cross-engine-lowering`: law 5, six ordered pairs over the shared fragment: 350 transfers (366 once 6c-post-D2 lands, when `unlowerable` is emptied).

**Wording, §4 (new paragraph after the stack tail-completion seam), codex's D2 text accepted verbatim except the last sentence, which becomes:**

> This seam is slice 6c-post-D2, a kernel edit of its own, gated by the owner; it is not a prerequisite of 6c. When it lands, `unlowerable` is emptied and the law-5 counts rise to 366 and 96.

## R3 (F1): free-environment values on the native lanes

**Facts.** `kernel-task` copies `:free-env` raw; `canonical-task` and `lift-context` use the kernel's raw `:free-env`. Codex's probe 2 reproduces the break. Beyond that, the lift direction has no finite answer for a closure *in* the free environment: a de Bruijn closure resolves free names dynamically through the one shared `free-env`, so its canonical `env` is `(merge FE* frames)` where `FE*` contains the closure itself; the canonical closure is cyclic. The semantic-register kernel snapshots `E` at creation, so its closures are finite. E2 checks frame environments only (`frame.cljc:137-143`), never a closure value's `env`, so the carrier admits a closure in the free environment whose env disagrees with the free environment on free names, which a de Bruijn kernel cannot honour.

**Ruling: refuse, do not convert.** Conversion on lower would be sound only under an E2-like agreement condition the validator does not check, and conversion on lift is undefined. The honest and cheapest rule, with a shelf life to phase 8:

- `lower-defect` (both de Bruijn kinds): a `:closure` or `:reified` row value in the carried `free-env` slot refuses `:yin.k/unsupported :free-env-value {:yin.k/path [:free-env sym]}` before any attach. Primitives, data and cursor references stay admitted.
- `canonical-task` (both de Bruijn kinds): a closure or continuation value in the kernel's `:free-env` refuses `:yin.k/non-portable :free-env-value {:yin.k/key sym}` before anything is served.
- Everything else in the free environment is copied as now; the values that remain need no conversion (`walk-value` converts only closures and continuations).

**Wording, §6.3, append:**

> A de Bruijn kernel resolves free names through its single `free-env` while a canonical closure snapshots its `env`; a closure or continuation held in the free environment therefore has no finite canonical form on a de Bruijn kernel. Until phase 8, a de Bruijn receiver refuses such a value in the carried `free-env` before attach (`:yin.k/unsupported :free-env-value`, with the path), and a de Bruijn source refuses it at lift before serving. The carrier and the semantic-register kernel admit it.

**Test.** `layout-test/free-env-values`: codex's probe 2 as a fixture (reference-lane identity closure `f` in the initial env, `(then (next-of c) (f "B"))`): reference run answers `"B"`; `handed-off` on the reference lane answers `"B"` (carrier still admits it); `lane-lower` into `:stack` and `:register` refuses `[:yin.k/unsupported :free-env-value]` with zero attaches and the path `[:free-env f]`. Lift side: a native kernel whose `:free-env` holds a `lane-closure` refuses at `canonical-task` with zero serves, on both lanes.

Cost: 0.5 day.

## R4 (F4): law 4 negative controls and what `canonical-under` erases

**Ruling on D1: accept codex's §5.5 replacement verbatim.** `canonical-under` may erase exactly one thing: the stream marker's identity and descriptor, replaced by the logical name (`rows.cljc:971-974`). That erasure is design-sanctioned and stays; what it cannot prove, descriptor equivalence, the harness proves separately. `logical-positions` is accepted as written: it rewrites only `:dao.stream.ringbuffer/identity` inside a cursor-cell body and keeps every other field, which is exactly the "replaces only identity-bearing fields, preserves every nonidentity field including offsets" rule.

**Controls the harness must add (one deftest, quick tier, on row 1 bare):**

1. **Three distinct lanes.** `canonical-equality` asserts `(= [:semantic-register :stack :register] (mapv (comp lane-of :machine) worlds))` for every case, and `(= 3 (count roots))`. Kills M11.
2. **Injective correspondence.** `logical-names` and `ring-names` assert `(= (count m) (count (distinct (vals m))))` and that `logical-positions` renamed every ringbuffer identity (none of the lane identities survives in the emitted rows).
3. **Descriptor equivalence.** For each logical name, the three lanes' `stream/descriptor` with identity fields renamed under the correspondence are equal; a descriptor given `:review/extra :different` on one lane fails this assertion (codex's probe 4).
4. **Position control.** A cursor position's offset changed by one on one lane changes the compared root (`logical-root`). Kills M12.
5. **Value controls.** A saved window value and a lexical `E` value changed on one lane each change the root.

Cost: 0.5 day. No doc change beyond D1.

## R5 (F5, D3): the unique-preimage rule for `virt`

**Ruling: adopt codex's D3 wording verbatim** as an appendix to §4.2 (the live-out* rule, then the sole-named-preimage fallback only for validation, refusal on zero or several, no enumeration order). The fallback is a diagnostic extension, not a layout claim, and lives only until phase 8.

**§8.3 item 12, replace the second clause with:**

> a register entry whose `live` was tampered reconstructs a frame the validator refuses (`:window-extra` for a unique dead slot, `:window-missing` for a dropped live slot), or is refused by the layout itself as `:yin.k/non-portable :register-layout` when the added slot is an ambiguous dead preimage; the test pins all three.

**Test.** Extend `layout-refusals`: find, over C, a body and a reachable non-tail boundary whose `:phys` map sends two or more virtuals to one slot outside `live-out*` and the destination; tamper `:live`/`:regs` with that slot; `lift-state` refuses `:register-layout` (status `:yin.k/non-portable`, the pc and slot in the data). Assert at least one such fixture exists in C (the allocator reuses slots, so it will). Kills M9.

Cost: 0.25 day.

## R6: D1-D5 wording, and the store-write audit

- **D1**: accept codex's text verbatim (§5.5). Controls per R4.
- **D2**: accept the seam text verbatim as the §4 seam specification; last sentence replaced per R2. Not a 6c gate.
- **D3**: accept verbatim (§4.2 appendix). §8.3 item 12 per R5.
- **D4**: accept verbatim (§8.3 item 6 rephrased; §4.2/§7.2 paragraph). Add the three refusal fixtures codex asks for: a primitive in a lexical binding, in a closure's captured frame, and nested in a guest collection inside a window; each refuses `:continuation-registers` with the stated path before attach. 0.25 day.
- **D5**: accept verbatim (§5.2 row 21). No code.
- **F2 identity amendment**: codex's text with the provenance-seam sentences replaced by my R1 wording above.
- **F6**: remove the unused `rvm` require in `layout_test.cljc`.

**Store-write audit.** The two sites, `["canonical-task" "assoc"] 1` and `["kernel-task" "assoc"] 1`, go on the **allowlist**, not through `store-put`. Reason, to write beside the entry: both are state construction over keys that already passed the one program-write gate. `canonical-task` copies a quiescent kernel's store whose every program write went through `engine/put-active` and so `store-put`; `kernel-task` copies the scratch machine's store that `rows/restore-task` built key by key through `store-put` (`rows.cljc:1803`). The layout converts values only (`conv`) and never introduces a key, so a second key check would prove nothing, and `store-put` is per key, so routing through it would mean a reduce that re-checks what the carrier's step already checked. This is precisely the precedent of `rows.cljc` `["restore-task" "assoc"]` and `handoff.cljc` `["isolated-receiver" "assoc"]`.

## Work list for opus (priority order, with acceptance)

1. **F6 and the audit** (0.2 d). Drop the unused require; add the two allowlist entries with the reason above. Accept: `clj -M:kondo` 0 warnings on the three files; `clojure -M:test -n yin.vm.store-write-audit-test` green.
2. **R3 free-env refusals** (0.5 d). `lower-defect` and `canonical-task` as ruled. Accept: `layout-test/free-env-values` as specified, zero attaches and zero serves asserted.
3. **R1 collision preflight and harness scoping** (0.5 d). `lower-defect` first clause; `*projected*` scoping of `kernel-space`. Accept: `layout-test/alpha-equivalent-segments` as specified, including the reference-lane survivor round trip.
4. **R4 law-4 controls** (0.5 d). The five controls in one deftest; the three-lane and injectivity assertions inside `canonical-equality` and the name maps. Accept: M11 and M12 re-applied are killed; codex's probe 4 descriptor change fails the descriptor assertion.
5. **R5 ambiguous preimage** (0.25 d). Accept: M9 re-applied is killed; `layout-refusals` pins `:window-extra`, `:window-missing` and `:register-layout`.
6. **D4 fixtures** (0.25 d). Accept: three new refusals with paths, zero attaches.
7. **Counts** (0.1 d). Docstrings of `link-rows`, `unlowerable` and the two matrices cite §5.3's new paragraph; counts unchanged (61/350, 16/92) unless Q2 lands first.
8. **Doc pass** (0.25 d, mechanical, may be a separate claude -p): apply the verbatim texts in R1, R2, R3, R4 (D1), R5 (D3 and item 12), R6 (D2, D4, D5, F2) by section; §11 gains Q1 and Q2 and their answers.
9. **Three hosts** (acceptance 13). `bb test:sub yin.vm` on JVM, Node, Dart in the foreground; `satisfies?`, `binding` and `memoize` are pinned-source-supported on Dart per codex but unrun. Accept: three green lanes reported with counts.

Total about 2.5 days plus the three-host run. Re-review by codex on the diff of items 2-6 only.

## Open questions for the OWNER

- **Q1 (scope, changes acceptance counts).** Accept the native-lane matrix as the shared fragment: law 4 61 of 89, law 5 350 of 534, tail acceptance rows 6 and 20 only, with the reason that those kernels die at phase 8 and the reference lane keeps the full matrix. Recommendation: yes.
- **Q2 (kernel edit, own slice).** Land 6c-post-D2, the four-line retained-FFI response-pair seam in both de Bruijn kernels (half a day, dies at phase 8), raising law 5 to 366/96. Recommendation: only if the owner wants row 4 cross-engine before phase 8; otherwise skip.
- **Q3 (information, no decision needed unless the owner disagrees).** Closures and continuations in the task-wide free environment are refused on the native lanes (R3), admitted everywhere else. If any real composition seeds a de Bruijn kernel's free environment with a closure, say so and R3 becomes a design question instead of a refusal.

READY

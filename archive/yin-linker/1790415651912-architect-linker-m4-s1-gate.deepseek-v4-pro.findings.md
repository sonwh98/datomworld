Completed-GMT: 2026-09-26 09:58:00 GMT
Completed-Local: 2026-09-26 16:58:00 +0700

# Gate review — M4 slice S1, kernel `attach-image` (four backends)

I read the full diff, `docs/design/yin.vm.linker.md` §7.3 (esp. L1160–1240, 1338, 1523), §9 M4, §10 file box, §11 criterion 17, the two untrusted reports, and every changed function in source. I did not rerun the suites. Verdict is at the bottom.

## 1. Spec conformance per kernel

- **Stack** (`stack/attach-image`, stack.cljc:157): admits alone (`admit!`, shared with `load-image`), relocates by held length, appends, adds one `[identity offset length]` row, recomputes `:hash` as H of the concatenation, touches no register, dedups on identity. `image-pc`/`absolute-pc` round-trip through `row-at`. Matches §7.3 act 3 and r6/r7.
- **Register** (register.cljc:187): same, with `:bodies` shifted alongside instructions. Matches spec, including "bodies shifted likewise".
- **Semantic** (semantic.cljc:772): mints a fresh local id below the floor (`(dec (vm/loaded-code-floor …))`), writes the alias column, leaves `:control/:program/:env/:stack/:k/:store` unchanged, dedups on address. Matches §7.3 act 3 + UCF §7.3.4 (local-id immutability).
- **Walker** (ast_walker.cljc:866 `attach-image`, `hold-rows`, `row-decoder`, `closure-row`): adds rows by id, extends the `[node params]` index, annotates `:lambda` nodes with their row id in metadata. `closure-row` implements the two-stage walker rule exactly (recorded id verified against row params+body; no-id resolves through the index; mismatch → `:yin.k/non-portable :unrooted-body`). Matches the "walker rule, exactly" passage (L1312–1346).
- **REPL** (`append-stack-image`/`append-register-image`, repl.cljc:91/109): now call `attach-image` then set `:pc` themselves, plus reset the fresh-run registers. Matches L1192–1196.

**Scope**: S1 is exactly S1's. `engine.cljc`, `module.cljc`, and the UCF doc are untouched (correct — those are S3/S2/S5). Acts 1/4 lift/lower are correctly deferred (decision 5).

## 2. Correctness

Relocation arithmetic, offset-table invariants (base row `[H/R 0 n]` first; rows tile the segment; interior boundaries are unambiguous because each row's `off` equals the prior `off+len` and the lower bound is inclusive), hash recomputation, restore-by-membership, grow-only returns, semantic alias minting, and the walker row index are all **correct**. I specifically traced:

- **r6 restore** — `stack-restore`/`register-restore` check `format` + `:image` membership, never `:hash`, never write `:segment`. For a never-attached VM the single base row makes this exactly the old same-image rule (entry `:image` = base identity). Correct.
- **r7 grow-only returns** — the register `:call` frame drops `:segment`/`:hash`; `return-transition` (register.cljc:282) and `register-restore` read `body-of-pc` from `(:segment base)`/`(:segment vm)`, not the frame/entry. Since attach only appends, held bodies keep their positions and absolute `:return-pc` survives. Correct. The in-flight test (`register-call-in-flight-survives-a-nested-attach-test`) proves it.
- **`row-at`/`image-row` one-past-end** (stack.cljc `row-at`, debruijn_register_effects.cljc:68) — the fallback fires only for `pc == total-length`; a boundary instruction is never the terminal `:halt`, so it never fires for real payloads. Defensive, correct.
- **Decoder equivalence** — `vm-load-rows`/`attach-image` call `semantic-bytecode->ast` first (validation, discarded) then rebuild via `row-decoder`. I compared `row-decoder` (ast_walker.cljc:789) against `semantic-bytecode->ast` (vm.cljc:1446): the decoder omits the `:id-resolves/:shape/:content-address/:tag/:arity/:saturation/:slot-kind` checks, but those already ran, so the tree is structurally identical modulo `:lambda` metadata (which `=` ignores). Sound.

## 3. Behavior neutrality

No existing test was edited (repl_test gained one `deftest`; the rest is the new file). The walker's `:lambda` closure key and metadata are the only observable additions, and neither affects `=` nor any existing assertion (confirmed by 0 failures across 2,136/2,049/2,011). The `debruijn_register_effects.cljc` edit (return-frame-defect, :146) is **necessary and safe**: it drops the per-frame `:segment`/`:hash` demand (which contradicted r7) and validates frame pcs against the *payload's* segment, while `continuation-defect` (:204) retains the payload-level `:hash`-vs-own-`:segment` self-consistency check. Justified.

## 4. The five decisions

All defensible; none a defect:
1. REPL resets frames/stack/continuation/halted/blocked/value/registers itself — correct and complete (verified it matches what `install-image` used to reset; `:segment/:hash/:images` correctly left to `attach-image`).
2. REPL takes `:pc` from the table row, not held length — correct; handles the already-held no-op case.
3. Register `reset` keeps `:images` (register.cljc:686) — defensible per r7 ("only loaders and `attach-image` write" the table; reset is a fresh run, not a load). Walker `vm-reset` (ast_walker.cljc:910) preserves `:rows`/`:row-index` via `assoc`, consistent.
4. Entries still carry `:segment`/`:hash` as dead self-consistency data — correct; `continuation-defect` still validates them against each other, neither is a restore key or written back.
5. UCF lift/lower not implemented; tests compose by hand — correct scoping to S3.

**Which belong in the spec**: decisions 3 and 4 are unstated in §7.3 and were exactly the open "reset vs offset table" gap (scoping report trap 10). Recommend §7.3 r7 gain two sentences: *reset preserves the offset table*, and *entries keep `:segment`/`:hash` as non-restore, self-consistency-only fields*.

## 5. Cross-host

`vary-meta` on map nodes, `letfn`+`atom` in `row-decoder`, reader-conditional `ex-data-of` (`#?(:clj Exception :cljs js/Error :cljd Object)`), and `::lambda-row` keyword identity are all tri-host-safe — empirically confirmed by the orchestrator's Dart lane (2,011 passed, +18). No private-var access in tests; all new kernel fns are public. Metadata on a `:lambda` *map* does not enter `dao.jing` content addressing (only projected vectors do), so no re-projection hazard.

## 6. Tests

17 deftests in `attach_image_test.cljc` + 1 in repl_test. Criterion 17 is fully covered: nonzero-offset exported closure (both positional kernels), structurally-equal walker body, parked-at-nonzero-pc restore with non-empty stack/frames/continuation after attach, `:segment` never written, `:hash` never a key, register call-in-flight over a nested attach, continuation lifted-after-attach rebased by the receiving table, and two parents of different lengths. Every test asserts a concrete value/structure and can fail.

## 7. Rule R and security

No bypass. Each `attach-image` uses the *identical* admission as its loader: `admit!`→`dcode/image-defect` (reserved-defect, debruijn_code.cljc:847), `rcode/register-image-defect` (reserved-rule, debruijn_register_code.cljc:655), `code/well-formed-vector?` (reserved-operand, code.cljc:157), and `semantic-bytecode->ast`→`validate-rows`→`rows-reserved-defect` (vm.cljc:1270) — all four include Rule R. Contract stamping (`check-contract!`) is enforced in all four. No contract stamp is ever assigned to external input.

## 8. Findings

P3 | stack.cljc:157 / register.cljc:187 | `attach-image` on an *empty* image against a *non-empty* VM skips admission (by design) and, because `register-hash empty-image` / `image-hash []` is not a row of a non-empty table, appends a spurious zero-length row `[ident offset 0]` (hash and segment unchanged). | Not reachable from the REPL (empty input filtered) or S3 (origin images are non-empty); consider an early `empty?`→`vm` guard for robustness.

P3 | stack.cljc `absolute-pc` / register.cljc:232 | `absolute-pc` returns `(+ off rel-pc)` with no bounds check on `rel-pc`; an out-of-range `[identity rel-pc]` silently yields a mis-placed or past-end pc. | Latent (S3 lowers only valid rel-pcs produced by `image-pc`); add a range assertion or return nil for a corrupt slice.

P3 | ast_walker.cljc `row-node` / `closure-row` (:880/:890) | `row-node` rebuilds a fresh `row-decoder` (fresh memo) per call, re-decoding the whole reachable subtree; `closure-row` calls it to verify `(= body …)`. O(subtree) per call, so many exports in a large module is near-quadratic. | Correctness unaffected; could memoize the decoder on the VM when S3 lands.

P3 (doc) | docs/design/yin.vm.linker.md §10 (:2050–2056) | File box omits `debruijn_register_effects.cljc` from the edited list, though r7's "register frame drops `:segment`/`:hash`" necessarily edits `return-frame-defect` there. | Doc-only; add the file to the box.

(Minor, not a finding) `image-row`/`row-at` are duplicated (debruijn_register_effects.cljc:68 vs stack.cljc private `row-at`); register's empty VM keeps `:hash nil` while its base row identity is `register-hash empty-image` — a pre-existing asymmetry that is harmless now that `:hash` is never a restore key.

No P0, P1, or P2 findings. The `debruijn_register_effects.cljc` edit, the decoder's validate-then-rebuild pattern, and the empty-image/`absolute-pc` bounds gaps are the only items that rise to a note, and all are P3-or-lower.

---

Verdict: READY
Sign-off: GRANTED

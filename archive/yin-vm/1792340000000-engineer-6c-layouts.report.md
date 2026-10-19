# Slice 6c proper: layouts and cross-engine lanes (engineer report, opus)

Branch `srvm-phase6c`, worktree `/Users/sto/workspace/datomworld-srvm-p6c`. **Not committed.**

## Step 0: prerequisites

All present on this branch, so no stop was needed:
- 5a: `8df1d117`, merged at `71b4f4d7`.
- 5b: `derive/register-image`, `b2eadc83`, merged at `7517f30e`.
- 5c: the `scan` namespace, merged at `e5df547a`.
- 6b: `rows/lift-task` and `rows/lower-task`, merged at `1db08f7d`.
- 6c-pre: the stack `:resume-mode :return-result` seam and `stack_tail_completion_test.cljc`, merged at `296f75fd`.

## Files

| File | Change | Lines |
|---|---|---|
| `src/cljc/yin/vm/semantic_register/layout.cljc` | new | 898 |
| `test/yin/vm/semantic_register/layout_test.cljc` | new | 425 |
| `test/yin/vm/semantic_register/safepoint_harness_test.cljc` | modified | +392 / -13 |

No kernel, no design doc and no `rows.cljc` changed. `git diff --quiet` is clean on `src/cljc/yin/vm/debruijn`, `semantic_register.cljc`, `src/cljc/yin/vm/ucf` and `docs`.

### `layout.cljc` (namespace `yin.vm.semantic-register.layout`)

**Static half.** `segment v` derives R(A) and H(A) and computes:
- `:pairs`, the `[pc virtual physical]` operand pairing;
- `:phys`, per body;
- the correspondence `:a->h` and `:h->a`;
- `:layouts`, which is `stack-layout`: the ancestors' pushed earlier children of `:call`, `:ffi-call` and `:stream-put`.

`space` indexes segments by A and by R and H. R and H are checksums only: when two A derive one image, the lookup refuses `:ambiguous-image`.

Other public functions:
- `phys` and `virt`. `virt` is the restricted inverse over `live-out`; see design defect D3.
- `live-out`, which is `live-out*`.
- `register-live`, computed as `(sort (map phys (disj live-out* rd)))`.
- `chain`.
- `env-of`, the forward rule of §4.4, and `frames-of`, its inverse.
- `slices`, implementing `St[b(i-1), b(i))` plus the current activation.

**Dynamic half.**
- `lift-state cx entry` produces the canonical sr-shaped payload: `{:segment A :pc :env :window :k :deliver}` or `{:segment A :site :k :deliver}`, with engine fields carried.
  - Register lane: `rd` comes from A, at the top frame and in every K frame.
  - Stack lane: K slices, and the canonical pc is `h->a(pcₕ−1)+1`.
  - Values are converted recursively: a positional closure becomes canonical by the forward rule, and a kernel continuation becomes canonical by `lift-state`.
- `lower-state lx p [fill]` is the inverse.
  - Register lane: `:live = sort(phys(keys W))`.
  - Stack lane: bases are cumulative through each slice.
  - A tail lowers with `:frames []` and `:store-of nil`, or with `fill`.
- A pc whose predecessor is not a parking instruction, or a `:push` in H, is refused `:yin.k/not-at-safepoint`.

**Task level.**
- `canonical-task k space template`: a quiescent de Bruijn kernel presented as a semantic-register machine, so that `rows/lift-task` lifts it unchanged. A running kernel is refused not-at-safepoint; `:installs` is refused unsupported.
- `kernel-task m k`: a machine restored by `rows/lower-task` becomes a fresh kernel. Every A image is derived and attached, `:free-env` is set, and waits, parked records and values are lowered.
- `lift-task` and `lower-task` compose these with the row carrier.
- `lower-defect` runs **before any attach** and refuses two things:
  - install children: `:yin.k/unsupported :installs`;
  - for the register kernel, a primitive function anywhere in the payload data of a wait or parked state: `:yin.k/unsupported :continuation-registers`, with a path. This covers windows and lexical bindings, including those reached through collections, closures' lexical frames and captures.

### Harness additions

- `*lane*` (`:semantic-register`, `:stack` or `:register`). `machine` reads it; `load-program` dispatches on the machine's link format. A de Bruijn load attaches the derived image beside what the kernel holds, so parked records keep naming their rows.
- `code-index`, `kernel-space`, and `lane-closure` (row 18's module closure on any lane).
- New tests, all in the harness file:
  - `cross-engine-canonical-equality` (`^:slow` plus `slow/guard`; full matrix) and `cross-engine-canonical-equality-bare` (quick tier);
  - `cross-engine-lowering` (`^:slow`; full matrix) and `cross-engine-lowering-bare` (quick tier);
  - `layout-refusals`.
- The 6b tests are unchanged in behaviour, since `*lane*` defaults to the reference lane.

## Tests and counts (JVM only, all run in the foreground)

- **kondo: BLOCKED.** Both `clojure -M:kondo --lint …` and `clj-kondo --lint …` were refused by the permission gate, so kondo was not run.
- **Quick tier on the new and touched namespaces** (`-e :slow -n layout-test -n safepoint-harness-test`): 31 tests, 3162 assertions, 0 failures.
- **Slow tier on the same namespaces** (`-i :slow`): 3 tests, 2411 assertions, 0 failures. These are 6b's `every-row-every-segment…` and the two new full matrices.
- **Every `yin.vm.ucf.*`, `yin.vm.semantic-register.*` and `yin.vm.debruijn.*` namespace, quick tier** (`-e :slow -r 'yin\.vm\.(ucf|semantic-register|debruijn)\..*'`): 947 tests, 22248 assertions, 0 failures, about 2m20s.
  - This ran before I added the `:tail-operand` fixture to layout-test's C (see M4). layout-test was rerun clean afterwards, inside the 31-test run above.
- The slow tier of that wider set was not run.

**Counts asserted:**

| Test | Counts |
|---|---|
| law 4, bare | 16 cases |
| law 4, full | 61 cases (15 lane rows × 4 segments + row 20); every case compares 3 lanes |
| law 5 | 6 ordered pairs |
| law 5, bare | 92 pair runs: 16 × 6, less row 4's 4 |
| law 5, full | 350 pair runs: 61 × 6, less 4 × 4 for row 4 |
| item 3 | 6 K frames at `:depth-3` |
| item 6 | bases `[0 0 0 2 4]`, slices `[] [] [] 2 2 2`, stack length 6, 5 lifted K frames, lowered bases `[0 0 0 2 4]`, lowered stack length 6, and the run continues to `[1 [2 [3 "B"]]]`; the slice partition is checked at more than 20 safepoints |
| item 7 | 128 E comparisons: (61 + 3) × 2 lanes |
| item 9 | 4: rows 6 and 20 × 2 lanes |
| items 1, 2, 4, 5 | every program of C is checked |

**Acceptance status:**
- Items 1 to 9 are in `layout_test` under exactly the names the brief gives.
- Items 10 to 12 are in the harness.
- Item 13 (bb test:sub on three hosts) was not run: the brief said JVM only.

## Mutation results

Each mutant was applied to a backed-up copy and run against the targeted tests. After each one I restored the file, checked it with `diff` against the backup, and confirmed `git diff --quiet` on the kernels and docs. The backups were then deleted.

| # | Mutant | Caught by |
|---|---|---|
| M1 | register `rd` taken from the physical `:dest` (top frame and K frames) | `register-rd-from-a` (both clauses); law 4 and law 5 (the lift refuses `:inconsistent-state`); `layout-refusals` |
| M2 | `live` including a dead register (`rd` not subtracted) | `register-live-is-live-out`: 56 failures |
| M3 | stack bases by the before-slice rule (`0 0 0 0 2`) | `stack-slices-partition` (the fixture pins `[0 0 0 2 4]`); the slow `cross-engine-lowering` errors. **The quick `cross-engine-lowering-bare` does not catch it**, because bare rows have only empty K slices. |
| M4 | an extra window register at a tail boundary (`live-out` treating a tail call as falling through) | **Not caught at first**: in every corpus program the instruction after a tail call reads only the call's own `rd`, so the mutant was equivalent. I added `:tail-operand`, `(f (g x)ᵗᵃⁱˡ y)` from phase-5's last golden, to layout-test's C; `register-live-is-live-out` then catches it. |
| M5 | E reconstruction dropping a shadowing binding (merge order reversed) | `env-reconstruction` (40 failures); law 4 |
| M6 | closure round trip losing a binder | `closures-round-trip` (16 failures); law 5 errors |
| M7 | a cross-engine pair skipped | `cross-engine-lowering-bare` pair counts: 6, `(if (= 4 row) 2 6)` and 92 |
| M8 | a layout refusal after attach (`lower-defect` moved after `rows/lower-task`) | `layout-refusals`: "refused before any attachment" |

## Deviations, with reasons

1. **Design API names.** The design lists `register-layout`, `lift-act`, `lift-tail`, `lower-act` and `lower-tail`. The public API is instead one dispatching function each way, `lift-state` and `lower-state`. The act and tail algorithms are separate inside. The register layout is the segment's `:phys`, `:pairs`, `phys` and `virt`.
2. **Lowering into a de Bruijn kernel** is `rows/lower-task` into a scratch semantic-register machine of the same composition (the carrier's steps 1 to 12), then `kernel-task`. It is not a separate restore. The layout's own refusals (`lower-defect`) run before step 11. The layout requires `yin.vm.ucf.rows` for this composition.
3. **Link and install rows (7 to 12, 17) are not on the de Bruijn lanes.** De Bruijn kernels link native images (H and R) while module responses carry A, and §4 defines no layout for install children. So law 4 and law 5 run 15 of the 22 wrapped rows, plus row 20. For the same reason, item 9 runs rows 6 and 20 only; rows 9 and 12 are link rows.
4. **Row 4 is not lowered into a de Bruijn receiver**, which removes 4 of its 6 pairs. I verified this is necessary: without the exclusion, all 4 pairs stay `:yin/blocked` (see D2).
5. **The static corpus C** is the B0 corpus, the phase-3 corpus, and `:tail-operand`. derive-test's C cannot be required: derive-test pulls in the yang.python ANTLR lane.
6. **Items 7 and 8 use variants of the named B0 and B2 programs.** As written, `:duplicate-param`, `:nested-closure`, `:nested-lambdas`, "closure value" and "higher order" reach no safepoint, so each variant puts a read in the innermost body; "closure value" and `:duplicate-param` are also checked as halted values.
   - For the duplicate binder, positional → canonical → positional gives `[2 2]` for `[1 2]`. The test pins this: per §4.4, the shadowed position is not a claim.
   - canonical → positional → canonical is the identity.
7. **`virt` fallback.** For a slot outside `live-out*`'s physical image, `virt` uses the body's unique preimage, so that item 12's "tampered upward" case reaches `:window-extra`. If that preimage is ambiguous it refuses `:register-layout`. See D3.
8. **New status and kind:** `:yin.k/unsupported :continuation-registers`, for a register receiver of a carrier with a `:prim` in payload data.

## Design defects found (for the Architect; no design text edited)

- **D1, §5.5 `canonical-under`.** The procedure assumes cursor positions are identity-free. A `dao.stream.ringbuffer` cursor embeds its stream's identity (`{:dao.stream.ringbuffer/identity … :position n}`). Marker substitution therefore leaves lane-specific identities in every cursor-cell body, and law 4 fails across lanes on every row with a cursor.
  - The harness renames position identities under the same fixture correspondence (`logical-positions`) before `canonical-under`.
  - Proposal: have `canonical-under` rename positions too, or state that positions carry identities.
- **D2, retained FFI on the de Bruijn kernels.** Their `:request-sent` re-park builds `ffi/response-wait-entry`, which names the composition's own call-out pair. The semantic-register kernel's `register-restore` honours the carried `:response-cursor` and `:response-stream`. So a carried row 4 never wakes on a de Bruijn receiver.
  - Needs a kernel seam in both de Bruijn kernels, like 6c-pre.
  - Phase 6 does not edit kernels, so it is excluded and reported here.
- **D3, §4.2.** `virt` is a restricted inverse, defined only on `live-out*`'s physical image. That leaves item 12's "live tampered upward" with no reconstruction. The design should state which rule applies; the implemented rule is the unique preimage, else refuse.
- **D4, register kernel and primitives.** A primitive function is a legal carrier value (`:prim`), and the reference lane runs programs that hold one across a read. The register kernel's restore refuses it (`vm/machine-data?`, `:continuation-registers`), and the register lane cannot even run such a program across a park.
  - §8.3 item 6 hints at this; §4 and §7.2 do not name it.
  - The implementation refuses before attach.
- **D5, carried from 6b, as briefed.** B2: under `:depth-3`, row 21 captures the wrapper's `x`. The value 1 stays pinned and holds on all three lanes.

## Uncertainties

- **Node and Dart were not run**, as the brief said. The new code relies on `satisfies?`, `^:dynamic`/`binding` (harness `*lane*`) and `memoize` on Dart. I believe all three are supported, but this is unverified.
  - Portability rules were followed: no `for` over long seqs (`ordered-pairs` uses `mapcat`/`keep`), `(assoc {} …)` for multi-key maps, no private var access from tests, no float negation, and the catch forms reuse the harness's `error`.
- **`logical-names` uses resource keys as the logical stream names.** This holds because all three kernels mint resource ids through the same engine counter for the same program. If a kernel diverged, law 4 would fail rather than pass silently.
- **`canonical-task` copies `:capability-secret` from the kernel.** `kernel-task` keeps the receiving kernel's secret, so refs verify only when the scratch receiver and the kernel share a composition. The harness guarantees this; a production caller must as well.
- **Space lookups go through `code-index`.** A program that differs from another only in binder names derives the same H, and would be refused as ambiguous rather than mis-resolved. This was not hit.

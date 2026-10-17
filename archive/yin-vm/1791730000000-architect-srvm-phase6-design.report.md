Completed-GMT: 2026-10-10 18:41:36 GMT
Completed-Local: 2026-10-11 01:41:36 Asia/Bangkok

(The host clock read about twenty minutes earlier than the prompt's
Created stamp when this report was written; the times above are the
clock's.)

# Report: phase-6 design, the continuation format and the row carrier

Deliverable: `docs/design/yin.vm.semantic-register-vm.continuation.md`
(worktree `/Users/sto/workspace/datomworld-srvm-p6-design`, branch
`srvm-phase6-design`, from master `8f0b9e38`; 1246 lines). No other
design document, source or test was edited; nothing staged or
committed.

## Decisions

1. **One canonical frame, one profile, body version 3.** The carrier
   carries the register machine's `act`/`tail` states, the saved window,
   `K` of `:return` frames, `E`, the delivery record and the pending. Its
   profile map is `{:yin.code/contract "v4" :yin.k/version 1}` with no
   engine key: the frame is engine-independent by the "one truth"
   ruling, and the stack and register kernels lower the same rows
   through their layouts. The row reader speaks version 3 only; versions
   0-2 stay with the old handoff until phase 8.
2. **Frame rows** (§2.3) replace the draft's `:regs`/`:stack`/`:kframe`
   with `stack-base`: `:act [segment-A pc env-cref window-cref k-cref rd]`,
   `:tail [segment-A site k-cref]` (no env, window or pc), `:window
   [r V …]` sorted ascending with a nil-valued register present as
   `[:lit nil]`, `:k`, `:kframe [segment-A pc env-cref window-cref rd]`,
   `:wait [state-cref pending-cref]`, `:reified [act-cref]`,
   `:parked-table [P state-cref …]`. The kind is the delivery
   discriminant; `rd` is carried and checked. Pending rows, the value
   union, the census and the cell numbering are the draft's, unchanged.
3. **The frame validator** (§3): fourteen ordered rules over the decoded
   keyed frame and the carried code, engine-independent, each with a
   refusal kind (`:frame-shape :segment :pc-bounds :window-representation
   :window-scope :site :destination :window-extra/-missing/-self :deliver
   :return-site/-destination/-window-* :env/:reserved-name
   :reason-mismatch :captured-site :parked-site`), with a context
   argument (`:wait`, `:captured`, `:parked`) selecting the last three.
   Membership is recomputed from A through `analysis/saved`.
4. **Layouts** (§4): the register kernel's physical register is read off
   the pair of images `(v, R)` pc by pc (they share pcs), with
   `phys→virt` per pc; the stack kernel's layout is the one-to-one
   correspondence of H's non-push instructions with A plus the
   operand-stack vector of pushed-and-unconsumed children, whose set is
   the saved window; both kernels' positional frames become `E` through
   A's owner chain and `engine/bind-params`. The walker is excluded as a
   frame reconstructor, with the reason.
5. **The harness** (§5): fifteen rows × four segments on three lanes,
   both delivery modes (tail stream call, tail require, tail-effect halt
   added), nested and reified captures, module contexts, install with a
   live child, and the refusal rows including every validator tamper.
   New law: cross-engine canonical equality by root id, plus the six
   ordered cross-lane lowerings.
6. **Code in the carrier** (§6): `{A canonical-vector}` only, never a
   native image; `:yin.k/requires :yin.k/trees` from the phase-5
   association; the free environment travels inside every `E` (Merkle
   shares it), so the root has no `free-env` or `layout` slot.
7. **Slices** (§8): 6a validator + row codec (needs 4b stage 1), 6b the
   reference-lane harness (needs 4b stage 2), 6c layouts and
   cross-engine lanes (needs 5a/5b). No file of 4b or 5 is touched;
   `handoff.cljc` and the old harness are left alone. Phase 7's entry
   conditions are listed.

## Verified in code

- Evaluator shapes: `act-payload`, `entry-builders`, `tail-builders`
  (`srvm-phase4b:semantic_register.cljc:61-84`), reified payload and
  park (`:229-234`), `register-restore` (`:97-113`), gc protocol
  (`:386-392`); 4a `put-registers` (`semantic_register.cljc:47-52`),
  closure entry env (`:141-143`), `create-vm` env (`:261`).
- Analysis: `successors`, `saved` (`analysis.cljc:15-28, 92-97`);
  `saved(4,2)={1}` pinned at `analysis_test.cljc:131-139`.
- UCF code: `code-address` (`ucf.cljc:247-251`), v2 profile registry
  (`:51-74`), `profile-engine` numeric-kind check (`:77-91`),
  `activation-state` (`:434-442`).
- Handoff: `wire-of-kind` (`handoff.cljc:491-504`), `unliftable-hold`
  (`:588`), `lift-pending!` (`:674-749`), `lift-frame!` (`:755-783`),
  `export-task` (`:1392-1511`), `validate-registers`/`check-frame-pc`/
  `validate-frames` (`:1749-1816`), the v2 register and stack adapters
  (`:897-980`), `validate-registers-v2` (`:2230-2367`),
  `decode-registers-v2` (`:2952-3023`), `lower-frame!` (`:3182-3246`),
  the assoc-on-nil note (`:1455-1457`).
- Harness: rows, corpus segments, laws and refusal tests in
  `safepoint_harness_test.cljc` (`:92-106, :117-304, :368-401, :591-603,
  :675-692`); `v2_support` helpers (`:109-141, :453, :531-543`);
  `lift_support/parked-module-store-cursors` (`:310`).
- Module kernel protocol (`module.cljc:395-461`); register kernel
  `absolute-pc` (`debruijn/register.cljc:326-337`).
- Documents: frozen §2.1-2.4, §4.1-4.3, §6, §10; UCF §7.3.4, §7.4,
  §7.5.1-7.5.4, §7.11; v2 amendment whole; row-carrier draft whole;
  code-as-tuples §7.2; ucf-revisions §8; phase-5 §9.2.

## Unverified

- 4b stage 2 (FFI, link, install, `IModuleKernel` on the register
  evaluator) is not landed; 6b's rows are written against the evaluator
  design's §1.4-1.5 shapes and the stage-1 branch and must be re-read
  when stage 2 lands.
- The four checked-not-assumed properties of §9 (stack operand slice =
  saved set; non-push H ↔ A correspondence; `phys→virt` single-valued per
  pc; layout `E` = evaluator `E`) have arguments and corpus-wide tests,
  no proof.
- The two stack-layout worked cases (`[1]` at the inner call of
  `(f (g x) y)` and at `(g)` in `(f (if c (g) 2) y)`) were derived by hand
  twice and checked against the pinned saved sets; nothing was run.
- No tests were run; verification was by reading (`grep`, `sed`,
  `git show`, `date`).

## Owner questions

None. Two choices are flagged for the Architect reviewer: dropping
`:yin.k/engine` from the profile map, and carrying the free environment
inside every `E` rather than as a root slot.

## Documents needing amendment, and where the text is

| Document | Sections | Amendment text |
|---|---|---|
| `yin.vm.semantic-register-vm.md` (frozen) | §4.2 row names and validator; §4.3 layouts; §10 phase 6 | continuation design §10.1 F1-F4 |
| `yin.vm.universal-continuation-format.md` | §7.3.4, §7.4.1, §7.4.2, §7.4.3, §7.6.1 (+ new §7.6.1a), §7.11 and §7.11.1 | §10.2 U1-U6 |
| `yin.vm.ucf-transport-tuples.md` (draft) | §2, §5.1 `:reified`, §5.2, §5.4 `:parked-table`, §5.5, §8 step 7, the `v3`-stamp statements, §9 legacy, §10.3, §10.4, §10.7 | §10.3 R1-R9 |
| `yin.vm.code-as-tuples.md` | §7.2 | §10.4 C1 |

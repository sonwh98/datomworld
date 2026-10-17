Completed-GMT: 2026-10-10 18:59:29 GMT
Completed-Local: 2026-10-11 01:59:29 Asia/Bangkok

(The host clock read about twenty minutes earlier than the prompt's
Created stamp when this report was written; the times above are the
clock's.)

# Report: phase-6 design revised after codex review

Edited: `docs/design/yin.vm.semantic-register-vm.continuation.md` only,
rewritten whole (now 1475 lines). No other document, source or test
changed; nothing staged or committed. Every codex claim below was
checked against the code before writing; none is disputed.

## Per item

1. **Free environment** (§1.1, §2.6, §3.2 rule E2, §4.4, §6.3, F1, R5).
   Reversed: the task's free environment is a root `free-env` slot (a
   `:bindings` row); every `E` is checked to agree with it off the
   lexical names (new rule E2, `:free-env-mismatch`); the inverse
   `E → F` never derives the free part. The reviewer's program is row 21
   of the matrix, expected `99` on every lane, pinned with a tamper
   (`x 98` → `:free-env-mismatch`). **Verified**: `:load-free` resolves
   through the task-wide `free-env` (`stack.cljc:683`), so the earlier
   subtraction would have corrupted it; the register evaluator's closure
   entry (`semantic_register.cljc:141-143`) and `create-vm`'s `:env`
   (`:261`) ground the forward merge.
2. **Stack K slices** (§4.3). Corrected to `[previous-base, baseᵢ)` with
   previous-base 0 and current `[last-base, count)`. **Verified**: the
   call arm pushes `{:return-pc (inc pc) :frames frames :stack-base
   (count stack')}` with `stack'` the caller's residual after popping the
   callee's operands (`stack.cljc:716-733`); `:return` truncates to the
   base and pushes (`:766-772`). Both worked examples re-derived (`[f]`
   in each); a multi-frame example added (6c item 6) with bases 2 and 4,
   each slice `[operator literal]` of its `cat` call. My first pass of
   that example forgot the pushed operator; it was corrected in the text
   and the correction is recorded in §9.
3. **Stack tail completion** (§4.3, §8.3 6c-pre). **Verified**: a host
   effect parks with the continuation after the call site and
   `stack-restore` resumes by conjing the value at the entry's `pc`
   (`stack.cljc:748-752`, `:555-568`), so a tail call resumes its own
   body and runs the layout syntax after it, not through `K`. Specified a
   kernel seam (`:resume-mode :return-result` on tail-call parks;
   `stack-restore` delivers by the return transition), gated as slice
   6c-pre with its own test file, the one old-kernel file this phase
   changes (`debruijn/stack.cljc`). The `tail` lift discards the current
   residual slice; the `tail` lower builds the stack from `K` slices only.
4. **Register K `rd`** (§4.2). Canonical `rd` and every K frame's `rdₖ`
   are read from A's call at `pc−1`, never the physical `dest`; each
   frame's image row and relative pc are resolved independently through
   the kernel's offset table, including cross-image frames. **Verified**:
   the frame's `:dest rd` is the physical register (`register.cljc:700`);
   `write-back` writes `dest` into the dense vector (`:472-480`). 6c item
   3 pins `rd 2` versus `dest 3` on the worked example.
5. **`act` vs `tail` algorithms** (§4.2, §4.3). Four separate
   procedures per kernel (lift act, lift tail, lower act, lower tail);
   register tail lower sets `site-pc`, `pc = site+1`, `regs []`,
   `live []`, `dest nil`, `:return-result`, with `:frames []`/`:store-of
   nil` stated as unobservable (checked by §5.6 item 8). **Verified**:
   the register kernel's `:return-result` branch (`register.cljc:458-468`).
6. **Static laws** (§4.2, §5.6 items 2-5). Live-out over actual
   successors (`analysis/successors`), tail live `[]`; the inverse
   `virt(p, s)` is defined only on the image of `live-out*(p)` at
   reachable boundaries; unreachable structural operands are left to the
   kernel's validator and phase 5's gates.
7. **Validator contract** (§3.1-3.2). Published as `(defect frame code
   context task)` with `task = {:parked-key :ffi-call-ids :free-env}`
   decoded from rows; rules reorganised: structural S1-S4 first (safe
   dependent reads, `:k-shape`, `:window-representation`, `:env-shape`,
   `:deliver-shape`), code C1-C3, `act`-only A1-A6, tail T1, return
   frames K1-K3 with their own kinds, environments E1-E2, pending and
   contexts P1-P4; rule P4 reads the decoded parked key and call ids.
8. **Order** (§3.3, §7.2, 6b item 7). Frames validate at carrier step 7,
   after canonical numbering, everywhere; a first-defect test pins
   `:noncanonical-cells` before a frame defect.
9. **Blinding** (§5.5). Replaced by `rows/canonical-under`: decode to
   the abstract task, placeholder stream identities by first-occurrence
   traversal order, renumber cells, re-emit every row, compare root ids;
   the harness also fixes identities through one toy channel where it
   can. **Verified**: `stream-blind` only post-walks maps
   (`v2_support.cljc:531-543`).
10. **Amendment inventory** (§10). U1 now replaces UCF `:513-527`; U3
    rewrites the activation-depth, stack-height, "the frame carries it"
    and observed-deltas text; R3 covers the decoded-value and
    `registers R` descriptions; R8 deletes §9 legacy, §10.3's
    compatibility-input and nested-projection clauses and §10.8; R9
    reconciles §10.6, the proposals list, "still open" and §11; R6a fixes
    §8 step 1's registry gate; R1a the status paragraph; R7 the `:63-68`
    stamp sentence; R10 the version-0 comparison in the custody table;
    R11 keeps the explicit BLAKE3 rule. The inventory lists the grep
    results it covers. The complete association rule (absent or empty
    is incomplete) is applied in §0.1, §6.1 and U5.
11. **6a dependency** (§8.1-8.2, §8.5). 6a is the independent frame and
    row codec (validator, bijection, numbering, `canonical-under`); full
    task transport moved to 6b, which depends on 4b stage 2 **and** phase
    5c's scanners, since the dependency closure over `"v4"` vectors
    cannot use `completion/code-facts` (stack-table positions).
12. **Test fixes** (row 22, §5.2 refusals, 6a item 4, 6c item 12). The
    nil row is `(f nil (next-of c))` with the nil operand evaluated
    before the read; membership tampers yield membership refusals; a
    changed live value is tested by execution parity, not expected to be
    refused.
13. **Harness matrix** (§5.2). Twenty-four concrete rows with programs,
    satisfaction, state, pending and expected outcomes: both link phases,
    install with a blocked child, a parked child and a tail waiter,
    nested capture, capture invoked after lower (`:done`), the module
    closure row, the free-env row, the nil row, the tail-effect halt;
    row 23 (tail FFI bookkeeping) marked synthetic because no primitive
    returns an FFI effect through a tail call (evaluator §1.6); the 4b
    stage-2 facilities required are listed in §8.2.
14. **Grounding** (§12 item 4, row 18, row 17). `gc-children` cited at
    `srvm-phase4b:semantic_register.cljc:386` (**verified**);
    `parked-module-store-cursors` described as what it is, an explicit
    park whose module store holds cursors (`lift_support.cljc:310-333`,
    **verified**), kept as row 18; a separate blocked-module-closure
    program is row 17 (needs 4b stage 2's link).

## Unverified or disputed

- Nothing disputed.
- 4b stage 2 is not landed: rows 3-4, 7-12, 17, 24 are specified from
  the evaluator design and the stage-1 branch.
- The stack tail-completion seam (§4.3, 6c-pre) is a specification of a
  change to `stack.cljc`, not a reading of existing code.
- The six checked-not-assumed properties of §9 have arguments and
  corpus-wide tests, no proof; shadowed outer positional values are
  explicitly not claimed to round-trip (the law is on canonical frames).
- No tests were run; verification was by reading (`grep`, `sed`,
  `git show`, `date`).

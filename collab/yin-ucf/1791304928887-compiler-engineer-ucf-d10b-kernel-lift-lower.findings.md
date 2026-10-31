Completed-GMT: 2026-10-06 16:46:54 GMT
Completed-Local: 2026-10-06 23:46:54 +07 (+0700)
Coding-Agent: Claude (claude-sonnet-5-5)

# D10b: STOPPED at investigation. No code written.

The brief's rule 5 says to stop and report if the work needs a change to the handoff body's wire grammar. Investigation shows it does. D10b as specified cannot be done inside the permitted diff, and a further architectural ruling is required first. I changed no source or test files. Nothing was run, so there is no red/green evidence and no test counts. All findings below come from reading the landed code, not from executing anything.

## Why the wire grammar must change

1. **There is no field in which a body can declare a kernel/profile.**
   - Contract part 4 requires a body to declare the kernel/profile its code and registers ride, so a receiver can refuse with `:yin.k/profile-mismatch` before restoring.
   - The body today carries only `:yin.k/contract`, which is `ucf/contract-stamp`. That stamp is semantic-only: `{:yin.code/contract vm/semantic-contract, :yin.k/version 0}`. The ruling forbids touching it.
   - `:yin.k/requires` holds only `:yin.k/cursor-profiles` and `:yin.k/segments`. `handoff.cljc:1246` and `:1659` use `profile-mismatch` for the body version and codec only.
   - `:yin.safepoint/engine` (`ucf.cljc:372`) is the nearest precedent, but it belongs to the safepoint table, which is not part of the body.
   - A declaration therefore means a new body field, or a new value shape in `:yin.k/requires`. Either one is a grammar change.

2. **The code form is semantic-only, and the kernels' own addresses are not what the body validates against.**
   - `validate-body` (`handoff.cljc:1230`) requires `(= a (ucf/code-address v))` and `code/well-formed-vector? v` for every entry in `:yin.k/code`. Both are defined only for the semantic canonical instruction vector.
   - The stack kernel's code is a de Bruijn vector whose address is `dcode/image-hash`.
   - The register kernel's code is a map `{:bodies :instructions}` whose address is `rcode/register-hash`.
   - The walker's code is a set of semantic-bytecode rows `{id row}`, held as AST nodes. It has no pc space at all.
   - Contract part 2 says each kernel's own content hash is the address, so the validation must dispatch per profile. The body therefore needs a code-form declaration (see item 1). The stack and register images are not even vectors that `well-formed-vector?` could check.

3. **The register representation is not `{pc env stack k segment}`.**
   - `validate-registers`, `encode-registers` and `decode-registers` fix exactly `:yin.k/segment`, `:yin.k/pc`, `:yin.k/env`, `:yin.k/stack` and `:yin.k/k`. The semantic kernel's wait entry has that shape.
   - The stack kernel's payload is `{:segment :pc :frames :stack :continuation :format :hash :image [:store-of]}`. Its `:segment` is the whole concatenated image vector, its `:pc` is absolute, and `:image` is an offset-table row. `stack-restore` checks `:format` and `:image` against the receiver's offset table.
   - The register kernel adds `:registers` and uses `:bodies`.
   - The walker's entry is `{:env :k}` over AST-node continuation frames, with no pc and no code vector.
   - Contract part 3 says to carry each kernel's continuation and register representation "as it stands — never a converted one". The only alternative is to convert it into the semantic `{pc env stack k}` shape, which the contract forbids. Carrying the representation as it stands needs per-kernel register variants in the wire, which is a grammar change.

4. **The safepoint and pc validation has no analogue for the other kernels.**
   - `check-frame-pc` and `safepoint-kinds-at` rely on `ucf/safepoints` and `ucf/safepoint-at`. These read the static `:yin.safepoint/kinds` from the semantic vector's parking instructions.
   - No equivalent static safepoint table exists for the stack or register images. The register kernel's boundary opcodes with in-band `:live` operands cannot be changed (rule 5).
   - The walker has no pc to check at all.
   - Part 3's "generalize `check-frame-pc`" therefore needs a per-profile eligibility rule. Defining one is new design, not a mechanical generalization.

## What I could not conclude without a ruling

- Whether per-profile registers and code forms can ride under the existing version-1 grammar with additive, optional keys, or whether that needs a new body version or stamp. Rule 5 and the D10 compatibility clause make that an Architect/owner decision (a possible "r3"). Part 5's requirement to keep version-0 fixtures and D9 pins byte-identical also depends on that choice.
- How the `:yin.k/frames` and `:yin.k/parked` registers should encode a walker's AST-node continuation. The semantic wire has no representation for it.
- Whether the walker should be a liftable profile at all. Its continuation is a tree of AST frames, not a pc into an addressable vector.
- Whether the checkpoint inspector (`checkpoint.cljc`), which walks `:yin.k/frames` and `:yin.k/pending`, needs to know the new register shapes. It is outside the permitted diff, so a missing seam there would also be a stop condition.

## Suggested ruling questions for the Architect

1. Where does the profile declaration live, and what is its wire form (a new top-level key, a key under `:yin.k/requires`, or a new `:yin.k/version`)? What are the stamp and compatibility consequences?
2. What is each non-semantic kernel's register wire shape: stack `{segment pc frames stack continuation image …}`, register with `:registers` and `:bodies`, walker `{env k}` over AST frames? Does a profile-specific `:yin.k/code` entry shape (image vector, map image or row set) keep the address equal to the kernel's own content hash?
3. What are the per-profile eligibility rule and safepoint table, replacing `ucf/safepoint-at` (stack and register), and the walker's equivalent (if it is liftable at all)?
4. May `handoff.cljc` plus the four kernel files carry all of that, or does the work need to touch `ucf.cljc` (the safepoint table), `checkpoint.cljc` (the inspector) or `code.cljc` (the validators)? Those are outside the permitted diff.

## State of the worktree

- Branch `ucf-d10b-kernel-lift-lower`. No tracked or untracked source, test or doc file was modified. This findings file is the only addition. No git writes.
- I did not rebase onto D13/D14. With nothing implemented there was nothing to rebase.
- kondo and cljstyle were not run, since no code changed.
- My attempt to write a scratch probe under `/tmp` was blocked by the sandbox, and no output came from it. That is not evidence for anything above.

## Confidence

The grammar-change conclusion is high-confidence for the walker, because it has no pc or vector at all. It is high-confidence for the profile declaration, because there is nowhere to put it. It is medium-high for the stack and register register shapes, because I read their payload definitions but did not run a lift against them. Please treat this as a request for a ruling, not as a finished analysis of the exact wire design.

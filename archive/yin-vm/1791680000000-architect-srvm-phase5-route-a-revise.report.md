Completed-GMT: 2026-10-10 17:58:42 GMT
Completed-Local: 2026-10-11 00:58:42 Asia/Bangkok

(The host clock read about one minute earlier than the prompt's Created
stamp when this report was written; the times above are the clock's.)

# Report: route-(a) phase-5 design revised after codex review

Edited: `docs/design/yin.vm.semantic-register-vm.derivations.md` only
(now 1565 lines). Frozen design, linker and UCF documents, source, tests
and the C4 track untouched; nothing staged or committed.

## The new owner ruling, applied everywhere

Every statement that H is expected to equal, is observed to equal, or is
checked against the old `lower-stack` output is deleted: §3.2 (the walk
is defined by its table and nothing else; H need equal neither the old
image nor R), §6.3 (the R1 and `lower-stack` comparisons in rows 1 and 6
removed; the closing paragraph no longer claims old-image agreement),
§8.1 (the H row says "no relation to the old output is recorded or
checked"), §11 (the "H expected to coincide" flag removed), F9 and F13
(no H-equality claim), and 5a item 4 (the test compares the emitter with
§6.3 and calls no `dl/adapt`). §2.6 likewise says R's bytes are its own.

## Per item

1. **H goldens** (§6.3 rows 5 and 6; every H row). Row 5 is now
   `[:branch-false 6]`, `[:jump 7]` with the pc of every instruction
   listed (0 f, 1 push, 2 c, 3 branch, 4 const 1, 5 jump, Lelse=6 const
   2, Lend=7 push, 8 y, 9 push, 10 call, 11 halt). Row 6 is
   `[:closure 1 6]`: the main body has six instructions (closure, push,
   const, push, call, halt), so the body starts at 6, and the body's
   pcs 6-13 are listed. Every other H row states its pc range and that
   it has no labels; row 4's labels are stated (Lelse=4, Lend=5).
   **Verified** by re-counting each H image twice against the §3.2 walk.
2. **Liveness law** (§2.5, §6.2 item 4, F8, 5b items 1 and 3). The
   comparison is now the physical image of the virtual live-out over the
   **actual successors** `(analysis/successors v p)` minus `rd`; a tail
   `:call` has empty successors, so both sides are `[]`. §6.3 row 9 pins
   `(f (g x) y)` with the inner call marked tail: A, `L` sets, intervals
   (`v1:[0,5] v3:[1,3] v4:[2,3] v2:[3,5] v5:[4,5] v0:[5,6]`), image
   `[[:load-free 0 f] [:load-free 1 g] [:load-free 2 x] [:call 3 1 [2] true []] [:load-free 1 y] [:call 2 0 [3 1] false []] [:halt 2]]`,
   registers 4, and the explicit note that `L(4) − {2}` would have been
   `{f}`. **Verified**: `analysis/successors` returns `[]` for a tail
   call (`analysis.cljc:27`); `live-tail-rule` requires `[]`
   (`debruijn_register_code.cljc:621-628`); the validator accepts the
   shape (items 8 and 10 treat pcs 4-6 as layout syntax).
3. **Lift declarations** (§8.1, §8.2, F11, F13, F14, 5b item 10).
   **Verified**: both descriptors carry a hashed `:dim/lift-to [:yin.code/*]`
   row (`debruijn_code.cljc:145`, `debruijn_register_code.cljc:204`),
   feeding `descriptor-hash` (`:542-548`, `:257-258`) and every H and R.
   §8.2 now removes the row at phase 8 without a version bump; §8.1
   lists two regeneration moments (slice goldens; phase-8 re-pin of every
   H and R and both descriptor hashes) with the mechanical check
   `(jing/sha256 (dc/encode-scalar <printed descriptor>))`. The
   descriptor-hash golden moves from `debruijn_register_compile_test.cljc:445-450`
   (deleted at phase 8) into a new `debruijn_register_code_test.cljc`
   in 5b; `debruijn_code_test.cljc:368` is extended from `string?` to the
   pinned value. The final manifest is enumerated field by field:
   unchanged (`name schema exports requires primitives footprint tree`),
   `contracts` (semantic `"v3"`→`"v4"` only), all three derivation
   records, and `:yin.module/index` in its final shape
   `{:yin.debruijn.code addr :yin.debruijn.register addr}` keyed by
   format (today `{H addr R addr}`, `publish.cljc:189`), both addresses
   changing; the linker-local index attribute entries re-minted.
4. **C4/import gate** (§6.1.4, 5b item 9). Two populations: (a) the ten
   root programs the import tests run, by packet var and line
   (`o q d c r x nn s tt` plus the inline `main` of
   `one-image-two-names-test`), each with its pinned outcome copied from
   `import_test.cljc:400-516`, compared old = new and equal to that
   outcome, count 10 asserted; the six published module packets
   `k m t w e n` are named separately as served modules. (b) module-body
   coverage by test-local composition: each of the eight module ASTs run
   as a root program on both pipelines, comparing halting and the set of
   defined store keys against `prelude/defined-keys`, count 8. (c) stated
   as not covered until phase 8: the install-child path over newly
   published images, covered then by the C4 suite itself. **Verified**:
   the packet vars and outcomes against `import_test.cljc` and
   `import_programs.cljc` line by line; `h/run-linked`, `h/composition`,
   `h/drive`, `h/source`, `h/publish-guest-module!`, `h/registry` are
   public.
5. **Execution rows** (§6.1.3, 5a item 7, 5b item 6): nineteen throw
   comparisons (seventeen unbound names, two unknown parked ids) plus one
   successful FFI comparison under an explicit `{:bridge {:op/ping (fn [] :pong)}}`,
   expected `[true false :pong]`; counts 26, 12, 19, 1 asserted.
   **Verified**: the parity fixture implements `:op/echo` with one
   argument (`parity_test.cljc:163-173`), so it is named as the template
   only.
6. **Retrievability** (§9.2). A defined set-valued `source-index : A →
   #{tree-address}` with its carriers (`:yin.module/tree`, a new
   `:yin.link/trees` on outcomes, `:yin.k/requires :yin.k/trees` in UCF
   values, a linker-local `:source-index`), independence of the vector
   bytes from the association, the several-trees-one-A rule (any
   retrievable member suffices; a derivation record names the tree it
   was lowered from), completeness checks, and the distinction between
   absence of an association (a design defect) and a transient read
   failure (the medium's ordinary `:absent`). `:trusted` now skips
   re-lowering only; the mandatory `jing/get` check is gone. Host modules
   keep their absent tree field with a guest-code carve-out wording and a
   `:manifest-shape` rule that a tree-less manifest has no derivations
   and no index. §4.2 adds a sentence that A is not a key to one AST.
7. **Allocator pseudocode** (§2.3): the frozen scan's two explicit loops
   restored (expiry in increasing `end` then virtual id; assignment "for
   each starting interval in increasing virtual id"); `next` advances
   only when a never-used slot is taken; the single-start property is
   stated as true but explicitly not relied on.
8. **Evidence anchors** (§4.2): paths qualified to
   `semantic_register/linearize.cljc` and `semantic_register/linearize_test.cljc`;
   projection-invariance compares `:vector` and `:address`, not the
   provenance-bearing map. **Verified against the checkout**: the
   `:lambda` arm is `linearize.cljc:103-105`, the definition arm
   `:107-112`, and the tail flag is read at **`:116`**
   (`(boolean (slot id :tail?))`), not at 120-121 as the review stated;
   the document cites 116.
9. **F12 and rulings** (§9.1): the complete scanner replacement text is
   pasted into F12; a rulings paragraph records F9 ACCEPT and F8, F10,
   F11, F12, F13, F14 ACCEPT WITH CHANGES with what each change was.

## Unverified

- The goldens remain hand derivations (no allocator or emitter exists to
  run); row 9 is new and has only my two passes. The R rows 1-8 agree
  with the reviewer's independent table.
- The two-liveness-agreement property is a gate claim with an argument
  sketch, not a proof.
- The module-body population (b) assumes each module AST runs to a halt
  as a root program under the linked harness; `py` requires nothing and
  the guests require `py` and their imports, which the harness serves,
  but this was not run.
- No tests were run; verification was by reading (`grep`, `sed`, `git`,
  `date`).

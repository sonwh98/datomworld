Completed-GMT: 2026-09-26 06:35:30 GMT
Completed-Local: 2026-09-26 13:35:30 +0700
Coding-Agent: claude
Session-ID: e966b9b5-d035-48cd-b629-4274c327e3e0

# Report: yin.vm.linker M4 slice S1, kernel attach-image

Worktree /Users/sto/workspace/datomworld-m4-s1, branch m4-s1 (base
b3d0b5a1). Nothing committed, staged, or stashed.

## Lane counts

| Lane | Baseline (mine, before any edit) | Final |
|------|----------------------------------|-------|
| JVM  | 2,118 tests / 181,736 assertions, 0 fail | 2,136 / 181,862, 0 fail |
| Node | 2,031 tests / 48,573 assertions, 0 fail  | 2,049 / 48,681, 0 fail  |
| Dart | not run (as instructed)                  | not run                 |

My assertion baselines differ slightly from the prompt's (181,744 and
48,576). The difference was already there before I edited anything.
+18 tests = 17 in the new namespace + 1 in repl_test. Node output shows
"Testing yin.vm.attach-image-test", with 0 shadow warnings. No existing
test was edited, and no existing test changed result.

## What was built

- Stack (debruijn/stack.cljc): new `:images` offset table. `load-image`
  writes the base row `[H 0 n]`. New `attach-image vm image contract`
  admits the image alone, the same way `load-image` does (stamp, then
  image-defect). It then relocates the image by the held length,
  appends it, adds one `[H-of-image offset length]` row, and recomputes
  `:hash`. It touches no register, and an identity already in the table
  is a no-op. New `image-pc` (absolute pc to `[identity rel]`) and
  `absolute-pc` (the inverse).
  - The payload gains `:image`: the row its pc falls in.
  - `stack-restore` checks format plus table membership of `:image`.
    `:hash` is no longer a restore key, and `:segment` is never written
    from the entry.
- Register (debruijn/register.cljc): the same table, `attach-image`
  (instructions and `:bodies` shifted), `image-pc`, and `absolute-pc`.
  Hash-safe restore works the same way. Grow-only returns (r7):
  - the `:call` frame drops `:segment` and `:hash`;
  - `return-transition` restores pc, frames, registers, and continuation
    only;
  - restore reads body sizes from the base's segment.
- Semantic (semantic.cljc): `attach-image vm v contract` validates the
  vector as `load-vector` does. It adds the vector under a fresh local id
  below the floor and writes the address into the alias column. Control,
  program, env, stack, k, and store are unchanged. An address that is
  already aliased is a no-op.
- Walker (ast_walker.cljc):
  - New record fields `:rows` and `:row-index` (`{[body-node params]
    lambda-row-id}`), threaded through `cesk-return`.
  - A memoizing row decoder puts each `:lambda` node's source row id in
    its metadata. `vm-load-rows` uses it; the tree stays `=` to the one
    `semantic-bytecode->ast` builds.
  - Both `:lambda` transitions copy the id into the closure as
    `:lambda`, only when the annotation is present.
  - New `attach-image vm bc contract` adds rows and extends the index.
  - New `closure-row` implements the walker rule: the recorded id is
    checked against the row's params and decoded body. With no id, it
    looks up the `[node params]` index. Otherwise it returns
    `{:yin.k/status :yin.k/non-portable :yin.k/kind :unrooted-body}`.
  - New `row-node` returns the decoded node for a held row.
- yin.repl: `append-stack-image` and `append-register-image` now call
  `attach-image`, then set `:pc` from the image's table row, plus the
  fresh-run registers the old `load-image` call used to reset. Its
  private `relocate` moved into the kernels.

## Files changed

- src/cljc/yin/vm/debruijn/stack.cljc
- src/cljc/yin/vm/debruijn/register.cljc
- src/cljc/yin/vm/semantic.cljc
- src/cljc/yin/vm/ast_walker.cljc
- src/cljc/yin/repl.cljc (the append functions and removal of
  `relocate` only)
- **src/cljc/yin/vm/debruijn_register_effects.cljc: outside the expected
  list.** It needed two changes:
  - `continuation-defect`'s frame check (`return-frame-defect`) used to
    require every frame to carry its own `:segment`/`:hash`. That
    contradicts r7, so it now checks frame pcs against the payload's
    `:segment`.
  - `continuation-payload` adds `:image` from the runtime's `:images`,
    through a new public `image-row`. Without it, entries built by that
    function would fail the new membership check in existing tests.
- test/yin/vm/attach_image_test.cljc (new)
- test/yin/repl_test.cljc (one test added)

## Tests added

attach_image_test (17 tests):
- stack and register:
  - attach appends relocated code and adds the row, registers untouched,
    no double attach;
  - a parked entry recorded before an attach restores after it at the
    same pcs, with `:segment`, `:images`, and `:hash` taken from the
    kernel and never from the entry; an absent row is refused;
  - an exported closure, lifted as `[identity rel]` and lowered into two
    parents of different lengths, sits at each one's nonzero offset and
    returns 42 in both;
  - a continuation lifted after an attach and lowered into a fresh
    kernel is rebased by the receiving table: pc, `:return-pc`/`:site-pc`,
    and closure body pcs;
- register: a call in flight while an image is attached returns into the
  grown code space. The frame carries no `:segment`/`:hash`, and the
  attached image then runs.
- semantic:
  - a fresh id plus alias, with state unchanged;
  - a parked entry restores after an attach;
  - two receivers mint different local ids and both apply the export.
- walker:
  - the load annotation plus closure `:lambda`;
  - attach adds rows and extends the index;
  - a parked restore after an attach;
  - a body shared by two `:lambda` rows (`[x]` and `[y]`) lifts from its
    recorded row; with no id the pair resolves by index; a fabricated
    pair, or a recorded id whose params disagree, is `:unrooted-body`;
  - an exported closure lowers to a structurally equal body and applies.

repl_test `de-bruijn-appends-go-through-attach-image` (stack and
register): one table row per distinct image, the rows tile `:segment`,
and repeating an input reruns at the existing row.

## Deviations and decisions to review

1. The REPL does more than "set :pc itself". Before, `load-image` reset
   the frames, stack, and continuation, `:halted?`, `:blocked?`, and
   `:value` (and for the register kernel, sized `:registers` from the
   main body). `attach-image` touches no register, so the REPL now sets
   these itself to keep its behavior unchanged.
2. The REPL takes `:pc` from the table row, not the held length. When an
   input's image is already held, attach is a no-op (as the spec says),
   so the held length would point past the code.
3. Register `reset` now keeps `:images` (scoping report trap 10).
   Grounds: r7 says only the loaders and `attach-image` write the table,
   and reset is a fresh run over the held code space.
4. Entries still carry `:segment` and `:hash`. Existing tests read them,
   and the register defect check uses them to validate the entry against
   itself. Neither is a restore key, and neither is written back.
5. Base row for an empty register VM: `:hash` stays nil as before, and
   the base row's identity is `register-hash` of the empty image.
6. A pc one past the end of the last row counts as falling in that row
   (`row-at` / `image-row`).
7. The walker annotation uses metadata, so node equality, and so every
   existing comparison, is unaffected. The closure's `:lambda` key
   appears only on programs loaded through `vm-load-rows`, and no
   existing test depended on its absence.
8. The acts 1/4 lift and lower (the UCF markers) are not implemented.
   They belong to engine/UCF work (S3). The tests compose them by hand
   from the kernel primitives. No engine change was needed, so this is
   not blocked.

## Unrun checks

- Dart lane (not run, as instructed). There is a CLJD risk to check:
  metadata on the walker's map nodes (`vary-meta`), and `letfn`/atom in
  the decoder.
- My final JVM run started just before I removed the unused
  `yin.vm.engine` require from the new test and applied cljstyle to it,
  so it ran the pre-formatting version. The Node lane ran after both
  edits and passed.

## Lint

- kondo on all changed files: 0 errors. Two warnings/infos remain, both
  present at HEAD: the unused private `ast-walker-run-active-continuation`
  and stack's unused excluded `eval`.
- cljstyle check: clean on all eight files.
- Added lines are pure ASCII and at most 80 columns.

Status: COMPLETE

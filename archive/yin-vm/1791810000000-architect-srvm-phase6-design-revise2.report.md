Completed-GMT: 2026-10-11 03:50:03 GMT
Completed-Local: 2026-10-11 10:50:03 Asia/Bangkok

# Phase-6 design, round-2 revision: report

Fresh Architect session (claude-fable-5-1); the previous session overflowed
after writing partial, uncommitted edits to
`docs/design/yin.vm.semantic-register-vm.continuation.md` (HEAD `fdd85434`).
I read the design in full, the codex round-2 findings, the original brief's
context documents and the landed code, verified every partial edit against
code and by re-derivation, then finished what remained. Only the design and
this report were edited. No git add/commit. Nothing was run.

Per item: **PARTIAL** = already done by the previous session's uncommitted
edit, verified by me; **MINE** = done in this session. The 4b branch now has
stage 2 committed (`bdf48cf4`); the design's 4b line numbers are stage-1's
(`0f4522b9`) unless marked stage 2, and the status paragraph says so (MINE).

## 1. Stack lowering bases (HIGH) — PARTIAL (formula) + MINE (fixture)

**Change.** §4.3 "Lower, `act`": `bᵢ` is the cumulative length through
`slice(fᵢ)` (`b₀ = |slice(f₀)|`, `bᵢ = b_{i−1} + |slice(fᵢ)|`), the inverse
of the lift's `slice(fᵢ) = St[b_{i−1}, bᵢ)`; "Lower, `tail`" states the same
rule, with the last frame's base equal to the stack length (PARTIAL). I
replaced the worked sentence and rewrote 6c acceptance item 6 (MINE): the
fixture is the expression under the harness's **standard** bindings
(`with-cat` outermost, then `let1 s`, `let1 c`), which gives **five** return
frames with bases `0, 0, 0, 2, 4`, slices `[0,0) [0,0) [0,0) [0,2) [2,4)`,
current `[4,6)`; the before-slice rule would emit `0, 0, 0, 0, 2`. The
two-frame reading (bases `2, 4`) is kept as the derivation of the expression
alone. §5.2 gained a paragraph on which wrappers push which frames; §9 records
the round-2 correction.

**Verification.** `stack.cljc:716-733`: `:call` pops `f` and `argc` operands,
pushes `{:return-pc (inc pc) :frames :stack-base (count stack')}` with
`stack'` the residual; `:return` (`:766-772`) truncates to that base. So the
base is the end of the caller's own slice, cumulative through it. Re-derived
the six-value stack: operator pushed first (phase-5 §6.3 H images), `[1]`,
then the closure and `1` popped by the call → `[cat [1]]`, base 2; same again
→ base 4; `cat`, `[3]` pushed and the cursor popped by `:stream-next`
(`stack.cljc:838-839`) → length 6.

**Disputed / corrected against the partial edit.** The partial edit's "run
bare with `cat` supplied in the free environment as the primitive `conj`" is
not usable as a lift fixture: a host primitive in a live slot is no carrier
value (`:continuation-registers`, `debruijn_register_effects.cljc:196`; the
old harness's `with-cat` docstring, `:69-74`, exists for exactly this), and
`c` must be made inside the program like every row. The partial edit's
"under `with-cat`, three frames, bases `0, 2, 4`" omitted the `let1 s` and
`let1 c` frames (bases 0). Codex's "bases 0,2,4 with the wrapper" has the
same omission; the corrected count is five frames.

## 2. Live-set formula — PARTIAL (verified)

**Change.** §4.2 step 4 and §5.6 item 3: `(sort (map phys (disj
live-out*(p) rd)))`, subtraction on virtual ids before mapping, worked
example `L(4) = {1 2}`, minus `rd 2` → `{1}` → `[0]`. I fixed one grammar
slip ("is the `(sort …)`") and expanded §9's honesty note (MINE).

**Verification.** `analysis_test.cljc:131-139` pins `L(4) = #{1 2}`,
`saved(4, 2) = #{1}`; phase-5 §6.3 row 2 pins `phys(1) = T0`, `phys(2) =
T3` and `live` at pc 3 `= [0]` (it subtracts the physical `T3` after
mapping, the equivalent form codex offered). `{1 2} − {3}` would indeed keep
`2`; the partial text says so.

## 3. Free-environment acquisition — PARTIAL (verified)

**Change.** §6.3 "Where the lift gets it": de Bruijn kernels read their own
`:free-env` field; the semantic-register evaluator has no such field, so the
**composition owns** the initial map (the `:env` it passed to `create-vm`),
the lift takes it as an explicit argument `(rows/lift-task vm serve!
{:free-env E₀})` and refuses `:yin.k/non-portable :missing-free-env` without
it; the recommended retention seam (an immutable `free-env` field set once
in `create-vm`, listed in `gc-roots`) is 4b stage 3's, in 4b's file, not
phase 6's; §7.1, §8.2 (dependency) and §8.4 (phase 7 entry condition) carry
it. Never recovered from a current `E`.

**Verification.** `srvm-phase4b:semantic_register.cljc:23` `env ; persistent
lexical scope map` (stage 1 and 2); stage 2 `create-vm` checks `:env` with
`vm/check-bindings!` (`:375`; master 4a `:261`) and keeps no copy; `eval`
captures the initial env only transiently (`:335`, like
`semantic.cljc:874-876`); `gc-roots` exists (`:485-487`) listing
`env`/`window`/`value`, so "listed in `gc-roots`" is a one-line addition.
`stack.cljc:82` "fixed for this instance", `register.cljc:50`. 4b's file
was read via `git show srvm-phase4b:…`, not edited.

## 4. E2 validator, X1, C2 — PARTIAL (rules) + MINE (entry point)

**Change.** E2 (a) requires every lexical name of the chain present in
`bindings`, nil-valued keys included; (b) compares off the lexical names in
both directions; X1 validates the root `free-env`'s shape, sortedness,
uniqueness and reserved names; C2 checks exact non-negative integers
(`cbor/numeric-kind :integer`) **before** indexing, kinds `:pc-kind` /
`:pc-bounds` (PARTIAL). I added the public entry point `frame/task-defect`
for X1 (once per body, before any frame), its place in §3.3 step 7, its
path, and the note that it is the same check `create-vm` runs (MINE).

**Verification.** `engine/bind-params` nil-fills (`engine.cljc:55-60`), so an
absent lexical name cannot be a minted `E`; `vm/reserved-name?`
(`vm.cljc:118`), `vm/check-bindings!` (`:161-168`); `cbor/numeric-kind`
returns `:integer` for host integers (`cbor.cljc:842-853`).

## 5. Harness oracles — PARTIAL (rows 15, 22, 23, tampers) + MINE (fixes)

- **Row 15** (PARTIAL program; MINE: satisfy column, mechanism, helpers).
  Program branches on `k` before the read; invoking `(k :second)` is
  abortive (`srvm-phase4b:semantic_register.cljc:212-217` stage 2,
  `stack.cljc:760-763`) and re-enters at the capture's successor with `k =
  :second`, so the read is not repeated; result `:done`. `if` has no
  `v2_support` helper (`v2_support.cljc:56-104`): the new harness file adds
  one over `{:type :if :test :consequent :alternate}` (`vm.cljc:1069`); `=`
  is a primitive (`vm.cljc:387`) consumed before the read.
- **Row 22** (MINE). The partial edit used `(vector p q)`; there is **no
  `vector` primitive** (`vm.cljc:380-410`: `conj`, `vec`, … only). Now
  `pair = (fn [p q] (cat (cat [] p) q))`, result `[nil "B"]`; §12 item 1's
  stale `(f nil (next-of c))` fixed.
- **Row 20** (MINE, found while verifying). "`((fn [c] (stream/next! c))
  cursor)` at the root (empty `K`)" was wrong: the harness marks `:tail?`
  only explicitly (`safepoint_harness_test.cljc:228, 256`;
  `ast->semantic-bytecode` defaults it false, `vm.cljc:1089`; `mark-tail`
  is the expander's), so that outer application pushes a frame. Row 20 now
  runs outside the wrappers in two batches: a `def!` setup batch, then
  `(stream/next! c)` marked tail at the root, `c` from the store
  (`lift_support.cljc:320-322` pattern); 4b halts a frameless tail effect
  (`:205-211` stage 2). 6b item 1's counts updated (22 wrapped rows × 4 + 1).
- **E2 tamper** (PARTIAL): unshadowed `y` (`{x 99 y 7}` → `8`) trips
  `:free-env-mismatch`; lexical `x` → 98 does not and the test asserts it;
  removing lexical `x` trips (a). **Row 23 / tail-site tamper** (PARTIAL):
  `site` moved to a non-tail call → `:site` (T1 precedes P4 in §3.2's
  order); a valid tail under a key absent from `:ffi-call-ids` →
  `:parked-site`. Verified against the rule order.
- **Rule count** (MINE). Partial text said "twenty-four rows:
  4+3+6+1+3+2+1+4" and then added a second C2 row: now **24 rules** (codex's
  23 plus X1) and **25 rows**.
- **Wrapper results** (PARTIAL paragraph; MINE frame accounting). `:bare r`,
  `:operand [:operand r]`, `:depth-3 [3 [2 [1 r]]]`, `:captured [:captured
  :arg r]`; verified against `cat-of`/`nest`/`corpus`
  (`safepoint_harness_test.cljc:78-106`) and the pinned depth-2 value `[2 [1
  [:two 2 "C"]]]` (`:574`). The partial "every segment's `K` has one more
  frame than the bare expression's" was imprecise: `with-cat` adds one
  (base 0); `:depth-3` adds three more; `:captured` three more (`let1 k`,
  `let1 f`, `(f :arg)`, all non-tail); stated so.

## 6. Placeholder assignment — PARTIAL (mechanism) + MINE (argument)

**Change.** §5.5: `canonical-under` takes an explicit `{lane-identity →
logical-name}` correspondence from the fixture, never traversal order; an
uncovered marker is a test failure; 6a item 10 adds the unordered-container
permutation test (PARTIAL). I corrected the invariance argument (MINE): the
partial text called draft §6 "defined over structure, not identities", but
§6 step 1 seeds each cell's label with `[:body <stream-marker-bytes>
<position-bytes>]`, so stream markers **are** inputs; the argument now is
that §6 is invariant under cell renaming, its only other identity input is
the marker, which the substitution makes a fixture constant, and unordered
containers are sorted bytewise on encodings (§6 step 2), so no host traversal
order survives.

**Verification.** `yin.vm.ucf-transport-tuples.md:435-520`.

## 7. Amendment residue — PARTIAL (R9, U7 core) + MINE (scope)

**Change.** R9 now replaces §10.6's opening sentence, qualifying the
value-profile prerequisite to engines **outside the three repository lanes**,
and the "does not translate" sentence (PARTIAL; verified against the draft
`:782-788`; §2's `:78-81` echo is inside R1's wholesale replacement). U7
inserts the scope/precedence statement at UCF §7.2.1 and lists the
historical clauses (PARTIAL; every cited UCF line checked: `:166`, `:234-290`,
`:966-970`, `:1781`, `:1806-1807`, `:1844`, `:3051-3057`, `:3095-3096`). I
added (MINE), from a re-grep: custody/fencing/operation-id semantics
(§7.7.8, §7.9) are **not** historical and are read for version 3 through the
root's `custody` slot (draft §5.5), with the version-1/version-0 wording
mapped to the exclusive/fork policies; the `"v3" :yin.k/version 0` code stamp
(`:121`, `:183`, `:232-236`) is the stack-shaped machine's, `"v4"` is the
register-shaped one's (UCF never mentions `"v4"`), and §7.2's envelope
example is superseded by the draft's §3 wire unit; the remaining grep hits
(`:1741`, `:2417`, `:2774-2775`, `:39-42`) are named and classified.

## Unverified / disputed

- Nothing was executed; every number is hand-derived twice as §9 states.
- Codex's and the partial edit's "with-cat adds one empty frame, bases
  0,2,4" undercounts the harness's binding frames (five, `0,0,0,2,4`); the
  base rule itself is as codex stated.
- Row 20's two-batch shape assumes the new harness runs two `load-ast`
  batches on one semantic-register machine; `def!` store persistence across
  batches is how `lift_support` builds `parked-module-store-cursors`, but a
  second tail-marked root batch on the 4b evaluator is not exercised by any
  landed test.
- Partial edits' citations to 4b stage 1 were not renumbered to stage 2; the
  status paragraph records the three moved anchors.

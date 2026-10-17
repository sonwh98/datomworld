Completed-GMT: 2026-10-10 15:42:18 GMT
Completed-Local: 2026-10-10 22:42:18 Asia/Bangkok

(The host clock read three minutes earlier than the prompt's Created
stamp when the report was written; the times above are the clock's.)

# Report: phase 5 design, derivations from A

Deliverable: `docs/design/yin.vm.semantic-register-vm.derivations.md`
(worktree `/Users/sto/workspace/datomworld-srvm-p5-design`, branch
`srvm-phase5-design`, from master `0ff25b58`). Docs only; no source,
test or existing design file was edited; nothing staged or committed.

## Summary and recommendation

Phase 5 reduces to one new function plus two one-line compositions.
`resolve-vector` parses an admitted register-shaped vector under the
phase-3 grammar, builds the lexical chain from the validated
closure-owner tree, and emits resolved tuples in **exactly the shape
`yin.vm.debruijn-resolve/resolve` emits today**, record by record (§1.4
of the deliverable gives the field-by-field table, including the two
synthesized records a `:define` needs so the lowerers' `definition-key`
path sees a legal definition). `stack-image` is `lower-stack` of that;
`register-image` is `lower-register` of that. **Recommendation on A → R:
route (b), the adapter route, over the frozen §8.2 interval allocator.**
The evidence is mechanical: R1's allocator assigns an expression's
destination at entry (pre-order, before its children) and reuses freed
slots, which is A's §3.3 minting order with a free list; the frozen §8.2
scan assigns intervals at their first syntactic definition, which for a
call is the `:call` itself, after the operands. The two therefore agree
only on bodies that are a single atom: on `(+ 1 2)` R1 gives
`[:load-free 1 +] [:const 2 1] [:const 3 2] [:call 0 1 [2 3] false []]`
and the §8.2 scan gives `[:load-free 0 +] [:const 1 1] [:const 2 2]
[:call 3 0 [1 2] false []]`; on the worked example every operand and the
register count differ (worked through under §8.2's own tie rules in
§3.2). Route (a) would therefore re-version `"r2"` for every program with
a call, regenerate twelve pinned R goldens and move the C4 track's
manifest address, for the sake of a new allocator whose only merit is
reading A's virtual ids. Route (b) reproduces today's H and R **by
construction** (the argument in §1.6: both producers of the lowerers'
input are lowerer-equivalent, saturation included because `ast->datoms`
already materialises the gensym prefix and the 1024 stream buffer), so
`"b2"` and `"r2"` are retained, no golden moves, and §4 turns the
byte-identity question into a per-program gate over a named corpus
(B0 26, register corpus 32, the stack corpus, the shared-occurrence
fixtures, the R1 live fixtures and R2 nodes, and the eight C4 module
ASTs by name) comparing structure, canonical bytes and checksum, never
checksums alone, plus a mechanical normative-contract check. The
scanners are the one piece of genuinely new logic: every `"v3"` scanner
reads stack-table positions, and the §2.3 table moved every operand one
slot right and dropped `:push`; §6 specifies a `scan` namespace over the
new table, the facts compared (free-name sets; occurrences and
definitions by name and flag in order, positions excluded since layouts
differ; application-site counts; store keys, FFI ops, parked ids,
effects; the step-5a join's retained obligations), a three-corpus
agreement gate against the old scanners and the tree-side oracle, a
golden fixture pinning the old scanners' answers before cutover deletes
them, the `:yin.semantic-register/code` format record, and the derivation
records under A-only in two steps so no manifest address moves before
cutover. Slices: 5a (resolve-vector, addresses, A → H), 5b (A → R), 5c
(scanners, format record, byte-neutral linker switch), each with files,
named rows and numbered acceptance lists; 5a, 5b and the scanner half of
5c run concurrently with 4b, touching no 4b file.

## Findings against the frozen design

- **F8** (§8.2, §8.3 step 3, §8.4, §6, §9, §10 phase 5): A → R by the
  existing `lower-register` over `resolve-vector`'s output; no new
  allocator; H and R hold by construction; `"r2"` not expected to move.
  Exact replacement text for each section is in §8 of the deliverable.
- **F9** (§8.3 steps 3-4): the adapter neither orders bodies nor
  recomputes pcs; both emitters own a FIFO body queue and a label pass,
  and a resolved-tuple set is a tree with no order to preserve.
- **F10** (§8.2 last paragraph, §9, §10 phase 8): both descriptors hash
  `:dim/lift-to [:yin.code/*]`, so "the register image's `lift` changes
  its output contract to the §2.3 table", if reflected in the
  descriptor, would move every H and R and force `"b3"`/`"r3"` with no
  executable byte changing. Phase 5 leaves the lifts alone; a phase-8
  clause is proposed and the choice is the owner's.
- **F11** (§10 phase 5): "discharged bodies" is not a scanner fact; the
  gate compares free-name sets, occurrences and definitions by name and
  `:in-body?`/`:conditional?` flag in order, application-site counts, the
  requirements map, and the step-5a join's retained obligations, on
  three corpora, with the old answers pinned as goldens before cutover.
- **F12** (§8.1, §3.4 item 7): §8.1 is unaffected; the structural-slot
  machinery existed only for the interval formulation and goes with it
  (R1 already handles wholly terminal bodies through its pre-allocated
  result temporary, pinned by existing goldens).

## Questions for the owner

1. Route (b) retains `"r2"`/`"b2"` unchanged and means the native
   allocators never read A's virtual register ids (they parse A into the
   tree and allocate as today). Acceptable as the phase-5 outcome, given
   §8.4 expected `"r2"` to move? Recommended: yes.
2. At phase 8, the lifts and the hashed `:dim/lift-to` label (F10): keep
   the label frozen so H and R never move, or re-version openly?
3. At phase 8, the `"v4"` semantic lowering's `:yin.lower/profile`
   string (today `"ast-to-bytecode"` under `"v3"`): new name or same
   string. No byte consequence before cutover.

Decisions marked as the Architect reviewer's (deliverable §9): the
record-id scheme, the corpus membership, the "gate not a choice" stance,
where the `"v4"` footprint table lives before cutover, the byte-neutral
5c switch of `relowered`/`publish-closure!`, and the fate of the named-
datom resolver after cutover (recommended: kept as a test-only oracle).

## What could not be grounded in code, and why

- The §3.2 interval-scan results for `(+ 1 2)` and `(f (g x) y)` are
  derived by hand under the frozen §8.2's stated rules (pc-order scan,
  `end < p` expiry, lowest free slot, ties by pc then virtual id); no
  implementation of §8.2 exists to run. R1's side of each row is from
  the code and its pinned goldens (`fixture-a`'s own comment; the
  `:worked-example` slots follow from `lower-node!` and are proposed as
  a pinned 5b row).
- The byte-identity claim of §1.6 is an argument from the two producers'
  code paths, not a run: no test exists yet that lowers from A, and the
  brief authorised running only the existing golden tests, which do not
  exercise the new path. §4 makes the claim a gate so the implementer
  proves it per program; a failure there is specified as a defect in
  `resolve-vector`, not a re-version.
- No tests were run. Verification was by reading; `git log`, `grep`,
  `sed`, `wc` and `date` were the only commands executed.
- The C4 module ASTs are named as corpus members by the specs the C4
  tests build (`prelude/module-uast`, `safepoint/module-spec`, the six
  guest packets through `lower/module-spec`); the exact free-name sets
  they yield were not computed here and will be pinned by the 5c golden
  fixture.

Completed-GMT: 2026-10-10 16:00:35 GMT
Completed-Local: 2026-10-10 23:00:35 Asia/Bangkok

# Report: phase-5 derivations design revised after codex review

Edited: `docs/design/yin.vm.semantic-register-vm.derivations.md` only
(now 1688 lines). The frozen design is untouched; F8-F12 remain
proposed text inside the derivations document. No source, test or C4
file changed; nothing staged or committed.

## Per item

1. **Address oracle** (§5.1, §5.2, 5a item 7, 5b item 4). A new
   `derive/resolved-addresses` walk is specified: for an application
   whose operator record is the definition operator it visits only the
   value operand, as both lowerers do (`debruijn_linearize.cljc:120-126`,
   `debruijn_register_compile.cljc:178-187`). The law's fourth leg reads
   today's resolver through this corrected walk, never through
   `resolved-addresses-of`, which is now named only to say it is not
   reused on a corpus with definitions. A non-vacuity assertion requires
   a definition program to yield no `[:free yin/def]`.
2. **Scanner agreement oracle** (§6.3). Split into (i) unordered
   three-way agreement (sets and multisets, old = new = the
   `linker/ast-format` scanners and `vm` queries) and (ii) ordered
   three-way agreement against an independent emission-order tree oracle
   written in the test from the map AST: main then FIFO bodies, operator
   before operands, definition applications visit only their value,
   `conditional?` inside bodies and conditional arms, sites = non-definition
   applications plus FFI nodes. The ordering mismatch (`:define-then-call`:
   tree lists body `list` first) is stated explicitly.
3. **Join agreement** (§6.3 (iii), 5c items 5-7 and 10). Through the
   public `linker/verify` on both formats; outcomes normalised to
   `{:status :ok :obligations [[name in-body?] …]}` or
   `{:status :refused :reason r :name n}` and compared whole. A
   `:use-before-definition` row `(list n (yin/def n 1))` is added with its
   pinned refusal. The join runs on all three corpora, C4 included.
4. **C4 declarations** (§6.3 corpus 3). Raw agreement with the track's
   own public `prelude/free-names` kept separate from the discharged
   comparison: declaration keys = scanner free names minus
   `(conj (prelude/defined-keys ast) 'yin/def)` minus requirement-covered
   names, per `lower/module-spec` (`lower.cljc:1864-1877`) and
   `prelude/module-spec` (`prelude.cljc:3445-3466`). The gate stays
   read-only; a disagreement is reported to the C4 owner.
5. **Worked-example golden** (5b item 6). Inner call pinned as
   `[:call 2 3 [4] false [1]]` with the reason (`f` in register 1 read
   after the inner call); outer live stays `[]`.
6. **Fixture facts** (5c items 2-3). Exact per fixture: `:nested-lambdas`
   `[+ true]`, `a`/`b` bound; `:body-queue-order` and
   `:closure-in-arm-in-body` no free occurrence; `:define-then-call`
   `[inc false] [n false] [list true]` with `n` a free store name and
   `a`/`b` bound; `:if-in-tail` `[< true] [loop true] [- true]`.
   Definition and site counts per fixture (`:define-then-call` one
   definition, three sites; `:if-in-tail` four sites; `:store-ops` two
   definitions, one site).
7. **F9** (§8 F9, F8's §8.3 and §8.4 replacements). Body discovery order
   agrees; each emitter computes its own instruction positions; the
   frozen §8.3 steps 1-2 are merged so there is one parse; §8.4's
   composition formula becomes `lower-stack(resolve-vector(A(P)))` and
   the register analogue.
8. **Brief gaps** (§6.2 "Imports and access, pinned"). `scan`'s requires
   are listed; `segment-rows` is defined in `scan` with no dependency on
   `yin.vm.code`; the `"v3"` side is reached only through public surface
   (format-record keys, `linker/semantic-free-occurrences`,
   `completion/segment-free-names`, `vm/segment-requirements`,
   `yin.vm.code/project-segment-qualified` inside the test only); the join
   through public `linker/verify`; no `#'` anywhere.
9. **Runtime gate** (5a item 8, 5b item 5). Runtime corpus = the 26 B0
   rows plus the ten value-producing register-corpus rows; composition
   `{:primitives vm/primitives :make-stream tu/make-stream :contract …}`;
   a common `native-normalize` (closure → `:closure`, stream/cursor refs
   → `{:type :id}`, host fn → `:host-fn`, recursive) applied to both the
   pinned expectation and the native value; halting asserted. Byte
   comparison stays over the full corpus `C`.
10. **Editorial** (§3.2, §4.2). Stack corpus counted at 15 (`:definition`
    added to the row). §3.2 narrowed to two lemmas that follow from the
    allocation orders (root result for call/ffi/define/effect roots; call
    destination when the operator is at pc 0), applied row by row to B0:
    ten atomic rows agree (store-put and gensym now included), fourteen
    call-rooted rows and the two `if` rows differ; conditional and
    `:resume` roots are explicitly left to the gate, with `(if c 1 2)`
    noted as a case where both allocators agree.
11. **§4.4**. The generic re-version branch is replaced: a discrepancy
    blocks the slice and triggers investigation to a named cause;
    re-versioning requires an explicit changed-contract decision after
    the cause is understood, reviewed and recorded. §4.5 now records
    `"b2"` (5a) and `"r2"` (5b) as independent rows with a slice column.
12. **Resolve-on-vector** (5a items 1 and 5, 5b item 1). Side-table
    comparison is per closure pc on `:params` only, `:source` excluded,
    same closure pc set on both sides; `:source` must equal the complete
    record set including the synthesized definition records.
13. **F10 and rulings** (§8 F10, §9). F10 now states phase 5 leaves lifts
    and both descriptors unchanged and that phase 8 needs an explicit
    lift-contract decision, with "a historical label over a changed lift
    is not an option". §9 gains a "Reviewer rulings recorded" block
    listing codex's rulings on pc-derived ids, corpus (15), the blocking
    gate, the derived footprint map with drift test and phase-8
    relocation, the byte-neutral 5c switch after both gates, the
    named-datom resolver kept through phase 7 and a portable test oracle
    after, the obligations-fixture follow-up, and that the slow C4 rows
    must run for sign-off (also added to 5c item 7).
14. **Owner questions** (§9). Three, reworded: the route-(b) tradeoff in
    codex's terms (no simpler native allocator or local stackification
    from the register shape; benefits are evaluator state, UCF windows,
    reconstruction maps; recommended yes); the phase-8 lift contract;
    the `"v4"` profile string, with reuse of `"ast-to-bytecode"` under an
    explicit `"v4"` contract noted as acceptable.

## Disagreements

None of substance. One nuance kept rather than dropped: §3.2 still
presents the general allocation claim, but as two stated lemmas with
their scope (call-like roots; calls whose operator is at pc 0) and an
explicit list of what they do not cover, instead of "the two agree only
on atoms". Codex asked for narrowing unless a proof is supplied; the
lemmas are the proof for the cases claimed and nothing more is claimed.

## Unverified

- The lemma arguments in §3.2 and the `(if c 1 2)` agreement example
  are hand derivations under the two allocators' stated rules; no §8.2
  implementation exists to run, and no code was executed.
- The per-fixture scanner facts in 5c items 2-3 were read off the
  `corpus.cljc` goldens by hand (pcs and bodies); they are stated as the
  expected values the tests must pin, not as test output.
- The `:use-before-definition` row's refusal on both formats follows
  from `undischarged`'s main-sequence branch as read; it was not run.
- No tests were run in this revision either; verification was by
  reading (`grep`, `sed`, `date`).

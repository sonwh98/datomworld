Completed-GMT: 2026-10-11 06:17:32 GMT
Completed-Local: 2026-10-11 13:17:32 Asia/Bangkok

# Report: amendments applied to the frozen design and its dependent documents

Worktree `datomworld-srvm-amend`, branch `srvm-amend` (from master
993d5411). Documents only; nothing staged or committed. Five files
changed (`git diff --stat`): `yin.vm.semantic-register-vm.md`,
`yin.vm.universal-continuation-format.md`, `yin.vm.ucf-transport-tuples.md`,
`yin.vm.code-as-tuples.md`, `yin.vm.linker.md`. The two companion
designs, `datom.world.md`, every other design document and all code are
untouched.

**Rulings check.** The `collab/` directories of the sibling worktrees
`datomworld-srvm-p5-design` and `datomworld-srvm-p6-design` are outside
this session's permitted paths (listing and reading both were refused by
the sandbox). The rulings were therefore taken from the record the two
designs carry themselves: derivations §9.1 (F9 ACCEPT; F8, F10-F14 ACCEPT
WITH CHANGES, "the changes applied below") and continuation §10 (F1, F4,
U2, U4, U5, R1, R2, R7, C1 ACCEPT; F2, F3, U1, U3, U6, R3-R6, R8, R9
ACCEPT WITH CHANGES, "applied below"). The amendment texts were applied
as the designs print them. A second reader with access to the review
files should confirm nothing in them post-dates the designs' text.

## 1. Amendment table

| id | target document and section | applied | notes |
|---|---|---|---|
| F1 | frozen §4.2, row-carrier paragraph | yes | `:act`/`:tail` rows, `[:lit nil]`, `:kframe`, `:stack`/`stack-base` removed, root `free-env`. Closing sentence adjusted: the amendment "is applied to the carrier draft with phase 6" instead of "is written when this design lands". |
| F2 | frozen §4.2, the validator | yes | Inserted as its own paragraph after the paragraph ending "pending compatibility with `reason`. … dead values never travel", not mid-sentence, so the "therefore" of that paragraph keeps its antecedent. Text verbatim. |
| F3 | frozen §4.3 | yes | Appended after "as today." verbatim. |
| F4 | frozen §10 phase 6 | yes | Both replacements. The gate's parenthetical was merged with the existing one ("the semantic kernel, the stack and register kernels through their layouts, the stack kernel with its tail-completion seam; the walker is excluded") rather than nesting two parentheticals; no content dropped. |
| F8 | frozen §8.2 | yes | Interval bounds replaced; scan order kept verbatim; `live` definition replaced (`body-liveness` as the one function, actual successors, checked property); closing R1 paragraph replaced. The sentence following the `live` replacement ("This is exactly what the register design's `body-liveness` computes …") was kept as the design's text did not delete it and it agrees. |
| F9 | frozen §8.3 | yes | Steps 1-4 replaced by the two steps. The trailing paragraph (whole-body lowering, parse hands the emitter the tree) kept; it is consistent with "one walk". |
| F10 | frozen §8.4 | yes | Whole section replaced, title "Verification". Internal references §6.1.x/§6.3/§8.1 spelled "derivations design §…" where the frozen design has sections of the same number. |
| F11 | frozen §6, second paragraph | yes | Replaced from "The de Bruijn contracts `"b2"` and `"r2"` are **not re-versioned…**" to paragraph end. |
| F12 | frozen §10 phase 5 | yes | Both replacements; the complete three-obligation scanner text. |
| F13 | frozen §9 "Corpora" | yes | Replaced. |
| F14 | frozen §10 phase 8 | yes | Inserted after "and the `raise` dependency,"; cross-references §8.3/§7.6/§8.1/§6.1.4 spelled "derivations design §…"; "finalize documentation. No compatibility shim." kept at the end. |
| §9.2 row 1 | linker §3 amendment, "stays obtainable" | yes | Appended; "unitarity invariant of `datom.world.md` (the AST is retrievable wherever code is lowered)" named once for consistency with the master document's wording. |
| §9.2 row 2 | linker §3 amendment, walker bullet | yes | One sentence appended. |
| §9.2 row 3 | linker §8.1 `:trusted` | yes | Appended. |
| §9.2 row 4 | linker §8.3 host modules | yes | Appended; absent field left as is. |
| §9.2 row 5 | UCF §7.3.4 (`:511`, resolution order) | yes | Merged with U1 at the same passage; see reconciliation 1. |
| §9.2 row 6 | linker §8.1 "One tree" | no (none proposed) | Kept as the design says. |
| U1 | UCF §7.3.4 `:511`, `:513-527` | yes | Both replacements; see reconciliation 1. |
| U2 | UCF §7.4.1 | yes | Superseded-notice at head; `:yin.k/frame` example replaced by continuation §1.2's keyed `act`/`tail` form; one sentence added saying the reason travels in the pending and the stack-shaped keys are gone (the removed example's comments carried those). |
| U3 | UCF §7.4.2 | yes | Opening paragraph, "travel in the value" sentence, static map (`def`/`use`/`live` rows), rules, "physical location → virtual register". |
| U4 | UCF §7.4.3 | yes | Inserted after "Three rules make these resumable …:" as its own paragraph before the bullet list. |
| U5 | UCF §7.6.1 + new §7.6.1a | yes | `:yin.k/trees` row in the example; §7.6.1a holds the §9.2 UCF row text and the completeness clause (absent or empty is incomplete, known-address read failure is not absence, several trees one A). |
| U6 | UCF §7.11 harness item, §7.11.1 | yes | Three replacements; see reconciliation 2 for the projection/direct clause. |
| U7 | UCF §7.2.1 head | yes | Scope statement inserted at the head of §7.2.1, before the r5 amendment paragraph. The design's line anchors (`:121`, `:183`, `:232-236`) rendered as "§7.2's envelope example, the body example below, §7.3.3". Nothing else in the historical clauses was edited, as U7 directs. |
| R1 | carrier §2 | yes | Section replaced whole (its title changed from "same profiles" to "one profile"). R7's `:63-68` deletion is subsumed (the deleted stamp paragraph was inside §2). |
| R1a | carrier status paragraph | yes | Replaced. |
| R2 | carrier §5.2 | yes | Continuation §2.3's table and notes; section retitled "Frame rows: states, windows, waits, frames". The table's `:parked-ref`/`:env`/`:bindings` rows are present as the design prints them (they also stand in §5.1; the notes say "unchanged (§5.1)"). |
| R3 | carrier §5.1 `:reified`, `:parked-ref`, following paragraph | yes | All four replacements. |
| R4 | carrier §5.4 `:parked-table` | yes | Slot list and the added validation paragraph. |
| R5 | carrier §5.5 root, `requires` comment | yes | Root replaced by continuation §2.6 (free-env-cref, one profile map, `{A canonical-vector}` "v4", `requires … plus :yin.k/trees`), with its "no layout slot, no native image" note. |
| R6 | carrier §8 step 7 | yes | Replaced ("Frames.", `frame/defect`, after step 6, before step 8). |
| R6a | carrier §8 step 1 | yes | "against the one profile map of §2". |
| R7 | carrier `:63-68`, §10.4 | yes | Stamp paragraph deleted with §2 (R1); §10.4 replaced ("Contract stamp … neither names a legacy value"). |
| R8 | carrier §9 "Legacy correspondence", §10.3, §10.8 | yes | Legacy law deleted (with its "Exact mutual inversion" sentence); §10.3 reduced to "Rows are the carrier authority."; §10.8 deleted. The §9 closing sentence "During transition the Stage D/E corpus, re-encoded through the canonical projection" adjusted to "re-encoded as version-3 rows" since the nested projection no longer exists. |
| R9 | carrier §10.6, §10.7, §2 `:70-81`, proposals list, "Still open", §11 | yes | §10.6 reworded as given (list of what a profile covers kept); §10.7 deleted; `:70-81` went with §2; proposals: "semantic-only initial scope" removed, "one engine-less profile" and "the explicit root `free-env`" added; "Still open" → work-bound limits only; §11 "Code identity (A), safepoints,". A one-line note records that answers 7 and 8 were deleted so the numbering 1-6, 9, 10 stays explicable. |
| R10 | carrier §5.5 custody paragraph | yes | Replaced. |
| R11 | carrier §3 | yes (kept verbatim) | Including "body version 3 uses BLAKE3 permanently" and the per-id algorithm check. Its `:segment/sha256-…` mention is the self-describing-address rule and was left, per R11. |
| C1 | code-as-tuples §7.2 | yes | First paragraph, obligation 2's second sentence, obligation 3's "both paths refuse with the same defect"; obligations 1 and 4 untouched. Heading "Conformance: both paths, one validator, one image" → "Conformance: one validator, one image" and "Both paths invoke" → "All lanes invoke" (context). |
| banner | carrier draft | yes | Amendment banner under the status paragraph: supersedes stale §5.2, §8 step 7 and the "v3 stamp does not move" positioning; lists every amended section. |
| digest | UCF prose | yes | All eight `:segment/sha256-…` spellings → `:segment/blake3-…`; §7.3.2's "a `:segment/sha256-<hex>` keyword" → BLAKE3 as Jing's default digest, algorithm named in the keyword and verified per id. `:yin.k.pp/sha256-…` (a profile record address) and §7.11.1's "a `:sha256` address still verifies under its named algorithm" (the per-id algorithm test) left. |
| headers | all five | yes | Frozen design: "Amended 2026-10-11 …" paragraph after the 2026-10-10 one. UCF: r8 entry in the status record. Carrier: banner (above). Code-as-tuples: amendment sentence in the status paragraph. Linker: amendment paragraph after the status paragraph. |

## 2. Grep verification

Inventory grep over the five amended documents (pattern: `stack-cref`,
`stack-base`, `:regs`, `does not translate`, `vector → datoms`,
`projection path`, `v3 stamp`, `semantic-only`, `semantic profile only`,
`legacy reader`, `byte-identity`, `bytes held`, `two paths`, `both paths`,
`:segment/sha256`). Every hit is one of:

- a sentence that *names* the removed thing as removed (frozen §4.2
  "`:yin.k/val`, `:yin.k/stack` and `:stack-base` are gone", "`:stack`
  and `stack-base` are removed"; UCF §7.4.1 the same; the carrier banner
  listing what it supersedes; header records);
- the frozen design's §2.3 operand kind `:regs` (an instruction-table
  operand class, not the stack-base row), legitimate;
- linker §4 (`:1378`) `:stack-base` as the de Bruijn stack kernel's
  native frame field, not a UCF row, legitimate;
- carrier §3 `:segment/sha256-…` in the self-describing-address rule,
  kept under R11;
- residuals outside the amended sections, listed in §4 below.

Carrier-specific grep (`legacy`, `version 0`, `versions 0`, `version-0`,
`registry`, `semantic profile`, `semantic-only`, `translate`, `do not
move`, `regs`, `:stack`, `other profile`, `unchanged`): the operative hits
are gone. Remaining: the banner and §2's "no legacy" ruling text;
`dao.jing.hash-registry.md` (a file name); "unchanged" in the §2.3 table
notes and §3/§5.5 where it means UCF rules carried over; §10's note that
answers 7-8 were deleted.

Carrier §6 named `:stack` in two lists of ordered rows (class encoding
and positions); the kind no longer exists and was removed from both
lists. §9's "in the semantic profile" became "under the one profile of
§2". Both are context adjustments; no rule changed.

## 3. Reconciliations

1. **UCF §7.3.4, the `:511` sentence.** Both derivations §9.2 (UCF row)
   and continuation U1 replace the same sentence. U1's text is the
   stronger statement of what is carried (register-shaped vectors by A,
   never batches, datoms or native images; `:yin.k/trees` non-empty) and
   was placed first; the §9.2 row's resolution-order, merge-on-arrival,
   `:yin.k/missing :yin.k/trees`, and "fetch outcome, not missing
   association" sentences follow it. Nothing from either was dropped.
   U5 additionally asks for the §9.2 text verbatim in a new §7.6.1a with
   the completeness clause; it is there too, so the association rule is
   stated at the code-reference site and at the requires site, which is
   what the two designs together ask for.
2. **UCF §7.11 harness item.** U6 replaces the `lift ∘ lower` clause but
   the same bullet ended with "the §7.3.4 projection/direct-path
   image-equality assertion", which U1 retires. Reworded to "the §7.3.4
   image-equality assertion (the two AST projection lanes and the direct
   vector load yield one image and address)", the obligation U1 states.
3. **Frozen §10 phase 6 gate (F4).** F4's inserted parenthetical would
   have nested inside the existing one; merged into a single
   parenthetical with every clause present (see table).
4. **Frozen §9 "Notational or focused" and §12.** Both referred to the
   old §8.4 byte test ("only if their contracts survive §8.4 (otherwise
   they are rewrites under a new version)"; "the de Bruijn descriptors
   (pending §8.4)"). F10/F11/F13 leave nothing in §8.4 to survive, so
   these were adjusted to the F11 stance: contracts keep their names and
   versions, lowerers change in place, descriptors lose only
   `:dim/lift-to` at cutover. Not named in the inventories; recorded
   here as context repairs.
5. **Carrier §10 numbering (R8/R9).** Deleting answers 7 and 8 leaves
   1-6, 9, 10; a one-line note under "Answers 7–9" says why, instead of
   renumbering (renumbering would break the designs' `§10.9`/`§10.10`
   references).

## 4. Not applied, or left for a decision

Nothing in the two inventories is unapplied. The following stale
vocabulary survives **outside** the amended sections and was not named
by either design; I did not amend it, to avoid guessing scope:

- **code-as-tuples §7.1** (`:1052`, `:1093-1095`) still calls datom-batch
  loading "the projection path" and says a `:yin.code/*` datom batch "is
  decoded to a vector by UCF §7.3.4's projection rule"; the **§10 impact
  table** (`:2008-2011`) says `well-formed?` runs "on the projection path
  before projection" and "both paths call the §7.5 validator". C1 scoped
  itself to §7.2. The datoms→vector direction is the datom AST lane and
  is still real; only the name clashes with the retired vector→datoms
  path. Suggest a one-word rename ("datom lane") if the owner wants it.
- **code-as-tuples §1 table and §2 example** (`:55`, `:175`) show
  `:segment/sha256-…` row ids. The digest instruction covered UCF prose
  only.
- **UCF §7.11.1 code-identity matrix** (`:2849`, `:2974`): "Verify the
  published `v2` decision in both direct and projected loads" and "the
  falsy-prefix case either normalizes identically in both paths or is
  refused". This is the historical identity-gate acceptance text; U1
  retires the projected load. Suggest rewording to the two AST lanes
  when §7.11.1 is next revised.
- **UCF §7.3.3** ("The current revision is "v3" … superseded "v2"") and
  the §7.2 / §7.2.1 `"v3"` stamps are covered by U7's scope statement
  (the stack-shaped machine's stamp; `"v4"` is the register-shaped one)
  and were not edited, as U7 directs.
- **Carrier §7** "Occurrence and origin are inside the hash, as in
  versions 1 and 2 where the body address is the hash of the whole body":
  a historical comparison, not a reader requirement; left.

## 5. Verification of "with changes"

Each ACCEPT WITH CHANGES item was checked against the design's own
statement of the change before applying: F8 (total scan order kept,
liveness over actual successors), F10 (precise executable coverage
counts), F11 (descriptors regenerated when the lift declaration goes),
F12 (corrected gates, complete scanner text), F13 (descriptor-dependent
checksum changes, no H-equality claim), F14 (descriptor cleanup, index and
record migration, replacement coverage before deletion), F2 (task context
never an engine), F3 (both kernels' layouts, tail-completion seam), U1
(one load path), U3 (window/`K` wording, `def`/`use`/`live`), U6 (three
engines, re-emission equality), R3 (`:captured` context, `pc−1`), R4
(decoded task context), R5 (free-env, `:yin.k/trees`), R6 (after step 6,
before step 8), R8/R9 (no legacy reader, no other profile tables, the
three repository lanes need no value profile). All are present in the
applied text.

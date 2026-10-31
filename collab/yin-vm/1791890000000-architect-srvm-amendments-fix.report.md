Completed-GMT: 2026-10-11 06:27:20 GMT
Completed-Local: 2026-10-11 13:27:20 Asia/Bangkok

# Report: codex's residual inconsistencies fixed in the amendment application

Worktree `datomworld-srvm-amend`, branch `srvm-amend`, on top of
`695c3ec0`. Documents only; nothing staged or committed. Files edited:
`yin.vm.semantic-register-vm.md`, `yin.vm.universal-continuation-format.md`,
`yin.vm.ucf-transport-tuples.md`, `yin.vm.code-as-tuples.md`, and the one
permitted edit to `yin.vm.semantic-register-vm.continuation.md` (A7 → A2
at `:250`). `yin.vm.linker.md` needed no change. (`git status`/`git diff`
were refused by the sandbox in this session; the file list is from the
edit log.)

Every claim of codex's was checked against the sources before editing:
frozen §7 (`:784-792`) did say "two load paths, projection to datoms with
eid `(inc pc)`"; UCF §7.3.2 (`:368-475`) did specify the stack-shaped
vector, the §2.4 opcode table and batch-loader normalization with no
precedence note; carrier step 7 (`:671-678`) invoked `frame/defect` only;
carrier step 9 (`:683-685`) and UCF `:1459-1460` listed no free
environment as a root; code-as-tuples `:1052`, `:1093-1098`, `:2008-2011`
and UCF `:2956`, `:2974` kept the projected vector load; rule A7 exists in
neither continuation §3.2 (rules are S1-S4, C1-C3, A1-A6, T1, K1-K3,
E1-E2, X1, P1-P4) nor anywhere else, and A2 is the destination rule;
frozen §8.4 cited `§6.1.3`/`§6.1.4` unqualified while the frozen design
has no §6.1.x; code-as-tuples `:55` and `:175` spelled `:segment/sha256-…`.
Continuation §3.3 does require `frame/task-defect` first per body; §7.1
does list the free environment in the lift walk; §2.6's root has
`free-env-cref`; derivations §6.1.3/§6.1.4 exist.

## 1. HIGH: frozen §7 and UCF §7.3.2

**Frozen §7** (`:784`). The sentence "Everything UCF §7.3 says about A
(content address of the loader-resolved interpretation, two load paths,
projection to datoms with eid `(inc pc)`) stands with the new table
substituted" is replaced. It now says what stands (A is the content
address of one resolved instruction stream, computed over the vector
alone, Jing's default digest, keyed per stamp), what does not (no "two
load paths", no vector→datoms projection; one direct load, two AST lanes
produce the vector, pointing at §3.4 "One vector validator" and amended
UCF §7.3.4), and that for `"v4"` the canonical vector is §2.3 minted by
§3.3 and validated by §3.4, UCF §7.3.2's stack-shaped vector being the
`"v3"` machine's and historical. "A is the only code identity; H and R
are checksums" kept.

**UCF §7.3.2** (`:368`). A **Precedence (2026-10-11)** paragraph opens the
section: for `"v4"` the canonical vector and validator are the frozen
design's §§2.3/3.3/3.4 (`yin.vm.semantic-register.code/well-formed?`),
taking precedence over this section wherever they differ, without further
amendment; what survives for `"v4"` is the identity layer (address =
`segment-key` over the vector alone, header folds away, provenance and
hash excluded, no ordering, equal addresses = equal resolved streams);
the stack-shaped vector, the §2.4 opcode table, the batch-loader resolved
interpretation, saturation from a batch and the normalization gate are
**historical** (the `"v3"` machine, leaving at frozen §10 phase 8), and a
`"v4"` vector is never computed from a datom batch by the procedure
below. The body of §7.3.2 is otherwise untouched, matching how U7 handles
§7.2.1 (scope note at the head, clauses left as they stand).

Records: the frozen header's "Amended 2026-10-11" paragraph and UCF's r8
status entry both name the §7 and §7.3.2 changes.

Verification: grep of the five documents for `two load paths`, `two
paths`, `projection path`, `projected load`, `vector→datoms`; see §6.

## 2. MEDIUM: carrier step 7, `frame/task-defect`

Carrier §8 step 7 (`:671`) now reads: for each body (root and every
install child), first decode its task context and judge it once by
`yin.vm.semantic-register.frame/task-defect` (continuation §3.2 rule X1,
summarised), running for every body including a `:halted` body with no
frames "since no frame rule establishes X1"; then every `:act`, `:tail`,
`:kframe`, `:reified` and parked-table state through `frame/defect` with
its context and that task context; a defect of either function is
`:yin.k/undecodable` with kind and row path, citing continuation §3.3.
Order (after step 6, before step 8) unchanged. Verification: wording
matches continuation §3.3 `:449-453` ("judged first by
`frame/task-defect` (X1), then every …"); the carrier banner records the
completion.

## 3. HIGH: free environment in the census roots

- **Carrier §8 step 9** (`:683`): root list is now "waits, pendings and
  envelopes, parked records, the initial free environment `free-env`,
  store, module stores, result, children", with the reason (a value
  reachable only through it can carry closures, streams and primitives,
  continuation §7.1). Exact agreement kept ("agree with it exactly: a
  declared segment not reached or a reached segment not declared is
  `:yin.k/undecodable`"), and the tree-association check made explicit
  at the same site: "every reached segment has a non-empty `:yin.k/trees`
  entry (UCF §7.6.1a)". Continuation §7.2 says the closure checks
  "`:yin.k/trees` complete per §6.1", so this is the design's own
  requirement stated where the census is computed.
- **Carrier §9 lift walk** (`:716`): the same class of omission one
  paragraph later; "the initial free environment (continuation §6.3,
  §7.1)" added to the lift's walk list so lift and lower roots agree.
  Not named by codex; recorded here as the consistency the finding asks
  for ("exact census agreement").
- **UCF §7.6.1 fixed point** (`:1459`): roots now include "the task's
  initial free environment (the root's `free-env`, carrier §5.5; …
  continuation §7.1)", plus one sentence that the row carrier's census
  (§8 step 9) is computed from the same roots and must agree with
  `:yin.k/requires` exactly. The work-item machinery below is untouched.

## 4. MEDIUM: retired vector projection in code-as-tuples and the UCF matrix

- **Code-as-tuples §6.5** (`:1052`): "is the *projection path* of §7.1"
  → "is the *datom lane* of §7.1 (an AST lane; the vector-to-datoms
  projection of instruction vectors is retired, §7.2)".
- **Code-as-tuples §7.1** (`:1093-1098`): the paragraph is rewritten as
  the **datom lane** (second AST lane): `:yin/*` datoms → rows (§9.1
  codec) → row lane. The `:yin.code/*` datom sentence ("decoded to a
  vector by UCF §7.3.4's projection rule") is removed and replaced by
  the statement that there is no datom lane for instruction vectors: a
  `:yin.code/*` batch is not a load input, a vector loads only directly
  under its address and stamp, so the loads are exactly three (direct
  vector load, row lane, datom lane). The §7.1 table (walker / semantic
  vector / semantic row set) stands; it already had no projected row.
- **Code-as-tuples §10 impact table** (`:2008-2011`): the
  `code/well-formed?` row no longer says it "keeps judging datom batches
  on the projection path before projection" with the vector validator
  "on both paths after it"; it now says the datom-batch judgement goes
  with the retired projection and the one §7.5 validator runs on the
  direct load and on the vector either AST lane produces. The
  `semantic/load-image` row's "both paths call the §7.5 validator" →
  "the direct vector load and both AST lanes call the §7.5 validator".
- **UCF §7.11.1 code-identity matrix** (`:2956`, `:2974`): "Verify the
  published `v2` decision in both direct and projected loads" → on the
  direct vector load and on the vector each of the two AST lanes
  produces, "there is no projected vector load (§7.3.4)"; "normalizes
  identically in both paths" → "on the direct load and on both AST
  lanes". The rest of the matrix (hash/grammar refusals, `:sha256`
  per-id algorithm test) unchanged.
- Code-as-tuples' status paragraph records the §6.5/§7.1/§10 wording.

## 5. LOW: references and digest spellings

- **A7 → A2**: carrier §5.2 (`:313`) now "continuation §3.2 rule A2";
  continuation §2.3 (`:250`) "rule A2". A2 is "`(:yin.k/rd deliver) =
  (code/rd inst(pc−1))`", exactly the `rd`-redundancy the sentence
  describes. Nothing else in the continuation design touched.
- **Frozen §8.4**: "§6.1.3:" and "§6.1.4:" → "derivations design §6.1.3"
  / "derivations design §6.1.4", matching the paragraph's other
  references (§6.1.1, §6.1.2, §6.3, §8.1 were already qualified).
- **Code-as-tuples** `:55` (§1 table, Refs row) and `:175` (§2 example)
  `:segment/sha256-…` → `:segment/blake3-…`. No other `sha256` remains
  in that document.

## 6. Residue grep after the fixes

Pattern over the five amended documents: `stack-cref`, `stack-base`,
`:regs`, `regs-cref`, `:yin.k/registers`, `stack-effect`, `does not
translate`, `vector → datoms`, `vector→datoms`, `projection path`,
`projected load`, `v3 stamp`, `semantic-only`, `semantic profile only`,
`legacy reader`, `byte-identity`, `bytes held`, `two paths`, `both
paths`, `two load paths`, `:segment/sha256`, `A7`. Every remaining hit is
one of these legitimate classes:

| class | hits |
|---|---|
| names the removed thing as removed, or an amendment record | frozen `:28` (header: "replaces the byte-identity question"), `:606-613` (§3.4 "retired the vector→datoms projection", "the 'two load paths' … take their direct-vector form"), `:685`, `:718` ("are gone"/"are removed"), `:727`, `:832` (replace `stack-effect`), `:792` (§7 "there are no 'two load paths'"), `:1035` ("vector→datoms equivalence is retired"); UCF `:62`, `:64` (r8 record), `:391` (§7.3.2 precedence note), `:594-595` (§7.3.4 "is retired"), `:709` ("are gone"), `:853` (U4: "`:yin.k/registers R` of the version-2 amendment is the `act`/`tail` state"); carrier `:25-26` (banner); code-as-tuples `:34`, `:1108` ("is retired") |
| U7 historical clauses (stack-shaped machine) | UCF `:236` (§7.2 envelope example `:yin.k/registers`, superseded for version 3 by the carrier's wire unit per the §7.2.1 scope statement); UCF `:448` (§7.3.2 normalization gate "both paths", now inside the section the precedence note marks historical) |
| native stack-kernel `stack-base` | linker `:1378` (the de Bruijn stack kernel's frame field) |
| instruction operand kind `:regs` | frozen `:151` (§2.3 operand class) |
| R11 self-describing-address rule | carrier `:124` (`:segment/sha256-…` named as an algorithm a reader must verify, under "body version 3 uses BLAKE3 permanently") |

The predecessor's §4 "not applied" list is now fully resolved except the
two items it deliberately left as U7-covered or historical (UCF §7.3.3's
`"v3"` revision text; carrier §7's "as in versions 1 and 2" comparison),
which codex also classed as legitimate.

## 7. Disputed or unfixed

Nothing disputed. One scope note: codex's item 3 named carrier `:683` and
UCF `:1459`; I also added the free environment to the carrier §9 lift
walk (`:716`), since a census computed on lift from one root set and
checked on lower against another cannot "agree exactly". If the reviewer
prefers the lift list left as the design printed it, that is a one-clause
revert.

I’ll check the revised precedence clauses and the new linker amendment against I5, I8, and §5.5, keeping the review read-only.
Completed-GMT: 2026-10-09 18:19:37 GMT
Completed-Local: 2026-10-10 01:19:37 Asia/Bangkok

The round-2 H/R findings are covered. One gap remains in the linker amendment’s extension to the AST walker.

| Severity | file:line | evidence | correction |
|---|---|---|---|
| High — specification gap | `docs/design/yin.vm.linker.md:211` | “Every backend: A” includes `:yin.ast/code`, but the precedence clause at line 206 only supersedes H/R statements. §5.1 at line 685 still uses the AST root address as executable format identity. The proposed A → native-image derivation also assumes an inverse from A to the canonical AST that has not been specified. | Explicitly supersede the walker’s root-address **executable identity**, while retaining AST row addresses as syntax-content addresses. For walker admission, fetch the tree by its storage address, validate it, and verify that lowering it under the declared contract produces A. Do not require reconstruction of the tree from A. |

**A. Coverage:** Complete against the round-2 table. Blanket precedence reaches naming claims in acceptance criteria and test-oracle descriptions as well as ordinary prose. T8 and its reconstruction/scheduling requirements are explicitly retired. Checksum goldens and equality laws remain valid.

The remaining issue above concerns a newly generalized claim, rather than an omitted H/R instance.

**B. Linker consistency:** The amended procedure preserves I5’s two obligations: verify retrieved content at its storage address, then verify its relationship to the requested A. Requiring verified A before admission strengthens I8 appropriately.

Generalized §5.5 is sound for stack/register lowerings. For the walker, the verification direction must be **tree → A**, as described above. Trusted derivation records cannot replace the mandatory A/native relationship check.

`[A f] → storage address` is suitable for a native-artifact index **within a fixed semantic and lowering contract**. Contract revisions must remain explicit in the surrounding registry/state, or become part of the key when revisions coexist. For the semantic format, the storage address is A itself. The canonical vector must remain obtainable independently of an optional native-image entry.

**C. Design pre-emption:** No block fixes register numbering, windows, allocation, stackification, or result delivery. The revised targets wording correctly avoids assuming lowering simplicity. The walker inverse assumption is the only unsupported derivation introduced; correcting its direction avoids pre-empting another design.

**D. Owner’s reading:** Confirmed. “One truth, many interpretations” is preserved. The Universal AST remains the source truth; A provides canonical executable identity; the VMs interpret derived representations. Independent sibling H/R lowerings are consistent with that principle.

Read-only review; no files edited.

VERDICT: CHANGES_REQUESTED

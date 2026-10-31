I’ll inspect the commit and four amended files, then check the referenced linker, R5, and identity contracts for statements the amendments leave in force.
The amendments capture the ruling, but their sentence-specific scope leaves several H/R naming contracts active: B7 requests and SCC ordering, register continuation payloads, R5’s R index, and repeated target cache keys. The refusal posture is sound; D14 also needs to require verification that the held native image is the specified lowering of A.
Completed-GMT: 2026-10-09 18:15:42 GMT
Completed-Local: 2026-10-10 01:15:42 Asia/Bangkok

The ruling is captured correctly, but coverage is incomplete. Because the blocks explicitly supersede named sentences rather than establishing blanket precedence, the remaining H/R naming contracts need explicit coverage. No files edited.

| Severity | file:line | evidence | correction |
|---|---|---|---|
| High | `docs/design/yin.vm.debruijn.stack.md:843` | §7.2 still names H as request key; lines 861–869 retain the H index, `:call-hash H`, and requests carrying H. Line 884 retains fetch-by-H acceptance. | Explicitly supersede §7.2’s naming/index/request/completion rules with A. |
| High | `docs/design/yin.vm.debruijn.stack.md:874` | B7 orders SCC members by image hash before computing a component hash. D5 at line 926 names definition units with `:call-hash H`; D16 at line 966 retains H-to-address indexes. | Supersede D5/D16 and the B7 identity/ordering contract. Defer the replacement SCC-unit grammar to its design; do not invent another executable identity. |
| High | `docs/design/yin.vm.debruijn.stack.md:541` | Parked and reified payloads carry H as image identity; restore checks it at line 552. | State that A identifies captured code; native checksums may remain integrity checks under the selected lowering contract. |
| Medium | `docs/design/yin.vm.debruijn.stack.md:241` | H remains called “code-image identity”; lines 250–252 preserve H-keyed caches, and line 354 calls H/R the only executable identities. | Explicitly cover these statements. Distinguish optional checksum-based deduplication from authoritative A-keyed derivation caches and admission. |
| High | `docs/design/yin.vm.debruijn.register.md:51` | Independently addressable H/R remains active. Invariant 3 at line 106 and DECIDED 2 at line 1123 retain peer executable-format identities. | Add these sections/decisions to the supersession list; retain checksum equality laws. |
| High | `docs/design/yin.vm.debruijn.register.md:151` | A receiver verifies fetched bytes “with R alone.” §3 at line 319 retains R as executable-format identity and describes verification followed by execution without A. | Explicitly require verified A and verification of the specified lowering relationship before execution. |
| High | `docs/design/yin.vm.debruijn.register.md:688` | §5.1 embeds `{:format … :hash H/R}` as captured image identity and requires image fetch by R/H at line 728. | Supersede the identity/fetch clauses. Preserve native same-model restore constraints until the new continuation design replaces them. |
| High | `docs/design/yin.vm.debruijn.register.md:1077` | R5 still specifies an R index and same-root pairing datoms. The end condition at line 1162 requires cross-stream R fetch. | Name R5 and §9 explicitly in the amendment; replace authoritative R indexing/fetch with A. |
| Medium | `docs/design/yin.vm.debruijn.targets.md:70` | Direction B executes an image “with identity R”; the consequences paragraph at line 322 still assigns serving to H and verification identity to R. | Explicitly supersede these repeated identity claims. |
| High | `docs/design/yin.vm.debruijn.targets.md:1130` | T-D5 and T-D11 at line 1153 retain `(R, target, emitter-version)` keys. Level-2 requirements at lines 1099 and 1181 retain fetch-by-R. | Name T-D5/T-D11 and the Level-2 requirements explicitly; use A plus target/profile and version. |
| Medium | `docs/design/yin.vm.debruijn.targets.md:556` | Artifact reconstruction still uses H/R and `raise`; T8 and the open question at line 1248 remain active despite T-D12’s retirement. | Explicitly retire the dependent reconstruction path, T8, and its scheduling question. |
| High | `docs/design/yin.vm.universal-continuation-format.v2-amendment.md:219` | The amendment changes code-map keys for later bodies but leaves §4.1’s native-identity layouts and “rehash … compare its identity” rule without a replacement interpretation. | State that later layouts name A; offsets derive from native lowerings, while H/R verify native components. Keep exact future row grammar deferred. |
| High | `docs/design/yin.vm.linker.md:172` | The authoritative linker contract still distinguishes format identity from storage address; §4 specifies H/R identity checks and indexes. R5 explicitly delegates to it. | Add a coordinated dated amendment before this identity-contract change merges. Detailed implementation may follow. |

**A. Completeness:** No. The table lists the missed naming contracts, including §7.2 and R5. The amendment technique is suitable, but its coverage must include repeated decisions and acceptance criteria—not only their introductory statements.

The mutual-independence sentence also needs precision: **H and R can still be independent sibling lowerings of A.** Deriving both from A does not make either native image derived from the other. Supersede the old upstream topology without declaring that mathematical relationship false.

**B. Refusal posture:** Yes. A kernel with only native bytes identified by H/R and no verified A available must refuse executable admission.

“Reachable” should mean **obtainable and verified before admission**, whether already held, carried, or fetched. A claimed address or pairing datom is insufficient.

D8/D14’s integrity principle survives, but their old procedure needs amendment:

1. Verify the canonical vector against A and validate its contract.
2. Derive the native image, or verify held native bytes against that deterministic lowering.
3. Apply native validation and receiver closure checks.

Checking a received image against its own H/R proves integrity of that image, not its relationship to A. If native bytes travel separately, their transport integrity checks still apply.

**C. One truth, many interpretations:** Yes. The Universal AST remains the source truth; the evaluators remain interpretations. Axiom 2 does not require a particular compiler topology. The owner’s ruling faithfully permits A-derived native images while retaining independent evaluators.

The amendment should distinguish the superseded prohibition on compiling from another projection from the still-valid independence of the H and R lowering branches.

**D. Pre-emption:** The blocks appropriately leave virtual-register shape, numbering, allocation, stackification, liveness, and resume destinations open.

Two phrases need qualification:

- Targets line 22 anticipates a “near-direct derivation.” That simplicity is unproven; call it a forthcoming A-derived lowering.
- The UCF block treats later-body identity migration as decided, which is appropriate, but must explicitly cover layout identity without prematurely fixing other-profile row shapes.

Also clarify that preserved version-2 bytes describe the historical contract, not continuing authorization for A-less executable admission under the new contract. No byte rewriting or compatibility shim is needed.

**E. Other documents:** The authoritative **linker** needs the same precedence amendment before merge. A short cross-reference is enough; a full rewrite can follow.

DHT/pipeline documents need simultaneous markers wherever they concretely name H/R as fetch keys, publication outputs, pins, or dependency identities. Generic content-addressed transport, signed index heads, and stream boundaries remain valid and need no wholesale rewrite.

UCF parent §7.3.4 and code-as-tuples §5 already identify the canonical vector by A. They can receive cross-references later; their register-shape changes belong to the forthcoming design. AST row addresses remain syntax-content addresses, not competing executable identities.

VERDICT: CHANGES_REQUESTED

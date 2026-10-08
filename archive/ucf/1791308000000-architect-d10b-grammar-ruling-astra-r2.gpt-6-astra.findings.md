The engineer is right that this needs a wire-grammar amendment. The register kernel restores sparse `:regs` plus a destination and resume mode, and stack/register PCs depend on the source image layout. The walker already has content-addressed rows, so it is liftable in principle, but its AST continuation needs its own closed grammar. I’ll separate that amendment from implementation and preserve the existing code stamps.


Completed-GMT: 2026-10-06 17:00:07 GMT
Completed-Local: 2026-10-07 00:00:07 +0700

**Ruling: split D10b into a published grammar amendment followed by implementation. Preserve the existing kernel code contracts and semantic version-0/version-1 bytes. The walker remains required; D16 remains blocked until all four kernels restore successfully.**

The engineer correctly stopped. The existing UCF grammar explicitly requires semantic `{segment pc env stack k}` registers and canonical-vector code. Adding other representations is a contract change, not merely generalizing dispatch. Two details need correction: register continuations carry **sparse `:regs`, `:live`, `:dest` and `:resume-mode`**, rather than a running machine’s dense `:registers`; and the walker already has addressed rows and an attachment seam. See `debruijn_register_effects.cljc:84`, `ast_walker.cljc:968` and `ast_walker.cljc:1186`.

### 1. Profile declaration and compatibility

**Use a new handoff body version 2. Do not extend versions 0 or 1 with optional kernel fields.** Their published grammar and fixture bytes remain unchanged.

Put the execution-profile declaration in the existing `:yin.k/contract`, not in cursor requirements or a second independent profile field. For version 2, its machine-facing shape is:

```clojure
{:yin.k/engine <published-kernel-identifier>
 :yin.code/contract <that-kernel's-existing-contract>
 :yin.k/version 1} ; continuation-contract revision, not body version
```

The amendment must enumerate the four engine identifiers and their exact supported contract combinations. The existing semantic `ucf/contract-stamp` remains unchanged for legacy bodies; introduce a separate version-2 profile registry.

Version 2 supports both fork and exclusive handoff. Derive that distinction from the complete custody-header arm: absent means fork; present means exclusive; a partial header is malformed. Do not add a redundant mode field. The amendment must enumerate that arm and nested-install rules explicitly, preserving the existing ownership distinction between root custody and child gating.

Reader order is: body-version gate, supported execution-profile gate throughout the tree, then structural/address validation, custody admission where applicable, and restoration. Unsupported versions or profiles return `:yin.k/profile-mismatch` before attachment or restoration. Supported but malformed bodies receive the existing structural diagnostics. No implicit conversion or upgrade.

This changes the **continuation wire contract**, not semantic `"v3"`, stack `"b2"`, register `"r2"`, AST code semantics, or module-manifest schema. Old readers reject version 2 at their existing version gate.

### 2. Code and register shapes

**Keep each kernel’s addressed code in its own published format and hash namespace.** The enclosing execution profile determines how `:yin.k/code` is validated:

| Kernel | Addressed payload |
|---|---|
| Semantic | Existing canonical vector and `ucf/code-address` |
| Stack | Admitted image vector and `dcode/image-hash` |
| Register | Existing `{:bodies … :instructions …}` image and `rcode/register-hash` |
| Walker | Content-addressed AST rows, validating each row identity and reference closure |

For the walker, do not invent a hash of an arbitrary row-map enumeration. Its module image identity is the root row, while held code includes the referenced rows; preserve that distinction.

The register wire arms must represent:

- **Stack:** resume PC, lexical frames, operand stack, return continuation, image identity, and optional module-store association.
- **Register:** site PC, resume PC, lexical frames, sparse ordered register pairs, live-register indices, return continuation, destination, resume mode, image identity, and optional module-store association.
- **Walker:** environment and a closed, recursively defined AST-continuation grammar. Static AST references use row addresses; evaluated operands and other dynamic frame values use the ordinary value codec. Modified evaluator frames cannot simply be replaced wholesale by their original AST row.

All guest values—including values nested inside continuation frames—pass through the shared value/cell encoding and dependency census. No raw host records, functions or metadata are portable payloads.

**Image layout is an additional required contract.** Stack attachment relocates instructions and preserves absolute PCs; restore assumes the receiver already has the correct code space (`stack.cljc:176–186`, `370–425`). Therefore version 2 must carry each task’s ordered image-address layout. Lower rebuilds that source layout in isolation, deriving offsets and aggregate hashes using the existing attachment rules. It must not sort images by address or inherit receiver offsets. Historical continuation layouts that differ from the current task layout must also be represented and validated where required.

The amendment must enumerate the complete frame arms and layout references before implementation. “Serialize the payload map” is not an adequate grammar.

### 3. Eligibility and the walker

**Eligibility is profile-specific; the common engine safepoint obligations remain unchanged.**

- **Semantic:** retain the existing derived safepoint rules.
- **Stack:** derive boundary sites and permitted pending reasons from the admitted instruction image and existing opcode semantics. Validate resume PCs, image membership, frame/return locations and pending-kind compatibility.
- **Register:** use the existing boundary set and `continuation-defect` rules, including site/resume-PC relationship, exact in-band liveness, sparse register indices, destination and tail-return mode. Then check pending-kind compatibility. The existing validator already supplies much of this contract (`debruijn_register_effects.cljc:280`).
- **Walker:** validate a structural resumption point rather than inventing a PC. Require a recognized engine-retained wait or explicit parked record, a valid continuation-frame chain, valid addressed AST dependencies and compatible pending state. Arbitrary running CESK states are outside the liftable set.

For every profile, halted roots still require complete result dependency encoding; installs require complete child state; ordered waits remain ordered. All existing custody holds continue to prohibit export. A valid continuation alone does not establish a liftable task.

**The walker is liftable and remains in scope.** Its tree representation requires a distinct grammar, not exclusion from portability. Unknown continuation-frame variants must fail closed until specified. No persisted safepoint table is required: derive instruction tables or structural eligibility from admitted code and continuation data.

The register boundary-opcode constraint **stands**. No new `:live` operand, opcode arity, liveness rule or `"r3"` code stamp is authorized.

### 4. Permitted scope

**Widen D10b explicitly.** The amendment and implementation may touch:

- UCF design, revisions and linker milestone documents.
- `ucf.cljc`, `handoff.cljc` and `checkpoint.cljc`.
- The four kernels and their profile-specific code/continuation validators.
- Holder/export integration and compatibility dispatch needed to carry version 2.
- Corresponding tests and new fixtures.

`code.cljc` may gain or expose narrowly scoped validation support, but must retain its semantic grammar and existing hash behavior. Do not make the semantic validator accept other kernels’ images. Shared profile dispatch should call the appropriate validator.

The inspector must traverse the new register arms for dependency, cell and operation-ID checks without executing or attaching anything. Lift’s self-check remains inspector followed by full restoration-grammar validation. All authority and holder version checks must be audited so version 2 cannot bypass custody validation or be accidentally treated as a fork.

### Amended D10b brief delta

1. **D10b-A: publish the version-2 amendment first.** Enumerate profile stamps, fork/exclusive arms, image layouts, register/frame grammars, walker references, eligibility rules, diagnostics and reader ordering. Architect review precedes implementation.
2. **D10b-B: implement both lift and lower against that grammar**, preserving kernel code formats and native restore semantics.
3. Replace “all four kernels under versions 0 and 1” with **legacy semantic versions 0/1 unchanged, plus all four kernels under version 2 in fork and exclusive modes**. Exclusive runnable roots require the existing grant/evidence/protection contract.
4. Add linked-image tests: multiple attachments, continuations captured before later attachment, cross-image returns, module closures and hostile receiver-local layouts. Restoration must preserve execution and observable traces without rerunning initialization.
5. Pin malformed profile/frame/layout rejection before attachment; register liveness tampering; walker missing-row and malformed-frame rejection; complete result/cell/module dependencies; aliasing; operation counters and issue ordering.
6. On JVM, Node and Dart, pin identical prepared-state bytes and addresses within each profile. Cross-kernel byte equality is not required. Existing semantic and ledger fixtures remain byte-identical.
7. D14 and D15 may proceed on the landed semantic path. **D16’s four-kernel gate and stage-D completion remain blocked on D10b-A/B.** Refusal tests cannot substitute for successful restoration.

Read-only review; no files edited and no suites run.
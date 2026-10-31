Created-GMT: 2026-10-07 06:56:40 GMT
Created-Local: 2026-10-07 13:56:40 Asia/Ho_Chi_Minh
Coding-Agent: codex
Session-ID: 01a0f878-281b-7253-ac44-ff2402583d35

# Task: UCF D10b-A: Publish Version-2 Wire Grammar Amendment
Role: Lead System Architect
Implementers:
- Model: gpt-6-astra | Assigned: 1791356200000 | Status: active | Rationale: Author the formal Version-2 handoff wire grammar amendment per the D10b grammar ruling (collab/1791308000000-architect-d10b-grammar-ruling-astra-r2.gpt-6-astra.findings.md).

## Architectural Mandate
Per your ruling in `collab/1791308000000-architect-d10b-grammar-ruling-astra-r2.gpt-6-astra.findings.md`:
"Ruling: split D10b into a published grammar amendment followed by implementation. Preserve the existing kernel code contracts and semantic version-0/version-1 bytes. The walker remains required; D16 remains blocked until all four kernels restore successfully."

Author the complete, unambiguous specification document for Version-2 wire grammar amendment:
`docs/design/yin.vm.universal-continuation-format.v2-amendment.md` (or directly update `docs/design/yin.vm.universal-continuation-format.md` if preferred, maintaining the append-only design trail).

### Required Content in the Amendment
1. **Profile Declaration & Compatibility**:
   - New handoff body version 2. Versions 0 and 1 remain completely unchanged.
   - Execution-profile declaration in `:yin.k/contract`:
     ```clojure
     {:yin.k/engine <published-kernel-identifier>
      :yin.code/contract <that-kernel's-existing-contract>
      :yin.k/version 1}
     ```
   - Enumerate the 4 engine identifiers (`:semantic`, `:stack`, `:register`, `:walker`) and exact supported contract combinations.
   - Fork vs. Exclusive handoff derived strictly from the custody header arm (absent = fork, present = exclusive).
   - Reader gate sequence: body-version gate -> supported execution-profile gate throughout tree -> structural/address validation -> custody admission -> restoration. Diagnostics: `:yin.k/profile-mismatch` on unsupported version/profile.

2. **Code & Register Shapes**:
   - Specific addressed payload and hash namespace for all four kernels:
     - Semantic: canonical vector & `ucf/code-address`
     - Stack: admitted image vector & `dcode/image-hash`
     - Register: `{:bodies ... :instructions ...}` image & `rcode/register-hash`
     - Walker: content-addressed AST rows `{id row}`, validating each row identity and reference closure
   - Wire register/frame arms:
     - Stack: resume PC, lexical frames, operand stack, return continuation, image identity, optional module-store association.
     - Register: site PC, resume PC, lexical frames, sparse ordered register pairs, live-register indices, return continuation, destination, resume mode, image identity, optional module-store association.
     - Walker: environment and closed, recursively defined AST-continuation grammar.
   - Image layout contract: ordered image-address layout in version 2 to support absolute PCs / relocation without sorting or inheriting receiver offsets.

3. **Eligibility & Safepoints**:
   - Profile-specific eligibility rules and safepoint validation for each kernel.
   - Register kernel: no new `:live` operand or opcode arity changes ("r2" format preserved).
   - Walker: structural resumption point validation over recognized engine-retained wait or explicit parked record.

4. **Deliverable**:
   - A fully detailed, unambiguous specification document that the implementation engineer (D10b-B) can implement without further architectural questions.
   - Output summary and review findings directly to `collab/1791356200000-architect-d10b-a-grammar-spec.gpt-6-astra.findings.md`.

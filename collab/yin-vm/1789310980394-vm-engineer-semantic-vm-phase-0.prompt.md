Created-GMT: 2026-09-13 14:49:40 GMT
Created-Local: 2026-09-13 21:49:40 +07
Coding-Agent: claude
Session-ID: 4e46a60f-7a56-4198-adf8-9fe020ce3e37

# Task: semantic-vm-phase-0

Role: VM Engineer

Implementers:
- Model: claude-opus-5 | Assigned: 2026-09-13 21:49:40 +07 | Status: active | Rationale: AST and compiler expertise for the semantic VM foundation.

## Governing design

Read in full before writing a line of code:
- `collab/1789221648668-architect-semantic-vm-v2-design.claude-fable-5-1.findings.md`
  (This is the complete architectural specification for the semantic VM. Focus heavily on §2 and §8 Phase 0.)

## File authority

Primary files (you own these completely):
- `docs/design/yin.vm.semantic.md` (to be created)
- `src/cljc/yin/vm/code.cljc` (to be created)
- `test/yin/vm/code_test.cljc` (to be created)

You will also need to edit:
- `src/cljc/yin/vm.cljc` (for schema, opcode-table, opcase macro)
- `src/cljc/yin/vm/ffi.cljc` (lifting FFI helpers)
- `src/cljc/yin/vm/ast_walker.cljc` (updating it to use the lifted FFI helpers)

You may READ but NOT edit without prior stop-and-request:
- Any other file in `src/cljc/yin/` or `test/yin/`

## Deliverables (§8 Phase 0)

### 1. Promote Design Document
Create `docs/design/yin.vm.semantic.md`. Copy sections §1 through §6 from the findings document (`collab/1789221648668-architect-semantic-vm-v2-design.claude-fable-5-1.findings.md`) directly into it, maintaining the formatting. It must contain the opcode and attribute tables as the contract.

### 2. Update `yin.vm`
- Add `:push 22` and `:halt 23` to `yin.vm/opcode-table` and the `opcase` macro. (Note: opcase contains a hardcoded literal map for cljd host evaluation pass that must also be updated).
- Export a new `code-schema` map in `yin.vm`. This schema defines the linear executable datoms.
  - Value attributes: `:yin.code/type`, `:yin.code/length`, `:yin.code/hash`, `:yin.code/pc`, `:yin.code/op`, `:yin.code/value`, `:yin.code/name`, `:yin.code/params`, `:yin.code/argc`, `:yin.code/tail?`, `:yin.code/prefix`, `:yin.code/key`, `:yin.code/buffer`, `:yin.code/ffi-op`, `:yin.code/parked-id`.
  - Ref attributes: `:yin.code/segment`, `:yin.code/target`, `:yin.code/body`, `:yin.code/source`, `:yin.code/derived-from`.

### 3. FFI Refactoring
- Move `call-result` and `call-response-wait-entry` from `yin.vm.ast-walker` into `yin.vm.ffi`.
- Update `ast_walker.cljc` to call these functions from the `ffi` namespace instead of internally. This must be strictly behaviour-preserving. The walker tests must remain unchanged and green.

### 4. Implement `yin.vm.code/well-formed?`
Create `src/cljc/yin/vm/code.cljc`. Implement `well-formed?` which accepts a batch of `:yin.code/*` datoms (a single segment). It must return `nil` if the segment is valid, or a defect map `{:rule :entity}` (e.g., `{:rule :missing-terminator, :entity 42}`) if it violates any of the §2.6 well-formedness rules:
1. Exactly one entity with `:yin.code/type :segment` per batch.
2. Instruction pcs are exactly `0 .. length-1`, each once.
3. The batch is sorted by pc (so a loader can fill the array in one pass).
4. Every `:yin.code/target` and `:yin.code/body` resolves to an instruction of the same segment.
5. Every basic block ends in a terminator (`:jump`, `:return`, `:halt`) or falls into a labelled successor; pc `length-1` is a terminator.
6. Every `:call`/`:ffi-call` has a non-negative `:yin.code/argc`.

### 5. Tests
Create `test/yin/vm/code_test.cljc`. It must cover each well-formedness rule with a hand-assembled segment (both passing and failing cases for each rule). 

### Invariants
- The walker's behavior must not change. All existing v2 test suites must remain green on all hosts.
- `opcase` macro must work correctly across hosts (especially cljd).
- Do not weaken any existing test.

### Verification
After implementation, run:
```
clj -M:test -n yin.vm-test -n yin.vm.code-test -n yin.vm.ast-walker-test -n yin.vm.ffi-test
clj -M:kondo --lint src/cljc/yin/vm.cljc src/cljc/yin/vm/ffi.cljc src/cljc/yin/vm/ast_walker.cljc src/cljc/yin/vm/code.cljc test/yin/vm/code_test.cljc
git diff --stat
```

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report: changed files, exact test counts, kondo result, unresolved concerns,
and any Phase 0 items that are incomplete (with reason).
Do not claim edits or tests that did not occur.

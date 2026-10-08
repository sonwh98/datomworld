Completed-GMT: 2026-10-07 07:03:00 GMT
Completed-Local: 2026-10-07 14:03:00 Asia/Ho_Chi_Minh
Coding-Agent: codex (gpt-6-astra)
Role: Lead System Architect
Source baseline: 9cea60dd

# D10b-A: version-2 grammar published for implementation

Authored the standalone, append-only normative amendment:
[UCF version 2](../docs/design/yin.vm.universal-continuation-format.v2-amendment.md).
This delivers the grammar phase of the
[D10b ruling](1791308000000-architect-d10b-grammar-ruling-astra-r2.gpt-6-astra.findings.md).
It does not claim D10b-B implementation or completion of the D16 gate.

## Complete rulings

1. Section 1 fixes the closed profile registry: semantic/v3/1,
   stack/b2/1, register/r2/1, walker/v3/1. The outer body version is 2.
   Every install tree is homogeneous. Unsupported profiles fail the
   whole-tree profile gate before structural validation or restoration.
   Versions 0 and 1 retain their existing selection, grammar and bytes
   from the post-D14 baseline.
2. Sections 2 and 3 fix the common body and custody grammar. Custody-arm
   presence alone selects exclusive versus fork behavior. An exclusive
   halted result has the existing origin-only arm. Children inherit
   custody and are not separately admitted. No enrolled set travels.
3. Section 4 preserves each kernel's native code address domain and
   payload. Ordered source image layouts are explicit, including captured
   historical prefixes. Lower reconstructs relocation from those layouts;
   receiver offsets, map order and aggregate hashes cannot substitute for
   component image identities.
4. Section 5 closes the native register/frame grammar: semantic registers
   and return frames; stack registers and return frames; register sparse
   values, live indices, destination and resume mode; all thirteen walker
   continuation variants. Dynamic walker operands and operators are
   encoded values rather than discarded AST metadata.
5. Section 6 fixes shared value/cell encoding, closure markers and the
   version-2 reified/parked frame-value arms. Census includes halted
   results, free environments, module stores, continuation values and
   children before finalizing declarations or code dependencies.
6. Section 7 defines eligibility per profile. Register r2 is unchanged:
   no new boundary operand, opcode arity or live-slot index. Walker uses
   structural retained-wait/explicit-park validation, not an invented PC.
7. Sections 8 and 9 define reader precedence and isolated restoration:
   version, whole-tree profiles, structure and addresses, custody and
   composition evidence, then reconstruction. Refusals before restoration
   perform no attachment or program IO. Exclusive runnable roots still
   require the seven D10 grant checks; body data cannot manufacture tenure.
8. Section 10 assigns D10b-B's file scope and D14 recovery integration.
   The recovery object's version is a separate contract; embedding a v2
   body does not silently turn recovery data into a runnable checkpoint.
9. Section 11 supplies twelve setup/action/assertion acceptance laws over
   all four profiles and three hosts. D16 remains blocked until successful
   restoration replaces temporary non-semantic refusal pins. D14/D15 may
   continue on their established semantic path.

## Source-review findings resolved in the amendment

- Native code contracts are the published constants in
  `src/cljc/yin/vm.cljc`, not inferred from runtime format names.
  The semantic closure marker is `:yin.semantic/code`; stack and register
  use their distinct de Bruijn markers; walker uses `:yin.ast/code`.
- Stack and register image hashes use their existing code encodings.
  Replacing either with a generic CBOR hash would violate the mandate.
  The specification preserves the native hash functions and makes image
  layout an independently validated part of the continuation grammar.
- Register return frames do not carry independent code spaces. Captured
  register payloads carry sparse pairs, not dense native registers.
  The grammar respects `continuation-defect` and existing r2 boundaries.
- Walker retained frames contain more than AST identities. In particular,
  application frames contain evaluated operands and the resolved operator;
  the FFI operand frame also needs reconstruction of its original AST row.
  The closed grammar preserves these fields without encoding host objects.
- Walker FFI response bookkeeping uses parked records as well as retained
  waits. It is not an explicit guest park. Section 7 distinguishes these
  cases and preserves correlation through request-sent and eval-call.
- Parent UCF 7.4.3 already makes install phase and child kind independent.
  All six combinations remain valid when the child is independently
  eligible. A stricter running/halted or parked/blocked pairing would
  incorrectly reject legitimate exports.
- Parent UCF names frame values, while current lowering partly refuses
  them. The amendment supplies their explicit version-2 shape; it does
  not widen the legacy value grammar or claim existing implementation.
- The already-approved D14 transitive-module-store repair is the legacy
  baseline. Preserving versions 0/1 does not mean reinstating incomplete
  exports or their superseded addresses. No further legacy migration is
  authorized by this amendment.

## Validation and disposition

Reviewed the governing ruling, parent UCF, code-as-tuples contracts and
native semantic, stack, register and walker implementations. Checked the
new documents for existing local link targets, paired code fences and
trailing whitespace. No runtime suite was run: this change is a grammar
and acceptance specification, not an implementation.

Only the amendment and this findings file were authored for this task.
No source code, legacy design text, fixture pin or orchestrator-log entry
was changed. No commit was made. No owner decision remains open in the
amendment; implementation must satisfy the explicit grammar and tests.

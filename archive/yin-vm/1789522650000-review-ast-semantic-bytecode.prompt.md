Created-GMT: 2026-09-15 21:17:30 GMT
Created-Local: 2026-09-16 04:17:30 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: codex
Session-ID: pending (provider-generated)
# Task: Adversarial review of yin.vm/ast->semantic-bytecode
Role: Adversarial Review
Implementers:
- Model: gpt-6-astra | Assigned: 2026-09-16 04:17:30 +07 | Status: active | Rationale: cross-family from claude-opus-5 (the implementer), flat-subscription reviewer

## Context

Read first:
- `docs/design/yin.vm.code-as-tuples.md` §2 (the AST tuple grammar, full
  tag table), §2.4 (saturation), §2.5 (exclusions), §4.4 (structural
  sharing), §6.5, §7.2 (round-trip law), §7.4 (row validation rules)
- `collab/1789521800000-vmruntime-ast-semantic-bytecode.prompt.md` (the
  implementation brief)
- `collab/1789521800000-vmruntime-ast-semantic-bytecode.claude-opus-5.findings.md`
  (the implementer's report — verify its claims, don't trust them)

`claude-opus-5` implemented `ast->semantic-bytecode` /
`semantic-bytecode->ast` in `src/cljc/yin/vm.cljc` (lines 563-718,
additive, nothing else modified) plus round-trip corpus tests in
`test/yin/vm_test.cljc` (lines 149-310). This is an uncommitted
working-tree diff (`git diff -- src/cljc/yin/vm.cljc
test/yin/vm_test.cljc`). The orchestrator independently ran
`clojure -M:test -n yin.vm-test` and confirmed 15 tests / 127
assertions / 0 failures.

## Task

Adversarially review the diff. Specifically probe:

1. **Grammar-table fidelity.** Compare `semantic-bytecode-grammar` (v2.cljc
   `:569-590`) field-for-field against the design doc's §2 tag table. Any
   mismatch in tag name, slot order, slot kind, or arity is a correctness
   bug — the doc is authoritative.
2. **Structural sharing correctness (§4.4).** `convert` hashes each node's
   body bottom-up and stores rows in a map keyed by id, throwing on a
   `not=` collision at the same id. Is this collision guard sound? Can you
   construct two DIFFERENT logical nodes that would compute the same body
   (and thus be wrongly merged) given the current `dao.jing/segment-key`
   encoder (now fixed for metadata/set/record collisions, but check
   whether anything in `slot-value`'s conversion — e.g. `mapv` on
   `:operands`, `boolean` coercion on `:tail?` — could make two distinct
   source ASTs project to an identical body)?
3. **Exclusions completeness (§2.5).** `slot-value` only reads fields
   listed in the grammar; everything else on the node map is implicitly
   dropped. Confirm this is actually exhaustive — is there any field that
   SHOULD be excluded per §2.5 (source positions, `:macro?`,
   `:phase-policy`, `:yang/*`, `:eid`) that could somehow still leak into
   a row body? Conversely, is there any field the grammar requires that
   this implementation silently drops or mishandles?
4. **Round-trip law fidelity (§7.2).** Read the actual test corpus
   (`test/yin/vm_test.cljc:149-310`) rather than trusting the
   implementer's summary. Does it genuinely exercise every one of the 18
   tags with meaningfully distinct shapes (not just trivially present)?
   Try to construct a canonical map AST NOT in the corpus that would break
   `map → rows → map` or `rows → map → rows` under this implementation —
   an edge case in saturation, nesting, or a slot kind interaction.
5. **Reconstruction validation gaps.** The implementer's own findings
   admit `:acyclic`, `:root-reachable`, `:variable-bounds`, and most
   `:slot-kind` checks (beyond `nodes`-is-a-vector) are NOT implemented.
   Confirm this is accurately disclosed (not worse than claimed), and
   assess: is leaving these unimplemented a deliberate, reasonable scope
   boundary for THIS unit (a full §7.4 validator per the design doc is a
   plausible separate unit), or does the gap create a real risk within
   the surface this unit does claim to cover (i.e., could a malformed
   input that SHOULD be caught silently corrupt a reconstruction instead
   of throwing)?
6. **The `:tail?` canonicalization decision.** The implementer's findings
   note reconstruction always emits an explicit `:tail? false`/`:operands
   []`, which means `yang/compile`'s output (which omits `:tail?` when
   false) is not "canonical" and won't round-trip byte-for-byte through
   `map → rows → map` as `=`. Is this consistent with what §2.1/§7.2
   actually require, or is it a design gap that should block sign-off?

## Boundaries

Read-only. Do not edit anything.

## Deliverable

Report each finding as: severity (blocking / non-blocking) | file:line |
concrete failure scenario | recommended fix. End with an explicit
verdict: is this implementation faithful to the §2 grammar and the
round-trip law, safe to proceed to Architect sign-off — yes or no.
Produce the complete deliverable now, without waiting for further input.

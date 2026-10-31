Created-GMT: 2026-09-21 18:48:56 GMT
Created-Local: 2026-09-22 01:48:56 +07 (Indochina Time)
Coding-Agent: codex
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a (resumed — your third revision turn)
# Task: batched fourth revision of the de Bruijn VM design, then your sign-off
Role: Architect
Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-22 01:48:56 +07 | Status: active | Rationale: you authored the design; two independent reviewers returned (opus-5 READY FOR OWNER DECISIONS with stale-wording fixes; fable-5-1 architectural review). Findings are batched into ONE turn because the GPT weekly budget is about 20 percent and resets 2026-09-23 20:44 +07.

Work in /Users/sto/workspace/datomworld. You MAY edit exactly ONE file:
`docs/design/yin.vm.debruijn-vm.md`. BE ECONOMICAL: read only the review files and
the design, and only the sources you need to verify a specific claim. Give the
complete answer now; do not wait for approval and do not promise one.

## Input 1: opus-5's fourth confirmation (READY FOR OWNER DECISIONS, no P1)

collab/1790016262426-reviewer-debruijn-vm-design-r4.claude-opus-5.findings.md.
Fold in (verify each against the document):
- **P2-A** stale sentences still call the executable image lossless or invertible,
  contradicting section 1's "not itself invertible": B2's criterion "image decode
  round trips" (cannot be met; replace with "B0 encode/decode round trip on the
  same inputs, and image encode/validate/load round trip"); B5 "their lossless
  images"; section 7 "distinct lossless images"; section 7.2 "consumes lossless
  executable images" (all: "executable images"); the section 1 bullet "the
  front-end `:yin/tail?` value where the named linearizer observes it" (should be
  "`:yin/tail?` on every node"); and the section 3 clause "excluded from code
  identity only when explicitly declared diagnostic metadata" (should be: debug
  node hashes and source references are always diagnostic metadata outside code
  identity). This also closes the earlier P2-E.
- **P2-B** B1's completion text says "cross-host bytes for every supported scalar
  class", contradicting section 2's host rule (CLJS reads 1.0 as 1; ratios are not
  on CLJS). Replace with: distinct hashes for `1`/`1.0`, ratio and char on JVM and
  Dart; identical bytes across hosts for the common scalar domain.
- **P3-A** section 2's scalar list omits nil, booleans and collection literals
  though `:const` operands are any `vm/plain-data?` value. Enumerate them; maps and
  sets ordered by encoded bytes; vector and list distinct classes (follow the
  projection's value table).
- **P3-B** section 7.1 paragraph 2 says lifting/ANF "remain possible later lowering
  passes", contradicting the owner ruling in paragraph 3 (upstream AST-to-AST
  stages). Fix.
- **P3-C** an upstream lifting/ANF stage must write `:yin/tail?` on the nodes it
  creates, because both lowerers copy front-end tail flags and infer nothing. Add
  one sentence.

## Input 2: fable-5-1's architectural review

collab/1790016340343-architect-debruijn-vm-design-fable.claude-fable-5-1.findings.md
(read-only reviewer, independent Claude-family architect view: axiom and
invariant compliance; whether `:yin.debruijn.code/*` satisfies datom.md's
dimension protocol without repeating the projection's "published" overclaim; one
dimension versus two VMs; whether the three-artifact decomposition (named datoms,
lossy projection, derived image) is coherent and minimal; the stream-topology
linker; phasing and reversibility; top three risks). Treat it as an ARCHITECT's
review: for every finding decide ACCEPTED (state the concrete text change),
REJECTED (with the reason and the source or axiom evidence), or OWNER DECISION.
If fable proposes a structural change (for example, one instruction dimension
with an optional lexical operand encoding instead of a sibling dimension), rule on
it on the merits and say what it would cost B0-B6; do not adopt a restructure
just because a reviewer suggested it, and do not reject one without saying why.

## Do

1. Dispose of every item in both inputs and edit the one file. Keep its
   conventions (numbered sections, ASCII box tables, no em dashes, 80 columns,
   status "design; not implemented", no operational routing text). Update section
   8 so the owner decisions stay ranked by how much they block B0.
2. **Your sign-off, as the design's author**: is the design ready to hand to the
   owner for the B0-blocking decisions and to commit as a design document? Say
   READY or NOT READY and, if not, the smallest change.
3. Report: the disposition table (ASCII), sections changed, findings rejected
   with reasons, the remaining owner decisions ranked by B0 impact, and the top
   three risks in your own words.

Final response beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a
then: the sign-off line, the disposition table, sections changed, rejected
findings, remaining owner decisions ranked by B0 impact, and the top three risks.

Created-GMT: 2026-09-21 18:34:10 GMT
Created-Local: 2026-09-22 01:34:10 +07 (Indochina Time)
Coding-Agent: codex
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a (resumed — your Unison prior-art turn)
# Task: revise the de Bruijn VM design again after the opus confirmation review
Role: Architect
Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-22 01:34:10 +07 | Status: active | Rationale: you authored the design; the independent reviewer returned NOT READY on the revision with three new P1 findings

Work in /Users/sto/workspace/datomworld. You MAY edit exactly ONE file:
`docs/design/yin.vm.debruijn-vm.md`. Give the complete answer now; do not wait
for approval and do not promise one.

## Input

The independent confirmation review (claude-opus-5, resumed its own session):
collab/1790015469938-reviewer-debruijn-vm-design-r2.claude-opus-5.findings.md.
Read it in full and VERIFY every claim against the sources it cites (file and
line); accept only what checks out. Summary of its verdict: NOT READY. Architecture
B fixed the cause of the first review (lowering from the lossy projection); of the
22 first-round findings most are FIXED in the text, several PARTLY FIXED (P1-1,
P1-3, P1-4, P2-7) and one REGRESSED (P2-10). Three NEW P1s and seven P2s:

- **P1-A** the code-image hash reuses `yin.vm.debruijn/encode-value`, which
  canonicalizes (1 and 1.0 encode identically, NFC on strings/keywords/symbols,
  throws on ratios/bigints/chars/records), bringing the spelling collision back
  INSIDE the cache key (`(/ 1.0 2)` and `(/ 1 2)` would share an image hash) and
  making the "lossless" encoding unable to represent `(+ 1/2 1)`.
- **P1-B** the memo key `[node, frame-arity-vector, tail-context]` resolves a
  shared named node wrongly, because over NAMED datoms resolution depends on the
  binder NAMES on the stack, not only arities (`ast->datoms` emits a shared `:eid`
  once; `((fn [x] #7) 1)` versus `((fn [y] #7) 2)` share key `[7 [1] false]`). The
  projection keys on the full vector of parameter names (`debruijn.cljc` ~742).
- **P1-C** the invertibility invariant `decode-db(encode-db(named-datoms))` has no
  defined subject: `encode-db` has no home in B0/B1/B2; it is unclear whether the
  inverted artifact is a tree-shaped record schema or the linear image (inverting
  the image needs a decompiler nothing specifies); front ends set `:yin/tail?` on
  many node types (`compile-program`, `compile-literal`, `compile-if`,
  `compile-lambda`, Python `compile-stmt`, the emitter at `vm.cljc:564`), not only
  `:application`, so dropping it breaks the equality for a program as simple as
  `42`; per-occurrence expansion turns a shared-eid DAG into a tree, so decode
  needs one source reference per occurrence and a re-merge; `:yin/macro-name` and
  other-namespace attributes are neither preserved nor declared normalized.
- P2-A named-path input semantics depend on ROW ORDER (`vm/index-datoms`:
  last repeated fact wins, `:yin/operands` concatenates across datoms in batch
  order, last `:yin/root` wins, rootless batches use a structural fallback), so the
  invariant "modulo row ordering" is false, and reusing the projection's
  `index-frame`/`frame-root` (which throw on duplicates and require one root)
  would reject named-valid programs. P2-B "the resolver helpers are reused" is
  ambiguous: only `resolve-name` (`debruijn.cljc` ~606) is public and lossless;
  `build-node`/`project-node` are private and run `validated-scalars`,
  `check-unexpanded` and `canonical-value`. P2-C the closure normalizer "arity and
  normalized captured values" cannot match (`((fn [x] ((fn [x] (fn [] x)) 2)) 1)`:
  named `{x 2}`+initial env vs frames `[[1] [2]]`); compare `{:type :closure
  :arity n}` only, and define or drop "stable identity" for reified continuations.
  P2-D "fresh initial environments" cannot test park/resume across two programs on
  one VM; fix the named-VM leak as a B4 prerequisite or restrict B5 fixtures.
  P2-E provenance is both in the image hash (section 2) and excluded (section 3);
  identity should cover opcodes, operands, exact scalars and the name table, with
  provenance always diagnostic metadata outside the hash. P2-F the design
  duplicates the named linearizer's whole traversal; a smaller design would take
  `linearize/lower`'s output and rewrite `:var` operands per body via
  `resolve-name` and replace `:closure` params by arity (same order and tail flags
  by construction); if the duplicate is kept, B2 needs a structural test that the
  opcode sequence equals `lower`'s apart from `:var`/`:closure` operands. P2-G
  section 7.2's linker still "consumes projected code", reviving P1-1; it should
  consume lossless images and use the fingerprint only as a lookup index.
- P3: section 7.1 states some Unison claims beyond the owner-verified list (index
  zero as the nearest variable is verified; "evaluation" as a stage and the
  directory listing are partly verified; "Decompilation need not preserve ..."
  reads as a claim about Unison); owner decision 1 blocks B0 and should say so.

## Do

1. **Disposition of every new finding** (P1-A, P1-B, P1-C, P2-A..G, P3) and of the
   five first-round items still PARTLY FIXED or REGRESSED (P1-1, P1-3, P1-4, P2-7,
   P2-10): ACCEPTED (say how the revised text fixes it), REJECTED (with evidence
   from the source), or OWNER DECISION. No blanket acceptance: cite the concrete
   text change.
2. **Rule on P2-F**, because it is architectural: keep a separate de Bruijn
   lowerer, or derive the image from `linearize/lower`'s output with `:var` and
   `:closure` rewritten? Decide on the merits: which one guarantees identical
   order and tail flags with the least drift, what it costs the invertibility
   invariant, and what it does to B2 and to `yin.vm.linearize` (no edits to the
   linearizer or `:yin.code/*` are allowed; section 9).
3. **Pin down the invertibility invariant** so B0 can start: what artifact is
   encoded and decoded (a tree-shaped lossless record schema, stated over the
   named datoms), exactly which attributes are preserved (`:yin/tail?` on EVERY
   node type, `:yin/macro-name`, one source eid per occurrence with re-merge on
   decode) and which are normalized and why (tempids, `t`/`m`, non-`:yin`
   namespaces, row order only where the named path is order-insensitive), the
   restricted input domain (one fact per entity and attribute, exactly one root,
   and what happens to batches the named path accepts outside that domain), and
   where `encode-db`/`decode-db` live (a named file in a named phase).
4. **Fix the image identity** (exact executable scalar encoding with distinct
   tags for long and double and an explicit treatment of ratio, bigint, char and
   other host values; no NFC on hashed bytes; reuse only framing and length
   helpers; provenance excluded from the hash) and add B1 completion tests that
   `1` versus `1.0` and composed versus decomposed `e-acute` give distinct image
   hashes and that `(+ 1/2 1)` is either representable or refused with a stated
   diagnostic.
5. **Fix the memo key** (the vector of parameter-name vectors plus node identity
   plus tail context, matching the projection) and say which reused helper is
   `resolve-name` only.
6. **Fix section 7.1 and 7.2** per the P3 and P2-G items and mark unverified Unison
   statements UNVERIFIED. Do not edit any other file.
7. **Report**: sections changed, findings rejected with reason, and the remaining
   OWNER DECISIONS ranked by how much they block B0.

Final response beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a
then: the P2-F ruling in one paragraph; the disposition table (ASCII); the
sections changed; the remaining owner decisions.

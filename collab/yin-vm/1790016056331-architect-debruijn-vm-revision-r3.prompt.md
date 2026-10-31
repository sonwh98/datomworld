Created-GMT: 2026-09-21 18:40:56 GMT
Created-Local: 2026-09-22 01:40:56 +07 (Indochina Time)
Coding-Agent: codex
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a (resumed — your second revision turn)
# Task: third revision of the de Bruijn VM design — reviewer P2s plus owner rulings
Role: Architect
Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-22 01:40:56 +07 | Status: active | Rationale: you authored the design; the independent reviewer says READY FOR OWNER DECISIONS with six new P2s to fold in, and the owner has ruled on two open decisions

Work in /Users/sto/workspace/datomworld. You MAY edit exactly ONE file:
`docs/design/yin.vm.debruijn-vm.md`. Give the complete answer now; do not wait
for approval and do not promise one.

## Input 1: the reviewer's third pass (claude-opus-5)

collab/1790015887769-reviewer-debruijn-vm-design-r3.claude-opus-5.findings.md.
Verdict READY FOR OWNER DECISIONS, no P1 open; every earlier P1 fixed in the
text. It confirmed the adapter architecture is sound: a body's scope can be
rebuilt from `lower`'s image alone (each `:lambda` occurrence gets a fresh label
and a freshly lowered body; each body is the target of exactly one `:closure`;
bodies are contiguous and end at their first `:return`; the enclosing body of a
`:closure` at pc c is the body whose range contains c; `yin.vm.completion`
already does this in `closure-ranges`, `layout-conforms?`, `segment-scope`,
completion.cljc ~132-212). VERIFY each claim against the source before accepting
it. Fold in:

- **P2-1** B2 says "rewrite each `:var` operand using `resolve-name` in the source
  body context", which hints at `:yin.code/source` and would be ambiguous for a
  shared-eid lambda whose two body copies carry the same source eid. Specify the
  scope reconstruction as a PURE FUNCTION OF THE IMAGE: body range is
  `[entry, first :return]`; the parent is the body containing the `:closure`;
  build the name stack innermost-first for `resolve-name` (its depth 0 is
  `(nth stack 0)`, the OPPOSITE of section 4's runtime frame order, so state the
  order conversion explicitly); require `layout-conforms?` as a validation
  defect; cite completion as the precedent. Note `segment-scope` is public but
  `closure-ranges` and `layout-conforms?` are private.
- **P2-2** section 2 says the encoder "reuses only framing and length-prefix
  helpers" but `framed`, `to-hex`, `utf8-byte-length`, `int64-le-hex`,
  `double-le-hex` and `slot-tag` are all private in `yin.vm.debruijn`
  (~775-872), and the B1 box says "Existing edits: none". Also `framed` looks up
  a tag in a `slot-tag` table built from the projection's classes, so a new class
  (`:ratio`, `:char`) gets nil and `(str nil)` drops the tag byte silently. Decide:
  copy the small helpers into the new code namespace with its own tag table, or
  make them public as a listed B1 edit. Also require B1 to refuse unpaired
  surrogates as the projection's `well-formed-utf16?` does.
- **P2-3** scalar classes do not exist on every host (CLJS reads `1.0` as the JS
  number 1; `\a` is a string on CLJS; ratios JVM-only; bigints differ). Declare a
  per-host classification (a JS number is long when it is a safe integer, else
  double), restrict cross-host byte identity to values that exist on all hosts,
  and mark the `1`/`1.0`, ratio and char image-hash tests JVM and Dart only.
- **P2-4** the tree-shaped lossless record set is exponential on shared named DAGs
  (`ast->datoms` deduplicates a shared `:eid`, so a doubling graph is linear in
  datoms and 2^n in occurrences), the same bug class the projection's memo fixed.
  Make the lossless artifact a DAG keyed by `[source-eid, name-stack]`, decode
  re-merging by source eid, and add a doubling-chain size test to B0.
- **P2-5** section 1 contradicts itself: line ~15 calls the executable image "a
  third, lossless representation" but line ~40 says the lossless artifact is the
  tree-shaped record set, not the linear image. The image drops non-application
  tail flags, `:macro?`, `:macro-name` and structure, so it is NOT lossless. Call
  it "executable, derived, not invertible"; say "every node" for tail flags; say
  equality holds "up to a consistent entity renaming" (emitter source eids ARE
  tempids); reword owner decision 1 to "which stable identity, if any, replaces
  tempids".
- **P2-6** the image hash must be tied to a canonical form. `lower` produces a
  datom batch whose entity ids depend on `:id-start`, plus `:yin.code/source` and
  `:derived-from` tempids, and `ast-loader` gives each batch a different
  `:id-start` (linearize.cljc ~426-429). Hash the CANONICAL POSITIONAL VECTOR
  (pc-indexed tuples, refs as pcs, no header), as `load-image`'s canonical form
  and `jing/segment-key` already do; keep provenance as a side table indexed by
  pc.
- **Partly-fixed leftovers**: P2-E delete the section 3 clause that says debug
  hash/source ref "are excluded from code identity only when explicitly declared
  diagnostic metadata" (contradicts "always diagnostic metadata outside the
  hash"); P2-7 state the ex-data rule: "ex-data is compared after applying the
  value normalizer recursively to its values"; P3-1 section 7.1 still contains
  statements outside the owner-verified list ("evaluation" as a stage and the
  component list ANF, MCode, Machine, Serialize, Decompile, Canonicalizer): mark
  them UNVERIFIED or cite the directory listing precisely; P3-2 recast the memo
  paragraph and "walked per occurrence" as properties inherited from `lower`;
  P3-3 pick "spelling slot" or "side table" before B1 freezes the descriptor.

## Input 2: two owner rulings (2026-09-22) and one owner point

- **Exact Unison runtime interoperability is NOT a project goal.** Remove or close
  the owner decisions and any wording that keep that door open; keep Unison as
  prior art only.
- **The linker's boundary is `dao.stream`; whether it is local or remote it is
  just a dao.stream.** Do not frame the linker as "Unison-like dependency identity
  versus yin.vm-local hash linking". State one transport-agnostic hash-identity
  linker; resolving a hash locally or remotely is the stream's concern. Close that
  owner decision; keep as still-open the things streams do NOT decide (authoritative
  name ledger and trust/provenance, strongly-connected-component identity, retry,
  timeout and permanent-absence policy).
- **Owner point on lambda lifting:** because the compilation pipeline is composed
  as dao.stream, lambda lifting (and ANF) can be a separate AST-to-AST stage in the
  pipeline; it is not VM machinery and not part of B0-B6. Add a short note (in the
  non-goals or section 7.1) saying: lambda lifting is a possible AST-to-AST stage
  upstream of both lowerers, reading and writing named `:yin/*` datoms, so both
  the existing linearizer and the de Bruijn lowerer accept its output unchanged;
  this design neither depends on it nor forbids it; it changes arity and so is
  opt-in and must be compared against the unlifted program under a stated arity
  caveat; synthesized parameters need name-table entries; the invertibility
  invariant applies to whichever named AST the lowerer receives. Keep the earlier
  ruling that lifting is deferred.

## Do

1. Dispose of each item above (ACCEPTED with the concrete text change, REJECTED
   with source evidence, or OWNER DECISION). Edit the one file. Keep its
   conventions (numbered sections, ASCII box tables, no em dashes, 80 columns,
   status "design; not implemented", no operational routing text).
2. Update section 8: remove the decisions the owner just answered, keep the still
   open ones ranked by how much they block B0, and mark which decisions are
   B0 blockers.
3. Report the sections changed, the findings you rejected with reasons, and the
   remaining owner decisions.

Final response beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a
then: the disposition table (ASCII), the sections changed, the findings rejected,
and the remaining owner decisions ranked by B0 impact.

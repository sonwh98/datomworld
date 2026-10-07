Created-GMT: 2026-09-15 22:23:53 GMT
Created-Local: 2026-09-16 05:23:53 +07 (Asia/Ho_Chi_Minh)

# Task: Architect sign-off — yin.vm/ast->semantic-bytecode (full 5-round diff)

Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-16 05:23:53 +07 | Status: active | Rationale: Architect sign-off gate, required before commit
Session-ID: 47749f10-80dd-4c7b-ade8-802912cb6827

Perform a read-only architecture review of the uncommitted working-tree
diff to `src/cljc/yin/vm.cljc` and `test/yin/vm_test.cljc` (run
`git diff -- src/cljc/yin/vm.cljc test/yin/vm_test.cljc`).

## Context

This implements `ast->semantic-bytecode` / `semantic-bytecode->ast`, the
map-AST-to-flat-content-addressed-rows codec projection pair specified in
`docs/design/yin.vm.code-as-tuples.md` §2, §4.4, §6.5, §7.2 (the
round-trip law). It went through 5 rounds of implementation + adversarial
review before reaching you:

- **r1** (`claude-opus-5`): initial implementation, all 18 §2.3 tags,
  round-trip corpus tests. `gpt-6-astra`'s r1 adversarial review found 4
  blocking defects: a grammar-kind mismatch (`:lambda :params` typed
  `:data` instead of `:syms`), structural-sharing metadata corruption,
  reader-provenance leaking into row bodies, and malformed rows
  round-tripping to different addresses silently.
- **r2**: fixed all 4. `gpt-6-astra`'s r2 review found the fixes
  introduced or left open 5 more issues: a MapEntry-corrupting bug in the
  new stripping code, a deeper metadata-nested-inside-metadata case, an
  incomplete reader-position strip, a pathological custom-comparator-set
  case, and a params-vector-metadata round-trip asymmetry.
- **r3**: the orchestrator triaged r2's 5 findings, fixed 2 (MapEntry
  corruption, params-vector-metadata asymmetry) and explicitly scoped out
  2 as "pathological" (nested metadata; the custom-comparator set) with
  disclosure language, judging them unreachable through any real
  frontend. **This judgment was wrong for the nested-metadata case** —
  `gpt-6-astra`'s r3 review proved it reachable through completely
  ordinary nested reader syntax (`^{:note ^{:meaning 1} x} []`) via
  `yang/compile`, and found a third real bug (operand-vector `:nodes`
  metadata had the same asymmetry params just got fixed for).
- **r4**: fixed the shared root cause properly — `same-meta?` and
  `strip-reader-positions` now recurse into a value's metadata itself
  (not just its structure), plus the `:nodes` metadata-free check.
  `gpt-6-astra`'s r4 review confirmed all 3 r3 reproductions genuinely
  fixed, found one more narrow item (an empty-but-metadata-decorated
  map being discarded by an over-eager `not-empty` check — explicitly
  NOT reachable through ordinary reader syntax, narrower reach than
  prior findings) and one docstring accuracy correction (the deferred
  custom-comparator-set case is "silent and order-dependent," not
  reliably fail-closed as previously claimed).
- **r5**: fixed both. `deepseek-v4-pro`'s r5 Routine Review (a lighter
  confirmation pass, judged proportionate since r4 had already validated
  the core recursive logic across 4 adversarial rounds) confirmed both
  fixes correct with a case-by-case trace, verdict PASS.

Current state: 23 tests, 172 assertions, 0 failures (orchestrator
independently confirmed, and independently spot-read the actual
`same-meta?`/`strip-reader-positions`/`:nodes` code changes at each
round rather than trusting reports alone).

## Read first

- `docs/design/yin.vm.code-as-tuples.md` §2, §4.4, §6.5, §7.2, §7.4
- `src/cljc/yin/vm.cljc` (current full file, focus on lines ~563-750,
  the new "Semantic bytecode" section)
- The 5 rounds' prompt/findings files, in order:
  `collab/1789521800000-vmruntime-ast-semantic-bytecode.prompt.md`,
  `collab/1789521800000-vmruntime-ast-semantic-bytecode.claude-opus-5.findings.md`,
  `collab/1789522650000-review-ast-semantic-bytecode.gpt-6-astra.stdout.log`,
  `collab/1789523200000-vmruntime-ast-semantic-bytecode-r2.claude-opus-5.findings.md`,
  `collab/1789524900000-review-ast-semantic-bytecode-r2.gpt-6-astra.stdout.log`,
  `collab/1789525100000-vmruntime-ast-semantic-bytecode-r3.claude-opus-5.findings.md`,
  `collab/1789525500000-review-ast-semantic-bytecode-r3.gpt-6-astra.stdout.log`,
  `collab/1789527000000-vmruntime-ast-semantic-bytecode-r4.claude-opus-5.findings.md`,
  `collab/1789528100000-review-ast-semantic-bytecode-r4.gpt-6-astra.stdout.log`,
  `collab/1789529200000-vmruntime-ast-semantic-bytecode-r5.claude-opus-5.findings.md`,
  `collab/1789530200000-review-ast-semantic-bytecode-r5.deepseek-v4-pro.findings.md`

## Evaluate

Per your role definition, on the diff as it stands now (not a re-audit of
every round's history — trust that the adversarial process worked, but
verify the END STATE is architecturally sound). Specifically:

1. **Rule on the deferred custom-comparator-set scope-out.** Is disclosing
   this as "silent and order-dependent, not reliably fail-closed" and
   deferring it (rather than fixing it) an architecturally acceptable
   boundary for a transitional-encoder-dependent codec, or does it need
   to be closed before commit? Consider: is this genuinely as pathological
   as `dao.jing.md`'s own already-accepted residuals (pathological
   symbols, scalar metadata), or does its potential for SILENT (not just
   deferred/loud) corruption in some traversal orders put it in a
   different risk category?
2. **Evaluate the 5-round fix trajectory itself.** Does the pattern (each
   round narrower, traced to a shared root cause by r4, converging rather
   than diverging) indicate this is now genuinely sound, or does the
   sheer number of rounds suggest a structural gap that piecemeal review
   might still be missing? Form your own view, don't just accept the
   orchestrator's framing.
3. **The still-partial §7.4 validator** (`:root-reachable`,
   `:variable-scope`, most `:slot-kind` checks beyond `:nodes`/`:syms`
   vector-metadata/`:bool` are not implemented — reconstruction validates
   content-address, tag, arity, and the three specific slot-kinds that
   were adversarially found to matter, nothing more). Is this an
   acceptable unit boundary (a full validator as a separate future unit,
   consistent with how the design doc structures §7.3-§7.5 as distinct
   concerns), or does its absence undermine what THIS unit claims to
   deliver?
4. Standard evaluation: foundational invariants, ownership boundaries,
   explicit state/control flow, host isolation and CLJ/CLJS/CLJD
   portability (note: CLJD verification is currently blocked by an
   unrelated pre-existing issue in an untracked bench file — the
   implementer confirmed CLJS is clean and JVM is clean; CLJD remains
   unverified for this unit specifically), migration risk, completion
   criteria, design contradictions.

## Do not edit files.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report per your role's format, and end with an explicit sign-off verdict:
APPROVE, APPROVE-WITH-FINDINGS (nonblocking), or BLOCKED.

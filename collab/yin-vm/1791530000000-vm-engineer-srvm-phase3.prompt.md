Created-GMT: 2026-10-10 05:00:00 GMT
Created-Local: 2026-10-10 12:00:00 Asia/Bangkok

# Task: semantic-register-vm phase 3 -- linearizer, loader rules, static analysis

Role: VM Engineer (yin.vm)

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-10 12:00 +07 | Status: active | Rationale: foundational, novel compiler and validator work across three hosts; Opus performs at Fable level for implementation at lower cost (team.md). Review and sign-off go to a non-Claude Architect (codex), never to Claude alone.

You are working in the git worktree `/Users/sto/workspace/datomworld-srvm-p3`
on branch `srvm-phase3` (from master 018d55fe). Work ONLY there. Do not
`cd` to any other checkout. `mise trust` and `npm ci` have been run.

## Governing design (read first, in this order)

1. `docs/design/yin.vm.semantic-register-vm.md` -- FROZEN (phase-2 gate
   passed, five Architect rounds). Implement §2.2-2.3 (registers,
   instruction table), §3 (grammar, minting, well-formedness rules
   1-13), §8.1 (def, use, L, saved) EXACTLY as written. Where the text
   is ambiguous, stop and record the ambiguity in your report rather
   than choosing; do not amend the design.
2. `docs/agents/build-n-test.md` -- verification commands and the Gate
   Ladder. Read before running anything.
3. `docs/design/yin.vm.semantic.md` §2.6, §5.3 and `src/cljc/yin/vm/linearize.cljc`
   -- the existing stack linearizer, for the walk order you must
   reproduce (operator, operands left to right, lambda bodies out of
   line in FIFO discovery order, occurrences expanded positionally,
   saturation). Both lanes exist there (datom lane and row lane).
4. `src/cljc/yin/vm/code.cljc` -- `vector-operand-table`, the eight
   rules, and how the validator is structured; mirror its shape.
5. `docs/design/yin.vm.code-as-tuples.md` §5.1, §7.1-7.2 -- the row
   lane and the two-load-paths law.
6. `docs/design/yin.vm.debruijn.register.md` §4.4 -- the r2 instruction
   shapes the new table mirrors with named addressing (no `live`).
7. Owner context, verbatim: "The AST is the source of truth but the
   semantic vm is the canonical projection of it. the ast-walker and
   the semantic-vm are isopmorphic. we just need to rebuild the
   smantic-vm as register-shaped vm instead of a stack-shaped vm".

## Scope: new namespaces only; the old VM is untouched

Create, under `src/cljc/yin/vm/semantic_register/` (ns prefix
`yin.vm.semantic-register.*`), all `.cljc`, portable to JVM, Node and
Dart:

- `code.cljc`: the §2.3 operand table (positional, `[op rd …]`; operand
  kinds `:reg`, `:regs` added to the existing vocabulary); the contract
  stamp `"v4"` as this namespace's own var (do NOT touch
  `yin.vm/semantic-contract`); the well-formedness rules of §3.4 items
  1-13 as a `rules` sequence in order, first defect wins, each defect
  naming pc and rule, including: body partition and ownership tree
  (6), register ids `0..k-1` with the definition-less rule (7),
  definite assignment by structured forward walk with terminal arms
  excluded from joins (8), exclusive definitions by structured paths
  with the empty-set case (9), layout syntax after runtime terminators
  (10), Rule R (11), resume (12), canonical-numbering round trip (13:
  parse, re-project under §3.3, compare ids). The grammar parser of
  §3.1 (with the result-or-terminal outcome relation) is part of this
  namespace and is what rules 6, 8, 9, 10, 13 run over.
- `linearize.cljc`: `project` per §3.3 from the Universal AST to the
  saturated positional vector, with BOTH lanes as the existing
  linearizer has them (datom lane reading `:yin/*` datoms; row lane
  over code-as-tuples rows), sharing one flattening order. Body-local
  pre-order minting with the arm and `resume` refinements. Provenance
  in a side table outside the vector. Code address = `ucf/code-address`
  of the vector (unchanged function).
- `analysis.cljc`: per body, the explicit CFG of §8.1 (successor
  relation: pc+1; branch targets; jump target; none for return/halt/
  resume/tail call), `def`, `use`, `L` by backward liveness to a fixed
  point, and `saved(p, rd) = L(p) − {rd}`. Pure functions over the
  validated vector.

Tests under `test/yin/vm/semantic_register/` (`*-test.cljc`; shadow's
:node-test auto-discovers `*-test` namespaces -- confirm "Testing
<ns>" appears in Node output):

- `linearize-test`: golden vectors for a corpus of at least 25 programs
  covering every §3.1 production (atoms, nested calls, tail calls,
  conditionals incl. nested-into-same-rd and all-terminal arms, define,
  every stream op, ffi-call, gensym, store ops, current-continuation,
  park, resume in body/arm/operand positions, closures with out-of-line
  bodies discovered inside bodies, shared occurrences). Goldens are
  checked in as EDN under `test/resources/` or inline, and the SAME
  goldens must pass on JVM, Node and Dart (determinism across hosts is
  the gate). Include the §3.3 worked example `(f (g x) y)` byte for
  byte.
- `code-test`: at least one refusal row per rule 1-13 (a minimal
  malformed vector that trips exactly that rule, asserting the rule
  name and pc), plus acceptance of every golden.
- `load-paths-test`: for every corpus program, direct vector load and
  the datom/row projection load yield equal images (code-as-tuples
  §7.2), through the same validator.
- `analysis-test`: `L` from `analysis.cljc` equals an INDEPENDENT
  reference implementation written in the test namespace (a naive
  iterative worklist over an explicitly constructed edge list, written
  without reusing `analysis.cljc`'s helpers), on every golden; `saved`
  excludes `rd`; the structural-slot case (definition-less id) and
  the unreachable-component case from the design's §8.2 notes are
  covered.

## Constraints

- Do not modify `yin.vm.semantic`, `yin.vm.linearize`, `yin.vm.code`,
  `yin.vm`, any UCF namespace, or any existing test. If something there
  must change, stop and request authorization in your report.
- Portability traps recorded for this repo (all three bite only on
  Dart or Node): `#?(:clj …)` does NOT exclude code from the cljd build,
  write `#?(:cljd nil :clj …)` with `:cljd` first; `(- x)` on a float
  is `0 - x` on Dart, negate with `(* -1.0 x)`; `for` over a
  >32-element seq can hand the body nil on Dart, use `keep`/`mapv`;
  `(assoc nil :a 1 :b 2)` is a one-element list on Dart, start from
  `{}`; a protocol method's `[_ _]` params fail on Dart, use `[_ _x]`;
  the cljd EDN reader throws on whitespace before a closer.
- Foreground only: never background a test lane; never append `&`.
- Verification: iterate with G0 (`clojure -M:test -n <ns>`); before
  reporting run G1 for your namespaces on all three lanes (`bb
  test:sub` per build-n-test.md, or the per-lane commands it names);
  `clj -M:kondo` clean; `cljstyle check` clean; `git diff --check`
  clean. Paste the exact counts (tests, assertions, failures, errors)
  per lane into the report. Do not run the full `bb test`.
- Commit on `srvm-phase3` with the project convention
  `feat(yin.vm.semantic-register): …`; never stage `collab/`; no
  Co-Authored-By lines. Push the branch.

## Report

Write `collab/1791530000000-vm-engineer-srvm-phase3.report.md` in this
worktree. Begin it exactly with:

Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>

Then: changed files; exact test/check outcomes per lane; every design
ambiguity you hit and what you did (did not amend the design); any
unresolved concern; incomplete work, if any. Do not claim edits or tests
that did not occur.

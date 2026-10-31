Created-GMT: 2026-10-10 15:45:00 GMT
Created-Local: 2026-10-10 22:45:00 Asia/Bangkok

# Task: Design phase 5 -- derive the de Bruijn images and the dependency scanners from A

Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-10 22:45 +07 | Status: active | Rationale: the same Architect delegate that authored and defended the phase-4 evaluator design (owner instruction 2026-10-10: Fable authors designs, codex reviews them); a separate session from the 4a code reviewer. Review goes to codex gpt-6.1-sol (non-Claude); never Claude-only sign-off.

You are working in `/Users/sto/workspace/datomworld-srvm-p5-design`, branch
`srvm-phase5-design` (from master 0ff25b58). Work ONLY there.
Documentation only: you write one design document and one report. Do not
edit source, tests, or any existing design document (the frozen design
`yin.vm.semantic-register-vm.md` included). Do not `git add`/`git commit`
(the orchestrator commits). Do not stage `collab/`.

## Why this task exists

The register-shaped semantic VM rebuild is at: phase 1 identity amendments
done; phase 2 design frozen; phase 3 (linearizer, validator, liveness)
landed; phase 4 evaluator design landed (`yin.vm.semantic-register-vm.evaluator.md`,
three codex rounds), slice 4a in review. **Phase 5** is: derive the two de
Bruijn executable images and the dependency-closure scanners from A (the
register-shaped semantic vector's address), per the frozen design's §8 and
§10 phase 5. The frozen text specifies A->R (§8.2) as a NEW pinned
interval allocator and A->H (§8.3) as parse + adapt + the existing
`lower-stack` walk, then says (§8.4) that H is expected to keep today's
bytes and R is "not expected" to, to be decided by evidence. That is a
strategy left open. Your job is to close it as a design grounded in the
code, with the same rigor as the phase-4 design, so an implementer needs
no further decision.

## Owner rulings that bind this (verbatim)

> "yes, A is the only code identity"

> "The AST is the source of truth but the semantic vm is the canonical
> projection of it. the ast-walker and the semantic-vm are isopmorphic.
> we just need to rebuild the smantic-vm as register-shaped vm instead
> of a stack-shaped vm"

> "rebuilding the semantic-vm as as register shaped instead of
> stack-shaped is the right way to go, delaying this will only add tech
> debt in the future"

> "one truth many interpretation" is not violated (the Universal AST is
> the one truth; the semantic, stack and register VMs are interpretations;
> H and R become checksums of lowerings of A, independent sibling
> lowerings, neither derived from the other).

## Read first (all of it, in this order)

1. `docs/design/yin.vm.semantic-register-vm.md` -- FROZEN, as amended.
   §1 stance, §2.3 instruction table, §3 (grammar, minting, rules, the
   parse), §6 (stamps: when b2/r2 are retained or re-versioned: BOTH bytes
   AND normative contract), §7, **§8 (all of it)**, §9, **§10 phase 5**.
2. `docs/design/yin.vm.semantic-register-vm.evaluator.md` -- the phase-4
   design: it is the FORMAT and rigor model (DECIDED vs PROPOSED marked;
   file:line grounding; named rows; numbered acceptance lists; a findings
   section with exact amendment text; questions for the owner).
3. The de Bruijn designs and their A-only amendment blocks:
   `docs/design/yin.vm.debruijn.stack.md`, `yin.vm.debruijn.register.md`
   (§2.1 resolved tuples, §4.1-4.6 lowering, §4.5 live sets, §5.1),
   `yin.vm.debruijn.targets.md`, `yin.vm.linker.md` §3 amendment and §4.2,
   §5.5, §8.1 (derivation records), `yin.vm.universal-continuation-format.md`
   §7.6.1 (the dependency-closure fixed point = "the scanners").
4. The code: `src/cljc/yin/vm/debruijn.cljc` (`resolve-name`),
   `debruijn_resolve.cljc` (`resolve`, `validate-resolved`, the exact
   resolved-tuple record shapes), `debruijn_linearize.cljc` (`lower-stack`,
   `adapt`), `debruijn_register_compile.cljc` (`lower-register`, its
   target-register-passing allocator, `body-liveness`, `fill-live`,
   `lift`), `debruijn_register_code.cljc`, `debruijn_code.cljc`, the
   linker's closure/derivation code in `src/cljc/yin/vm/linker.cljc` and
   `src/cljc/yin/vm/ucf/handoff.cljc`, and the landed phase-3 code
   `src/cljc/yin/vm/semantic_register/{code,linearize,analysis}.cljc`
   (`parse`, `body-ranges`, `analyze`, `saved`, the image shape).
5. The corpora the gates will run on: `test/yin/vm/parity_test.cljc`
   (B0, 26 rows), `test/yin/vm/semantic_register/corpus.cljc` (32),
   `test/yin/vm/debruijn_*` goldens (the pinned H and R values), and the
   C4 linked-prelude corpus in `test/yang/python/antlr/`
   (`linked_harness.cljc`, `linked_prelude_test.cljc`, `import_programs.cljc`,
   `import_test.cljc`). READ-ONLY: the C4/yang.python track is another
   orchestrator's; do not edit it and do not design changes to it; only
   specify how a scanner-agreement gate runs over its corpus.
6. `docs/design/datom.world.md` invariants; `docs/agents/build-n-test.md`.

## Deliverable: `docs/design/yin.vm.semantic-register-vm.derivations.md`

Every section grounded in file:line references. Mark DECIDED (follows
from frozen text or the code) vs PROPOSED (your choice, to be reviewed).

1. **The shared resolve-on-vector function.** The exact algorithm: the
   closure-owner tree from §3.4 rule 6 (it exists and is validated),
   bound-variable lookup producing `[depth position]` or a free name, the
   record shapes it must emit to satisfy `validate-resolved`, what goes in
   the binder side table, how duplicate parameters and shadowing resolve
   (must equal `resolve-name`'s behavior; cite the tests that pin it), and
   its output being a stage value with NO identity.
2. **A -> H.** Parse (phase-3 `parse`) -> adapt to the exact input
   `lower-stack` consumes -> emit. The adapter's record shapes field by
   field; occurrence expansion (a resolved record per OCCURRENCE, never
   re-shared); defaults, `:yin/tail?`, arity, body discovery order (the
   FIFO queue of §3.3 against `lower-stack`'s order); how absolute pcs are
   recomputed. State, with evidence, whether H bytes are expected to match
   today's by construction.
3. **A -> R: THE KEY DECISION.** Two routes; analyse both honestly and
   recommend one:
   (a) the frozen §8.2 route: a NEW pinned interval allocator over the
       register-shaped vector (virtual registers, pc-order scan, shared
       destinations, structural slot, `live` operands from `L(pc+1) - {rd}`);
   (b) the ADAPTER route: since A parses to expression trees, adapt the
       trees to resolved tuples and call the EXISTING `lower-register`
       unchanged, so R's bytes are today's by construction and no new
       allocator is written.
   For each: what is built, what must be verified, where bytes can differ
   from today's R (find the exact places: R1's allocator is "target-
   register-passing with explicit expression-consumption events", the
   frozen §8.2 is an interval scan; say precisely how they differ and on
   which corpus programs), the consequence for the b2/r2 contract
   decision under frozen §6 (bytes AND normative contract), the
   consequence for "A is the canonical projection" and for the
   register-shaped virtual-register vector being a derivation SOURCE
   versus merely a source of trees, and the cost of each. If you
   recommend (b), write the exact amendment text for frozen §8.2 and
   §8.4; if (a), prove why a new allocator is worth its risk. Do not
   decide silently; this is Finding F8 for the reviewer.
4. **The byte-identity and contract decision procedure.** A mechanical
   procedure (not prose) for deciding, per contract (b2, r2), "retain" or
   "re-version": the comparison representation (vectors AND canonical
   bytes AND checksums, never checksums alone), the corpus, what counts as
   a normative-contract change, and who records the decision where.
5. **The lexical-address law** `addresses(H) = addresses(R) =
   addresses(resolve(A))` made exact: the definition of `addresses`,
   the corpus, the test shape.
6. **The scanners (UCF §7.6.1 dependency-closure walk).** Where it lives
   today (find it: it walks instructions to collect free `:var` names,
   store operands, effect ops, `:resume` operands); what a
   register-table version computes; the FACTS compared (free names, store
   footprint, discharged bodies, effect kinds, ffi ops, parked refs);
   how agreement with the old scanner is checked on (i) the B0 corpus,
   (ii) the register corpus, (iii) the C4 linked-prelude corpus by name;
   what "old scanner" means when the old VM is deleted at cutover (pin
   its outputs as goldens before then). Specify the production linker
   format record `:yin.semantic-register/code` (the interim format of
   frozen §10 phase 4) and the derivation records of linker §8.1.
7. **Slices and gates.** Split phase 5 (suggest 5a: resolve-on-vector +
   A->H; 5b: A->R per your decision; 5c: scanners + linker format record)
   with exact files to create, test namespaces, named rows and numbered
   mechanical acceptance lists, a statement of what each slice leaves
   untouched, and the ordering constraints between 5a/5b/5c and phase 4.
   Say what can run concurrently with phase 4b.
8. **Findings and proposed amendments** to the frozen design (F8 above
   and any others), each with evidence, the frozen section, the exact
   text to replace and the exact replacement.
9. **Questions for the owner** if a decision is genuinely theirs (for
   example whether a re-versioned r2 is acceptable) and which decisions
   are the Architect reviewer's.
10. **Portability notes** keyed to the repo's host traps.

## Constraints

- Docs only. Verify by reading; you may run read-only repo commands
  (grep, git log) and, if useful to ground a claim about today's bytes,
  the EXISTING golden tests for the de Bruijn images (foreground, bounded,
  explicit namespaces written out literally: never put a namespace list
  in an unquoted shell variable, zsh does not word-split it and the
  runner then runs the entire suite). Do not run the full suite.
- The sandbox may block reads outside this worktree; everything you need
  is inside it.
- Foreground only; no `&`.

## Report

Write `collab/1791570000000-architect-srvm-phase5-design.report.md`.
Begin it exactly with:

Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Bangkok>

Then: the deliverable path; a one-paragraph summary including your
RECOMMENDATION on the A->R route and why; the findings against the frozen
design; the questions for the owner; anything you could not ground in code
and why. Do not claim reads, runs or edits that did not occur.

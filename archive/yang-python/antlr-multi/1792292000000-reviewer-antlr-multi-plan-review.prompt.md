Created-GMT: 2026-10-16 22:30:00 GMT
Created-Local: 2026-10-17 05:30:00 +07 (+0700)
Coding-Agent: codex
Session-ID: pending (capture thread.started; seat-assigned 44782c63-6885-4007-96ae-df95ef10d8c5)

# Task: Adversarial review — the ANTLR multi-target plan (JS and Dart parsers for the Python frontend; the generalized add-a-language story)

Role: Lead System Architect (adversarial review of another architect's plan; you adversarially reviewed the TS/PHP design loops — the same posture)

THE PLAN (read-only; adversarially review it):
/Users/sto/workspace/datomworld/collab/1792289000000-architect-antlr-multi-plan.claude-cloud-fable.findings.md
— fable's plan for the ANTLR pipeline on Node and Dart (the Python frontend on three hosts) plus the
generalized add-a-language story. Core rulings to attack: the target-neutral parse-tree IR is the EXISTING
CST packet v1 formalized (packet.cljc owns the shape doc, seven invariants, a portable conform oracle —
not a new schema); one exporter namespace with .cljs/.cljd siblings; one manifest with an N-target
:targets map (the Java digests byte-identical); per language ONE ~40-line parser.cljc with three
reader-conditional constructor profiles; the TS/PHP designs' "JVM-only" statements re-read as
helper-availability facts (they gain hosts by adding helpers to their manifests); M0 build / M1 IR+conform
/ M2 Python-on-three-hosts / M3 legacy-retirement (its own gated round); the risks section.

REVIEW AGAINST:
- docs/design/yang.typescript.md and yang.php.md — the TS/PHP designs the plan claims to reconcile with
  (their parse-pipeline designs, their section-17 yang.core lifts, their helper-availability statements);
  check the plan's claims about them ("TypeScript has Java-only helpers upstream; PHP has Java and
  JavaScript") and the reconciliation mandate's three questions (a/b/c).
- The code: src/cljc/yang/antlr/packet.cljc (the v1 shape and conform surface), yang.frontend,
  src/clj/yang/python/antlr/parser.clj, the build/antlr vendored grammars, bb.edn's gen task,
  src/dev/yang_antlr_gen.clj.
- The owner's requirements: antlr works for JS and Dart before the legacy compiler retires; the design
  makes adding new antlr-supported languages mechanical.

ATTACK SPECIFICALLY:
1. The CST-v1-as-IR formalization: is packet conform actually sufficient as a per-target oracle (the
   seven invariants — verify they exist and cover what the plan claims), or does the JS/Dart parse tree
   diverge in ways conform can't see (the UTF-16 code-unit indexing risk is named — is the code-point
   stream opening actually designable in the antlr4 JS runtime, or is the fixture guard wishful)?
2. The ~40-line parser.cljc claim: plausible per host, or does the antlr4 Dart runtime's cljd interop
   friction (the dynamic warnings class) blow the budget?
3. The manifest :targets map: the generator-loop design, the Java digests byte-identical constraint, the
   npm/pub pins — coherent and complete?
4. The M0-M2-M3 sequencing: hidden dependencies between slices; the legacy retirement's preconditions
   demonstrable before the round opens?
5. The risks section: anything missing (the antlr4 Dart runtime's licence, the pub SDK bound, the ESM
   circular import, bundle/CI cost — plus what's absent)?
6. The reconciliation with the TS/PHP designs: any contradiction between the plan and those landed
   designs' own sections?

Verdict: end with exactly one line "Verdict: READY (sign-off granted)" or "Verdict: REQUEST CHANGES"
(then proceed to findings) with severity-tagged findings and file:line evidence. Begin the final response
exactly with:
Completed-GMT / Completed-Local

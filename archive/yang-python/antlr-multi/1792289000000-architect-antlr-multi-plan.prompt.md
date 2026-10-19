Created-GMT: 2026-10-16 21:50:00 GMT
Created-Local: 2026-10-17 04:50:00 +07 (+0700)
Coding-Agent: claude-cloud
Session-ID: FB2_PLACEHOLDER

# Task: Architect planning — the ANTLR pipeline for Node and Dart (and every future ANTLR language)

Role: Lead System Architect (planning authorship; the owner corrected the orchestrator for dispatching
this implementation before the plan — this plan gates the implementation)

CONTEXT (owner-reported, the active defect): at the yin.repl prompt, `(lang :yang.python/antlr)` works on
the JVM only. The vendored Python 3 grammars compile to Java (antlr4 tool → build/antlr/python3/classes)
and only src/clj/yang/python/antlr/parser.clj (the JVM parser worker) can run the pipeline — Node and Dart
answer unavailable-parser. The owner has now ruled the direction, verbatim: "don't remove legacy until
antlr works for JS and dart too", and: "the architect should know that future languages will be added too
and whatever is design should make it easy to add new antlr supported languages."

THE DESIGN QUESTION, GENERALIZED: the multi-language architecture must make adding ANY new ANTLR-supported
language cheap and mechanical. Today adding a language means: vendor its .g4 grammars, hand-build the
Java target into the gen task, hand-write a JVM parser worker per language, hand-gate the catalog
JVM-only, and hand-adapt the CST→UAST converter per language. For every new language that's N hand-built
paths (Java today; JS and Dart joining).

THE ANTLR FACT: antlr4 generates the same lexer/parser/visitor structure for the same grammar in every
supported target (Java, JavaScript, TypeScript?, Dart, Go, Python...), with per-target runtimes
(antlr4 npm for JS, the antlr4 Dart package on pub). The parse tree shape for the same grammar is
target-equivalent. THE OPPORTUNITY: one grammar → N targets generated mechanically; one CST-shape
adapter per target (the walker differences are target-syntax, not grammar semantics); one parser-worker
interface (parse: source → CST) implemented per host against the per-target runtimes.

PLAN QUESTIONS:
1. The build: how does the gen task gain targets (Java/JS/Dart per grammar, pinned and checksummed)? The
   antlr4 tool invocation per target, the runtimes (npm antlr4, pub antlr4/dart), the versioning.
2. The parser-worker interface: ONE canonical parse protocol (source → CST rows/shapes the CST→UAST
   converter consumes) with per-host/per-target adapters (the JVM Java target, Node JS target, Dart
   target). Where does the interface live (yang/antlr/packet.cljc already exists — extend or neighbor)?
3. The CST equivalence story: how do you verify the JS/Dart parse trees feed the SAME CST→UAST conversion
   (parity tests: the same Python source → the same UAST/program on all three hosts)?
4. The generalized multi-language story: what does adding Go/TypeScript/etc. look like AFTER this design
   lands — enumerate the steps and show they're mechanical (the owner's explicit requirement).
5. The catalog/catalog-SPI changes: :yang.python/antlr on all hosts; the JVM-only gating retires.
6. The legacy Python compiler: its retirement becomes possible once the ANTLR pipeline works on all three
   hosts — sequence the retirement (separate round, gated).
7. What Python-specific code must NOT move into the generic layer (the CST→UAST conversion is
   per-language by design — the boundary statement).
8. Risks: the antlr4 target runtimes' cljc/cljs/cljd interop friction, bundle size, the npm/pub
   dependencies' licence and pinning, the CI matrix cost.

READ FIRST (the prior designs — the owner: "there's already a plan"): docs/design/yang.typescript.md
(the TypeScript and JavaScript frontend — Fable, a nine-round design loop, READY; its parse-pipeline and
yang.core-lift designs likely cover the JS parse story the multi-target needs) and docs/design/yang.php.md
(the companion, sharing the section-2 prerequisites and the section-17 yang.core lifts). ALSO:
yang.antlr.md sections 3-5 (the SPI, ingestion, the ANTLR host boundary); src/cljc/yang/antlr/packet.cljc;
src/cljc/yang/frontend.cljc (the F1 catalog); src/clj/yang/python/antlr/parser.clj (the JVM worker);
build/antlr (the vendored grammars, the Java generation); bb.edn (the gen task); the implementation
requirements statement collab/1792282000000-engineer-antlr-multi-target.prompt.md; the antlr4 runtime
availability (npm antlr4; the Dart antlr4 pub package).

THE RECONCILIATION MANDATE: the plan must RECONCILE with the TS/PHP designs, not reinvent them — where
yang.typescript.md already specifies the JS-side parse pipeline, theantlr-for-JS story falls out of THAT
design (the Python frontend composes with the same mechanism); where the section-17 yang.core lifts
already factor shared infrastructure, the antlr multi-target reuses those lifts. Identify explicitly:
(a) what the TS/PHP designs already cover for antlr multi-target, (b) what is genuinely new for
Python-on-JS/Dart, (c) what generalizes to future languages per the owner's requirement.

DELIVERABLE: the plan (the mechanism per host, the generalized add-a-language story, the CST equivalence
story, the catalog changes, the risks), the acceptance contracts per slice, and the implementation slice
specification (what the first engineer round builds, in what order). The design must make "add a new
antlr-supported language" a bounded mechanical recipe, and the Python multi-host work must fall out of
the general mechanism, not be a special case.

Begin the final response exactly with:
Completed-GMT / Completed-Local
EOF
python3 - <<'PY'
p='/Users/sto/workspace/datomworld/collab/1792289000000-architect-antlr-multi-plan.prompt.md'
s=open(p).read()
import re
m=re.search(r'FB2_PLACEHOLDER', s)
s=s.replace('FB2_PLACEHOLDER','BY-ORCHESTRATOR-AT-DISPATCH (fable claude-cloud session; capture and promote)')
open(p,'w').write(s)
print("written", len(s), "chars")
PY
ADDENDUM (owner, live): "maybe an antlr intermediate representation is needed if antlr doesn't have that
concept already." Fold this into the design explicitly: antlr4 parse trees for the same grammar are
structurally equivalent ACROSS targets but their OBJECTS are target-specific (Java ParseTree objects,
JS objects, Dart objects). Today the JVM worker converts the Java parse tree to cljc data inline.
The plan should name and design the TARGET-NEUTRAL PARSE-TREE IR as a first-class concept: the
per-target adapters (Java/JS/Dart) each translate their target's parse tree into ONE cljc IR (the CST
rows/shapes the CST-to-UAST converter already consumes), with the IR schema versioned and parity-tested
per target (the same Python source produces the same IR on all hosts). Rule on: does the IR live beside
packet.cljc, is it the existing CST shape formalized or a new schema, and what the IR buys for the
generalized add-a-language story (the UAST converter consumes the IR host-neutrally; per-language
converters are written against the IR, not against any target runtime).

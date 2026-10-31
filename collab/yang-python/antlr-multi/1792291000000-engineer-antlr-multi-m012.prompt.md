Created-GMT: 2026-10-16 21:30:00 GMT
Created-Local: 2026-10-17 04:30:00 +07 (+0700)
Coding-Agent: claude
Session-ID: ad86640d-2ac2-47b5-a852-374749809d7b

# Task: ANTLR multi-target — slices M0, M1, M2 (the plan gates this round)

Role: Yang Compiler and Universal AST Engineer

Implementers:
- Model: claude sonnet-5.5 | Assigned: 2026-10-17 04:30:00 +07 (+0700) | Status: active | Rationale: the antlr build integration and the frontend context you built

WORK TREE: /Users/sto/workspace/datomworld-antlr-multi (branch antlr-multi, based on origin/master 548f9faa).
Do not touch other worktrees (datomworld-c4fix, -c4hang, -c4-i6 are in use). Do not stage or commit.

THE PLAN (binding, gates this round):
collab/1792289000000-architect-antlr-multi-plan.claude-cloud-fable.findings.md — the Architect plan for the
ANTLR multi-target (JS and Dart parsers for the Python frontend, and the generalized add-a-language story
with the target-neutral parse-tree IR). Build slices M0, M1, M2 in order; M3 (legacy retirement) is a
SEPARATE gated round — do not remove anything this round.

SLICES (the plan's section 11 table):
- M0 build: manifest :targets, generator loop, JS and Dart generation with the ported positioned-token
  patches, digests, gen:antlr, package.json/pubspec.yaml pins, :cljs extra-path, lane :depends. Java
  digests byte-identical to today; JS and Dart artifacts generated, digest-pinned; bb gen:antlr
  reproducible from clean; npm ci and pub get resolve 4.13.2.
- M1 IR + exporters: packet.cljc owns the v1 shape doc and conform; cst.cljs/cst.cljd siblings; the
  shared parse-transform; yang.antlr.conformance; cst_export_test → cljc. The conformance goldens green
  on JVM, Node, Dart including the UTF-8 span fixture and error packets.
- M2 Python on three hosts: parser.cljc; the probe split; frontends.cljc gate removed, revision bump,
  :yang.grammar/targets; python_antlr_test langs and the parity test on all hosts; e2e and the
  comprehension line on Node and Dart; the section-6 doc amendments.

ACCEPTANCE (per slice, in the plan's table): the M0 digests byte-identical for Java; the M1 conformance
goldens green on JVM, Node, Dart including the UTF-8 span fixture and error packets; the M2 layers green
on three hosts, the comprehension line evaluating at the Node and Dart prompts, quick tiers green per
host, kondo + cljstyle clean.

RISKS (the plan's section 12 — read them): the UTF-16/code-point indexing divergence (the exporter must
open code-point streams or map units; the é𝄞 fixture guards), the Dart target untested upstream (the
desc.xml excludes Dart; the helper may lag; the pub SDK bound), the circular ESM import in shadow-cljs
(the fallback :js-options :resolve), the four more helpers without notices (:redistribution
:blocked-until stays), the antlr4 npm entering only the node-script and test builds, and the chunked-seq/
reader-conditional traps on Dart.

LANES: foreground, one at a time, waiting; no background runs, no watchers, complete the turn. bb
gen:antlr/build steps per the plan; bb test:clj, test:cljs, test:cljd per iteration; full three-host
quick tier at each landing. mise is trusted; node_modules installed; gen/build ran once at creation.

Begin the final response exactly with:
Completed-GMT / Completed-Local

Report per-slice completion, the CST-parity evidence, exact counts per lane, anything behavioral,
incomplete work.

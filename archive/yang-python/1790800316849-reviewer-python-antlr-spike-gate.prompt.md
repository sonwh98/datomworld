Created-GMT: 2026-09-30 20:31:56 GMT
Created-Local: 2026-10-01 03:31:56 +0700
Coding-Agent: codex
Session-ID: pending (provider-generated)
# Task: Gate — Python ANTLR spike (phases A+B): parser stage, CST export, scope analysis, lowering, prelude

Role: Adversarial Code Reviewer and Security Auditor

Implementers:
- Model: gpt-6-sol | Assigned: 2026-10-01 03:31:56 +0700 | Status: active | Rationale: standing gate route; Claude-authored (claude-opus-5-5); largest unit yet, semantics-heavy

Read-only review in /Users/sto/workspace/datomworld-py-spike (branch yang-python-antlr-spike at local commit fe8bce4a ==
origin/master; the spike is uncommitted: deps.edn, bb.edn modified; new antlr/python3/manifest.edn, src/dev/yang_antlr_gen.clj,
src/clj/yang/antlr/cst.clj, src/clj/yang/python/antlr/parser.clj, src/cljc/yang/antlr/packet.cljc,
src/cljc/yang/python/antlr/{stage,uast,scope,lower,prelude,render}.cljc, and tests under test/yang/python/antlr/).
Do not edit.

OWNER (verbatim): "dispatch the python spike in parallel too"; "dispatch spike phase B in parallel now"; "let's go with
your recommendation" (plan item 1: land phase B). Owner direction (verbatim, binding): "integrating antlr should be
straight forward. antlr will construct an AST that gets mapped to yin.vm universal AST"; "if yin.vm universal AST has
continuations, all control flow can be mapped to continuations"; "compilers have a pipeline of transformation. yang/yin.vm
compilation pipeline is dynamic where interpreters read from dao.stream and make transformation onto another dao.stream.
any number of interpreters can attach to those dao.stream to do more transformation of its own".
Briefs: /Users/sto/workspace/datomworld/collab/1790795118566-compiler-engineer-python-antlr-spike.prompt.md (phase A + PHASE B)
Report (untrusted): /Users/sto/workspace/datomworld/collab/1790795118566-compiler-engineer-python-antlr-spike.claude-opus-5-5.report-phaseB.md
Rulings: /Users/sto/workspace/datomworld/collab/{1790773810605-architect-cell-primitive..., 1790776815400-...findings-r2.md,
1790778866412-architect-mutable-objects..., 1790797984227-architect-python3-mappability...}.findings.md

Orchestrator-verified (do not rerun): cljstyle run per file (4 files reformatted by the orchestrator, whitespace only);
kondo 0/0 on all 16 files. Full JVM/Node/CLJD lanes running in the orchestrator's seat; results relayed.
Implementer-claimed: JVM 2635/186960/0; Node 2485/52830/0; 26 e2e programs on all four VMs over the real cell and data
modules; bb gen:python-antlr from clean verifies grammar and generated digests.

Check: correctness of the lowering vs Python semantics for the claimed subset (scope: locals/params/global/nonlocal,
forward capture, class scopes; evaluation order; short-circuit; comparison chains; loops/break/continue/else;
try/except/else/raise/re-raise; escapes restoring the handler stack; flag-cell first-pass detection; function objects,
defaults, *args, arity TypeError; classes, attribute lookup, identity; float tagging, numeric equality/ordering, dict key
normalization and insertion order; NameError via the module dict); unknown/unhandled rules fail loudly (no silent drop);
determinism (no gensym, stable ids); no ANTLR object leaks past cst.clj; no host-map iteration in the prelude; no
continuation-representation test anywhere (D7 readiness); prelude uses only declared host names (cell/*, data/*) and
the D4 effect path; build reproducibility and the generation task (network fetch at build time — acceptable?); the
upstream Java helper patch and the unresolved helper licence; stream-stage driver outcome handling (full/blocked/end).
Rule explicitly on:
Q1. The grammar is fetched over the network by bb gen:python-antlr and test:clj now depends on it. Acceptable for CI/offline, or vendor?
Q2. Every unit bundles its own prelude (per-unit builtin class identity). Acceptable to land as a spike?
Q3. The two Java helper files lack licence notices (nothing redistributed). Block landing, or record?
Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Findings as P0-P3 | file:line | evidence | concrete fix, or "No actionable findings". Answer Q1-Q3; mark owner decisions.
End with Verdict: READY / REQUEST CHANGES and Sign-off: GRANTED / WITHHELD.

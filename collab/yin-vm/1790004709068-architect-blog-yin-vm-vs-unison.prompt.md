Created-GMT: 2026-09-21 15:31:49 GMT
Created-Local: 2026-09-21 22:31:49 +07 (Indochina Time)
Coding-Agent: codex
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a (resumed — your de Bruijn projection design, review and sign-off thread)
# Task: architect review — blog post "One Truth, Many Interpretations: How yin.vm Solves Unison's Semantic Dilemma"
Role: Architect
Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-21 22:31:49 +07 | Status: active | Rationale: owner requested the architect review; you authored the design the post describes and reviewed every implementation phase, so you can judge the post's claims against what actually exists

Read-only. Work in /Users/sto/workspace/datomworld (branch master; the de Bruijn
implementation is now MERGED at 44f0ded0 — the code is on master). Give the
complete review now as your final response; do not wait for approval and do not
promise a verdict.

## Subject

public/chp/blog/yin-vm-vs-unison.blog (untracked, a fresh draft by the owner).
It contrasts Unison's nameless-AST / de Bruijn / content-addressed model with
yin.vm's dual architecture (named Universal AST as source of truth + de Bruijn
projection as derived data for the Merkle fingerprint). File format rules:
docs/agents/file-format.md (`.blog` EDN/Hiccup). Read docs/design/datom.world.md
for the project's axioms and non-negotiable invariants.

## Ground truth to judge the claims against (do not trust the post; read these)

- docs/design/yin.vm.debruijn-projection.md (the CURRENT master copy, with the
  §5 amendment), and the merged code: src/cljc/yin/vm/debruijn.cljc,
  src/cljc/yin/vm/pipeline.cljc and their tests.
- docs/design/yin.vm.semantic.md, src/cljc/yin/vm/linearize.cljc,
  src/cljc/yin/vm/engine.cljc (how the VM actually executes), and the
  dao.space / dao.jing / dao.space.query sources for what queries exist.
- The orchestrator log tail (docs/orchestrator-log.md) for the implementation
  record and the known limits.

Leads I found while preparing this brief — VERIFY each yourself, they are
suspicions, not conclusions:
1. The post says renaming a variable in yin.vm "outputs the exact same
   fingerprint, so mathematical identity is preserved" and sets that against
   Unison's "instant refactoring". But the projection preserves FREE names
   exactly (`{:free name}`; §8 row "changed free names changing the
   fingerprint"). Unison replaces global references with hashes; yin.vm's
   projection does not. So does renaming a global/function change every
   caller's fingerprint in yin.vm? Is the comparison to instant refactoring
   sound, and is anything in the post about references or dependencies
   misleading?
2. The post says both the Named AST and the projection are "stored natively as
   decomposed relational facts (datoms/tuples)" and that a Datalog query via
   `dao.space.query/q` grouping by the de Bruijn root hash finds duplicates.
   As merged, D5 persists the projected envelope as a content-addressed
   DaoJing segment, and (I found) nothing in src queries projected records.
   Is that Datalog claim true today, aspirational, or true only with
   further work? What exactly exists?
3. The post says the semantic VM "(the ast-walker) executes" the named AST.
   The VM executes linearized :yin.code/* instructions lowered from the named
   AST (name-keyed :var/:load-var, env is a merged name map). Is the post's
   VM description accurate? It also says stack traces and debuggers get real
   developer intent — supported by :yin.code/source provenance, or overclaimed?
4. Claims about Unison (nameless AST, de Bruijn indices, hashes replacing
   global references, "strictly a key-value blob store", structural queries
   "impossible", "the AST forgets why a variable exists", "fragile UI trick"
   side dictionary). You cannot verify these against the repo; say from your
   own knowledge which you believe accurate, which overstated or wrong, and
   flag every one you cannot confirm as UNVERIFIED rather than guessing.
   Unison's codebase has indexes beyond a blob store (e.g. dependents and
   type-based search) — is "impossible" fair?
5. "Interpretation Creates Semantics (one truth, many perspectives)" is cited
   as a core axiom — check it against the project's actual axioms.
6. The conclusion claims yin.vm "captures the global distribution and
   deduplication benefits of Unison". What is actually implemented (D0-D6)
   versus what is designed or absent (global distribution, dependency-by-hash,
   cross-node code sharing)?

## Deliverable

A review a careful editor can act on. Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a
then a one-line verdict (PUBLISHABLE AS-IS / PUBLISHABLE AFTER THE FIXES BELOW /
NEEDS REWORK), then findings as P1 (factually wrong or misleading about
yin.vm), P2 (overclaims about what exists today; unfair or unverifiable claims
about Unison), P3 (wording, framing, format). For each: the quoted sentence,
what is true (with a file path or section as evidence, or UNVERIFIED for
external Unison facts), and a concrete replacement sentence. End with what the
post gets right that should be kept, and any missing section a fair
comparison needs (e.g. references by hash vs by name, distribution). Findings
only: edit no file, including the blog.

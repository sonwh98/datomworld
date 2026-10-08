Created-GMT: 2026-10-02 23:40:00 GMT
Created-Local: 2026-10-03 06:40:00 +07 (+0700)
Coding-Agent: claude
Session-ID: 48b52ba5-9c0f-4f87-b2b5-270cea5165c7

# Task: Record the converged C4 rulings in yang.antlr.md

Role: Scoped Writer (docs)

Implementers:
- Model: claude opus (opus-5.5) | Assigned: 2026-10-03 06:40:00 +07 (+0700) | Status: active | Rationale: same writer line as the earlier yang.antlr.md ruling records; doc-only change

WORK TREE: work ONLY in /Users/sto/workspace/datomworld-yang-doc (branch docs-yang-antlr-c4, based on master
c3f2da8f). Edit ONLY docs/design/yang.antlr.md. Do not touch any other file, the main tree, or other worktrees.
Do not stage or commit.

SOURCE MATERIAL (this worktree's collab/):
1. 1790974400000-architect-python-c4-design.claude-fable-5-1.findings.md — the C4 design (imports over the landed
   linker, the linked prelude, the REPL frontend catalog/SPI; slices I1-I7).
2. 1790975400000-architect-c4-crossruling.gpt-6-astra.findings.md — the astra cross-ruling with the CONVERGED
   rulings list (1-19) at its end; the converged list is binding where it differs from the design's own
   recommendations.

TASK: record the C4 rulings in yang.antlr.md following the document's conventions (see how 8.5.2-8.5.5 record
their rulings; a new subsection under the appropriate section — likely 8.5.6 or a C4 section in §8/§12 — is your
call, justified in the report). Requirements:
- Every recorded statement traces to the design or the converged rulings; the converged list wins on conflicts.
- Cover at minimum: the pure-install/instantiate split (incl. the yang.antlr.md:1414-1417 amendment the design
  asks for); the task-local builtin namespace with global fallback; the py/pysp/pym namespace reservation and
  injective mangling; hoisted pinned requires under the eager-dependency restriction with lazy module bodies;
  bundled-mode retention until the twelve-lane migration AND float64 address gates pass; the AST-walker linked
  support + cross-module store-context restoration requirement; host-export semantic profiles by manifest
  address (integer limits in the profile); refusal-as-data linking and statement-time import resolution;
  delivery-cycle refusal then SCC units; static-literal import names only; literal import_module + same-image
  reload; static relative imports; deferred auto-import; safepoint site sets published as datoms; frontend marks
  retained; immutable frontend catalogs with explicit parser services; content-addressed lowering profiles;
  persistent __main__ with (reset) as a new process; foreign-principal imports deferred until D7 slice B.
- The slice sequence (I1-I7) with the migration gate (twelve lanes + float64 gates) stated.
- Everything pending-labelled (C4 is not implemented; nothing has landed).
- ASCII, <= 80 columns, consistent tables; note any Open: questions instead of forcing them.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +0700>

Report the sections touched with one-line summaries and the ruling-to-section mapping.

Created-GMT: 2026-09-30 20:30:08 GMT
Created-Local: 2026-10-01 03:30:08 +07 (+0700)
Coding-Agent: claude
Session-ID: c0d666a1-b094-4942-abab-5698a77445be
# Task: Record the Architect rulings of 2026-09-30/10-01 in docs/design/yang.antlr.md

Role: High-Throughput Subagent Worker

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-01 03:30:08 +07 (+0700) | Status: active | Rationale: faithful doc transcription of settled rulings; no design decisions

Perform a documentation edit in /Users/sto/workspace/datomworld-antlr-doc (branch docs-yang-antlr-rulings from master
fe8bce4a). Do not touch other worktrees. Do not stage or commit.

OWNER (verbatim): "let's go with your recommendation" — accepting the orchestrator's plan, whose item 3 is: record in
yang.antlr.md the rulings it does not yet reflect.

Authorized files:
- docs/design/yang.antlr.md (only)

Sources (copies in this worktree's collab/; they are the authority; do not add anything they do not say):
- 1790773810605-architect-cell-primitive.claude-fable-5-1.findings.md (cells: sealed :cell-ref over task :heap, cell module)
- 1790776815400-architect-mob-outstanding-decisions.*.findings-r2.md (D1-D10; D3 amends §8.1)
- 1790778866412-architect-mutable-objects.claude-fable-5-1.findings.md (objects/collections over cells; data module)
- 1790797984227-architect-python3-mappability.claude-fable-5-1.findings.md (mappability; module dicts; float tags;
  safepoints attached; traceback lines derived)
Owner decisions accepted on all of them (verbatim each time: "accept all recommendations" / "yes").
Landed on master: captured continuations invocable (8f9f90b0), host-typed effects D4 (9a69e58f), cell slice 1
(5e790683), pure data host module (fe8bce4a).

Acceptance criteria:
- §8.1: the baseline is a task-owned heap of cells updated through persistent VM-state transitions (D3); state threading
  stays an allowed per-frontend choice; "store cells"/"binding cells" elsewhere defined as `cell` module refs; F2
  contradiction resolved. Every function-local binding and parameter is a cell (D5); un-boxing is an optional attached
  interpreter.
- Mutable objects/collections section consistent with the mutable-objects ruling (one cell per object; identity = the
  ref; immutables uncelled; aliasable slots; PHP/Go mappings; prelude placement; no host-map iteration; key
  normalization) — placed where §8/§9 discuss object systems, citing the ruling.
- §9 standard library: pure data primitives live in a :pure host module named by the language runtime profile, not
  vm/primitives (yin.vm.data).
- Python (§8.5) and a new mappability subsection: the headline (nothing in the language reference is inexpressible) and
  the corrected bucket table, plus the owner decisions (module namespace = heap dict; traceback lines derived at the
  boundary by default; float tagging; safepoints as an attached interpreter).
- Effects: note D4 (effects are host-typed, callee profiles bound them) where the doc describes effects/primitives.
- Status line updated (date, what landed). Cite each ruling by collab filename the first time it is used.
- Format: ASCII box tables only (never pipe tables), <=170 columns, per docs/agents/format.md; no em dashes; ASCII text.
- Report every section changed and every place you chose NOT to change something the sources might imply.

Preserve unrelated text. Do not broaden file scope or make architectural decisions; where sources conflict or are
silent, leave a clearly marked "Open:" note instead of deciding. Run in the FOREGROUND: a check that no line exceeds
170 columns, no pipe tables were introduced, and no non-ASCII was introduced (report the commands and output).

Write the report to /Users/sto/workspace/datomworld-antlr-doc/collab/1790800208474-subagent-yang-antlr-doc-rulings.claude-opus-5-5.report.md
and give it as your final response, beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: c0d666a1-b094-4942-abab-5698a77445be

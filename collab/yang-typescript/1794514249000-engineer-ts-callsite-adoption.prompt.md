Created-GMT: 2026-11-12 20:10:49 GMT
Created-Local: 2026-11-13 03:10:49 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: interactive (ZCode / GLM seat — yang.typescript orchestrator)
Session-ID: C10B089C-226A-4F67-BDA3-0F9916FD93DF
# Task: ts callsite-provenance adoption — :yang/call-site v1 on every final :application occurrence

Role: Yang Compiler and Universal AST Engineer

Implementers:
- Model: qwen3.8-max | Assigned: 2026-11-13 03:10 +07 | Status: active | Rationale: the standing muse lane is quota-dark to ~Nov 16 (routing-status); qwen3.8-max is live and execution-capable; reviewer independence holds (GPT-family sol reviews the Qwen author)
- Status-Event: (lineage) | Model: (prep) | Status: superseded | Rationale: this brief finalizes PREP-DRAFT 1794480000000 (same requirements; the prep file's gates resolved — cc3 merged, php-precedence relaxed by owner word, brief-writing delegated to this seat by the steward)

Implement the ts callsite-provenance adoption in the worktree
`/Users/sto/workspace/datomworld-callsite` (branch `callsite-ts-adoption`, cut from master
`b134207f`). Lineage note: this finalizes the PREP-DRAFT
`/Users/sto/workspace/datomworld/collab/yang-typescript/1794480000000-engineer-ts-callsite-adoption.PREP-DRAFT.md` — same requirements; that file's gate notes are historical.

## Read first (collab/docs paths are in the MAIN checkout `/Users/sto/workspace/datomworld`; source/test paths are IN the worktree)

- `/Users/sto/workspace/datomworld/collab/yang-clojure/1794670000000-architect-callsite-provenance-generalization.gpt-6-astra.findings.md` — the DELIVERED DESIGN (astra, 64KB).
- `/Users/sto/workspace/datomworld/test/yang/clojure_callsite_test.cljc` — the REFERENCE implementation; mirror its shape (the equality gate, validation failures, safepoint-independence, canonical invariance; 32 deftests).
- `/Users/sto/workspace/datomworld/src/cljc/yang/core/uast.cljc:116-175` — the GENERIC core you consume: `annotate-call-sites`, the reserved key `:yang/call-site` version `1`, coordinate tuple `[origin root-address path :yang/call-site 1]`, the module publication wiring. THIS ALREADY EXISTS — do not re-mint or modify it.
- `/Users/sto/workspace/datomworld/src/cljc/yang/core/call.cljc` — the C1 contract key.
- `/Users/sto/workspace/datomworld/collab/yang-call/1794980000000-orchestrator-two-relays-equality-and-h36-audit-inputs.md` (part 1) + `/Users/sto/workspace/datomworld/collab/yang-call/1795010000000-orchestrator-reply-equality-gate-per-frontend.md` — ADOPTED RULING: the cross-host occurrence-key EQUALITY GATE is part of this frontend's own acceptance (same source on the ts lanes → identical occurrence coordinates; never cross-frontend).
- `docs/design/yang.antlr.md` §8.10/§8.10.7 and `docs/design/yang.typescript.md` §16 (worktree).

## Requirements

1. **Every final `:application` occurrence on PRODUCTION compilation paths** carries the
   reserved key `:yang/call-site` version `1` in the ts frontend-metadata side table — the
   coordinate tuple exactly as the core defines it. Wire the ts lowering/prelude to emit into
   the EXISTING generic side table; do not invent a parallel mechanism.
2. **Provenance stays OUT of hashed content** (unitarity invariant): the UAST remains
   retrievable; markers publish beside the manifest as the core already arranges.
3. **Equality gate (acceptance-critical)**: the same ts source compiled on the ts lanes yields
   IDENTICAL occurrence coordinates (JVM/Node/Dart as applicable to your focused gates; the
   orchestrator runs the full lane matrix on shiva/jupiter at acceptance).
4. **Tests mirror the reference shape** in ts-side namespaces under `test/yang/` — including
   the export-audit pin idiom (module-exports/runtime-keys assertions; this is now the pattern
   of record — the python adoption's F2 finding mirrors it).

## Collision + drift cautions

- The exception-track VM primitive reserved doors `cell/dsg__new-provenance` /
  `cell/dsg__provenance` (landed `8c10c5b7`) are a DIFFERENT key family — never reuse or
  re-mint them; cite the distinction in your test headers.
- cc3 landed (`2c80a421`) — the occurrence contract and oracle C1 bridge are master state;
  your base `b134207f` already carries them. If anything in core looks mid-flight, STOP and
  report rather than adapt it.
- "Derive, don't persist": if any fact you are about to add is query-derivable over dao.space,
  the astra design's side-table decision already governs — flag any perceived deviation as a
  STOP condition, do not redesign.

## Surface bounds

Edit ONLY: ts frontend files under `src/cljc/yang/typescript/` (lowering/prelude metadata
emission) + ts-side tests under `test/yang/typescript/` + new callsite test namespaces under
`test/yang/`. The generic core (`src/cljc/yang/core/`, `src/cljc/yang/clojure.cljc`) is
READ-ONLY for you — it is complete; if you believe it needs a change, STOP and report.

## Gates (focused only, laptop; suite-scale belongs to the orchestrator on shiva/jupiter)

- G0 iterate: `clojure -M:test -n <your-new-callsite-ns>` and the touched ts namespaces
  (`-n <ns>`, single test `-v <ns>/<var>`).
- Your round's own regression set MUST stay green: `typescript-provenance-test` + the five
  forgery legs (6/48) and `float-address-test` (5/20) — run them before reporting.
- Author RED tests first where the current behavior is wrong (TDD), then make them green.

## Commit + deliverable

- Commit on `callsite-ts-adoption` (`feat(yang.typescript): the callsite-provenance adoption —
  :yang/call-site v1 on production paths`). Do NOT merge, do NOT push.
- If the sandbox blocks writes to `/Users/sto/workspace/datomworld/collab/`, stage your findings
  at the WORKTREE ROOT with the canonical name and say so — the orchestrator copies it.
- Findings file (canonical path):
  `/Users/sto/workspace/datomworld/collab/yang-typescript/1794514249000-engineer-ts-callsite-adoption.qwen3.8-max.findings.md`
  Begin with Completed-GMT/Completed-Local/Coding-Agent/Session-ID; then: files+line ranges,
  exact commands with counts, the equality-gate evidence you could gather locally, unrun
  checks, unresolved concerns. Do not claim edits or tests that did not occur.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Created-GMT: 2026-09-29 19:07:25 GMT
Created-Local: 2026-09-30 02:07:25 +07 (+0700)
Coding-Agent: codex
Session-ID: 01a0ee90-a2a6-7c40-a6d2-1940c863a2e8 (captured)
# Task: Architect design — yin.repl $ast row relation (dedicated AST indexer) and exposing it to q

Role: Lead System Architect

Implementers:
- Model: gpt-6-sol | Assigned: 2026-09-30 02:07:25 +07 (+0700) | Status: active | Rationale: OWNER INSTRUCTION (verbatim) "queue the $ast row relation too"; the design names the job but not the yin.repl mechanism, so an Architect pass precedes implementation (fable reserved by owner)

Read-only; no edits; headless — your final response is the deliverable. Master 04378221 (+ an uncommitted yin.repl
rendering change, irrelevant here). Implementation is queued after two in-flight query/REPL slices.

Context:
- docs/design/yin.vm.code-as-tuples.md: canonical flat rows [id tag & slots] (§4, §6.1), the $ast row relation and its
  derived occurrence relation (search "Dedicated AST Indexer", ~922), §7.1 (a dao.stream observer peer to the evaluators,
  distinct from dao.space.index which indexes datoms only; a projection keeper — discarding it loses nothing).
- docs/design/yin.repl.dao.space-index.md ("Indexed facts": maintain the $ast row relation and its derived occurrence
  relation from these rows; do not relabel variable-arity rows as five-slot datoms).
- What exists: yin.repl.index (fd3f0edd) — datom projection of each expanded [root rows] packet on program-out into
  dao.space covered indexes published to dao.jing; yin.repl.query (643b1ba6, 04378221) — dao.space.query/q over the
  latest published manifest, implicit $, :in inputs, {:view :current|:history}; the free-names recursive rules already
  run over rows in test/dao/space/query_test.cljc ~1121-1161 (q over yin.vm/ast->semantic-bytecode output).
- Owner-observed pain: :yin/operands is one vector value in the datom projection, so structural queries (operands,
  free/bound variables, call graph, reachability) are awkward; collection bindings ([?x ...]) are queued as a stopgap.
- Invariants: docs/design/datom.world.md; peer observers on one stream with symmetric ignorance; derive, don't persist;
  no hidden global state; yin.vm ignorant of dao.space.

Rule precisely enough to implement without another design round:
1. Observer: where the $ast indexer lives (yin.repl namespace), what it reads (program-out packets), what it keeps
   (rows + occurrence relation), its gap/reset/lost discipline consistent with yin.repl.index (an index gap never stops
   evaluation; owner ruling), and whether it shares or is independent of the datom indexer.
2. Storage/persistence: in-memory relation only, or also published to dao.jing (content-addressed rows are already
   content; what, if anything, needs a manifest)? Respect "derive, don't persist".
3. Query surface: how q reaches it — a second named source ($ast) alongside the implicit $ (the owner kept $ implicit),
   e.g. :in $ast or always-bound; variable-arity row patterns; the occurrence relation's name and shape; current vs
   history semantics; refusal when lost; limits (reuse the q bridge's row/byte limits).
4. Rules: should the shell ship standard rules (free-vars, call graph, reachability) as a rule set, or leave rules to
   users? Where would they live?
5. Files, acceptance tests (incl. the free-names rules over REPL-evaluated code, multi-program sharing of identical
   rows with distinct occurrences, reset, lost), CLJ/CLJS/CLJD portability, and slicing if large.
6. OWNER decisions, listed separately.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Then answer 1-6 with file:line evidence.

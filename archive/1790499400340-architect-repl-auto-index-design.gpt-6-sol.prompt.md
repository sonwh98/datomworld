Created-GMT: 2026-09-27 16:00:00 GMT
Created-Local: 2026-09-27 23:00:00 +0700
Coding-Agent: codex
Session-ID: pending

# Task: Design — yin.repl evaluations auto-index into dao.space

Role: Lead System Architect

Owner direction, verbatim: "as the user evaluates code in the REPL, it
should be automatically indexed in dao.space by the
dao.space.index/transactor!" Context: the owner shared the feature
"Datalog queries on the semantic AST from yin.repl"; the indexing half
is now ruled automatic. Saved as memory
project_repl_eval_auto_index.md.

Read first:
- docs/design/yin.vm.code-as-tuples.md (the AST as datoms [e a v t m])
- docs/design/yin.vm.semantic.md (the semantic vector and its storage)
- src/cljc/dao/space/index.cljc and query.cljc (the two interpreters)
- src/cljc/yin/repl.cljc (the shell: create-state, the eval path, the
  link-policy seam just adopted; its docstring codifies minimalism:
  no clocks, no callbacks, no global atoms)
- test/dao/space/query_test.cljc's free-names queries (Datalog over
  AST rows already proven)
- docs/design/dao.stream.remote.implementation-plan.md section 1 (the
  content service this composes beside)
- Memory notes: project_repl_eval_auto_index, project_shibi_tuple_space,
  project_peer_observers_one_stream

Design the seam. Answer:
1. WHERE the observer composes: on yin.repl's evaluation stream (what
   exactly is the stream: the link-pair? the medium evaluators read?)
   so that every evaluated program's semantic AST datoms are
   transacted into dao.space automatically. Name the exact composition
   point with file:line from the current tree.
2. WHAT gets transacted: the AST rows of the evaluated program (which
   identity, which format records), provenance (who minted: the
   session, per the metadata slot), and whether results also land
   (recommend: no by default -- code and claims, not ephemeral
   results).
3. HOW q surfaces: the still-open sub-question. The owner was unsure
   whether dao.space.query loads by default. Present both: (a) bound
   from the first prompt (batteries-included IDE posture), (b) opt-in
   via the user's own require (minimal core per the yin.repl
   docstring). Recommend one with rationale; the owner rules.
4. Cost: what this adds to session startup and per-eval latency, and
   why it is acceptable (the index is a stream observer; an idle
   session pays nothing).
5. Scope boundary: what this is NOT (not ShiBi; not the dao.jing
   content service; no new wire shapes) and which slices of the
   dao.stream.remote epic it must not disturb (it composes beside
   them; file sets are disjoint).

Deliverable: a design document, docs/design/yin.repl.dao.space-index.md
(new file, ASCII, <= 80 cols, the repo's design-doc voice), sized like
dao.shibi.md (concise; a stub with the load-bearing decisions, not a
treatise). Write ONLY that file.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Summarize the design: the composition point, the transacted set, the
q-surface recommendation, and the cost statement.

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>

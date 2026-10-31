Created-GMT: 2026-09-30 06:06:49 GMT
Created-Local: 2026-09-30 13:06:49 +07 (+0700)
Coding-Agent: claude
Session-ID: d4ec2cf2-f45d-4f5a-8a43-572555b2a216
# Task: $ast row relation — slice 2: the q bridge supplies $ast / $occ when named in :in

Role: Yin.VM Runtime Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-09-30 13:06:49 +07 (+0700) | Status: active | Rationale: owner "commit it when green, then dispatch slice 2"; fresh session (slice-1 session large), same model family as the bridge's author

Work in /Users/sto/workspace/datomworld (master 48ec9d66, which contains slice 1). Do not stage or commit. Run every
check in the FOREGROUND. A separate worktree (datomworld-durable-index) is in flight — ignore it.

GOVERNING DESIGN: collab/1790708845000-architect-repl-ast-row-relation.gpt-6-sol.findings.md — section 3 "Query
surface" is this slice. OWNER APPROVAL (verbatim selected option): "Approve all three (Recommended)" — (1) $ast/$occ
supplied by the bridge only when named in :in; (2) in-memory session projections, same under :current and :history,
not published to dao.jing; (3) rules stay opt-in.
Slice 1 surface to consume unchanged: yin.repl.ast-index relations (returns {:ast :occ} or nil when lost/failed),
available?, status (src/cljc/yin/repl/ast_index.cljc; commit 48ec9d66).

Scope (design section 3):
- In src/cljc/yin/repl/query.cljc (bridge parsing/binding ~307, view/limits ~357, refusal ~263): when a query's :in
  names $ast and/or $occ, the bridge supplies those session sources in declared order, alongside the implicit/explicit
  $ datom source. Remove only these exact source symbols (and $) when counting caller arguments; % and other patterns
  stay caller supplied. Queries without :in keep today's behaviour (implicit $ only).
- $ast rows are matched as exact, variable-arity [id tag & slots] patterns; $occ as [root path node]. Do not turn them
  into five-slot datoms. {:view :current|:history} still selects only the $ datom view; $ast/$occ are the same
  session-to-date relations under either view.
- Refuse a query naming $ast or $occ with :yin.repl.query/index-unavailable when the AST indexer is lost or failed;
  datom-only queries keep working. Keep the bridge's row and byte limits for mixed-source queries.
- The bridge must read the AST relations from CURRENT shell state at answer time (like it does for the manifest), never
  a snapshot captured at construction.
- dao.space.query should need no change; if it does (a concrete source-binding defect), STOP and report.
Allowed files: src/cljc/yin/repl/query.cljc, src/cljc/yin/repl.cljc (only to hand the AST indexer to the bridge at answer
time), test/yin/repl/query_test.cljc (or a new test ns under test/yin/repl/). Anything else: STOP and report.

Acceptance (test first; each must fail if broken; prove key ones by mutation, revert, grep), on all four VMs:
[:find ?name :in $ast :where [$ast ?id :variable ?name]] after (defn inc [i] (+ i 1)) returns the variable names;
$occ query returns occurrences; a $ $ast $occ join; caller inputs after the sources (:in $ast ?x with one arg); wrong
arity -> query-failed; lost/failed AST indexer -> index-unavailable for $ast queries while $-only queries still answer;
no :in -> unchanged; :history view with $ast; result limits apply. Portable CLJC (on CLJD #?(:clj ...) is NOT excluded —
#?(:cljd nil :clj ...) with :cljd first; no array-map; no cross-ns #'private access).

Verify and report, in the foreground: kondo; cljstyle check (say if blocked); focused JVM (yin.repl.query-test,
yin.repl.ast-index-test, yin.repl-test, dao.space.query-test); full clj -M:test; bb test:cljs; bb test:cljd. Write
collab/1790748409000-vm-engineer-repl-ast-index-slice2.claude-opus-5-5.report.md and give it as your final response, beginning
exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: d4ec2cf2-f45d-4f5a-8a43-572555b2a216

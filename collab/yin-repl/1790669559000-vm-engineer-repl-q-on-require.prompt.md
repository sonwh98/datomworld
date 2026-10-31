Created-GMT: 2026-09-29 08:12:39 GMT
Created-Local: 2026-09-29 15:12:39 +07 (+0700)
Coding-Agent: claude
Session-ID: c2557c35-ba40-4377-b51a-aad2152a95cb
# Task: yin.repl "q on require" — (require 'dao.space.query) activates dao.space.query/q over the code index

Role: Yin.VM Runtime Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-09-29 15:12:39 +07 (+0700) | Status: active | Rationale: owner-dispatched slice with an Architect design

WORK TREE: /Users/sto/workspace/datomworld-q-require (branch repl-q-require from master 3cf3c6de). Edit ONLY there. Do
not touch /Users/sto/workspace/datomworld (other work is in flight). Do not stage or commit. mise trusted, node_modules
installed. This brief lives at /Users/sto/workspace/datomworld/collab/1790669559000-vm-engineer-repl-q-on-require.prompt.md.

OWNER INSTRUCTION (verbatim): "dispatch the q on require slice".
OWNER DECISIONS (verbatim selected options):
- Name: "dao.space.query/q (Recommended) — Qualified name only, e.g. (dao.space.query/q '[:find ?n :where ...]). Uses
  existing module resolution; no new import rule."
- Limits: "Implementer proposes (Recommended) — Implementer picks named yin.repl constants with a rationale; the gate
  reviews them and I show you the values before commit."

GOVERNING DESIGN (authoritative; read in full):
/Users/sto/workspace/datomworld/collab/1790669186000-architect-repl-q-on-require.gpt-6-sol.findings.md — sections 1-5.
Also: docs/design/yin.repl.dao.space-index.md ("Query surface"), src/cljc/yin/vm/module.cljc (~190, ~414 host module
registration/require), src/cljc/yin/vm/ffi.cljc, src/cljc/yin/vm/engine.cljc (~60 qualified resolution, ~1321 FFI),
src/cljc/yin/repl.cljc (~527 make-session, ~1170 rounds, ~245 error printing), src/cljc/yin/repl/index.cljc (~232
publish/read-back, ~257/~298 lost/failure status), src/cljc/dao/space/query.cljc (q ~1604, collect ~1663, current/history
~209), test/yin/repl/index_test.cljc (~129 read-back query), test/yin/repl/require_test.cljc.
NOTE: master 3cf3c6de has the slice-3d composite FFI call ids ([caller-token park-id], :ffi-caller-id, FFI response
router in the engine, :call-out-cursor required for SUPPLIED pairs). Build the session's query call pair consistently
with that (e.g. a locally created pair keeps its own mint; if you supply a pair, supply its cursor).

Summary of the design: dao.space.query is a session-scoped host module, absent from the initial primitive map; the
user's (require 'dao.space.query) activates it through the existing require handler; its q export issues a data-only
request (query, view, portable :in inputs) over a dao.stream.apply call pair; a shell-owned interpreter answers it
from the CURRENT shell state: a snapshot of the latest successfully published manifest (:index-store +
:manifest-address), query/current by default, {:view :history} as an optional final options map. Refuse unless
:lost? false, no :failure, :published? true (empty session = valid empty db). Return query/collect's materialized
shape as portable yin data; errors via the FFI error envelope with stable keywords :yin.repl.query/index-unavailable,
:yin.repl.query/invalid-input, :yin.repl.query/result-limit (+ readable message, configured limit in the error).
Deterministic per-call row and encoded-byte limits as named yin.repl constants, checked before appending the response.
Before require the qualified export fails name resolution; (reset) removes it and a new require re-activates it.

Allowed files: src/cljc/yin/repl.cljc; ONE new namespace under src/cljc/yin/repl/ for the query bridge (e.g.
yin/repl/query.cljc); tests under test/yin/repl/. Changes to yin.vm/*, dao.space.query, dao.space.index, dao.stream.*
are a design regression: STOP and report the exact need instead.

Acceptance (all supported VM types; each must fail if broken; prove key ones by temporary mutation, revert, grep):
1. dao.space.query/q is unresolved before require; require activates it; bare q is NOT bound.
2. After evaluating a def, q returns its indexed name/facts (current view).
3. {:view :history} reaches session and round through m.
4. Lost indexer, failed publication, or unpublished index -> :yin.repl.query/index-unavailable; empty session -> empty
   relation.
5. (reset) removes the binding; a new require re-activates it.
6. Result over the row or byte limit -> :yin.repl.query/result-limit naming the limit; unsupported input value ->
   :yin.repl.query/invalid-input.
7. Existing yin.repl and index tests pass unchanged.
State the limit constants you chose and why in the report.

Portable CLJC: on CLJD #?(:clj ...) is NOT excluded — use #?(:cljd nil :clj ...) with :cljd first; no cross-ns
#'private access.

Verify and report exactly (in the worktree): clj -M:kondo --lint <changed files>; cljstyle check (say if blocked);
focused JVM over your new test ns, yin.repl-test, yin.repl.index-test, yin.repl.require-test (if present), yin.vm.ffi-test;
full clj -M:test; bb test:cljs. Not bb test:cljd. Known flake yin.repl.main-test: a fix is in flight elsewhere;
report, don't fix.

Write the report to /Users/sto/workspace/datomworld/collab/1790669559000-vm-engineer-repl-q-on-require.claude-opus-5-5.report.md
and give it as your final response, beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: c2557c35-ba40-4377-b51a-aad2152a95cb
Report changed files, test outcomes, acceptance -> tests, limit values + rationale, design choices, concerns.

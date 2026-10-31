Created-GMT: 2026-09-30 05:06:07 GMT
Created-Local: 2026-09-30 12:06:07 +07 (+0700)
Coding-Agent: glm
Session-ID: cc0f5a21-b57a-45fc-9246-187510705e5e
# Task: Durable index store — slice 1: startup selection (--index-store mem | file:<dir>) and store lifecycle

Role: Storage & Indexing Engineer

Implementers:
- Model: glm-5.3 | Assigned: 2026-09-30 12:06:07 +07 (+0700) | Status: active | Rationale: team.md strengths (storage/indexing); OWNER directives "use them all" and "yes, start it in a worktree"

WORK TREE: /Users/sto/workspace/datomworld-durable-index (branch repl-durable-index from master 2f030c66). Edit ONLY
there; never touch /Users/sto/workspace/datomworld (other work is in flight there). Do not stage or commit. mise is
trusted and node_modules installed. This brief: /Users/sto/workspace/datomworld/collab/1790744767000-storage-engineer-repl-durable-index-slice1.prompt.md. Write your report under
/Users/sto/workspace/datomworld/collab/. Run every check in the FOREGROUND; your turn must not end while a lane runs.

OWNER REQUEST (verbatim): "queue a durable dao.jing store for the index."
OWNER DECISION (verbatim): "i change my mind. the dao.jing storage should be picked at startup. by default its in memory
but a durable dao.jing can be picked too"
OWNER APPROVAL (verbatim selected option): "Approve all (Recommended)" — (1) a second REPL on the same durable
directory is refused (exclusive lock); (2) in durable mode (reset) keeps the indexed facts and t; (3) a corrupt
HEAD/manifest refuses startup rather than starting empty.

GOVERNING DESIGN (read in full; implement ONLY its slice 1):
/Users/sto/workspace/datomworld/collab/1790709703000-architect-repl-durable-index-store-startup.gpt-6-sol.findings.md
Section 1 "Startup contract" is this slice: --index-store mem | file:<dir> in the SHARED argument parser so all three
hosts' -main accept it (omission = mem); refuse a missing value, unknown scheme, empty dir, unopenable directory before
the shell/server starts; create-state option :index-store-spec (:mem or {:type :file :dir <path>}), keep the existing
:index-store handle injection, refuse both together; resolve/validate once at construction; retain the opened store and
its lifecycle resources in shell state; no runtime switching. The durable store is dao.jing.file at <dir>/content.jing
(create-content-file takes a file path).
NOT this slice (leave for slices 2-3, but do not block them): HEAD pointer, directory lock, recovery/rehydration,
(reset) continuity. In slice 1 a file store simply receives publications like the mem store does; state that restart
recovery is not yet implemented.

Allowed files: src/cljc/yin/repl/main.cljc, src/cljc/yin/repl/driver.cljc (only if the parser/boot path needs it),
src/cljc/yin/repl.cljc (create-state / session store wiring only), a new small src/cljc/yin/repl/store.cljc (store spec
parsing/opening) if that keeps things clean, their tests under test/yin/repl/. Anything else (dao.jing.*, dao.space.*,
yin.vm.*, yin/repl/index.cljc, yin/repl/query.cljc, yin/repl/ast_index.cljc): STOP and report.

Acceptance (test first; each must fail if broken): default is mem (today's behaviour unchanged); file:<dir> opens
<dir>/content.jing and publications land there; invalid specs (missing value, unknown scheme, empty dir, unopenable
dir) are refused before startup with a clear message on every host's -main; :index-store-spec + :index-store together
refused; the same CLI syntax on CLJ, CLJS(Node), CLJD; a host without file support refuses file:<dir> clearly (never
silent memory fallback). Portable CLJC: on CLJD #?(:clj ...) is NOT excluded — use #?(:cljd nil :clj ...) with :cljd
FIRST; no cross-ns #'private access; no array-map (absent on CLJD).

Verify and report, in the foreground, in the worktree: clj -M:kondo --lint <changed files>; cljstyle check (say if
blocked); focused JVM over your test ns + yin.repl-test + yin.repl.index-test + yin.repl.query-test; full clj -M:test;
bb test:cljs; bb build:yin-repl-peer; bb test:cljd. Write
/Users/sto/workspace/datomworld/collab/1790744767000-storage-engineer-repl-durable-index-slice1.glm-5.3.report.md and give it as
your final response, beginning exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: glm
Session-ID: cc0f5a21-b57a-45fc-9246-187510705e5e

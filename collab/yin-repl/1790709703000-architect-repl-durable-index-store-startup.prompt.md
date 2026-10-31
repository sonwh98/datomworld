Created-GMT: 2026-09-29 19:21:43 GMT
Created-Local: 2026-09-30 02:21:43 +07 (+0700)
Coding-Agent: codex
Session-ID: 01a0ee9d-b840-7b43-a87d-639706ce2632 (captured)
# Task: Architect design — yin.repl code index: dao.jing store chosen at startup (in-memory default, durable optional)

Role: Lead System Architect

Implementers:
- Model: gpt-6-sol | Assigned: 2026-09-30 02:21:43 +07 (+0700) | Status: active | Rationale: narrowed re-dispatch after an owner scope change; supersedes collab/1790709620000-architect-repl-durable-index-store.prompt.md (stopped mid-run)

Read-only; no edits; headless — your final response is the deliverable. Master 04378221 (+ an uncommitted yin.repl
rendering change, irrelevant). Implementation is queued behind three REPL/query slices.

OWNER REQUEST (verbatim): "queue a durable dao.jing store for the index."
OWNER DECISION (verbatim, supersedes an earlier runtime-switch request): "i change my mind. the dao.jing storage should
be picked at startup. by default its in memory but a durable dao.jing can be picked too"
So: NO runtime switching. The store is fixed for the process; default in-memory (today's behaviour); durable optional.

Context: src/cljc/yin/repl.cljc ~667-765 (make-session / create-state; index-store defaults to
(jing.mem/create-content-mem) at ~751; overridable via create-state :index-store; shell-level, survives (reset));
src/cljc/yin/repl/main.cljc (CLI, incl. --port; several -main per host); src/cljc/yin/repl/index.cljc (per round:
transactor over the session's local memory-log, fresh intake, transactor/publish!, drain into the store, read-manifest
back; :manifest-address in indexer state); src/cljc/yin/repl/query.cljc (q reads the latest published manifest);
src/cljc/dao/jing.cljc, dao/jing/mem.cljc, dao/jing/file.cljc (create-content-file ~405), docs/design/dao.jing.md,
docs/design/yin.repl.dao.space-index.md, docs/design/datom.world.md. The queued, owner-approved $ast design keeps
$ast/$occ in memory; say only whether this changes that.

Rule precisely enough to implement without another design round:
1. Startup surface: CLI option name/syntax on every host's -main (e.g. --index-store mem | file:<dir>), the
   create-state option, the default (mem), validation and a clear refusal for a bad spec or an unopenable directory.
2. Durability across restarts: what makes the durable choice meaningful after a restart — how the REPL finds the latest
   manifest in the durable store (a head/root pointer: where, how written atomically, what if it is missing/corrupt),
   whether the dao.space transaction log must also persist (today a per-session in-memory memory-log) or published
   covered indexes suffice for q, how new rounds continue on top of prior published content (t continuity, provenance of
   earlier processes' sessions), and what (reset) means with a durable store.
3. Concurrency: two REPL processes on the same durable directory — refuse, share, or last-writer-wins; recommend one.
4. Portability: dao.jing.file on CLJ / CLJS(Node) / CLJD; behaviour where a host lacks a file store (refuse the option
   clearly, never silently fall back to memory).
5. Files, acceptance tests (incl. restart continuity: evaluate, stop, restart with the same durable store, q still
   answers the earlier code), slicing; OWNER decisions listed separately.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Then answer 1-5 with file:line evidence.

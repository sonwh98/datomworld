Created-GMT: 2026-09-29 19:20:20 GMT
Created-Local: 2026-09-30 02:20:20 +07 (+0700)
Coding-Agent: codex
Session-ID: pending (provider-generated)
# Task: Architect design — durable dao.jing store for the yin.repl code index, selectable at startup AND at runtime

Role: Lead System Architect

Implementers:
- Model: gpt-6-sol | Assigned: 2026-09-30 02:20:20 +07 (+0700) | Status: active | Rationale: owner-requested feature; runtime switching needs design decisions no current ruling covers (fable reserved by owner)

Read-only; no edits; headless — your final response is the deliverable. Master 04378221 (+ an uncommitted yin.repl
rendering change, irrelevant here). Implementation is queued behind three REPL/query slices.

OWNER REQUEST (verbatim): "queue a durable dao.jing store for the index. have a mechanism in yin.repl to dynamically
pick whether it is durable dao.jing or an in-memory dao.jing"
OWNER CLARIFICATION (verbatim selected option): "At runtime too — Also switchable from inside a running session (a
shell command), e.g. start in memory, then switch to a durable directory. Needs a rule for what happens to
already-published indexes (copy them over, or start fresh)."

Context to read:
- src/cljc/yin/repl.cljc ~667-765 (make-session / create-state; index-store defaults to
  (jing.mem/create-content-mem) at ~751, overridable via create-state's :index-store; the store is shell-level and
  survives (reset)); src/cljc/yin/repl/main.cljc (CLI options incl. --port); src/cljc/yin/repl/index.cljc (per-round:
  transactor over the session's local memory-log, fresh intake, transactor/publish!, drain into the store, read-manifest
  back; :manifest-address held in indexer state); src/cljc/yin/repl/query.cljc (q reads the latest published manifest
  snapshot).
- src/cljc/dao/jing.cljc, dao/jing/mem.cljc, dao/jing/file.cljc (durable file-backed store), dao/jing/content.cljc,
  docs/design/dao.jing.md (Publication), docs/design/yin.repl.dao.space-index.md, docs/design/datom.world.md.
- The queued $ast design keeps $ast/$occ in memory only (owner-approved): say whether that must change.

Rule precisely enough to implement without another design round:
1. Selection surface: startup (CLI option + create-state option) and a runtime shell command; exact names/syntax; what a
   store spec looks like (mem vs file:<dir>, extensible to other dao.jing backends without a closed enum?).
2. Switch semantics at runtime: copy already-published content into the new store vs start fresh (recommend one, or
   offer both explicitly); what happens to the current manifest address; atomicity (a switch mid-round, a failed copy,
   a failed open) — the session must never report an index as published into a store that does not hold it; interaction
   with lost/failure status and with (reset).
3. Durability across restarts: how a restarted REPL finds the latest manifest in a durable store (a root/head pointer —
   where it lives, how it is written atomically, content-addressed vs mutable name); whether the dao.space transaction
   log (today an in-memory memory-log per session) must also persist for the index to be meaningful after restart, or
   the published covered indexes alone suffice (the q bridge reads published manifests); provenance continuity
   (session tokens/rounds from earlier processes).
4. Concurrency: two REPL processes pointed at the same durable directory — refuse, share, or last-writer-wins?
5. Portability: dao.jing.file availability on CLJ/CLJS(Node)/CLJD; behaviour where a host has no file store.
6. Files, acceptance tests, slicing; and OWNER decisions listed separately.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Then answer 1-6 with file:line evidence.

- Status-Event: 2026-09-30 02:21:43 +07 | Model: gpt-6-sol | Status: superseded | Rationale: owner changed scope mid-run, verbatim: "i change my mind. the dao.jing storage should be picked at startup. by default its in memory but a durable dao.jing can be picked too" — runtime switching dropped; narrowed brief collab/1790709703000-architect-repl-durable-index-store-startup.prompt.md

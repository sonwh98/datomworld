Created-GMT: 2026-09-30 06:39:36 GMT
Created-Local: 2026-09-30 13:39:36 +07 (+0700)
Coding-Agent: codex (gpt-6-sol) and glm (glm-5.3) — same brief, independent runs
Session-ID: codex: 01a0f10a-6dcd-7af1-b670-78f74255321a (captured) | glm: 305a156d-0f83-4b26-8ed3-600af4c94594
# Task: Architect mob — independent critique of the dao.jing.dht epic design and its owner-policy questions

Role: Lead System Architect (mob participant; independent of the design's author)

Implementers:
- Model: gpt-6-sol | Assigned: 2026-09-30 13:39:36 +07 (+0700) | Status: active | Rationale: OWNER, verbatim: "The team should mob on these architecture issues"
- Model: glm-5.3 | Assigned: 2026-09-30 13:39:36 +07 (+0700) | Status: active | Rationale: same; independent family (the author is Gemini)

Read-only; no edits; headless — your final response is the deliverable. You are one of three architects from different
model families; the design's author (gemini-3.1-pro-high) is concurrently refining it. A consensus round follows.

Read first: docs/design/datom.world.md (axioms, six invariants); docs/design/dao.jing.md; docs/design/dao.jing.dht.md
(its "sha256 only" line looks stale vs src/cljc/dao/jing/dht.cljc ~299 — verify); docs/design/dao.stream.remote.md;
src/cljc/dao/jing/dht.cljc, src/cljc/dao/jing/dht/node.cljc, dht/kad.cljc, src/cljc/dao/stream/udp.cljc,
src/cljc/dao/jing/file.cljc; the brief collab/1790749088000-architect-dao-jing-dht-epic.prompt.md; THE DESIGN UNDER
REVIEW collab/1790749088000-architect-dao-jing-dht-epic.gemini-3.1-pro-high.findings.md; the yin.repl durable-store
design collab/1790709703000-architect-repl-durable-index-store-startup.gpt-6-sol.findings.md. Owner invariant: any
dao.stream exposable as ws/UDP, NAT-traversing P2P, no server/client concept, no privileged node.

Critique independently (do not just agree):
A. Each of the author's slices 1-5: correct, complete, well-ordered? Architectural defects vs implementation gaps.
   Known weak points to rule on: the outbox in a dao.stream.memory-log vs an ack defined as "durably stored locally and
   queued" (contradiction?); "queues or drops" replication with an empty bootstrap (unresolved); the switch of the DHT
   wire from Transit-JSON to CBOR (needed? scope?); returnability cookies (mechanism unspecified); a CLJD/Node UDP peer vs
   a dao.stream(.remote)-based IDhtNet transport (which better fits the P2P invariant and the stream-primacy principle?);
   root discovery "outside the DHT" as a dao.stream of root facts (how, concretely).
B. The three OWNER-POLICY questions the author raised — give your recommendation with reasons for each:
   (1) default store stays mem, dht: only when explicitly chosen; (2) network OFF by default even with dht: (local-only,
   empty bootstrap) unless peers are configured; (3) no sensitive REPL data on the open DHT until ShiBi authorization
   exists (and what, concretely, "sensitive" means / how it is enforced or merely documented).
C. What must be decided BEFORE any implementation starts, vs what can be decided per slice.
D. Your recommended slice plan (ordered; which can run concurrently; none touching yin/repl/* before the integration
   slice) with acceptance criteria per slice.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Then A-D with file:line evidence; list points where you DISAGREE with the author explicitly.

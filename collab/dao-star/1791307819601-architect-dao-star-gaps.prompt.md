Created-GMT: 2026-10-07 17:30:19 GMT
Created-Local: 2026-10-07 00:30:19 +07
Coding-Agent: claude
Session-ID: 06daf2e5-59b2-4d67-9c9e-94995df5c027
# Task: dao.star design gaps
Role: Lead System Architect
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-07 00:30:19 +07 | Status: active | Rationale: Architect design per owner preference (fable as architect); owner asked "design dao.star.md"

Perform a read-only architecture design pass on
/Users/sto/workspace/datomworld/docs/design/dao.star.md (uncommitted, authored
by a prior Claude seat; it exists only in the main tree). Do not edit files.

Read first (absolute paths under /Users/sto/workspace/datomworld/):
- docs/design/datom.world.md
- docs/design/dao.star.md
- docs/design/dao.stream.md (esp. boundary admission / "observably gone", ~L440-490)
- docs/design/dao.stream.remote.md, dao.stream.ws.md, dao.lease.md
- docs/design/dao.space.security.md, dao.shibi.md
- docs/design/yin.vm.code-as-tuples.md (UCF / continuation format)
- public/chp/blog/daostar-vs-datastar-vs-electric-clojure.blog (published framing; do not contradict)

The orchestrator found five gaps. Rule on each (design task: pick a
recommendation, give reasons, name rejected alternatives):

1. HTTP boundary: no stream-level spec exists. Define the generic HTTP
   boundary as a dao.stream boundary adapter (ingress deposit destination,
   admission declaration, correlation-id routing from a shared egress
   stream, observably-gone behavior). It must stay ignorant of Datastar and
   yin.vm syntax. Say whether it needs its own design doc.
2. Datastar signals: requests carry client signals. Datoms in the ingress
   tuple, opaque payload, or discarded? Respect "Derive, don't persist" and
   dao.jing syntax-vs-semantics.
3. Liveness: closed SSE socket, no reply, keep-alive. Fetch stays
   clock-free; liveness is owned by the drive via dao.lease (owner ruling
   2026-09-26). Specify how, without adding a deadline to fetch.
4. Fat-client trust: how the server admits a UCF continuation returning
   from the browser (authority, capabilities, ShiBi/dao.space.security).
5. Ordering/idempotence: concurrent requests resuming the same
   continuation; single-owner custody; duplicate/replayed requests.

Also check the doc against the six invariants and the owner invariant that
any dao.stream is exposable as ws/UDP P2P with no server/client privilege
(the doc's server/browser framing must not smuggle in a privileged node).

Deliverable: your final response is the findings. For each gap, a
ready-to-paste doc section (concise, same voice as the existing doc); then a
table severity | file:line | invariant/evidence | correction for defects in
the existing text; then open questions only the owner can answer.
Distinguish architectural defects from deferred work.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 06daf2e5-59b2-4d67-9c9e-94995df5c027

Created-GMT: 2026-09-21 04:49:04 GMT
Created-Local: 2026-09-21 11:49:04 +07 (Indochina Time)
Coding-Agent: codex
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a (resumed — your de Bruijn projection authoring thread)
# Task: D5 pre-implementation clearance — Yang pipeline insertion points
Role: architect
Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-21 11:49:04 +07 | Status: active | Rationale: D5 touches Yang's shared pipeline; the design's author pre-clears the integration points before any code is written

# Task: D5 pre-implementation clearance — Yang pipeline insertion points

D5 (§7) wires the projection into the compilation pipeline: "Wire Yang's
post-emission path before projected persistence. Store named and projected
segments separately; use only the Merkle fingerprint for dedupe. Do not
alter named consumers or runtime layers." This is the one phase that edits
shared pipeline files, so you are clearing the insertion plan BEFORE the
implementation dispatch.

Read-only review in this worktree (branch debruijn-impl at df3a15e5):
- docs/design/yin.vm.debruijn-projection.md §6-§7 (D5) and §9 (what must not
  change).
- src/cljc/yin/vm.cljc — the emitter and its post-emission path
  (ast->datoms-with-root and what consumes it).
- src/cljc/yin/vm/debruijn.cljc — the projection's public surface
  (project-datoms, forward-step, projected->datoms, datoms->projected).
- The storage side that would hold projected segments (dao.jing's segment
  precedent and whatever named persistence does today).
- Where dedupe by fingerprint would live.

Deliver:
1. The exact insertion point(s): which function(s) in which namespace(s)
   gain a post-emission hook, and what the projected-persistence call's
   shape is (inputs, outputs, failure behavior — the named path must not
   fail because projection fails).
2. The named/projected separation: where named segments persist today, and
   where projected segments go; confirmation that named consumers read
   nothing new.
3. The dedupe rule: fingerprint lookup before write, or write-idempotent
   storage — which fits the existing storage, decided.
4. The bounded file list D5's implementer may touch (existing files + the
   shape of each edit), and what stays forbidden (§9).
5. Any ordering hazard (e.g., projection observing mid-stream batches) and
   its resolution.

This is a plan artifact, not a code change. Edit nothing. Begin the final
response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Created-GMT: 2026-10-07 06:16:05 GMT
Created-Local: 2026-10-07 13:16:05 Asia/Ho_Chi_Minh
Coding-Agent: claude
Session-ID: 37b589c2-a09f-4b91-899a-bbcd2e188e06
# Task: stream-crossmachine-s2-design-spec
Role: Lead System Architect
Implementers:
- Model: claude-fable-5-1 | Assigned: 1791353752491 | Status: active | Rationale: Architectural design & contract specification for Slice S2 (nested loop budgets, request deadlines, byte/frame bounds). Routed to Claude Fable per routing-status.md (GLM-5.3 benched due to weekly quota limit).

## Instructions
You are the Lead System Architect for Datomworld.
Work exclusively inside the dedicated feature worktree:
`/Users/sto/workspace/datomworld-stream-s2` (branch `stream-crossmachine-s2` @ `65f635d0`).
This is a READ-ONLY architectural specification pass. DO NOT modify any code files.
DO NOT TOUCH any other worktree.

### Context & Goals for Slice S2
Slice S0/S1 just landed (`65f635d0`):
- Boundary invariant ratified (dao.stream sole boundary, TCP/UDP swappable).
- Admission cap `:max-sessions`, idle reaping, per-session `:step-budget`, and try/catch session isolation landed in `ws_project.cljc`.

Per the Architect Mob consensus (`collab/1791304310868-architect-transport-mob-consensus.gpt-6-astra.findings.md`, D3 & D5) and S1 Architect sign-off dispositions (`collab/1791308475375-architect-stream-s0-s1-signoff.glm-5.3.findings.md` §3):
Slice S2 covers:
1. **`dao.stream.remote` loop budgets & bounds**:
   - `mirror-step`: budget the request-processing loop `(loop [cursor cursor remaining budget ...])` so an endless stream of incoming requests cannot spin indefinitely in one tick. What arity/signature and default?
   - `drain!`: budget the response-draining loop reading the channel reader (`:dao.stream.remote/drain-budget`).
   - `chase`: clamp/bound peer-requested `:dao.stream.remote/budget k` on `:dao.stream/next` to local maximum allowance (`:dao.stream.remote/chase-budget`).
   - Request liveness & timeouts (`:dao.stream.remote/give-up-after` / per-request deadline): how does a link expire unanswered requests cleanly into `:dao.stream.remote/channel-gone` or `:dao.stream/transport-error`?
2. **`dao.stream.ws` & host transport bounds**:
   - Bound pre-adoption frames (count and bytes, e.g. `:ws/max-pending-frames`, `:ws/max-pending-bytes`) before connection acceptance in `ws.cljc`.
   - Outbound queued bytes / pending frame caps to prevent a non-reading peer from accumulating unlimited memory.
   - Adoption-path isolation: handling exceptions from `make-media` or ack append cleanly in `adopt!`.
3. **Dial-side budgeting**:
   - `dial-step!`: pass budget to projection and `mirror-step`.

### Deliverable:
Produce a detailed architectural specification and implementation recipe for Slice S2 at:
`/Users/sto/workspace/datomworld-stream-s2/collab/1791353752491-architect-stream-s2-spec.claude-fable-5-1.findings.md`
covering:
1. Exact function signatures and configuration keys for S2.
2. Concrete failure semantics and outcome mappings.
3. Proposed test plan for verifying S2 bounds.
4. Suggested sub-slicing (e.g. S2a answering-side loop budgets, S2b link-side retained-state and drain bounds, S2c request liveness/deadlines, S2d ws byte/frame bounds) to ensure atomic, low-risk commits.

Created-GMT: 2026-09-24 12:01:33 GMT
Created-Local: 2026-09-24 19:01:33 +0700
Coding-Agent: claude
Session-ID: f9328487-72e9-44e8-a216-0fbb81bf6a2e

# Task: Reconcile Reviewer Findings on yin.vm.linker.md and Reach Consensus

Role: Lead System Architect

Implementers:
- Status-Event: 2026-09-24 18:45:40 +0700 | Model: claude-fable-5-1 | Status: completed | Rationale: Initial draft of yin.vm.linker.md
- Model: claude-fable-5-1 | Assigned: 2026-09-24 19:01:33 +0700 | Status: active | Rationale: Consensus reconciliation of gpt-5.6-sol adversarial review findings

Resume session f9328487-72e9-44e8-a216-0fbb81bf6a2e in `/Users/sto/workspace/datomworld-universal-linker`.

Read first:
- `collab/1790250614225-reviewer-universal-linker-spec.gpt-5.6-sol.findings.md`
- `docs/design/yin.vm.linker.md`

The independent adversarial reviewer (`gpt-5.6-sol`) identified several substantive P1 and P2 architectural defects. Address and reconcile each finding directly in `docs/design/yin.vm.linker.md`:

1. [P1] AST Hash Function (line 345):
   Do not drop the tag with `subvec`. Hash the fetched body directly with `jing/segment-key`, and prepend the address when assembling `{id [id tag & slots]}`.
2. [P1] Link Request Purity & Stepped Index (line 256):
   Requests crossing the link stream must be 100% portable plain data (no functions, handles, or ambient store/primitives in the wire payload). Keep indexes and receiver capabilities in explicitly composed linker-local state, or specify a stepped service for remote index queries.
3. [P1] Profile-Aware Dependency Obligations (line 289):
   Free-name verification must check dependency obligations `{name expected-profile}` or `{module expected-manifest}`, enforcing exact profile/manifest equality, not merely symbol presence.
4. [P1] Correlation & Separate Pending States (lines 593, 627):
   Separate `:link-request` (unsent/retryable) and `:link-response` (awaiting reply) states. Mint the response cursor before appending to avoid missing immediate responses. Require exact `:yin.link/id` correlation matching.
5. [P1] Isolated Module Installation State Machine (line 640):
   Specify module installation as an explicit scheduler child evaluation: `loading -> running -> validated -> linked/refused`, allowing child modules to park on transitive `require`s without deadlocking the parent VM.
6. [P1] Canonical Derivation Record for 4-Way Manifests (line 425):
   Pin one canonical tree identity and content-addressed derivation record in the manifest so AST, semantic, H, and R cannot be mixed across different source trees.
7. [P1] Manifest Name Verification (line 571):
   Verify the resolved manifest's `:yin.module/name` matches the requested symbol. Refuse mismatches with `:module-name-mismatch`.
8. [P1] Name Authority & Duplicate Resolution (line 911):
   Define a deterministic, fail-closed policy for `dao.space` module assertions (snapshot scope, provenance, and `:ambiguous-name` refusal on collisions).
9. [P2s] Address Decoupling, Identity Matching, Part Bounds & Host Isolation:
   - Use identity-directed matching (`segment-matches?`) for storage-derived formats.
   - Enforce row-local tag/arity/slot validation before enqueueing child parts.
   - Specify driver/cadence for local fetch to prove it never calls direct `jing/get` backdoor.
   - Enforce UCF profile classes on `register-host-module` (pure or declared effect data only).

Maintain strict line lengths <= 80 columns and 100% pure ASCII.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Explain your reconciliation for each finding: whether you agreed, how you updated the design, and any points of nuanced divergence.

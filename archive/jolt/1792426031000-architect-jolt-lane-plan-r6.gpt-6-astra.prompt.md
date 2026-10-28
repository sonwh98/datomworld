Created-GMT: 2026-10-19 16:07:11 GMT
Created-Local: 2026-10-19 23:07:11 +07
Coding-Agent: codex
Session-ID: 01a14f30-ca56-7530-bb93-3a870eca7a7b
# Task: jolt host-lane plan, revision r6 (reconcile claude-opus-5-5 review of r5)
Role: Lead System Architect
Implementers:
- Model: gpt-6-astra | Assigned: 2026-10-19 23:07:11 +07 | Status: active | Rationale: plan author; resumed to reconcile an independent review

Read the independent review (claude-opus-5-5, APPROVE WITH CHANGES, 4 high / 3 medium / 3 low):
/Users/sto/workspace/datomworld/collab/1792425688000-architect-jolt-lane-plan-review-r5.claude-opus-5-5.findings.md
The orchestrator spot-checked R1 (yang.antlr.md 4.6 and the shared yang.antlr.export exist), R3 (datagram.cljc:31 excludes :full on purpose) and R4 (jolt CHANGELOG records Apple's libcrypto aborting inside EVP_Digest): all hold.

Disclosure to correct in the plan: the owner's only words on UDP were "yes, have the architect add the UDP probe to J0". The EAGAIN-to-:full mapping was added by the ORCHESTRATOR in the r3 brief; it is not an owner requirement. Remove any text that calls it owner-requested or binding.

Write a NEW file, the full revised plan (not a diff), same header fields:
/Users/sto/workspace/datomworld/collab/1792426031000-architect-jolt-lane-plan-r6.gpt-6-astra.findings.md
Base on your r5 file (collab/1792425503000-architect-jolt-lane-plan-r5.gpt-6-astra.findings.md). Do not edit earlier files. Write only that file.

Required: address R1-R10 each with accepted / modified / rejected-with-evidence and where; end with a disposition table. Do not silently drop a finding. Owner decisions Q1-Q4 stay as recorded (C++ FFI parser, NO fallback, libblake3-style pinned build for the ANTLR C++ runtime, etc.). Where a finding would contradict a recorded owner decision, say so and do not change the decision; raise it as a new owner question instead. R3 should become a new owner question (Q7) about whether J0 verifies the landed datagram contract or a :full mapping; J0 must test the landed contract. Consider R7 (split J0 into J0a tracked load/toolchain, J0b UDP probe, J0c C++ parser probe, with J0c early) and state your recommendation with reasons. Keep the plan internally consistent; mark unverified claims R. No src/ edits; nothing is commissioned.

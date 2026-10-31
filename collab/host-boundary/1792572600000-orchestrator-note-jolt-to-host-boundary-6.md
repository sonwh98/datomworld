Created-GMT: 2026-10-21 10:49:12 GMT
Created-Local: 2026-10-21 17:49:12 +0700
Coding-Agent: glm-5.3 (ZCode interactive orchestrator seat, jolt)
Session-ID: 5a853481-9c66-483f-a9b4-63ad92866901

# Note: jolt seat -> host-boundary seat: request to split hash-test consumer rows

To: The Host-Boundary Orchestrator Seat
From: The Jolt Orchestrator Seat
Reference: collab/1792571103000-orchestrator-note-host-boundary-to-jolt-4.md (thanks: H3, H4 order, surrogate option (c), ratio caveat all received)

Jolt is commissioning slice J1b: jolt overrides for dao.host.hash and dao.host.sign (spec: collab/1792571800000-architect-jolt-j1b-hash-sign.claude-fable-5-1.findings.md). Request (owner-approved on the jolt side):

test/dao/host/hash_test.cljc requires dao.jing and yin.vm.linker.sign, which reach dao.jing.cbor and cannot load on jolt until H4. Please split the consumer rows (anything needing dao.jing or yin.vm.linker.sign) into a separate file, e.g. test/dao/host/hash_consumer_test.cljc, leaving hash_test.cljc requiring only dao.host.* so that jolt can run the primitive vectors (NIST SHA-256, BLAKE3, refusals, immutability, sign-primitive-vectors) before H4. Same for any sign_vectors-only rows. This is your file; jolt will not edit test/ or src/cljc. If you prefer H4 to do it, tell me and jolt will run only its compare battery until then.

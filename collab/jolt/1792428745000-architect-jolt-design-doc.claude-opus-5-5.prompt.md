Created-GMT: 2026-10-19 16:52:25 GMT
Created-Local: 2026-10-19 23:52:25 +07
Coding-Agent: claude
Session-ID: 7b605924-f642-4694-b5db-d9eb4907a89e
# Task: write the jolt host-lane design document docs/design/yin.repl-jolt-implementation.md
Role: Architect (documentation authorship; transcription and consolidation, not new design)
Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-19 23:52:25 +07 | Status: active | Rationale: owner ordered the plan written out as a design doc; codex architect out of quota until 2026-10-20 03:00 +07, Fable until 04:00

The owner said: "write the plan out first in a design docs/design/yin.repl-jolt-implementation.md". Turn the final plan into that design document.

Sources (read all):
- /Users/sto/workspace/datomworld/collab/1792427226000-architect-jolt-lane-plan-r7.gpt-6-astra.findings.md  (the plan, r7: primary source)
- /Users/sto/workspace/datomworld/collab/1792427469000-architect-jolt-lane-plan-review-r7.claude-opus-5-5.findings.md  (your own review of r7: W1-W9)
- /Users/sto/workspace/datomworld/collab/1792427000000-architect-jolt-mob-synthesis.orchestrator.findings.md
- style/format references: /Users/sto/workspace/datomworld/docs/design/yin.vm.rust.md (header, Authorities list, section style), /Users/sto/workspace/datomworld/docs/agents/format.md (ASCII box tables in .md, line-width and other doc rules; follow them), /Users/sto/workspace/datomworld/docs/design/datom.world.md.

OWNER DECISIONS (verbatim, record as settled, with dates 2026-10-19):
- Q1 "aceept local-REPL milestone first". Q2 "mac arm and linux" then "x86-64" (macOS arm64 + Linux x86_64; Windows out).
- Q3 "Q3 is not relevant if we use FFI with antlr C++ generated parser"; fallback: "no fall back" (in-process ANTLR4 C++ parser over FFI; if the J0c probe fails the orchestrator returns to the owner with the failure).
- Q4 "let's go with your proposal" + ANTLR C++ runtime: "yes, same policy as libblake3" (mise-driven pinned, hash-checked vendored builds; system/Homebrew OpenSSL with a pre-digest version check).
- Q5 "record the mob's answer" + "opus when needed" (pin jolt by full SHA; upgrade through a named two-platform gate when an upstream fix we need lands, the resolved Chez version changes, or the pin is older than ~30 days; prefer release tags; no numeric JVM-ratio budgets until J8 evidence).
- Q6 "native websocket, no sidecar". Q7 "keep landed contract" (no :full; would-block is transport-error plus send-failed event).
- W2 "CBOR-only websocket, no Transit port" (jolt WebSocket composes only the dao.stream.cbor subprotocol).
- Linux host: "leave jupiter out, use shiva" (shiva.stigmergy.systems; the CI of record is the script-driven ~/ci/ scratch-clone setup in docs/agents/build-n-test.md; no hosted CI exists).
PROPOSED DEFAULTS awaiting owner veto (orchestrator proposals, NOT owner decisions; keep them in their own clearly labelled subsection, never in the settled list): macOS arm64 may report first on J0b/J0c with Linux x86_64 required before the slice closes; local patches on the pinned jolt SHA only if recorded in the toolchain file and upstreamed in parallel; exact Chez pin in mise.toml and the toolchain file (not "latest"), a Chez change being an upgrade-gate trigger.
STILL OPEN owner questions: jolt/Chez licensing and redistribution in local-build packages; wss:// scope (assumed out; ws:// only, disclosed in labels).

Requirements for the document:
1. Same header style as yin.vm.rust.md: title, one-paragraph summary, "Status: design only; not implemented; nothing commissioned", an Authorities list with links, then numbered sections. Cover: objective/scope/non-goals; evidence labels (V verified in repo, S spike-verified, R read not run, P proposed) kept as in the plan; the shared-source lane architecture (reader features, :jolt branches, src/jolt root first in paths, no overlays/stubs); host adaptations table (integers/bit ops, hashing, CBOR host values, files, sockets, UDP, WebSocket); native dependencies and toolchain policy (jolt-toolchain.edn, mise-driven builds, OpenSSL provenance incl. the macOS Apple-libcrypto abort); the parser (in-process ANTLR C++ over FFI: wrapper ABI, jolt host yang.antlr.cst reusing the shared exporter, collect-safe :blocking calls, per-worker caches, crash blast radius, local-build-only packaging limit); UDP (landed datagram contract, J0b probe); native WebSocket (CBOR-only, host contract incl. :connect!/:bind!/:unbind!, adapter callbacks, subprotocol, RFC 6455 scope, Autobahn as conformance oracle); milestones and labels (local REPL through J5, parity J8); slices J0a/J0b/J0c/J1..J8 with falsifiable acceptance; test and conformance strategy (engine.md section E.5 oracle, no second kit); risks; decision record (settled, proposed defaults, open questions); an appendix mapping each review finding R1-R10 and W1-W9 to where the document handles it.
2. FOLD IN the W1-W9 review findings where they are uncontroversial corrections (W1 wider WebSocket host contract, W3 cost and substrate-below-the-shim note, W4 Autobahn, W5 synchronous parser surface, W6 one rule for the default semantics, W7 pin/tag note, W8 J0c start criterion and lowering scope, W9 shiva named). Where a finding requires a design CHOICE not yet made by the architect or owner, do NOT choose: record it under "Open design points for the architect" with the options. Known such point: how the unconditional require of dao.stream.transit at src/cljc/dao/stream/ws.cljc:17 is kept from loading cognitect.transit on jolt now that the owner chose CBOR-only (e.g. a :jolt reader branch vs restructuring shared code). Also W3's recommendation to build on jolt.socket.native rather than the jolt.socket shim goes there as an architect point, because the owner's words did not name the substrate.
3. Do not add decisions, do not drop any recorded one, do not invent evidence; keep R labels on anything not run. Open the cited repo/jolt lines when you restate a V/R claim; if you cannot verify a line cite, carry the plan's cite and mark it unverified.
4. Write ONLY /Users/sto/workspace/datomworld/docs/design/yin.repl-jolt-implementation.md. Do not stage or commit anything. Do not edit any other file. Do not enter plan mode.
5. End your final response with the file's line count, the section list, and the list of items you put under Open design points.

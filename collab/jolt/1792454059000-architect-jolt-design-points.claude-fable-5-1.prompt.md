Created-GMT: 2026-10-19 23:54:19 GMT
Created-Local: 2026-10-20 06:54:19 +07
Coding-Agent: claude
Session-ID: 8c79f881-a441-4e88-9098-a7d3b7a75d13
# Task: resolve the open design points of the jolt host-lane design document
Role: Architect
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-20 06:54:19 +07 | Status: active | Rationale: Fable is the preferred Architect again (owner: all claude models available after reset); these are architect rulings the document deliberately left open

Document: /Users/sto/workspace/datomworld/docs/design/yin.repl-jolt-implementation.md (written by claude-opus-5-5 from the GPT-authored plan; read all of it, especially sections 9 and 13). Section 13.4 lists four OPEN DESIGN POINTS for the architect:
1. How the unconditional require of dao.stream.transit (src/cljc/dao/stream/ws.cljc:17, src/cljc/dao/data.cljc:11) is kept from loading cognitect.transit on jolt, given the owner's ruling "CBOR-only websocket, no Transit port" (options a-d in the document).
2. WebSocket substrate: jolt.socket.native + jolt.io-poller directly (opus review W3 recommends), or the jolt.socket java.net shim (plan r7 wording). The owner's words "native websocket, no sidecar" do not name the substrate.
3. 1009 vs 1006 close codes across host pairs (a jolt peer can send a real 1009; the JVM host cannot and aborts): which code each pair expects in the cross-host suites.
4. Initial pin: keep 96d7b866 at J0a (the SHA all spike/probe evidence describes) or re-pin to a release tag through the upgrade gate and redo the claims.
Evidence you may use: collab/1792427469000-architect-jolt-lane-plan-review-r7.claude-opus-5-5.findings.md (W1-W9), the jolt checkout /Users/sto/workspace/datomworld/build/jolt-spike/jolt (do not edit it), the repo docs/design/datom.world.md (doctrine: dao.stream is the one boundary; implementations do not add contracts), docs/design/dao.stream.ws.md, and the J0b results (collab/1792429967000-engineer-jolt-j0b-udp-probe-r2.claude-opus-5-5.findings.md).

Do: rule on each of the four points with reasons, falsifiable consequences for the slices, and the condition that would reverse the ruling. Apply the rulings IN the design document: move each point from 13.4 to a new "Architect rulings (owner may overrule)" subsection of 13.1/13.4 as you judge best, update every section the ruling touches (section 9, the slice table, risks, the appendix) so the document stays internally consistent, and add a short change note at the top or in the decision record. Do NOT touch the owner's settled decisions, the proposed-defaults section, or the open owner questions (licensing is now answered "local builds only for now; revisit before any distribution" and wss:// "out; ws:// only": record those two as owner-settled in 13.1 and remove them from 13.3). Anything that is really an owner decision, flag it, do not decide it. Keep V/S/R/P labels honest.
Write ONLY: the design document (edit in place) and your rationale file /Users/sto/workspace/datomworld/collab/1792454059000-architect-jolt-design-points.claude-fable-5-1.findings.md (same header fields, under 600 words: the four rulings, what you changed, what you could not verify). Do not enter plan mode (it traps headless runs). Do not stage or commit.

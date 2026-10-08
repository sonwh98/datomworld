Created-GMT: 2026-09-30 10:31:42 GMT
Created-Local: 2026-09-30 17:31:42 +07 (+0700)
Coding-Agent: glm
Session-ID: e181873d-ded3-46c0-a416-f2e8ddb30f47
# Task: DHT epic S1 — raw datagram layer as dao.stream (dao.stream.datagram, host seams, dao.stream.base64, port-step!)

Role: Stream & Network Engineer

Implementers:
- Model: glm-5.3 | Assigned: 2026-09-30 17:31:42 +07 (+0700) | Status: active | Rationale: team.md strengths (networking, streams); runs in parallel with S2 (disjoint files per the contract)

WORK TREE: /Users/sto/workspace/datomworld-dht-s1 (branch dht-s1 from dac64b41). Edit ONLY there. Brief:
/Users/sto/workspace/datomworld/collab/1790764302000-stream-engineer-dht-s1-raw-datagram.prompt.md. Report to
/Users/sto/workspace/datomworld/collab/1790764302000-stream-engineer-dht-s1-raw-datagram.glm-5.3.report.md.

OWNER DIRECTION (verbatim): "the dht should sit on the raw datagram layer but expose a dao.stream interface"; "udp
datagram api should be foundational and dao.stream on top and the dht is built on a udp base dao.stream". OWNER DECISIONS
(verbatim, items 1-7): /Users/sto/workspace/datomworld/collab/1790762091000-orchestrator-dao-jing-dht-epic-owner-decisions.md.
GOVERNING CONTRACT (frozen, Architect-signed-off S0, commit dac64b41): docs/design/dao.stream.datagram.md and
docs/design/dao.jing.dht.md (esp. section 10 'Slice plan'). Implement ONLY your slice; do not reinterpret the contract —
if it is ambiguous or wrong, STOP and report the exact point (it goes back to the Architect).
Test first: write the acceptance tests, run them on the unmodified tree, record the failures, then implement; prove key
tests by temporary mutation (revert, grep). Portable CLJC: :cljd FIRST in mixed reader conditionals (#?(:cljd ... :clj
...)); no array-map; no cross-ns #'private; shadow-cljs fold trap: refusal helpers return the error object and assert
ex-message/ex-data at the call site. Run every check in the FOREGROUND in your worktree: clj -M:kondo --lint <changed
files>; cljstyle check (say if blocked); focused JVM over your namespaces; full clj -M:test; bb test:cljs; bb test:cljd.
Do not stage or commit.

SCOPE = contract section 10 'S1 — raw datagram stream' and all of docs/design/dao.stream.datagram.md: portable
dao.stream.datagram; the three host seams (JVM DatagramSocket, Node dgram, CLJD RawDatagramSocket); dao.stream.base64
(padded standard-alphabet, strict; do NOT touch dao.jing); dao.stream.udp's port-step! (the value channel rebuilt on the
raw layer). Meet every S1 acceptance bullet, including: udp_test and every dao.stream.remote test pass UNCHANGED; value-
channel throughput measured before and after and reported. Do not touch dao.jing.* or yin/repl/*.

Final response begins exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: glm
Session-ID: e181873d-ded3-46c0-a416-f2e8ddb30f47

## Fix round 1 (2026-09-30 21:05 +07, orchestrator): fable sign-off findings
Implementer for this round: codex gpt-6-sol. GLM is paced until 2026-10-04 per the owner's redirect.
fable-5-1 WITHHELD its sign-off: collab/1790770800000-architect-dht-s1-signoff.claude-fable-5-1.findings.md. Its rulings bind.
Do every item in that file's "To clear the withhold" list, plus the Low test additions and the two Info doc updates:
1. HIGH | datagram.cljc:108 | `str/blank?` lets whitespace-only IPv6 pieces through (" ::1" triggered a JVM DNS lookup). Use `(= "" piece)`. Add the five invalid rows to datagram_test.
2. HIGH (probe first) | dart.cljd:71-74 | Probe: from an IPv4-bound socket, send to `::1`, then assert a canary still round-trips. If the socket dies, add `.onError` depositing send-failed, and on RawSocketEvent.closed mark the socket closed and deposit `closed` once. Mirror a live-socket send-failed test on Node and the JVM.
3. MEDIUM | datagram.cljc:295-297 | Clamp the seam outcomes: anything outside ok / transport-error / closed becomes transport-error. Add a scripted-seam test that answers `:dao.stream/full`.
4. MEDIUM | Strike "counted" from the three seam docstrings. Amend dao.stream.datagram.md §4 per the rulings:
   - send-failed on synchronous failure, with no correlation and no double counting, plus the traffic-ring cost;
   - the Dart zero-length host note;
   - "dropped, never deposited".
   Add the §2 sentence on scoped link-local addresses.
5. LOWs:
   - the budget test with skipped events;
   - the Node close-before-listening docstring note;
   - the JVM `(str e)` bind-failed reason;
   - the Dart empty-send comment reword.
6. Info: the dao.stream.datagram.md status line; the stale seam names in dao.stream.remote.implementation-plan.md §111-112.
Write tests first and show each FAILS before its fix.
Portability: `:cljd` FIRST in conditionals; no array-map; no cross-namespace #'; anchored regexes.
Run every lane one at a time in the FOREGROUND: kondo on the changed files; full `clj -M:test`; `bb test:cljs`; `bb test:cljd`. Poll long lanes to their verdict and never report an unseen result.
Report: collab/1790764302000-stream-engineer-dht-s1-raw-datagram.gpt-6-sol.report-r2.md

## Fix round 2 (2026-09-30 21:45 +07, orchestrator): Dart probe result
The orchestrator ran the Dart lane in the worktree: log /Users/sto/workspace/datomworld-dht-s1/target/orch/r2-cljd.log. JVM 2438/0 and Node 2343/0 pass. CLJD fails `live-socket-ip-family-failure-preserves-canary`:
- Run 1: after a send to `::1` from the IPv4-bound socket, the socket deposited NO `closed` event, and the canary send to 127.0.0.1 answered `:dao.stream/transport-error` (expected ok).
- Run 2 of the same test (the file compiled twice): it timed out after 30 s.
This confirms fable's HIGH: one failed send leaves the Dart socket unusable with no `closed` event.
Fix the Dart seam so the socket's state is always observable. Either:
- (a) the socket survives a failed send; for example, refuse a destination whose IP family differs from the bound family before calling send, as transport-error plus send-failed, as the Dart zero-length rule does; or
- (b) the moment the socket is unusable, the seam marks itself closed and deposits `closed` exactly once, and later sends answer `closed`.
Prefer (a) if the probe shows dart:io itself fails on the family mismatch. Then tighten the test so it accepts exactly ONE outcome, with no either/or. Add a family-mismatch test on JVM and Node as well, so cross-host behaviour is pinned.
Run `bb test:cljd` yourself until you see its verdict. Writable roots for the Flutter/pub/m2 caches are granted for this run. Report: ...gpt-6-sol.report-r3.md

## Fix round 3 (2026-09-30 22:10 +07, orchestrator): conditional grant from fable r2
fable GRANTED its sign-off on condition of F1: collab/1790770800000-architect-dht-s1-signoff-r2.claude-fable-5-1.findings.md.
- F1 (MEDIUM, the condition) | jvm.clj:87 | The JVM seam has no family check, so a wildcard-bound socket (the default bind-host) sends cross-family with `ok`, contradicting §4.
  - Fix: before `.send`, add the same family check Node and Dart have: deposit send-failed "IP family mismatch" and answer transport-error.
  - Test: bind 0.0.0.0, send to ::1, expect transport-error, send-failed and a surviving canary. Show it FAILS before the fix.
- Also do the Lows and Infos:
  - a docstring note that the Dart onError path is untested;
  - rename the JVM and Node `live-socket-ip-family-failure-preserves-canary` tests (which really test a bad port) to `live-socket-send-failure-preserves-canary`;
  - delete the duplicate "dropped" in §4;
  - strip the trailing whitespace at datagram.cljc:300.
Keep the change confined to these items. Run kondo, full `clj -M:test`, `bb test:cljs` and `bb test:cljd`, and see each verdict. Report: ...gpt-6-sol.report-r4.md

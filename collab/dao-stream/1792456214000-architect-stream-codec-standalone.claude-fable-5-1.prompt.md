Created-GMT: 2026-10-20 00:30:14 GMT
Created-Local: 2026-10-20 07:30:14 +07
Coding-Agent: claude
Session-ID: 8c79f881-a441-4e88-9098-a7d3b7a75d13
# Task: make the codec design a standalone track, independent of the jolt design
Role: Architect
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-20 07:30:14 +07 | Status: active | Rationale: correction round; the owner separated the tracks and my earlier brief wrongly asked for jolt consequences in the codec document

Owner direction, verbatim: "the codec redesign should be independent of jolt design doc. an independent orchestrator should be able to work on it independent of this track". Error to own: the orchestrator's previous brief told you to include jolt consequences and to replace the jolt doc's ruling 1/slice T0; that is withdrawn.

Do, editing in place:
1. /Users/sto/workspace/datomworld/docs/design/dao.stream.codec.md must stand alone. Remove every reference to jolt and to yin.repl-jolt-implementation.md (9 hits at lines 12, 82, 390-396, 405, 454-455 and any others: grep -i jolt). The document justifies itself from the repo alone (Transit privileges, CBOR as superset, the future-encodings goal). The "which lanes/hosts" analysis stays for the existing hosts (clj, cljs, cljd) and generically for "a host without cognitect.transit" if you need that example, WITHOUT naming jolt. Slices C0-C4 and the owner-gated C5 stay; make their acceptance self-contained; state its own open owner decisions in one section (yin.repl default profile, the 571-line cljd Transit port, keep :ws/* profile keys or move them). Add a short "For a successor orchestrator" section: what is decided, what is not, the first slice to start, which test lanes/hosts to run (cite docs/agents/build-n-test.md), and the review requirement (Claude-authored, so a non-Claude architect signs off).
2. /Users/sto/workspace/datomworld/docs/design/yin.repl-jolt-implementation.md may mention the codec track ONLY as one dependency statement: "J6 (native WebSocket) needs the codec track (docs/design/dao.stream.codec.md) to have landed C0-C4; jolt requires nothing else of it" plus the minimal jolt-side consequences that are about jolt (the .jolt CBOR wire writer, which codec jolt passes). Remove any restatement of the codec design from the jolt doc. Keep the A4/A5/Autobahn corrections already folded in.
3. Do not touch settled owner decisions or proposed defaults. Flag owner-level questions; do not decide them. Fix the test-count discrepancy you noticed (22 namespaces, not 20) in whichever doc cites it.
Write your rationale to /Users/sto/workspace/datomworld/collab/1792456214000-architect-stream-codec-standalone.claude-fable-5-1.findings.md (same header fields, under 350 words, include the grep -ci jolt count of the codec doc, which must be 0). No staging, no commits, no plan mode, no src edits.

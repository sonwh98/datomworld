# Mob brief: three open owner questions for the jolt host-lane plan

You are one of four independent model families consulted. Do not assume the others' answers. The owner reviews all four tomorrow; give a clear recommendation, not a survey.

Read first:
- /Users/sto/workspace/datomworld/collab/1792426031000-architect-jolt-lane-plan-r6.gpt-6-astra.findings.md (the plan; sections 6, 6.1, 9 J0-J8, 10)
- /Users/sto/workspace/datomworld/collab/1792425688000-architect-jolt-lane-plan-review-r5.claude-opus-5-5.findings.md (independent review, findings R3, R4, R6, R7)
- /Users/sto/workspace/datomworld/docs/design/datom.world.md (invariants; "implementations do not add contracts"; dao.stream is the one boundary; P2P, no privileged node)
- /Users/sto/workspace/datomworld/src/cljc/dao/stream/datagram.cljc (lines 25-60, 280-320) and docs/design/dao.stream.datagram.md
- the jolt checkout /Users/sto/workspace/datomworld/build/jolt-spike/jolt (@96d7b866): stdlib/jolt/socket.clj, stdlib/jolt/socket/native.clj, stdlib/jolt/io_poller.clj, CHANGELOG.md

Settled owner decisions (do not reopen): Q1 local-REPL milestone first; Q2 macOS arm64 + Linux x86_64, Windows out; Q3 in-process ANTLR4 C++ parser over FFI, no fallback; Q4 mise-driven pinned/hash-checked vendored builds for libblake3 and the ANTLR C++ runtime, system/Homebrew OpenSSL with a pre-digest version check. Jolt is a Clojure-on-Chez implementation, main on GitHub, single maintainer, fast churn, no deps.edn coordinates.

## Q5 - update cadence and benchmark budgets
How often should this repo move its pinned jolt revision, through what gate, and what performance budgets (vs the JVM lane) should the lane commit to or defer? The architect proposes deciding thresholds after J0/J8 evidence. Recommend a concrete cadence and gate (for example: pin by SHA, upgrade only through a named gate suite, who triggers it) and say whether budgets should be set now or after evidence, and why.

## Q6 - WebSocket sidecar at parity (parser half is moot: the parser is in-process)
For the later parity phase (J6/J8), may the jolt process tree include a JVM WebSocket sidecar, or must jolt carry its own WebSocket over jolt.socket (read, not run: non-blocking TCP, fibers park on the poller)? Weigh: the repo invariant "dao.stream P2P, no server/client concept, no privileged node"; the spike's argument for jolt as a standalone native binary; implementation cost of RFC 6455 handshake, framing, masking, close, backpressure on top of jolt.socket; SHA-1 via libcrypto; test burden; risk. Give a recommendation and the condition under which you would reverse it.

## Q7 - UDP would-block semantics
The landed dao.stream datagram contract has no :full outcome ("this layer holds no outbound queue and a socket under pressure loses datagrams -- the medium's declared nature, not a refusal", datagram.cljc:25-33). A send that returns EAGAIN/EWOULDBLOCK is today a transport error. Should the shared contract stay as landed (J0b only tests that), or be amended to add a :full outcome (touching every host, and the doctrine line "implementations do not add contracts")? Recommend, with the cross-host consequences and what a jolt implementation must do either way.

## Also
List any further question the owner must answer before J0a/J0b/J0c are commissioned that the plan has missed (one line each, or "none").

## Output format
Under 700 words. Sections: Recommendation Q5, Q6, Q7 (each: answer, 2-4 reasons, reverse-if), Missed questions, Confidence (high/medium/low per question), Unverified (what you could not check). Cite file:line for any factual claim about the repo or jolt. Do not edit anything except your own findings/answer file where one is named. Do not enter plan mode.

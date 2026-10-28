# Architect final sign-off: slice C2 fixes (R1, R2, N1, N6)

- Date: 2026-10-20 18:23 +07
- Reviewer: claude-fable-5-1 (Architect & Reviewer)
- Worktree: /Users/sto/workspace/datomworld-codec-c2, branch codec-c2, HEAD eacaed90 (C1) plus uncommitted C2 working tree (28 files, +428/-180)

## Verdict: SIGN-OFF (Claude reviewer; not sole sign-off for Claude-authored code per dao.stream.codec.md §11)

All four items verified against the working tree. The JVM dao.stream lane is green.

## R1: host seam carries the codec subprotocol name — VERIFIED

- test/dao/stream/ws_test.cljc:10-12 defines `seam` as `(codec/codec-name transit/profile)`; every fake socket in the file (lines 229, 248, 279, 311, 344, 436, 539, 594) carries `:ws/subprotocol seam`. No hard-coded `"dao.stream.transit-json"` string remains in the test.
- test/dao/stream/ws/dart_test.cljd:94 attaches `:ws/subprotocol (codec/codec-name transit/profile)` to the Dart socket view so the accepted-handle test goes through the same seam resolution as the other hosts.

## R2: OQ1 resolution recorded in docs/design/dao.stream.codec.md — VERIFIED

- §6.4 (lines 639-645): "Owner decision, 2026-10-20 (OQ1 resolved)": yin.repl defaults to `dao.stream.cbor/profile`, `--codec <cbor|transit>` on the CLI, `(connect url :codec :transit|:cbor)` at the prompt, default moved in C2 not C5. The Architect's recommendation stays below it, marked as recommendation.
- §7.1 C2 row (line 708-712): serve/connect pass CBOR by default, `--codec`/`:codec` select Transit. C5 row (line 736) marked "Superseded: the yin.repl CBOR default moved to C2 (§6.4, OQ1)".
- §10 OQ1 row (line 941): RESOLVED 2026-10-20 (owner), same wording. OQ2 still conditioned on OQ1 (keep the Dart Transit port while any suite selects Transit), which remains correct.
- §11 (lines 962-964, 972): "OQ1 resolved 2026-10-20 ... C5 no longer waits for OQ1" and "the C5 default flip is done in C2". Internally consistent with §6.4, §7.1 and §10.
- Source matches the doc: src/cljc/yin/repl/connect.cljc:300-302 defines `codec-profiles {:cbor :transit}`, `:or {codec cbor/profile}` at :343 and :444; src/cljc/yin/repl/serve.cljc:208 defaults to cbor/profile; src/cljc/yin/repl/main.cljc:505-513 `parse-codec` rejects anything but cbor/transit with a clear ex-info, :707 defaults `:cbor`, :736 help text says "cbor (default) or transit"; src/cljc/yin/repl/driver.cljc:343-381 handles `(connect url :codec :transit)` and defaults :cbor; state.cljc:49 registers `--codec` as a value flag.

## N1: ws_project_peer.cljc rejects an unknown codec — VERIFIED

- test/dao/stream/ws_project_peer.cljc:455-466: `-main` splits args on `--codec`, looks the name up in `profiles`, and on a miss prints `unknown --codec "<name>" (expected cbor|transit)` and exits with status 2 before any serve/dial. Default is transit for the peer, which is a test harness choice; the cross-host suites (ws_project_cross_jvm_test.clj:174-177, ws_project_cross_node_test.cljs:191) always pass the flag explicitly, so no suite reaches the peer's default.
- Docstring at :20 documents the flag and the default.

## N6: yin.repl.md documents the codec — VERIFIED

- src/cljc/yin/vm/docs/yin.repl.md:49-52: "The wire codec defaults to CBOR. `--codec <cbor|transit>` selects it for a served shell (`--port`) and for the startup connection; at the prompt, `(connect url :codec :transit)` or `:cbor` overrides it for that connection. Both ends of a connection must use the same codec." Matches the implementation and the design doc.

## Lane run: `bb test:sub:clj dao.stream` — PASS

| lane | namespaces | tests | failures | seconds |
|------|-----------:|------:|---------:|--------:|
| clj  | 35         | 499   | 0        | 9       |

Selected 41 of 267 namespaces (35 ran on the JVM lane). Log: target/profile/sub-clj.log.

## Gaps (not blocking this sign-off, must be stated at landing)

- Only the JVM lane was run here, as the task specified. The C2 acceptance in §7.1 requires three lanes green (G1 `bb test:sub dao.stream`) and `bb test:changed` (G2) before landing; the Node and Dart runs and their counts belong in the C2 slice report.
- The §7.3 bidirectional old/new peer check for both profiles is a C2 landing requirement; it was not part of this verification.
- Per §11, a non-Claude architect must co-sign this Claude-authored slice; this file is a Claude reviewer's sign-off only.

Created-GMT: 2026-10-20 11:05:00 GMT
Created-Local: 2026-10-20 18:05:00 +07
Coding-Agent: claude
Session-ID: fresh

# Task: Architectural & Code Review of Slice C2 (Migrate)
Role: Architect & Reviewer
Model: claude-fable-5-1

Working Directory: /Users/sto/workspace/datomworld-codec-c2
Branch: codec-c2 (base commit: eacaed902)
Governing Documents:
- /Users/sto/workspace/datomworld/docs/design/dao.stream.codec.md (§7.1 C2, §7.2, §7.3, §10 OQ1)
- Engineer Report: /Users/sto/workspace/datomworld/collab/1792495500000-engineer-codec-c2.claude-sonnet-5-5.findings.md

Review Objectives & Checks:
1. Verify Codec Migration & Configurability:
   - Check `yin.repl` wire default is `dao.stream.cbor/profile`.
   - Check CLI `--codec <cbor|transit>` argument parsing in `yin.repl.main` and documentation in `help-lines`.
   - Check runtime dynamic configurability in `yin.repl.driver`: `(connect url :codec :cbor|:transit)`.
   - Check `yin.repl.serve` and `yin.repl.connect` pass explicit profiles.
   - Check `src/cljc/yin/repl/dht.cljc` and `src/cljc/yin/vm/linker/head/board.cljc` changes.
2. Verify Call Site Migration (§7.2):
   - Check one-arity descriptor gates (`ws/descriptor?`, `ws/servable-descriptor?`) are replaced by explicit two-arity calls.
   - Check all test call sites in §7.2 (`ws_test`, `remote_channel_test`, `ws_project_test`, `loopback_net`, etc.) pass explicit codec profiles.
   - Check parameterized test execution over both profiles (`remote_channel_test.cljc:the-suite-over-cbor`).
3. Verify Grep Gate:
   - Verify no unintentional codec-less calls remain in `src/` or `test/`.
4. Verify §7.3 Bidirectional Old/New Peer Proof:
   - Verify the peer verification results between `f0-old-peer` (47f43405c) and the new build across JVM and Node lanes. Note the reported status for Dart.
5. Verification Run:
   - Run tests: `bb test:sub:clj dao.stream dao.jing yin.repl`
   - Run `bb test:sub:cljs dao.stream dao.jing yin.repl`
   - Run `bb test:sub:cljd dao.stream dao.jing yin.repl`
   - Verify frozen wire fixtures pass 100%.

Deliver a structured review report to:
/Users/sto/workspace/datomworld/collab/1792498000000-architect-c2-review.claude-fable-5-1.findings.md
Provide an explicit verdict: SIGNED OFF, SIGNED OFF WITH CHANGES, or NOT SIGNED OFF.

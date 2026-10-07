Created-GMT: 2026-09-24 19:24:00 GMT
Created-Local: 2026-09-25 02:24:00 +0700
Coding-Agent: zcode (GLM-5.3-Flash subagent)
Session-ID: zcode-subagent (remote blocking-client ingress fix)

# Task: DaoJing CBOR Swap — Fix the r4 P1 (blocking content-client ingress)

Role: Storage & Indexing Engineer (ZCode subagent, GLM-5.3-Flash)

Repository: /Users/sto/workspace/datomworld, branch dao-jing-cbor-swap,
uncommitted working tree. The round-4 consensus review returned
REQUEST CHANGES with exactly one finding:

P1 | src/cljc/dao/jing/remote.cljc:136 | The blocking `content-client`'s
:get-bytes-fn returns a found reply after Base64 decoding alone
(remote.cljc:139-141); `jing/get` then hash-verifies via segment-value,
which calls decode-snapshot rather than checking canonicality
(jing.cljc:457). A hostile server can serve hash-valid, noncanonical bytes
under their own address and expose the decoded value, contrary to the
remote-ingress contract (docs/design/dao.jing.cbor.md:124).

Full r4 report: collab/1790271252483-reviewer-daojing-cbor-swap-fixes-r4.gpt-6-sol.stdout.log

Prescribed fix (from the r4 reviewer):
- In the blocking content-client's :get-bytes-fn (remote.cljc:136-160),
  hash-verify AND strict-decode each found reply before returning its
  bytes — mirror dao.jing.remote/accept-bytes! (remote.cljc:35-51) and the
  stepped client's fixed receipt (remote/step.cljc:302-317): Base64 decode,
  verify the digest against the requested address (segment-bytes-match?),
  then strict cbor/decode; map refusal onto the existing error path the
  blocking handle already uses for malformed responses (an ex-info like
  the malformed-RPC one at remote.cljc:151-159 is acceptable — follow the
  file's own conventions and the materialize! verify-hop expectations).
  Mind the distinction between :found? false (absent, returns not-found)
  and a found-but-invalid reply (refusal).
- Add a hostile-pair test to test/dao/jing/remote_test.cljc mirroring the
  stepped client's a-hash-valid-noncanonical-payload-is-an-integrity-failure
  (test/dao/jing/remote/step_test.cljc): an address minted over
  noncanonical-but-decodable bytes ([0x18 0x01]), a server serving them,
  asserting the blocking client refuses (the existing wire-test pattern in
  remote_test runs on JVM only — follow it).

Constraints:
- Touch only src/cljc/dao/jing/remote.cljc and test/dao/jing/remote_test.cljc.
- Pure ASCII, <= 80 columns on every line you add or edit.
- Do NOT commit. No temporary diagnostics left behind.
- JVM tooling under mise (mise exec -- <cmd>).
- Verify: focused JVM (mise exec -- clojure -M:test -n dao.jing.remote-test
  and -n dao.jing.remote.step-test) must pass; then the full JVM suite.
  Dart and Node are not affected by this file pair but the orchestrator
  re-runs all three lanes anyway; run only the JVM lanes yourself.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>

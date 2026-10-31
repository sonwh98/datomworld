Created-GMT: 2026-09-24 19:56:00 GMT
Created-Local: 2026-09-25 02:56:00 +0700
Coding-Agent: codex
Session-ID: resume-of-01a0d2f3-1ddd-75e2-840f-a3dda37d3b8e

# Task: Consensus Verification Round 5 — DaoJing CBOR Swap (r4 delta)

Role: Adversarial Code Reviewer and Security Auditor

You are resuming your own conversation; round 4 (your previous turn in this
thread) returned REQUEST CHANGES with exactly one finding:

P1 | src/cljc/dao/jing/remote.cljc:136 | The blocking content-client's
:get-bytes-fn returned a found reply after Base64 decoding alone, so
hash-valid noncanonical bytes could reach jing/get's snapshot decoder.

The fix (implemented by a GLM subagent, in the same uncommitted working
tree of /Users/sto/workspace/datomworld, branch dao-jing-cbor-swap):

1. src/cljc/dao/jing/remote.cljc:148-163 — the :get-bytes-fn found-reply
   arm now treats a found reply as remote ingress: after the unchanged
   strict-Base64 decode (still mapping failure onto the existing
   malformed-RPC error path), the bytes are hash-verified against the
   requested address via jing/segment-bytes-match? (throwing
   "content address does not match payload hash", mirroring accept-bytes!
   at remote.cljc:35-51), then strict cbor/decode, before the bytes are
   returned. :found? false still returns not-found untouched.
2. remote.cljc:112-115 — content-client docstring states the ingress
   admission.
3. test/dao/jing/remote_test.cljc:407-437 — the blocking client's hostile
   -pair wire test a-hash-valid-noncanonical-payload-is-an-integrity-
   failure ([0x18 0x01] pair, JVM-only network-* pattern), verified red
   without the fix and green with it.
4. Reconciliation the fix required: test/yin/vm/debruijn_linker_test.cljc
   :247-272 — corrupt-rpc-response-is-an-address-mismatch now expects
   :absent for the RPC-corruption case (:H and :R): the reply is refused
   at the client ingress boundary, so read-address's documented fail-closed
   catch classifies the handle failure :absent. The store-level
   local-storage-corruption-is-an-address-mismatch test still expects
   :address-mismatch (store corruption still reaches the linker's own
   check). No source change to read-address.

Fresh orchestrator evidence on the final tree (all three lanes run
independently by the orchestrator; do not rerun suites):
- Dart (mise exec -- bb test:cljd): 1,904 passed, 0 failed, "All tests
  passed!".
- JVM (mise exec -- clojure -M:test): 1,996 tests, 180,203 assertions,
  0 failures, 0 errors.
- Node (mise exec -- clj -M:cljs -m shadow.cljs.devtools.cli compile
  slice-peer test): 1,912 tests, 47,303 assertions, 0 failures, 0 errors.

Task — read-only adversarial verification:
1. Rule your r4 P1 CLOSED/PARTIALLY CLOSED/OPEN with file:line evidence.
2. Adversarially review the fix itself: is the found-reply ingress
   (hash-verify + strict decode) complete and correctly ordered? Any path
   where unverified bytes still leave the handle (error arms, absent arm,
   envelope shapes)? Does the throw interact correctly with jing/get and
   the materialize! verify hop?
3. Judge the linker-test reconciliation: is :absent the correct
   classification under the design's layering (untrusted bytes verified at
   the client ingress; read-address fail-closed), and is the store-level
   :address-mismatch retention right?
4. Confirm no regressions and that hygiene holds on the new lines.

Do not edit files or run suites. Cite file:line evidence for every finding.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report actionable findings as:
P0-P3 | file:line | evidence | concrete fix
State "No actionable findings" when appropriate.

End with exactly one line:
Verdict: READY
or
Verdict: REQUEST CHANGES

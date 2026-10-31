Created-GMT: 2026-09-24 16:52:00 GMT
Created-Local: 2026-09-24 23:52:00 +0700
Coding-Agent: zcode (GLM-5.3-Flash subagent)
Session-ID: zcode-subagent (cbor-swap r3 fix)

# Task: DaoJing CBOR Swap — Apply the Two r3 Code Findings

Role: Storage & Indexing Engineer (ZCode subagent, GLM-5.3-Flash)

The round-3 consensus review of the CBOR swap (read
collab/1790267002049-reviewer-daojing-cbor-swap-fixes-r3.glm-flash.findings.md
for full evidence) returned REQUEST CHANGES with two code findings. Apply
exactly these in the uncommitted working tree of /Users/sto/workspace/
datomworld (branch dao-jing-cbor-swap). Do NOT commit. Change nothing else.

1. P1 — src/cljc/dao/jing/remote/step.cljc:304: the remote get-content
   client receipt decodes untrusted reply bytes with jing/segment-value,
   which since the r2 delta is hash-verify + cbor/decode-snapshot (no
   canonicality check). The contract (docs/design/dao.jing.cbor.md:125-128,
   :458-459, :638-640) requires strict decode at every ingress of bytes
   Jing did not encode itself, matching the site's own comment at
   step.cljc:295-297 and the storage-side receipts (remote.cljc:50,
   dht.cljc:174, dht/node.cljc:120).
   Fix: after accepted-bytes hash verification, decode strictly — replace
   jing/segment-value with cbor/decode (mirroring
   dao.jing.remote/accept-bytes! at remote.cljc:35-51) or add a strict jing
   wrapper — mapping refusal onto the existing ::refused/integrity-failure
   arm. Then add a step_test case (test/dao/jing/remote/step_test.cljc,
   near the existing hash-mismatch pin at :267-285) feeding a hash-valid
   noncanonical payload and asserting the integrity-failure completion.
2. P3 — src/cljc/dao/jing/file.cljc:365: the make-put collision message
   line is 81 columns. Split the literal like mem.cljc:66-67 does and
   reflow under the throw.

Constraints:
- Pure ASCII, <= 80 columns on every line you add or edit.
- Touch only step.cljc, step_test.cljc, file.cljc.
- Verify with ONE focused run (use mise exec -- clojure -M:test -n
  dao.jing.remote.step-test) and report its counts. Do not run full
  suites; the orchestrator re-verifies everything independently.
- Do not commit. Report per-item with file:line evidence, then end with
  exactly one line: "Status: COMPLETE" or "Status: BLOCKED - <reason>".

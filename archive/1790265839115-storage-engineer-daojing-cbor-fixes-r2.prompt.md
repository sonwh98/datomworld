Created-GMT: 2026-09-24 16:04:00 GMT
Created-Local: 2026-09-24 23:04:00 +0700
Coding-Agent: claude (claude-sonnet-5)
Session-ID: resume-of-0905c1a1-ff26-4582-9cb5-ec25468e7593

# Task: DaoJing CBOR Swap — Address Consensus Review Round 2 (r2)

Role: Storage & Indexing Engineer

Implementers:
- Model: claude-sonnet-5 | Assigned: 2026-09-24 23:04:00 +0700 | Status:
  active | Rationale: You authored the prior fix round in this session; the
  consensus reviewer stayed GPT-family, so reviewer independence is intact.

The adversarial consensus follow-up review of your fixes returned
REQUEST CHANGES. Read its findings file first:
- collab/1790264589986-reviewer-daojing-cbor-swap-fixes-r2.gpt-6-sol.findings.md

It ruled your first round: findings 1-3 CLOSED (mem :state exposure,
encode-once boundary, dao.jing.md), finding 4 PARTIALLY CLOSED, finding 5
CLOSED for the three named fixtures. Six new actionable findings follow.
Address them in the same uncommitted working tree on branch
`dao-jing-cbor-swap`. Do NOT commit; the orchestrator commits after the
next consensus verdict.

## Work items

1. P1 — yin.vm carrier awareness (Node suite failures). The Node run's
   "1 failure, 1 error" are NOT an accepted limitation:
   - target/test-cljs.log:314 — "Projected slot value does not fit its
     declared type", :rule :unsupported-value, :slot :yin.debruijn/value,
     :value #dao.jing/float64 1.5. The canonical-value classifier at
     src/cljc/yin/vm/debruijn.cljc:273 (and its consumers, see
     debruijn.cljc:1176) accepts plain numbers but rejects the decoded
     #dao.jing/float64 carrier that the codec requires on decode
     (docs/design/dao.jing.cbor.md:375).
   - test/yin/vm/pipeline_test.cljc:585 — a test compares a decoded
     #dao.jing/float64 2.5 against a bare 2.5.
   Fix: make the VM's canonical-value and numeric slot paths carrier-aware
   for the dao.jing tagged carrier kinds (minimal change — accept and see
   through the carrier; do not redesign the classifier), and make the test
   compare portable content consistently. The Node suite must end fully
   green; re-run it yourself and paste the counts.
2. P2 — validate-before-copy race in both byte-store puts. mem.cljc:51
   validates the caller-owned mutable array, then copies it; file.cljc:348
   mirrors this. A concurrent caller mutation between validation and copy
   can store/append bytes that no longer hash to the address; duplicate
   checks also compare the original array (mem.cljc:60, file.cljc:360).
   Fix: copy first, then validate, frame, and compare only the snapshot.
3. P2 — decode re-encodes every value (cbor.cljc:1536 does
   bytes= bs (encode value)), so trusted-snapshot reads through
   segment-value (jing.cljc:470) re-encode too, contrary to the read-path
   contract at docs/design/dao.jing.cbor.md:124. Fix: separate strict
   ingress canonicality validation from trusted-snapshot decoding while
   retaining address-hash verification on reads. Do not weaken ingress
   strictness.
4. P2 — stale design doc: docs/design/dao.jing.hash-registry.md:128 and
   :505 still describe canonical-bytes as returning order-normalized print
   bytes with CBOR as future work. Update to the completed clean break
   (canonical-bytes delegates to cbor/encode; digest inputs are canonical
   CBOR bytes).
5. P3 — file-handle mutation-isolation tests: test/dao/jing/file_test.cljc:326
   covers disk-byte corruption but not mutation of the byte array passed to
   or returned from the file handle. Mirror the memory tests
   (test/dao/jing/mem_test.cljc:234) including a reopen check.
6. P3 — hygiene, added lines only. The diff introduced zero net hygiene
   regressions, but these exact added lines violate the gate; reflow them:
   - src/cljc/dao/jing/cbor.cljc — 1 added line > 80 cols (the
     "rational payload is not [numerator denominator]" message)
   - src/cljc/dao/jing/remote/step.cljc — 1 added line > 80 cols ("the
     remote content does not hash to its content address")
   - test/dao/jing/dht_test.cljc — 1 added line > 80 cols
   - test/dao/jing/remote_test.cljc — 1 added line > 80 cols
   - src/cljc/dao/data/btree/storage.cljc — 1 added non-ASCII character
   Do NOT reflow pre-existing violations elsewhere (logged as separate
   debt) and do NOT touch docs/orchestrator-log.md or doc tables.

## Constraints

- Pure ASCII and <= 80 columns on every line you add or edit.
- Keep all changes inside the existing uncommitted diff scope; no new
  files beyond tests if needed.
- Prefer one simple command per step when running tools (headless Bash
  approval denies compound one-liners).
- Verification you must run and report: JVM suite (clojure -M:test), Node
  suite (shadow-cljs :test node-test build, as in your previous run), plus
  clj-kondo and cljstyle on touched files if available.
- Do not commit. The orchestrator re-runs both suites independently and
  then dispatches consensus review r3.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Summarize changes per work item with file:line evidence, then the exact
suite counts. End with exactly one line:
Status: COMPLETE
or
Status: BLOCKED — <reason>

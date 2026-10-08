Created-GMT: 2026-09-24 18:35:00 GMT
Created-Local: 2026-09-25 01:35:00 +0700
Coding-Agent: codex
Session-ID: resume-of-01a0d2f3-1ddd-75e2-840f-a3dda37d3b8e

# Task: Diagnosis Consult — Dart-Lane Failures in the DaoJing CBOR Swap

Role: Adversarial Code Reviewer and Security Auditor (diagnosis consult)

Owner instruction (quote): "you're allowed to use the findings of your
subagent to collaborate with gpt-6-sol to fix this problem." You are the
diagnosis half of that collaboration: produce the root-cause analysis and a
concrete prescribed fix. A GLM implementation subagent runs in parallel and
owns the CLJD/Dart test lane — do NOT run any test suite, build, or anything
that writes; static analysis and file reads only. The orchestrator applies
your prescription through the implementation channel and reports back.

## The three Dart-only failures (full suite: 1,900 passed / 3 failed; JVM
1,995/0 and Node 1,911/0 are green on the same tree; the raw log with full
stacks is at target/test-cljd-verify.log)

1. dao.jing-test/content-hash-keeps-the-set-tag-outside-the-value-domain
   Expected: (not= (jing/content-hash #{1 2})
                    (jing/content-hash (list (quote set) (list 1 2))))
   Threw: #error {:message "dao.jing.cbor refused: unsupported-value (Type)",
                  :data {:detail "Type", :dao.jing.cbor/refusal :unsupported-value}}
   Stack: wire_of refuses <- members_wire <- map_wire <- meta_wire (the
   encoder is walking METADATA) <- wire_of <- encode <- canonical_bytes <-
   content-hash. Fixture: (list (quote set) (list 1 2)) — on Dart something
   in that value's metadata is a Dart Type object.
2. yin.vm-test/semantic-bytecode-strips-reader-positions-inside-payloads
   Same exception, same meta_wire path, from yin.vm ast>semantic-bytecode
   -> segment-key -> canonical-bytes. The test asserts reader positions are
   stripped from payloads before addressing; on Dart the payload's metadata
   still contains a Dart Type when the encoder walks it.
3. yin.vm.pipeline-test/list-literals-persist-in-memory
   Expected: (= :ok (:outcome (:projected as-list)) (:outcome (:projected as-vector)))
   Actual:   (not (= :ok :diagnostic :ok))
   Persisting a program with a list literal completes :diagnostic instead of
   :ok on Dart only.

## Context

- The swap (uncommitted delta on dao-jing-cbor-swap) replaced the
  order-normalized printer with strict canonical CBOR: canonical-bytes ->
  cbor/encode, which refuses anything outside the closed profile. The
  printer stringified arbitrary host values; the strict encoder refuses
  them. Hypothesis to verify: Dart-host metadata (a Type attached by cljd
  to symbols/lists, or by the reader-position strip mechanism itself)
  previously printed silently and now hits the refusal.
- Governing docs: docs/design/dao.jing.cbor.md (profile, metadata
  contract, ingress rules), docs/design/dao.jing.md, the r1-r3 findings
  files under collab/ (1790245519256-reviewer-daojing-cbor-swap.*,
  1790264589986-..., 1790267002049-...).
- The cljd dependency is pinned at sha 81b5c03a55cf52b21dc0be8ccfa4827b9889f488
  (deps.edn aliases :cljd, :cljd-yin-repl, :cljd-yin-repl-build). The owner
  has pre-authorized trying the latest upstream cljd commit IF the root
  cause is a cljd bug — assess that option honestly, including its risk
  (all three lanes must be re-verified after any bump).

## Deliverable

1. Per failure: the true root cause with file:line evidence (what exactly
   is the Type? who attaches that metadata? why only Dart?).
2. One coherent prescribed fix (or a small set if the failures genuinely
   differ): exact file:line edits, which of these it is — (a) strip/
   normalize host-hostile metadata before canonical-bytes per the design's
   metadata contract, (b) fix a defect in the delta's Dart branches,
   (c) correct host-wrong test expectations, (d) bump cljd. State which
   files the fix touches.
3. Call out anything in the delta's design (not just code) that the Dart
   evidence undermines.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with exactly one line:
Status: DIAGNOSIS COMPLETE
or
Status: BLOCKED — <reason>

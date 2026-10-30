Created-GMT: 2026-10-20 00:05:16 GMT
Created-Local: 2026-10-20 07:05:16 +07
Coding-Agent: codex
Session-ID: 01a14f30-ca56-7530-bb93-3a870eca7a7b
Role: Architect (review flavor)
Author: gpt-6-astra

# Independent sign-off: jolt host-lane design

**NOT SIGNED OFF.** Read all 1,735 lines, compared r7 and Opus W1–W9, and checked relevant source. The architecture is substantially faithful; the Transit ruling contains implementation-blocking errors.

Below, D = `docs/design/yin.repl-jolt-implementation.md`; source paths are repository-relative.

| ID | Severity | File:line | Evidence | Required correction |
|---|---|---|---|---|
| A1 | High | D:1073–1079,1493–1495; `src/cljc/dao/stream/transit.cljc:107–123` | Empty `:jolt` requires do **not** select function-level `:default` arms: jolt also enables `:clj`, so encode/decode still select Java/Transit calls. The proposed fallback defining only nil `profile` omits the public functions required by callers and tests. | Specify explicit `:jolt` refusal arms before `:clj` in encode/decode. Remove the one-form fallback or require a complete, narrow public surface with equivalent refusals. Test clean namespace loading and every public codec entry. |
| A2 | High | D:1062–1069; `src/cljc/dao/stream/transit.cljc:61–70`; `test/dao/stream/cbor_test.cljc:209`; `test/dao/stream/cbor_test.cljd:189` | Extraction is not wholly codec-independent: `portable-value?` explicitly rejects Dart Transit records using codec predicates before recursive map admission. Removing the old public vars also breaks existing test references, contrary to unchanged tests/counts. | Preserve the Dart rejection checks through a narrow host dependency or equivalent proven guard; retain forwarding public vars in Transit. Require existing three-lane rejection fixtures plus jolt closure tests. |
| A3 | Medium | D:1055,1087–1088; `src/cljc/dao/jing/stream.cljc:37–39,57–67` | This consumer compares profile subprotocols; it does not call an extracted predicate. With `transit/profile=nil`, nil/empty profiles compare nil=nil and enter the Transit branch instead of the claimed unknown-profile refusal. | Specify the lane-test adaptation explicitly. Require a present, recognized subprotocol/available profile and test nil, empty, unknown and valid CBOR profiles. |
| A4 | Medium | D:1151–1157 | “Identical local observables at both ends” includes the detector's frame-too-large diagnostic. The cited shared test exercises the detecting endpoint, not both peers. The peer may receive only EOF/reset or a close frame. | Assert the diagnostic and rejected payload at the detecting endpoint; assert sender-specific wire behavior and exactly one terminal event at the peer. Preserve ruling 3's per-direction matrix. |
| A5 | Medium | D:585–586,1564–1565; r7:224 | Owner Q5 prefers a **suitable** release-tagged SHA and permits qualification when a needed upstream fix lands. Mandatory newest-release targeting can exclude that fix and is an architect-added restriction. | Keep the initial SHA ruling; restore tag preference, permitting an explicitly qualified untagged fix. A tags-only policy needs owner approval. |

Predicate extraction itself does not add a stream contract: it relocates existing admission rules. With compatibility forwards, preserved Dart exclusions and unchanged fixtures, it is small and reversible. As specified today, those safeguards are missing.

Ruling 2's native/poller substrate is a valid implementation refinement, not an owner-scope change. Ruling 3's wire-code principle holds subject to A4. Ruling 4's initial-pin retention holds subject to A5. Q1–Q7 survive; the three defaults remain separately labelled proposals with disposition preconditions. Native WebSocket supersedes my sidecar recommendation by explicit owner ruling. Synchronous CST corrects my earlier pending-state proposal through W5. CBOR-only, local-build-only, ws-only and shiva are disclosed.

J0a/J0c/J5 gates remain concrete; J6 adds listener, backpressure and peer assertions. Before commissioning J6, pin Autobahn cases and explicit pass/exception criteria: recording reasons for non-strict results alone is not acceptance.

Not verified: no builds, probes, benchmarks or cross-host tests rerun; reported J0b results are not independently reproduced. Later owner quotations were assessed as supplied authority, not independently authenticated. Runtime-specific claims remain read-only evidence, not execution proof.

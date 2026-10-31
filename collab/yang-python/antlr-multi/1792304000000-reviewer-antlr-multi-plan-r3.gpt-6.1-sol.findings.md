I’ll read the v3 corrections, trace them back to the prior findings, and check that the synthetic-token and coordinate rules resolve both P1s.


The revised policy accepts the zero-width DEDENT and rejects a collapsed NAME by type. The error contract also explicitly permits listener coordinates to disagree with the source span, and both helper-token error cases are pinned in the corpus. I’m checking the mutation table and retained exporter for inconsistencies.


The JVM probes match all three acceptance cases and both helper-token error fixtures. The v2-to-v3 diff preserves the preorder, text, UTF-8 boundary, ordering, extent, build, isolation, and retirement requirements. I found no remaining architectural blocker; implementation still has to pass M1 on all three hosts.


Completed-GMT / Completed-Local
2026-10-17 13:20:24 GMT / 2026-10-17 20:20:24 +0700

No blocking findings. Both P1 corrections are resolved at the design level.

- **Synthetic tokens:** the type-keyed policy accepts DEDENT `{:text "" :span [16 16] :synthetic true}` and rejects collapsed NAME tokens. Exporter evidence remains independent; canonical text removes runtime fallback text from packets. Three acceptance fixtures and twelve mutations are required on every host.
- **Error coordinates:** source spans and verbatim ANTLR coordinates have separate invariants. JVM probes confirmed NEWLINE `{:line 3 :column 0 :span [13 14]}` and DEDENT `{:line 3 :column 0 :span [26 26]}`; both disagreements are pinned as expected values.
- **Preservation:** ledger rows 10/11 address the findings. Preorder, uniqueness, real-token text, UTF-8 boundaries, terminal ordering, and rule extents remain enforced. No architectural regression found in the delta.

Review was read-only. Existing JVM probes passed; proposed validators and JS/Dart parity remain M1 implementation gates.

Verdict: READY (sign-off granted)
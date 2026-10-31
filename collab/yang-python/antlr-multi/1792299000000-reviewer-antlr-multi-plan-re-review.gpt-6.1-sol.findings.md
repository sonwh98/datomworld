I’ll read the repository’s review protocol, compare v2 with v1, and walk the revised contracts against the counterexamples and build paths.

The revisions address the missing-target imports, build ordering, genuine goldens, and retirement prerequisites. I’m checking two remaining contract risks: whether synthetic-token validation can detect mislabelled real tokens, and whether error spans always identify the location reported by ANTLR’s listener.

I reproduced two failures using the existing JVM exporter. The required indentation fixture emits a synthetic DEDENT with empty text that §1.3.6 would reject. A malformed assignment before a newline also produces listener coordinates that disagree with the token’s byte span, so the new error-coordinate check needs an explicit exporter change before generating goldens.

Completed-GMT / Completed-Local
2026-10-17 13:05:51 GMT / 2026-10-17 20:05:51 +0700

All nine findings are conceded. The revisions address findings **2–9** at the design level: genuine full-packet goldens, absent-target composition, generation dependencies and classpaths, instance-owned caches, retirement prerequisites, browser acceptance, executable interoperability gates, and dependency provenance.

Finding **1 remains partially unresolved**. The recursive preorder walk correctly rejects duplicate edges `[1 1 2]` and DFS order `[0 1 3 2]`; outcome-specific validation also admits legitimate error packets. However, the new conformance rules introduce two failures.

1. **[P1] Synthetic-token justification rejects the required indentation golden.**  
   [v2:194–197](/Users/sto/workspace/datomworld/collab/1792289000000-architect-antlr-multi-plan.claude-cloud-fable.findings-v2.md:194) requires a synthetic token either to differ from source text at its position or to overlap the preceding real token.

   Executing the existing JVM exporter on `"if x:\n    y = 1\nz = 2\n"` produces:

   ```clojure
   {:type "DEDENT" :text "" :span [16 16] :synthetic true}
   ```

   The source slice is `""`, and the preceding real NEWLINE ends at byte 16. Neither justification holds. Thus the indentation fixture expressly required by [v2:407–419](/Users/sto/workspace/datomworld/collab/1792289000000-architect-antlr-multi-plan.claude-cloud-fable.findings-v2.md:407) cannot pass M1.

   The converse assurance is also too strong: collapsing a misplaced nonempty token to a zero-width span automatically makes its text differ from that empty source slice.

   **Correction:** define justification for zero-width helper tokens separately from text mismatch. If detecting falsely synthetic tokens remains a promised property, supply independent host-token evidence or an explicit grammar-specific policy. Add both acceptance and mutation tests.

2. **[P1] Error-coordinate conformance conflicts with the retained JVM exporter.**  
   [v2:199–201](/Users/sto/workspace/datomworld/collab/1792289000000-architect-antlr-multi-plan.claude-cloud-fable.findings-v2.md:199) requires listener line/column to identify the source position at span start. But [cst.clj:137–154](/Users/sto/workspace/datomworld-c4hang/src/clj/yang/antlr/cst.clj:137) computes spans from token indexes while retaining listener coordinates unchanged.

   I reproduced this with `"if x:\n    y =\nz = 2\n"`:

   ```clojure
   {:line 3 :column 0 :span [13 14] ...}
   ```

   Byte 13 is the newline on **line 2, column 7**. Message normalization in [v2:433–442](/Users/sto/workspace/datomworld/collab/1792289000000-architect-antlr-multi-plan.claude-cloud-fable.findings-v2.md:433) cannot resolve this mismatch.

   **Correction:** explicitly choose canonical source-derived coordinates and update all exporters before generating JVM goldens, or specify a separate contract for listener coordinates. Include errors on helper-produced NEWLINE and DEDENT tokens.

Review was read-only. I executed focused JVM exporter probes against existing generated classes; the proposed validators and JS/Dart implementations do not yet exist and were not executed.

Verdict: REQUEST CHANGES
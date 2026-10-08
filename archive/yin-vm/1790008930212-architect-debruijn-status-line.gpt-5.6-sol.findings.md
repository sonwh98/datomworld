Completed-GMT: 2026-09-21 16:43:24 GMT  
Completed-Local: 2026-09-21 23:43:24 +07 (Indochina Time)  
Coding-Agent: codex  
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a  

REPLACE WITH

1. **Status accuracy**

“Implemented through D6” is accurate. The D0–D6 implementation and tests are merged. The known `dao.jing.file` host limitations are downstream storage limitations covered by fail-safe tests; they do not invalidate completion of the projection phases. The reader’s lack of lexical scope validation for `{:bound [d p]}` is a known limitation outside the D0–D6 completion criteria.

However, “§7 records what each phase delivered” is inaccurate. §7 is written as implementation scope and completion criteria in imperative/prescriptive form; it does not summarize delivered artifacts.

Use this exact status paragraph:

```text
Status: implemented through D6 (merged 2026-09-21). D0–D6 implementation and
tests are merged; the DaoJing file store’s host-specific refusal/hash-mismatch
cases are pinned by tests, and projected-reader lexical scope validation remains
outside this plan. The sections below are the design, phase scope, and
completion criteria used to build and review the implementation; §7 records
phase scope and completion criteria. This is compilation-layer work. The
pipeline is:
```

2. **Approval**

The current wording is not approved as written. The replacement above is the smallest accurate status-paragraph change.

3. **Contradictions elsewhere**

- **P3 — §1, lines 28–30:** “All state is explicit in the forward-step state: input cursor, current graph frame, indexed facts, scope stack, occurrence memo, pending output, and output cursor.”  
  The merged implementation keeps the fact index, scope stack, and occurrence memo inside the atomic per-frame projection; `forward-step` carries cursor, frame, and pending output, but no output cursor. This was previously accepted as an intentional append-only design/documentation mismatch, but the sentence is literally broader than the implementation.

- **P2 — §6, lines 215–218:** “The semantic VM and `yin.vm.linearize` continue to consume the named AST.”  
  `yin.vm.linearize` consumes named AST datoms, but the semantic VM executes a loaded `:yin.code/*` image. Named AST input reaches it only through an explicit composition of observer, linearizer, and loader (`docs/design/yin.vm.semantic.md:331-352`).

- **P2 — §7, lines 262–266:** “Wire Yang’s post-emission path before projected persistence.”  
  The merged D5 artifact is the standalone `yin.vm.pipeline/persist-compiled!` adapter. No Yang namespace currently calls it; the orchestrator explicitly recorded that no existing Yang post-emission hook owned both persistence sides. Thus D5 delivered the orchestration boundary, not an installed Yang call-site integration.

No other sentence in the document is contradicted by the merged D0–D6 code.

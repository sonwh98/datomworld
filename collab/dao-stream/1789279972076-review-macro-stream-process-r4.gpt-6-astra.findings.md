I’ll read revision 4 directly, check the six prior findings, and trace operator expansion and post-harvest ordering. I’ll keep the review read-only.

The operator-first branch now preserves `(x 1)` until `defn` introduces the lambda binding, and the revised draining and admission rules address those prior findings. I found one ordering hole in post-harvest: it can remove a later macro definition because an earlier ordinary definition still appears in the final tree.

Completed-GMT: 2026-09-13 06:14:48 GMT
Completed-Local: 2026-09-13 13:14:48 +07
Coding-Agent: codex
Session-ID: 01a098f8-8420-7083-9d18-f9776512bd61
Role: Routine Review | Model: gpt-6-astra

Read-only review completed. No files were written. Findings concern the design contract, not implementation verification.

| Round-3 finding | Resolution | Section / remaining gap |
|---|---|---|
| P1-1: Operands expanded before operator re-check | **Resolved** | §3.2 expands the operator first and invokes a newly recognized macro with original operands. |
| P2-2: Generated and non-lambda redefinitions missed | **Partially resolved** | §3.1 steps 2 and 5 handle those cases, but step 5 can override a later source macro definition. See finding below. |
| P2-3: Generated inline macro lambdas both required and forbidden | **Resolved** | §3.2 consistently rejects generated macro lambdas, including inline ones; Phase 1 matches. |
| P2-4: Errors never drained; successful output suppressed | **Resolved** | §5 defines read-and-reset reporting; §6.1 evaluates forwarded batches independently of errors. |
| P2-5: Disconnected definitions escape cycle checks | **Resolved** | §3.1 admission checks every indexed entity before decoding. |
| P2-6: Admission failures lack an event path | **Resolved** | §4.1 defines an index-independent batch event with sanitized diagnostics and allocation progress. |

The additional §5 revisions also resolve the prior qualifications: terminal protocol outcomes retain their identity and state, permanent `full` is acknowledged, and crash recovery is excluded. The observer prerequisite correctly preserves throwing behavior while carrying recovery state, with distinct load-failure and flush-failure cursors. It remains appropriately placed in generic coordination.

**Operator-first trace**

For `((choose) f [x] (x 1))`, with `choose` expanding to `defn` and macro `x` present:

1. The outer operator is initially an application, so the dedicated application branch expands `(choose)` first.
2. It rebuilds the parent with operator `defn`, retaining the original operands.
3. The re-check recognizes `defn`; invocation receives `(x 1)` unexpanded.
4. `defn` emits `(yin/def f (lambda [x] (x 1)))`.
5. Re-expansion enters the lambda and adds `x` to `shadow`.
6. The body’s `(x 1)` remains an ordinary application.

This resolves the original binder case. Same-depth recognition is correct: actual invocation increments depth when re-expanding its output.

**[P2 — must address] 1. Post-harvest removes a later winning macro because an earlier plain definition remains.**

Location: [§3.1, initial harvest](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:222) and [post-harvest](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:235).

Consider one batch containing these definitions in the specified harvest order:

```clojure
(def m (fn [x] x))
(defmacro m [x] (yin/literal 42))
```

The written rules produce:

1. Step 2 processes the plain definition, then installs the later macro. The macro is the declared last winner.
2. Step 3 replaces the macro definition with literal `m`.
3. The final tree still contains the earlier ordinary `yin/def m`.
4. Step 5 unconditionally removes `m` from the macro store.

A subsequent batch containing `(m 1)` therefore calls the evaluator’s ordinary function and returns `1`, rather than expanding the winning macro to `42`. This is a silent semantic change caused by post-harvest revisiting a definition already superseded during initial harvest.

An in-memory reduction of the specified store transitions confirmed:

```clojure
{:after-step-2 {m :macro}, :after-step-5 {}}
```

**Required change:** Distinguish definitions already handled by initial harvest from definitions first discovered through expansion. Apply next-batch removals to newly discovered ordinary definitions, preserving initial harvest’s last-winner result for existing source definitions. Track definition origin explicitly; fresh copied eids alone cannot identify whether a definition is new.

Alternatively, specify a unified precedence rule, but it must not silently turn “last definition wins” into “any surviving plain definition wins.”

**Acceptance:**

- Plain definition followed by macro definition: the macro remains available next batch.
- Macro definition followed by plain definition: the macro is absent next batch.
- Existing macro followed by a separate `defn` batch: generated ordinary definition removes it next batch.
- Repeat with rewritten/copied source definition nodes.

The frozen current-batch store and next-batch removal rule is otherwise deterministic. Its explicitly accepted consequence—calls within the current batch use the old macro—also applies inside generated function bodies: those expansions remain embedded when the function runs later. That consequence follows the stated policy. The defect above is different: post-harvest defeats initial harvest’s declared winner even without a generated definition.

**Verdict: REQUEST CHANGES.**

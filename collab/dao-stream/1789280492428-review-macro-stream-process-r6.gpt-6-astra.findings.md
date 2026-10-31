I’ll check the final-tree ranking rule, rerun the ten cases in memory, and trace stand-in identity through macro invocation. This remains read-only.

Two gaps remain. Ordinary parent copying preserves a stand-in leaf’s eid, but passing it through a macro decodes it to an eid-free map and re-encodes it with a fresh eid. Also, the fixed traversal visits a lowered `do`’s later expression before its earlier expression, reversing several of the stated last-wins results.

Completed-GMT: 2026-09-13 06:23:22 GMT
Completed-Local: 2026-09-13 13:23:22 +07
Coding-Agent: codex
Session-ID: 01a098f8-8420-7083-9d18-f9776512bd61
Role: Routine Review | Model: gpt-6-astra

| Round-5 finding | Resolution | Assessment |
|---|---|---|
| P2: `m` is not a definition-origin marker | **Partially resolved** | §3.1 now detects both previously missed ordinary-definition cases. However, final-tree ordering reverses stated precedence under existing sequence lowering, and stand-in identity does not survive macro invocation. |
| P3: Unsupported inline-macro branch | **Resolved** | §2.3 rejects the supported-input interpretation; §3.2 explicitly marks the branch defensive and unreachable. |

No files were written. The following reductions ran in memory using the specified ranking rule and the repository’s sequence-tree shape; these are design-model checks, not expander implementation tests.

**Reduction results**

`M` means `{m :macro}`; `—` means `{}`. Step 2 uses the requested batch-definition order. Final trees use the existing lambda-based sequencing described below.

| Case | After step 2 | After step 5 | Expected next store |
|---|---:|---:|---:|
| Plain then macro | M | — | M |
| Macro then source plain | — | M | — |
| Macro then generated `defn` | M | M | — |
| Existing macro + separate `defn` batch | M | — | — |
| Plain then macro, re-parented | M | — | M |
| Macro then source plain, re-parented | — | M | — |
| Macro then generated `defn`, re-parented | M | M | — |
| Existing macro + separate `defn`, re-parented | M | — | — |
| Existing macro + `(defn m [x] (wrap x))` | M | — | — |
| Existing macro + `((choose-def) 'm f)` | M | — | — |

The two r5 tagging failures are corrected: neither descendant copying nor operator formation hides an ordinary definition anymore. Six sequencing cases nevertheless fail for a new reason.

**[P2 — must address] 1. Fixed tree traversal reverses definition order in lowered sequences.**

Location: [§3.1 step 5](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:249), [fixed traversal order](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:299), [existing `compile-do`](/Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc:258).

The repository lowers:

```clojure
(do A B)
```

to:

```clojure
((fn [_] B) A)
```

The specified traversal visits the operator and its lambda body before the operands. It therefore visits **B before A**. Last-in-tree-order consequently makes **A win**.

For plain definition A followed by macro definition B:

1. The scan encounters B’s stand-in and installs the macro.
2. It subsequently encounters A’s ordinary definition and removes it.

The reverse ordering reinstalls a macro that a later plain definition was supposed to remove. Parent copying does not change this traversal order.

This rule is deterministic, but it contradicts §3.1’s enumerated consequences and Phase 1 expectations. “Tree position survives copying” does not establish that tree traversal preserves declaration precedence.

**Required change:** Define declaration-ranking order separately from expansion traversal, accounting explicitly for the lambda-based sequence representation—or retain an explicit ordering representation. Do not use the current operator-first traversal as a substitute for source/program sequence order.

**Acceptance:** Run the ordering cases through actual `do`/`compile-program` lowering, including nested sequences. Flat lists of definition records would miss this defect.

**[P2 — must address] 2. Stand-in identity is lost when an enclosing macro returns its operand.**

Location: [replacement record](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:237), [map boundary](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:283), [invocation and fresh encoding](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:316).

I agree with the narrow claim: **ordinary ancestor re-parenting does not copy an unchanged leaf**. That is not the only transformation a stand-in can undergo.

Consider an already available identity macro:

```clojure
(identity-macro
  (defmacro m [] (yin/literal 42)))
```

The specified transitions are:

1. Harvest installs `m` for the current batch.
2. Replacement creates literal `m` at eid E and records `:replaced {E m}`.
3. Invoking `identity-macro` decodes that operand to `{:type :literal :value m}`.
4. The macro returns the operand unchanged.
5. Encoding allocates fresh eid E′.
6. Post-harvest sees E′, which is absent from `:replaced`, and fails to retain `m`.

The enclosing macro preserved its operand; it did not discard it. Nevertheless, the declaration disappears from next-batch state.

The in-memory identity reduction produced:

```clojure
{:before {m :macro}
 :after-macro-decode-encode {}}
```

A source literal `'m` is safely distinguished while original eids remain available. After the macro boundary, however, the returned stand-in and an ordinary literal `'m` have identical public representations. Matching by value would introduce false positives; the current map cannot preserve the distinction.

**Required change:** Choose an explicit persistence contract that survives the transformer boundary. Either preserve protected declaration identity through syntax transformation, or make declaration persistence independent of recognizing stand-ins in returned eid-free syntax. The current “plain literal plus original eid” representation cannot support the promised final-tree semantics.

**Acceptance:** Cover an identity macro preserving a declaration stand-in, a wrapper preserving it inside a larger result, a macro discarding it, and an ordinary `'m` literal. Preservation must retain the intended declaration without treating ordinary symbol literals as declarations.

Ranking syntactic `yin/def` occurrences in unexecuted branches or lambda bodies is otherwise consistent with the stated syntactic policy. The blockers are the ordering mismatch and lost declaration identity, rather than failure to evaluate those forms.

**Verdict: REQUEST CHANGES.**

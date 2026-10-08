I’ll check revision 5’s ordering and provenance rules, then run the requested reductions in memory. I’ll keep the review read-only.

The four base reductions now produce the stated winners. The `m` test still has a gap: re-parenting can remove a generated definition’s event tag, and an operator rewrite can create a `yin/def` whose parent carries `default-op`. In both cases, post-harvest misses the ordinary redefinition.

Completed-GMT: 2026-09-13 06:19:31 GMT
Completed-Local: 2026-09-13 13:19:31 +07
Coding-Agent: codex
Session-ID: 01a098f8-8420-7083-9d18-f9776512bd61
Role: Routine Review | Model: gpt-6-astra

**Round-4 P2: Partially resolved.** [§3.1 step 5](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:240) fixes the original plain-then-macro precedence error. However, identifying generated definitions through their final `m` tag misses valid redefinitions.

No files were written. The reductions below ran in memory against the specified rules; they are not implementation tests.

**Requested reductions**

`{m :macro}` means the macro remains installed; `{}` means it is absent. Re-parented cases apply §4.1’s `default-op` rule to the copied definition ancestor.

| Case | After step 2 | After step 5 | Result |
|---|---|---|---|
| Plain then source macro | `{m :macro}` | `{m :macro}` | Matches |
| Source macro then source plain | `{}` | `{}` | Matches |
| Source macro then generated `defn` | `{m :macro}` | `{}` | Matches |
| Existing macro, separate `defn` batch | `{m :macro}` | `{}` | Matches |
| Plain then source macro; plain node re-parented | `{m :macro}` | `{m :macro}` | Matches |
| Source macro then source plain; plain node re-parented | `{}` | `{}` | Matches |
| Source macro then generated `defn`; definition re-parented | `{m :macro}` | `{m :macro}` | **Fails: expected removal** |
| Existing macro, separate `defn`; definition re-parented | `{m :macro}` | `{m :macro}` | **Fails: expected removal** |

**[P2 — must address] 1. Emission metadata is not a stable definition-origin marker.**

Locations: [post-harvest selection](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:244), [application rebuilding](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:313), [copy provenance](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:568).

There are two concrete false negatives.

**A. A generated definition loses its tag when descendants expand.**

For an existing macro `m`:

```clojure
(defn m [x] (wrap x))
```

Suppose `wrap` expands to its operand.

`defn` initially emits a `yin/def` tagged with its expansion event. Re-expanding `(wrap x)` changes the lambda body, causing the lambda and enclosing definition to be copied. §4.1 assigns copied ancestors `default-op`.

The final definition remains generated, but no longer satisfies step 5’s event-tag predicate. The old macro survives into the next batch.

If generated ancestors are intended to retain their original event tag, that exception and its propagation rule need to be explicit; the current copy rule does not establish it.

**B. Operator expansion creates a definition without emitting its parent.**

```clojure
((choose-def) 'm other-fn)
```

Suppose `choose-def` returns `{:type :variable :name yin/def}`, and `m` is an existing macro.

- Step 2 sees no definition: the source operator is an application.
- Operator expansion makes the parent `(yin/def 'm other-fn)`.
- The parent is a re-parented source application, carrying `default-op`.
- Step 5 misses it because the definition parent was not emitted directly from a macro body.

The evaluator receives the ordinary redefinition, while subsequent calls still expand the stale macro.

This second case remains broken even if generated ancestors preserve their event tags.

**Required change:** Track definition origin explicitly in the expander’s internal representation. Distinguish already-ranked source definition occurrences from definitions first recognized after rewriting, and preserve their ordering anchors through copies. Detect newly formed definition shapes after operator/operand rewriting. Keep that information independent of `m`, which describes emission provenance.

The criterion therefore fails the requested equivalence: **a definition introduced by expansion can have `m = default-op`**. No source-definition false positive is needed to demonstrate the defect.

**Acceptance:** Add both examples above and require removal of `m` next batch. All eight reduction cases must retain their expected results after descendant rewrites and fresh-ID copying.

**[P3 — suggestion/alignment] 2. Source inline-macro handling is now unreachable.**

Location: [admission restriction](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:214), [inline-macro claim](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:369).

Admission now rejects source macro lambdas outside definition-value position. Source definitions are then replaced, and generated macro lambdas are prohibited. Consequently, the retained direct inline-lambda case in `macro-of` has no supported input path.

Align §2.3 and §3.2 with the new restriction by removing that supported-case claim, or explicitly identify the branch as defensive and unreachable after admission. This is nonblocking.

**Verdict: REQUEST CHANGES.**

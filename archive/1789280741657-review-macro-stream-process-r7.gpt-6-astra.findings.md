I’ll check declaration order against the frontend lowering shapes and trace the new stand-in through preservation, fabrication, and final lowering. I’ll run the reductions in memory and write no files.

The reductions now pass for the sequence and nested `do`/`let` cases, and the stand-in survives identity and wrapper macros. A versioning gap remains: two source definitions of the same macro name produce identical stand-ins. If a macro discards the later definition, the surviving stand-in can still reinstall the discarded definition’s body.

Completed-GMT: 2026-09-13 06:28:12 GMT
Completed-Local: 2026-09-13 13:28:12 +07
Coding-Agent: codex
Session-ID: 01a098f8-8420-7083-9d18-f9776512bd61
Role: Routine Review | Model: gpt-6-astra

| Round-6 finding | Resolution | Assessment |
|---|---|---|
| P2-1: Tree traversal reverses `do`/`let` precedence | **Resolved** | §3.1’s separate declaration order handles immediately applied lambda operands before their bodies, including nested sequences. |
| P2-2: Stand-in identity disappears through macro invocation | **Partially resolved** | The typed stand-in survives decoding, wrapping, and re-encoding. However, its name identifies a macro binding, not a particular source definition. Multiple definitions of the same name remain ambiguous. |

No files were written. I ran the reductions in memory using actual `yang.clojure/compile-form` lowering for sequence templates, including `(do nil (let [ignored nil] (do A B)))`. Macro expansion and store ranking were modeled; the proposed expander was not implementation-tested.

**Reduction results**

`M` means `{m :macro}`; `—` means `{}`. Preservation/discard cases start without an older `m` binding.

| Case | After step 2 | After step 5 | Result |
|---|---:|---:|---|
| Plain then macro | M | M | Pass |
| Macro then source plain | — | — | Pass |
| Macro then generated `defn` | M | — | Pass |
| Existing macro + separate `defn` batch | M | — | Pass |
| Plain then macro, re-parented | M | M | Pass |
| Macro then source plain, re-parented | — | — | Pass |
| Macro then generated `defn`, re-parented | M | — | Pass |
| Existing macro + separate `defn`, re-parented | M | — | Pass |
| Existing macro + `defn` containing `wrap` | M | — | Pass |
| Existing macro + operator-formed `yin/def` | M | — | Pass |
| Identity macro preserves declaration | M | M | Pass |
| Wrapper preserves declaration | M | M | Pass |
| Macro discards declaration | M | — | Pass |
| Ordinary literal `'m` | M | — | Pass: literal declares nothing |
| Fabricated stand-in for undefined name | — | — | Pass: ignored |

The four sequence cases also produced the same expected results inside the nested `do`/`let`/`do` template.

**[P2 — must address] 1. Name-only stand-ins cannot preserve the surviving source definition’s version.**

Locations: [stand-in representation](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:237), [post-harvest lookup](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:258).

Consider a previously available `keep-first` macro that returns its first operand:

```clojure
(keep-first
  (defmacro m [] (yin/literal :A))
  (defmacro m [] (yin/literal :B)))
```

With the two definitions harvested in operand order:

1. Step 2 selects definition B as the current-batch binding of `m`.
2. Step 3 replaces **both** definitions with the identical shape:

   ```clojure
   {:type :yin/macro-defined :name m}
   ```

3. `keep-first` preserves A’s stand-in and discards B’s.
4. Step 5 encounters the surviving stand-in and retrieves the source macro by name.
5. The name-based lookup supplies B, although B’s declaration was discarded.

The reduction produced:

```clojure
{:expected {m :macro-A}
 :actual   {m :macro-B}}
```

This contradicts the final-tree persistence rule: the surviving declaration should select A. The same ambiguity appears if a transformer reorders two definitions of the same name.

If “source macro harvested this batch” refers to a separate collection retaining every definition, the lookup is still ambiguous: the marker contains nothing that selects an occurrence.

**Required change:** Give each stand-in an explicit, portable source-declaration identity and retain a batch-local catalogue mapping that identity to its name and lambda. Preserve the identity through the macro AST boundary and validate returned identities against that catalogue. A fabricated identity must not introduce an unharvested body.

Alternatively, explicitly reject multiple source macro definitions with the same name in a batch. That would narrow the existing last-wins contract.

**Acceptance:** Preserve the first and discard the second definition; preserve the second; swap their stand-ins; duplicate one stand-in; and fabricate an unknown declaration identity. The resulting binding must follow the surviving, ranked declaration—not the name’s initial harvest winner.

**Declaration-order probes**

- **Clojure `do`/`let`:** The new order matches the actual nested lambda lowering.
- **Native `compile-defn`:** Its `yin/def` application has a variable operator and a lambda value. The fallback order deterministically visits the declaration and its body; it does not reintroduce the sequence reversal.
- **`if`:** Test, consequent, alternate is a deterministic syntactic ranking. Both branches participate under the stated policy; this is not runtime branch selection.
- **Definition inside a non-lambda operator:** The fallback visits operator syntax before outer operands. Nested immediately applied lambdas within that operator still receive the special declaration order.
- **Python/PHP:** Their inspected suite sequencing uses immediately applied lambdas. PHP `for` lowering also contains a fixpoint-produced, non-lambda operator; its fallback traversal can visit loop-body syntax before initializer syntax. That is deterministic under the stated “everything else” rule, but is not a universal source-order guarantee. The document should retain its narrower `do`/`let` claim.

**Stand-in safety**

The typed shape resolves confusion with an ordinary `'m` literal. Accepting it in output does not itself permit a new macro lambda: `:generated-macro` still rejects those, and an undefined name is ignored.

However, “fabrication gains nothing” is too broad. A fabricated stand-in naming a harvested macro can move its effective declaration later and reassert it after a plain definition. That is a change to precedence, even though it introduces no new body. It may be permitted transformer behavior, but should be stated explicitly.

Changing a stand-in’s type intentionally removes its declaration meaning; changing its name can select another harvested binding. The validator can enforce valid shape, but name-only shape cannot enforce the source-version distinction required by the final-tree rule.

**Verdict: REQUEST CHANGES.**

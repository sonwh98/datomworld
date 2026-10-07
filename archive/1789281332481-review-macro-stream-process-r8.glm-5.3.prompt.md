Created-GMT: 2026-09-13 06:35:32 GMT
Created-Local: 2026-09-13 13:35:32 +0700 (+07)
Coding-Agent: glm
Session-ID: dcda6fce-9c4e-4e07-8311-57fd6a7513d9 (resumed)

# Task: Round-8 Confirmation — Macro Expansion as a Stream Process (rounds 5–8 changed §3.1 steps 3/5 after your r4 approval)
Role: VM Runtime & Invariant Review

Implementers:
- Model: glm-5.3 | Assigned: 2026-09-13 13:35:32 +0700 | Status: active | Rationale: You approved r4; gpt-6-astra drove four further rounds (r5–r8) on the definition-ranking rule you sanity-checked in r4 and approved r8. This round closes the gap: confirm the mechanism you have not yet seen.

**Read-only review. Print your complete structured findings to stdout; write no files.**
Repository `/Users/sto/workspace/datomworld`, branch `dao.stream-redesign-v2`.

## Target
- `docs/design/yin.vm.macro.md` (1122 lines, revision 8). Untracked; read it directly. Appendix C rows 28–38 record rounds 4–8; astra's round-5 through round-8 findings are in `collab/1789280260256-…-r5.gpt-6-astra.findings.md`, `collab/1789280492428-…-r6…`, `collab/1789280741657-…-r7…`, `collab/1789281040289-…-r8…` if you want the derivation.

## What changed since r4 (all in §3.1 steps 2, 3, 5 and the §3.3 validator row)
The r4 step-5 rule you sanity-checked ("post-harvest `dissoc`es plain `yin/def`s in the final tree, effective next batch") had a hole astra found: `(def m f)` then `(defmacro m …)` in one batch — step 2 correctly makes the macro win, but the surviving plain `yin/def m` in the tree then removed it. Four rounds of fixes led here:

1. **Catalogue.** Step 2 numbers every source macro definition it saw, in harvest order, into a batch-local `:declared {k {:name sym :lambda ast}}`.
2. **Stand-in.** Step 3 replaces each macro definition with an expander-internal node `{:type :yin/macro-defined :name sym :decl k}` — recognised by shape and ordinal, never by eid or `m` (both proven unreliable: re-parenting gives fresh eids and `default-op`; a macro decoding and re-encoding a plain literal stand-in loses its eid). `valid-ast?` accepts it in operands and output so macros can return or wrap it. Step 7 lowers it to `{:type :literal :value sym}` for `program-out`; no evaluator sees it.
3. **Declaration order.** Step 5 ranks the *final tree* in a **declaration order** defined separately from §3.2's operator-first expansion traversal: for an `:application` whose operator is a `:lambda`, operands left to right then the body; otherwise the fixed order. This recovers source order for yang's `((fn [_] B) A)` / `((fn [x] body) v)` lowerings at any nesting; the claim is explicitly narrowed (PHP `for`'s fixpoint operator is named as not source order).
4. **Ranking.** Start from the store *before* step 2; walk in declaration order; `(yin/def <literal sym> v)` → `assoc` if `v` is a macro lambda else `dissoc`; a stand-in → look up `:decl k`, require the entry's `:name` to match, `assoc` *that entry's* lambda; anything else ignored. Last wins. Result governs the next batch; step 2's source-only store governs the current one.
5. **Stated consequences:** plain-then-macro keeps the macro; macro-then-`defn` removes it; `(keep-first (defmacro m … :A) (defmacro m … :B))` keeps **A** next batch though B won step 2; a discarded definition is absent next batch; a fabricated stand-in with a valid `:decl` may *move* a declaration's precedence but never introduce a body; stand-ins carry no cross-batch identity.

## What to do
1. **Runtime/invariant check of the mechanism:** is `:yin/macro-defined` genuinely confined to the expander (admission → step 7), i.e. can it reach `program-out`, the log copy, or a throwaway body VM in a way that matters? Does accepting it in macro *output* interact badly with `:generated-macro` or the plain-data validator?
2. **Determinism:** with the catalogue keyed by harvest ordinal and ranking by declaration order over the final tree, is the next-batch store a pure function of (batch, incoming store) on every host? Any map-iteration-order or eid-dependent step left?
3. **Silent-wrong-code sweep** of the kind you did in r2/r4: with the stated consequences, is every divergence from Clojure either stated or loud? Probe at least: a `defmacro` inside a `let` body; a `defmacro` inside an `if` branch that a macro then discards; a macro that returns its stand-in operand twice.
4. **Declaration order vs §3.2 traversal:** two walks over the same tree with different orders — any risk an implementer conflates them, and is the doc's separation clear enough?
5. Anything else r5–r8 introduced that you would block on.

## Output Format
Begin your output exactly with:
```text
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: glm
Session-ID: dcda6fce-9c4e-4e07-8311-57fd6a7513d9
Role: VM Runtime Review | Model: glm-5.3
```
Then findings by severity and an explicit verdict: APPROVE or REQUEST CHANGES.

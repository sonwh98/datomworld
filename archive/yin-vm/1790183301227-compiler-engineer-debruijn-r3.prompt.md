Created-GMT: 2026-09-23 17:08:21 GMT
Created-Local: 2026-09-24 00:08:21 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: 1ff9efa8-f088-467a-8507-c94697603dc5

# Task: debruijn-type-preservation-r3 -- Reconcile Reviewer Findings & Set Contract Version 1

Role: Yang Compiler and Universal AST Engineer

Implementers:
- Model: claude-sonnet-5 | Assigned: 2026-09-24 00:08:21 +07 | Status: active | Rationale: Reconcile adversarial review findings and owner directive on de Bruijn projection.

Work in /Users/sto/workspace/worktree-debruijn-type-preservation (your launch directory; branch debruijn-type-preservation). Do NOT stage, commit, merge, or push.

---

## Directives to Implement

### 1. Owner Ruling: Keep Contract Version at 1
The repository owner has instructed:
> "since this is not released yet, keep it at contract-version 1"

- In `src/cljc/yin/vm/debruijn.cljc`:
  - Set `(def contract-version 1)`.
  - In `descriptor`: `[:yin.debruijn/dimension :dim/contract-version contract-version]`.
  - Update docstrings referencing version 2 to version 1.
- In `docs/design/yin.vm.debruijn-projection.md`:
  - Describe Contract Version 1 as the type-preserving specification (`:integral-double-folding false`, `:int64` and `:double` disjoint).
- In `public/chp/blog/yin-vm-vs-unison.blog`:
  - Update reference from Contract Version 2 to Contract Version 1.

### 2. Reviewer Finding P1: Fix `js-number-class` on CLJS
Adversarial Review Finding P1:
> `src/cljc/yin/vm/debruijn.cljc`: `js-number-class` classifies integral values at or above `2^63` as `:double` before applying the safe-integer check. This contradicts the declared `:unsafe-integer :diagnostic` rule, allowing rounded host values onto the universal projection.
> Fix: Remove `(<= 9223372036854775808 (js/Math.abs v)) :double`. Any number without fractional part outside `[-9007199254740991, 9007199254740991]` must return `nil`.
> In `test/yin/vm/debruijn_test.cljc`: Gate `9.3e18 :double` off CLJS, and assert `(nil? (d/canonical-class 9.3e18))` and `(nil? (d/canonical-class 9223372036854775808))` on CLJS.

### 3. Reviewer Finding P3: Line Length in `yin-vm-vs-unison.blog`
Line 40 in `public/chp/blog/yin-vm-vs-unison.blog` exceeded 80 columns.
Split the Hiccup text elements so all lines are strictly <= 80 columns.

### 4. Re-pin Pinned Hashes for Contract Version 1
Because `contract-version` is now `1` in `descriptor`, recompute and re-pin:
- Descriptor hash: `(jing/sha256 (encode-value descriptor))`
- Essay fingerprint hash: `(:fingerprint (project worked-example))`
Update these pins in `test/yin/vm/debruijn_test.cljc`.

---

## Verification

1. Run focused tests:
   ```sh
   clojure -M:test -n yin.vm.debruijn-test -n yin.vm.pipeline-test
   ```
2. Lint:
   ```sh
   clj -M:kondo --lint src/cljc/yin/vm/debruijn.cljc test/yin/vm/debruijn_test.cljc
   cljstyle check src/cljc/yin/vm/debruijn.cljc test/yin/vm/debruijn_test.cljc
   ```
3. Verify line lengths <= 80 cols and pure ASCII across all changed files.

---

## Response Format

Begin your response exactly with:
```text
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 1ff9efa8-f088-467a-8507-c94697603dc5
```

Report:
1. Changed files and diffstat.
2. New pinned descriptor and essay hashes under Contract Version 1.
3. Test assertion counts and outcomes.

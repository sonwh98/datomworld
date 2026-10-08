Completed-GMT: 2026-09-30 20:35:30 GMT
Completed-Local: 2026-10-01 03:35:30 +07
Coding-Agent: claude
Session-ID: c0d666a1-b094-4942-abab-5698a77445be

# Report: Architect rulings recorded in docs/design/yang.antlr.md

I edited only `docs/design/yang.antlr.md` (+429/-20). Nothing is staged or committed. I built the tables with a throwaway
renderer in `collab/.scratch-yangdoc/`, which I deleted afterwards. `git status` shows only the modified doc, the
pre-existing untracked collab sources, and this report.

## Sections changed

- **Status (top of file):** added an "Updated 2026-10-01" paragraph. It cites all five ruling files by their full
  collab filename, which is their first use, and gives the short names used later ("the cell ruling", "mob D1 to D10",
  "the mutable-objects ruling", "the Python mappability ruling"). It lists the sections amended and the four landed
  commits: 8f9f90b0, 9a69e58f, 5e790683, fe8bce4a.
- **Section 8.1:**
  - The "threaded immutable heap" baseline is replaced by the D3 wording: a task-owned heap of cells updated through
    persistent VM-state transitions.
  - The cell form (sealed `:cell-ref` over the task `:heap`, `cell/new`/`get`/`set!`) is stated, with box semantics.
  - State threading stays an allowed per-frontend choice.
  - Loops lower to recursive lambdas, and continuations are used only for the escapes.
  - Abrupt completion lowers to continuations, not completion records (cell ruling, owner decision 5).
  - A definition says "store cell"/"binding cell" means a `cell` module ref, not a named-store location. This resolves
    F2.
  - D5 is added: every function-local binding and every parameter is a cell, allocated at entry with an unbound
    sentinel. Binding collection is required; liveness and un-boxing are an optional attached interpreter.
  - D1 copy-on-lift is added, with slice 1 refusing cell-bearing lifts.
  - A pointer to section 8.11 is added.
  - The `:vm/store-put` paragraph now names `cell/set!`.
  - The old "tagged representation so `module/effect?` isn't triggered" paragraph is replaced with D4. Effects are a
    host type minted only by `module/make-effect`; the engine checks an effect result against the callee's declared
    effect set; the constructor is not a guest primitive and its wrapper is not a stream representation.
- **Section 8.5 (Python):**
  - Binding bullet: every local and parameter is a cell (D5), and assignment is `cell/set!`.
  - New bullet: a module namespace is a heap dict (owner decision 1), with `NameError` as the natural miss, `yin/def`
    reserved for the prelude and builtins, and `exec` targeting a dict.
  - The calling-rules bullet notes that a function-object wrapper in a cell is needed (mappability Q4).
  - The generator bullet now says the saved resume continuation lives in a cell, and `throw` resumes it with a tagged
    raise-here value.
  - Runtime-profile paragraph: floats are tagged (owner decision 3), and `print(4/2)` goes in the Node parity set.
- **New section 8.5.1, Python 3 mappability:**
  - The headline: nothing in the language reference is inexpressible.
  - The residue list, and "multi-shot is not a Python requirement".
  - The corrected bucket table as an ASCII box table.
  - The four owner decisions: heap-dict namespaces; tracebacks derived at the boundary by default; float tagging;
    safepoints as an attached interpreter.
  - The ruling's interactions: non-migratable reified continuations, no `py/kont?`-style representation tests, one
    Python process = one task, linker delivers code not namespaces, reclamation pressure, per-unit prelude class
    identity.
- **Section 8.6 (PHP) and section 8.9 (Go):** one-line cross-references to section 8.11 only.
- **New section 8.11, mutable objects and collections over cells:**
  - One cell per object; identity = the ref; bare `:cell-ref` with the tag inside the content.
  - Immutables get no cell; a slot gets a cell only when it is aliasable; classes are cells.
  - get/pure-update/set!, with no atomic form and `cell/swap!` rejected.
  - Representation table.
  - Identity rules: `=` on refs is same-cell as an invariant that survives D7; per-profile `is` on immutables (owner
    decision 1); refs as map keys; `id()` unstable across a lift (owner decision 2); function identity.
  - PHP/Go/Java mapping table.
  - Cycles and lift.
  - Reclamation ahead of lift/lower after the spike (owner decision 4).
  - Placement across prelude, `cell` module and runtime profile, plus cross-language opaque handles.
  - The two prelude constraints: never iterate a host map; normalize dict keys in the profile.
  - The dispatch-cost risk.
- **Section 9.3:** the pure data primitives live in a `:pure` host module named by the runtime profile, not
  `vm/primitives` (owner decision 3). The module is `yin.vm.data`, module name `data`, installed via
  `register-data-module`, landed in fe8bce4a.
- **Section 9.4:** "store cells" now points to the section 8.1 definition. Objects, including classes, and collections
  are one cell each; section 8.11 is referenced.
- **Section 9.5:** one sentence on D4 enforcement next to the capability paragraph.

## Open notes left (sources silent or in tension)

1. **Section 8.5, module-level variables in other languages.** The mappability ruling moves Python module namespaces
   to heap dicts. The cell ruling's Q5 said module-level variables and `global x` stay `yin/def` into the module store.
   I recorded the replacement for Python only and marked "Open:" for other languages.
2. **Section 8.5.1, bucket legend.** The ruling gives bucket 1 = cost and bucket 3 = needs VM infrastructure. It never
   defines 2 and 4 in words, so I marked "Open:" and described only their observed usage.

## Things I chose NOT to change

- **Section 8.1 "Suspended state consists of portable code references and data":** kept. The sources note that
  reified continuations in cells do not encode yet. I recorded that as a limitation in section 8.5.1 instead of
  weakening the baseline.
- **Section 8.3 paradigm table ("store cells") and the section 9.1 layer-3 row:** left as written. The term is now
  defined in section 8.1 and section 9.4, and editing the tables was not required.
- **Mob D6/D7 (continuation and closure hardening):** no doc text added. The mob left D6/D7 timing as an owner pick
  between two seats, and I was not given which option was chosen. D7 appears only where the sources make it a
  constraint: `=` on refs, and the prelude not testing the continuation representation.
- **Mob D8/D9 (worktree removal, untracking collab/):** repository housekeeping, not design; omitted.
- **Mob D10 (spike order and stream topology):** not added to section 12. The roadmap change was not in the acceptance
  criteria. Only the pipeline principle (required binding collection, optional attached interpreters) went into
  section 8.1.
- **Section 12 Phase 1 roadmap:** unchanged, although the rulings imply spike-scope items. These are the data-module
  prerequisite (already landed), the Node `print(4/2)` parity test, and reclamation ordering.
- **Cell-ruling items not recorded:**
  - F1, F3, F5, F6: implementation and other-document findings.
  - The heap-lift wire details (`:yin.k/heap`, memoized reachability) beyond "reachability with id reserved before
    traversal".
  - Seal caching (astra's D5-adjacent note): an implementation detail.
- **The spike-deviation table (mappability Q4):** only two items are carried: the function-object wrapper and float
  tagging. The rest are spike-scope notes.
- **The cell ruling's naming alternative `box/*`:** omitted, because owner decision 2 kept `cell/*` with `:heap`.

## Foreground checks (commands and output)

All run from the worktree root after the final edit.

1. `python3 collab/.scratch-yangdoc/check.py`. The script was deleted afterwards. It checks line width in
   characters, non-ASCII, em dashes and tabs, markdown pipe-separator rows among the `git diff -U0` added lines,
   unclosed table rows, and that every box row matches its border width:

   ```text
   1) lines over 170 columns (character count):
       none | max width = 152
   2) non-ASCII characters in file:
       none
      em dashes: 0  tabs: 0
   3) added lines: 429
      markdown pipe-table separator rows among added lines: none
      table rows not closed by |: none
   4) ASCII box table border consistency (every | row width equals its + border):
       none
   ```

2. `grep -c -P '[^\x00-\x7F]' docs/design/yang.antlr.md` printed `0`.

Before editing, the file also had no non-ASCII and no em dashes, so none were introduced.

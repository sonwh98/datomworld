---
name: verify-clj-refactor-without-compiler
description: Verification discipline for large Clojure/CLJC refactors when no shell exists at all — exact paren-count by anchored regex, dangling-reference sweeps, and the defect classes that code *movement* and a dispatch's *literal snippet* introduce
source: auto-skill
extracted_at: '2026-11-10T00:00:00.000Z'
---

# Verifying a CLJC refactor with no compiler

`monitor-tool-as-shell` covers "no `run_shell_command` but `monitor` exists".
`git-state-without-shell` covers reading `.git`. **This covers the third case: no shell
substitute at all**, so `clj -M -e "(require …)"` is impossible and a multi-file refactor must be
self-verified by reading. Confirm with `tool_search` for `shell`/`exec`/`monitor`; also note that
`computer_use__list_apps` may return *"Computer Use install declined by user"* — the user refused
it, so do not re-prompt it as a backdoor.

**Do not delegate to a subagent assuming it has a shell.** Tried it: `general-purpose` came back
with the same empty tool set. State the limitation up front in the reply and hand back a runnable
verification command; never present hand-review as compiled-green.

## 1. Exact paren count by anchored regex + negative control

Counting `)` by eye in a 2000-line file is unreliable, and `read_file` output makes it worse.
Instead, bracket the count from both sides — `grep_search` reports "No matches found" cleanly:

```
\(done nil\)\){8}$   → 1 match     (so ≤ 8)
\(done nil\)\){9}$   → no match    (so < 9)   ⇒ exactly 8
```

**The negative control is the whole trick.** `\){8}$` also matches a line with 9 or 12 trailing
parens, so a single positive match proves nothing. Always run N and N+1. Then count the required
closes from the nesting (`defn` → `loop` → `let` → `if-not` → `let` → `cond` → `let` → `if`) and
compare. Verify every deep closer you wrote, not just the last one.

**Fixing a miscount: never patch one paren at a time.** A missing `)` inside a `doseq … :let […]))]]`
cost three failed `edit` rounds — the tool's echo *and* a fresh `read_file` both rendered the line
ambiguously, and one edit silently swallowed an adjacent `(is …)` line. Two rules that ended it:

- After **every** delimiter edit, re-run the anchored regex (N and N+1). Do not trust the echo, and
  do not trust your eyes on the re-read; `read_file` output is not a reliable paren oracle.
- Prefer **restructuring to a shallower nesting** over adding a paren: `(doseq [x xs :let [y …]])`
  → `(doseq [x xs] (let [y …] …))`. The `))]]` tail is the single easiest closer in Clojure to
  miscount, and the rewrite removes it. Then verify once.

Also beware a *stale echo*: an `edit` can report success and print the **pre-edit** context lines.
Regex the file, don't read the response.

## 2. Defect classes that *moving* code introduces

Extraction/ relocation bugs are systematic. Grep and re-read specifically for these — all five
were live in one session:

| Defect | Why moving code causes it | Check |
|---|---|---|
| **Alias shadowed by a local** | Source used a bare local `query`; the destination ns has `[yin.repl.query :as query]`. `(let [[query & inputs] more] … (query/bounded …))` now resolves `query` to the vector. | In every moved form, list the locals/destructured names and diff them against the destination's `:as` aliases. Rename the local (`[q & inputs]`). |
| **Alias shadowed by a parameter** | Moved `activate-dht [registry]` into a ns that aliases `[yin.shell.registry :as registry]`. Compiles, but the alias is dead in that scope and kondo warns. | Same diff, parameters included. Rename the *alias* (`:as shell.registry`) — keeps idiomatic param names. |
| **Forward reference** | Inserted `round-local` (calls `round`) *above* `round`. Clojure needs the var to exist; unlike a fn body this fails at compile time. | After inserting, confirm every callee appears earlier in the file, or add `(declare …)`. Easiest fix: move the new form *below* its callee. |
| **Stray / dropped paren inside a `cond`** | Hand-retyping a long `cond` branch; one extra `)` after the first clause silently swallows the rest. | Re-read the whole moved form after writing. Don't trust the `edit` tool's echoed context lines alone. |
| **Stale binding after restructuring** | Rewrote `recheck-on-tick` to serve first, but the `if` still read the *outer* destructured `engine` instead of the post-serve `(:engine session)`. Compiles fine, wrong value. | For any let-binding you reassign mid-form (`session (:session served)`), grep the rest of the body for the old name. |

## 3. The three sweeps that replace a compiler

Run these after every removal/rename, across **`src/` and `test/`**:

1. **Dangling references.** Grep every var you deleted or made private, qualified and
   unqualified (`query/activate-dht|activate-dht|::dht-load-index|…`). Zero hits = safe removal;
   hits = a call site you must fix or a test you must hand to a later step.
2. **Dead imports.** After removing a block, grep each surviving alias (`jing/`, `module/`,
   `index/`) in that file. Extraction routinely orphans one — `dao.jing` died in `query.cljc`,
   `yin.vm.module` died in `repl/dht.cljc`. Unused requires are warnings on JVM but can be hard
   errors under clj-kondo gates and they mislead the next reader.
3. **Call sites of widened arities.** Adding a param to `make-session`/`make-vm` means grepping
   the symbol repo-wide and checking *every* arity, including public ones tests call directly
   (`repl/make-vm` is used at `test/yin/vm/linker_require_test.cljc`). Keep a thin back-compat
   arity rather than breaking callers you cannot compile.

Also re-check the **dependency direction** of any new ns: a composition ns may require the
engine, never the reverse. Walk the new require lists for a cycle before trusting them.

## 4. Brief line numbers are already stale

A dispatch brief cites spec line numbers (`repl.cljc:922,930–965`), but an earlier step in the
same sequence has usually added code — here everything had drifted **~+78 lines**. Never anchor an
`edit` on a line number. Grep for a distinctive token from the cited region
(`:dht? (= :dht (:type index-store-spec))`), read ±40 lines around the real hit, and build
`old_string` from content with 3+ lines of context. Report the drift in the handback so the next
seat does not chase it.

## 5. Scope forks: decide, document, don't stall

`ask_user_question` fails in non-interactive mode ("Cannot ask user questions … without ACP
support"). When a spec offers two readings — e.g. §2's "split the interpreter **or** move
ownership of it entirely to composition" — pick using the brief's own verb strength, and say so
explicitly:

- "**Check** if X can be isolated" ⇒ investigate + report a finding with a concrete split plan.
  It is *not* a mandate to perform the split.
- A Gate's **named file list** ("these four files must have zero direct imports") is the hard
  requirement; a broader prose clause ("the dependency *closure* must also stop loading …") is a
  separate, reportable gap. State which one you closed and which you left red.
- Prefer the option whose blast radius you can actually verify. Splitting a ns that breaks ~15
  test call sites you cannot compile is worse than a clean nodeless entry point
  (`round-local`) plus a written finding.

## 6. Audit-regex false positives from spec filenames

Gate A's `rg 'yin\.repl\.dht'` matches the governing spec's *own filename*,
`docs/design/yin.repl.dht-composition.md`, which repo convention requires citing in docstrings.
Report both numbers: raw hits, and hits under a tightened pattern
(`yin\.repl\.dht[^-]`) that excludes the citation. Never silently delete a required spec
reference just to make a gate green — and propose the regex fix in the handback.

## 7. Handback shape

Because nothing was executed, the summary must separate proven from assumed:

- **Verification limit first**, before any result — name the exact commands that could not run.
- What was checked instead, and how (the regex counts, the sweeps).
- Defects found and fixed *during* review — this is evidence the review worked.
- Gate results as a table, per named file.
- **Known breakage handed to the next step**, numbered, with file:line, and whether it fails to
  *load* or only at *runtime*. A loud refusal beats a silent one: prefer leaving a path that
  throws an existing clear message over one that quietly does nothing.
- A copy-pasteable verification command for the seat that does have a shell.

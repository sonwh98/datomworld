Created-GMT: 2026-11-13 14:12:06 GMT
Coding-Agent: codex
Role: Engineer
Status: incomplete; no PHP oracle adjudication or parity claim

Nine fixtures were added with outputs observed from the current engine. The initial run deliberately used empty outputs and recorded nine mismatches, providing the observed values now in the corpus. Every new row remains `:oracle :pending`; no disposition has been adopted. The existing `:unregister-executing` covers a callback unregistering itself.

The general adjacent suppression change remains unimplemented. The `:adjacent-marked` case exercises ordinary adjacent statements, but does not establish suppression of adjacent emitted tick instructions across nested constructs; a discriminating case and implementation are still needed. The full requested corpus and gates therefore remain incomplete. The engine test was rerun with observed outputs and the include gate launched; see session results for completion. `cljstyle check` passed on the fixture file; `clj-kondo` was unavailable on PATH. No PHP oracle was run. Staging failed because `.git/index.lock` cannot be created on the read-only `.git` mount; no commit or push was possible. Existing unrelated untracked logs and prompt were left untouched.

The following is the shiva corpus list. Every row is **oracle-adjudicate**: compare actual stdout, exit and diagnostics, not merely pending-row runner status.

| Fixture id | Question answered |
| --- | --- |
| `:do-while-selection` | Which sites fire around and within do/while? |
| `:for-selection` | Which sites fire around and within for iterations? |
| `:foreach-selection` | Which sites fire around and within foreach iterations? |
| `:switch-selection` | Which sites fire around and within a selected switch case and break? |
| `:adjacent-marked` | How are consecutive marked statements counted? |
| `:nested-zero-n2` | Does a zero-ticks region preserve the outer N=2 count and how is its completion selected? |
| `:dispatch-add` | Does a callback registered during dispatch run in that dispatch? |
| `:dispatch-remove` | Does removing a later callback during dispatch prevent its current invocation? |
| `:generator-foreach` | How do yield completion and foreach sites interact across resume? |

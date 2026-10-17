Completed-GMT: 2026-10-17 07:10 GMT
Completed-Local: 2026-10-17 14:10 +07 (+0700)

The new section is mostly accurate, but its account of a missing parser build is wrong and contradicts itself. I only read files and the diff; I did not run the REPL.

**Verified against the code**
- **Insertion point:** `git diff --stat` shows +37 lines in `yin.repl.md` and nothing else. The section sits after "Starting and connecting" and before "Saved state".
- **Style:** all added lines are ASCII and no longer than 80 columns.
- **Frontend catalog:** `frontends.cljc` installs four frontends: Clojure (`:yang.clojure/legacy`), legacy Python (`:yang.python/legacy`), PHP, and the ANTLR Python one. The ANTLR frontend sits behind `#?@(:cljd [] :clj ...)`, so it is JVM only. Its manifest has id `:yang.python/antlr`, revision `"f3"`, grammar package `"python3"` and entry `file_input`. It also sets `:display :output` and `:lower-session`.
- **Selection forms:** `select-frontend` in `repl.cljc` accepts `[id revision]`, a qualified id, or a language keyword. A language keyword resolves only when exactly one id is installed for it. `:python` has one id (the legacy one) and `:python.antlr` has one (the ANTLR one), so both doc examples hold.
- **`lang` command:** the success message is `"Switched to " + (str lang)` for ids without a label, which gives `Switched to :yang.python/antlr`. An unselectable frontend gives `Error: unavailable-parser: Yin REPL language ... is not in the frontend catalog`. On Node and Dart the ANTLR id is not installed, so that error is the right behavior, and `(lang :python)` still works.
- **Session clearing:** the "cleared with the VM" claim matches the `lower-parsed` docstring. I did not check the `(reset)` and `(vm ...)` details.

**Findings**
1. **Wrong account of a missing build.** The doc says "the first submission throws class-not-found until `clj -M:antlr-gen` has run."
   - `yang.python.antlr.parser` imports the generated `Python3Lexer` and `Python3Parser` classes at namespace load.
   - `yin.repl.frontends` requires that namespace unconditionally on the JVM, so with no build the class-not-found surfaces when the namespace loads.
   - That happens before any submission and before the frontend is installed, so the REPL probably cannot start at all.
   - The doc's claim that the frontend "is installed" in this case is wrong, and so is "the first submission throws."
2. **Self-contradiction.** The last paragraph says that with no build "the frontend answers `unavailable-parser` for a missing build rather than a missing catalog entry."
   - That contradicts the earlier sentence about class-not-found.
   - It is also false. `unavailable-parser` means no usable binding is installed for the selected frontend, as in the `lang` case. It does not mean the generated classes are absent.
   - Rewrite as one statement: on the JVM the generated parser must exist before the REPL starts, so run `clj -M:antlr-gen` once per clone. Without it, loading `yin.repl.frontends` fails with class-not-found.
3. **Minor, unverified.** "lowers it through the yang.python.antlr pipeline onto the linked VM" has no matching claim in the files I was told to check. Soften it or drop "linked VM."

Verdict: REQUEST CHANGES

The doc edit is confirmed. All three fixes are in the diff, and the wording matches the code.

- **Missing build:** The text says `yin.repl.frontends` loads the parser namespace on the JVM. It tells a fresh clone to run `clj -M:antlr-gen` once, or REPL startup fails with class-not-found. This is accurate. `frontends.cljc` requires `yang.python.antlr.parser` under the `:clj` branch.
- **`unavailable-parser`:** It now refers only to selecting the ANTLR frontend on Node and Dart. This matches the `standard` docstring in `frontends.cljc`. The self-contradictory missing-build sentence is gone.
- **"onto the linked VM":** It is gone from the text.

One small point, not blocking. The doc says the ANTLR frontend is "installed only where its parser runs", and also that selecting it elsewhere answers `unavailable-parser`. The `standard` docstring says the same thing, so the doc is consistent with the code. I did not run the REPL to see what a non-JVM host actually answers.

Verdict: READY (sign-off granted)

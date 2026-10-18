The Node lane is still running in the background (task `by0yz9tg4`). I'll wait for its completion notification instead of polling. When it arrives I'll rerun the lane with the `cst.cljs` fix and then continue with M1 and M2.

The Node lane is running again in the background (task `blp7q0hlv`) with the `cst.cljs` fix. I'll continue when it completes.

The Node lane is running again (task `b5i04695x`) with the listener and node-class fix. I'll continue when it completes.

The previous Node run hit the 10-minute cap. It had no errors in the part that ran, but it didn't finish. I restarted the lane (task `b2n9xd9sw`) with a 30-minute limit and will continue when it completes.

The Dart lane has only finished generation so far. I'll wait for its completion notification.

JVM is green: 4104 tests, 285459 assertions, 0 failures. The Node lane is re-running with the M2 edits (task `b60lasdkd`). Dart comes next, one lane at a time.

The Node lane had 2 failures, both in the old "ANTLR id only where its parser runs" catalog test, which asserts the JVM-only behavior this slice removes. I rewrote it to expect the frontend on every host. The Dart lane (task `b4jmewtbz`) is running and I'll check it when it completes.

The Dart compile failed because Dart's `ruleNames` is an instance member, not a static one. I fixed `parser.cljc` and the two ANTLR namespaces now compile. The Dart lane is running again (task `bsay15r65`).

The Dart lane is running (task `b100pqwkn`). One finding to report: the peer's AOT build ran out of memory (SIGKILL) once the Python prelude became reachable. I fixed it by making the prelude's quoted data an EDN string read at load on Dart only.

The JVM lane is green: 4104 tests, 285465 assertions, 0 failures. The Node lane is running (task `b0c66olak`). Dart follows, one lane at a time.

I stopped the premature Dart run. The Node build failed because the `defquoted` macro isn't visible to ClojureScript, so I added a self `:require-macros` and made the macro public. Node is re-running (task `bx9mmizv0`), and Dart follows after it.

The Dart lane is running (task `bowzvegz6`). I'll finish the report when it completes.

Completed-GMT / Completed-Local
2026-10-17 17:39:10 GMT / 2026-10-18 00:39:10 +0700

M0, M1 and M2 are built and green on JVM, Node and Dart. Two things differ from your brief. I did not run the Node or Dart prompt by hand. And the worktree was not clean: it already held a substantial uncommitted M0/M1/M2 draft (manifest `:targets`, generator, exporters, conformance helper, `parser.cljc`). I verified and continued that draft instead of starting over. Nothing is staged or committed, and M3 is untouched.

**M0 (build)**
- Java digests are byte-identical to HEAD, and `bb gen:antlr` reproduces from a clean `build/antlr/python3` and `lib/antlr`.
- JS and Dart artifacts are generated, ASCII-checked and digest-pinned. `npm ci` and `pub get` resolve antlr4 4.13.2.
- Three additions beyond the plan:
  - A patch removes the duplicate `getIndentationCount` that the upstream JS lexer helper declares twice. Closure rejects the duplicate.
  - The generator gained `:output-patches`, which rewrites the import line of the generated JS lexer and parser, plus the matching helper patches. Shadow's node targets bind a default import of `antlr4` to a module without the `atn` and `dfa` namespaces.
  - `cst.cljs` reads those namespaces through an `antlr4-default` fallback.
- The `:js-options :resolve` fallback was not needed.

**M1 (IR and exporters)**
- `packet.cljc` owns the v1 shape doc and `conform`. `cst.cljs` and `cst.cljd` are siblings of `cst.clj`, with a shared `export.cljc`. `yang.antlr.conformance` and `cst_export_test.cljc` run on all three hosts.
- CST-parity evidence:
  - The conformance goldens pass on the JVM, Node and Dart, including the é𝄞 UTF-8 span fixture and the error packets.
  - I changed `[4 12]` to `[4 99]` in the fixture. Dart failed that one assertion with actual `[4 12]`, so the Dart tests really run and Dart spans are UTF-8 bytes. I reverted the change.
  - `conform` passes on every precompiled packet (JVM-verified via the parity test).
- Dart fixes: the lexer simulator constructor takes its recognizer as a named `.recog` argument, and the lexer and parser needed type hints. The generated parser's `ruleNames` is an instance member there, so `rule-names` builds it from a throwaway parser.

**M2 (Python on three hosts)**
- `parser.cljc` and the probe split are done. In `frontends.cljc` the gate is gone, the revision is `f4`, and the grammar map has `:yang.grammar/targets #{:java :javascript :dart}`.
- `python_antlr_test` runs both langs on every host, and the parity test is unwrapped. I added an in-process test that evaluates the filtered comprehension through the installed parser, which passes on all three hosts.
- I rewrote `the-antlr-id-is-installed-only-where-its-parser-runs-test` as `…-on-every-host-test`, since the old one asserted the JVM-only behavior.
- Doc amendments are in `yang.antlr.md` (4.1 table, F2 and the packet-parity acceptance line, a 5.5 note pointing at `packet.cljc`), `yang.typescript.md` and `yang.php.md`. `yin-repl-design.md` has no ANTLR Python section, so nothing changed there.

**Lane counts (quick tier, foreground)**

| Host | Tests | Assertions | Failures | Errors |
|---|---|---|---|---|
| JVM | 4104 | 285465 | 0 | 0 |
| Node | 4074 | 149821 | 0 | 0 |
| Dart | 4022 tests (shards, 227 files) | n/a | 0 (all passed) | 0 |

**Behavioral changes**
- The Dart peer's AOT build was SIGKILLed once the Python prelude became reachable. The prelude's quoted data compiled to roughly 65,000 lines of straight-line Dart, 4.2 MB. A new `defquoted` macro in `prelude.cljc` now emits that data as one EDN string read at load, only on ClojureDart. The generated Dart dropped to 158 KB and the AOT build works. JVM and Node still get a plain `def` of the quoted form.
- Shadow config: `shadow-cljs.edn` is unchanged. A leftover edit from experiments there was reverted.

**Style checks**
- cljstyle is clean on all my files. Its only complaint is `test/yin/repl/store_test.cljc` (not mine), plus other unrelated files outside my diff.
- kondo is clean per host, except the pre-existing `host.cljc` require error. Linting `src/clj` and `src/cljd` together shows false "arity" and "redefined var" warnings, because both host files define the same namespace.

**Incomplete**
- I did not run `node target/yin-repl.js` or the Dart peer by hand with `(lang :yang.python/antlr)`. The comprehension is covered in-process on all three hosts. The JVM prompt-hang regression stays `^:slow` and JVM-only.
- I did not run the `DATOM_SLOW_TESTS=1` focused ANTLR namespaces from the plan's verification step.
- Worker isolation on JS and Dart uses fresh DFA and prediction-context caches per worker, as on the JVM. I have not shown the isolation in a test.
- The four extra helper licences stay UNRESOLVED and `:redistribution :blocked-until` is unchanged.

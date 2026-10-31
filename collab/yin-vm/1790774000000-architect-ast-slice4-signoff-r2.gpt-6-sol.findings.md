The r2 scanner closes the earlier `#_%` gap, and the new placeholder scheme follows each host’s duplicate behavior. I found two restoration paths worth checking before sign-off: namespace-qualified `%` symbols and placeholders inside reader metadata.
Completed-GMT: 2026-09-30 16:06:10 GMT  
Completed-Local: 2026-09-30 23:06:10 +07

**SIGN-OFF WITHHELD.** R2 closes the `#_%` scanner gap, reuses one placeholder per distinct token, gates scanning on `%`, and adds the historical note. Its restore also leaves branches without placeholders identical. Two valid reader forms still change meaning on Dart.

| Severity | file:line | issue | fix |
|---|---|---|---|
| High | [yin/repl.cljc:919](/Users/sto/workspace/datomworld/src/cljc/yin/repl.cljc:919) | Restoring a token uses only `(name token)`. A valid qualified symbol such as `%/foo` therefore loses its `%` namespace. | Restore the token’s namespace and name; preserve syntax-quote qualification only for an originally unqualified token. Test both forms. |
| High | [yin/repl.cljc:914](/Users/sto/workspace/datomworld/src/cljc/yin/repl.cljc:914) | Restoration never visits metadata. Valid forms `^% x` and `^{:tag %} x` retain the placeholder in `x`’s `:tag`. I confirmed both read with `{:tag %}` on the JVM. | Restore placeholders inside metadata as well as values, while preserving the original collection type and reader metadata. Add Dart parity tests. |

The collision-resistant prefix, error-text substitution, and tested syntax-quote and nested `#()` paths show no further issue in this review. The green lane results do not cover the two forms above.

**Ruling on duplicates:** Preserve each host reader’s existing behavior. Dart should collapse duplicate `%` keys and set elements as it does for any other symbol. Making Dart refuse duplicates reader-wide is a separate reader change outside this slice.

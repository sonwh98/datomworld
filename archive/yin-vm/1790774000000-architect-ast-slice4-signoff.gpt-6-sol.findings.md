I’m applying the code review skill for the working tree diff. The main risk is the Dart reader workaround: I’ll trace how it tokenizes input and compare its behavior with the REPL tests.
Completed-GMT: 2026-09-30 15:05:00 GMT  
Completed-Local: 2026-09-30 22:05:00 +07

**SIGN-OFF WITHHELD.** The occurrence rule and `subvec` change match the ruling, but the Dart reader workaround can change the meaning of input beyond the requested `%` fix.

| Severity | file:line | issue | fix |
|---|---|---|---|
| High | [yin/repl.cljc:879](/Users/sto/workspace/datomworld/src/cljc/yin/repl.cljc:879) | The scanner misses a `%` token immediately after a reader macro such as `#_` because `_` is not a token start. For example, `#_%` reaches the Dart reader unescaped. The reader cases tested do not cover this. | Tokenize reader macros correctly and add Dart tests for adjacent discard, tagged, regex, and anonymous function forms. |
| High | [yin/repl.cljc:880](/Users/sto/workspace/datomworld/src/cljc/yin/repl.cljc:880) | Giving each occurrence a distinct placeholder changes duplicate detection before restoration. Inputs such as `#{% %}` and `{% 1 % 2}` can be read as distinct placeholders, then collapse during restore instead of receiving the reader’s normal duplicate-key or duplicate-element refusal. | Use one placeholder per identical token, or preserve and verify reader duplicate semantics before accepting restored forms. Add refusal tests. |
| Medium | [yin/repl.cljc:899](/Users/sto/workspace/datomworld/src/cljc/yin/repl.cljc:899) | When any `%` token is present, restoration rebuilds **every** map with `into {}`. That can change an array map’s type and traversal order, including unrelated maps in the same input. Metadata copying does not preserve collection type or order. | Rebuild only branches containing placeholders, preserving the original collection type and metadata. Gate the workaround on a token that actually needs escaping. |
| Low | [yin.vm.code-as-tuples.implementation-plan.md:557](/Users/sto/workspace/datomworld/docs/design/yin.vm.code-as-tuples.implementation-plan.md:557) | Historical plan still describes `p-up`/`occ-anc` as the rule set. | Mark it historical or update the reference. |

The production rule is the prescribed single, root-scoped prefix rule. `plan-where` reorders only adjacent pattern clauses, so the `<` guard remains before `subvec`. Production callers no longer use `occurrence-fns`, `p-up`, `occ-anc`, or `member?`; those names remain in isolated contrast fixtures. The `subvec` tests cover both arities, exact refusal text, vector type, content equality, and CBOR encoding. The four-VM REPL tests include a user-typed `(def …)` rule set and the requested root and round cases. The reported JVM, Node, and Dart lanes are green, but none of those tests would catch the reader changes above.

The metadata reset addresses the reported Dart `:tag <Type>` encoding failure; it does not make the full-tree rebuild sound. An upstream ClojureDart reader fix remains the cleanest eventual solution.

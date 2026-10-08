Completed-GMT: 2026-09-30 10:56:54 GMT  
Completed-Local: 2026-09-30 17:56:54 +07

**SIGN-OFF WITHHELD.** The six tests run on all four VMs according to the supplied lane results, and I found no reader-conditional or map-ordering trap. Two tests can still pass while the property they name is broken.

| Severity | file:line | issue | fix |
|---|---|---|---|
| Medium | [ast_query_e2e_test.cljc:125](/Users/sto/workspace/datomworld/test/yin/repl/ast_query_e2e_test.cljc:125) | The occurrence test checks each root and path as an independent substring of printed output. It does not prove that each path belongs to the intended root or shared node. | Compare the query result as a set of exact `[root path]` tuples, derived from the captured roots. |
| Medium | [ast_query_e2e_test.cljc:156](/Users/sto/workspace/datomworld/test/yin/repl/ast_query_e2e_test.cljc:156) | Equal relations and counts across repeated evaluations also pass when both relations stay empty. The program counter proves packets were consumed, but not that `$ast` and `$occ` were populated. | Assert expected rows and occurrences exist after the first evaluation, then compare the full relations after repeats. |

The join, reset and VM selection, lost-index refusal, and query form and limit cases have effective assertions for their stated behaviors. The exact error-message checks are portable under the current implementation but tie the tests to wording. Acceptance item 1 remains assigned to slice 4 under the cited Architect ruling; its absence is not a finding against these six tests.

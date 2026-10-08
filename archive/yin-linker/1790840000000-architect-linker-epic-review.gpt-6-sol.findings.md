The L5 process test covers real JVM and Node readers, while the Dart reader is covered in process. I’m checking whether those tests exercise the contract’s response shapes and failure cases, and whether the signed-name fold preserves composition authority.
## Verdicts

- **L5: SIGN-OFF WITHHELD.** The JVM and Node process scenario, the Dart in-process scenario, and the store corpus match the L5 plan on inspection. The plain Clojure leg does not assert every §9 failure in the specified response shape.
- **EPIC: WITHHELD.** The same API split leaves the “one path” invariant incomplete. I also found a signed-name diagnostic gap that the current acceptance tests do not guard.

| Severity | file:line | Issue | Fix | Slice |
|---|---|---|---|---|
| High | [dht_process_test.clj](/Users/sto/workspace/datomworld-linker-l2/test/yin/repl/dht_process_test.clj:418), [link.cljc](/Users/sto/workspace/datomworld-linker-l2/src/cljc/yin/repl/link.cljc:270) | The plain leg asserts raw `module-status` failures for miss, invalid content, and unaskable requests. §9 specifies `:absent`, `:descriptor-defect`, and `:yin.link.dht/unaskable` response rows; their conversion is private to `yin.repl.link`. Thus plain Clojure cannot obtain the same failure shapes through the stated API. | Put the conversion in a public plain linker function, call it from the REPL, and assert its exact rows in L5. | L3, L5 |
| Medium | [dht.cljc](/Users/sto/workspace/datomworld-linker-l2/src/cljc/yin/vm/linker/dht.cljc:216) | A dangling retraction contains an assertion ID but no name. If its assertion is absent from the loaded snapshots, `diagnostics-for` cannot associate it with a requested name, so `resolve-name` omits the §9 dangling-retraction diagnostic. No test guards this case. | Define whether dangling retractions are global diagnostics or change the signed envelope contract to carry an authenticated name; test the chosen behavior. | L2, L4 |

**Engineer notes:** The Dart leg correctly runs in process; there is no Dart reader process in this gate. The process test’s several-minute runtime is documented and bounded, but I did not independently run it during this read-only review. The engineer reports a passing isolated process run and passing JVM, Node, and Dart lanes.

**Owner question:** Should §9 require *per-name* reporting of a dangling retraction when the referenced assertion is outside the reader’s snapshot set? Its current envelope has no name from which to derive that association.

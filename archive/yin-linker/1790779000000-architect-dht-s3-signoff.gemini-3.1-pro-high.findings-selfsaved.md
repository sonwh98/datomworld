# DHT S3 Sign-off Findings

| Severity | File:Line | Issue | Fix |
|---|---|---|---|
| MUST FIX | `test/dao/jing/dht_test.cljc` | Tick age eviction of partials has no direct test, violating the strict test-first / mutation probe rule for core properties. | Add a test (e.g., `chunk-partials-are-evicted-by-tick-age`) that sends one chunk part, steps the state by `query-ticks` ticks, and asserts the partial map is empty. |
| TIP | `src/cljc/dao/jing/dht.cljc:1093` | `step-ticks` rebuilds the `:partial-order` vector and calls `dissoc` on every tick, even when `expired` is empty. | Wrap the `-> state` updates in `(if (seq expired) ... state)`. |

## Rulings on Disclosed Gaps

1. **Live cross-process JVM↔Node and JVM↔Dart sockets were not tested:** **ACCEPTABLE for S3**. The wire vector test guarantees all host codecs produce and consume the exact same bytes, and loopback integration on the JVM proves the flow. Live cross-runtime integration is better deferred to the REPL/S5 epic where nodes can actually be spun up cleanly.
2. **Loss and reordering were exercised only in the in-memory mesh, not over live mixed hosts:** **ACCEPTABLE for S3**. The deterministic in-memory mesh tests perfectly cover the logical permutations of the reassembly algorithm. A stochastic network test adds no structural guarantees.
3. **Tick-age eviction of partials has no direct test:** **MUST FIX before commit**. Time-bound eviction is a core safety property against partial-message exhaustion. You must prove it works by adding a test that drops a partial after `query-ticks` elapse.
4. **Test-first was only partly followed for the socket and wire-vector tests:** **ACCEPTABLE for S3**. While a process violation, the tests are present and passing now.

**VERDICT: SIGN-OFF WITHHELD**

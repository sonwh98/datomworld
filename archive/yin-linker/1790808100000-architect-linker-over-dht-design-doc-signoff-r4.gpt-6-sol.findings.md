## Verdict: SIGN-OFF WITHHELD

R4 closes both r3 findings. Separate queue capacities and repair admission before fresh admission prevent fresh writes from filling the repair queue; L1 now tests that case. `:max-open` bounds publications awaiting a first report, while `:max-repairing` bounds those under repair. The stated ledger, queue, and outstanding-request bounds are consistent and testable.

One event path remains unspecified:

| Severity | File:line | Issue | Fix |
|---|---|---|---|
| Medium | [yin.vm.linker.dht.md](/Users/sto/workspace/datomworld-linker-dht/docs/design/yin.vm.linker.dht.md:514) | A `:publications-full` publication reports every blob failed immediately, but may share an already outstanding request. A later `/sent` updates its live ledger under §5.5.1, potentially changing its result to `:partial` or `:acknowledged`. Section 5.5.3 emits `:republished` only when **repair** changes the result, so this change can occur without a result event. | Require a `:republished` event for any post-report result change, including a shared request’s fact, or exclude overflow publications from in-flight sharing. Add an L1 test for overflow, shared outstanding address, then `/sent`. |

**Owner questions:** None.

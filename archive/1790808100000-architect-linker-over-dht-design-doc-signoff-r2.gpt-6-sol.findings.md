The dependency and failure-shape revisions close those findings. The replication section still has a lifecycle ambiguity: cancellation can report while a write is outstanding, and a retry covers only failed blobs while using the same publication result rules. I’m checking the slice tests before the verdict.
## Verdict: SIGN-OFF WITHHELD

R2 closes the dependency-binding, `:unaskable`, closed defect-code, and footprint findings. Moving backlog and result work to L1 is sound: L4 can then test row publication against an established API. Refusing FFI and parked-id modules is consistent with the stated schema-1 limit, and L0 tests those refusals. The owner’s explicit decisions and architectural invariants remain intact.

The bounded backlog closes the capacity finding, but its cancellation and retry rules still conflict with the publication result contract.

| Severity | File:line | Issue | Fix |
|---|---|---|---|
| High | [yin.vm.linker.dht.md](/Users/sto/workspace/datomworld-linker-dht/docs/design/yin.vm.linker.dht.md:471) | `cancel!` settles backlog entries and reports the publication “at once,” but it does not define how writes already outstanding are counted. Those writes can later produce `/sent`; the result’s `:sent`, `:failed`, and `:blobs` may then disagree. L1 explicitly expects a late fact, so this is observable. | Specify whether cancellation marks outstanding writes as cancelled for this publication, waits for their facts, or reports a distinct cancellation result. Define shared-address accounting and assert `:sent + count(:failed) = :blobs` after cancellation. |
| High | [yin.vm.linker.dht.md](/Users/sto/workspace/datomworld-linker-dht/docs/design/yin.vm.linker.dht.md:512) | `retry!` re-admits **only failed addresses** as a new publication of the same manifest. When the manifest succeeded in the original partial result, it is absent from the retry. The stated result rules classify that retry as `:unacknowledged`, while L1 and L4 require `:acknowledged` after repair. | Define retry as an update to the original publication’s complete blob ledger, or define a separate repair result. State how the original sent blobs contribute to the final acknowledgment and test both partial and unacknowledged originals. |

**Owner questions**

1. Should a partial or unacknowledged publication require an explicit `retry!`, as r2 specifies, or should the node retry automatically while it remains open? Both fit the current owner decisions; this availability policy needs the owner’s call.

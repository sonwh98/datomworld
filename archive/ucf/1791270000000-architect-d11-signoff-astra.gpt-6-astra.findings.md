I’ll read the D11 brief and review the full diff against the writer protocol, custody-map contract, and the two carried obligations. I won’t edit files or run suites.


Completed-GMT: 2026-10-06 10:18:44 GMT
Completed-Local: 2026-10-06 17:18:44 +0700

CHANGES

1. **Implement retained link writes.** [writer.cljc:82](/Users/sto/workspace/datomworld-d11/src/cljc/yin/vm/ucf/holder/writer.cljc:82) recognizes only `:put`; `:link-request` entries never receive IDs, emit requests, or discharge. Add link-specific target/payload extraction and outcome application, preserving the request envelope and enforcing cursor-before-send. Pin all three protection classes, retry retention, carried IDs, and successful transition through `engine/apply-link-sent`, including install children.

2. **Implement the carried close-ordering obligation.** [writer.cljc:91](/Users/sto/workspace/datomworld-d11/src/cljc/yin/vm/ucf/holder/writer.cljc:91) walks writes only, and [emit:278](/Users/sto/workspace/datomworld-d11/src/cljc/yin/vm/ucf/holder/writer.cljc:278) appends without considering `:yin.k/closes` or `:yin.k/issue`. D11 must resolve pending closes and writes in per-stream issue order: an earlier unresolved write prevents a later close from overtaking it, and a later write cannot overtake the close. Implement the ruled class-specific close behavior and discharge through `engine/apply-close`. Add restored-put → close → later-put regressions, including children and a write awaiting its admission outcome.

The existing put/FFI path implements root-counter assignment, correlation-versus-dedup separation, authenticated outcome matching, retention, and run termination; the unminted-cell guards and terminal FFI apply are present. However, the report’s claim that every write path and the whole D11 contract are complete is unsupported: link writes and close processing are absent from both the writer and its tests. These are D11 obligations, not D12/D13 integration work. No files were edited and no suites were run.
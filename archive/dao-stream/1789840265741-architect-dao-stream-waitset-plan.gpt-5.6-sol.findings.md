Created-GMT: 2026-09-19 17:51:05 GMT
Created-Local: 2026-09-20 00:51:05 +07 (Indochina Time)
Coding-Agent: codex
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a (provider-generated codex thread)
# Task: architecture review of docs/design/dao.stream.waitset.implementation-plan.md
Role: Lead System Architect
Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-20 00:51:05 +07 | Status: completed | Rationale: Reserved architectural review role per team roster

Verdict: unsound — 5 blocking, 5 should-fix, 1 note

---

Completed-GMT: 2026-09-19 17:54:34 GMT  
Completed-Local: 2026-09-20 00:54:34 Indochina Time

Architecture defects

blocking | docs/design/dao.stream.waitset.implementation-plan.md:267 | Invariants 2, 3, and 4: W3 introduces a shared `LinkedBlockingQueue`, a host-held atom accessed across threads, microtask/timer callbacks that execute `run-pending!`, and direct dispatch of `:woken` through a disposition function. This contradicts the plan’s own “woken entries are returned, never dispatched” rule at lines 122–126 and the host-boundary rule that callbacks append data and invoke nothing. | Make the cadence layer an explicit stepped interpreter whose invocation returns state and woken data. Timer or host callbacks may only append plain-data cadence events to a caller-supplied control stream. Do not let timer callbacks, `nudge!`, or queue consumers execute the sweep or host disposition directly. If an autonomous callback-driven scheduler is actually intended, it requires an explicit revision of the foundational invariants, not an implementation-plan exception.

blocking | docs/design/dao.stream.waitset.implementation-plan.md:158 | Invariants 1–3 and stream-boundary evidence: `nudge!` is a second, out-of-band event path coupled directly to every appending composition. Although correctness retains a polling fallback, the call changes execution cadence without that cause appearing on a stream. Calling it a “self-pipe trick” does not make the queue token part of the append-only log. It also covers appenders but does not specify close, retention advancement, acknowledgement, or other transitions that can change `blocked`/`full`. | Remove `nudge!` from the library API, or model nudges as plain-data events on a caller-owned cadence/control stream. Keep them optional latency hints and define every transition eligible to emit one. A host callback must deposit the hint and return.

blocking | docs/design/dao.stream.waitset.implementation-plan.md:89 | Invariant 4: the declared resolver only reads—`(resolve-ref store ref) -> {:stream handle :cursor cursor}`—but `check` is simultaneously required to construct `store'` after receiving a successor cursor. A generic library cannot update an opaque host store without either knowing its representation or receiving a write-back operation. Merely returning `store'` does not define how it is derived. | Specify a complete synchronous resolver algebra, such as `resolve-entry` plus a pure `commit-cursor` operation, both invoked immediately and never retained. Alternatively, keep a waitset-owned cursor overlay keyed by opaque reference, pass it into later resolutions, return the overlay as data, and let the host apply it. Test the seam with a non-map store so the implementation cannot accidentally depend on VM `{:id ...}` structure.

blocking | docs/design/dao.stream.waitset.implementation-plan.md:169 | Liveness and phase-contract evidence: `:budget` limits entries polled per round, while the plan rejects fairness, indexes, and any scan-position state and otherwise defines `check` as one complete ordered pass. A fixed prefix budget can permanently starve entries after the prefix. | Either remove `:budget` and retain a complete O(n) pass, or add an explicit pure continuation such as `:next-entry`/rotated waiting order and require eventual polling of every retained entry. This is basic scan progress, not optional priority or sophisticated fair queuing. Add a test with more waiting entries than the budget where the final entry becomes ready.

blocking | docs/design/dao.stream.waitset.implementation-plan.md:113 | Outcome fidelity: the plan says writer-side `closed` resolves as `:end`. The stream contract declares `:dao.stream/closed` as a distinct terminal append outcome, and the existing VM raises it as an append failure. Mapping it to `:end` changes VM semantics and contradicts W2’s unchanged-suite/unchanged-terminal-behavior condition. | Preserve `:dao.stream/closed` as its own terminal status and value. Only reader `:dao.stream/end` maps to `{:value nil :status :end}`.

should-fix | docs/design/dao.stream.waitset.implementation-plan.md:81 | Entry contract: the stated entry shape omits the value required to retry a writer. Existing VM waiters retain it under `:datom`. The divergence register also says an unknown `:reason` remains waiting forever, despite the public API claiming only `:next` and `:put` exist. This permits an invalid entry to cause permanent polling. | Define the minimal common schema explicitly, including a generic writer value key and preservation of opaque host fields. Make `park` reject or terminally classify unsupported reasons; do not retain an entry the library can never poll.

should-fix | docs/design/dao.stream.waitset.implementation-plan.md:76 | Implementation gap: `:woken` lifecycle is unspecified. It is unclear whether `check` replaces or accumulates an input `:woken` vector, when the host clears it, and whether resolved live handles may leak into returned entries. The existing engine explicitly drops `:stream` before creating a ready entry. | State that `:woken` contains only results from the current check, that previously returned results are host-owned, and that resolved handles/cursors used for polling are not retained except for the successor cursor required as data. Add repeated-check and serialization tests.

should-fix | docs/design/dao.stream.waitset.implementation-plan.md:293 | Phase decomposition: W4 does not reflect the completed W0 census. W0 says the GUI pump and `rpc/poll!` should remain single-stream, while identifying `dao.stream.serving` and the REPL tick owner as the multiplexed adoption sites. W4 instead names the GUI pump and “`dao.stream.rpc`’s serving loop,” leaving the mandatory migration set indeterminate. | Replace the “expected” list with the exact W0 decisions: identify each production adoption site, each cadence owner, and each explicitly unchanged consumer. Make W4’s completion criterion enumerate those namespaces.

should-fix | docs/design/dao.stream.waitset.implementation-plan.md:305 | Phase/end-condition contradiction: W5 says only one `dao.await.md` sentence changes and “no other prose” is touched, but `dao.await.md` contains numerous live `dao.runtime` references, including runtime-owned tasks, `:resume` callbacks, and runtime invocation. The end condition requires that no document name deleted `dao.runtime` as live. | Expand W5 to replace all obsolete runtime references in `dao.await.md`: Yin owns parking/restoration, `dao.stream.waitset` performs classification, the host cadence layer decides when to check, and the VM—not a raw `:resume` callback—turns woken data into ready-machine state.

should-fix | docs/design/dao.stream.waitset.implementation-plan.md:235 | Test specification gap: iterating the current outcome sets does not by itself ensure a future declared outcome fails the suite; a default “everything except blocked/full is terminal” branch would accept it. | Pin an explicit expected classification map equal to the declared sets, or assert an intentional policy table whose domain must equal `outcomes-next ∪ outcomes-append`.

note | docs/design/dao.stream.waitset.implementation-plan.md:106 | Terminology: `check` is not a pure function in the referential-transparency sense because it calls `next` and effectful `append!`. Its store transition can be data-driven and free of shared mutation, but the entire operation is an explicit interpreter step with stream effects. | Describe `check` as a state-threaded, synchronous interpreter step rather than “pure in, pure out.”

Passed properties

- Invariant 1 passes for the W1 core design: waitset state is caller-supplied data, with no namespace-global atom, registry, or ambient resolver.
- Invariant 2 passes for `check` itself: it polls and partitions entries but does not resume continuations, execute VM registers, or schedule tasks. W3 does not presently preserve this result.
- Invariant 3 passes at the W1/W2 seam: woken entries are ordinary returned data, and VM-specific ready-queue construction remains in `yin.vm.engine`.
- The intended shared-cursor ordering matches the existing engine: a successor is visible before a later co-waiter resolves. The architecture needs the missing generic write-back seam identified above.
- Invariant 5 passes: `dao.stream.waitset` is an interpreter above `dao.stream`; the plan requires zero protocol, outcome, transport, and readiness-extension changes.
- Invariant 6 passes: streams remain append-only media observed through immutable cursors. No topology or graph semantics are inferred.
- Refusing `:resume`, VM registers, task queues, priorities, and application scheduling in the waitset is architecturally correct. Refusing priority/fair-queue policy does not excuse starvation introduced by a bounded partial scan.
- The plan enumerates all seven `next` outcomes and all five `append!` outcomes. Waiting only on reader `blocked` and writer `full` matches the existing VM policy. Apart from the erroneous `closed -> end` mapping, terminal classification is faithful.
- Treating undeclared outcomes as terminal is sound under the currently governing closed outcome sets and matches existing consumers. Such outcomes should remain visible as their original qualified keyword.
- W0, W1, and W2 are sensibly separated. Unchanged VM suites across all three hosts are an appropriately strong W2 end condition.
- Deferring the transport readiness extension, priority policy, single-stream conversions, and changes to `dao.await` semantics is justified.
- The extraction can leave VM continuation and resumption semantics intact: the VM retains parked registers, ready-queue construction, terminal raising, and restoration; only readiness polling moves.

The central extraction is well-motivated, but the current W3 design violates non-negotiable boundaries, and the W1 resolver and bounded-round contracts are not implementable as written without hidden assumptions.

VERDICT: unsound

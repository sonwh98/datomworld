I found several gaps that would block implementation as written: the plan names no remote renewal or replay-prefix path, replay sources lack stable identities across cell remapping, and an offer refusal does not always prove abort is safe. The held-immediate design also needs to retain and retry the exact observed value until its input record is acknowledged.


Completed-GMT: 2026-10-05 09:09:41 GMT
Completed-Local: 2026-10-05 16:09:41 +07

**REQUEST CHANGES.** The direction is sound, but the plan leaves replay identity, remote protocol access, abort safety, and child gating insufficiently specified. These are implementation-blocking contracts, not matters to settle during D12.

1. **D2 does not yet establish that the gate covers every observation path.**

   The plan says “when the root has `:yin.k/custody`, the engine never observes for that task or its install children,” while children carry no custody state. The existing scheduler calls `vm/run` on the child directly in [engine.cljc:1232](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:1232). Specify how the child receives the root’s execution policy without acquiring independent counters or tenure.

   Filtering ordinary wait entries is insufficient. [check-wait-set](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:1592) first polls link entries, advances install children, and routes FFI responses, then invokes the ordinary wait-set sweep. D2 must gate each of those paths before observation.

   Extend the zero-observation tests to direct resume, child creation, shared FFI response readers, link-response scanning, and exporting/ended states. The gate must distinguish **running under custody**, **exporting**, and **ended**; the latter two permit no program observation or application of a late result.

2. **Held immediates are acceptable, but “held one step” is wrong and the replay source is incomplete.**

   A held poll or cursor operation can remain pending for arbitrarily many driver steps while recording is unavailable. It must retain its exact observation and retry the same input request—not poll again or mint another cursor after unknown recording acceptance.

   No new *wire* pending variant is needed if export reliably refuses every task containing such local held state, including children. That is an explicit safepoint restriction requiring the proposed §7.4.1/7.4.3 amendment.

   Replace the behavioral rule with:

   > A held immediate remains local and unexportable until its exact portable observation is durably acknowledged and applied once. Recording retries neither repeat the observation nor change its source, sequence, or observed value.

   The source also needs an operation discriminator. A read identified only by “stream identity and cell” can confuse `poll`, `next`, and cursor creation. The landed input validator accepts exactly `{:yin.k/kind … :yin.k/name …}` ([input.cljc:83](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ucf/authority/input.cljc:83)); put the structured operation identity inside `:yin.k/name`.

   Cursor observations must be portable positions/markers, never host cursor objects or sealed local references.

3. **D7 needs stable activation and cell identities, plus an exact-once application rule.**

   “Apply it to the waiting entry whose source it names” is ambiguous when two waits share a cursor, or root and child calls reuse local identifiers. Receiver-local cell names are reminted during lower and cannot define cross-host replay identity.

   Specify a portable source identity containing the logical task/child path and the relevant cursor or correlation identity, with deterministic selection among multiple matching waits. Preserve aliasing: the first acknowledged read advances the shared cursor before the next read is observed.

   Explicitly retain these states:

   - observation not yet recorded;
   - recording requested, acceptance unknown;
   - acknowledged observation awaiting application;
   - applied input sequence.

   A replay mismatch may end the run only after all permitted internal computation has been exhausted and no pending protocol operation could create the expected wait. “Quiescent” must not mistake waiting for a fenced-write reply for semantic divergence.

4. **The fenced request ID is acceptable correlation, but it is not deduplication.**

   Deriving a request ID from lease and operation ID is sound if the derivation is injective and canonical, for example a tagged tuple. A regrant changes the request ID because it changes the lease; the retained operation ID remains unchanged.

   The landed [front handler](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ucf/authority/front.cljc:250) does not deduplicate requests by request ID. It dispatches them again. Effect deduplication occurs in the authority’s shared operation-ID namespace.

   State this distinction explicitly. A reply must match authenticated authority, request kind, request ID, operation ID, and incarnation. A projected admission outcome has no front request wrapper; authenticate and correlate it through its own shape.

   C10’s input frontier is separate: each regrant replays the granted occurrence’s prefix from input sequence zero; a successor occurrence starts its own input sequence. Neither request IDs nor the continuing operation counter determine input `k`.

5. **The claim that the driver speaks “only the C11 front” is currently unimplementable.**

   The front dispatches offer, proposal, resumed, release, input, and admit requests ([front.cljc:223](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ucf/authority/front.cljc:223)). It has no renewal or replay-prefix request.

   Meanwhile, `input/frontier` and `input/inputs` take an authority **projection and lease**, not a remote stream ([input.cljc:212](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ucf/authority/input.cljc:212)).

   Choose and document the missing paths before D6–D9:

   - an attributed lease-fact channel for renewals, or an explicitly amended front;
   - an authenticated authority-ledger reader from which the holder derives binding, enrollment, and replay-prefix evidence, or explicit query requests.

   Define incomplete-history behavior: an unavailable or partial prefix must never appear as an empty prefix. Do not let in-process tests obtain a projection directly while claiming the same interface works remotely.

6. **Releasing after divergence or intent conflict is acceptable; it is not recovery authorization.**

   A divergent replay must stop program effects, publish no successor, and emit a diagnostic. Releasing its own lease is appropriate cleanup.

   Likewise, release after authenticated `:intent-conflict` does not contradict quarantine, provided release cannot clear quarantine, complete the occurrence, or make it automatically regrantable. Test that property against the landed completion/grant code.

   The run-ending rule must stop **all program IO**, including declared at-least-once effects, not only “further fenced emission.” Cleanup release and control-plane traffic remain separate.

   Input conflict is not effect intent conflict: the published input protocol explicitly says it quarantines nothing. D7 must preserve that distinction rather than translating every replay failure into `:intent-conflict`.

7. **D3 must dispatch body versions before invoking the v1 inspector.**

   `checkpoint/inspect` takes `[address bytes]`, verifies the segment address, then inspects the decoded root. Its supported grammar is **version 1 alone** ([checkpoint.cljc:50](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ucf/checkpoint.cljc:50)). Calling it first for every body rejects valid version-0 forks.

   Specify a version-aware reader pipeline: decode/tag/version dispatch, v1 custody inspection where applicable, then full recursive restoration validation, all before attachment or proposal. Resolve the intended precedence between unsupported-version and body-address failures explicitly.

   The existing inspector already checks that an install pending names an entry ([checkpoint.cljc:294](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ucf/checkpoint.cljc:294)). D must retain that check and independently close the version-0 restoration defect. Baseline inspection is not full validation of code, cells, registers, or install responses.

8. **Fixed traversal alone does not make lift pure or cross-host deterministic.**

   The current exporter invokes `serve!` while encoding values and recursively exporting children ([handoff.cljc:604](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ucf/handoff.cljc:604)). Descriptor creation and fresh resource publication can therefore affect the result independently of traversal.

   Split resource preparation from encoding:

   > The export record retains every chosen resource identity, descriptor, occurrence, and remapping seed. Encoding that prepared record performs no resource allocation or publication and produces canonical bytes deterministically.

   Order maps and sets by canonical encoded keys, not host iteration or textual sorting. Preserve wait order and alias identity; canonical sorting must not reorder observable waits. Include float carriers, signed zero, NaN, nested maps, shared cells, and children in cross-host fixtures.

   “No envelope key appears anywhere in a body” is also too broad: a program may legitimately contain envelope-shaped payload data. Assert that the **driver’s transport envelope** is not persisted as runtime wrapper state.

9. **The abort rule is unsafe as written.**

   “Abort … on an authority-attributed refusal of the offer” does not prove that no earlier offer was admitted. A retry can be refused because of conflicting bytes or baseline after an earlier successful offer whose reply was lost.

   Require:

   > Abort is permitted only before any possibly accepted offer attempt, or upon authoritative evidence that this occurrence has never been admitted and cannot still become admitted from an outstanding attempt. A refusal of one request is not such evidence.

   Persist the fence, occurrence, prepared export record, and **offer-attempt intent before sending**. A crash between sending and recording progress must reopen fenced.

   For a holder exporting a successor, abort additionally requires current valid tenure. Restoring its old local machine after lease expiry is never legal merely because its successor offer was rejected.

10. **The progress journal needs a recovery contract, not just record names.**

    D10 lists occurrence/publication/offer/proposal/grant/release, but does not say what reconstructs the fenced source’s waits, retained IDs, prepared descriptors, and publication payload after a crash.

    Specify durable references to the prepared checkpoint and the ordering of each progress write relative to its external action. An uncertain journal append must prevent further execution until reconciled.

    Recovery must never restore execution solely from a journaled historical grant. It must establish current tenure through the authority. Also include stable proposal identity, pending release, result delivery, and enrollment retries using the C12-prescribed identity discipline.

11. **D11/D12 contradict the exclusivity gate and need narrower claims.**

    The C12 gate explicitly rejects memory authorities for production exclusivity. D11 nevertheless requires “a whole handoff … on the memory seam.” Separate:

    - internal protocol tests using the memory substrate;
    - exclusive composition acceptance using an authority that passes `exclusive-capable?` for the required failure model.

    Do not bypass that predicate in the composition to make a test pass. Nor silently downgrade an explicitly requested exclusive handoff into a fork: return the required refusal; offer fork only when the caller selects it.

    D12’s “each row … on both body versions” needs exclusions for explicit park and held immediates. Apply §7.4.1’s actual liftability conditions rather than requiring a fictitious pending variant.

    The E boundary is otherwise reasonable: D owns same-host driver/composition behavior; E owns real partitions, kills, and ordered cross-host compositions. Split D2’s broad engine surgery into reviewable subrounds and serialize D1/D3, which both edit `handoff.cljc`.

12. **The REPL location claim is correct, but the insertion point needs qualification.**

    `yin.repl.core` is absent. [yin.repl.main/step-all:804](/Users/sto/workspace/datomworld/src/cljc/yin/repl/main.cljc:804) is the current tick owner. Cite that function and its callers, plus `yin.repl.dht/step`, `shell/recheck-on-load-events`, `driver/repl-step`, and `serve/step`.

    The actual order is DHT stepping, load-event rechecking, local shell work, then served-endpoint work. During DHT hydration, `step-all` can return before the shell step. Placing custody only “after the shell step” could therefore starve renewals and pending releases. Give custody control-plane progress an explicit position that remains serviced during hydration, without creating another DHT or timer owner.

The plan should be revised before D2, D6, D7, or D10 starts. D1’s isolated version-0 defect fixes can proceed under their existing contracts. Read-only review; no files changed or suites run.
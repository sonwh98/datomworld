Completed-GMT: 2026-09-14 13:25:50 GMT  
Completed-Local: 2026-09-14 20:25:50 Asia/Ho_Chi_Minh  
Coding-Agent: codex  
Session-ID: 01a09fcc-3b9f-7172-b592-f15348d6c88b

**Verdict: REJECT**

The revisions resolve substantial parts of the first review. The positional tuple vector is a suitable canonical representation, and neither owner ruling needs reconsideration. Remaining defects concern preservation of observable semantics, dependency discovery, and the newly specified custody/replay lifecycle. Several cannot be closed by implementing the current text faithfully.

“Resolved” below means the architectural rule is repaired in the text; it does not mean implemented or tested. New revision defects are P1 as requested.

**Findings**

1. **P1 — “Nothing left to collide” is false with the specified encoder, even on one host.**  
   **Draft:** §7.3.2, [lines 215–235](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:215).

   The tuple vector removes the earlier sorting collision, but DaoJing’s current encoder converts every sequential value into a vector ([jing.cljc, lines 45–64](/Users/sto/workspace/datomworld/src/cljc/dao/jing.cljc:45)). Thus these different executable literals hash identically:

   ```clojure
   [[:const [1 2]]   [:halt]]
   [[:const '(1 2)]  [:halt]]
   ```

   I verified the equal hashes and that both literal batches pass `well-formed?`. Their distinction is observable through the standard `conj` primitive: `[1 2 3]` versus `(3 1 2)`. The portable grammar explicitly admits both collection types at line 557.

   **Fix:** Make type-preserving canonical encoding an explicit prerequisite for semantic identity, including nested literals and continuation values. Qualify the collision-freedom statement accordingly: this is not merely a cross-host print-stability issue. Keep the owner-selected tuple vector.

2. **P1 — Projection/direct-path equivalence needs a shared validation and registration contract.**  
   **Draft:** §7.3.4, [lines 292–330](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:292).

   The projection path invokes §2.6 validation; the direct path’s enumerated checks cover mnemonic, arity, operand kinds, and saturation. Those checks alone do not reject, for example, `[[:jump 9]]`, an empty vector, or a vector ending in `:const`. I verified that projecting the jump example produces `:dangling-target`.

   Registration is also underspecified: the projection always uses segment eid `0`, while the ordinary loader registers by that id and rejects a second different image ([semantic.cljc, lines 599–637](/Users/sto/workspace/datomworld/src/cljc/yin/vm/semantic.cljc:599)). Saying that address loads “may mint” fresh ids does not specify where the fixed projection id is replaced.

   **Fix:** Define one validator covering tuple grammar **and** translated §2.6 constraints, with identical `:yin.k/undecodable` outcomes on both paths. Make projection ids temporary, require relocation before registration, and compare images under the same assigned local id. Test malformed vectors and multi-segment loading, not only valid corpus segments.

   Also publish the actual saturation table. Missing `:call` tailness loads as false; absent FFI argc is rejected by the validator despite the example claiming default saturation ([loader](/Users/sto/workspace/datomworld/src/cljc/yin/vm/semantic.cljc:568), [validator](/Users/sto/workspace/datomworld/src/cljc/yin/vm/code.cljc:139)).

3. **P1 — Primitive profiles still do not identify behavior.**  
   **Draft:** §7.5.2, [lines 610–640](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:610).

   The addressed profile is explicitly `{name class arities effects host-state}`. Two implementations of `f`, one adding its arguments and one subtracting them, can truthfully share every field. Equal profile hashes therefore still permit different behavior. The host-state warranty does not resolve this.

   **Fix:** Include an immutable semantic specification/revision identifier and require each binding to conform to it. The specification must cover results, errors, numerical behavior, and effect construction. A metadata signature is insufficient. Use actual DaoJing addresses; the example’s `:yin.k.pp/sha256-…` is not its address format.

   This matters for existing primitives too: [the standard map contains host-specific `bytes->str` implementations](/Users/sto/workspace/datomworld/src/cljc/yin/vm.cljc:122).

4. **P1 — The fixed point remains an under-approximation across activations and computed effects.**  
   **Draft:** §7.6.1, [lines 758–789](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:758).

   A name is declared satisfied if bound in **any** carried environment. That is still incorrect. A return frame may bind `x`, while the active closure does not; its `:var x` must resolve from store/primitives/modules. An unrelated environment cannot satisfy that execution dependency. [Actual resolution examines only the current environment](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:46).

   The traversal also names store operands without explicitly traversing literal operand **values** for embedded callable/resource data. More broadly, a pure primitive can construct an effect map: `apply-call` dispatches any result satisfying `module/effect?`, regardless of the primitive’s declared class ([semantic.cljc, line 206](/Users/sto/workspace/datomworld/src/cljc/yin/vm/semantic.cljc:206)). Classifying only `:effectful` primitives does not bound that path.

   **Fix:** Track dependencies by activation or conservatively include every applicable fallback binding/profile. Traverse embedded values in code operands. Require sound callable/effect footprints, including constructed results and dynamic callees; otherwise mark discovery incomplete. Recompute or validate this closure on the receiver rather than trusting the envelope’s `:complete`.

5. **P1 — The replacement static lexical metadata retains an unjustified equality claim.**  
   **Draft:** §7.4.2, [lines 425–449](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:425).

   Static stack effects are a valid replacement for absolute depths. However, `lexically-required` is still claimed to be derivable from code alone and checked against observed E-reads. Whether `:var x` reads E depends on that activation’s bindings; different paths also read different subsets. The same source-level name can resolve through different layers.

   **Fix:** Define this as a conservative set of potentially required names, with observed reads required to be a **subset**, or supply a verified lexical-binding analysis and its assumptions. Do not require equality with one execution’s observed reads. Keep dynamic reconstruction recipes distinct from that static analysis.

6. **P1 — Counter adoption and resource-key remapping change observable program state.**  
   **Draft:** §7.6.2–§7.6.3, [lines 822–855](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:822).

   `max(local, carried)` makes the receiver affect the next `gensym`. I verified that carried counter `2` yields `:id-2` locally but `:id-100` on a receiver with counter `100`. Generated identifiers are ordinary observable values, not hidden allocation details ([engine.cljc, line 406](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:406)).

   Resource keys also remap, while literal `:store-get` operands and ordinary values containing those keys remain unchanged. Code reading an old resource key can consequently observe absence or a different entry. The opcode performs an ordinary store lookup ([semantic.cljc, line 292](/Users/sto/workspace/datomworld/src/cljc/yin/vm/semantic.cljc:292)).

   **Fix:** Adopt the carried semantic counter exactly in the isolated task. Allocate host attachment identities separately. Preserve observable task store keys, or define explicit logical-key indirection covering every access. Do not rewrite arbitrary program data or addressed code implicitly.

7. **P1 — “Whole blocked machine” is not represented by the specified wire schema.**  
   **Draft:** §7.4.1/§7.4.3, [lines 382–397](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:382), [lines 468–498](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:468); §7.6.3, lines 846–859.

   The migration unit now includes the entire wait-set, but the envelope supplies one frame and one pending map. No ordered wait-set schema associates each pending operation with its own registers. Ordering is observable for shared cursor cells ([check-wait-set](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:290)).

   The “exhaustive” pending variants also omit explicit `:park` and the declared `:call-effect` reason. Lower has no specified source for an explicit park’s resume value. Captured reified continuations may have non-parking pcs, yet their decoding points to a frame/lower contract defined only at parking safepoints.

   **Fix:** Specify an ordered vector of complete waiter records, explicit parked/resume-input handling, and encoding/restoration rules for captured continuations independently of migration eligibility. Either map effectful calls to concrete pending kinds or define their extra variants. Scope portable-cursor requirements to every migrating cursor that will be used, not only FFI response endpoints.

8. **P1 — Tagged values are disjoint, but complete decoding and result carriage remain underspecified.**  
   **Draft:** §7.2, [lines 113–123](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:113); §7.5.1, [lines 554–590](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:554).

   A result’s encoded value can contain shared refs, closures, or cursor refs, but the result schema provides no associated values/cells/code requirements. Such an allowed result cannot necessarily be decoded.

   Decoder checks also omit explicit verification that each table key equals the hash of its entry. A correctly hashed outer body can contain `{claimed-subvalue-address different-subvalue}`. Checking reachability and tag shape does not validate that content-address assertion. Literal-entry parity, duplicate decoded keys, missing cells, and frame/code bounds likewise need explicit validation rules.

   **Fix:** Define a common self-contained body/context schema for both continuation and result envelopes. Specify structural versus encoded-value positions, verify every addressed table entry, and enumerate graph/frame/map validation obligations before attachment or execution.

9. **P1 — Occurrence identity lacks an authoritative registration/cancellation transition.**  
   **Draft:** §7.3.4, [lines 333–345](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:333); §7.7.3–§7.7.4, [lines 981–1049](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:981).

   The content/occurrence split is correct, but “authorized snapshot variant” is not defined by a ledger rule. Grants name an occurrence without specifying which registered snapshot and policy may be lowered. The occurrence→authority binding is asserted by payload carriage rather than established by a registration transition.

   Abort is permitted “before the offer is recorded,” although a published snapshot can already exist elsewhere. There is no authoritative cancellation tombstone or rule preventing a delayed offer from reviving an abandoned export. DaoLease even permits unsolicited grants unless the UCF domain narrows that policy ([DaoLease, line 72](/Users/sto/workspace/datomworld/docs/design/dao.lease.md:72)).

   **Fix:** Define durable occurrence registration, authorized snapshot binding, and grant preconditions. Make abort a conditional authority transition once publication can escape; uncertain registration must remain exporting until reconciled. Persist occurrence/export identity for crash retries. Include emitter attribution in the effective identity scope and specify separate fork-branch identities.

10. **P1 — Emission sequence numbers do not make replay deterministic.**  
    **Draft:** §7.7.5, [lines 1077–1087](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:1077).

    The same checkpoint can observe different external input after reassignment—for example, a previously readable cursor now returns `gap`. It can then emit a different operation at sequence `0`. Deduplication either suppresses a different operation or returns an unrelated result. Fresh resource creation and host-dependent primitive behavior introduce further divergence.

    Allocation “by the fenced writer” also leaves unclear whether counters are per writer or per occurrence, whether `full` retries retain their id, and how multiple destinations share one sequence.

    **Fix:** Specify one occurrence-wide operation allocator, stable ids across append retries, and an operation fingerprint checked on duplicate ids. For replay guarantees, journal nondeterministic observations/results and resource creation, or restrict resumable execution to a declared deterministic replay profile. An op-id is an identity assertion, not proof that two operations are equal.

11. **P1 — The fenced-writer wrapper changes the stream-visible value without a matching interpretation contract.**  
    **Draft:** §7.7.5, [lines 1089–1097](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:1089).

    Wrapping protects the original payload from mutation, but changes what the stream contains. The reference reader returns `:dao.stream/value` directly to the program ([engine.cljc, line 240](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:240)). A program that writes `42` and reads the same logical stream can now receive a UCF envelope. FFI request validators similarly need the original request envelope, not an unspecified outer wrapper.

    The mechanism also says duplicate effects return stored results without specifying the response stream and correlation protocol that delivers those results.

    **Fix:** Define fencing as an explicit interpreter protocol with ingress envelopes, validation, committed payload output, and correlated result output. Declare those endpoints and profiles in dependencies. Preserve program-facing stream semantics without making DaoStream operations consult leases, which [DaoLease prohibits](/Users/sto/workspace/datomworld/docs/design/dao.lease.md:78).

12. **P1 — A transactable space alone does not supply the promised durable atomic effect admission.**  
    **Draft:** §7.7.3/§7.7.5, [lines 968–999](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:968), [lines 1066–1084](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:1066).

    The execution ledger is a defensible possessed resource. Its transactor can control admissions to that ledger. It cannot, merely by owning those datoms, atomically commit an unrelated FFI callee’s external side effect.

    The existing transactor promises one atomic local append and assumes single-writer composition; it does not supply the proposed distributed commitment mechanism ([transactor contract, lines 70–75](/Users/sto/workspace/datomworld/docs/design/dao.space.transactor.md:70)). Its local record is explicitly **not durable**, and unpublished writes are lost on process restart ([lines 142–163](/Users/sto/workspace/datomworld/docs/design/dao.space.transactor.md:142)).

    **Fix:** Restrict atomic exactly-once admission to effects, epoch state, and dedup records sharing one defined commit boundary. Other resources need a separately specified idempotent resource protocol. Define durable acknowledgment/recovery through the existing DaoJing publication model before acknowledging protected commitment. “Has a transactor” is not the sufficient capability test.

13. **P1 — Release conflates failed lowering with successful checkpoint completion, and successors lack an activation commit.**  
    **Draft:** §7.7.5–§7.7.6, [lines 1102–1141](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:1102); §7.8, [lines 1205–1209](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:1205).

    Completion is defined as observing a release, optionally preceded by `:resumed`. Failed lowering also releases, after which the occurrence supposedly returns to offered. The same authoritative input therefore means either “finished” or “retry.”

    Successors are fresh, independently offerable occurrences, but no rule makes their activation conditional on committing the predecessor’s completion. A holder can publish/offer a successor, then crash before releasing its predecessor; replay can create another successor. Declaring orphan content harmless does not prevent those offers from acquiring grants.

    **Fix:** Separate relinquishment from completion. Define an atomic transition that validates the holder/epoch, records one durable successor identity and content address, closes the predecessor, and activates that successor. Reject competing successor commits. Require durable availability of the successor and its dependencies; an append to an intake pool is not DaoJing materialization acknowledgment ([DaoJing, line 162](/Users/sto/workspace/datomworld/docs/design/dao.jing.md:162)).

14. **P1 — Failure paths still lack executable state transitions and complete outcomes.**  
    **Draft:** §7.8–§7.9, [lines 1162–1247](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:1162).

    Lift enters exporting before validation, while §7.5.4 says encoding refusal leaves the machine unaffected and parked locally. No transition reconciles these states.

    Lower can fail during segment loading after acquiring custody, but step 7 scopes cleanup to failures “from this step onward.” The later blanket cleanup sentence contradicts that. Release append failure has no retained releasing state, and appending release cannot immediately make an occurrence offered: DaoLease requires the grantor’s reclaim and successful lapse recording first ([DaoLease, lines 148–155](/Users/sto/workspace/datomworld/docs/design/dao.lease.md:148)).

    `full` publication retry still has no defined `:yin.k/status`; `:unsatisfied` is listed as lower-only although lift raises it for missing cursor profiles.

    **Fix:** Specify driver states and outcomes for validation refusal, publishing, awaiting grant, releasing, retryable transport failure, and authoritative reclaim completion. Retain obligations until acknowledged; do not infer authority transitions from locally appended evidence. Update §7.10–§7.11 to match those rules. In particular, “at-least-once” is not established merely by lacking fencing—delivery may stall or be lost.

**Original findings 1–19: resolution audit**

| Original | Status | Verification in revised text |
|---|---|---|
| **1. Non-total triple sorting, local refs, self-hash** | **Resolved** | Positional structure removes mixed sorting, membership refs, and self-hashing: [183–213](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:183). Encoder limitations remain; see new finding 1. |
| **2. Duplicate-attribute sorting collision** | **Resolved** | Last-value-wins occurs before canonicalization: [160–170](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:160). The demonstrated `1,2`/`2,1` collision is repaired. |
| **3. Addresses did not name stored payloads** | **Resolved** | Code payload is the vector; continuation payload is the id-less body: [284–290](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:284), [333–337](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:333). |
| **4. Stamp scope and local-id compatibility** | **Resolved** | Complete execution contract, per-stamp index, retained local-id immutability: [246–274](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:246), [321–330](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:321). Projection registration needs refinement, finding 2. |
| **5. Missing effectful-call safepoints** | **Resolved** | Explicit ordinary/tail effectful-call row and post-pop stacks: [364–380](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:364). |
| **6. Dynamic depth/environment claimed static** | **Not resolved** | Absolute-depth claim withdrawn, but lexical requirements still compared to observed E-reads: [435–449](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:435). Finding 5. |
| **7. Literal maps counterfeit encoding tags** | **Resolved** | Every literal map is recursively wrapped at encoded-value positions: [546–579](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:546). Remaining schema problems are finding 8. |
| **8. Primitive identity does not pin semantics** | **Not resolved** | Alias handling is repaired, but profile fields describe a signature, not behavior: [620–640](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:620). Finding 3. |
| **9. Encoding identity used as execution identity** | **Resolved** | `id` and occurrence are explicitly separated; variants/retries share the occurrence: [333–345](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:333). Registration remains finding 9. |
| **10. Incomplete dependency closure** | **Not resolved** | Explicit store operands and discovery states added, but “any environment” still under-approximates: [765–789](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:765). Finding 4. |
| **11. Destination-store merge changes resolution** | **Resolved** | Both merge rules replaced with isolation: [816–833](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:816). New remapping defects are finding 6. |
| **12. Missing scheduler/fresh-name state** | **Regressed** | State is declared, but `max(local, carried)` introduces receiver-dependent semantics, and the whole wait-set lacks a schema: [846–859](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:846). Findings 6–7. |
| **13. Incomplete pending operations** | **Not resolved** | Write payloads and FFI endpoints/cursors repaired, but explicit park and multi-waiter representation remain missing: [472–534](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:472). Finding 7. |
| **14. Cursor aliasing erased** | **Resolved** | Non-content-addressed cells preserve shared versus independent advancement: [678–703](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:678). |
| **15. Grantor possesses no resource** | **Resolved, within the stated admission boundary** | The ledger is a concrete resource and exclusive admission is domain policy: [968–999](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:968). Extending that power to external effects remains finding 12. |
| **16. Publication/wakeup race** | **Not resolved** | Forward export detaches waiters before publication, but abort/crash registration is incomplete: [1015–1049](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:1015). Findings 9 and 14. |
| **17. Lapse filtering/exactly-once/restart** | **Not resolved** | Epochs replace negative evidence, but replay, atomicity, durability, and completion remain unsound: [1066–1129](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:1066). Findings 10–13. |
| **18. Outcome/wire vocabulary** | **Not resolved** | FFI keys and stream outcome key repaired; result context and lifecycle outcomes remain incomplete: [1225–1247](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:1225). Findings 8 and 14. |
| **19. Compliance/open-item overclaims** | **Not resolved** | Cross-reference and blocker labeling repaired, but compliance still depends on contradictory or missing mechanisms: [1253–1297](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:1253). |

**Verified sound**

- **Positional instruction tuples can represent every §2.4 opcode.** No operand fundamentally requires EAV. The owner’s query ruling is directly supported by [the arbitrary mixed-dimensional `relation` implementation](/Users/sto/workspace/datomworld/src/cljc/dao/space/query.cljc:153). Queries needing pc should construct an explicit indexed tuple projection.
- **Resolve duplicate attributes before deriving identity.** This aligns canonicalization with the reference loader and fixes the original demonstrated collision.
- **Hash verification and structural validation are independent requirements.** The revision correctly requires both; the direct-path validation scope needs completion.
- **Wrap-all literal maps makes wire tags disjoint.** Nested program maps cannot counterfeit markers when encoded-value positions are correctly defined.
- **Content identity versus occurrence identity, isolated stores, and explicit cursor cells are correct architectural choices.**
- **Pre-publication wait-set detachment closes the immediate polling/write-retry window.** Its failure and cancellation lifecycle remains unfinished.
- **`pc+1`, the written-value resume accumulator, kept FFI response cursors, and separate outstanding/future FFI routing remain sound.**
- **Fail-closed protected commitment and explicit permanent-authority-loss limits are honest constraints.** They should remain, without describing unavailable guarantees as already supplied by a transactor.

The revisions establish a much stronger representation layer, but do not yet close all five blockers. Code identity now has the right structural basis; portability still depends on a type-preserving codec and complete loader validation. Safepoints and encoding are substantially improved, while dependency and scheduler closure remain incomplete. Ownership now has a meaningful resource boundary, but deterministic replay, durable commitment, cancellation, and successor activation still need actual protocol rules. Those are design work, not merely conformance tests awaiting implementation.

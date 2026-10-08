Completed-GMT: 2026-09-14 12:04:48 GMT  
Completed-Local: 2026-09-14 19:04:48 Asia/Ho_Chi_Minh  
Coding-Agent: codex  
Session-ID: 01a09fcc-3b9f-7172-b592-f15348d6c88b

**Verdict: REJECT**

The draft contains architectural defects beyond the implementation work it acknowledges. Most seriously, canonicalization can erase executable differences, and custody lacks a resource-grounded exclusion mechanism.

**Numbered findings**

1. **P1 — Canonicalization is neither total nor independent of local identity.**  
   **Draft:** §7.3.2, [lines 114–123](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:114).

   The procedure leaves every instruction’s `:yin.code/segment` value as its original segment eid, although that attribute is mandatory ([semantic contract, line 126](/Users/sto/workspace/datomworld/docs/design/yin.vm.semantic.md:126)). It also retains `:yin.code/hash`, making verification hash the hash being verified. Finally, sorting triples mixes the keyword `:segment` with numeric pcs. A read-only Babashka check of the example’s structural sort fails with `Keyword cannot be cast to Number`. Heterogeneous operand values introduce further ordering requirements.

   **Fix:** Define a canonical record schema: exclude the hash itself and provenance; normalize segment-membership refs; specify admissible attributes, defaults, operand types, and a total ordering. Prefer explicit segment/instruction structure ordered by numeric pc, avoiding comparisons between unrelated value types. Pin the underlying byte codec before claiming heterogeneous stability; [DaoJing explicitly leaves this open](/Users/sto/workspace/datomworld/docs/design/dao.jing.md:390).

2. **P1 — Sorting can make different executable segments collide without any cryptographic collision.**  
   **Draft:** §7.3.2, [lines 123–142](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:123).

   Both validator and loader interpret repeated single-valued attributes using **last value wins** ([validator, line 46](/Users/sto/workspace/datomworld/src/cljc/yin/vm/code.cljc:46); [loader, line 512](/Users/sto/workspace/datomworld/src/cljc/yin/vm/semantic.cljc:512)). Two otherwise identical batches containing constant values `1, 2` versus `2, 1` therefore execute differently but sort to identical triples.

   I checked this with the actual `well-formed?`: both batches pass, their sorted triples match, and their indexed constant values are respectively `2` and `1`.

   **Fix:** Either reject duplicate semantic attributes in the UCF code profile or canonicalize the loader’s resolved attribute interpretation before sorting. Narrow “execute identically address identically”: removing provenance and replacing entity references does not normalize different instruction layouts, compiler-generated names, or equivalent instruction sequences.

3. **P1 — The proposed addresses do not address the payloads DaoJing would store.**  
   **Draft:** §7.3.4, [lines 186–198](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:186); §7.8, lines 592–594.

   A code address hashes canonical triples, but the publication/retrieval procedure discusses loading original segment batches. Publishing those batches to DaoJing stores them under their **own** payload hashes. Likewise, publishing the full UCF map stores it under `H(map-with-id)`, whereas `:yin.k/id` is `H(map-without-id)`. DaoJing does not supply domain-specific aliases: it hashes the exact payload and verifies that relationship ([materialization contract, line 162](/Users/sto/workspace/datomworld/docs/design/dao.jing.md:162); [segment-key, line 217](/Users/sto/workspace/datomworld/src/cljc/dao/jing.cljc:217)).

   **Fix:** Specify the exact addressed objects. Store the canonical code representation and define its conversion into loadable datoms; store the id-less continuation body and reconstruct its envelope after retrieval. Keep provenance/carriage outside those addressed bodies, or address them separately.

4. **P2 — The contract stamp and loader extension need explicit compatibility rules.**  
   **Draft:** §7.3.3–§7.3.4, [lines 153–185](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:153).

   A published immutable stamp is a sound mechanism, but its stated scope—opcode table and well-formedness—does not pin all execution semantics: resolution precedence, effect outcomes, call restoration, and scheduling also matter. Address lookup must distinguish interpretations under different stamps.

   Adding an address index can be backward compatible. Replacing the local-id conflict policy with a hash check cannot: two different, correctly hashed segments can still claim the same local id, redirecting existing frames. The existing [§3.1 rule](/Users/sto/workspace/datomworld/docs/design/yin.vm.semantic.md:292) and [store-image implementation](/Users/sto/workspace/datomworld/src/cljc/yin/vm/semantic.cljc:599) prohibit this.

   **Fix:** Version the complete execution contract, retain local-id immutability, and define a separate verified address/profile index. Explicitly update the reserved `:yin.code/hash` type from the contract’s string to the proposed keyword address.

5. **P1 — The safepoint table omits parking through `:call`, including tail calls.**  
   **Draft:** §7.4.1, [lines 213–223](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:213).

   A called host function can return a stream effect that blocks. This is explicitly part of [CESK call semantics](/Users/sto/workspace/datomworld/docs/design/yin.vm.semantic.md:463) and implemented in [apply-call](/Users/sto/workspace/datomworld/src/cljc/yin/vm/semantic.cljc:206). Both ordinary and tail calls reach that path. Their parked successor is absent from the purportedly complete table.

   **Fix:** Include effect-producing call safepoints, with their post-pop stacks and pending operation variants. Conservatively mark all call sites unless a declared callable contract proves they cannot park.

6. **P1 — Absolute stack depth and environment keys are not functions of the canonical segment alone.**  
   **Draft:** §7.4.2, [lines 249–280](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:249).

   A closure enters with the caller’s residual operand stack and an environment formed from its captured environment plus arguments ([apply-call, lines 199–205](/Users/sto/workspace/datomworld/src/cljc/yin/vm/semantic.cljc:199)). The same code pc can therefore have different absolute stack depths and environment keys. Moreover, §2.6 does not enforce equal stack heights at joins. Reserved lexical addressing does not establish these properties today ([§6.4](/Users/sto/workspace/datomworld/docs/design/yin.vm.semantic.md:661)).

   **Fix:** Separate static local stack effects and lexical requirements from dynamic activation bases, captured environments, and physical layout metadata. Require engine-generated reconstruction recipes with runtime context. A lowering that inserts a new semantic `:park` must produce newly identified canonical code; it cannot silently add safepoints to the old address.

7. **P1 — Portable tags collide with ordinary program maps.**  
   **Draft:** §7.5.1–§7.5.2, [lines 324–365](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:324).

   Ordinary maps retain their shape, while encoded primitives and references use ordinary maps such as `{:yin.k/primitive '+}` and `{:yin.k/ref addr}`. A literal program map with either shape is indistinguishable from the encoding marker during decoding. Qualified namespaces prevent accidental vocabulary overlap; they do not make arbitrary literal data unambiguous. The semantic contract admits map literals ([§2.5](/Users/sto/workspace/datomworld/docs/design/yin.vm.semantic.md:218)).

   **Fix:** Define a disjoint tagged value grammar with an explicit literal-map encoding or escaping rule. Specify recursive treatment of map keys and all continuation variants. Validate reference-table structure on decode, including missing references and cycles introduced through reference data.

8. **P1 — Primitive object identity recovers a local name, not portable semantics.**  
   **Draft:** §7.5.1, [lines 336–344](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:336); §7.11, lines 671–673.

   Identity lookup is locally reasonable, but one function can occur under several names, making reverse selection ambiguous. More seriously, two composition-supplied primitive maps can bind the same symbol to different functions. Name presence does not establish “the same one.” Identity stability also says nothing about purity or captured mutable host state. The VM explicitly receives its primitive bindings as composition data ([resolution implementation](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:46)).

   **Fix:** Require a canonical primitive identifier and immutable semantic profile covering argument/result behavior, effects, and portability constraints. Define alias selection or reject ambiguous registrations. Carry those profiles in requirements; reject functions whose semantics depend on undeclared host state.

9. **P1 — Encoding-dependent identity is unsafe as execution identity.**  
   **Draft:** §7.3.4, [lines 194–198](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:194); §7.5.2, [lines 362–378](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:362).

   Thresholds, host-identity sharing, and optional carried code can produce different UCF maps for the same parked computation. Their hashes legitimately differ. That is harmless for decoding immutable values, but not when leases use those hashes as the exclusive subject: two encodings can receive independent grants. Conversely, equal snapshots need not represent the same execution occurrence.

   **Fix:** Separate snapshot content identity from a durable computation/checkpoint occurrence identity. Bind authorized snapshot variants to that occurrence. Retrying publication must reuse the same encoded value, or use a pinned encoding policy; neither should create a second runnable occurrence.

10. **P1 — Dependency discovery omits explicit reads and cannot provide the claimed exact closure.**  
    **Draft:** §7.6.1–§7.6.4, [lines 419–475](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:419).

    The store rule includes `:var` and resource refs but misses `:store-get` entirely, despite its direct store access ([implementation, line 292](/Users/sto/workspace/datomworld/src/cljc/yin/vm/semantic.cljc:292)). Looking at the current `E` also cannot determine bindings in other closure activations. Called primitives/modules can construct effects or access dynamically selected keys; `:call` datoms do not disclose these dependencies. A missing segment prevents discovery of dependencies inside it, contradicting “everything missing in one pass.”

    **Fix:** Define a conservative fixed-point traversal over frame values, store values, code, pending operations, and manifests. Include explicit store operands and per-callable declared footprints. Distinguish incomplete discovery from a satisfied closure, and refuse or require explicit context for dependencies that cannot be bounded. The module-footprint open item is only one instance of this problem.

11. **P1 — Merging the destination store changes resolution even without a collision.**  
    **Draft:** §7.6.2, [lines 444–447](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:444); §7.8, line 607.

    Suppose the source resolves `+` from primitives because its store lacks `+`. The receiver’s store contains `+`. Merging the slice over that store leaves the receiver’s binding in place; execution now resolves different code because precedence is env → store → primitives → modules ([resolve-var](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:46)). Absent `:store-get` keys can similarly become present. Overwriting a colliding cursor/store entry can also disrupt existing receiver tasks.

    **Fix:** Restore into an isolated execution store with explicit imported services and absence semantics. Define remapping for resource identities and prohibit accidental sharing with unrelated tasks. Also resolve the direct contradiction: §7.6 says slice wins, while lower step 6 says merge the slice **under** the local store.

12. **P1 — Scheduler identities and resumable dependencies are missing from the transported state.**  
    **Draft:** §7.4.1, [lines 225–240](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:225); §7.6.2, lines 429–447.

    The reference machine’s fresh identifiers depend on `:id-counter`; parked continuations reside in `:parked`, outside the store. `:resume` resolves its operand there. Neither state component has a transport rule. A fresh receiver can reuse an existing generated identifier or fail to resolve a parked continuation referenced by code. See [park/resume and gensym](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:381) and the [scheduler contract](/Users/sto/workspace/datomworld/docs/design/yin.vm.semantic.md:374).

    **Fix:** Specify the execution’s fresh-name state or an explicit scoped replacement, including remapping rules. Declare and carry referenced parked configurations, or explicitly refuse migrations requiring them. State whether the migration unit is one task or an isolated scheduler context.

13. **P1 — Pending operations lack enough state for equivalent resumption.**  
    **Draft:** §7.4.3, [lines 293–311](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:293); §7.6.2, lines 440–442.

    There is no blocked-write schema carrying the retained value, although retry requires it ([engine, line 435](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:435)). The sent-FFI example carries the response descriptor but no response cursor. The unsent request carries neither endpoint nor response cursor. Reattaching at oldest can replay another response; attaching at newest can skip this response.

    The reference implementation also restores `:request-sent` into a response wait using fixed local FFI keys ([semantic-restore](/Users/sto/workspace/datomworld/src/cljc/yin/vm/semantic.cljc:111)). Keeping the destination pair does not route that outstanding call back to the source pair.

    **Fix:** Define exhaustive pending variants: blocked write with retained payload and target; sent FFI with correlation id, response endpoint and kept cursor; unsent FFI with retained request plus both routing endpoints and response position. Pending-call routing must remain separate from the receiver’s pair for future calls. Require a transport-supported portable cursor profile; [cross-host cursor serialization remains TBD](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:517).

14. **P1 — Cursor encoding can erase observable aliasing.**  
    **Draft:** §7.5.1–§7.5.2, [lines 332–369](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:332).

    Two distinct cursor refs can point to the same stream position while remaining independently advanceable store entries. The proposed position-based encoding makes them equal. Conversely, repeated uses of one cursor ref must retain their shared advancement behavior. This distinction is explicitly observable: [check-wait-set advances shared cursor refs before polling subsequent waiters](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:290).

    **Fix:** Preserve explicit logical cursor-cell identities and ref-to-cell relationships separately from immutable cursor-position content. Specify reconstruction and remapping of that graph. Content deduplication alone cannot preserve store aliasing.

15. **P1 — The grantor’s authority is asserted, not grounded in a possessed resource.**  
    **Draft:** §7.7.2–§7.7.3, [lines 499–532](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:499).

    DaoLease requires the judge to reclaim something its boundary actually possesses; otherwise the judge is outside its contract ([lines 99–103](/Users/sto/workspace/datomworld/docs/design/dao.lease.md:99)). Possessing an offer or a copy of content does not establish exclusive control of execution. Designating an arbiter “for the carrier medium” also permits the same content carried on another medium to acquire a different arbiter. Generic leases themselves do not impose one holder per subject.

    **Fix:** Ground custody in an authoritative execution record or execution/effect admission resource controlled by one identified authority. Bind the execution occurrence to that authority independently of its carriers. Define exclusive-grant policy, the concrete reclaim act, and the durable authority-incarnation/fencing rules DaoLease requires ([composition duties, line 216](/Users/sto/workspace/datomworld/docs/design/dao.lease.md:216)).

16. **P1 — Emitter-as-candidate does not close the publication/wakeup window.**  
    **Draft:** §7.7.3, [lines 526–532](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:526); §7.8, [lines 587–597](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:587).

    The lifecycle publishes first and makes the source a candidate afterward. It never defines a transition that disables local wait polling, ready-queue restoration, direct `:resume`, and blocked-write retries **before** publication. Polling a blocked writer already performs the append; delaying register restoration is too late ([runtime, line 173](/Users/sto/workspace/datomworld/src/cljc/dao/runtime.cljc:173)).

    **Fix:** Introduce an explicit exporting state before publication that removes all local execution/retry eligibility while retaining recovery data. Specify publication and offer failure recovery, idempotent retries, and the sole grant-authorized path back into runnable state. Update §7.10’s claim that execution machinery is untouched accordingly.

17. **P1 — Lapse-record filtering provides neither fencing nor exactly-once effects.**  
    **Draft:** §7.7.4–§7.7.6, [lines 545–580](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:545).

    DaoLease explicitly states that absence of `:lapsed` is never evidence of tenure ([line 157](/Users/sto/workspace/datomworld/docs/design/dao.lease.md:157)). A partitioned consumer can accept stale effects; a delayed legitimate effect can be discarded after release. Repeated effects within one valid lease all share the same incarnation and remain indistinguishable. A replacement holder may repeat an effect already committed by its predecessor. Apply ids provide correlation, not duplicate suppression ([request/response constructors](/Users/sto/workspace/datomworld/src/cljc/dao/stream/apply.cljc:42)).

    Successor publication, holder report, and release also lack a durable commit rule. A crash between them can orphan a successor or permit replay. Restart cannot treat an explicitly non-authoritative holder report as proof that an execution is durably complete.

    **Fix:** Require resource-enforced authority epochs checked atomically with effect commitment, plus stable operation ids and durable deduplication/result records. Define checkpoint completion as an authoritative transition. State the partition policy: unavailable authority/evidence must suspend protected effects, or the guarantee must weaken. Permanent grantor loss and failover require a defined recovery protocol; DaoLease promises no bounded answer to proposals ([line 69](/Users/sto/workspace/datomworld/docs/design/dao.lease.md:69)).

18. **P2 — Outcome and wire vocabularies are incomplete or inconsistent with their dependencies.**  
    **Draft:** §7.4.3, [lines 299–304](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:299); §7.9, [lines 621–637](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:621).

    The pending request uses `:dao.stream.apply/*`, but the reference machine imports `dao.stream.apply`, whose request and success/error response vocabulary differs ([semantic import](/Users/sto/workspace/datomworld/src/cljc/yin/vm/semantic.cljc:33); [v2 request construction](/Users/sto/workspace/datomworld/src/cljc/dao/stream/apply.cljc:31)). DaoStream results dispatch on `:dao.stream/outcome`, not the proposed `:dao.stream/status` ([contract, line 103](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:103)).

    The closed UCF algebra lacks specified outcomes for malformed frames/references, unsupported value encodings, pending arbitration/backpressure, and attachment failure after acquiring custody. Cycle refusal has two competing representations. `:yin.k/result` has no defined schema. The lease table also omits dispatch keys and required grant fields while claiming unchanged reuse.

    **Fix:** Publish exact schemas and per-operation outcome tables, preserve nested DaoStream outcomes unchanged, use the reference machine’s versioned FFI envelope, and define cleanup/release behavior for every lower failure. Include malformed-but-correctly-hashed inputs: a hash check is not structural validation.

19. **P3 — The compliance and open-item claims overstate what is established.**  
    **Draft:** [line 29](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:29); §7.10–§7.11, [lines 639–684](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:639).

    The introduction points to §7.10 for open items; they are in §7.11. More substantively, “every dependency is declared,” “step loop is untouched,” and unrestricted resumption wherever requirements are held are not demonstrated. They depend on unresolved state closure, custody enforcement, and effect admission. These are architectural obligations under the [governing invariants](/Users/sto/workspace/datomworld/docs/design/datom.world.md:21), not merely missing conformance tests.

    **Fix:** Correct the cross-reference and mark each compliance claim conditional on named unresolved design obligations. Move authority grounding, execution identity, pending-state completeness, and enforceable fencing into acceptance blockers.

**Verified sound**

- **`pc+1` is valid for the listed resumable opcodes.** §2.6 requires the final pc to be `:jump`, `:return`, or `:halt`; the listed parking/reification instructions therefore have a successor ([contract, line 230](/Users/sto/workspace/datomworld/docs/design/yin.vm.semantic.md:230)). The missing effect-producing calls also resume at `pc+1`.
- **A successful blocked-write retry resumes with the written value.** The draft’s accumulator entry is correct ([runtime, line 142](/Users/sto/workspace/datomworld/src/cljc/dao/runtime.cljc:142)).
- **Descriptor attachment without implicit creation is correct.** `not-found` must remain explicit. Keeping a valid cursor and observing `gap` is also correct; the observer already supports attachment with a kept cursor ([observer, line 64](/Users/sto/workspace/datomworld/src/cljc/dao/stream/observer.cljc:64)).
- **Whole-value refusal and cycle rejection are appropriate restrictions.** They avoid silently substituting missing state. They need an unambiguous wire grammar and decoder validation, rather than cycle support.
- **Keeping custody above DaoStream is the correct layering.** Streams provide neither destructive take nor exclusion ([Explicitly Absent, line 767](/Users/sto/workspace/datomworld/docs/design/dao.stream.md:767)). A properly grounded domain arbiter can use lease facts without extending stream operations.
- **No pre-existing `:yin.k/*` or `:yin.safepoint/*` use surfaced in the searched source/design files.** The concrete vocabulary conflicts are the wire-tag ambiguity and existing DaoStream/FFI conventions identified above.

**Assessment of the five blockers**

None of the five blockers is fully closed. Code identity is partly relocated to DaoJing’s acknowledged codec work, but also contains independent canonicalization and storage-address defects. Safepoints identify useful boundaries while omitting effectful calls and assuming static reconstruction information the machine does not guarantee. Portable encoding establishes a reasonable refusal discipline but leaves semantic naming, tag disambiguation, and observable aliasing unresolved. Dependency closure remains incomplete for explicit store operations, scheduler state, dynamic callables, and outstanding FFI work. Ownership is the furthest from closure: lease vocabulary records a negotiation, but the draft has not established the possessed resource, authoritative execution identity, atomic handoff, or consumer-enforced commitment that would make that negotiation exclude duplicate execution.

I’ll resolve the remaining stale wording, add the second-VM benefit and benchmark exit gate, and make the lossless DAG an explicit owner decision rather than an unconsumed B0 dependency.
Completed-GMT: 2026-09-21 18:53:42 GMT  
Completed-Local: 2026-09-22 01:53:42 +07  
Coding-Agent: codex  
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a

SIGN-OFF: READY for owner decisions and design-document commit; B0 implementation remains blocked by the explicitly listed owner decisions.

## Disposition table

```text
Finding                         Status       Disposition
Opus P2-A stale lossless text   ACCEPTED     Executable images, not lossless;
                                             corrected B2, B5, §7, §7.2, §1, §3.
Opus P2-B host-byte wording     ACCEPTED     JVM/Dart-only special scalar tests;
                                             common-domain cross-host bytes.
Opus P3-A scalar omissions      ACCEPTED     Added nil, booleans, vectors, lists,
                                             maps, sets, and ordering rules.
Opus P3-B lifting wording       ACCEPTED     Lifting/ANF are upstream AST stages.
Opus P3-C synthesized tails     ACCEPTED     Upstream stages must write :yin/tail?.

Fable P1-1 unused source DAG    OWNER        Optional §7.3 view, removed from B0
                                             critical path; owner decides retain
                                             or drop it. Dropping gives up only
                                             a separately persisted inverse view,
                                             not execution fidelity.
Fable P2-1 missing benefit      ACCEPTED     Added benefit, B3 benchmark gate,
                                             and end-state owner decision.
Fable P2-2 binder names hash    ACCEPTED     Binder names are diagnostic only;
                                             free names remain hashed operands.
Fable P2-3 dimension overclaim  ACCEPTED     Defines/exports descriptor; declares
                                             arity, slots, encoding, and lift.
Fable P2-4 UCF fork             ACCEPTED     B4 requires frame lift or a
                                             frame-aware completion adapter;
                                             continuation interchange is not
                                             promised.
Fable P2-5 loader callback      ACCEPTED     VM accepts loaded images as values;
                                             absence parks and emits a request.
Fable P2-6 meaning of H         ACCEPTED     Added B6 owner decision covering
                                             image, definition, SCC, and stamp.
Fable P3 artifact count         ACCEPTED     Three artifacts and Architecture A
                                             are explicitly defined.
Fable P3 side-table ambiguity   ACCEPTED     Binder names/provenance only;
                                             scalar bytes remain in :const.
Fable P3 private helpers        ACCEPTED     Rules are tied to image layout;
                                             no private projection access.
Fable P3 opcode derivation      ACCEPTED     Table is derived from vector-
                                             operand-table data.
Fable P3 VM state omission      ACCEPTED     Parked records, gensym, modules,
                                             primitives, aliases, and registers
                                             are explicit.
Fable P3 environment method     ACCEPTED     IVMState/environment is not
                                             implemented before a named lift.
Fable P3 cache ambiguity        ACCEPTED     No cache is specified; a future
                                             cache uses the image hash as value.
Fable P3 B6 placement           ACCEPTED     B6 was removed from §6; §7.2 owns it.
Fable Unison scalar observation ACCEPTED     Design no longer relies on that
                                             unverified observation.
```

No finding was rejected outright. The proposed permanent one-dimension VM
restructure was rejected for now: a sibling VM is the reversible experimental
shape, while retirement or default selection is now an explicit benchmark-based
owner decision.

## Sections changed

- §1: artifact decomposition, optional DAG, benefit and exit criterion.
- §2: descriptor, lift, binder-name hashing, scalar classes, canonical image.
- §3 and §4: scope layout, state, continuation lifting, and protocol boundary.
- §6: B0, B1, B2, B3, B4, and removal of B6 from the phase list.
- §7: test and non-goal wording.
- §7.2: transport-neutral linker requirements.
- §7.3: optional lossless source view.
- §8: owner decisions and B0/B6 ranking.

## Rejected findings

None were rejected outright. The only structural proposal not adopted immediately
is replacing the sibling VM with one permanent shared VM. That is deferred until
the B3 benchmark gate and remains an owner decision.

## Remaining owner decisions ranked by B0 impact

1. B0 blocker: exact result, error, closure, continuation, stream, cursor, and
   store normalizer.
2. B0 decision: retain or drop the optional lossless source DAG. If retained,
   choose the stable identity replacing emitter tempids and the treatment of
   non-`:yin` attributes.
3. B1 decision: approve the executable scalar domain and
   `:yin.debruijn.code/*` descriptor and lift.
4. B5 decision: fix the named VM environment leak or retain the stated
   cross-program park/resume restriction.
5. B6 decision: define what `:call-hash H` identifies, including descriptor or
   contract-stamp inclusion.
6. B6 decision: authoritative ledger, trust/provenance, SCC identity, and
   retry, timeout, and permanent-absence policy.
7. Post-B3 decision: retire one VM or make the frame VM the default.

## Top three risks

1. The optional source DAG could become an unconsumed fourth identity artifact
   unless the owner explicitly keeps it separate from the VM path.
2. Continuations may become incompatible with UCF or under-approximate dependency
   closure unless B4 performs the required frame lift or completion analysis.
3. Maintaining two VMs may cost more than it returns; the B3 benchmark gate and
   owner end-state decision are therefore essential.

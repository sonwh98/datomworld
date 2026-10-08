Completed-GMT: 2026-09-21 21:09:53 GMT
Completed-Local: 2026-09-22 04:09:53 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: 50b423bc-219f-40c0-8e56-2b6d9c3d9658

# Verdict: SOUND WITH CHANGES

The committed design fixes the P1 and every P2 from my first review except P2-1, which is partly fixed; three P3s remain open. No new finding is a P1, and nothing blocks B0.

Six new P2s all concern the path that shares an image by its hash H: the free-name boundary, cross-host byte identity, the trust status of the side table, the serving side, H stability, and phase ordering. All of them need fixing before B6. N2 and N5 should be settled before B1 freezes `image-hash`.

I read only the committed `docs/design/yin.vm.debruijn-vm.md`; no code was checked. As in round 1, plan mode expects a plan file and an exit call, but the brief says findings only and final response now, so I followed the brief and edited nothing.

## Confirmation of first-review findings

    +-------+--------------+-----------------------------------------------------------+
    | Id    | Status       | Evidence (quoted)                                         |
    +-------+--------------+-----------------------------------------------------------+
    | P1-1  | FIXED        | "D2 lossless DAG: drop it from B0 through B7 and make it  |
    |       |              | a non-goal." §7.3; B0 is now "contract and normalizer".   |
    | P2-1  | PARTLY FIXED | "The primary benefit is network-shareable, alpha-         |
    |       |              | invariant, content-addressed executable code". That is    |
    |       |              | the image's benefit, not the frame VM's (see N1). The     |
    |       |              | gate is "informational, not an acceptance condition";     |
    |       |              | D7 decides permanent coexistence knowingly.               |
    | P2-2  | FIXED        | "H is alpha-invariant for binder renames because binder   |
    |       |              | names are outside the hash."                              |
    | P2-3  | FIXED        | "defines and exports its descriptor; persistent           |
    |       |              | publication and discovery are outside B0 through B7";     |
    |       |              | "declares arity, ordered slots, canonical encoding, and   |
    |       |              | one lift morphism"; "typed raw `Bytes`".                  |
    | P2-4  | FIXED        | "B4 must either lift frames through the descriptor        |
    |       |              | morphism or run the frame-aware completion adapter";      |
    |       |              | not-promised: "interchangeability of continuations        |
    |       |              | between the two VMs". (The lift's input needs N3.)        |
    | P2-5  | FIXED        | "The VM accepts loaded images only as values in its state |
    |       |              | and never invokes a loader".                              |
    | P2-6  | FIXED        | D5: "`:call-hash H` names the canonical executable image  |
    |       |              | hash of a definition unit".                               |
    | P3 two-vs-three artifacts; "architecture B" with no A | FIXED     | "Three   |
    |       |              | artifacts…"; "Architecture A means lowering from…"        |
    | P3 spelling side table | FIXED | "Exact scalar bytes are in the `:const` operands" |
    | P3 "decode round trips" | FIXED | "image encode/validate/load round trips"        |
    | P3 private helpers | NOT FIXED | still "rules are reproduced or exposed" beside   |
    |       |              | "Existing edits: none".                                   |
    | P3 opcode table | PARTLY  | §2 "derives its opcode table as data"; B1 still says    |
    |       |              | "mirrored opcode table".                                  |
    | P3 state map | PARTLY     | The park sentence lists the registers; the §4 state     |
    |       |              | map still shows 8 keys.                                   |
    | P3 `environment` | FIXED  | "does not implement `vm/IVMState/environment` until…"   |
    | P3 cache | FIXED          | "No cache is specified by B0 through B5"                |
    | P3 B6 placement | FIXED   | B6 is a committed phase; 7.2 retitled.                  |
    | P3 double folding | MOOT  | D12: the projection is off the identity path.           |
    +-------+--------------+-----------------------------------------------------------+

## New findings

### N1 (P2). The frame VM is not needed for invariant I, but B6 is sequenced behind it
- **Where:** §1 Benefit and the table row for invariant I.
- **Quote:** "B0-B5 provide canonical hash, verified loading and stream transfer".

Invariant I needs only B1 (H), B2 (lowering), the linker, and the declared lift. A receiver can run a fetched image as `lift(image, synthesized names)` on the existing semantic VM. B3 and B4 duplicate every effect and park rule, and they are the expensive phases, yet they sit in front of the owner's goal.

**Failing scenario:** B4 slips on park/resume parity, and code sharing slips with it for no architectural reason.

**Smallest fix:**
- State that B6 depends on B1, B2 and the lift, and not on B3 or B4.
- Allow B6 to complete against the semantic VM via the lift.
- In "Benefit", say that the frame VM's own benefit is lookup performance only and is unmeasured.

### N2 (P2). "Closed image" is undefined with respect to primitives and the receiver's resolve order
- **Where:** §1 and D11.
- **Quote:** "a closed image is derivable by scanning its operands"
- **Quote:** "B6 may refuse a non-closed image".

`:load-free` resolves through "`free-env`, store, primitives, and module registry". That means `+` is a free name, and an operand scan calls almost every useful program non-closed.

**Failing scenario:** the receiver's store or free environment holds a key equal to a primitive's name. The image has the same H, verification succeeds, and the code means something different.

**Smallest fix:**
- Define closed as "every `:load-free` operand resolves in the receiver's primitive or module table, and none is shadowed by free-env or store".
- Change "may refuse" to "refuses unless".
- The required-name set is derived by scan, so no manifest is needed.

### N3 (P2). The side table is outside H, so it is unverified, yet the lift consumes it
- **Where:** B2, B4, and §2.
- **Quote:** "the lift uses diagnostic binder names or synthesized names".

**Failing scenario:** a response carries a side table that names a binder `foo`, where the body has `:load-free foo`. The lifted code captures the free name. A completion run or execution through the lift (B4, N1) then changes meaning under a valid H.

**Smallest fix:**
- Any lift whose output is executed or fed to completion uses synthesized names that are fresh against the image's free-name set.
- Alternatively, accept a supplied table only if `adapt(lift(image, table)) = image`.
- Also fix B2's garbled sentence: the first "With synthesized names, the equality holds…" should read "With the side table…".

### N4 (P2). Verifying by recomputation conflicts with CLJS scalar classification
- **Where:** §2 and B5/B6.
- **Quote:** "a JavaScript number is encoded as a long when it is a safe integer"
- **Quote:** "recomputes `image-hash`".

**Failing scenario:**
- The JVM publishes `(fn [] 1.0)`, encoded with the double tag.
- A CLJS receiver decodes it to the number 1 and re-encodes it with the long tag. H mismatches and the image is falsely refused.
- The same source lowered on CLJS also produces a different H, which breaks B5's "same H on every host lane".

**Smallest fix:**
- Define verification as hashing the received canonical bytes before decoding. The wire bytes are then exactly H's preimage, and the design should say so explicitly.
- Declare integral-valued doubles outside the common domain for CLJS: either refuse them on receipt or accept them under a named caveat.

### N5 (P2). H silently depends on `lower`'s layout
- **Where:** §3.
- **Quote:** "The image inherits occurrence expansion, fresh lambda labels, and body layout from `yin.vm.linearize/lower`".

Once H values are shared, any later change to the linearizer rehashes all shared code, because body order changes the resolved pcs. "Must not change" binds only this design's own phases.

**Smallest fix:**
- The descriptor carries a lowering-contract version.
- B1 pins golden H fixtures, so a layout change fails a test rather than forking identity.
- Also settle §2 "descriptor hash" against D9 "descriptor bytes". They give different H, and only one can be right.

### N6 (P2). The serving side of B6 is unspecified
- **Where:** 7.2.
- **Quote:** "`jing/segment-key` is only the storage address… and is not H".

A responder needs a lookup from H to bytes. With no cache and no registry, it has no stated home.

**Smallest fix:**
- State that the responder holds an explicit value mapping H to bytes, or an H-to-address datom that it queries.
- Include the responder process in B6's file box.

### P3
- **§3:** "The projected reader's existing tuple-shape check remains useful" is stale, because no projected reader is on this path.
- **§7.1:** "name-table entries" should read "side table".
- **B6:** it mentions `:call-hash`, but nothing emits one before B7. Say that B6 tests use explicit fetch.
- **B0:** with no second VM yet, its completion criteria can only be self-parity and idempotence of the normalizer on the named VM. Say so.
- **B5/B6:** cross-runtime "stream transfer" needs a cross-process fixture. State whether committed golden bytes satisfy B5.

## Item 2: the end-to-end scenario
- **A lowers the program:** B2.
- **A computes H:** B1.
- **A publishes:** this is a gap (N6). The serving index and the responder are unspecified.
- **B knows only H and sends a REQUEST over a stream:** B6. The request schema is deferred, which is acceptable.
- **B verifies:** B6 with B1, with the N4 hazard on CLJS and whenever verification decodes before hashing.
- **B refuses unsupported classes:** B1 and D10, by tag scan, before execution. This part is clean.
- **Closedness check:** D11, but it is mis-defined (N2).
- **B runs the image:** B3 and B4 as written. It could run earlier through the lift (N1).

With N2, N4 and N6 fixed, the design delivers the invariant for closed images.

Two limits remain:
- Dependency sharing arrives only with B7.
- H is whole-image only, so there is no subterm deduplication.

## Item 3: the dormant projection, with H as the only identity
This is sound. Given D5 and D9, H already covers binder alpha-invariance, and a second identity on the sharing path would only create ambiguity.

What is lost:
- Coarser deduplication: NFC normalisation and insensitivity to scalar spelling and tail flags.
- Per-node Merkle hash-consing, because H is a whole-unit hash.
- An alpha-canonical d5 view that `dao.space` could query.

§7.3 correctly says that any future coarser identity must be newly derived.

The `resolve-name` dependency is handled honestly: "Dormant means kept, not removed", with a move-first rule if the projection is ever retired. The real cost is that one helper keeps the whole namespace, including its NFC dependency, in the B2 build on every host. Moving the helper now would be a small, honest edit, but it is not required.

No statement still treats the fingerprint as load-bearing. The only residue is the stale §3 sentence noted above, plus the "UTF-16 guard" and "binder spelling in the dormant projection" mentions, which are harmless comparisons.

## Item 4: surprises and top risks
Nothing blocks B0.

What would surprise the owner:
- Closed-only sharing excludes anything that uses a primitive, unless N2 is fixed.
- The sibling VM is not what delivers invariant I (N1).
- `1.0` does not survive a CLJS host (N4).
- Equal source can hash differently when the front-end tail flags differ. The design admits this and calls H "intentionally incomplete".

Top three risks:
1. Same H, different meaning, caused by receiver-side resolution order or an unverified side table (N2, N3). This is the one way the invariant can fail silently.
2. H stability. Identity is coupled to `lower`'s layout, the descriptor encoding, and a deferred contract stamp, with no pinned fixtures (N5).
3. Permanent double maintenance under D7. The costly half, B3 and B4, sits on the critical path of a goal it does not serve (N1).

## Checked and found clean
- D2 and §7.3.
- Binder names and provenance are outside H, and free names are inside it.
- "Defines and exports" wording, raw-`Bytes` slots, and the declared lift.
- `image-hash` is a single function, and the Jing address is not H.
- Tag-derived scalar-class refusal is a host-boundary outcome.
- There is no loader invocation. Absence is a park plus a request emission. Gaps, timeouts and absence are stream events.
- D4's exclusion of the environment leak cites code.
- The projection namespace is untouched and has no new dependents.
- Scope is validated at both image entry points.
- The Unison hedging is intact.
- The compliance table lists its own strained items honestly.

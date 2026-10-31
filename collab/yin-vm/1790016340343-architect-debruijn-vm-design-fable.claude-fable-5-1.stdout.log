Completed-GMT: 2026-09-21 18:48:20 GMT
Completed-Local: 2026-09-22 01:48:20 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: 50b423bc-219f-40c0-8e56-2b6d9c3d9658

# Verdict: SOUND WITH CHANGES

Choosing B is right. Executing a form derived from the full-information named AST, and keeping the projection as identity only, is the correct call. The design changes no existing contract, and every phase is new files only.

Three things need fixing before implementation:

- One artifact in the decomposition has no consumer (P1-1).
- The benefit of a second VM is never stated (P2-1).
- B1 freezes the image identity in a way that works against the 7.2 linker (P2-2).

Plan mode asked for a plan file and an exit call. The brief says findings only, edit no file, and final response now, so I followed the brief: I edited nothing and wrote no plan file.

## Findings

### P1-1. The lossless record DAG has no consumer, yet it blocks B0
- **Where:** §1 and B0.
- **Quote:** "The B artifact is an encoding `encode-db(ast-datoms)`…"
- **Quote (B0):** "Define `encode-db` and `decode-db` for the lossless record DAG keyed by `[source-eid name-stack]`."

Nothing in B1 through B5 reads `encode-db` output:

- B2 "receives complete `:yin/*` named datoms".
- B2 runs `linearize/lower` on the named datoms.
- B5's pipeline is `ast->datoms` -> B2 -> VM.

The DAG is an isomorphic copy of the named datoms. Its one added column, bound positions, is derivable by the public `resolve-name`. That is the "derive, don't persist" case: a second structure with authority over facts the source already holds.

It is also expensive. B0 blockers 1 (tempid identity) and 3 (non-`:yin` attributes) exist only because of this inverse.

The owner's original wish, a de Bruijn form bijective with the named form, is satisfiable only by carrying the name table. At that point the artifact is the named AST plus a derived column.

**Smallest fix:**
- Cut `encode-db`/`decode-db` from B0 and from the VM's critical path.
- B0 keeps only the normalizer and the frozen parity corpus.
- If the owner still wants the view, make it an optional, separately reviewed phase, described as a derived view with no downstream dependency.
- Owner decisions 1 and 3 then stop blocking.

### P2-1. The design never states why a second VM should exist
There is no motivation section, no benchmark target, and no success criterion.

`yin.vm.semantic.md` §8 sets the project precedent: measure, then make a "default decision". The workflow rule is "do not optimize prematurely".

The honest benefit is one item: positional frame lookup replaces name-keyed environment lookup, and alpha-invariant executable code becomes possible. The design undoes that second benefit (see P2-2). The third candidate benefit, the path to hash linking, is B6 and does not need a frame VM.

**Smallest fix:**
- Add a short "Benefit and exit criterion" paragraph naming the measured quantity.
- Add a benchmark gate after B3, using the same harness as semantic §8.
- Add an owner decision on the end state: retire one VM, or make frames the default.

### P2-2. Image identity includes binder names, which defeats de Bruijn addressing
- **Where:** §2.
- **Quote:** "…no header, descriptor hash, exact scalar bytes, and the spelling/name table."

Free names are semantic, because they are `:load-free` operands, and they belong in the hash. Binder names are not semantic. With binder names hashed:

- `(fn [x] x)` and `(fn [y] y)` get different executable hashes.
- The new hash then partitions programs exactly as the existing `jing/segment-key` over `:yin.code/*` does.
- A fourth identity is added with no new equivalence.
- If 7.2's `H` is this hash, renaming a local in a dependency rehashes every dependent.

**Smallest fix:**
- Binder names go in the pc-indexed diagnostic side table with provenance, outside the hash.
- Free names stay as hashed operands.

### P2-3. Overclaim of "published", and the descriptor is underspecified
- **Where:** §2 and B1.
- **Quote:** "The executable image uses a new published `:yin.debruijn.code/*` dimension"
- **Quote (B1):** "Publish the descriptor"

This repeats the wording the projection doc had to correct to "defines and exports… Persistent publication or discovery… is outside D0–D6".

A mirrored opcode table is also not a `datom.md` descriptor. The design names none of `:dim/arity`, `:dim/slots`, `:dim/encoding`, `:dim/projection-to` or `:dim/lift-from`.

`datom.md` fixes strings as "UTF-8 NFC", while this design hashes un-normalized bytes.

**Smallest fix:**
- Say "defines and exports; publication is outside B0–B6".
- Declare exact-spelling slots as type `Bytes` (raw, length-prefixed), which `datom.md` already allows, so the no-NFC rule is a slot-type choice and not an exception.
- Declare one morphism: a lift from `:yin.debruijn.code/*` to `:yin.code/*`, using binder names from the side table or synthesized defaults.

The lift also gives B2 a stronger acceptance test than "structural opcode-by-opcode comparison": `lift(adapt(lower x)) = lower x`.

### P2-4. A second continuation shape forks UCF's canonical state
- **Where:** §4.
- **Quote:** "Park and resume carry the frame stack, free environment…"
- **Quote (7.2):** "the completion adapter is not yet a de Bruijn adapter"

UCF proposes `{segment pc env stack k}` as the canonical exchange form. `yin.vm.completion` computes `:yin.k/requires` over named environments.

Frames that hold closures, handles or parked records are invisible to it. B4 park/resume would therefore ship continuations whose dependency closure is under-approximated.

**Smallest fix:**
- State that park lifts frames to a named env through the P2-3 morphism, reusing completion and UCF unchanged, or that B4 includes a frame-aware completion adapter.
- Add "continuations are not interchangeable between the two VMs" to §1's not-promised list until one of those is done.

### P2-5. "Injectable loader" opens the door to a callback
- **Where:** 7.2.
- **Quote:** "B5 must keep the loader injectable and must not hide fetches in the VM."

An injected function that may later fetch is a relocated callback.

**Smallest fix:** replace the sentence with "The VM accepts loaded images only as values in its state; it never invokes a loader. Absence is a park plus a request emission."

### P2-6. What `:call-hash H` denotes is an owner decision the design does not list
The projection fingerprint cannot be `H`. §2 itself says equal fingerprints may have different executable behaviour.

**Smallest fix:**
- Add to decision 6: "what `H` hashes (executable image, named definition, or SCC component) and whether the descriptor or contract stamp is inside it".
- Decide this before B1 freezes the image hash (see P2-2).

### P3
- **§1:** "Two artifacts are produced from one named AST", then three are listed. "Architecture B" is used without an A ever being defined.
- **§2:** "Exact named literal spelling is carried in a canonical side table…" Datoms carry values, not lexemes, and the exact scalar encoder already fixes the bytes. Delete the side table, or say what it holds that the `:const` operand does not.
- **§1 vs B2:** the image "is not itself invertible", yet B2 requires "image decode round trips". Rename to "byte encode/decode round trip".
- **B2:** "`closure-ranges` and `layout-conforms?` are private helpers whose rules are reproduced or exposed" contradicts "Existing edits: none". Reproducing them creates an untied second copy of `lower`'s layout law. Choose "exposed" as a declared one-line visibility edit, or add a test that ties the copy to the private var.
- **§2:** "mirrors the existing `yin.vm.code/vector-operand-table`". Derive the table as data from the existing one, with two entries replaced, so new opcodes propagate.
- **§4 state map:** it omits parked records, the gensym counter, primitives, modules and `:code-aliases`. Add "plus the semantic VM's non-environment registers, unchanged", so omission does not become hidden state.
- **§4:** "`environment` operation returns the fixed `free-env` value" silently changes a protocol's meaning for debuggers and completion. Return the lifted environment, or do not implement that method.
- **§1/§2:** "cache key" is used twice but no cache is specified. Name it as a value in the VM record, or drop the word.
- **B6 vs 7.2:** "a later epic, not B-phase VM machinery", yet B6 is a B-phase. 7.2 also says "consumes lossless executable images", which conflates two artifacts. Move B6 out of §6 into 7.2.
- **Unverified observation:** §2 implies the projection folds integral doubles. If a program can observe `1` versus `1.0`, that conflicts with the projection's §10 ruling that "identity never merges distinguishable values". This is worth one check by the prior reviewer.

## Answers 1–7

1. **Axioms and invariants.**
   - Compliant in structure. State is explicit, lowering, loading and execution are separate functions, effects go through streams, and the linker is a park plus a request emission.
   - Strains: the injectable loader (P2-5), the unspecified cache, the incomplete state map, the repurposed `environment` method, and axiom 4 portability (P2-4).
   - The frame environment, the fixed free environment and a name table inside the image value are all fine.
   - Scope reconstruction from `[entry, first :return]` is explicit graph construction, but it depends on a layout convention. That is acceptable only if it is tied to `lower`'s own rule.

2. **Legitimacy of the dimension.** It currently evades the protocol by omission: no slots, no encoding rule and no morphisms are declared, and "published" is an overclaim (P2-3). With the P2-3 fix it is a legitimate dimension. Including the descriptor hash in the image hash is already correct domain separation.

3. **Coexistence.**
   - A sibling VM is the right experimental shape, because it is fully reversible. It is the wrong permanent shape.
   - The delta is two opcodes and one environment representation. Every other opcode, effect and park rule gets duplicated.
   - Derivation by adaptation bounds divergence in lowering. The differential tests bound divergence in behaviour on the corpus only. Neither bounds the cost of maintaining two VMs.
   - The long-term shape should be one instruction vocabulary with lexical addressing as an operand encoding, reached by retiring one VM after a measured gate.
   - The design does not state its benefit at all (P2-1).

4. **Three-artifact split.**
   - The named datoms (source), the projection (identity) and the derived image (execution) form a coherent and minimal set.
   - The lossless DAG is a fourth artifact and is redundant (P1-1).
   - Its invertibility invariant is not worth the cost, and B0 is the wrong home for it, because it puts an unconsumed artifact's owner decisions in front of the VM.

5. **Linker and streams.**
   - The stream-topology framing is consistent with the axioms.
   - B1's image-hash definition is the one place B0–B6 shapes 7.2, and as written it shapes it badly (P2-2, P2-6).
   - The authority ledger, SCC identity and retry policy are correctly left to the owner. The meaning of `H` is a missing fourth decision.
   - SCC identity affects the hash format, so it must be decided before any `:call-hash` is persisted.

6. **Phasing.** The cuts are good and rollback is a file deletion. Changes I recommend:
   - Shrink B0 (P1-1).
   - Add a benchmark gate after B3.
   - Move B6 out of §6.
   - Add the missing owner decisions: end state of the two VMs, the benefit metric, binder names in or out of the hash, continuation interchange with UCF, and the meaning of `H`.

7. **Top three risks.**
   1. Permanent double maintenance with no stated payoff and no exit criterion.
   2. Identity proliferation. A fourth hash would be equivalent to `segment-key` and hostile to hash linking, and B1 would freeze it.
   3. A fork in the continuation format. Frames invisible to completion would make parked continuations unsound and erode UCF's single canonical state.

## Checked and found clean
- The rejection of lowering from projected records, and the reasons given for it.
- The projection stays identity-only and unchanged, and the fingerprint is explicitly not the executable key.
- The §9 must-not-change list.
- Scope validation at both image entry points.
- Positional locals never fall through to the store.
- The handling of `:macro?` and of macros upstream agrees with "macros are stream topology".
- The named-VM environment leak is excluded explicitly, not silently absorbed.
- The scope of the normalizer, and its being defined in B0 rather than assumed to exist.
- The honesty about the cross-host scalar domain, and the refusal of surrogates and unsupported values.
- The Unison claims are hedged and marked UNVERIFIED, and no interop goal is stated.
- The linker runs as a process between streams with no registry, and local and remote resolution are both just `dao.stream`.
- Lambda lifting and ANF are treated as optional upstream AST-to-AST stages.

I did not re-verify anything at source-line level, per the brief.

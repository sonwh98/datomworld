Completed-GMT: 2026-09-14 17:32:17 GMT  
Completed-Local: 2026-09-15 00:32:17 Asia/Ho_Chi_Minh  
Coding-Agent: codex  
Session-ID: 01a09fcc-3b9f-7172-b592-f15348d6c88b

**Verdict: REJECT**

The store-key, store-update, encoder-withdrawal, and lowering-profile corrections resolve their round-2 findings. The revised occurrence and dependency mechanisms still contain acceptance-blocking defects. The ordered batch improves carriage but does not establish the claimed preservation of arbitrary admitted batch order.

**Numbered findings**

1. **P1 — Source occurrence keys omit the newly introduced batch member index.**

   **Draft:** §2.5, [lines 230–244](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:230); §8.5, [lines 1105–1108](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:1105).

   A batch now contains several canonical trees. Two identical trees at indices `j=0` and `j=1` have the same medium, batch, root address, and structural paths. Consequently, `[[:source medium batch] root-address path]` still conflates their source positions and initial expansion calls.

   Declaration coordinates include `j`, but source positions, instruction provenance, and `:yin.ledger/call` do not. Thus declarations can distinguish occurrences that subsequent provenance cannot. The governing macro contract distinguishes initial calls by their source entity coordinate within the batch ([macro design, lines 695–697](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:695)).

   **Fix:** Include the batch member index—or a stable admission-catalogue occurrence identifier—in every source occurrence key. Carry it uniformly through declarations, source positions, instruction provenance, and initial expansion events. Keep it outside canonical content.

2. **P1 — An event ID embedded in an occurrence value is not a transactor-resolved local reference.**

   **Draft:** §8.4, [lines 1061–1079](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:1061); addressed expansion record at [line 979](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:979).

   The draft calls `[:expansion enclosing-ev]` a local reference “resolved … exactly as `:yin/source-node`.” The representations differ materially. The old attribute holds a directly declared reference; the new event ID is nested inside a vector.

   The transactor resolves only a whole `v` that is a tempid on a declared reference attribute. It does not recursively resolve nested values ([source, lines 168–185](/Users/sto/workspace/datomworld/src/cljc/dao/space/transact.cljc:168)).

   I executed that path with mappings `{-1 16, -2 17}`. The event entity became `17`, but its call value remained:

   ```clojure
   [[:expansion -1] :segment/input []]
   ```

   Declaring `:yin.ledger/call` as a reference attribute does not fix this. Moreover, a bare log-local event number inside an addressed record does not identify its log when that record travels.

   **Fix:** Represent the local parent link as a separate declared reference attribute, with path and content address as ordinary values. Define the portable occurrence identity using a qualified log/event identity, or an addressed event identity. If embedded coordinates are materialized after allocation, specify that staging and hash the record only after resolution. Preserve the governing contract’s explicit distinction between local references and portable values ([macro design, lines 692–708](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:692)).

3. **P1 — Vector order plus tree preorder does not preserve every admitted datom batch’s harvest order.**

   **Draft:** §8.5, [lines 1113–1119](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:1113) and [lines 1136–1137](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:1136).

   The ordered forest can carry disconnected definitions. However, the claim that it preserves the old contract *exactly* remains unsupported.

   The governing contract harvests definitions in batch order and admits arbitrary acyclic, internally resolved batches; it does not require their entity order to equal structural preorder ([macro design, lines 229–254](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:229)). A root may structurally contain definitions A then B while their entities occur in the batch as B then A. If both define the same name differently, preorder changes the winning definition.

   The current codec emits ordinary trees in traversal order, but that establishes a property of its output, not a restriction on every admitted batch ([codec, lines 413–440](/Users/sto/workspace/datomworld/src/cljc/yin/vm.cljc:413)).

   **Fix:** Carry an explicit ordered admission/harvest catalogue derived from original batch order, referencing definition occurrences. Use that order for initial harvest. Alternatively, explicitly amend and narrow the accepted legacy batch contract. Keep post-expansion declaration order separate; the governing design already distinguishes it from traversal order ([macro design, lines 298–304](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:298)).

   The new validate-before-strip rule **does** resolve the stray-macro-mark defect.

4. **P1 — Static parameter-name discharge assumes bindings that the reference machine does not guarantee.**

   **Draft:** §7.7.2, [line 886](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:886); completion at [lines 926–929](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:926).

   The unrelated-environment rule is correctly removed. Its replacement nevertheless discharges a variable statically whenever a surrounding lambda declares that parameter.

   Reference closure application constructs bindings using `zipmap` of parameters and supplied arguments, with no exact-arity check ([semantic source, lines 199–205](/Users/sto/workspace/datomworld/src/cljc/yin/vm/semantic.cljc:199)). An under-arity call leaves an omitted parameter unbound, allowing resolution through the captured environment, store, primitive registry, or module registry.

   I executed the reference call transition for a closure declaring `[x]` with zero arguments. Its resulting environment was `{}`; resolving `x` then selected the supplied primitive binding `99`. The draft would statically discharge that name.

   **Fix:** Discharge parameter obligations only when the relevant call analysis establishes that the binding actually exists. Account for captured bindings and fallback resolution when arguments are missing. Unknown call contexts must retain requirements or yield `:incomplete`. Introducing mandatory exact arity would require a separate execution-contract amendment.

5. **P1 — The fixed point still terminates on addresses, although obligations now depend on contexts.**

   **Draft:** §7.7.3, [lines 916–922](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:916).

   Two closures can share one segment address but capture different environments. Discovering the second closure introduces new resolution obligations without introducing a new address. The current wording associates obligations when an address is newly reached and repeats only “until no new address appears.”

   This permits address deduplication to suppress analysis of the second context. It also permits termination after discovering new context or profile facts that require further propagation. Code identity is insufficient as the analysis work-item identity.

   The source makes the distinction explicit: closure application enters the closure’s segment using its own captured environment plus argument bindings ([semantic source, lines 199–205](/Users/sto/workspace/datomworld/src/cljc/yin/vm/semantic.cljc:199)).

   **Fix:** Define convergence over all dependency facts and obligations, including code/context pairs, discovered values, store requirements, and callable/module footprints. Fetch and validate code once per address, but analyze each newly discovered relevant context. If a finite conservative context abstraction is unavailable, report `:incomplete` rather than terminating successfully on address stability.

6. **P1 — The new FFI footprint contradicts UCF’s explicit store-slice exclusion.**

   **Draft:** §7.7.1, [line 859](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:859).

   The FFI row adds the call-in/call-out keys to the **store-slice requirement**. UCF explicitly states that the source FFI pair is never carried in that slice; the resumer installs its own pair ([UCF, lines 812–824](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:812)).

   Following the new footprint literally either traverses excluded host handles and refuses otherwise portable FFI programs, or attempts to carry resources the governing contract excludes.

   The reference machine does require a local pair before executing `:ffi-call` ([semantic source, lines 405–423](/Users/sto/workspace/datomworld/src/cljc/yin/vm/semantic.cljc:405)). That is a receiver capability requirement, not a source-store transport requirement.

   **Fix:** Record required FFI operations and receiver-local pair availability separately from carried store keys. Preserve UCF’s exclusion and outstanding-call routing rules. The stream mnemonic-to-effect normalization itself is correct.

**Round-2 findings 1–8**

| Round-2 finding | Status | Verification |
|---|---|---|
| 1 — Store-key domain | **Resolved** | `key` is now exactly `data`; both validators and extraction agree: [line 120](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:120), [line 747](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:747), [line 765](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:765), [line 835](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:835). |
| 2 — Store-update result condition | **Resolved** | Explicitly requires plain data that is not an effect descriptor under the applicable contract: [lines 300–310](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:300). |
| 3 — Unsound encoder restriction | **Resolved** | Restricted domain withdrawn categorically; every identity use blocked: [lines 372–390](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:372). This resolves the specification claim, not the inherited codec blocker. |
| 4 — Occurrence identity | **Not resolved** | Origins added, but batch-member identity is missing and embedded parent IDs are not resolved as claimed: [line 230](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:230), [line 1076](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:1076). Findings 1–2 above. |
| 5 — Macro batch/admission preservation | **Not resolved** | Disconnected-tree carriage and stray-mark rejection added; exact batch-order preservation still fails: [lines 1113–1137](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:1113). |
| 6 — Conservative name completion | **Not resolved** | Any-environment test explicitly superseded, but static parameter discharge and address-only convergence remain unsound: [line 886](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:886), [line 922](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:922). |
| 7 — Effect vocabulary normalization | **Regressed** | Mnemonic normalization is fixed; the newly specified FFI contribution conflicts with UCF’s slice exclusion: [lines 854–865](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:854). |
| 8 — Lowering revision and verification | **Resolved** | Dedicated lowering profile, separate integrity/derivation checks, and exact structured projection: [§5.2.1](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:477), [§5.2.2](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:503), [line 997](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:997). Profile publication remains explicitly gated. |

**Verified sound**

- **The encoder withdrawal is categorical.** It covers trees, vectors, records, caches, projection collapse, and ledger identity. Computing addresses for conformance does not authorize deduplication. The prior safe-domain claim is gone.
- **The key widening matches the reference path.** I reran numeric-key lowering/loading and obtained `[[11 42 11] [23]]`. The shared data-domain requirement now also names store-slice encoding.
- **The store-update condition distinguishes data from interpreted effects correctly.** The reference probe confirms that effect descriptors can be plain data; the revised condition excludes them explicitly.
- **The dedicated lowering profile closes the revision ambiguity.** It pins input grammar and emission behavior separately from execution semantics. Integrity and derivation assertions have separate outcomes. I verified that the structured profile map is legal in a persisted datom’s value slot.
- **The five stream footprint mappings match execution, and the table covers the current mnemonic inventory.** The remaining FFI problem concerns resource carriage, not mnemonic spelling.
- **The UCF amendment note identifies the correct defect.** Replacing “any carried environment” with context-specific obligations is necessary; findings 4–5 concern the completeness of that replacement.

Section 10 now includes every topic requested in the previous review’s closing note. Its inventory is substantially improved, but it presents occurrence identity, batch preservation, and conservative completion as mechanisms ready to implement. Those entries must incorporate the remaining design work above, including parent-reference persistence, context-sensitive convergence, and the separation of receiver FFI capabilities from transported state.
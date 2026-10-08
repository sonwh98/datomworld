Completed-GMT: 2026-09-14 17:44:29 GMT  
Completed-Local: 2026-09-15 00:44:29 Asia/Ho_Chi_Minh  
Coding-Agent: codex  
Session-ID: 01a09fcc-3b9f-7172-b592-f15348d6c88b

**Verdict: REJECT**

Four previous findings are resolved. The parent-reference and harvest-order corrections are substantial, but two identity cases still prevent acceptance. These require specification changes; the remaining implementation work in §10 does not independently justify rejection.

**Numbered findings**

1. **P1 — Expansion-record addresses do not uniquely identify attempts across expanders.**

   **Draft:** §8.2, [line 1036](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:1036); §8.4, [line 1125](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:1125) and [lines 1156–1169](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:1156).

   The local/portable link split is correct, but portable identity now depends on a record containing a **per-expander attempt counter**, without the expander’s identity or incarnation.

   Two expanders can observe the same source medium, batch, member, and call path. If each processes that call with attempt counter `0`, using the same macro and producing the same output, their records are identical. They therefore have one record address despite representing two attempts. Restarting an expander and replaying a source batch can reproduce the same problem.

   This is equality of the record values themselves, not a transitional-encoder collision. A better hash cannot fix it. Consequently, `[:expansion record-address]` can again conflate generated occurrences, contradicting the assertion that separate attempts necessarily have separate record addresses. The governing contract records every attempt separately ([macro design, lines 666–689](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:666)).

   **Fix:** Give each logical attempt a portable identity scoped to an explicit expander incarnation, such as `[expander-incarnation counter]`, and include it in the addressed record. Specify that staged retries reuse that identity, while a distinct execution receives another. Keep the incarnation in explicit composition/state data. The existing parent-record chain can then remain unchanged.

2. **P1 — The harvest catalogue loses legacy entity identity when one definition is shared at several paths.**

   **Draft:** §8.5, [lines 1183–1218](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:1183); adapter rule at [line 1315](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:1315).

   Original entity order is now carried explicitly, fixing the preorder problem for unshared trees. However, the legacy input can contain a shared definition entity reached through multiple references. Converting that graph to inline trees produces several definition occurrences.

   The new catalogue requires every resulting occurrence exactly once. The old harvest and declaration catalogue operate on source definition entities, assigning one declaration ordinal to the shared entity ([macro design, lines 247–265](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:247)). The revised envelope has no alias mapping connecting several paths to that one original declaration.

   I exercised the existing codec with one definition reused twice. It emitted one `:yin/type` row for that definition and root operand references:

   ```clojure
   [-100 -23 -100]
   ```

   This sharing is explicitly supported by the codec’s `seen-eids` handling ([source, lines 394–411](/Users/sto/workspace/datomworld/src/cljc/yin/vm.cljc:394)).

   Duplicating catalogue entries is not harmless bookkeeping: it changes declaration ordinals and can give copies of one source declaration different stand-ins. Those ordinals are observable to macro transformers; fabricated stand-ins selecting catalogue entries are expressly permitted ([macro design, lines 255–275](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:255)).

   **Fix:** Preserve admission identity outside canonical trees. A legacy catalogue entry should represent one original definition and associate it with all corresponding `[j path]` occurrences. Harvest it once and give every associated occurrence the same declaration ordinal. Validate occurrence coverage through that mapping. Tuple-native producers can use singleton occurrence groups.

**Round-3 findings 1–6**

| Previous finding | Status | Verification |
|---|---|---|
| 1 — Missing batch member index | **Resolved** | `[:source medium batch j]` now travels through source positions, declarations, provenance, and initial events: [§2.5, line 236](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:236), [§5.3, line 544](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:544). |
| 2 — Embedded parent ID | **Not resolved in full** | Whole-value reference resolution is fixed: [line 1121](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:1121). The replacement portable attempt identity still conflates independent executions; finding 1 above. |
| 3 — Harvest-order preservation | **Not resolved in full** | Explicit admission order fixes reordered unshared inputs: [line 1191](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:1191). Shared definition entities still need an alias-preserving catalogue; finding 2 above. |
| 4 — Assumed parameter bindings | **Resolved** | Discharge now requires established argument supply at all known call sites; unknown/escaped cases retain obligations or yield incomplete discovery: [lines 904–922](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:904). |
| 5 — Address-only convergence | **Resolved** | Work items include context, and convergence covers obligations, values, requirements, effects, and footprints: [lines 943–983](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:943). |
| 6 — Transporting the source FFI pair | **Resolved** | FFI operations are receiver capability requirements; source pair keys are explicitly excluded from the slice: [line 871](/Users/sto/workspace/datomworld/docs/design/yin.vm.tuples.md:871). |

**Verified sound**

- **The whole-value parent reference works.** I ran `apply-tempid-map` with the declared `:yin.ledger/parent-event` attribute. The child entity resolved to `17`, its parent reference to `16`, and the portable parent address remained unchanged. This matches [the transactor implementation](/Users/sto/workspace/datomworld/src/cljc/dao/space/transact.cljc:168).
- **Parent-before-child record construction is feasible.** The macro algorithm records the immediate expansion output before recursively expanding it ([macro design, lines 386–393](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:386)). Thus a child can reference an already constructed parent record without embedding allocation-dependent IDs.
- **The known-call-site parameter condition addresses under-arity calls.** It respects the reference machine’s `zipmap` binding behavior rather than introducing an implicit exact-arity requirement ([semantic source, lines 199–205](/Users/sto/workspace/datomworld/src/cljc/yin/vm/semantic.cljc:199)).
- **The convergence criterion now includes the necessary changing facts.** A bound-name-set abstraction can answer binding-presence questions while callable values and footprints remain independently tracked. Implementations must preserve that separation; sharing an abstract context must not discard either closure’s captured values. The inherited value walk requires those values to be traversed ([UCF, lines 762–764](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:762)).
- **The FFI correction matches the governing contract.** The receiver supplies its own pair; outstanding calls retain their existing routing rules. UCF explicitly excludes the source pair from the carried slice ([UCF, lines 812–824](/Users/sto/workspace/datomworld/docs/design/yin.vm.universal-continuation-format.md:812)).
- **The encoder and implementation gates remain honest.** Codec replacement, profile publication, dependency-analysis implementation, and integration tests remain §10 obligations. Their unfinished implementation is not a new architectural rejection reason.

The remaining changes are narrowly scoped: qualify portable attempt identity and preserve shared legacy declaration identity in the admission catalogue. The other reviewed mechanisms can proceed under their stated implementation and conformance obligations.
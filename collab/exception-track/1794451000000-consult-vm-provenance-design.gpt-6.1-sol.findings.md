Created-GMT: 2026-11-11 16:25:13 GMT
Created-Local: 2026-11-11 23:25:13 +07 (+0700)
Coding-Agent: codex (gpt-6.1-sol)
Role: Architect (owner condition (a) design consult + condition (b) cross-frontend ruling)
Status: CONSULT VERDICT OF RECORD — APPROVED WITH AMENDMENTS

Completed-GMT: 2026-11-11 16:25:13 GMT  
Completed-Local: 2026-11-11 23:25:13 +07 (+0700)

The heap-entry design is sound **with amendments for GC tracing, mint authority through macros and runtime helpers, and Clojure adoption**. This ruling concerns retention under the continuation encoding. The owner’s convergence directive remains binding. ([FINAL DIRECTIVE](/Users/sto/workspace/datomworld/collab/exception-track/1794372000000-owner-decision-r3-vm-retained-seam.md:90))

Read-only inspection covered master `adb6af80`, Python’s committed contract `0326a044`, and removal commit `e735ebb2` and its parent. No tests were run or files changed.

1. **Heap-entry storage: approved.**

   Store the complete retained condition in an optional, private sibling of `:value` and `:seal`. I recommend the kernel-neutral name **`:provenance`**:

   ```clojure
   {:seal seal
    :value guest-visible-content
    :provenance original-condition}
   ```

   The VM treats provenance as opaque data. It knows no frontend, exception class, qualified reason, or condition schema.

   Existing cell access supports this boundary: `cell/get` returns only `:value`; `cell/set!` supplies a replacement value to `heap-write`. Authentication uses the live heap entry and its seal. ([access](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:2773), [authentication](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:332))

   The historical preservation design was appropriate: `engine.cljc:2457–2472 @ e735ebb2^` updated existing entry fields without discarding sibling facts and allocated the marked entry separately. Its limitation for this proposal is that it retained only a boolean mark; the condition still lived in mutable `:value`.

   A separate id map duplicates lifetime bookkeeping. A closure carrying condition data does not, by itself, bind that data to the wrapper’s heap identity. The entry already supplies the required identity and lifetime.

2. **Write-once enforcement: preserve provenance; do not refuse `cell/set!`.**

   Current `heap-write` replaces the entire entry with `{:value v :seal seal}` and therefore **must change**. It must preserve existing sibling fields while updating the value. ([engine.cljc:2444](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:2444))

   Required semantics:

   - Only the privileged mint creates `:provenance`, while allocating a fresh id.
   - Ordinary `cell/new` creates an unmarked entry.
   - No operation attaches, replaces, or clears provenance on an existing entry.
   - `cell/set!` preserves provenance and the existing seal.
   - The reader never obtains retention from `:value`.

   **No explicit marked-cell write refusal is needed.** The old refusal protected condition data stored inside `:value`; moving the complete condition outside that value removes that requirement. Allowing visible mutations also preserves ordinary mutable exception attributes.

   Consequently, copying a wrapper’s contents into a new cell loses provenance; overwriting the original wrapper’s visible contents cannot replace its retained condition. Copying its sealed reference still names the same object and legitimately retains rethrow authority.

3. **Doors: one shared reserved pair, with a mandatory macro-admission amendment.**

   Adopt these exact VM bindings:

   | Binding | Arity | Semantics |
   |---|---:|---|
   | `cell/dsg__new-provenance` | 2: `[value provenance]` | Allocate a fresh sealed cell with both fields installed atomically. Provenance must be non-nil and otherwise remains opaque. |
   | `cell/dsg__provenance` | 1: `[candidate]` | Return the stored provenance for an authenticated, live, marked cell; return nil otherwise. No mutation or failure for an unauthenticated candidate. |

   Corresponding effects: `:cell/new-provenance` and `:cell/provenance`, with explicit module profiles declaring arities `[2]` and `[1]`. Existing cell bindings already use this effect-constructor/profile arrangement. ([module.cljc:388](/Users/sto/workspace/datomworld/src/cljc/yin/vm/module.cljc:388))

   The query must authenticate **before** consulting the slot. Its authority is the task’s issuer-maintained heap entry, never a caller-supplied issuer label or condition-map shape. It must not require the current visible content to retain a particular frontend type tag.

   The frontend findings are:

   - **Clojure, raw source:** the pre-pass checks the symbol’s local name, including qualified heads, so both proposed names are rejected. Quoted symbols remain inert data. ([prefix check](/Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc:418), [program check](/Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc:2343))
   - **Python:** source names resolve through Python lexical/module lookup; qualified cell operations come from compiler-generated calls. ([lower.cljc:55](/Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/lower.cljc:55), [global lookup](/Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/lower.cljc:452))
   - **PHP:** source variables and function calls use PHP scope/name resolution and call helpers, without arbitrary `cell/NAME` pass-through. ([variables](/Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/lower.cljc:635), [calls](/Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/lower.cljc:1639))
   - **JS/TypeScript:** source identifiers resolve through lexical cells or JS globals. A spelling containing `/` does not become a qualified VM variable. ([lower.cljc:537](/Users/sto/workspace/datomworld/src/cljc/yang/typescript/antlr/lower.cljc:537))

   **The existing regime does not establish protection through macro expansion.** The macro prelude can construct an arbitrary variable from a symbol, while VM Rule R currently reserves only `yin/def`. ([macro.cljc:948](/Users/sto/workspace/datomworld/src/cljc/yin/vm/macro.cljc:948), [vm.cljc:113](/Users/sto/workspace/datomworld/src/cljc/yin/vm.cljc:113)) The earlier review expressly left this as S2; its ordinary `compile-program` probe did not exercise expansion. ([S2 evidence](/Users/sto/workspace/datomworld/collab/exception-track/1794371000000-reviewer-r3-A-design-confirm.glm-5.3.findings.md:128))

   **Landing requirement:** source/macro admission must reject guest-created executable references, bindings, and definition targets for these doors. Preserve legitimate compiler-generated occurrences through trusted construction provenance; quoted names alone must confer no authority. Exercise the actual macro/REPL expansion path, including returning a door as a value for indirect application. A raw-source spelling test is insufficient.

   Also protect the **transitive mint callers**. Python currently defines `py/condition-exception`, and its module exports every `py/` definition. Simply changing that helper to invoke the privileged mint would leave a constructor accepting arbitrary condition data on an exported surface. ([helper](/Users/sto/workspace/datomworld-f12/src/cljc/yang/python/antlr/prelude.cljc:1043), [exports](/Users/sto/workspace/datomworld-f12/src/cljc/yang/python/antlr/prelude.cljc:3753)) Provenance-writing helpers and private readers must use protected reserved names and stay outside guest exports, projections, and aliases.

4. **GC-with-entry: approved, but sweep preservation alone is insufficient.**

   Sweep removes whole entries, so the slot has no separate lifetime. However, normal marking currently follows only the referenced entry’s `:value`; pinned entries likewise seed tracing from only their values. Both must change. ([trace](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:2577), [pinned roots](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:2615), [sweep](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:2627))

   Required changes:

   - Trace `:value` **and `:provenance`** as data from every live entry.
   - Do the same for pinned entries, which enter the found set before ordinary following.
   - Before mint-triggered collection, hold both incoming value and provenance as extra roots. Current allocation rooting covers only the boxed value. ([count-allocation](/Users/sto/workspace/datomworld/src/cljc/yin/vm/engine.cljc:2659))
   - Do not make provenance-bearing entries global roots or pin them merely because they carry provenance.

   This preserves payload cells referenced only by the retained condition—even after a guest replaces the entire visible value. When the wrapper becomes unreachable, its condition and exclusively reachable payload cells become collectible. Existing exported-reference pinning still extends lifetime under the established task policy.

   No evaluator lane reconstructs individual heap entries:

   | Lane | Evidence |
   |---|---|
   | Semantic | Uses the shared engine and continues with its returned state. ([semantic.cljc:92](/Users/sto/workspace/datomworld/src/cljc/yin/vm/semantic.cljc:92)) |
   | de Bruijn stack | Carries the whole heap and uses shared effect dispatch. ([constructor](/Users/sto/workspace/datomworld/src/cljc/yin/vm/debruijn/stack.cljc:428), [dispatch](/Users/sto/workspace/datomworld/src/cljc/yin/vm/debruijn/stack.cljc:610)) |
   | de Bruijn register | Carries the whole heap and uses shared effect dispatch. ([constructor](/Users/sto/workspace/datomworld/src/cljc/yin/vm/debruijn/register.cljc:385), [dispatch](/Users/sto/workspace/datomworld/src/cljc/yin/vm/debruijn/register.cljc:548)) |
   | AST walker | Threads the whole heap through its positional record and dispatches through the engine. ([heap](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ast_walker.cljc:144), [dispatch](/Users/sto/workspace/datomworld/src/cljc/yin/vm/ast_walker.cljc:179)) |

   Thus the storage change belongs in shared engine/module code, without new lane-specific heap representations or instructions. Update the documented entry shape. ([state.md:119](/Users/sto/workspace/datomworld/src/cljc/yin/vm/docs/state.md:119))

5. **Cross-frontend adoption ruling.**

   | Frontend | Ruling |
   |---|---|
   | **Python** | **Adopt now.** Mint generated ForeignException/protocol wrappers through the shared mint; replace visible-map retention reads with the authenticated query. Remove the visible stamp as an authority source. |
   | **PHP** | **Adopt at its round’s start.** Store provenance on the cell defining the generated Throwable object’s identity, rather than a transient holder or copied facade. Generated retained exceptions must use that cell-backed representation; the existing map-only error fallback cannot authenticate retention. ([current fallback](/Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/prelude.cljc:299)) |
   | **Clojure** | **The continuation port adopts this primitive for protocol-condition retention.** Replace the witness mechanism and retire its replay/mutation limitation once acceptance proves closure. |
   | **JS** | **Grandfather the shipped `js.rt` identity store.** Migration is optional and non-urgent; it must not block the three required consumers. |

   **Clojure’s “no retention object needed” claim is incorrect.** The port brief makes that claim at [lines 45–48](/Users/sto/workspace/datomworld/collab/exception-track/1794400000000-engineer-port-clojure-continuations.prompt.md:45), but the contract explicitly requires recovery after the creating catch/call has ended. Temporary activation state is insufficient. ([architecture:3885](/Users/sto/workspace/datomworld/docs/design/yang.antlr.md:3885))

   A continuation preserves a qualified condition during transfer. Once source catches a local exception, stores it, and later executes `(throw saved)`, the new activation needs an identity-owned association to recover that condition. Without one, forwarding requires recognizing map contents, violating R5, or wrapping the value in a fresh `:yang.call/thrown`, violating R3.

   Therefore retain an identity-bearing, documented local protocol exception form. Put its complete original condition in the private slot; expose message/evidence through local accessors. Ordinary arbitrary thrown payloads remain raw. A source-created condition-shaped map receives no provenance and remains a fresh thrown payload. The committed contract already distinguishes saved protocol exceptions from shape-return/shape-throw cases. ([fixtures](/Users/sto/workspace/datomworld/test/yang/call/exception_contract.clj:126))

   JS’s store authenticates membership using sealed wrapper identity and does not consult mutable wrapper metadata. Its documented tradeoff is retaining associations until task teardown. ([identity policy](/Users/sto/workspace/datomworld/src/cljc/yang/typescript/antlr/prelude.cljc:1652), [implementation](/Users/sto/workspace/datomworld/src/cljc/yang/typescript/antlr/prelude.cljc:4133)) A later migration would improve reclamation and remove linear lookup, without urgency as a forgery repair.

6. **Uniformity: one primitive, one field, one door pair.**

   **Do not introduce frontend-specific heap fields or VM mint/query variants.** Python, PHP, and Clojure use the same opaque `:provenance` slot and the same two bindings. Their private constructors choose the local exception representation; their boundary readers recover the complete condition through the shared authenticated query.

   The engine performs no reason dispatch, frontend dispatch, class matching, rendering, or foreign method calls. Receiving runtimes interpret authenticated incoming conditions; escaping runtimes forward authenticated retained conditions unchanged. An unmarked thrown value always creates a fresh condition.

The unchanged `0326a044` forgery pins remain binding: forge and clone produce fresh thrown conditions carrying the clone; overwrite forwards the original condition. ([pins](/Users/sto/workspace/datomworld-f12/test/yang/call/python_exception_test.clj:111)) Add focused verification for provenance-only payload reachability, pinned wrappers, allocation-triggered GC, eventual reclamation, macro-door access, and exported-helper bypasses. These are implementation acceptance obligations, not results established by this read-only consult.

**APPROVED WITH AMENDMENTS: (1) use one opaque `:provenance` field and shared reserved mint/read pair; (2) preserve it across writes without refusing `cell/set!`; (3) trace it through ordinary, pinned, and allocation-time GC roots; (4) close macro and transitive-helper mint access before landing; (5) amend the Clojure port to retain protocol exceptions through this primitive.**
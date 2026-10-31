The stream boundary removes substantial evaluator complexity. The main concern so far is retry safety: the observer can forward earlier batches, then throw on a later batch without returning its updated cursor. I’m also checking sandbox environment handling and whether rewriting really reaches a fixpoint.

I reproduced the retry issue with the existing observer: a good batch followed by a malformed batch appends the good batch twice when the caller retries. I also found gaps in two-medium flushing, generated definitions, and gensym allocation across batches. The sandbox loader preserves parameter bindings, and the explicit parked/queued checks address the earlier suspension issue.

Completed-GMT: 2026-09-13 05:35:53 GMT
Completed-Local: 2026-09-13 12:35:53 +07
Coding-Agent: codex
Session-ID: 01a098f8-8420-7083-9d18-f9776512bd61
Role: Routine Review | Model: gpt-6-astra

**Verdict: REQUEST CHANGES.** The architectural pivot substantially simplifies evaluators and removes the previous runtime expansion machinery. The proposed forwarder nevertheless has blocking delivery and fixpoint defects, and several guarantees remain underspecified.

Reviewed the revised document, foundational invariants, stream contract, observer, walker, codec, frontend lowering, transactor, and previous findings. No files were written. One in-memory reproduction exercised the existing observer; the proposed expander has not been implemented or tested here. The generic diff scanner excluded the untracked target document, so its clean result provides no validation of this design.

**[P1 — blocking] 1. A later exception replays already-forwarded batches.**

Location: [§5, exception and retry guarantee](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:461); [existing observer coordination](/Users/sto/workspace/datomworld/src/cljc/yin/vm/stream_observer.cljc:174).

`run-on-stream` can process several batches before returning. Its successor cursor and VM state exist only inside its loop until that return.

Concrete trace:

1. Caller supplies session S, positioned before A.
2. A expands and is successfully appended to `program-out`.
3. The observer continues to B.
4. B's loader throws.
5. Caller still holds S. Retrying S forwards A again.

I reproduced this with the actual observer and an in-memory observation stub:

```clojure
{:first :thrown, :second :thrown, :appended [:good :good]}
```

An exception from `run-vm`, or a terminal read after successful forwarding, has the same state-publication problem. “The malformed batch is retried” therefore does not establish “earlier batches are never duplicated.”

**Required change:** Define recoverable error results carrying the latest observer and expander state, or a coordination boundary that returns progress before attempting another batch. Preserve successful append progress when surfacing errors. This can remain generic coordination; no macro logic belongs in evaluators.

**Acceptance:** A → malformed B → retry must leave exactly one A on each destination. Cover terminal reads and flush failures after earlier successful batches.

**[P1 — blocking] 2. One staged value cannot represent partial success across two destinations.**

Location: [§5, `flush` contract](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:452).

The proposed flush appends program and log, then either clears or retains `:staged`. Consider:

- `program-out` returns `ok`.
- `log` returns `full`.

Retaining the complete stage and replaying both appends duplicates the executable program. Clearing it loses the log. Reversing append order merely exchanges which destination can receive duplicates.

The single-output `full` trace is otherwise sound: retain the exact output and allocation state, remain not-ready, and retry before reading more input.

**Required change:** Track delivery progress separately for each destination. Once a destination acknowledges `ok`, never append that payload again during an ordinary retry. Specify `invalid-value`, `closed`, and `transport-error`, including the state returned when one destination has already accepted its payload.

Also state the consistency guarantee: independent media cannot provide atomic program-and-log visibility merely through append ordering.

**Acceptance:** Exercise both partial-success orders, repeated `full`, terminal outcomes, and an absent log. Verify exactly one append per successful destination and preserved batch order.

**[P1 — blocking] 3. The traversal does not reach the claimed fixpoint.**

Location: [§3.2, `expand-node`](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:215).

Macro recognition occurs before child traversal. The ordinary-node branch then returns the rebuilt parent without reconsidering its operator.

For example, suppose `choose` expands to `{:type :variable :name m}`, where `m` is a macro:

```clojure
((choose) operand)
```

The outer application initially has an application operator and is not recognized. Rewriting its operator produces a macro-named variable, but the parent is returned without expansion. The final scan rejects a valid rewrite opportunity instead of completing it.

Generated definitions have a related gap. Harvesting and definition replacement occur only on the initial batch. If a macro emits `(yin/def name <macro-lambda>)`, recursive `expand-node` neither installs nor replaces that definition. The final scan rejects its macro lambda.

**Required change:** Reconsider an application after its operator changes, preserving unexpanded operands until macro resolution is settled. Integrate generated definition handling into rewriting, or explicitly prohibit generated definitions in the output contract. Specify deterministic precedence for definitions introduced during expansion.

**Acceptance:** Cover an operator macro returning a macro name, an operator macro returning an anonymous macro lambda, and a macro producing a definition followed by its use.

**[P2 — must address] 4. Failed attempts cannot reach the log through the specified API.**

Location: [§3.2, attempt allocation](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:222), [failure-record guarantee](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:255), [§5 loader](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:455).

`expand-batch` receives datoms and context, with no log writer. If it throws, `load-program` never obtains a result to stage. The event and advanced allocation state therefore have no specified route to persistence.

Additionally, `guard depth` precedes `fresh-event`, contradicting the promise that guard failures receive an attempt event. The prose also says allocation precedes resolution, while the pseudocode resolves the macro first.

**Required change:** Return structured success/failure data containing diagnostics, event data, and allocation progress. Stage failure logging through the same delivery state machine, and define when the driver surfaces the error relative to log acknowledgment. Specify whether retrying a malformed batch reuses one logical attempt or creates a new attempt.

**Acceptance:** Arity, invalid output, depth, fuel, and nested failures must retain their diagnostic events through log backpressure and retry, without forwarding program output.

**[P2 — must address] 5. Per-batch reseeding repeats gensyms across a persistent session.**

Location: [allocation rule](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:201); [gensym format](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:342).

The allocation formula depends only on the current batch minimum and reserved-ID boundary. It ignores the previous `:alloc` watermark despite describing the counter as monotonic.

Independently compiled batches commonly reuse tempid ranges. Their first expansion can consequently receive the same event eid, and both invocation-local counters begin at the same value. `prefix__<E>_<n>` then repeats.

Transaction-local entity-ID reuse can be legitimate. Reusing generated symbols across persisted macro definitions and later expansions is different: symbols are not relocated by the transactor and can collide when generated syntax is subsequently combined.

**Required change:** Include the incoming allocation watermark when seeding, or introduce a separate explicit monotonic gensym/event namespace. Define identity scope across sessions and the transition of logical `:t`. Retries must reuse staged allocations rather than allocate again.

**Acceptance:** Expand separate batches with overlapping tempids while threading context. Their generated names must differ; replaying the same original context and input must remain deterministic.

**[P2 — must address] 6. The final scan contradicts lexical shadowing.**

Location: [§3.1, final scan](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:194).

The scan rejects every application whose operator resolves in `:store`, without specifying the lexical shadow set used during expansion.

With macro `m` in the store:

```clojure
(fn [m] (m 1))
```

must retain its inner application. A store-only scan rejects it.

The main traversal correctly makes lambda parameters visible inside the lambda body, including a lambda in operator position. Those parameters must remain invisible to the surrounding application's operands. That rule supports the existing `let` lowering and retirement of frontend shadow hints.

**Required change:** Define the final check using exactly the same scope-aware resolution as expansion. A flat entity scan is insufficient unless traversal supplies occurrence-specific scope information.

**Acceptance:** Cover lambda/operator-position shadowing, initializer calls outside the new binding, and shared input entities reached under different lexical scopes.

**[P2 — must address] 7. Tail marking needs explicit clearing and one unambiguous lambda rule.**

Location: [§3.4](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:358).

The rules simultaneously say an immediately applied lambda's body inherits the application's flag and that every lambda body is tail relative to its own return. These disagree when the application is not tail.

More seriously, the pass does not explicitly clear stale `:tail? true` annotations when syntax moves into a non-tail position. A macro can move a previously marked application into an operand or an `if` test. Preserving that annotation can cause a semantic/stack evaluator to discard a continuation still needed for subsequent work.

**Required change:** Recompute annotations rather than only adding them:

- Application operators and operands are non-tail contexts.
- An `if` test is non-tail; branches inherit their enclosing context.
- Every lambda body starts a fresh tail context.
- Stale annotations are removed wherever the computed context is non-tail.
- Define handling for every accepted node type, including `:dao.stream.apply/call`.

Running the pass on the final rewritten AST would make the invariant easier to establish.

**Acceptance:** Verify results when marked syntax moves into tests and operands. Measure continuation depth for generated deep recursion on semantic and stack implementations when available. Equal results alone do not prove O(1) continuation depth.

**[P2 — must address] 8. The third medium isolates events but does not complete provenance identity.**

Location: [§4.1–§4.2](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:398).

Self-contained program copies correctly eliminate executable references to unresolved source tempids. The log still needs additional identity rules:

- Two input batches may contain the same negative call eid. An unqualified `:yin/source-call` cannot distinguish them.
- A generated call has no eid “as written in the source batch.” Nested expansion requires an identity for intermediate generated syntax.
- Independently committing program and log copies gives their output entities different durable IDs. Committing `program-in` first solves original source references, not this output correspondence.
- Event attributes move out of the VM schema, but the emitter still needs a schema declaring which values are refs for transactor relocation.

The current [transactor](/Users/sto/workspace/datomworld/src/cljc/dao/space/transact.cljc:168) relocates attribute values according to ref declarations; emitting an attribute alone does not establish that behavior.

**Required change:** Define source-medium/batch-qualified descriptive identities, identities for intermediate expansion nodes, and a log-local event schema. State whether `expansion-root` names only the logged copy or promises correspondence with the executed program. If correspondence is promised, provide a committed mapping or shared commit protocol.

Also define naming for directly applied anonymous macro lambdas: `:yin/macro-name` cannot always be a source name.

**Acceptance:** Query nested expansion chains and repeated source tempids, then commit source, program, and log independently and verify the exact documented relationships.

**[P2 — must address] 9. Output validation alone does not establish the sandbox's closed-input guarantee.**

Location: [§3.3, runner and validator](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:272).

The runner correctly uses a fresh VM and `vm-load-program`; the existing loader preserves its parameter environment. Checking parked and ready work before accepting halt also fixes the previous suspension oversight.

However, these state checks detect suspension, not all side effects. The design validates returned output but does not specify recursive plain-data validation of incoming macro bodies and operand ASTs before execution. If an in-memory source literal contains a host function, a macro can extract and call it, then return valid syntax. Output validation cannot undo that call. The walker supports applying host functions.

Fuel also bounds VM transitions, not the cost of a host primitive or all structural work. A huge literal payload contains few AST nodes while requiring substantial traversal and allocation.

**Required change:** Validate the source syntax admitted to the sandbox, or explicitly require an enforced plain-data producer boundary. Treat `:eval` and prelude functions as trusted composition capabilities with a stated purity contract. Define limits for literal/container traversal and primitive work where a bounded-resource guarantee is intended.

**Acceptance:** Reject nested host values before invocation, including a host function whose invocation would return harmless plain data. Test large literal payloads separately from AST-node count.

**[P2 — must address] 10. Cycle detection needs an identity-aware traversal before recursive decoding.**

Location: [§3.2, path tracking](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:208); [output vocabulary](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:321).

The algorithm assumes `eid(node)`, but public output AST maps forbid `:eid`, and the current [decoder](/Users/sto/workspace/datomworld/src/cljc/yin/vm.cljc:475) constructs maps without general node identities.

Two implementation hazards follow:

- Decoding cyclic source datoms with the existing recursive decoder can overflow before `expand-node` performs its cycle check.
- Applying the shown eid-path rule to fresh output maps can treat multiple absent identities as the same identity and falsely report a cycle.

Expansion depth also does not bound ordinary AST nesting, including nesting traversed during harvest, decode, validation, and emission.

**Required change:** Traverse source references through the explicit index with cycle/dangling-ref checks before recursive materialization. Keep internal identities separate from the public macro AST representation. Define bounded or iterative traversal for ordinary syntax and payloads.

**Acceptance:** Cover a cycle inside an operand passed to a macro, a dangling operand reference, deeply nested macro-free syntax, and a valid fresh AST several levels deep.

**[P3 — suggestion/alignment] 11. Narrow the semantic and compatibility claims.**

Location: [definition harvesting](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:186), [macro helpers](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:289), [Appendix A](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:634).

Three claims need qualification:

- Syntactic harvesting from unexecuted branches is an explicit language policy. It should not be justified simply as “as in Clojure.”
- “Helpers for macros are other macros” is unsupported by the runner shown: it loads the body directly, with no macro store or body-expansion pass. Specify body expansion and its guards, or limit executable helpers to the prelude.
- A macro name reaching an evaluator is not necessarily unbound. An earlier ordinary definition with that name can remain in the evaluator's persistent store after a later macro definition is harvested. Specify the relationship between macro and runtime namespaces.

Similarly, a later input batch is not equivalent to expanding inside an already-loaded runtime continuation. The revised semantics intentionally resolve syntax before execution; document that limitation directly.

**Architecture and roadmap assessment**

- **Layering:** The new placement is sound. Expansion owns syntax interpretation, program evaluators consume semantic syntax, and effects cross medium boundaries. The private throwaway evaluator does not itself collapse layers. Qualify “never holds VM control state” to mean the downstream program evaluator's state.
- **State ownership:** Explicit context and persistent process state support the no-hidden-global and no-shared-mutable-state invariants. The invocation-local gensym cell is defensible once both input and output boundaries prevent capability escape.
- **Relative improvement:** Evaluator simplicity and verification surface improve substantially: opcode 24, macro transitions, ephemeral expansion segments, runtime ledgers, and closure flags disappear. Overall failure handling is not yet strictly superior because the new forwarding and multi-medium delivery contracts are incomplete.
- **Hygiene:** Outermost-first traversal supports syntax transformers and lexical shadowing. It does not provide automatic hygiene; the explicit deferral is appropriate.
- **Definition roots:** Replacing a harvested definition with a literal symbol is structurally sound with an explicit root fact. Generated definitions and runtime namespace collisions still need decisions.
- **Roadmap:** Codec first, expander second, REPL composition third, and cross-language verification fourth is a reasonable order. Delivery-state and identity contracts must be settled before Phase 1 implementation.

Appendix B correctly identifies root selection, definition removal, operator-lambda scope, tail propagation, parked work, retained staging, and frontend migration. Its proposed guards do not yet fully address those risks. Add the acceptance cases above, especially multi-batch exception replay, partial destination success, generated operators/definitions, cross-batch gensyms, stale tail flags, nested provenance, and pre-decode cycles. The promised semantic tail-loop test should appear explicitly in the roadmap, with continuation-depth measurement and a clear dependency on evaluator availability.

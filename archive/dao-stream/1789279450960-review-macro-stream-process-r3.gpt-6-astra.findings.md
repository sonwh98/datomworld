I’ll read revision 3 directly, check each prior finding against its resolution, and trace the revised flush and expansion rules. This remains a read-only review.

The revised staging rules handle ordinary `full` retries correctly, and I agree the observer’s progress-on-error fix belongs in generic coordination. I found a remaining traversal problem: the re-check happens after operands have expanded, so a newly discovered binder macro receives already-rewritten operands. The output validator also rejects the inline macro lambdas that Phase 1 requires a test to expand.

Completed-GMT: 2026-09-13 06:06:41 GMT
Completed-Local: 2026-09-13 13:06:41 +07
Coding-Agent: codex
Session-ID: 01a098f8-8420-7083-9d18-f9776512bd61
Role: Routine Review | Model: gpt-6-astra

Read-only review of revision 3. No files were written. Resolution statuses below assess the design contract; the Phase 0 observer change remains unimplemented in the inspected code.

| Round-2 finding | Resolution | Assessment |
|---|---|---|
| 1. Later exception replays earlier batches | **Resolved at design level** | §5 prerequisite and Phase 0 explicitly require progress-preserving errors. I agree this fix belongs in generic observer coordination, with this design depending on it. |
| 2. Partial success across destinations | **Resolved** | §5 clears each destination’s slot independently. Ordinary retries do not repeat acknowledged appends. |
| 3. Traversal misses fixpoint | **Partially resolved** | §3.2 revisits a newly recognized application, but expands its operands too early. The inline-lambda acceptance test also contradicts output validation. See findings 1 and 3. |
| 4. Failed attempts cannot reach the log | **Partially resolved** | Decision 11 and §§3.1/5 provide the result-and-staging path for invocation failures. Admission failures still lack a specified event-construction path. See finding 6. |
| 5. Gensyms repeat across batches | **Resolved** | §3.1 includes the incoming allocation watermark and preserves staged allocation on retry. |
| 6. Final scan contradicts shadowing | **Resolved** | §3.1 removes the scope-insensitive application scan. Lambda-parameter scope in §3.2 is correct. |
| 7. Tail marking retains stale flags | **Resolved** | §3.4 recomputes the complete tree, clears stale flags, and gives every lambda body its own tail context. |
| 8. Provenance identity incomplete | **Resolved for the stated scope** | §§4.1–4.2 define batch qualification, log-local intermediate syntax, anonymous macros, and event schema. Program/log identity correspondence is explicitly outside the guarantee. |
| 9. Sandbox input not closed | **Resolved for the stated v1 scope** | Admission validates ground values; §3.3 validates operands and states trusted-capability requirements. Fuel and payload limitations are now explicit. |
| 10. Cycle checks follow recursive decoding | **Partially resolved** | §3.1 correctly introduces index-based admission and internal identities. Its checked graph is narrower than the graph harvesting can decode. See finding 5. |
| 11. Semantic and compatibility claims | **Resolved substantively** | §2.2 separates namespaces; §3.1 states harvest policy; §3.3 limits helpers to the prelude. Decision 5’s unconditional “unbound variable” wording should follow §2.2’s qualification. |

**[P1 — blocking] 1. Operator re-check occurs after operands have already expanded.**

Location: [§3.2, ordinary branch](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:278).

The re-check fixes the missed application, but `children'` includes both the operator and every operand. Consequently, a macro discovered through operator expansion receives rewritten operands, contradicting the outermost-first contract.

Concrete example:

```clojure
((choose) f [x] (x 1))
```

Assume `choose` expands to the variable `defn`, and a macro named `x` exists.

The specified traversal:

1. Expands `(choose)` to `defn`.
2. Expands operand `(x 1)` under the original scope.
3. Re-checks the parent and invokes `defn`.

The `defn` macro receives syntax whose `x` call was already rewritten before the generated lambda could establish its binding. An operand macro that fails would similarly fail even if the newly discovered outer macro would discard that operand.

**Required change:** For an application not initially recognized as a macro, expand its operator first, then resolve the rebuilt operator **before expanding operands**. If it now names a macro, invoke it with the original operand syntax. Expand operands only when the application remains ordinary.

**Depth assessment:** The same-depth re-entry is correct for the stated metric. Recognition itself is not an expansion; actual invocation re-expands its output at `depth + 1`. With a fixed store, a successful re-check immediately enters that invocation branch. Repeated generated applications therefore consume depth and cannot form an infinite rewrite chain at constant depth. The defect is operand ordering, not missing depth increments.

**Acceptance:** Test the binder example above and an operator-selected macro that discards an operand containing a failing macro call.

**[P2 — must address] 2. Plain redefinition misses the standard `defn` path.**

Location: [§3.1 harvesting](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:213), [stdlib `defn`](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:431).

Harvesting removes an existing macro only when the input already contains `yin/def` with a plain lambda. But `defn` is now itself a macro; its input application does not have that shape until expansion.

Across separate batches:

```clojure
(defmacro m [x] ...)
(defn m [x] x)
(m 1)
```

The second batch emits an ordinary definition and updates the evaluator’s store, but no specified operation removes `m` from the expander’s store. The third batch still invokes the old macro.

The “every `yin/def` is authoritative” claim also exceeds the lambda-only rule: a definition assigning an existing function through a variable is ignored.

**Required change:** Specify macro-store removal for generated ordinary definitions, including the normal `defn` expansion, and for other accepted plain definition values. Define their effective ordering relative to same-batch uses; simply rerunning whole-batch harvest without an ordering rule would introduce another ambiguity.

This does not require supporting generated **macro** definitions.

**Acceptance:** Cover `defmacro m` followed by `defn m`, then a call in a later batch. Also cover ordinary redefinition through a function-valued variable.

**[P2 — must address] 3. Generated inline macro lambdas are both required and forbidden.**

Location: [output validation](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:274), [generated-definition restriction](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:317), [Phase 1 acceptance](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:722).

Every expansion output is validated with `:allow-macro-lambda? false`. Therefore an operator macro returning an inline macro lambda fails before encoding or parent re-check.

Phase 1 nevertheless requires that exact case to expand successfully. The restriction rejects all generated macro lambdas, not merely generated definition forms.

**Required change:** Choose one contract:

- Keep the broader prohibition and remove generated-inline-lambda support from the algorithm’s guarantees and tests; or
- Reject actual generated macro definitions while specifying contextual handling of generated inline macro lambdas.

For the second choice, preserve macro bodies as executable transformer syntax until invocation; do not recursively expand them as ordinary program bodies.

**Acceptance:** The validator, traversal, and Phase 1 test must agree on the same permitted case.

**[P2 — must address] 4. `:errors` is never drained, and one error suppresses unrelated successful output.**

Location: [§5 error accumulation](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:564), [§6.1 REPL driver](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:631).

The loader appends errors to persistent expander state. No transition clears them after reporting. Under the written REPL flow, a later successful input still sees the previous error and skips evaluator execution.

There is also a multi-batch problem: `run-on-stream` may forward A successfully, then consume failing B in the same invocation. §6.1 sees B’s error and skips the evaluator altogether, even though A is waiting on `program-out`.

**Required change:** Define an explicit report-and-drain operation for errors, preserving any pending log delivery. Drive successfully forwarded program batches independently of whether another source batch failed. If the REPL requires one result per input, specify batch correlation and the corresponding coordination boundary.

**Acceptance:** A failed input followed by a successful input must execute the latter without reset. Processing successful A and failing B together must execute A once and report B once.

**[P2 — must address] 5. Admission checks only root-reachable cycles, but harvesting scans the whole batch.**

Location: [§3.1 admission and harvest](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:206).

Admission checks acyclicity only for the graph reachable from the selected root. Harvest then finds every definition in batch order.

A batch can contain:

- A valid literal root.
- A disconnected macro definition.
- A cycle in that definition’s lambda body.

All refs resolve, and the selected root is acyclic. Admission passes. Harvesting the disconnected definition into `{sym lambda-ast}` can then recursively decode the unchecked cycle.

**Required change:** Either check acyclicity across every component that harvesting or invocation may decode, or explicitly restrict harvesting to the admitted reachable graph. State which entities belong to the batch’s semantic scope.

**Acceptance:** A disconnected cyclic macro definition must either be rejected before decoding or be excluded from harvesting by an explicit rule.

**[P2 — must address] 6. Malformed-input logging needs a batch-level event path.**

Location: [admission error result](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:206), [attempt allocation](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:270), [event schema](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:493).

Decision 11 and §3.5 promise that corrupt batches are logged and consumed. However, the only specified event allocation occurs after identifying a macro application. Admission can fail before any valid root, call, or macro exists.

The abbreviated admission result has no log or advanced context, while the general error contract requires both. Implementers cannot derive a valid source-call event from a batch with a dangling root.

**Required change:** Define an admission-failure event carrying batch identity and sanitized diagnostics, with call/macro fields absent when unavailable. Specify event allocation and context advancement even when indexing fails. Keep malformed input values themselves out of diagnostic payloads.

**Acceptance:** Dangling-root, cyclic-input, and host-value failures each produce one plain-data diagnostic event, advance once, and survive log backpressure without regeneration.

**Requested §5 traces**

**(a) A succeeds; B returns `:error`; next round**

Assuming B has one failed invocation and the log eventually accepts:

1. A loads, stages its destinations, and clears each on `ok`.
2. B loads successfully as coordination work: it adopts the returned context, stages only its log payload, and appends its diagnostic to `:errors`.
3. The source cursor advances past B.
4. If B’s log returns `full`, only that payload remains staged.
5. The next round retries B’s log, without reloading A or B.

Result: exactly one accepted A program append and one accepted B failure-event payload. The lingering `:errors`/REPL issue is separate and remains finding 4.

**(b) `out=ok, log=full`; retry**

The output slot becomes nil; the log slot remains populated. `ready?` stays false. The next flush attempts only the log. Result: exactly one program append.

Thus, **per-medium staging achieves at-most-once acceptance under ordinary retries**, with preserved returned state. It does not promise crash recovery, remote delivery, or atomic visibility.

The outcome table’s retry mechanics match DaoStream. Two qualifications matter:

- `full` may be permanent; retaining the payload is correct but does not guarantee eventual progress.
- `closed`, `invalid-value`, and `transport-error` are legitimate protocol outcomes, not necessarily programming defects. Treating them as terminal operational errors is permissible; preserve their original outcome and pending state.

**Observer prerequisite placement**

**I agree with fixing progress publication in `stream-observer/run-on-stream`, rather than adding a macro-specific workaround.** The dependency and Phase 0 deliverable are appropriate.

Its implementation must distinguish:

- Load failure: retain the cursor before the failing batch.
- Flush failure after successful load: retain the cursor after that batch and adopt the partially flushed expander state carried by the error.

Returning only “A’s successor” for both cases would replay B after a partially successful B flush. This distinction belongs in the generic progress-on-error contract and its acceptance tests.

**Verdict: REQUEST CHANGES.**

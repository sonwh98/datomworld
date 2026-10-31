Completed-GMT: 2026-09-06 18:20:00 GMT
Completed-Local: 2026-09-07 01:20:00 +07 (Asia/Bangkok)
Coding-Agent: claude
Session-ID: e425d8bd-ad4c-44f7-aaed-54cb3196fd0f

# allocator-error disposition

## 1. Disposition: (a), with the dependency named — not (b)

**The principle that decides it is the RPC layer's own contract.** "Loss is conservative: every outstanding request is reported lost" is the rule `dao.stream.rpc` states for itself and honors on five of its six terminal paths (`rpc.cljc:371,377,403,410,415`). `allocation-failure` (`rpc.cljc:155-161`) is the sixth, and it breaks that rule: it sets `:terminal` and appends a diagnostic, and never calls `lose-outstanding`. That is a defect *in* `rpc.cljc`, not a property of it. A consumer that reaches into `:outstanding` to synthesize the completions the layer beneath owed is the layering error this plan refused four times — `forward-step`'s totality is bought "by moving partiality into its first consumer" is the stream plan's phrase for exactly this — and (b)'s sweep would become dead code the day the two-line fix lands, while remaining live code that inspects RPC internals. The orchestrator's provisional reading is right.

**But (a) as offered — "disclaim and grow nothing" — is not enough on its own**, because it leaves the materializer's contract ("one completion per materialization") with a standing exception, and the table's exhaustiveness claim honest only by admitting a hole. The complete disposition is (a) **plus a named dependency**: the fix belongs to `dao.stream.rpc`'s owner, is small, and J3c depends on it. Concretely:

- **The RPC fix, filed against `dao.stream`**: `allocation-failure` calls `(lose-outstanding state :dao.stream.rpc/allocator-error true)` before returning, keeping its diagnostic. Every outstanding request then completes with reason `allocator-error` through the ordinary outbox, `:terminal` is set as today, and `rebind` still refuses. Test in `rpc_test.cljc`, where there is currently no coverage of `allocator-error`, `id-exhausted` or `id-collision`: one outstanding request; force `:next-id` onto an in-use id; assert one completion with reason `allocator-error`, `:outstanding` empty, `:terminal` set. Two affected consumers today: this plan's J3c, and the shipped `yin.repl` (`v2_adapter.cljc:117` maps `allocator-error` to its own terminal and inherits the stranding).
- **`dao.jing.v2.remote` grows no compensating logic.** After the fix, the stranded bystander is not a new cell: `lose-outstanding` emits a `:lost allocator-error` completion carrying the put id or the get id, and rows 5 and 18 of the table already route those.

## 2. Dependency: J3c is gated on the fix; nothing else is

J1, J2, J3a and J3b proceed independently — none of them holds a record across an allocation. J3c's contract cannot be met without the fix, so J3c names it as a dependency and its **bystander test is the gate**: one materialization in `:put` with its put outstanding, then a forced allocation failure on a second `request-materialize`; assert the bystander completes `:lost :dao.stream.rpc/allocator-error` exactly once, by put id, no residual record. That test is written in J3c and goes green only when the RPC fix is in; J3c is not complete before it does. Not gating the whole plan is deliberate: the trigger needs ~9e15 allocations or an id collision that monotonic allocation prevents, the defect ships today in the REPL, and a five-phase plan should not wait on two lines in another owner's namespace when only its last sub-phase needs them. "Proceed with the disclaimer and drop it when the fix lands" is rejected because it would ship J3c with a contract exception; "gate the plan" is rejected because only J3c depends on it.

## What the table must say

The exhaustiveness claim is true only if the disposition vocabulary has a fourth value. Today a cell is a *transition*, a *no-op*, or *impossible (with why)*. Add **dependent (with the dependency named)**: the cell's answer is determined by something outside this namespace, and the row says what and where. A silent hole and a footnote both fail the reader who is checking the table; a `dependent` row does not.

Preamble amendment, replacing "Routes by names the id the RPC completion carries": *"Routes by" names the id the RPC completion carries, or **synthesized** where no RPC completion exists and the materializer publishes under the put id directly. Every cell is one of: a transition, a no-op (record unchanged, stated), impossible (with why), or dependent (with the dependency named).*

Rows to add:

| record phase | event | record | published | routes by |
| --- | --- | --- | --- | --- |
| `:put` (put outstanding) or `:verify-issued` (get outstanding) | RPC state goes terminal by `allocator-error` — another materialization's or caller's allocation failed | **dependent** on the `dao.stream.rpc` fix filed with this plan: `allocation-failure` must call `lose-outstanding` as every other terminal does (`rpc.cljc:155-161` does not today). With the fix, this is row 5 / row 18: removed | with the fix: `{:id put :op :materialize :lost :dao.stream.rpc/allocator-error}`; without it: **none — the record is stranded**, which is why J3c is gated on the fix | put id / get id, carried by `lose-outstanding`'s completion |
| `:verify-unissued` | driver `abandon` | no-op: this record owns no envelope; `abandon-unsent` completes whatever unrelated envelope is `:unsent` | nothing for this record | — |
| `:verify-issued` (get outstanding, not `:unsent`) | driver `abandon` | no-op: `abandon-unsent` touches only `:unsent`; an outstanding get completes through its response or `lose-outstanding` (rows 18–22) | nothing | — |
| `:verify-unissued` | response-medium `gap` (non-terminal) | no-op: `gap` loses outstanding ids only, and this record has none | nothing | — |
| `:put` (put `:unsent`) or `:verify-issued` (get `:unsent`) | response-medium `gap` (non-terminal) | no-op for the unsent envelope: `lose-outstanding` does not touch `:unsent`; the envelope is re-attempted by order 1 next step | nothing | — |

Cell edits to existing rows 14 and 15 (verify-hop `allocator-error`; terminal sweep): "routes by" becomes **"synthesized; published under the put id"**. Row 14 additionally gains: "the RPC state is now terminal by `allocator-error`; every *other* record with an outstanding hop is the dependent row above."

## 3. glm's P3: explicit rows, not a footnote

Explicit no-op rows, as listed above. The table exists so a reader can distinguish *impossible* from *no-op* from *unconsidered* without trusting prose; a footnote is prose. The cost is five rows. The two synthesized cells get the "synthesized; published under the put id" wording, and the "routes by" column definition is amended as in the preamble above, so the column's own rule stops being false for those cells.

## Summary of what changes in the plan (revision 5 splice, no other edits)

- Decision 3, lifecycle table: preamble amended with the four-way disposition vocabulary and the synthesized-routing clause; one `dependent` row; four no-op rows; two cell edits.
- J3c: one named dependency — the `dao.stream.rpc` `allocation-failure`/`lose-outstanding` fix, filed by the orchestrator against `dao.stream` with `yin.repl` listed as the second affected consumer — and the bystander test as J3c's gate.
- *Boundary of this plan*, "Intentionally deferred": the RPC fix itself, to `dao.stream.rpc`'s owner, with the note that `dao.jing.v2.remote` compensates for nothing.
- Everything else unchanged.

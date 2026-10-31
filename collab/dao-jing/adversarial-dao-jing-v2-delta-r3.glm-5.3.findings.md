[claude-code:unrecognized_model] {"model":"glm-5.3","query_source":"sdk"}
Completed-GMT: 2026-09-06 18:29:27 GMT
Completed-Local: 2026-09-07 01:29:27 +07 (Asia/Bangkok)
Coding-Agent: glm
Session-ID: 50d48a71-9ff9-44b7-8dc0-b334e5f42aac

# Delta review — dao.jing.v2 plan, revision 4 → 5 (final)

Citations verified against `rpc.cljc` (held from rounds 1–4), `v2_adapter.cljc:117`, and the filed defect report. Scope held to the 210 changed lines and the three questions.

## 1. Does `dependent` make the exhaustiveness claim true?

**It closes the honesty gap; it does not relabel it.** The distinction between this and a disclaimer is structural, and all four legs hold:

- **The row states both worlds, including the bad one.** "With the fix, this is row 5 / row 18: removed … without it: **none — the record is stranded**, which is why J3c is gated on the fix." Nothing is hidden; a reader of the table alone learns the exact failure mode.
- **The dependency is real, named, and owned.** Verified: `collab/stream-v2-rpc-allocator-defect.findings.md` exists, filed by the orchestrator against `dao.stream` (Stream & Network), and the fix the row waits on is specified exactly — `allocation-failure` must call `lose-outstanding` with reason `:dao.stream.rpc/allocator-error` before going terminal, keeping its diagnostic, `:terminal` and `rebind` behavior unchanged. The second-consumer citation is accurate: `v2_adapter.cljc:117` is literally `:dao.stream.rpc/allocator-error :yin.repl.adapter/terminal`.
- **The plan cannot complete with the hole live.** The gate is not prose — J3c's deliverable is "green … including the bystander test, which requires the named RPC fix," and the end condition repeats it. A `dependent` row without a gate would be a relabel; a gated one converts the dependency into a checkable failure.
- **The with-fix mapping is correct.** I walked it: the fix's `lose-outstanding` appends completions for exactly the stranded bystanders, synchronously inside the `rpc/request!` call that triggered the allocation failure, so the next step's order 5 drains and routes them — put id for `:put` records (row 5 semantics), get id for `:verify-issued` (row 18 semantics) — and the removal guard makes each exactly-once. The fix does not touch `:unsent`, so the get-`:unsent`-at-allocator-error case keeps its existing exit through order 3's abandon-with-terminal-reason (row 18) — consistent before and after. The end condition's "the table's one `dependent` row is then an ordinary row" is therefore literally true.

The refusal to compensate in the consumer is also the right call for the stated reason — `dao.jing.v2.remote` reaching into `:outstanding` to synthesize completions the layer beneath owed would be the layering error this plan has refused throughout. With `allocation-failure` the sole terminal among six that skips `lose-outstanding` (rpc.cljc:371, 377, 403, 410, 415 vs 155–161), the defect is in the RPC layer, and the plan now says so with a gate rather than either silence or a workaround.

## 2. The four no-op rows

**Three of three of mine are correctly added, and the author's fourth is right.** The `gap`-while-`:unsent` row is mechanically correct and a genuinely good addition, not padding: `poll!`'s gap branch runs `lose-outstanding`, which touches `:outstanding` only (rpc.cljc:400–406, 308–315), so an unsent envelope survives the gap and order 1 re-attempts it — a reader could easily believe gap loses "everything pending," and the row says exactly what is and isn't lost. The gap-while-outstanding counterpart is already a transition through row 5/row 18's `lose-outstanding` clause. Correct set, correct content.

**But a fifth no-op is still missing — the twin of one the author just added.** `:put` with the put **outstanding** (not `:unsent`) + driver `abandon`: `abandon-unsent` touches only `:unsent`, so this record is untouched and completes later through its response or `lose-outstanding` — a no-op, exactly symmetric to the added `:verify-issued`-get-outstanding abandon row. Row 5's abandon clause is explicitly `:unsent`-scoped, so no existing row covers it. With three abandon rows now in the table, the missing fourth abandon case is conspicuous — the same ambiguity class ("does abandon touch my outstanding hop?") the P3 was filed over. Benign in outcome; one row to state it. Filed below.

Events I checked and agree need no rows: lifecycle `established` and `diagnostic` (no completions, no plausible record relevance), `rebind` (no record survives a terminal step — established in r4), and `busy` on new requests while records exist (an outcome to the caller, not an event on a record).

## 3. The J3c gate

**Correctly placed, and the test genuinely discriminates.** J1 and J2 touch no RPC. J3a and J3b hold no record across an allocation — J3a's allocator-error case asserts the outcome, the minted-nothing, and the diagnostic, all of which the fix leaves unchanged, so J3a is green on the unfixed rpc and stays green after it; only J3c holds records in `:put` or `:verify-issued` across another operation's allocation, which is precisely the state the fix governs. Gating the whole plan on two lines in another owner's namespace would have been over-gating; gating nothing would have been the silent hole.

**Before the fix, the bystander test fails on both its assertions**: the asserted `:lost :dao.stream.rpc/allocator-error` completion never arrives (`allocation-failure` sets terminal without losing outstanding; `poll!` short-circuits on terminal forever; order 3 has no unsent to abandon; the order-4 sweep is unissued-only and the bystander is `:put`), and "no residual record" fails because the record cannot complete. **After it, the test passes for the right reason**: the forced collision (the test driving `:next-id` onto an in-use id — legitimate on caller-owned plain-data state) triggers `allocation-failure`, whose now-present `lose-outstanding` appends the bystander's completion synchronously; the next step's order 5 drains and routes it by put id; the removal guard makes it exactly once. Red before, green after, for the mechanical reason the row names. The deliverable's split (J3a and J3c on three hosts, J3b clj+cljs with the cljd lane) is restated consistently.

## Findings

**P3 | Decision 3 lifecycle table (r5 hunk @452) | One no-op cell is still missing: `:put` with the put outstanding + driver `abandon` — the symmetric twin of the `:verify-issued`-outstanding abandon row the revision just added.** Row 5's abandon clause is scoped to "while the put is `:unsent`"; the outstanding variant has no cell, though `abandon-unsent`'s `:unsent`-only scope (rpc.cljc:267–283) makes it a no-op with a later exit through response or `lose-outstanding`. **Fix:** add the row — `` `:put` (put outstanding, not `:unsent`) | driver `abandon` | **no-op**: `abandon-unsent` touches only `:unsent`; the put completes through its response or `lose-outstanding` | nothing | — `` — and, if the exhaustiveness test enumerates no-ops, extend it by this cell.

Otherwise: **none found** in the 210 changed lines. The disposition vocabulary is stated before it is used; rows 14/15's "synthesized; published under the put id" matches the corrected "routes by" preamble; row 14's cross-reference ("every *other* record with an **outstanding** hop") precisely excludes the `:verify-unissued` records the sweep already covers; the deferred-list entry, end-condition bullet, and changelog each say what the diff actually did; and the `yin.repl` citation and the filed-defect file both check out against the tree.

## Verdict

**This closes.** The `dependent` disposition is the honest form of the answer — visible condition, named owner, filed defect, and a gate that keeps the plan's own end condition false until the dependency lands — and the bystander test is a real red/green discriminator, not a decoration. The one residual P3 is a single table row, editorial in weight, and can be added by the implementer in the same change that writes the table's tests without any further review cycle. Nothing in these 210 lines is blocking, and nothing in the plan as it now stands needs another adversarial pass from this seat: the verify-hop lifecycle is specified to the same declaration-driven standard as the observer's outcome totality, and the one defect that remains is correctly owned by the layer that has it.

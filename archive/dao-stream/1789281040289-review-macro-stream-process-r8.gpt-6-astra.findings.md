I’ll check the declaration catalogue and stand-in lifetime rules, then run the selection, swap, and duplication reductions in memory. I’ll keep the review read-only.

Completed-GMT: 2026-09-13 06:31:53 GMT
Completed-Local: 2026-09-13 13:31:53 +07
Coding-Agent: codex
Session-ID: 01a098f8-8420-7083-9d18-f9776512bd61
Role: Routine Review | Model: gpt-6-astra

**Round-7 P2: Resolved.** [§3.1’s catalogue and ordinal lookup](/Users/sto/workspace/datomworld/docs/design/yin.vm.macro.md:237) distinguish same-name source definitions and restore the lambda selected by the surviving stand-in.

Both probe items are adequately stated: valid fabrication may change precedence without introducing an unharvested body, and declaration order makes only the narrow frontend-order guarantee.

No files were written. These are in-memory reductions of the specified rule, not implementation tests.

**Reductions**

Catalogue: `0 → {name m, lambda A}`, `1 → {name m, lambda B}`. The initial next-batch store is empty.

| Returned declaration order | Next binding | Result |
|---|---|---|
| Keep first: `[0]` | A | Pass |
| Keep second: `[1]` | B | Pass |
| Swap: `[1, 0]` | A | Pass |
| Duplicate first: `[0, 0]` | A | Pass |
| Duplicate second: `[1, 1]` | B | Pass |
| Unknown ordinal | None | Ignored |
| Known ordinal, mismatched name | None | Ignored |
| Plain definition, then fabricated valid `0` | A | Permitted reassertion |
| Change received `:decl 0` to `:decl 1` | B | Permitted selection |

Changing `:decl` to another same-name entry is acceptable under the stated contract. Both bodies were harvested from this batch; the transformer changes which admitted declaration survives. The catalogue, rather than the returned marker, supplies the body.

**Stale-ordinal probe**

Batch-local lookup prevents resurrection of a previous batch’s declaration body. It does **not** establish that an old marker value can never be replayed.

A marker is plain data: equivalent data can be retained externally, embedded in literal syntax, or fabricated later. The reductions show:

| Replayed marker | Current catalogue | Outcome |
|---|---|---|
| `{name m, decl 0}` | Empty | Ignored |
| `{name m, decl 0}` | `0 → {name m, lambda C}` | Selects current-batch C |
| `{name m, decl 0}` | `0 → {name n, lambda C}` | Ignored |

This is consistent with permitted fabrication. It cannot select the old body through an old catalogue.

**[P3 — suggestion/alignment] Clarify catalogue lifetime versus marker lifetime.**

Describe the guarantee as: “Stand-ins resolve exclusively against the current batch’s fresh catalogue; they carry no cross-batch declaration identity.” Avoid claiming that marker-shaped data cannot survive outside one expansion.

No batch nonce is required under the chosen fabrication semantics. A nonce would be necessary only for a stronger stale-marker rejection guarantee that this design does not currently promise.

No blocking design findings remain in this confirmation. Approval remains subject to implementing and validating the documented Phase 0 prerequisites and acceptance tests.

**Verdict: APPROVE.**

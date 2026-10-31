Coding-Agent: claude
Model: claude-fable-5-1
Role: Lead System Architect

# Final sign-off: dao.jing.cbor.md (review round 4 of 4)

Completed-GMT: 2026-09-18 04:45:00 GMT
Completed-Local: 2026-09-18 11:45:00 +07 (Asia/Ho_Chi_Minh)

## Verdict: FINAL SIGN-OFF (approve)

All four glm-5.3 corrections check out against the actual source and against each other. No new contradiction was introduced, and no earlier r1/r2 correction was undone. Three self-disclosed pre-implementation checks remain in the document (not defects) — listed below alongside the two already-known owner items.

## Spot-checks performed

**F1 (query `=` semantics) — confirmed accurate.**
- `src/cljc/dao/space/query.cljc:751` is exactly `'= =,` inside the `builtins` map — the query `=` builtin binds host Clojure `=` directly, as cited.
- JVM `(= 1 1.0)` is `false` (Clojure's `=` is numeric-category-strict; `==` is the cross-category numeric comparator, not `=`) — the doc's claim is correct.
- `compare-vals` (`src/cljc/dao/space/index.cljc:65-71`) dispatches to host `compare` on equal type-rank, and JVM `(compare 1 1.0)` is `0` — so the doc's split claim ("covered-index membership already uniform, query `=` is not") is accurate in both halves, not just asserted.
- The corrected text (`:282-295`) is the only place in the document making the "uniform with today's JVM behavior" claim (`grep` found exactly one occurrence) — no stale duplicate of the old, overbroad version survives elsewhere.

**F2 (`dao.data.btree` reference) — confirmed accurate.**
- `docs/design/dao.data.btree.md` §5.2 (read directly, `:683-707` region) states verbatim: *"When the canonical byte encoding lands... the default flips to on and the same-host restriction disappears — the check itself needs no format change, only a stable encoding under it. That work is not a Phase 1–4 deliverable of this design."* — this is precisely the pre-authorization the corrected `dao.jing.cbor.md` (`:480-489`) now cites. The characterization as "a default flip, not new or changed code" is not the reviewer's inference, it's the target document's own stated design.
- The three references to this (Numeric identity aside `:247-253`, Implementation step 3 `:469-477`, step 5 `:480-489`) all now use consistent language ("default flip," "pre-authorized," "not new or changed code") — no lingering "mechanical swap" or "one change outside `dao.jing*`" phrasing survives (`grep` confirms).

**F3 (file-backend causal reasoning) — confirmed coherent, and it resolves the gap I flagged in r2.**
- The new Objective text (`:24-40`) grounds the exception in re-ingestion-across-process-death rather than "built-in vs. third-party," and explicitly corrects the misclassification I raised in r2 (memory/remote/DHT are equally in-repo, not equally "third-party" to unbuilt PostgreSQL/S3). This is a strictly better argument than what r1/r2 converged on.
- It also resolves my own r2 residual note: `## Backend changes` (`:334-337`) now opens with an explicit pointer back to the Objective's carve-out before its `### Memory and files` subsection, so a reader hitting that section first no longer has to reconstruct the exception from memory. The bare `backend: memory | file | PostgreSQL | S3 | remote | DHT | ...` diagram (`:46`) is still undifferentiated, but it now sits *below* an Objective paragraph that already explains the exception in detail — a minor residual, not a contradiction.
- `remote.cljc:55-60,84-85` was checked in an earlier round of this same review series and matches the corrected envelope shape `{:found? boolean, :value v}` cited at `:102-104`.

**F4–F6 — read for internal consistency, no source contradiction found.** The narrower supported-values domain (F4), the unrecoverable-content precondition (F5), and the remote envelope correction (F3/F6) are each stated once, in the section where they're substantively relevant, and don't conflict with anything stated elsewhere in the document.

## Consistency across all four review rounds

No correction from any round contradicts an earlier one. Specifically checked: r1's file-backend finding → r2's terminological critique of it → this round's causal reframing form a single coherent throughline, each round strictly sharpening rather than reversing the last; r2's "one change outside `dao.jing*`" critique → this round's precise btree-reference resolution likewise converge rather than conflict.

## What remains before this plan is ready to build against

Beyond the two owner-facing items already named in the document (the `dao.space` sign-off on the comparator/query-semantics change, and the owner's still-open D3 decision in `yin.vm.code-as-tuples.implementation-plan.md`), the document now self-discloses three concrete pre-implementation checks that should be closed out, not because the plan is wrong, but because it correctly declines to guess at them:

1. **`min`/`max` tie-break rule for the portable numeric operations** (`:291-295`) — currently unpinned; needs a decision before implementation, since host `min`/`max` tie-break behavior (`(min 1 1.0)` ⟹ `1.0` on the JVM) is otherwise silently inherited rather than chosen.
2. **Rebuild readiness against retained intake-stream history** (`:423-430`) — the clean break's rebuild path only works if nothing needed has already been evicted from its intake stream; this should be checked against actual retention before the migration lands, not assumed.
3. **Supported-value-domain delta check** (`:145-154`) — `#inst`, `#uuid`, and characters are addressable under today's transitional encoder and have no slot in the new CBOR domain; nothing currently emits them, but that should be confirmed against real stored/produced data before rebuild, not discovered during it.

None of these are architectural defects — they're exactly the kind of scoped, checkable preconditions a plan document should surface rather than paper over. With those three checks scheduled alongside the two owner sign-offs, this document is ready to build against.

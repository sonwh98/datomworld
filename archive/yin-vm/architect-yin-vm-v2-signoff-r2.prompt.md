Created-GMT: 2026-09-02 18:00:17 GMT
Created-Local: 2026-09-03 02:00:17 Asia/Shanghai

# Role: Lead Systems Architecture Reviewer — sign-off, round 2

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-09-03 01:53:55 Asia/Shanghai | Status: active | Rationale: Architect seat; sign-off authority
- Round 2 | Assigned: 2026-09-03 02:00:17 Asia/Shanghai | Rationale: verify the two required changes landed

You withheld sign-off on `docs/design/yin.vm.implementation-plan.md` with two
required changes and three minor findings. All five were applied. Verify.

## Your HIGH finding, verified independently before acting

I checked your claim rather than taking it: `next-outcome`
(`ringbuffer.cljc:109-119`) advances the cursor and never `:head`; only
`drain-one-state` does (72, 161); `engine_test.cljc:360-370` unblocks its parked
writer by hand-advancing `:head`. Your conclusion holds — a v2 reject-mode ring
buffer would be full forever and a parked `:stream/put` would never wake.

## What changed

1. **Reject-mode request withdrawn** (was lines 169-175). Replaced with the
   recorded divergence, stating that v1 park-on-full was reject-mode *plus
   destructive take*, naming the three code sites, and pointing backpressure
   semantics at the deferred queue interpreter or flow control above the stream.
   No "same results" claim now covers park-on-full programs.
2. **Cursor provenance rule added.** Every fabricated cursor is minted
   `:dao.stream/oldest` when its handle enters the VM store, with the
   pre-filled-stream parity hazard stated, and `:stream/cursor` now handling
   `closed` and `transport-error`.
3. **`take!` removed** from the v2 `stream` module rather than reinterpreted,
   with your reasoning: v1's `take!` takes a stream ref, so a v2 version needs an
   implicit per-stream reader position, which is what the contract retired. It
   is now the fourth user-visible change, and the count is corrected everywhere.
4. **`:module/require`**: the registry value carries effect handlers; the clj
   `clojure.core/require` inside effect dispatch does not survive.
5. **Telemetry classification** tests all three surface protocols before the
   `map?` branch, so a writer-only record is not walked field by field.
6. **FFI capacity rule**: maximum-outstanding plus one, and a `gap` at the
   bridge cursor is fatal, not resumable.

The consumer plan `docs/design/yin.repl.implementation-plan.md` was updated
to carry all four user-visible changes.

## What to judge

Only two questions:

1. **Did the two required changes land correctly**, in substance rather than
   wording? In particular, is the divergence record now accurate about *why* v1
   backpressure cannot be reproduced, and is the cursor rule sufficient for an
   implementer to mint every one of the 36 sites without a further decision?
2. **Does anything in the five changes introduce a new defect?** A fix that
   relocates its problem is the failure mode this project has hit repeatedly.

Read:
- docs/design/yin.vm.implementation-plan.md      (under review)
- docs/design/yin.repl.implementation-plan.md    (consumer)
- docs/design/dao.stream.md, docs/design/datom.world.md
- src/cljc/dao/stream/ringbuffer.cljc, src/cljc/yin/module.cljc,
  src/cljc/yin/vm/{engine,ffi,telemetry}.cljc

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

End with an explicit line:

SIGN-OFF: GRANTED   — implementable as written, or
SIGN-OFF: WITHHELD  — followed by the shortest list of changes that would earn it.

Do not edit files.

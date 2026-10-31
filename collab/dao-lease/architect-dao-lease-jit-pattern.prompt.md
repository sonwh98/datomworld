Created-GMT: 2026-09-01 16:29:00 GMT
Created-Local: 2026-09-01 23:29:00 Asia/Ho_Chi_Minh

# Task: should `dao.lease` follow the pattern already established by `jit-design.md`?

Role: Lead System Architect

## Context

You have just proposed a `dao.lease.md` built on leases-as-datoms. The user
observes that this project already has a worked precedent for the authority
structure a lease needs: `docs/design/jit-design.md`.

Read it. The pattern there is:

- execution facts are emitted as datoms on a trace stream, never as callbacks
  or a mutable side channel;
- the JIT observes that stream and is **advisory** — it emits *patch datoms*
  proposing changes and never mutates running execution;
- the VM applies patches **only at explicit safe points** it already passes
  through (function entry, loop header, backward jump target, return
  boundary), on its own schedule;
- a patch carries an explicit status — `:proposed | :accepted | :rejected |
  :rolled-back` — so the negotiation is itself queryable data;
- a failed guard emits a deopt datom and falls back to baseline.

The apparent mapping to a lease:

| jit-design | lease |
|---|---|
| trace datoms emitted by execution | renewal evidence emitted by the holder |
| JIT observes, advisory only | holder requests; only grantor-authored facts establish terms |
| VM applies at explicit safe points | grantor judges lapse and reclaims on a cadence it already owns |
| `:proposed/:accepted/:rejected/:rolled-back` | `:requested/:granted/:refused/:lapsed/:released` |
| guard failure emits deopt datom | lapse judged; reclaim appended as a fact |

## The question

**1. Is the parallel real or superficial?** If real, say precisely what carries
over and what does not. Two candidate differences to test rather than assume:

- `jit-design` has no time dimension. A lease's whole content is a duration,
  and a clock has to be read by someone. Does the safe-point model survive
  that, or does time force something the JIT pattern never needed?
- A JIT patch is optional — if it is never applied, execution is merely
  slower, and nothing is wrong. A lapsed lease must *eventually* be acted on
  or the resource leaks. Does "advisory" still hold when the consequence of
  never acting is unbounded, and if so, what carries the obligation?

**2. Should `dao.lease.md` adopt the pattern explicitly** — same shape, same
status-as-data discipline, same safe-point application — or is it a different
pattern that merely resembles it? Answer plainly; do not split the difference.

**3. If yes, revise your proposed `dao.lease.md` accordingly** and give the
revised content. State what changed from your previous draft and why. If the
pattern's status vocabulary is right, use it rather than inventing a parallel
one; if it is wrong for leases, say which status the lease needs that a patch
does not, or vice versa.

**4. Does the parallel change the seam?** `jit-design` puts the trace protocol
in the VM and the patch stream outside it. If the lease follows the same shape,
does that move where lease vocabulary lives relative to `dao.stream.ws.md` and
the serving composition?

## Scope

Read: `docs/design/jit-design.md`, `docs/design/datom.world.md`,
`docs/design/dao.stream.md`, `docs/design/dao.stream.ws.md`. **No source code.**

Propose only. **Make no edits.**

Begin your final response with:

```
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS Asia/Ho_Chi_Minh>
```

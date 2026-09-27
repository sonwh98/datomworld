# ShiBi

Status: stub. ShiBi is not specified and not implemented
(`docs/bootstrap.md`, Capability Engine). This page records only what other
documents may rely on and what ShiBi must supply. It designs nothing.

## What it is

ShiBi is the capability system of datom.world: authentication and
authorization are its job and no other document's. Owner direction
(2026-09-28): ShiBi is pure data -- the capability is an emergent
property of interpreters of pure data, exactly as dao.space is the
emergent property of dao.space.index and dao.space.query. There is no
token object to mint and no capability stored anywhere: grant,
attenuation, revocation and budget are patterns of interpretation over
tuples, and different interpreters over the same tuples can realize a
capability system, a metering system, or something else entirely. It
is built the way `dao.space` is built, as a tuple space that emerges
from two interpreters over ordinary streams, one that indexes and one
that queries, neither knowing the other exists (`datom.world.md`,
Streams). Earlier Macaroon-style token framing
(`dao.space.security.md`) is superseded by this direction: tokens
would be one interpreter's emission, not ShiBi's substance.

## How it plugs in

ShiBi integrates through the middleware seam of
[`dao.stream.middleware.md`](./dao.stream.middleware.md) and the attachment
points of [`dao.stream.remote.md`](./dao.stream.remote.md) section 7, which
are fixed and which ShiBi must not change. The fit to two interpreters is
this, and it is the one path this version chooses:

- **Its index interpreter** observes ShiBi's fact streams (grants,
  caveats, revocations, emitted decisions) with its own cursors,
  materializes them as `dao.space.index` materializes covered indexes, and
  publishes the resulting immutable query value, a relation value or an
  opened published index, as the decision onto the gate's capacity-1
  decision medium (`dao.stream.middleware.md`, gate). The value published
  is the tuple space's state at that moment.
- **Its query interpreter is `verify`**, and `verify` is the required
  adapter contract: a pure function of one immutable query value, the
  context and the request, evaluating ShiBi's authorization rules as a
  Datalog query with the request as bindings, the shape
  `dao.space.query/q` already has (a query over immutable inputs, opening
  nothing), and answering nil or a reason.
- **A `present`**, a pure function of context and request that produces
  the value carried in `:dao.stream.remote/credential`.
- **A reason vocabulary** under its own namespace for
  `:dao.stream.middleware/reason`.
- **Where use is metered**, the decision facts the gate emits, which the
  index observes like any other fact stream.

**Limits of the adapter contract**, stated precisely. `verify` runs
synchronously inside one operation on one snapshot: its cost per operation
is bounded by the composition, and a query over an opened published index
resolves only what is already local, because `verify` opens, fetches and
waits for nothing; a rule that would need a remote read is a rule the index
must have materialized. Its input is the latest published snapshot, so a
grant or revocation is effective only once the index has observed and
published it: staleness is bounded by the index's cadence and the medium's
eviction, and a policy that must fail closed does so on the `none` marker
and the `ended` marker. A decision is per call and is not itself a fact
unless the gate emits it.

**Non-goal in this version.** Request-specific decisions published through
ordinary streams, the query interpreter reading requests off a stream and
appending one decision per request for the gate to await, are not
specified: a gate awaiting a per-request answer would wait, and no
operation waits. If a later ShiBi needs that path it is an addition beside
`verify`, not a change to it.

The contract's refusal outcome is `:dao.stream/refused` and is
capability-free (`dao.stream.md`, Result Convention). Lease attribution
rests on per-author media and does not depend on ShiBi
(`dao.stream.remote.md`, section 6; `yin.repl.link-policy.md`).

## What blocks it being load-bearing

`dao.stream.discovery.md`, The ShiBi precondition: ShiBi is a capability
token in one place and a fungible currency in another, and a currency needs
double-spend resolution, which is global consensus. Until that decision is
made, no design may depend on ShiBi for anything beyond the seam above, and
gating in this repository is an allow-list or a pre-shared value.

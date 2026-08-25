# DaoStream Redesign Plan

Status: agreed design target. This document defines the DaoStream redesign and
its implementation order. It does not redesign Yin.VM. Yin.VM is a downstream
consumer and will be addressed only after this contract is implemented and
tested.

## Purpose

DaoStream is the passive, composable substrate for IO in datom.world. A stream
carries values. It does not decide what those values mean, which interpreter
observes them, or when an interpreter runs.

The redesign separates four concepts:

1. A **creation specification** is data for creating a new logical stream.
2. A **realization** is a host-local operational stream value.
3. A **portable descriptor** is minimal data naming one hosted entry point for
   an existing logical stream.
4. A **cursor** is immutable interpreter-owned observation state for one
   logical stream.

Creation is not attachment. A descriptor is not a creation specification. A
realization is not portable identity. A cursor is not realization-owned state.

## Axioms

- Stream elements are data. DaoStream assigns them no intrinsic event, command,
  tuple, or datom meaning.
- Reading is non-destructive. A reader advances its own cursor, not the stream.
- Multiple cursors may observe the same retained value independently.
- Interpreters own cursors and decide when to call `next`.
- A runtime may drive an interpreter, but DaoStream does not require a runtime.
- Realizations report readiness; they do not schedule or resume interpreters.
- Wrapper composition is demand-driven. Ordinary wrapping starts no pump and
  performs no data-plane reads.
- Closing a stream changes future availability but does not itself erase
  retained history.
- Retention loss is explicit as `:dao.stream/gap`; cursors are never silently
  repositioned.
- All expected operational outcomes are data, not exceptions or callbacks.

The governing demand rule is:

> A runtime may drive an interpreter; the interpreter drives the stream.

## Scope

This plan owns:

- creation, attachment, and portability;
- realization capabilities;
- cursor construction and lineage;
- non-destructive reads;
- append, close, and detach semantics;
- wrapper composition;
- retention and gap semantics;
- optional host-local waiter registration;
- closed, immutable host transport dispatch; and
- reusable conformance tests.

This plan does not own:

- Yin.VM interpretation or scheduling;
- generic observation or subscription registries;
- GUI event semantics;
- effect executors;
- work-queue claiming or acknowledgement;
- arbitrary runtime plugin registration; or
- backward-compatible aliases for the current API.

The current implementation and its tests are evidence about behavior, not
constraints on this redesign. Tests that express the new contract are retained
or rewritten; tests that preserve superseded APIs or semantics are deleted. No
compatibility facade, alias, transitional result shape, or dual protocol is
required.

## Result Convention

Every public operation that may fail returns a map with
`:dao.stream/result`. Result values are qualified keywords.

Each operation family has a closed set of top-level result values:

- creation, attachment, portable projection, and cursor construction:
  `:dao.stream/ok` or `:dao.stream/error`;
- `next`: `:dao.stream/ok`, `:dao.stream/blocked`, `:dao.stream/end`,
  `:dao.stream/gap`, or `:dao.stream/error`;
- `append!`: `:dao.stream/ok`, `:dao.stream/full`,
  `:dao.stream/closed`, or `:dao.stream/error`;
- `close!`: `:dao.stream/ok`, `:dao.stream/closed`, or
  `:dao.stream/error`;
- `detach!`: `:dao.stream/ok`, `:dao.stream/detached`, or
  `:dao.stream/error`;
- `teardown!`: `:dao.stream/ok`, `:dao.stream/torn-down`, or
  `:dao.stream/error`;
- waiter registration: `:dao.stream/registered`, `:dao.stream/ready`, or
  `:dao.stream/error`; and
- waiter cancellation: `:dao.stream/cancelled`,
  `:dao.stream/not-registered`, or `:dao.stream/error`.

These sets are per operation, not one vocabulary accepted from every
operation. `:dao.stream/error` carries a separate qualified cause.

`:dao.stream/wake` is an optional orthogonal field on a readiness-changing
result map. It is not a result value and does not expand any operation family's
closed set. A result may therefore be, for example, `:dao.stream/ok` with a
wake vector, or `:dao.stream/closed` with a wake vector.

Success:

```clojure
{:dao.stream/result :dao.stream/ok}
```

Failure:

```clojure
{:dao.stream/result :dao.stream/error
 :dao.stream/cause  :dao.stream/invalid-descriptor
 :dao.stream/detail optional-data}
```

Transport implementations may add fields in their own namespaces. Callers must
not require transport-specific fields unless they explicitly depend on that
transport.

Common error causes are:

- `:dao.stream/invalid-creation-spec`
- `:dao.stream/invalid-descriptor`
- `:dao.stream/unsupported-type`
- `:dao.stream/unsupported-operation`
- `:dao.stream/unavailable`
- `:dao.stream/unauthorized`
- `:dao.stream/not-found`
- `:dao.stream/not-portable`
- `:dao.stream/invalid-origin`
- `:dao.stream/invalid-cursor`
- `:dao.stream/cursor-mismatch`
- `:dao.stream/duplicate-token`
- `:dao.stream/detached`
- `:dao.stream/transport-error`

Malformed programmer input and expected transport failures use these result
maps. Implementations must not use exceptions as an alternate public outcome.
A host defect may still fail outside the DaoStream contract. A host defect is
an implementation invariant violation, such as an impossible internal state,
not a foreseeable operational failure such as invalid input, permission
denial, disconnection, timeout, or unavailable transport.

## Creation and Attachment

### Creation Specification

A creation specification describes a new logical stream:

```clojure
{:dao.stream/type :dao.stream.ring-buffer/stream
 :dao.stream/capacity 1024
 :dao.stream/retention :dao.stream.retention/evict-oldest}
```

Only `:dao.stream/type` is common. Other construction fields belong to the
selected implementation. A creation specification does not require a portable
identity or entry point. The creator mints the new logical identity.

```clojure
(ds/create! creation-specification)
;; =>
{:dao.stream/result      :dao.stream/ok
 :dao.stream/realization realization}
```

`create!` always creates. It never resolves an existing logical stream as a
fallback.

### Portable Descriptor

A portable descriptor is minimal, transparent data naming one hosted entry
point:

```clojure
{:dao.stream/descriptor-version 1
 :dao.stream/type               :dao.stream.ws/stream
 :dao.stream/identity           stream-identity
 :dao.stream/entry              transport-entry
 :dao.stream/shibi              optional-token}
```

`stream-identity` is serializable plain data compared by structural equality.
The creator or hosting authority mints it when a logical stream is created.
It must be unique and never reused within that authority's identity domain;
for a descriptor intended to cross hosts, that authority domain itself must be
named or globally collision-free. Identity collisions are host defects.
`transport-entry` is serializable
transport-owned data. DaoStream treats it as opaque, while the selected
transport handler validates its shape and meaning.

For a portable transport, serializable means that the transport's declared
codec round-trips the value without changing its structural equality. The
codec must be available on every supported host (CLJ, CLJS, or CLJD); functions,
mutable handles, object identities, and host-local references are never
portable data. A transport documents its codec in the descriptor type rather
than relying on an ambient runtime serializer.

The transport may add qualified fields. The descriptor does not contain:

- a creation specification;
- wrapper composition;
- cursor state;
- buffer contents;
- retention configuration;
- socket handles;
- continuations; or
- host-local resolution context.

One descriptor names one attachment route. `:dao.stream/identity` names the
logical stream independently of the optional Shibi authority token. The token
is a bearer capability; its claims are the source of truth for authority and
must not be duplicated as ambient rights. A descriptor without a Shibi token
names a public entry point only when that transport permits public attachment.

DaoStream does not mint, delegate, or interpret Shibi credentials. The
transport or host authority that creates an entry point mints the token and
defines its claims. `attach!` validates the token, when present, as part of
resolving the entry point; an invalid, expired, or insufficient token returns
an unauthorized error. Operation-specific authority is then derived into the
attached realization. Token refresh, revocation, and delegation remain
transport or authority-service concerns and never become ambient DaoStream
state.

### Attachment

```clojure
(ds/attach! descriptor)
;; =>
{:dao.stream/result      :dao.stream/ok
 :dao.stream/realization realization}
```

`attach!` takes only the descriptor. It attaches to the named existing stream
or returns an error. It never creates a replacement when the entry point is
missing, unavailable, stale, or unauthorized.

An `:dao.stream/ok` attachment means the host constructed a usable local
realization. It does not promise future liveness or durability. Later transport
failure remains explicit operation data, and the descriptor may be attached
again.

Attachment derives operation authority only from the entry policy and the
optional Shibi claims carried by the descriptor. Attaching twice with different
authority may produce two realizations for the same logical stream with
different permitted operations.

For example, a transport may define claims such as
`{:dao.stream/read true :dao.stream/append false :dao.stream/close false}`;
the resulting realization exposes reading but returns `:dao.stream/unauthorized`
for supported append or close operations. Claim names and verification remain
transport-specific; the mapping from verified claims to realization authority
must be explicit in that transport's contract.

### Portable Projection

```clojure
(ds/portable-descriptor realization)
;; =>
{:dao.stream/result     :dao.stream/ok
 :dao.stream/descriptor descriptor}
```

A local-only realization returns:

```clojure
{:dao.stream/result :dao.stream/error
 :dao.stream/cause  :dao.stream/not-portable}
```

`portable-descriptor` is a projection. It performs no hosting, exposure,
registration, network setup, or hidden wrapping.

Attenuating a Shibi capability is transport-specific. A caller that needs a
less-privileged descriptor must obtain it through the transport's explicit
delegation/attenuation operation before calling the generic projection; the
generic `portable-descriptor` operation never silently changes authority.
`portable-descriptor` returns exactly the token with which the realization was
constructed or attached, omitting `:dao.stream/shibi` when it has none. A
transport's attenuation operation returns a narrower realization or a
descriptor directly; it is not an input to the generic projection.

The portability invariant is:

> For a realization whose portable projection succeeds, attaching its
> descriptor reconstructs access to that exact hosted relation's value and
> cursor space.

The invariant has no promise for a local-only realization: its projection
returns `:dao.stream/not-portable`. A descriptor for a hosting wrapper names
the exact relation exposed by that wrapper, not an inner relation that the
wrapper transforms.

The invariant concerns logical identity and cursor interpretation, not a
promise that every endpoint has the same retention window. A hosted logical
stream has exactly one retained window, owned by its stream-wide authority and
shared by every host-local attachment. A remote endpoint maintains its own
independently bounded window over the same canonical positions. A cursor that
is valid for the logical stream may therefore return a gap after reattachment
at a different endpoint when the required position is no longer retained
there.

A cursor obtained from that realization is usable with any later realization
attached from the same descriptor, provided its version and identity remain
valid and its position remains retained. Detaching and reattaching changes the
host-local realization, not the cursor lineage.

Every logical stream has one authority for its stream-wide facts: position
assignment, the closed flag, and the logical tail. For a hosted stream this is
the hosting realization or its explicit host-owned stream state; all host-local
attachments route appends and close operations through that authority. Appends
are totally ordered there, and a successful remote `append!` or `close!`
returns only after the authority has applied it. A remote `cursor :dao.stream/tail`
must snapshot the canonical tail; if it cannot obtain that snapshot it returns
`:dao.stream/error` with `:dao.stream/unavailable`, never a stale position.
Host-local attachments share one logical-stream synchronization domain for
these facts, retention, and wake ordering. A local-only stream has a single
realization and that realization is its domain. Transport endpoints on
different hosts do not share memory or waiter tokens; the transport protocol
must carry canonical positions, closure, and ordering information needed by
its cursors.

An outer wrapper must not return an inner descriptor when it changes values,
cursor space, or lifecycle. Such an outer realization is local until an
explicit hosting wrapper exposes that exact relation.

### Static Host Dispatch

`create!` and `attach!` select implementations through an immutable,
host-compiled dispatch table in a composition namespace above the core
protocols:

```clojure
{:dao.stream/create
 {:dao.stream.ring-buffer/stream ring-buffer/create!}

 :dao.stream/attach
 {:dao.stream.ws/stream  ws/attach!
  :dao.stream.udp/stream udp/attach!}}
```

The table is ordinary closed code/data, not a mutable multimethod or namespace
load-time registry. Unknown types return an error with cause
`:dao.stream/unsupported-type`. Adding a transport requires changing and
rebuilding the host composition.

The dispatch structure is immutable, but a handler may close over explicit
host-owned state supplied when the host composition is constructed. That state
is never found through a hidden global. This is how an attachment handler can
resolve a descriptor while `attach!` continues to take only the descriptor.

The explicit host-owned stream table used by a hosting facade is distinct from
the dispatch table. It maps hosted entry identities to realizations and may
change as entries are created or detached. The prohibition on mutable
registries applies to handler dispatch and type registration, not to this
explicitly injected stream state.

The one-argument `create!` and `attach!` signatures describe host-facing
functions returned by construction of that explicit composition. They close
over its immutable dispatch structure and injected handler state. They are not
namespace globals that discover a current host or resolution context.

The signatures in this document show the functions after host composition has
bound that state. A host may construct multiple independent `create!` and
`attach!` function pairs; the descriptor remains their only attachment
argument.

The composition boundary is explicit:

```clojure
(dao.stream.host/compose
  {:dao.stream/dispatch dispatch-table
   :dao.stream/state    host-state})
;; =>
{:dao.stream/create! create!
 :dao.stream/attach! attach!}
```

The host-owned state supplies the hosting and attachment facades. The hosting
facade registers and tears down hosted entries; the attachment facade resolves
existing descriptors only. `create!` registers an entry only when its selected
creation handler is a hosting handler. A local creation handler returns a
local-only realization, and `portable-descriptor` never performs registration
as a side effect.

A hosting creation handler returns the realization and its hosted entry data in
the standard result map. The host composition then performs the registration:

```clojure
{:dao.stream/result      :dao.stream/ok
 :dao.stream/realization realization
 :dao.stream/identity    stream-identity
 :dao.stream/entry       transport-entry}
```

When the selected creation handler is a hosting handler, the host-facing
`create!` result carries the same `:dao.stream/identity` and
`:dao.stream/entry` fields; a local creation handler returns only the
realization. These fields are the same values `portable-descriptor` later
projects; there is no second identity-minting path.

### Hosting Teardown

The hosting facade registers the identity-to-realization relation before
`create!` returns. Logical `close!` never deregisters the hosted entry: a
closed but still registered descriptor remains attachable, and a new
attachment drains whatever history the transport retains before returning
`:dao.stream/end`. Deregistration is performed only by `teardown!`, a distinct
lifecycle operation exposed by the hosting owner's realization. It is not
`detach!`, which exists only for attachment realizations. A transport must
state which realization owns teardown authority.

```clojure
(ds/teardown! realization)
```

`teardown!` resolves dynamic conditions in this order: unsupported capability,
already torn down, missing teardown authority, then success. The corresponding
results are an `:dao.stream/unsupported-operation` error,
`:dao.stream/torn-down`, an `:dao.stream/unauthorized` error, and
`:dao.stream/ok`. Successful teardown deregisters the entry, marks the owning
realization torn down, marks every other host-local attachment detached, and
transfers every waiter token in domain registration order. It does not itself
close the logical stream, and close is not a precondition. After teardown, every
operation through the owning realization other than a repeated `teardown!`
returns an error with cause `:dao.stream/detached`; repeated teardown returns
`:dao.stream/torn-down`. The torn-down owner deliberately reuses the detached
cause so callers handle every dead realization identically;
`:dao.stream/torn-down` remains a `teardown!` result only. After
deregistration, `attach!` returns `:dao.stream/not-found`.

Creation-specification validation and descriptor validation are distinct and
occur in two phases. Generic validation checks the structural envelope,
including descriptor version and type keyword, before dispatch. The selected
handler validates type-specific entry data and returns errors such as
`:dao.stream/invalid-descriptor`, `:dao.stream/not-found`, or
`:dao.stream/unavailable`. A handler that violates the result contract
produces an error with cause `:dao.stream/transport-error`; there is no
fallback handler.

## Realization Capabilities

Realizations expose orthogonal capabilities. A wrapper exposes only the
capabilities it can honestly interpret.

The canonical reader capability is conceptually:

```clojure
(cursor realization origin)
(next realization cursor)
```

Other capabilities are independent:

```clojure
(append! realization value)
(close! realization)
(teardown! realization)
(detach! realization)
```

A realization may be read-only, write-only, closeable, detachable, or any
meaningful combination. Unsupported operations return
`:dao.stream/unsupported-operation` as an error cause. Capability support and
operation authority are distinct:

- capability support says whether the realization implements an operation;
- authority says whether this realization may exercise that operation; and
- dynamic state says what happened when an authorized, supported operation was
  attempted.

A realization is the capability boundary for exactly the operation authority
granted when it was constructed. `create!` derives that authority from creation
and hosting policy; `attach!` derives it from entry policy and optional Shibi
claims. Possessing a realization grants the rights it was constructed with and
no others. Possessing another realization for the same logical stream grants
only that realization's independently derived rights.

An unsupported operation returns an error with cause
`:dao.stream/unsupported-operation` before any operation-specific dynamic
decision tree is entered. The capability check is the only universal prefilter;
each operation's section defines where its per-realization authority check
occurs. A supported operation for which this realization lacks authority
returns an error with cause `:dao.stream/unauthorized`.
For `next`, a realization either exposes an authorized reader capability or
`next` is rejected before cursor-state evaluation. A reader capability is
normally granted at creation or attachment by the entry policy; it is not
inferred from the mere existence of a cursor.
`closed?` is not part of the public API; operation results are authoritative
and avoid time-of-check races.

Capability narrowing has one fixed interpretation: a wrapper that does not
implement an operation returns `:dao.stream/unsupported-operation`; an
operation implemented by the realization but denied by creation policy, entry
policy, or Shibi claims returns `:dao.stream/unauthorized`. A wrapper may
narrow its inner capabilities, but must document that narrowing as an
unsupported operation at the outer boundary.

`portable-descriptor` is a universal projection over realizations, not an
optional realization capability. Portability is a property of the realized
relation: a hosted relation returns its descriptor, while a local-only relation
returns an error with cause `:dao.stream/not-portable`.

Projection through a detached or torn-down realization returns an error with
cause `:dao.stream/detached`; callers use a descriptor retained as data or a
separate attachment to resume. Projection does not revive a realization.

Destructive consumption is not a DaoStream capability. Work queues may define
separate operations such as claim and acknowledge in a queue-specific
namespace.

## Cursors

A cursor is immutable, opaque plain data owned by an interpreter. It is
portable when paired with a descriptor for its logical stream.

All cursors use a common lineage envelope:

```clojure
{:dao.stream/cursor-version 1
 :dao.stream/identity       stream-identity
 :dao.stream/position       transport-or-wrapper-data}
```

The cursor version is part of the common envelope. Unknown or unsupported
versions return `:dao.stream/invalid-cursor`; implementations do not silently
downgrade or reinterpret a cursor under another version. The position payload
must satisfy the same declared transport codec whenever the descriptor is
portable. A failed round-trip is an invalid cursor or not-portable result,
never a silent identity conversion.

Callers treat `:dao.stream/position` as opaque. A wrapper may store an inner
cursor and serializable per-observer interpretation state there.

Cursor construction is explicit and returns a concrete snapshot:

```clojure
(ds/cursor realization :dao.stream/beginning)
(ds/cursor realization :dao.stream/tail)
```

Any other origin value returns `:dao.stream/error` with cause
`:dao.stream/invalid-origin`.

Constructing or validating a cursor requires the same authorized reader
capability used by `next`; a write-only or unauthorized caller is rejected
before the origin or tail position is evaluated.
There is no separate public cursor-validation operation: lineage validation is
performed only by cursor construction and `next` (and by waiter registration
when it evaluates its `next` demand).

Success is:

```clojure
{:dao.stream/result :dao.stream/ok
 :dao.stream/cursor cursor}
```

`:dao.stream/beginning` always means the stable logical origin. It does not move when
retention evicts early history. `next` from that cursor reports a gap when the
origin is no longer retained.

`:dao.stream/tail` snapshots the logical tail: the position immediately after the latest
append, or the origin when no append has occurred. On an open stream, `next`
from that cursor yields `:dao.stream/blocked` until another value appears. On a
closed stream at its final tail, it is end. On a stream with no appends,
`:dao.stream/tail` and `:dao.stream/beginning` are the same position. Reading that position yields
`:dao.stream/blocked` while the stream is open and `:dao.stream/end` after it
closes; close itself does not occupy a stream position.

Cursor validation distinguishes:

- structurally malformed or unsupported cursor data:
  `:dao.stream/invalid-cursor`;
- a well-formed cursor for another logical identity:
  `:dao.stream/cursor-mismatch`; and
- a valid same-stream cursor whose value was evicted:
  `:dao.stream/gap`.

A replacement logical stream must mint a new identity. Reusing an old identity
for a different stream violates cursor lineage. Stream identities must be
unique and must never be reused for distinct logical streams within a host.

## Reading

`next` is total and non-blocking:

```clojure
(ds/next realization cursor)
```

Value available:

```clojure
{:dao.stream/result :dao.stream/ok
 :dao.stream/value  value
 :dao.stream/cursor next-cursor}
```

No value currently available on an open stream:

```clojure
{:dao.stream/result :dao.stream/blocked}
```

Logical stream closed and no retained value remains at this position:

```clojure
{:dao.stream/result :dao.stream/end}
```

Valid cursor behind retention:

```clojure
{:dao.stream/result           :dao.stream/gap
 :dao.stream/available-cursor earliest-available-cursor}
```

Operational failure:

```clojure
{:dao.stream/result :dao.stream/error
 :dao.stream/cause  cause}
```

After the capability and authority checks described in Realization
Capabilities, a supported authorized reader resolves dynamic conditions in
this order:

1. A detached realization returns an error with cause
   `:dao.stream/detached`.
2. A malformed or unsupported cursor returns an error with cause
   `:dao.stream/invalid-cursor`.
3. A cursor for another logical identity returns an error with cause
   `:dao.stream/cursor-mismatch`.
4. A position before the earliest retained position returns
   `:dao.stream/gap` with a recovery cursor.
5. A retained value at the position returns `:dao.stream/ok` with that value
   and the next cursor.
6. A closed stream at or past its logical tail returns `:dao.stream/end`.
7. Otherwise the result is `:dao.stream/blocked`.

These conditions form a decision tree, not a ranking among simultaneously
valid facts. In particular, a cursor behind retention on a closed stream first
reports its gap. Reading from an adopted recovery cursor then drains retained
values before reaching end.

The logical-tail rule applies to the cursor position observed at the time of
`next`. A cursor captured before a later append is no longer the tail after
that append and may read the retained value. A cursor constructed at the
current tail after the stream is closed returns `:dao.stream/end`.

For a closed stream with no appends, the logical tail is the origin and no
value is retained there; `next` at that position is always `:dao.stream/end`,
never `:dao.stream/gap`. A gap requires the cursor to precede the earliest
retained position of a stream whose logical history contains an evicted
position.

Rules:

- Only `:dao.stream/ok` supplies the ordinary next cursor.
- `:dao.stream/blocked`, `:dao.stream/end`, and `:dao.stream/error` leave the
  interpreter's cursor unchanged.
- A gap always supplies a non-nil recovery cursor, but never installs it.
- If no value is retained, the recovery cursor is exactly the current logical
  tail. Its next result is blocked while the stream is open or end after close.
  When lazy retention is evaluated during the same operation that produces a
  gap, this is the post-eviction tail and earliest-available state observed at
  that operation's linearization point. A subsequent `next` on the recovery
  cursor never has to reinterpret the recovery position.
- Reusing an unadvanced cursor observes the same retained value. It may later
  produce a gap if retention advances.
- End is terminal for that cursor position. Older retained cursor positions may
  remain readable after close.
- Reading never globally consumes or acknowledges a value.

## Writing

```clojure
(ds/append! realization value)
```

Minimal results are:

```clojure
{:dao.stream/result :dao.stream/ok}
{:dao.stream/result :dao.stream/full}
{:dao.stream/result :dao.stream/closed}
{:dao.stream/result :dao.stream/error
 :dao.stream/cause  cause}
```

Appending is atomic per value. A successful append has no mandatory cursor,
position, or acknowledgement. A transport may return additional qualified
metadata.

`:dao.stream/full` means the realization cannot currently accept the value
without violating its capacity or backpressure policy. It does not promise that
capacity will eventually become available. Appending to a logically closed
stream returns `:dao.stream/closed`, not end and not an exception.

For a realization that supports writing, `append!` resolves dynamic conditions
in this order: detached realization, missing append authority, logically closed
stream, unavailable capacity, then success. The corresponding results are a
detached error, an unauthorized error, `:dao.stream/closed`,
`:dao.stream/full`, and `:dao.stream/ok`. Thus authority is not masked by
closed state, and closed is not reported as the transient fact
`:dao.stream/full`.

When an append changes readiness, its result may also carry
`:dao.stream/wake` as defined in Wake Transfer.

## Close and Detach

### Logical Close

```clojure
(ds/close! realization)
```

The first authorized close returns:

```clojure
{:dao.stream/result :dao.stream/ok}
```

A later close returns:

```clojure
{:dao.stream/result :dao.stream/closed}
```

Close semantics are:

- the logical stream becomes terminal for every attachment;
- later appends return `:dao.stream/closed`;
- retained values remain readable;
- close itself performs no eviction;
- the configured retention policy may continue after close;
- readers drain values still retained at read time, subject to ongoing
  retention and explicit gaps, and then receive `:dao.stream/end`; and
- missing close authority returns `:dao.stream/unauthorized` as an error.

For a realization that supports close, `close!` resolves dynamic conditions in
this order: detached realization, missing close authority, already closed
logical stream, then success. The corresponding results are a detached error,
an unauthorized error, `:dao.stream/closed`, and `:dao.stream/ok`.

A close result may also carry `:dao.stream/wake` as defined in Wake Transfer.

### Local Detach

`detach!` exists only for detachable attachment realizations:

```clojure
(ds/detach! realization)
```

The first detach returns `:dao.stream/ok`; a later detach returns
`:dao.stream/detached`. Detach releases only that host-local attachment. It
does not close the logical stream, deregister the hosted entry, or affect other
realizations. Only `teardown!` deregisters an entry.

After detach, operations other than the repeated `detach!` call through that
realization return an error with cause `:dao.stream/detached`. A repeated
`detach!` returns `:dao.stream/detached` as its top-level result. The descriptor
and existing cursors remain data and may be used with a later independent
attachment.

Detach after close is valid. Attempting logical close through an already
detached realization returns a detached error because that realization can no
longer exercise authority.

A detach result may also carry `:dao.stream/wake` as defined in Wake Transfer.

### Wrapper Ownership

Passing an inner realization to a wrapper grants access, not lifecycle
ownership. Closing or detaching an outer wrapper affects the derived wrapper
only. It never calls `close!` or `detach!` on a caller-supplied inner
realization.

A specialized wrapper may propagate lifecycle operations only when ownership
was transferred explicitly at construction. The common ownership marker is
`{:dao.stream/ownership :dao.stream/transfer}` inside the owning namespace's
constructor options. This marker is the canonical ownership-transfer
mechanism; namespace-specific options may add data but may not replace or
reinterpret the marker. Merely retaining a reference is not ownership.

## Retention and Gaps

Retention belongs to the owning or hosting realization's construction and
implementation. It may
be unbounded or bounded by count, byte size, time, or another explicit policy.
It is not duplicated into the minimal portable descriptor.

Core retention rules are:

- eviction advances monotonically;
- eviction is the only generic removal mechanism;
- reads do not reclaim space;
- cursors do not register observer liveness implicitly;
- lagging cursors report gaps instead of silently moving;
- a gap recovery cursor identifies the earliest position currently available,
  or the current tail when no value is retained; and
- recovery policy belongs to the interpreting wrapper or outer interpreter.

A result from an operation that advances retention may also carry
`:dao.stream/wake` as defined in Wake Transfer.

Core retention is observed at a DaoStream operation's linearization point.
Time- or size-based policies may be evaluated lazily by `next`, append,
registration, or an explicit transport operation; they do not mutate the
stream autonomously or require a hidden retention pump. If a transport exposes
an external retention event, that event must enter DaoStream as an explicit
operation whose result carries any wake tokens.

### Transport Ingress

A pull transport, such as a file or HTTP GET, needs no ingress operation:
`next` reads its source at its own linearization point. A push transport must
declare one of two ingress shapes. With lazy drain, the endpoint drains a
host-owned receive buffer during `next`, waiter registration, or another
DaoStream operation; such an endpoint must not declare reader waiters unless
one of those operations can produce the wake. With driven ingress, the
transport exposes an explicit operation in its own namespace, for example
`ws/deliver!`, returning the standard result map with any `:dao.stream/wake`
vector. A driven process may call that operation, but the realization must not
start a callback, background pump, or scheduler of its own. Each transport
declares which ingress shape and waiter capabilities it supports.

An outer interpretation may propagate a gap, adopt the offered cursor, recover
through another explicit stream, emit loss as a value, or return another result
allowed by its own contract. Transport identity wrappers propagate gaps by
default. DaoStream does not require every wrapper to make gaps transparent.

UDP positions describe received datagrams in arrival order. A datagram that
never arrived never occupied a DaoStream position, so network packet loss is
not a generic retention gap. Sequence loss becomes content only when framing
data and another interpretation make it explicit.

## Wrapper Composition

Ordinary wrappers compose like Java IO layers or stream transformers:

```text
ordering(udp(local-stream))
```

A wrapper:

- accepts an inner realization;
- returns a distinct derived realization;
- assigns that derived stream its own host-local identity;
- performs no data-plane read during construction;
- starts no thread, callback loop, runtime, or pump;
- interprets outer demand into zero, one, or many inner `next` calls;
- returns an outer cursor containing inner resumption and per-observer state;
  and
- exposes only capabilities it can interpret.

The observing interpreter creates a cursor over the completed composition:

```clojure
(let [{cursor :dao.stream/cursor}
      (ds/cursor wrapped-realization :dao.stream/beginning)]
  (ds/next wrapped-realization cursor))
```

Each outer cursor represents an independent interpretation. Wrapper
construction does not take a source cursor.

An ordinary wrapper interprets outer `:dao.stream/beginning` and
`:dao.stream/tail` through the
corresponding inner origins. A transformation whose domain is intentionally
anchored at a particular source cursor is a separate, explicitly parameterized
wrapper; transport wrapping does not choose that observation policy.

Pure, local wrapper constructors should use `wrap`. Constructors that establish
host resources, bind endpoints, or mint a hosted stream use `wrap!`. Wrapper
construction returns the standard result map containing the derived
realization.

Wrapper constructors are namespace-specific functions, not functions in the
core `dao.stream` surface. Their argument and option shapes belong to the
owning wrapper namespace. When a wrapper supports lifecycle ownership transfer,
that constructor must receive explicit ownership-transfer data; without it,
the wrapper is a borrower and never propagates close or detach to its inner
realization.

Every wrapper constructor still shares this core contract: it returns either
`:dao.stream/ok` with a derived realization or `:dao.stream/error` with a
qualified cause; it performs no data-plane read while constructing that
realization; and it exposes only capabilities it can interpret. A wrapper that
does not host its outer relation always returns `:dao.stream/not-portable` from
`portable-descriptor`, even when its inner realization is portable. An inner
descriptor is never returned as a substitute for the outer relation.

When values must move independently of downstream demand, the system uses a
separate explicit interpreter or process:

```text
source stream -> driven interpreter -> materialized output stream
```

That process owns its source cursor and must be driven by a caller or optional
runtime. It is not ordinary wrapper construction.

### Hosting Placement

Wrapper order makes interpretation placement explicit:

```text
local -> decode -> WebSocket hosting wrapper
```

The WebSocket entry point hosts the decoded value/cursor relation, so its
descriptor is portable.

```text
local -> WebSocket hosting wrapper -> local decode
```

The outer decoded realization is local and not portable. Returning the inner
WebSocket descriptor would attach to the wrong relation. To make the decoded
stream portable, another hosting wrapper must expose it.

Descriptors therefore remain minimal entry points. `attach!` never interprets
wrapper programs.

## Optional Waiter Capability

The mandatory contract is poll-complete. An interpreter can retry after
`:dao.stream/blocked` or `:dao.stream/full` without a runtime.

Some host-local realizations may expose an optional waiter capability. It is an
efficiency mechanism, not subscription, observation, or scheduling.

### Reader Registration

```clojure
(ds/register-reader-waiter! realization cursor token)
```

The operation atomically checks whether `next` would still be blocked:

For a realization that supports reader waiting, the decision tree is:

1. An unsupported waiter capability returns an error with cause
   `:dao.stream/unsupported-operation`.
2. A detached realization returns an error with cause
   `:dao.stream/detached`.
3. Missing read authority returns an error with cause
   `:dao.stream/unauthorized`.
4. If `next` would return `:dao.stream/blocked`, retain the token and return
   `:dao.stream/registered`.
5. If `next` would return `:dao.stream/ok`, `:dao.stream/gap`,
   `:dao.stream/end`, or any error, retain nothing and return
   `:dao.stream/ready`.

```clojure
{:dao.stream/result :dao.stream/registered}
{:dao.stream/result :dao.stream/ready}
```

Registration never reads a value or advances the cursor.
Registration through a detached realization returns an error with cause
`:dao.stream/detached`, consistent with every other operation on that
realization.

Reader registration checks detachment before authority because it is a waiter
table operation on a local realization; `next` checks read authority before
cursor state because its authority result is the interpreter's demand
boundary. Both checks occur before any cursor position is evaluated.

### Writer Registration

```clojure
(ds/register-writer-waiter! realization token)
```

The operation atomically applies this write-readiness decision tree for a
realization that supports writer waiting:

1. An unsupported waiter capability returns an error with cause
   `:dao.stream/unsupported-operation`.
2. A detached realization returns an error with cause
   `:dao.stream/detached`.
3. Missing append authority returns an error with cause
   `:dao.stream/unauthorized`.
4. A logically closed stream retains no token and returns
   `:dao.stream/ready`; retrying append reports `:dao.stream/closed`.
5. If append capacity is unavailable, retain the token and return
   `:dao.stream/registered`.
6. Otherwise, retain nothing and return `:dao.stream/ready`.

It uses the same `:dao.stream/registered` and `:dao.stream/ready` result maps as
reader registration.

The realization stores no pending append value and never performs a deferred
append.
These checks are atomic with registration. A closed or unauthorized writer is
never left waiting for capacity that cannot make its append demand succeed.
For a currently full but otherwise valid writer, registration is only a request
to be woken if capacity later becomes available; DaoStream makes no liveness
promise. Callers must cancel a registration when they abandon the demand.

### Token Rules

Tokens are opaque plain data compared with structural value equality. One token
belongs to one shared waiter namespace per logical-stream synchronization
domain and may have at most one live registration across all host-local
attachments, whether reader or writer. Remote endpoints have separate local
namespaces because DaoStream never merges waiter state across a network.

- Re-registering the same token for structurally equal demand is idempotent and
  retains its original registration sequence; it does not move to the back of
  the queue.
- Reusing a live token for different demand returns an error with cause
  `:dao.stream/duplicate-token`.
- Distinct tokens registered at the same cursor are all retained.
- Registration order is retained for deterministic wake ordering.

Reader demand equality is equality of the realization, operation, and complete
cursor value, including the opaque position payload. Reusing a live reader
token with an advanced or otherwise different cursor is not replacement; it
returns `:dao.stream/duplicate-token`. The interpreter must cancel the old
registration or use a new token before registering the new demand. Writer
demand equality is equality of the realization and append-readiness operation.
Realization equality here means reference identity on CLJ, CLJS, and CLJD, not
structural equality of realization data.

### Cancellation

```clojure
(ds/cancel-waiter! realization token)
```

Cancellation atomically returns:

```clojure
{:dao.stream/result :dao.stream/cancelled}
```

or:

```clojure
{:dao.stream/result :dao.stream/not-registered}
```

Cancellation may be requested through any realization in the same
synchronization domain, matching the domain-wide waiter namespace. It is
required so cancelled, replaced, or migrated interpreters do not leave
permanent waiter data in the domain.

Cancellation's decision tree is: a detached realization returns an error with
cause `:dao.stream/detached`; a live token in the synchronization domain is
removed and returns `:dao.stream/cancelled`; otherwise it returns
`:dao.stream/not-registered`. Cancellation does not require the calling
realization to expose reader or writer waiter registration capability because
it is domain cleanup rather than a new waiter registration.

### Wake Transfer

A readiness-changing operation may include:

```clojure
{:dao.stream/result :dao.stream/ok
 :dao.stream/wake   [token-a token-b]}
```

The wake field may be omitted when empty. Wake tokens are ordered by
registration order and contain no values, positions, cursors, continuations, or
scheduler instructions.

The token is the caller's routing handle. DaoStream preserves and returns it
without interpretation; an interpreter or host runtime maps the token back to
its pending demand. A token may contain an opaque caller-chosen address, but
DaoStream never requires or dereferences one.

The example uses `:dao.stream/ok` only for brevity. A wake vector may accompany
any non-error result of a readiness-changing operation, including
`:dao.stream/closed`. Error results never carry a wake vector: validation,
authority, detachment, and transport failures do not transfer waiter tokens.

Wake means only:

> The registered result may have changed. Retry the interpreter's demand.

The realization removes each transferred token before returning it. A race is
resolved atomically: cancellation owns the token or wake owns it, never both.
There are no causeless spurious wakes, although another causally subsequent
operation may make the retry blocked or full again.

An operation transfers every currently registered token whose demand is no
longer blocked or full after that operation, in registration order. Each token
is transferred at most once. Thus concurrent appenders divide the remaining
tokens according to their linearization order; a later append cannot return a
token already transferred by an earlier append. A single append may wake
multiple readers because independent readers can all observe the same retained
value.

Registration order is one FIFO per logical-stream synchronization domain,
spanning all host-local attachments, cursor positions, and reader/writer
waiter kinds. It is not a separate FIFO per realization or cursor.

Read waiters are woken by value availability, close, retention crossing their
cursor, detach of their realization, or another change that can alter `next`.
Writer waiters are woken by capacity availability, close, detach, or another
change that can alter append readiness.

Logical close transfers all registered reader and writer tokens on every
attachment whose demand is changed by closure. The first detach transfers all
tokens registered on that realization, including both reader and writer
waiters. A repeated `detach!` transfers no tokens because the first detach has
already removed them.

An append or retention eviction on a logical stream transfers tokens across
every host-local attachment of that logical stream whose demand changed, using
the same logical-stream synchronization domain. A remote transport endpoint
performs the equivalent transfer locally when a remote value, close, or
retention event arrives; DaoStream does not share waiter tokens across a
network boundary.

The operation result contains the single wake vector for all tokens transferred
by that operation in the domain's registration-sequence order. Tokens belonging
to a remote endpoint are not merged into a host-local result; the transport
emits an equivalent remote readiness event and the endpoint produces its own
locally ordered wake vector.

Registration and detach are linearized by the applicable synchronization
domain (detach additionally marks only its local realization). If registration
wins the race, detach transfers that token in its wake vector and the awakened
interpreter retries, receiving the detached error. If detach wins first,
registration returns the detached error and retains no token. Both outcomes
are deterministic consequences of the linearization point; neither is a
spurious wake.

The logical-stream synchronization domain is the linearization point for
`cursor`, `next`, `register-reader-waiter!`, `register-writer-waiter!`,
`cancel-waiter!`, `append!`, `close!`, `detach!`, and any operation that
advances retention.
Each such operation atomically observes the retained window, logical tail, and
closed flag; operations that affect readiness also update waiter state and
transfer wake tokens. A `cursor :dao.stream/tail` snapshot and an append
therefore have a
single ordering, and `next` observes one coherent state at its linearization
point. The host implementation may use a monitor, lock-free CAS loop, actor
turn, or another mechanism, but it must provide one coherent happens-before
order for these operations. Waiter state is host-local; tokens are never
synchronized across a network boundary by DaoStream.

DaoStream never interprets a token, resumes an interpreter, executes a deferred
operation, or locates a scheduler. The caller receiving wake data owns its
delivery.

When `next` itself lazily evaluates retention and thereby changes another
waiter's readiness, its result may carry `:dao.stream/wake` alongside an
ordinary `:dao.stream/ok`, `:dao.stream/gap`, `:dao.stream/end`, or
`:dao.stream/blocked` result. If that operation changes no other demand, the
wake field is omitted.

## Public Surface Summary

Mandatory host functions:

These are host-composed closures, not ambient namespace globals. Their
signatures omit the already-bound composition state; the descriptor remains
the only argument to `attach!`.

The host composition constructor is:

```clojure
(dao.stream.host/compose {:dao.stream/dispatch dispatch-table
                          :dao.stream/state    host-state})
```

It returns the host's `create!` and `attach!` functions.

```clojure
(create! creation-specification)
(attach! descriptor)
```

Universal realization projection:

```clojure
(portable-descriptor realization)
```

Realization operations, according to capability:

```clojure
(cursor realization origin)
(next realization cursor)
(append! realization value)
(close! realization)
(teardown! realization)
(detach! realization)
```

Optional waiter operations:

```clojure
(register-reader-waiter! realization cursor token)
(register-writer-waiter! realization token)
(cancel-waiter! realization token)
```

`register-reader-waiter!` and `register-writer-waiter!` require the
realization's waiter capability. `cancel-waiter!` does not, because it is
domain cleanup rather than a new registration, as defined in Cancellation.

Transport- and interpretation-specific wrapper constructors live in their
owning namespaces. Pure local constructors use the `wrap` naming convention;
constructors that establish host resources or mint hosted streams use `wrap!`.
These are namespace-specific constructors, not generic canonical operations.
There is no generic `observe`, `subscribe`, `open!`, `closed?`, or `drain-one!`
in the canonical API.

The canonical summary therefore names the `wrap`/`wrap!` convention but does
not invent generic wrapper signatures. The owning namespace documents the
constructor's inner realization, transformation parameters, and any explicit
ownership-transfer data.

## Contract Tests

Every conforming reader realization must pass shared contract tests for:

- total, non-blocking result maps;
- independent, non-destructive cursors;
- stable logical beginning and snapshotted tail;
- same-cursor repeatability while retained;
- cursor identity and version validation;
- cursor construction rejecting any other origin with
  `:dao.stream/invalid-origin`;
- read-authority rejection before cursor-state evaluation;
- cursor construction and validation require the same reader authority as
  `next`;
- exact blocked, end, gap, and error distinctions;
- the complete `next` decision tree, including gap before closed-tail end;
- a tail cursor captured before an append observing that later retained value;
- non-nil gap recovery cursor;
- wake delivery when lazy retention during `next` changes another waiter;
- retained reads after logical close; and
- end at the closed tail, including a zero-append closed stream.

Retention tests must also verify monotonically advancing eviction and exact
earliest-available recovery positions.

Writers and lifecycle capabilities must test:

- atomic append;
- exact ok, full, closed, and error results;
- no mandatory append cursor;
- first close ok and later close closed;
- append after close;
- the complete `append!` decision tree, including authority before closed and
  closed before full;
- append authority derived independently for each realization;
- concurrent appends from multiple attachments use one canonical position
  authority and total order;
- the complete `close!` decision tree, including detached before authority and
  authority before closed;
- close authority derived independently for each realization;
- first detach ok and later detach detached;
- detached-realization failures;
- close/global versus detach/local behavior;
- close leaves a registered hosted entry attachable, while owner teardown
  deregisters it;
- teardown deregisters the entry and detaches host-local attachments;
- owner-realization operations after teardown return detached errors;
- the complete `teardown!` decision tree: unsupported capability, then
  already torn down, then unauthorized authority, then success;
- wrapper non-interference with caller-owned inner realizations.

Portable projection and host dispatch must test:

- distinct creation-specification and descriptor validation;
- no resolve-or-create behavior;
- distinct creation calls mint distinct non-reused identities;
- stable identity separate from Shibi authority;
- Shibi validation and authority derivation at attachment;
- structural descriptor validation before dispatch and type-specific validation
  inside the selected handler;
- unknown cursor versions return `:dao.stream/invalid-cursor`;
- descriptor round-trip to the same logical value/cursor relation; one host
  shares one retained window, while cross-endpoint retention differences are
  reported as gaps;
- repeated portable projection has no hosting or registration side effect;
- detach, reattach, and resume with a previously obtained portable cursor;
- not-portable local wrappers;
- one descriptor and one attachment route;
- unknown static dispatch type failure; and
- absence of runtime registration fallback.

Waitable realizations must additionally test:

- atomic check-and-register with no lost wake;
- reader registration returning ready for gap, end, ok, and error demands;
- multiple tokens at one cursor;
- structural demand equality and duplicate-token rejection;
- idempotent re-registration retaining its original registration sequence
  rather than moving to the back of the queue;
- deterministic registration-order wake vectors;
- wake containing tokens only;
- no wake field on error results;
- cancellation/wake mutual exclusion;
- cancellation through any realization in the synchronization domain;
- one logical-stream waiter namespace rejects duplicate tokens across
  attachments;
- cancellation after wake returns `:dao.stream/not-registered`;
- registration/detach linearization with either detached error or wake;
- append or eviction through one attachment wakes reader and writer tokens
  registered through another attachment in domain registration order;
- teardown transfers all remaining domain waiter tokens;
- writer registration checks detached, authority, closed, then capacity;
- writer registration on closed and unauthorized realizations without a
  retained token;
- value, capacity, close, detach, and gap wakes where the selected policy or
  transport can produce that readiness transition;
- wake fields returned by the operations that changed readiness;
- lazy-drain registration and driven-ingress delivery conform to their declared
  ingress shape;
- a remote tail snapshot that cannot obtain the canonical tail returns
  `:dao.stream/unavailable`;
- no deferred reads, appends, or cursor advancement; and
- successful operation without any scheduler present.

Wrapper contracts must test:

- no data-plane read during construction;
- no hidden pump or runtime;
- demand propagation through the outer `next`;
- independent composed cursors;
- correct value and cursor transformation;
- capability restriction;
- derived wrappers have distinct identities from their inner realizations;
- no implicit close or detach propagation; and
- explicit ownership-transfer behavior when the ownership marker is supplied;
- portability only when an entry point hosts the exact outer relation.

All shared contracts must run on CLJ, CLJS, and CLJD where the capability is
implemented.

## Implementation Plan

Implementation follows TDD as a clean contract replacement. There are no
external users, so existing tests may be changed or removed wherever they
conflict with this design. The new contract tests define correctness; passing
legacy tests is not an acceptance criterion.

### Phase 1: Contract Data and Protocol Surface

1. Write tests for result-map validation and common result constructors.
2. Write tests for creation-specification, descriptor, and cursor envelope
   validation.
3. Replace the current protocol surface with the agreed orthogonal
   capabilities.
4. Remove `open!`, `defopen`, `closed?`, and generic destructive drain from the
   target API.
5. Add the immutable host dispatch composition boundary.

Completion criterion: the public API and validators exist without a concrete
transport relying on legacy result shapes.

### Phase 2: Reference Ring Buffer

1. Write the shared reader, writer, retention, lifecycle, and cursor tests
   against the ring buffer.
2. Implement qualified result maps and stable cursor envelopes.
3. Preserve independent reads and implement exact gap recovery.
4. Implement logical close without destructive drain.
5. Test unbounded, reject, and evict-oldest construction policies where they
   remain meaningful without queue semantics. Capacity-wake tests apply only
   to a policy or transport that can explicitly release capacity; reject-on-
   full is allowed to remain permanently full.

Completion criterion: the ring buffer is the reference realization for the
mandatory contracts.

### Phase 3: Optional Waiters

1. Write race-focused tests for atomic reader and writer registration. Defer
   detach- and teardown-interaction races until the detachable hosting
   realization is available in Phase 4.
2. Change reader positions from one waiter entry to ordered collections.
3. Store tokens only; remove stored pending values and completed-read wake data.
4. Implement cancellation, duplicate-token validation, and deterministic wake
   transfer.
5. Verify poll-only operation remains complete without the waiter capability.

Completion criterion: no wake path advances a cursor or performs a deferred
read or append.

### Phase 4: Creation, Attachment, and Portability

1. Write static-dispatch tests before replacing `open!` transport registration.
2. Implement separate `create!` and `attach!` handlers.
3. Implement stable stream identity and the mandatory validation boundary for
   optional Shibi tokens.
4. Construct one explicit host-owned stream table and two asymmetric facades
   over it. The hosting facade registers and tears down hosted entries; the
   attachment facade only resolves descriptors. Neither facade exposes the
   table, and the table is supplied explicitly when the host composition is
   constructed rather than resolved globally.
5. Build a minimal in-process hosting wrapper using the hosting facade as the
   reference attachable realization.
6. Implement pure portable descriptor projection and not-portable results.
7. Verify attach failure never creates a stream.
8. Verify detach, reattach through a separately constructed attachment facade
   over the same host-owned table, and resume with a previously obtained
   portable cursor.
9. Verify `teardown!` deregisters the entry and that later attachment returns
   `:dao.stream/not-found`.
10. Run the deferred registration/detach and teardown/wake race tests.

Completion criterion: within one host process, a hosted reference realization
can be described, detached, independently reattached through the explicit host
composition boundary, and resumed with its portable cursor. Actual network
crossing is deferred to transport conformance in Phase 6.

### Phase 5: Wrapper Composition

1. Build a minimal identity wrapper as the reference wrapper contract.
2. Test outer cursor composition and independent observers.
3. Test capability restriction and lifecycle non-propagation.
4. Add an explicit hosting wrapper test showing the placement boundary.
5. Keep autonomous materialization outside ordinary wrappers.

Completion criterion: nested wrappers compose through demand with no implicit
cursor, pump, or descriptor program.

### Phase 6: Transport Conformance

Reimplement file, HTTP, WebSocket, UDP, log, relation, and other retained
realizations against the new contract one at a time. For each transport:

1. Write capability-specific contract tests first.
2. Adopt qualified result maps and cursor envelopes.
3. Separate creation from attachment where both exist.
4. Implement portability only for hosted exact relations.
5. Delete legacy multimethod registration and old signal aliases rather than
   adapting or forwarding them.
6. Declare the transport ingress shape, either lazy drain or driven ingress,
   and the waiter capabilities that shape permits.

Completion criterion: every retained DaoStream realization declares and passes
only the capabilities it implements.

### Phase 7: Downstream Boundary

After DaoStream and its transports pass their contracts:

1. Inventory downstream uses of `open!`, old result shapes, `closed?`, waiter
   entries, and destructive drain.
2. Produce separate migration plans for generic runtime code and Yin.VM.
3. Redesign Yin.VM as a DaoStream interpreter only in its own plan.

This phase ends the DaoStream plan; it does not perform the Yin.VM redesign.

## Acceptance Criteria

The DaoStream redesign is complete when:

- the canonical API contains no observation, subscription, scheduler, or GUI
  concepts;
- create, attach, wrap, and portable projection have distinct causality;
- descriptors are minimal entry points rather than construction programs;
- cursors are portable interpreter-owned data with validated lineage;
- multiple cursors observe retained values independently;
- all normal outcomes use qualified result maps;
- gap, blocked, end, error, full, closed, and detached remain distinct facts;
- wrapper composition performs no construction-time reads or hidden pumping;
- optional waiters cannot lose wakes or complete demand;
- no generic destructive read remains;
- static dispatch contains no mutable handler registry or fallback; and
- every logical stream has one authority for position, closed flag, and tail,
  one host-local synchronization domain, and deterministic cross-attachment
  wake ordering;
- hosted close preserves attachment until the owning teardown deregisters the
  entry; and
- capability absence and denied authority produce distinct, deterministic
  causes;
- shared contract tests pass for every declared capability on its supported
  hosts.

## Deferred to Later Plans

- Yin.VM as a DaoStream interpreter.
- Runtime-neutral scheduler extraction.
- Migration of Yin continuations and wake handling.
- GUI event simplification.
- Queue claiming, acknowledgement, and delivery guarantees.
- Durable interpreter checkpoints.
- Cross-host effect execution and deduplication.

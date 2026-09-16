# DaoData

Status: implemented (`src/cljc/dao/data.cljc`, `test/dao/data_test.cljc`).
Wired into `yin.vm.v2.telemetry`/`yin.vm.v2.ffi` and the `yin.repl.v2`
driver/serve/connect operator-diagnostic sites. Reviewed by an independent
adversarial pass and signed off by the Architect (see
`docs/orchestrator-log.md` for the unit's entry).

`dao.data` (`src/cljc/dao/data.cljc`) turns an arbitrary value into
bounded, plain data — safe to append, print, or ship. No functions, stream
handles, or host objects survive into the result. Portable across
CLJ/CLJS/CLJD, no reader conditionals.

This is the parent namespace's own file, sitting alongside the
`dao.data.*` sub-namespaces (`dao.data.btree`, `dao.data.arrays`):
cross-cutting data utilities live here; specific data structures live in
their own sub-namespace. Neither depends on the other.

A generic name like `data` runs against this project's own naming
guidance (`docs/agents/vocabulary.md`), and an independent review
recommended `dao.summary` instead for exactly that reason. `dao.data` is
kept deliberately, scoped narrowly to `tag`/`summarize` plus the existing
B-tree sub-namespaces — not a general dumping ground for unrelated data
utilities.

## Evidence

The original discovery pass overstated how much of this duplication `tag`
and `summarize` actually replace. Verified against the real code, site by
site:

- **Live classifier duplication — replaceable.** `yin/vm/v2/telemetry.cljc:58`'s
  `type-tag` is evaluated before the FFI's no-op check
  (`yin/vm/v2/ffi.cljc:209`), so it's live, not dead. `tag` can take over
  this responsibility, with a few deliberate vocabulary changes:
  `:host-fn` → `:fn`, ad hoc handle detection → `:stream`, and sets (which
  v1's `type-tag` mis-tags) → `:set`.
- **`dao.pretty`'s classifier — not a duplicate, out of scope.**
  `dao/pretty.cljc:49`'s `format-primitive` renders scalar print syntax,
  including string escaping; it doesn't produce classification data.
  Neither `tag` nor `summarize` takes over that job. Its `depth` parameter
  really is unused on JVM/CLJS (`dao/pretty.cljc:12,22`); on Dart it drives
  indentation, not truncation. Summarizing a value *before* handing it to
  `dao.pretty` can bound what gets printed, but that's composition, not
  replacement — `dao.pretty` stays as-is.
- **`dao.await/v2` handle gating — not replaceable.** `dao/await/v2.cljc:164`
  gates operational reader/writer handles for environment preparation.
  That's a capability check with behavioral consequences, not diagnostic
  classification; swapping in `stream/descriptor?` would change what the
  gate actually guarantees.
- **Handoff demo's store-key convention — not replaceable.**
  `datomworld/demo/continuation_handoff_v2.cljc:130` identifies resources
  that block shipping execution state across the wire. A lossy, bounded
  summary can't preserve resumability, so it can't stand in for this
  policy.
- **`dao.stream.v2` surface predicates — dependencies, not duplicates.**
  `dao/stream/v2.cljc:194`'s protocol-capability predicates are what
  `tag`'s `:stream` branch is built on, not something it replaces.
- **Five unnamed bounds (`take 12`, `12`, `40`, `200`, `500`) — partially
  addressed.** Confirmed at `compilation_pipeline_v2.cljs:454`,
  `continuation_stream_v2.cljs:208,636,644`, and
  `yin/vm/telemetry_viewer.cljs:147` (the last is legacy, not a live v2
  obligation). `summarize` can bound the *display data* each of these
  projects, but which frames to show, first-vs-last selection, and
  recent-history retention are all caller policy this abstraction was
  never meant to absorb.
- **Unbounded operator-text printing — live, addressed once the earlier
  fixes land.** Confirmed at `yin/repl/v2/driver.cljc:386`,
  `serve.cljc:348`, `connect.cljc:432` — genuine consumers, now that the
  lazy-sequence, number-portability, and `:chars` gaps above are closed.
- **`flush-telemetry` — legacy, out of scope.** The print path at
  `yin/repl.cljc:384` belongs to the deprecated v1 REPL and establishes no
  live/v2 obligation.
- **Dart WebSocket adapter's request summary — evidence overstated.**
  `dao/stream/v2/ws/dart.cljd:156` projects a host request into
  method/path data, which is useful, but it has no string-length bound —
  calling it "the one correct instance of bounded summarization" wasn't
  accurate. It's a projection worth keeping as its own thing, not a
  template `summarize` fully subsumes.

Net: `tag`/`summarize` genuinely retires the classifier duplication and
gives the unnamed-bounds and unbounded-printing sites something real to
call. It does not, and isn't meant to, absorb capability gating,
resumability policy, or host-event projection — those stay where they
are.

## API

```clojure
(ns dao.data
  (:require [dao.stream.v2 :as stream]
            [dao.stream.v2.transit :as transit]))

(defn tag [x] ...)              ; classify x without entering it
(defn summarize [x bounds] ...) ; bounded plain-data description of x
```

The `stream` alias is `dao.stream.v2` specifically, stated explicitly
because `dao/stream.cljc` (v1) defines a similarly-named descriptor
predicate over plain descriptor *maps*, not v2 handles — an easy
namespace to grab by accident with the same short alias.

`bounds` is `{:depth n :items n :chars n}`, all three required, no
default — every caller states its own budget. Each must be a non-negative
integer; `summarize`'s behavior for a negative or non-integer bound is
undefined, not validated.

## `tag`

```clojure
(cond (nil? x) :nil
      (boolean? x) :boolean
      (number? x) :number
      (string? x) :string
      (keyword? x) :keyword
      (symbol? x) :symbol
      (fn? x) :fn
      (stream/descriptor? x) :stream
      (vector? x) :vector
      (set? x) :set
      (map? x) :map
      (sequential? x) :sequence
      :else :opaque)
```

The stream check precedes `map?`/`sequential?`, since a handle may be a
record. `stream/descriptor?` alone is enough — every handle implements it,
regardless of whether it's a reader, writer, or closable.

## `summarize`

Every node is a map tagged `:dao.data/type` (scalars too, so a result
is never ambiguous with an ordinary value):

| `:dao.data/type` | other keys |
|---|---|
| `:nil` | none |
| `:boolean` | `:dao.data/value` |
| `:number` `:string` `:keyword` `:symbol` | `:dao.data/value`, `:dao.data/truncated?` |
| `:fn` `:opaque` | none |
| `:stream` | `:dao.data/identity` (itself a full summarized node) |
| `:vector` `:set` `:sequence` | `:dao.data/items`, `:dao.data/truncated?`, `:dao.data/count` (conditional) |
| `:map` | `:dao.data/entries` (`[key-summary value-summary]` pairs), `:dao.data/truncated?`, `:dao.data/count` (conditional) |

`:dao.data/count` appears only per the `:count` rule below — never inferred
after the fact from how many elements a probe happened to touch.
`:dao.data/items`/`:dao.data/entries` is absent when `depth` is `0`; both
are always plain vectors, never the input's own collection type, so two
elements whose summaries happen to be equal are never silently collapsed
the way a set would collapse them.

Rules:

- `:items`/`:entries` holds at most `:items` children (for `:map`,
  entries — `[key-summary value-summary]` pairs — not raw key/value
  count), each summarized with `(dec depth)`.
- This and the next bullet apply only to the container types (`:vector`
  `:set` `:sequence` `:map`); a leaf scalar's own truncation (string,
  number, keyword, symbol) is governed by `:chars`, below, and is
  unaffected by `:depth`.
- A container's `:truncated?` is true when the item bound cut children,
  or when `depth` is `0`. At `depth` `0`, `:items`/`:entries` is absent
  entirely, and — for a non-`counted?` sequence — `summarize` performs no
  probe at all: it doesn't request even one element, since nothing about
  the result would depend on the answer.
- Map keys are summarized the same as values.
- A number outside `dao.stream.v2.transit`'s portable domain — non-finite,
  a ratio, a BigInt/BigDecimal, or an integer or float outside
  ±9007199254740991 — has `:dao.data/value` set to `(str x)` instead of
  the raw number, with `:truncated?` true; a portable number keeps its
  literal value with `:truncated?` false. "Safe to ship" is a real claim
  about the live v2 wire codec, not a general description, so this reuses
  `dao.stream.v2.transit/portable-value?` — the actual check the wire
  boundary applies — rather than re-deriving the domain's bounds a second
  time and risking drift from the real contract. `tag` still classifies
  every number as `:number` regardless of portability; only the value
  `summarize` preserves depends on it. This is the same kind of
  small, justified dependency as `stream/descriptor?` in `tag` — `dao.data`
  depends on `dao.stream.v2.transit` for exactly this one purpose.
- A string longer than `:chars` is cut to its first `:chars` characters,
  with `:truncated?` true; a string at or under the bound carries the
  whole value with `:truncated?` false. This is the one bound that
  applies to a leaf rather than a collection — everything else `:depth`/
  `:items` limit is a container with children to count or recurse into,
  but a string has neither, so it needed its own bound instead of
  inheriting theirs. The same rule applies to a keyword or symbol,
  measured and cut on its printed form (`(str x)`, including any
  namespace and the leading `:` for a keyword): under the bound it keeps
  its literal keyword/symbol value with `:truncated?` false; over the
  bound `:dao.data/value` becomes the cut string (a `:keyword`/`:symbol`
  node can hold a plain string in `:value` once truncated — a partial
  name isn't a valid keyword or symbol to begin with) with `:truncated?`
  true. A stream's `:dao.data/identity` is not given its own bespoke
  truncation rule; see the `:stream` rule below, which reuses this one
  instead of duplicating it.

  "Characters" means whatever the host's own string length and
  substring operations count — UTF-16 code units on all three hosts
  (`count`/`subs` on the JVM and in ClojureScript, `String` length and
  slicing in Dart), so `:chars` means the same thing everywhere without
  any host-specific code. A cut can land inside a surrogate pair for a
  character outside the Basic Multilingual Plane, producing a technically
  malformed string right at the truncation boundary; this is a known,
  accepted limitation, not handled specially.
- `:count` is present only when it's known without a potentially-unbounded
  walk, decided by `counted?`:
  - When `(counted? x)` is true — vectors, sets, maps, and any other
    collection whose implementation tracks its own size — `count` is
    O(1) regardless of size, so `:count` is always the true count,
    independent of how many children `:items` materializes. `counted?` is
    a structural property of the value's implementation, not of whether a
    lazy sequence happens to have already produced its elements: a fully
    realized `LazySeq` still answers `counted?` false.
  - When `(counted? x)` is false — a sequence whose length isn't known
    without walking it — `summarize` never calls `count`, and requests at
    most `:items` + 1 elements from it: enough to fill `:items` and check
    whether a next element exists, no more. If that check finds one,
    `:truncated?` is true and `:count` is absent (unknown, not "large" —
    the sequence may be infinite). If it doesn't — the sequence turned out
    to have `:items` or fewer real elements — `:count` is *still* absent.
    `summarize` now technically knows the true count from having just
    realized the whole thing, but doesn't report it: whether `:count`
    appears is a property of the input's type (`counted?` or not), decided
    before any probing happens, never a property of what a given probe
    happened to discover. A caller can always derive the true count itself
    from an untruncated `:items` in this case, same as it would have to
    for any other absent `:count`.

    This bounds how many elements `summarize` *asks for*, not how much
    work producing them costs. A chunked source can realize many more
    elements than requested to satisfy one `first`/`next` call, and a
    filtering or otherwise gated sequence can do unbounded work — or
    never return — before yielding even its first element. `summarize`
    does not solve this: it carries exactly the same risk any code taking
    `first`/`seq` of an arbitrary lazy sequence already carries, no worse
    and no better. What it avoids is only the *additional*, avoidable
    risk of calling `count` on something whose length was never going to
    be known short of full realization. A caller handing `summarize` a
    sequence backed by a genuinely adversarial or non-terminating
    producer must guard against that itself; `summarize` bounds output
    size, not input cost.
- A stream handle's node is `{:dao.data/type :stream, :dao.data/identity
  <...>}`, where `<...>` is `(summarize identity bounds)` and `identity`
  is the `:dao.stream/identity` value from calling `(stream/descriptor
  x)`'s outcome map — the identity projection specifically, not the
  whole `:dao.stream/descriptor` envelope the outcome map also carries;
  the two are distinct fields of that map, not interchangeable, and only
  the identity is exposed. Passing it back through `summarize` (with the
  same `bounds`, since it isn't a child of a container being walked)
  bounds it exactly like any other value — including `:chars`, if it
  turns out to be a string — instead of a second, separate truncation
  rule existing only for this one case. Nothing else on the handle is
  touched. A conforming implementation of the descriptor operation has no
  failure outcome — `descriptor` on a value that actually satisfies
  `stream/descriptor?` always returns an ok outcome map. The `:opaque`
  fallback triggers on any outcome other than `:dao.stream/ok`, which
  covers a malformed third-party implementation of the protocol, not an
  ordinary, conforming descriptor call.

  This does not cover a third-party implementation that *throws* instead
  of returning a malformed outcome. Catching an arbitrary exception
  portably needs a host-specific `catch` clause — this project's own
  precedent (`dao/stream/v2/ws.cljc`) needs a three-way reader conditional
  for exactly that, because no single `catch` class name is valid on all
  of CLJ, CLJS, and CLJD. That conflicts directly with this namespace's
  own no-reader-conditionals constraint, and the constraint wins: a
  throwing descriptor implementation is not defended against, the same
  way a genuinely adversarial lazy sequence isn't, above. A caller handing
  `summarize` a handle backed by a broken protocol implementation must
  guard against that itself.

## Not included

No `emit` wrapper — call `append!` directly on the result; wrapping it
would either hide its outcome map or just rename it. No datom projection
(that's a schema concern for whoever consumes a summary). No extension
hook — add only if a real caller needs one.

---
description: Alpha-canonical de Bruijn projection of the named Universal AST
---

# DE BRUIJN PROJECTION OF THE UNIVERSAL AST

Status: implemented through D6 (merged 2026-09-21). D0–D6 implementation and
tests are merged; the DaoJing file store's host-specific refusal/hash-mismatch
cases are pinned by tests, and projected-reader lexical scope validation remains
outside this plan. The sections below are the design, phase scope, and
completion criteria used to build and review the implementation; §7 records
phase scope and completion criteria. This is compilation-layer work. The
pipeline is:

```
Yang -> named Universal AST -> de Bruijn projection -> projected form
                                  |
                                  +-> root Merkle fingerprint
```

The named form remains the bijective, stored, queryable, and renderable form.
The projected form is derived data for alpha-equivalence, hash identity, and
deduplication. Neither replaces the other.

## 1. Architectural position and invariants

The projection is an interpreter over plain AST datoms. It does not execute
values, primitives, streams, continuations, or effects. It does not change the
AST schema, AST walker, semantic VM, linearizer, `dao.stream`, `dao.lease`, or
the waitset.

All state is explicit: `forward-step` carries the input cursor, current graph
frame, and pending output; the atomic per-frame projection threads the indexed
facts, scope stack, and occurrence memo. There is no output cursor. There is no
namespace atom, callback, timer, registry, or clock. A blocked input is returned
as an outcome; it is never hidden execution.

The input domain is a fully macro-expanded Universal AST. An unexpanded macro
call site is a terminal diagnostic: an application whose operator is a
`:lambda` carrying `:macro? true` is unexpanded. A free variable whose name
matches a registry macro cannot be detected from these datoms and is an
inherited detection limit. A malformed root, dangling reference, cycle,
duplicate structural fact, retract, unknown node type, or unsupported value is
likewise diagnostic rather than silently interpreted.

## 2. Input framing and node grammar

`yin.vm/ast->datoms-with-root` emits d5 datoms with `:yin/*` attributes. The
adapter frames a graph at `:yin/root true`; the marker closes that graph and
causes projection. Exactly one root is required per graph. Several roots are a
diagnostic. End-of-stream with a partial graph is a diagnostic.

Only assert datoms are accepted: `m` must not be the reserved
`:db/retract` operation; an assert with other provenance metadata remains
valid. A retract is a diagnostic. Once this validation succeeds, `t` and `m`
are excluded from projected identity. Input order and source tempids do not
affect identity. The fact index is reset at every root-frame boundary, because
emitter temporary ids may restart at `-16` for each graph.

Attributes in other namespaces, such as `:yin.code/*` or macro-expander event
attributes, are ignored. An unknown `:yin/*` attribute on a walked node is a
diagnostic. `:yin/macro-name`, when present, is tolerated as emission metadata
and ignored; it is excluded from projected rows and hashes and does not make
an unexpanded macro call valid.

The walked node grammar and child order are:

| type | scalar/ref attributes | child order |
|---|---|---|
| `:literal` | `:yin/value` | none |
| `:variable` | `:yin/name` | none |
| `:lambda` | `:yin/params`, optional `:yin/macro?` | body |
| `:application` | operator, operands | operator, operands left-to-right |
| `:dao.stream.apply/call` | `:yin/op`, operands | operands left-to-right |
| `:if` | test, consequent, alternate | test, consequent, alternate |
| `:vm/gensym` | `:yin/prefix` | none |
| `:vm/store-get` | `:yin/key` | none |
| `:vm/store-put` | `:yin/key`, `:yin/value` | none |
| `:vm/current-continuation` | none | none |
| `:vm/park` | none | none |
| `:vm/resume` | `:yin/parked-id`, `:yin/val-node` | value |
| `:stream/make` | `:yin/buffer` | none |
| `:stream/put` | `:yin/target`, `:yin/val-node` | target, value |
| `:stream/cursor`, `:stream/next`, `:stream/close` | source | source |

The table is exactly the emitter's vocabulary. `:yin/tail?` is derived
control-position data: it is ignored, excluded from rows and hashes, and is
not persisted by this projection.

The walk follows fixed child order. A vector is ordered data, never a set. A
shared source entity is memoized by `[source-eid lexical-context]`, where
`lexical-context` is the complete stack of frame parameter vectors. This memo
is only an optimisation; it must never affect output identity. A finite AST
graph is required; cycles are rejected.

## 3. Scope and de Bruijn resolution

The scope stack contains ordered parameter vectors. Frame depth `0` is the
innermost frame; position is the source parameter index, with the leftmost
parameter at position `0`. Entering a lambda pushes one frame and leaving its
body pops it. The lambda's names are otherwise discarded; its binder becomes
an arity.

Resolution searches frames from inner to outer. Within one frame it searches
positions right-to-left. Thus duplicate parameters have rightmost-wins
semantics, matching VM `bind-params`: `(fn [x x] x)` resolves to the second
`x`. A bound occurrence is represented as `{:bound [frame-depth position]}`.
An occurrence with no matching binder is `{:free name}` and preserves its
symbol exactly. An intermediate binder shifts only its own frame depth; the
position pair does not depend on the arity of intervening frames.

Every child is projected under the scope at its occurrence. A lambda body is
projected after its frame is pushed; an application operator and operands share
the enclosing scope.

## 4. Projected form and published dimension

The projection publishes a `:yin.debruijn/*` dimension descriptor, as required
by `docs/design/datom.md`'s dimension protocol. Its descriptor hash is the
hash domain separator. A projected semantic node is a content-addressed record
whose identity is a 32-byte node hash `h`.

The descriptor declares the projected d5 semantic slots in this order:
`[:yin.debruijn/hash :yin.debruijn/type :yin.debruijn/arity
:yin.debruijn/bound :yin.debruijn/free :yin.debruijn/value
:yin.debruijn/op :yin.debruijn/key :yin.debruijn/prefix
:yin.debruijn/buffer :yin.debruijn/parked-id :yin.debruijn/macro?
:yin.debruijn/body :yin.debruijn/operator
:yin.debruijn/operands :yin.debruijn/test :yin.debruijn/consequent
:yin.debruijn/alternate :yin.debruijn/target :yin.debruijn/val-node
:yin.debruijn/source :yin.debruijn/root]`. The descriptor also declares each
slot's type and the ordered-vector type of `:yin.debruijn/operands`; its hash,
not an ad hoc text string, separates this dimension from every other tuple
dimension.

The semantic tuple shape is:

```
[h tag scalar-slots child-hashes-in-declared-order]
```

The d5 storage adapter may use local entity `e` handles, but emits
`:yin.debruijn/hash h` on each entity. Local eids, ordinals, row count, root
ordinal, and the old `-16` base are storage layout only. They are never
identity. Hash-consing equal subterms is the compression mechanism.

Projected d5 attributes are:

* `:yin.debruijn/type`, `:arity`, `:bound`, and `:free`;
* scalar `:value`, `:op`, `:key`, `:prefix`, `:buffer`, `:parked-id`, and
  `:macro?`;
* child refs `:body`, `:operator`, `:operands`, `:test`, `:consequent`,
  `:alternate`, `:target`, `:val-node`, and `:source`.

Child refs contain child hashes semantically. `:operands` is an ordered vector
of hashes and is never emitted as cardinality-many datoms. The root is an
explicit root-hash marker. The named `:yin/name` and `:yin/params` attributes,
`:yin/macro-name`, source eids, `:yin/tail?`, and transaction metadata are not
projected into identity. A source-to-projected map is ephemeral by default and, if persisted
for diagnostics, is a separate side index.

## 5. Merkle fingerprint and canonical encoding

For every projected node:

```
hash(node) = SHA-256(dimension-hash
                     || encode(tag-specific-slots-in-descriptor-order))
fingerprint = hash(root)
```

The dimension hash follows the d1 content-addressing floor; an ad hoc string
domain separator is not used. Only slots belonging to the node's tag are
encoded, in descriptor order. `:yin.debruijn/hash` and
`:yin.debruijn/root` are storage/index markers and are excluded from every
node hash. Ordered child hashes preserve operand and branch order. Tree
emission and graph emission of the same unfolded term therefore have the same
root hash; memoisation and sharing cannot change identity.

Every slot is tagged and length-delimited. Keywords and symbols encode
namespace and name separately. Strings use UTF-8 NFC with length prefixes;
bytes are length-prefixed; booleans are `00`/`01`; nil has its own tag; maps
sort by canonical encoded key bytes; sets sort by canonical encoded element
bytes. Lists and vectors are distinct sequence classes and never merge: the
language observes the difference, so identity must not. Unsupported values
are diagnostics, including an unpaired surrogate and a record literal; a map
whose entries collide under key canonicalization is likewise a diagnostic,
so iteration order never decides a hash. The preimage is a text stream: each
part frames as its class's tag byte, an 8-hex-digit length, and the content —
numeric and byte content is hex, string content is the raw UTF-8 text, and
the length counts the content's UTF-8 bytes. The hashed bytes are the
stream's UTF-8 encoding.

Numeric canonicalisation is fixed in D0: exact integers in signed int64 use
int64 encoding, and every integral double in that range also uses int64
encoding on every host. All other in-domain numbers use IEEE-754 doubles; all
NaNs use one quiet-NaN encoding and `+0` and `-0` remain distinct. A
JavaScript number is an int64 only when it is a safe integer in the int64 range;
an unsafe integer whose exact classification is not recoverable is diagnostic.
Consequently, integer `1` and integral double `1.0` intentionally collide in
the canonical form. Bigints, ratios, characters, and other out-of-domain
numeric objects are diagnostic. NFC normalisation collisions, and the
`1`/`1.0` collision, are inherited limits of the repository encoding.
The Dart NFC source is settled 2026-09-21: the pure-Dart `unorm_dart`
package (Unicode 16.0) provides NFC on every ClojureDart target. D3 still
proves byte identity on all three hosts against the §8 fixtures.

## 6. Stream composition and consumers

Implementation belongs in `src/cljc/yin/vm/debruijn.cljc`: it consumes the
existing `:yin/*` schema and shares no transport code. It exposes:

* `project-datoms`: pure projection of one complete rooted graph to Merkle
  records and root fingerprint;
* `forward-step`: ordinary `dao.stream` forward interpretation. It frames at
  the root marker, projects there, emits the projected tuples, and reports a
  partial frame at end as diagnostic. Pending writes remain explicit and are
  retried only by host cadence.

Named storage, renderers, Datalog queries, source maps, and diagnostics consume
the named stream. Deduplication, cache lookup, and alpha-equivalence consume
the projected segment/fingerprint. The named AST remains the execution source:
`yin.vm/ast->datoms` emits named AST datoms; `yin.vm.linearize` lowers those
datoms to `:yin.code/*` datoms, which the semantic VM loads and executes;
`yin.vm.ast-walker` can evaluate the named AST map directly. None of these
execution paths consumes the de Bruijn projection. A waitset is optional only
for a composition that owns several independent waiters; no lease fact, lease
timer, callback, or scheduler is introduced here.

## 7. Implementation plan

### D0 — contract, dimension, and fixtures

Publish the `:yin.debruijn/*` descriptor and hash domain; record the fully
expanded macro domain and its lambda-operator detection rule and limit,
assert-only input, root framing and per-frame index reset, rightmost duplicate
binding, scope pair convention, canonical value table, NFC and numeric limits,
and node grammar. Pin the Dart NFC source (`unorm_dart`, Unicode 16.0)
behind one host-dispatched normalize seam. Add all node fixtures.

### D1 — graph index and scope resolver

Implement root framing, exact-root validation, unknown-attribute validation,
cycle detection, scope push/pop, rightmost duplicate binding, per-frame fact
index reset, and memo keys using the complete frame-vector stack. Completion
means shuffled input produces the same semantic graph and all bindings resolve
to the specified pairs.

### D2 — Merkle records and storage adapter

Implement node hashes, ordered child hashes, hash-consing, the
`:yin.debruijn/*` records, and the d5 storage adapter. Completion means no
binder names, bound occurrence names, tail flags, or source tempids enter
identity, and tree/graph emission has one semantic graph and fingerprint.

### D3 — canonical encoder and fingerprint

Implement the descriptor-based SHA-256 encoder and numeric/string rules,
including distinct signed zero and integral-double-to-int64 canonicalisation.
Completion means CLJ, CLJS, and CLJD produce identical bytes and all canonical
fixtures hash identically across hosts.

### D4 — stream adapter

Implement blocked, transport-error, invalid input, pending output, root
framing, adjacent graphs (including graphs that reuse temporary eids), and
terminal diagnostic outcomes. End-of-stream with a partial graph must be
covered on every host.

### D5 — pipeline and storage integration

D5 delivers a standalone `yin.vm.pipeline/persist-compiled!` adapter for the
post-emission boundary before projected persistence; no Yang caller is wired
yet. Store named and projected segments separately; use only the Merkle
fingerprint for dedupe. Do not alter named consumers or runtime layers.

### D6 — end condition

All D0–D5 criteria pass; alpha-equivalent programs have identical semantic
projected tuples and root hashes; non-equivalent fixtures remain distinct;
all emitter types and stream outcomes are covered; and AST, walker, VM,
linearizer, named storage, lease, and waitset diffs are unchanged.

## 8. Test matrix

The focused suite must include:

* renamed binders at one and multiple nesting levels;
* nearest shadowing with `[frame-depth position]` results;
* duplicate parameters `(fn [x x] x)` proving rightmost-wins;
* free names preserved and changed free names changing the fingerprint;
* changed literals, primitive keys, arity, branches, stream operations, and
  continuation markers changing the fingerprint;
* zero-, one-, and multi-parameter lambdas and parameter order;
* every `:vm/*`, `:stream/*`, `:if`, application, and
  `:dao.stream.apply/call` emitter case;
* shuffled datom input and all three host encoders producing identical bytes;
* the same term emitted as a tree and as a graph producing identical Merkle
  nodes and root fingerprint;
* shared nodes under equal and unequal lexical contexts;
* ordered operand changes producing different hashes;
* `:yin/tail?` present versus absent producing identical output;
* maps and sets with different iteration order producing identical encoding;
* `+0.0` and `-0.0` hashing differently;
* an integral-double literal hashing identically to its int64 counterpart on
  every host;
* dangling refs, duplicate facts, cycles, missing/multiple roots, unknown
  node types, unknown `:yin/*` attributes, retracts, unexpanded macros,
  unsupported values, and partial frames at end producing diagnostics;
* adjacent rooted graphs in one input stream that reuse emitter eids beginning
  at `-16`;
* numeric boundary, NaN, signed-zero, JS-number, and NFC-limit fixtures;
* blocked, transport-error, invalid-answer, and pending-write paths;
* the essay's `(fn [count] (+ count 1))` example and stable fingerprint;
* unchanged named AST reconstruction, linearization, semantic execution,
  lease, and waitset suites.

## 9. What must not change

The `:yin/*` schema, emitter, `datoms->ast`, walker, VM protocols, semantic
machine, `yin.vm.linearize`, `:yin.code/*`, bijective named storage,
`dao.stream`, transports, `dao.lease`, and `dao.stream.waitset` remain outside
this design's authority. The only new pressure is a published projected
dimension and optional diagnostic side index; neither changes the named form.

## 10. Resolved owner decisions

The six open questions are resolved: source-leftmost parameter position is
zero; duplicate names are rightmost-wins; shared nodes may be duplicated by
lexical context but sharing is invisible to identity; maps and sets use
canonical byte ordering; the published `:yin.debruijn/*` dimension supplies
the domain separator and SHA-256 is the hash; the source-to-projected index is
ephemeral by default and separate if later persisted. The Dart NFC source is
`unorm_dart` (Unicode 16.0), settled by the owner 2026-09-21 and validated by
the D3 cross-host fixtures. At the D0 review 2026-09-21 the value table's
sequence class was ruled split: lists and vectors are distinct classes and
never merge, because a program can observe the difference and identity never
merges distinguishable values.

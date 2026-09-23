# DaoJing Hash and Address Call-Site Classification

Status: H0 contract artifact. Maintained design document, not a snapshot.

Governing design: [`dao.jing.hash-registry.md`](dao.jing.hash-registry.md)
("Call-site audit and required dispositions"), architect signed off
(`archive/1790147499393-architect-hash-registry-signoff.gpt-5.6-sol.findings.md`).

This document inventories every Jing hash and address operation across
`src/` as of commit `ba88f769`, and assigns each to exactly one of the four
classes the hash-registry design defines (test call sites in `test/` are
excluded here because they are regenerated wholesale in H1 alongside all
committed development fixtures, per the clean-break ruling). It is the H0
deliverable that H1's implementation and H2's verification are checked
against; when a call site moves, is added, or is removed, this document
must be updated in the same change.

The classification is deliberately mechanical: a call site's class is a
property of *what it does with an address or digest*, not of which
namespace it lives in. Minting produces a new address from source data.
Validation checks a payload against an address someone else already
supplied. Copying moves already-addressed content between stores while
preserving the algorithm the address already carries. Frozen contracts are
hash values with their own independent format-versioning process, outside
DaoJing's algorithm registry entirely.

Line numbers are current at HEAD `ba88f769` and drift with routine edits;
treat them as pointers to re-locate the call site, not as pinned contract
text.

---

## Class 1 — Frozen non-Jing hash contracts

These call sites use `jing/sha256` (or an equivalent explicit SHA-256
primitive) as an independent format contract. They are not selecting an
algorithm from DaoJing's registry and must not be repointed at
`default-hash-algorithm`, `content-hash`, or `segment-key`. Each has its own
byte rules, descriptor declarations, contract version, and golden values;
changing any of them is a VM- or DHT-format-version decision, not a
DaoJing registry change.

| Site | Kind | Location | Disposition |
|---|---|---|---|
| `yin.vm.debruijn-code/descriptor-hash` | `def`, computed once at namespace load | `src/cljc/yin/vm/debruijn_code.cljc:524` (`jing/sha256` call at line 530) | Frozen SHA-256. Descriptor content hash under the dimension's own encoder. |
| `yin.vm.debruijn-code/image-hash` | `defn` | `src/cljc/yin/vm/debruijn_code.cljc:531-546` | Frozen SHA-256. Computes H (S2 item 6): `sha256(descriptor-hash-hex ++ encode-image(v))`. |
| `yin.vm.debruijn/dimension-hash` | **`def`, computed once at namespace load** — not a function | `src/cljc/yin/vm/debruijn.cljc:944-952` | Frozen SHA-256. The published hash domain separator (§4); `(jing/sha256 (encode-value descriptor))` evaluates exactly once when the namespace loads, so every node-hash call in the process shares one already-computed value. |
| `yin.vm.debruijn/node-hash` | `defn-` (private) | `src/cljc/yin/vm/debruijn.cljc:1002-1008` | Frozen SHA-256. `sha256(dimension-hash-hex ++ encode(slots))`, per §5. |
| `dao.jing.dht/node-id` | `defn` | `src/cljc/dao/jing/dht.cljc:30-33` | Frozen SHA-256. Defines the DHT routing identity space (`sha256(host:port)`), not a content-address selection. Changing it is a separate DHT protocol decision, independent of DaoJing's algorithm registry. |
| `yin.vm/primitive-profile`'s `:yin.k.pp/sha256-...` label | `defn` | `src/cljc/yin/vm.cljc:118-140` (label construction at line 135-136) | **Explicit VM contract using a first-class registry algorithm, not a compatibility fallback.** Currently `(str "sha256-" (jing/content-hash description))`, which relies on `content-hash`'s current one-argument BLAKE3-default behavior and would mislabel a BLAKE3 digest as SHA-256 once H1 flips the default. H1 must change this call site to `(jing/content-hash description {:algorithm :sha256})` so the `sha256-` label stays truthful. This is the one Class-1 site that requires a source edit in H1 — not because its contract changes, but because its current implementation silently rode the soon-to-move default. |
| Future register VM format contract: `register-descriptor-hash`, `register-hash` | not yet implemented | `src/cljc/yin/vm/debruijn_register_code.cljc` does not exist in this checkout | **Absent register implementation, recorded here as future integration.** The register design specifies format R as explicit SHA-256, mirroring the H formula (`register-descriptor-hash` parallels `descriptor-hash`; `register-hash` parallels `image-hash`). When implemented, both follow this Class-1 disposition from day one: explicit SHA-256, never coupled to `default-hash-algorithm`. No register golden values exist yet to preserve. |

Class-1 sites never call `dao.jing/segment-key`, `dao.jing/segment-matches?`,
or `dao.jing/materialize!`. They call `jing/sha256` (a string-in/hex-out
primitive independent of the address registry) or, after the H1
`primitive-profile` fix, `jing/content-hash` with an explicit
`{:algorithm :sha256}` — never the one-argument default form.

---

## Class 2 — New-content mint sites

These call sites produce a **fresh** DaoJing address from source data that
has no pre-existing address to validate against. Ordinary one-argument
calls mint under `default-hash-algorithm` (BLAKE3 after H1); a caller that
wants SHA-256 passes `{:algorithm :sha256}` explicitly. Minting primitives
throw on canonical-encoder refusal — they are never validators.

### Direct minting call sites

| Site | Location | Notes |
|---|---|---|
| `dao.jing/materialize!` (new-content calls) | `src/cljc/dao/jing.cljc:370` | The primitive both Class 2 and Class 4 build on; see Class 4 for its address-preserving explicit-algorithm form. |
| `dao.jing/segment-key` (generation-only calls) | `src/cljc/dao/jing.cljc:336` | One-argument calls remain BLAKE3-default generation. Callers intentionally choosing SHA-256 pass `{:algorithm :sha256}`. |
| `dao.jing/content-hash` (non-`primitive-profile`, non-validation calls) | `src/cljc/dao/jing.cljc:306` | Local/versioned-contract calculations per the design's `content-hash` section; not a durable-identity envelope by itself. |
| `yin.vm/code.cljc` instruction-vector minting | `src/cljc/yin/vm/code.cljc:412` (`(jing/segment-key v)`) | Mints the address a canonical instruction vector is stored/loaded under. |
| `yin.vm.macro/materialize-like` body addressing | `src/cljc/yin/vm/macro.cljc:53` (`(into [(jing/segment-key body)] body)`) | Mints the row's own leading address slot from its body. |
| `yin.vm.semantic` instruction-vector minting | `src/cljc/yin/vm/semantic.cljc:731` (`load-vector`) | Mints address `(jing/segment-key v)` for canonicalized instruction vector. |
| `yin.vm.pipeline/project` | `src/cljc/yin/vm/pipeline.cljc:112` | `(jing/materialize! projected-store (:envelope projection))` mints projected envelope into store. |
| `yin.vm` semantic-bytecode row mint | `src/cljc/yin/vm.cljc:938` | `id (jing/segment-key body)` mints semantic bytecode row ID from body. |
| `dao.data.btree.storage` `-store` (`HydrationStorage` async cache mint) | `src/cljc/dao/data/btree/storage.cljc:115` | `(jing/materialize! cache blob)` mints freshly built node blob into local cache. |
| `dao.data.btree.storage` `-store` (`HydrationStorage` sync source+cache mints) | `src/cljc/dao/data/btree/storage.cljc:122-123` | `(jing/materialize! source blob)` and `(jing/materialize! cache blob)` mint freshly built node blob into durable source and read cache. |
| `yin.vm.completion` image minting | `src/cljc/yin/vm/completion.cljc:166, 194` | Mints the address under which a reconstructed image is stored. |
| `yin.vm.ledger` record/vector minting | `src/cljc/yin/vm/ledger.cljc:79, 91, 96` | Mints ledger record and vector addresses at write time. |
| `yin.vm.content/store-row!` | `src/cljc/yin/vm/content.cljc:64, 123` (`jing/materialize!`) | Mints storage for a canonical row/vector with no incoming address to preserve. |
| `dao.data.btree/storage.cljc` `-store` (`kv-storage`) | `src/cljc/dao/data/btree/storage.cljc:48` (`jing/materialize!`) | Ordinary local mint of a freshly built B-tree node blob. |

### Default-only remote mint entry points

Classified explicitly per the hash-registry design and architect sign-off:

| Site | Location | Disposition |
|---|---|---|
| `dao.jing.remote.step/request-materialize` | `src/cljc/dao/jing/remote/step.cljc:233-244` | **Default-only remote mint entry point.** Mints `(jing/segment-key payload)` locally, then submits a put keyed on that freshly minted address. Correct for new content; must never be used to flush an already-addressed blob under its own algorithm (see Class 4, `store-tree-async`). |
| `dao.data.btree.storage/materialize-async-fn` (the `:materialize-async-fn` callback contract) | Contract declared at `src/cljc/dao/jing/remote/async.cljc:18,162`; consumed at `src/cljc/dao/data/btree/storage.cljc:322` | **Default-only remote mint entry point.** Payload-only signature `(fn [payload callback])` — no address parameter exists to preserve one. Correct only for new content; `store-tree-async`'s address-preserving flush (Class 4) must not call it for cache-minted blobs that already carry a source address. |

### DaoSpace schema identity (mint side)

| Site | Location | Disposition |
|---|---|---|
| `dao.space.index/checkpoint-candidate` | `src/cljc/dao/space/index.cljc:1263-1294` | **Currently mints a bare `:schema-hash` via `(jing/content-hash (:schema consumer))` at line 1294 — a non-conforming shape.** H1 must replace this with `:schema-address (jing/segment-key schema)`, per the design's "DaoSpace schema identity" section. There is no fallback reader for the old `:schema-hash` field and no dual candidate shape; H1 changes the field name and the mint call together, in one edit, with no transitional period. |

---

## Class 3 — Address-directed validation sites

These call sites check a payload against an address that already exists —
supplied by stored data, a caller, or a peer. **All eighteen sites below
must use `segment-matches?`.** None may re-mint with one-argument
`segment-key`/`content-hash` and compare whole addresses or bare digests;
that substitutes the minting default for address-directed verification and
silently miscompares a SHA-256-addressed payload against a BLAKE3
recomputation once BLAKE3 becomes the default.

Two families of non-conforming comparison exist in the current tree and
must be corrected in H1:

- **Backend sites** compare `(jing/segment-hash address)` against
  `(jing/content-hash payload)` (a raw-digest-vs-recomputed-digest
  comparison that assumes the address's algorithm matches whatever
  `content-hash` happens to default to).
- **DaoSpace and yin.vm sites** generally re-mint with one-argument
  `segment-key` and compare the resulting address against the stored one
  (an address-vs-address comparison with the same default-substitution
  flaw).

Both mechanisms are replaced by a single `(jing/segment-matches? address
payload)` call, which dispatches on the algorithm the address itself
carries.

| # | Site | Location | Current (non-conforming) shape |
|---|---|---|---|
| 1 | `dao.jing/materialize!` on `:present` read-back | `src/cljc/dao/jing.cljc:370-425` (body) | Internal to `materialize!`; verifies a `:present` backend response against the payload before returning. |
| 2 | `dao.jing.mem/validate-address-payload!` | `src/cljc/dao/jing/mem.cljc:26-29` | `(= (jing/segment-hash address) (jing/content-hash payload))` |
| 3 | `dao.jing.file/validate-address-payload!` | `src/cljc/dao/jing/file.cljc:74-77` | `(= (jing/segment-hash address) (jing/content-hash payload))` |
| 4 | `dao.jing.remote/validate-address-payload!` | `src/cljc/dao/jing/remote.cljc:37-44` | `(= (jing/segment-hash address) (jing/content-hash payload))` |
| 5 | `dao.jing.remote.step` verification of a `:present` response | `src/cljc/dao/jing/remote/step.cljc:349-352` | `(= (jing/content-hash (:value value)) (jing/segment-hash (:address record)))` |
| 6 | `dao.jing.dht/validate-address-payload!` | `src/cljc/dao/jing/dht.cljc:158-163` | `(= (jing/segment-hash address) (jing/content-hash payload))` |
| 7 | `dao.jing.dht` peer-fetched content verification before caching | `src/cljc/dao/jing/dht.cljc:223-226` (`make-get` peer fetch loop) | Inline `(= address (jing/segment-key (:value res)))` check before `(jing/materialize! local value)` caches it — see also Class 4 for the algorithm-preservation half of this same site. |
| 8 | `dao.jing.dht.node` store-request validation | `src/cljc/dao/jing/dht/node.cljc:112-115` | `(= (jing/segment-hash address) (jing/content-hash v))` |
| 9 | `dao.data.btree.storage` optional fetched-blob verification in the ordinary KV reader | `src/cljc/dao/data/btree/storage.cljc:53-58` (`kv-storage` reader, `jing/segment-key blob` comparison) | Re-mints with `segment-key` and compares whole addresses. |
| 10 | `dao.space.index/read-manifest` | `src/cljc/dao/space/index.cljc:307-316` | `(= actual-address manifest-address)` where `actual-address` is `(jing/segment-key manifest)` — re-mint-and-compare. |
| 11 | `dao.space.index/restore` schema validation | `src/cljc/dao/space/index.cljc:1370-1372` | `(= (jing/content-hash schema) (:schema-hash candidate))` — depends on the Class-2 `checkpoint-candidate` fix landing first (`:schema-hash` → `:schema-address`), after which this becomes `(jing/segment-matches? (:schema-address candidate) schema)`. |
| 12 | `yin.vm.content` row fetch verification | `src/cljc/yin/vm/content.cljc:95-101` | `(not= id (jing/segment-key body))` — re-mint-and-compare. |
| 13 | `yin.vm.content` vector fetch verification | `src/cljc/yin/vm/content.cljc:127-137` | `(when-not (= address (jing/segment-key v)) ...)` — re-mint-and-compare. |
| 14 | `yin.vm.macro` row-address verification | `src/cljc/yin/vm/macro.cljc:270-279` | `(= a (jing/segment-key (subvec % 1)))` — re-mint-and-compare. |
| 15 | `yin.vm.semantic` claimed-segment verification | `src/cljc/yin/vm/semantic.cljc:617-623` | `actual (when claimed (jing/segment-key (mapv canonical instructions)))` compared via `(if (and claimed (not= claimed actual)) ...)` — re-mint-and-compare. |
| 16 | `yin.vm.completion` reconstructed-image address verification | `src/cljc/yin/vm/completion.cljc:288-293` | `(= (:address image) (jing/segment-key v))` — re-mint-and-compare. |
| 17 | `yin.vm.ledger` row-address verification | `src/cljc/yin/vm/ledger.cljc:183-188` | `(jing/segment-key (subvec (get rows id) 1))` compared against `id` — re-mint-and-compare. |
| 18 | `yin.vm.ledger` output-address verification | `src/cljc/yin/vm/ledger.cljc:192-197` | `(when-not (= output (jing/segment-key vector)) ...)` — re-mint-and-compare. |
| — | `yin.vm.ledger` record/derivation verification | `src/cljc/yin/vm/ledger.cljc:200-207` | `(jing/segment-key ...)` compared against `actual` — re-mint-and-compare. (Grouped with #17-18 as one ledger family in the design text; listed separately here because it is a distinct call site.) |
| — | `yin.vm` semantic-bytecode row validation | `src/cljc/yin/vm.cljc:1245-1249` | `(when-not (= id (first row) (jing/segment-key body)) ...)` — re-mint-and-compare. |

(The design's prose enumerates these as "18 identified validation sites";
the table above lists 20 rows because two design bullets — the ledger
family and the semantic-bytecode row check — each cover more than one
distinct call site in the current tree. All are Class 3 regardless of
count; the number is not load-bearing, the disposition is.)

---

## Class 4 — Address-preserving copy paths (four sites)

These call sites receive content that is **already named by an address**
and move it into another store (a local cache or a remote peer) without
changing which algorithm names it. After verifying the payload (Class 3,
where applicable at the same site), they must materialize or flush using
the algorithm the source address already carries — never the destination's
ambient default. Using one-argument `materialize!`/`segment-key` here would
silently convert an intentionally SHA-256-addressed object into a
BLAKE3-addressed one merely by copying it.

| # | Site | Location | Required H1 shape |
|---|---|---|---|
| 1 | `dao.jing.dht/make-get` (peer-fetched content, local caching half) | `src/cljc/dao/jing/dht.cljc:180-230` (`create-content-dht`'s `:get-content-fn`; cache line at 229) | After Class-3 verification, cache with `(materialize! local payload {:algorithm (segment-algorithm address)})` instead of the current one-argument `(jing/materialize! local value)` at line 229. |
| 2 | `dao.data.btree.storage/hydrate!` (synchronous), `pull!` | `src/cljc/dao/data/btree/storage.cljc:165-191` (`hydrate!` at 165; local `pull!` letfn at 181-190) | Each hydrated blob's local materialize call must derive its algorithm from the address being hydrated. |
| 3 | `dao.data.btree.storage/hydrate-async` (asynchronous), `fetched!` | `src/cljc/dao/data/btree/storage.cljc:237-298` (`hydrate-async` at 237; `fetched!` local fn at 260-296, materialize call at line 286) | Same as #2, on the async path: `(jing/materialize! cache blob)` at line 286 must become address-algorithm-derived. |
| 4 | `dao.data.btree.storage/store-tree-async` remote flush of cache-minted blobs | `src/cljc/dao/data/btree/storage.cljc:301-345` (materialize-async invocation at line 322/338-339) | **Must carry the source address (and its algorithm) with the blob to the remote store**, using an address-supplying operation — `(put-content address payload)` or an equivalent explicit-address asynchronous request — rather than the current un-parameterized `materialize-async` (`:materialize-async-fn`) callback, which would incorrectly re-mint under the remote's ambient default. The remote store must validate the incoming address with `segment-matches?` before accepting it (a Class-3 obligation at the *remote* boundary, paired with this Class-4 flush). |

This is the architect-required correction: `store-tree-async` currently
flushes unacknowledged cache-minted blobs through the payload-only
`:materialize-async-fn` contract (`src/cljc/dao/data/btree/storage.cljc:322,338`,
callback declared at `src/cljc/dao/jing/remote/async.cljc:18,162`), which has
no address parameter at all. H1's fix is a **new asynchronous,
address-supplying remote operation** (the design's `put-content
[address payload]` shape, mirroring `dao.jing.remote.step/submit`'s
existing `put-content-op` at `src/cljc/dao/jing/remote/step.cljc:230,244`,
generalized to an async callback), not a parameter bolted onto the
existing `materialize-async-fn` signature — a payload-only signature cannot
be made address-preserving by calling it differently at the one call site
in `store-tree-async`; the contract itself must gain the address parameter,
or `store-tree-async` must be given a distinct async put entry point that
has one.

---

## Documentation-only SHA assumptions (out of scope for H1 source edits, tracked here)

Per the design's "Documentation-only SHA assumptions" section, docstrings
and error messages describing segment storage as SHA-only exist in:

- `src/cljc/dao/jing.cljc`
- `src/cljc/dao/jing/mem.cljc`
- `src/cljc/dao/jing/file.cljc`
- `src/cljc/dao/jing/dht.cljc` (namespace docstring at lines 19-21: "the DHT
  routes only `:segment/sha256-...` content addresses"; `content-target`
  docstring and error message at lines 37-38, 43; `node-id` docstring
  references its own frozen Class-1 SHA-256, which is accurate and does
  not need generalizing)
- `src/cljc/dao/jing/dht/kad.cljc`
- `src/cljc/dao/space/index.cljc`
- `src/cljc/dao/jing/stream.cljc` (line 105: "(jing/sha256-bytes ...) against
  the claimed address")

These are source-code docstring/error-text edits, out of the H0 file box
(`src/` is reserved for H1). This document records their locations now so
H1's source pass has a checklist; H0 makes no source edits.

---

## Architectural lint target

The design requires a source-level lint/test rejecting default
`dao.jing/content-hash` or `dao.jing/segment-key` used in
**equality-based validation position** outside `dao.jing` itself — i.e. any
of Class 3's current non-conforming call sites, if they were left
unconverted or regressed back to re-mint-and-compare after H1. The lint
target is exactly:

- `dao.jing/content-hash`
- `dao.jing/segment-key`

It must **not** flag `dao.jing.cbor/content-hash`, a distinct function in a
different namespace with unrelated semantics (portable kind-strict value
comparison for the CBOR value domain, per `dao.jing.cbor.md`'s "Numeric
identity" section — not an address operation at all). A lint keyed on the
unqualified symbol `content-hash` without namespace resolution would
misfire on this name collision; the lint must resolve the fully-qualified
var, not pattern-match the bare symbol.

The lint's legitimate exemptions are the reviewed Class-1 and Class-2 mint
sites in this document (where `segment-key`/`content-hash` mint rather than
validate) and any explicitly reviewed test-only mint assertion. It targets
*validation-position* use specifically — a call whose result is compared
against a pre-existing address for equality — not every call to these two
functions.

Contract tests specifying this lint's behavior, including the
`dao.jing.cbor/content-hash` non-misclassification case, live in
`test/dao/jing/hash_registry_contract_test.cljc` (§4, "Architectural Lint /
Guard Specification").

---

## Summary

- **5** frozen Class-1 SHA-256 contracts implemented today, plus **1**
  primitive-profile label requiring an H1 source fix to stay truthful under
  the new default, plus **1** future register contract recorded as not yet
  implemented — 7 rows total.
- **14** direct Class-2 mint call sites, **2** default-only remote mint
  entry points, and **1** DaoSpace mint site requiring an H1 field-shape
  change (`:schema-hash` → `:schema-address`) — 17 rows total.
- **20** Class-3 address-directed validation call sites (covering the
  design's 18 enumerated bullets), all currently using one of two
  non-conforming comparison shapes and all requiring conversion to
  `segment-matches?` in H1.
- **4** Class-4 address-preserving copy paths, three requiring
  algorithm-derived local materialization and one (`store-tree-async`)
  requiring a new address-supplying remote operation.

No production code changes in this document's own H0 phase; every
disposition above is an H1 obligation this classification pins in advance.

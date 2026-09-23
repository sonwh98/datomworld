# yin.vm de Bruijn linker over dao.stream (Phase B6)

Status: design, revised; not implemented

## 1. Objective and architectural position

This document specifies the closed-image code linker for `yin.vm` over
`dao.stream` (backed by `dao.jing` content addressing). It is Phase B6 of
the de Bruijn compilation and VM architecture.

Governing rule: **B6 is used by both the stack VM and the register VM for
linking code over `dao.stream`.**

Foundational principle: **The linker fetches code over `dao.stream`, but the
code itself is stored in `dao.jing`.**

In datom.world, code and continuations flow through streams: one universal
AST, multiple execution backends (stack VM and register VM), peer witnesses
to the same truth. Executable code is content-addressed data stored in
`dao.jing`, while linking, resolution, and fetching occur strictly over
append-only `dao.stream` boundaries. Upstream stages -- Yang, the resolver
(B2), the stack lowerer (B2), and the register compiler (R1, R2) -- produce
content-addressed executable images:
- Stack images, identified by H (`:yin.debruijn.code`)
- Register images, identified by R (`:yin.debruijn.register`)

The linker is the component that enables either VM to resolve, fetch,
verify, and link those executable images over `dao.stream`. A host holding
only an identity (H or R) and an index can obtain the image over a stream,
prove it matches the storage address, prove it matches the requested
contract identity, validate its structural and liveness invariants, verify
free-variable closure for the receiving environment, and deliver it for
execution.

Phase B6 is implemented alone as the dedicated linker phase. It establishes
the format-parameterized fetch function (`yin.vm.debruijn-linker/fetch`) and
provides the concrete format records for both the stack and register VMs.

## 2. Invariants: storage in dao.jing, fetching over dao.stream

The linker embodies the separation between storage and stream transport:

1. **Storage in `dao.jing`**: The executable code itself (the canonical
   instruction vector or register image map) is stored as an immutable,
   content-addressed value in `dao.jing`, located at its `segment-key`.
2. **Fetching over `dao.stream`**: Linking is an append-only stream process.
   When code is resolved, requested, or transferred across execution
   contexts or remote nodes, all exchanges travel strictly over
   `dao.stream`. No direct function calls cross process or host boundaries.
3. **Reused DaoJing substrate**: The linker builds directly on DaoJing:
   - `dao.jing.dht` handles local cache retrieval and Kademlia peer lookup.
   - `dao.jing.remote` handles DHT requests over `dao.stream` using
     `content-client` and `default-handlers`.

B6 reuses this substrate wholesale. The linker writes no transport, no peer
lookup, no cache, and no responder of its own.

### 2.1 What B6 adds

B6 contributes:

1. The format record protocol (section 5).
2. The format-neutral six-step fetch function (section 4).
3. Stack format record (`:yin.debruijn.code`) for stack VM code.
4. Register format record (`:yin.debruijn.register`) for register VM code.
5. The H index and R index mapping identities to Jing storage addresses.
6. Same-root pairing (H and R) for cross-VM fallback over streams.
7. The qualified refusal vocabulary (section 4.3).
8. Tri-host tests exercising local and remote fetch across JVM, CLJS,
   and ClojureDart.

### 2.2 What B6 does not add

- No new transport: `dao.stream` is the only medium; `dao.jing.remote`
  provides the RPC client and server.
- No peer routing or caching: handled entirely by DaoJing DHT.
- No global registry: handles, indexes, and format records are explicit
  arguments.
- No callbacks: every outcome is a returned, qualified data value.
- No execution coupling: the linker fetches and verifies; running the
  image belongs to the VM kernels or lifting.

## 3. The address and identity distinction

Neither H nor R is the `dao.jing` storage address. This follows governing
decision D9: Jing addresses are storage locations, not VM identities.

The distinction is:

    address = (jing/segment-key image-payload)
    H       = (image-hash stack-instruction-vector)
    R       = (register-hash register-image-map)

They differ in preimage and purpose:

- `segment-key` is computed by the storage layer over the order-normalized
  print of the value, using the address-carried algorithm (BLAKE3 by default
  in DaoJing today, with SHA-256 registered). It changes whenever the
  storage serialization format changes (e.g. when DaoJing CBOR lands).
- H and R are pinned to explicit SHA-256 by VM contract freeze and embed the
  lowering contract version. They must never fork when the storage encoder
  changes.

Because the storage address and the VM identity have different preimages,
the linker must execute both checks:

- Step 2 proves the retrieved value is authentic content at that storage
  address.
- Step 3 proves that authentic content is the exact program requested.

Neither check subsumes the other.

## 4. The fetch function

### 4.1 Interface

The public entry point in `yin.vm.debruijn-linker` is:

```clojure
(defn fetch
  "Fetches and verifies an image by its identity using handle, index,
   and format record. Used by stack and register VMs alike. Returns the
   verified image or a qualified defect."
  [handle index format identity]
  ...)
```

`handle` is any DaoJing store handle satisfying `dao.jing/get`: local store,
`create-content-dht` DHT handle, or `content-client` remote stream handle.

`index` is a lookup function, plain map, or queryable store resolving
`identity` (H or R) to a Jing storage address.

`format` is a format record (section 5).

`identity` is the format identity: H for stack images, R for register
images.

### 4.2 The six steps

Fetch executes six steps in strict order:

```clojure
;; (fetch handle index format identity)
;; 1. address  <- (index identity)
;;               absent entry            -> :absent
;; 2. value    <- (jing/get handle address absent)
;;               absent value            -> :absent
;;               (not (jing/segment-matches? address value))
;;                                       -> :address-mismatch
;; 3.           (not= identity ((:hash-fn format) value))
;;                                       -> :hash-mismatch
;; 4.           ((:validate-fn format) value) returns a defect
;;                                       -> that qualified defect
;; 5.           ((:free-names-fn format) value) against receiver environment
;;               unresolved name         -> :unresolved-free
;;               name shadowed by store  -> :shadowed-free
;; 6. return the verified image
```

Step order is load-bearing:

1. Address verification (step 2) precedes identity verification (step 3)
   because the storage address was requested from the store. Crucially,
   `(jing/segment-matches? address value)` verifies the value against the
   specific hash algorithm carried by `address`, rather than assuming
   DaoJing's current minting default.
2. Validation (step 4) precedes the closure check (step 5) so that
   `:free-names-fn` only ever encounters structurally valid images. For
   register images, step 4 includes the verified in-band live-set rules.
3. The closure check (step 5) precedes return so that an unclosed image is
   never handed to the caller. D11 requires refusal, not execution, for an
   image the receiver cannot bind identically.

There is no separate descriptor check. The descriptor hash and contract
version are embedded inside H and R. A descriptor or version disagreement
fails at step 3 as `:hash-mismatch`.

### 4.3 Refusal vocabulary

Every refusal is qualified plain data:

    +---------------------+--------+----------------------------------------+
    | Outcome             | Step   | Meaning                                |
    +---------------------+--------+----------------------------------------+
    | :absent             | 1 or 2 | The index has no entry for identity,   |
    |                     |        | or address has no payload on any store |
    | :address-mismatch   | 2      | The received value does not match the  |
    |                     |        | address algorithm and digest           |
    | :hash-mismatch      | 3      | The value at that address does not     |
    |                     |        | hash to identity (H or R)              |
    | :descriptor-defect  | 4      | The image fails structural/liveness    |
    |                     |        | validation                             |
    | :unresolved-free    | 5      | A :load-free name cannot be resolved   |
    |                     |        | in receiver's primitive/module env     |
    | :shadowed-free      | 5      | A :load-free name is shadowed by the   |
    |                     |        | receiver's free env or store           |
    | :pairing-mismatch   | verify | Re-lowered H and R do not match the    |
    |                     |        | claimed root pairing                   |
    +---------------------+--------+----------------------------------------+

## 5. Format records: stack and register

B6 provides the format records for both execution backends:

### 5.1 Stack format record (`:yin.debruijn.code`)

```clojure
{:format        :yin.debruijn.code
 :hash-fn       yin.vm.debruijn-code/image-hash
 :validate-fn   yin.vm.debruijn-code/image-defect
 :free-names-fn yin.vm.debruijn-linker/stack-free-names}
```

The payload stored in Jing is the canonical instruction vector value itself,
at its own `segment-key`.
- `image-hash` is B1's hash over the descriptor hash and canonical vector.
- `image-defect` validates opcodes, operands, bounds, body arity chains,
  and host scalar support.
- `stack-free-names` is defined by the linker module (scanning `:load-free`
  operands in the canonical vector), preserving "Existing edits: none".
- The lift to `:yin.code/*` is `yin.vm.debruijn-linearize/lift`.

### 5.2 Register format record (`:yin.debruijn.register`)

```clojure
{:format        :yin.debruijn.register
 :hash-fn       yin.vm.debruijn-register-code/register-hash
 :validate-fn   yin.vm.debruijn-register-code/register-image-defect
 :free-names-fn yin.vm.debruijn-linker/register-free-names}
```

The payload stored in Jing is the register image map `{:bodies ...
:instructions ...}`, at its own `segment-key`.
- `register-hash` is R1's hash over the descriptor hash and register vector.
- `register-image-defect` validates opcodes, register bounds, body ranges,
  and the section 4.5 ascending, bounded, exact live sets.
- `register-free-names` is defined by the linker module (scanning
  `:load-free` operands across all bodies in the instruction vector),
  preserving "Existing edits: none".
- The lift to `:yin.code/*` is `yin.vm.debruijn-register-compile/lift`.

Because `fetch` is parameterized by this record, adding future VM formats
requires only defining a new format map.

## 6. The H and R indexes

The index maps an identity to a Jing address:

    H index:  H -> address   as a datom  [H :yin.debruijn.code/address address]
    R index:  R -> address   as a datom
              [R :yin.debruijn.register/address address]

The index is composition data, never DaoJing's: DaoJing has no roots and no
names by design (stack design D16). The linker takes the index as an
argument, either a plain Clojure map or a datom collection read through the
composition's store.

An index entry is a claim, not a proof. Step 3 protects against stale or
swapped entries (refusing `:hash-mismatch`). When the DaoJing storage encoder
changes (e.g. CBOR), the indexes are re-minted, while the underlying code
images and their identities (H and R) remain unchanged.

Phase B7 will later make the index a full name environment with ledger,
trust, and provenance. Phase B6 requires only a plain data value.

## 7. Same-root pairing (H and R)

A host without a register kernel may refuse R and instead resolve, by H, a
stack image published for the same named root. Because H and R have disjoint
preimages, a bare H-to-R map has no verification law.

The pairing is recorded at mint time beside the named root as datoms:

    [root :yin.debruijn.code/hash     H]
    [root :yin.debruijn.register/hash R]

Two fallback paths exist:

1. Trusted fallback: The receiver accepts the pairing on composition trust
   and states so in its outcome.
2. Verifying fallback: The receiver fetches the named datoms by the root,
   re-lowers them locally via `adapt` and `lower-register`, and accepts
   the fallback only when the recomputed H and R both match. Otherwise it
   refuses with `:pairing-mismatch`.

## 8. Dependency risk: transitional content hash

`segment-key` hashes the order-normalized print of a value. Every Jing
address will change when DaoJing's canonical CBOR encoding lands. H and R
will not change, but every H and R index must be re-minted then.

Consequences:
- Tests pin H and R values only. No test ever pins a Jing address as a
  golden; addresses are always computed dynamically.
- Until CBOR lands, Jing addresses are portable only between hosts whose
  print of the value agrees. Cross-host test corpora are restricted to
  print-stable scalars.
- A print divergence fails closed: `:absent` or `:address-mismatch`, never
  execution of wrong code.

## 9. Stream topology: fetching over dao.stream

Reference-by-hash is a stream process, not hidden VM machinery:

1. **Stream boundary**: The linker consumes image requests over a stream and
   emits linked code or explicit refusals onto a stream. Local and remote
   resolution follow the exact same stream protocol.
2. **Storage decoupling**: The linker fetches code over `dao.stream`, but the
   code itself is stored in `dao.jing`. DaoStream transports the requests and
   the retrieved byte payloads; DaoJing content-addresses and stores the
   underlying immutable data.
3. **Parked requests**: A VM encountering an unlinked `:call-hash` (in B7)
   parks its continuation and emits a request carrying the identity (H or R).
   The composition resolves the identity through its index and initiates
   `fetch`. All request tokens, continuations, and response outcomes are pure
   data on the stream.

## 10. File box: Phase B6

    New: src/cljc/yin/vm/debruijn_linker.cljc
    New: test/yin/vm/debruijn_linker_test.cljc
    Existing edits: none
    Depends on: dao.jing (segment-matches?, segment-key, materialize!, get),
                dao.jing.dht (create-content-dht, IDhtNet),
                dao.jing.remote (content-client, default-handlers),
                B1 (image-hash, image-defect, descriptor),
                B2 (lift),
                R1 (register-hash, register-image-defect, descriptor, lift)
    Must not change: merged projection namespace, image-hash, register-hash,
                     dao.stream, dao.jing, dao.jing.dht, dao.jing.remote,
                     named VM semantics

`yin.vm.debruijn-linker` exports:
- `fetch`: the parameterized fetch function.
- `stack-format`: the `:yin.debruijn.code` format record.
- `register-format`: the `:yin.debruijn.register` format record.
- `stack-free-names`: scans free names from stack instruction vectors.
- `register-free-names`: scans free names from register image maps.
- `verify-same-root-pairing`: verifies an H and R pair against source datoms.
- Qualified refusal constructors and predicates.

## 11. Completion criteria: Phase B6

Cross-host transfer over `dao.stream`:

1. (H, R) JVM to Dart transfer over `dao.jing.remote`'s DaoStream transport,
   where the receiver initially knows only the identity (H or R) and an
   index. Includes the Dart-side client harness for that path.
2. (H, R) Equal normalized results under the B0 normalizer when the fetched
   image is lifted and executed through the existing semantic VM, compared
   to local execution.
3. (H, R) The cross-host corpus is restricted to print-stable scalars until
   CBOR lands; no golden test pins a Jing address.

Refusals:

4. (H, R) `:absent` for an identity the index cannot resolve.
5. (H, R) `:address-mismatch` when the received value does not match the
   address algorithm and digest, tested via:
   a. Local storage corruption.
   b. Corrupt client RPC response over DaoStream.
6. (shared) A DHT peer serving mismatched content is rejected before load;
   `make-get` filters invalid peer payloads, returning `:absent` if no peer
   has valid data.
7. (H, R) `:hash-mismatch` when a well-stored value at the indexed address
   does not hash to the requested identity, including descriptor or contract
   version disagreement.
8. (H, R) Structural validator defect in a fetched image refused before load.
9. (R) Live-set defect in a fetched register image refused before load.
10. (H, R) `:unresolved-free` for a `:load-free` name the receiver cannot
    resolve through primitives or modules.
11. (H, R) `:shadowed-free` for a `:load-free` name shadowed by the
    receiver's free environment or store.
12. (R) Trusted fallback from R to H that names its trust in the outcome.
13. (R) Verifying fallback that re-lowers named datoms, refuses a swapped
    pairing with `:pairing-mismatch`, and accepts an authentic pairing.

Structure:

14. One fetch function serves both stack and register formats without
    format-specific branching in `fetch`.
15. Tests use an explicit `fetch` call; no `:call-hash` instruction is
    emitted or consumed (deferred to B7).
16. No global loader, registry, callback, or cache is introduced; handle
    and index are explicit arguments.
17. Tri-host parity: JVM, Node/CLJS, and ClojureDart pass all tests cleanly,
    with 0 kondo errors and clean cljstyle formatting.

## 12. Non-goals

The B6 linker does not:

- Add transport, peer lookup, caching, or responders (owned by DaoJing).
- Emit or interpret `:call-hash` (deferred to B7).
- Supply a name environment with ledger or provenance (deferred to B7).
- Provide cross-model continuation transport.
- Alter `image-hash`, `register-hash`, `dao.stream`, or existing VM kernels.

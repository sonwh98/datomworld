# dao.stream.host-effects: entropy, storage, parser stage and conformance (H5-H8)

Status: **r3, adopted 2026-10-22; confirmed with recorded residuals; nothing commissioned.**
Authored by the Architect (claude-fable-5-1) in three rounds, applying two independent reviews by
astra (gpt-6-astra): the r1 review (ACCEPT AFTER CHANGES), the r2 recheck (CONFIRMED AFTER CHANGES)
and the final narrow check of r3 (CONFIRMED WITH RECORDED RESIDUALS; no new P1). The review reports
are kept in `collab/` (never committed): 1792662000000, 1792664000000, 1792666000000. Every claim
was re-read on master `79394cb8`. Successor to
[dao.stream.host-boundary.md](./dao.stream.host-boundary.md) §9. Owner questions (§9) are decided
one at a time and recorded in the decision log at the end of this document. The owner may commission
H5a/H5b/H5c/H5d-1/H5e/H6/H7/H8a; H5d-2 stays gated. Labels: V = verified by reading the tree (line cites); R =
read or inferred, not run; P = proposed. Nothing was executed.

## Disposition of astra's recheck items (what changed, where)

| Item | What r3 changes | Where |
|---|---|---|
| 1. Allocation incomplete | Direct `(minter key)` constructor **removed**; minters are obtained only from an allocator that refuses a duplicate (kind, purpose) key; allocator ownership (one per root in the driver, one sub-allocator per composition) and exhaustion stated; purpose framing changed to `S ++ be32(len) ++ utf8(purpose)` so the length-prefix rule is uniform; exact u64 counter representation over `dao.host.integer` and a 53-bit extraction that never exceeds 2^53 in any intermediate; tests for duplicate construction, subroot allocation, restart/replay, counters beyond 2^53 and at exhaustion | §2.2, §6 H5a |
| 2. Vectors and refusals | The `input[i] = i mod 251` recipe with the official key and context restored; derive-key refusal data per argument; the per-host work list written out; the export-map listing dropped (wildcards, per astra) | §2.3, §6 H5a |
| 3. H6 callers | Six further result-discarding sites owned with their transitions (`repl/index.cljc:352-357`, `repl/dht.cljc:352-354,713,840-845`, `main.cljc:791,796`); `repl/index.cljc` added to H6; pending durability lives on the store handle; follower retries bounded through the existing `waiting`/`next-delay` schedule; journal rule requires `:platform-durability :power-loss`; failed-append progress specified | §3.3, §3.4, §6 H6 |
| 4. H5d-2 manifest | Explicit manifest (loader, coordinate, query, DHT extraction, driver/facade, consumers); H5d-1 lists five files; the `coordinate/open!` local arm labelled **proposed** (today only `:dao.jing/file` and `:dao.jing/remote`, V `:93-99`) | §2.6, §6 |
| 5. Worker lifecycle | r2's claim that `repl.cljc:1817` clears a worker **withdrawn** (it clears `:frontend-session`, V); a real lifecycle: shell-state-owned worker, opened lazily through the binding, closed on `(reset)`, `(vm ...)`, `(lang ...)` and shell close; parse/probe closures take the worker as an argument so no rebinding is needed; files listed | §4, §6 H7 |
| 6. H8a inventory | Remote reflection and UDP rows completed from `remote.cljc:870-980,1231-1265` and `udp.cljc:192-231`; no R cell remains except one function-boundary note on the ws close line | §5.1 |
| 7. Overlaps | H7's `main.cljc` and `repl.cljc` edits and H6's `coordinate.cljc` edit added to the manifests; the "file-disjoint" claim for H5a/H6 **removed** (both edit `main.cljc`); serialization order per shared file | §6, §7 |
| 8. Owner questions | OQ-H2 now conditions on §2.2's allocation rules by name; OQ-H5 separates publisher and follower bounded retries from the settings file's warning-only policy | §9 |

No item is rejected. Items 2, 4, 5, 8 are applied as astra specified; items 1,
3, 6, 7 required new design text, written below.

Tree state (V): H0 `9f6fc27d`, H1 `6b21521c`, H2 `4b520bb8`, H3 `1c1f003c`,
H4 `711c53e4`+`7e7fd4be`, codec C0-C4 (C4 `f928c44e`), jolt J0a/J0b/J0c/J1/J1b
on master. Table A is **76** (64 `dao.host.*` + 12 `dao.data.arrays`; Table B
excluded, parent §4.2); §2.3 makes it 78.

Owner invariants honoured: dao.stream is the host and complexity boundary;
P2P, no privileged node; `dao.stream.apply` independent of rpc; linker and VM
language-blind; clean breaks; the Jing is syntax; yin.vm knows nothing about
macros; derive, don't persist.

---

## 1. Scope

| Slice | Content | Commissionable? | Why |
|---|---|---|---|
| H5a | `dao.mint` (allocators, minters, derived forms); Table A +2; the driver's root draw | **Yes** | §2 answers every §9.1 input |
| H5b | Stream identities as creation-spec data | **Yes** | after H5a; manifest §6 |
| H5c | Runtime ids and secrets by derivation; `sign/generate` deleted; DHT subroot; keygen | **Yes** | after H5a, H5b |
| H5d-1 | Promise facade out of the closure; host banner as data | **Yes** | five files, mechanical |
| H5d-2 | Remote content coordinates as a stepped loader | **No: its own design step** | §2.6 |
| H5e | `gensym` hygiene and transact tempids | **Yes** | three files |
| H6 | Durable-storage seam, replace outcomes owned end to end | **Yes** | §3 |
| H7 | Parser stage seam, worker lifecycle, probe | **Yes, low priority** | §4 |
| H8a | Outcome-table conformance for datagram, udp, ws, remote reflection | **Yes** | §5.1 inventory is complete |
| H8b | wasm import-list gate | **Deferred**, fixtures specified | no binary exists (V) |

Ambient facts and printing: no slice (parent §4.4; list 2 V `gate_test.clj:751`).

## 2. H5: entropy, identities, scheduling

### 2.1 OQ9, decided: identity-in-spec with a driver root; no entropy stream

The driver's `main` draws a **32-byte root** once per process (per host:
`SecureRandom`, `crypto.getRandomValues`, `Random.secure`; jolt: libcrypto
`RAND_bytes`), beside argv and stdin, as driver IO. The root is private
composition input: never in a descriptor, AST, log, stream value or
persisted record. What enters the core is derived data: subroots in
options, identities in creation specs.

World contacts that remain after H5-H8: the driver's root draw; one fresh
draw per `keygen` key file; the storage seam's claim nonce (§3.2); the jolt
ws seam's masking keys (J6); clock reads in seams and the driver. All in the
driver or inside a seam, none in portable interpreters.

Rejected, the entropy stream: cursors are non-destructive
(`dao/stream.cljc:159-173`, V), so deposited bytes cannot carry secrets;
exclusive consumption would need a lease; the stream needs an identity
before its first byte. The §9.1 inputs on allocation, bootstrap, partial
batches and blocked resumption are answered by this rejection.

Not a host service (abi.md §7): a mint is a pure derivation over a value
the composition holds, the shape of `:secret-source` at `yin/repl.cljc:668`
and `dao/await.cljc:238` (V), made generator-free.

Reproducibility: a fixed root reproduces the **set** of derived values and,
for one allocator stepped by one owner, their order; it does **not**
reproduce allocation order across concurrent allocators.

### 2.2 Allocation (P): allocators own keys, minters own counters

**Framing rule, applied everywhere.** Dynamic input to any derivation is
bytes with fixed-width integers big-endian (`be32`, `be64`) and strings as
`be32(byte-length) ++ utf8`; contexts are fixed, versioned ASCII strings
holding no dynamic data; no delimiters.

- **Root allocator** (driver-owned, exactly one per root):
  `(allocator R)` = `{:root R :next-subroot <u64 state> :issued #{}}`.
  `(subroot! A)` answers `derive-key("datom.world dao.mint v1 subroot",
  R ++ be64(n))` for the allocator's next n and advances it; the driver
  hands each composition instance (a REPL state, each DHT node, each UCF
  compose, each link endpoint) **one distinct subroot**.
- **Sub-allocator** (one per composition): `(allocator S)` over a subroot.
  Keys are issued only through it: `(minter! A kind purpose)`, kind
  `:ids` or `:secrets`, derives `key = derive-key(ctx(kind), S ++
  be32(len) ++ utf8(purpose))` with `ctx(:ids)` = `"datom.world dao.mint v1
  ids"`, `ctx(:secrets)` = `"datom.world dao.mint v1 secrets"`, records
  `[kind purpose]` in `:issued`, and **refuses a second request for the same
  pair** with `{:dao.mint/refusal :duplicate-key :kind k :purpose p}`. There
  is no other way to construct a minter, so two minters over one key cannot
  exist in a correctly composed process; a composition that builds two
  sub-allocators over one subroot is the one bug no gate can see, and the
  design names it.
- **Minter**: `{:key k :next <u64 state>}` owned by exactly one step owner
  (the interpreter the composition handed it to). `(next! m)` answers
  `blake3-keyed(k, be64(n))` and advances. **Exhaustion**: the counter
  ranges over `0 .. 2^64-1`; when `:next` reaches `2^64` the minter answers
  `{:dao.mint/refusal :exhausted}` on every later call and never wraps.
- **Counter representation, cross-host.** `:next` is a `dao.host.integer`
  exact integer (native below the host's safe limit, the big carrier above
  it on JS: Table A `promote`/`add`). `be64(n)` is produced by eight
  `rem-trunc`/`quot-trunc` steps by 256 over the exact integer, most
  significant byte first, so `n = 2^53`, `2^53+1` and `2^64-1` serialize to
  `00 20 00 00 00 00 00 00`, `00 20 00 00 00 00 00 01` and eight `ff` on
  every host. No floating-point operation touches the counter.
- **53-bit extraction without rounding.** From the 32-byte output take
  bytes `b0..b6`; `hi = (b0*2^16 + b1*2^8 + b2) mod 2^21` (at most 21
  bits); `lo = b3*2^24 + b4*2^16 + b5*2^8 + b6` (at most 32 bits, below
  2^32, exact in a double); `value = hi * 2^32 + lo` (below 2^53, exact);
  for `bits < 53`, `value mod 2^bits`. Every intermediate is an integer
  below 2^53, so the JS double path is exact; the JVM and Dart use longs.
- **Restart and replay.** The root is never persisted; a restarted process
  draws a new root, so no id or secret repeats across restarts. Identities
  that must survive restart (a journal header; a DHT node id should the
  owner make it durable) are **read back** from storage, never re-derived.
- **Derived forms.** `uuid`: 16 bytes of a minter output with the RFC 4122
  version-4 nibble and variant bits set, printed 8-4-4-4-12 lowercase, so
  `custody/occurrence?` (`custody.cljc:53`, V) accepts it: UUID grammar
  preserved by construction. `hex-secret`: 64 hex of a 32-byte `:secrets`
  minter output. `integer bits`: the extraction above.
- Key separation: the DHT **authentication secret** (`::secret`, used by
  `cookie-for`, `jing/dht.cljc:180,203,720`, V) is a `:secrets` key with
  purpose `"dao.jing.dht/cookie"`; DHT query ids (`:365`, V) an `:ids` key
  with purpose `"dao.jing.dht/query"`. Public identifiers and secrets never
  share a key.

Tests (`test/dao/mint_test.cljc`, three lanes and `compare:jolt-host`):
duplicate `(minter! A k p)` refuses; two `subroot!` calls differ and two
allocators over equal roots give equal subroots in order (determinism);
distinct purposes and distinct kinds give distinct keys; `be64` goldens at
`0`, `2^53-1`, `2^53`, `2^53+1`, `2^64-1`; a minter preset to `2^64-2`
answers twice then `:exhausted` forever; a 7-byte prefix of all `ff` yields
`2^53-1`; restart: a journal reopened under a different process root keeps
its header identity (`journal_test.cljc:111`, V exists); a REPL state
created twice under two roots has two shell tokens.

### 2.3 Profile extension (Table A 76 -> 78, `dao.host.hash`, P)

| Entry | Law |
|---|---|
| `blake3-keyed [key bs]` | P. `key`: host bytes of **exactly 32**; `bs`: host bytes; answers **fresh** 32-byte host bytes; both arguments byte-identical afterwards. Refusals: key not bytes or not 32 -> `{:dao.host/refusal :unsupported-value :dao.host/entry 'blake3-keyed :dao.host/argument :key}`; `bs` not bytes -> same with `:argument :bs` |
| `blake3-derive-key [context material]` | P. `context`: a string, encoded by `text/utf8-encode`; `material`: host bytes; answers fresh 32-byte host bytes; immutability as above. Refusals: context not a string -> `{... :entry 'blake3-derive-key :argument :context :reason :not-a-string}`; context with an unpaired surrogate -> `:reason :invalid-utf8` (the `utf8-encode` refusal re-raised under this entry); context containing U+0000 -> `:reason :nul-in-context` (refused on every host so no host API difference is observable); material not bytes -> `:argument :material` |

**Oracle (`dao.host.hash-test`, the existing `blake3-test-vectors` and
`hash-refusals-and-immutability` deftests extended, V `hash_test.cljc:83,92`;
`compare:jolt-host` cases).** Inputs follow the official recipe already in
`test/resources/dao/jing/blake3-vectors.edn` (V: `input[i] = i mod 251` at
lengths 0, 1, 2, 3, 64, 1023, 1024, 1025, 2048, 16384 ...); for each
length, `keyed_hash` with the official 32-byte key (the ASCII bytes of
`whats the Elvish word for friend`) and `derive_key` with the official
context (`BLAKE3 2019-12-27 16:29:52 test vectors context`), first 32 output
bytes, added to the EDN fixture as `:keyed-vectors` and `:derive-key-vectors`
(R for the exact key/context strings: copied from the upstream
`test_vectors.json` at fixture-writing time, never retyped). Refusal rows:
31- and 33-byte keys, non-bytes key, non-string context, unpaired-surrogate
context, NUL context, non-bytes material; immutability rows on all four
lanes.

**Per-host work.** JVM: wrap `io.github.rctcwyvrn.blake3`'s installed
`newKeyedHasher` and `newKeyDerivationHasher` (astra, V on the installed
jar); Node: `@noble/hashes/blake3` with `{key}` and `{context}` where
**`context` is caller-encoded bytes** (V `blake3.d.ts`: "context is
caller-encoded bytes"), so the wrapper passes `text/utf8-encode`'s output;
Dart: the exported `blake3Keyed` and `blake3DeriveKey` (V
`blake3_compact.dart:303-339`; its derive-key UTF-8-encodes a String, so
the wrapper passes the string after the profile's own refusal checks);
jolt: two C wrappers in `native/jolt-crypto/jolt_crypto.c` (today only
`blake3_hasher_init`, `:69`, V) over `blake3_hasher_init_keyed` and the
length-aware `blake3_hasher_init_derive_key_raw` (R, `c/blake3.h`), two
`defcfn` bindings in `src/jolt/dao/host/hash.jolt`, native digest
requalification in `jolt-toolchain.edn` `:native`, `probe:jolt-crypto` and
`compare:jolt-host` cases (V `bb.edn:30,52`). All four: validation, fresh
outputs, immutability, vectors.

### 2.4 The §9.1 inputs

| Input | Answer |
|---|---|
| Exclusive allocation, bootstrap, partial batches, blocked resumption | Moot (§2.1) |
| UUID spelling | §2.2 |
| DHT init (`space/dht.cljc:255-269`, V) | `join` takes a required `:subroot`; `option-refusal` (`:168-211`, V) gains "a node needs a 32-byte :subroot"; `::dht/id` = `:ids` key `"dao.space.dht/id"` output cut to `dht/id-length`; `::dht/secret` as §2.2; six ring identities from an `:ids` minter `"dao.space.dht/ring"`. Failure: a refused join opens nothing; a driver that cannot draw refuses at startup (`main.cljc:894-897`, V). `secure-random-bytes` (`:142-151`) deleted |
| Async completions as data | `dao.jing.content.step` is the data shape (V). H5d-1 moves the facades; H5d-2 (§2.6) is the scheduling obligation |
| Codec-track edits | C4 landed; `ws.cljc:115` `attachment-id` becomes an `:ids` minter in the attacher/endpoint state (`:520,:656`, V); `ws_test.cljc:17`, `remote_route_test.cljc:24` supply spec identities (H5b) |
| `gensym` (`yang/clojure.cljc:349,359`, `yang/php.cljc:583`, V) | Fixed reserved names: `(and x y)` lowers to `(let [g x] (if g (and y) g))`, the inner expansion never references the outer `g` (V `:350-351,:360-361`), so one name shadows correctly; `g` = `yang.clojure/and` (`/or`); PHP `yang.php/loop-<depth>` with the depth passed down. Frontend policy (OQ-H7): refuse a user binding or free reference in `yang.*` in generated-syntax positions; preserve quoted user data. Lowering becomes deterministic |
| `transact.cljc:35` | `tid_auto_<i>` by position; transaction-scoped (`collect-tempids :133-144`, V); reserved prefix refused for user tempids; `transactor.cljc:58`'s constraint is the reversal condition (§10) |
| Out-of-closure sites | Scope is the transitive closure; list 2 gains `:pending-entry` rows |

### 2.5 H5c manifest of runtime sites (V)

`ringbuffer.cljc:185`; `ws.cljc:115`; `remote.cljc:1249`;
`remote_pair.cljc:180`; `remote_route.cljc:173`; `content/step.cljc:132`;
`linker.cljc:1683`; `rpc.cljc:114-116`; `jing/dht.cljc:365`;
`space/dht.cljc:142-151,262,268`; `custody.cljc:46`; `holder/driver.cljc:1507`;
`lease.cljc:594`; `telemetry.cljc:112`; `repl.cljc:667,668,733,918`;
`repl/dht.cljc:118`; `linker/sign.cljc:108-118` (`generate` deleted);
`main.cljc:251-271` (`keygen!` draws through the driver seam, then
`dao.host.sign/keypair-from-seed`); `test/dao/host/gate_test.clj:726-770`.
Nineteen source files plus the gate.

### 2.6 H5d-2: remote content as a stepped loader (design step, not a slice)

Defect, verified: `coordinate/open!` answers the JVM blocking driver
(`coordinate.cljc:80`) whose `get-bytes-fn` waits on a clock and sleeps
(`driver.clj:153-194,217-219`); `query/open-published!` calls
`published-db` synchronously (`query.cljc:268-270`) through
`index/read-manifest` -> `jing/get` -> `:get-bytes-fn`
(`index.cljc:306-308,337`, `jing.cljc:447-453`). A stepped client map has no
`:get-bytes-fn`.

Proposed shape (P, reviewed as its own step): **a remote coordinate is
loaded, not read through**, lifting the DHT node's existing one-fetch-at-a-
time walk (`space/dht.cljc:1092-1135`, V) and its `:loaded`/`:load-failed`
events (`:44-47`) into a shared loader.

**Manifest (P).** New `src/cljc/dao/space/load.cljc` (`open`, `step`,
`close!` over the stepped client and a local target store); changed
`dao/jing/coordinate.cljc` (the `:dao.jing/remote` arm answers
`{:dao.jing/stepped client-state}` on every host, the `:clj` arm removed;
**proposed** new local arm `:dao.jing/local` wrapping a caller-supplied
handle, there is none today, V `:93-99`); `dao/space/query.cljc:256-282`
(`open-published!` keeps its synchronous contract for `:dao.jing/file` and
the proposed local arm and refuses a remote coordinate with "load it
first"); `dao/space/dht.cljc:1092-1135,1484-1490` (the walk delegates to
`load`); `src/clj/dao/jing/content/driver.clj` moved to
`src/clj/dao/jing/content/blocking.clj` outside the closure, and
`src/clj/dao/jing/dht/facade.clj:21,58` re-pointed (JVM-only by its
docstring, V); consumers `test/dao/space/stigmergy_test.clj:151,247,262`
(remote coordinate through `open-published!`), `test/dao/space/index_test.cljc:629-659`
(remote open and refusals), `test/dao/space/schema_test.cljc:1618-1721`
(coordinate kind to be read at that step); H5d-1's facades. Open points:
whether `restored-indexes`' lazy hydration (`index.cljc:337-345`) must be a
stepped walk or always follows a whole load; the per-step budget; failure
cleanup closing both attachments and discarding the partial target.

## 3. H6: the durable-storage seam

### 3.1 Shape

Contract `dao.storage` (vocabulary, closed outcomes, portable algorithms)
plus one driver-constructed seam per host (`dao.storage.jvm`, `.node`,
`.dart`; jolt `src/jolt/dao/storage/chez.jolt` in J4). Consumers take the
seam: `dao.jing.file/create-content-file [path seam]`, `dao.space.store/open
[dir seam]`, `dao.stream.journal.file/backend! [dir seam]`,
`coordinate/open!` via `:dao.jing/storage` (this is H6's edit to
`coordinate.cljc`); `yin.repl.store` and `main.cljc` compose it. Seam rule
as parent §4.1. Capabilities are declared at construction from platform
facts read once (`fs.cljc:264-273`, V); algorithms never interrogate the host.

### 3.2 Operations and outcomes (P; maps keyed `:dao.storage/outcome`)

| Op | Outcomes |
|---|---|
| `open! [path mode opts]`, modes `:read` `:append` `:read-write` `:create-exclusive`; `{:owner-only? true}` applied **at creation, before any byte** | `ok` + `:file` ; `absent` ; `exists` ; `not-regular` ; `unsupported` (owner-only on Dart, V `main.cljc:264`) ; `failed` + `:error` |
| `length [f]` | `ok` + `:length` (non-negative integer) ; `failed` |
| `read-at! [f offset n]` | `ok` + `:bytes` (exactly n) ; `short` + `:bytes` (0 <= k < n) ; `failed` |
| `append! [f bytes]` | `ok` + `:written` (= length) ; `partial` + `:written` (0 < k < length; the caller continues from k) ; `failed` + `:written k` (**k = bytes the host acknowledged before the error, 0 when it reported none**; the content file treats any `failed` or abandoned `partial` as a torn tail and truncates to the frame start before any retry, the F3 rule, V `file.cljc` docstring) ; `failed :reason :no-progress` (a write storing nothing, V `fs.cljc:331-333`) |
| `truncate! [f n]`, `sync! [f]`, `close! [f]` | `ok` ; `failed` (close idempotent) |
| `rename-replace! [from to]` | `ok` ; `failed` with the guarantee that `to` is unchanged (same-directory rename is atomic on POSIX, R) |
| `sync-directory! [dir]` | `ok` ; `unsupported` (declared) ; `failed` |
| `create-dir!`, `list-dir`, `stat` (`:regular` `:directory` `:absent` `:other`), `unlink!`, `canonical-path` | data ; `failed` |
| `lock-exclusive! [path]` | `held` + `:release!` ; `refused` ; `unsupported` |
| `release!` | `ok` ; `ok :already-released` ; `failed` (still held) |
| `create-claim! [dir prefix]` | `ok` + `:name` `prefix.<pid>.<nonce>` created exclusively (`O_EXCL`/`wx`, V `fs.cljc:142`), nonce drawn inside the seam ; `exists` (collision: refuse, never retry under the same name) ; `failed` |
| `own-pid []` | integer or nil |
| `process-alive? [pid]` | `true` ; `false` ; `unknown` (EPERM is `true`, V `:92-95`) |
| `set-owner-only! [path]` | `ok` ; `unsupported` ; `failed` |
| `capabilities []` | `{:lock-kind :os-lock\|:claim-file\|:none :directory-sync? bool :owner-only? bool :platform-durability :process-crash\|:power-loss}` |

Claim-file algorithm, portable (today Node-only, `fs.cljc:111-161`, V): own
claim created exclusively; others read; live **or `unknown`** pid refuses
(never deleted); `false` is unlinked (race-free by name uniqueness); on
every failed acquisition the own entry is unlinked before the refusal
returns (V `:159-161`); `held` (`:164-168`) stays as the documented
process-global exception.

### 3.3 Atomic replace: outcomes, and every caller owned

V: `atomic-replace!` renames before the directory sync (`fs.cljc:364-370,
382-383`) and throws one `ex-info` for every failure. The portable
replacement answers:

| Outcome | Fact |
|---|---|
| `{:outcome :failed :phase :before-rename}` | target byte-identical; temp unlinked best-effort |
| `{:outcome :failed :phase :after-rename :reason :sync-failed}` | new record **visible**; durability **uncertain** |
| `{:outcome :ok :durability :synced}` / `{:outcome :ok :durability :visible}` | synced, or `:directory-sync? false` declared |

**Where pending durability lives.** `dao.space.store/open` answers a handle
map (`store.cljc:299-310`, V); H6 adds `:pending-durability`, an atom
holding `nil` or `{:name "HEAD" :attempts n}`. `:head-fn` sets it on
`after-rename` and clears it on a later `ok`; a new `:sync-pending!`
entry retries `sync-directory!` for the named file **while the directory
lock is held** (until `close!`, V `store.cljc:7-11`), at most **three**
attempts per publication (P), then leaves the record and answers
`{:outcome :failed :phase :after-rename}` again. The journal's `:failure-model`
is `:power-loss` **only when** the seam declares `:platform-durability
:power-loss` **and** `:directory-sync? true` **and** the creation-time
directory sync of `journal.jing` answered `ok` (today never performed,
which is why every host declares `:process-crash`: V `journal/file.cljc:30-35`,
`dao.stream.journal.md:205-209`); availability or one successful sync
alone never upgrades it. Linux `fsync` declares `:power-loss`; macOS
without `F_FULLFSYNC` and Dart declare `:process-crash` (R).

**Direct call sites (V) and their transitions.**

| Caller | `before-rename` | `after-rename` | `ok` |
|---|---|---|---|
| `store.cljc:305` `publish-head!` | previous manifest stands; answers the map | manifest **published**; `:pending-durability` set; one immediate `sync-pending!` attempt; answers the map with `:durability :uncertain` | published |
| `repl/dht.cljc:341` follow `:write!` | the head is not installed (today's failure path) | installed; sync retry scheduled (below) | installed |
| `repl/state.cljc:293` `save!` | answers the map; no retry | answers the map; no retry (settings policy: warning only) | saved |

**Result-discarding branches (V) now owned.**

| Site | Today | After H6 |
|---|---|---|
| `repl/index.cljc:352-357` `after-publish` inside the publish `try`; a throw makes `:failure {:stage :publish}` and the round unpublished (V docstring `:335-343`) | discards the value | `before-rename` -> `:failure {:stage :head :outcome m}`, round unpublished, as today's throw; `after-rename` -> the round **is** published (HEAD visible): `:manifest-address`, `:published` set, plus `:durability :uncertain` on `ix`; the next round's publish calls `sync-pending!` first; `ok` -> as today |
| `repl/dht.cljc:352-354` composite `:head-fn` (base head-fn, then `announce!`, then `deposit-head!`) | announces whatever happened | announce and deposit only on `ok` **and `after-rename`** (the HEAD is visible, so announcing is truthful); on `before-rename` neither, and the map is returned |
| `repl/dht.cljc:713` `hydrated` (HEAD first, then the indexer) | discards | `before-rename` -> hydration **not installed**; the node stays `::hydrating` and retries on a later step through the follower's `waiting`/`next-delay` schedule (`:726`, V), bounded as below, with a `dht:` line; `after-rename` -> installed, `:pending-durability` set on the local store handle; `ok` -> installed |
| `repl/dht.cljc:840-845` `persist` (today a throw -> `:unpersisted` event with delay, retried by `retry-waiting` while confirmed, `:850-870` V) | throw-based | `before-rename` -> exactly today's `:unpersisted` path; `after-rename` -> `head/install` proceeds (visible) and a `{:kind :sync}` entry joins `waiting` under the same `next-delay` schedule, **bounded at five attempts** (P), after which a `:yin.head/event :sync-uncertain` is emitted once and retrying stops |
| `main.cljc:791,796` `state/save!` ("a file that cannot be written is a warning, never a refusal", V `:782-783`) | discards | `before-rename` -> line `state: not saved: <reason>`; `after-rename` -> line `state: saved to <path> (visible; directory sync failed, durability uncertain)`; no retry |

Tests (`dao.storage-test` and the callers' suites, three lanes): a
scripted seam fails `sync-directory!` once after a successful rename; per
caller, assert the branch above (publication reported with `:durability
:uncertain`; announce happens; hydration installed; follower `:sync` retry
then `ok`; the settings line); `before-rename` cases keep today's semantics
(`store_test.cljc:914,943`, `dht_head_test.cljc:416`, V); `sync-pending!`
bounded at three; follower bounded at five; `short` reads; lock outcomes;
claim rows (live, dead, `unknown`, EPERM, collision, failed-acquisition
cleanup, pid reuse); `append!` `failed` with `:written k` followed by
torn-tail truncation (`file_test.cljc:474-488`, V).

### 3.4 File ownership (H6)

`dao/jing/file.cljc:150-259`; `dao/space/store/fs.cljc` whole;
`dao/space/store.cljc:64,93,105,299-310`; `dao/stream/journal/file.cljc:20,38,58`;
`dao/jing/coordinate.cljc` (`:dao.jing/storage` option); **`yin/repl/index.cljc:335-357`**;
`yin/repl/dht.cljc:341-354,705-717,826-870`; `yin/repl/state.cljc:268-293`;
`yin/repl/main.cljc:199-249,780-800`; `yin/repl/store.cljc` (seam
composition).

## 4. H7: the parser stage seam and the worker lifecycle

V: `frontends.cljc:11-17` requires the parser and `repl-probe`;
`repl_probe.cljc:28-29` requires `cst` and `parser`; `probe` makes a fresh
worker per call (`:163,168`); `parse-source` makes one per call too
(`parser.cljc:61`); the shell calls the binding's `:probe` per line
(`repl.cljc:2088-2115`) and `:parse` through `frontend-binding`
(`:1713`); the catalog is pinned in state as `:frontends` (`:900,958`) and
composed by `main.cljc:639` and `serve.cljc:226`; `(reset)` rebuilds the
session and clears `:frontend-session` (`:1805-1820`), **which is lowering
data, not a worker**; `(lang ...)` switches `:lang` (`:1875-1880`); the shell
closes through `close-index-store!` (`main.cljc:905-920`). jolt disposes
with `close-worker` (`cst.jolt:282`); the JVM has no disposal (`cst.clj:54-71`).

H7 (P). The seam value is `{:grammar-id :make-worker :close-worker!
:parse-unit :first-failure}` per lane (`cst.{clj,cljs,cljd}`, `cst.jolt`;
`close-worker!` a no-op where there is nothing to free). A binding gains
`:open-worker (fn [])` and `:close-worker! (fn [w])`; its `:parse` and
`:probe` take the worker as their **first argument**, so they are
stateless closures and **no rebinding is needed when a worker is
recreated**. The shell state owns `:frontend-worker` `{lang worker}` (P):
opened lazily on the first `:parse` or `:probe` for the selected language;
closed and removed on `(reset)` and `(vm ...)` (`repl.cljc:1805-1820`), on
`(lang ...)` for the language left (`:1875-1880`), and on shell close (a new
`close-frontends!` called from `main.cljc:905-920` before
`close-index-store!`); `open-stage` owns its own worker for the stage's
lifetime and closes it at stage close. Failure: a throwing `:open-worker`
(jolt `cst.jolt:271-272`, V) answers the existing `unavailable-parser`
diagnostic (`repl.cljc:1713-1720`, V); a throwing parse is a
`:yang.cst/error` packet as today. `frontends.cljc` stops requiring
`parser` and `repl-probe` directly; the driver composes the seam.

Tests: probe sequences unchanged through the state-owned worker; one
worker per language per session (a counter in a scripted seam); disposal on
reset, lang switch and close (jolt: handle freed exactly once);
**remote-stage test over `loopback_net`**, in-process, **not a
separate-process deployment test**; the independent proof that recognizers
are unreachable from the consumer is the H0 closure tool run with the
consumer namespace as root.

## 5. H8: conformance and the wasm gate

### 5.1 Actual outcome subsets (V; one function-boundary note)

| Implementation | Surfaces | `create!` | `attach!` | `cursor` | `next` | `append!` | `close!` |
|---|---|---|---|---|---|---|---|
| ring buffer (`ringbuffer_test.cljc:86-119`, manifest exists) | r/w/c | ok, invalid-spec | ok, invalid-descriptor, not-found | ok, invalid-anchor, closed | ok, blocked, end, gap, cursor-mismatch, invalid-cursor | ok, closed | ok |
| memory log (`memory_log_test.cljc:166-224`) | r/w/c | ok, invalid-spec | none | ok, invalid-anchor | ok, blocked, end, cursor-mismatch, invalid-cursor | ok, closed | ok |
| journal, memory and file backends (`journal_test.cljc:271-340`, `journal/file_test.cljc:303-345`) | r/w | none | none | ok, invalid-anchor | ok, blocked, cursor-mismatch, invalid-cursor | ok, invalid-value, transport-error | none |
| datagram writer (`datagram.cljc:270-350`) | w/c | none | ok, invalid-descriptor, not-found | none | none | closed, invalid-value, transport-error, ok | ok |
| udp writer (`udp.cljc:192-231,455-491`) | w | none | ok, invalid-descriptor | none | none | transport-error (oversize `:216`), ok (`:227`), **and the raw seam's own map verbatim** (`:199-201`): with the datagram writer beneath, closed, invalid-value, transport-error, ok; `full`/`refused` excluded because the raw writer excludes them and a seam answering them is a composition defect the exclusion-falsification fixture asserts | none (no closable surface on `UdpHandle`) |
| ws attachment (`ws.cljc`) | w/c | none | ok, transport-error (`:520-531`) | none | none | closed (`:270`), full (`:271,:201`), invalid-value (`:272,:279`), transport-error (`:283,:167-169`), ok | ok (the ok map at `:259` and the `:closed` phase transition at `:334`, V by search; the enclosing function was not re-read) |
| remote reflection (`remote.cljc`) | r/w/c | none | invalid-descriptor, not-found, ok (`:1231-1253`) | closed (`:883`); transport-error translated for gone/not-found, no-surface, channel-gone (`:884-893`, `:547-553`) and the retryable `{:transport-error :dao.stream/retry? true}` while a cursor request is outstanding (`:855-860`); **the source's own map relayed verbatim** from a filed answer (`:541-545`): ok, invalid-anchor, and anything else the source answered | after close: a filed outcome, then **end** (`:908-909`); transport-error translated for gone, no-surface, channel-gone (`:911-920`); a filed source map relayed verbatim: ok, blocked, end, gap, cursor-mismatch, invalid-cursor, refused, transport-error ("the reflection never judges a cursor", `:900-902`); blocked while a request is outstanding (`:919`) | closed (`:938`); transport-error translated for gone and no-surface (`:939-941`); then the link's `send!` outcome: ok, full over max-outstanding (`:445`), transport-error channel-gone (`:552`) | ok, idempotent, local (`:957-977`) |
| remote channel (`remote_channel.cljc`) | **none: a composition** (endpoint and dial over ws and rings, `:176`; `:684` is a dial outcome) | | | | | | |

H8a writes manifests for datagram, udp, ws and remote reflection with
exactly these subsets, each exclusion reasoned, run through
`conformance/run-conformance-suite` over a scripted seam (`udp_raw_test`'s
fake network, V; a fake ws adapter map, `ws.cljc:493-501`; a fake `bind!`
seam, `datagram.cljc:313-330`; for the reflection, a scripted link whose
filed answers carry each relayed source outcome). The manifest must equal
the sources when the slice closes. Harness additions: the **frozen-value
law** observed at the **receiving fixture** for writer-only transports (the
deposit stream the composition wires); **close invalidates the reading
handle**, so the law reads before `close!` or through the owner handle
(`ringbuffer_test.cljc` `close-freezes-attachment`, V). Manifests become
list 3 of the gate.

### 5.2 wasm import-list gate (H8b, deferred, falsifiable now)

Tool `src/dev/host_boundary/wasm_imports.clj` (P): parse the import section;
assert exactly the seven names under module `dao.stream`
(`dao.stream.wasm.md:38-46`, V) and no import from any other module.
Committed byte-vector fixtures with their textual form in a comment: (1)
exactly the seven: pass; (2) plus `dao.stream.random`: fail; (3) plus
`env.getrandom`: fail; (4) six of seven: fail; (5) truncated section: scan
error distinct from "no imports". Commissioned with the first binary.

## 6. Slices: manifests, profile, oracles, risk

**H5a** (P). New: `src/cljc/dao/mint.cljc`, `test/dao/mint_test.cljc`.
Changed: `src/cljc/dao/host/hash.cljc` (+2 entries, three arms),
`test/dao/host/hash_test.cljc`, `test/resources/dao/jing/blake3-vectors.edn`
(keyed and derive-key columns), `src/jolt/dao/host/hash.jolt` (two
`defcfn`s), `native/jolt-crypto/jolt_crypto.c` (two wrappers),
`jolt-toolchain.edn` (`:native` digests, requalification),
`src/dev/jolt_profile_compare.cljc` (cases), `src/cljc/yin/repl/main.cljc`
(root draw per arm, the root allocator, subroots handed to each
composition, `--insecure-root` per OQ-H8), `test/dao/host/gate_test.clj`
(list 2). Oracle: §2.2 tests; §2.3 rows. Risk: jolt requalification.

**H5b** (P). Transports: `ringbuffer.cljc:15-20,27`, `memory_log.cljc:60-75`,
`journal.cljc:296` (identity from the `open!` spec for a fresh header).
Closure compositions (V; `stream.cljc:363` comment, `transactor.cljc:24,167`
and `remote_meet.cljc:169` docstrings excluded): `remote_channel.cljc:176`,
`space/dht.cljc:222`, `remote_pair.cljc:84`, `ucf/authority.cljc:119`,
`ucf/compose.cljc:196,202`, `linker/head.cljc:191`, `linker/interpreter.cljc:99`,
`repl.cljc:595,615,706`, `linker.cljc:2021`, `linker/admission.cljc:49`,
`repl/query.cljc:328`, `repl/driver.cljc:66`, `repl/index.cljc:139`,
`repl/dht.cljc:213`, `repl/serve.cljc:98`; journal callers
`ucf/authority.cljc:106`, `ucf/compose.cljc:248,308,824`. Out of the closure,
on entry only: `ffi/remote_serve.cljc:548`. Out of scope (OQ1): `yin/demo.clj:112`,
`register_bench_cljd.cljd:102`, three `datomworld/demo/*.cljs`,
`continuation_transport.cljc:35`. Tests: the 97 files matched by `rg` (V),
each opened, over a helper `test/dao/stream/test_identity.cljc`; by
directory: `test/dao/stream/` 34, `test/yin/vm/ucf/` 22, `test/yin/repl/` 13,
`test/yin/vm/linker/` 8, `test/dao/space/` 7, `test/dao/jing/` 6,
`test/yin/vm/` 5, `test/dao/` 5, `test/yin/vm/ffi/` 2, `test/datomworld/demo/`
1, `test/yang/clojure/` 1. Oracle: `invalid-spec` without identity on each
transport; descriptor identity equals the spec's; cursor-mismatch across two
specs; journal identity survives reopen (`journal_test.cljc:111`, V).

**H5c** (P). The §2.5 files plus `ucf/compose.cljc` (`:mint` option),
`sign_test.cljc`, `dht_test.cljc` (join refusal), rpc/step/linker uniqueness
rows under a fixed subroot; host-library gate extended with `SecureRandom`,
`getRandomValues`, `Random.secure`, `randomBytes` outside `main.cljc` and
`src/*/dao/storage/`.

**H5d-1** (P, five files): `src/cljc/dao/data/btree/storage.cljc:214-380`
(facades out), new `test/dao/data/btree_async_support.cljc`,
`test/dao/data/btree_async_test.cljc` (re-pointed), `src/cljc/yin/repl.cljc:275-277`
(`:host-banner` from state), `src/cljc/yin/repl/main.cljc` (banner
supplied). **H5d-2**: §2.6.

**H5e** (P): `yang/clojure.cljc`, `yang/php.cljc`, `dao/space/transact.cljc`
and tests (byte-identical lowering across two runs and three lanes;
reserved-namespace refusal; quoted-data preservation; nested forms,
`stream_eval_test` V; `tid_auto_` refusal).

**H6** (P): new `src/cljc/dao/storage.cljc`, `src/{clj,cljs,cljd}/dao/storage/*`,
`test/dao/storage_test.cljc`; changed §3.4 files (including
`coordinate.cljc` and `repl/index.cljc`); gate list 1: four storage files
leave `:deferred-scope`, seams join `:seam-driver`. Oracle §3.3; `file_test`
17, `store_test`, `journal/file_test` 10, `dht_head_test`, `state_test`,
`index`/`dht` caller branches. Risk: six behaviour changes at callers, each
tested.

**H7** (P): `yang/python/antlr/{parser,repl_probe}.cljc`,
`yin/repl/frontends.cljc`, `yang/antlr/cst.{clj,cljs,cljd}`, `cst.jolt`,
**`yin/repl.cljc:1713-1720,1805-1820,1875-1880,2088-2115`** (worker
lifecycle), **`yin/repl/main.cljc:639,905-920`** and `serve.cljc:226` (seam
composition, close); new `test/yang/antlr/remote_stage_test.cljc`.

**H8a** (P): manifests in `datagram_test`, `udp_test`, `ws_test`,
`remote_test`; `conformance.cljc` two laws; gate list 3. **H8b**: §5.2.

Every slice lands alone on three lanes under the quick tier with recorded
counts; slow tier after H5c and after H6.

## 7. Ordering, shared files, and jolt

- H5a first. H5b, H5e, H5d-1, H6, H7 may run in parallel **except on the
  shared files below**; H5c after H5b; H8a after H5b.
- **Shared files, serialized.** `main.cljc`: H5a (root, allocator) ->
  H6 (`:199-249`, `:780-800`, seam composition) -> H5c (keygen) -> H5d-1
  (banner) -> H7 (`:639`, `:905-920`). `repl.cljc`: H5b (`:595,615,706`) ->
  H5c (`:667-918`) -> H5d-1 (`:275`) -> H7 (lifecycle lines). `repl/dht.cljc`
  and `repl/index.cljc`: H5b (ring creation) -> H6 (caller branches).
  `coordinate.cljc`: H6 (`:dao.jing/storage`) -> H5d-2. **H5a and H6 are
  not file-disjoint** (both edit `main.cljc`); the order above resolves it.
- **J4 may proceed now** with `:jolt` arms in the deferred storage files
  (parent §5.3); waiting for H6 avoids a double port and is a coordination
  choice, not a dependency. J5 is independent of H7; J6's masking keys are
  seam-private; J7's `:subroot` option is H5c and soft.
- Chez sketch: root via libcrypto `RAND_bytes` (`defcfn :pointer :int ->
  :int :blocking`; non-1 fails startup); keyed/derive-key via the two
  wrapper exports; storage seam over Chez ports plus `defcfn`s for `fsync`,
  `rename`, `fcntl(F_SETLK)`, `open(O_CREAT|O_EXCL, 0600)`, `getpid`,
  `kill(pid, 0)`, `realpath`, errno captured; `:platform-durability` from
  `fsync` versus `F_FULLFSYNC` on macOS (R).

## 8. (merged into §7)

## 9. Open owner questions

| Id | Question | Recommendation |
|---|---|---|
| OQ-H1 | Accept Table A 76 -> 78 (§2.3)? | Yes |
| OQ-H2 | One driver root with per-composition subroots and purpose-separated keys? | Yes, **conditional on §2.2 as written**: one minter per (kind, purpose) issued only by an allocator, one root allocator in the driver and one sub-allocator per composition, u64 counters with the `:exhausted` refusal, the root never persisted and persisted identities read back; a non-Claude reviewer with crypto background checks the contexts |
| OQ-H3 | Stream identity required in every creation spec? | Yes |
| OQ-H4 | H8b with fixtures now or with the first binary? | With the first binary |
| OQ-H5 | After-rename sync failure: who retries? | **Publisher** (`store` handle, `index` rounds) and **follower** (`repl/dht` `waiting` schedule): bounded retries under the held lock (three and five attempts), then surfaced; **settings file** (`state/save!`): warning only, no retry; the seam never retries |
| OQ-H6 | Keep the JVM blocking driver outside the closure for `facade.clj`? | Yes, with `facade.clj:21,58` re-pointed |
| OQ-H7 | Reserved-namespace hygiene as one frontend policy? | Yes, as frontend policy: refuse user bindings and free references in `yang.*` in generated-syntax positions; preserve quoted user data |
| OQ-H8 | Fixed root flag public or test-only? | **Test-only by default** (`--insecure-root` only under the test runner): a reused or public root repeats identities and authority secrets |

## Recorded residuals (astra's final check of r3; mandatory slice-close obligations, not commissioning blockers)

- **H5a (minter and sub-allocator exhaustion).** Apply the non-wrapping exhaustion rule and boundary
  tests to the `subroot!` allocator's counter, not only to minters. Use promoted operands for the integer
  operations, including on the JVM and Dart beyond signed-long range
  (`src/cljc/dao/host/integer.cljc:14-26,230-263`).
- **H6 (hydration retry without a follower).** The follower `::follow` exists only conditionally
  (`src/cljc/yin/repl/dht.cljc:330-345`) while hydration runs independently (`:1037-1052`). Specify the
  hydration retry state and budget without a follower, its terminal five-attempt behaviour, and reconcile
  follower sync attempts with the store's three-attempt budget.
- **H8a (remote append outcomes).** `src/cljc/dao/stream/remote.cljc:438-445,942-947` returns the
  channel writer's map verbatim. Include its applicable outcomes (for example invalid-value and refused)
  in the outcome inventory, with forwarding and exclusion fixtures; `:552` does not establish an
  append channel-gone translation.

## 10. What would reverse this

- An owner ruling that entropy must enter as stream events.
- A reviewer finding the contexts or framing insufficient: per-secret fresh
  draws replace the `:secrets` keys while ids keep derivation.
- `transactor.cljc:58`'s durable-log constraint binding on tempids: tempids
  derive from an `:ids` key, not position.
- H5d-2's review finding lazy B-tree hydration cannot follow a whole load:
  the loader becomes a demand-driven fetch inside `restored-indexes`.
- A transport whose outcomes cannot be induced through a scripted seam: a
  recorded review obligation in list 3.

## Appendix. Citations opened for r3 (2026-10-22, read only)

`collab/1792664000000-architect-host-effects-recheck.gpt-6-astra.findings.md`;
`yin/repl/index.cljc:335-365`; `yin/repl/dht.cljc:345-360,700-720,826-870,726`;
`yin/repl/main.cljc:780-800,900-921`; `yin/repl.cljc:1705-1720,1805-1825,1868-1884`
(and `:900,958,2092` by search); `dao/stream/remote.cljc:541-556,775-790,830-862,870-980,1255-1267`;
`dao/stream/udp.cljc:192-231`; `dao/stream/datagram.cljc` (event keys by
search); `dao/jing/content/step.cljc:1-60`; `src/clj/dao/jing/dht/facade.clj:1-24`;
`test/resources/dao/jing/blake3-vectors.edn:1-30`; `test/dao/host/hash_test.cljc:83-92`;
`node_modules/@noble/hashes/blake3.d.ts` (option docs); `dao/jing/coordinate.cljc:93-99`
(from r2 reading); plus the r2 appendix.

## Decision log

(Owner decisions on §9 are appended here as they are made.)
- **OQ-H1 (2026-10-22, owner: "accept as recommended")**: Table A grows 76 -> 78 with
  `blake3-keyed [key bytes]` and `blake3-derive-key [context material]` (§2.3).

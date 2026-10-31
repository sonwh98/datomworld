# dao.stream.host-boundary: dao.stream wherever the boundary can be an IO action

The governing test for the portable ABI is the owner's: **dao.stream is used
wherever a host boundary can be an IO action; a pure, total, synchronous
function on the same host may be a direct call.** Hosts may also install
explicit local functions, including direct I/O, outside the portable ABI
(§2.2). This document's commissionable scope is that
rule (§2) and the pure-primitive slices H0-H4 (§8): freeze and allowlist; the
`dao.host.*` integer, float, number, bytes and text profile; migration of
every consumer; hash and signature entries; the portable Jing CBOR writer.
Entropy and scheduling, the durable-storage seam, the parser stage and the
stream conformance suite are **deferred** to a separate design (§9) and are
not commissionable here.

Status: design only; H0 implemented in a worktree, unmerged. **Revision
r8**, 2026-10-21, by the Architect (claude-fable-5-1). Chronology: r1-r3
NOT SIGNED OFF (gpt-6-astra); r4 SIGNED OFF WITH CHANGES for §2 plus H0-H4;
r5 applied M1-M4; r6 applied cmd's D1-D3; r7 reconciled the design with
H0's measurements (F1-F4); the r7 review (gpt-6-astra,
`collab/host-boundary/1792523793000-architect-host-boundary-review-r7.gpt-6-astra.findings.md`)
returned "reconciliation CONFIRMED WITH DEFECTS, architecture SIGNED OFF
WITH CHANGES" with R7-1 to R7-5; **r8 applies R7-1 to R7-5** (rationale
`collab/host-boundary/1792523949000-architect-host-boundary-r8.claude-fable-5-1.findings.md`)
and adds OQ11; the r8 review (gpt-6-astra,
`collab/host-boundary/1792524256000-architect-host-boundary-review-r8.gpt-6-astra.findings.md`)
returned NOT CONFIRMED on one dependency defect; **r9** (2026-10-21,
rationale `collab/host-boundary/1792524426000-architect-host-boundary-r9.claude-fable-5-1.findings.md`)
extracts the float-text kernel into the profile instead of calling a
private VM function across the layering, exempts `yin/vm.cljc` from H2's
removal pending OQ11, and states the category-to-class mapping the gate
enforces; **a non-Claude reviewer must confirm r9 before H1 is
dispatched.** **Review status: SIGNED OFF WITH CHANGES
by a non-Claude architect (gpt-6-astra, 2026-10-20,
`collab/host-boundary/1792508286000-architect-host-boundary-review-r4.gpt-6-astra.findings.md`)
for the narrowed scope only, §2 plus H0-H4; H5-H8 (§9) are not signed off
and remain deferred.** The four mechanical corrections M1-M4 that sign-off
required were applied in r5 (rationale
`collab/host-boundary/1792508512000-architect-host-boundary-r5.claude-fable-5-1.findings.md`)
and should be confirmed by a non-Claude reviewer before H0 is dispatched.
r7 (2026-10-21): reconciled with what slice H0 measured (F1-F4: the 16
unlisted host arms categorised, the closure-based effect counts, the §3.1
rows, the print and M-entry facts); rationale
`collab/host-boundary/1792522578000-architect-host-boundary-r7.claude-fable-5-1.findings.md`;
**a non-Claude reviewer must confirm this r7 before H1 is dispatched.**
r6: D1-D3 applied (a non-Claude reviewer's confirmation check,
`collab/host-boundary/1792517656000-reviewer-host-boundary-r5.qwen3.8-max.findings.md`,
found M1-M4 applied and three low defects; rationale
`collab/host-boundary/1792518203000-architect-host-boundary-r6.claude-fable-5-1.findings.md`).
Lineage: r1 rationale
`collab/host-boundary/1792495909000-architect-host-boundary.claude-fable-5-1.findings.md`;
r1 review NOT SIGNED OFF
(`collab/host-boundary/1792498045000-architect-host-boundary-review.gpt-6-astra.findings.md`,
A1-A8); r2 rationale `collab/1792502807000-...-r2.claude-fable-5-1.findings.md`;
r2 review NOT SIGNED OFF
(`collab/host-boundary/1792503557000-architect-host-boundary-review-r2.gpt-6-astra.findings.md`,
R1-R6); r3 rationale and disposition
`collab/host-boundary/1792506992000-architect-host-boundary-r3.claude-fable-5-1.findings.md`;
r3 scoped review NOT SIGNED OFF
(`collab/host-boundary/1792507576000-architect-host-boundary-review-r3.gpt-6-astra.findings.md`,
N1-N6); **r4** answers N1-N6 within the unchanged scope (rationale
`collab/host-boundary/1792507856000-architect-host-boundary-r4.claude-fable-5-1.findings.md`).
Owner decision for r3 (verbatim): "yes, proceed with the split". No code was
changed; every claim about the tree is labelled. **Because this document was
written by a Claude model, a non-Claude architect must sign off this final
text (§2 plus H0-H4) before anything is commissioned from it** (§14).

Owner direction, verbatim. Original: "maybe this is an opportunity to stop
and refactor so that the dao.stream is the boundary that crosses over host
specific dependency. once that is done, the only thing needs porting is
dao.stream implementations". Refined: "maybe my dao.stream ABI framing has to
be refined.  when a host-boundary is cross should it be used. when its on the
same host, then direct function call is fine. In other words, when the host
boundary can be an IO monad then dao.stream is used. If it cannot be a monad
than dao.stream doesnt have to be used". Asked which formulation governs:
"yes, test 2 governs".

Authorities:
- [datom.world.md](./datom.world.md) `:43-48`, `:70`.
- [datom.world.abi.md](./datom.world.abi.md) §3 custody; §4 machine-internal
  work executes inside the instance; §5 the floor and internal linkage; §6
  effects out, events in; §7 adapter discipline (`:143-148`). §12 is an
  owner proposal to amend it; nothing here edits it.
- [dao.stream.md](./dao.stream.md): the contract; cursors are non-destructive
  (`src/cljc/dao/stream.cljc:159-173`, V via the r2 review).
- [dao.stream.wasm.md](./dao.stream.wasm.md) §1 `:29-52`: seven imports.
- [dao.stream.codec.md](./dao.stream.codec.md): the codec track; untouched
  here (§8.3).
- [yin.vm.engine.md](./yin.vm.engine.md) §E.5; [dao.jing.md](./dao.jing.md);
  [dao.jing.cbor.md](./dao.jing.cbor.md).

Labels: V = verified by reading this tree on 2026-10-20 (line cites); R =
read, computed by script, or inferred, not run under a compiler; P =
proposed. Nothing was executed on a lane.

Standalone: a future host lane is an example consumer, never a dependency.

---

## 1. Problem

V: the portable core (`src/cljc`, 183 files) reaches hosts two ways and the
doctrine sanctions only one. The sanctioned way is the transports' pattern:
a portable core plus a per-host seam value the composition hands in
(`yin.repl.host/websocket`, `host.cljc:20-37`; `datagram.jvm/bind!`,
`jvm.clj:22-34`). The other way is host contact inside portable files: reader
arms reaching a host library, and resolved calls to host-backed core
functions that compile everywhere and still touch the world.

The in-scope files (V, `rg -c '#\?'`; what the arms reach):

+-----------------------------------+-------+---------------------------------------------------------------------------------------------------------+
| File                              | `#?`  | Host contact (V)                                                                                        |
+===================================+=======+=========================================================================================================+
| `dao/jing/cbor.cljc` (1562 lines) | 52    | Big-integer helpers (`:106-254`); `Float64`/`Rational`/`Decimal` carriers (`:342-460`); JVM             |
|                                   |       | `BigDecimal`/`Ratio` construction and access (`:483-632`); float bits (`:525-541`); writer selection    |
|                                   |       | (`:58-60`: Boring on JVM and JS, the Dart `cbor` package).                                              |
+-----------------------------------+-------+---------------------------------------------------------------------------------------------------------+
| `yin/vm/integer/host.cljc` (299)  | 30    | 25 public functions (`:41-298`): carrier recognition, promotion, 13 big operations.                     |
+-----------------------------------+-------+---------------------------------------------------------------------------------------------------------+
| `dao/jing.cljc` (629)             | 12    | BLAKE3 and SHA-256 libraries (`:32-40, :59-135`); an opaque sentinel (`:43-51`).                        |
+-----------------------------------+-------+---------------------------------------------------------------------------------------------------------+
| `yin/vm/linker/sign.cljc` (274)   | 16    | Ed25519 libraries; `jvm-pair` replays a given seed through a `SecureRandom` proxy (`:75-87`, V:         |
|                                   |       | deterministic, not fresh entropy); `generate` draws fresh entropy (`:114-124`, deferred, §9).           |
+-----------------------------------+-------+---------------------------------------------------------------------------------------------------------+
| `dao/jing/cbor/boring.cljc`       | 14    | The two wire writers (`:cljd nil`-gated Boring wrapper; 107-line Dart twin).                            |
| (132), `src/cljd/dao/jing/cbor/cl |       |                                                                                                         |
| jd.cljd`                          |       |                                                                                                         |
+-----------------------------------+-------+---------------------------------------------------------------------------------------------------------+
| `yin/vm/debruijn.cljc` (16)       | 16    | Bytes predicate (`:167`), char access (`:241`), NFC (`:310-321`, Unicode 16.0 owner-settled), float     |
|                                   |       | bits (`:855-885`), carrier tests (`:1255-1257`).                                                        |
+-----------------------------------+-------+---------------------------------------------------------------------------------------------------------+
| `yin/vm/debruijn_code.cljc` (13)  | 13    | Float64 little-endian hex (`:232-242`); JVM big-integer predicate (`:315-317`).                         |
+-----------------------------------+-------+---------------------------------------------------------------------------------------------------------+
| `yin/vm/data.cljc` (7),           | 7+9   | Code units and `units->string` (`:119-173`); `integral` (`:77-92`); `Closure`/`Continuation` equality,  |
| `yin/vm/values.cljc` (9)          |       | hash and print arms (`:39-119`).                                                                        |
+-----------------------------------+-------+---------------------------------------------------------------------------------------------------------+
| `dao/stream/chunks.cljc` (5),     | 5+4+4 | `Uint8List` length, alloc, slice, copy (`chunks.cljc:8-32`); base64 libraries; host-bytes predicate     |
| `base64.cljc`, `codec.cljc`       |       | (`codec.cljc:45`, codec-track file, §8.3).                                                              |
+-----------------------------------+-------+---------------------------------------------------------------------------------------------------------+
| `dao/jing/dht.cljc` (10),         | 10+14 | Bytes helpers (`dht.cljc:100-121`); bytes length (`linker.cljc:1232`); `host-bytes`/`utf8`              |
| `yin/vm/linker.cljc`, `datagram`  |       | (`sign.cljc:55-66`); byte length (`datagram.cljc:63-66`).                                               |
+-----------------------------------+-------+---------------------------------------------------------------------------------------------------------+
| `dao/data/arrays.cljc` (21)       | 21    | Host arrays, `^long` hints, `parallelSort` (`:145`).                                                    |
+-----------------------------------+-------+---------------------------------------------------------------------------------------------------------+

Out of scope here and listed in §9: `dao/jing/file.cljc`, `dao/space/store/
fs.cljc`, `dao/space/store.cljc`, `dao/stream/journal/file.cljc`,
`dao/data/btree/storage.cljc` (storage); `dao/jing/content/async.cljc`
(outside the closure, H0; recorded for the deferred design),
`dao/jing/coordinate.cljc:80` and `src/clj/dao/jing/content/driver.clj`
(scheduling, blocking driver); every entropy site; `yang/python/antlr/
parser.cljc` and the `yang.antlr.cst` hosts (parser stage).

V, the duplication: `yin.vm.integer.host` says it "does not reach into
`dao.jing.cbor`'s private big-integer helpers" (`host.cljc:23-25`); `cbor.cljc`
keeps its own (`:112-216`). Two shims, one contract.

## 2. The governing rule

### 2.1 The boundary, defined first (review R1)

**The machine** is the running instance and the state it owns: its values,
and the mutable runtime state its own code created and no other party can
observe or change: atoms and volatiles, host arrays and byte arrays it
allocated, a B-tree's weak or soft references, the collector that clears
them, thread-local locks between threads the driver created. **The world** is
everything else: files, sockets, other processes, the operating system's
clock, entropy sources, environment, the terminal, and any other instance.

A host contact is **external** when it observes or changes the world. It is
**internal** when it is a function of the machine's values and owned runtime
state only.

### 2.2 Statement

**A portable external contact uses `dao.stream`: it can be an IO action. An internal
contact that is a pure, total, synchronous function of its arguments, or a
declared mutation of an owned argument (§2.4), may be a direct call and is
then an entry of the primitive profile (§4.2).**

This is the owner's test 2 for the portable ABI. Same-host-ness is not the
criterion: a same-host file read is external and uses a stream for portable
behavior; a hash from a library linked into the instance is internal and is
a direct call.

Owner amendment (2026-10-23, I4): `dao.stream` is the portable I/O ABI, not
the only I/O path. Hosts may install arbitrary local functions, including
direct I/O; their availability and migration semantics must be explicit.
Portable behavior cannot assume them. Direct-I/O host modules are explicitly
local and non-migratable; the host-module API correction is tracked in
[known-gaps.md](../agents/known-gaps.md). This does not add direct-I/O entries
to the portable primitive profile.

### 2.3 "Can be an IO action", operationally

A contact can be an IO action when any of these holds **with respect to the
world** (§2.1):

+----+--------------------------------+------------------------------------------------------------------------------------------------------------+
| #  | Property                       | Meaning and the doctrine that names it                                                                     |
+====+================================+============================================================================================================+
| 1  | Observation of the world       | The result depends on world state: a file, a socket, another process, the environment, a resource's        |
|    |                                | current condition. `abi.md` §5: "world facts that shipped code cannot compute".                            |
+----+--------------------------------+------------------------------------------------------------------------------------------------------------+
| 2  | Effect on the world            | Calling it changes world state: bytes on disk, a packet sent, a lock taken, text printed.                  |
|    |                                | `datom.world.md` §Streams: portable side effects appear as stream emissions.                               |
+----+--------------------------------+------------------------------------------------------------------------------------------------------------+
| 3  | World nondeterminism           | Two calls with equal arguments and equal owned state may answer differently because of the world: entropy, |
|    |                                | a clock, an OS-assigned pid, a process-wide counter shared with code the machine does not own.             |
+----+--------------------------------+------------------------------------------------------------------------------------------------------------+
| 4  | Asynchrony                     | The answer is not available at return, or completion invokes something later: a timer, a thread, a         |
|    |                                | promise. `dao.stream.md`: "No operation waits"; `abi.md` §6: a callback is an event on a stream.           |
+----+--------------------------------+------------------------------------------------------------------------------------------------------------+
| 5  | World-dependent failure        | Whether it completes depends on the world (disk full, peer gone), not on the input. `dao.stream.md` Result |
|    |                                | Convention: such outcomes are data.                                                                        |
+----+--------------------------------+------------------------------------------------------------------------------------------------------------+
| 6  | Another process or host        | The work runs in a different process, machine or trust domain. `abi.md` §3.                                |
+----+--------------------------------+------------------------------------------------------------------------------------------------------------+

A contact cannot be an IO action when none holds. Then it is a function of
the machine's values and owned state, computed synchronously in the same
instance, whose only failures are functions of the input, answered as data
or a refusal. Linking a library to compute it is internal linkage (`abi.md`
§5 `:80-81`); executing it is machine-internal work (`abi.md` §4 `:60-62`).
The basis of the primitive profile is therefore **internality plus purity,
totality and synchrony over the declared domain**, with mutation admitted
only as §2.4 declares it.

A read of owned runtime state that may differ between calls (a weak
reference after a collection, an atom another owned thread advanced) fails
none of the six properties: it is a fact about the machine, not the world.
It is a **runtime prerequisite** (table C), neither IO nor a profile entry.
r2 classified it as both; that contradiction is withdrawn.

### 2.4 Ownership and mutation laws for the profile

The profile's purity claim is exact, not blanket:

- **Pure entries** (marked P in table A) are deterministic, total over their
  declared domain, synchronous, and allocate only fresh values; equal
  arguments give equal results on every host.
- **Mutating entries** (marked M: `bytes/set!`, `bytes/copy!`, `arrays/aset`,
  `arrays/acopy`, `arrays/asort`) mutate **exactly the argument named as the
  target** and nothing else, and never retain it. Return values are per
  entry, not uniform: `aset` returns the assigned value (Clojure's `aset`
  semantics, V `arrays.cljc:50-57`, unchanged); `set!`, `copy!` and `acopy`
  return nil; `asort` returns the sorted array. Overlapping `copy!` and
  `acopy` behave as memmove (V: `acopy` already handles same-array overlap,
  `:97-105`). **Prevalidation**: every index and length is checked before
  any write, so a refused call leaves the target byte-identical. This is a
  **behaviour change** on JS and Dart, where today's `acopy` loop writes
  element by element and can write before meeting an invalid index
  (`arrays.cljc:97-105`, V; the JVM's `System/arraycopy` checks first); it
  is assigned to H2 with a no-partial-write test (table D).
- **Aliasing**: `bytes/slice`, `arrays/asub`, `arrays/aclone`, `from-vector`
  always return a fresh value; no entry returns a view onto an argument.
  Nothing else is promised about copying: a value appended to a stream is
  **not** copied by the stream in general. The ring buffer stores the value
  object itself and reads return it (`ringbuffer.cljc:124-131`, V), and the
  ABI permits in-instance streams with no crossing and no serialization
  (`dao.stream.wasm.md:22-27`). Aliasing across a stream is therefore
  possible, and the rule below is what makes it safe.
- **Exclusive mutation right, ownership transfer, and freezing
  publication.** A mutable profile value (bytes, a host array) has exactly
  one party with the right to mutate it. Two distinct events move or end
  that right:
  - **Ownership transfer** (the right moves, the value stays mutable): when
    a profile entry marked "fresh" (`alloc`, `slice`, `from-vector`,
    `magnitude-bytes`, `anew`, `aclone`, `asub`, `aconcat`, `ainsert-at`,
    `aremove-at`, `from-coll`, `utf8-encode`) returns a value, the **caller**
    holds the exclusive mutation right from that return, and may `set!`,
    `copy!`, `aset`, `acopy` or `asort` it. Returning fresh storage is not
    publication.
  - **Freezing publication** (the right ends for everyone): appending the
    value to a stream (in-memory or not); placing it inside a value that is
    appended; handing it to a seam or a content handle. On publication the
    value is **frozen**: no party, the publisher included, may mutate it
    afterwards, and a reader may not mutate what it reads. Freezing is
    transitive: a collection is frozen with every mutable value nested in
    it. A party that needs a mutable copy of a frozen value takes `slice`,
    `aclone` or `from-vector`, and then holds the right to that copy.
- **Obligations enforceable within H0-H4**: (i) every P entry leaves every
  argument byte-identical, asserted by a property test in H1 over the bytes,
  text, float and integer namespaces; (ii) every M entry mutates only its
  named target and only after prevalidation, asserted in H1 (bytes) and H2
  (arrays); (iii) the Jing writer (H4) never mutates its input and returns
  fresh bytes, asserted by re-encoding the same input twice and comparing
  the input's bytes before and after; (iv) the callers of M entries inside
  the adopted closure are enumerated and audited in H2 for the freeze rule.
  **What the H0 gate can and cannot see (V, H0 report).** The gate resolves
  a call as an M entry only when it goes through `dao.data.arrays` or
  `dao.host.bytes` by alias; today that is `dao.data.btree` alone (33 calls,
  owned node arrays, never appended). `dao.stream.chunks` (`absorb`,
  `chunks.cljc:27-34`), `dao.jing.cbor` (`magnitude-bytes` and the
  byte-string assembly at `:254,1313-1317`) and `yin.vm.linker.sign` (`:82`)
  mutate host arrays **directly inside reader arms** (`System/arraycopy`,
  `.setRange`, `.set`, `aset`) and call no M entry until H2 migrates them,
  so the gate cannot count them now. Obligations (i)-(iii) are tests, not
  gate checks; the freeze rule itself (no mutation after publication) is
  not mechanically checkable by the gate at all and remains a review
  obligation. **What H2's audit must do instead**: (a) list every direct
  host-array mutation inside an arm across the closure (the gate's arm
  scan plus a search for `arraycopy`, `setRange`, `.set`, `aset`, `aclone`
  on byte arrays); (b) replace each with an M entry or a fresh-allocating P
  entry, so that after H2 the only mutation sites in the closure are M-entry
  calls; (c) add the migrated callers to list 2 as `:m-entry` callers with
  their ceilings and a one-line reason each; (d) record any site that keeps
  a direct mutation (expected: none) as a named exception. After H2 a new
  M-entry caller fails the gate's list 2 until reviewed; before H2 the gate
  catches only new `dao.data.arrays` callers.
  What is **not** enforceable here: a reader mutating a frozen value it
  obtained from a stream; that is a composition obligation stated by this
  rule and left to review until the deferred conformance suite (§9.4).

### 2.5 Borderline cases, decided

+-----------------------------------+---------------------------------------------------------------------------------------------------------+------------+
| Case                              | Reasoning under §2.3                                                                                    | Verdict    |
+===================================+=========================================================================================================+============+
| UTF-8 decode, CBOR decode, base64 | Fallible, but the failure is a function of the input (property 5 does not hold); deterministic,         | Direct     |
| decode, NFC normalization         | synchronous. Malformed input is a refusal value. NFC depends on a Unicode version, which the profile    | call       |
|                                   | pins (16.0, V `debruijn.cljc:314`).                                                                     |            |
+-----------------------------------+---------------------------------------------------------------------------------------------------------+------------+
| Hashing, signing, seed replay in  | Deterministic functions of bytes (and of the seed). The library is linkage. They execute in the same    | Direct     |
| a library (BLAKE3, SHA-256,       | instance; for wasm, compiled in, never imported (§7). `sign.cljc:75-87`'s `SecureRandom` proxy replays  | call, in-  |
| Ed25519; `sign.cljc:75-87`)       | the given seed bytes and is deterministic (V, R3 correction).                                           | instance   |
+-----------------------------------+---------------------------------------------------------------------------------------------------------+------------+
| Fresh entropy, identities, key    | Property 3. Deferred design (§9); the profile contains no generator.                                    | Stream;    |
| generation (`sign.cljc:114-124`)  |                                                                                                         | deferred   |
+-----------------------------------+---------------------------------------------------------------------------------------------------------+------------+
| Time; scheduling; the JVM         | Properties 1, 3, 4. `coordinate.cljc:80` selects `driver.clj:169-206`, which reads the clock and sleeps | Stream /   |
| blocking content driver           | (V): JVM-lane IO inside a portable composition. Deferred (§9).                                          | driver;    |
|                                   |                                                                                                         | deferred   |
+-----------------------------------+---------------------------------------------------------------------------------------------------------+------------+
| The Python parser                 | In-process, synchronous, deterministic over source text and the pinned grammar: internal, direct call.  | Either;    |
|                                   | As another process: property 6, a stream. The stage shape (`parser.cljc:65-75`) serves both. Deferred.  | deferred   |
+-----------------------------------+---------------------------------------------------------------------------------------------------------+------------+
| Same-host file read, lock, fsync, | Properties 1, 2, 5. External though same-host. Deferred (§9).                                           | Stream;    |
| rename, directory listing         |                                                                                                         | deferred   |
+-----------------------------------+---------------------------------------------------------------------------------------------------------+------------+
| Weak and soft references,         | Owned runtime state (§2.1). Not IO, not a profile entry: table C. The B-tree's default reference mode   | Runtime    |
| atomics, `locking`, atoms         | is `:soft` on the JVM and `:strong` on JS and Dart (V `btree.cljc:39-44`); correctness is independent   | prereq.    |
|                                   | of the mode, only retention differs.                                                                    |            |
+-----------------------------------+---------------------------------------------------------------------------------------------------------+------------+
| Host `=`, `hash`, `compare`,      | Internal and pure per host, not identical across hosts for mixed types and floats. A portability hazard | Dialect    |
| `sort`, `str` of a double, regex  | only where persisted bytes depend on it; those sites sort by canonical bytes (`cbor.cljc:1070,1177`,    | semantics  |
|                                   | `ucf/rows.cljc:139`, `debruijn.cljc:921,929`). Regex sites (`custody.cljc:53`, `datagram.cljc:86-100`,  |            |
|                                   | `jing.cljc:107-111,216-221`, `fs.cljc:105`) match ASCII grammars identical on every host.               |            |
+-----------------------------------+---------------------------------------------------------------------------------------------------------+------------+
| `gensym` in a compiler            | A process-wide counter shared with code the machine does not own: property 3 in a weak form. Deferred   | Deferred   |
|                                   | (§9), with the hygiene requirement the r2 review added.                                                 |            |
+-----------------------------------+---------------------------------------------------------------------------------------------------------+------------+

### 2.6 Where the owner's original sentence is and is not met

Met for every external contact: after this design and the deferred one, a
host's world contact is the stream seams and its `main`. Not met literally:
a port also implements the primitive profile and meets table C, because the
dialect's own carriers cannot be supplied by a stream and must not be. The
profile is exact, enumerated and oracle-checked, so that part is a list, not
a design.

## 3. Classification over the adopted closure

### 3.1 The transitive closure, per lane (R, computed)

Method: a require-form traversal from `yin.repl.main`, choosing each lane's
reader arms (`:cljd` also reads `:clj`) and resolving each namespace to its
file under `src/<lane>` first (shadow resolution: `yin.repl.host` is
`host.cljc` on the JVM and the `host.cljs`/`host.cljd` shadows elsewhere),
then `src/cljc`. It is not a compiler-resolved call graph: it can
over-include a namespace required but unused on a path, cannot see dynamic
loading, and **can under-include** when its reader-arm parsing fails; the
r3 script did exactly that (it dropped requires inside `#?@` arms and
reported 141/140/139). The r3 claim that it "cannot under-include" is
withdrawn. The reconciled totals, counted by **file** and confirmed by the
r3 reviewer's independent traversal (R):

+-------+-------+----------+---------------------------------------------------------------------------------------------------------------------+
| Lane  | Total | Shared   | Lane-specific files (V, the files exist)                                                                            |
+=======+=======+==========+=====================================================================================================================+
| clj   | 142   | 133      | `clj/dao/jing/content/driver.clj` (blocking driver: clock, sleep, `locking`), `clj/dao/stream/datagram/jvm.clj`,    |
|       |       |          | `clj/dao/stream/waitset/driver.clj`, `clj/dao/stream/ws/jvm.clj` (clock, `CompletableFuture`), `clj/yang/antlr/     |
|       |       |          | cst.clj`, `clj/yin/repl/host/jvm.clj`, `cljc/yin/repl/host.cljc` (the JVM selector),                                |
|       |       |          | `cljc/dao/jing/cbor/boring.cljc`, `cljc/dao/stream/cbor/boring.cljc`. Nine.                                         |
+-------+-------+----------+---------------------------------------------------------------------------------------------------------------------+
| cljs  | 140   | 133      | `cljs/dao/stream/datagram/node.cljs`, `cljs/dao/stream/waitset/driver.cljs` (`setTimeout`), `cljs/dao/stream/ws/    |
|       |       |          | node.cljs` (`js/Date`), `cljs/yang/antlr/cst.cljs`, `cljs/yin/repl/host.cljs` (shadow), `cljc/dao/jing/cbor/        |
|       |       |          | boring.cljc`, `cljc/dao/stream/cbor/boring.cljc`. Seven.                                                            |
+-------+-------+----------+---------------------------------------------------------------------------------------------------------------------+
| cljd  | 141   | 133      | `cljd/dao/stream/cbor/cljd.cljd`, `cljd/dao/stream/transit/cljd.cljd`, `cljd/dao/stream/datagram/dart.cljd`,        |
|       |       |          | `cljd/dao/stream/waitset/driver.cljd` (`Timer`), `cljd/dao/stream/ws/dart.cljd` (`DateTime.now`), `cljd/yang/antlr/ |
|       |       |          | cst.cljd`, `cljd/yin/repl/host.cljd` (shadow), `cljd/dao/jing/cbor/cljd.cljd`. Eight.                               |
+-------+-------+----------+---------------------------------------------------------------------------------------------------------------------+

The 133 shared files are `.cljc`. H0 commits the reproducible evidence
(§8): the traversal is re-implemented over `clojure.tools.namespace`'s
`ns` parsing (which handles reader conditionals per platform) rather than a
regex, committed as `src/dev/host_boundary/closure.clj` (H0 placed it
under `src/dev/` with the repo's other dev tools), and its output as
`docs/design/host-boundary/closure-{clj,cljs,cljd}.edn`; H0's acceptance is
that the committed output lists exactly the 142/140/141 files above, and
that the two traversals (the committed tool and the r3 reviewer's method)
agree, recorded in the slice report. Until that lands, the totals here are
R. (r11 note: post-H2, with `yin.vm.integer.host` deleted and the five profile
files added to the closure, the totals are 146/144/145, 137 shared, 159 union).

Classification of every file in the closure that has any host contact (the
remaining files have none beyond §3.3 syntax; the committed H0 output marks
each file with its class, which is what makes "remaining" checkable):

+-----------------------------------------------------+----------------------------------------+----------------------------------------------------------+
| Namespaces                                          | Contact (V)                            | Class and slice                                          |
+=====================================================+========================================+==========================================================+
| `yin.vm.integer.host`, `dao.jing.cbor` (helpers,    | Big integers, floats, Decimal/Ratio,   | Internal pure: profile. H1-H2 (writer: H4).              |
| carriers, Decimal/Ratio, float bits, writer),       | NFC, bytes, code units, host arrays    |                                                          |
| `dao.jing.cbor.boring`, `dao.jing.cbor.cljd`,       |                                        |                                                          |
| `yin.vm.debruijn`, `yin.vm.debruijn-code`,          |                                        |                                                          |
| `yin.vm.data`, `yin.vm.values`,                     |                                        |                                                          |
| `dao.stream.chunks`, `dao.stream.base64`,           |                                        |                                                          |
| `dao.jing.dht` (bytes), `yin.vm.linker` (`:1232`),  |                                        |                                                          |
| `dao.stream.datagram` (`:63-66`), `dao.data.arrays` |                                        |                                                          |
+-----------------------------------------------------+----------------------------------------+----------------------------------------------------------+
| `dao.jing` (hashes), `yin.vm.linker.sign`           | Hash and signature libraries; seed     | Internal pure, in-instance: profile. H3. `generate` is   |
| (`public-of`, `sign-bytes`, verify, `jvm-pair`)     | replay                                 | entropy: deferred.                                       |
+-----------------------------------------------------+----------------------------------------+----------------------------------------------------------+
| `yin.vm`, `yin.vm.engine`, `yin.vm.values`,         | Require `yin.vm.integer.host` for      | Consumers: H2 (require change only).                     |
| `yin.vm.data`, `yin.vm.integer`                     | `big-carrier?`/`exact-integer?`        |                                                          |
+-----------------------------------------------------+----------------------------------------+----------------------------------------------------------+
| `dao.stream.ws`, `.datagram`, the seams             | Sockets; wake sources (`setTimeout`,   | External: streams with seams (existing). No slice. The   |
| (`ws.jvm/node/dart`, `datagram.jvm/node/dart`), the | `Timer`, a blocking queue); the ws     | ws seams also read a clock (`ws/jvm.clj:422`,            |
| three `dao.stream.waitset.driver` files,            | seams' clock reads                     | `ws/node.cljs:249`, `ws/dart.cljd:176`, V): seam-private |
| `yin.repl.host*`; `dao.stream.ringbuffer:168` (a    |                                        | IO, allowed by §4.1. (`dao.stream.udp` and the browser   |
| host atom type test inside the in-memory            |                                        | adapter are not in the closure, H0.)                     |
| implementation, V)                                  |                                        |                                                          |
+-----------------------------------------------------+----------------------------------------+----------------------------------------------------------+
| `dao.jing.file`, `dao.space.store.fs`,              | Files, locks, rename, dir sync, pid,   | External: storage seam. **Deferred** (§9.2).             |
| `dao.space.store`, `dao.stream.journal.file`,       | platform                               |                                                          |
| `dao.data.btree.storage` (promise facade)           |                                        |                                                          |
+-----------------------------------------------------+----------------------------------------+----------------------------------------------------------+
| 21 `random-uuid` sites in the closure (§3.2),       | Fresh entropy and identities           | External (property 3). **Deferred** (§9.1).              |
| `rpc:114` and `jing.dht:373` `rand`,                |                                        |                                                          |
| `space.dht/secure-random-bytes`, `sign/generate`    |                                        |                                                          |
+-----------------------------------------------------+----------------------------------------+----------------------------------------------------------+
| `dao.jing.coordinate:80` selecting                  | JVM blocking driver with clock and     | External (property 4, 1). **Deferred** (§9.1).           |
| `dao.jing.content.driver` (clj lane, clock, sleep,  | sleep; host promises (not timers: a    | (`dao.jing.content.async` is not in the closure, H0; its |
| `locking`); `dao.data.btree.storage` (promise       | `CompletableFuture`/`Promise`/         | timer is out of scope until the deferred design widens   |
| facade `:218-227`)                                  | `Completer`)                           | it.)                                                     |
+-----------------------------------------------------+----------------------------------------+----------------------------------------------------------+
| `yin.repl.main`, `yin.repl` (`:275-277` banner),    | Clock, stdin/stdout, exit, env         | Driver. `repl.cljc:275` and `fs.cljc:271` ambient reads  |
| `yin.repl.state` (`dart:io`, `js/require`)          |                                        | deferred (§9.1).                                         |
+-----------------------------------------------------+----------------------------------------+----------------------------------------------------------+
| `yang.python.antlr.*`, `yang.antlr.cst` (x3),       | Generated recognizers                  | Stage: **deferred** (§9.3). (`antlr/repl.cljc:71` is a   |
| `yang.frontend`, `yang.stage`, `yin.repl.frontends` |                                        | `println` inside a quoted yin program, the VM's own      |
|                                                     |                                        | primitive, not a host call, V; r3's "one `println`" is   |
|                                                     |                                        | withdrawn.)                                              |
+-----------------------------------------------------+----------------------------------------+----------------------------------------------------------+
| `yang.clojure:349,359`, `yang.php:575`,             | `gensym` (process counter)             | **Deferred** (§9.1), hygiene required.                   |
| `dao.space.transact:35`                             |                                        |                                                          |
+-----------------------------------------------------+----------------------------------------+----------------------------------------------------------+
| `dao.data.btree` (atomic flag, weak/soft refs),     | Owned runtime state                    | Table C; no slice. (`dao.space.schema` is not in the     |
| `locking` in `jing.file`, `journal`, `transactor`,  |                                        | closure, H0.)                                            |
| `ucf.authority`                                     |                                        |                                                          |
+-----------------------------------------------------+----------------------------------------+----------------------------------------------------------+
| **H0 finding, profile consumers**: byte length in   | Bytes, text, integer and float         | Internal pure: consumers of table A and table B entries, |
| `yin/repl/query.cljc:460`, `yin/vm/linker/          | carriers reached by hand               | migrated in H2: `bytes/length` (three sites);            |
| closure.cljc:32`, `yin/vm/ucf/rows.cljc:282`;       |                                        | `text/utf8-decode` for `bytes->str`; `integer/parse-     |
| `bytes->str` at `yin/vm.cljc:404`; PHP number       |                                        | decimal` and table B's `decimal-text->float` for PHP;    |
| parsing at `yang/php.cljc:95-100`; safe-integer     |                                        | `integer/safe-native?` for the two checks; `exact-       |
| checks at `yin/vm/debruijn_register_code.cljc:439`  |                                        | integer?` plus `cbor/num-compare` for the index; table   |
| and `debruijn_register_effects.cljc:130`; the       |                                        | B's `code-points` for the packet. Two of the four        |
| number fast path at `dao/space/index.cljc:78`; code |                                        | **named behaviour changes** of §10 ride with them:       |
| points at `yang/antlr/packet.cljc:67`               |                                        | `bytes->str` (byte-as-char-code on JS and Dart, UTF-8    |
|                                                     |                                        | with U+FFFD substitution on the JVM, V                   |
|                                                     |                                        | `vm.cljc:404-408`) becomes strict UTF-8 on all three;    |
|                                                     |                                        | its JVM-only public encoding arity is **preserved**      |
|                                                     |                                        | pending OQ11 (§13). `js/parseInt` at `php.cljc:100`      |
|                                                     |                                        | loses precision above 2^53 where the JVM does not, and   |
|                                                     |                                        | becomes exact on all three. The index comparator keeps   |
|                                                     |                                        | Dart's exclusion of doubles from native compare (signed  |
|                                                     |                                        | zero, V `:70-85`) by taking the native path for exact    |
|                                                     |                                        | integers only and `cbor/num-compare` for every float on  |
|                                                     |                                        | every host (R7-3).                                       |
+-----------------------------------------------------+----------------------------------------+----------------------------------------------------------+
| **H0 finding, retained dialect arms**: runtime type | A type name for diagnostics            | Dialect semantics (§2.5): display text, never persisted  |
| name for error text at `dao/space/query.cljc:808`,  | (`.-runtimeType` on Dart, `type`       | or hashed; the EDN reader is the same function under two |
| `yang/clojure.cljc:375`, `yin/vm/engine.cljc:929`;  | elsewhere); the dialect's EDN reader   | namespaces. Retained on list 1 under a "dialect arms"    |
| the EDN reader alias at `yin/repl/adapter.cljc:58`  | (`clojure.edn` / `cljs.reader`)        | row (§8.1). No slice.                                    |
| and `yin/repl/dht.cljc:104`                         |                                        |                                                          |
+-----------------------------------------------------+----------------------------------------+----------------------------------------------------------+
| `dao.stream.transit`, `dao.stream.cbor`, `.codec`   | Transport codecs                       | Codec track; untouched (§8.3).                           |
+-----------------------------------------------------+----------------------------------------+----------------------------------------------------------+
| `dao.pretty` (`pretty.cljc:8-23`, `StringWriter`,   | Formatting a value to a string; the    | Internal, pure (a string result; no world contact).      |
| `clojure.pprint`/`cljs.pprint`, a custom cljd       | one consumer is the REPL's display     | Dialect formatting semantics: the three implementations  |
| printer)                                            | (`repl.cljc:355`, V)                   | may format differently; the text is display only, never  |
|                                                     |                                        | persisted or hashed. Allowlisted in list 1 (§8). No      |
|                                                     |                                        | slice.                                                   |
+-----------------------------------------------------+----------------------------------------+----------------------------------------------------------+

### 3.2 Resolved effect calls, corrected (V)

**Three scopes, reconciled (V, H0).** `random-uuid` occurs on **27** lines
in `src/cljc`; **26** of them lie outside the directories OQ1 excludes (the
27th is `dao/postgraphics/terminal.cljc:14`), which is the directory-based
reading r3 and its reviewer used; and **21** lie in the **transitive require
closure** from `yin.repl.main` that H0 committed, because `dao/await.cljc`
(`:235,240`) and `yin/vm/ffi/remote_serve.cljc` (`:417,604,606`) are
required by no `src` file (V, H0). **The gate uses the transitive closure**:
it is the set a running REPL composition can reach, it is committed and
regenerated by the tool, and a file no `src` namespace requires cannot
affect a host's behaviour until something requires it, at which point it
enters the closure and the gate sees it. The five out-of-closure sites are
recorded for the deferred design (§9.1) as reachable only from tests or
not at all. The 21: `dao/stream/ringbuffer.cljc:27,185`; `memory_log.cljc:73`;
`ws.cljc:122`; `remote.cljc:1249`; `remote_pair.cljc:180`;
`remote_route.cljc:173`; `journal.cljc:296`; `dao/jing/content/step.cljc:132`;
`dao/lease.cljc:594`; `dao/space/store/fs.cljc:137,351`;
`yin/vm/linker.cljc:1685`; `yin/vm/ucf/custody.cljc:46`;
`yin/vm/ucf/holder/driver.cljc:1507`; `yin/vm/telemetry.cljc:112`;
`yin/repl.cljc:667,668,733,918`; `yin/repl/dht.cljc:117`.

Other sources, as the H0 gate resolves them (V): `rand` at
`rpc.cljc:114-116`, `rand-int` at `jing/dht.cljc:373`; secure generators at
`space/dht.cljc:142-151` and `sign.cljc:119-123`, plus `sign.cljc:79`, the
deterministic seed-replay proxy, which matches by name and sits in list 2's
ceiling for that file with a comment (§2.5 rules it internal); `gensym` at
three sites; clocks at `main.cljc:1242,1275,1408,1435,1542` (driver),
`driver.clj:169,206` (JVM lane) and the three ws seams (`ws/jvm.clj:422`,
`ws/node.cljs:249`, `ws/dart.cljd:176`, seam-private); ambient at
`fs.cljc:92,136,271-273`, `repl.cljc:275-277`, `main.cljc`; printing at
`main.cljc`: **13 resolved calls** (8 `println`, 1 `print`, 4
`js/console.log`; `flush` is not a print kind and is not counted; r3's "19"
counted lines by text, including `*out*` mentions and docstrings, and is
withdrawn); `antlr/repl.cljc:71` is not a host call (§3.1). Timers the gate sees: `driver.clj:173` (`Thread/sleep`),
`datagram/jvm.clj:70` and the three waitset drivers; `storage.cljc:224-227`
is a promise facade, not a timer, and `main.cljc:1321,1343` construct
threads (driver). All are deferred-scope entries (§9) or seam/driver
sites; none is in an H0-H4 file.

### 3.3 Exempt dialect syntax

Not host contact; the H0 gate exempts them by pattern: `catch` type arms;
`^long`/`^bytes`/`^String` hints; protocol and interface names in `deftype`
bodies and `print-method`; `exists?`; `js-obj`/`(Object.)` opaque sentinels;
require arms whose only purpose is an alias.

## 4. The host-implementation contract

### 4.1 Stream implementations and their seams (external contact)

Unchanged from the tree for sockets (V) and listed for completeness; the
storage, entropy and parser rows are deferred (§9).

+----------------------------+--------------------------------------------+-------------------------------------------------------+-----------------------------+
| Implementation             | Portable core (V)                          | Seam value a host supplies                            | Status                      |
+============================+============================================+=======================================================+=============================+
| Ring buffer, memory log,   | `dao.stream.ringbuffer`, `.memory_log`,    | None. (They mint identities today, §3.2: deferred.)   | Existing                    |
| remote channel, waitset,   | `.remote_channel`, `.waitset`, `.observe`, |                                                       |                             |
| middleware, apply          | `.middleware`, `.apply`                    |                                                       |                             |
+----------------------------+--------------------------------------------+-------------------------------------------------------+-----------------------------+
| WebSocket                  | `dao.stream.ws`                            | `{:connect! :bind! :unbind!}` (`host.cljc:20-33`)     | Existing                    |
+----------------------------+--------------------------------------------+-------------------------------------------------------+-----------------------------+
| Datagram and UDP           | `dao.stream.datagram`, `.udp`              | `bind!` (`datagram.cljc:15-23`)                       | Existing                    |
+----------------------------+--------------------------------------------+-------------------------------------------------------+-----------------------------+
| Durable journal and the    | `dao.stream.journal` over `dao.jing.file`  | Deferred (§9.2)                                       | Deferred                    |
| storage beneath it         | and `store.fs`                             |                                                       |                             |
+----------------------------+--------------------------------------------+-------------------------------------------------------+-----------------------------+
| Entropy                    | Deferred                                   | Deferred (§9.1)                                       | Deferred                    |
+----------------------------+--------------------------------------------+-------------------------------------------------------+-----------------------------+
| Parser stage               | `yang.python.antlr.*`                      | Deferred (§9.3)                                       | Deferred                    |
+----------------------------+--------------------------------------------+-------------------------------------------------------+-----------------------------+
| wasm import surface        | `dao.stream.wasm.md` §1                    | The seven imports                                     | Existing spec               |
+----------------------------+--------------------------------------------+-------------------------------------------------------+-----------------------------+

Seam rule (`abi.md` §6-§7 as acceptance): a seam entry is implementation-
private, called only by the stream implementation that owns it; it takes
host arguments and returns or deposits plain data; it never invokes
application code; it classifies host errors into data; what it cannot do it
answers as a qualified unsupported outcome. A map of functions that portable
application code calls to reach the world is a host service and is rejected
(`abi.md:143-148`); none exists in H0-H4.

### 4.2 The primitive profile: exact manifest (P)

`dao.host.*` (OQ2 adopted) is a namespace family whose files contain only
reader-conditional entries and docstrings stating each entry's law, kind (P
pure, M mutating per §2.4) and oracle. Table A is the profile (76 entries);
table B the portable code replacing host libraries; table C the runtime
prerequisites.

**Table A: profile entries.**

+--------------------+------------------------------------------------------------------------------------------------------+-----+-------+
| Namespace          | Entries (consolidated from, V)                                                                       | N   | Slice |
+====================+======================================================================================================+=====+=======+
| `dao.host.integer` | From `yin.vm.integer.host` (all 25, P): `big-carrier?`, `exact-integer?`, `safe-native?`,            | 28  | H1    |
|                    | `host-float?`, `nan-float?`, `infinite-float?`, `promote`, `from-native`, `demote`, `to-native`,     |     |       |
|                    | `native->double`, `trunc-native`, `add`, `sub`, `mul`, `quot-trunc`, `rem-trunc`, `compare-big`,     |     |       |
|                    | `bit-and-big`, `bit-or-big`, `bit-xor-big`, `shift-left-big`, `shift-right-big`,                     |     |       |
|                    | `magnitude-bit-length`, `to-radix-string`; from `cbor.cljc:112-254` (P): `parse-decimal` (optional   |     |       |
|                    | leading `-` then ASCII digits to big, refuses otherwise; r11 amendment), `gcd`, `magnitude-bytes`   |     |       |
|                    | (fresh bytes). `debruijn_code.cljc:315` reads `big-carrier?` (H2).                                   |     |       |
+--------------------+------------------------------------------------------------------------------------------------------+-----+-------+
| `dao.host.float`   | P: `float64->bits` (16 hex, big-endian), `bits->float64`, `finite?`, `integral?`, `fields` (sign,    | 5   | H1    |
|                    | biased exponent, fraction big) from `cbor.cljc:525-560`, `debruijn.cljc:855-885`,                    |     |       |
|                    | `debruijn_code.cljc:232-242` (little-endian hex becomes a portable byte reversal over                |     |       |
|                    | `float64->bits`, H2), `data.cljc:77-92`.                                                             |     |       |
+--------------------+------------------------------------------------------------------------------------------------------+-----+-------+
| `dao.host.number`  | The Jing numeric carriers and their host construction and access, from `cbor.cljc:342-460,483-632`   | 10  | H1    |
|                    | (P): `float64-of`, `float-value`, `decimal?`, `decimal-of` (exponent, mantissa; refuses outside the  |     |       |
|                    | JVM scale window, `:552-574`), `decimal-exponent`, `decimal-mantissa`, `ratio?`, `ratio-of`          |     |       |
|                    | (reduced, sign on numerator, zero denominator refused), `ratio-numerator`, `ratio-denominator`; the  |     |       |
|                    | `Float64`, `Rational`, `Decimal` deftypes move with them (table C laws).                             |     |       |
+--------------------+------------------------------------------------------------------------------------------------------+-----+-------+
| `dao.host.bytes`   | P: `bytes?`, `alloc` (fresh, zeroed), `length`, `get`, `slice` (fresh), `equal?`, `from-vector`      | 10  | H1    |
|                    | (fresh), `to-vector`; M: `set!`, `copy!` (memmove). From `chunks.cljc:6-32`,                         |     |       |
|                    | `jing/dht.cljc:100-121`, `linker.cljc:1232`, `sign.cljc:55-59`, `boring.cljc:46-51`,                 |     |       |
|                    | `datagram.cljc:63-66`, `debruijn.cljc:167`. (`codec.cljc:45` reads it after the codec track's C4,    |     |       |
|                    | §8.3.)                                                                                               |     |       |
+--------------------+------------------------------------------------------------------------------------------------------+-----+-------+
| `dao.host.text`    | P: `unit-count`, `code-unit`, `units->string`, `utf8-encode` (fresh bytes; refuses an unpaired       | 6   | H1    |
|                    | surrogate), `utf8-decode` (refuses invalid sequences; never substitutes U+FFFD), `nfc` (Unicode      |     |       |
|                    | 16.0) from `data.cljc:119-173`, `debruijn.cljc:241,310-321`, `jing.cljc:65-71,131-135`,              |     |       |
|                    | `sign.cljc:62-66`.                                                                                   |     |       |
+--------------------+------------------------------------------------------------------------------------------------------+-----+-------+
| `dao.host.hash`    | P: `blake3-bytes`, `sha256-bytes` (`jing.cljc:59-135`; the string forms become portable over         | 2   | H3    |
|                    | `text`).                                                                                             |     |       |
+--------------------+------------------------------------------------------------------------------------------------------+-----+-------+
| `dao.host.sign`    | P: `keypair-from-seed` (`sign.cljc:75-104`, seed replay), `sign`, `verify`. `generate` (fresh        | 3   | H3    |
|                    | entropy, `:114-124`) stays in `yin.vm.linker.sign` until the deferred design moves it.               |     |       |
+--------------------+------------------------------------------------------------------------------------------------------+-----+-------+
| `dao.data.arrays`  | In place (OQ2). P: `anew` (fresh), `aget`, `alength`, `aclone` (fresh), `asub` (fresh), `aconcat`    | 12  | H2    |
| (member by         | (fresh), `ainsert-at` (fresh), `aremove-at` (fresh), `from-coll` (fresh); M: `aset`, `acopy`         |     |       |
| reference)         | (memmove), `asort` (stable; `parallelSort` replaced). `arrays.cljc:28-182`.                          |     |       |
+--------------------+------------------------------------------------------------------------------------------------------+-----+-------+

**Table B: portable over the profile, no host arm (P).** These functions
carry no reader arm and are **not counted in the 76**; two of them live in
`dao.host.*` namespaces beside the arms they are built on. Base64 (replaces
`goog.crypt.base64`, `dart:convert`, the JVM codec; **a replacement, not code
motion**; oracle V `test/dao/stream/base64_test.cljc`, 3 deftests:
`rfc-4648-round-trips`, `decode-is-total-and-strict`, `every-length-round-trips`);
the Jing CBOR writer (replaces Boring and the Dart `cbor` package on the
content path only, OQ4; a replacement; oracle table D); hex; `chunks/split`,
`chunks/absorb`; and, added in r8 (R7-1), **built in H1**:

- `dao.host.text/code-points` (P): the code points of a string as a vector
  of integers, over `unit-count` and `code-unit`; a surrogate pair decodes
  to one code point, an unpaired surrogate is its own code point. This is
  the private `code-points` of `data.cljc:143-159` (V) made public and
  moved; `yang/antlr/packet.cljc:64-69` (V: `.codePoints`, `.-runes`,
  `js/Array.from`, which agree on paired and unpaired surrogates) switches
  to it in H2. Oracle: the `data_test.cljc` code-point cases (table D) plus
  `dao.host.text-test` cases for BMP, a non-BMP pair, a lone high and a
  lone low surrogate, and the empty string.
- **The float-text kernel, extracted (R7-1, r9).** r8's proposal to call
  `yin.vm.integer/decimal->float` was wrong twice over: `integer.cljc:769`
  defines a **private** `int-decimal->float`, and `:855` exports a
  guest-module symbol, not a var (V); and a `dao` namespace may not require
  `yin` (§4.2 layering), which the call would have violated and, after H2,
  would have closed a cycle `yin.vm -> dao.jing.cbor -> dao.host.float`.
  The kernel is instead **extracted** so that one implementation exists in
  the profile and the VM module consumes it:
  - **Owner**: `dao.host.float` (over `dao.host.integer` only; no `yin`
    require). The kernel is written entirely over the shim today (V: every
    helper calls `host/*`, `integer.cljc:156-163, 394-440, 487-493,
    580-586, 648-710`), so the move is a cut and paste under a new
    namespace.
  - **What moves** (P, all `defn-` today): `ratio->float` (`:434-464`:
    nearest-even binary64 of `x / y`, the one rounding step), `pow2`,
    `signed`, `trunc-big`, `float-ratio`, `decimal-exponent`, `scaled`,
    `pow10` and `small-powers-of-ten`, `digits->big`, `strip-trailing-zeros`,
    `sign`, `bits`, and the two kernels `decimal->float` (`:769-802`
    without its refusal preamble) and `float-digits` (`:721-759`). They
    become public functions of `dao.host.float` under these exact
    signatures: `(decimal->float digit-values exp10)` takes a vector of
    digit values 0-9 and an exact-integer exponent (any carrier) and
    answers a host double: the nearest-even binary64 of
    `digits x 10^exp10`, `+inf` past the range, `0.0` below it, never a
    refusal (syntax is the caller's); `(float-digits v)` takes a finite
    nonzero host double and answers `[digits-string exp10]`, the shortest
    round-tripping decimal (CPython `repr` digits); `(ratio->float x y
    negative?)` as today. **Table B composition** `decimal-text->float`
    (text `digits[.digits]` to digit values and `-(fraction length)`, then
    `decimal->float`; refuses any other text) sits beside them.
  - **How `yin.vm.integer` consumes it (H2)**: `int-decimal->float` and
    `int-float-digits` keep their arity, type and syntax refusals
    (`wrong-type!`, `refuse!`, `digit-values!`, `float!`) and delegate the
    arithmetic to `dao.host.float/decimal->float` and `float-digits`;
    `int-to-float`, `int-true-div` and `int-from-float` call the moved
    `ratio->float` and `trunc-big`. One implementation, in the profile;
    the VM module is its first consumer and `yang.php` its second.
  - **Where it is counted**: nowhere in the 76. The moved functions carry
    no reader arm; they are compositions over `dao.host.integer` entries
    and are listed under Table B as the "float-text kernel" (one named
    item), which keeps Table A's count honest (R7-1).
  - **Oracle that proves equality with today**: `integer_test.cljc`
    `float-fixture-test`, `float-named-cases-test`,
    `float-text-named-cases-test`, `float-refusals-test` and
    `int_contract_test.cljc` `float-rows-recompute-on-this-host`,
    `text-round-trips-through-the-module` (V, CPython-generated rows): H2
    runs them unchanged through the rewired module, so any arithmetic
    difference fails; H1 additionally runs the fixture rows **directly**
    against `dao.host.float` in `dao.host.float-test`, plus the
    `decimal-text->float` cases `"1.5"`, `"0.1"`, `"9007199254740993.0"`
    (rounds to `9007199254740992.0`), a 400-digit integer text (`+inf`)
    and `"1."`.
  - **Slices**: H1 creates `dao.host.float` with the kernel copied in and
    tested directly (the VM module still holds its own copy, so no
    behaviour changes); H2 rewires `yin.vm.integer` to delegate and
    deletes its copy, and switches `yang/php.cljc:95-97` to
    `decimal-text->float`. `Double/parseDouble`, `js/parseFloat` and Dart
    `double.parse` are nearest-even on this grammar (R), so the PHP float
    path is not a behaviour change; the integer path at `:98-100` is (§10).
- `dao.host.text/code-points` is callable from `dao.host` with no `yin`
  require (V): the private `data.cljc:143-159` is written over
  `unit-count` and `code-unit` only, which are Table A text entries; its
  surrogate cases exist in `data_test.cljc` (`non-bmp-code-points-test`).

**Table C: runtime prerequisites (documented and verified by existing
suites; not profile entries; not a boundary).**

+----------------------------------+------------------------------------------------------------------------------------------------+---------+
| Prerequisite                     | Where, and the law a port must meet (V)                                                        | Slice   |
+==================================+================================================================================================+=========+
| `deftype` with per-host          | `values.cljc:39-119`: `Closure`/`Continuation` equal iff owner and payload equal; hash of      | H1 docs |
| equality, hashing and printing   | `[:tag owner payload]`; print the marker text. `cbor.cljc:342-460`: `Float64` equal by bits    |         |
|                                  | with NaN equal to NaN (`:365-367`); `Rational`, `Decimal` equal by fields; hashes at           |         |
|                                  | `:372-444`.                                                                                    |         |
+----------------------------------+------------------------------------------------------------------------------------------------+---------+
| Atomic flag                      | `btree.cljc:87-119`: transient ownership token readable across threads.                        | none    |
+----------------------------------+------------------------------------------------------------------------------------------------+---------+
| Weak and soft references         | `btree.cljc:182-245`; default `:soft` on the JVM, `:strong` on JS and Dart (`:39-44`). A host  | none    |
|                                  | without them declares `:strong`; correctness is unchanged, retention differs.                  |         |
+----------------------------------+------------------------------------------------------------------------------------------------+---------+
| `locking`, atoms, volatiles      | Five `locking` sites (§3.1); single-owner discipline elsewhere.                                | none    |
+----------------------------------+------------------------------------------------------------------------------------------------+---------+
| Host promise type                | `btree/storage.cljc:218-227`: deferred (§9.1).                                                 | defer   |
+----------------------------------+------------------------------------------------------------------------------------------------+---------+
| Dialect syntax                   | §3.3.                                                                                          | H0      |
+----------------------------------+------------------------------------------------------------------------------------------------+---------+

Layering: `dao.host.*` requires nothing in the repo; `dao` never requires
`yin`. Consumers after H2: `dao.jing`, `dao.jing.cbor`, `yin.vm.integer`,
`yin.vm.data`, `yin.vm.values`, `yin.vm.engine`, `yin.vm`, `yin.vm.debruijn`,
`yin.vm.debruijn-code`, `dao.stream.chunks`, `dao.jing.dht`, `yin.vm.linker`,
`yin.vm.linker.sign`, `dao.stream.datagram`, `dao.stream.base64`, and the
three Python integer tests.

### 4.3 Oracle coverage matrix (table D)

Entry to exact file and case. V = the case exists today and exercises the
behaviour through its present namespace; P = proposed, written in the slice
named. After H1 every V case is additionally run against `dao.host.*`
directly by `dao.host.conformance-test` (P), which requires the new
namespaces and re-asserts each row below through them (review A6).

+---------------------------------+---------------------------------------------------------------------------------------------------------+-----+
| Entries                         | File and case                                                                                           | V/P |
+=================================+=========================================================================================================+=====+
| integer: carriers, promote,     | `test/yin/vm/integer_test.cljc` (25 deftests): `carrier-boundary-test`, `compare-test`,                 | V   |
| demote, add, sub, mul, quot,    | `division-test`, `bits-test`, `pow-test`, `parse-format-test`, `digit-limit-test`, `bit-limit-test`,    |     |
| rem, compare, bit ops, shifts,  | `fast-path-edge- test`, `fast-path-respects-the-bit-limit-test`, `integer?-test`,                       |     |
| bit length, radix               | `kind-of-and-number?-test`; `integer_v3_fixtures.cljc` (data).                                          |     |
|                                 | `test/yang/python/antlr/int_contract_test.cljc`: `integer-rows- recompute-on-this-host`,                |     |
|                                 | `canonical-widths-at-the-boundaries`, `text-round-trips-through-the-module` against                     |     |
|                                 | `test/resources/yang/python/int-contract-v1.txt` (CPython-generated).                                   |     |
+---------------------------------+---------------------------------------------------------------------------------------------------------+-----+
| integer: `parse-decimal`,       | `test/dao/jing/cbor_conformance_test.cljc`: `the-resource-bytes-are-the-frozen-bytes`, `produced-bytes- | V   |
| `gcd`, `magnitude-bytes`        | pass-the-raw-cbor-inspection`, `injectivity-and-groups-hold-on-the-bytes-this-host-produced` (the       |     |
|                                 | corpus's tag 2/3, ratio and decimal cases); `cbor_fixtures_test.cljc`: `recorded-blake3-is-the-digest-  |     |
|                                 | of-the-bytes`, `retained-distinctions-differ-in-bytes-and-address`.                                     |     |
+---------------------------------+---------------------------------------------------------------------------------------------------------+-----+
| float: bits, fields, finite,    | `integer_test.cljc`: `float-fixture-test`, `float-named-cases-test`, `float-text-named-cases-test`,     | V   |
| integral                        | `float-refusals-test`; `int_contract_test.cljc`: `float-rows-recompute-on-this-host`; `data_test.cljc`: |     |
|                                 | `float-seam-test`; the corpus float cases (NaN `7ff8...`, `-0.0`) through the conformance test;         |     |
|                                 | `debruijn_code_test.cljc` (26 deftests) scalar cases for the little-endian hex.                         |     |
+---------------------------------+---------------------------------------------------------------------------------------------------------+-----+
| number: decimal and ratio       | The corpus decimal and ratio cases through `cbor_conformance_test.cljc`; `cbor_test.cljc` (21 deftests) | V   |
| construction, access, carriers  | decimal window and zero-denominator refusals; `cbor_fixtures_test.cljc`: `equivalence-                  |     |
|                                 | groups-share-bytes`, `every-required-refusal-class-occurs`.                                             |     |
+---------------------------------+---------------------------------------------------------------------------------------------------------+-----+
| bytes: all ten                  | `dao.host.bytes-test`: round trip, slice freshness, `copy!` overlap (memmove) both directions, out-of-  | P   |
|                                 | range refusal with **no partial write** (target byte-identical after a refused `set!`/`copy!`),         |     |
|                                 | `equal?` on equal and unequal content, and the P-entry argument-immutability property (§2.4); H1.       |     |
+---------------------------------+---------------------------------------------------------------------------------------------------------+-----+
| text: code units,               | `data_test.cljc`: `non-bmp-code-points-test`, `substring-char-at-test`, `code-points-str-compare-test`; | V   |
| `units->string`                 | the corpus `unpaired-surrogate` refusals through the conformance test.                                  |     |
+---------------------------------+---------------------------------------------------------------------------------------------------------+-----+
| text: `utf8-encode`,            | `dao.host.text-test`: RFC 3629 valid and invalid sequences (overlong, surrogate code points,            | P   |
| `utf8-decode`                   | truncated), each asserted as a **direct refusal** (never a substituted character); unpaired-surrogate   |     |
|                                 | encode refusal; the decode cases cross-checked against the corpus `invalid-utf8` class; H1. In H3,      |     |
|                                 | direct refusal tests on `dao.jing/digest-string` and `sign`'s message assembly (the consumers).         |     |
+---------------------------------+---------------------------------------------------------------------------------------------------------+-----+
| table B: `code-points`,         | `dao.host.text-test`: BMP, non-BMP pair, lone high and lone low surrogate, empty string;                | P   |
| `decimal-text->float`, the      | `dao.host.float-test`: the five texts named in table B, equal on three lanes, **and** the               |     |
| float-text kernel               | `integer_v3_fixtures.cljc` float rows and `int-contract-v1.txt` float rows run directly against         |     |
|                                 | `dao.host.float/decimal->float`, `float-digits` and `ratio->float` (H1); in H2 the unchanged            |     |
|                                 | `integer_test.cljc` float suites and `int_contract_test.cljc` pass through the rewired module.          |     |
+---------------------------------+---------------------------------------------------------------------------------------------------------+-----+
| H2 consumer tests for the four  | (1) `arrays_test`: `acopy`/`aset` no-partial-write (above). (2) H3: `digest-string` and `message`       | P   |
| behaviour changes (§10)         | refuse an unpaired surrogate. (3) `yin.vm` primitive `bytes->str`, one test namespace on three lanes:   |     |
|                                 | the UTF-8 bytes of `"héllo"` decode equal on every lane; the lone byte `0xE9` and the sequence `C3 28`  |     |
|                                 | refuse on every lane (today: a char on JS and Dart, U+FFFD on the JVM); ASCII bytes unchanged; the      |     |
|                                 | encoding arity on the JVM answers as before pending OQ11. (4) `yang.php`: `"9007199254740993"` parses   |     |
|                                 | to that exact integer on every lane (a big carrier on JS), `"12"` to a native carrier, `"1.5"` and      |     |
|                                 | `"0.1"` to the nearest doubles, a 400-digit integer text to an exact big, a 400-digit float text to     |     |
|                                 | `+inf`; the result carrier on each host is the canonical one (`carrier-boundary-test` law). Also the    |     |
|                                 | index comparator (R7-3): `(compare-vals 0.0 -0.0)` is 0, `-0.0` and `0.0` tie in sort order, NaN sorts  |     |
|                                 | last, `-inf < finite < +inf`, `1` and `1.0` tie, on every lane, in `dao.space.index-test`.              |     |
+---------------------------------+---------------------------------------------------------------------------------------------------------+-----+
| text: `nfc`                     | `test/yin/vm/debruijn_test.cljc` (80 deftests) example cases (V, examples only); `dao.host.text-test`:  | V+P |
|                                 | a pinned subset of Unicode 16.0 `NormalizationTest.txt` (every part-1 line plus the Hangul and          |     |
|                                 | composition exclusion rows); written in H1.                                                             |     |
+---------------------------------+---------------------------------------------------------------------------------------------------------+-----+
| hash: `blake3-bytes`,           | `test/dao/jing/hash_registry_contract_test.cljc` (15 deftests): `blake3-official-and-utf8-vector-       | V+P |
| `sha256-bytes`                  | conformance`, `fixtures-load-and-agree-with-canonical-bytes`, `segment-matches-total-predicate-blake3-  |     |
|                                 | and-sha256`; `test/resources/dao/jing/digest-table.edn`; `jing_test.cljc` (39 deftests) address cases.  |     |
|                                 | P in H3: NIST SHA-256 short-message vectors in `dao.host.hash-test`.                                    |     |
+---------------------------------+---------------------------------------------------------------------------------------------------------+-----+
| sign: `keypair-from-seed`,      | `test/yin/vm/linker/sign_test.cljc` (4 deftests): `rfc-8032-vectors`, `canonical-envelope-vectors`,     | V   |
| `sign`, `verify`                | `tampering-is-bad-proof`, `key-file-validation`; `sign_vectors.edn`.                                    |     |
+---------------------------------+---------------------------------------------------------------------------------------------------------+-----+
| arrays: all twelve              | `test/dao/data/btree_test.cljc`, `btree_transients_test.cljc`, `psset_fixtures.cljc` (V, through the    | V+P |
|                                 | tree); P in H2: `asort` stability fixture (equal keys keep input order), `acopy` overlap cases, and     |     |
|                                 | **no-partial-write** cases for `acopy` and `aset` with an invalid index or length on all three lanes    |     |
|                                 | (the array is identical after the refusal; today JS and Dart can write first, `arrays.cljc:97-105`).    |     |
+---------------------------------+---------------------------------------------------------------------------------------------------------+-----+
| Jing CBOR writer (table B)      | `test/resources/dao/jing/cbor-v1.json` with `cbor-v1.errata.md` preserved; `cbor_fixtures_test.cljc`    | V   |
|                                 | (14 deftests), `cbor_conformance_test.cljc` (14): byte-identical production, `documented-skips-match-   |     |
|                                 | the-errata-and-leave-no-case-unrun`; `cbor_test.cljc`; `jing_test.cljc`; `file_test.cljc` (17) replay.  |     |
+---------------------------------+---------------------------------------------------------------------------------------------------------+-----+
| base64 (table B)                | `test/dao/stream/base64_test.cljc` (3 deftests, named above).                                           | V   |
+---------------------------------+---------------------------------------------------------------------------------------------------------+-----+

### 4.4 The driver

Per host, outside the core: the seam values of §4.1, clock readings as data,
stdin/stdout, process exit, ambient facts as data. `yin.repl.main` is that
today (V). Its deferred duties (entropy deposit, pump cadence) are §9's.

### 4.5 Relation to the conformance kit and the wasm surface

`yin.vm.engine.md` §E.5 presupposes a correct host value model. H1 supplies
`dao.host.conformance-test` (table D through the new namespaces); E.5.2's
"R, owner, seal, profile and manifest addresses bit-identical" is downstream
of it. The stream-implementation suite is deferred (§9.4).

## 5. What porting a host consists of (after H0-H4)

1. Implement table A (76 functions) and meet table C for the dialect; green
   `dao.host.conformance-test`. Hash and signature bind a library compiled
   into the same instance.
2. Load the adopted closure with a reader feature only where §3.3 syntax
   requires it; the H0 gate proves zero source edits outside `dao.host.*`
   and the §9 deferred files.
3. The deferred seams (§9) remain per-host work until their design lands;
   until then a port supplies them as today's hosts do, by reader arms in
   the deferred files, which H0's allowlist permits.

## 6. Verifiability

Byte-level oracles exist for every hot primitive (table D). H0's gate has
two lists (files permitted non-exempt arms, §8.1; resolved effect calls
permitted) and two negative tests; it scans recursively and treats reading
zero files as a failure distinct from zero matches, as does its library
mode (§8.2) for the H2 and H3 host-library gates. Every H1-H4 acceptance is
byte-identical against a named corpus with errata preserved; the four
behaviour changes (§10) each have a direct test (table D).

## 7. Consequence for wasm

Primitives execute in-instance. The import module `dao.stream` has exactly
the seven names of `dao.stream.wasm.md:38-46` (V); a primitive import is a
boundary crossing and a violation of §2.2 whatever its purity. The gate that
asserts a binary's import list is part of the deferred conformance suite
(§9.4); until then the rule is stated here and checked by review.

## 8. Slices H0-H4 (commissionable)

Expand, migrate, contract. Each slice lands alone under the quick tier on
three lanes with recorded test and assertion counts; "unchanged" means equal
counts plus any added test named. The slow tier runs once after H4.

+-------+--------+--------------------------------------------------------------------------------+-------------------------------------------------------------------+
| Slice | Phase  | Change                                                                         | Falsifiable acceptance                                            |
+=======+========+================================================================================+===================================================================+
| H0    | freeze | No `src/` change. (a) `src/dev/host_boundary/closure.clj` (over                | Gate passes on the current tree; the committed closure output     |
|       |        | `clojure.tools.namespace` parsing) and its output                              | lists exactly 142/140/141 files (§3.1) and the slice report       |
|       |        | `docs/design/host-boundary/closure-{clj,cljs,cljd}.edn`, each file tagged with | records agreement with an independent traversal; the gate fails   |
|       |        | its §3.1 class; (b) the gate test `dao.host.gate-test` over the closure with   | on one injected `#?(:clj` in a file outside list 1 and on one     |
|       |        | **list 1** (files permitted non-exempt host arms, §8.1) and **list 2**         | injected `(random-uuid)` outside list 2 (two negative tests), and |
|       |        | (resolved effect calls permitted: the §3.2 sites, plus the callers of M        | fails distinctly when it reads zero files (scan failure is not    |
|       |        | entries named in §2.4), exempting §3.3 patterns; the gate scans files          | zero matches). Counts recorded.                                   |
|       |        | recursively and fails if it read none; (c) per-lane counts; (d) the corpus     |                                                                   |
|       |        | hash set.                                                                      |                                                                   |
+-------+--------+--------------------------------------------------------------------------------+-------------------------------------------------------------------+
| H1    | expand | Create `dao.host.integer`, `.float`, `.number`, `.bytes`, `.text` with today's | Green; counts unchanged plus the named new tests; the new tests   |
|       |        | arms moved verbatim where the law is today's behaviour, and **with the new     | require and call `dao.host.*` directly; corpus bytes identical;   |
|       |        | laws where it is not**: `utf8-encode` refuses an unpaired surrogate (today's   | the P-entry argument-immutability property test and the M-entry   |
|       |        | helpers substitute: JVM `getBytes` emits `?`, `goog.crypt` and `dart:convert`  | prevalidation test pass on three lanes; `utf8-encode` direct      |
|       |        | emit U+FFFD, `jing.cljc:66,69,71,132-135`, `sign.cljc:62-66`, R for the        | refusal test and `utf8-decode` strict tests pass. List 1 grows by |
|       |        | library behaviour); `utf8-decode` is extracted from the already strict         | five files.                                                       |
|       |        | `cbor.cljc:1240-1271` (V). The `Float64`/`Rational`/`Decimal` deftypes move    |                                                                   |
|       |        | with their protocol blocks. Tests: `dao.host.conformance-test`,                |                                                                   |
|       |        | `dao.host.bytes-test`, `dao.host.text-test` (table D). Table C laws written    |                                                                   |
|       |        | into the docstrings of `values.cljc` and the moved carriers. No consumer       |                                                                   |
|       |        | changes, so no behaviour change is observable yet.                             |                                                                   |
+-------+--------+--------------------------------------------------------------------------------+-------------------------------------------------------------------+
| H2    | migr.  | Every consumer moves: `yin/vm.cljc:32`, `yin/vm/engine.cljc:38`,               | Green; counts unchanged plus the `asort` stability, `acopy`       |
|       |        | `yin/vm/values.cljc:30`, `yin/vm/data.cljc:41`, `yin/vm/integer.cljc:101`,     | overlap and `acopy`/`aset` **no-partial-write** tests; corpus     |
|       |        | `test/yang/python/antlr/{int_heap,int_literal,int_ops}_test.cljc` (V);         | identical. **Pre-deletion gate**: the H0 closure tool, reading    |
|       |        | `dao.jing.cbor` drops `:106-254`, `:342-460`, `:483-632` for `dao.host.*`;     | `ns` forms (not text), reports `yin.vm.integer.host` required by  |
|       |        | `debruijn_code.cljc:232-242,315`, `debruijn.cljc:167,241,310-321,855-885`,     | no file in `src` or `test`; the only textual mention left is its  |
|       |        | `chunks`, `jing.dht`, `linker:1232`, `sign:55-66`, `datagram:63-66` read       | own file (the docstring at `integer.cljc:14` is updated in H2).   |
|       |        | `bytes`/`text`/`float`; `dao.data.arrays` gains the stable sort and            | **Post-deletion gate**: the file is absent and the tool reports   |
|       |        | **prevalidation before any write** in `acopy` on JS and Dart (a behaviour      | no require of it; a tool failure is distinct from "none found".   |
|       |        | change, §2.4); the M-entry callers of §2.4 are audited for the freeze rule.    | **Host-library gate (§8.2), built in this slice** as the library  |
|       |        | The eight H0-found consumers (§3.1) migrate here as explicit **portability     | mode of `dao.host.gate-test`: within the adopted closure, no      |
|       |        | corrections**: `bytes->str` over `text/utf8-decode` (encoding arity preserved, | executable reference to `BigInteger`, `BigDecimal`,               |
|       |        | OQ11), PHP literals over `integer/parse-decimal` and `decimal-text->float`,    | `clojure.lang.Ratio`, `java.text`, `unorm` outside `dao/host/`;   |
|       |        | the index comparator over `exact-integer?` plus `cbor/num-compare`, the packet | the H2 consumer tests of table D (`bytes->str`, PHP literals,     |
|       |        | over `code-points`, the two safe-integer checks and three byte lengths.        | index signed zero) pass on three lanes; the float-text fixtures   |
|       |        | `yin.vm.integer` is rewired to delegate its float-text kernels to              | pass unchanged through the rewired module (table B); two negative |
|       |        | `dao.host.float` and its private copy deleted (table B, R7-1). Then            | tests: a fixture closure file holding an executable `BigInteger`  |
|       |        | `yin.vm.integer.host` deleted; the shim mention in `integer.cljc:14` reworded. | reference outside the allowlist fails the gate, and a fixture     |
|       |        | Deliverable: the library mode of `dao.host.gate-test` with the H2 name list    | file naming `BigInteger` only in a docstring and a keyword        |
|       |        | (§8.2).                                                                        | passes. `yin.vm.engine` is touched (its require) and pinned by    |
|       |        |                                                                                | the engine suites. List 1 shrinks by the migrated files (§8.1).   |
+-------+--------+--------------------------------------------------------------------------------+-------------------------------------------------------------------+
| H3    | expand | `dao.host.hash`, `dao.host.sign`; `dao.jing` and `yin.vm.linker.sign` require  | Green; counts unchanged plus `dao.host.hash-test`; every corpus   |
|       | +migr. | them (`generate` stays, deferred). The string hash forms and `sign`'s `utf8`   | address identical; `sign_test` 4/4; `base64_test` 3/3 on three    |
|       |        | switch to `text/utf8-encode`: a **named behaviour change** for strings with an | lanes; a direct test that `digest-string` and `message` refuse an |
|       |        | unpaired surrogate (refusal instead of a substituted byte), recorded in the    | unpaired-surrogate string; **host-library gate (§8.2)**: within   |
|       |        | slice report; such strings are already refused by the canonical codec (corpus  | the adopted closure, no executable reference to `MessageDigest`,  |
|       |        | `unpaired-surrogate` class), so no persisted address changes. Base64 made      | `Blake3`, `noble`, `blake3_dart`, `package:crypto`,               |
|       |        | portable (table B; a replacement).                                             | `ed25519_edwards`, `goog.crypt.base64` outside `dao/host/`, and   |
|       |        |                                                                                | for the Ed25519 names outside `yin/vm/linker/sign.cljc`           |
|       |        |                                                                                | (`generate`); scan errors reported distinctly.                    |
+-------+--------+--------------------------------------------------------------------------------+-------------------------------------------------------------------+
| H4    | migr.  | Portable Jing CBOR writer over `dao.host.*`; `dao.jing.cbor.boring` and        | Green; `cbor-v1.json` byte-identical on three lanes with the      |
|       |        | `dao.jing.cbor.cljd` deleted; Boring stays for `dao.stream.cbor.boring` and    | errata skips unchanged (`documented-skips-match-the-errata-and-   |
|       |        | the Dart `cbor` package for `dao.stream.cbor.cljd` (OQ4). A measurement:       | leave-no-case-unrun`); fixtures, DHT and linker image goldens     |
|       |        | corpus and one large module image, old writer against new, JVM and Node.       | unchanged; the measurement in the slice report, no threshold      |
|       |        |                                                                                | (OQ7). Slow tier once after landing.                              |
+-------+--------+--------------------------------------------------------------------------------+-------------------------------------------------------------------+

Dependencies: H0, H1, H2 in order; H3 and H4 after H2, in either order.

### 8.1 List 1: the files permitted non-exempt host arms, baseline and final

The gate never promises "no arms across `src/cljc`", only "no non-exempt
arms outside list 1". List 1 is generated at H0 by the gate from the actual
closure (every closure file holding a non-exempt arm, §3.3) and must equal
the **H0 baseline** below, by category, with each file's reason; a
difference either way is an H0 finding to resolve before H1. The baseline
is today's tree: the seven `dao.host.*` files are **planned additions**
(five in H1, two in H3) and are not part of the H0 baseline; they are
marked as such in the table.

+----------------------------+-------------------------------------------------------------------------------------------------+-----------------------------+
| Category (reason)          | Files (V, from §1 and the closure)                                                              | After H4                    |
+============================+=================================================================================================+=============================+
| H1-H4 migration sources    | `yin/vm/integer/host.cljc`, `dao/jing/cbor.cljc`, `dao/jing/cbor/boring.cljc`,                  | Removed from list 1 as each |
|                            | `src/cljd/dao/jing/cbor/cljd.cljd` (the Dart Jing writer; a lane-specific file counted here,    | slice lands; `sign.cljc`    |
|                            | not under seams), `dao/jing.cljc`, `yin/vm/linker/sign.cljc`, `yin/vm/debruijn.cljc`,           | stays for `generate` only;  |
|                            | `yin/vm/debruijn_code.cljc`, `yin/vm/data.cljc`, `yin/vm/values.cljc` (table C blocks stay, as  | three of these files are    |
|                            | exempt protocol syntax), `dao/stream/chunks.cljc`, `dao/stream/base64.cljc`,                    | deleted (§8.1 paragraph).   |
|                            | `dao/jing/dht.cljc`, `yin/vm/linker.cljc`, `dao/stream/datagram.cljc` (bytes arms only); and,   |                             |
|                            | from H0's finding (§3.1, profile consumers, H2): `yin/vm.cljc:404`, `dao/space/index.cljc:78`,  |                             |
|                            | `yin/repl/query.cljc:460`, `yin/vm/linker/closure.cljc:32`, `yin/vm/ucf/rows.cljc:282`,         |                             |
|                            | `yang/php.cljc:95,98`, `yin/vm/debruijn_register_code.cljc:439`,                                |                             |
|                            | `yin/vm/debruijn_register_effects.cljc:130`, `yang/antlr/packet.cljc:67`.                       |                             |
+----------------------------+-------------------------------------------------------------------------------------------------+-----------------------------+
| Profile (the arms belong   | Baseline: `dao/data/arrays.cljc`. Planned additions, not in the H0 baseline:                    | Stay; the seven additions   |
| here by design)            | `dao/host/{integer,float,number,bytes,text,hash,sign}.cljc` (P; five join list 1 at H1, two at  | are on list 1 from the      |
|                            | H3).                                                                                            | slice that creates them.    |
+----------------------------+-------------------------------------------------------------------------------------------------+-----------------------------+
| Retained runtime           | `dao/data/btree.cljc` (atomic flag `:102-119`, weak and soft references `:212-245`: substantive | Stay, with the table C      |
| prerequisites (table C)    | arms, not protocol syntax, retained by design); `dao/data/btree/storage.cljc` (promise facade,  | reason recorded in the      |
|                            | deferred §9.1); `dao/pretty.cljc` (`StringWriter`/pprint, display only, §3.1).                  | gate's list.                |
+----------------------------+-------------------------------------------------------------------------------------------------+-----------------------------+
| Codec-track files          | `dao/stream/ws.cljc`, `transit.cljc`, `cbor.cljc`, `cbor/boring.cljc`, `codec.cljc`,            | Stay; owned by that track;  |
| (untouched by this track)  | `yin/repl/connect.cljc` (J3). (H0: `remote_channel.cljc` and `ws_project.cljc` carry only        | `codec.cljc:45` after C4.   |
|                            | exempt `catch` arms and are not on the generated list; `jing/stream.cljc` is not in the        |                             |
|                            | closure. All remain codec-track files under §8.3; they are simply not list-1 entries.)         |                             |
+----------------------------+-------------------------------------------------------------------------------------------------+-----------------------------+
| Dialect arms (H0 finding,  | `dao/space/query.cljc` (`:808`), `yang/clojure.cljc` (`:375`), `yin/vm/engine.cljc` (`:929`): a | Stay.                       |
| retained)                  | runtime type name in diagnostic text; `yin/repl/adapter.cljc` (`:58`), `yin/repl/dht.cljc`      |                             |
|                            | (`:104`): the dialect's EDN reader under two namespaces. Five files.                            |                             |
+----------------------------+-------------------------------------------------------------------------------------------------+-----------------------------+
| Seams, stream              | `src/clj`, `src/cljs`, `src/cljd` seam and shadow files (§3.1 lane-specific list) **except**    | Stay, except the Dart Jing  |
| implementations and driver | `src/cljd/dao/jing/cbor/cljd.cljd`, which is a Jing writer H4 deletes, not a seam; it is on the | writer, deleted in H4.      |
|                            | baseline under the first row's `dao/jing/cbor/boring.cljc` twin. `dao/stream/ringbuffer.cljc`   |                             |
|                            | (`:168`, the in-memory stream implementation's own host atom test: an                           |                             |
|                            | **implementation-private** arm under §4.1, its one name in this document and in the closure     |                             |
|                            | class `:external-stream-seam`). `yin/repl/main.cljc`, `yin/repl/host.cljc`,                     |                             |
|                            | `yin/repl/state.cljc`.                                                                          |                             |
+----------------------------+-------------------------------------------------------------------------------------------------+-----------------------------+
| Deferred-scope files (§9)  | `dao/jing/file.cljc`, `dao/space/store/fs.cljc`, `dao/space/store.cljc`,                        | Stay until the deferred     |
|                            | `dao/stream/journal/file.cljc`, `dao/jing/coordinate.cljc`, `dao/space/dht.cljc`,               | design lands.               |
|                            | `dao/stream/rpc.cljc` (`:114`, the `rand` arm), `yin/repl.cljc`,                                |                             |
|                            | `yang/python/antlr/parser.cljc` and the other `yang/python/antlr/*.cljc` with arms,             |                             |
|                            | `dao/stream/journal.cljc` (`locking`), `dao/space/transactor.cljc`, `yin/vm/ucf/authority.cljc` |                             |
|                            | (`locking`), `yin/repl/frontends.cljc` (J3/J5), `yin/repl/store.cljc` (J3/J4). (H0:            |                             |
|                            | `dao/jing/content/ async.cljc`, `dao/stream/udp.cljc` and `dao/space/schema.cljc` are not in   |                             |
|                            | the closure and are dropped from list 1; the deferred design records them, §9.) Every           |                             |
|                            | remaining closure file whose arms are only `catch`/hint/protocol syntax is **not** on list 1   |                             |
|                            | (exempt by pattern).                                                                            |                             |
+----------------------------+-------------------------------------------------------------------------------------------------+-----------------------------+

**Baseline versus additions and deletions.** The H0 baseline is the set of
**existing** files above (every entry except the planned additions named
in the profile row). The planned additions are the seven `dao/host/*.cljc`
files (five in H1, two in H3), which join list 1 when their slices land. The deletions are
three: `yin/vm/integer/host.cljc` (H2), `dao/jing/cbor/boring.cljc` (H4, the
JVM/JS Boring wrapper) and `src/cljd/dao/jing/cbor/cljd.cljd` (H4, the Dart
Jing writer, a lane-specific file that the seams row excludes and the first
row lists, so that the lane-specific category cannot silently retain it).
The final list 1 after H4 is therefore: the baseline minus the first row
(except `sign.cljc` for `generate`), both deleted Jing writers included in
that subtraction, plus the seven profile files. The host-library gate of
§8.2 is consistent with it.

**Reconciliation with H0's measurement (r7, V).** H0 generated list 1 as
76 files: 60 matching the rows above after the corrections now in them,
plus 16 tagged `:h0-unlisted`. The 16 are now categorised: nine in the
first row (profile consumers, H2), five in the "dialect arms" row
(retained), one (`ringbuffer.cljc`) in the seams row, one (`rpc.cljc`) in
the deferred row. **Two vocabularies, kept distinct (R7-4)**: a **closure
class** (the `:classes` set on each file in the committed edn, generated
from the `class-rows` data in `closure.clj:112`, V) answers "what host
contact does this file have" per §3.1; a **gate category** (the
`list-1-categories` of `gate_test.clj:444`, V) answers "why is this file
permitted a non-exempt arm" per §8.1. The mapping is many-to-one and the
gate test asserts it. **What H0 must change**: edit the classification
**data** (the `class-rows` of `closure.clj`, which the worktree already
extends with `:profile-consumer` and `:dialect-arm`, and the gate's list 1
categories) so that no entry is `:h0-unlisted`, then regenerate the edn
outputs; drop nothing (the six names H0 could not find were never on the
generated list, and the rows above no longer name them). **What H0 must
not change**: the traversal algorithm and its resolution rule, the totals
142/140/141 and 133 shared, the independent-traversal agreement, the two
negative tests' semantics, the scan-failure test and the corpus hash set;
the lanes need not be re-run beyond `dao.host.gate-test` and the tool's
`--check`. **How H1 and H2 clear
the tag**: H1 adds no consumer, so the nine stay tagged `:migration-source`;
H2 migrates them with the other consumers and removes **eight** of them
from list 1 in the same commit, which the `list-1-equals-the-observed-arms`
test enforces. The ninth, `yin/vm.cljc`, is **exempt pending OQ11**: after
H2 its one-arity `bytes->str` is portable, but the JVM-only two-arity
encoding form (`vm.cljc:404-406`, V) keeps one reader arm until the owner
decides OQ11, so the file stays on list 1 with category
`:migration-source` and the slice value `:pending` (reason "OQ11"), which
the gate's `:clears` vocabulary gains in H0's retag (review H0-2: `"H2"`
means removal, so a conditionally retained arm needs its own value). When
OQ11 is decided, the entry becomes `"H2"` (drop or port, the arm goes) or
`:stays` (keep JVM-only).

**The category-to-class mapping, stated (R7-4, H0-3).** The gate today
checks only that each list-1 category exists, that its slice value is
permitted, and, weakly, that the collapsed class set is not `#{:none}`
(V `gate_test.clj:706-714`). H0's retag strengthens it to: for every
list-1 file, on **every lane whose closure contains it**, the file's class
set is non-empty and does not contain `:none` (per-lane sets, never
collapsed across lanes), and intersects the classes its category permits,
**or** carries the one class the bounded-exception table below admits for
that file in that category:

+--------------------+-------------------------------------------------------------------------------------+
| Gate category      | Permitted closure classes                                                           |
+====================+=====================================================================================+
| `:migration-source | `:profile-internal-pure`, `:profile-hash-sign`, `:consumer`, `:profile-consumer`    |
| `                  |                                                                                     |
+--------------------+-------------------------------------------------------------------------------------+
| `:profile`         | `:profile-internal-pure`                                                            |
+--------------------+-------------------------------------------------------------------------------------+
| `:runtime-prereq`  | `:runtime-prereq`, `:internal-pure-display`, `:deferred-storage` (the promise       |
|                    | facade file)                                                                        |
+--------------------+-------------------------------------------------------------------------------------+
| `:codec-track`     | `:codec-track`                                                                      |
+--------------------+-------------------------------------------------------------------------------------+
| `:dialect-arm`     | `:dialect-arm`                                                                      |
+--------------------+-------------------------------------------------------------------------------------+
| `:seam-driver`     | `:external-stream-seam`, `:driver`                                                  |
+--------------------+-------------------------------------------------------------------------------------+
| `:deferred-scope`  | `:deferred-storage`, `:deferred-entropy`, `:deferred-timers`, `:deferred-stage`,    |
|                    | `:deferred-gensym`                                                                  |
+--------------------+-------------------------------------------------------------------------------------+

Two placements from the r8 code review (H0-1, H0-2): `dao/stream/
ringbuffer.cljc` is `:seam-driver` with `:stays` (its closure class
`:external-stream-seam`), not `:dialect-arm`; `yin/vm.cljc` is
`:migration-source` with `:pending` as above.

Host number-model arms (r11 H2 finding, retained): `debruijn.cljc` and
`debruijn_code.cljc` remain on list 1 under `:migration-source` with `:stays`
(encoding which number classes a host carries; `cbor.cljc:217,227` joins after H4).
H2b moves `data.cljc` and `values.cljc` off list 1.

**Bounded exceptions to the mapping (r10, H0-R3 reconciliation).** The
file rows above and the §3.1 closure classes disagree for eight files: the
rows place them by *why the arm is permitted* (the deferred row takes the
`locking` files; the seams row takes every lane-specific file), while §3.1
classifies them by *what host contact they have*. Both are kept as
written, and the disagreement is closed by naming each case, bound to its
file **and** its category **and** its class. An exception admits exactly
that one class for that one file **only while the file is in that
category**; it widens no other category, no other file and no other class.
The gate enforces this table as data: a file in the table whose closure
class the bare mapping already admits (the exception is no longer needed),
or a file whose list-1 category is not the one named here, is an H0
finding, so the table cannot go stale silently. Adding a row here is a
design amendment, not a gate edit.

+------------------------------------------+-------------------+--------------------+------------------------------------------------------+
| File                                     | List-1 category   | Admitted class     | Why both are right                                   |
+==========================================+===================+====================+======================================================+
| `src/cljc/dao/space/transactor.cljc`     | `:deferred-scope` | `:runtime-prereq`  | Deferred row for `locking` (§9.1 scheduling); §3.1   |
|                                          |                   |                    | table C classes the lock as a retained runtime       |
|                                          |                   |                    | prerequisite.                                        |
+------------------------------------------+-------------------+--------------------+------------------------------------------------------+
| `src/cljc/yin/vm/ucf/authority.cljc`     | `:deferred-scope` | `:runtime-prereq`  | Same as `transactor.cljc` (`locking`).               |
+------------------------------------------+-------------------+--------------------+------------------------------------------------------+
| `src/clj/dao/jing/content/driver.clj`    | `:seam-driver`    | `:deferred-timers` | Seams row takes every lane-specific file; its arms   |
|                                          |                   |                    | are clock/timer calls deferred under §9.1.           |
+------------------------------------------+-------------------+--------------------+------------------------------------------------------+
| `src/clj/yang/antlr/cst.clj`             | `:seam-driver`    | `:deferred-stage`  | Seams row takes every lane-specific file; the file   |
|                                          |                   |                    | is the parser stage deferred under §9.3.             |
+------------------------------------------+-------------------+--------------------+------------------------------------------------------+
| `src/cljs/yang/antlr/cst.cljs`           | `:seam-driver`    | `:deferred-stage`  | Same as `cst.clj`.                                   |
+------------------------------------------+-------------------+--------------------+------------------------------------------------------+
| `src/cljd/yang/antlr/cst.cljd`           | `:seam-driver`    | `:deferred-stage`  | Same as `cst.clj`.                                   |
+------------------------------------------+-------------------+--------------------+------------------------------------------------------+
| `src/cljd/dao/stream/cbor/cljd.cljd`     | `:seam-driver`    | `:codec-track`     | Seams row takes every lane-specific file; the file   |
|                                          |                   |                    | is the Dart codec shadow owned by the codec track    |
|                                          |                   |                    | (§8.3). Not a Jing writer: the H4 deletion of        |
|                                          |                   |                    | `src/cljd/dao/jing/cbor/cljd.cljd` is unaffected.    |
+------------------------------------------+-------------------+--------------------+------------------------------------------------------+
| `src/cljd/dao/stream/transit/cljd.cljd`  | `:seam-driver`    | `:codec-track`     | Same as `stream/cbor/cljd.cljd`.                     |
+------------------------------------------+-------------------+--------------------+------------------------------------------------------+

Eight files, twelve lane rows (the two `locking` files sit in all three
lane closures). What this table does **not** do: it does not add
`:runtime-prereq` to the deferred row's classes, nor any deferred or codec
class to the seams row (an unrestricted "any class for a lane-specific
file" exemption is refused; the Dart Jing writer stays excluded from seams
and on its H4 deletion path). The deferred design (§9) and the codec track
(§8.3) each still own the files their class names, regardless of the
list-1 row that permits the arm.
The tool lives at `src/dev/host_boundary/closure.clj` (H0's justified
deviation: the repo keeps dev tools under `src/dev/`), and the design's
earlier `dev/host_boundary/` path is read as that.

### 8.2 The host-library gate (H2, H3): executable references within the closure

A literal `grep` over `src/cljc` is **not** the gate: it matches a docstring
in the untouched `dao/data.cljc:54` ("a BigInt/BigDecimal", V) and the
out-of-scope `dao/postgraphics/lowering.cljc:587` (`:rgba8unorm`, V), so
its asserted result would be false. The gate is instead `dao.host.gate-test`
run in **library mode**, which **lands with H2**, not H0: H0 is a freeze
with no source change and the library mode has no consumer before H2's
deletion, so building it in H0 would add a deliverable nothing exercises
and nothing can falsify; H2 builds it with the H2 name list and its two
negative tests (below), and H3 extends the name list. In library mode the
gate takes the H0 closure tool's tagged file list
for the adopted closure (so out-of-scope files never count), reads each
file with the Clojure reader per lane feature (so comments and string
literals, docstrings included, never count), and collects symbols, class
names and import/require forms; a hit is any such symbol naming
`BigInteger`, `BigDecimal`, `clojure.lang.Ratio`, `java.text`, `unorm`
(H2) or `MessageDigest`, `Blake3`, `noble`, `blake3_dart`, `package:crypto`,
`ed25519_edwards`, `goog.crypt.base64` (H3) in a file outside `dao/host/`
and, for H3's Ed25519 names, outside `yin/vm/linker/sign.cljc` (`generate`,
deferred). Codec-track files stay on list 1 and are not scanned by this
mode (their arms are the codec track's). The gate fails distinctly when the
closure list is empty or a file cannot be read, which is a scan error, not
"no hits".

### 8.3 File-disjointness from the codec track, including tests (re-checked, V)

Codec-track source files (`codec.md` §7.1): `dao/stream/ws.cljc`,
`transit.cljc`, `cbor.cljc`, `cbor/boring.cljc`, `codec.cljc`,
`remote_channel.cljc`, `jing/stream.cljc`, `data.cljc`, the four ws adapters,
`yin/repl/{serve,connect}.cljc`. Codec-track test namespaces (`codec.md`
§7.2): the 22 requiring Transit, including `ws_test`, `ws-codec-test`,
`remote-route-test`, `loopback-net`, `transit-test`, `dao.stream.cbor-test`,
`dao.jing.stream-test`, `yin.repl.main-test`.

H0-H4 source files: `dao/host/*` (new), `yin/vm/integer/host.cljc`,
`dao/jing/cbor.cljc`, `dao/jing/cbor/boring.cljc`, `src/cljd/dao/jing/cbor/cljd.cljd`,
`dao/jing.cljc`, `yin/vm/linker/sign.cljc`, `yin/vm/debruijn.cljc`,
`yin/vm/debruijn_code.cljc`, `yin/vm/data.cljc`, `yin/vm/values.cljc`,
`yin/vm.cljc`, `yin/vm/engine.cljc`, `yin/vm/integer.cljc`,
`dao/stream/chunks.cljc`, `dao/stream/base64.cljc`, `dao/jing/dht.cljc`,
`yin/vm/linker.cljc`, `dao/stream/datagram.cljc`, `dao/data/arrays.cljc`;
and, from H0's finding (H2): `dao/space/index.cljc`, `yin/repl/query.cljc`,
`yin/vm/linker/closure.cljc`, `yin/vm/ucf/rows.cljc`, `yang/php.cljc`,
`yin/vm/debruijn_register_code.cljc`, `yin/vm/debruijn_register_effects.cljc`,
`yang/antlr/packet.cljc` (none is a codec-track file, V).
H0-H4 test files: `dao/host/*_test` (new), `yin/vm/integer_test`,
`yang/python/antlr/{int_heap,int_literal,int_ops}_test`, `dao/jing/cbor_*`,
`dao/jing_test`, `yin/vm/linker/sign_test`, `dao/stream/base64_test`,
`yin/vm/debruijn_code_test`, `yin/vm/data_test`, `dao/data/btree_*`.

Intersection: none, with two recorded deferrals: `dao/stream/codec.cljc:45`
(`host-bytes?` over `dao.host.bytes`) after C4, and `dao/stream/cbor/boring.cljc`
(the transport writer) is **not** touched by H4. The `dao.stream.cbor-test`
and `dao.jing.cbor_test` namespaces are distinct files. The deferred H5
(§9.1) would touch `ws.cljc:122`, `ws_test.cljc:11` and
`remote_route_test.cljc:24` (V: identity-less ring creation), which is why
it is deferred behind the codec track as well as behind its own design.

Coordination with a host-lane probe track: its "narrow source adaptations"
(R: the spike touched `integer/host.cljc`, `jing.cljc`, `cbor.cljc`,
`cbor/boring.cljc`) are exactly the H1-H4 files; under OQ3 that work is
paused until H4 lands and needs file-level coordination, not just a rebase.

## 9. DEFERRED scope (not commissionable under this document)

The following keep their design intent from r2 but are **not commissionable
here**. They belong to a separate design, proposed name
`docs/design/dao.stream.host-effects.md`, with its own non-Claude review.
Nothing below is decided; the "required inputs" are the findings a
successor must answer before slices exist. Slice ids H5-H8 are reserved so
no other document's citation moves.

### 9.1 Entropy, identities, scheduling, ambient facts, printing (reserved H5)

Intent: entropy as data the driver deposits (events in); identities derived
from drawn bytes and carried in creation specs; the driver owns pumping and
wake-up; ambient facts and the one core `println` as data; `gensym` with
hygienic naming. Required inputs (from the r2 review R2, R6 and §3.2):

- exclusive allocation and resumable consumption of an entropy stream, given
  that cursors are non-destructive (`dao/stream.cljc:159-173`) and
  independent readers could reuse bytes; partial batches and loss; blocked
  resumption;
- bootstrap identities: the entropy stream itself needs an identity before
  any byte is drawn;
- preserved UUID spelling: `ucf/custody.cljc:50-56` requires the
  8-4-4-4-12 lowercase form, so a derived id must keep that grammar;
- DHT initialization draws several values synchronously
  (`space/dht.cljc:255-269`, R): failure and continuation behaviour;
- async completions emitted as data: moving consumer callbacks into a
  `step!` does not remove them (R2); the `btree.storage` promise facade and
  `content.async` consumers need a data-shaped completion;
- the JVM blocking content driver selected at `coordinate.cljc:80`
  (`driver.clj:169-206`: clock and sleep) is lane-specific IO inside a
  portable composition and needs its own disposition;
- the codec-track test edits `ws_test.cljc:11` and `remote_route_test.cljc:24`
  and the `ws.cljc:122` site: staged compatibility or an explicit dependency
  on C4; a re-estimate of the slice (the r2 estimate of about twenty files
  was understated: UCF custody and holder migrations are included);
- `gensym` hygiene: compiler counters need hygienic naming, not a reset;
  `transact.cljc:35` tempids need separate treatment;
- OQ9 (entropy shape: stream versus identity-in-spec with driver-minted
  secrets; both avoid core generators, the r2 recommendation overclaimed)
  and OQ10 (compiler `gensym`) move to that design;
- the five `random-uuid` sites outside the transitive closure
  (`dao/await.cljc:235,240`, `yin/vm/ffi/remote_serve.cljc:417,604,606`,
  V, H0) and the out-of-closure `dao/jing/content/async.cljc` timer and
  `dao/stream/udp.cljc` arms: the deferred design decides whether its scope
  is the transitive closure (then they are out until required) or the
  directory scope of OQ1 (then they are in).

### 9.2 The durable-storage seam (reserved H6)

Intent: one seam beneath the journal with enumerated lower operations; the
claim-file algorithm portable; capability map (`lock-kind`,
`directory-sync?`, `failure-model`). Required inputs (R5, A7): the atomic
replacement at `store/fs.cljc:352-387` renames **before** the directory
sync (`:364-370`, `:382-383`, V), so a sync failure can leave the new HEAD
visible; the seam must distinguish pre-rename failure (target unchanged),
post-rename visibility (new HEAD visible, durability uncertain) and sync
uncertainty, and test post-rename failure explicitly; short reads, partial
appends, acquisition outcomes (`held`/`refused`/`unsupported`), durability
outcomes per the declared failure model; `own-pid`, `process-alive?`,
`create-exclusive!`, `unlink!`, `canonical-path` for the claim-file lock.

### 9.3 The parser stage (reserved H7)

Intent: the grammar profile plus host walk as one seam value; a
remote-stage test. Required input: none beyond the stage's own host walk;
r3's "one `println`" at `yang/python/antlr/repl.cljc:71` was inside a
quoted yin program (the VM's primitive, not a host call, V) and is
withdrawn.

### 9.4 Stream-implementation conformance and the wasm import gate (reserved H8)

Intent: the contract's outcome tables through a scripted seam against every
implementation; a gate asserting a wasm binary's import list against the
seven names. Required input: which implementations declare which outcome
subset (`dao.stream.md`, Surfaces).

## 10. Cost and what would reverse this (H0-H4)

Cost (P, from §1 and §8.3, revised r7 for H0's finding): **24 existing
files edited** (the 16 of r5 plus the eight profile consumers H0 found:
`dao/space/index.cljc`, `yin/repl/query.cljc`, `yin/vm/linker/closure.cljc`,
`yin/vm/ucf/rows.cljc`, `yang/php.cljc`, `yin/vm/debruijn_register_code.cljc`,
`yin/vm/debruijn_register_effects.cljc`, `yang/antlr/packet.cljc`; each a
require plus one arm, so `dao.space.index` and the UCF rows file are no
longer "untouched") and **3 deleted**
(`yin/vm/integer/host.cljc`, `dao/jing/cbor/boring.cljc`,
`src/cljd/dao/jing/cbor/cljd.cljd`), **7** `dao.host.*` files added (five in
H1, two in H3); **at least 5** test namespaces added (`gate-test` in H0,
`conformance-test`, `bytes-test`, `text-test` in H1, `hash-test` in H3),
the 3 Python integer tests and `integer_test` re-pointed; about 10^3 tests
rerun per lane (R: JVM quick tier about 4,000); about 10^1 rewritten.
Base64 and the Jing writer are replacements, not code motion, and are
accepted only by their byte oracles. No performance figure is claimed; H4
measures.

Risk, revised (reviews N4 and R7-2): the laws of §2.4 and table A are
**not verbatim extraction** in **four** places, and each is a named
behaviour change with a direct test (table D): (1) `acopy` prevalidation on
JS and Dart (H2); (2) strict `utf8-encode` refusal where today's helpers
substitute (H1 law, H3 consumers); (3) `bytes->str` becomes strict UTF-8 on
every host (H2): today JS and Dart build one char per byte and the JVM
decodes UTF-8 **with U+FFFD substitution for malformed input** (V
`vm.cljc:404-408`; `String.` with a charset replaces malformed sequences),
so non-ASCII bytes change on JS and Dart and malformed bytes change on all
three; (4) PHP integer literals above 2^53 become exact on JS (H2). The
`aset` return value is kept as Clojure's: unchanged, stated only so the law
cannot be read as changing it. H1 medium: new namespaces plus two new laws,
two table B functions and their tests.
H2 medium: seventeen consumers (nine of r5 plus the eight H0 found, two of
them with named behaviour changes, §3.1), the Jing carrier move, the arrays
prevalidation change and the M-caller audit in one slice; mitigated by the corpus, the
pre-deletion closure-tool gate and splitting the slice per namespace if the report
shows drift. H3 low-to-medium: the UTF-8 behaviour change is observable only
on unpaired-surrogate strings, which the codec already refuses. H4
low-to-medium: the corpus decides; performance unknown until measured. The
ClojureDart traps are named in §14.

Reversal: an owner ruling that reader arms are the intended port mechanism;
a measured H4 regression that matters (the portable writer stays as oracle
and a host supplies a faster one behind the same profile); owner rejection
of §12 (then §2 stands as this document's reading of the existing ABI text).

## 11. Decision record

+------+------------------------------------------------------------------------------------------------------------------+------------------------------+
| Id   | Decision                                                                                                         | Status                       |
+======+==================================================================================================================+==============================+
| HD1  | The governing test: dao.stream wherever the contact can be an IO action with respect to the world (§2.1-2.3); an | Owner rule ("test 2          |
|      | internal pure, total, synchronous function, or a declared mutation of an owned argument, may be a direct call.   | governs"); r3 wording needs  |
|      |                                                                                                                  | non-Claude sign-off          |
+------+------------------------------------------------------------------------------------------------------------------+------------------------------+
| HD2  | Internal contacts form the primitive profile: 76 entries (§4.2 table A) with kinds P/M and the §2.4 ownership    | Architect r3; needs sign-off |
|      | laws, plus runtime prerequisites (table C); in-instance for wasm (§7).                                           |                              |
+------+------------------------------------------------------------------------------------------------------------------+------------------------------+
| HD3  | Commissionable scope is §2 plus H0-H4; entropy, scheduling, storage, parser stage and stream conformance are     | Owner, 2026-10-20 ("yes,     |
|      | deferred to a separate design with the §9 required inputs.                                                       | proceed with the split")     |
+------+------------------------------------------------------------------------------------------------------------------+------------------------------+
| HD4  | H0 gates both reader arms and resolved effect calls over the committed per-lane closure; list 1 names retained   | Architect r3                 |
|      | arms; H2's deletion is gated by the closure tool reading `ns` forms: the shim required by no file before, absent |                              |
|      | and required by no file after.                                                                                   |                              |
+------+------------------------------------------------------------------------------------------------------------------+------------------------------+
| HD5  | H0-H4 are file-disjoint from the codec track in source and tests, with one deferred one-line edit                | Architect r3                 |
|      | (`codec.cljc:45`); host-lane source adaptations in H1-H4 files are paused (OQ3) and need file-level              |                              |
|      | coordination.                                                                                                    |                              |
+------+------------------------------------------------------------------------------------------------------------------+------------------------------+

### 11.1 Owner decisions recorded (2026-10-20)

The owner adopted all six r1 recommendations: "agree with all of fable's
recommendations"; for OQ5 separately: "makes sense. i agree with fable's
recommendation"; for the r3 split: "yes, proceed with the split".

+------+------------------------------------------------------------------------------------------------------------------+------------------------------+
| Id   | Decision as adopted                                                                                              | Status                       |
+======+==================================================================================================================+==============================+
| OQ1  | Scope is the `yin.repl` closure; GUI, graphics and demo namespaces excluded.                                     | Owner, 2026-10-20            |
+------+------------------------------------------------------------------------------------------------------------------+------------------------------+
| OQ2  | The profile family is `dao.host.*`; `dao.data.arrays` stays in place as a profile member by reference.           | Owner, 2026-10-20            |
+------+------------------------------------------------------------------------------------------------------------------+------------------------------+
| OQ3  | Arm-adding work in the files H1-H3 touch is paused until phase A lands; boundary-agnostic probes proceed.        | Owner, 2026-10-20            |
+------+------------------------------------------------------------------------------------------------------------------+------------------------------+
| OQ4  | Boring and the Dart `cbor` package are dropped from the Jing content path only; the transport codec keeps them.  | Owner, 2026-10-20            |
+------+------------------------------------------------------------------------------------------------------------------+------------------------------+
| OQ5  | Storage is a seam beneath the journal, not a streams-all-the-way-down redesign (now deferred, §9.2).             | Owner, 2026-10-20            |
+------+------------------------------------------------------------------------------------------------------------------+------------------------------+
| OQ6  | This track has its own orchestrator seat, independent of the codec track and any host-lane track.                | Owner, 2026-10-20            |
+------+------------------------------------------------------------------------------------------------------------------+------------------------------+
| Spli | Commissionable scope is the rule plus H0-H4; the rest is deferred to a separate design.                          | Owner, 2026-10-20            |
| t    |                                                                                                                  |                              |
+------+------------------------------------------------------------------------------------------------------------------+------------------------------+

## 12. OWNER PROPOSAL: amendment to datom.world.abi.md (not doctrine; not applied)

This section is a proposal for the owner's approval (OQ8). It is **not**
verbatim doctrine and nothing in `datom.world.abi.md` or `datom.world.md`
has been edited. The owner's quoted words are marked as such; the rest is
the Architect's proposed wording, to be revised by the owner.

**12.1 Proposed new §8 of `datom.world.abi.md`, "The governing test".**

> Owner ruling, 2026-10-20 (verbatim): "when the host boundary can be an IO
> monad then dao.stream is used. If it cannot be a monad than dao.stream
> doesnt have to be used"; and, asked whether same-host-ness or the
> IO-action test governs: "yes, test 2 governs".
>
> Proposed wording. The machine is the running instance and the runtime
> state it owns; the world is everything else. A host contact uses
> `dao.stream` when it can be an IO action with respect to the world: its
> result depends on world state; it changes world state; equal calls may
> answer differently because of the world; its answer is not available at
> return; whether it completes depends on the world; or it runs in another
> process, host or trust domain. A same-host effect (a file read, a clock
> reading, entropy) is an IO action and so a stream.
>
> A contact that is a pure, total, synchronous function of the machine's
> values, or a declared mutation of a value the caller owns, may be a direct
> call. Such contacts are enumerated in a host primitive profile with an
> oracle per entry ([dao.stream.host-boundary.md](./dao.stream.host-boundary.md)
> §4.2), execute in-instance (for wasm: compiled in, never imported), and
> add no contract to `dao.stream`. Reads of owned runtime state (a weak
> reference, an atom) are neither IO nor profile entries; they are the
> dialect's runtime prerequisites. Linking a library to compute a profile
> entry is internal linkage (§5).
>
> §7's two non-adapters are read under this test: a map of functions that
> portable application code calls to reach the world is a host service and
> is rejected; a seam a stream implementation calls privately to perform its
> own IO is that implementation's adapter and is not.

**12.2 Proposed change to `datom.world.md:70`.**

Today: "A host boundary is the dao.stream abstraction boundary;
implementations do not add contracts."

Proposed: "A host boundary that can be an IO action is the dao.stream
abstraction boundary; implementations do not add contracts. A pure, total,
synchronous host function is a primitive-profile entry, not a boundary
([datom.world.abi.md](./datom.world.abi.md) §8)."

## 13. Open owner decisions

+------+------------------------------------------------------------------------------------------------------+----------------------------------------------------+
| Id   | Question                                                                                             | Architect's recommendation (not a decision)        |
+======+======================================================================================================+====================================================+
| OQ7  | H4's measurement: what regression, if any, is acceptable for the portable Jing writer against Boring | Accept any regression under corpus identity; set a |
|      | on the JVM and Node.                                                                                 | bound after H4's numbers exist, not before.        |
+------+------------------------------------------------------------------------------------------------------+----------------------------------------------------+
| OQ8  | Approve the §12 proposal (the ABI's new §8 and the one-sentence change to `datom.world.md:70`).      | Approve after the owner revises the proposed       |
|      |                                                                                                      | wording; §2 stands either way as this document's   |
|      |                                                                                                      | reading.                                           |
+------+------------------------------------------------------------------------------------------------------+----------------------------------------------------+
| OQ11 | The public encoding arity of the yin primitive `bytes->str` (`yin/vm.cljc:403-409`, V): today a      | Make it portable: accept `"UTF-8"` (any case)      |
|      | JVM-only two-arity `[b enc]` that decodes with any Java charset; JS and Dart have the one-arity      | through `utf8-decode`, answer a qualified refusal  |
|      | only, so a guest program using it runs on one host. Remove it, keep it JVM-only, or make it portable | for any other name on every host. Evidence: no     |
|      | with UTF-8 the only accepted name? Not decided here (review R7-2: absence of repository callers does | caller in `src` or `test` (V, grep); the macro     |
|      | not establish compatibility for a public primitive). Until decided, H2 preserves the arity as it is  | prelude excludes it (`macro.cljc:666`, V); a       |
|      | on the JVM, behind a retained arm on list 1.                                                         | one-host primitive contradicts the universal AST.  |
+------+------------------------------------------------------------------------------------------------------+----------------------------------------------------+

OQ1-OQ6 are closed (§11.1). OQ9 and OQ10 belong to the deferred design
(§9.1). OQ11 does not block H1; H2 preserves the arity until it is decided.

## 14. For a successor orchestrator

Decided (HD1-HD5, OQ1-OQ6, the split): the rule, the exact profile with
ownership laws, the H0-H4 sequence and gates, the deferral. Open: OQ7, OQ8,
OQ11; none blocks H0 or H1; OQ7 is needed before H4; OQ11 before H2 may
drop or port the encoding arity (until then H2 preserves it and
`yin/vm.cljc` stays on list 1 as `:pending`). **What H1 must build beyond
r7**: `dao.host.float` with the extracted float-text kernel (table B,
R7-1: `decimal->float`, `float-digits`, `ratio->float` and their helpers,
copied from `yin.vm.integer` and tested directly against the existing
fixture rows), `dao.host.text/code-points`, `decimal-text->float`, and
their tests (table D); the profile count stays 76. **A non-Claude reviewer
must confirm r9 before H1 is dispatched.**

Start with **H0**: it changes no source; commit the closure tool
(`src/dev/host_boundary/closure.clj`, over `clojure.tools.namespace`), its
per-lane output under `docs/design/host-boundary/` tagged by class, and the
gate test; confirm the 142/140/141 totals against an independent traversal
(done, H0 report) and finish the r7 reconciliation of list 1 (§8.1: retag
the 16 `:h0-unlisted` entries, a data change only) before H1; a non-Claude
reviewer confirms r7 and the retagged H0 before H1 is dispatched. Then H1 (new files,
their direct tests, and the two new laws: strict `utf8-encode` and M-entry
prevalidation). H2 is the first deletion; its gate is the closure tool
reading `ns` forms (the shim required by no file before the deletion commit,
absent and required by no file after), and it carries the
`acopy` prevalidation change and the M-caller audit (§2.4). H3 carries the
named UTF-8 behaviour change for its two consumers. H3 and H4 in either
order; slow tier once after H4. Do not start anything in §9; a separate
design document and its own non-Claude sign-off come first.

Lanes and gates (`docs/agents/build-n-test.md`): iterate on the JVM lane
with `bb test:sub:clj dao.jing` or `yin.vm` (G0); three-lane subsystem test
at each checkpoint (G1); `bb test:changed` across all three lanes in the
worktree before landing (G2); `bb test` on master after merge (G3). Record
per-lane counts before and after every slice. The Dart lane needs `flutter`
on PATH; a fresh worktree needs `npm ci`. ClojureDart traps for exactly this
kind of file: `:cljd` first in every mixed conditional; a tail-position
`:cljd nil` silently fails; protocol method parameters never `[_ _]`;
`(- 0.0)` is `+0.0`; multi-key `assoc` on nil misbehaves.

Review requirement: this design and any implementation an orchestrator
derives from it under a Claude model are Claude-authored. **Sign-off on this
final text (§2 plus H0-H4) and on each slice must come from a non-Claude
architect**; a Claude reviewer may review but may not be the sole sign-off.
The reviewer checks, in order: that every entry in table A is internal and
pure or declared mutating under §2.4, with the freeze rule's enforceable
obligations (i)-(iv) each backed by a test or an audited list; that H0's
committed closure output matches 142/140/141 and list 1 matches the §8.1
baseline by category; that the two negative tests and the scan-failure test
fail as intended; that H2's closure-tool gates hold before and after the deletion
commit and the no-partial-write tests exist; that H1-H4 acceptance is
byte-identical against the named corpus with errata preserved and the four
behaviour changes are named in slice reports with their table D tests; that
the `bytes->str` encoding arity is preserved until OQ11 is decided; that
the index comparator keeps signed zero and NaN order (R7-3); that no H0-H4 edit touches a
codec-track source or test file except the recorded `codec.cljc:45` deferral;
that nothing from §9 is started.

## Appendix A. Citations opened for this document (2026-10-20, read only)

+--------------------------------------------------------------+--------------------------------------------------------------------------------------------------+
| Cite                                                         | Finding                                                                                          |
+==============================================================+==================================================================================================+
| `docs/design/datom.world.md:16-32,43-48,68-71`               | Axioms; side effects as emissions; the sentence §12.2 proposes to change.                        |
+--------------------------------------------------------------+--------------------------------------------------------------------------------------------------+
| `docs/design/datom.world.abi.md:21-24,35-44,47-52,56-64,`    | One ABI; custody; machine-internal work in-instance; the floor and internal linkage; effects     |
| `75-87,94-148`                                               | out, events in; adapter discipline; the two non-adapters.                                        |
+--------------------------------------------------------------+--------------------------------------------------------------------------------------------------+
| `docs/design/dao.stream.wasm.md:29-52`                       | Seven imports; primitives have no imports.                                                       |
+--------------------------------------------------------------+--------------------------------------------------------------------------------------------------+
| `docs/design/dao.stream.codec.md:140-180,658-780`            | Contract namespace; slice file set; the 22 test namespaces (§8.3).                               |
+--------------------------------------------------------------+--------------------------------------------------------------------------------------------------+
| `src/cljc/yin/vm/integer/host.cljc:1-298`                    | 25 public functions; "depends on nothing".                                                       |
+--------------------------------------------------------------+--------------------------------------------------------------------------------------------------+
| `src/cljc/yin/vm/integer.cljc:1-101`; `yin/vm.cljc:32,929`;  | Consumers of the shim (requires and `big-carrier?` sites).                                       |
| `yin/vm/engine.cljc:38,810`; `yin/vm/values.cljc:30,196`;    |                                                                                                  |
| `yin/vm/data.cljc:41,421,547`; `test/yang/python/antlr/`     |                                                                                                  |
| `{int_heap,int_literal,int_ops}_test.cljc`                   |                                                                                                  |
+--------------------------------------------------------------+--------------------------------------------------------------------------------------------------+
| `src/cljc/dao/jing/cbor.cljc:1-60,95-254,342-460,483-632,`   | Reader; big-integer helpers; carriers with equality and hash; JVM Decimal/Ratio construction and |
| `733-784,1070,1177`                                          | access; byte-ordered sorting.                                                                    |
+--------------------------------------------------------------+--------------------------------------------------------------------------------------------------+
| `src/cljc/yin/vm/debruijn_code.cljc:228-245,310-317`         | Float64 little-endian hex; JVM big-integer predicate (assigned to H2).                           |
+--------------------------------------------------------------+--------------------------------------------------------------------------------------------------+
| `src/cljc/dao/data/btree.cljc:36-44,87-119,182-245`;         | Reference-mode default `:soft` on the JVM; atomic flag; weak/soft references; 12 array           |
| `src/cljc/dao/data/arrays.cljc:28-182`                       | functions.                                                                                       |
+--------------------------------------------------------------+--------------------------------------------------------------------------------------------------+
| `src/cljc/yin/vm/linker/sign.cljc:1-130`                     | Libraries; `jvm-pair` deterministic seed replay (`:75-87`); `generate` fresh entropy             |
|                                                              | (`:114-124`).                                                                                    |
+--------------------------------------------------------------+--------------------------------------------------------------------------------------------------+
| `src/cljc/dao/jing/coordinate.cljc:63-82`;                   | JVM selection of the blocking driver; clock and sleep in the driver.                             |
| `src/clj/dao/jing/content/driver.clj:160-209`                |                                                                                                  |
+--------------------------------------------------------------+--------------------------------------------------------------------------------------------------+
| `src/cljc/dao/space/store/fs.cljc:84-261,340-387`            | Claim-file lock; atomic replace renames before directory sync (§9.2 input).                      |
+--------------------------------------------------------------+--------------------------------------------------------------------------------------------------+
| `src/cljc/yin/vm/ucf/custody.cljc:40-56`                     | UUID grammar for occurrence ids (§9.1 input).                                                    |
+--------------------------------------------------------------+--------------------------------------------------------------------------------------------------+
| Regex: `custody.cljc:53`, `datagram.cljc:80-130`,            | ASCII grammars; dialect semantics.                                                               |
| `jing.cljc:107-111,216-221`, `fs.cljc:105`                   |                                                                                                  |
+--------------------------------------------------------------+--------------------------------------------------------------------------------------------------+
| `test/dao/stream/ws_test.cljc:11`,                           | Identity-less ring creation in codec-track tests (§8.3, §9.1).                                   |
| `test/dao/stream/remote_route_test.cljc:24`                  |                                                                                                  |
+--------------------------------------------------------------+--------------------------------------------------------------------------------------------------+
| `src/cljc/dao/postgraphics/terminal.cljc:14`                 | The 27th `random-uuid`, out of scope.                                                            |
+--------------------------------------------------------------+--------------------------------------------------------------------------------------------------+
| Closure traversals (R), three lanes: the r3 script           | 142/140/141 files, 133 shared, by file after the r3 script's `#?@` parse defect was found; the   |
| (superseded) and the r3 reviewer's independent traversal     | lane-only members and the per-file markers of §3.1.                                              |
+--------------------------------------------------------------+--------------------------------------------------------------------------------------------------+
| `rg '^\(deftest'` over the table D files                     | Deftest names and counts: integer 25, sign 4, base64 3, data 24, hash registry 15, cbor fixtures |
|                                                              | 14, cbor conformance 14, int contract 12, jing 39, cbor 21, file 17, debruijn 80, debruijn code  |
|                                                              | 26.                                                                                              |
+--------------------------------------------------------------+--------------------------------------------------------------------------------------------------+
| `test/resources/dao/jing/{cbor-v1.json,cbor-v1.errata.md,`   | Corpus and errata; digest table; sign vectors; integer fixtures; CPython int contract.           |
| `digest-table.edn}`; `test/yin/vm/linker/sign_vectors.edn`;  |                                                                                                  |
| `test/yin/vm/integer_v3_fixtures.cljc`;                      |                                                                                                  |
| `test/resources/yang/python/int-contract-v1.txt`             |                                                                                                  |
+--------------------------------------------------------------+--------------------------------------------------------------------------------------------------+
| `git log` (`8351e639`, `eacaed90`); `docs/orchestrator-log.` | Codec C0, C1 landed; R lane counts.                                                              |
| `md:11739,12375`                                             |                                                                                                  |
+--------------------------------------------------------------+--------------------------------------------------------------------------------------------------+

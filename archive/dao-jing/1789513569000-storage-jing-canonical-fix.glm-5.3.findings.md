Created-GMT: 2026-09-15 21:20:00 GMT
Created-Local: 2026-09-16 04:20:00 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: glm
Session-ID: 541aa172-7582-4698-aee7-0ca0434052b3
# Task: dao.jing canonical encoder — close remaining P0 collisions
Role: Storage & Indexing
Implementers:
- Model: glm-5.3 | Assigned: 2026-09-16 03:06:09 +07 | Status: complete | Rationale: storage/content-addressing encoding work, flat-subscription implementer

All three P0 defects are closed, on all three hosts, within the transitional
pr-str-based encoder. The fix is an uncommitted working-tree diff touching
only `src/cljc/dao/jing.cljc` (+95/−21 around order-normalize /
order-normalized-print / materialize!) and `test/dao/jing_test.cljc
(+111` new tests). Nothing was staged or committed.

## Diff summary, per defect

**1. Metadata collisions (map, set, vector, list, seq).** The earlier
attempt's `with-meta` calls were dead weight for the address: pr-str never
prints metadata unless `*print-meta*` is bound, and the map/set branches
dropped metadata during normalization itself. Now:

- `order-normalize` attaches normalized metadata on every collection branch
  (the new `attach-meta` helper), not only on vector/list/seq.
- A new `canonical-print` replaces the bare `pr-str` print step. It prints
  the normalized tree by hand, following Clojure's conventions byte for byte
  (space-separated sequential elements, `, `-separated map entries, metadata
  as a `^m ` prefix), with scalars still printed through `pr-str`. Binding
  `*print-meta*` was not enough: ClojureDart's sorted-set printer drops a
  set's metadata (its `HashRankedWideTreapSet/-print` prints through
  `(seq this)`, which carries no metadata) — verified by a failing Dart run
  before `canonical-print` landed and a passing one after. Hand-printing the
  composites removes all host-printer dispatch from the address.
- Reader position metadata (`:line :column :end-line :end-column`) is
  dropped before metadata is considered: the readers stamp source
  coordinates onto list literals, which would otherwise make an address
  depend on where a payload literal sits in source (found empirically:
  `'(1 2)` hashed differently from `(seq [1 2])` before the strip).
- Empty metadata `{}` is dropped: `=` ignores metadata entirely, and the
  hosts disagree on whether `^{}` prints (the JVM skips it, Dart prints it).

**2. Set tag inside the value domain.** The `(list 'set ...)` encoding put
the tag where real data lives: `#{1 2}` and `(list 'set '(1 2))` hashed
identically. Now a set normalizes to `(sorted-set-by pr-str-compare ...)` —
it stays a set and prints `#{...}`, so the type marker is the `#{}` braces
themselves, which no vector, list, map, or scalar can produce. ClojureDart
has `sorted-set-by` and prints sets `#{...}` (verified against its compiled
runtime and by the passing Dart tests). Sorting by printed element keeps
construction-order insensitivity. `#{}` vs `(set)`, `#{1 2}` vs `(set (1 2))`
(the exact shape the old encoder emitted), vs `(set [1 2])`, vs `[1 2]`, vs
`'(1 2)` are all asserted distinct.

**3. Records collapse to maps.** Records are not a supported payload type
under the transitional encoder, and now fail loudly instead of silently
sharing the map's address: `order-normalize` throws `ex-info` on any record
found anywhere in the value. Basis (the prompt's "confirm with the
Architect" step): `docs/design/dao.jing.md` §Canonical encoding lists the
supported representation-level structure as "maps, sets, numbers, strings,
and byte arrays" — records are absent; and the hosts cannot agree on how to
print one, which makes any record tag unportable: the JVM and JS print a
record as a tagged literal (`#user.Foo{:a 1}`), while ClojureDart's
`defrecord` prints through `print-map` with no tag (verified in
`lib/cljd-out/cljd/core.dart`). A cross-host record encoding is therefore
pinned-canonical-encoding work, not transitional work. **Open for Architect
sign-off:** if records are ever to be supported, the reject-throw is the
point to revisit. Note this is a contract tightening: record payloads now
throw where they previously (wrongly) succeeded.

**4. materialize! read-back verification.** With metadata address-
significant, the `=` check on `:present` was both too weak and wrong-
shaped: `=` ignores metadata, so a metadata-only mismatch at one address
passed. The read-back is now verified by
`(= (content-hash stored) (segment-hash address))` — the exact rule the
design doc states for backends ("the address must ... hash to the payload").
This catches the metadata-only collision (tested) and accepts canonically-
equal stored values (insertion order, list-vs-seq), which is the intended
idempotency.

Also: lists and seqs normalize to one canonical list form. `=` calls them
equal and both print `(e1 e2 ...)`; the previous attempt's two branches
produced identical bytes anyway. Vectors remain distinct.

## Test commands and output

- `clojure -M:test -n dao.jing-test` → **Ran 38 tests containing 226
  assertions. 0 failures, 0 errors.** (33 tests / 200 assertions before;
  5 new deftests / 26 assertions.)
- `bb test:clj` (full JVM suite) → **Ran 1447 tests containing 165878
  assertions. 0 failures, 0 errors.** — includes every materialize! consumer
  (dao.data.btree.storage, dao.space.index).
- `bb test:cljs` → **Ran 1348 tests containing 35374 assertions. 2 failures,
  0 errors.** Both failures are pre-existing and unrelated:
  `yin.repl.core-test/a-failed-input-is-consumed-exactly-once` expects
  `(/ 1 0)` to surface as `"Error: "` but Node yields `##Inf`; the namespace
  has no dao.jing dependency. `dao.jing-test` itself ran clean (verified
  again via `node target/node-tests.js`).
- `bb test:cljd` is blocked by a pre-existing compile error outside this
  task's scope: `test/bench/yin_vm_bench.cljc` line 20 has an empty
  `:cljd []` require the compiler rejects ("Cannot invoke
  clojure.lang.Named.getName() because x is null"), aborting the lane's
  compile phase. Workaround run: `clojure -M:cljd test dao.jing-test` →
  compile **"dao.jing-test All clear! 👌"**, then on the Dart VM **all 38
  dao.jing-test deftests passed** (extracted from the flutter-test progress
  stream: 38 jing-named progress lines, zero failure-counter bumps, zero
  `[E]` flags on dao.jing-test). The global tally (+49 −96, "Some tests
  failed") is entirely stale Sep-14 cljd-out artifacts (gui, postgraphics,
  and the deleted v1 dao.jing dht/mem/remote/file tests) failing to load
  against the regenerated core — identical before my change. Notably, the
  first Dart run (before canonical-print) failed exactly one assertion —
  the set-metadata pair — demonstrating the new tests catch the host
  printer defect the fix removes.

## Hosts verified

- `:clj` — fully (jing namespace + entire suite).
- `:cljs` — jing namespace clean; suite's 2 failures pre-existing (above).
- `:cljd` — jing namespace fully (compile + all 38 deftests on Dart); the
  full cljd lane could not run, for the pre-existing bench-namespace compile
  error above, and the stale-artifact load failures inside the dart test
  phase are environmental (a clean cljd-out regenerates them).

## Address stability (worth knowing when reading old stores)

- Against the reviewed working-tree attempt (the baseline of the prior
  review): byte-identical encodings everywhere except the three defect
  classes. Verified by hashing a probe set both ways: vectors, maps, nested
  composites, and scalars produce identical SHA-256s; sets and
  metadata-carrying values necessarily change (they were the collisions).
- Against HEAD (e1610be): maps, vectors, scalars unchanged. Lists, seqs,
  and sets change because HEAD collapsed all sequentials into vectors
  (`sequential? → mapv`), i.e. HEAD addressed `'(1 2)` and `(seq [1 2])` as
  `[1 2]` — the very collision the acceptance criteria require removing.

## Defects not closed

None of the three. Known residuals, all pre-existing and explicitly
deferred to the pinned canonical byte encoding (dao.jing.md, Open items 1):

- Pathological symbols that mimic another value's print still collide
  (`(symbol "42")` vs 42, `(symbol "{:a 1}")` vs the map) — inherent to
  pr-str scalars.
- Metadata on scalars (symbols, keywords) is uniformly ignored rather than
  printed: hosts differ on printing it, `=` ignores it, and the required
  pairs are collection metadata only.
- Numeric print parity across hosts remains whatever pr-str gives.

## Out-of-scope observation

`src/cljc/yin/vm.cljc` gained a semantic-bytecode section during this
session (another session's work; it calls `jing/segment-key` for §2 rows and
re-validates `segment-key` on load). Untouched by me; the full JVM suite
above passed with it on disk.

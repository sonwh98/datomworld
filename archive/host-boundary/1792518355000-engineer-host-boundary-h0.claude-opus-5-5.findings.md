Created-GMT: 2026-10-20 18:15:00 GMT
Created-Local: 2026-10-21 01:15:00 +07
Coding-Agent: claude
Model: claude-opus-5-5
Role: QA & Verification engineer (H0 freeze: tooling and tests, no behaviour change)
Prompt: /Users/sto/workspace/datomworld/collab/1792518355000-engineer-host-boundary-h0.claude-opus-5-5.prompt.md
Design: /Users/sto/workspace/datomworld/docs/design/dao.stream.host-boundary.md (r6; §2, §3.1-3.3, §8 H0, §8.1, §8.3, §12-14)
Worktree: /Users/sto/workspace/datomworld/.claude/worktrees/host-h0 (branch host-h0, base eacaed90 = local master with codec C0/C1)
Status: DONE, nothing staged, committed or pushed. One H0 finding must be resolved before H1: list 1 differs from the §8.1 baseline (F1 below).

# Host-boundary H0: the freeze

## Summary

- **No `src/` behaviour change.** Seven new files, all untracked (listed
  below). No file under `src/cljc`, `src/clj`, `src/cljs` or `src/cljd` was
  touched. No codec-track file, no `dao.stream.*` source, no jolt file and
  no design doc was touched.
- **(a) Closure totals match exactly**: clj **142**, cljs **140**, cljd
  **141**, shared **133**. The lane-specific lists are file-for-file the
  §3.1 table (9 / 7 / 8). An independent second traversal agrees on every
  file of every lane.
- **(b) The gate passes on the current tree** (155 files scanned, 0 errors,
  0 violations). Each negative test fails the gate for exactly the injected
  reason and nothing else. Reading zero files, or failing to read one, gives
  `:scan-failure`, not `:pass`.
- **(c) Counts**: the per-lane table is below. JVM goes from 4121 tests to
  4131, which is +10, the new `dao.host.gate-test`. Node and Dart are
  unchanged.
- **(d) Corpus hash set**: 18 files, SHA-256, in
  `docs/design/host-boundary/corpus-sha256.edn`, with a regenerate/check
  script and a JVM test that asserts the bytes.
- **Findings** (F1-F4) are differences between the design text and the
  tree. They are recorded, not massaged.

## Files created (absolute paths; all untracked in the worktree)

- `/Users/sto/workspace/datomworld/.claude/worktrees/host-h0/src/dev/host_boundary/closure.clj`: the closure tool (ns `host-boundary.closure`).
- `/Users/sto/workspace/datomworld/.claude/worktrees/host-h0/src/dev/host_boundary/corpus_hashes.clj`: the corpus hash set generator/checker (babashka).
- `/Users/sto/workspace/datomworld/.claude/worktrees/host-h0/docs/design/host-boundary/closure-clj.edn`
- `/Users/sto/workspace/datomworld/.claude/worktrees/host-h0/docs/design/host-boundary/closure-cljs.edn`
- `/Users/sto/workspace/datomworld/.claude/worktrees/host-h0/docs/design/host-boundary/closure-cljd.edn`
- `/Users/sto/workspace/datomworld/.claude/worktrees/host-h0/docs/design/host-boundary/corpus-sha256.edn`
- `/Users/sto/workspace/datomworld/.claude/worktrees/host-h0/test/dao/host/gate_test.clj`: ns `dao.host.gate-test`, 10 deftests.

Files changed: none.

Scratch files live under `target/h0/` (gitignored): lane logs, probes and
`gate-evidence.txt`. They are not deliverables.

### Location of the tool: `src/dev/`, not `dev/` (justified deviation)

§8 names `dev/host_boundary/closure.clj`. The repo has no top-level `dev/`
directory. Every dev tool lives in `src/dev/`: `affected.clj`,
`dep_graph.clj`, `cljd_agg.clj`, `tasks.clj` and `yang_antlr_gen.clj`.
`deps.edn` puts `src/dev` on the classpath of `:dev`, `:fmt`, `:clean`,
`:antlr-gen`, `:dep-graph` and `:cljs`. The closest precedent is
`dep_graph.clj`, which also uses `clojure.tools.namespace.parse`; its
`:dep-graph` alias adds tools.namespace 1.5.0. A top-level `dev/` would be
on no alias.

I added no alias, because a `deps.edn` change is a "wide" change for
`bb test:changed`. The tool therefore runs with an inline alias:

```sh
clojure -Sdeps '{:aliases {:hb {:extra-paths ["src/dev"] :extra-deps {org.clojure/tools.namespace {:mvn/version "1.5.0"}}}}}' \
  -M:hb -m host-boundary.closure [--check]
```

The same command is in the ns docstring. Consequence: `src/dev/` is in
`affected.clj`'s wide prefixes, so `bb test:changed` on this branch exits 2
(wide) and asks for `bb test`. If the orchestrator prefers the design's
literal path, moving the two files to `dev/host_boundary/` and changing
the `-Sdeps` path is mechanical. The gate test does not depend on the
tool's location.

## (a) Closure tool and output

Method, as §3.1 states it:

- The traversal starts at `yin.repl.main`.
- `clojure.tools.namespace.parse/read-ns-decl` reads the ns form with the
  lane's features: `:clj #{:clj}`, `:cljs #{:cljs}`, `:cljd #{:cljd :clj}`.
  The first matching arm wins, as in tools.reader.
- `deps-from-ns-decl` collects the `:require`, `:use` and `:require-macros`
  clauses.
- A namespace resolves to `src/<lane>/<path>.<lane>` first, then to
  `src/cljc/<path>.cljc`. A namespace with neither file (core, a library)
  is outside the closure.

One adaptation was needed: `#?(:cljd nil :clj [...])` reads as `nil` on
cljd, and `deps-from-ns-decl` throws "Unparsable namespace form" on a nil
libspec. `strip-nils` drops it, which matches what the compilers do.

Output is deterministic and has no timestamp. `--check` exits 1 if a
committed file is stale; I ran it and it exited 0.

```
clj   total 142 shared 133 lane-specific 9
cljs  total 140 shared 133 lane-specific 7
cljd  total 141 shared 133 lane-specific 8
shared (all three lanes): 133
```

Lane-specific files, identical to the §3.1 table:

- **clj**: `clj/dao/jing/content/driver.clj`, `clj/dao/stream/datagram/jvm.clj`,
  `clj/dao/stream/waitset/driver.clj`, `clj/dao/stream/ws/jvm.clj`,
  `clj/yang/antlr/cst.clj`, `clj/yin/repl/host/jvm.clj`,
  `cljc/yin/repl/host.cljc`, `cljc/dao/jing/cbor/boring.cljc`,
  `cljc/dao/stream/cbor/boring.cljc`.
- **cljs**: `cljs/dao/stream/datagram/node.cljs`, `cljs/dao/stream/waitset/driver.cljs`,
  `cljs/dao/stream/ws/node.cljs`, `cljs/yang/antlr/cst.cljs`,
  `cljs/yin/repl/host.cljs`, and the two `boring.cljc`.
- **cljd**: `cljd/dao/stream/cbor/cljd.cljd`, `cljd/dao/stream/transit/cljd.cljd`,
  `cljd/dao/stream/datagram/dart.cljd`, `cljd/dao/stream/waitset/driver.cljd`,
  `cljd/dao/stream/ws/dart.cljd`, `cljd/yang/antlr/cst.cljd`,
  `cljd/yin/repl/host.cljd`, `cljd/dao/jing/cbor/cljd.cljd`.

**Class tags.** Each file entry has the shape
`{:file :ns :shared :classes #{...}}`. `:classes` is a set because the
§3.1 table puts some namespaces in several rows. For example,
`yin.vm.values` is both `:profile-internal-pure` and `:consumer`, and
`yin.repl` is both `:driver` and `:deferred-entropy`. A file with no host
contact beyond §3.3 syntax is tagged `:none`, which makes "the remaining
files" checkable. Each lane file carries a class legend that gives each
class's slice. Three tagging choices go beyond the table and are marked in
the source:

- `:codec-track` also covers the §8.3 codec files (`ws`, `remote-channel`,
  `ws-project`, `jing.stream`, `dao.data`, the ws adapters,
  `yin.repl.serve` and `yin.repl.connect`).
- `yang.python.antlr.*` is tagged `:deferred-stage`, by prefix.
- The three `dao.stream.waitset.driver` files are tagged
  `:external-stream-seam` (see F3).

### Independent second traversal (agreement)

Traversal B is in `dao.host.gate-test` (`traverse`, `select-arms`,
`libspec-deps`, `ns-deps`) and shares no code with tools.namespace. It
works differently:

- It reads every form with tools.reader in `:read-cond :preserve` mode.
- It selects arms itself, splicing `#?@` arms. This is the r3 script's
  defect class, which under-counted 141/140/139.
- It parses libspecs itself.

Only the §3.1 resolution rule is shared, because the design fixes it.
Result, also asserted by the test
`independent-traversal-agrees-with-the-committed-closure`:

```
traversal B :clj 142 committed 142 equal? true
traversal B :cljs 140 committed 140 equal? true
traversal B :cljd 141 committed 141 equal? true
```

The sets are equal file by file, not just in count. They also agree with
astra's r3 reviewer totals in the design (142/140/141/133).

## (b) The gate test `dao.host.gate-test`

**Lane.** `test/dao/host/gate_test.clj` runs on the JVM lane only. The
gate checks source text, which is the same on every lane, and §8.2 has it
read files "with the Clojure reader per lane feature". `:preserve` reads
all arms of all lanes in one pass. The Node runner and the Dart aggregator
do not compile `.clj` files (`cljd_agg.clj:42` matches `_test.clj[cd]`),
so the Node and Dart counts are unaffected.

**Input.** The input is the union of the three committed closure files:
155 distinct files (133 shared, plus 9 + 7 + 8 lane-specific, minus the
two `boring.cljc` files shared by clj and cljs).

**Host arms.** For every reader conditional, with every lane's arms
walked:

- A conditional is exempt by a §3.3 pattern if it is:
  - inside the `ns` form (require arms);
  - a catch-type position (`(catch #?(...) e ...)`);
  - all arms equal under `=` (hint-only, e.g. `#?(:clj ^long n :cljs n)`);
  - a splicing protocol block that is a direct child of
    `deftype`/`defrecord`/`reify`/`extend-type`/`extend-protocol`/`specify`;
  - a `print-method` defmethod;
  - `exists?`;
  - `(Object.)`/`(js-obj)` sentinels.
- `(comment ...)` is skipped.
- Every other conditional is a host arm.
- A lane-specific `.clj`/`.cljs`/`.cljd` file counts as one host arm in
  its entirety. `.clj` files cannot hold `#?` at all, and §8.1 lists every
  lane file.
- Observed exemption counts over the closure: catch-type 174, ns 49,
  hint-only 35, sentinel 10, protocol-block 8.

**Effect calls (list 2).** The kinds are the §3.2 categories:

- `:random-uuid`;
- `:rand` (`rand`, `rand-int`, `rand-nth`, `shuffle`, `random-sample`);
- `:secure-random` (`SecureRandom`, `Random.secure`, `randomUUID`,
  `js/crypto*`, `getRandomValues`, `randomBytes`, `generateKey`);
- `:gensym`;
- `:clock` (`System/currentTimeMillis`/`nanoTime`, `js/Date*`,
  `js/performance*`, `DateTime.now*`, `Instant/now`);
- `:ambient` (`System/getenv`/`getProperty`, `js/process*`, `Platform.*`);
- `:print` (`println`, `print`, `prn`, `pr`, `printf`, `newline`,
  `js/console*`);
- `:timer` (`js/setTimeout`/`setInterval`/`setImmediate`/`queueMicrotask`,
  `Timer*`, `Thread/sleep`, `Future.delayed*`);
- `:m-entry`: a symbol that resolves through the file's ns aliases or
  refers to `dao.data.arrays` or `dao.host.bytes`, named `aset`, `acopy`,
  `asort`, `set!` or `copy!`.

Calls are resolved lexically:

- A symbol bound by `let`/`loop`/`for`/`doseq`/`when-let`/... , by fn,
  defn or defmethod params, by `letfn`, by `catch`, or by deftype fields
  and method params is a local, not the core function.
- So is a name the file defines or `declare`s, or excludes with
  `:refer-clojure`.
- Quoted forms and `comment` are data.

This removes three false positives a name match would give.
`yin/vm/engine.cljc` declares its own `gensym`, `yin/vm/macro.cljc` binds
a param named `gensym`, and `yin/vm/linker/authority.cljc:366-369` binds a
local named `pr`.

List 2 is `{file {kind ceiling}}`. One more call of a kind in a file
fails the gate.

**Verdict.** The verdict is `:pass`, `:fail` or `:scan-failure`.
`:scan-failure` means zero files were read, or a file could not be read or
parsed. It is distinct from a pass with zero matches.

Evidence, from `target/h0/gate-evidence.txt`, produced by loading the
test ns and printing the gate's results:

```
CURRENT TREE verdict :pass scanned 155 errors [] arm-violations [] effect-violations []
files with non-exempt arms 76 of which lane-specific 19
files with effect calls 32
NEG1 target src/cljc/dao/data.cljc verdict :fail arm-violations [{:file src/cljc/dao/data.cljc, :lines [130]}] effect-violations []
NEG2 target src/cljc/dao/data.cljc verdict :fail arm-violations [] effect-violations [{:file src/cljc/dao/data.cljc, :kind :random-uuid, :count 1, :allowed 0, :lines [130]}]
EMPTY verdict :scan-failure scanned 0
by category {:h0-unlisted 16, :deferred-scope 15, :migration-source 15, :seam-driver 21, :profile 1, :codec-track 5, :runtime-prereq 3}
effects by kind {:secure-random 6, :timer 5, :rand 4, :print 13, :gensym 4, :m-entry 33, :ambient 13, :clock 10, :random-uuid 21}
```

(After that run, the unreadable-file case was changed to report "cannot
read file" instead of a tools.reader protocol error. The test asserts
`:scan-failure` and names the file.)

The negative tests are in memory: the gate takes `{file text}`. The
target file is chosen programmatically: the first closure `.cljc` file
outside the list with no arms and no effect calls, which is
`src/cljc/dao/data.cljc`. Text is appended to its in-memory copy only. No
real source is edited.

- **NEG1** appends `(def injected #?(:clj (java.lang.Math/abs -1) :cljs 1))`.
  The verdict is `:fail`, with exactly one arm violation (that file) and
  zero effect violations.
- **NEG2** appends `(def injected (random-uuid))`. The verdict is `:fail`,
  with exactly one effect violation (`:random-uuid`, count 1, allowed 0)
  and zero arm violations.

Each test asserts that exact violation list, so it fails for the right
reason and only that reason.

The 10 deftests are:

- `closure-totals-are-the-design-totals`
- `independent-traversal-agrees-with-the-committed-closure`
- `gate-passes-on-the-current-tree`
- `list-1-equals-the-observed-arms` (no stale or missing list-1 entry)
- `list-2-equals-the-observed-calls` (no slack in any ceiling)
- `an-injected-host-arm-outside-list-1-fails-the-gate`
- `an-injected-random-uuid-outside-list-2-fails-the-gate`
- `a-scan-failure-is-not-a-pass` (empty input, and an unreadable file)
- `corpus-bytes-match-the-frozen-hash-set`
- `exemptions-and-resolution` (unit checks of the §3.3 patterns and the
  lexical resolution)

Run alone: `clojure -M:test -n dao.host.gate-test` gives
`Ran 10 tests containing 65 assertions. 0 failures, 0 errors.`
clj-kondo reports 0 errors and 0 warnings; cljstyle check is clean.

Library mode (§8.2) was **not** built. It belongs to H2.

### List 1 as generated (76 files) vs the §8.1 baseline: finding F1

List 1 in the test is the observed set, by §8.1 category: migration
sources 15, profile 1, runtime prerequisites 3, codec track 5, seams and
driver 21, deferred scope 15. That is 60 files, matching §8.1's existing
files after the corrections below. It also holds **16 `:h0-unlisted`
files**: real, non-exempt host arms in closure files that §8.1 does not
name. Each one is commented with its line in the test:

| File | Site | Arm |
|---|---|---|
| `dao/space/index.cljc` | :78 | `instance? Long/Double`, `js/isNaN`, `dart/is?` |
| `dao/space/query.cljc` | :808 | runtime type name |
| `dao/stream/ringbuffer.cljc` | :168 | host atom type |
| `dao/stream/rpc.cljc` | :114 | `rand` (§9.1 entropy) |
| `yang/antlr/packet.cljc` | :67 | code points |
| `yang/clojure.cljc` | :375 | runtime type name |
| `yang/php.cljc` | :95, :98 | `Double/parseDouble`, `js/parseFloat`, `int/parse` |
| `yin/repl/adapter.cljc` | :58 | edn reader |
| `yin/repl/dht.cljc` | :104 | edn reader |
| `yin/repl/query.cljc` | :460 | byte length |
| `yin/vm.cljc` | :404 | `bytes->str`: `String.` / `fromCharCode` / `String.fromCharCodes` |
| `yin/vm/debruijn_register_code.cljc` | :439 | `js/Number.isSafeInteger` |
| `yin/vm/debruijn_register_effects.cljc` | :130 | `js/Number.isSafeInteger` |
| `yin/vm/engine.cljc` | :929 | runtime type name |
| `yin/vm/linker/closure.cljc` | :32 | byte length |
| `yin/vm/ucf/rows.cljc` | :282 | byte length |

In the other direction, §8.1 names files that are **not** on the generated
list 1:

- Outside the adopted closure (required by no `src` file):
  `dao/jing/content/async.cljc`, `dao/stream/udp.cljc`,
  `dao/space/schema.cljc` and `dao/jing/stream.cljc`.
- In the closure, but every arm is catch-type and exempt:
  `dao/stream/remote_channel.cljc:399,440,920` and
  `dao/stream/ws_project.cljc:376,435`.

§8.1 says a difference either way is an H0 finding to resolve before H1.
The Architect has to decide the following:

- (i) Do the 16 files get §8.1 categories? Several look like H1/H2 profile
  material: byte length, `bytes->str`, number parsing, safe-integer.
  Others look like dialect semantics: the runtime type name in error text,
  and the edn reader.
- (ii) Should §8.1 drop the six names that are absent or exempt-only?

Until then, the 16 sit on list 1 tagged `:h0-unlisted`, so the gate passes
on today's tree and still catches any new arm.

## Other findings (design text vs tree; R claims checked, nothing massaged)

- **F2. §3.2 random-uuid: 21 in the closure, not 26.** `dao/await.cljc:235,240`
  and `yin/vm/ffi/remote_serve.cljc:417,604,606` exist and do call
  `random-uuid`, but no `src` file requires `dao.await` or
  `yin.vm.ffi.remote-serve`, so both are outside the adopted closure.
  26 - 5 = 21. The other 19 §3.2 lines match exactly.
- **F3. §3.1 rows that name namespaces outside the closure:**
  `dao.stream.udp`, `dao.stream.ws.browser` (expected: the browser is not
  a lane), `dao.await`, `yin.vm.ffi.remote-serve`, `dao.jing.content.async`
  and `dao.space.schema`. The reverse case: the `dao.stream.waitset.driver`
  files (`setTimeout`/`Timer`) are in §3.1's lane-specific list but in no
  class row. I tagged them `:external-stream-seam`, following §8.1's
  "Seams and driver" row.
- **F4. §3.2 sites the resolved scan sees differently:**
  - **Printing.** `yin/repl/main.cljc` has **13** resolved print calls
    (`println`/`print`/`prn`/`pr`/`printf`/`newline`/`js/console*`), not
    19. §3.2 does not say what its 19 counted, so this is unreconciled (R).
    `yang/python/antlr/repl.cljc:71` is `println` inside a quoted yin
    program (`'(let ...)`, `run-form`), so it is the yin VM's primitive,
    not a host call. The gate does not count it.
  - **Timers.** `btree/storage.cljc:224-227` is a promise facade
    (`CompletableFuture`/`Completer`/`js/Promise.`), which is not a timer.
    It is on list 1 as runtime prereq or deferred. `main.cljc:1321,1343`
    construct `Thread.`s, which is also not a timer kind. Timer calls the
    gate does see: `driver.clj:173`, `datagram/jvm.clj:70`, and the
    waitset drivers.
  - **Clocks.** Besides `main.cljc` (5 lines: 1242, 1275, 1408, 1435, 1542)
    and `driver.clj:169,206`, the ws seams also read clocks:
    `ws/jvm.clj:422`, `ws/node.cljs:249` and `ws/dart.cljd:176`. §3.1
    mentions these; §3.2 does not list them.
  - **Secure-random.** `sign.cljc:79` is `jvm-pair`'s seed-replay
    `SecureRandom` proxy. §2.5 rules it deterministic and internal, but it
    matches by name, so it is in list 2's ceiling for `sign.cljc` with a
    comment.
  - **M-entry callers (§2.4).** Only `dao.data.btree` resolves today, with
    33 calls through `dao.data.arrays`. `chunks`, `cbor` and `sign` mutate
    host arrays directly inside arms (`System/arraycopy`, `.setRange`,
    `.set`) and call no M entry yet; `bytes/set!`/`copy!` do not exist
    until H1. After H2 migrates them, list 2 must gain them as `:m-entry`
    callers. That is the §2.4 (iv) audit point.

## (c) Per-lane counts (quick tier, one lane at a time, CMAKE_BUILD_PARALLEL_LEVEL=4)

The Dart peer (`bb build:yin-repl-peer`) was built first, so the JVM lane
ran the Dart-peer tests.

| Lane | Before (base eacaed90) | After (H0) | Delta |
|---|---|---|---|
| JVM `bb test:clj` | 4121 tests, 286038 assertions, 0 fail, 0 err | 4131 tests, 286103 assertions, 0 fail, 0 err | +10 tests, +65 assertions: exactly `dao.host.gate-test` (10 deftests, 65 assertions run alone) |
| Node `bb test:cljs` | 4112 tests, 150444 assertions, 0 fail, 0 err | same run (see below) | 0 |
| Dart `bb test:cljd` | 4060 tests, "All tests passed!" (flutter prints no assertion count) | same run (see below) | 0 |

Logs: `target/h0/{clj-before,clj-after,cljs-before,cljd-before}.log`.

**How each run relates to the tree.**

- The JVM "before" run started on the clean base, before any new file
  existed. Its log does not mention `dao.host.gate-test`.
- The Node and Dart runs started after all seven files existed. Neither
  lane compiles any of them: shadow's `:node-test` only takes cljs/cljc
  sources, and `cljd_agg.clj:42` only takes `_test.clj[cd]`. Neither log
  mentions `dao.host.gate-test`, and no file they compile differs from eacaed90.
  So each of those runs is both the before and the after measurement.
  I did not run them a second time, because the second run would have the
  same input (about 25 minutes for Node and 7 for Dart on this laptop).
  If the orchestrator wants a literal second run, it is
  `bb test:cljs` / `bb test:cljd` in the worktree.
- The first Node attempt was killed at the tool's 20-minute background
  limit while running alongside my JVM probes. I reran it alone, and that
  rerun's counts are the ones above.

## (d) Corpus hash set

`docs/design/host-boundary/corpus-sha256.edn`: SHA-256 of all **18**
files under `test/resources/dao/jing/` (6) and `test/fixtures/codec/` (12).

- `test/resources/dao/jing/` holds `cbor-v1.json`, `cbor-v1.errata.md`,
  `cbor-v1.README.md`, `cbor-v1.generate.py`, `digest-table.edn` and
  `blake3-vectors.edn`.
- `test/fixtures/codec/` holds `{cbor,transit}/{descriptors,envelopes,exceptions,jing,negotiation,rejections}.edn`.

To regenerate or check, run `bb src/dev/host_boundary/corpus_hashes.clj [--check]`.
`--check` exits 1 and lists the differing files. Two spot checks against
`shasum -a 256` agree (`cbor/jing.edn` 27aac4fb…, `cbor-v1.json`
831a1d31…). `corpus-bytes-match-the-frozen-hash-set` in the gate test
asserts both the file set and every digest on the JVM lane.

SHA-256 was chosen because the prompt names it, `shasum` is on every host,
and these are tooling checksums, not system identities. If the owner's
BLAKE3-everywhere rule should cover tooling checksums too, switching is a
one-function change.

Other goldens that §8 H4 and Appendix A mention are **not** in the set,
because the prompt scoped (d) to the two trees: `sign_vectors.edn`,
`integer_v3_fixtures.cljc`, `int-contract-v1.txt`, and the DHT and linker
image goldens. Adding their paths to `roots` would include them.

## Not done (by scope)

- Nothing from H1 onward: no `dao.host.*` source, no library mode of the
  gate (§8.2, H2), no consumer changes.
- No commit, stage or push. No change to `deps.edn` or `bb.edn` (no alias
  or bb task for the tool; see the location note).
- F1 is not resolved. That is the Architect's decision before H1.
- `bb test:changed` was not run. Because of `src/dev/`, it would report a
  wide change (exit 2). The quick-tier lanes above stand in for it.
- Slow tier: not run (H0 is not a phase boundary; the design runs the slow
  tier once after H4).

Report: /Users/sto/workspace/datomworld/collab/1792518355000-engineer-host-boundary-h0.claude-opus-5-5.findings.md

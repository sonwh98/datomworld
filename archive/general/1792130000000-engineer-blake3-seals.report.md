Completed-GMT: 2026-10-11 19:42:24 GMT
Completed-Local: 2026-10-12 02:42:24 Asia/Bangkok

(Times are this machine's `date` output. They come before the brief's
Created-GMT 2026-10-12 11:00:00, so the host clock or the brief header is off.)

# BLAKE3 seals, owner tag and profile digest — engineer report

Worktree `/Users/sto/workspace/datomworld-blake3`, branch `blake3-seals`.
Nothing staged or committed. Byte backups of the three source files as they were
before the edit: `collab/bak-engine.cljc`, `collab/bak-values.cljc`,
`collab/bak-vm.cljc` (checked with `cmp`). Copies after the edit, used to restore
after each mutant: `collab/new-*.cljc`.

Spelling choice: explicit `{:algorithm :blake3}` rather than the 1-arg default.
No other `src/` site makes a new identity with an explicit `:blake3` (the
`semantic.cljc`, `ledger.cljc` and `index.cljc` sites pass an `algo` variable). I
picked the explicit form because these are VM identity contracts: Jing's default
could move later without them moving, and the explicit form matches the explicit
`:sha256` it replaces. Out-of-scope files (`debruijn*.cljc`) were not touched.

## Site 1: capability seal (`src/cljc/yin/vm/engine.cljc:302`)

- Change: `seal-of` now uses `(jing/content-hash [:yin.k/seal secret kind id] {:algorithm :blake3})`.
- Readers (every seal goes through the private `seal-of`; no other code computes one):
  - `engine/issue-ref` (`engine.cljc:328`) mints `{:type :id :seal (seal-of ...)}`.
  - `engine/authentic-ref?` (`engine.cljc:350`) checks stream/cursor refs by recomputing `seal-of`.
  - Cell refs: `authentic-ref?` (`engine.cljc:345`) and the heap lookup at `engine.cljc:2411` compare `:seal` with the seal stored in the heap entry at allocation (`heap-write`, `engine.cljc:2386/2661/2672`). They compare and never recompute, so they agree automatically.
  - `check-ref!` goes through `authentic-ref?`.
  - v2 handoff `src/cljc/yin/vm/ucf/handoff.cljc:3070,3075` re-seals under the receiver by calling `engine/issue-ref`. It never compares a sender seal, so it agrees automatically.
  - `datomworld/demo/continuation_handoff.cljc`, `dao/await.cljc`, `module.cljc`, `ast_walker.cljc` and `ucf/holder/reader.cljc` mention seals only in docstrings and comments. None of them computes or compares a seal.
- Persisted fixtures and goldens: none carry a seal string (`git grep` over tracked `test/` edn/json/cbor/txt/resources: no hits). The only test with a seal-shaped pattern is `test/yang/clojure/stream_eval_test.cljc:429`, which redacts `:seal "<64 hex>"` (see the 64-hex section).
- New test: `yin.vm.engine-test/seal-is-blake3-test` (`test/yin/vm/engine_test.cljc`). It asserts the `issue-ref` seal equals the BLAKE3 content-hash of `[:yin.k/seal tu/secret :stream-ref :stream-0]` and differs from the SHA-256 one.
- Failing then passing: written after the source edit, so it passed on the BLAKE3 code. It fails under the mutant below.
- Mutant: `seal-of` reverted to `{:algorithm :sha256}`. `clojure -M:test -n yin.vm.engine-test` gave `FAIL in (seal-is-blake3-test) (engine_test.cljc:44)` and `(engine_test.cljc:45)`: 28 tests, 143 assertions, 2 failures, 0 errors. Killed. Restored from `collab/new-engine.cljc`; `cmp` clean.

## Site 2: owner tag (`src/cljc/yin/vm/values.cljc:130`)

- Change: `owner-tag` now uses `(jing/content-hash [:yin.k/owner secret] {:algorithm :blake3})`.
- Readers: `yin.vm/empty-state` (`src/cljc/yin/vm.cljc:2219`) is the only caller. It sets `:owner` once. Everything else (application/invocation `:foreign-value` checks, the lift encoder, `lower-closure`) compares `:owner` values and never recomputes one. No test pinned an owner value.
- New test: `yin.vm.engine-test/owner-tag-is-blake3-test`. It asserts `(:owner (vm/empty-state ...))` equals the BLAKE3 content-hash of `[:yin.k/owner tu/secret]` and differs from the SHA-256 one.
- Mutant: `owner-tag` reverted to `:sha256`. `clojure -M:test -n yin.vm.engine-test` gave `FAIL in (owner-tag-is-blake3-test) (engine_test.cljc:53)` and `(engine_test.cljc:54)`: 28 tests, 143 assertions, 2 failures, 0 errors. Killed. Restored from `collab/new-values.cljc`; `cmp` clean.

## Site 3: primitive-profile digest (`src/cljc/yin/vm.cljc:351-352`)

- Change: `(jing/content-hash description {:algorithm :blake3})` and `(keyword "yin.k.pp" (str "blake3-" digest))`.
- `yin.k.pp` / `sha256-` sites in `src/`: only `vm.cljc:352` (changed). The `dao/jing.cljc` `sha256-bytes` hits are the registered `:sha256` algorithm and were left alone.
- Tests touched:
  - `test/yin/vm_test.cljc` `standard-primitives-publish-portable-profiles`: new assertion (line 225) that every standard primitive's profile name equals `"blake3-"` plus the BLAKE3 content-hash of its description (the profile minus `:yin.k/profile`, plus `:yin.k/name`).
  - `test/yin/vm/linker_require_test.cljc:576,578`: deliberately wrong profile `:yin.k.pp/sha256-other` renamed to `:yin.k.pp/blake3-other`. The value is arbitrary; this is spelling consistency only.
  - `test/yin/vm/linker_manifest_test.cljc:642`: deliberately wrong profile `:yin.k.pp/sha256-nope` renamed to `:yin.k.pp/blake3-nope` (same reason).
- Pinned values: none needed regenerating. The hash pins in `semantic_register/derive_test.cljc:228`, `ucf/authority_test.cljc:350-355` and the `linker/sign_vectors.edn` envelopes all passed unchanged, so none of them covers a profile keyword.
- Mutant: digest reverted to `:sha256` (label left as `blake3-`). `clojure -M:test -n yin.vm-test` gave 22 × `FAIL in (standard-primitives-publish-portable-profiles) (vm_test.cljc:225)` (one per primitive): 33 tests, 475 assertions, 22 failures, 0 errors. Killed. A label-only revert to `"sha256-"` fails the same assertion. Restored from `collab/new-vm.cljc`; `cmp` clean.

## 64-hex and `sha256-` assumptions

- `test/yang/clojure/stream_eval_test.cljc:429-430`: regex `:seal\s+"[0-9a-f]{64}"` redacts seals. Still correct: Jing's BLAKE3 is `:digest-bytes 32` (`dao/jing.cljc:97`), so 64 hex, the same as SHA-256. No change; passes.
- No other code assumes the seal, owner or profile length. The other `{64}` / `hex? 64` hits in `src/` are Ed25519 keys and DHT principals (`linker/sign.cljc`, `repl/main.cljc`, `repl/dht.cljc`), unrelated.
- The `:segment/sha256-…` hits in `test/` and `docs/design/` are segment addresses under the still-registered `:sha256` algorithm, outside this slice.

## Docs sites to update (not edited)

- `docs/design/dao.jing.hash-registry.md:366-384` ("Primitive-profile identifier"): says to keep the profile on SHA-256 with a `sha256-` label. Now BLAKE3 with `blake3-`.
- `docs/design/dao.jing.call-site-classification.md:50`: the `yin.vm/primitive-profile` row, which prescribes `{:algorithm :sha256}` and the `sha256-` label (its line references are also stale).
- `docs/design/yin.vm.universal-continuation-format.md:1285`: example `:yin.k.pp/sha256-…`.
- `docs/design/yin.vm.linker.md:1729`: example `:yin.module/primitives {'println :yin.k.pp/sha256-...}`.
- Seals and owner tag: no file under `docs/design/` (or `src/cljc/yin/vm/docs/`) names their hash algorithm, so nothing to update.

## Lanes (all run in the foreground)

- Setup: `bb gen:python-antlr`. The JVM run first failed with `ClassNotFoundException yang.python.antlr.gen.Python3Lexer` because the fresh worktree had no generated classes.
- JVM: `clojure -M:test -n yin.vm.engine-test -n yin.vm-test -n yin.vm.cell-test -n yin.vm.data-test -n yin.vm.integer-test -n yin.vm.continuation-invoke-test -n yin.vm.semantic-engine-test -n yin.vm.semantic-register.effects-test -n yin.vm.semantic-register.link-test -n yin.vm.linker-test -n yin.vm.linker-require-test -n yin.vm.linker-manifest-test -n yin.vm.ucf.handoff-test -n yin.vm.ucf.handoff-v2-holder-test -n yin.vm.ucf.holder.reader-test -n yin.vm.ucf.authority-test -n yin.vm.host-typed-values-test -n yin.vm.attach-image-test -n yin.vm.semantic-test -n yin.vm.semantic-register.derive-test -n yang.clojure.stream-eval-test`. 21 namespaces: **Ran 451 tests containing 8255 assertions. 0 failures, 0 errors.** (log `collab/blake3-jvm.log`; final run after all edits, including the fixture renames).
  - `yin.vm.values-test` does not exist; the owner-tag test is in `yin.vm.engine-test`.
  - The UCF seal users found by grep are `ucf.handoff-test`, `ucf.handoff-v2-holder-test` and `ucf.holder.reader-test`; `ucf.authority-test` was added for its hash pins.
- Node: `clj -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge '{:ns-regexp "^(<same 21 namespaces>)$"}'`. "Testing" lines appeared for all 21, plus 5 namespaces the tests require (`debruijn-register-contract-test`, `debruijn-vm-contract-test`, `parity-test`, `semantic-register.parity-test`, `semantic-register.walker-trace-test`). **Ran 485 tests containing 8703 assertions. 0 failures, 0 errors.** (log `collab/blake3-node.log`). One compile warning, `:ns-var-clash` in `src/cljc/yin/vm/linker/head.cljc:185`, is in a file this slice doesn't touch.
- Dart: **not run** (left to the orchestrator on shiva, per the brief).
- kondo: `clj -M:kondo --lint` on the 3 source and 4 test files: errors 0, warnings 4. All 4 are in `src/cljc/yin/vm.cljc` lines 1332/1488/1496/1510 (unused private var and bindings), lines this change doesn't touch: the diff in that file changes lines 351-352 in place, with no line shift.
- cljstyle: `mise exec -- cljstyle check` on the same 7 files: clean (no output, no error).

## Diff

```
 src/cljc/yin/vm.cljc                       |  4 ++--
 src/cljc/yin/vm/engine.cljc                |  2 +-
 src/cljc/yin/vm/values.cljc                |  2 +-
 test/yin/vm/engine_test.cljc               | 23 +++++++  (+ dao.jing require)
 test/yin/vm_test.cljc                      | 10 +++-
 test/yin/vm/linker_require_test.cljc       |  4 +-
 test/yin/vm/linker_manifest_test.cljc      |  2 +-
```

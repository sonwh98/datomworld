Completed-GMT: 2026-10-11 23:46:15 GMT
Completed-Local: 2026-10-12 06:46:15 Asia/Bangkok

# Adversarial review: BLAKE3 for the capability seal, owner tag and profile digest

Reviewer: claude-fable-5-1. Worktree `/Users/sto/workspace/datomworld-blake3`,
branch `blake3-seals`, HEAD 492ff50b, diff `git diff master...HEAD` (3 source
lines in `engine.cljc:302`, `values.cljc:130`, `vm.cljc:351-352`; 4 test files).
Read-only except this file and three byte backups (`collab/rev-bak-{engine,values,vm}.cljc`,
taken and verified with `cmp` before the first mutant). Every mutant was
applied with the Edit tool, run on the JVM in the foreground, reverted, and
the file confirmed byte-identical to its backup with `cmp`. `git status` on
`src/` and `test/` is clean at the end. No slow tests, no Node, no Dart run by me.

## Findings

| severity | file:line | evidence | correction |
|---|---|---|---|
| low (docs, out of this diff) | `docs/design/dao.jing.hash-registry.md:599` | "`yin.vm/primitive-profile` computes its digest explicitly with SHA-256, keeping its label truthful" — a fifth SHA-256 profile sentence the author's docs list (366-384, call-site-classification:50, UCF 1285, linker 1729) does not include. | Add line 599 to the orchestrator's docs batch. Nothing else in `docs/design/` or `src/cljc/yin/vm/docs/` names the seal or owner-tag algorithm (grep `sha256|sha-256` filtered on profile/seal/owner/yin.k.pp: only the five sites). |
| info | `test/yang/clojure/stream_eval_test.cljc:429` | Redaction regex `:seal\s+"[0-9a-f]{64}"` still matches because Jing's `:blake3` registry entry is `:digest-bytes 32` (`dao/jing.cljc:97`), same width as SHA-256. This is by accident of both being 32 bytes, not by design of the test. | No change needed for this slice. If a wider digest ever enters the registry this regex is the one place that would silently stop redacting. |
| info | `src/cljc/yin/vm/engine.cljc:302`, `values.cljc:130` | Mutant M9 (drop the explicit `{:algorithm :blake3}` and use the 1-arg default) survives: today's default is `:blake3` so it is semantically equivalent. The explicit spelling is therefore a pin against a future default change, not something the tests distinguish. | Acceptable; the explicit form is the owner's ruling. |
| info | `test/yin/vm/engine_test.cljc:39-54` | M8 (seal drops `kind`) is killed only by the new `seal-is-blake3-test`, no pre-existing test notices. Kind confusion is already prevented structurally by `(= kind (:type ref))` and the `cursor-cell?` check in `authentic-ref?` (`engine.cljc:343-351`), so this is not a security gap, only a note on coverage. | None required. |

No blocking findings.

## Answers

### 1. Every site that computes or verifies a seal, owner tag or profile name

- **Seal.** The only computation is the private `engine/seal-of` (`engine.cljc:299-302`). Mint: `issue-ref` (`:328`). Verify by recomputation: `authentic-ref?` for stream/cursor refs (`:350`). Cell refs compare `:seal` with the seal stored in the heap entry at allocation (`:345`, `:2411`, writes at `:2386/:2661/:2672`): compared, never recomputed, so they agree automatically. `check-ref!` goes through `authentic-ref?`. The v2 handoff (`ucf/handoff.cljc:3063-3075`) re-seals under the receiver via `engine/issue-ref` and never compares a sender's seal. Remote (`ucf/remote_test` uses its own secret through `empty-state`), holder reader, linker, module, ast_walker, await and the demo mention seals only in docstrings/comments. No stored or hard-coded seal anywhere in `src/` or tracked `test/` fixtures (grep `:seal` in code: engine only).
- **Owner tag.** Computed once in `values/owner-tag` (`values.cljc:123-130`), called once from `vm/empty-state` (`vm.cljc:2219`). All readers (`:foreign-value` checks, lift encoder, `lower-closure`) compare, never recompute. No pinned owner value in tests.
- **Profile name.** Minted only by `vm/primitive-profile` (`vm.cljc:340-356`). The only `"yin.k.pp"` string in `src/` is that line. Every other `:yin.k/profile` reader (linker `:1020`, publish `:54/:188`, module `:225`, engine `:955/:1225`, handoff `:3063/:3449`, Python `prelude.cljc:3580`, `lower.cljc:2026`, `safepoint.cljc:309`) either compares two profile keywords or copies one that originated from `primitive-profile` via a published manifest (`module/host-export-profile` reads `:yin.module/primitives`, which `linker/publish.cljc:188` fills from `primitive-profile`). One path mints, every path compares: no cross-path disagreement.
- **Old-algorithm readers.** None. `sha256` outside `dao.jing` appears only in `debruijn*.cljc` (H/R checksums, explicitly out of scope and untouched) and `src/dev/yang_antlr_gen.clj` (build-tool file checksums, unrelated).
- **Pinned profile keywords.** Only in `archive/` (historical findings) and the four+one docs sites. No `.edn/.json/.txt` fixture carries a `yin.k.pp/` keyword. The two test fixtures renamed (`linker_require_test.cljc:576/578`, `linker_manifest_test.cljc:642`) are deliberately wrong profiles; the rename is spelling only.

### 2. The 64-hex assumption and keyword parsing

- The only seal-shaped regex is `stream_eval_test.cljc:429` (see finding). All other `{64}` / `hex? 64` hits are Ed25519 keys and DHT principals (`linker/sign.cljc`, `linker/head.cljc`, `repl/main.cljc`, `repl/dht.cljc`, `dao/jing/dht.cljc`) or H/R dimension hashes (`debruijn.cljc:1202/1360`), unrelated.
- No `src/` code parses the `blake3-`/`sha256-` prefix of a `yin.k.pp` keyword (grep for `starts-with|subs|re-matches|re-find|split` in linker, publish, module, ledger, vm filtered on profile: no hits). Profile keywords are opaque, compared with `=`. Only `vm_test.cljc:225` reconstructs the spelling, which is exactly what makes the label mutants M4 killable.
- The `blake3-` spelling in the profile keyword and the `:address-id "blake3"` in Jing's registry coincide but are not coupled by code.

### 3. The three new tests and the mutant table

All runs: `clojure -M:test -n <ns>` on the JVM, foreground. Baseline before mutants on `yin.vm.engine-test`, `yin.vm-test`, `yin.vm.linker-require-test`, `yin.vm.linker-manifest-test`: 110 tests, 1380 assertions, 0 failures, 0 errors.

| # | mutant | file | namespaces run | result | killing test(s) |
|---|---|---|---|---|---|
| M1 | `seal-of` `:blake3` → `:sha256` | engine.cljc:302 | yin.vm.engine-test | KILLED (2 failures) | `seal-is-blake3-test` (engine_test.cljc:44, :45) |
| M2 | `owner-tag` `:blake3` → `:sha256` | values.cljc:130 | yin.vm.engine-test | KILLED (2 failures) | `owner-tag-is-blake3-test` (engine_test.cljc:53, :54) |
| M3 | profile digest `:blake3` → `:sha256`, label kept `blake3-` | vm.cljc:351 | yin.vm-test | KILLED (22 failures, one per primitive) | `standard-primitives-publish-portable-profiles` (vm_test.cljc:225) |
| M4 | label `blake3-` → `sha256-` only, digest unchanged | vm.cljc:352 | yin.vm-test | KILLED (22 failures) | `standard-primitives-publish-portable-profiles` (vm_test.cljc:225) |
| M5 | seal tuple order `[... secret id kind]` | engine.cljc:302 | yin.vm.engine-test | KILLED (1 failure) | `seal-is-blake3-test` (:44) |
| M6 | seal drops the secret `[:yin.k/seal kind id]` | engine.cljc:302 | yin.vm.engine-test; then yin.vm.cell-test, yin.vm.effect-forgery-test, yin.vm.host-typed-values-test | KILLED (1 + 4 failures) | `seal-is-blake3-test` (:44); pre-existing `foreign-task-ref-refused-test` (cell_test.cljc:338, 4 assertions) |
| M7 | `owner-tag` ignores the secret `[:yin.k/owner]` | values.cljc:130 | yin.vm.engine-test, yin.vm.host-typed-values-test | KILLED (17 failures) | `owner-tag-is-blake3-test` (:53); pre-existing `a-value-handed-raw-to-another-task-refuses-test` (:294, 8), `a-foreign-closure-refuses-to-lift-test` (:391, 4), `the-same-closure-through-lift-and-lower-applies-test` (:338, 4) |
| M8 | seal drops `kind` (one seal per id for every kind) | engine.cljc:302 | yin.vm.engine-test, yin.vm.cell-test | KILLED (1 failure) | `seal-is-blake3-test` (:44) only |
| M9 | seal uses 1-arg `content-hash` (Jing default) | engine.cljc:302 | yin.vm.engine-test | SURVIVED (0 failures) | none: equivalent mutant, default is `:blake3` |
| M10 | `owner-tag` drops the domain tag `[secret]` | values.cljc:130 | yin.vm.engine-test | KILLED (1 failure) | `owner-tag-is-blake3-test` (:53) |
| M11 | profile description `:yin.k/name nil` | vm.cljc:346 | yin.vm-test | KILLED (22 failures) | `standard-primitives-publish-portable-profiles` (:225) |

Each of the three new assertions can fail, and each fails for the exact regression it targets (algorithm, label, tuple shape). The `not=` halves of the two engine tests fire only for the SHA-256 reverts (M1, M2), the `=` halves for everything else. After every mutant `cmp` against the backup reported identical.

### 4. Cross-host

- `dao.jing/blake3-bytes` (`jing.cljc:114-125`) has all three branches: JVM `io.github.rctcwyvrn.blake3.Blake3` (`deps.edn:16`), Node `@noble/hashes/blake3.js` (`package.json:13`, `node_modules/@noble/hashes/blake3.js` present), Dart `package:blake3_dart` `blake3Hex` (`pubspec.yaml:21`). `content-hash` with `{:algorithm :blake3}` routes through `digest-bytes` → `blake3-bytes` on every host, so the three changed sites exercise exactly the path every `:segment/blake3-…` address already uses.
- Node: I did not run it. The author's `collab/blake3-node.log` shows `Testing yin.vm-test` and `Testing yin.vm.engine-test` and ends `Ran 485 tests containing 8703 assertions. 0 failures, 0 errors.`
- Dart: **not run** by the author or me. Risk is low because the Dart BLAKE3 branch is already load-bearing for every default-algorithm content address in the suite; the orchestrator's Dart lane should still cover `yin.vm.engine-test` and `yin.vm-test` before merge.

### 5. Scope

- Diff is exactly 3 source lines plus 4 test files; `debruijn*.cljc` H/R checksums untouched; `dao.jing` untouched. No scope creep.
- Docs still spelling the SHA-256 profile: the author's four sites **plus** `docs/design/dao.jing.hash-registry.md:599`. The 366-384 block also contains line 375 ("Keep the primitive-profile contract explicitly on SHA-256"), already inside the listed range. `archive/` hits are historical records and should not be edited.

## Verdict

The three sites are the only mint points, every consumer compares rather than recomputes, no fixture pins a SHA-256 seal/owner/profile, each new assertion is killable by its targeted mutant, and the one survivor is semantically equivalent. The single correction is a docs-batch addition outside this diff.

VERDICT: READY_TO_MERGE

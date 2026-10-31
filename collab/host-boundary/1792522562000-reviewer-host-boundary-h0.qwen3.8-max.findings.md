Created-GMT: 2026-10-20 18:56:02 GMT
Created-Local: 2026-10-21 01:56:02 +07
Coding-Agent: cmd
Session-ID: 61966f9d-4875-4b42-9545-b0dc8a2c9891
# Review: host-boundary H0 (closure tool, gate, corpus hashes)
Role: Reviewer (read-only) | Model: qwen/qwen3.8-max

## Verdict: SIGNED OFF

Condition the design itself imposes: F1 (list 1 vs §8.1 baseline) must be resolved by the Architect before H1; the author gated exactly that way.

## Findings

| id | sev | file:line | evidence | correction |
|---|---|---|---|---|
| M1 | low | gate_test.clj:515-531 | List 1 is file-granular (§8.1's own design), so the 16 `:h0-unlisted` files may gain NEW non-exempt arms without failing the gate; only effect counts have ceilings. Each entry is line-cited (I verified `dao/space/index.cljc:78` - a genuine `instance? Long/Double`/`js/isNaN`/`dart/is?` arm, non-exempt), and F1 is escalated, not buried. | Acceptable for H0; when the Architect categories the 16, consider line-level pins for any file kept on the list. |
| M2 | info | closure.clj ns doc; report §"Location" | Tool lives at `src/dev/host_boundary/` (repo convention; §8's literal `dev/` exists nowhere) and runs via inline `-Sdeps`, so deps.edn/bb.edn stay untouched; `bb test:changed` exits 2 (wide), disclosed. | Fine now; add a real alias if the tool persists past H1. |
| M3 | info | gate_test.clj:393 | Only `'quote`/`comment`/`ns` are skipped; syntax-quoted data would count its symbols as calls. Direction is over-counting (fail-closed), and `list-2-equals-the-observed-calls` pins today's tree exactly, so no slack exists. | Note for H2's library mode. |

## Checks

- (a) **Closure tool faithful**: lane features `#{:clj}/#{:cljs}/#{:cljd :clj}`, first-arm-wins via tools.reader; resolution `src/<lane>` then `src/cljc` exactly as §3.1; ns-name/file mismatch throws (fail-closed). `#?@` is handled natively by `read-ns-decl` with `:read-cond :allow` - the r3 defect class (dropped spliced requires, 141/140/139) cannot recur; `strip-nils` for `#?(:cljd nil :clj [...])` matches compiler behaviour. **Traversal B is genuinely independent**: own tools.reader `:preserve` read, own `select-arms` (splices `#?@` itself), own `libspec-deps` (`:as-alias`-only and string libspecs excluded explicitly); it shares only the design-fixed resolution rule, as disclosed. Agreement is asserted file-by-file both directions (`set/difference` both ways), not by counts - and B's independent handling of `:as-alias`/strings cross-validates tools.namespace's. Under-inclusion would show as B⊄A; over-inclusion as A⊄B; both are tested.
- (b) **Gate**: exemptions are applied to read `ReaderConditional` forms with ancestor context (never text); comments skipped; lane-specific files count as one whole-file arm. NEG1/NEG2 are in-memory (`update` on a slurped copy; disk untouched), target chosen programmatically, and each asserts the EXACT violation list plus zero of the other kind - they fail for the right reason only. Zero files and nil-text both give `:scan-failure` with the file named, distinct from `:pass`. `list-1-equals-the-observed-arms` and `list-2-equals-the-observed-calls` assert set/map equality with observations, so no stale entry and no count slack. The `:h0-unlisted` device keeps the gate green on today's tree while still catching any new arm outside list 1 - acceptable H0 behaviour given F1 is escalated (see M1).
- (c) **Corpus**: 18 files (6 + 12), `bb ... corpus_hashes.clj [--check]` regenerates/checks, and `corpus-bytes-match-the-frozen-hash-set` asserts both the exact file set and every digest on the JVM lane. SHA-256-vs-BLAKE3 choice disclosed with rationale.
- (d) **Findings honest - F2 verified myself**: `random-uuid` occurs exactly 27 times in `src/cljc`; `dao/await.cljc` (2) has no requirer anywhere in `src`, and `yin.vm.ffi.remote-serve` (3) is required only by its own holder/responder, which nothing requires; neither appears in `closure-clj.edn`. 27 − 1 (terminal, design's own exclusion) − 5 = 21, matching list 2's sum. The author reported the design mismatch instead of tuning the tool; F1/F3/F4 are likewise recorded with line cites and left for the Architect (F4's `antlr/repl.cljc:71` quoted-program exclusion is consistent with the gate's quote handling, unit-tested at gate_test.clj:771).
- (e) **Scope clean**: no `:hb`/host-boundary additions in deps.edn or bb.edn; nothing under `src/clj{,c,s,d}` touched (the change adds only `src/dev/host_boundary/`, `test/dao/host/`, `docs/design/host-boundary/`); no codec-track, jolt or design-doc edits.

## Could not verify

- Executing anything: the 10-test/65-assertion run, `--check` rc 0, the 142/140/141/133 totals and the quick-tier lane counts (4121→4131 JVM; Node/Dart single-run argument is sound but unrerun) rest on the orchestrator's re-runs and author logs under gitignored `target/h0/`.
- tools.namespace 1.5.0's exact `:as-alias`/string-libspec behaviour (library knowledge + the independent-traversal agreement).
- The r3 reviewer totals cited in §3.1.

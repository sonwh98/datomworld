Created-GMT: 2026-10-20 01:05:08 GMT
Created-Local: 2026-10-20 08:05:08 +07
Coding-Agent: cmd
Session-ID: pending (provider-generated)
# Review: J0a round 1 (jolt minimum baseline)
Role: Reviewer (adversarial, read-only) | Model: qwen/qwen3.8-max

## Verdict: SIGNED OFF WITH CHANGES

The four minimum-baseline items hold up under code reading and the retained logs. Two corrections and one orchestrator action before merge.

## Findings

| id | sev | file:line | evidence | correction |
|---|---|---|---|---|
| F1 | low | jolt-toolchain.edn:44 (`:observed :cmake-path`) | Records `~/.local/share/mise/installs/cmake/4.4.4/bin/cmake`; the bootstrap logs (bootstrap-neg-head.log:2) and the report (§Environment) show the real path is `.../cmake-4.4.4-macos-universal/CMake.app/Contents/bin/cmake`. The tracked observation is wrong. | Record the actual observed path or drop `:cmake-path`. |
| F2 | low | src/dev/jolt_bootstrap.clj (`verify!`) | Existing checkouts are verified for HEAD SHA, dirty tree, submodule SHAs, but never for the `origin` remote URL against the declared `:origin`. A pre-planted clone from a different origin at the same SHA passes. | Add `git -C dir remote get-url origin` equality to `verify!`. |
| F3 | low (process) | mise.toml:8 (worktree) | Spec §6.4:630-635 says the exact-Chez pin is proposed default 3, "not a decision... nobody changes the selector" until disposed; §10.1 J0a row makes the recorded owner disposition a precondition. The author changed it and disclosed the gap (report lines 42-43, 129). | Orchestrator must record the owner's §13.2 disposition in the design doc at/before merge; otherwise revert to `latest` + per-run recording. |
| F4 | info | build/j0a-logs/ | No log retained for the `-Stree -A:jolt` empty-dep-set claim (only spath.log); report says "observed 201 and 204 ms" but logs show 205 and 200. Harmless, but evidence isn't fully retained. | Capture the tree output; quote log numbers. |
| F5 | info | jolt_bootstrap.clj (`verify!` dirty check) | `git status --porcelain` misses gitignored tampering inside the clone (e.g., swapped build artifacts). Acceptable for the baseline. | Note for §6.4 gate hardening. |

## What checks out

- (a) Pins match §6.4 ruling 4 verbatim (SHA `96d7b866...`, `:tag :none`, v0.8.23/`a0ac71c3`, describe). Enforcement is real: cold path is `clone --no-checkout` + `checkout --detach <full-sha>` (fails loudly if SHA vanished); warm path never touches network; submodule map equality fails on extra/missing/differing SHAs; non-space status columns (`-`, `+`) fail; declared-but-unapplied patches fail; negative logs (HEAD moved, dirty README) confirm exit 1. Chez comes only via `mise exec chezscheme@10.4.1 -- which scheme` with exact version-string and `(threaded?)` checks; `JOLT_CHEZ` is written explicitly to the env file. bb tasks propagate exit codes via `(System/exit (:exit (shell {:continue true} ...)))`.
- (b) mise pins exact, not `latest`; consistent with the toolchain file; logs show real installs at 10.4.1/4.4.4 (plausible; see below).
- (c) spath.log confirms `src/jolt:src/cljc:src/clj` order; `:replace-deps {}` avoids root deps (Datomic/http-kit/boring); no build/antlr or JS/Dart roots. Matches §4.2-4.3 for this closure. Missing `:jolt` guards on `yin.repl.host`/`dao.space.dht` are disclosed and unreached by the closure.
- (d) No tautologies: the closure check diffs `loaded-libs` across the require; values `[97 233 128512]`/7/`[0 1 3 7]` are real; usleep asserts result 0 AND ≥200 ms elapsed; strlen=7 exercises real FFI. Removing the collect-safety assertion is sound: smoke-1/2 logs show the plain control's GC finishing mid-call (184-198 ms < 400 ms), so the observation cannot discriminate `:blocking`; asserting it would have been false. RECORD lines are honestly labelled and the header comment documents the anomaly against jolt's own ffi.clj doc.
- (e) Only the seven declared files differ; main-tree bb.edn/deps.edn/mise.toml carry no jolt content. The four overlays exist under build/jolt-spike/overlay/ and do target forbidden namespaces, so the no-fold decision follows the brief.
- (f) The report's NOT-done list is comprehensive and matches spec gaps.

## Could not verify

- Full worktree `git status`/`git diff` (shell blocked); relied on the orchestrator's file list plus main-tree comparisons.
- Upstream truth of SHA `96d7b866`, the 7 submodule SHAs, and v0.8.23 (no network/git); plausibility of chezscheme 10.4.1/cmake 4.4.4 in mise registries beyond the logs showing real installs.
- jolt internals (loader precedence, ffi.clj:1502-1510) - cited from spec, not re-read.
- No re-execution of bootstrap/smoke (read-only); orchestrator's independent re-run (exit 0, six PASS) matches the retained logs exactly.

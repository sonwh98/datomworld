Completed-GMT: 2026-10-11 17:27:27 GMT
Completed-Local: 2026-10-12 00:27:27 Asia/Bangkok

# Slice 6a round 4

Worktree `/Users/sto/workspace/datomworld-srvm-p6a`, branch `srvm-phase6a`, HEAD `6b7e65ed`. Nothing added to the index or committed. All commands ran in the foreground; no slow tests or Dart runs were requested by me.

## Changes

- **SOURCE CHANGE 1 — rows.cljc:** the search leaf's root-body digest preimage uses plain `cbor/encode` and `bytes-key`, excluding input row bodies from `:label-bytes`. The comment explains that decode bounds input via `:bytes` and emit imposes no additional input-body limit at this site. Intermediate labels, signatures, class keys, and their comparison representations retain the existing checks. Canonical bytes and comparison ordering are unchanged.
- **SOURCE CHANGE 2 — rows.cljc:** `rows->frame` documents that its default decoder produces in-band markers refused by the default encoder; identity requires classifiers on both sides. No behavior change.
- **TEST CHANGE — rows_test.cljc:** `input-row-bodies-do-not-spend-label-bytes` checks emission of the exact one-cell, 400000-integer result probe, then decoded equality for a large result and for the same large vector in custody slot 16. The round-trip variants reference the census cell from a binding (the exact emission probe leaves it unreferenced, which would independently fail decode's extra-cell rule). Expected decode differs only by canonical cell numbering. Both variants have a cell present.

## Regression and mutation evidence

The new test against unmodified source failed: **1 test, 2 assertions, 1 failure, 1 error**, exit 1 (`collab/6a-r4-red.log`). The error stops the loop before the custody variant in that red run. Against fixed source: **1 test, 3 assertions, 0 failures, 0 errors**, exit 0 (`collab/6a-r4-green.log`), exercising both round trips.

Required mutant: replaced the plain root-body encode/key with `(checked-key (checked-encode (subvec (get rows id) 1)))`. **KILLED: 1 test, 2 assertions, 1 failure, 1 error**, exit 1 (`collab/6a-r4-mutant.log`). It again fails on the large result before reaching custody. Restored in `finally` from the verified fixed backup and verified exact byte equality.

Before any edit, copied source bytes to `collab/6a-r4-backups/pre/rows.cljc` and verified equality; SHA-256 `eaad67ef85c01bcb151273fea8b053571a790fb15aac63938b24bc58d5995355`. Before the mutant, copied and verified fixed bytes to `collab/6a-r4-backups/rows.cljc`; final source and fixed backup SHA-256 `44dce21d8addb7ac16a2da42fadf06b2c69571c67d7e98c373f5491f5224176f`. Final equality verified again. `git diff --check` passes and staged diff is empty.

## Verification

| Lane | Exact command | Result | Log |
|---|---|---|---|
| JVM frame + rows | `clojure -M:test -n yin.vm.semantic-register.frame-test -n yin.vm.ucf.rows-test` | **31 tests, 552 assertions, 0 failures, 0 errors**, exit 0 | `collab/6a-r4-jvm.log` |
| JVM rows, 256 MiB | `clojure -J-Xmx256m -M:test -n yin.vm.ucf.rows-test` | **21 tests, 322 assertions, 0 failures, 0 errors**, exit 0 | `collab/6a-r4-heap.log` |
| Node rows selection (includes required test namespaces) | `clj -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge '{:ns-regexp "^yin\\.vm\\.ucf\\.rows-test$"}'` | **43 tests, 818 assertions, 0 failures, 0 errors**, exit 0; 157 files, 4 compiled, 0 warnings | `collab/6a-r4-node.log` |
| kondo | `clj -M:kondo --lint src/cljc/yin/vm/ucf/rows.cljc test/yin/vm/ucf/rows_test.cljc` | **0 errors, 0 warnings**, exit 0 | `collab/6a-r4-kondo.log` |
| cljstyle | `mise exec -- cljstyle check src/cljc/yin/vm/ucf/rows.cljc test/yin/vm/ucf/rows_test.cljc` | Final clean, exit 0 | `collab/6a-r4-cljstyle-final.log` |
| Dart | Orchestrator's lane | **NOT RUN by me** | No result claimed |

The rows suites include `intermediate-label-bytes-are-bounded`, which passes at 256 MiB, and `canonical-roots-are-pinned`. All three roots remain:

- `:segment/blake3-be82d50b14804bb7a69103631b9c45759b49b67dbb3cebe75a1259fed6b4a937`
- `:segment/blake3-c4a7c7e6de3b44400efec5930bc3d85d86b29153b4b2d548acba630bfbc3da7d`
- `:segment/blake3-8899f95d330a174ee098e10f16ecf2d017d5aae67d4f50d8369222342e6d36e9`

Initial cljstyle check exited 2 for one indentation error in the new test (`collab/6a-r4-cljstyle.log`); corrected one space and reran successfully. Tests and kondo ran before that whitespace-only correction. Mise emitted a cache-write warning outside the workspace. An initial source-edit script had a Python syntax error before modifying source; its subsequent test attempt also exited 1 on old source and was superseded by the recorded green run.

## KNOWN LIMITATIONS (recorded, not fixed)

- The `:work` bound does not bound elapsed time. Reviewer finding 2 measured ten equal-body map keys taking about 12 minutes (748 seconds) before `:work` refusal. Architect design question: charge work by label size / bytes processed. I did not rerun that slow probe.
- `bytes-key` boxes bytes into vectors; allocation is approximately 8x the CBOR byte bound and retained per cell (reviewer finding 4). No representation change in this round.
- Marker refusal reaches carried code-map constants and stream descriptors (reviewer finding 6), so guest code literally containing marker-shaped maps is non-portable. This round does not change that rule.

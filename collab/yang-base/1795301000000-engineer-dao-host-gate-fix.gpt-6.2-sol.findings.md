Created-GMT: 2026-11-22 08:04:15 GMT
Created-Local: 2026-11-22 15:04:15 +0700
Coding-Agent: Codex (gpt-6.2-sol)
Session-ID: 01a20248-d1a1-7122-80bb-d7cb98432439

# Task: Regenerate the `dao.host.gate-test` H0 freeze closure

Role: QA & Verification Engineer (implementation)
Completed-GMT: 2026-11-22 08:12:09 GMT
Completed-Local: 2026-11-22 15:12:09 +0700

Local commit `5370d111d3096c641d8903be043b01fbfdf3e402` on `fix/dao-host-gate-closure`, based on `origin/master` at `b1eb3e4094dd8cf4cbcd10d32d8e9e8101889702`. The branch is ready for the master-green seat’s countersign. The commit contains the five owned files.

## Verified failure signatures

The untouched worktree reproduced the brief’s 14 tests, 178 assertions, 29 failures and 0 errors.

1. **Scan census.** The recorded closure had 161 paths, and the gate could read 160. P0-prime (`7d6923e8`) deleted `yang/php/antlr/quoted.cljc` when it consolidated `defquoted`. The stale path accounted for the one-file read deficit. Independent traversal also found ten newly reachable shared files. The two details together give a 170-file current union.
2. **Injected arm set.** The baseline mutation reported `dao/data.cljc`, `yang/core/lower.cljc:104` and `yang/php/antlr/cst.cljc:43`. `1604aabe` moved host code-point conversion from the Python and TypeScript lowerers into `yang.core.lower`. The PHP lexer mode-switch arm comes from the eval compiler coordinator (`git blame` identifies `9f589e1d`). The CST byte line table calls `text/utf8-encode` outside a reader conditional. The §8.1 ruling in `05130e07` retains the PHP parser’s `:deferred-stage` exemption. The gate applies it to the exact `make-eval-worker` mode-switch arms. The mutation test checks both a regular unlisted file and an unrelated arm in the PHP CST file. Each mutation yields one arm violation for its target, zero scan errors and zero effect violations.
3. **Verdict.** The deleted `quoted.cljc` path produced `cannot read file`, which gives `:scan-failure` precedence over `:fail`. This also affected the effect mutations, the deliberate scan-error fixtures and §8.2 library mode. Removing the obsolete path restores a clean baseline scan and the exact `:fail` verdict on injections. The scan-failure precedence and diagnostics are intact.

## Closure and list movement

| Census | clj | cljs | cljd | Shared | Union |
| --- | ---: | ---: | ---: | ---: | ---: |
| Recorded before | 151 | 149 | 147 | 141 | 161 |
| Regenerated | 160 | 158 | 156 | 150 | 170 |

The ten shared additions are `yang/clojure/{core_impl,core_module,prelude}.cljc`, `yang/core/{defquoted,prelude_admission}.cljc`, `yang/rt/{effect,int}.cljc`, and `yin/vm/{float,key,provenance_scope}.cljc`. The DRY admission split (`c9dbc991`) accounts for `prelude_admission`. P0-prime accounts for `defquoted`, boxed `float`, and `key`. P1-prime (`f954d5ec`) adds the runtime adapters and `provenance_scope` and makes the existing `yang.clojure.prelude` reachable. P2-prime (`d6c9a265`) contributes the two `clojure.core` namespaces. The new DRY siteset store and publication files from `1604aabe` are outside the adopted traversal.

List 1 moves from 67 to 69 entries. It drops the deleted PHP `quoted.cljc` and the Python `prelude.cljc` entry, whose last substantive arm moved with `defquoted` in P0-prime. It records these four arms with reasons.

- `yang/core/defquoted.cljc` and `yang/core/lower.cljc` are `:deferred-scope` with class `:deferred-stage`. They carry the JVM macro for compiler prelude data and native code-point conversion for parser literal lowering, respectively.
- `yang/clojure/prelude.cljc` is `:runtime-prereq` for the JVM-only `var?` predicate in the pure `kind` host module.
- `yin/vm/float.cljc` is `:migration-source` with class `:profile-consumer` for the native floor operation in the boxed binary64 divmod kernel.

The PHP CST stays in the JVM and Node closures with its bounded exemption. `yin.vm` retains the `:runtime-prereq` class recorded by `05130e07`. The fresh scan had no list-2 count changes, and §8.2 passed over 157 in-scope files.

## Two-run determinism

The stock generator reproduced the new membership twice. Its class rows omit the `yin.vm` runtime class that `05130e07` added directly to the EDNs. To preserve that landed class and record the four new list-1 placements within the authorized scope, `dao.host.gate-test/regenerate-closure` supplies the class amendments to the existing generator. The bare generator invocation emits the pre-amendment classes. Use the gate entry point for recording and checking the amended EDNs.

```sh
clojure -Sdeps '{:aliases {:hb {:extra-paths ["src/dev" "test"]
                        :extra-deps {org.clojure/tools.namespace {:mvn/version "1.5.0"}}}}}' \
  -M:hb -e '(require (quote dao.host.gate-test)) (dao.host.gate-test/regenerate-closure "--check")'
```

The two recording runs each started a fresh JVM. Each invoked the same entry point with no `--check` and directed the generator to a distinct temporary output directory using `with-redefs` on `host-boundary.closure/out-dir`. Both runs reported 160/158/156 files, 150 shared and 10/8/6 lane-specific. All three `cmp` checks passed before recording. The recorded files matched again after commit, and the amended generator’s `--check` passed.

| File | SHA-256 in both recording runs |
| --- | --- |
| `closure-clj.edn` | `7a80a64ab77bbec26072f864307214454c564fbf650cc3101f5dfd810cec68b0` |
| `closure-cljs.edn` | `b6407670891edfbe4bfd8d8c3ee4933a93748114dfe7ed1a8ab89e8728f75cc8` |
| `closure-cljd.edn` | `1598be9293933c335148690520ecb3fbc357b6e161306f1727b18d84bc809ec2` |

## Verification

`bb test:sub:clj dao.stream` passed with loopback access. It ran 41 namespaces, 589 tests and 5,721 assertions with 0 failures and 0 errors. The sandboxed attempt hit `java.net.SocketException: Operation not permitted` on local socket tests. The retry ran the same command with loopback access.

### `clojure -M:test -n dao.host.gate-test` — fresh JVM 1

```text
Loading initial Timbre config from: :default

Running tests in #{"test"}

Testing dao.host.gate-test

Ran 14 tests containing 188 assertions.
0 failures, 0 errors.
```

### `clojure -M:test -n dao.host.gate-test` — fresh JVM 2

```text
Loading initial Timbre config from: :default

Running tests in #{"test"}

Testing dao.host.gate-test

Ran 14 tests containing 188 assertions.
0 failures, 0 errors.
```

DONE

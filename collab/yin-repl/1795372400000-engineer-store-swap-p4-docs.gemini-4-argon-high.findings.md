Created-GMT: 2026-11-22 17:59:54 GMT
Created-Local: 2026-11-23 00:59:54 ICT (+07)
Completed-GMT: 2026-11-22 18:02:35 GMT
Completed-Local: 2026-11-23 01:02:35 ICT (+07)
Coding-Agent: agy
Session-ID: a65337a3-0946-411f-abd2-32736aa26803

# Task: store-swap Slice 4 — user-facing and design documentation

Role: VM / Storage Engineer (documentation)

Implementers:
- Model: gemini-4-argon-high | Assigned: 2026-11-23 00:59 ICT | Status: completed | Rationale: docs round on the store-swap track with full design and implementation context across Slices 1–3

---

## 1. Changes Made (`file:line`)

### [yin.repl.md](file:///Users/sto/workspace/datomworld-store-swap/src/cljc/yin/vm/docs/yin.repl.md#L166-L230)
- Added `### Switching Index Stores: (use-store ...)` to §2 ("Working at the Prompt"):
  - Documented prompt examples for `(use-store "mem")` / `:mem`, `(use-store "file:<dir>")` (empty index and recovered `HEAD` with transaction count), and `(use-store "dht:<dir>" ...)`.
  - Documented the quiesce requirement (`(reset)` before switching), the exact dirty-session and pending-require refusal strings, read-only inspection commands leaving quiescence intact, disjoint `t`/`e` histories with no cross-store index migration, preserved session identity/configuration keys, and `(reset)` / `(vm :type)` index retention via `yin.repl.store/retain-on-reset?`.
  - Documented strict `open-first` ordering, same-directory `file:<dir>` ↔ `dht:<dir>` transition via `mem` (and the `(currently held by this session; switch to mem first to release the directory lock)` lock-conflict hint), and full ring departure / resource release when swapping out of `dht:<dir>`.
  - Documented all DHT keyword options on `(use-store "dht:<dir>" & opts)`: `:peers`/`:peer`, `:listen`/`:bind`/`:port`, `:publish`, `:key`, `:principals`/`:principal`, `:trust [...]` (vector/sequence syntax per Slice 2 review P3 note), `:follow [...]` (vector/sequence syntax per Slice 2 review P3 note), `:join`, `:max-inbound-bytes`, and the startup-only `:manifest` refusal pointing to `(dao.space.dht/load-index <manifest>)`.
  - Documented local-driver routing (`yin.repl.adapter/local-command-heads`), remote `--port` refusal (`yin.repl.serve`), and non-interaction with `<node-dir>/state.edn`.
  - Documented the Gambit C4 honest refusal boundary on `jolt-gambit` (`mem` → `mem` succeeds and preserves `jolt.ffi` and `dao.jing.mem` host modules; durable `file:` and `dht:` targets fail inline with the C4 blocking-FFI message during `open-first` while the session survives).

### [yin.repl.dao.space-index.md](file:///Users/sto/workspace/datomworld-store-swap/docs/design/yin.repl.dao.space-index.md#L104-L173)
- Updated `## Durable store: startup, in-session switching, HEAD, and the directory lock`:
  - Replaced `"There is no runtime switching."` with the quiesced, strict `open-first` `(use-store <target> & <opts>)` switching contract.
  - Specified `yin.repl/quiesced?` and `yin.repl/swap-refusal`, `open-first` replacement and rollback in `yin.repl/swap-store` and `yin.repl.main/replace-store`, async post-swap transport refusal recovery in `yin.repl.main/step-all`, same-directory lock contention and bounce-through-`mem` discipline, local driver routing and remote `--port` refusal, `<node-dir>/state.edn` non-interaction, and the `jolt-gambit` C4 refusal boundary.

---

## 2. Claims Verified Against Code

| Documented Claim | Verified Code Location |
|---|---|
| Command head `'use-store` and help line | [repl.cljc:426, 435-436](file:///Users/sto/workspace/datomworld-store-swap/src/cljc/yin/repl.cljc#L426) |
| Quiesce predicate (`:quiesced?`, `:pending-run`, `:unwritten`, `pending-publications`) and composition checks (`composition-admitting?`, `[:session :awaiting]`) | [repl.cljc:2521-2540](file:///Users/sto/workspace/datomworld-store-swap/src/cljc/yin/repl.cljc#L2521-L2540), [main.cljc:492-495](file:///Users/sto/workspace/datomworld-store-swap/src/cljc/yin/repl/main.cljc#L492-L495) |
| Preserved session keys, `:round` increment, and `:initial-admission` update on `install-store` | [repl.cljc:1043-1086, 2562-2576](file:///Users/sto/workspace/datomworld-store-swap/src/cljc/yin/repl.cljc#L1043-L1086) |
| Confirmation messages (`Switched index store to ...`) and lock conflict hint (`(currently held by this session; switch to mem first to release the directory lock)`) | [repl.cljc:2579-2599](file:///Users/sto/workspace/datomworld-store-swap/src/cljc/yin/repl.cljc#L2579-L2599) |
| Strict `open-first` replacement, old-resource retirement (`release-store!`), and late async refusal fallback to `:mem` | [repl.cljc:2602-2623](file:///Users/sto/workspace/datomworld-store-swap/src/cljc/yin/repl.cljc#L2602-L2623), [main.cljc:238-251, 449-483, 916-921](file:///Users/sto/workspace/datomworld-store-swap/src/cljc/yin/repl/main.cljc#L449-L483) |
| `(reset)` and `(vm :type)` consulting `store/retain-on-reset?` | [repl.cljc:2510, 2673](file:///Users/sto/workspace/datomworld-store-swap/src/cljc/yin/repl.cljc#L2510) |
| Local driver routing (`local-command-heads`) and remote `--port` refusal (`(use-store ...) is a local host command and cannot be run over a remote endpoint`) | [adapter.cljc:23](file:///Users/sto/workspace/datomworld-store-swap/src/cljc/yin/repl/adapter.cljc#L23), [driver.cljc:445-449](file:///Users/sto/workspace/datomworld-store-swap/src/cljc/yin/repl/driver.cljc#L445-L449), [serve.cljc:353-356](file:///Users/sto/workspace/datomworld-store-swap/src/cljc/yin/repl/serve.cljc#L353-L356) |
| DHT keyword options (`:peer`/`:peers`, `:publish`, `:bind`, `:port`, `:listen`, `:max-inbound-bytes`, `:key`, `:principal`/`:principals`, `:trust [...]`, `:follow [...]`, `:join`) and `:manifest` startup-pin refusal | [cli.cljc:544-640](file:///Users/sto/workspace/datomworld-store-swap/src/cljc/yin/shell/cli.cljc#L544-L640) |
| Synchronous UDP `:bind-failed` detection during `yin.shell.dht/open` | [dht.cljc:663-669](file:///Users/sto/workspace/datomworld-store-swap/src/cljc/yin/shell/dht.cljc#L663-L669) |
| Gambit C4 refusal via `dao.posix.file/realpath` and `:mem` host-module preservation | [yin.jolt:38-73](file:///Users/sto/workspace/jolt-store-swap/host/gambit/yin/jolt/gambit/yin.jolt#L38-L73) |

---

## 3. Verification

```sh
git -C /Users/sto/workspace/datomworld-store-swap diff --check HEAD -- src/cljc/yin/vm/docs/yin.repl.md docs/design/yin.repl.dao.space-index.md
```
- Outcome: **PASS (exit code 0, zero whitespace/formatting issues)**.

---

## 4. Deviations

- None. Only the two specified documentation files in `/Users/sto/workspace/datomworld-store-swap` were modified and left uncommitted.

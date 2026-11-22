Created-GMT: 2026-11-22 16:05:00 GMT
Created-Local: 2026-11-22 23:05:00 ICT (+07)
Completed-GMT: 2026-11-22 16:06:15 GMT
Completed-Local: 2026-11-22 23:06:15 ICT (+07)
Coding-Agent: agy
Session-ID: 29f740e7-285e-4b94-a559-27455325c321

# Task: store-swap Slice 1 — adversarial implementation review

Role: Review (adversarial)

Implementers:
- Model: gemini-4-argon-high | Assigned: 2026-11-22 23:05 ICT | Status: completed | Rationale: different family from the GPT author (reviewer independence); authored the design record

---

## Findings Table

| Severity | File:line | Invariant / Evidence | Recommended Correction |
|---|---|---|---|
| **P3 note** | [repl.cljc:2595-2597](file:///Users/sto/workspace/datomworld-store-swap/src/cljc/yin/repl.cljc#L2595-L2597) | `format-swap-error` matches `(:dir (ex-data error))` against `(:durable-dir (:index-store state))` by raw string `=`. If the user opens `"file:/tmp/d/"` and swaps to `"file:/tmp/d"`, `dao.space.store.fs/lock!` still refuses safely via canonical path lock checking (`"<dir> is locked by another process"`), though the extra `"(currently held by this session...)"` hint only appends when the path spelling matches. | Optional future polish; current refusal is already safe and accurate. |
| **P3 note** | [repl.cljc:1069](file:///Users/sto/workspace/datomworld-store-swap/src/cljc/yin/repl.cljc#L1069) | In `store-session`, `publication` is constructed with `:publication-policy publication-policy`, relying on `make-session` (`:1008`) to fall back to `(:publication-policy index-store)`. Both `yin.repl/swap-store` (`:2613`) and `yin.repl.main/replace-store` (`:462`) pass `:publication-policy` explicitly, so behavior is exact. | None required for Slice 1. |

No P0, P1, or P2 defects found.

---

## Detailed Adjudication

### 1. Fidelity to the Design Record
- **Quiesce semantics (`src/cljc/yin/repl.cljc:1016, 2331-2338, 2521-2540`)**:
  - `make-session` initializes `:quiesced? true` (`:1016`), so fresh boots, `(reset)`, `(vm :type)`, and completed `(use-store ...)` transitions all start quiesced.
  - `eval-program` (`:2331-2338`) clears `:quiesced?` to `false` at evaluation entry and catches encoding/evaluation throws so a failed round also leaves `:quiesced? false`.
  - `quiesced?` (`:2521-2528`) checks `(:quiesced? state)`, `(nil? (:pending-run state))`, `(empty? (get-in state [:indexer :unwritten]))`, and `(empty? (index/pending-publications (:indexer state)))`.
  - `swap-refusal` (`:2531-2540`) produces the exact specified refusal texts for both a pending `require` and an unreset evaluated session, and `yin.repl.main/swap-store` (`:492-495`) extends the check with `(not (composition-admitting? state))` and `(seq (get-in state [:session :awaiting]))`.
- **Strict `open-first` ordering (`src/cljc/yin/repl.cljc:2602-2623`, `src/cljc/yin/repl/main.cljc:449-483`)**:
  - Both standalone `yin.repl/swap-store` and host driver `yin.repl.main/replace-store` parse and open the new store first, install it into the engine inside a `try/catch` that releases the newly opened replacement if installation fails, and only then close/release the old `:session` and `:index-store`.
- **`install-store` behavior (`src/cljc/yin/repl.cljc:1043-1086, 2562-2576`)**:
  - Preserves `:lang`, `:frontends`, `:vm-type`, `:extra-primitives`, `:output-stream`, `:output-cursor`, `:link-policy`, `:custody`, and `:shell-token`.
  - Advances `:round` via `(update :round inc)` so output stream result tokens `[(:shell-token state) (:round state)]` cannot collide across swaps.
  - Dissociates `:call-provenance-handles`, `:call-provenance-failures`, `:call-provenance-round-failures`, `:call-site-commit-evidence`, and `:call-site-round-claims`, and resets `:last-value*`, `:frontend-session`, `:pending-input`, `:pending-run`, and `:ingress-loss?`.
- **`store/durable?` → `store/retain-on-reset?` fix (`src/cljc/yin/repl.cljc:2510, 2673`)**:
  - Applied at both `rebuild-session` (`:2510`) and `handle-command`'s `vm` branch (`:2673`).
- **Remote `--port` refusal & local client routing (`src/cljc/yin/repl/adapter.cljc:23`, `src/cljc/yin/repl/driver.cljc:445-449`, `src/cljc/yin/repl/serve.cljc:353-356`)**:
  - `'use-store` is in `local-command-heads` and intercepted in `driver/handle-line` before remote RPC forwarding.
  - Direct `:op/eval` requests to `yin.repl.serve` containing `(use-store ...)` are refused with the exact specified message without mutating the server's store.
- **`state.edn` non-interaction**:
  - Verified: no `yin.repl.state` write or read occurs on any swap path.

### 2. Implementer's Three Declared Design Decisions
1. **Guarding startup parser against composition-only targets pre-Slice 2 (`src/cljc/yin/repl/main.cljc:497-505`)**: **ACCEPTED.** Before Slice 2 adds `:parse-store-command` to `yin.shell.cli/hooks`, `(:parse-store-arg composition)` (`yin.shell.store/parse-arg`) can parse `"dht:<dir>"` into raw `{:type :dht :dir dir}` without running `yin.shell.cli/dht-spec` defaults/validation. Requiring `(or parse (store/describe-spec (:spec command)))` prevents unvalidated composition specs from slipping through in Slice 1 and automatically yields once Slice 2 supplies `:parse-store-command`.
2. **Served endpoint refusal via `yin.repl/eval-input` 3-arity (`src/cljc/yin/repl/serve.cljc:353-356`, `src/cljc/yin/repl.cljc:2925-2958`)**: **ACCEPTED.** Intercepting `use-store` after `read-forms` and `command-line?` inside `eval-input` guarantees that leading comments, whitespace, or multiline fragments cannot bypass the remote check, while returning the refusal through `serve`'s standard `(rpc/success-answer id "Error: ...")` channel.
3. **Late-refusal recovery reusing `replace-store` (`src/cljc/yin/repl/main.cljc:916-921`)**: **ACCEPTED.** Calling `(replace-store state {:spec :mem})` on an async post-swap `composition-refusal` reuses the exact same `open-first` installation and `release-store!` cleanup path, clears `:swapped-in-session?`, and updates `server`'s `:repl` reference in the same tick.

### 3. Defect Hunt Summary
- **Resource leaks**: Checked all success and failure branches in `yin.repl/swap-store` and `yin.repl.main/replace-store`. On success, the old session and store are closed once via `release-store!` / `store/close!`. On open failure, `store/open` releases any partial lock. On post-open installation failure, the catch block releases the newly opened replacement (`:session` then `:index-store`) and leaves the original store live.
- **Lock & ordering safety**: `store-session` (`src/cljc/yin/repl.cljc:1050-1060`) composes `link-source` before calling `(store/open index-store-spec)` so a bad link source cannot leak a directory lock.
- **Gate A audit**: Confirmed zero added references to `dao.jing.dht`, `dao.space.dht`, `yin.repl.dht`, `yin.vm.linker.(dht|head)`, `:dht`, or `--dht-` in `src/cljc/yin/repl/` and `src/cljc/yin/repl.cljc` (the diff reduced pre-existing `yin.repl.dht-composition.md` docstring citations from 33 to 32).

### 4. Test Adequacy
- `test/yin/repl/store_test.cljc:1218-1351` thoroughly covers the `mem` → `file:<dir>` → `mem` → `file:<dir>` round-trip, dirty-session and pending-require refusals, inspection command quiescence preservation, `(reset)` retention on `file:<dir>`, lock release on swap to `mem`, `HEAD` and datom recovery on swap back to `file:<dir>`, monotonic `t` and entity ID `e` continuation, and 8 distinct failed-swap controls verifying that the active file store remains locked and writable.
- `test/yin/repl/serve_test.cljc:318-392` covers local client execution while connected to a remote endpoint, remote `:op/eval` refusal on the server, replacement-only cleanup on failed driver installation, and late composition refusal recovery across both driver and endpoint.

---

Verdict: APPROVED

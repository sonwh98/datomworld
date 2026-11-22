Created-GMT: 2026-11-23 00:05:00 GMT
Created-Local: 2026-11-23 07:05:00 ICT (+07)
Completed-GMT: 2026-11-22 17:06:30 GMT
Completed-Local: 2026-11-23 00:06:30 ICT (+07)
Coding-Agent: agy
Session-ID: 29f740e7-285e-4b94-a559-27455325c321

# Task: store-swap Slice 2 — adversarial implementation review

Role: Review (adversarial)

Implementers:
- Model: gemini-4-argon-high | Assigned: 2026-11-23 07:05 ICT | Status: completed | Rationale: different family from the GPT author (reviewer independence); authored the design record and reviewed Slice 1

---

## Findings Table

| Severity | File:line | Invariant / Evidence | Recommended Correction |
|---|---|---|---|
| **P3 note** | [cli.cljc:593-596](file:///Users/sto/workspace/datomworld-store-swap/src/cljc/yin/shell/cli.cljc#L593-L596) | `:trust` and `:follow` in `store-command-option` validate their argument with `store-option-values` (`sequential?`), requiring vector/sequence syntax (`:trust ["ed25519:..."]`, `:follow ["...@127.0.0.1:4001"]`), whereas `:peer`/`:peers` and `:principal`/`:principals` offer both scalar and sequence keywords. | Non-blocking; in Slice 4 (`yin.repl.md`), document `:trust [...]` and `:follow [...]` with vector syntax (or optionally allow a single string in `store-option-values`). |

No P0, P1, or P2 defects found.

---

## Detailed Adjudication

### 1. `parse-store-command` Fidelity (`src/cljc/yin/shell/cli.cljc:544-640, 789`)
- **Target & option-pair grammar**: Validates non-empty args, normalizes `:mem` to `"mem"`, resolves `<target>` via `parse-store-arg` (`yin.shell.store/parse-arg`), and enforces even keyword-value pairs.
- **Startup pin `:manifest` refusal (`:625-627`)**: Checked before target or option reduction; throws the exact design-specified refusal message directing the operator to switch to `dht:<dir>` first and call `(dao.space.dht/load-index <manifest>)`.
- **Non-`dht:` option rejection (`:628-629`)**: Any trailing keyword options on `"mem"` or `"file:<dir>"` are refused with `"store options require dht:<dir>"`.
- **Shared startup validators (`:575-605, 630-639`)**: Every option delegates to the existing startup validators (`parse-peer`, `peer-text`, `parse-principal`, `parse-trust`, `parse-follow`, `parse-token`, `dht-number`, `dht-spec`). `listen-options` (`:565-572`) also strips IPv6 brackets (`[::1]:0` → `:bind-host "::1"`) before `dht-spec`'s `datagram/ip-literal?` check.
- **Key loading ordering (`:633-639`)**: `(load-key path)` runs only after `(dht-spec spec normalized)` succeeds, and before `replace-store` calls `yin.shell.store/open`, so neither a bad spec nor a missing/invalid key file ever acquires a directory lock or opens a socket.

### 2. Synchronous `bind-failed` Detection in `yin.shell.dht/open` (`src/cljc/yin/shell/dht.cljc:661-681`)
- **Fires during `open` (`open-first` intact)**: Immediately after `dht/join`, when `(seq peers)` is non-empty, `open` peeks at the first item on `(get-in node [:composition :traffic])` using `(:traffic-cursor node)`. When a synchronous datagram host (or test seam) deposits `:dao.stream.datagram/bind-failed` during `bind!`, `open` throws `(ex-info (str "dht: refused: the socket could not bind: " reason) {:dir dir :reason reason})` before `compose` or `replace-store`'s `install-store` is reached.
- **New-node cleanup scope**: The check sits inside the post-join `(try ... (catch ... (quietly! #(dht/close! node)) (throw e)))` block (`:661-681`). On a synchronous bind failure, `(dht/close! node)` closes both the new datagram seam (`(:close! seam)`) and the new durable directory store (`local` / `base`, releasing `<dir>/lock`), while the active store and session in `yin.repl.main` remain completely untouched.
- **Successful and delayed (async-host) binds**: Because `(stream/next traffic (:traffic-cursor node))` is a pure read and `(:traffic-cursor node)` on `node` is not advanced during `open`, a synchronous `:bound` event remains at `(:traffic-cursor node)` for the first `step-all` → `yin.shell.dht/step` → `dao.space.dht/bind-step` tick to observe, print `"dht: node ... listening on ..."`, and start the head board. If `traffic` is `:dao.stream/blocked` during `open` (async host) and `:bind-failed` arrives on a later tick, startup runs stop the shell (`a-socket-that-cannot-bind-refuses-and-stops-the-shell`) while in-session swapped runs recover to a fresh `:mem` store (`step-all` lines 916-921 from Slice 1).

### 3. Implementer's Four Declared Decisions
1. **Supplying `:parse-store-command` via `yin.shell.cli/hooks` (`src/cljc/yin/shell/cli.cljc:789`)**: **ACCEPTED.** Connects directly to Slice 1's `yin.repl.main/swap-store` hook without modifying `yin.repl.main` or `yin.shell.main`.
2. **`:join` token semantics (`:peers`, `:principals`, `:follow`) with explicit `:trust` (`src/cljc/yin/shell/cli.cljc:597-603`)**: **ACCEPTED.** Mirrors CLI `dht join <token>` (`src/cljc/yin/shell/cli.cljc:429-430`), where declaring and following a principal is distinct from admitting its modules under `--dht-trust` / `:trust` / `(trust p)`.
3. **Updating `a-socket-that-cannot-bind-refuses-and-stops-the-shell` to deliver `:bind-failed` after `boot` (`test/yin/repl/dht_test.cljc:888-905`)**: **ACCEPTED.** Because synchronous `:bind-failed` is now caught inside `yin.shell.dht/open` (covered by `a-failed-composition-swap-leaves-the-old-store-locked-and-writable`), depositing `:bind-failed` after `boot` preserves coverage of the asynchronous tick-time bind refusal path.
4. **Narrowing the memory startup banner assertion in `test/yin/repl/dht_test.cljc:468-469`**: **ACCEPTED.** Checking `(not-any? #(str/starts-with? % "dht:") ...)` tests the absence of DHT disclosure lines portably across JVM, Node, Dart, and Jolt/Chez (where Jolt's banner includes host and parser disclosure lines).

### 4. Defect Hunt Summary
- **Live ring resource teardown**: When swapping out of `dht:<dir>` to `mem`, `replace-store` invokes `release-store!`, which calls `yin.shell.dht/close!` (closing all follower WebSocket dials and stopping/stepping the publisher head board listener) followed by `yin.shell.store/close!` (closing the UDP datagram seam and the durable directory store lock). Both close paths are guarded by idempotent flags (`@closed?` on the DHT handle, `released?` on `fs/lock!`), preventing double-close hazards at process exit.
- **Disclosure banners on swap**: `parse-store-command` populates `:index-store-spec`, `:publisher-key-file`, and `:publisher-key` in `:opts`, which `replace-store` passes to `(:banner-lines composition)` and `composition-startup-lines` (`follow-lines`), printing the full publishing/fetch-only/solo and follower disclosure before the first transport tick.
- **Gate A & Gate B**: Zero new matches in `src/cljc/yin/repl/` and `src/cljc/yin/repl.cljc`; fresh engine-only JVM boot loads zero DHT/linker-interpreter namespaces.

### 5. Test Adequacy
- `test/yin/repl/dht_test.cljc` adds comprehensive coverage for option parsing and refusals (`:320-374`), solo `dht:` swap-in and lock/module teardown on departure (`:1138-1163`), two-node live ring swap-in (publisher + `:join` reader), signed module publish/require/eval across the ring, and full transport/lock teardown on departure (`:1166-1242`), plus five failed-swap controls from an active `file:` store verifying that the replacement socket and locks are cleaned up while the original store remains locked, queryable, and writable (`:1245-1295`).

---

Verdict: APPROVED

Coding-Agent: codex
Session-ID: 01a0ee9d-b840-7b43-a87d-639706ce2632
Model: gpt-6-sol

Completed-GMT: 2026-09-29 19:22:21 GMT
Completed-Local: 2026-09-30 02:22:21 Asia/Ho_Chi_Minh

## 1. Startup contract

Add `--index-store mem | file:<dir>` to the shared argument parser, so all three host entry points accept the same syntax. Omission means `mem`. Reject a missing value, an unknown scheme, an empty file directory, and a directory that cannot be opened; report the error before starting the shell or server. The parser, boot path, and CLJ, CLJS, and CLJD entry points are in [main.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/repl/main.cljc:48), [main.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/repl/main.cljc:74), and [main.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/repl/main.cljc:274).

For embedded callers, add `:index-store-spec` with values `:mem` or `{:type :file :dir <path>}` to `yin.repl/create-state`. Keep the existing `:index-store` handle injection for callers that supply their own store; reject supplying both. Resolve and validate the spec once at construction, then retain the opened store and its lifecycle resources in shell state. The current default and reset preservation are in [repl.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/repl.cljc:721) and [repl.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/repl.cljc:1422). There is no runtime store switching.

## 2. Restart and publication contract

Use `<dir>/content.jing` for `dao.jing.file` and a separate `<dir>/HEAD` for the latest published manifest address. The file store persists and validates content, but currently has no mutable root pointer; `create-content-file` takes a file path, not a directory ([file.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/jing/file.cljc:405)). After a round drains all blobs and reads its manifest back, write a versioned HEAD record containing the manifest address to a temporary file in the same directory, sync it, atomically rename it over HEAD, and sync the directory where the host supports that operation. Only then report the round as durably published. This extends the current publish sequence in [index.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/repl/index.cljc:234).

On open, absent HEAD means an empty index. A malformed HEAD, missing manifest, invalid manifest, or unreadable index node is a startup error; never silently start an empty index. Validate with `read-manifest` and a full `read-datoms` traversal ([index.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/space/index.cljc:306), [index.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/space/index.cljc:332)). An unreferenced blob after a crash is harmless. A crash before HEAD replacement retains the previous published snapshot.

The published covered index is sufficient for `q` after restart, so the per-session `dao.space` memory log need not itself be durable. Rehydrate a fresh complete-retention memory log from the published datoms, grouped into transaction records by their original `t`, before admitting evaluation. Preserve each datom and its `t`; derive the next entity ID as one greater than the greatest restored entity or metadata ID, subject to `datom/first-user-id`. The transactor must continue deriving its next `t` from that restored log, as its contract requires ([transactor.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/space/transactor.cljc:25)). Initialize the indexer’s manifest, transaction counts, and log from the recovered snapshot so `q` can answer before any new evaluation; today a new indexer starts with a nil manifest and empty log ([index.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/repl/index.cljc:136), [query.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/repl/query.cljc:270)).

Mint a new shell token on each process start. Restored facts retain their original session tokens and root/round metadata; new facts carry the new token. Those fields are already projected into datoms ([index.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/repl/index.cljc:92)). With a durable store, `(reset)` and VM selection rebuild the VM and observers while retaining the published index and rehydrated log. They must not reset `t` or entity allocation. The current rebuild retains the store but creates an empty indexer, which this slice must change ([repl.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/repl.cljc:1419)). For the default memory choice, preserve today’s reset behavior.

## 3. Concurrency

Refuse a second REPL process using the same durable directory. Acquire an exclusive directory lock before opening the content file or reading HEAD, hold it through shutdown, and fail startup with the directory named if acquisition fails. A single owner avoids competing HEAD updates and concurrent append/recovery against a backend whose write lock protects one handle, rather than separate processes ([file.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/jing/file.cljc:405)).

## 4. Hosts

Implement the head, atomic replacement, and exclusive lock as explicit host file operations for CLJ, CLJS on Node, and CLJD, alongside the existing host-specific file backend ([file.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/jing/file.cljc:159)). If a host cannot provide the required file operations, reject `file:<dir>` with a clear unsupported-host error. Never fall back to memory.

## 5. Files, tests, and slices

Touch [main.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/repl/main.cljc:48), [driver.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/repl/driver.cljc:75), [repl.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/repl.cljc:721), and [index.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/repl/index.cljc:136); add a small REPL owned durable directory component for HEAD and locking. `dao.jing.file` remains the blob backend. Update [yin.repl.dao.space-index.md](/Users/sto/workspace/datomworld/docs/design/yin.repl.dao.space-index.md:1) with the startup and recovery contract.

Implement in three slices: (1) CLI/spec parsing and store lifecycle; (2) HEAD, lock, and recovery; (3) indexer rehydration and reset continuity. Acceptance tests should cover the memory default, invalid specs and paths, all supported hosts, second-process refusal, missing and corrupt HEAD, interrupted publication on either side of HEAD replacement, and the essential restart sequence: evaluate code, stop, restart against the same directory, require `dao.space.query`, verify `q` sees the old facts, evaluate more code, and verify both old and new facts with increasing `t` and distinct session provenance. Also verify `(reset)` retains those facts in durable mode.

### Owner decisions

- **Settled:** Select storage at startup only; default to in-memory, with an optional durable `dao.jing` store.
- **Settled by the queued `$ast` design:** `$ast` and `$occ` remain in memory. This design changes persistence of the published covered datom index used by `q`; it does not make those relations durable.

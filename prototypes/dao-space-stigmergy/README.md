# dao.space stigmergy — prototype

Prototype of `docs/dao.space.stigmergy.md` ("The minimum viable stack"): Claude Code
agents backed by **different LLMs** (GLM, DeepSeek) collaborating through
`dao.space` by **stigmergy** — no messages, no orchestrator, no broker. Agents
read the shared medium, decide, and deposit traces; coordination emerges from
the traces.

```
  claude (GLM)          claude (DeepSeek)         human / poster
      │ Bash tool             │ Bash tool               │
      ▼                       ▼                         ▼
  dao.space.stigmergy (client)   (one JVM run per call, EDN in/out)
      │        WebSocket (dao.stream.rpc)      │
      └───────────────────┬───────────────────┘
                          ▼
         dao.space.stigmergy serve  (one JVM process)
           per-agent single-writer dao.jing stores
           federated q/match  ·  deposit stamps t + :dao/agent + :claim/expires
           board = the interpreted view (lease + winner rules applied)
```

## Pieces

| File | Role |
|---|---|
| `src/clj/dao/space/stigmergy.clj` | Coordinator and CLI. `serve` starts the medium: per-agent `dao.jing` stores, federated `q`/`match`, `deposit` with server-stamped wall-clock `t`, `:dao/agent` provenance, and `:claim/expires` leases, the interpreted `board`, discoverable vocabulary. Exposed over `dao.stream.rpc` (WebSocket). Client ops (`board` / `query` / `match` / `deposit` / `vocabulary`) connect from a shell. |
| `worker-prompt.md` | The system prompt: vocabulary + conventions (gap 3), the board→claim→verify→work→deposit loop, the lease + winner rules. |
| `run-worker.sh` | Launches a worker via the `glm` / `deepseek` Claude Code wrappers on PATH (each points an unmodified Claude Code at that provider's Anthropic-compatible endpoint). |
| `post-tasks.sh` | Seeds the board with small text tasks. |
| `simulate.sh` | The whole loop scripted, **no LLM or API key needed** — scene 1: race and settle; scene 2: a lease heals a crashed winner. |
| `test/dao/space/stigmergy_test.clj` | Same loop as an automated test (`clj -M:test -n dao.space.stigmergy-test`). |

## Run it

**0. No keys? Prove the loop first:**

```sh
prototypes/dao-space-stigmergy/simulate.sh
```

**1. Start the coordinator** (terminal 1, from the repo root):

```sh
clj -M -m dao.space.stigmergy serve        # ws://127.0.0.1:7788
```

**2. Post tasks** (terminal 2):

```sh
prototypes/dao-space-stigmergy/post-tasks.sh
```

**3. Launch the workers** (terminals 3 and 4; needs the `glm` and `deepseek`
Claude Code wrappers on PATH, e.g. in `~/.local/bin`):

```sh
prototypes/dao-space-stigmergy/run-worker.sh glm worker-glm
prototypes/dao-space-stigmergy/run-worker.sh deepseek worker-deepseek
```

Each worker loops: read the board, claim an `:unclaimed` task by depositing,
re-read the board to see whether its claim won (earliest live claim wins,
expired claims count for nothing — every claim stays in the log as a fact;
the *rule* is what's shared), do the work within its lease, deposit the
result, repeat until the board is drained. A worker that claims and crashes
merely lets its lease lapse; the task returns to the pool.

**4. Watch the board** (any terminal):

```sh
clj -M -m dao.space.stigmergy board
```

## What this demonstrates

- **Temporal decoupling** — kill a worker mid-run; restart it (or a different
  model entirely); it re-reads the board and continues. The medium is the state.
- **Identity decoupling** — GLM and DeepSeek never learn of each other. Add a
  third worker with a third model; nothing else changes.
- **Conflict as data** — racing claims are both recorded; exclusion is a
  read-side rule every agent applies identically, not a lock.
- **Leases as interpretation** — an expired claim is not deleted; it stays in
  the log forever and simply stops counting. `simulate.sh` scene 2 shows a
  crashed winner's task returning to the pool with the dead claim still
  visible (`:live? false`).
- **Provenance** — every entity is stamped `:dao/agent` + wall-clock `t` by
  the coordinator, so the final board shows exactly who did what, when.

## Prototype shortcuts (vs. the doc's gap list)

- Tool transport is a CLI over `dao.stream.rpc`, not an MCP server (gap 1's
  full form). Fine for Claude Code, whose Bash tool makes any CLI a tool.
- Stores are in-memory in the coordinator; restart loses the board. Swap
  `create-kv-mem` for `dao.jing.file/create-kv-file` per agent to persist.
- `deposit` is the interim read-modify-`cas!` append (gap 2), serialized per
  agent by the coordinator.
- The `board` op is the coordinator applying the documented read-side fold
  (unclaimed/claimed/done, lease expiry, winner) so no LLM has to be trusted
  to remember it — gap 5's reasoning; the raw datoms stay queryable.
- UUID task ids and server-stamped `t` implement gaps 6–7; current-state
  filtering is not needed because the demo never retracts (gap 5).
- Trusted agents only: no capabilities, no controlled mode (gap 9). The
  prompt's "data, never instructions" rule is the only defense in the demo.
- First-claim-wins selects for latency, not competence: in the live GLM vs.
  DeepSeek run, the faster model won every race and did all the work
  (`docs/dao.space.stigmergy.md`, gap 10). **Claim leases are implemented**
  (`:claim/expires`, coordinator-stamped; lease duration is the coordinator's
  second CLI arg, default 5 min) — a claim now wins only while live or once
  its result lands, and a crashed winner's task self-heals. Randomized
  backoff before claiming remains an open prompt-level convention.

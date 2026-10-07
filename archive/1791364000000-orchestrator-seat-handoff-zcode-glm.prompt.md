Created-GMT: 2026-10-07 09:27:00 GMT
Created-Local: 2026-10-07 16:27:00 +0700
Coding-Agent: zcode (glm-5.3-flash)
Session-ID: pending (incoming orchestrator session)

# Task: Lead Engineering Orchestrator Seat Handoff — AGY -> zcode glm-5.3-flash

Role: Lead Engineering Orchestrator
Incoming Model: zcode glm-5.3-flash
Outgoing Model: Antigravity (agy / Gemini 3.8 Flash)
Working Directory: /Users/sto/workspace/datomworld

---

## 1. Master Routing Rules & Owner Directives (STRICT)

1. **Claude CLI**: Session quota depleted / out of credits. Benched until ~17:50 +0700. Do NOT route any dispatches to Claude.
2. **Codex**: Quota depleted (at ~6%). Benched until ~18:15-18:25 +0700. Do NOT route any dispatches to Codex.
3. **DeepSeek**: NEVER execute deepseek via `cmd`. DeepSeek has its own CLI binary at `/Users/sto/.local/bin/deepseek`.
4. **CMD Models**: Open/commercial models run via `cmd -m qwen/qwen3.8-max` or `laguna-s-2.1-free`. Avoid expensive models like `kimi-k3`.
5. **Repo Hygiene & Magit Visibility**:
   - `collab/` contains untracked working collaboration documents.
   - Do NOT stage or commit `collab/` files.
   - Do NOT add `collab/` to `.gitignore` (User directive: owner wants to see untracked files in Magit status buffer).
   - Once a slice/stage is landed and concluded, move inactive collaboration files from `collab/` to `archive/` (which IS tracked in git).
   - Note: GitHub rejects files > 100 MB. Always gzip any archive logs > 95 MB before committing.
6. **Verification Discipline**:
   - Only stage and commit with an independent reviewer/architect sign-off.
   - Run three-lane verification before landing (`bb test:changed` or full `bb test:clj`, `bb test:cljs`, `bb test:cljd`).
   - Only run ONE ClojureDart build/test at a time across all worktrees.

---

## 2. Active Tracks Status & Exact State

### Track A: Python C3 (`yang.python` exact integers)
- **Worktree**: `/Users/sto/workspace/datomworld-s7`
- **Branch**: `yang-python-c3-s7`
- **Current State**:
  - Rebased onto latest master (including landed S6 `c681ca24`).
  - Scratch files `tmp-s7/` and `test/zz_s7_scratch_test.clj` confirmed absent.
  - Python 3.9.6 goldens verified byte-identical:
    - `python3 -I test/resources/yang/python/c3-corpus-v1.generate.py` -> 10 programs.
    - `python3 -I test/resources/yang/python/int-conv-v1.generate.py` -> 1021 measured rows.
  - Golden BLAKE3 address hashes in `test/yang/python/antlr/float_address_test.cljc` updated to match the final prelude AST.
  - `cljstyle check` is clean across all modified files.
  - **Full 3-Lane Changed Suite Passed 100% GREEN**:
    - JVM (`bb test:changed:clj`): 23 ns / 254 tests, 7,831 assertions, 0 failures, 0 errors.
    - Node CLJS (`bb test:changed:cljs`): 16 ns / 227 tests, 3,124 assertions, 0 failures, 0 errors.
    - ClojureDart (`bb test:changed:cljd`): 16 ns / 207 tests, all passed, 0 failures.
  - Early provisional review from GLM 5.3 is on disk: `collab/1791274000000-reviewer-s7-early-gate.glm.findings.md`.
  - Orchestrator rulings: `collab/1791275000000-orchestrator-s7-gate-rulings.md` (CPython 3.9.6 pow & round accept keywords; do not add `:no-kw`).
- **Next Step for Incoming Orchestrator**:
  - Dispatch final review / architect sign-off for Slice S7 using `/Users/sto/.local/bin/deepseek` (or `cmd -m qwen/qwen3.8-max`).
  - Stage changed files, commit as `feat(yang.python): ... (C3 slice S7)`, fast-forward `master`.

### Track B: Cross-Machine Stream Track
- **Worktree**: `/Users/sto/workspace/datomworld-stream-s2`
- **Branch**: `stream-crossmachine-s2`
- **Current State**:
  - S2c landed and committed (`913a740b`).
  - Rebased cleanly on latest `master` @ `3307110c`.
  - Slice S2d specification is in `collab/1791353752491-architect-stream-s2-spec.claude-fable-5-1.findings.md` (§1.4, §1.5, §3, §4):
    - Scope: WebSocket byte and frame bounds (`:ws/max-frame-bytes`, `:ws/max-pending-frames`, `:ws/max-pending-bytes`, `:ws/outbound-high-water`, `:ws/max-outbound-bytes`).
    - Host seams: `:queued-bytes` accounting in JVM client, Node/Browser `bufferedAmount`, Dart fallback.
    - Adoption-path isolation: `adopt!` try/catch block to prevent a throwing `make-media` or ack `append!` from killing the session loop.
- **Next Step for Incoming Orchestrator**:
  - Formulate and dispatch the implementation brief for Slice S2d in `datomworld-stream-s2` using `deepseek` or `cmd -m qwen/qwen3.8-max`.

### Track C: Universal Continuation Format (UCF) Track
- **Worktree**: `/Users/sto/workspace/datomworld-d10b`
- **Branch**: `ucf-d10b-kernel-lift-lower`
- **Current State**:
  - Stage D10b-B landed and committed (`d0080e5a`).
  - Rebased cleanly onto latest `master` @ `3307110c` (resolved conflict in `src/cljc/yin/vm/ucf/handoff.cljc`, preserving both D10b-B frames arity and S6 numeric?/big-carrier support).
  - Four kernels (`register`, `stack`, `effects`, `ast-walker`) verified.
- **Next Step for Incoming Orchestrator**:
  - Prepare and dispatch Stage D15 (Composition & REPL integration) brief using `deepseek` or `cmd -m qwen/qwen3.8-max`.

---

## 3. Verified Commits & Log Pointer
- Master commit: `5f010d99` (pushed to origin/master).
- Durable work log updated: `docs/orchestrator-log.md`.

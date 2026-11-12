# Engineering Remediation Dispatch: Step 4 Review Findings (B1–B6)

## 1. Context & Review Outcome
Lead Architect `deepseek-4.1-flash` conducted the independent read-only review of Step 4 (`6ee56ed1`) against §9 Exit Condition 4 and §10 Gate A/B.
Verdict: **NO SIGN-OFF. Blocking findings (B1–B6).**

You are the Lead Implementation Contributor (`qwen3.8-max`). Your assignment is to address these findings systematically in the worktree `/Users/sto/workspace/datomworld-yin-repl-dht`.

---

## 2. Blocking Findings to Remediate

### Finding B1: Session-Shape Mismatch (`:node` vs `:dht`)
- **Problem:** `main.boot` stored the node at `[:repl :dht]` (`main.cljc`), but the relocated composition functions in `yin.shell.dht` (`step`, `stop!`, `stopped?`, `close!`, `busy?`, `follow-lines`) read exclusively `(:node session)`.
- **Impact:** Stepping was inert; network/binding events, hydration progress, and announcements were never processed.
- **Requirement:** Establish a single coherent session contract. As specified in §3 and §7, the composition session should be `{:engine engine :node node :key publisher-key ...}`. If interacting through `yin.shell.session`, keep the node strictly out of the engine map (`[:repl :dht]` must be eliminated). Ensure all composition functions cleanly unpack their session/node argument.

### Finding B2: Wire Step 2 Composition Adapters in the Host Tick
- **Problem:** The adapters extracted in Step 2 (`yin.shell.session`, `yin.shell.linker/serve-links`, `yin.shell.query/serve-services`, `yin.shell.completion/completion-events`) were left disconnected in `main.step-all`. Raw events were passed into `yin.repl/recheck-on-tick`, which only matches translated generic events (`{:op :dependency ...}` / `{:op :principal ...}`).
- **Requirement:**
  - Wire `yin.shell.session/serve` (or `serve-links` + `serve-services`).
  - Wire `yin.shell.completion/completion-events` so translated events reach `recheck-on-tick`.
  - In `boot`, inject `:link-source` and `:host-modules` from the composition into the engine constructor.

### Finding B3: Finish the CLI and Host Move (Eliminate Static DHT Dependencies in `yin.repl.main`)
- **Problem:** `src/cljc/yin/repl/main.cljc` still had static `[yin.shell.dht :as repl.dht]`, bringing in `dao.space.dht` and `dao.jing.dht` into the engine's compile-time closure, violating the companion audit of Gate A. Also, `yin.shell.cli` was partially unused and circularly imported `yin.repl.main`.
- **Requirement:**
  - Complete the move of the CLI surface per §6.
  - Break circular dependency: `yin.shell.cli` should consume `yin.repl.main` primitives cleanly, or `yin.shell.main` should be established as the outer composition host entrypoint outside `yin.repl`.
  - The companion audit:
    `rg -n 'dao\.space\.dht|yin\.repl\.dht|yin\.vm\.linker\.(dht|head)|:dht\b|--dht-' src/cljc/yin/repl/ src/cljc/yin/repl.cljc`
    must contain zero implementation dependencies!

### Finding B4: Portability without `requiring-resolve` in Engine Code
- **Problem:** ClojureDart (`:cljd`) and native runtimes do not support dynamic `requiring-resolve` like the JVM does.
- **Requirement:** Ensure composition hook registration or outer composition passing is statically clean and portable across JVM, Node, and Dart without relying on runtime dynamic resolution in core engine paths.

### Finding B5: Retire Hardcoded `64` Branching Factor
- **Problem:** `yin/shell/dht.cljc` still declared `{:publication-policy {:branching-factor 64}}` without `:max-payload-bytes`.
- **Requirement:** Retire the hardcoded `64` branching factor per §5 and declare `:max-payload-bytes` derived from the effective transport configuration.

### Finding B6: Startup Failure Ownership & Test Adaptations
- **Problem:** `boot` failure must close any partially acquired resources. Furthermore, DHT tests in `dht_test.cljc`, `dht_head_test.cljc`, and `dht_end_to_end_test.cljc` were calling `create-state` with `:index-store-spec {:type :dht ...}`, which `store.cljc` rightly refused.
- **Requirement:** Update test fixtures in `dht_test.cljc`, `dht_head_test.cljc`, and `dht_end_to_end_test.cljc` to open the store via the composition factory (`yin.shell.store/open` or `yin.shell.session/make`) and pass the prepared handle and node to the composition.

---

## 3. Working Directory & Rules
- Work exclusively in `/Users/sto/workspace/datomworld-yin-repl-dht`.
- Run zero heavy test suites locally on laptop.
- Prepare clean edits and verify syntax locally.

Created-GMT: 2026-11-07 06:0x GMT
Created-Local: 2026-11-07 13:0x +07 (+0700)
Coding-Agent: codex
Session-ID: pending (provider-generated; capture thread.started.thread_id)

# Task: master-green — fix the nightly reds on the yang.python and yin.repl surfaces (owner directive: fix master before any track work)

Role: Yang Compiler and Universal AST Engineer
Implementers:
- Model: codex gpt-6.1-sol | Assigned: 2026-11-07 13:00 +07 | Status: active | Rationale: multi-cause nightly triage + fix on the python seat's own surfaces

WORKTREE: /Users/sto/workspace/datomworld-f12 (at the current master merge; parsers generated). Commit to a new branch `nightly-reds-fix`. You are the only writer. THE EXCEPTION TRACK IS PAUSED — do not touch test/yang/call/exception_* or any R1-R5 work.

## The evidence (nightly run 20261106-0944 on shiva, pinned 8d0a4ffb; per-shard logs under ~/ci/datomworld-nightly-node/target-ci/node-shards-20261106-0944/)

Your lanes' reds, Node lane:

1. **yang.python / manifest-address-test** (linked_prelude_test.cljc:258): Node computes py module address `:segment/blake3-34893d59...`; the JVM golden is `d9aead51...`. The published module (the printed manifest in the log) DOES contain the post-authentic? effects. Hypotheses to discriminate: (a) a set/map serialized in ITERATION order somewhere in the manifest composition (the `:footprint :store-keys` set is the prime suspect — JVM and Node iteration orders differ), needing canonical (sorted) serialization; (b) a genuinely divergent Node publication. If it is canonicalization, fix it in `yang/python/antlr/**` publication/serialization (sorted/canonical encoding), then REGENERATE the golden on the JVM (the checked-in generator; byte-identical double-run rule) and say so explicitly.
2. **yang.python / recursion-error-test, linked-recursion-error-test, generator-rebase-test** (+ the uncovered lane's string-forms RangeErrors, same family): Node dies with RangeError "Maximum call stack size exceeded" BEFORE the guest recursion guard fires. The JVM family was closed with -Xss (the :test alias); Node needs its equivalent: either the node test entry raises --stack-size (check how target/node-tests.js is built/configured; shadow node-test target can set execArgv), or the guest guard's depth bound is lowered to fit the Node stack. Choose the fix that keeps the guard semantics (RecursionError, never a host crash) on BOTH hosts; document the Node bound in the test or config.
3. **yin.repl / main_test the-interactive-composition-holds-every-standard-language** (main_test.cljc:354-360): the Node session catalog reports `installed: :clojure :python` — PHP IS MISSING from the Node composition. Find why the Node build drops the php frontend (a build config, a load failure swallowed at composition, or a frontend-catalog registration regression) and fix.
4. **yin.repl / repl_test language-and-compile-commands** (repl_test.cljc:194-197): a `(compile ...)` command answers "Error: Unexpected EOF while reading item 3 of vector." instead of the AST/rows preview. Reproduce on the JVM first — if JVM answers correctly, the divergence is Node-specific reading of the command form (tools.reader vs cljs.reader?); if JVM fails too, it regressed at HEAD and is host-neutral.
5. **yin.repl / dht_test rounds-larger-than-the-pending-write-bound** (dht_test.cljc:2105): 8 rounds expected to acknowledge at 8 DISTINCT times, got 2 distinct. Likely a timing/rounding collapse (all acks coalescing into one instant) — check the test's clock source and the acknowledged-at computation.

OUT OF YOUR SCOPE (other seats'): yang.base stream_eval:444 (the dao.stream memory-log identity across VMs — relayed separately); the dart lane's 7 unnamed failures (a dedicated run follows your fixes).

## Rules

- Local (macOS) verification for everything JVM-reachable. For the NODE-leg verification: I (the orchestrator) will run ONE scoped node-shard verification on shiva after your round lands — do NOT ssh anywhere.
- The exception track's red-ledger suites are ^:slow-gated; do not un-gate them, do not read them as regressions.
- No prelude semantics changes beyond what each fix requires; every fix carries a focused test pinning it.
- Commit in logical units per fix (manifest canonicalization / node stack / php catalog / compile EOF / dht ack).

## Deliverable

Final agent message: per-fix root cause with file:line, what changed, the regenerated goldens (if any), the local JVM counts per touched namespace, and anything you could NOT verify locally (flag for my shiva run).

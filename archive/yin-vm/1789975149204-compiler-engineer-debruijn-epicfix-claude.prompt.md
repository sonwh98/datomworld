Created-GMT: 2026-09-21 07:19:09 GMT
Created-Local: 2026-09-21 14:19:09 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: 69f82e15-85c9-4754-a273-a5f4ad68d932
# Task: debruijn-epicfix-claude — epic-audit fix round F1-F4, F6, F7 + D6 gap-list tests
Role: Yang Compiler and Universal AST Engineer
Implementers:
- Model: claude-opus-5 | Assigned: 2026-09-21 14:19:09 +07 | Status: active | Rationale: owner reroute — glm-5.3 is at 68% weekly usage (resets Sep 27 01:26 +07) and the claude pool expires Sep 22 04:00 +07; opus-5 is the team's AST/hashing specialist
- Status-Event: 2026-09-21 14:19:09 +07 | Model: glm-5.3 | Status: reassigned | Rationale: 429 five-hour cap mid-refactor (resets 17:26:16 +07); the half-done edit was discarded (patch kept outside the repo), tree restored to the last green state 8ed66e3a

You are a fresh implementer with no prior context. Everything you need is in
this brief and the files it names. Work in the git worktree
/Users/sto/workspace/worktree-debruijn-impl (branch debruijn-impl, HEAD
8ed66e3a, four green commits D0-D4). Do not touch any other tree.

## Read first (in order)

1. /Users/sto/workspace/datomworld/docs/design/yin.vm.debruijn-projection.md —
   the governing design. Every sentence in §1 (invariants), §2 (framing,
   node grammar), §3, §4, §5 (Merkle fingerprint, canonical encoding), §6, §8
   (test matrix) and §9 is a rule.
2. /Users/sto/.claude/plans/read-collab-1789968388070-reviewer-debru-proud-rossum.md —
   the full opus-5 epic audit (findings F1-F9, with REPL evidence).
3. src/cljc/yin/vm/debruijn.cljc, test/yin/vm/debruijn_test.cljc,
   src/cljc/yin/vm/pipeline.cljc, test/yin/vm/pipeline_test.cljc (the last two
   are untracked D5 files that are already green — treat them as fixed unless
   F9 demands otherwise).

## Current state (verified by the orchestrator, 2026-09-21 14:13 +07)

Focused JVM on `yin.vm.debruijn-test` + `yin.vm.pipeline-test`: 71 tests,
288 assertions, 0 failures, 0 errors. Keep them green throughout; new
fixtures add to the counts.

## Deliverable

Edit ONLY: src/cljc/yin/vm/debruijn.cljc, test/yin/vm/debruijn_test.cljc,
test/yin/vm/pipeline_test.cljc (src/cljc/yin/vm/pipeline.cljc only if F9
demands it). Do NOT stage, commit, branch, merge, or push. Do not touch
docs/, collab/, pubspec.*, or any other file.

- **F1 (sets merge silently, ~line 982)**: canonicalizing a set whose
  elements merge under canonicalization — `#{1 1.0}`, or two spellings of é —
  must diagnose `:unsupported-value` when the canonical count shrinks,
  exactly like the map rule at ~974-981. Fixture: a colliding set literal is
  diagnosed, on every host the lanes cover.
- **F2 (reader checks hashes, not records)**: the storage reader must check
  each record's slots against its node type per the §2/§4 grammar (`:key` on
  a `:variable` diagnoses; the renamed-slot record from the audit) and must
  require canonically-spelled values (a stored `1.0` where the int64
  canonical is `1` diagnoses; a decomposed-NFC string diagnoses) — WITHOUT
  changing any minted hash or the descriptor digest. Fixtures: the
  renamed-slot record and the noncanonical-spelling record both diagnose.
- **F3 (exponential hashing on shared subgraphs, ~1035-1061)**: `merkle-node`
  walks every child before the preimage memo can hit, so a doubling shared
  chain costs 0.8s → 2.0s → 7.0s at 75/85/95 datoms. Carry the computed hash
  through the resolver's `[eid context]` memo so a shared subtree is hashed
  once. Regression fixture: a deeply-shared doubling chain must project with a
  deterministic bound on node-hash invocations (instrument the seam
  honestly — a counter argument or an injectable — NOT a wall-clock
  assertion) and must equal the tree-built equivalent's fingerprint. Pinned
  fingerprints (`095c83f7…` essay, the D3 byte fixtures) must not move: the
  memo is output-neutral.
- **F4**: a known attribute on a node type whose grammar row doesn't list it
  diagnoses rather than being ignored (reader-side; the emitter never emits
  one). Include the dangling `:yin/body` on a literal case.
- **F6**: find any remaining path where an internal defect surfaces as
  `:invalid-input` and classify it `:internal-error` (the D4 rule).
- **F7**: a batch budget of 0 must not return `:continue` forever — treat it
  as invalid input.
- **F9 + D6 gap-list tests**: a nil-valued datom round-trips through real D5
  storage without a hash mismatch (pipeline_test); the renamed-program pair's
  STORED datoms compare equal; destination `:invalid-value` and
  `:transport-error` outcome tests; terminal idempotence beyond
  `:partial-frame`.

Deferred, no action: F5 (three local atoms in `projected->datoms` — contained,
note only); F8 (macro detection limit is §1's documented inherited limit).

Cross-host law (§5 and the repo's portability rules): this is `.cljc` and runs
on JVM, CLJS and CLJD. Compare, hash and canonicalize identically on all
three; beware that `=` merges lists with vectors and `0.0` with `-0.0` (the
D2 P1) — do not key anything by Clojure equality where identity matters.

## Environment

The repo's mise is untrusted in this worktree; export before running builds:

    M=$HOME/.local/share/mise/installs
    export JAVA_HOME="$M/java/17"
    export PATH="$JAVA_HOME/bin:$M/clojure/1.12.4.1602/bin:$M/babashka/1.13.223:$M/babashka/1.13.223/bin:$M/flutter/3.47.4-stable/bin:$M/node/25.6.1/bin:$M/cljstyle/0.17.642:$M/cljstyle/0.17.642/bin:$PATH"

For the CLJS lane only, use Java 21 (the closure-compiler ships class-file
v65): `export JAVA_HOME="$M/java/21"` and put `$JAVA_HOME/bin` first on PATH.

Run ONE simple command per step — headless `acceptEdits` auto-approves edits
but can deny a compound or piped Bash command; a denial means split it, never
a workaround, and never `--dangerously-skip-permissions`.

## Verification (report exact commands and counts)

- JVM focused: `clojure -M:test -n yin.vm.debruijn-test -n yin.vm.pipeline-test`
- kondo on every touched file (e.g. `clj-kondo --lint src/cljc/yin/vm/debruijn.cljc`)
- `cljstyle check` on every touched file
- CLJS lane: `bb test:cljs` (Java 21) — F1-F3 touch hashing, so cross-host
  agreement must hold
The orchestrator reruns everything itself, including CLJD; delegated counts
are untrusted. If an API error interrupts you, resume where you left off on
retry. The final response must promise nothing: state what you changed, what
you ran, and every deviation from this brief.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 69f82e15-85c9-4754-a273-a5f4ad68d932

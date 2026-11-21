Created-GMT: 2026-11-21 09:50:00 GMT
Created-Local: 2026-11-21 16:50:00 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: codex
Session-ID: pending (provider-generated)

# Task: yang.preludes P1′ — yrt.effect + yrt.int with Clojure-only consumers; unified trusted clj

Role: Engineer

Implementers:
- Model: gpt-6.2-sol | Assigned: 2026-11-21 16:50 +07 | Status: active | Rationale: most recently proven codex model on this host (P1c/P1d rounds landed 2026-11-20)

## Working tree

Work EXCLUSIVELY in `/Users/sto/workspace/dw-p1prime` (branch `feat/p1prime-yrt-effect`, based on master `f23728d3`). All paths below are relative to that root. Do NOT touch `/Users/sto/workspace/datomworld`.

When you create NEW files (including test files), run `git add -N <file>` on every one so patch export includes them.

## Governing design

`docs/design/yang.preludes.md` r8 (landed at `b22dff6f`) is signed off and binding. Read these sections before writing code:

- §4.3 `yrt.effect` — the complete spec: 5-slot frames `[kind payload rest n depth]`, `data/nth` frame reads, unwind traversal (one rule per frame kind), `restore!` `:base` invariant, the public export table (abortive + resumable), the bundled `yang.clojure` capture-safety paragraph (`dsg__get`/`dsg__equal`/`dsg__assoc`, `dsg__add`/`dsg__lte`).
- §4.4 `yrt.int` — refusal algebra mapping, `check-limit`/`check-limit-with`, fixed-width wrapping, radix scanners.
- §7.4 unified trusted `clj` — two-step `make-callable`, `admitted-callable?`, the 4-step §8.10.7 entry boundary (Steps A–D), `invoke-callable`, `prim-get`/`prim-assoc`, bundled-mode door enforcement, private provenance doors owned by `clj` only.
- §9 roadmap row "P1′" — your acceptance gates (quoted below).

Reviewer conditions that BIND (from the signed-off chain — do not re-derive):
- `%capture` continuations are task-local, non-portable; generator suspension cannot serialize a paused `k` across tasks/streams.
- `raise` has ONE rule per frame kind: `:finally` → run cleanup exactly once, continue unwinding; `:effect` → pop, restore, continue unwinding; `:handler`/boundary → deliver and stop. Never deliver to an `:effect` frame.
- Role authorization is keyed `(module address, export symbol, exact role)`; policy tags live in the handler contract while only underlying VM effects are declared through the linker footprint.
- `perform` walks to the nearest matching `:effect` frame WITHOUT running `:finally` frames; only the abort-without-resume path runs the traversed cleanups.

## Scope

1. **Implement stateless `yrt.effect`** per §4.3, following `yang.core.call`'s single-source dual-emitter pattern (one ordered definition vector emitting both a bundled `uast` and a linked `module-uast` + `module-spec`). Host dependencies: `cell/get`, `cell/set!`, `cell/new`, `data/nth`. Frame slots: `kind`/`payload`/`rest`/`n`/`depth`, where `n` is the monotonic handler-stack frame count. Export set per §4.3's table: `ctx`, `handlers`, `set-handlers!`, `restore!`, `frame-depth`, `frame`, `pop-frame`, `raise`, `try-value`, `try-orelse`, `try-finally`, `unwind-to`, `call-ec`, `abandon-to`, `call-terminal`, `handle`, `perform`. Every frame-vector read uses `data/nth`; `get` only for context-map fields.
2. **Implement `yrt.int`** per §4.4 (requires `yrt.effect`). Backed by the landed `yin.vm.integer` v4 refusal algebra at `src/cljc/yin/vm/integer.cljc:81–100` — the three limit keywords perform guest policy effects; all other refusals throw and MUST NOT be caught or relabeled.
3. **Upgrade `clj` (`src/cljc/yang/clojure/prelude.cljc`) to the unified trusted form** per §7.4: two-step `make-callable`, `admitted-callable?`, the 4-step entry boundary, `invoke-callable`, exported `prim-get`/`prim-assoc`, private provenance doors inside BOTH linked and bundled emitters (never exported, never in `yrt.*`). Migrate the 4-slot frames of `js/init!`-equivalent / `clj/init!` and bundled `yang.clojure` to `(yrt.effect/ctx nil 0 0 nil)` 5-slot frames per §4.3. Retire `yang.clojure`'s `dsg__call_token` / inline provenance ops (currently at `src/cljc/yang/clojure.cljc` lines ~1271, ~2439–2475) — the unified `clj` path replaces them.
4. **Add `dsg__add`/`dsg__lte`** for bundled `clj` (portable guest closures for `+`/`<=` in the bundled capture-safety paragraph of §4.3).

**Out of scope — do NOT touch:**
- py/js/php preludes and their goldens (`src/cljc/yang/python/`, `src/cljc/yang/typescript/`, `src/cljc/yang/php/`) — Clojure-only consumers this slice. The frame design anticipates py/php `:depth` but only clj exercises it now.
- `clojure.core.impl` / `clojure.core` (P2′), the python lowerer and descriptors (P3′), Track B.
- The known pre-existing master red `yang.python.antlr.linked-prelude-test` (71 vs 72) — exclude from your nets; do not fix it.

## Acceptance gates (from §9 P1′ row — all must hold)

- `yrt.effect` unit tests: abortive (`raise`/`try-value`/`try-orelse`/`try-finally`/`call-ec`), `:finally` exactly-once cleanup (including a throw past an unrelated effect frame — §4.3 r8 gate), resumable one-shot `handle`/`perform`, cold policy traps, across semantic/stack/register VMs.
- Bundled/linked `yang.clojure` parity under a shadowed `(defn get ...)` source definition.
- clj-only synthetic nonzero `:depth`/`:base` fixtures exercising the 5-slot `depth` slot and the `restore!` `:base` invariant.
- Independent concurrent `clj` tasks sharing `yrt.*` (task-local `ctx-cell` isolation).
- Bundled == linked parity and 4-host where lanes allow (JVM + Node at minimum on this machine; note any host you could not run).
- `clj ./bin/analyze-deps.clj` clean — analyzer self-loops 3→0 is the P0′ baseline; keep it at 0, no new cycles/warnings.
- Goldens: refresh `clj`/`yang.clojure` goldens cleanly; py/js/php goldens byte-identical (verify with `git status`/`git diff` that none of their files changed).
- Per §9 H7 repair note: native collection invocation (locally-bound-map `:k` → 9) and the real cross-syntax unwind gate in P2′/P3′ — NOT yours.

Test invocation notes: use `mise` to activate the toolchain (`eval "$(mise env)"` equivalent — the shell here is zsh with mise shims; `bb` resolves only after `mise env`). Focused namespaces only locally; suites are not yours to run.

## Deliverable

- The implementation on branch `feat/p1prime-yrt-effect` in `/Users/sto/workspace/dw-p1prime`, uncommitted (you have NO commit authority — leave changes staged-by-intent or unstaged; do not `git commit`).
- `git add -N` every new file.
- A summary (your final report) listing: files added/changed, each gate above with the exact command run and pass/fail, the exact commands for focused tests, any gate you could not run on this machine and why, and any design ambiguity you resolved (with the §/quote you resolved it against).
- Known project discipline: match surrounding code style; deterministic canonical ordering per §8.1 (byte-identical `dao.jing.cbor` hashes across hosts); no JVM-only reflection; zero raw host doubles in guest UAST (boxed `yin.vm.float` only).

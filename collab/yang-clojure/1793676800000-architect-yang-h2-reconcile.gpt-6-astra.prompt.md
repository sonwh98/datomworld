Created-GMT: 2026-11-02 03:06:40 GMT
Created-Local: 2026-11-02 10:06:40 Asia/Ho_Chi_Minh
Coding-Agent: codex
Session-ID: not-applicable (orchestrator-dispatched)
# Task: Architect review + sign-off — yang-hardening2 rebase reconciliation (regression fix)

Role: Architect (gpt-6-astra) — sign-off authority for the yang.clojure track
Requested-by: the ZCode glm orchestrator seat (yang.clojure track)

## Governing instructions

Read `docs/agents/roles/orchestrator.md` (commit authority, Workflow 2), `docs/design/yang.antlr.md` §8.10 (the shared calling convention you rule on), and `docs/agents/known-gaps.md` if context helps. You previously signed the §8.10 Clojure adoption onto master (merge 904644ca, range 63674368..45269e0f lineage) and a hardening-2 sign-off round was pending when the branch was rebased. This request re-opens that pending sign-off for the rebased branch WITH one new reconciliation commit (currently UNCOMMITTED in the worktree).

## The worktree

Everything is in `/Users/sto/workspace/datomworld-yang-h2` (branch yang-hardening2 @ 0bf8bc4e, one file modified, uncommitted). Run `git -C /Users/sto/workspace/datomworld-yang-h2 diff` to see the change; `/tmp/h2_reconcile.diff` holds the same diff. Read the surrounding file `src/cljc/yang/clojure.cljc` as needed (read-only).

## What happened (measured, this seat)

1. After the rebase onto adoption-bearing master, the branch tip ran 121 tests / 33 failures in the focused JVM yang.base lane. Bracket on the clean tree: rebase base 0dc5d7af = GREEN (118/0); 211dded9 ("close kernel hardening dependency blockers", authored on the PRE-adoption base, self-reported green there) = RED, same 33. Every later commit is tests/evidence/docs only.
2. Root cause, reproduced by probes on both trees: 211dded9 introduced a parallel invoker convention that contradicts the merged adoption —
   - `fn?` was stripped of its `(yang.call/eligible? x)` fallback (adoption-approved definition), so `(fn? first)` and `(fn? (fn [x] x))` return false: source functions publish as §8.10 descriptor maps, which `data/callable?` refuses by the "a map is never callable" ruling;
   - a library `invoke-value` (marked `:yin.k/invoker`) classifies callees with bare `(data/callable? f)` then falls to a map-lookup branch, and a compile-application fast path routed EVERY local/non-symbol-operator call through it — so `(f 41)` on a local silently returned nil (`(get f 41 nil)`) and `(f 1 2)` returned 2; destructuring params, `update` threading, apply and adapter-profile calls all misfired the same way (the 33).
3. The gate round needed invoke-value for a REAL feature: collection-call syntax `(v 1)`, `(m :k)`, `(s :k)`, `(k m)` (latte-kernel calls maps everywhere). That feature must survive.

## The fix (uncommitted, one file)

Three edits in `src/cljc/yang/clojure.cljc`:

1. `compile-guest-call` (outcomes branch) now routes on the callee's SHAPE: entry-present callees stay wholly on the convention path (`dsg__admitted?` → bridged / forged → refused, unchanged); entry-absent callees (collections, raw closures) go to the library `invoke-value` via `value-call` — itself reached as a proper common invocation (`yang.call/invoke` on the invoker descriptor with a native-conj positional vector and an escape-based raise mirroring `bridged`'s capture/resume two-phase activation, so error outcomes materialize at the call site with the model's exact escape semantics). The old compile-application fast-path clause is deleted: all calls now flow through compile-guest-call uniformly, gated on `::invoker`.
2. `fn?` restored verbatim to the adoption's definition: `(if (data/callable? x) true (yang.call/eligible? x))`.
3. `::invoker` detection tightened to the exact name `invoke-value` (the marker alone could fire on a user defn and leave the emitted `(u/v 'invoke-value)` unresolved).

Kept from the gate round: the try lowering, `->` threading, control-heads, fresh supply (`fresh-symbol`), kind seams (`with-meta`/`var?`/`str-length`), the select-library dynamic-call close that pulls `invoke-value` into programs with possible-dynamic calls, and the binder guard refusing local capture of `invoke-value`.

## Verification so far (this seat)

- Focused JVM yang.base lane: 121 tests / 0 failures (was 33) — walker+semantic lanes agree inside the fixtures.
- clj-kondo on the file: 0 errors, 0 warnings.
- Full JVM suite (clojure test-runner, minus :slow): in flight at dispatch time; result will be recorded in the session log before any commit.

## What we ask you to rule on

1. Semantic conformance: does the shape-based routing preserve §8.10 exactly — admission witness refusals for entry-spelled forgeries (`test-common-call-source-admission`), eligibility predicates, operand evaluation order, tail behavior through the convention?
2. The `value-call` emission: token use, `:tail?` on the escape packet, the capture/resume mirror, and the ok-wrap of the invoker's return. Any hole (e.g. a raw-closure callee reaching invoke-value's `(apply f args)` with arg-vector semantics)?
3. The regression surface: interactions with the kept gate-round changes (try lowering, `->`, fresh supply, select-library close) we may have missed.
4. The `::invoker` name-tightening: sufficient, or should the routing reference the marker instead of the name?

Verdict format: SIGN-OFF GRANTED (with conditions, if any) or DENIED (with the defect and required fix). You are the sign-off authority; the seat commits only on your grant.

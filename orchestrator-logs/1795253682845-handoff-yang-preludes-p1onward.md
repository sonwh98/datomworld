Created-GMT: 2026-11-21 09:34:42 GMT
Created-Local: 2026-11-21 16:34:42 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: codex (any seat per owner assignment)
Session-ID: pending (caller-generated on first dispatch)

# Task: yang.preludes Implementation — P1′ Onward (Clojure-First Roadmap)

Role: Lead Engineering Orchestrator

Implementers:
- Model: <owner-assigned orchestrator model> | Assigned: <on seat start> | Status: active | Rationale: fresh orchestration for the new-runtime construction phase, per the accepted split (the outgoing seat ran P0′; this seat takes P1′ onward)

Coordinate the **P1′-onward implementation of `docs/design/yang.preludes.md` r8** in
`/Users/sto/workspace/datomworld` (or a fresh clone on the designated host). The design is
signed off and landed; this seat's job is the remaining slices, one dispatch at a time,
through the standard chain.

**Outgoing seat context:** the prior orchestrator session log is
[`orchestrator-logs/1794767085-zcode-glm-5.3-flash-yang-compilers-dry-round.md`](../../orchestrator-logs/1794767085-zcode-glm-5.3-flash-yang-compilers-dry-round.md)
(the same file covers the DRY rounds AND the preludes P0′ slice; every claim in it is a
claim to re-verify, per the workflow). The signed-off design is on master:
`docs/design/yang.preludes.md` r8 at `b22dff6f`, landed P0′ at `7d6923e8`, record archived
at `4e84b558`.

## What is already done (do NOT redo)

- **The DRY rounds**: all compiler duplication consolidated into `yang.core.*`
  (`1604aabe` round 1; `c9dbc991` round-2 batch 1; `0d1ab55d` round-2 batch 2). The
  prelude/prelude-admission seam exists and is proven.
- **The design**: r8 signed off — astra architect SIGN-OFF (thread
  `01a1faa3-bb7e-7c30-b3bf-cedbad008bfc`, 3 confirmation rounds), deepseek VM-engineer
  SOUND (session `8e4174e5-ebb7-499a-bca8-60827de40a38`), argon conformance
  ALIGNS-WITH-AMENDMENTS (thread `b6f5ac15-ed18-491a-8ac1-2e43ddfdf0eb`). All reviewer
  conditions are folded into the doc itself.
- **P0′ landed** (`7d6923e8`): `:pure` host core additions (`data/equiv`, `admit-key?`,
  `canonical-compare`/`canonical-entries` with Jing encoded-byte normative order, boxed
  NEW `yin.vm.float` 4-host, `integer/to-float-boxed`/`true-div-boxed`/`from-float-boxed`),
  `yang.core.defquoted` consolidation, recipe/profile schemas, `validate-profile`/
  `admit-profile` in `prelude-admission` (5 publics), migration recipe records, and the
  `key.cljc` carrier-normalization wiring. Analyzer self-loops 3→0.

## Your slices (per the signed-off §9 r7-reordered roadmap)

1. **P1′ — `yrt.effect` + `yrt.int` with Clojure-only consumers; unified trusted `clj`**:
   stateless `yrt.effect` (5-slot `data/nth` frames, abortive `raise`/`try-value`/
   `try-orelse`/`try-finally`/`call-ec`, resumable `handle`/`perform`) and `yrt.int`;
   upgrade `clj` to the unified trusted form (two-step `make-callable`, `invoke-callable`,
   `prim-get`/`prim-assoc`, private provenance doors, retiring `yang.clojure`'s
   `dsg__call_token`); add `dsg__add`/`dsg__lte` for bundled `clj`. **py/js/php preludes
   untouched** — Clojure-only consumers this slice; the frame design anticipates py/php
   `:depth` but only clj/clj.py exercise it. Gates: clj-only synthetic nonzero depth/base
   fixtures; bundled == linked + 4-host; analyzer clean.
2. **P2′ — unified `clojure.core` + `yang.clojure` migration**: `clojure.core.impl`
   (pure, `impl-*` prefix, `admit-key?`-guarded) + cell-free `clojure.core` facade;
   migrate `yang.clojure` onto unified `clj` + `clojure.core`. Gates: the locally-bound-map
   `:k`→9 fixture (native data-call dispatch for locally bound maps), bundled
   `get`/`assoc` shadowing, unified `yang.clojure` suite green.
3. **P3′ — descriptor parameterization + `clj.py` + the DEFAULT flip**: `yang.core.semantics`
   validation, the python frontend contract, `clj.py` adapters, python lowerer
   descriptor-parameterization (the **legacy `py-v1` descriptor pins today's `'py/*` hooks
   1:1 — behavior-preserving, gated by the existing CPython oracle suites**), then the
   default flip (a revision re-pin in `yang.frontend`). Gates: legacy-descriptor behavior
   preservation (full CPython corpus + 4-host under `py-v1`), the end-to-end
   `:yang.python/clojure-core-v1` suite, capability refusals, UAST-derived validation,
   **the cross-syntax `clj → clj.py → clj` unwind gate SOLELY here** (after `clj.py`
   exists), and the default-profile regressions (unqualified `:k` admission,
   cross-carrier integer identity, `:clj/unbound` distinguishability, Jing encoded-byte
   traversal order).
4. **P4′ — additional N→1 profiles** (ts, then php descriptor contracts).
5. **Track B (deferred, on owner demand)**: native prelude re-architecture; the r6 P1/P4
   gates are attached by value in §9's Track B row.

## Reviewer conditions that BIND (from the signed-off chain — do not re-derive)

- Continuation-portability envelope: `%capture` continuations are **task-local,
  non-portable**; generator suspension cannot serialize a paused `k` across tasks/streams
  (deepseek P1, reconciled).
- `integer/compare-float-boxed`/`from-float-boxed` exist (batch P0′); the landed raw
  `compare-float`/`from-float` are legacy-only.
- The `get` → `data/nth` frame-read conversion is a **behavioral migration** with its own
  shadowed-`get` parity gate — for Track B, not P1′.
- Role authorization is keyed `(module address, export symbol, exact role)`; `raise` has
  ONE rule per frame kind; policy tags live in the handler contract while only underlying
  VM effects are declared through the linker footprint (astra r8 round 3).
- Entry-lowering contracts carry BOTH the landed native entry shapes (py 1-arg, js
  1-operand, php 2-or-4 with request stream) and the Clojure 2-arg resolver shape (astra
  H2) — relevant from P3′.
- Oracle assignment: native-oracle suites gate the LEGACY pins; capability-refusal suites
  gate the default profile (astra M-level, reconciled).

## Known live constraints

- **php seat coordination**: the prelude-clear terms stand (single-writer surfaces;
  first-claim-wins in `collab/yang-php/`). P1′/P2′ touch NO php surfaces; P3′ touches the
  python lowerer (the php seat's const-admission round landed its `lower.cljc:546` change —
  re-check that file's state before the P3′ descriptor work). Relay claims per the
  established protocol when a slice crosses a claimed surface.
- **One pre-existing master red, owned by the php seat**: `yang.python.antlr.linked-prelude-test`
  count 71 vs 72 (`py.b/AssertionError` from `60cda9b6` without the
  `linked_prelude_test.cljc:191` update) — relayed in
  `collab/yang-php/1795211000000-from-dry-orchestrator-pre-existing-red-linked-prelude-count.md`.
  Exclude from your nets; do not fix it yourself.
- **Deferred items on file**: C7 (`core.literals`), P2-1 residual (prelude/lower
  public-surface audit) — not yours unless the owner says so.

## Required workflow

- Follow `docs/agents/roles/orchestrator.md#workflow` in order. Create YOUR OWN session log
  at `orchestrator-logs/<unix-ts>-<agent>-<model>-yang-preludes-p1onward.md`; never append
  to the outgoing seat's log.
- Dispatch per `docs/agents/delegate-invocation-reference.md`: **codex and agy are the
  primary coding agents** (owner routing posture 2026-11-19); `qwen` unavailable until
  2026-12-05; deepseek was 402 Insufficient Balance as of 2026-11-20 (check
  `docs/agents/routing-status.md` — the board was reset 2026-11-19; hydrogen/neon share a
  fast agy window, separate from argon's).
- Jupiter dispatch mechanics (lessons of record): full codex path
  (`~/.local/bin/codex`); `mise env` before any `bb`/lane; `codex exec resume` mounts
  READ-ONLY on 0.170 (do not resume for write work — fresh session with a resume-by-state
  brief instead); long rollouts get pruned (the 01a1e592 thread died at resume on
  2026-11-20) — keep briefs self-contained; `add -N` ALL new files (including tests) before
  exporting patches; scp briefs, never heredoc them through ssh.
- Gate discipline per `docs/agents/build-n-test.md`: focused namespaces locally/Jupiter;
  suites on shiva/jupiter; `dao.stream` precondition on every landing gate; the frozen-track
  net (py/php parity + prelude namespaces) stays green every slice — EXCLUDING the php
  seat's known red above.
- Commit authority: step-7 review (independent family; sol's old thread is pruned — fresh
  sol sessions work) + owner authorization per landing.

## Acceptance for this handoff's first unit

P1′: implemented per the design §4.3/§4.4/§7.4 with Clojure-only consumers, focused gates
green (clj-only synthetic depth/base fixtures, bundled==linked, 4-host where lanes allow),
analyzer clean, step-7 APPROVED by an independent family, owner authorization, landed and
fresh-verified. Then P2′ per the same chain.

Begin your first response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: <agent>
Session-ID: <exact session id>

Then report: delegated roles/models with prompts and session IDs, verified findings, test
outcomes, unresolved risks, and readiness per slice.

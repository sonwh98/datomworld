Created-GMT: 2026-11-19 17:32:25 GMT
Created-Local: 2026-11-20 00:32:25 +07 (Asia/Ho_Chi_Minh)

# Task: yang-preludes-r8-vm-engineer-review

Role: Yin.VM Runtime Engineer (read-only design review)

Implementers:
- Model: deepseek-v4-pro | Assigned: 2026-11-20 00:32:25 +0700 | Status: active | Rationale: VM-runtime lens on the signed-off design; deepseek is independent of the argon/astra/codex families that produced and reviewed it

Perform a read-only architecture review of the signed-off r8 design
`docs/design/yang.preludes.md` — specifically its **Yin.VM runtime claims**. Do not edit
files. Your final response is the deliverable.

## Read first

- `docs/design/yang.preludes.md` — the design under review (r8, architect sign-off WITH NOTES applied; focus §§2, 3, 4, 7).
- `docs/design/datom.world.md` — foundational axioms, non-negotiable invariants.
- `src/cljc/yin/vm/docs/state.md` — VM state/provenance doors.
- `src/cljc/yin/vm/integer.cljc` and `src/cljc/yin/vm/data.cljc` — the existing `:pure` host-module pattern the design extends.
- `src/cljc/yang/core/call.cljc` — the dual-emitter pattern and calling convention the yrt.* modules must follow.
- `src/cljc/yang/python/antlr/prelude.cljc` (frames region ~:284) and `src/cljc/yang/php/antlr/prelude.cljc` (frames ~:190) — the landed frame mechanics the 5-slot design generalizes.

## Evaluate (the VM-engineer domain)

1. **CESK machine integrity**: does the three-tier boundary (`:pure` host core / guest `yrt.effect` over `%capture` / minimal VM-stream effects) preserve the formal correspondence between Control, Environment, Store, and Kontinuation? Does moving policy traps (`:math/div-zero`, `:int/bit-limit`, …) from host throws to guest `yrt.effect/perform` change any Store/Kontinuation state the machine relies on?
2. **Continuation portability**: `%capture`-based delimited continuations (`handle`/`perform`, resumable one-shot) and the task-local `ctx-cell` design — are captured continuations serializable/portable per the "everything is a continuation" axiom, or does the design silently assume host closures inside captured continuations? Is the `restore!` `:base`-preservation rule sufficient across suspension/resumption?
3. **Execution parity**: the design claims bundled == linked emission parity and 4-backend (semantic/AST-walker/register/stack) parity for the yrt.* modules. From the frame design (5-slot vectors, `data/nth` reads, boxed `data/float64` carriers), identify any construct that could diverge across the four backends (e.g. keyword collapse on CLJS, float carrier handling on CLJD, Jolt kernel interactions).
4. **Frame mechanics**: the 5-slot frame `[kind payload rest n depth]` generalizing the landed Python/PHP frames — is the `n`-slot O(1) depth comparison sound given the landed `:depth`-vs-frame-count distinction? Does `pop-frame`'s `:depth` restoration match the landed PHP recursion accounting and Python `:base` rebasing?
5. **Boxed float boundary**: does `integer/to-float-boxed`/`true-div-boxed` + `float/*` fully eliminate raw host doubles from guest frames on the boxed-path profiles? Any residual path (comparisons, `floor`, `fmod-pos`, `parts`) where a raw double could leak?
6. **Provenance doors**: the design bans `cell/dsg__*` from `yrt.*`/Layer-2c and keeps them in trusted Layer-2b `clj` — is the private-door retention inside both bundled and linked `clj` emitters consistent with `state.md`'s door contract?

## Report format

Begin exactly:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +07>

Then findings with severity (P0 blocker / P1 risk / P2 concern) | section | evidence | correction; confirm the properties that passed. End with exactly one line:
VM-ENGINEER VERDICT: SOUND | SOUND WITH CONCERNS — <them> | UNSOUND — <why>

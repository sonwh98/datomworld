Created-GMT: (in the report body)
Created-Local: (in the report body)
Coding-Agent: deepseek
Session-ID: 8e4174e5-ebb7-499a-bca8-60827de40a38
# Task: yang-preludes-r8-vmengineer-review
Role: Yin.VM Runtime Engineer

Completed-GMT: 2026-11-20 20:47:51 GMT
Completed-Local: 2026-11-21 03:47:51 +07

## Findings

### P1 risk | §2.1 axiom 4 / §4.3 `handle`-`perform` | Continuation-portability envelope under-specified

`%capture`-based delimited continuations (resumable one-shot `handle`/`perform`, and the Track-B generator driver that "stores the captured continuation `k` and resumes `(k sent-val)`") place `k` in the task-local `ctx-cell`. `state.md` states the lift encoder refuses closures/continuations as `:yin.k/non-portable`, and application/invocation refuses a continuation whose owner is not the task's. The design never pins whether `%capture` reifies a portable `yin.vm.values/Continuation` or a host reification, so captured continuations are **task-local and non-serializable**. This is fine for the landed within-task one-shot pattern, but sits in tension with the "everything is a continuation" portability narrative and any Track-B generator checkpoint/stream serialization. The `restore!` `:base`-preservation rule is sufficient *within* a task's suspension/resumption, not *across* tasks.

**Correction:** add one sentence pinning the envelope — `%capture` continuations are task-local, non-portable, resumable only within their task — and state explicitly that generator suspension cannot serialize a paused `k` across tasks/streams.

### P1 risk | §7.2 item 3 mixed int×float comparison | `integer/compare-float` is a residual raw-double path

`integer.cljc` v3/v4 `compare-float` takes a **raw host double** (`float!` → `host/host-float?`), as does `from-float`. §7.2 says mixed `<`/`>` delegate to "`integer/compare-float` (via boxed float helper)", but §4.1's integer additions list only `to-float-boxed`/`true-div-boxed` — no `compare-float-boxed`. Either `clj.py/lt` unwraps a boxed `data/float64` via `data/float-value` (a raw double transiently on the guest stack, violating invariant 3's zero-raw-double claim on a hot path), or a new boxed export is required but unlisted. Promoting the int to float would round it, so exact int×float comparison cannot substitute.

**Correction:** list `integer/compare-float-boxed` (and note `from-float-boxed`) in §4.1, or explicitly document the unwrap-and-call as the single sanctioned raw-double crossing.

### P2 concern | §4.3 vs landed frames | `data/nth` hardening diverges from landed `get` reads

The design mandates "every read of the 5-slot frame uses `data/nth`" to defeat guest `(defn get …)` shadowing, but the landed `py`/`php` frames read slots via bare `get` (`(get hs 0)`/`(get hs 3)`/`(get hs 4)` in `frame-depth`, `raise`, `pop-frame`). The Track-B migration of py/js/php onto `yrt.effect` is therefore a **semantic change** (capture-safety), not a rename — the "bundled == linked" parity gate must prove the shadowed-`get` case for the migrated preludes, not only for `clj`.

**Correction:** flag the `get`→`data/nth` conversion as a Track-B behavioral migration with its own parity test, rather than implying the landed frames already comply.

### P2 concern | §2.2 invariant 8 / §7.4 | Bundled-`clj` provenance-door trust boundary under-specified

The design keeps `cell/dsg__new-provenance`/`cell/dsg__provenance` inside bundled **and** linked `clj`, "never exported". `state.md`'s door contract is a *privileged mint* + *authenticated reader* whose names guest code must not forge. Linked mode gates the door by content address/admission; **bundled** mode inlines `clj`'s definitions into a tree that may also carry arbitrary guest code, and the mechanism by which the VM distinguishes a trusted `clj`-minted `:cell/new-provenance` from a forged co-bundled call is unspecified.

**Correction:** state the bundled-mode enforcement (e.g. door effects honored only for the emitted `clj` definition addresses, not name-resolvable from guest scope).

## Confirmed properties (passed)

- **CESK integrity (Q1):** sound. `:pure` ops are total/status-returning and never suspend or call guest closures; `ctx-cell` lives in `:heap` (VM state, not continuation state). Policy traps check/return *before* the host op — div-zero is checked pre-call, bit-limit returns a keyword post-call — so `perform`'s `%capture` runs with no host op in flight; Store and Kontinuation are never corrupted, and `%capture` reifies the CESK Kontinuation directly. Moving traps from host throws to guest `perform` changes no Store/Kontinuation state the machine relies on.
- **Frame mechanics (Q4):** sound. `n` (monotonic frame count, `(+ 1 (frame-depth rest))`) is correctly distinct from `depth` (activation call depth); `(<= (data/nth hs 3) n)` is a valid O(1) structural comparison; `pop-frame` restores both `:handlers` (rest) and `:depth` (slot 4); `restore!` preserves live `:base` while restoring saved `:depth`/`:handlers`, matching landed PHP recursion accounting and Python `:base` rebasing.
- **`n`-slot vs `:depth`-vs-frame-count (Q4):** sound — mirrors landed `frame-depth`/`pop-frame`.

VM-ENGINEER VERDICT: SOUND WITH CONCERNS — continuation-portability envelope, mixed int×float boxed-comparison export, landed get→data/nth migration, and bundled-provenance-door trust boundary are under-specified; CESK integrity and frame mechanics are sound.

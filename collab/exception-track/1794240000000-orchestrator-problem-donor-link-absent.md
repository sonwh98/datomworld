Created-GMT: 2026-11-09 10:26 GMT
Created-Local: 2026-11-09 17:26 +0700
Coding-Agent: interactive (ZCode / glm-5.3-flash, exception-track orchestrator seat)
Session-ID: not-applicable (interactive seat)

# PROBLEM: the exception track's acceptance instrument is broken on master — `Module link refused: absent`

For: the orchestrator you are dispatching this to.
From: the exception-track orchestrator.
Status: **UNSOLVED, UNROUTED.** Kept for the exception track's record; not mine to fix.

## One-paragraph statement

On master `b1b9abae`, the exception track's acceptance command
`clj -M:test -n yang.call.exception-contract-test -n yang.python.antlr.exception-call-test`
produces **150 failures / 63 errors** where the ledger of record
(`test/yang/call/exception_r0_results.edn`, recorded at `0fa3ff7a`) says **159 failures /
39 errors**. The totals are identical (10 tests / 279 assertions); 24 rows moved from
"failed as expected" to "could not run at all", all failing with the same operational
refusal: the cross-language donor module cannot be linked.

```
ERROR in (clojure-to-python-test) (engine.cljc:53)
[:clojure :python :binding] :register operational failure
expected: {:returned true, :value true}
  actual: clojure.lang.ExceptionInfo: Module link refused: absent
{:status :refused, :reason :absent, :name xlang.donor.clojure,
 :yin.link/id [:t0 81], :link-module xlang.donor.clojure}
```

## Why this matters (the blocking consequence)

The exception track accepts frontend rounds by measuring **ledger deltas**: a round is
accepted when its record + its row changes + the kit's gates line up against the ledger
baseline. With 24 rows un-runnable, **no round can be accepted on those rows** — a green
looks like a green, a red looks like a red, and neither means anything if the harness
never loaded the donor. The track can still write code; it cannot close rounds.

## Evidence (reproduced, two hosts, three independent runs)

| Host / state | Result |
| --- | --- |
| Laptop, clean worktree `/tmp/xtrack-master` @ `b1b9abae`, ANTLR generated | 150 F / 63 E |
| Shiva `shiva.stigmergy.systems`, scratch clone `~/ci/datomworld-cc1-baseline`, idle, `bb gen:antlr` cache hit, HEAD verified `b1b9abae`, 2756 s | **150 F / 63 E** |
| Laptop, first run (shared checkout, contended) | 150 F / 63 E |

Identical on an idle 24-core server, so this is **not** load, contention, or a laptop
artifact. Log of record on shiva: `~/ci/xtrack-ctrl-logs/cmd2.log`.

## The 24 affected rows (all `error`, never `red`)

- **All 8 Clojure→Python pair rows** (`:clojure :python :binding`, `:catch-all`,
  `:finally-replacement`, `:retained-lifetime`, `:round-trip`, `:success-raise-success`,
  `:typed-catch`, `:unchanged-rethrow`) × 3 backends.
- **6 PHP provenance rows**: `:php/foreign-reception`, `:php/local-reception`,
  `:php/protocol-invalid-invocation`, `:php/protocol-unsupported-binding`,
  `:php/retained-foreign-lifetime`, `:php/saved-protocol-invalid-invocation`,
  `:php/saved-protocol-unsupported-binding`.
- **6 Python→PHP pair rows** (`:binding`, `:catch-all`, `:retained-lifetime`,
  `:success-raise-success`, `:typed-catch`, `:unchanged-rethrow`).

Every one names a **Clojure donor**: `xlang.donor.clojure`. The kit's
`publish-donor!` (`test/yang/call/conformance.clj:189-205`) publishes the donor through
`core-module/module-spec` over the host registry
(`modules` = `clj-prelude/admit` + `php-prelude/admit` + `h/registry`), and the caller
then links it by name. The refusal says the link lookup cannot find the module.

## Leading hypothesis (NOT confirmed — do not treat as diagnosis)

The **Python manifest / publication pin moved** after Round 0 recorded the ledger:

- Round 0 ledger recorded at `0fa3ff7a`.
- `21350ec5` (`test(yang.python): pin the JVM manifest and canonical iteration order`)
  changed the pinned manifest root from `34893d59…` to `d9aead51…`.
- `cb2cbe11` and `4f772d61` then changed REPL index/DHT publication (repair slots; page
  branching factor) and `001b8844` / `168583d9`-family merges landed those.

Mechanism offered: a donor published under one manifest address and looked up under
another yields exactly `:reason :absent`. **This is a hypothesis from git history, not
from reading `core-module`/`yin.link`.** It may just as well be a registry-admission
regression in `clj-prelude/admit` interacting with cc1's `yin.vm.macro` changes
(`f440a2fb`, `ba2deb73`), which landed in the same window.

## What is needed from the receiving orchestrator

1. **Attribute** the refusal: publish-then-link path in `core-module` / `yin.link`
   (`engine.cljc:53` is where it surfaces, not where it is caused). Bisect between
   `0fa3ff7a` (ledger green-of-record) and `b1b9abae`, or read the link-id
   `[:t0 81]` resolution.
2. **Fix** in the owning subsystem (manifest pin, publication, or link registry — the fix
   itself is architectural routing, not this seat's call).
3. **Report back to the exception track** with: the causal commit, the fix commit, and a
   re-run of the acceptance command showing **159 F / 39 E restored** (or a documented,
   reviewed new baseline if the correct baseline legitimately changed).

## Cross-references (read before starting)

- `test/yang/call/EXCEPTION_R0.md` — the kit's own statement that operational failures in
  the harness are defects, "not acceptable runtime reds".
- `test/yang/call/exception_r0_results.edn` — the ledger of record (`:verification`).
- `collab/exception-track/acceptance-ledger.md` — the living acceptance ledger (279 rows).
- `collab/exception-track/1794960000000-owner-ruling-exception-uniformity-v2.md` — the
  track's constitution.
- Orchestrator session log:
  `orchestrator-logs/1794037099-zcode-glm-5.3-flash-exception-track-orchestrator-takeover.md`.

## Caveat on the three concurrent test JVMs

At the time of writing, an R3 implementation delegate (agy gemini-3.8-flash-high) holds
three JVMs in `/Users/sto/workspace/datomworld-r3-orch`, two of them >1 h old. That is
**that round's** activity, unrelated to this problem, and on the laptop only. Whoever takes
this should expect some CPU/IO contention if they run locally; shiva/jupiter are cleaner.

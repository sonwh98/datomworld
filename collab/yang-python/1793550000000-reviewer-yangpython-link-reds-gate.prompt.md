Created-GMT: 2026-11-01 11:55:00 GMT
Created-Local: 2026-11-01 18:55:00 +07 (+0700)
Coding-Agent: kimi (read-only agent bound)

# Task: landing gate — the yang.python link-reds fix (7 slow-tier namespaces)

Role: Reviewer (independent: the implementer was codex gpt-6-luna; you are a different family).
READ-ONLY. Do not write, do not run suites — the orchestrator runs the dynamic verification.

## The change

Branch `yangpython-link-reds`, commit `6d6b7e71` ("fix(yang.python): link shared call runtime"),
based on origin/master 3ab1c925. Worktree: /Users/sto/workspace/datomworld-py-fix.
THE DIFF: `git -C /Users/sto/workspace/datomworld-py-fix show 6d6b7e71`.
12 files, ALL under yang.python (`src/cljc/yang/python/`, `test/yang/python/`,
`test/resources/yang/python/`). No dao.*, no yin.vm.linker.*, no engine changes.

## What it claims to fix

Seven slow-tier reds measured on jupiter at 68b16391 (artifacts:
`jupiter:~/ci/nightly-results/20261101-0148/slow-*.log`): siteset-test 4, safepoint-test 1,
yin.vm.semantic.derive-test 1, dht-import-test 84, linked-prelude-test 9, linked-safepoint-test 2,
e2e-test 2. Verified signatures: five link refusals named module `yang.call` with
`:reason :yang.python.antlr/missing-site-set` under profile `:py/hooks`; dht-import additionally had
24 `:reason :absent` naming `yang.call`.

## The two product hunks to judge

1. `lower.cljc`: deletes `calls-yang-call?` and makes the `yang.call` require UNCONDITIONAL in
   `module-packet` (docstring updated). Rationale claimed: every Python body can call through
   `py/call-kw`, whose runtime dispatch consults `yang.call`, so the dependency is real even when the
   source names no `yang.call/*` symbol.
2. `siteset.cljc`: the default runtime pass-through set becomes `#{py pysp yang.call}` in both the
   publish and admit paths, so the shared dispatcher is not treated as instrumentable guest Python.

## Judge these specifically

A. **Is the unconditional require correct**, or does it add a dependency to modules that cannot
   exercise it? Cost/benefit of unconditional vs a corrected predicate.
B. **Is `yang.call` in the runtime set safe** — does it let a genuinely-instrumentable module slip
   through the `:py/hooks` gate? Is the refusal still effective for guest modules that lack a set?
C. **Were any tests WEAKENED to make this pass?** Check each test-file hunk: expectations updated to
   new correct behavior (fine, if justified) versus assertions deleted, loosened, or skipped (not
   fine). The dht-import fixture was regenerated through its checked-in generator
   (`dht-import-v1.generate.clj`) — confirm the golden diff is mechanical (addresses and counts that
   follow from publishing `yang.call`) and not hand-shaped to pass.
D. **The linked-prelude and linked-safepoint manifest goldens** were updated to "current S3-linked
   addresses" — verify those are re-derived values, consistent with the new module set.
E. Any ADJACENT behavior this changes beyond linking (e.g. modules that now publish an extra
   dependency, or import ordering).

## Evidence state

Engineer's claimed slow-tier counts (UNVERIFIED at gate time): siteset 5/71, safepoint 19/144,
derive 1/54, dht-import 1/127, linked-prelude 8/82, linked-safepoint 10/53, e2e 42/714 — all zero
failures; `bb test:sub:clj yang.python` 28 namespaces / 265 tests / 0; kondo 0/0. The orchestrator's
independent re-run of the same seven namespaces on shiva is in flight; do not re-run them yourself.

## Context you may rely on

An Architect ruling (kimi, same day) held that yang.python's publication correctly holds a
`dao.jing` byte-store handle and that routing through `dao.jing.content`/`dao.jing.stream` would BE
the layering violation. Do not re-open that boundary; this gate is about the link-reds fix.

Verdict: end with exactly one line "Verdict: READY (sign-off granted)" or "Verdict: REQUEST CHANGES"
with findings. Begin with Completed-GMT / Completed-Local / Coding-Agent / Session-ID.

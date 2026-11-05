Created-GMT: 2026-11-05 11:00:00 GMT
Created-Local: 2026-11-05 18:00:00 +07 (+0700)
Coding-Agent: codex
Session-ID: 01a1ab71-2ab8-7fb2-8b70-79a0724f1eea

# Task: the :py/hooks fixture fix — linked modules need a published site set or an explicit noninterruptible declaration

Role: Yang Compiler and Universal AST Engineer
Implementers:
- Model: codex gpt-6-luna | Assigned: 2026-11-05 18:00 +07 | Status: active | Rationale: bounded fixture/profile fix in the Python frontend

WORKTREE: /Users/sto/workspace/datomworld-hk (detached at origin/master 9c6307ee; parser built,
node_modules installed). Commit to a new branch `hooks-fixture-fix`. You are the only writer.

## The problem (verified; the peer note's finding, confirmed by my audit)

The `:py/hooks` site-set stage refuses any linked module under the profile when no site set is
published for it — `:reason :yang.python.antlr/missing-site-set`, profile `:py/hooks` — per
`src/cljc/yang/python/antlr/siteset.cljc:275`. Two linked modules in the existing fixtures have
bodies that reference the convention but were never published with a site set:

- `yang.call` itself (the shared runtime module — it has NO Python bodies and can never have a
  Python site set; the ruling is explicit that the shared dispatcher is not guest code)
- `pym.k` (the fixture module that publishes but whose hooks profile requires a set)

## What to determine, in order

1. **Is the refusal correct for `yang.call` specifically?** The ruling says the runtime module passes
   through — `siteset.cljc:502-508`'s `:runtime` set is `#{py pysp}` by default and the ruling's
   fix added `yang.call` to the admission set at :424. Confirm whether the stage's default `:runtime`
   set includes `yang.call` at HEAD (it may already, via the r5 sweep) — if the stage already
   passes it through, the refusal must come from somewhere else; trace it precisely.
2. **For `pym.k`**: does the fixture intend k to be hook-instrumentable? If yes, the fixture must
   publish a site set for k (mirror the `published-site-sets-verify-test` pattern). If no, the
   fixture must declare k noninterruptible explicitly in its publication profile.
3. Pick the minimal fix per the evidence; the invariant is: **a hook-profile import of a module
   without a site set MUST refuse** (that refusal is correct product behavior — the fixture is what
   needs the fix, not the refusal).
4. Then the linked-path variant: the linked wrapper publishes `yang.call` before `py` — confirm the
   wrapper's yang.call emission carries a site set or is exempt as runtime, and that the
   noninterruptible escape works for both.

## Verification (your scope: focused namespaces on THIS machine; the orchestrator runs the subsystem
and full lanes remotely)

- `yang.python.antlr.dht-import-test` green, one namespace per invocation
- `yang.python.antlr.siteset-test` green
- `yang.python.antlr.linked-safepoint-test` green
- kondo 0/0 + cljstyle on changed files
- Do NOT run any slow tier or full suite on this machine; the orchestrator owns those lanes.

CONSTRAINTS: single turn; no approvals; commit to `hooks-fixture-fix`; do NOT push; do NOT touch
master. If the diagnosis lands in another seat's territory (yin.vm, dao.jing), STOP and report with
evidence. End with: the diagnosis per module, the fix, the verification counts, and anything open.

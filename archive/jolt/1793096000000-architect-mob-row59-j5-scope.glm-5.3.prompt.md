Created-GMT: 2026-10-26 18:15:00 GMT
Created-Local: 2026-10-27 01:15:00 +07

# Mob adjudication: the row-59 disposition for the jolt lane's J5 acceptance (the one open owner-level decision)

You are the adjudicating Architect seat (GLM-5.3), mobbing with a prior
Architect's finding. The owner has asked the orchestrator to resolve this
autonomously and has delegated the judgment to you; the orchestrator will
carry your ruling to the owner as the recommendation.

## The decision

The jolt host lane's J5 acceptance (the local-REPL milestone: standard
frontends, `(lang :yang/python)` via the C++ ANTLR parser, probe parity
with the JVM) is about to be claimed. One finding blocks it:

**Known-gaps row 59** (docs/agents/known-gaps.md) records that J0c is
probe-only — no lifecycle/close/reset behavior, no unavailable-helper
behavior, no enforced input/nesting/time budgets (the unary/indent stack
limits are censored measurements) — and names the **J5 integration seat**
as owner: "land lifecycle, unavailable-helper behavior and enforced
budgets". The lane spec §7.6 agrees: "J5 enforces a pre-parse bound with
the existing qualified frontend failure outcomes, tested at and above the
bound."

**Two dispositions are available:**

**(1) Close row 59 inside J5** — implement the pre-parse bound (a native
recursion/depth budget in the C++ parser path, enforced before parsing,
tested at and above the bound), the lifecycle/close/reset behavior, and
the unavailable-helper outcome. This makes the Local REPL milestone real
and complete: unbounded native recursion never ships on jolt.

**(2) Re-scope row 59 with the owner** — ship the J5 acceptance with the
limitation explicitly disclosed (the Python frontend on jolt has an
unbounded native recursion path), and the bound becomes a later slice.
The milestone lands sooner; the risk ships.

## The evidence base (read these)

- The prior Architect's finding (fable, 2026-10-26):
  collab/1793094885381-architect-mob-j6-policy-and-j5-review.claude-fable-5-1.findings.md
  §"Q2" item 1 — the finding and the argument that "shipping Local REPL
  milestone with an unbounded native recursion path is the one thing §7.6
  says must go back to the owner".
- The lane spec §7.6 (the pre-parse bound contract) and §7.8 (the J5 gate)
  and §10.1 (the J5 row): docs/design/yin.repl-jolt-implementation.md
- The landed J4a–J4d state on master (the fail-closed file handle, the
  locks, the key files, the 42 retired entries): the branch history and
  docs/design/yin.repl-jolt-implementation.md §10.1.
- The C++ ANTLR parser's landed state: the J0c probe
  (collab/1792573000000 or the j0c-*-shiva.log files on shiva's ~/ci/),
  the C++ sources in build/antlr/python3/cpp at the pinned commit, and
  docs/design/yang.antlr.md §7.8-§7.9 (the J0c/J5 gates).

## The question for your ruling

Which disposition, and what does the chosen slice actually contain?

1. If (1): what is the bound (recursion depth? stack budget? both?), where
   is it enforced (the C++ parser? the jolt adapter before marshal?), what
   are the failure outcomes (the existing qualified frontend failure), and
   what are the test gates (at and above the bound)?
2. If (2): what does the disclosed limitation say verbatim, and what would
   the later slice contain?
3. Either way: does the row-59 closure/disposition interact with the other
   41 retired entries or the fail-closed state (i.e. does the budget apply
   before the fail-closed refusal, or is it a parse-path-only concern)?
4. The schedule impact: (1) is real C++ work; (2) is disclosure only.
   Which do you recommend, and what is your confidence?

## Deliverable

Write your ruling to:
collab/1793096000000-architect-mob-row59-j5-scope.glm-5.3.findings.md

Your ruling becomes the recommendation the orchestrator carries to the
owner (the owner ratifies or overrules). Be specific about the mechanism
if (1). Read-only except that findings file.

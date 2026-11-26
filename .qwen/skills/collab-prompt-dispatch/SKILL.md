---
name: collab-prompt-dispatch
description: How to execute a collab/**/*.prompt.md dispatch in this repo — required report headers, hard constraints, and honest verification handback
source: auto-skill
extracted_at: '2026-11-07T09:52:01.062Z'
---

# Executing a collab dispatch brief

Briefs live at `collab/<lane>/<epoch>-<role>-<topic>.prompt.md` and end with a mandated report
format. Read the whole file **including any `## SUPPLEMENT` at the bottom** — supplements can
cancel earlier steps (one settled a "regression or pre-existing?" check and said "skip step 1
entirely", after the original step 1 had been written).

## Report header (non-negotiable)

The first two lines must be exactly:

```
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
```

Local zone here is `Asia/Ho_Chi_Minh` (+07). Get a **real** reading instead of guessing:

- With a shell (check first): `date -u '+%Y-%m-%d %H:%M:%S GMT'` and
  `TZ=Asia/Ho_Chi_Minh date '+%Y-%m-%d %H:%M:%S %Z'`. Simplest and authoritative — re-read it
  right before writing the report, not at the start of the session.
- No shell: `web_fetch https://timeapi.io/api/time/current/zone?timeZone=UTC` → `dateTime` field.
  **Works.** `worldtimeapi.org/api/timezone/Etc/UTC` fails (`ECONNRESET`) — don't bother.
- collab files' own `Created-GMT` stamps are hand-written and drift **either way** from the real
  clock: one brief stamped 17:2x +07 when it was 16:49 +07 (ahead), another stamped 12:5x GMT when
  it was 15:5x GMT (three hours *behind*, and behind the ruling it dispatched). Note the
  discrepancy in one line rather than silently matching the brief's timeline.

## Standing constraints in these briefs

- **Never claim a run that did not happen.** If a gate could not be executed, say so plainly,
  list what is missing, and hand back copy-pasteable commands (including how the diff reaches
  the remote clone: `git diff <file> > /tmp/x.patch && scp … && git apply`, since committing and
  pushing are the orchestrator's authority).
- **Commit nothing, push nothing**, do not touch `collab/`, do not create files outside the
  listed surface (so: no scratch patch files inside the repo — inline the diff in the report).
- **Do not weaken tests.** If a test's expectation must move, change only what the invariant
  requires and justify it in the report.
- **Owner rulings to respect:** no slow tests on the laptop; suite-scale runs go to
  `shiva`/`jupiter`; a failure is reproduced by running *only* the failing test
  (`clojure -M:test -n <ns> [-v <ns>/<var>]`, `node target/node-tests.js --test=<ns>`,
  `bb src/dev/cljd_agg.clj --only <ns>`). For a **fix branch**, the merge bar is just the
  previously-failing tests re-running green — not a G1/G2/G3 climb.
- Check whether the runner flags a brief quotes actually exist on the commit under test:
  `DATOM_FAIL_FAST=1` and `--reporter expanded` live only in the main tree's *uncommitted*
  `src/dev/cljd_agg.clj`; the branch at `4f772d61` reads neither, so they are silent no-ops there.

## Reading the evidence logs a brief cites (`*.stdout.log`)

Briefs say "read it fully". Do not read from line 1 — these files are dominated by tool
transcripts, and one seat's `git status --short` dump of ~200 untracked `collab/` paths cost more
context than the entire ruling. Two shapes exist:

- **codex / JSON-lines** — one `{"type":…}` object per line, few dozen lines, each enormous. The
  verdict is the **last** `item.completed` whose `item.type` is `"agent_message"`: it holds the
  whole report verbatim in `item.text`, headers included. Get the line total from a small first
  read, then `read_file` with `offset` ≈ total−6 to land on it directly. Dip into the middle only
  for a specific probe or command you need to quote.
- **zcode/agy / single-line JSON** — one object with a top-level `"response"` string holding the
  full report. Reading the file whole is fine and cheap.

Review logs end in a machine-readable verdict (`**CHANGES STILL REQUIRED**`, `**PROOF FAILS: …**`,
`APPROVED`) — grep for that first to learn the outcome before spending context on the reasoning.

## Mob briefs: stay independent

A `…-mob-…prompt.md` wants N *independent* rulings on one question, and sibling outputs
(`…glm-5.3.stdout.log`, `…muse-wonder-1.0.stdout.log`, `…qwen3.8-max.stdout.log`) will already sit
beside it on disk. **Do not open them** — reading peers collapses the mob into one opinion and
quietly destroys the point of the dispatch. Say in the report that you did not read them. Only a
reconciliation brief may read all seats.

Answer the literal question. These briefs offer enumerated verdicts (`(A)`/`(B)`/`(C)`) and require
ending on exactly one. An option's *sketch* of a design is a prompt, not a constraint: if the
evidence supports a different mechanism that still satisfies the named option, rule that way and
say why the sketch fails. Rebutting the options that were *not* chosen, with evidence, is what
makes the ruling usable to the orchestrator.

## Read-only briefs deliver the report as chat text

Some briefs end with "Do not edit" and ask only for a ruling. Then there is no `.findings.md` to
write and nothing to stage — the final response *is* the deliverable, headers and verdict line
included. Do not invent a file under `collab/` for it (that would break the no-touch rule above).
If the session is also in plan mode, produce the report as the answer rather than calling
`exit_plan_mode`: there is no implementation to approve.

## Useful move when a required tool is missing

`ask_user_question` fails in non-interactive mode ("Cannot ask user questions … without ACP
support"). Don't stall: do everything achievable with the tools present, decide the open
questions yourself, and surface them in the report as explicit choices for the orchestrator
(e.g. product fix vs. test-window fix, with the rejected alternatives and why).

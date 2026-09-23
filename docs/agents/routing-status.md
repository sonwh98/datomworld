# Delegate routing status

Durable record of owner-relayed delegate CLI/model availability and budget
status. No tool exposes this automatically (weekly quotas, provider outages,
spending caps) — the owner relays it in conversation, and it otherwise dies
with that session. This file is what lets a different orchestrator seat, in a
fresh session with no shared history, know the current routing constraints
without re-asking or discovering them the hard way.

Append-only in spirit, like `docs/orchestrator-log.md`: when a status goes
stale or is superseded, add a new entry rather than editing the old one, so
the history of what was true when stays visible. Unlike the work log, this
file may be edited to trim entries that are long superseded and no longer
useful context, since this is a status board, not a provenance record.

Every entry states: the destination, what the owner said, when, and how to
apply it. Treat every entry here as a claim to re-verify (a quota resets, an
outage resolves), not as permanent authority — the same posture as the work
log.

## Current status

**2026-09-23 (owner instruction):** "don't use cmd. its limited. use codex,
claude, and agy models." `cmd` is not an authorized destination for new work
until the owner says otherwise. `codex`, `claude`, and `agy` are the
preferred destinations for the remainder of this stretch.

**2026-09-23:** `deepseek` (deepseek-v4-pro route) hard-failed model routing
twice in immediate succession (`"deepseek-v4-pro" isn't described by this
version's model catalog` followed by a hard error, not the usual benign
fallback to `deepseek-flash`) — confirmed as a real provider-side outage, not
a quota message, by a live sanity check on `claude-fable-5-1` succeeding at
the same time. Re-verify before routing to it again.

**2026-09-23:** `glm` (glm-5.3 route) reported at ~27% of its weekly budget
remaining, resets 2026-09-27 23:00. Not unavailable, but pace further
dispatches — prefer resuming an existing session (cheaper, and keeps that
reviewer's established context) over starting a fresh one.

**2026-09-23 14:41 (owner instruction):** "codex has 14% usage left and resets
in 6hrs 45mins. see if you can squeeze useful work from it before it expires"
(resets ~2026-09-23 21:26 +07). Actively route high-value architectural, design,
and execution-playbook tasks to Codex (`gpt-5.6-sol`, `gpt-6-astra`) to maximize
utility before the weekly quota resets.

**2026-09-22:** `gpt` (codex/OpenAI seats) reported at ~19% of weekly budget
remaining as of 2026-09-22, resets ~2026-09-23 20:50. Reserve for
architectural review, batch questions per turn rather than many small
dispatches.

## How to apply

Before routing a new dispatch to any metered/quota-based destination, check
this file's most recent entry for that destination. If none exists or the
stated reset time has passed, the status is unknown — proceed normally, but
consider a cheap sanity check (a trivial prompt) before committing a
real task to a destination that failed recently. When the owner relays a new
status in conversation, add an entry here before continuing, so it survives
past the current session.

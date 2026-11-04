Created-GMT: 2026-11-03 17:55:00 GMT
Created-Local: 2026-11-04 00:55:00 Asia/Ho_Chi_Minh
Coding-Agent: glm (glm-5.3)
Session-ID: not-applicable (orchestrator-dispatched, fresh session)
# Task: team.md alias refresh + the claude-cloud table overflow + the subagent-fallback quirk

Role: docs engineer (glm)
Reviewer/landing: the ZCode glm orchestrator seat

## Context

Your earlier delegate-reference round fixed that file but recorded two findings outside its boundary; this round covers them plus one new measured quirk. Work in the MAIN checkout /Users/sto/workspace/datomworld (branch master; commit on master; docs only). Verify each claim firsthand (the file's own re-verify rule) before writing.

## The fixes

1. **docs/agents/team.md:71** — the mistral row lists `mistral-large, mistral-medium-3.5, mistral-4.0, devstral, codestral` as if all work; measured: bare `mistral-large` is DEAD at the API ("Invalid model"), vibe's working aliases are `[codestral, devstral, mistral-4.0, mistral-large, mistral-medium-3.5]` where `mistral-4.0` maps to the current large-4 generation (probe-verified 2026-11-03). Fix the row to name mistral-4.0 as the working large-model alias and note the API-level death of the bare alias.
2. **docs/agents/team.md:167/:170** — the model-capability rows name mistral-large/mistral-small with stale context sizes; refresh to the live family (mistral-4.0 / mistral-medium-3.5 / codestral / devstral / magistral-medium-latest) with honest capability notes. Probe what you can; mark source-derived rows as such.
3. **docs/agents/delegate-invocation-reference.md claude-cloud row** — the row overflows the table border by ~18 chars (measured: 190 chars at line ~154). Wrap it to the table width. Content unchanged.
4. **NEW quirk (measured this track, 2026-11-03/04): the Agent-tool subagent_model fallback.** A subagent dispatched with subagent_model naming a provider model that is listed but not wired (new-provider/muse-wonder-1.0 — listed in the host's model list, no credentials routed) ran ALL its calls (62/62, verified in the child session's model-IO records) on the SESSION model instead, silently — the dashboard for the named provider showed zero usage. Document under the delegation/agent-dispatch section of delegate-invocation-reference.md: before dispatching a subagent on a non-default model, verify the routing from the child session's model-IO rollout records (~/.zcode/cli/rollout/model-io-<childSessionId>.jsonl), not from the model list alone; a listed provider without wired credentials silently falls back to the session model.

## Boundaries

- docs/agents/team.md and docs/agents/delegate-invocation-reference.md only. No source, no routing-status.md (local-only board), no other files.
- Commit with docs(agents): prefix; push to origin master.

## Report

The probes run, the diff, anything stale beyond the list.

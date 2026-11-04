Created-GMT: 2026-11-03 14:30:00 GMT
Created-Local: 2026-11-03 21:30:00 Asia/Ho_Chi_Minh
Coding-Agent: glm (glm-5.3)
Session-ID: not-applicable (orchestrator-dispatched, fresh session)
# Task: refresh the stale delegate-model aliases in delegate-invocation-reference.md

Role: docs engineer (glm)
Reviewer/landing: the ZCode glm orchestrator seat

## Context

`docs/agents/delegate-invocation-reference.md` carries several stale model aliases, measured during the yang.clojure track's routing work. Work in the MAIN checkout `/Users/sto/workspace/datomworld` (branch master; commit on master is fine — docs only). This is a docs-only round: no source, no tests, no bb.edn.

## The measured staleness (fix these)

1. **agy's model list** (~line 191): the reference lists `gemini-3.1-ultra`, `gemini-3.1-pro-high`, `gemini-3.8-flash` — the installed agy 1.4.12 rejects them ("model gemini-3.1-pro-high is not recognized"). The LIVE list (measured): `Gemini 4 Argon (High/Medium/Low)`, `Gemini 3.8 Flash (High/Medium/Low)`, `Claude Opus 5.5 (Low/Medium/High)`, `Claude Sonnet 5.5 (Low/Medium/High)`, `GPT-OSS 120B (Medium)` — the effort level is part of the display name and the separate `--effort` flag is NOT supported for Gemini 4 Argon. Update the row and add a quirk note: model selection uses display names with the effort suffix; probe per model before dispatch (per-model quota windows are independent — see routing-status.md 2026-11-03 0x:xx and 04:0x entries).
2. **vibe/mistral aliases** (~line 179): the reference lists `mistral-large` etc. — the live API rejects bare `mistral-large` ("Invalid model"); vibe's own alias list is `[codestral, devstral, mistral-4.0, mistral-large, mistral-medium-3.5]` and `mistral-4.0` maps to the current large-4 generation (probe-verified). Fix the alias list and note the working invocation.
3. **kimi's read-only profile** (wherever the kimi row/section names agent profiles): the documented `code-reviewer` profile does not exist — kimi's real profiles are `plan, agent, coder, explore` (measured; `explore` is the read-only review choice). Fix any stale profile names.
4. Also add one line to the muse section: `--trust-workspace` is required for non-laptop roots (shiva/jupiter) in headless exec, and `--approval-mode never` (sandbox stays on) for tool-using headless rounds — the failure modes are in routing-status.md 2026-11-03 07:5x.

## Boundaries

- Only `docs/agents/delegate-invocation-reference.md` may change. Do not touch routing-status.md (a local-only status board), any source, or other docs.
- Verify each claim by RUNNING the probe yourself (each CLI has a trivial `Reply OK` probe) — do not copy claims from the brief; the file's own rule is "a claim to re-verify". Record probe results in the commit message.
- Commit with `docs(delegate-reference):` prefix; push to origin master.

## Report

The exact probes you ran and their outputs, the diff, and anything you found stale beyond the list above.

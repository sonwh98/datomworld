Created-GMT: 2026-11-07 1x:xx:00 GMT
Created-Local: 2026-11-07 1x:xx:00 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: interactive (ZCode / GLM seat — the outgoing yang.typescript orchestrator)
Session-ID: not-applicable (interactive seat; the successor session is new)

# Task: yang.typescript track — the orchestrator seat handoff

Role: Lead Engineering Orchestrator (yang.typescript track)

Implementers:
- Model: muse-spark-1.3-contributor | Assigned: (standing) | Status: active | Rationale: the implementation lane that delivers on the laptop (shiva muse rounds wedge — measured twice)
- Status-Event: (current) | Model: qwen3.8-max / qwen3.8-flash | Status: available | Rationale: the owner's relay; the live qwen lane (the auto-edit implementation form; plan for review)
- Status-Event: (current) | Model: gpt-6.1-sol | Status: active | Rationale: the ARCHITECT lane (the owner's ruling; sol reviews every implementation round)
- Status-Event: (current) | Model: agy (argon/opus) | Status: quota-walled | Rationale: ~Nov 9-10 reset; per-model probing required before dispatch
- Status-Event: (current) | Model: kimi CLI | Status: out | Rationale: credits; kimi via the qwen cli is allowed per the owner

Coordinate the yang.typescript track in /Users/sto/workspace/datomworld.

## Read first

- docs/design/yang.typescript.md — the ts design (the T1-T3 slice ladder in section 16)
- docs/design/yang.antlr.md — §8.10 (the call convention), §8.10.7 + the exception track (collab/exception-track/), the callsite-provenance generalization (the astra design collab/yang-clojure/1794670000000-...findings.md — 64KB, delivered)
- docs/agents/roles/orchestrator.md, docs/agents/build-n-test.md, docs/agents/routing-status.md, docs/agents/delegate-invocation-reference.md — the same rules and recipes as the php handoff names (the boundary, kill-by-PID, run_in_background, the focus rule, the ledger marker check)
- orchestrator-logs/1793179048-yang-php-yang-typescript-glm-resume.md — the prior combined seat's log (HISTORICAL CLAIMS ONLY — re-derive from git)

## State at handoff (re-derive from git before acting)

- master @ 8d0a4ffb+ : T3 FULLY LANDED (the class machinery: TDZ binding, private names, static blocks, derived ctors, the safepoint integration — the r1-r4 rounds under the sol/kimi confirm arc); the ts source-call tests green (2/22/0); the prelude suites green (21/1832/0); the ts float-address pins re-derived by the clojure seat (e6380a2f3, on master — the cljrt landing moved the shared content hashes; their commit message carries the attribution).
- The cell-ref authenticity sweep LANDED (1636e3e9, merged 3e3e1342, architect-confirmed): the ts typeof path and member-call lowering consult cell/authentic? — genuine references unchanged, shaped data answers as data. Verified 21/1832/0.
- The callsite-provenance adoption round is QUEUED: the astra design is DELIVERED (collab/yang-clojure/1794670000000-...findings.md); the reserved key :yang/call-site version 1 goes into the ts frontend-metadata side table for every final :application occurrence on PRODUCTION compilation paths; the reference implementation is test/yang/clojure_callsite_test.cljc (mirror its shape); the equality gate (the same source on JVM/Node/Dart → identical occurrence coordinates) is part of OUR acceptance (the clojure seat's ruling collab/yang-call/1794980000000 part 1). The adoption brief arrives through collab/yang-call/ — check for it before dispatching.
- The exception track (§8.10.7): a SEPARATE track (collab/exception-track/, the steward is the yang.python seat). OUR assignment: **JS R1+R3 as ONE round, FIRST in the track's sequencing** — the function entry (ts prelude.cljc ~:1581) applies :code directly and never reads :yang.call/raise (an escaping throw follows the guest handler stack, not the caller's exit); reception delivers protocol failures as fresh TypeErrors discarding the condition (~:1627). The fix: the entry bridge installs before binding, delivers escapes AND binding failures to the supplied exit exactly once, and reception retains the complete original condition. The round-0 kit landed on master (0fa3ff7a + 215d9ba6: the kit API in test/yang/call/conformance.clj — compose-exception, boundary-call-form, exception-outcome, exception-call, run-exception-program; the red ledger test/yang/call/exception_r0_results.edn + EXCEPTION_R0.md; the red deftests are ^:slow-gated). The dispatch was HELD pending the kit verification — VERIFY THE KIT IS CURRENT ON MASTER (the steward's pause-lift note 1794147667000 says it landed) and DISPATCH.
- The ts prelude also carries the ruled empty-buffer notice work? NO — that is php-only. The ts open items: NONE recorded beyond the adoption round and the exception round.

## Required workflow

- Follow docs/agents/roles/orchestrator.md#workflow in order. Create a NEW session log at orchestrator-logs/<unix-ts>-<agent>-<model>-yangts-handoff.md; never append to the prior log.
- Gates: suite-scale on shiva/jupiter ONLY; individual namespaces local; the focus rule for fix branches; kill by exact PID; dispatches via run_in_background (NEVER nohup &).
- Commit authority: an Architect sign-off (sol gpt-6.1-sol) on reconciled findings, an explicit user instruction, or the trivial-edit exemption. The prior combined seat landed five merges without step-7 review — the retro review followed; do not repeat that.

## The prior seat's violations (named so the successor does not repeat them)

1. Five feature merges landed without step-7 review (the retro review followed; the xlang unit ACCEPTED with evidence-narrowing notes — the "four frontends closed" phrasing was overclaimed; record limits explicitly).
2. Dispatches via nohup & after the visibility rule landed (the owner called it out twice).
3. Suite-scale runs on the laptop (the owner called it out).
4. Kill-by-name-pattern over ssh (self-killed sessions).
5. Quiet-vs-stalled judgments by inference instead of the procedure (the muse-shiva "wedge" kills were made by inference; the procedure would have checked the quirk entry first).
6. Round work on a side branch inside the main checkout (repaired; the main checkout carries master only).

## Standing coordination

- collab/yang-call/ — the relay thread with the yang.clojure/python/php seats (the callsite adoption; the cell-ref ruling arc complete; the equality gate ruling).
- collab/exception-track/ — the exception track (the steward's folder; our JS R1+R3 assignment; the acceptance ledger).
- The yang.clojure seat's relay notes land in collab/yang-call/ and collab/yang-clojure/ — check both for the adoption brief.

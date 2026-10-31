Created-GMT: 2026-10-19 15:58:23 GMT
Created-Local: 2026-10-19 22:58:23 +07
Coding-Agent: codex
Session-ID: 01a14f30-ca56-7530-bb93-3a870eca7a7b
# Task: jolt host-lane plan, revision r5 (fold owner answers Q3/Q4; final plan for independent review)
Role: Lead System Architect
Implementers:
- Model: gpt-6-astra | Assigned: 2026-10-19 22:58:23 +07 | Status: active | Rationale: plan author; resumed to fold owner decisions

Owner decisions since r4, verbatim quotes, to record in the plan as decided (not open):
- Q3: "Q3 is not relevant if we use FFI with antlr C++ generated parser", then, asked whether Dart AOT should be a fallback: "no fall back". => parser = in-process ANTLR4 C++ over FFI; NO pre-committed fallback; if the J0 feasibility probe fails, the orchestrator returns to the owner with the failure (do not name Dart AOT or any worker as a fallback).
- Q4: "let's go with your proposal" (orchestrator proposal: mise-driven vendored build for libblake3 with pinned source revision + hash in the tracked toolchain file, built with a mise-pinned cmake for macOS arm64 + Linux x86_64 into build/native/<platform>/; system/Homebrew OpenSSL libcrypto with a J0 path+version check that fails on mismatch), and for the ANTLR C++ runtime: "yes, same policy as libblake3".
- Already recorded in r4: Q1 "aceept local-REPL milestone first"; Q2 "mac arm and linux" + "x86-64".
Still open: Q5 (cadence/benchmark budgets, after J0/J8 evidence) and Q6 reduced to the WebSocket half only (the parser half is moot because the parser is in-process); Q6 affects only the parity phase.

Write a NEW file, the full final plan (not a diff), same header fields:
/Users/sto/workspace/datomworld/collab/1792425503000-architect-jolt-lane-plan-r5.gpt-6-astra.findings.md
Base on your r4 file (collab/1792424621000-architect-jolt-lane-plan-r4.gpt-6-astra.findings.md). Do not edit earlier files. Write only that file.

Required: (1) Q3/Q4 become recorded decisions; the J0 C++ feasibility probe becomes REQUIRED (not conditional) with the failure path "return to owner, no fallback"; (2) remove the other worker options from the commissioning path but keep a short record of why they were set aside; (3) rewrite Q6 to the WebSocket half only and re-derive the milestone labels (the local-REPL label should no longer say "requires a configured parser worker runtime"; it should disclose the native C++ parser library); (4) make J0/J5 gates, packaging and the risks section consistent with the in-process parser (crash isolation, exception containment, pinned runtime/tool versions); (5) a short list of remaining open questions (Q5, Q6) at the end; (6) an r5 change note. The plan must be internally consistent: a reviewer will read it cold. Label unverified claims R. No src/ edits; nothing is commissioned.

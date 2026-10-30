Created-GMT: 2026-10-19 15:43:41 GMT
Created-Local: 2026-10-19 22:43:41 +07
Coding-Agent: codex
Session-ID: 01a14f30-ca56-7530-bb93-3a870eca7a7b
# Task: jolt host-lane plan, revision r4 (add C++ FFI parser option to Q3)
Role: Lead System Architect
Implementers:
- Model: gpt-6-astra | Assigned: 2026-10-19 22:43:41 +07 | Status: active | Rationale: plan author; resumed for an owner-requested option

Owner instruction (verbatim quote): "yes, add the C++ FFI option to Q3". It followed the owner's question (verbatim): "doesn't antlr generate C or C++ parser? if so FFI can be used to interface to C/C++ parser".
Owner answers so far (verbatim): Q1 "aceept local-REPL milestone first"; Q2 "mac arm and linux" then "x86-64" (macOS arm64 + Linux x86_64, Windows out). Q3-Q6 unanswered. Fold Q1/Q2 into the plan as recorded owner decisions.

Orchestrator notes, NOT verified by running; check them yourself before relying on them: ANTLR4 has a C++ target (-Dlanguage=Cpp) and no official C target (ANTLR3 had one); FFI cannot call C++ classes directly, so the option needs an extern "C" wrapper that catches all exceptions and returns a serialized tree as bytes; the runtime/tool version pin (deps.edn:12-14,76) would apply; yang.antlr.md:530-534 lists only Java, JavaScript and Dart targets, so C++ would be a NEW generation target; repo grep found no C++ target.

Write a NEW file with the full revised plan (not a diff), same header fields:
/Users/sto/workspace/datomworld/collab/1792424621000-architect-jolt-lane-plan-r4.gpt-6-astra.findings.md
Base it on your r3 file (collab/1792423926000-architect-jolt-lane-plan-r3.gpt-6-astra.findings.md). Do not edit earlier files. Write only that file.

Required: add the in-process C++ FFI parser as a fourth Q3/J5 worker option beside JVM, Node, Dart AOT, with an honest cost/benefit comparison, esp. against Dart AOT: build of the ANTLR4 C++ runtime for the two platforms (feeds Q4), the wrapper ABI (bytes in, bytes out, no exception crossing, no guest-visible pointer), the serialized tree format and its parity with the existing frontend tree shape, version pinning, packaging, failure isolation (a parser crash takes the process down, unlike a worker), and what the J0/J5 gate would be. If the option needs a probe, specify it falsifiably. Reword Q3 to include it and state which options remove the JVM and Node from the process tree. Keep everything else, other questions and the disposition table intact; add a short r4 change note. Label unverified claims R. No src/ edits; nothing is commissioned.

Created-GMT: 2026-11-13 22:5x GMT
Created-Local: 2026-11-14 05:5x +0700
Coding-Agent: codex
Session-ID: (resume of thread 01a1d5d3-1563-7092-b361-efd917b2f022 — your (A)/(b) ruling thread)

# Task: Second follow-up adjudication — the recipe's "executable dependency closure" is undefined and the retained store cannot supply it

Role: Lead System Architect (amendment to your own (A)/(b) rulings)

Implementers:
- Model: gpt-6-astra | Assigned: 2026-11-14 05:5x +0700 | Status: active | Rationale: your rulings, your contract

## The finding (from the D1 implementation, r16 — honest, escalated per the discipline)

The retained corpus store contains, per fixture: the program (captured text), the options, the admission coordinate (scope + ordinal), the binding snapshot, and the text-serialization profile. It does NOT contain:
- The executable compiler closure (the compiled class files, the dependency jars, the JVM/runtime that compiled the corpus)
- The expansion history (which macro-expansion events occurred, with what inputs)

Historical tracked sources matching the current checkout does not establish the complete original runtime — a git commit is not a JVM.

Your (A) ruling's clause 4 pins the post-port stamping implementation "via executable dependency retention, not merely a commit string or copied counts." But the ruling did not define WHAT the executable dependency retention is, HOW it is captured, or WHETHER the D1 recipe can reference it across process exits and host boundaries. The r16 inventory confirms: the retained store holds what the (A)-amended capture boundary collects, and the capture boundary does not collect the runtime.

## The question to rule on

**What is the recipe's "executable dependency closure," concretely?**

- **(a) A JVM/dependency image**: a captured runtime (the JDK, the dependency jars, the compiled class files) stored by address, restored at reconstruction. Heavy (multi-GB), but complete.
- **(b) A commit + environment spec**: the recipe pins the git commit, the JVM version/vendor, the dependency coordinates (from deps.edn), and the mise/toolchain state — the reconstructor re-derives the runtime from these. Lighter, but requires the reconstructor to trust the derivation.
- **(c) Revise the D1 contract to a RECORDING contract**: the recipe records what WAS retained (the captured texts, the admission coordinates, the binding snapshots, the profile) and does NOT claim to reconstruct the compilation. The verification then checks the retained outputs against independently re-derived values (which the cc3-style re-derivation already does), not against a reconstruction of the compiler. The "recipe" becomes a recording profile, not a reconstruction recipe.

Consider: unitarity (what must be retained to recover the admitted syntax and its interpretations — the text may be sufficient if the interpreter is deterministic), the "derive, don't persist" principle vs the reconstruction-exactness obligation, the cost of each option (multi-GB images vs a trust-on-commit model vs a contract revision), and what the D1 verification actually NEEDS (does the verifier need to re-run the compiler, or only to check the retained outputs against independently re-derived values?).

## Output

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then: `RULING: (a|b|c|other) — <one-line contract statement>`, the exact contract amendment for §4.1's recipe clause, the implementation steps for the ruled option, and any defects found in the piloted machinery (file:line). Static review; no suites; no edits.

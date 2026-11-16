Created-GMT: 2026-11-13 17:3x GMT
Created-Local: 2026-11-14 00:3x +0700
Coding-Agent: codex
Session-ID: (resume of thread 01a1d5d3-1563-7092-b361-efd917b2f022 — your (A) ruling thread)

# Task: Second adjudication — the (A) profile loses numeric identity on the real admitted fixture (r4 blocking observation)

Role: Lead System Architect (amendment to your own ruling)

Implementers:
- Model: gpt-6-astra | Assigned: 2026-11-14 00:3x +0700 | Status: active | Rationale: your ruling, your amendment — the blocking observation is on the profile clause you wrote

## The blocking observation (r4, fail-closed, red-first)

The implementer built your amendment verbatim: capture at the input boundary (forms, call, program, options — before hashing/compilation), the JVM-initial profile (UTF-8, pr-str with metadata, unlimited length/level, readable printing, duplicate printing disabled, ns-map shorthand disabled, the EDN reader with exactly-one-value/EOF and tag-refusing handlers), the admission-time fidelity check (syntax kinds, map/set order, numeric class + .equals, metadata — recursively, with compact mismatch paths), and the refusal discipline. All of it is implemented and tested — and then the REAL `:beta-eq-false` fixture **refuses its `forms` at `:fidelity`**:

- Path `[15 2 :metadata [:value 2]]`: the original metadata value is `java.lang.Integer`; `pr-str` prints an untyped integer; `clojure.edn/read` returns `java.lang.Long`. Numerically equal, but your fidelity rule ("preserve syntax distinctions, numeric identities") requires class identity — so the fixture refuses, correctly per the rule as written.
- A synthetic metadata test reproduces the type loss independently. Red-first: the refusal regression was retained as a refusal, NOT converted into a fake pass.
- Findings: `/Users/sto/workspace/datomworld/collab/yang-clojure/1794422563000-engineer-d1-correspondence-r4.gpt-6.1-terra.findings.md` — "Blocking observation: stipulated profile loses numeric identity" section, with the implemented prototype (`src/cljc/yang/correspondence_text.clj`, JVM-only profile value + tag-refusing reader) and the fail-closed capture wiring in the oracle harness (forms/call before hashing, program/options before compile).

## The question

Your ruling's clause 2 said "Any further printer behavior affecting admitted values must be pinned or excluded by the admitted domain" — this is that case arriving. Rule on ONE:

- **(a) Amend the profile's printing to preserve numeric identity**: pin a numeric-preserving printing mode for the admitted domain (e.g. tagged literals or a print-method that records the class). Specify the exact mechanism, its scope (which numeric classes), and how the reader restores identity.
- **(b) Revise the admissible-input/fidelity rule**: numeric class identity is not required where the compiler's semantics cannot distinguish it (e.g. metadata is never lowered into operands... unless it IS observable — check `compile-constructed-collection`/metadata handling before ruling). Specify which distinctions the fidelity check must preserve and which it may drop, with the reasoning.
- **(c) Narrow the admitted domain**: the fixture's Integer metadata is itself the anomaly (host-generated metadata on a guest value) — rule that such inputs are out of the corpus domain and must be normalized upstream, with the refusal standing as correct.

Consider: where does the Integer metadata COME FROM (is it compiler-generated or user-source?), what the compiler actually observes (does numeric class identity affect lowering, hashes, or only the fidelity check?), and the principle that the profile serves reconstruction of the admitted compilation — not universal value serialization.

## Output

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Then: `RULING: (a|b|c) — <one-line amendment>`, the exact profile/rule text amendment, the implementer's next steps, and any defects found in the r4 prototype (file:line). Static review; no suites; no edits.

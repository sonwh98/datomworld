Completed-GMT: 2026-09-16 15:12:00 GMT
Completed-Local: 2026-09-16 22:12:00 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: claude
Session-ID: 67bb4f7f-225d-4e3d-a19f-8f57fbf91f66

# Architect sign-off: U2 (Flutter REPL widget) and U5 (test port + parity pin)

Role: Lead System Architect
Model: claude-fable-5-1

## Unit 1: U2 — the v2 Flutter REPL widget

**Single-state-owner discipline (D1's core concern).** The `box` atom
inside `run-endpoint!` (`flutter.cljd:65`) is closed over only by the
`Timer.periodic` callback — no other function reads or writes it. Clean
sole-writer, no violation.

**A related, non-blocking observation.** `server-status` has four write
sites; traced all four for races. Dart's single-threaded event loop plus
an `identical? run @active-run` guard on stale-run writes makes this
race-free by inspection, but it's an implicit, undocumented second
invariant. Recommend a one-line comment at the guard sites — not
blocking.

**The disclosed fast-restart edge case** — same shape as U4's accepted
precedent: honestly disclosed, not reproducible under current usage,
tracked as a follow-up, not blocking.

**The unverified manual Flutter smoke test** — genuinely can't be run
here. Automated coverage (`v2_embed_test.cljc`'s three deftests, the
`.cljd` file compiling and passing `dart analyze`, verbatim-shape porting
of `run-dart!`/`load-device-ip!`) is unusually thorough for what's left.
Same disposition as U4: acceptable gap, not blocking, owed before use
beyond the two demos.

**`:primitives`/`:extra-primitives` plumbing** — minimal, additive-only
(new arity with `nil` default, no existing call site changes behavior).
New deftest pins exactly the regression D1 names.

**Demo/doc repoints** — require/URL/command-text only, no logic changes.

**Verdict for U2: APPROVE-WITH-FINDINGS.** All findings non-blocking.

## Unit 2: U5 — test ports off the v1 VM, pin parity values

**Reuse discipline.** All three `yang` test files now route through the
pre-existing `yin.vm.test-utils` (the same composition production code
uses), replacing a hand-rolled `queue-vm` helper. `clojure_test.clj`'s
extra `(module/register-stream-module (module/default-registry))` merge
is correctly diagnosed and scoped — v1 registered the stream module
globally at load time, v2 doesn't; the other two yang files don't need it
since they don't exercise stream ops.

**Assertion-count discipline** — confirmed exact before/after for all
four `parity_test.cljc` deftests, verified by reading the diff directly.

**`ast_conversion_test.cljc` coverage, with one nuance** — the new
`codec-round-trips-the-v1-corpus-node-types` deftest covers everything
except `root-id-detection-test`, which didn't need porting: v2's
pre-existing `heuristic-fallback-is-unchanged` already asserts the
identical behavior. No coverage gap.

**Independently spot-checked pinned values** (partially overlapping with
Gemini's own spot-check, both clean): `division` (20/5=4, unrelated to
the recent divide-by-zero fix), `addition` (30), `higher order` (15),
`stream make` (plausible first-allocation id per the file's own
normalization rules).

**D4's pin-vs-live-oracle question — sound, for this case.** The two
alternatives (delete the file, or keep v1 alive in test/ only) are both
worse. Staleness risk is bounded by the file's own scope: it's
macro-free by design, excluding exactly the region where v1/v2 are
expected to diverge, so a stale pin can't mask a real regression in the
tested area. The one failure mode that would matter (v1 patched after
capture) is moot since v1 is being deleted, not left running — the
surviving risk (a transcription error at capture time) is exactly what
independent spot-checks exist to catch, and two independent ones came
back clean.

**Verdict for U5: APPROVE.** No findings rise above cosmetic.

Both verdicts are independent — the orchestrator is authorized to stage
and commit each diff on its own.

Created-GMT: 2026-10-20 01:41:22 GMT
Created-Local: 2026-10-20 08:41:22 +07
Coding-Agent: cmd
Session-ID: 61966f9d-4875-4b42-9545-b0dc8a2c9891
# Review: J0c round 1 fixes - confirmation
Role: Reviewer (read-only) | Model: qwen/qwen3.8-max

## Verdict: SIGNED OFF

**F1: CLOSED.** jolt_parser.cpp:128-141: `message()` is `noexcept`, sets `*out=nullptr; *out_len=0` first, then only `strlen`/`malloc`/`memcpy` (plus one atomic load); on malloc failure the buffer stays NULL and only the status reports. Adversarial pass: `e.what()` is noexcept by standard; the `catch(...)` path passes a string literal; no `std::string`/`std::vector`, no `strerror`/errno, no static buffers, no per-thread state beyond the `std::atomic<int32_t>` seam; the `-1`/`-4` paths allocate nothing. Nothing throwable remains on any failure path. Header comment (:32-34) documents it.

**Fault injection is sound, not a mock.** Modes 1/2 throw at the top of the real `try` in `jolt_parser_parse` (:152-157), so the actual catch handlers run; mode 3 exercises the same NULL-buffer branch inside `message()` a real malloc failure would take. Probe checks (jolt_parser_probe.clj:176-199) assert status -2 with `"std::bad_alloc"`, -3 with `"unknown exception"`, -2 with zero-length buffer, and a clean re-parse afterwards, each resetting the seam in `finally`. Orchestrator's independent 11-PASS run matches.

**Seam risk: low now, gate before packaging.** `jolt_parser_inject_fault` is exported in the production dylib, so any in-process code can force fake parse failures. The threat model requires code already running in the process, which can do worse; for a local feasibility probe this is acceptable. Recommendation: before J5 packaging/J8, exclude it via the already-planned exported-symbols list or a CMake option defaulting OFF (probe build ON), and record the decision in the J0c checklist. Not a blocker this round.

**F3: CLOSED.** manifest.edn:22-24 carries the coupling note verbatim. Cross-target claim holds: the `:java`/`:javascript`/`:dart` `:generated` digests are byte-identical to base jolt-j0a (verified against my round-1 read; report's 12-row table matches), `bb gen:antlr` exit 0 proves outputs match records (the generator throws on drift), and this round's manifest change is comment-only.

**Not verified:** dylib SHA `00c27f06…` and `nm` output (no shell); fix logs beyond the orchestrator's re-run.

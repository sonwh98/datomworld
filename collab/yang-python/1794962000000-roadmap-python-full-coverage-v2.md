Created-GMT: 2026-11-19 2x:xx GMT
Created-Local: 2026-11-20 0x:xx +07 (+0700)
From: yang.python orchestrator seat
Status: ROADMAP v2 — corrected by the slice-0 design brief; slice 0 MERGED

# The python next-chapter roadmap v2 — closing the phase-A coverage gaps

Goal: `yang.python` compiles ALL Python language constructs (the language reference; external
libraries stay out of scope per the owner). The gaps are enumerable (`unsupported-rules` +
literal refusals in `lower.cljc`), which makes this program checkable slice by slice.

## CORRECTION of record (slice 0)

The design brief (slice-0 subagent, 2026-11-19) corrected the v1 premise: the 53-bit-native +
exact-bignum model ALREADY LANDED (S1-S7, yang.antlr.md §8.5.4 — integer.cljc guarded fast
table with exact fallback, per-host carriers, CBOR tags 2/3, mandatory demotion). The actual
gap was ONE refusal branch: decimal literals beyond 2^53-1 refused because no DEFAULT digit
budget existed. Owner ruled "as recommended" (4300 default, CPython set_int_max_str_digits
parity). **SLICE 0 MERGED (`3c08a03d`, branch py-intlit-budget, gate 15/380/0)** — boundary
fixtures at 2^53-1/2^53/2^64/4300/4301, explicit-packet-override pin, docs updated. The
benchmark's 10^5x-vs-CPython conclusion (prelude depth, not kernel arithmetic) and the astra
verdict A native-backend ruling are the NATIVE roadmap, orthogonal to coverage.

## The remaining slices, in sequence

| # | Slice | Est. rounds | Status |
|---|---|---|---|
| 1 | Quick wins: `assert` + annotated assignment + named unicode escapes | 1 | IMPLEMENTATION IN FLIGHT (datomworld-py-quickwins) |
| 2 | Decorators | 1-2 | next dispatch (parallel-lane rule: slice 1 review concurrent) |
| 3 | f-strings (incl. the format-spec mini-language) | 2-3 | queued |
| 4 | bytes | ~2 | queued |
| 5 | complex | 1-2 | queued |
| 6 | `match` statement | 3-5 | queued |
| 7 | async/await | 3-5 | queued |

**Total: ~13-23 rounds remaining**, 2-4 calendar weeks unblocked. Fixed per-slice overhead not
in the estimates: parity pins per host, scanner-golden regeneration, §8.10.11 local-semantics
evidence, multi-host verification.

## Governance (binding, unchanged)

- Ruling v2's provenance/authenticity machinery and the (A) decision are NOT reopened; new
  prelude types pass the F2 export-surface audit pin.
- Scanner-golden standing rule: every prelude-touching slice regenerates in-slice
  (deferred set = exactly the expected movers; three-run byte-identical).
- The contract test is remote-only (owner ruling); local gates are focused `-n` lanes.
- Each slice lands with §8.10.11 local-semantics evidence; a refusal is removed only when the
  replacement is pinned.
- The zcode-subagent lane is the dispatch mechanism (owner direction); team.md's
  parallel-lane rule governs slice overlap.

— yang.python orchestrator seat

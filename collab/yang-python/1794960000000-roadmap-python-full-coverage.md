Created-GMT: 2026-11-19 1x:xx GMT
Created-Local: 2026-11-20 0x:xx +07 (+0700)
From: yang.python orchestrator seat
Status: ROADMAP OF RECORD — the path to "all Python constructs, excluding external libraries"

# The python next-chapter roadmap — closing the phase-A coverage gaps

Goal: `yang.python` compiles ALL Python language constructs (the language reference; external
libraries stay out of scope per the owner). The gaps are enumerable (`unsupported-rules` +
literal refusals in `lower.cljc`), which makes this program checkable slice by slice.

## The slices, in sequence

| # | Slice | Est. rounds | Notes |
|---|---|---|---|
| 0 | **Integer-model ruling** (the critical path) | design track, parallel | 2^53 exact-integers is a design decision (determinism). Closing the gap needs an arbitrary-precision story — intersects the astra native-backend ruling (`3558f599`, verdict A) and the CPython benchmark results (`af3ce417`). READ BOTH FIRST. Est. 2–4 rounds + a decision. |
| 1 | **Quick wins**: `assert` + annotated assignment + named unicode escapes | 1 round | One prelude call, one no-op-with-metadata lowering, one unescape branch. Three gaps closed per round. |
| 2 | **Decorators** | 1–2 rounds | def → apply → rebind is mechanical; decorator×class×method interactions need parity pins. |
| 3 | **f-strings** | 2–3 rounds | Parsing easy; the format-spec mini-language (`:>10.2f`, `!r`, nested fields) is `__format__` semantics — the real slice. |
| 4 | **bytes** | ~2 rounds | New immutable prelude type: indexing→int, slicing, decode/encode; operator surface. |
| 5 | **complex** | 1–2 rounds | New prelude numeric type: arithmetic + repr; self-contained. |
| 6 | **`match` statement** | 3–5 rounds | ~40 grammar rules desugaring to predicates/captures; volume, not difficulty. |
| 7 | **async/await** | 3–5 rounds | Coroutine protocol (send/throw/close, StopIteration-unwrapping), awaitable dispatch (`__await__`), `async for`/`async with`, scheduler story. The VM IS the loop: dao.stream wait-sets + blocked outcomes + cursor resume are the native suspension primitives; Python needs only one-shot (yang.antlr.md:1368 residue analysis). The generator machinery (`py/make-generator`/`yield`/`yield-from`) is most of the runway. |

**Total: ~15–25 rounds**, 2–4 calendar weeks unblocked. Fixed per-slice overhead not in the
estimates: parity pins per host, scanner-golden regeneration (deferred-set per run),
§8.10.11 local-semantics evidence, multi-host verification.

## Governance (binding)

- Ruling v2's provenance/authenticity machinery and the (A) decision are NOT reopened by any
  slice; new prelude types go through the same export-surface audit the F2 pin enforces.
- Scanner-golden standing rule: every prelude-touching slice regenerates in-slice
  (deferred set = exactly the expected movers; three-run byte-identical).
- The contract test is remote-only (owner ruling); local gates are focused `-n` lanes.
- Each slice lands with §8.10.11 local-semantics evidence and the
  `:yang.python.antlr/unsupported` refusal removed only when the replacement is pinned.

## First actions

1. Slice 0 reading: the astra ruling + the CPython benchmark → the integer-model design brief
   for the owner (the one decision that gates the numeric floor).
2. Slice 1 dispatch (quick wins) — can run in parallel with the slice-0 reading.
3. Slices 2+ follow the gate cadence; the roadmap table above is the progress ledger.

— yang.python orchestrator seat

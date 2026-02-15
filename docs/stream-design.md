# Stream Design Summary (Working Notes)

## Context
You are defining a canonical stream model for datom.world where "everything is a stream", including VM execution traces for JIT.

## What We Agreed On

1. Streams should carry generic data, not only datoms.
2. VM execution tracing should use the same stream API as other streams, not a special tracing API.
3. Cursors should be external to stream storage state so many clients can read independently.
4. Reads should be non-destructive (cursor advances, events remain in stream history).
5. Ordering guarantee should be per-stream total order.
6. VM trace retention should be bounded by default.
7. Typed streams are useful for performance, and type constraints should be strict when enabled.
8. You prefer one concrete product type per typed stream for fast parsing.
9. For typed streams, preferred physical representation is:
   - Columnar SoA layout
   - Fully fixed-width fields
   - Variable-size data moved to separate blob streams by reference
10. Go-style channel behavior is attractive as an API ergonomics model, but should not erase append-only history + cursor semantics underneath.

## Proposed API Direction Discussed

The proposed cross-language core direction was:
- canonical stream contract at kernel level
- Clojure-specific `seq` should be an adapter, not the canonical contract
- expose language adapters per runtime (Clojure seq/eduction, JS iterator/async iterator, Python iterator/async iterator, etc.)

The proposed minimal surface discussed included operations like:
- `open` or `make`
- `put`
- `cursor`
- `next` (or `take`)
- `seek`
- `bound`
- `close`

## Important Clarifications from Discussion

1. Similarity to Kafka:
   - Yes on log + external cursor structure.
   - Different goals: VM semantic substrate, typed stream optimization contracts, explicit causality metadata, bounded stream values for replay/query/JIT.

2. Plan 9 influence:
   - Useful inspiration for simple uniform interfaces.
   - You want something stronger for typed optimization and explicit causality.

3. Optional language libraries:
   - Recommended for frontends that do not have first-class stream constructs.
   - Programmers can ignore libraries and still run code.
   - Using stream-aware libraries should unlock predictable stream-specific optimization in yin.vm.

## Decisions Still Pending

1. **Canonical read mode contract**
   - Should core be:
     - non-blocking poll (`ok|blocked|end`) plus explicit wait primitive,
     - async-first,
     - or another hybrid.
   - This was challenged and remains unresolved.

2. **Exact canonical API shape and names**
   - Final function set and naming (`next` vs `take`, `open` vs `cursor`, etc.) not yet locked.
   - Error/timeout/cancellation contracts not yet locked.

3. **Cursor identity and lifecycle**
   - Cursor representation (value vs stored entity), persistence, GC, lease/expiry semantics remain open.

4. **Typed stream schema declaration format**
   - How product types are declared, versioned, and migrated is still open.

5. **Compatibility policy for untyped streams**
   - Whether all streams are generic by default with optional typing, or typing is required in selected subsystems, remains open.

6. **Backpressure and blocking semantics**
   - How producer flow control works across bounded retention and consumer lag is not finalized.

7. **JIT integration specifics**
   - Exact event payloads, checkpoint cadence, patch/deopt event contracts, and cross-backend rollout sequence still need a final spec pass.

## Current Working Principle
Keep one universal stream abstraction with explicit causality and external cursors.  
Layer typed, fixed-layout streams for performance-critical paths (especially VM/JIT traces), while providing language-native adapters as projections rather than core semantics.

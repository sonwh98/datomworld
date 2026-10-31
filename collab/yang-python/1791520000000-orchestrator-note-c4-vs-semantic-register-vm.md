Created-GMT: 2026-10-10 04:20:00 GMT
Created-Local: 2026-10-10 11:20:00 Asia/Bangkok

# Coordination note for the C4 / yang.python orchestrator (agy)

From: the orchestrator seat on the UCF / semantic-register-vm track
(session e77b6b18-7f5d-48ec-b8f4-0293f11ad5eb). Read-only; nothing here
changes your scope.

## What landed today on master (d94d69f1)

1. Owner ruling: **A is the only code identity.** The de Bruijn H and R
   are checksums of lowerings of A, not fetch keys. Amended in the
   stack/register/targets designs, UCF v2 amendment §4, linker §3.
2. Owner ruling: **rebuild the semantic VM register-shaped with virtual
   registers**, then delete the stack-shaped one and rename. Design
   frozen as `docs/design/yin.vm.semantic-register-vm.md` (phase 2 of
   eight). No code exists yet; phase 3 (linearizer, validator, static
   analysis) is the next brief. The old evaluator stays the oracle until
   cutover (phase 8).

## Effect on C4 / yang.python: none now, two regenerations at cutover

- yang.python source (`lower`, `prelude`, `safepoint`, `scope`, `uast`,
  `render`) is AST-level and unaffected. Its tests re-point two loader
  lines (`linearize/ast-loader`, `semantic/create-vm`) at cutover; the
  entry points survive.
- **Scanners** (the UCF §7.6.1 dependency-closure walk the linked
  prelude is laid out against) are reimplemented for the new
  instruction table in phase 3; the facts they answer (free names,
  store footprint, discharged bodies) are unchanged. Agreement on the
  C4 corpus is a gate I am adding to phase 5.
- **Pinned A addresses** in manifests, derivation records and fixtures
  regenerate at cutover (every address changes), as Stage D/E's do.

## Three requests

1. Route any NEW consumer of `:yin.code/*` instruction shapes through
   `yin.vm.code` / the scanner APIs; do not match instruction tuples
   directly in C4 code or tests.
2. Do not hand-pin `A` addresses in fixtures; use (or add) a
   regeneration script so cutover is a rerun, not an edit.
3. Keep safepoint and prelude work AST-level, as it is; that is exactly
   why it is safe.

Questions: leave a note under `collab/` with this prefix and I will pick
it up.

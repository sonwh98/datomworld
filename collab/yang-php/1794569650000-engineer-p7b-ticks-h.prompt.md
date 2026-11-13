Created-GMT: $(date -u '+%Y-%m-%d %H:%M:%S GMT')
Created-Local: $(date '+%Y-%m-%d %H:%M:%S %z') (+0700)
Coding-Agent: codex
Session-ID: pending

# Task: P7-b part H — the consumer chain: verified link-time derivation + production entry profile. SOLE goal: include-handoff-test green.

Role: Engineer. Branch p7b-ticks @ b784516b (origin). Producer side EXISTS (read part G findings
1794569546000 first): content-addressed occurrence site sets + manifest-bound record + verifier;
empty sets published; SCC aliases publish empty sets. The include test fails `L` vs `LT` because
the CONSUMER chain is missing. Nothing else is in scope.

## Implement (per docs/design/yang.php.md §12 + slice-3 site-set semantics + part G findings' own list)
1. Signed discovery of the site-set record at link time (use the existing signed-discovery
   machinery the corpus/SCC aliasing already uses — same pattern as yin.vm.linker).
2. Verified link-time derivation: when linking a tick-profile unit, re-derive with the published
   site set (marks restored onto the linked image); reject mismatch via the existing verifier.
3. Hook dependency publication/pinning + bottom-up derived dependency + SCC alias rebinding to
   the derived image (the design's derivation record: input/output roots, hook profile incl
   :after, site-set address).
4. Entry production execution: selected-profile derivation at the production entry paths (not
   the trusted-uninstrumented harness shortcut — the harness may keep a derived-image path).

## Definition of done (this session)
`yang.php.antlr.ticks-test/include-handoff-test` green (sequential, 600s budget); tick-fixtures
STAY 8/8 green (re-run); siteset-test stays green; yang.safepoint-test 9/20 green. kondo 0/0 on
touched files. Push per green. If the chain is bigger than a session: push verified partials with
exact next-step notes in findings (that pattern works). Findings: collab/yang-php/${TS}-engineer-p7b-ticks-h.gpt-6.1-terra.findings.md.
Statement-selection/registry fixtures: NOT your scope (part I).

Created-GMT: $(date -u '+%Y-%m-%d %H:%M:%S GMT')
Created-Local: $(date '+%Y-%m-%d %H:%M:%S %z') (+0700)
Coding-Agent: codex
Session-ID: pending

# Task: P7-b part J — WIRE the site-set consumer chain into the PHP linker + entry paths. SOLE acceptance gate: include-handoff-test green.

Role: Engineer. Branch p7b-ticks @ 811e484b (origin). Primitives EXIST (part H, read its
findings 1794569769000 + the exact next-steps list — that list IS your edit map):
signed-entry/entry-datoms/discover/signed-entries + derive-verified (manifest-bound verifier,
per-row input hashing, published-marks application, derivation record returned but NOT
served). Nothing wired yet. Pattern reference: yang.python.antlr.siteset/stage + interpreter
composition (mirror it for PHP).

## Implement (H's next-steps, in order)
1. PHP linker stage: discovery from signed linker index datoms -> derive-verified at link time
   -> publish/serve the derived module; pin the derivation record (input/output roots, profile
   incl :after, published full site-set address even when selected-sites differ).
2. Hook dependency: publish/pin a module exporting the tick hook; derive the dependency closure
   bottom-up; rebind SCC alias requirements to derived bundle addresses.
3. Entry production paths: apply the selected profile derivation (no trusted-uninstrumented
   shortcut for the linked path); route the linked harness through the wired stage.

## Definition of done
include-handoff-test GREEN (semantic/stack/register `LT`, 600s budget); tick-fixtures stays
8/8; siteset stays green; yang.safepoint-test stays 9/20; kondo 0/0 on touched files. Push per
meaningful green. If it exceeds a session: push verified partials + exact next-step findings
(the H/G pattern — every hand-off has been precise and consumed cleanly).
Findings: collab/yang-php/${TS}-engineer-p7b-ticks-j.gpt-6.1-terra.findings.md.
Statement selection / registry fixtures: part K, NOT your scope.

Created-GMT: 2026-10-06 23:35:00 GMT
Created-Local: 2026-10-07 06:35:00 +0700
Coding-Agent: claude (fable-5-1, resume of session ff5b8c32-5cb0-4d81-9476-0a88c6109319)
Session-ID: ff5b8c32-5cb0-4d81-9476-0a88c6109319

# Task: scope ruling for D10's four-kernel restoration finding (read-only; the ruling is the deliverable)
Role: Architect

The second architect's D10 sign-off (astra, codex — its findings are at
collab/1791260000000-architect-d10-signoff-astra.gpt-6-astra
.findings.md) found the four-kernel restoration acceptance
unsatisfiable as landed: walker, stack and register sources hit the
PRE-EXISTING `:unaddressed-segment` lift refusal (export-task has only
ever addressed semantic-kernel code), so the test pins refusals where
the D10 acceptance wants successful restoration. astra's ruling
options: (a) amend D10's scope — four-kernel lower restoration moves
to D16's gate (which already owns the four-kernel zero-call list and
the corpus harness), with D10 pinning the refusal regression as
landed, and D16's brief upgraded to own the lift/lower generalization
as its prerequisite; (b) a new slice D10b for the lift generalization
before D11; (c) another shape.

Rule, considering:
- The pre-existing limitation predates D10 and predates the D-stage
  (the exporter addressed only semantic-kernel machines since stage 1).
- D16's row already owns the four-kernel gate; the generalization
  (addressing non-semantic kernels' code on lift, and their lowers)
  is real engine work that should not silently vanish.
- D11 and D12 depend on D10 landing promptly; the semantic-kernel
  lower is the path the driver composition exercises first.
- The register kernel's code-format constraint you ruled on in the D5
  cursor ruling (no boundary-opcodes change) may interact with
  generalizing addressing — say how.

Reply with the ruling: which option, what the owning slice's
acceptance contract is, and the exact D10 landing condition for
finding 3. Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

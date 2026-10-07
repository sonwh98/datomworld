Recorded-GMT: 2026-09-04
Recorded-Local: 2026-09-04 Asia/Ho_Chi_Minh

# Provenance correction

The `Coding-Agent: agy` field in
`architect-phase5-r3-r4-signoff.fable.prompt.md` and its derived findings
summary is incorrect. It was copied from an earlier AGY-oriented review prompt
and then required verbatim by that prompt.

The review was invoked directly with the Claude CLI:

    claude --model claude-fable-5-1 --name architect-phase5-r3-r4-signoff ...

Correct provenance:

- Coding-Agent: claude
- Model: claude-fable-5-1 (Fable)
- Session-ID: none

This correction changes provenance metadata only. The Fable review and its
findings remain unchanged.

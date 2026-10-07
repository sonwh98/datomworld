Recorded-GMT: 2026-09-04 05:09:00 GMT
Recorded-Local: 2026-09-04 12:09:00 Asia/Ho_Chi_Minh

# Claude session ID recovery

Several Phase 5 R3/R4 prompts and reports incorrectly recorded `Session-ID:
none` even though Claude session persistence was enabled. The exact IDs were
recovered from Claude's local session records by matching each custom session
name.

- `stream-phase5-r3-r4-fable-fixes` (Claude Opus):
  `f2d6fd9d-1b4a-4196-b2c6-fdc7b6f59305`
- `architect-phase5-r3-r4-opus-fixes-verification` (Claude Fable):
  `fde8b517-9c3f-48c6-a4d9-c455489fbebc`
- `stream-phase5-r3-r4-fable-blockers-fix` (Claude Opus):
  `68d2717c-853d-4859-8c8e-49f508a6ed62`

The current blocker-fix run is a new conversation, not a resume of the first
Opus session. It reconstructs task context from the repository diff and the
prior prompt, stdout, and findings artifacts. Future related follow-ups must
use `claude --resume 68d2717c-853d-4859-8c8e-49f508a6ed62 ...`.

For future Claude delegations, generate a UUID before launch, pass it through
`--session-id <uuid>`, and record that same UUID in the prompt. Do not infer
that a `--name` value is the session ID, and do not record `none` merely because
text output omits session metadata.

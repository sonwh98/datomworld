Created-GMT: 2026-09-08 14:40:06 GMT
Created-Local: 2026-09-08 21:40:06 +0700 (Asia/Bangkok)
Coding-Agent: codex
Session-ID: 01a080de-1a15-7d23-9a35-4106b127e4f0
# Task: confirm the memory-log plan revision (r2)
Role: Architect review

**Read-only. Print to stdout; write nothing.**

Revised plan:
`collab/1788878129246-architect-v2-memory-log-r2.claude-fable-5-1.findings.md`
Contract amendment: `git diff -- docs/design/dao.stream.md` (uncommitted).

All six findings addressed:

- **P1-1.** You ruled for the plan; I amended the contract to your wording —
  exhaustion the transport can observe and return from is `transport-error`,
  fatal host exhaustion producing no result is outside the outcome algebra,
  and a transport whose operations either complete or do not return has no
  firing condition and excludes it. §3 rewritten on that footing; Phase A now
  names the amendment.
- **P1-2.** `next` made total, range checked before any indexed read:
  non-map → `invalid-cursor`; missing identity key → `invalid-cursor`;
  identity mismatch → `cursor-mismatch`; non-integer / `< 0` / `> tail` →
  `invalid-cursor`; `0 <= pos < tail` → `ok`; `pos = tail` → `end` if
  closed else `blocked`. It argues `pos > tail` must be `invalid-cursor`
  rather than `blocked` because nothing is evicted and the tail only grows,
  so no cursor this stream minted can hold it — deliberately differing from
  the ring buffer, which is unmodified.
- **P1-3.** `run-retention-laws` runs only when `:retention` is present;
  rejects unknown values; requires a reader surface and a `gap` exclusion;
  mints the origin cursor **before** appending; compares observed sequences
  rather than cursor representations; repeats both replays after close where
  `:closable` is declared; and requires a `:retention-fixture` for
  reader-only manifests, whose absence is a violation rather than a pass. The
  ring-buffer falsification becomes a committed test.
- **P2-1/2/3.** `full` rationale replaced with the `dao.space`-semantics
  one; rotation named as future work needing a causality-carrying checkpoint,
  with the "publish and start fresh" implication removed; handoff rewritten to
  preserve host ownership; extra-key spec policy settled with fixtures.

## Judge

1. Does each finding close?
2. **The claim I most want checked:** it argues `pos > tail` is
   `invalid-cursor` here while the ring buffer answers `blocked`. Two
   transports answering differently for the same cursor shape — is that
   contract-legitimate, or does it break a law the shared suite relies on?
3. Its own new observation: *an exclusion is an unchecked assertion, because
   no fixture can be written for an excluded outcome.* Is that general enough
   to be worth recording in the contract or the conformance suite beyond
   `:retention`?
4. Anything the revision newly broke.

State plainly whether this is ready to implement.

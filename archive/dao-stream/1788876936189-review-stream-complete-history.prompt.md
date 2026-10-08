Created-GMT: 2026-09-08 14:15:36 GMT
Created-Local: 2026-09-08 21:15:36 +0700 (Asia/Bangkok)
Coding-Agent: codex
Session-ID: 01a080de-1a15-7d23-9a35-4106b127e4f0
# Task: confirm the contract fix for the completeness P0
Role: Architect review

**Read-only. Print your review to stdout; write nothing.**

You found the P0: a freshly minted `:dao.stream/oldest` cannot detect
already-evicted history, so the transactor/index plan's full-history invariant
was undeliverable. The contract is now fixed in the working tree, uncommitted.

Review `git diff -- docs/design/dao.stream.md` (+65/−2, four edits).

## What was decided

Both mechanisms stand and answer different questions: a transport that
**declares it excludes `gap`** gives *completeness*; a **kept origin cursor**
gives *detection*. Where completeness is a correctness requirement only the
declared transport delivers it, and the origin cursor is the honest failure
mode that remains when completeness was not wired.

The `dao.space` ruling: wiring a log onto an evicting transport is a host
assembly defect, and it is `dao.space`'s — latent since the local stream was
first wired, independent of this migration.

## What I verified myself

- `v2/ringbuffer.cljc:88` reports `gap` only when `pos < (:first s)`;
  `:62` mints `:oldest` at exactly `(:first s)`; `:125` advances `first'`
  on every evicting append. A freshly minted cursor is definitionally blind.
- `create!` exists in exactly one v2 namespace, `ringbuffer.cljc:152`. **v2
  has no transport that can serve a complete-history consumer today.**
- v1 local streams open with no capacity and v1's eviction test is
  `(and capacity …)`, so they never evict: today's correctness is accidental
  and undeclared.

## Judge

1. **Does the text actually close your P0?** A consumer following it must end
   up unable to make the mistake the plan made.
2. **Is the promise now true?** The old sentence ("a reader must be able to
   know it missed values") promised more than the mechanism delivers; it is now
   a promise to a *cursor*. Check that nothing else in the document still
   makes the stronger promise.
3. **Consistency with what is already there** — "declared, never
   interrogated", the outcome-exclusion rule, the `seek` rationale, and the
   `closed?` absence. The new *Explicitly Absent* bullet forbids a retention
   predicate; verify nothing else in the contract implies one.
4. **The / constraint.** The text says a transport excluding `gap`
   cannot also exclude `full` unless genuinely unbounded — "refusing and
   evicting are the two answers to the same condition". Is that right, and is
   it the complete set of obligations for whoever builds such a transport? We
   are about to build one, so an omission here becomes an omission there.
5. **Anything the fix breaks or leaves owed**, including for existing v2
   transports and for `dao.stream.ws.md` if it is affected.

State plainly whether this is ready to commit.

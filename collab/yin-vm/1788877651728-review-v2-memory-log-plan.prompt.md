Created-GMT: 2026-09-08 14:27:31 GMT
Created-Local: 2026-09-08 21:27:31 +0700 (Asia/Bangkok)
Coding-Agent: codex
Session-ID: 01a080de-1a15-7d23-9a35-4106b127e4f0
# Task: review the complete-retention v2 transport plan
Role: Architect review

**Read-only. Print to stdout; write nothing. No code exists yet.**

The contract you approved is committed (`2e25b5c`). This is the plan for the
transport it requires — the thing that unblocks the transactor/index
migration. Review it before an implementer is briefed.

Plan: `collab/1788876999259-architect-v2-complete-retention-transport.claude-fable-5-1.findings.md`

It proposes `dao.stream.memory-log`: an in-memory, unbounded, append-only
log, on the reading that v1's local streams are capacity-less ring buffers
that never evict and die with the process, so a file-backed durable log would
be scope creep changing semantics under cover of a migration.

## The one place it contradicts the contract you just approved

**`transport-error`.** The committed text says a logically unbounded writer
may exclude `full`, then: *"Logically unbounded is not physically infinite:
unexpected exhaustion of the underlying medium is `transport-error`, never the
eviction of acknowledged history."*

The plan excludes `transport-error` from **every** operation, arguing:

- the host heap is not the transport's medium — it is shared by the whole
  process, and an append that exhausts it was not refused by the log;
- heap exhaustion happens *during* the append, so there is no state where the
  transport answers `ok` and then fails to have appended;
- catching `OutOfMemoryError` on the JVM to return data would assert a
  recoverability that does not exist, and JS and Dart offer no portable signal
  — while an exclusion must hold identically on all three hosts.

My own reading is that your sentence was written with a disk-backed log in
mind and over-reaches for an in-memory one, and that the plan is right. But it
is your sentence and your call. **Rule on it.** If the plan is right, say
whether the contract needs qualifying; if the contract is right, say what
`transport-error` would mean here and when it would fire.

## Also judge

1. **The manifest** (§2) against `conformance.cljc:24`'s
   `validate-manifest`: produces ∪ exclusions must equal the contract outcome
   set per operation, intersection empty, every exclusion with a non-empty
   reason. It claims `attach!` is *absent, not excluded*, and that
   `validate-manifest` permits that. Verify.
2. **The `full` decision** (§3) — unbounded and excluded, on the argument that
   a configured maximum makes `full` **permanent** rather than transient, so
   the writer's only sanctioned recourse never comes. Sound?
3. **The conformance addition** (§4): a new `:retention :complete` manifest key
   licensing `run-retention-laws`, plus a falsification check (bolt the key
   onto the ring buffer's manifest and confirm the laws fail). Does it
   actually pin "never evicts", given no existing test can observe that by
   construction — the same blind spot that produced the original P0?
4. **Cursors** (§5) and the claim that a fresh `:oldest` *is* the origin here.
5. **The scoping judgement** (§0) — in-memory rather than durable. If you
   disagree, say what `dao.space` requires that this does not give.
6. **Anything owed and unnamed**, and whether the handoff sentence in §7 is
   precise enough for the transactor plan to quote.

State plainly whether this is ready to implement.

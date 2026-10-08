Created-GMT: 2026-10-05 14:20:26 GMT
Created-Local: 2026-10-05 21:20:26 +07
Coding-Agent: claude
Session-ID: 06a2b171-f2e5-47cd-8b2a-49779ebcbeb5

# Task: architect-hydration-ruling

Role: Lead System Architect

Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-05 21:20:26 +07 | Status: active | Rationale: owner prefers fable for Architect rulings; a design question the orchestrator may not decide

Perform a read-only architecture ruling on one question raised by the H0 engineer
while implementing slice H0 of `docs/design/yin.vm.linker.dht.head.md` (merged into
master; the working tree is /Users/sto/workspace/datomworld/.claude/worktrees/head-h0).
Do not edit any file.

## The question

Section 11, slice H0, ends with: "`seq-of` over a rehydrated index equals `seq-of`
before the restart; ... two HEAD writes of one directory never share it across
rounds, name transactions, `(reset)` and the unwritten-row recovery of
`yin.vm.linker.dht.md` 5.1. **If a HEAD move that commits no transaction is found,
the slice stops and 5.2 is reopened.**"

The engineer found one: `src/cljc/yin/repl/dht.cljc` `hydrated` (about lines
202-212) writes HEAD to a loaded remote manifest and commits no transaction. It
reports it and, following the literal stop rule, wrote no test or workaround. Its
analysis, which you must check and not trust:

- Within one directory, hydration never makes two HEAD writes share a sequence:
  it runs only into an empty directory, or one whose HEAD already names that
  manifest (`refused-head`, `open` about lines 56-80 of the same file), and the next
  publication then gets greatest `t` plus 1.
- Across directories it is the "key kept and directory lost" case of 5.2: a node
  that hydrates some other manifest and publishes under a key that already signed
  that sequence would be judged `:yin.head/equivocation` by readers. 5.2 names
  hydration as the remedy for that case, and section 5.8 (line 448) keeps
  `--dht-manifest` as an explicit hydrating pin, so it may be intended.

## Rule on it

1. Is hydration a "HEAD move that commits no transaction" in the sense of the stop
   rule, so that 5.2 must be reopened? Or does the derived-sequence claim, as
   written, cover only a directory's own publication rounds, so that hydration is
   outside it? Decide, with the reasoning and `file:line` evidence from the design
   and the code.
2. If outside: give the exact clarifying text for 5.2 and for H0's completion
   criteria so the stop rule is no longer ambiguous, and say whether H0 should add
   a test pinning the hydration cases (empty directory, same manifest, refused
   different manifest) and what it asserts.
3. If 5.2 must reopen: say what replaces the derived sequence, what it costs, and
   which slices change. Prefer the smallest sound change.
4. A second, small point: the engineer made a sequence above 2^53 - 1 malformed in
   `judge`, taken from section 5.10 (line 556: "within 2^53"). The 5.5 table does
   not name that bound. Confirm the choice or correct it, and say whether the table
   needs the row.

Read first: docs/design/yin.vm.linker.dht.head.md (5.2, 5.5, 5.8, 5.10, 11 H0),
src/cljc/yin/repl/dht.cljc, src/cljc/yin/repl/index.cljc,
src/cljc/dao/space/transactor.cljc, docs/design/yin.vm.linker.dht.md (5.1, 6.5).

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 06a2b171-f2e5-47cd-8b2a-49779ebcbeb5

Then give the ruling in the first paragraph (outside the shared claim, or reopen),
followed by: severity | file:line | invariant/evidence | recommended correction,
and the exact text to add if any.

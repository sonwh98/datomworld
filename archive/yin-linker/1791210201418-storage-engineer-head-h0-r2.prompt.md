Created-GMT: 2026-10-05 14:23:21 GMT
Created-Local: 2026-10-05 21:23:21 +07
Coding-Agent: claude
Session-ID: c7244636-7620-475e-b23a-a591106058b2

# Task: head-h0 (round 2: the hydration test)

Role: DaoSpace and DaoJing Storage Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-05 21:05:56 +07 | Status: active | Rationale: same engineer, resumed to add the test the Architect ruled H0 needs

## The ruling on your stop

You stopped correctly on the literal wording. An Architect (claude-fable-5-1)
ruled, read-only, that hydration is OUTSIDE the derived-sequence claim: 5.2 is NOT
reopened and H0 resumes. Full ruling:
`collab/1791210026362-architect-hydration-ruling.claude-fable-5-1.stdout.log`. In
short: the claim protects "the HEAD writes of one directory carry strictly
increasing sequences"; hydration is only ever a directory's FIRST HEAD write (the
different-manifest case throws in `open` before the node is joined; the
same-manifest case never sets `::hydrating`, so `hydrated` does not run), and it
installs the index at its own greatest `t`, as restart recovery does. One correction
to your analysis: for the same manifest hydration does not run at all. Your
2^53 - 1 bound is confirmed. No change to `head.cljc` or `sign.cljc` is asked.

## What to add (the Architect's spec; check it against the code)

Add ONE test group to `test/yin/repl/dht_test.cljc`, extending the setup of
`a-reader-given-a-manifest-hydrates-then-queries-the-remote-index` (about lines
584-614). Record HEAD writes the way `test/yin/vm/linker/head_test.cljc`'s
`recording` does. Assert:

1. **No HEAD.** Exactly one HEAD write, the hydrated manifest. `seq-of` of the
   reader's recovered datoms equals `seq-of` of the publisher's index. One reader
   round then writes a second HEAD whose sequence is exactly one greater. A trace
   of the hydrated manifest at that sequence is judged `:duplicate` against a floor
   equal to that sequence, and the next round's trace is judged `:candidate`.
2. **Same manifest.** Reopening the hydrated directory with the same `:manifest`
   admits evaluation at once, starts no load, and records zero HEAD writes.
3. **Different manifest.** `open` throws naming the HEAD the directory holds, HEAD on
   disk is unchanged, zero HEAD writes are recorded, and the directory opens again
   afterwards (the lock was released).

## Scope and process rules (unchanged, plus one file)

- You may now edit ONLY `test/yin/repl/dht_test.cljc` (add the test; change nothing
  existing). If the case needs a helper, add it in that file. If you find you must
  touch another file, stop and ask.
- No git commands, no formatter, no Node or Dart runs, no background processes.
- Verify in the foreground: `clj -M:test -n yin.repl.dht-test -n
  yin.vm.linker.head-test -n yin.vm.linker.sign-test` and `clj -M:kondo --lint
  test/yin/repl/dht_test.cljc`. State assertion counts. Portable `.cljc` rules and
  the ClojureDart traps from the first brief still apply (a JVM-only part is
  `#?(:cljd nil :clj ...)`, `:cljd` first).
- Do not weaken or edit any existing test. If your new test cannot be written as
  specified because the code behaves differently from the ruling, STOP and report
  exactly what the code does, with file:line.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: c7244636-7620-475e-b23a-a591106058b2

Then report: the test names, the exact commands and outcomes with assertion
counts, whether each of the three cases behaved as the ruling states (cite the
code if not), and anything unresolved.

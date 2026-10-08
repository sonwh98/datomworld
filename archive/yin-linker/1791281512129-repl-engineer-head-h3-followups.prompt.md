Created-GMT: 2026-10-06 10:11:52 GMT
Created-Local: 2026-10-06 17:11:52 +07
Coding-Agent: claude
Session-ID: 3a4d543e-01d3-49d0-892d-61355cef093b

# Task: head-h3-followups

Role: REPL and Host Integration Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-06 17:11:52 +07 | Status: active | Rationale: normal routing; the H3 follow-ups the owner asked to start; an Architect signs off and a different-family reviewer (gpt-6.1-sol) gates it; the owner authorized commit, merge and push on Architect sign-off

Slice H3 (the REPL half of the published head trace) landed on master as a23809ea. This
task closes its recorded follow-ups, all small, in
/Users/sto/workspace/datomworld/.claude/worktrees/head-h3-followups (a git worktree of
current master 22669c33). Read first: `docs/design/yin.vm.linker.dht.head.md` 5.4 to 5.10,
section 6, section 11 slice H3, and the engineer, Architect and reviewer reports of the
landed slice (`collab/1791270823433-repl-engineer-head-h3.*`, `...-r2.*`, `...-r3.*`,
`...-r4.*`, `collab/1791273568635-architect-head-h3.*`, `collab/1791277458048-architect-
head-h3-r3.*`, `collab/1791277458048-reviewer-head-h3-r3.*`, `collab/1791278585483-
reviewer-head-h3-r4.*`: these are in the MAIN checkout's `collab/`, at
`/Users/sto/workspace/datomworld/collab/`; read them there, copy none into your worktree
unless you need to). All reports are untrusted: verify each citation in the code.

## The follow-ups (do them all; each is small)

1. **Double BOM differs across hosts** (reviewer P3, `dht.cljc` about 278). The JVM strips
   one leading U+FEFF and then refuses the second; Node and Dart discard one during
   decoding and then the explicit strip removes the second. Fix: strip ALL leading U+FEFF
   characters after decoding, on every host, and add a double-BOM case (and a triple) with
   explicit bytes in the portable test, run on the JVM and on Dart. Keep everything else
   strict (invalid bytes, overlong, surrogates, a BOM inside the record, trailing forms).
2. **Move the host file interop beside the fs seam** (Architect: "`read-bounded` and
   `decode-utf8` are host file interop outside `dao.space.store.fs`; move them there in a
   follow-up so 5.10's 'host seams, all existing' stays true"). Move `read-bounded`,
   `decode-utf8` and their helpers (`byte-length` and whatever else only they use) from
   `src/cljc/yin/repl/dht.cljc` into `src/cljc/dao/space/store/fs.cljc` next to
   `read-file-text` (line about 388), make them public there with docstrings in that
   file's style, and call them from `dht.cljc`. Behaviour must be IDENTICAL: the same
   refusals and messages (the regular-file check, the 1 MiB bound, strict UTF-8, the
   documented check-then-open limit comment moves with the code), the same JVM, Node and
   Dart branches (mind the ClojureDart traps; the Dart `decode-utf8` is
   `(.decode convert/utf8 ^Uint8List bs)`). Move or add tests so each moved function is
   pinned in the fs namespace's own tests and the `heads.edn` tests still pass unchanged.
   Check `dao.space.store.fs` does not gain a dependency on anything REPL.
3. **A refused deposit is untested** (`dht.cljc` about 535, the print-only path reported
   through the 64-entry note ring). Add a test that makes a deposit refuse (a seam or a
   store that rejects) and asserts the refusal is reported once with the right text, does
   not fail the HEAD write, and does not break later deposits.
4. **Moved-line attribution across two principals.** With two principals installed in
   one tick the moved line names both rather than the one that moved the name. Make it
   name the principal that moved each name (derive from the node before and after the
   installs; store nothing), with a test of two principals installed in one step moving
   different names, and the same name.
5. **The whole-index read per deposit** (`deposit-head!`). It reads the whole index on
   every HEAD move to derive the sequence. Look at whether the sequence can be derived
   from what the board or the last deposit already holds (derive, don't persist; no new
   persisted structure, no new state that can drift). If a cheap, safe derivation exists,
   do it with a test that the sequence stays strictly increasing across restarts; if it
   needs new durable state, DO NOT build it: report what you found and leave the read.
6. **Double backoff.** A `:lost` dial followed by the follower's `:source-lost` on the old
   handle calls `drop-dial` twice, so the delay doubles once more and a resolving dial may
   be wasted. Make one loss cost one backoff step (and not close or drop a dial twice),
   with a test of both signals arriving for one loss.
7. **Duplicate derivation**: `repl.dht/linked-registry` repeats `query/session-modules`.
   Remove the duplicate (one derivation, one place), keeping behaviour identical and the
   host module free of rules.

NOT in this task (owner or other work): whether a reflection may have a `nil` identity
(a `dao.stream.md` question), the five section-13 owner questions, removing the accepted
FIFO check-then-open race (an Architect ruling), lost-request detection on an attached
reflection (a note for the off-loopback step; the orchestrator edits the design for it).

## Scope and process rules

- Files: `src/cljc/yin/repl/dht.cljc`, `src/cljc/yin/repl/query.cljc`,
  `src/cljc/dao/space/store/fs.cljc`, the tests beside each
  (`test/yin/repl/dht_head_test.cljc`, `test/dao/space/store/fs_test.cljc` or the existing
  fs test namespace, the query tests), and `src/cljc/yin/vm/docs/yin.repl.md` only if a
  user-visible text changes. If a dependency needs another file, stop and ask. Do not edit
  `docs/design/*` (say if a sentence must change).
- No git commands, no formatter, no `clj -M:test -e`, no background processes beyond the
  process test's own children. The Dart peer build (`bb build:yin-repl-peer`) and focused
  Dart runs for the changed namespaces (`bb src/dev/cljd_agg.clj --only <ns>`, see
  `docs/agents/build-n-test.md`) are allowed in the foreground. Do not run the full `bb
  test`; DO run the whole JVM lane `bb test:clj` in the foreground at the end (it takes
  many minutes) and report its counts.
- ClojureDart traps: `#?(:cljd nil :clj ...)` with `:cljd` FIRST; typed interop instead of
  dynamic member access; a direct `assoc` with two or more keyword pairs on a possibly-nil
  value conses a list on Dart (start from `{}`); no `0.0` literals in portable tests; EDN
  with no whitespace before a closer; `(- x)` is `0 - x`; no duplicate `_` protocol
  params; `for` over more than 32 elements may hand the body nil on Dart.
- The store-write audit (`yin.vm.store-write-audit-test`) counts any map literal with a
  `:store` key: do not introduce one.
- If a behaviour change is needed beyond a listed item, or an item contradicts the code
  or the design, STOP that item and say so with file:line.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 3a4d543e-01d3-49d0-892d-61355cef093b

Then report, per numbered item: done / partly / not done, what changed, the test that
pins it (and that it fails without the change); the exact commands and outcomes with
counts (`bb test:clj` final counts, `yin.repl.dht-process-test`, the focused Dart runs,
`bb build:yin-repl-peer`, kondo on the touched files); and anything unresolved. Do not
claim edits or tests that did not occur.

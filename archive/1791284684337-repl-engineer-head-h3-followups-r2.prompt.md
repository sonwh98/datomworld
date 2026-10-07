Created-GMT: 2026-10-06 11:04:44 GMT
Created-Local: 2026-10-06 18:04:44 +07
Coding-Agent: claude
Session-ID: 3a4d543e-01d3-49d0-892d-61355cef093b

# Task: head-h3-followups (round 2: two BOM findings)

Role: REPL and Host Integration Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-10-06 17:11:52 +07 | Status: active | Rationale: same engineer, resumed for a short round on the reviewer's two P3s

Your follow-ups are verified and green on all three lanes (full `bb test`: JVM 3394 tests,
Node 3245, Dart +3200). The Architect SIGNED OFF
(`collab/1791283601886-architect-head-h3-followups.claude-fable-5-1.stdout.log`), the
reviewer (gpt-6.1-sol) found no P0 to P2 and two P3s, both in `without-boms` / the BOM code
of `dht.cljc` (`collab/1791283601886-reviewer-head-h3-followups.gpt-6.1-sol.findings.md`;
both reports are in the worktree's `collab/`, untrusted, verify). Fix both:

1. **P3 quadratic copying** (`dht.cljc` about 159): removing one BOM per `subs` copies the
   string each time; a 1,048,575-byte BOM-only file took 6.48 s to refuse on the JVM. Scan
   the leading U+FEFF run by index and take ONE substring (no per-mark copy), identical
   result. Test: a large BOM-only (and BOM-then-record) input refuses/reads in bounded time
   (a generous deadline so it cannot flake; the old code must fail it on the JVM).
2. **P3 non-leading BOM is host-dependent** (`dht.cljc` about 157): the JVM refuses
   `{:version 1 ﻿:heads {}}` but the compiled Node reader accepts it as the normal
   record. After removing the leading marks, REFUSE any remaining U+FEFF anywhere in the
   text (before parsing), with a named refusal in the existing style ("not valid" style,
   consistent with the other `heads.edn` refusals), on every host. Tests with explicit
   bytes: a BOM between tokens, a BOM just before a closer, a BOM inside a string
   (also refused: a record legitimately contains none), and the leading-BOM cases still
   read. Keep every other behaviour identical.

Same rules as before: files `dht.cljc` and `test/yin/repl/dht_head_test.cljc` (and the fs
test namespace only if a moved helper changes, which it should not); no git, no formatter,
no `clj -M:test -e`, no background processes beyond the process test's own children, nothing
under `docs/design/*`. Verify in the foreground: kondo on the touched files; the changed
namespaces on the JVM (`yin.repl.dht-head-test`, `dao.space.store.fs-test`,
`yin.vm.store-write-audit-test`) and `yin.repl.dht-process-test`; a focused Dart run for
`yin.repl.dht-head-test` (`bb src/dev/cljd_agg.clj --only ...`); and, because item 2
changes Node's behaviour, a focused Node run of the same portable test if
`docs/agents/build-n-test.md` shows a way to run a single namespace on Node; otherwise say
the Node lane has not run. Do not run the full `bb test`; run `bb test:clj` fully at the end
and report its counts.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 3a4d543e-01d3-49d0-892d-61355cef093b

Then report per item: what changed, the test that pins it (and that it fails without the
change); the exact commands and outcomes with counts, including `bb test:clj`.

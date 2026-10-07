Created-GMT: 2026-09-07 14:05:11 GMT
Created-Local: 2026-09-07 21:05:11 +07 (Asia/Bangkok)
Coding-Agent: glm
Session-ID: 50d48a71-9ff9-44b7-8dc0-b334e5f42aac
# Task: implement P1 — dao.jing.file without a stream
Role: Storage & Indexing Engineer
Implementers:
- Model: glm-5.3 | Assigned: 2026-09-07 21:05:11 +07 | Status: active | Rationale: Storage & Indexing primary per team.md; it also reviewed every revision of the plan being implemented and the P0 commit this builds on

**This is an implementation task with write authority**, bounded to the files
named below. Work in `/Users/sto/workspace/datomworld` on branch
`dao.stream-redesign-v2`, currently clean at `1bebf5a`.

Read first:
- `docs/design/dao.jing.implementation-plan.md` — the whole invariants list
  (groups A-G), Decision 2, and **P1**, which is your contract
- `docs/design/dao.jing.md` — the design the invariants are drawn from
- `src/cljc/dao/jing/file.cljc` (213 lines), `test/dao/jing/file_test.cljc`
  (251), `src/cljc/dao/stream/log.cljc` (252),
  `test/dao/stream/log_test.cljc` (170)
- `src/cljc/dao/jing.cljc` for `materialize!`, `get`, `segment-key`,
  `segment-address?`, `content-hash`

## What to build

Exactly P1. `dao.jing.file` rebuilt from invariants **F1-F6 and D1-D5**, with
a private framed file replacing the `dao.stream` coupling: open-or-create,
truncate an incomplete tail, replay, append-and-sync, plus `records` (F5) as
the test's view of a path. Three host branches — `RandomAccessFile`, Node
synchronous `fs`, `dart:io` `RandomAccessFile` — written
`#?(:cljd … :clj … :cljs …)` with **`:cljd` first**: a bare `#?(:clj …)` does
not exclude code from the cljd build, and `:cljd` in tail position silently
fails.

Then delete `src/cljc/dao/stream/log.cljc`, `test/dao/stream/log_test.cljc`,
and the old `test/dao/jing/file_test.cljc`, and write a new `file_test.cljc`
from the invariants — not ported from the old one.

## What you are building from, and what you are not

The old implementation and its tests carry **no authority**. Nothing is in
production; there is no stored content to be compatible with. F3 says the
framing is free. Do not preserve the 4-byte big-endian prefix or the `pr-str`
encoding because they are there — F7 marks them `[T✗]`, implementation owed
nowhere. Choose the framing you would choose today and say why in the
namespace docstring.

Write the tests from the invariants, not from the old file. The old tests are
evidence of what was pinned, and a reviewer has already mapped all 54 of them
to invariants; anything they pinned that matters is in F1-F6 and D1-D5.

## One trap, verified and recorded

The torn-tail cases are the only coverage of the truncation code and must be
hand-written per host. **Do not copy `log_test.cljc`'s fixture**: its payload
is `(->bytes [11 22])`, which is not a decodable `[address payload]` record,
so `create-content-file` fails closed on it *before* reaching the torn tail.
Write one valid encoded record first, then hand-write the torn bytes.

## Proof required, all three hosts

Per P1: round trip of every payload kind including `nil`; `:inserted` then
`:present` with an unchanged record count; durability across close and reopen;
acknowledged insert survives immediate close; equal duplicate records recover;
collision on replay fails the open; each fail-closed category (malformed EDN,
wrong shape, invalid address, hash mismatch) fails the open; truncation of an
overlong-length tail, a sub-prefix tail and a negative length, each followed
by a clean put and reopen; put and get throw after close; close idempotent;
JVM contention writes exactly one record.

Run and report, with counts:
```
bb test:clj      bb test:cljs      bb test:cljd
clj -M:kondo --lint <each changed file>
```
`bb test:cljd` regenerates `test/cljd-out/` and only one process may own that
lane — do not run it concurrently with anything. Confirm `Testing
dao.jing.file-test` appears in the Node output rather than assuming discovery.
`test/dao/data/btree_durability_test.cljc` must pass unchanged as the consumer
check; if a stale comment in it names the log stream, fix only that comment.

## Bounds

Change only: `src/cljc/dao/jing/file.cljc`, `test/dao/jing/file_test.cljc`,
and the two deletions. `dao.jing` core, `dao.jing.mem`, `dao.jing.remote`,
`dao.jing.coordinate`, the observer, and every `dao.space*` file are **out of
scope** — P1 touches no observer and no pool. `dao.stream.file` (the live-tail
`:file` transport consumed by `yin.io.file`) is a **different transport**: do
not touch it. Do not stage or commit anything; leave the work in the tree for
review. If a suite outside your scope fails, stop and report rather than
widening.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: glm
Session-ID: 50d48a71-9ff9-44b7-8dc0-b334e5f42aac

Then report: the framing you chose and why, every file changed or deleted,
the three suites' counts, kondo results, which invariants each new test
covers, and anything in P1 you could not do with the reason.

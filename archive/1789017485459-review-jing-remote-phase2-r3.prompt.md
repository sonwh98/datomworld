Created-GMT: 2026-09-10 05:18:05 GMT
Created-Local: 2026-09-10 12:18:05 +0700 (Asia/Bangkok)
Coding-Agent: codex
Session-ID: 01a0868e-e9f2-7242-92e8-58d63e7f9574 (resumed)
# Task: confirm dao.jing.remote Phase 2 — the tenth path and the close pin
Role: Routine Review

**Read-only. Print to stdout; write no file.** Scope: `git diff` for
`src/cljc/dao/jing/remote.cljc`, `test/dao/jing/remote_test.cljc`,
`docs/design/dao.jing.remote.implementation-plan.md`,
`docs/design/dao.jing.md`. **Ignore `docs/agents/*`** — the owner's parallel
edits.

Both your findings are settled, one of them by disproof.

## P1 — the tenth path, fixed as an entry gate

`validate-timing-options!` now runs at `call!`'s entry, **before the lock and
before `request!`**, so an invalid `:request-timeout-ms` or
`:poll-interval-ms` throws while nothing is on the wire and there is no state
to settle. Your framing was "another exit"; the implementer pointed out it is
better understood as an **entry gate**, and I agree — the principle is that an
argument defect must throw **before** the wire, not after it. My N11 and
`dao.jing.md` text now say that rather than counting it as an exit. Judge both
the fix and my wording.

## P2 — your suggested seam is impossible for this var, and that was measured

The implementer implemented your `with-redefs` wrap of `stream/close!`. It
**counted zero while the exit demonstrably ran**, in-runner and out. Cause:
`close!` is a `defprotocol` method (`IDaoStreamClosable`,
`dao/stream.cljc:182`), and Clojure links protocol call sites directly to
the interface method, bypassing the var. I verified the declaration.

So it took the alternative: the test keeps its three valid assertions and the
comment now states the close is **statically reviewed, not tested**. No claim
of coverage it lacks remains. Judge whether that is the right resolution, and
whether any *other* mechanism would pin it without a production seam — if
none, say so, because that conclusion belongs in the record.

## Verified by me

clj **1458 / 165526**, cljs **1360 / 35026** (remote-test present), cljd
**+1314**, demo 212 files 0 warnings — all zero failures. Closure greps **0**
in all three code files; `reset! (:rpc` appears **once**, inside `settle!`.

## What to judge

1. **The entry gate** — is it genuinely before any submission on every path
   into `call!`, and does it cover both options and the values a caller can
   `assoc` in?
2. **Is there an eleventh?** Three paths that skipped `settle!` have surfaced
   across three rounds. Say plainly whether you believe the enumeration is now
   complete, and on what basis — that judgement matters more than another
   pass over what is fixed.
3. **My document edits** — N11's entry-gate paragraph and `dao.jing.md`'s
   sentence. Accurate, or overstated?
4. **Anything the gate broke** in what you have already cleared.

## Report

Ordered by severity. If clean, say so plainly and say it is ready to commit.

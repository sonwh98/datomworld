Created-GMT: 2026-09-10 05:38:30 GMT
Created-Local: 2026-09-10 12:38:30 +0700 (Asia/Bangkok)
Coding-Agent: codex
Session-ID: 01a0868e-e9f2-7242-92e8-58d63e7f9574 (resumed)
# Task: confirm dao.jing.remote Phase 2 — the bounded gate and the close pin
Role: Routine Review
Implementers:
- Model: gpt-6-astra | Assigned: 2026-09-10 12:38:30 +0700 | Status: active | Rationale: GPT family, independent of the GLM implementer per team.md's family rule; this conversation found every defect in this phase and holds its full history

**Read-only. Print to stdout; write no file.** Scope: `git diff` for
`src/cljc/dao/jing/remote.cljc`, `test/dao/jing/remote_test.cljc`,
`docs/design/dao.jing.remote.implementation-plan.md`,
`docs/design/dao.jing.md`. Ignore `docs/agents/*`.

Both r3 findings are settled, and your named mechanism worked.

## P1 — the timing domain is bounded on both ends

`max-timing-ms` = **86400000 (one day)**, public, beside the three defaults.
The stated reason: a timing option bounds how long **one** connect or call may
wait; a day is the longest duration that still means that, and leaves nine
orders of magnitude of headroom against long overflow.
`validate-timing-options!` now requires `(and (int? value) (<= 1 value
max-timing-ms))` — `int?` rejects the BigInt/BigDecimal shapes that satisfy
`integer?` but cannot become a JVM sleep duration. Placement unchanged:
`call!` before the lock and before `rpc/request!`; `connect-content!` first
form of its body, before the descriptor and before `attach!`.

**The domain is inclusive** — the bound is a supported value, proven working
end-to-end on both paths, everything strictly past it rejected. The
implementer asked; I confirmed inclusive.

Boundary proof: at the bound both paths succeed; `(inc max-timing-ms)`,
`Long/MAX_VALUE` and `1234567890123456789012345N` each throw on both paths
with nothing submitted, nothing attached, allocator unmoved. Mutation check:
gate reverted to r4's `(and (integer? value) (pos? value))` → **8 failures**,
including `inc max-timing-ms` completing normally and putting its op on the
wire.

## P2 — your mechanism works, and the test no longer touches the network

`with-redefs` on `jvm/connect!` — a plain function var, reachable, unlike the
protocol method — installs a scripted socket whose `:close!` records.
Attacher, `WsHandle`, protocol dispatch and `stream/close!` all stay real.
Deleting `(stream/close! handle)` from the ninth exit fails exactly that
assertion. The ServerSocket machinery is gone, so this test no longer pays
N2's one-JDK-connection-per-run.

## Verified by me

clj **1458 / 165538**, cljs **1360 / 35026** (remote-test present), cljd
**+1314**, demo 212 files 0 warnings — all zero failures. Emitted
`remote-test_test.dart` imports `ws/jvm` **0** times; `remote.dart` imports
v1 RPC **0** times.

## What to judge

1. **Is the domain now closed?** Any value passing `(and (int? v) (<= 1 v
   86400000))` that still breaks either deadline computation or
   `Thread/sleep`, on any of the three hosts?
2. **Is the bound's reasoning sound**, and is inclusive the right ruling?
3. **Does the close pin bite for the right reason** — is it possible for the
   recorder to fire without the production close having run, or vice versa?
4. **Is the enumeration complete now?** You declined to call it complete while
   the gate had a hole. The hole is closed; say plainly whether you can call
   it complete, and on what basis.
5. **My document text** — N11's entry-gate paragraph and `dao.jing.md`'s
   "before the wire" sentence. Do they now match the code exactly?

## Report

Ordered by severity. If clean, say so plainly and say it is ready to commit.

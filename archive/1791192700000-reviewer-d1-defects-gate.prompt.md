Created-GMT: 2026-10-05 09:26:00 GMT
Created-Local: 2026-10-05 16:26:00 +0700
Coding-Agent: glm (glm-5.3, plan review)

# Task: gate review, M-next D1 — the version-0 codec/address mismatch fix (read-only; a verdict is the deliverable)
Role: Review (routine gate)

Review the uncommitted work in /Users/sto/workspace/datomworld-d1 (branch
ucf-d1-defects, based on master). The engineer's report is at
/Users/sto/workspace/datomworld-d1/collab/1791191725340-compiler-engineer
-ucf-d1-defects.findings.md. Read it first, then `git -C
/Users/sto/workspace/datomworld-d1 diff` for the full change (two files:
src/cljc/yin/vm/ucf/handoff.cljc, test/yin/vm/ucf/handoff_test.cljc).

## The contract

The third post-A version-0 defect (linker-dht 14.3, C4 rulings): export
returned `:address` minted by `jing/content-hash` over the body map while
`:bytes` were `dao.stream.cbor` bytes, so the address was not the digest
of the emitted bytes. The fix: the returned address is the digest, under
the jing address scheme, of the exact bytes export emits. The body's
bytes must not change (version-0 wire frozen). Test-first red-then-green;
the engineer reports red as 24 tests / 164 assertions / 2 failures /
1 error and green as 0/0 with the new assertions (segment address valid,
digest of `:bytes` equals the address, `jing/segment-bytes-match?`
accepts, different bytes rejected).

## What to attack

1. The address shape changed from a bare hex string to a segment keyword
   (`:segment/blake3-<hex>`). Search the tree (src/ and test/) for every
   consumer of `export-task`'s result and of the old address shape; is
   any consumer broken by the shape change? Is the new shape consistent
   with how handoff.cljc and checkpoint.cljc handle addresses elsewhere
   (e.g. what `jing/segment-bytes-match?` and the linker store expect)?
2. The version-0 wire frozen claim: does any byte that crosses hosts
   change? The body bytes, the codec, the grammar must be untouched.
3. The fix's correctness across hosts: the address is derived from the
   emitted bytes; is the derivation host-independent (codec, digest,
   keyword construction)?
4. The new tests: do they actually pin the contract (red-then-green
   evidence), are they portable .cljc (:cljd-first reader conditionals,
   no float literals, no host-number traps), and do they fail loudly if
   someone reintroduces the mismatch?
5. No other behavior change: the diff should touch nothing but the
   address derivation and the tests; check the diff line by line.
6. Style: lines <= 80 columns, ASCII only (the engineer could not run
   cljstyle).

Verdict first: READY or NOT READY (with what must change), then numbered
findings with file:line evidence. Read-only: edit nothing, run no suite;
the orchestrator runs the three lanes.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

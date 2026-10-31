Created-GMT: 2026-09-22 07:15:02 GMT
Created-Local: 2026-09-22 14:15:02 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: d3f7a2c9-1b8e-4a6f-9c5d-7e2b8f4a3d61 (resumed: your B0 and B1 session)
# Task: debruijn-b1-fix: apply the independent reviewer's findings
Role: yin.vm / Interpreter Engineer
Implementers:
- Model: claude-sonnet-5 | Assigned: 2026-09-22 14:15:02 +07 | Status: active | Rationale: same engineer fixes its own code (resume rule); reviewer was qwen3.8-max (different family), verdict NOT READY, two P1s

Same worktree (/Users/sto/workspace/worktree-debruijn-b1). Do NOT stage or
commit; the previous B1 commit was undone (soft-reset) specifically so this
fix round happens before any commit, not after. Input:
collab/1790060000000-reviewer-debruijn-b1.qwen3.8-max.findings.md, read in
full. Apply every P1 and P2, and every P3 (all five are described by the
reviewer as small and local; apply all).

## P1s (mandatory; these defeat guarantees this phase exists to provide)

1. **`:str` operands bypass the UTF-16 surrogate guard.** In
   `encode-operand`, the `:str` case (`(framed :string (or x ""))`) hashes
   a raw string without the surrogate check `encode-scalar` already applies
   to `:const` string values. Fix by routing non-nil `:str` operands through
   the same guarded path (either call `encode-scalar` on it, or add the
   explicit `well-formed-utf16?` check and throw the same qualified
   `:unsupported-value` ex-info `encode-scalar` throws). This must also make
   `image-defect` itself reject a malformed `:str` operand before hashing,
   not just make `encode-operand` throw -- check both entry points the
   reviewer named (`image-defect`'s kind check at the old line ~607, and
   `image-hash`'s call into `encode-operand`).
2. **The scope validator silently drops a conflicting second arity/chain
   declaration for one body pc.** `all-body-chains` (and `walk-body`'s
   re-reach case) currently skips an entry already present in `owner`
   instead of detecting the conflict, so two `:closure` instructions
   declaring the same body pc with different arities can both validate,
   and which one "wins" depends on BFS/walk order -- a hand-built image can
   pass validation with a `:load-bound` that is in range under one
   declared chain but out of range under the other, then fault or misread
   at runtime. Fix: when a body pc is reached again with a DIFFERENT chain
   than already recorded, return a defect (a new rule, e.g.
   `{:rule :scope-conflict, :pc entry}`) instead of silently keeping the
   first one seen. Handle both the `all-body-chains` BFS case and the
   cross-body `:jump` re-reach case in `walk-body`. Add a test using the
   reviewer's exact fixture:
   `[[:closure 2 3] [:closure 1 3] [:return] [:load-bound 0 1] [:return]]`
   must now be rejected, AND the swapped-order variant
   `[[:closure 1 3] [:closure 2 3] [:return] [:load-bound 0 1] [:return]]`
   must ALSO be rejected (proving the fix removed the walk-order
   dependence, not just this one ordering).

## P2s (mandatory)

3. **JVM `host-double?` accepts BigDecimal/Float, which then silently fold
   to the nearest double bit pattern.** Make the JVM arm of `host-double?`
   require `(instance? Double v)` specifically (keep the CLJS/CLJD rules as
   they are, which the reviewer confirmed correct there). A JVM BigDecimal
   or Float value then classifies as unsupported (nil from `scalar-class`),
   giving the qualified `:unsupported-value` refusal instead of silent
   canonicalization. Add a test: `(encode-scalar 0.1M)` and
   `(encode-scalar 0.10000000000000000001M)` no longer both succeed and
   collapse to the same bytes; either both refuse, or -- if you decide
   BigDecimal deserves its own future scalar class -- refuse for now and
   say so in the report (do NOT add a new scalar class in this fix round;
   that is new scope).
4. **No test exercises any `:unsupported-value` refusal path, on any
   host.** Add a cross-host deftest: a lone UTF-16 surrogate (JVM `"\uD800"`,
   CLJS `(js/String.fromCharCode 0xD800)`, CLJD's equivalent construction)
   passed to `encode-scalar` throws `{:rule :unsupported-value, ...}`, and
   `image-hash` of `[[:const <that string>] [:return]]` throws the same;
   after the P1-1 fix, also test
   `[[:gensym <that string>] [:return]]` throws. This directly proves
   P1-1's fix and closes the coverage gap the reviewer traced P1-1's
   survival to.

## P3s (all five; reviewer says each is small and local, apply all)

5. `saturated-operands` (hand-copied) is missing `[:ffi-call 2]`. Add it,
   or derive the set from the source table's saturated operands for
   carried mnemonics if that is cleaner -- your call, say which.
6. Ratio components outside long range throw an unqualified
   `IllegalArgumentException` from `int64-le-hex`. Range-check before
   calling it and throw the qualified `:unsupported-value` ex-info, or
   route through the `:bigint` text encoding rule -- your call.
7. On CLJS, `:uint`/`:pc` operands admit unsafe-integer doubles (e.g.
   `1e30`) that JVM correctly rejects, giving silently inexact bytes on
   CLJS and host-divergent validation. Bound `nonneg-int?` (or wherever
   `:uint`/`:pc` operands are kind-checked) to the safe-integer range on
   CLJS.
8. The hashed descriptor contains prose sentences (the `:image-hash`
   formula description, the lift-morphism sentence), so an editorial
   change to either sentence forks every image's H. Move those sentences
   into docstrings; keep the hashed descriptor as pure data (keywords,
   version numbers, arity, slots).
9. `image-scalar-classes` scans only `:const` operands, missing
   `:store-get`/`:store-put`'s `:data` operands, so a receiver's
   host-support refusal can silently miss an unsupported class actually
   present in the hashed bytes. Scan every `:data`-kind operand across all
   mnemonics, not just `:const` (the reviewer calls this "cheap, strictly
   more conservative").

## Never

Do not add a new scalar class, do not touch `yin.vm.code`, `:yin.code/*`,
or the merged projection namespace, do not implement B2/B3/B6. Keep every
existing passing test passing; if a P3 fix changes a golden fixture's
bytes (it should not, since these are all refusal/robustness fixes, not
encoding-shape changes), stop and report why before changing a pinned
literal.

## Environment

Same as your prior turns: default PATH gives Java 21 and the mise clojure
and bb. If kondo, cljstyle, the Java 17 lane, or the CLJD lane are denied,
say exactly what was denied and stop; do not retry, do not use
`--dangerously-skip-permissions`.

## Final report

Begin exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: d3f7a2c9-1b8e-4a6f-9c5d-7e2b8f4a3d61
Then: per finding (agree and how you fixed it, or declined with reason);
files changed; new/changed tests; whether any golden fixture literal
changed and why; what you ran with exact counts; what you could not run;
every deviation. Facts only; promise nothing.

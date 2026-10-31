Created-GMT: 2026-09-15 22:46:40 GMT
Created-Local: 2026-09-16 05:46:40 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: claude
Session-ID: 206534e8-0432-4941-8631-0212c8f59132
# Task: ast->semantic-bytecode r5 — fix one narrow not-empty bug, update deferred-set disclosure wording
Role: VM Runtime
Implementers:
- Model: claude-opus-5 | Assigned: 2026-09-16 05:46:40 +07 | Status: active | Rationale: same implementer, resumed session

## Context

gpt-6-astra's r4 review confirmed all three r3 fixes genuinely closed
(nested metadata, reader-position stripping, operand-vector metadata —
all reproduced and verified fixed). Two smaller items remain:

## Fix 1 — `not-empty` discards a metadata-bearing empty map

`strip-reader-positions`' final step does something like `(not-empty
(strip-reader-positions (dissoc m ...)))` to decide whether to keep the
metadata at all. Reproduction:

```clojure
{:type :literal
 :value (with-meta [] (with-meta {} {:meaning 1}))}
```

Here the OUTER value's metadata is `{}` (empty as a map), but that empty
map ITSELF carries metadata `{:meaning 1}` (via `with-meta` on the map).
After your recursive fix, `(meta (meta value))` should still be
`{:meaning 1}` — but `not-empty` on the stripped `{}` returns `nil`
(since `not-empty` only looks at the map's own entries, not its
metadata), discarding the carrier and losing the `{:meaning 1}` that was
attached to it.

Fix: only collapse the stripped metadata map to `nil` when it BOTH has no
entries AND carries no metadata of its own — i.e. check
`(and (empty? stripped) (nil? (meta stripped)))` rather than relying on
plain `not-empty`.

## Fix 2 — update the deferred custom-comparator-set disclosure wording

gpt-6-astra's r4 review found the deferred case is subtler than "always
throws": it constructed two sorted sets (comparator over `pr-str`) where
`same-meta?`'s set-element lookup can pick the wrong element depending on
traversal order — sometimes throwing (fail-closed), sometimes silently
accepting a mismatch (NOT fail-closed). Update the disclosure in
`same-meta?`'s docstring to say this deferred case is **silent and
order-dependent**, not reliably fail-closed — don't claim it always
throws, since that's no longer accurate. No code fix needed for this one,
just correct the docstring's claim.

## Contract

Add a regression test for Fix 1 (the exact reproduction above — assert
`(meta (meta value))` survives round-trip as `{:meaning 1}`, or that the
function now correctly preserves it). Re-run `clojure -M:test -n
yin.vm-test`, confirm 0 failures.

## Boundaries

Only `src/cljc/yin/vm.cljc` and `test/yin/vm_test.cljc`.

## Deliverable

Report exact diff, reproduction-then-fix evidence, and test results.
Write findings to
`collab/1789529200000-vmruntime-ast-semantic-bytecode-r5.claude-opus-5.findings.md`
with the same header block as this prompt. Nothing staged or committed.

Created-GMT: 2026-09-15 22:30:00 GMT
Created-Local: 2026-09-16 05:30:00 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: claude
Session-ID: 206534e8-0432-4941-8631-0212c8f59132
# Task: ast->semantic-bytecode r4 — recursive metadata handling (the r3 scope-out was wrong)
Role: VM Runtime
Implementers:
- Model: claude-opus-5 | Assigned: 2026-09-16 05:30:00 +07 | Status: active | Rationale: same implementer, resumed session

## Correction to r3's scope-out decision

gpt-6-astra's r3 review
(`collab/1789525500000-review-ast-semantic-bytecode-r3.gpt-6-astra.stdout.log`
— read the full report) proved the orchestrator's r3 judgment wrong:
"metadata nested inside metadata" is NOT pathological. It reproduced
silent corruption using completely ordinary nested reader syntax through
`yang/compile`:

```clojure
(if true
  ^{:note ^{:meaning 1} x} []
  ^{:note ^{:meaning 2} x} [])
```

Both branches receive the same address; reconstruction gives BOTH
occurrences `{:meaning 2}`. This is a real, reachable defect, not a
scope-out candidate. Same for reader-position stripping: ordinary
`LineNumberingPushbackReader` output (reading `^{:note (helper x)} []`)
attaches `{:line 1 :column 9}` to a metadata VALUE, and that survives
stripping through both conversion functions today.

The root cause in both cases is the same: `same-meta?` and
`strip-reader-positions` recurse into a value's STRUCTURE, but neither
recurses into a value's METADATA'S OWN VALUE (which can itself carry
metadata, arbitrarily deep). Fix that one shared gap properly, in both
functions, rather than patching each call site.

## Fix 1 — `same-meta?` must compare metadata recursively, all the way down

Currently `same-meta?` compares `(meta a)` and `(meta b)` at each level
with (implicitly) `=`, which doesn't detect a metadata VALUE that itself
carries different nested metadata. Change it so that comparing metadata
maps recurses through `same-meta?` itself on the metadata's own keys and
values, not just `=`. Concretely: wherever you currently do something
like `(= (meta a) (meta b))`, replace it with a recursive call that walks
into the metadata map the same way `same-meta?` already walks into
map/set/sequential structure — metadata is just another value that can
carry (meta ...) at any of its own nesting levels.

## Fix 2 — `strip-reader-positions` must recurse into metadata's own value AND the metadata map's own metadata

Currently it dissocs `:line`/`:column`/`:end-line`/`:end-column` from a
value's own metadata map, but doesn't recurse `strip-reader-positions`
onto the VALUES held in that metadata map (so a metadata value like
`(helper x)` that itself carries reader-position metadata isn't cleaned),
and doesn't recurse onto the metadata map's OWN metadata (metadata can
itself carry metadata, per Clojure's data model). Fix: after computing
the stripped/dissoc'd metadata map, recursively apply
`strip-reader-positions` to every value in that metadata map (and to the
metadata map's own metadata, if it has any) before reattaching it.

## Fix 3 (new finding from r3) — operand-vector (`:nodes`) metadata has the same asymmetry `:syms` params just got fixed for

`:application`'s `:operands` slot and `:dao.stream.apply/call`'s
`:operands` slot are both `:nodes` kind. Projection's `:nodes` case
(`mapv convert v`) always mints a metadata-free vector, same as `:syms`
did before your r3 fix — but `dao.jing/segment-key` hashes vector
metadata, so a hand-constructed row with a metadata-bearing operands
vector can hash correctly, pass reconstruction, and then re-project to a
different address. Fix reconstruction's `:nodes` case the same way you
fixed `:syms` in r3: reject (throw `:slot-kind`) if the operands vector
itself carries any metadata, since projection can never produce one that
does.

## Confirmed still deferred (r3 reviewer agrees)

Finding 4 (a set built with a custom comparator that admits `=`-equal,
metadata-distinct elements) remains out of scope — genuinely requires a
deliberately pathological collection construction, unlike the two fixes
above which are reachable through ordinary nested `with-meta`/reader
syntax. Keep the existing docstring disclosure for this one specifically;
remove or correct the disclosure text that previously (incorrectly)
grouped the nested-metadata case with it.

## Contract

Add tests reproducing gpt-6-astra's exact r3 reproductions (the nested
`^{:note ^{:meaning 1} x}` case, the reader-generated nested-position
case, and the operand-vector metadata case) and assert they're now
fixed. Re-run `clojure -M:test -n yin.vm-test`, confirm 0 failures.
The CLJD lane should be usable now (a concurrent fix landed for the
unrelated `test/bench/yin_vm_bench.cljc` compile blocker) — check `ps`
yourself to confirm no other process holds it before running
`bb test:cljd`.

## Boundaries

Only `src/cljc/yin/vm.cljc` and `test/yin/vm_test.cljc`.

## Deliverable

Report exact diff, reproduction-then-fix evidence for all three fixes,
and test results. Write findings to
`collab/1789527000000-vmruntime-ast-semantic-bytecode-r4.claude-opus-5.findings.md`
with the same header block as this prompt. Nothing staged or committed.

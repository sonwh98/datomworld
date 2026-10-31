Completed-GMT: 2026-10-14 06:40:00 GMT
Completed-Local: 2026-10-14 13:40:00 +07
Coding-Agent: claude
Session-ID: claude-fable-5-1 (read-only review session)

# L-d-2 adversarial review: registry pin invariant

Reviewed: uncommitted diff on `linker-ld2` vs master@f484b5f3, files
`src/cljc/yin/vm/module.cljc`, `src/cljc/yin/vm/engine.cljc`,
`test/yin/vm/linker_require_test.cljc`, `test/yin/vm/module_test.cljc`,
`docs/design/yin.vm.linker.md`, `docs/design/yin.vm.linker.dht.md`.
Slice row and acceptance (scoping log §3, L-d-2): `module/pin-conflict`;
`receive-module` refuses; `link-install` present-and-unequal only; docs 7.4 and
4.3 row; flip `:absent` to link ok; deferred pin then a `try-require` at another
address refuses as data with registry and stores unchanged; concurrent install
refused at the parent's receive; `:matching`, `:inherited`, `:fresh` unchanged.

## Verdict: APPROVE-WITH-NITS

No P0/P1/P2 found. Every acceptance line is implemented and covered by a test
I traced by hand. Independent verification on this worktree:
- `clj -M:kondo --lint` on the four changed Clojure files: 0 errors, 0 warnings.
- `clojure -M:test -n yin.vm.linker-require-test -n yin.vm.module-test`:
  52 tests, 1211 assertions, 0 failures, 0 errors.

## Checklist results

1. `module/pin-conflict` (`module.cljc:113-142`)
   - Case (a): `existing` scans every linked owner (`some? :address`) whose
     manifest pins `module-name` at an address other than `(:address entry)`. Correct.
   - Case (b): `incoming` scans the entry's own pins against held entries by
     registry path (`module-entry`), refusing on unequal address. Correct.
   - Pure: derives everything from manifests already in the registry plus the
     incoming entry; no pin table, no persisted state. Derive-don't-persist holds.
   - Determinism: conflicts sort by `[(str name) (str owner) (str pinned)
     (str address)]`. Addresses are `jing/segment-key` keywords
     (`:segment/blake3-...`), so `str` and `=` are host-stable; `module-entries`
     walk order does not matter after the sort. Vector-key `sort-by` has cljc
     precedent (`publish.cljc:234`).
   - Shape matches the brief exactly: `:status :refused :reason
     :dependency-pin-mismatch :module :name :pinned :linked`.
2. `receive-module` (`engine.cljc:1103-1104`)
   - The throw is the first form, before `attach-module`, `lower-resources`,
     store instantiation and `assoc-module`. Nothing is mutated on refusal.
   - All three callers sit inside existing try/catch: spawn-child inheritance
     (`start-install`, phase `:loading`), act 3 and act 4 in `link-install`
     (`advance-install` halted branch, phase `:linked`, from the round's starting
     state). No new catch site was needed; no throw can leave a round.
   - Inheritance of a consistent registry cannot self-refuse: any subset of a
     consistent registry is consistent, and `spawn-child` seeds the child's
     registry with the full linked set before receiving each entry.
3. `link-install` (`engine.cljc:1387`)
   - `(and (some? entry) (not= pinned linked))`: present-and-unequal refuses,
     absence passes. The check is still needed (not redundant with receive):
     it compares against the halted child's registry, where a dependency the
     installing module itself required can sit at the wrong address before the
     module's own pin is in any registry (`a-pin-mismatch-refuses-before-lifting-test`).
   - Q4 hole closed end to end: deferred pin, later require at Q, parent act 3
     receive of bar@Q trips case (a) against foo's pin P
     (`deferred-pin-refuses-a-later-different-address-test`, both `require` and
     `try-require`, registry/stores/store asserted unchanged).
4. Tests and portability
   - `module_test.cljc`: (a), (b), matching, absent, owner-name tie-break, host
     entry under a pinned name. Good.
   - `linker_require_test.cljc`: `:absent` flipped to link ok and asserts only
     `'foo` was requested; three previously absent-based fixtures now use real
     present-and-unequal conflicts; concurrent conflict covers both act 3
     (pin owner foo) and act 4 (pin owner baz); inconsistent composition refuses
     at `:loading` with state unchanged.
   - No `with-redefs`, no host interop, maps start from `{}`, `doseq` not `for`.
5. Invariants: language-blind (no frontend words), A is the only identity
   (pins compare manifest addresses), derive-don't-persist. All hold.

## Findings (none blocking)

P3 | docs/agents/known-gaps.md:17 | Ledger row "a host entry under a pinned name
refuses with `:linked nil`, same as absent" is now stale: absent is permitted at
install and at receive, while a held host entry (nil address) still refuses in
both `link-install` and `pin-conflict` case (b) (unit test "a held host entry
also disagrees"). | Reword the row to "absent is permitted; a host entry under a
pinned name refuses with `:linked nil`", keep it open at P3.

P3 | docs/design/yin.vm.linker.dht.md:1873 | The new 7.4 bullet says "no linked
module may have an address different from any linked manifest's pin", but
case (b) also refuses a non-linked (host) entry held under a pinned name. Doc
is narrower than code. | Say "no held module" or point at the ledger row above.

P3 | src/cljc/yin/vm/module.cljc:136 | The pinning owner is computed for the
sort and then discarded; combined with `error-refusal` overwriting `:module`
with the installing module, an act-4 conflict surfaces as `:module foo :name
bar` with no trace of which manifest (baz) carried the pin. Shape is per brief,
so not a defect here. | Ledger as a diagnosability gap; a later slice may add a
`:pinned-by` key with a shape amendment, not this one.

P3 | src/cljc/yin/vm/engine.cljc:1321 | Inheritance runs `pin-conflict` once
per linked entry, each re-walking `module-entries`: O(N^2) per spawn over N
linked modules. Negligible at current registry sizes; noting for the record. |
No change now; if spawn cost ever shows, check the inherited registry once
before the reduce.

## Out of scope, not findings
- Scoping §2.3's 5a/5b doc-justification rewording and the 7.5.3 "deferred
  requirement is asked on the importing task's own request" sentence are not in
  the L-d-2 acceptance row and are absent from this diff; neither phrase exists
  verbatim in the docs today. Leave to L-d-3 or a docs pass.

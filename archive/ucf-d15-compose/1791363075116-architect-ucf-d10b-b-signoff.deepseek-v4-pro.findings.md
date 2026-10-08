# Lead System Architect Sign-off — UCF Stage D10b-B (Four-Kernel Lift/Lower)

- **Reviewer**: Lead System Architect (`deepseek-v4-pro`)
- **Target**: branch `ucf-d10b-kernel-lift-lower` (working tree atop `9cea60dd`), 2,384 insertions / 205 deletions across 13 files
- **Normative basis**: `docs/design/yin.vm.universal-continuation-format.v2-amendment.md` (authoritative for body version 2) + parent `yin.vm.universal-continuation-format.md` (authoritative for v0/v1)
- **Independent review under sign-off**: `collab/1791362024950-reviewer-ucf-d10b-b-deepseek-v4-pro.findings.md` — verdict **ACCEPT**

## Verdict: GRANT — sign-off for staging and committing Stage D10b-B

The implementation is a faithful realisation of the v2 amendment. I independently
re-verified each of the five architectural rulings this stage asks me to make,
spot-checking the load-bearing dispatch and layout code against the specification
rather than relying on the independent reviewer's trace. All four closed
execution profiles, the structural custody/fork dispatch, and the
stack/register prefix-sum layout reconstruction are correct. I grant formal
sign-off for committing D10b-B, with the D16 gate remaining open on the two
verification-completeness findings (F2, F3) — which are D16's acceptance rows,
not this stage's implementation burden (§11).

---

## Ruling 1 — Closed Execution Profile Registry: SATISFIED

`ucf/profiles` (`ucf.cljc:51-69`) is a closed registry keyed by literal
unqualified engine keywords, each an exact three-key `:yin.k/contract` map.
The contract strings resolve to `vm/semantic-contract` `"v3"`,
`vm/stack-contract` `"b2"`, `vm/register-contract` `"r2"`, and
`vm/ast-contract` `"v3"` (`vm.cljc:260-278`) — matching §1's table exactly.
Walker and semantic share `"v3"` and are distinguished by `:yin.k/engine`
alone; neither is inferred from the string. `profile-engine`
(`ucf.cljc:77-91`) enforces the exact-map equality (`= 3 (count profile)`) and
compares the inner `:yin.k/version` on the canonical CBOR integer kind
(`cbor/numeric-kind` = `:integer`, `cbor/num=`), so a float64 carrier never
validates as a version — satisfying §2's `N` requirement on every host.

Lift emits the canonical contract directly from the registry
(`handoff.cljc:1600-1602`, `:yin.k/contract (get ucf/profiles engine)`), never
inferred from image shape; the engine is taken from the kernel's own
`link-format` via `engine-of`, not from a guessed profile. `ucf/contract-stamp`
remains frozen for v0/v1 and is untouched by this stage.

**Ruling: CONFIRMED.** The four profiles are exact, closed, and correctly
carried; version-kind checking is correct.

## Ruling 2 — Custody & Structural Dispatch: SATISFIED

Fork vs. exclusive is derived **structurally** from the custody-arm key
intersection, not from a mode flag and not from body version alone.
`checkpoint.cljc:75-77` defines `header-keys` as the exact §3 set
`[:yin.k/policy :yin.k/occurrence :yin.k/arbitration :yin.k/origin :yin.k/next-op-seq]`.
`inspect-v2` (`checkpoint.cljc:454-473`) computes
`fork? (not-any? #(contains? body %) header-keys)` — an empty intersection is a
fork, any non-empty intersection routes through `check-root-header!`, which
enforces the role-specific table: halted roots carry `:yin.k/origin` only and
require it (`checkpoint.cljc:188-194`); blocked/parked exclusive roots require
policy/occurrence/arbitration/next-op-seq with policy exactly
`:yin.k/exclusive` (`checkpoint.cljc:195-222`); install children carry none of
the five (`:child-header` refusal, `checkpoint.cljc:230-232`). A fork baseline
is explicitly marked `::fork`, forbids carried operation ids (`:fork-op-id`),
and is refused by an authority rather than having header keys added.

The reader-side version gate (`handoff.cljc:3281-3310`) and the whole-tree
exclusive/fork check both key off this same structural intersection for
version 2, and off `(= 1 v)` for version 1. Version-2 reader dispatch
(`handoff.cljc:3516-3517`) computes `version-one?` separately from validated
body version plus structural role — precisely the §10 instruction to "dispatch
by validated version plus structural role", not `>= 1`.

**Ruling: CONFIRMED.** Structural custody dispatch honors §3 exactly; there is
no new mode flag and no version-alone inference.

## Ruling 3 — Four-Kernel Lowering & Resumption: SATISFIED

I independently verified the two most subtle reconstruction paths against §4.1:

- **Stack** `compose` (`stack.cljc:224-243`) and **register** `compose`
  (`register.cljc:245-274`) both derive the offset table by prefix sums of
  instruction counts, relocate **only** `:pc`-kind operands (register also
  shifts each body's inclusive start/end), rebuild the aggregate hash from the
  relocated concatenation, and refuse `:layout-overflow` against
  `max-layout-length` (2^52−1). Register preserves the empty-base convention —
  `:hash (when (seq instructions) (rcode/register-hash segment))` returns nil
  until a nonempty image attaches (§4.1's nil-aggregate rule).
  `unrelocate` is the exact inverse, and `slice`/`row-at` enforce the
  half-open row-interval and one-past-end rules.

- The reviewer's trace of register sparse-frame validation
  (`reffects/continuation-defect` + `:image`-equality + site/context gate,
  §5.3/§7.2) and the walker closed-Kw chain (`kw-fields`, `kw-node-tags`,
  `eval-operand` runtime-field dissociation, FFI `request-sent`/`eval-call`
  correlation against live call-ids, `decode-kw` without inventing PCs,
  §5.4/§7.3) is consistent with the specification text; I find no divergence.

- Lower rebuilds isolated code spaces (`stack/rebuild`, `register/rebuild`,
  `walker/attach-rows`+`clear-code`), clears semantic layout before rebuild
  (§9), restores waits in wire order, and delivers no wait result during
  restoration — matching §9's isolation contract.

**Ruling: CONFIRMED.** Prefix-sum layout relocation, sparse-frame rules, and
the walker Kw codec are implemented to specification; cross-kernel resumption
is sound.

## Ruling 4 — Verification Evidence: SATISFIED on JVM and Dart (core); Node outstanding

- `collab/jvm-final.log` tail: `Ran 1197 tests containing 13596 assertions. 0 failures, 0 errors.`
- `collab/cljd-v2.log` shows a **Dart (ClojureDart)** run of the core
  four-profile round-trip namespace `yin.vm.ucf.handoff-v2-test` — `engines =
  [:semantic :stack :register :walker]` — 9 tests, `All tests passed!`,
  covering the blocked-reader, blocked-write, sent-FFI, retained-FFI, link,
  install-child, and explicit-park/halt round trips across **all four
  profiles**, plus the fork-refused-by-exclusive-only and
  exclusive-needs-grant custody rows.

This materially refines the independent reviewer's F2 premise ("only the JVM
run is evidenced"): Dart has been exercised for the core cross-profile round
trips. Node is still unproven, and the full 12-row §11 matrix — in particular
row 10 (canonical-byte cross-host agreement) — has not been run on all three
hosts.

**Ruling: PARTIALLY DISCHARGED.** Sufficient for D10b-B commit; insufficient
for D16 (see F2).

## Ruling 5 — Advisory Findings F1/F2/F3

### F1 (naming of `v1?`, `handoff.cljc:1447`) — ACCEPT as advisory; rename, do not "simplify"

`v1?` is `(boolean (or (some? header) (::v1 opts) v2?))` — it no longer means
"version 1" but "use the jing canonical codec + exclusive-style lift", and it
**silently includes version 2**. I confirm the reviewer's deeper note: this
conflation is **load-bearing and correct in behavior**, because (a) version
emission is `(cond v2? 2 v1? 1 :else 0)` with `v2?` tested first, so v2 still
emits `2` and pulls `:yin.k/contract (get ucf/profiles engine)`; and (b) `v1?`
being true for v2 is what routes the v2 lift through `jing.cbor/encode` and the
`jing.cbor/refusal` non-portable catch. The actual §10 rule ("do not replace
`version == 1` with `>= 1`") is **honored**, not violated: reader-side custody
dispatch uses the separately-computed `version-one?` from validated version +
structural role, never this flag. The residual risk is purely a naming trap
for a future maintainer.

**Architect's ruling:** rename `v1?` to `jing-codec?` (or `exclusive-lift?`),
keeping the version-2 dispatch visibly separate. This is a readability cleanup,
**not** a gate for D10b-B commit and not a mechanical "fix" (a naive
`(= 1 …)` change would break the v2 non-portable catch). Target it as a small
follow-up before D16 if convenient; it does not block staging.

### F2 (Node/Dart runs before D16) — ACCEPT, refined; still a D16 gate

Correct the premise: **Dart has already run** the core four-profile round trips
(`cljd-v2.log`, 9/9 passing). What remains before D16 is (a) **Node** for the
same surface, and (b) the **full 12-row §11 matrix on all three hosts**, with
row 10's canonical-byte agreement being provable only cross-host. No code
change is implied; this is a verification-completeness gate, and per §11 it
sits at **D16**, not at D10b-B.

### F3 (acceptance-row test-matrix completeness for D16) — ACCEPT; require a traceability confirmation

The 10 `handoff_v2_*` namespaces (52 `deftest`s) cover rows 1–4 and 6–8 with
evident dedicated tests. Rows 5 (image growth with cross-image return frames,
empty base, row boundary), 9 (D4–D9 export holds in root **and** child),
10 (canonical bytes across hosts), and 12 (isolation/poisoning) need an
explicit traceability check that each maps to a real test — the reviewer could
not confirm a dedicated namespace for every one of them in the diff. This is a
coverage-audit item, not a defect. **Architect's ruling:** before D16, produce
a one-line-per-row mapping of the 12 §11 rows to concrete test names; add any
genuine gap. Not a D10b-B blocker.

---

## Conditions attached to this sign-off

1. **D10b-B may be staged and committed now.** The four architectural
   invariants (closed profile registry, structural custody dispatch,
   four-kernel lowering/resumption, JVM+Dart-core verification) are satisfied,
   and no correctness defect exists in the implementation.
2. **D16 and stage-D completion remain gated** on:
   - F2 — Node run + full 12-row matrix across JVM/Node/Dart (row 10 canonical
     bytes included);
   - F3 — explicit 12-row → test-name traceability, with gaps filled.
3. **F1** — rename `v1?` to `jing-codec?`/`exclusive-lift?` as a readability
   cleanup (pre-D16 if convenient; not a commit blocker, and not to be done as
   a naive mechanical change).

The implementation does not reverse the D14 v1 dependency repair, does not
change any instruction/AST-row/scalar-class/opcode vocabulary, and keeps
legacy v0/v1 bytes and ledger pins unchanged — consistent with §1 and §10.

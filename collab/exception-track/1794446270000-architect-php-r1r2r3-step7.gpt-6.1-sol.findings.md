Created-GMT: 2026-11-12 01:28:59 GMT
Created-Local: 2026-11-12 08:28:59 +0700 (Asia/Ho_Chi_Minh)
Coding-Agent: codex
Session-ID: unavailable in this review environment
Role: Architect, step-7 read-only code review
Reviewed-Base: 0e666a3f
Reviewed-Tip: 4939a3ae
Reviewed-Commits: 1757aae7, e4f271ff, 4939a3ae
Verdict: FIX-LIST — NOT MERGE-READY

# PHP R1+R2+R3 step-7 findings

Two implementation defects remain outside the disclosed 18 lane-blocked assertions.
The primitive adoption correctly changes where retention lives, but the exported receiver
still provides an indirect arbitrary-provenance mint. Reception also mistakes a foreign
map with a PHP-looking handle key for a valid local object and performs an invalid cell read.

No source, tests, fixtures, ledgers, commits, or runtime configuration were changed for this
review. The findings artifact is the only requested repository write. The contract test,
slow e2e corpus, remote lanes, and engineer gate suites were not run by this reviewer.

## Blocking findings

### P1 — Remove the exported transitive mint, php/receive-condition

Evidence: src/cljc/yang/php/antlr/prelude.cljc:2851 defines the receiver under `php/`;
:2872 and :2873 send its arbitrary condition argument to the private minting paths.
Those paths call `php.rt/mint-retained` at :4648 and :4653. The export rule at
src/cljc/yang/core/prelude.cljc:71 takes every definition in namespace `php`;
src/cljc/yang/php/antlr/prelude.cljc:6115 uses that rule without a deny-list.

A firsthand namespace-load inspection printed:

```
:receive-condition-exported true
:mint-exported false
```

A caller of this exported helper can supply its own non-nil condition map, catch the generated
local wrapper, and rethrow it from a later PHP entry. The bridge at prelude.cljc:2727 then
authenticates the legitimately minted cell and forwards the caller-chosen condition as retained
authority. This does not require extracting a seal, mutating a companion, or receiving a genuine
prior retained exception.

A bounded, parserless JVM AST-walker diagnostic constructed exactly that sequence: invoke
`php/receive-condition` under `php/try`, retain its wrapper, then raise the wrapper from a
`php/make-function` body whose published entry receives a non-returning raise exit. Its result:

```
:export-forgery {:forwarded true, :reason :yang.call/invalid-invocation}
```

The chosen condition was
`{:yang.call/reason :yang.call/invalid-invocation :yang.call/data {:forged true}}`.
This is mechanism evidence from synthetic guest UAST plus the actual export set; it is not a
claim of a parsed cross-language or linked acceptance test.

This is the exact transitive-helper bypass forbidden by the binding adoption spec,
collab/exception-track/1794471000000-to-yangphp-adoption-spec-and-dispatch-confirmed.md:28.
Python closes it by using the non-exported reserved `dsg__condition-exception`
(src/cljc/yang/python/antlr/prelude.cljc:1044), with reception inside the invocation's
callback (:1061). Hiding only the final constructor is insufficient.

Required repair: make the condition-accepting receiver compiler-private/non-exported and
update all three helper references (:2876, :2879, :4891). Preserve exact-symbol resolution
in bundled and linked forms. Audit the entire transitive condition-to-mint path, not just
names containing “retained”. Add an export/projection regression that fails at this tip
because `receive-condition` is exported, and a forged-condition regression through the
available guest-facing module surface. Re-run the existing parity, lifetime, clone, and
mutation pins.

The new adoption pin at test/yang/call/php_exception_test.clj:370 checks only
`mint-retained`; the `php.rt/` prefix assertion at :372 cannot detect a dangerous helper
exported under a stripped `php/` name. The older surface pin at :341 misses this helper too.

### P2 — Authenticate handle candidates before local-Throwable classification

Evidence: src/cljc/yang/php/antlr/prelude.cljc:2848 accepts any non-nil `:php/obj`
when `:php/type` is absent. At :2870 that passes into `php/throwable?`.
The existing `php/instance-of` then performs
`(cell/get (get v :php/obj))` at :4573 without authenticating the candidate.

An otherwise-foreign thrown payload `{:php/obj 42}` is ordinary legal foreign data.
It passes the new shape test and causes an operational refusal instead of being wrapped
in a local ForeignException retaining the complete condition.

Firsthand bounded JVM AST-walker diagnostics delivered both payloads through a synthetic
callable's supplied raise exit, `yang.call/invoke` via `php/call`, and `php/try`:

```
:plain-map {:class ForeignException, :condition-retained true}
:handle-lookalike :failure Dead or forged cell reference
{:reason :dead-or-forged-reference, :effect :cell/get, :kind :cell-ref, :id nil}
```

The synthetic callable had the same entry/raise-exit shape used by the kit; no invalid cell
operation was requested by its body. The invalid read is introduced by PHP reception.
Thus this is not a demand to catch and relabel a caller's VM/custody failure. Reception must
avoid performing the invalid operation on an otherwise-foreign payload.

The raw-cell classification defect fixed in e4f271ff is real and its repair works for
non-object-looking data, but “whatever its representation” at :2858 remains too broad.

Required repair: distinguish valid local object representations from arbitrary lookalikes
before dereferencing/classifying them, using a total authenticated reference query for a
handle candidate and safe validation of the state used for local class lookup. Preserve
the existing legitimate map-error representation and unchanged local-Throwable identity.
Do not add a broad host exception catch. Add a boundary regression for a PHP-handle-looking
foreign map, retaining the original reason, complete evidence, and payload identity.

## Item 1 — R1 bridges: ACCEPT, with acceptance obligations carried forward

- Function entry reads the supplied exit at prelude.cljc:2706. Binding-profile comparison
  (:2708) precedes the bridge frame (:2713). Unsupported-binding transfers at :2733 under
  the untouched caller context.
- Named placement (:2720) and the sole function-cell `:code` application (:2724) are
  both inside the bridge body. Its handler either forwards authenticated retained
  provenance or makes a fresh thrown condition (:2727).
- Closure entry (:4798) delegates to the function cell's entry and changes only callee
  (:4802), preserving the supplied exit, positional/named arguments, and profile.
- `php/try` restores the saved context before its handler (:274); `php/raise` pops and
  runs intervening finally frames (:220). No broad host catch was introduced.
- Bound methods copy the entry with the state (:3890); invocation routes to `php/call`
  or `php/call-named` (:3900). Higher-order calls route through `php/call-value`
  (:4857); ordinary method/static paths go through `php/invoke`. Lowering emits the
  three public call helpers (src/cljc/yang/php/antlr/lower.cljc:1645). Inspection found
  no additional function-cell `:code` bypass in the PHP prelude/lowering family.

The entry structure supports exactly-once transfer under the stipulated non-returning
raise exit. The directed pins at php_exception_test.clj:144 and :186 check that path and
identity; I did not rerun those linked pins. Suspension, recursion, cleanup replacement,
and completed-call exit lifetime remain acceptance obligations, not proven by this source
inspection. Generator context save/restore at prelude.cljc:5106, :5115, :5123, and :5137 is
consistent with a fresh resume context; this is not a new suspension proof.

## Item 2 — R2 reception: PARTIAL; P2 blocks approval

The representation guard fixes the disclosed raw-cell/float-value failure, and valid local
Throwables are raised unchanged at prelude.cljc:2871. The generated ForeignException is a
local Exception subclass (:5820); its mint receives the complete condition (:4628), not
selected evidence. Its fixed local message (:4649) does not invoke foreign methods.
The entry's retained branch (:2731) avoids wrapping an unchanged generated rethrow.

However, arbitrary foreign data is not safely classified in all representations: P2 is a
newly reproduced counterexample. The success of the engineer's raw-cell fixture does not
cover the object-looking-map case.

## Item 3 — R3 provenance adoption: MECHANISM ACCEPTED; EXPORT CLOSURE REJECTED (P1)

The cell mechanism matches the accepted Python adoption:

- Atomic reserved-door allocation at prelude.cljc:4618 places provenance on the actual
  identity cell under `:php/obj`; no intermediate holder is used.
- Memberless/missing classes refuse at :4611 instead of producing an unmarkable retained
  map. The ordinary non-retained `php/make-error` fallback remains a different path.
- Reader at :4642 passes the identity cell to `cell/dsg__provenance`, with no attribute,
  ordinal, content equality, or wrapper-supplied condition consulted.
- Both doors are declared at :99. Firsthand export inspection found zero `php.rt`
  exports. The r2 lexical retention-store definition, installer, placeholders and init
  hook are absent from the final prelude.
- VM allocation stores value/seal/provenance together
  (src/cljc/yin/vm/engine.cljc:2811); authenticated read is total (:2828).
  `heap-write` changes the value while preserving provenance (:2444).
  GC traces the provenance sibling (:2628).

Consequently contents-cloning into a fresh unmarked cell carries no provenance;
mutation of the genuine cell's visible contents does not replace the association.
The PHP pins at php_exception_test.clj:235 and :268 exercise these respective attacks.
There is no source-readable companion condition to overwrite or extracted companion seal
to stamp onto another cell. The old store's lookup/growth residual is removed; ordinary
heap reachability retains the condition.

This closes the mechanism defects exposed by the JS ce99e65e/31287b17 fix arc, including
companion mutation and metadata-based authority. JS's eventual private identity membership
and hidden helper exports are the relevant comparison (current JS prelude :4131, :4141).
But PHP's exported receiver leaves the transitive minting-authority hole open. Thus the
adoption as a whole is not equivalent to the accepted Python/JS closure of that edge.

Nil provenance is refused by the VM (:2812). The absent PHP pre-guard remains the same
documented low-priority behavior split accepted for Python (8018005d merge record);
it is not an additional blocker here.

## Item 4 — Disclosed r2 lane findings: disposition

### Shutdown/global release: prefer an instrument-shape repair

The diagnosis is supported by unchanged implementation: `php/run-main` runs shutdown at
prelude.cljc:5758; shutdown calls release-globals (:4376), which explicitly unsets every
global (:4338). The kit source initializes `$original` at module scope
(test/yang/call/exception_contract.clj:60) and exports functions reading it (:61).

Preference on record: keep PHP lifecycle behavior and change the kit-owned setup so the
identity-bearing payload is created after unit shutdown, before the observations, as the
directed source's `seed` already does (php_exception_test.clj:109). Retain the original
payload-identity/once/after assertions and use one real object across all relevant
observations. Do not weaken expectations or silently mark these rows green. The steward
owns that fixture change and ledger disposition; this review does not implement it.

The disclosed 18 assertion failures are structurally plausible: six fresh-egress failures,
three local-reception failures, and nine duplicated batch failures. I did not independently
rerun or compare their failure bytes against the old base.

### Concurrent-content stall: no direct core change, possible workload interaction

No dao.jing, query, linker, scheduler, content store, or shared kit implementation changes
in the reviewed diff. The kit explicitly runs three futures reading shared published content
(test/yang/call/conformance.clj:468). Its cross-frontend reproduction does not by itself
exclude PHP interaction, since PHP publication is shared by those legs.

The larger PHP module, per-entry result-cell allocation (:2712), bridge/capture work,
and provenance allocation can alter content sizes, allocation pressure and scheduling.
Those are plausible triggers for an existing stall, not proof of a PHP concurrency bug.
The disclosed base-fast/head-hang sample should stay on record. No evidence here warrants
moving a dao.jing repair into this PHP round. Keep the shiva clean-content acceptance run
and bounded per-leg diagnosis; do not treat a retry as proof the stall is fixed.

## Item 5 — Scope and gate plausibility: ACCEPT WITH RECORD CORRECTIONS/LIMITS

The actual diff is 10 files: the PHP prelude, the new PHP boundary-test namespace, and
eight exception-track briefs/spec/findings records. Runtime regions are function entry,
Closure entry, reception through the three helpers, retention helpers, host-door declaration,
and the seeded exception hierarchy. This is consistent with the supplied PHP round and
adoption briefs. No shared kit, design doctrine, scanner golden, ledger, or other frontend
implementation was edited. `git diff --check 0e666a3f..4939a3ae` passed.

The referenced region registry
`collab/exception-track/1794343000000-exception-track-surface-registry.md` is absent from
this worktree and from the reviewed base. Its literal region list cannot be certified here;
scope was checked against the available binding briefs and actual diff instead.

Gate tables are plausible as scoped engineer reports, not independently reproduced gate
results. The hard-rule parity pin (:298) uses the kit boundary and parsed PHP bare rethrow,
asserts the fully qualified reason and condition identity, and checks all three backends.
The clone/mutation pins inspect guest identity before rendering. Test data and assertions
were not weakened by r2/r3. The reported Node/Dart/remote and slow-corpus gaps are explicit.

Correction: there are **13 PHP fixtures**, confirmed by loading the actual contract data,
not 15. The batch test at php_exception_test.clj:327 has
`1 + 13 * (1 + 3) = 53` assertions. Three failing fixtures therefore leave **10 green
fixtures**, not “the other 12” in r2's gate table and r3 findings:83. This arithmetic
correction does not change the nine batch failures.

The gate tables do not cover the exported receiver attack or malformed handle reception;
their green claims can coexist with both findings. Missing acceptance evidence remains:
the 12 directed pairs, full context-lifetime scenarios, required portable lanes, slow e2e,
and the shiva-only contract gate. Also ensure the merge's required dao.stream gate is
accounted for (docs/agents/build-n-test.md:836); no result for it appears in these tables.

## Reviewer verification and final disposition

Firsthand: base/tip/commit scope, full code diff and relevant call paths, actual export set,
door declarations, fixture count, bounded parserless JVM AST-walker diagnostics above,
and clean diff whitespace. Diagnostics used the installed JDK and existing local jars
directly with a 60-second process bound; no dependency installation or repository cache
generation was needed. No linked/parsed-source acceptance result is claimed.

**FIX-LIST, not MERGE-READY.** Close P1 (exported transitive mint) and P2 (unsafe local-object
classification), add the missing regressions, and re-run the focused gates. The lifecycle
instrument decision and remote acceptance evidence remain explicitly steward-owned.

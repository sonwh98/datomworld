# Architectural sign-off: Track A Slice L-f

Date: 2026-10-08.
Architect: Codex, Lead System Architect.
Repository: `/Users/sto/workspace/datomworld-l-f`.
Branch: `linker-l-f`, based on master at `e12765c8`.
Verdict: **ACCEPTED for merge under the amendments below.**

## 1. Scope and authority

Reviewed the complete [Engineer Report](./1791406000000-engineer-l-f.claude-opus-5-5.findings.md),
[Remediation Specification](./1791405500000-architect-c4-p2-remediation.claude-fable-5-1.findings.md),
and [Adversarial Review Report](./1791406500000-reviewer-l-f.codex.findings.md),
the architectural foundations, and `git diff master -- src test docs`.
The implementation is in the working tree, not a committed branch diff.

This ruling explicitly amends the remediation specification where named.
It preserves the earlier reports as the historical record; the engineer's
original omission was not authorized until this ruling. The reviewer's
REVISE verdict was correct against the unamended specification.

F1 and F2 are resolved by formal acceptance amendments. F3 is a confirmed,
pre-existing liveness defect assigned to a separate linker slice, with an
explicit amendment to P2's evidence requirements. F4 is corrected in the
design document in this review. No further implementation remediation is
required before L-f merges. This sign-off neither merges nor commits files.

## 2. F1 — explicit oracle-sizing waiver (R4, R5 and section 3.3)

**The quadratic Datalog oracle on `wide-module 1000` is not required for
L-f acceptance, on any host. No intermediate-size oracle run is required.**
Replace R4's prelude-sized oracle requirement, and R5's inherited sizing
requirement, with these two distinct obligations:

1. Semantic conformance: retain the existing 20 fixtures plus
   `wide-module 12` for all three query-oracle comparisons. Compare full
   occurrence records, including root/path, `:in-body?` and
   `:conditional?` as applicable, retaining the shared-row exact-path
   assertion and duplicate checks.
2. Scale acceptance: retain the guarded slow publication test using
   `wide-module 1000` (6006 rows, 11003 occurrences in the reported run),
   under the default bounds, with the outcomes specified in section 3
   below. Preserve the host slow-lane evidence and JVM measurements.

These together satisfy the amended R4/R5 acceptance gate. The slow lane
does not have an additional large-oracle obligation for this slice.

Rationale: the inspected walker classifies each occurrence using its own
path and scope, without a row-level visited set or a size-dependent branch.
The shared-row fixture exercises the principal semantic hazard, and the
other fixtures exercise nested binders, conditional definitions, invocation
positions and discharge cases. Increasing the wide fixture from 12 to 1000
repeats the same construction; it primarily tests size, fetching and
publication. Those concerns have separate large-fixture evidence. Accepting
that division is an architectural judgement about sufficient evidence,
not a claim that publication proves equality of scanner relations.

The 8–15 minute oracle cost alone would not waive a written requirement.
This explicit amendment does. Neither engineer nor reviewer ran the
6006-row oracle, and this sign-off does not claim otherwise. Residual risk
of a discrepancy outside the tested fixtures remains. `vm/occurrence-rules`
and the definition/application queries remain normative; no semantic
equivalence rule is weakened, and future scanner changes must preserve
the conformance fence. This sizing waiver is specific to L-f.

## 3. F2 — formal replacement of section 3.3's slow-test expectation

Replace the requirement for four successful links with:

> The synthetic wide module publishes a complete closure under default
> bounds and produces one link result for each of the four formats.
> `:yin.semantic/code`, `:yin.debruijn.code` and
> `:yin.debruijn.register` each return `:ok`, retaining exactly the
> declared primitives `#{+ *}`. `:yin.ast/code` returns
> `:refused :undeclared-free`, naming a sibling export, pending L-b.

The existing slow test is accepted under this amendment. It asserts the
AST refusal reason and membership of its name in the export set; it need
not pin the incidental first sibling to `f1`.

Publication completeness and success of every returned link are different
claims. Here the closure is complete even though the AST link is refused.
Each definition form is itself an application site. Under the unchanged
tree dominance rule, a sibling definition does not precede every site,
so a sibling body read remains an obligation. This agrees with section 6's
already stated L-b limitation. Do not alter step 5a, declare sibling names
as primitives, or weaken the scanner to manufacture four successes.
L-b owns the future semantic change and deliberate update of this test.

## 4. F3 — serving policy, deferred liveness, and P2 evidence amendment

**Explicit `:trusted` is the correct serving policy for P2's self-publishing
composition. Multi-round verifying-attempt liveness is an independent
deferred linker item, not a merge blocker for L-f or the amended P2.**

The defect is real: `attempt-budget` counts 64 drive rounds, including
rounds that successfully fetch content. `attempt` constructs a fresh
runtime on retry. A larger fetch repeats its prefix rather than resuming
the previous link state. The reviewer's reproduction at `wide-module 30`
shows that this is not merely a timeout estimate for the full prelude.
Increasing ring capacity does not repair the state loss or round cap.

For the corpus legs, keep section 6's explicit composition:

```clojure
(link/composition
  {:content-store store
   :name-env {'py address}
   :derivation :trusted})
```

This applies because that composition published the module into its own
store. It does not authorize an implicit trusted default, automatic
fallback when verification stalls, or trusting arbitrary remote content.
The default remains `:verifying`; DHT policy is unchanged. Trusted serving
reports `:trust :composition`, never `:verified`.

Amend the P2 requirements added by remediation section 6 as follows:

- Keep A1's publication through `publish-module!` and its verifying
  `link-local` outcomes once per namespace on each host. P2 must inspect
  the three lowered outcomes for `:ok` and `:trust :verified`; a manifest
  address alone is insufficient evidence. This retains actual verifying
  derivation evidence through the local runtime, which does not use the
  REPL attempt budget.
- Defer the large `a-verifying-serve-links-py` slow test, including its
  verified-versus-trusted output parity requirement, to the independent
  liveness remediation. It is removed from P2's current acceptance gate,
  not silently replaced by a trusted result or recorded as passing.
- Retain the small-module default-verifying serving coverage already
  present in L-f. It demonstrates policy plumbing, not large-fetch
  liveness. P2's trusted corpus parity, publication checks, cost report
  and size budget remain required.
- Assert serving trust on the response. The registry stores the
  `:derivation` record, not a separate `:trust` field. When the deferred
  verifying-serve test is restored, it must assert `:trust :verified`
  on the response and any registry derivation assertion separately.

Deferred item: **REPL link attempt continuation across budget yields**;
owner: linker/REPL seat, separate specification and review. Its acceptance
must demonstrate completion of a responsive multi-thousand-part verifying
fetch over successive bounded serves, without restarting the fetched
prefix, while retaining finite work per serve and content/identity checks.
It must cover delayed responses and preserve honest pending/refusal
behavior. The exact continuation or scheduling mechanism is not selected
here; removing bounds or merely raising 64 is not this disposition.

Large default verifying serves remain unsupported by the present attempt
driver. This is a documented limitation, not a claim that verification or
remote liveness was fixed by L-f. P2 may proceed after L-f lands under
the amended evidence plan above.

## 5. F4 — corrected scanner description

Updated [linker design section 4.1](../docs/design/yin.vm.linker.md): each
scanner uses the same direct occurrence walk, invoked separately, then
filters and sorts its records. A scanned tree verification performs three
traversals; there is no single fused execution pass. Sorting adds cost
beyond traversal, so the complete scanner must not be described as
strictly linear solely because its traversal is linear. No fusion or
performance redesign is required for acceptance.

## 6. Related contract clarifications

R3 expressly permitted the optional request key. L1 is an installation
invariant and production-call-site restriction, not a claim that the
exported low-level API enforces caller provenance. A step-4 completion
without `:obligations` is not an installable, discharged image. The current
manifest derivation fetch is the sole production caller requesting it;
ordinary requested images and serving/install paths must continue scanning
and must not forward an untrusted requester's scan suppression flag.
No private-API refactor is required in L-f.

Section 5.2's unchanged-default-behavior clause has one explicit additive
exception: lowered serving responses carry the derivation record and trust
classification required by R6/section 5.3. Default policy remains verifying;
this exception does not authorize a new registry trust slot or engine change.
Documenting the request key in design section 6.3 is authorized by R3.

## 7. Evidence and merge disposition

The inspected diff preserves bounds enforcement, identity and whole-value
validation before scan suppression, occurrence-local scope, existing
dominance semantics, and vector scanners. Both closure walks and local
links receive caller bounds. The measured definition/application query
costs authorize R5's conversions. The reported JVM publication time of
38.7 seconds meets the 60-second target; it is a measurement, not a test
assertion or a cross-host performance guarantee.

Acceptance relies on the recorded engineer and independent reviewer
executions, not newly claimed reruns in this sign-off:

- Reviewer: focused JVM, 109 tests / 1201 assertions, passing; JVM slow
  publication, 1 test / 11 assertions, passing; focused Node, 156 tests /
  1792 assertions, passing; focused Dart, 109 passing.
- Engineer: Node and Dart slow lanes passing, including the large fixture.
- Broad fast lanes retain the named baseline failure/error
  `yin.vm.ucf.handoff-v2-census-test/a-version-2-body-is-the-same-bytes-on-every-host`
  due to missing `test/resources/yin/vm/ucf/handoff-v2.txt`. Broad lint has
  the reported unchanged-file warnings; changed-file reviewer lint is clean.

These baseline exceptions are accepted for this slice, not represented as
green full suites. This review changes only the F4 design prose and adds
this sign-off; no production or test code is changed. A final
`git diff --check` passes. Runtime lanes were not rerun for these prose-only
changes, and the waived large oracle was not run.

**L-f is ACCEPTED for merge with F1–F4 disposed of above.** No additional
L-f remediation is pending. Merge must include the F4 wording correction;
the collaboration reports remain unstaged under the existing workflow.
L-b and REPL verifying-attempt liveness remain separate deferred work.

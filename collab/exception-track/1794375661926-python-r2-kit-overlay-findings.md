Created-GMT: 2026-11-11T05:41:01.926963+00:00
Coding-Agent: codex
Session-ID: 01a1c90e-221e-7f11-8906-e490c6c6379a
Branch: exception-py-r2r3
Regression instrument: be87df74

# Python r2: the acceptance overlay restored the baseline prelude

The steward's red output is real. The saved acceptance inputs show why it differs
from the clean branch: the overlay replaced the fixed prelude after checkout.
No additional runtime change is justified by this reproduction. This round adds
both an explicit injected-call pin and the exact Python batch path to the unit gate.

## Exact divergence and evidence

Read-only inspection of shiva's existing `~/ci/xtrack-py-logs/head` recorded
`a396ae28ebba86d544dae9dc9578d2a48c11129a`. Its rc file recorded the 2928-second
failing contract/pair run. Its `cmd2.log` contains the reported Python
`:yang.call/thrown` / `:condition-identical false` output.

However, `/tmp/xtrack-py.sh:9` (the saved launch script) extracts
`~/ci/xtrack-py.tgz` AFTER checking out the branch. That archive contains one file:
`src/cljc/yang/python/antlr/prelude.cljc`. The remote archive's file and the saved
local archive's file have the same SHA-256. Both are byte-identical to the prelude
at **4cfa609d**, before all three exception fixes. Remote modification times tie
these inputs to the run: archive `2026-11-10T23:37:55Z`, launcher `23:38:08Z`,
recorded HEAD `23:38:17Z`, final test log `2026-11-11T00:27:22Z`.

| Content | Prelude SHA-256 |
| --- | --- |
| Remote and local xtrack-py overlay; git 4cfa609d | `78c406307a7464d9a64336b5bf15af7bc75afe84323cb7c1111fd6ae42a5ad80` |
| Git a396ae28, 04cb7887, and this clean branch | `dd004ce5d21a6cf51c07869fe26cc2cee16162df0a6f20802c3f4c4dec87354d` |

The archived prelude has no `ForeignException`, `py/condition-exception`, or
`py/retained-condition`. Its exact loss points are:

- `prelude.cljc:1046`: protocol reception creates a fresh `TypeError`, dropping
  the incoming condition.
- `prelude.cljc:912`: public egress unconditionally constructs a fresh
  `:yang.call/thrown` condition around that exception.
- `prelude.cljc:1045`: otherwise-foreign thrown payloads are raised raw, so the
  foreign-reception fixture cannot receive a local ForeignException.

The clean branch already repairs these paths at current `prelude.cljc:917`,
`:1051`, and `:1061`: generated local exceptions retain the complete condition in
an identity-owned slot outside source-writable attributes; egress forwards that
retained condition. Fresh/forged exceptions still produce fresh thrown conditions.

## What the first gate missed

There was no weaker reason/identity assertion in the original protocol pins:
`check-fixture` already compares each full compliant expected map, including
`:reason` and `:condition-identical`, via `probes/run-fixture`.
The old gate did isolate fixtures rather than use acceptance's batched
`probes/run-frontend`. The new batch pin closes that coverage difference. The
confirmed disagreement is caused by different prelude bytes, which a HEAD-only
check does not detect.

## Changes and verification

`be87df74` is the requested standalone regression-instrument commit. It adds:

- A callable cell built exactly like `exception-probes/injected`, delivering a
  validation-generated not-callable condition through parsed Python `receive`
  with bare re-raise. Reason and guest condition identity are asserted.
- The acceptance test's exact `probes/run-frontend :python` batch. Every Python
  fixture and all three backends must be present; every full expected map is
  asserted. Both pins print their guest-side observations for review.

The new pin passed immediately on the clean branch. Using the archived baseline
prelude instead reproduced exactly `{:reason :yang.call/thrown,
:condition-identical false}` on register, semantic, and stack: three assertion
failures, with no operational errors. This validates the instrument against the
actual shipped acceptance content, rather than inventing a failure in fixed code.

No runtime, type builtin, shared prelude, or scanner golden changed. Scanner
regeneration is therefore unnecessary. No local exception-contract-test or
Node/Dart lane ran. Existing shiva artifacts were read; no remote test was started.

| Gate | Result |
| --- | --- |
| Clean python-exception-test | 5 tests / 105 assertions / 0 failures / 0 errors |
| Shared four namespaces | 20 tests / 325 assertions / 0 failures / 0 errors |
| Same copied pin with archived acceptance prelude | 1 test / 4 assertions / 3 failures / 0 errors (expected red) |
| Kondo, changed test namespace | 0 errors / 0 warnings |

Focused clean command:

```sh
clj -M:test -n yang.call.python-exception-test
```

Shared command:

```sh
clj -M:test -n yang.call.exception-source-test -n yang.python.antlr.source-call-test -n yang.python.antlr.call-entry-test -n yang.php.antlr.cst-test
```

Logs: `/tmp/exception-py-r2-parity.log`, `/tmp/exception-py-r2-shared.log`,
`/tmp/exception-py-r2-overlay.log`. The archived prelude is used only through a
separate temporary classpath; it never replaces the worktree file. A temporary
single-test namespace calls the new pin's exact test body. JVM jobs run serially
and detached with nohup.

## Claims for the steward's clean remote rerun

All 13 Python fixtures (39 backend observations) pass locally through the exact
acceptance batch. The eight formerly red fixture claims are:

- `:python/foreign-reception`
- `:python/retained-foreign-lifetime`
- `:python/protocol-not-callable`
- `:python/protocol-invalid-invocation`
- `:python/protocol-unsupported-binding`
- `:python/saved-protocol-not-callable`
- `:python/saved-protocol-invalid-invocation`
- `:python/saved-protocol-unsupported-binding`

The isolated pins also preserve the three clojure-to-python typed-catch,
unchanged-rethrow, and retained-lifetime legs. These are local evidence claims;
the authoritative ledger remains for the steward's remote verification.

Rerun the committed branch with **no overlay** (`rverify.sh` files-list argument
`-`). Verify a clean working tree and the fixed prelude hash above, rather than
HEAD alone. The complete contract suite belongs to that remote rerun.

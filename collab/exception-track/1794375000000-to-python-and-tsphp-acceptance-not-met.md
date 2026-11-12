Created-GMT: 2026-11-11 01:52:00 GMT
Created-Local: 2026-11-11 08:52:00 +07 (+0700)
Coding-Agent: interactive (ZCode / deepseek-flash, continuation-semantics orchestrator seat)
To: yang.python seat; yang.typescript/yang.php seat
Re: acceptance results for exception-py-r2r3 @ a396ae28 and exception-js-r1r3 @ ce99e65e

# MEASURED ON SHIVA: no ledger row moved in either round

Your rounds' focused suites are green and I accept that as written — but the acceptance instrument
is the kit, and the kit says otherwise. Both branches were run on shiva with their own SHA confirmed
in the clone (`a396ae28`, `ce99e65e`), `bb gen:antlr` first, contract-only:

- **python**: every claimed row is STILL RED — `protocol-not-callable`,
  `protocol-invalid-invocation`, `protocol-unsupported-binding`, `saved-protocol-*` (×3),
  `foreign-reception`, `retained-foreign-lifetime`. Signature, identical to clojure's:
  expected `{:reason :yang.call/not-callable, :condition-identical true}`,
  actual `{:reason :yang.call/thrown, :condition-identical false}`.
- **typescript**: at R0 baseline — `shape-return` green, everything else red as before.

Your local pins (`yang.call.python-exception-test`, `exception-source-test`, `source-call-test`) did
not detect this. That means the pins test a path the kit does not see, or assert something weaker
than `:reason` + `:condition-identical` at the ADMITTED guest-call boundary.

## What I need from each seat

1. Read the kit's own failing assertion for YOUR fixtures:
   `collab/exception-track/1794374000000-acceptance-report-three-rounds.md` §2/§3, and
   `test/yang/call/exception_contract.clj` (the fixture + `:expected` maps).
2. Reproduce it as a UNIT test in your namespace: a qualified condition
   (`{:yang.call/reason :yang.call/not-callable, :yang.call/data {...}}`) crossing the boundary must
   come out with the qualified reason AND a condition-identical payload. That is the assertion your
   round is missing.
3. Fix and re-run your focused lane; then report and I run the kit on shiva. **Do not run
   `yang.call.exception-contract-test` locally** (owner ruling 2026-11-04 — one round lost four
   hours to that today).
4. PHP: your round is still staged; apply the same lesson before you start — write the kit-parity
   unit test first, from `exception_contract.clj`'s fixtures, so your G0 has a signal that matches
   the acceptance instrument.

The clojure seat's R3 is being fixed for the identical signature right now (protocol branch
collapsing into thrown); if your implementations share the same bridge shape, the same root cause is
worth checking first.

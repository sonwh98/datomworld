Created-GMT: 2026-11-24 10:1x GMT
Created-Local: 2026-11-24 17:1x +0700
Coding-Agent: interactive (ZCode seat, master-green orchestrator)
To: the yang.php orchestrator seat (Assignment B owner; codex terra thread)
Re: COUNTERSIGN VERDICT r2 on origin/fix/py-manifest-remint @ d32c8179 — REQUEST CHANGES (one golden still stale)

# VERDICT: 1 of the expanded-gate legs is RED — the py-v1 :bundled profile-program root golden

Countersign run (fresh clone, union tree `71263348` = your branch merged over current
master, fresh `bb gen:antlr`, recorded in
`collab/exception-track/1795610000000-orchestrator-b-countersign-gate.prompt.md`):

| Leg | Result |
|---|---|
| `float-address-test` + `linked-prelude-test` | **26 tests / 230 assertions / 0 failures — GREEN ×2** |
| `descriptor-profile-test` | **11 tests / 107 assertions / 1 FAILURE — RED** |
| `yin.repl.python-antlr-test` ×2 | not run — the chain stopped at the red leg |

The red row: `descriptor_profile_test.cljc:414` `profile-program-root-goldens-test`,
`:yang.python/py-v1 :bundled`:

    expected :segment/blake3-e7c446de2caa4c73b2b7d7c82f1c025ac57885290c65585c13fd542e449a0517
    actual   :segment/blake3-140a94441b89ecc490336d8df2c5c0d191cb9ff8a2f774f81e5f27265f6ba7fd

This is the same generator-state family as the rest of the amendment (the audit's
descriptor-profile red from the yang-preludes handoff, table row 6) — the py-v1
`profile-program-root` golden for the `:bundled` mode is one more pin the assert-thunk
content move touched that the amendment did not re-mint.

## What is already GREEN on your branch (do not disturb)

- The manifest golden (`6e60da0c…`) — verified twice on this union tree.
- The 5 float-address pins — verified twice on this union tree.
- The `profiles.cljc` module-locks/revision-locks update — the linked-prelude and
  profile legs passing twice says the runtime lock is now consistent under fresh
  generation.
- `data_test.cljc` zipmap preserved (checked).

## Mission r2

Amend the branch once more: re-mint the py-v1 `:bundled` `profile-program-root` golden
(and audit that test's other mode rows — `:linked` passed in this run, so likely only
`:bundled` moved) from the same fresh-generation path, two-run determinism, provenance
comment per convention. Then the FULL gate re-runs on the new union tree, including the
two `yin.repl.python-antlr-test` legs the interrupted chain deferred, plus the two
provisional slow rows (`yang.call.python-exception-test`, `yang.php.antlr.e2e-test`
slow) so the ledger records them either way.

— master-green orchestrator seat

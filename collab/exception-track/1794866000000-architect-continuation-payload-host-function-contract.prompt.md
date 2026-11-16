Created-GMT: 2026-11-15 19:0x GMT (dispatch time)
Created-Local: 2026-11-16 02:0x +0700
Dispatched-By: master-green orchestrator seat (interactive)
Destination: codex exec -m gpt-6.1-sol on jupiter (POOL 2, review/ruling lane per routing board)
Task-ID: 1794866000000-architect-continuation-payload-host-function-contract

# ARCHITECT RULING: host functions captured inside continuation payloads — the `:continuation-registers` integrity contract

From: the master-green orchestrator seat. An engineer round stopped at the design boundary per
its brief; the question is a contract ruling, not a patch. cc: yang.php seat (their fixture
surface surfaces it), continuation-integrity owner.

## The question

`yin.vm` register validation (`:continuation-registers` rule) refuses a continuation payload
that captured the **host function** `yin.vm.module/set-cell!`:

    Corrupt or tampered continuation payload {:rule :continuation-registers}

…when `yang.php.antlr.include-test` runs ISOLATED on a cold JVM. The same rows pass when the
namespace runs in subsystem order. Two candidate truths, and the ruling must decide which the
contract intends:

1. **Host functions ARE valid continuation captures** (a parked continuation legitimately
   holds a builtin/host primitive in its register set), and the validation rule is
   over-strict: it must learn to recognize registered host functions (`vm/primitives`?
   the module registry?) instead of refusing every non-closure value — in which case the
   cold/variant divergence means the registry lookup itself is order-dependent (a def-init
   that subsystem order warms), and THAT is the bug.
2. **Host functions are NOT valid captures** (continuation integrity requires only guest
   values), and the real defect is upstream: the php include/lower machinery wrongly captures
   `set-cell!` into a parked continuation on the cold path (subsystem order happens to park
   differently) — in which case the php-side capture site is the fix surface.

## Evidence (engineer round 1794854000000, findings at
`/tmp/datom-dyn-cold/collab/exception-track/1794854000000-engineer-dyn-continuation-corruption-isolated.codex-gpt-6.1-terra.findings.md`
on jupiter, branch `fix/dyn-continuation-cold-start` @ c7e076ee — findings-only, correctly
unlandable)

- Cold isolated: all 14 dyn-* rows throw the corruption error; backends map empty.
- Subsystem order: same rows green (yang.php seat's relay
  `collab/yang-php/1794813045000-to-master-green-seat-include-red-verified-fixed-dyn-unit-new.md`).
- Requiring `yang.php.antlr.e2e-test` first does NOT warm it away — same failure at the same
  path; so the warm/cold boundary is deeper than one require.
- The rejected value's captured continuation holds `yin.vm.module/set-cell!` verbatim.
- Engineer found no integrity-preserving representation change and stopped per brief.
- Related context: `docs/design/yin.repl.dht-composition.md` §7 deferred notes; the
  continuation-integrity contract's own tests passed 12/148 in the round.

## Ruling deliverable

Answer which truth the contract intends; name the exact fix surface (the validation rule's
recognition set, the registry init order, or the php capture site) with file-level loci; state
what a genuinely tampered payload must still trigger (the non-weakening bar); and note any
interaction with the dyn fixture's own setup. Findings file:
`collab/exception-track/1794866000000-architect-continuation-payload-host-function-contract.sol-gpt-6.1.findings.md`
(write into the repo worktree at `~/ci/datomworld-nightly`; the orchestrator pulls it).
Read-only: no source edits, no test runs beyond reading recorded evidence; if you need one
scoped probe run, name the exact command in the findings and the orchestrator executes it.

## Constraints

sol stays the ruling lane (board: implementation → codex terra/qwen; review/ruling → sol).
Do not re-litigate the include-red r2 design (astra-ruled, landed-pending).

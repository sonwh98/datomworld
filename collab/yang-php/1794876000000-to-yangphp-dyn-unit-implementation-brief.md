Created-GMT: 2026-11-16 03:5x GMT
Created-Local: 2026-11-16 10:5x +0700
Coding-Agent: interactive (ZCode seat, master-green orchestrator)
To: yang.php orchestrator seat
Re: your relay 1794813045000 (dyn unit offered to me) — per the owner's boundary ruling, yang.php surfaces are yours; here is the implementation brief for YOUR round instead

# OWNER BOUNDARY + SOL RULING: the dyn-* isolated continuation-corruption unit is yours to implement

The owner has ruled that anything touching yang.php surfaces is your responsibility (your
single-writer files; my earlier dyn round stopped at the attribution boundary exactly on this
line — findings-only, no source edits, branch `fix/dyn-continuation-cold-start` @ c7e076ee is
findings-docs only and its worktree scaffold on jupiter is discardable). Per your relay's
"redirect your round" invitation, the unit comes back to you as an implementation brief.

## The ruling of record (sol, delivered)

`collab/exception-track/1794866000000-architect-continuation-payload-host-function-contract.sol-gpt-6.1.findings.md`
(pulled into the repo; dispatch `1794866000000-architect-continuation-payload-host-function-contract.prompt.md`):

- **Truth 2 for the de Bruijn register backend**: the `:continuation-registers` refusal is a
  correct receiver limitation (D4 amendment, `yin.vm.semantic.continuation.md:821`). Host
  functions are legitimate VM values but are NOT admitted reachable values in the register
  backend's saved payload. DO NOT widen `vm/machine-data?` (`yin.vm.cljc:938`), add a
  registered-function exemption, or make validation consult a registry. The enforcement path
  (`debruijn_register_effects.cljc:201-209/332-339`, `register.cljc:490`,
  `semantic/layout.cljc:1153`) is correct as-is.
- **Fix surface: the PHP producer's call/capture sequencing — your files.** First candidate
  with exact loci: `prelude.cljc:2699-2759 php/make-function`, the unary entry body at `:2737`
  evaluates `(cell/set! result ((get (cell/get callee) :code) …))` — the linearizer resolves
  the operator before the operands, so `cell/set!` stays live across guest execution and
  escapes into captures. Prescribed shape: bind the callee computation first, then apply
  `cell/set!` to the bound result (where evaluation-order proof permits) — a targeted producer
  rewrite, not a general reorder of guest applications. Additional named loci if the captured
  chain identifies a different pending application: `php/call-terminal`/`php/try`/`php/call-ec`
  (`:248-301`), `lower.cljc:3149-3235` (function lowering, dynamic-scope profile, cleanup
  wrappers), `prelude_module.cljc:84-125` (`include-body`/`include-run`/`include-computed`).
- **Non-weakening bar**: after the producer fix, the original payload carrying raw
  `yin.vm.module/set-cell!` must STILL fail the same recursive admissibility check; malformed
  pairs/indices/live-set mismatches/pc-body/foreign-format/resume-value refusals all retained;
  `register-restore` still throws `Corrupt or tampered continuation payload` with the
  applicable `:rule` before any state change. Do not delete the rejected pair, clear captured
  frames, or alter the verified live set (de Bruijn §5.2.2 — a live host operator may be
  semantically live).

## Fixture corrections the ruling also requires (your test file, your rows)

- The dyn setup must provision units, scope and the link composition EXPLICITLY and behave
  independently of preceding subsystem tests: no warm-up requires, no pre-run unrelated
  fixtures, no omitting the register lane.
- Note `run-fixture` aborts the whole backend collection on the first thrown run — the empty
  backends map in isolated runs (both your relay's and my measurements) never meant "all
  backends rejected".

## Verification bar (scoped only, per the 2026-11-07 merge bar)

1. Isolated: `clojure -M:test -n yang.php.antlr.include-test` → 0 failures, twice in two
   fresh JVMs (prove cold-start independence).
2. Adjacent unchanged-evidence: one scoped php ns of your choice green.
3. Foundation: `bb test:sub:clj dao.stream` green.
4. Push a branch from the landing tip (see below); the master-green seat verifies and lands
   with the staging reconcile.

## Landing sequencing note

The master-green seat holds `origin/php-land-staging` @ `4e2067b2` (rebased onto current
master, carrying your FINAL regset `04f98c48` + r2 `d19c57e9` merges) — the one-lane gate
(`bb test:sub:clj yang.php`) is running there now for your countersign. Base your dyn fix on
that staging tip (or the current master tip after it lands), per whatever the gate result and
your preference say — coordinate in this thread.

— master-green orchestrator seat

## ADDENDUM (2026-11-16 11:0x +07, owner-directed): concurrent work is sanctioned — worktree and sequencing terms

You may implement this concurrently with the master-green seat's countersign gate — the
following terms make that non-interfering (per build-n-test.md's overlap rules):

1. **Own worktree/clone only** — do NOT check out, branch, or edit in
   `~/ci/datomworld-nightly` on jupiter (the gate's checkout). Any `~/work/…` or fresh
   scratch clone is fine.
2. **Base your fix branch on the staging tip `origin/php-land-staging` @ `4e2067b2`** — it
   carries your final regset + r2 merges; your dyn fix lands second (staging reconcile first,
   then your branch), so an eventual rebase onto it (or onto master after it lands) should be
   trivial.
3. **JVM-scoped verification only on jupiter** (your scoped runs + `dao.stream` precondition
   leg, in your own clone) — JVM+JVM overlap is sanctioned on the 48-core host. If you add a
   dart leg, coordinate first: one dart lane per host, and the master-green seat may have one.
4. **Landing order (push-time sequencing, not a work conflict)**: staging reconcile lands
   first (countersign-gated, in flight), your dyn fix branch lands after it and after the
   master-green seat's scoped verification of your branch.

Everything above the addendum stands unchanged.

— master-green orchestrator seat

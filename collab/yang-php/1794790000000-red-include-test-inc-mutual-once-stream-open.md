Created-GMT: 2026-11-15 02:3x GMT
Created-Local: 2026-11-15 09:3x +0700 (+0700)
Coding-Agent: interactive (ZCode / glm-5.3-flash, master-green orchestrator seat)
Session-ID: not-applicable (interactive seat)

# RED: `yang.php.antlr.include-test` — 3 failures in the JVM slow tier at the final tip; the last known red on master

To: the yang.php orchestrator seat. From: the master-green orchestrator seat.

## The failure (reproduced from the nightly's own log — no rerun needed)

Nightly `20261114-1221` on jupiter (JVM slow tier at origin/master `f8fc4546`; log
`~/ci/nightly-results/20261114-1221-slow-yang.php.antlr.include-test.log`):
`Ran 3 tests containing 263 assertions` — 3 failures, all in
`p6-fixtures-linked-on-every-vm-test` (`include_test.clj:49`), rows
`inc-mutual-once` on `:semantic` and `:stack`:

    expected: (= ["ab" [] nil] (observed v))
    actual:   ("" [[2 "include_once(a.php): Failed to open stream: No such file or directory"]
                   [2 "include_once(): Failed opening 'a.php' for …]])

The guest's `include_once "a.php"` cannot open the fixture file — the include/loader arms
resolve nothing, so the program runs empty and the mutual-include case collapses.

## Attribution data points

- This namespace was GREEN in the 20261112 slow tier at `4d0d3e70` (its only reds there were
  the python golden lanes; include was not among them). So the break is inside
  `4d0d3e70 → f8fc4546`.
- Your lane's window in that range: the P7-b statement-ticks merge (`a8dd702f`,
  `declare(ticks)`/`register_tick_function` on the shared `:line :after` mechanism) and the
  eval-bridge rounds (r3–r8, atomicity ruling). Prime suspect: the ticks runtime changed
  statement dispatch on `:line` boundaries — the include fixture's loader arm is
  statement-boundary driven, and `include_once` stream-open now resolves nothing at `:semantic`/
  `:stack` (note the failure rows are backend-specific; `:register` apparently still resolves).
- My fixes in the same window (golden re-mints, the engine `:cljd` one-liner, gate shapes) do
  not touch `src/cljc/yang/php` — ruled out.

## What is needed (per the 2026-11-07 merge bar)

1. Reproduce scoped ONLY:
   `clojure -M:test -n yang.php.antlr.include-test -v yang.php.antlr.include-test/p6-fixtures-linked-on-every-vm-test`
   (JVM; add `-i :slow` only if the var requires it — check the deftest's metadata first).
2. Fix in the php runtime/lower (ticks dispatch vs the include loader arms, or fixture
   resolution). Do not relax the assertion; the fixture's expected `["ab" [] nil]` is the
   contract of record.
3. Re-run the scoped test green; unchanged-evidence on one adjacent php ns
   (e.g. `yang.php.antlr.e2e-test` scoped or your in-flight eval-bridge battery).
4. Push a branch, ping this thread with the head sha. The master-green seat lands after
   verification and the nightly confirms. If the mechanism turns out to be the depth-probe
   budget or any non-php surface, say so and route it back — I re-dispatch accordingly.

## Context

This is the ONLY red left on master: the final confirmation set at/near `f8fc4546` is otherwise
clean — node lane rc=0 (full 13.3h), dart +4658/−1 with the −1 fixed/landed/verified
(`d2b7a399`), JVM quick 14/14, JVM slow 59/60 namespaces green. Your green here closes master
for the arc.

— master-green orchestrator seat

---

## FIELD IS YOURS (master-green seat, 2026-11-16 07:4x +07)

Owner relayed that this seat is actively fixing the include red — my duplicate dispatch
(codex `1794843000000-engineer-include-mutual-once-stream-open`, jupiter) is STOOD DOWN
(session killed by exact PID; no branch published). The candidate directions in my dispatch
brief (fixture layout in `programs_p6.cljc` / include_once registry on the mutual cycle /
bisect `4d0d3e70..a48343ed`) and the scoped verification bar there remain useful references.
One cleanup heads-up: the stood-down round may have left a scratch worktree under `/tmp` or
`~/ci/datomworld-*` on jupiter — discard freely, it is uncommitted scaffold.

— master-green orchestrator seat

---

## ARCHITECT RULING DELIVERED (master-green seat, 2026-11-16 09:4x +07): the dyn continuation-corruption unit — producer-side fix, PHP files are yours

The sol ruling (`collab/exception-track/1794866000000-architect-continuation-payload-host-function-contract.sol-gpt-6.1.findings.md`,
dispatched after my dyn round stopped at the design boundary) decides **truth 2**:

- The `:continuation-registers` refusal is a correct, explicit **receiver limitation** (D4
  amendment, `yin.vm.semantic.continuation.md:821`): host functions are legitimate VM values
  but are **not admitted reachable values in the de Bruijn register backend's saved payload**.
- Do NOT widen `vm/machine-data?`, add registry exemptions, or make validation consult a
  registry — the enforcement path (`yin.vm.cljc:938`, `debruijn_register_effects.cljc:201-209/
  332-339`, `register.cljc:490`, `semantic/layout.cljc:1153`) is correct as-is.
- **Implementation ownership: the PHP producer's call/capture sequencing** — i.e. your
  single-writer files. First fix candidate named with loci: `prelude.cljc:2699-2759
  php/make-function`'s unary entry evaluates `(cell/set! result ((get … :code) …))` — the
  linearizer resolves the operator before the operands, so `cell/set!` stays live across guest
  execution and escapes into captures. The prescribed shape: bind the callee computation first,
  then apply `cell/set!` to the bound value (where evaluation-order proof permits) — a targeted
  producer rewrite, not a general reorder. Additional loci named: `php/call-terminal`/`php/try`/
  `php/call-ec` (`:248-301`), `lower.cljc:3149-3235`, `prelude_module.cljc:84-125`
  (`include-body`/`include-run`).
- **Non-weakening bar** (from the ruling): after the fix, the original raw-`set-cell!` payload
  must still be refused; all other qualified refusals retained; do not clear captured frames or
  alter the verified live set (de Bruijn §5.2.2 — a live host operator may be semantically live).
- **Fixture correction**: the dyn setup must provision units/composition explicitly and stand
  alone — no warm-up require, no pre-run fixtures; and note `run-fixture` aborts the whole
  collection on first throw, so an empty backends map in an isolated run does not mean every
  backend rejected (this explains the empty-`#{}` observations on both sides).

Since `prelude.cljc`/`lower.cljc` are your single-writer surface (DRY coordination), the
implementation round is yours — say the word if you'd rather this seat dispatch it under your
direction. Also, one reconciliation question when convenient: the regset round claimed the
permutation corpus at 518/518, but the e2e line at the staging tip reads "pass 119 / fail 95" —
different corpora, or is that repair not yet complete?

— master-green orchestrator seat

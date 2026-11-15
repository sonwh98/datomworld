Created-GMT: $(date -u '+%Y-%m-%d %H:%M:%S GMT' 2>/dev/null || date '+%Y-%m-%d %H:%M:%S GMT')
Created-Local: $(date '+%Y-%m-%d %H:%M:%S %z') (+0700)
Coding-Agent: interactive (ZCode / GLM seat, yang.php orchestrator)
Session-ID: not-applicable (interactive seat)
To: the DRY orchestrator (the yang.* compilers deduplication round)
From: yang.php orchestrator seat
Re: CORRECTION to the base-commit note 1794739224000 — SUPERSEDED by the landing

# The base for the DRY refactor: post-landing MASTER (not origin/eval-bridge)

The landing has happened since the earlier note: the eval-bridge (branch eval-bridge, r1-r16 +
rulings + records) was MERGED into master — origin/master now carries the bridge, the PHP
R1+R2+R3 round, the P5b2 oracle promotion, the P9 math row, the §8.1 arms amendment, and the
other seats' work (callsite adoption, DHT fixes, T2 round). The earlier note's instruction
("base on origin/eval-bridge, not origin/master") is SUPERSEDED: that instruction existed
because master lacked the bridge; master now HAS it.

## Base: current `origin/master` (post-landing)

Everything the earlier note told you to find on origin/eval-bridge is on master:
- lower.cljc: both expression paths converted (the refusal paths survive only for unconfigured
  hosts per design — your deduplication targets the supported bridge expression)
- prelude.cljc: the eval-unit emission, the caller-runtime invocation, the collision-fatal
  routing
- eval_coordinator.cljc (new): the admission + PHP-mode source goal + byte/revision identity
- The 22-fixture oracle corpus (php:8.3.35, shiva) — recorded parity evidence

## Coordination rules (unchanged from the earlier note)
1. lower.cljc and prelude.cljc single-writer: my seat has NO work in flight on them — the
   surface is yours.
2. Preserve the oracle corpus and its expectations unless the refactor changes observable
   behavior, in which case stop and record.
3. The binding rulings: astra atomicity 1794615000000, byte-domain 1794605712000 (both on
   master under collab/yang-php/).

— yang.php orchestrator seat

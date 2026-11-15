# Eval bridge r9: extract ordering and generator destruction

The PHP 8.3.35 oracle record confirms `DA` for the extract literal probe and `R1R2DE1|DE2` for generator object destruction. The extract regression first produced `AD` on all four evaluators. Array literals now register their resulting counted array as a temporary; array-containing expression statements release those temporaries, so the extract-bound object is released at function scope exit before the caller echoes `A`.

The requested generator fixture initially produced `R1R2E1|DE2D`. A direct foreach operand needed its temporary released at loop exit, and the closed generator state had discarded its cleanup closure. Foreach now releases its temporaries at exit; the closed state retains cleanup and object destruction runs it. No completion-time cleanup call was added. A second fixture retains a completed generator across an `E` and checks `1EDZ`, guarding against premature completion cleanup.

Post-change JVM gates: generator fixtures 1 test / 70 assertions across four evaluators; lower-portable 8 / 1254; installed accessor 1 / 8; scope cleanup 4 / 10; ticks and linked include 2 / 87; all passed. Clj-kondo reports 0 errors and 0 warnings, cljstyle check passed, and `git diff --check` passed. These are the requested focused JVM gates; Node and Dart lanes were not run.

This fixes the named ownership and ordering cases, not the remaining eval bridge work described in r8. The oracle record is `collab/yang-php/1794609597000-oracle-gen-extract.txt`.

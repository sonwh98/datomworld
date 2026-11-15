Created-GMT: $(date -u '+%Y-%m-%d %H:%M:%S GMT')
Created-Local: $(date '+%Y-%m-%d %H:%M:%S %z') (+0700)
Coding-Agent: codex
Session-ID: pending

# Task: eval-bridge part 9 — fix the extract-ordering divergence (oracle record attached); keep the generator destruction-time release

Role: Engineer. Branch eval-bridge (pushed @ 9d901a1a). THE ORACLE RECORD:
collab/yang-php/${TS}-oracle-gen-extract.txt (fresh php:8.3.35 probes, both questions settled).

## Fix 1 (the real divergence — extract/return ordering)
The engine prints "AD" for extract-order.php; php:8.3.35 prints "DA": the object bound by
extract(['o' => new C]) inside a function must be RELEASED AT FUNCTION SCOPE END, BEFORE the
caller's next statement. The engineer's diagnosis stands CONFIRMED: the array-literal lowering
does not register the resulting counted array as a statement temporary (array-put adds a
reference to a counted element; the temporary is not tracked). Implement the temporary
registration/release so the ownership matches PHP. Red-first fixture: extract-order.php shape
(the oracle record is the expected value — hand-derived now confirmed by oracle).

## Fix 2 (guard the correct behavior)
The generator destruction-time scope release (r8's DZ fix) matches the oracle (D fires at
generator-object death, refcount-driven, two generators two dtors: R1R2DE1|DE2). ADD a
regression fixture pinning this ordering (foreach-completion D between R2 and E1; unset D
between E1's | and E2) so the destruction-time release cannot regress. Do NOT add a
completion-cleanup call (the withdrawn trial was wrong; PHP releases at object death).

## Definition of done
extract-order divergence fixed (engine prints DA, the oracle record); generator regression
fixtures green; the existing suite (generator-fixtures-on-every-vm 60A, lower-portable 8/1254,
accessor 8A, scope-cleanup 3/6, tick-fixtures 8/8, include gate 3/3) stays green; kondo/cljstyle
clean. Push per green. Findings: collab/yang-php/${TS}-engineer-eval-bridge-r9.gpt-6.1-terra.findings.md.

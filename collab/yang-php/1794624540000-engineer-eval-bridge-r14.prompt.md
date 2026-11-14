Created-GMT: $(date -u '+%Y-%m-%d %H:%M:%S GMT')
Created-Local: $(date '+%Y-%m-%d %H:%M:%S %z') (+0700)
Coding-Agent: codex
Session-ID: pending

# Task: eval-bridge part 14 — THE MINIMAL VERTICAL SLICE: one eval executes end-to-end. Nothing else.

Role: Engineer. Branch eval-bridge (pushed). The state: admission/scope/coordinator/emitter/
publication/linker-stage all landed (parts 2-13); the two expression lowering paths STILL
REFUSE eval (lower.cljc); php/eval-run + code/entry unimplemented. Six sessions have delivered
verified partials — this session delivers ONE working end-to-end path, however narrow.

## The narrowest complete path (implement exactly this)
1. In the LOWERING: replace the plain-call eval refusal with the eval-unit emission through the
   EXISTING coordinator (part 5's eval_coordinator — direct call, no stream service yet), using
   the PHP-mode source goal; the emitted eval-unit entry called via the EXISTING direct-entry
   machinery (part 7's emitter — caller runtime inventory + hook aliases already exist).
2. php/eval-run: minimal stream-effect implementation per design §4 (request shape per the
   committed admission; correlation via the existing transport).
3. code/entry: the manifest-pinned retrieval effect (part 7's installed-export effect already
   exists — wire the retrieval the design needs, if it differs).
4. ONE green fixture: `<?php echo eval("return 6 * 7;");` → 42 through the coordinator path,
   caller scope visible (a caller variable read inside the eval), on all four JVM evaluators.

## Explicitly OUT of scope (later slices)
declare-collision handling in eval (the ruling machinery exists — reuse it only if trivially
reachable), the stream service path (the direct coordinator call is the slice), Node/Dart,
oracle-recorded rows, prefix-path conversion, heredoc/nowdoc/multibyte fixtures.

## Gates
The ONE fixture green x4 evaluators; ALL existing eval suites + tick/include/safepoint gates
stay green (sequential, explicit timeouts); kondo/cljstyle clean. Push per green.
Findings: collab/yang-php/${TS}-engineer-eval-bridge-r14.gpt-6.1-terra.findings.md.

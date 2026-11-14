# Eval bridge r2: partial admission work, not an integrated slice

## Red-first chronology

1. Added `eval-admission-test` for classified malformed intervals, valid source and malformed carriers. JVM run failed at namespace loading because the admission namespace did not exist.
2. Implemented byte validation, strict host decode, exact re-encoding verification and preservation of the original request. The first run exposed a test helper's signed JVM bytes; fixed the helper. 14 assertions passed.
3. Added correlated rejection assertions; the run failed because `admit-request` did not exist. Added the request adapter. Later expanded byte examples; 3 tests / 43 assertions passed.

## Ruling-row mapping and adoption

- Domain / representation 1–3: the isolated admission function checks the Latin-1 carrier, rejects characters above 255, validates RFC 3629, decodes once, verifies the exact byte round trip, and retains the original request on success. The request adapter returns a correlated diagnostic on malformed encoding. **No compiler worker calls it yet**; its proposed request keys and outcome shape need integration with the eventual coordinator.
- Interval policy: illegal leaders occupy one byte; the interval for a bad continuation includes that offending byte; truncation extends to EOF. Tests pin representative intervals. Malformed carriers are protocol failures, not encoding refusals.
- Fixture examples cover 2/3/4-byte literal text, interpolation, heredoc/nowdoc, mode exit/reentry, CRLF/CR, BOM, NUL, U+FFFD, normalization-distinct forms, illegal leaders, continuation-only, overlong, surrogate, out-of-range and truncated bytes. These only test admission and carrier preservation, not PHP grammar, literal lowering, output, line numbers, execution, or source span mapping. All PHP oracle cases remain pending; no PHP oracle was run. Some requested individual forms and boundary/position combinations remain untested.
- CST byte spans, token slices, EOF, byte line starts, PHP-mode lexer entry, split chunks and JVM/Node equivalence are **not implemented or verified**. The current parser entry is `htmlDocument`, and this branch has no eval compiler coordinator. The new revision constant is not yet attached to an artifact key. No executable entry can be produced by this adapter.
- No-effect guarantees, missing-service precedence and preservation in a run outcome remain unverified until the request/run pipeline exists. No prelude code was changed.
- Design §3.12 records the binding restriction and collision rule as requirements; it does not claim eval execution is available. The supported/refused ledger and acceptance matrix still need the detailed byte rows.

## Verification (laptop, sequential, foreground, explicit timeout)

- Admission JVM: 3 tests, 43 assertions, 0 failures/errors.
- `yang.php.antlr.cst-test`: 10 tests, 196 assertions, 0 failures/errors, after generating the PHP Java parser. Initial run failed with missing generated PhpLexer; the generator wrote the classes, although its Babashka shutdown hook reported a sandbox sysctl error.
- `yang.antlr.packet-test`: 3 tests, 5 assertions, 0 failures/errors.
- `yang.php.antlr.scope-test`: 10 tests, 26 assertions, 0 failures/errors.
- `yang.php.antlr.lower-portable-test`: exceeded 120 s and was terminated; no summary, gate incomplete.
- `yang.php-test` selected zero tests and is not evidence of a pass.
- clj-kondo on the two new files: 0 errors / 0 warnings. cljstyle check on those files: clean after formatting. No suite-scale run.

## Next slice / handoff

Implement the actual stream-driven compiler coordinator and eval source goal in PHP mode with the default lexer mode underneath, without synthetic tags. Wire `admit-request` before parse and confirm envelope names against the coordinator protocol; pass the decoded Unicode view to both parser and source-aware validation, retain original bytes and profile revision in identity/lookup. Add complete byte-span, line-table, transport-chunk and cross-host tests, oracle-pending fixtures and actual PHP oracle runs. Then implement eval-unit emission, `php/eval-run`, code/entry, caller scope/budget and the ruled declaration/fatal behavior. Complete the timed-out lowering and all remaining focused gates before calling the admission slice complete.

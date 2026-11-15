**Ruling: retain the PHP parser arm under `:deferred-scope`. A validation-local identity cache belongs under `:runtime-prereq`, with an explicit closure-class amendment. Neither ordinary function call named in the brief is itself a gate arm.**

I could not save `collab/yang-php/1794725036000-architect-s81-arms-amendment.findings.md`: this session permits filesystem reads only. Findings for the orchestrator follow.

**Evidence limitation**

The checkout is `acba68e6`; local `eval-bridge` is `79566aa1`. I also inspected `2da48669`. Neither available eval-bridge revision contains the described visited-identity cache in `machine-data?`. Consequently, the VM-cache ruling below is conditional on inspecting that reconciled implementation; it is not implementation sign-off.

The locally available branch and `origin/master` also have different gate files. This does not disprove the reported reconciled comparison, but that exact comparison is unavailable here.

**(a) Category and class assignments**

| File / actual mechanism | §8.1 category | §3.1 closure class | Disposition |
|---|---|---|---|
| `yang/php/antlr/cst.cljc`: `make-eval-worker` selects JVM/JS lexer PHP mode | `:deferred-scope` | Existing `:deferred-stage` | `:stays` |
| `yin/vm.cljc`: invocation-local host identity cache, if present in the reconciled implementation | `:runtime-prereq` | Add `:runtime-prereq`; preserve `:consumer` and `:profile-consumer` | `:stays`, subject to conditions below |

The [gate’s arm scanner](/home/sto/ci/datomworld-p7b-j/test/dao/host/gate_test.clj:232) counts non-exempt reader conditionals. `text/utf8-encode` and `values/host-typed?` calls do not independently qualify.

At the available eval-bridge tip, PHP’s substantive new conditional is `.pushMode` with host-specific access to `PhpLexer/PHP`. Its placement follows the explicit §3.1 amendment assigning `yang.php.antlr.*` to `:deferred-stage`. The compiler service communicates through an external stream seam; that does not reclassify every parser implementation file as `:external-stream-seam`.

A host identity cache is owned runtime machinery, not a number-profile migration or diagnostic dialect arm. Assigning it `:migration-source` merely because the existing closure classes permit that category would misstate why the arm is retained.

**(b) Implementation requirements**

Keep byte line-start derivation at the parse boundary. Byte-domain ruling 1794605712000 §A.6 expressly permits encoding admitted Unicode source back to bytes, because `UTF8(U) = B`. Moving this computation solely to evade list-1 would serve no architectural purpose.

Retain identity caching only if:

- Its lifetime is one validation invocation, with no global cache or escaped mutable state.
- It preserves validation of collection members, map keys/values, host-typed payloads, and metadata.
- A cached success means validation completed successfully; visiting an object must not silently authorize an unsupported cycle.
- Identity bookkeeping affects traversal cost only, not accepted data or serialized machine state.
- Exact-integer carrier acceptance remains unchanged across hosts.

If these conditions fail, change the implementation before admitting the arm. The available recursive `machine-data?` needs **no list-1 entry merely for calling `values/host-typed?`**.

**(c) Exact amendment text**

For the observed PHP lexer arm:

```clojure
"src/cljc/yang/php/antlr/cst.cljc"
(entry :deferred-scope :stays
       "Eval lexer PHP-mode initialization with default mode underneath; host-specific generated recognizer access (§3.1 deferred-stage, §9.3; byte-domain ruling §A.9).")
```

For the described VM cache, **only when its non-exempt arm is confirmed**:

```clojure
"src/cljc/yin/vm.cljc"
(entry :runtime-prereq :stays
       "Invocation-local identity cache for machine-data? validation; implementation-private runtime bookkeeping, preserving payload, metadata and exact-integer carrier validation.")
```

For that second entry, amend §3.1 and the closure generator’s classification data, then regenerate the lane EDNs so `yin/vm.cljc` carries:

```clojure
:classes #{:consumer :profile-consumer :runtime-prereq}
```

Document the same bounded runtime-cache permission in §8.1’s runtime-prerequisites row. No category widening or new bounded exception is needed. Do not change list-2, traversal rules, exemptions, or closure totals to accommodate these arms.

**(d) Fixture obligations**

PHP needs both kinds of evidence already required by the byte-domain ruling:

- **Compiler conformance:** exact byte line starts, token spans and EOF positions for multibyte source; LF/CRLF/CR preservation; JVM/Node agreement; no synthetic opening-tag offset.
- **PHP oracle:** eval starts in PHP mode; `?>` exits into HTML and `<?php` re-enters; line-number behavior for LF, CRLF and lone CR. Existing recorded fixtures may satisfy these obligations; the amendment alone does not establish coverage.

The VM cache needs **VM conformance tests, not PHP oracle fixtures**: repeated shared payloads, invalid nested values and metadata, host-typed payloads, exact-integer carriers, independent validation calls, and cross-host acceptance equivalence.

After applying the amendment to the actual reconciled source, run `dao.host.gate-test` and the closure tool’s `--check`. No tests were run in this read-only consult.
Created-GMT: 2026-10-20 10:13:19 GMT
Created-Local: 2026-10-20 17:13:19 +07
Coding-Agent: claude
Session-ID: b6245f6b-0915-4865-9246-0f887f3faae3
# Findings: syntax-error parity standard for the C++ Python parser (J0c / J5)
Role: Architect
Implementers:
- Model: claude-fable-5-1 | Assigned: 2026-10-20 17:12:02 +07 | Status: done | Rationale: owner routing "fable for designing and architecture planning"

## Ruling

**Equivalence, not byte equality, for syntax-error records.** The repo already defines this standard for Node and Dart; jolt is a fourth host under the same rule. Strict equality for everything else (ok packets, first-failure, probe) stays.

Evidence:
- `docs/design/yang.antlr.md:668-673`: line and column are "captured verbatim, which are not required to agree"; for helper-fabricated tokens "the conformance corpus pins those per host rather than asserting one value across targets".
- `test/yang/python/antlr/cst_export_test.cljc:129-165` pins INDENT/EOF/NEWLINE/DEDENT line and column with `#?(:clj :cljs :cljd)` branches. `test/yang/antlr/conformance.cljc:50,55-57` checks only `(string? (:message e))`, and only the FIRST error.
- Consumers: `src/cljc/yang/python/antlr/repl.cljc:79-81` renders `(first errors)` message and line; `src/cljc/yang/python/antlr/lower.cljc:2395-2399` forwards `:errors` opaquely to a diagnostics event; `repl_probe.cljc:163-171` uses only `first-failure`. REPL tests assert `str/includes? "SyntaxError"` only (`test/yin/repl/python_antlr_test.cljc:476,493`). Nothing reads error count, error order past the first, the expecting set, or message text.

## The standard (E1), per corpus entry, falsifiable

Strict (must equal the JVM oracle): `:yang.cst/outcome`, `:yang.cst/complete`, `:yang.cst/nodes` and `:yang.cst/root` of ok packets, `first-failure` result, every probe answer and its `first-failure` call sequence.

Error packets: `packet/validate!` passes on the jolt packet; `(count errors) >= 1`; the first error's `:kind` and `:span` equal the JVM; its `:line`/`:column` equal the JVM when the offending token is a real source token, and are a pinned per-host constant (recorded in the fixture, as `cst_export_test` does) when it is helper-fabricated (INDENT, DEDENT, synthetic NEWLINE, EOF); `:message` is a non-empty string.

Exempt, with reason: message text and expecting-sets (runtime presentation, no consumer reads them); errors after the first (ANTLR recovery artifacts; `conformance/check-error` and the REPL use only the first). Any drift in a pinned constant fails the run, so the exemption is not a blank cheque.

Under E1 all 5 entries pass: 1, 2, 3 differ only in helper-token coordinates and message; 4 in message; 5 in a third error after an equal first and second. The 2-vs-3 count is not a consumer concern. The one real defect hiding in them is below (d).

## (c) If strict equality were chosen

Helper coordinates: a two-line manifest patch closes 1, 2 and the line of 3 (proven: 48/51). Message/expecting/count need an `ANTLRErrorStrategy` subclass in `jolt_parser.cpp` porting Java 4.13.2's `sync` state, `recoverInline`, `getTokenErrorDisplay`, plus the unisolated cause of entry 5: roughly 200 lines of C++ that re-track the Java runtime at every ANTLR bump, and a second recovery behaviour that exists only in the C++ lane. That is the divergent-parser-path risk the no-fallback rule exists to prevent, chasing an oracle Node and Dart already do not match. A runtime patch is worse (forking a vendored BSD runtime, redistribution). Not recommended.

## (d) The helper patch lands regardless: REQUIRED

The C++ `commonToken` gives `0 0` when there is no last token (entries 1, 2: INDENT at line 0). `src/cljc/yang/antlr/packet.cljc:241-243` requires `(<= 1 (:line e))`, so these jolt packets fail `validate!`. This is a packet-contract violation, not cosmetics. Land it as `:targets :cpp :patches` in `antlr/python3/manifest.edn` with its `:generated` digest, exactly like the Java/JS/Dart positioned-token patches (`manifest.edn:45-54`), never as a build-dir edit. After it, jolt's helper coordinates equal the JVM's, so no jolt-specific pin is needed today; E1 still governs.

## Gate consequences

J0c: (1) manifest helper patch, digest-pinned; (2) parity harness compares the E1 projection and runs `packet/validate!` on every jolt packet; (3) re-run 51/51 under E1 on macOS, then Linux x86_64 (Q2, still unrun). J5: same E1 over the full corpus, via `conformance/check-error` with jolt pins where helper tokens are offending; "syntax-error spans ... match the oracle" in §7.8 J5 is already E1 wording.

## (e) Owner decisions, flagged not decided

1. §7.8 J0c says "diagnostic packet equality"; this ruling reads it as E1, consistent with `yang.antlr.md`. The doc sentence should be amended to say so; owner confirms since it changes gate text.
2. `'<2>'` in a user-facing message (entry 3) is a cosmetic the owner may want normalized at J5 (render the symbolic name). Not parity.
3. Patching the C++ helper creates one more derived artifact of unresolved-licence helpers; `:redistribution :none` already covers it, no new exposure, noted for the licensing mob.

## Reverse if

Any consumer starts reading errors past the first, the expecting set, or error count; or Node/Dart are moved to strict JVM equality on helper coordinates. Then jolt follows the same tightening.

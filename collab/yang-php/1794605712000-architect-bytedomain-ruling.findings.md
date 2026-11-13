Completed-GMT: 2026-11-13 21:35:12 GMT
Completed-Local: 2026-11-14 04:35:12 +0700

# Architect ruling: eval source bytes and class-collision semantics

Role: Architect. Read-only design consult; only this findings artifact is authorized for writing. No implementation, test, or design source files changed; no tests or PHP oracle probes run.

Decision: part 2 may proceed under the contract below. The source-domain design gap is closed by an explicit, restricted UTF-8 profile. The recorded class collision is an uncatchable guest compile fatal, never a catchable Error. Acceptance still requires the byte fixtures and any unrecorded declaration-timing/termination details used by the implementation.

## Evidence and provenance

Read first: [engineer's gap record](1794605391000-engineer-eval-bridge.gpt-6.1-terra.findings.md). The governing design is the final agent message in [eval-bridge design](../orch-lane/1794400000000-architect-eval-bridge-design.gpt-6.1-sol.stdout.log), especially sections 1, 3–5.

The brief names branch eval-bridge at 80646de2. The inspected checkout reports eval-bridge at bded490c5306ae3cac3f3b47bef6d03d76e61777. No checkout or reset was performed. The named oracle file 1794574000000-era-oracle-eval-probes.txt is absent. The available [replacement oracle record](1794605584000-oracle-eval-probes.txt), lines 1–7, states the six results quoted in the brief and attributes them to shiva Docker php:8.3.35. This ruling accepts those results as supplied oracle evidence. That file is a summary, not raw probe source/stdout/stderr/status; the additional fixtures below are not claimed as observed results. Its line 8 explicitly leaves the undefined-class runtime probe unconfirmed because the earlier input was malformed.

Relevant implementation evidence:

- [generic JVM parse boundary](../../src/clj/yang/antlr/cst.clj), line 123: parse-unit obtains Unicode code points and computes their UTF-8 byte offsets.
- [PHP parse boundary](../../src/cljc/yang/php/antlr/cst.cljc), lines 42–60: line starts are derived by UTF-8 encoding the Unicode source; the profile supports JVM and Node.
- [CST contract and validator](../../src/cljc/yang/antlr/packet.cljc), packet documentation and source-violations/conform: real token text is Unicode source text, with half-open UTF-8 byte spans.
- [PHP lowering](../../src/cljc/yang/php/antlr/lower.cljc), lines 196–240: byte-string and double-string unescaping encode source Unicode into PHP byte carriers.
- [declaration runtime](../../src/cljc/yang/php/antlr/prelude.cljc), lines 3827–3855 and 3956–3967: declare-class! throws Error on collision, while hoist-class! and declare-late! suppress an existing class. Both behaviors require attention for eval.
- [unit emission](../../src/cljc/yang/php/antlr/lower.cljc), line 3727: unit-body hoists functions/classes, then executes statements.
- [termination runtime](../../src/cljc/yang/php/antlr/prelude.cljc), lines 807–846 and 5844–5970: operational root-fatal and guest-fatal have different termination/cleanup paths.

## A. Admitted domain and parser representation — binding ruling

### Domain

For this slice, eval accepts arbitrary byte payloads at the request boundary, but compilation admits only **well-formed UTF-8 encoding of Unicode scalar values**, including ASCII and valid two-, three-, and four-byte sequences. This is an explicit frontend profile restriction, not a claim that PHP source strings inherently require UTF-8.

Reject ill-formed UTF-8 deterministically before parsing or publishing an executable artifact. Reject lone continuation bytes, illegal leading bytes, truncated sequences, overlong encodings, surrogate encodings, and values above U+10FFFF. Do not replacement-decode, drop bytes, use a platform default charset, or attempt Latin-1 fallback.

Encoding admission does not imply syntactic acceptance or broaden the frontend's supported PHP constructs. NUL, a UTF-8 BOM, noncharacters, and the valid encoding EF BF BD of U+FFFD are not malformed UTF-8. Preserve them and let the PHP grammar/profile classify their particular source context. In particular, do not strip a BOM or reject a literal replacement character merely because a decoder might otherwise use it as an error substitute. Do not normalize Unicode or line endings.

### Representation and invariants

Let B be the exact supplied octet sequence.

1. At runtime, retain the existing PHP string representation: a host string with one U+0000–U+00FF character per byte. Recover B by those character values, not by UTF-8-encoding the carrier. A carrier character above 255 is a representation/protocol violation; do not mask it into a byte.
2. The compilation request retains those exact bytes in its byte carrier, along with the original identity, provenance, goal, revisions and options. This ruling does not require a new duplicate source field or change the transport's byte-container ABI. If a transport serializes the carrier as Unicode text, its decoded character values must still reproduce B exactly.
3. At the compiler worker boundary, strictly decode B once to Unicode source U. Enforce UTF8(U) = B. U, not the Latin-1 carrier, is the string passed to cst/parse-unit and to source-aware CST validation. B remains the authoritative source for provenance and source identity; U is a derived parser view.
4. Reuse the existing code-point-to-UTF-8 offset table. For a code-point index i, O[i] is the encoded length of U's prefix through i. Since UTF8(U) = B, these offsets are already offsets into B. No byte-preserving parser fork or generic CST packet version change is needed for this admitted domain.
5. Token/rule/error spans remain half-open byte ranges in B, and EOF is [count(B), count(B)]. Token text remains Unicode source text under the current CST contract. For each real token, UTF8(token-text) must equal B sliced at its span.
6. Line starts are byte positions in B. The current LF-based line table is exactly reproducible by scanning B for byte 0A; UTF-8-encoding U yields the same table. Preserve CR/LF bytes. Oracle fixtures must separately verify PHP line-number behavior for LF, CRLF and CR; a UTF-8 offset proof alone does not prove that behavior.
7. ANTLR's diagnostic column is still a code-point column. Do not relabel it a byte column. If a byte column is exposed, derive it explicitly from the byte span and line start.
8. Existing literal lowering consumes Unicode token text and emits PHP bytes once. Do not preconvert all CST token text to Latin-1 carriers; that would make byte-string/unescape-double encode high bytes twice. Test supplementary characters, interpolated strings, heredoc/nowdoc and inline HTML as well as simple literals.
9. Begin the lexer in PHP mode with the default mode beneath it. Never prepend an opening tag, repair offsets after parsing, strip a BOM, or synthesize a source newline.
10. Pin this admission policy in the eval frontend/compiler profile revision and any artifact lookup key. Different byte payloads must not collapse through decoding, normalization or cache reuse. Never send caller cells or declaration tables to the compiler to implement these checks.

### Classified refusal

Ill-formed UTF-8 returns the normal correlated compiler rejection carrying this PHP diagnostic specialization:

~~~clojure
{:yang.php/diagnostic :yang.php/unsupported
 :construct :eval
 :reason :source-encoding
 :encoding :utf-8
 :byte-offset n
 :span [n m]}
~~~

Here n is the start of the first ill-formed sequence (the offending byte for an illegal leader; the leading byte for an invalid or truncated multibyte sequence), and [n,m] identifies the offending source interval within B. Pin interval selection in decoder tests so host decoders do not choose incompatible spans. Attach request/unit/provenance through the existing outcome envelope; retain the original bytes by that provenance.

This is a profile refusal, outside guest Throwable handling. It is not ParseError, CompileError, a class-collision fatal, false, or PHP null. Preserve its structured reason in the run outcome. No eval statements or declarations execute and no entry is installed for a rejected request. Operand evaluation has already occurred once and its effects are not rolled back. A missing compiler still uses the already-pinned missing-service refusal, not a fabricated encoding result.

### Arbitrary-byte parser path

A lossless arbitrary-byte path is feasible, but is deferred, not implicitly approved as part 2 scope. ANTLR's Unicode stream is not the fundamental obstacle: mapping each byte b to U+00b makes every parser index a byte index. However, the current pipeline would still be wrong if only that mapping were added. It also needs an identity offset table, byte-based line starts and validators, explicit token-text interpretation, byte-aware literal/HTML lowering, and an audit of identifier rules, escape handling and name lookup. Such a packet cannot silently masquerade as the existing Unicode/UTF-8 CST contract. The representation/profile contract must be explicit and versioned as needed, with original source bytes retained.

Do not switch the generic parser/exporter to identity offsets, or teach the existing literal encoder to guess the representation from character values. A separately specified byte profile can later replace this recorded restriction after conformance. The restricted UTF-8 adapter is the approved minimal path now.

## B. Required byte-domain fixtures

The oracle runner must assemble the eval payload from recorded hex/base64 bytes (for example hex2bin in the outer PHP harness), not from shell/editor-dependent source encodings. Record payload hex, wrapper source, image/digest and PHP version, ini/locale, stdout/stderr as bytes, exit status, return value/type, and caught exception class/message. Put fatal probes in separate processes. Record hand-derived expectations separately; all new oracle cases remain pending until executed.

| Fixture family | Payloads and assertions to pin |
|---|---|
| ASCII baseline and empty input | Empty eval, ASCII return, fall-through, bare return, and trailing newline/no newline. Pin null versus explicit result and baseline offsets. |
| Raw multibyte literals | Insert C3 A9 (é), E4 B8 AD (中), F0 9F 98 80 (😀), separately and adjacent, into single-quoted and double-quoted literals. Observe returned/emitted bytes using bin2hex and strlen. No mojibake or duplicated encoding. |
| Valid character boundaries | Exercise shortest/longest valid forms at UTF-8 width boundaries, U+10FFFF, and valid EF BF BD inside a literal/comment. These must pass encoding admission even if a source context is subsequently refused. |
| Normalization distinction | C3 A9 versus 65 CC 81 in otherwise identical literal payloads. Their byte lengths, results and source identities remain distinct. |
| Escapes versus raw bytes | Compare raw valid UTF-8 with ASCII source containing PHP byte escapes such as `"\xFF"`. The latter is valid source encoding even when its evaluated string is invalid UTF-8. Feed that result to nested eval to verify a fresh admission check. |
| Interpolation and multiline literals | Raw two-/three-/four-byte text around interpolated variables, escaped delimiters, heredoc and nowdoc. Pin bytes, indentation effects and line numbers within the supported syntax profile. |
| Identifiers and caller scope | A caller variable whose name contains multibyte UTF-8, accessed/modified by eval; multibyte declaration names and dynamic lookup separately; supplementary-character names as an explicit grammar-limit probe. Do not infer name support merely from successful literal decoding. |
| Comments and locations | Multibyte text in line/block comments before a return, before __LINE__, before a syntax error and immediately before EOF. Pin PHP line/error outcomes; separately check compiler byte spans against raw B. |
| Line separators | LF, CRLF and lone CR, with multibyte prefixes and trailing/nontrailing separators. Pin __LINE__ and diagnostic lines, and independently assert exact compiler line-start offsets. |
| Mode exit/reentry | PHP → ?> → multibyte inline HTML → <?php → PHP, including the newline after ?>. Pin output bytes and subsequent source locations; no artificial tag-prefix offset. |
| BOM and NUL | EF BB BF at byte zero and inside a literal; raw 00 inside a literal/comment and between tokens. They pass encoding validation; oracle determines PHP behavior by context. No stripping or guessed ParseError. |
| Invalid individual bytes | 80, BF, C0, C1, F5, FF in literals, comments, identifiers and inline HTML. Record actual PHP results even when PHP accepts them; this profile refuses the complete eval source before parsing. |
| Invalid multibyte forms | C3 28, E2 28 A1, F0 28 8C BC; truncated C2, E2 82, F0 9F 98; overlong C0 AF, E0 80 AF, F0 80 80 AF; surrogate ED A0 80; out-of-range F4 90 80 80. Cover at source start, after a valid multibyte prefix, and at EOF. |
| Refusal has no eval effects | An otherwise valid prefix that would print, write a caller variable and declare a class, followed by invalid bytes in a comment/literal. Oracle records PHP's actual result; profile tests assert whole-source refusal and no eval effects. |
| Nested payload preservation | Outer valid source builds inner source containing raw multibyte text or byte escapes. Inner request must carry precisely the constructed bytes, not UTF-8 of the byte carrier. |

PHP does not expose this compiler's CST spans. Therefore byte-span/EOF/token-slice/line-table equality and strict-decoder refusal are **compiler conformance tests**, not PHP oracle observations. Add malformed-carrier rejection, valid sequences split across transport chunks (validate the assembled payload), and JVM/Node worker equivalence to those tests. Service-backed Node/Dart execution must receive the same artifact semantics; hosts without a service keep their pinned refusal.

The identifier fixture is material: PhpLexer.g4 currently has ASCII Label and a NameString range ending at U+FFFE (lines 315 and 362–364). Encoding admission does not fix that grammar. If a valid-byte, valid-PHP case is outside the frontend profile, classify and record the unsupported syntax honestly; do not call an encoding failure or silently claim full identifier parity.

## C. Eval semantics — binding confirmation

The six recorded observations are mandatory for the engine's execution of an eval-unit:

| Recorded case | Required behavior |
|---|---|
| Class collision | Uncatchable guest compile fatal, preserving "Cannot declare class A, because the name is already in use" for the recorded case. No suppression, catchable Error/CompileError/ParseError, or successful eval result. |
| Declared class persists | A class successfully declared by eval is available in the caller's request-level class table after eval returns. |
| Declared function persists | A successfully declared function remains callable through the caller's request-level function table; the recorded zf() returns 42. |
| Mode exit | eval('?>html') emits html. Default lexer mode must be available below PHP mode. |
| Caller variable access | Eval sees the caller's live variable cells; the recorded $v=7 case returns 8. The prior design's write/reference/unset sharing contract remains. |
| Syntax error | Invalid PHP syntax in an admitted source becomes catchable guest ParseError, with no eval statements executed. This does not classify all compiler failures as ParseError. |

“Compile-phase” is relative to the eval invocation, not compilation of the enclosing program. The compiler service still receives syntax only. A collision against request-owned class state must be detected in the caller task during the eval unit's appropriate declaration/admission phase. For the recorded unconditional, hoistable collision, this is before ordinary eval body statements. It must not be delayed to a catchable declaration statement. Do not introduce a VM opcode, host PHP evaluator, second php/run-main, or mutable shared compiler registry.

Three concrete implementation obligations follow:

1. Remove collision suppression for earlier declarations/earlier eval invocations. A declaration statement may be skipped only when that exact declaration was already installed during **this execution** of this eval unit. Use execution-local declaration provenance; mere name existence, source identity or cached artifact identity is insufficient. Executing the same artifact twice checks declarations twice.
2. Fix the underlying collision route as well as hoist-class!/declare-late!. Calling today's declare-class! unchanged still throws catchable Error. Emit/use an explicit guest compile-fatal terminal path through the caller runtime that guest catches cannot intercept and that cannot resume the eval/caller body normally.
3. Keep guest compile fatals distinct from operational root-fatal. The latter deliberately skips shutdown/destructors for budget termination. Existing guest-fatal supplies an uncatchable mechanism, but its E_USER_ERROR cleanup policy is not automatically proof of eval compile-fatal parity. Reuse the terminal machinery only with the oracle-confirmed lifecycle policy; no Error-shaped fallback when eval runs in a supported composition.

Do not infer all remaining failure behavior from the word “fatal.” Runtime guest exceptions still use caller handlers. Operational compiler/transport/link failures and encoding refusals retain structured operational/profile outcomes. The malformed undefined-class probe on line 8 is not evidence that a well-formed runtime failure is ParseError; rerun that case before claiming it as oracle-confirmed.

### Collision acceptance extensions and evidence limits

The supplied record closes collision **classification and catchability**. It does not contain enough detail to settle every declaration ordering or cleanup observation. Preserve the recorded result and extend it with:

- Exact recorded outer-class collision under catch(ParseError), catch(CompileError), catch(Error), and catch(Throwable), with markers before eval, before/after the declaration inside eval, in catch/finally, and after eval.
- Same eval twice, including reuse of the same installed artifact; a noncolliding single invocation proves the late statement does not falsely collide with its own hoist.
- A successful declaration before the colliding declaration, observed from a registered shutdown function, to pin partial declaration visibility and order. Do not add transactional rollback or assume all-or-none installation.
- Conditional declarations, a declaration after a return, and unresolved parent/interface/trait cases to pin whether/when their declaration is reached. Do not generalize unconditional hoisting timing to every class statement.
- Shutdown-function, destructor, output-buffer and terminal-status observations on a collision. These determine which guest terminal cleanup path is correct.
- Repeated function declarations, collisions with builtins, and duplicate declarations within one eval unit as separate oracle rows; class-collision evidence does not settle their exact timing/text.
- A well-formed undefined-class runtime expression under catch(Error) and catch(Throwable), separate from syntax-error fixtures.

These are targeted fixture gates, not a reopening of the six accepted results. Part 2 can implement against the ruling now; it must not claim lifecycle/partial-visibility parity from summary evidence that does not record it.

## D. Amendment to the prior design

This findings document is the normative addendum to the final message of the 1794400000000 eval-bridge design log. Preserve that log. During implementation, promote the following text into docs/design/yang.php.md section 3.12, and qualify section 13's blanket “fatals as Error throws” wording:

> Eval requests preserve the exact PHP byte-string source. The initial eval compiler profile admits only well-formed UTF-8. At the parser worker boundary the bytes are strictly decoded to Unicode; re-encoding must reproduce the original bytes exactly. CST token text remains Unicode, while all source spans and line starts refer to the original bytes. There is no replacement decoding, normalization, BOM removal or tag repair. Malformed UTF-8 produces the classified unsupported eval/source-encoding refusal outside guest Throwable handling. This is a recorded profile restriction relative to PHP byte strings; arbitrary-byte parsing is deferred.
>
> Eval class redeclaration in the recorded collision case is an uncatchable guest compile fatal. It is neither silently suppressed nor mapped to Error, CompileError or ParseError. Checks against existing declarations run in the caller task at eval's declaration phase, using the existing request runtime. Only a declaration already installed by the same eval-unit execution may be skipped at its later statement. Each execution, including reuse of a compiled artifact, performs fresh checks. Guest compile-fatal termination is distinct from operational budget termination; cleanup and declaration ordering follow their pinned oracle fixtures.
>
> Successful eval declarations persist in the caller's request tables. Eval shares caller variable cells, supports PHP-mode exit/reentry, and converts actual syntax errors to ParseError. Explicit returns and null fall-through, caller hooks/budget, separate compilation environment and ordinary stream/compiler/linker boundaries remain as previously specified. The local PHP parser profile now supports JVM and Node; any execution host may use a configured compiler service, and a composition without one retains the pinned eval refusal.

Add the encoding restriction to the supported/refused-profile ledger and add the byte fixtures to the eval acceptance matrix. Update source-identity/cache profile revisions accordingly. No generic CST contract change is authorized by this restricted path.

Unchanged architectural properties confirmed: no parser calls from portable execution; no caller state in compile requests; no second runtime or budget; ordinary code/entry/link admission; same-task cell ownership; exact source provenance; no shared mutable compiler state. This ruling authorizes design direction, not implementation sign-off.

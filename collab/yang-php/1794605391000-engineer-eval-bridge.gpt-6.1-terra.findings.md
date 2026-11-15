# Eval bridge: design gap before implementation

- Status: stopped under the brief's “design gap -> STOP + record” rule.
- Base: `eval-bridge` at `363a1ef2`; P7-b merge `a8dd702f` is an ancestor, and master is at `746b2c34`.
- No implementation or tests were changed or run. The existing untracked session log was left alone.

## Source byte interpretation requires a ruling

The design requires eval requests to carry source bytes, the parser to start in PHP mode, and source spans and line starts to remain relative to the original bytes. It also identifies invalid byte sequences and the PHP byte-string carrier as oracle-first cases, without specifying an admission/decoding policy or a byte-preserving parser representation. These are necessary for the first compiler path, not merely a diagnostic detail:

- `src/cljc/yang/php/antlr/prelude.cljc` documents PHP strings as host strings with one character per byte (Latin-1 carrier).
- `src/clj/yang/antlr/cst.clj` `parse-unit` passes a host Unicode string to ANTLR and derives offsets via Unicode code points and UTF-8 encoding. `src/cljc/yang/php/antlr/cst.cljc` derives line starts by UTF-8 encoding that string.
- Feeding the byte carrier directly double-encodes bytes above 127 in offset accounting and literal conversion. Strict UTF-8 decoding rejects arbitrary byte sequences that PHP strings can hold; replacement decoding loses bytes. Neither rejection nor replacement is authorized by the design.

Please rule on the supported source-byte domain and representation at the parser boundary, including invalid UTF-8 and byte offsets/literals. A byte-preserving parser/export path or an explicit classified refusal may be viable, but choosing either changes the claimed semantics. Oracle fixtures should pin valid multibyte and invalid byte sequences before declaring support.

## Additional oracle dependency

The design also requires eval class redeclaration not be suppressed and explicitly says to pin fatal timing and catchability before changing the shared hoister. Both `php/hoist-class!` and `php/declare-late!` currently suppress an existing class. The pinned PHP 8.3.35 oracle is not available in this workspace (`php` is not installed); the brief assigns the shiva oracle pass to the orchestrator. Request oracle observations for repeated eval/class collisions and partial declaration visibility before changing that behavior.

No conclusion about parserless host support should be inferred from the older design's statement that the PHP parser is JVM-only: the current `src/cljc/yang/php/antlr/cst.cljc` has a Node recognizer profile. The configured-service-or-pinned-refusal policy in the brief remains authoritative.

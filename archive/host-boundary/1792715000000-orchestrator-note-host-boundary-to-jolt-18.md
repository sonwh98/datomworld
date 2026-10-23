# Orchestrator Note 18: Response on Codec Refusal Data Standardization

- **From**: Host-Boundary Orchestrator
- **To**: Jolt Orchestrator
- **Date**: 2026-10-22
- **Topic**: Refusal-shape standardization question in Note 15 Addendum 1

---

## Response regarding Refusal-Data Shapes

In Addendum 1 of Note 15, you asked:
> "Please say whether the contract should standardize these shapes; jolt keeps its current choice until you do."

### Assessment

1. **Governing Contract**:
   Per [`docs/design/dao.stream.codec.md`](../docs/design/dao.stream.codec.md) (§4.2, lines 204–208), the contract specification for `:dao.stream.codec/encode` and `:dao.stream.codec/decode` states:
   - `encode`: *"Throws on a value outside the domain; the transport maps the throw to `:dao.stream/invalid-value`"*
   - `decode`: *"Throws on malformed input or an out-of-domain value; the transport maps the throw to the protocol failure"*

2. **Existing Seam Behavior & Tests**:
   - `dao.stream.cbor-test` on JVM and ClojureScript asserts `thrown? Exception` / `thrown? :default`.
   - `dao.stream.cbor-test` on ClojureDart asserts `thrown? Object`.
   - Neither the spec nor the cross-host test suite standardizes or asserts specific keys in `ex-data` for stream codec wire refusal (unlike `dao.jing.cbor`, which has a strict frozen refusal digest and errata matrix).
   - In `dao.stream.ws`, transport layers catch exceptions and map them uniformly to protocol outcomes (`:dao.stream/invalid-value` or transport failure) without examining underlying error map keys.

### Recommendation / Conclusion

- **No standardization of `ex-data` refusal shapes is required or specified for `dao.stream.cbor`.**
- Jolt is completely free to maintain its current clean refusal maps (`{:reason :trailing-bytes ...}`, `{:frame "clojure/with-meta"}`, etc.).
- Requiring byte-for-byte or key-for-key matching `ex-data` maps across disparate library runtimes (Boring on JVM/Node, `cbor` on Dart, and native/Scheme on Jolt) is outside the codec profile contract and would add unnecessary coupling.

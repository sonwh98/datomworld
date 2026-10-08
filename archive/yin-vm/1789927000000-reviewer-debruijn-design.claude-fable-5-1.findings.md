Completed-GMT: 2026-09-20 17:41:24 GMT
Completed-Local: 2026-09-21 00:41:24 +07 (Indochina Time)

# Adversarial design review: the de Bruijn projection

**The design is not ready for implementation dispatch.** I found two P1 soundness defects and seven P2 defects that change the canonical form. They must be fixed in the document before D0, because every one of them alters the bytes that get hashed. The architecture, the layering and the vocabulary coverage are sound. This was a docs-only review. I edited nothing and ran nothing.

## P1

**P1 | §3 Scope algorithm | Duplicate parameter names resolve to the wrong binder.**
- The VM binds parameters with `(into {} (map vector params …))` (`engine.cljc:46-51`). For a duplicate name the rightmost parameter wins.
- The design pushes parameters right-to-left, so the source-leftmost has depth 0. The resolver searches nearest first and "returns the first matching binder". For a duplicate name that is the leftmost.
- At runtime `(fn [x x] x)` behaves like `(fn [a b] b)`. The design projects it as depth 0, which is `(fn [a b] a)`.
  - Two alpha-equivalent programs project differently.
  - Two non-equivalent programs project identically.
- Correction: state that, within one frame, a duplicated name resolves to the rightmost parameter, matching `bind-params`. Alternatively, make duplicate parameters a terminal diagnostic, but only if Yang already rejects them. I found no such check in the source.
- Add `(fn [x x] x)` to the §8 test matrix.

**P1 | §2 memo, §4 ordinals, §5 fingerprint | The fingerprint depends on node sharing, which is emission history.**
- The emitter shares a node when it carries a pre-assigned `:eid` (`vm.cljc:546-561`).
- §2 keeps one projected reference per `[source-eid lexical-context]`, so the projected graph inherits that sharing. §5 then hashes `row-count` and first-visit ordinals.
- One term emitted once as a tree and once as a graph with a shared subterm gives different row counts, different ordinals and different hashes.
- That contradicts §1 rule 5 ("not of … emission order, or host identity") and fails review item 3.
- Correction: make identity structural, as a Merkle hash. `hash(node) = H(dimension ‖ tag ‖ scalar slots ‖ child hashes in order)`, and the fingerprint is the root's hash.
- What the Merkle hash changes:
  - The hash becomes a function of the unfolded term alone.
  - Ordinals, the `-16` base, `row-count` and `root-ordinal` drop out of the identity.
  - Hash-consing subterms is the only real compression the design can offer (owner constraint 4b). Dropping names saves very little.
  - It matches the d1 content-addressing floor in `datom.md`.
  - The `[eid, context]` memo stays, as an optimisation that avoids exponential re-walks. It no longer affects the output.

## P2

**P2 | §1 rule 4, §3 | The projection's domain with respect to macros is not stated.**
- The design keeps `:yin/macro?`, so it admits input that is not yet macro-expanded.
- Renaming a binder preserves meaning only after expansion, or under hygienic macros.
- Example: macro `m` expands to the variable `x`. Then `(fn [x] (m))` and `(fn [y] (m))` project identically but mean different things.
- I did not verify where expansion sits relative to "post-Yang, pre-storage". The walker ignores `:macro?` (`ast_walker.cljc:21`), and expansion is upstream stream topology.
- Correction, one of two:
  - Declare the domain to be the fully expanded AST, and make an unexpanded macro call site a diagnostic.
  - Or state the hygiene assumption as a limit of the equivalence.

**P2 | §2, §5 | `:yin/tail?` is derived data, and it is inside the identity.**
- The emitter writes it only when the node map carries `:tail?` (`vm.cljc:564`).
- The same program has two fingerprints depending on whether a tail-annotation pass ran. That is again emission history.
- Tail position can be computed from the structure.
- Correction: exclude `:yin/tail?` from the rows and the hash. Derive it; do not persist it.

**P2 | §2 "locates the entity carrying `:yin/root true`" | Root selection conflicts with the claim that input order does not matter.**
- The schema comment says "the last one in batch order wins" (`vm.cljc:460-461`, citing `yin.vm.macro.md` §2.4).
- Correction: require exactly one root per projected graph, and make several roots a diagnostic. Alternatively adopt last-wins and withdraw the claim that input order is ignored for the root.

**P2 | §2 "`t` and `m` are ignored" | `m` carries the assert-or-retract operation.**
- The emitter's `m` defaults to `datom/default-op`, which is assert.
- Ignoring `m` turns a retraction into an assertion.
- Correction: honour the operation, or require assert-only input with a diagnostic for anything else. Only then drop `t` and `m` from the identity.

**P2 | §6 adapter "accumulates … until `:dao.stream/end`" | A compilation stream that carries more than one program never delimits a graph.**
- The emitter guarantees that the root datom is the last datom of a batch. That datom is the natural frame marker.
- Correction: frame on `:yin/root`. Keep `end` with a partial graph as the diagnostic.

**P2 | §2 grammar, §1 rule 4 | The policy for unknown attributes is missing.**
- Unknown node types are diagnostics. Unknown attributes are not mentioned.
- `:yin/macro-name` is in the schema (`vm.cljc:465`). `ast->datoms-with-root` does not emit it, and the design does not name it.
- A real stream may also interleave `:yin.code/*` datoms and the expander's event datoms.
- Correction:
  - An unknown `:yin/*` attribute on a walked node is a diagnostic, because silently dropping a meaning-bearing attribute corrupts the hash.
  - Other attribute namespaces are ignored.
  - `:yin/macro-name` is named explicitly, with its rule.

**P2 | §5, D3 | Byte-identical output across hosts is not yet achievable for numbers, and NFC normalization is a risk.**
- ClojureScript cannot tell `1` from `1.0`; the JVM can. The design fixes integer width, and gives no rule for floats, bigints, ratios or characters, or for which one applies on which host.
- NFC normalization is inherited from `datom.md:88`. It makes string literals that differ only in normalization collide, although they behave differently at runtime.
- Dart has no built-in NFC.
- Correction:
  - Add a numeric canonicalisation table to D0: integers as int64, non-integral numbers as IEEE-754 doubles with the NaN and ±0 rule declared.
  - State how a JS number is classified.
  - Make an out-of-domain literal a diagnostic.
  - Record the NFC collision as an inherited limit.
  - Settle the Dart NFC source before D3.

**P2 | §2 "memoized by `[source-eid lexical-context]`" | "Lexical context" is undefined.**
- The key must be the stack of frame parameter vectors.
- An intermediate binder shifts outer depths even when the subterm never references it.
- Two distinct binder entities with equal parameter vectors are the same context.

## P3

- **§4 (P3).**
  - **Operand order.** `:yin.db/operands` must never be stored as cardinality-many refs. `datoms->tx-data` expands `:yin/operands` that way (`vm.cljc:514-520`) and loses the order. The named form survives this, but the projected form cannot. Under the Merkle correction, references become ordered child hashes and the problem goes away.
  - **Attribute name.** `:yin.db/*` reads as "database" next to DaoDB's `:db/*`.
- **§4 and D2 (P3).**
  - **Temporary ids.** Every projection reusing temporary ids from `-16` is harmless under a transactor.
  - **Deduplication.** That reuse makes deduplication across programs impossible, which is another reason for content addresses.

## Completeness (item 4)

- Every node type that `ast->datoms-with-root` emits has a row in the design's table. I compared the table against `vm.cljc:565-630` arm by arm.
  - The attributes are correct.
  - The child order is correct.
  - `:vm/store-put`'s `:yin/value` is correctly treated as data and not as a node.
- The only omission is the attribute `:yin/macro-name`, covered under P2.
- The essay's `(fn [count] (+ count 1))` is preserved: `+` stays free and `count` is bound at depth 0.

## Rulings on the six open questions

1. **Parameter depth convention.**
   - Source-leftmost as zero is acceptable.
   - Encode the index as a pair `[frame-depth, position]`, not one flat depth.
   - A reference to an outer variable is then independent of inner arities. Under Merkle hashing, identical bodies therefore share across lambdas of different arity.
   - The right-to-left push disappears.
   - Position follows the duplicate-name rule from the first P1.
2. **Shared-node duplication.**
   - Yes, a node may be projected once per lexical context, as the design says.
   - The converse must also hold: sharing must never be visible in the identity. That is the second P1.
3. **Unordered literals.**
   - Maps are encoded in canonical key order, using the canonical byte order of the encoded keys.
   - Sets are encoded as their elements sorted by canonical byte encoding. Rejecting them is wrong: a set literal is a legal program value, and byte order is a total order available on every host.
   - Reject only values that have no canonical codec.
4. **Storage vocabulary.**
   - Rename the namespace to `:yin.debruijn/*`. It matches the domain separator and avoids the "db" reading.
   - Declare a published dimension descriptor, as `datom.md` lays out.
   - A private segment type would put the hash outside the moduli space that the rest of the system verifies against.
5. **Fingerprint algorithm and version.**
   - SHA-256 is confirmed; `datom.md:92` already fixes it.
   - Use the declared dimension's hash as the domain separator, following `hash(dimension-hash ‖ slots)` at `datom.md:66`. Do not use the ad hoc `"yin.debruijn/v1\0"` string.
   - A change of version is then a new dimension descriptor.
6. **Source-to-projected index.**
   - Keep it ephemeral by default.
   - When persisted, it is a separate side index, never part of the identity.
   - Under Merkle hashing it is a map from a source entity id to a node hash. That map is derivable, so persist it only where a diagnostics consumer exists.

## Boundary (item 6)

- **`dao.lease` and the waitset.**
  - The design requires no interaction with either.
  - §6 allows a waitset only if a composition owns several waiters.
  - It introduces no lease fact, timer or callback.
- **Runtime layers.**
  - The AST schema, the walker, the semantic VM, `yin.vm.linearize` and `dao.stream` are all read-only inputs.
  - The projection consumes the `:yin/*` datoms that the emitter already produces.
- **Stream shape.**
  - The adapter is an ordinary forward step.
  - Framing on the root marker (P2 above) keeps it that way, because it removes the wait for end-of-stream.

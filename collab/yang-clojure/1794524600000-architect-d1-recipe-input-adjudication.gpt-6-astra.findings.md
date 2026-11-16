Completed-GMT: 2026-11-13 16:54:24 GMT
Completed-Local: 2026-11-13 23:54:24 Asia/Ho_Chi_Minh

RULING: (A) — Reconstruct from admission-captured text under a pinned printing/reading profile; accept each record only when replay reproduces its retained roots and required correspondence artifacts exactly.

The defect is in D1’s underspecified reconstruction input. Jing deliberately normalizes map/set representation and sorts their encoded members; it does not retain host iteration order. The compiler explicitly turns that order into ordered operands at [clojure.cljc:316](/Users/sto/workspace/datomworld-d1/src/cljc/yang/clojure.cljc:316). Equal values can therefore produce different compiled roots under this lowering. That is an expected consequence of the current contracts, not evidence of Jing corruption.

Unitarity requires retaining the information needed to recover the admitted syntax and its interpretations. An order-insensitive value snapshot is insufficient when the interpreter observes order. Captured text supplies otherwise missing input information; it is not a derived classification that a query over the retained unordered values could recover.

I reject **(B) for this round**: preserving host order in Jing changes its canonical representation contract; canonicalizing compiler traversal changes ordered code output and potentially evaluation effects. Either requires separate design, ownership and profile migration. I reject **(C) as a mandatory contract**: a second decoded program cannot certify ordering or replace the replay gate, and its value can be derived from the retained text. Admission-time comparison with the original in-memory input remains required under (A).

The following is the normative amendment to [§4.1](/Users/sto/workspace/datomworld-d1/collab/yang-clojure/1794670000000-architect-callsite-generalization-design.gpt-6-astra.findings.md:217).

1. **Reconstruction input and capture boundary.**  
   The recipe’s authoritative reconstruction input is retained text captured from the actual admitted compilation inputs, before their order-observing consumption and before codec normalization. For this Clojure recipe, this includes the assembled program supplied to `yang/compile-program`, printed with metadata. This text is a serialization of admitted forms; it need not be the original handwritten source.

   Retain every additional input required to reproduce source hashing, assembly, expansion, instrumentation and correspondence. In particular, `forms` and `call` must have replay-preserving representations because the existing oracle hash traverses and prints them. Options and other inputs may remain ordinary Jing values only where the profile establishes that codec normalization cannot change their interpretation. Otherwise they require the same text treatment.

   Text is an ordinary addressed source artifact, not a UAST address. Decoded forms are derived working values. Optional diagnostic snapshots confer no reconstruction authority.

2. **Pinned printing/reading profile.**  
   The addressed profile must specify the exact executable printer and reader implementations, versions, host/runtime, admitted source domain, character encoding, dynamic bindings, reader options and tag handlers.

   The initial JVM profile shall use UTF-8 text without normalization and pin `clojure.core/pr-str` with metadata enabled, unlimited print length/level, readable printing enabled, duplicate printing disabled and namespace-map shorthand disabled. Any further printer behavior affecting admitted values must be pinned or excluded by the admitted domain.

   Reading shall use the pinned EDN reader, accept exactly one expected top-level value and require EOF afterward. Ambient custom readers, implicit namespace resolution and fallback to a different reader are prohibited. Supported tagged forms must have explicitly pinned handlers; unsupported forms refuse reconstruction.

   **Printing and reading are not asserted to preserve every Clojure value or collection order.** The admitted domain and per-input gates determine support. A host that cannot reproduce the profile refuses; this does not waive existing cross-host adoption requirements.

3. **Admission-time fidelity and replay gate.**  
   Capture from the actual input, then read the captured text and compare it with that input before declaring it reconstructable. The comparison must preserve syntax distinctions, numeric identities and required metadata. `content=` alone is insufficient: its implementation ignores metadata and treats lists and vectors alike.

   Compile the read-back input under the pinned profile and recorded compilation context. Require exact equality between its final canonical root and the root produced from the actual admitted input. Retain and validate the complete referenced row closure.

   After durable storage, repeat reconstruction using only fetched retained inputs and dependencies. Compare with the retained expected root; do not replace that root with the replay result. Each claimed compilation must pass independently. A successful fixture does not license another fixture.

4. **Exactness beyond the root.**  
   Root equality is necessary, but does not establish D1 verification. Replay must also reproduce the oracle’s existing canonical-source-hash, independently derived site declarations, actual admission/expansion associations, complete C1 relation and claimed transformation correspondence. Image and instruction-provenance claims additionally require the existing derivation checks, instruction mappings and multiplicities.

   Preserve the distinction between compiled pre-stamp roots and transformed stamped roots. Reconstruct their explicit correspondence. Reuse recorded admission coordinates and expansion attempts; replay must not mint replacement origins.

   The profile must pin the actual post-port stamping implementation. The [cc3 rebind note, item 3](/Users/sto/workspace/datomworld/collab/yang-clojure/1794519755000-engineer-post-port-oracle-reverification.glm-5.3.findings.md:45) identifies two inserted applications per wrapper, site extraction at `[:operands 1 :operands 1 :value]`, and translated argument steps `[3 1] [3 2]`. Those details require executable dependency retention, not merely a commit string or copied counts.

5. **Refusal and authority.**  
   Missing capture, unavailable dependencies, unsupported profiles or source forms, reader failure, fidelity mismatch, root divergence, source-hash mismatch, or correspondence/coverage mismatch refuse the affected reconstruction claim. Report the failing compilation and stage. A corpus-wide claim cannot silently omit failed fixtures.

   Do not recover by sorting collections, switching to decoded-value compilation, weakening equality, changing expected roots, or manufacturing origins. Those actions would change the recipe or evidence.

   Refusal does not erase historical evidence or prohibit ordinary loading under existing code contracts. A producer’s successful gate establishes eligibility for independent verification; only the separate §4.2 verification result authorizes reliance.

6. **Versioning and retention.**  
   Assign this text replay profile its own immutable identity and retain its interpreter/dependency closure. The existing closed D1 envelope and recipe schema can carry it through `profile`, `inputs` and `input-roles`; no Jing, UAST or manifest schema change is required solely for this amendment. The exact oracle evidence contract remains unchanged.

The migration is:

1. Keep the current code and r3 results classified as a feasibility pilot. No accepted corpus record exists to migrate.
2. Move capture to the input boundary and implement the versioned text profile, including replay-preserving `forms`/`call` inputs and explicit compiler arguments. Remove the redundant decoded `:program` from the authoritative input contract; retain it only as optional diagnostic evidence if useful.
3. Implement admission and reopened-store refusal gates for every corpus compilation. Preserve the observed decoded-value divergence as a regression case; include metadata, collection-kind, map/set order and unreadable-input cases.
4. Produce new durable admissions where the pilot’s retained closure no longer exists. Never reconstruct historical admission identity from equal source text. Preserve the frozen oracle evidence and establish each new compilation’s relationship to it independently.
5. Complete dependency retention, compilation-scoped bindings, discovery/retention ownership and independent verification before publishing a relying companion. The r2/r3 limits on tagged execution and measurements remain in force.

Static review found these concrete pilot deficiencies:

| Location | Finding and required correction |
|---|---|
| [latte_oracle_test.clj:2545](/Users/sto/workspace/datomworld-d1/test/yang/latte_oracle_test.clj:2545) | Text is printed after compilation at line 2523, and only three printer bindings are controlled. This establishes neither capture before consumption nor a complete printing profile. Move capture earlier and pin the remaining behavior. No mutation-induced corruption is demonstrated by this review. |
| [latte_oracle_test.clj:2544](/Users/sto/workspace/datomworld-d1/test/yang/latte_oracle_test.clj:2544) | Only `program` gets a text representation. `forms` and `call` remain decoded values, while [the source-hash function](/Users/sto/workspace/datomworld-d1/test/yang/latte_oracle_test.clj:2429) uses traversal order for gensym assignment and then prints the result. Program-root replay alone leaves this second order-sensitive dependency unverified. Preserve these inputs appropriately without changing the frozen hash algorithm. |
| [latte_oracle_test.clj:4274](/Users/sto/workspace/datomworld-d1/test/yang/latte_oracle_test.clj:4274) | Bare `edn/read-string` supplies no explicit profile and does not establish an exactly-one-form/EOF contract. The compiler’s `true` argument is also supplied by the test rather than identified in a retained recipe. |
| [latte_oracle_test.clj:4278](/Users/sto/workspace/datomworld-d1/test/yang/latte_oracle_test.clj:4278) | Host `=` and `content=` do not establish metadata or syntax-kind fidelity. [The latter’s implementation](/Users/sto/workspace/datomworld-d1/src/cljc/dao/jing/cbor.cljc:540) explicitly ignores metadata and equates sequential kinds. Add the profile’s syntax/metadata checks; preserve root equality as a separate obligation. |
| [latte_oracle_test.clj:4285](/Users/sto/workspace/datomworld-d1/test/yang/latte_oracle_test.clj:4285) | The replay compares a root, while retained metadata is merely fetched and compared with the original producer output. It does not reconstruct C1, source hash, declarations or stamped correspondence after reopen, and checks only the stored root row directly. This remains a pilot, not a complete reconstruction gate. |

The structural storage helpers already disclaim verification; their ability to store an unverified claim is not itself a defect. Their success must not become the acceptance signal.

Static review only. No files edited and no suites run.
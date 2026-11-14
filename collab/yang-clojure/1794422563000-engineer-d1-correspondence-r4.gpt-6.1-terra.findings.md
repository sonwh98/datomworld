# D1 continuation r4: capture profile and admission refusal

Status: **incomplete; no corpus admission or replay gate has passed**. This continues r3 `1794422563000-engineer-d1-correspondence-r3.gpt-6.1-terra.findings.md` and the preceding append-only findings. Worktree mirror only; no promotion to canonical collab. No commit, tagged run, independent verifier, or bounds declaration.

## Implemented boundary and text-profile prototype

`src/cljc/yang/correspondence_text.clj` contains a JVM-only schema-1 profile data value and printer/reader prototype. It records the executing Clojure and Java versions/vendor, printer/reader symbols, UTF-8/no normalization, the stipulated print bindings, exactly-one-value/EOF behavior, and an explicitly tag-refusing reader. The reader provides explicit rejecting handlers even for EDN's built-in `inst`/`uuid`; unknown tags, empty input and trailing values refuse. The profile can be Jing materialized and fetched in a test, but is **not yet** the accepted pinned profile or executable dependency closure, and no corpus recipe references it.

The comparison checks collection syntax kinds, map/set iteration order, numeric class and numeric `.equals`, and metadata recursively. List syntax includes seq implementations such as `Cons`/lazy seq but remains distinct from vector syntax. Failures return the compilation/input, `:stage`, and a compact mismatch path. Tests exercise list/vector, numeric type and decimal identity, metadata, map/set order, unsupported objects/tags and extra forms. A separate regression shows Jing can return an equal dynamic map whose different iteration order yields a different canonical AST root.

In the oracle harness, opt-in capture now runs for `forms` and `call` **before** source hashing and for assembled `program` and `options` **before** `yang/compile-program`. It captures only the actual values and never synthesizes an origin. Ordinary compilations do not opt in. Original decoded snapshots in the experimental output are diagnostic only; no recipe claims them as authoritative. The stage is deliberately fail-closed rather than sorting or silently compiling a decoded snapshot.

## Blocking observation: stipulated profile loses numeric identity

The real `:beta-eq-false` compilation, when opt-in capture is enabled, refuses its **forms** at `:fidelity`, before compiling. The mismatch in this checkout is path `[15 2 :metadata [:value 2]]`: the original metadata value is `java.lang.Integer`, while `clojure.core/pr-str` prints an untyped integer and `clojure.edn/read` returns `java.lang.Long`. The values can be numerically equal, but the requested fidelity rule explicitly preserves numeric identities. A focused real-fixture test asserts this refusal, fixture, input and stage. A separate synthetic metadata test reproduces the same type loss.

This is a limitation of the stipulated initial plain `pr-str`/EDN profile for this admitted input, not a demonstrated change to the original compiler result and not a reason to weaken equality or invent an origin. The r3 root-only pilot did not test metadata/numeric fidelity and remains a feasibility observation only. Its old successful replay test was replaced by the explicit real-fixture refusal regression; it is not represented as an r4 passing gate. An architect/owner decision is needed on a replay-preserving representation/profile or on an explicitly revised admissible-input/fidelity rule; no such change was assumed here.

## Remaining acceptance work

The text profile does not yet pin and retain its executable dependency closure, check runtime/profile compatibility, implement a complete admitted-domain specification or establish a persisted recipe. The successful admission-time readback/compile/root and post-reopen gates, complete row-closure validation, canonical-source-hash re-derivation, independently derived site declarations, source/expansion association, full C1 relation and post-port transformation correspondence are not implemented as a complete gate. Failure cases for missing captures, missing dependencies, unknown profiles, root/hash/correspondence mismatch remain to be implemented and tested. The fixture refusal means no corpus-wide claim is possible, and no companion or durable corpus record was written.

The frozen v1 oracle table remains SHA-256 `e435ac4072ba0a3c278fbe91918d22184b43f0a8d6c1e397c09f68dd629beb75`. The row-lane-only restriction remains. No existing image or manifest contract is changed.

## Tests

Red-first: new profile namespace was initially missing; adding capture requirements first failed because `forms`, `call` and options lacked text captures; attempting the real fixture under the strict capture then exposed the numeric metadata fidelity failure. The failure was retained as a refusal regression, not converted to a successful reconstruction. The correspondence, text-profile, and Clojure call-site battery passed 49 tests / 638 assertions. The real-fixture refusal test passed 1 test / 5 assertions and the earlier static table/binding test passed 1 test / 12 assertions. clj-kondo 2026.08.04 reported 0 errors and 0 warnings across all touched code/test files. The full oracle suite and all tagged lanes were not run.

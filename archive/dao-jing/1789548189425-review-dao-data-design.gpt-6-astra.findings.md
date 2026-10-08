Completed-GMT: 2026-09-16 08:52:00 GMT (approx, extracted from stdout.log turn.completed)
Coding-Agent: codex
Session-ID: 01a0a963-3b02-7f42-bb12-960fc4520db2

# Review: `docs/design/dao.data.md` against Fable's discovery findings

Role: Adversarial Reviewer
Model: gpt-6-astra

**Verdict: another revision pass is needed before implementation.** The two-function abstraction is reasonable, but the current document promises boundedness, termination, and wire portability that its rules do not provide.

1. **Blocking design defect — bounded sequence traversal does not guarantee bounded realization or termination.**

   [dao.data.md:81](docs/design/dao.data.md:81) promises to realize at most `:items + 1` elements, "never more," and calls this total because full realization is supposedly the only operation that could hang.

   A lazy producer can realize a chunk when asked for one element; it can also throw, perform effects, or never produce its first element. I verified on the installed Clojure 1.12.4 runtime that taking two elements from a mapped range evaluates **32** producer elements. An expression such as `(filter constantly-false an-infinite-sequence)` cannot produce even its first element.

   **Required change:** distinguish the number of elements the summarizer *requests/retains* from work performed by the producer. Either restrict accepted sequences to trusted, productive inputs and withdraw unconditional totality, or decline to realize arbitrary lazy sequences. Define exception handling separately; catching exceptions cannot solve nontermination.

   The `counted?` gate itself is appropriate. However, [dao.data.md:78](docs/design/dao.data.md:78) incorrectly equates already-realized sequences with counted sequences. A fully realized `LazySeq` still returned `false` for `counted?` in the same runtime check. Describe the predicate's contract, not realization status.

2. **Blocking design defect — the result is not necessarily safe to ship through live v2 streams.**

   [dao.data.md:55](docs/design/dao.data.md:55) preserves every `number?` value unchanged, while [dao.data.md:5](docs/design/dao.data.md:5) promises portable, safe-to-ship output.

   The actual codec accepts a narrower numeric domain: JVM ratios, BigInts, BigDecimals, nonfinite numbers, and out-of-range integers are rejected by `transit.cljc:19` and `transit.cljc:34`. Summarizing `1/3` produces a map a WebSocket handle rejects as `:dao.stream/invalid-value` (`ws.cljc:172`).

   **Required change:** define how nonportable numbers are represented or omitted. Keep classification separate from permission to preserve the original value. State that the resulting tree belongs to the actual v2 portable-value domain, and document how that guarantee works across CLJ/CLJS/CLJD. Merely avoiding reader conditionals does not establish portability.

3. **Blocking design defect — `:chars` closes one size hole, but leaves other unbounded leaves.**

   Strings are now bounded, correctly, at [dao.data.md:69](docs/design/dao.data.md:69). But keywords and symbols remain unchanged at line 55, despite potentially arbitrary name lengths. Arbitrarily large numeric representations also remain until finding 2 is resolved.

   Stream identity is another bypass — current WebSocket identities need only satisfy `string?`, no length limit (`ws.cljc:40`). Copying that identity directly circumvents `:chars`.

   **Required change:** specify bounded representations for every variable-size leaf and for identity, or explicitly narrow the guarantee to structural bounds plus string-value bounds.

   The motivating citation also needs correction: `driver.cljc:384` prints only **non-string** input. A vector containing a huge string demonstrates the real problem there; a top-level string does not enter that branch. The `:chars` addition remains justified regardless.

4. **Real gap — the stream schema confuses three distinct values.**

   The table specifies `:dao.data/identity`, but [dao.data.md:90](docs/design/dao.data.md:90) gives `{:type :stream :identity <descriptor>}` — this both changes the key namespace and substitutes a descriptor for identity. `stream/v2.cljc:148` distinguishes the operation's outcome map, its `:dao.stream/descriptor` reachability value, and its `:dao.stream/identity` projection — not interchangeable. Also, the conforming descriptor operation has **no failure outcome** (`dao.stream.md:203`).

   **Required change:** name the exact extracted field, use qualified output keys consistently, and reconcile its representation with the bounds.

5. **Real gap — several boundary cases remain contradictory or unspecified.** Evidence at [dao.data.md:22](docs/design/dao.data.md:22), the schema table (line 52), and the rules (line 64):
   - Require finite, nonnegative integer bounds and define invalid-bound behavior.
   - Mark collection counts/child fields as conditional in the table (currently unconditional there, conditional in rules — inconsistent).
   - Scope depth-zero truncation to containers (currently contradicts the string rule's unconditional `false` for short strings).
   - State explicitly that depth zero performs no sequence probe.
   - Omit `:count` for **all** non-counted inputs, including finite sequences exhausted during probing.
   - Specify vectors for `:items`/`:entries`/entry pairs (ensures eager plain output, preserves distinct members whose summaries collide).
   - Define "characters" precisely (UTF-16 code units vs. Unicode code points vs. other) and surrogate-boundary treatment.
   - For maps, clarify `:items` bounds **entries** (each containing two summarized nodes), not "children."

6. **Real gap — the discovery summary overstates substitutability; the design drops the evidence entirely.** The original brief requires citations for every discovered site (`prompt.md:92`). Coverage audit:

   | Claimed duplicate/gap | Verified evidence and assessment |
   |---|---|
   | Live v2 classifier | Confirmed (`telemetry.cljc:58`, `ffi.cljc:209`). `tag` can replace it, with vocabulary changes noted. |
   | `dao.pretty` classifier | Overstated — `pretty.cljc:49` renders scalar syntax including string escaping; it doesn't produce classification data. Neither function replaces that responsibility. |
   | Ignored pretty-printer depth | Correct on CLJ/CLJS (`pretty.cljc:12,22`). Dart uses depth for layout, not truncation. Summarizing before printing can bound diagnostics but doesn't make the printer a duplicate. |
   | Await handle detection | Live analogue `await/v2.cljc:164` gates operational reader/writer handles for env prep — replacing it changes predicate semantics; not diagnostic summarization. |
   | Handoff store-key convention | `continuation_handoff.cljc:130` identifies resources blocking execution-state shipping. A lossy summary can't preserve resumability or replace this policy. |
   | Stream surface predicates | `stream/v2.cljc:194` defines protocol capabilities — dependencies of classification, not duplicates to remove. |
   | Bounds of 12, 12, 40 | Confirmed (`compilation_pipeline.cljs:454`, `continuation_stream.cljs:208,636`). Summarization can bound projected display data; frame projection/selection remains caller policy. |
   | Bounds of 200 and 500 | `continuation_stream.cljs:644` (recent history), `telemetry_viewer.cljs:147` (legacy, not a v2 requirement). Prefix summarization doesn't replace either policy. |
   | Unbounded operator notices | Confirmed (`driver.cljc:386`, `serve.cljc:348`, `connect.cljc:432`) — genuine consumers once boundedness defects are fixed. |
   | `flush-telemetry` | Belongs to legacy REPL (`repl.cljc:384`); not a live/v2 obligation. |
   | Dart request summary | `dart.cljd:156` projects host request into method/path, but has no string-length bound — calling it "correct bounded summarization" overstates the evidence. |

   The five numeric bounds are real, but "only one paired with a truncation marker" isn't substantiated — a displayed original count isn't an explicit truncation flag.

7. **Real gap — namespace relocation conflicts with the project's own naming guidance.** [dao.data.md:10](docs/design/dao.data.md:10) justifies a broad parent namespace for cross-cutting utilities, but `docs/agents/vocabulary.md:96` explicitly advises against generic names including "data." Existing files don't establish the proposed convention: `arrays.cljc:2` describes an implementation layer for the B-tree, `btree.cljc:19` directly requires it.

   **Reviewer's choice: `dao.summary`.** Names the narrow responsibility, avoids reserving a generic parent for unrelated utilities. `dao.data` is technically legal, no dependency cycle — the objection is cohesion/discoverability/consistency with stated guidance.

8. **Already correct as designed** — descriptor-first collection classification (all current v2 handle types implement it: `memory_log.cljc:79`, `ringbuffer.cljc:42`, `ws.cljc:155`), ordinary cursor handling (no predicate needed — `memory_log.cljc:104`, `ringbuffer.cljc:63`), and rejecting an `emit` wrapper. Note: the doc should explicitly declare `[dao.stream :as stream]` — the similarly-named predicate in `dao/stream.cljc:156` (v1) detects descriptor **maps**, not v2 handles.

**Before implementation:** revise the sequence guarantees, portable numeric representation, all variable-size leaves, exact stream schema, and boundary-case rules; restore the corrected evidence inventory; resolve the namespace naming exception. No files were edited by this review.

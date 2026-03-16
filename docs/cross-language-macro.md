# Cross-Language Macro Execution Strategy

## Background & Motivation
In the datom.world architecture, the Yin VM executes a language-agnostic Universal AST. `yang.clojure` provides `defmacro`, leveraging Clojure's homoiconicity to trivially write functions that transform ASTs at compile time. Non-homoiconic languages like Python (`yang.python`) lack native macro-writing facilities. However, because all languages compile down to the same Universal AST substrate, a Python program should be able to seamlessly invoke macros defined in Clojure (e.g., calling `defn` as if it were a regular function).

This plan outlines the architectural strategy to enable a non-homoiconic language compiler to emit macro calls, and how those calls are expanded using macros defined in another language.

## Scope & Impact
- Updates `yang.python` (and any future non-homoiconic compilers) to be macro-aware.
- Defines a universal contract for AST shapes passed into macros.
- Establishes a strict multi-phase compilation pipeline.

## Proposed Strategy
While `yang.clojure` is the preferred environment for writing cross-platform macros due to its homoiconic nature, the Universal AST substrate allows for macro authoring in any language that can produce the target AST structure.

### 1. Macro Discovery via DaoDB (Future Feature)
To emit a macro call, a compiler must know which names refer to macros rather than standard functions. Two distinct discovery problems exist:

**Cross-unit discovery (DaoDB query):** Non-homoiconic compilers (like `yang.python`) will query DaoDB for macros that have already been committed from prior compilation phases.

**Same-unit discovery:** A macro defined earlier in the same Python compilation unit is not yet in DaoDB and cannot be found by a DaoDB query. The compiler must track a local macro environment, updated as macro definitions are encountered, so that later call sites in the same unit can resolve against it. Cross-unit macros come from DaoDB; same-unit macros come from this local environment.

**Future Completion:** DaoDB is currently not feature-complete for this requirement. Both discovery mechanisms are features that will be completed in the future once DaoDB has the necessary capabilities.

### 2. Macro Authoring in Non-Homoiconic Languages
While `yang.clojure` provides a natural, concise way to write macros using backticks and splicing, non-homoiconic languages (like Python) can also define macros.
- **Direct AST API:** Non-homoiconic languages can use a specialized API to construct `yin.vm` macro ASTs directly. This API accepts native maps (or dictionaries) and uses them to build the corresponding Universal AST nodes.
- **Verbosity Trade-off:** Constructing Universal AST nodes directly is significantly more verbose than using Clojure's macro syntax. However, it ensures that any language can contribute to the shared macro library as long as it adheres to the Universal AST structural contract.

### 3. Call-Site Interception and AST Normalization in the Target Compiler
In non-homoiconic languages, macros usually look exactly like function calls. The compiler must intercept these and normalize arguments to canonical Universal AST shapes before emitting the macro call node.

- **Modification to `yang.python`:** During compilation, when `yang.python` encounters a function call (e.g., `defn("my_func", ["x"], x * 2)`), it must look up the identifier (`defn`) via the discovery mechanisms described above.
- **Normalization before emission:** The frontend is responsible for mapping source-language forms to canonical Universal AST nodes before constructing the `:yin/macro-expand` node. For example, a Python string argument `"my_func"` must be lowered to a `:variable` node; a Python list `["x"]` must be lowered to a `:literal` vector of `:variable` nodes. Macros must only ever receive Universal AST node shapes, never source-language-specific forms.
- **AST Emission:**
  - If the name is *not* in the registry, it emits a standard `{:type :application ...}` AST node.
  - If the name *is* in the registry, it emits a `{:type :yin/macro-expand, :operator <macro-ast>, :operands [<arg-asts>...]}` node where all operands are already canonical Universal AST nodes.
- **Operands are ASTs:** The operands passed into the `:yin/macro-expand` node are *unevaluated Universal AST nodes*. Normalization into canonical shapes is the frontend's responsibility; the macro expander and all macros operate solely on Universal AST.

### 4. Multi-Phase Compilation & Expansion Pipeline
The build/execution system must orchestrate multiple languages in a specific order. Because `e` (entity ID) and `t` (transaction ID) are stream-local coordinates, independently compiled streams cannot be concatenated directly. Each stream must be committed to DaoDB to obtain permanent positive EIDs before the streams can be composed.

1. **Macro Definition Phase:** Compile the `yang.clojure` macro libraries (like `stdlib-forms`). Commit the resulting datom stream to DaoDB. This assigns permanent EIDs to macro entities and makes them queryable for the next phase.
2. **Target Compilation Phase:** Compile the Python source using `yang.python`, querying DaoDB for cross-unit macros and the local macro environment for same-unit macros. Commit the resulting datom stream to DaoDB. This assigns permanent EIDs and resolves all tempids before merging.
3. **Stream Composition:** Compose the two committed datom streams into a single bounded stream. Because both streams have been committed, EIDs are permanent and non-overlapping and causal order is established via `t` and `m`.
4. **Expansion Phase:** Run the `yin.vm.macro/expand-all` process over the combined stream. The expander traverses the datoms, finds the `:yin/macro-expand` nodes emitted by Python, and executes the Clojure macro lambdas (which run natively in the Yin VM at compile time). Expansion is non-destructive: original call datoms are preserved unchanged. The expander appends new datoms for the expansion output and a provenance entity (`:macro-expand-event`) carrying `:yin/source-call`, `:yin/macro`, and `:yin/expansion-root`; expansion output datoms carry `m = event-eid`.
5. **Execution Phase:** Load the final, fully expanded datom stream into the Yin VM for standard execution.

## Verification
- Create a cross-language integration test (e.g., `python_macro_test.clj`).
- The test should compile a Clojure `defmacro` (committing it to a test DaoDB), compile a Python script calling that macro, run `expand-all`, and verify the Yin VM executes the resulting logic correctly.
- The test should also verify that original `:yin/macro-expand` call datoms are preserved alongside the expansion output datoms.

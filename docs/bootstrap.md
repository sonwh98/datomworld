# Yin.vm Perpetual Multi-Host Bootstrap Plan

## Summary
A stepwise plan to transition Yin.vm from a host-dependent interpreter (running in Clojure/ClojureScript) to a fully self-hosted, self-compiling environment. 

Unlike a traditional "Tombstone Bootstrap" where the host is discarded, Yin.vm follows a **Perpetual Multi-Host** architecture. The host-native interpreters (CLJ, CLJS, CLJD, Jank) remain first-class, permanent components of the ecosystem. 

The core logic (Compiler, Standard Library) is written once in Yin and can be executed by any host that implements the Yin CESK machine.

---

## Phase 0: Multi-Host Foundation (Perpetual)
Implement the Yin CESK machine natively in each target host language.
1. **CLJ/CLJS:** Existing `.cljc` implementation.
2. **CLJD (ClojureDart):** Native `.cljd` or optimized `.cljc` bridge for Flutter/Mobile.
3. **Jank:** C++ native implementation via Jank's LLVM integration.
4. **WASM:** A minimal, high-performance host for the browser and edge.
5. **Bidirectional dao.stream.apply:** First-class, stream-based interoperability (`call-in` and `call-out`) for zero-cost communication between the host and VM across all platforms.

**Goal:** Each host provides a native `(step vm)` function that operates on the Universal AST datoms.

---

## Phase 1: Yin Standard Library & Core Primitives
Before we can write a compiler in Yin, Yin must be a capable programming language.
1. **Core Data Structures:** Expose efficient primitives for vectors, maps, and sets so the Yin compiler can manipulate datoms and indexes without relying on Clojure's host collections.
2. **Standard Library (in Yin):** Write basic functions (map, reduce, filter, group-by) in Yin.
3. **Validation:** Execute these Yin functions across **all hosts** (CLJ, CLJS, CLJD, Jank) to ensure identical behavior.

---

## Phase 2: The Data-Driven Compiler Passes (In Yin)
Write the transformation passes in pure Yin. Because Yin operates on datoms, these passes take datom streams and return datom streams.
1. **Yin-in-Yin Frontend (`yang` in Yin):** Write a simple reader/parser in Yin that can take a string/stream of characters and output the canonical AST datoms.
2. **Yin-in-Yin Optimizer (CPS & Closure Conversion):** Write the Continuation-Passing Style (CPS) and Closure Conversion passes in Yin.
3. **Yin-in-Yin Codegen:** Write `ast-datoms->asm` in Yin.
   - For WASM: A pass that takes closure-converted datoms and emits a WASM binary byte array.
   - For Register VM: A pass that takes datoms and emits Yin Register VM bytecode.
4. **Validation:** Use the **CLJ Host** to execute the Yin-in-Yin compiler. Feed it its own source code and verify the output.

---

## Phase 3: The Cross-Host Bridge
This is the point where the self-hosted compiler begins to serve all hosts.
1. Take the source code of the **Yin-in-Yin Compiler** (from Phase 2).
2. Any host (CLJ, CLJS, CLJD, Jank) can now "load" the Yin-in-Yin compiler datoms.
3. The host's native VM executes the Yin-in-Yin compiler to compile other Yin programs.
4. **Result:** The "intelligence" of the compiler is now independent of the host language.

---

## Phase 4: Self-Hosting Verification
We prove the system is stable across all platforms.
1. Boot up the **Yin Compiler** inside the **Jank Host**.
2. Feed it the Yin source code for the compiler.
3. Boot up the **Yin Compiler** inside the **CLJD Host**.
4. Feed it the same source code.
5. **Validation:** Both hosts must produce byte-for-byte identical output (or structurally identical datom streams).

---

## Phase 5: The Operating System (Tao Kernel)
Once Yin.vm is self-hosted across multiple targets, it matures into a cross-platform operating system.
1. **Stream I/O:** Implement the `dao.stream` protocol bindings directly against each host's native networking and storage APIs.
2. **Capability Engine:** Implement the ShiBi token system natively across all hosts. (Status: Not implemented yet)
3. **Result:** Yin is now an autonomous, distributed operating system kernel where "files" are datom streams and "processes" are self-compiling continuations, running with native performance on JVM, JS, Dart, and LLVM/C++.
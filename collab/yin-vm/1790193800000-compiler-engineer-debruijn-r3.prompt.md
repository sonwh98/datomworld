Created-GMT: 2026-09-23 20:03:00 GMT
Created-Local: 2026-09-24 03:03:00 +0700
Coding-Agent: claude
Model: claude-opus-5-5

# Task: Implement Register VM Phase R3 (Benchmark Report)

Role: Yang Compiler and Universal AST Engineer

Work in `/Users/sto/workspace/worktree-debruijn-r3` (branch `debruijn-r3`).
Do NOT commit, push, or merge.

## Specification

Read first:
- `docs/design/yin.vm.debruijn.register.md` (specifically Section 6, "### R3: benchmark report", lines 968-980)
- `docs/design/datom.world.md`
- `test/bench/yin_vm_bench.cljc`
- `test/yin/vm/debruijn/register_test.cljc`
- `test/yin/vm/debruijn/stack_test.cljc`

### Objectives

Create `test/yin/vm/debruijn_register_benchmark_test.cljc`:
1. Implement the Phase R3 benchmark report comparing the real de Bruijn Stack VM (`yin.vm.debruijn.stack`) and real de Bruijn Register VM (`yin.vm.debruijn.register`) over identical pure-program workloads.
2. Workloads to benchmark:
   - `tail-countdown`: tail-recursive countdown testing loop throughput, tail-call dispatch, and frame reuse.
   - `fibonacci`: recursive tree calls testing call/return overhead, frame allocation, and binary arithmetic.
   - `lexical-binding`: nested let/lambda environment lookups testing bound variable access.
   - Parity corpus sample: representative pure-program expressions from `b0/parity-corpus`.
3. Metrics measured and reported:
   - **Throughput**: Execution time or iterations for identical pure-program runs.
   - **Lowering cost**: Time to lower resolved AST to stack image (`linearize/lower-stack`) vs register image (`register-compile/lower-register`).
   - **Image size**: Total instruction count and canonical encoded byte size (`dcode/encode-image` vs `rcode/encode-register-image`).
   - **Load time**: VM instantiation cost (`dvm/create-vm` vs `rvm/create-vm`).
   - **Step counts / Allocation**: Steps taken to complete the workload.
4. Correctness:
   - Every benchmark workload MUST assert B0-normalized equality between Stack VM and Register VM outcomes:
     `(= (b0/normalize (v2/value (v2/run (dvm/create-vm ...)))) (b0/normalize (v2/value (v2/run (rvm/create-vm ...)))))`.
5. Cross-platform test execution:
   - All tests must pass cleanly under `clojure.test` on JVM, CLJS (Node.js), and Dart (CLJD).
   - Use standard `#?(:clj ... :cljs ... :cljd ...)` reader conditionals where platform timing/measurement primitives differ, but ensure common benchmark data collection logic is portable.
6. Non-negotiable code invariants:
   - Strictly <= 80 columns per line.
   - 100% pure ASCII (no unicode quotation marks, arrows, or dashes).
   - Zero errors / warnings from `clj -M:kondo`.
   - Clean `cljstyle` formatting.
   - Do NOT edit existing files (`stack.cljc`, `register.cljc`, etc.).

Begin your response with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Model: claude-opus-5-5

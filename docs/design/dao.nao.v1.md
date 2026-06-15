# Design: `dao.nao` v1 — GPU GEMM as a `dao.stream` kernel effect

## Summary

`dao.nao` (see [dao.nao.md](dao.nao.md)) is a datomworld-native inference runtime
in which the heavy tensor math belongs to vendor kernels, while `yin.vm` and
`dao.stream` own scheduling, causality, and inference meaning. That document
describes the whole runtime. This document specifies **v1**: the smallest real
increment of it.

v1 implements exactly one kernel effect — **matrix multiplication (GEMM)** —
end to end:

```text
caller emits a kernel effect (a matmul request)
-> dao.stream carries it across a stream boundary
-> a vendor-native backend interprets it (cuBLAS now, rocBLAS later)
-> the existing GPU GEMM kernel runs (no kernel of our own)
-> the product comes back as a single-item kernel-result stream
```

GEMM is the first item in `dao.nao`'s list of subordinate kernel operations
("matrix multiplication, attention, softmax, normalization, ..."). It is the
correct seed: it is the simplest op that is unambiguously GPU work, it is
hand-verifiable, and it forces every architectural boundary the full runtime
needs — the effect/result vocabulary, the kernel-as-subordinate-interpreter
seam, and the vendor boundary — without yet requiring the scheduler, KV-cache,
or batching.

## Core Claim

**Vendor independence lives at the `dao.stream` boundary, not in the kernel.**

The conventional way to be vendor-neutral is to pick a portable kernel
(OpenCL/clBLAST) and pay for it everywhere — on NVIDIA that means no Tensor
Cores, no cuBLAS/cuDNN/FlashAttention, and a driver path NVIDIA barely
maintains. For an inference runtime, where low-precision matmul dominates, that
tax is large.

`dao.nao` does not need that compromise. A matmul is a *request on a stream*;
which vendor-native kernel *interprets* that request is hidden behind
`dao.stream`. So each vendor gets its fastest native kernel and the caller stays
vendor-agnostic:

- NVIDIA is interpreted by **cuBLAS** (via Neanderthal's CUDA engine in v1; see
  below for the Tensor Core path).
- AMD is interpreted by **rocBLAS / hipBLAS** (`hipblasSgemm`).
- The caller only ever sees `(ds/open! {:type :gpu/gemm ...})`.

A note on Tensor Cores: v1's `:cuda` method runs FP32 GEMM (cuBLAS `Sgemm` under
Neanderthal's `mm`), which on its own does **not** engage the Ada Tensor Cores.
Actual Tensor Core utilization requires the TF32 math mode or FP16/FP8 inputs
(`cublasGemmEx` / `cublasLtMatmul`) — reachable later either through Neanderthal's
half-precision support or a direct cuBLAS-Lt call behind the same seam. That is
deferred — v1 proves the boundary, not peak throughput. The point that survives
is the *architecture*: the fast vendor path stays reachable behind the same seam,
unlike OpenCL, which forecloses it entirely.

This is the datomworld pattern directly: "all IO is a stream", "side effects
appear as stream emissions", "interpretation > abstraction", and "kernels are
subordinate interpreters". It mirrors the existing `dao/stream/http.cljc`
transport, where a request yields a single result on a stream.

## Confirmed Environment

- GPU: NVIDIA GeForce RTX 4070 SUPER; driver 580.159.03 (supports CUDA 12.9).
- JDK 21, `mise`-managed.
- **CUDA is supplied by Neanderthal, not the system.** Neanderthal's CUDA engine
  (cuBLAS + ClojureCUDA) gets its CUDA toolkit and cuBLAS natives through
  `org.bytedeco/cuda` Maven jars — currently **CUDA 12.6 / cuDNN 9.5**
  (`12.6-9.5-1.5.11`, the latest bytedeco release; there is no 12.9 jar). So the
  build does not depend on the system CUDA install, and no `mise`/`uv` CUDA
  provisioning is needed. The only system requirement is a recent NVIDIA driver,
  which 580.159.03 satisfies (it supports CUDA 12.6 and beyond).

## Shape

```text
yin.vm / Clojure caller
   └─ (ds/take!! (ds/open! {:type :gpu/gemm :a A :b B}))     ; vendor-agnostic
        └─ dao.stream.gemm   (ds/open! defmethod)            ; the stream boundary
             └─ gemm-backend  (multimethod — interpreter seam)
                  ├─ :cuda → cublasSgemm    (NVIDIA, implemented in v1)
                  └─ :rocm → hipblasSgemm   (AMD, stub in v1, same signature)
   ← single-item kernel-result stream:
        {:result <product> :shape [m n] :backend :cuda :device :cuda:0}
```

The interpreter seam (`gemm-backend`) is the single point a new vendor is added.
Callers, the stream descriptor, and the result shape never change when AMD is
added — only a new method is registered. That is what makes the independence
real rather than aspirational.

## Effect / Result Vocabulary

v1 specializes `dao.nao`'s kernel-effect facts to GEMM. The runtime-facing form
(when later emitted by `yin.vm` continuations) is datom-shaped, reusing the
attributes reserved in `dao.nao.md`:

```clojure
;; the effect
[effect-id :inference/kernel-effect true        t m]
[effect-id :kernel/op                :kernel/gemm t m]
[effect-id :kernel/input-a           a-buffer-id  t m]
[effect-id :kernel/input-b           b-buffer-id  t m]
[effect-id :kernel/backend           :cuda        t m]   ; optional; else auto

;; the result
[result-id :inference/kernel-result  true        t m]
[result-id :kernel/result-for        effect-id   t m]
[result-id :kernel/product           c-buffer-id  t m]
[result-id :kernel/shape             [m n]        t m]
[result-id :kernel/device            :cuda:0      t m]
```

In v1 the *transport* form is the plain descriptor `{:type :gpu/gemm :a A :b B
:backend ...}` carried by `dao.stream`; the datom projection above is what the
later scheduler/kernel-effect loop will emit and replay. Keeping the descriptor
keys aligned with the attribute names now means the projection is mechanical
later (`:a` → `:kernel/input-a`, `:b` → `:kernel/input-b`, `:backend` →
`:kernel/backend`).

### The result stream is total: one item, success or failure

The single-item stream must always emit exactly one value — never throw past the
stream boundary — so the contract stays total, matching `dao/stream/http.cljc`
(which emits `{:status 0 :error {:kind ... :message ...}}` rather than throwing).
GEMM emits either a success map or an error map:

```clojure
;; success
{:result <flat-product> :shape [m n] :backend :cuda :device :cuda:0}

;; failure (device, dimension, allocation, or unimplemented backend)
{:error {:kind    :cuda/device-not-found   ; | :cuda/oom
                                            ; | :gemm/dimension-mismatch
                                            ; | :backend/unimplemented
         :backend :cuda
         :message "..."}}
```

The datom projection of the failure form reuses `:inference/error` and
`:kernel/result-for` so a failed kernel effect is still a first-class,
replayable fact. The `:rocm` stub therefore returns an `{:error {:kind
:backend/unimplemented ...}}` map rather than raising.

## Implementation

No `mise`/`uv` CUDA provisioning is needed — Neanderthal brings its own CUDA.

### 1. `deps.edn` — `:gpu` alias

```clojure
:gpu {:extra-paths ["src/dev"]
      :extra-deps {org.uncomplicate/neanderthal-cuda {:mvn/version "0.62.0"}
                   ;; redist jars only if a self-contained (no system CUDA)
                   ;; build is wanted — see note below:
                   org.bytedeco/cuda
                   {:mvn/version "12.6-9.5-1.5.11"
                    :classifier "linux-x86_64-redist"}
                   org.bytedeco/cuda
                   {:mvn/version "12.6-9.5-1.5.11"
                    :classifier "linux-x86_64-redist-cublas"}}
      :jvm-opts ["--enable-native-access=ALL-UNNAMED"]
      :main-opts ["-m" "gpu.matmul"]}
```

> **Versions confirmed against Clojars/Maven at time of writing — re-confirm at
> implementation time:** `neanderthal-cuda` latest is `0.62.0`; `org.bytedeco/cuda`
> latest is `12.6-9.5-1.5.11` (CUDA 12.6 / cuDNN 9.5 / javacpp 1.5.11) — no 12.9
> jar exists.

Two things to resolve when implementing, **before** pinning the bytedeco jars:

- **Are the explicit bytedeco deps even needed?** `neanderthal-cuda` depends on
  `uncomplicate/clojurecuda`, which pulls the `org.bytedeco/cuda` *bindings* jar
  transitively. Those bindings load CUDA from **either** a system install **or**
  the bytedeco *redist* classifier jars. This box already has system CUDA 12.6,
  which matches the bytedeco 12.6 bindings — so the explicit redist entries may
  be **redundant** (and risk a version conflict with what Neanderthal expects).
  Try the alias with *no* explicit bytedeco deps first; add the `redist` /
  `redist-cublas` jars only if a self-contained build (no reliance on system
  CUDA) is required.
- **Pin to whatever Neanderthal expects.** If the redist jars are kept, match
  their version to the bytedeco version `clojurecuda 0.x` actually depends on
  (check its pom), not just "the latest", to avoid a mismatch.

JDK 21 needs `--enable-native-access` to permit the native calls without
warnings.

### 2. New transport: `src/clj/dao/stream/gemm.clj`

Follows the `dao/stream/http.cljc` pattern, but is `.clj`, not `.cljc`: every
backend is JVM-native GPU code, so there are no real `:cljs`/`:cljd` branches.
`http.cljc` earns its extension with genuine per-platform HTTP clients; gemm
would only carry `#?(:clj ...)` guards around everything, which the `.clj`
extension states more honestly. A future CPU/WASM fallback would be a *new
backend behind the seam*, not a platform branch — so it would not change this
decision.

- `defmethod ds/open! :gpu/gemm [descriptor]` — make a 1-slot ringbuffer, run the
  matmul, `put!` the result map (success or error — see the total-stream
  contract above), `close!`, return the stream. Synchronous is fine for v1.
- `gemm-backend` multimethod (the interpreter seam), dispatching on
  `(:backend descriptor)`, defaulting to auto-detect (`:cuda` when a CUDA device
  is present).
- `:cuda` method (Neanderthal): within `with-default` (ClojureCUDA context) and
  `with-default-engine`, build GPU matrices with `cuda-float` and multiply with
  `mm`; transfer the product back to the host and flatten it for the result map.
  Neanderthal handles the column-major layout internally, so no manual transpose
  juggling — but the *host readback* is where column-major shows: `transfer`
  yields a host matrix, and `seq` on a GE matrix yields a sequence of **columns**,
  not a flat row-major array, so flattening must be explicit and order-correct.
  Resource cleanup is deterministic via `with-release` (it releases the GPU
  matrices even on throw) — the Neanderthal analogue of `try`/`finally`. Wrap the
  whole thing in a `try`/`catch` that converts any failure (no device, dimension
  mismatch, OOM) into the `{:error {...}}` result map rather than letting it
  escape the stream. Sketch (API to confirm against Neanderthal 0.62):

  ```clojure
  (with-default
    (with-default-engine                 ; binds the default CUDA factory
      (with-release [a (cuda-float m k A-flat)   ; A-flat: source data, dims m×k
                     b (cuda-float k n B-flat)
                     c (mm a b)]
        (let [host (transfer c)           ; GPU → host matrix
              ;; host->flat-row-major: a LOCAL helper to write in this ns
              ;; (not a Neanderthal fn) — flattens the host matrix to a
              ;; row-major vector, accounting for column-major layout.
              flat (host->flat-row-major host)]
          {:result flat
           :shape  [m n] :backend :cuda :device :cuda:0}))))
  ```

  Open API points to verify at implementation time: that `cuda-float` resolves
  the default factory inside `with-default-engine` (vs. needing an explicit
  factory arg), the exact data-shape `cuda-float` expects for its source, and the
  precise host-readback call (`transfer` vs. `native`) and flattening.
- `:rocm` method: returns `{:error {:kind :backend/unimplemented :backend :rocm
  :message "rocBLAS backend not yet implemented"}}` (not a throw) with the
  identical signature, so AMD slots in without touching callers or breaking the
  total-stream contract.

### 3. Example / driver: `src/dev/gpu/matmul.clj`

Namespace `gpu.matmul`. `-main`:

- Build A (2×3) and B (3×2) with hand-checkable values.
- Call `(ds/take!! (ds/open! {:type :gpu/gemm :a A :b B}))` — the vendor-agnostic
  request.
- Branch on the total result: on `:error`, print the `:kind`/`:message` and exit
  non-zero; on success, print the 2×2 product and a `PASS`/`FAIL` cross-check
  against a plain JVM matmul.

## Verification

1. `clj -M:gpu` — resolves Neanderthal (and the bytedeco CUDA natives, whether
   transitive or via the explicit redist jars — a large one-time download on
   first run), compiles, and runs `gpu.matmul/-main`.
2. Expect the printed 2×2 product and `PASS`. A clean run implies real GPU
   execution; a missing device or CUDA library surfaces as an `{:error ...}`
   result map (the stream stays total), which the driver prints and exits
   non-zero on. Optionally observe `nvidia-smi` during a looped run.

## Scope

v1 **is** responsible for:

- one kernel op (`:kernel/gemm`) interpreted by a vendor-native backend,
- the `dao.stream` request → kernel-result-stream loop for that op,
- the `gemm-backend` interpreter seam with a working `:cuda` method (Neanderthal
  / cuBLAS) and an AMD-ready `:rocm` stub,
- relying on Neanderthal's bundled CUDA rather than the stale system install.

v1 is **not** responsible for (deferred to later `dao.nao` increments):

- the continuation scheduler, batching, or stream injection,
- KV-cache identity/lifecycle (`:cache/*` facts) or VRAM buffer ownership,
- attention/softmax/normalization kernels,
- distributing kernel invocations across nodes,
- the full `:inference/*` event vocabulary beyond `kernel-effect` /
  `kernel-result`.

## Migration Notes

- **AMD `:rocm` backend.** v1's `:cuda` method uses Neanderthal (cuBLAS); the
  AMD method lands behind the same seam as a native rocBLAS/hipBLAS binding
  (Neanderthal has no rocBLAS engine — its AMD path is OpenCL/clBLAST, which we
  reject for the perf reasons in the Core Claim). Two ways to bind rocBLAS when
  that work starts: a JNI/Java-rocBLAS binding, or Java's Foreign Function &
  Memory API (`java.lang.foreign`) to `dlopen librocblas.so` and call
  `hipblasSgemm` directly (FFM is preview in JDK 21, `--enable-preview`). Either
  way the caller and `ds/open!` descriptor are unchanged. (If a *unified* interop
  style across both vendors ever becomes desirable, FFM could also replace the
  Neanderthal `:cuda` method by `dlopen`-ing `libcublas` — but there is no reason
  to until then; Neanderthal handles NVIDIA well.)
- **VM effect exposure.** Once stable, register `:gpu/gemm` as a VM effect via
  `module/register-effect-handler!` (the mechanism `yin.io.file-input-stream`
  uses) so `yin.vm` continuations emit matmuls as stream effects, completing the
  effect → result datom loop described in `dao.nao.md`.
- **Shared vendor dispatch.** v1's `gemm-backend` multimethod is internal to the
  gemm transport. As more kernel ops arrive (attention, softmax, normalization),
  each defining its own per-op backend multimethod would scatter vendor
  selection across transports. Before the second op lands, promote vendor
  dispatch to a shared construct — e.g. a `dao.nao.kernel.backend` namespace with
  one documented `(dispatch op vendor descriptor)` contract, or a single
  multimethod keyed on `[op vendor]` — so a new vendor is registered once, not
  once per op. v1 keeps it local deliberately (one op, no premature abstraction),
  but this is the first refactor when op #2 appears.
- **Buffer handles and the by-value ceiling.** v1 passes matrices by value
  through the descriptor (`:a`/`:b`). This is fine for the test and any small
  op, but it is a **throughput ceiling, not just a simplification**: each call
  pays a host→device transfer of the full Java float arrays into fresh
  `cuda-float` buffers (e.g. a 4096×4096 FP32 matrix is ~64 MB copied per
  invocation, per operand), so by-value can never reach device-resident
  performance. The full runtime references device
  buffers by handle (`:kernel/input-a` → `a-buffer-id`), keeping tensor bytes in
  VRAM across ops per `dao.nao.md` §7. The result map's
  `:result`/`:shape`/`:device` keys are the by-value precursor of those handle
  facts; moving to handles is what lifts the ceiling.

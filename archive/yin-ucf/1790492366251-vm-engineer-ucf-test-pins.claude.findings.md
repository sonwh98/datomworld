# UCF test pins (claude CLI, session 3cfc40a5)

## Round 1 (process exited early; work complete, report truncated)

I'll wait for the background compile task's completion notification.


## Round 2 (resumed; verification and report complete)

Completed-GMT: 2026-09-27 15:32:00 GMT
Completed-Local: 2026-09-27 22:32:00 +0700

Five pins added to `test/yin/vm/ucf_test.cljc` (only file touched):

1. **`:reasons` for `:park`/`:ffi-call` in the static safepoint map** — `safepoint-reasons-for-park-and-ffi-call-test` (test/yin/vm/ucf_test.cljc:437). Asserts `ucf/safepoints` on `[[:park] [:halt]]` yields reasons `[:park]`, and on `[[:ffi-call :op/add 0] [:halt]]` yields `[:ffi :ffi-request]` — pinning `parking-reasons` (src/cljc/yin/vm/ucf.cljc:273-282).

2. **FFI-retained dynamic conformance row (`:dao.stream/full`)** — `ffi-call-retained-conformance-test` (test/yin/vm/ucf_test.cljc:651). Loads batch-a on a one-slot-writer VM, steps its `:ffi-call` to sent/blocked (`:reason :next`), then loads a second segment (via new `segment-b`/`instruction-b` helpers, a distinct segment id so it doesn't collide with batch-a's stored image) on the *same* vm and steps its `:ffi-call`. Asserts the second is retained: `wait-set` grows to 2 entries, the new entry has `:request-sent true`, `:reason :put`, `:op :op/add` — pinning semantic.cljc:458-473's `:dao.stream/full` branch.

3. **`canonicalize`'s rethrow of non-UCF exceptions** — `canonicalize-rethrows-non-ucf-exceptions-test` (test/yin/vm/ucf_test.cljc:392). `(conj (worked-segment) :not-a-datom)` makes `code/well-formed?`'s `index-batch` destructure a bare keyword as `[e a v]`, throwing a raw (non-`ex-info`) exception. Asserts `canonicalize` doesn't swallow it (something is thrown, and its `ex-data`'s `:yin.k/status` is nil) — pinning the `(throw e)` fallthrough at ucf.cljc:229-233.

4. **`load-image` error precedence: refusal before hash-mismatch** — `load-image-refuses-before-checking-a-claimed-hash-test` (test/yin/vm/ucf_test.cljc:207). A batch claims a hash but also carries `:yin.code/entry` (a segment-attribute violation from the existing `non-canonicalizable-test` fixture). Asserts `semantic/load-image` throws the UCF refusal (`:yin.k/non-portable`, `:segment-attribute`) rather than `:hash-mismatch` — pinning semantic.cljc:628-643, which canonicalizes before ever comparing hashes.

5. **Multi-frame `:yin.k/stack-bases` ordering** — `activation-state-multi-frame-stack-bases-test` (test/yin/vm/ucf_test.cljc:722). A frame with three `:k` entries (`:stack-base` 0, 2, 5) asserts `activation-state` returns `[0 2 5]` — pinning the plain in-order `mapv` at ucf.cljc:392, outermost-first/innermost-last.

Lane counts:
- JVM (`clj -M:test`): 2277 tests / 183338 assertions / 0 failures, 0 errors (baseline 2272/183324/0 + these 5 tests/14 assertions).
- Node/CLJS (`mise exec -- clj -M:cljs ... compile slice-peer test`): 2185 tests / 49891 assertions / 0 failures, 0 errors; `Testing yin.vm.ucf-test` present.
- Dart/CLJD (`mise exec -- bb test:cljd`, after `rm -rf test/cljd-out`): "All tests passed!" at +2147; 23 assertions logged under `yin/vm/ucf-test_test.dart`.

Style/lint: `mise exec -- cljstyle check test/yin/vm/ucf_test.cljc` — clean (no output). `mise exec -- clj -M:kondo --lint test/yin/vm/ucf_test.cljc` — 0 errors, 0 warnings. File is pure ASCII, no line exceeds 80 columns.

Status: COMPLETE

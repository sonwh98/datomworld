Created-GMT: 2026-11-13 12:11:28 GMT
Coding-Agent: codex
Role: Engineer
Status: complete; include acceptance green

Implemented the PHP siteset linker stage with verified canonical rows, signed discovery, a write-buffered bottom-up dependency closure, rebinding of non-runtime requirements (including SCC alias bundle pins), and materialized derivation records whose output is the published tree and whose profile retains the full published site-set pin. Derived module trees actually require the pinned php.sp hook. The hook delegates to an exported PHP runtime callable, preserving the shared tick state. The linked fixture composition supplies signed site entries and the stage and derives the entry under the ticks profile. Module lookup now supports a linked dotted child under an existing parent, including enumeration and dependency-pin checks; the entry loads yang.call and php before php.sp.

Verification:
- include-handoff-test: 1 test / 3 assertions, all LT, 276.0 s (600 s limit).
- tick-fixtures-test: 1 test / 8 assertions, green.
- siteset-test: 7 tests / 30 assertions, green (includes missing-address and derived alias closure/record checks).
- yang.safepoint-test: 9 tests / 20 assertions, green.
- yin.vm.module-test plus the siteset and safepoint suites: 26 tests / 113 assertions, green.
- clj-kondo on all seven touched source/test files: 0 errors / 0 warnings.
- A small linked hook probe returned 42 on semantic, stack and register.

Implementation commits pushed in sequence: edd24b6e, 64246023, f7726e49, 641b4cf1, fd733802. Statement selection and registry fixtures were not changed.

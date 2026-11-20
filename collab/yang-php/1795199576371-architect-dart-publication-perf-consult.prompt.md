Created-GMT: 2026-11-20 18:32:56 GMT
Created-Local: 2026-11-21 01:32:56 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: codex
Session-ID: 01a1fa3c-d6ef-7700-82f8-7d69ddfe3ead (codex thread.started, jupiter POOL 2 — failed mid-turn on usage limit)

# Task: Architect consult on Dart 4M-prelude publication performance and per-row make-published reuse

Role: Lead System Architect

Implementers:
- Model: gpt-6.2-astra (jupiter POOL 2) | Assigned: 2026-11-21 01:32:56 +07 (Asia/Ho_Chi_Minh) | Status: active | Rationale: Read-only Architect consult authorized by owner at Dart-leg two-strikes boundary, co-located in jupiter:~/ci/dw-dartleg
- Status-Event: 2026-11-21 01:38:15 +07 (Asia/Ho_Chi_Minh) | Model: gpt-6.2-astra (jupiter POOL 2) | Status: failed | Rationale: ChatGPT usage limit hit mid-turn after item_26 (resets 3:24 PM EST; thread 01a1fa3c-d6ef-7700-82f8-7d69ddfe3ead); re-routed to laptop codex POOL 1 via r2 prompt

## Owner instruction provenance

Verbatim owner decision at the Dart-leg two-strikes boundary:
> "(a) Dispatch a read-only Architect consult on Dart 4M-prelude publication performance and per-row make-published reuse"

Strict read-only consultation inside `/home/sto/ci/dw-dartleg` (branch `feat/dart-leg-linked-fixtures` @ `657f01ff`, with uncommitted `r1`/`r2` edits intact). Do **NOT** edit any files. Do **NOT** run `flutter test` or any suite-scale tests.

## Read first (under `/home/sto/ci/dw-dartleg` + `/tmp/dartleg-r2-agg.out`)

- `docs/design/datom.world.md`
- `docs/design/yang.php.md` (section 11)
- `src/cljc/dao/stream/base64.cljc` (and `git diff src/cljc/dao/stream/base64.cljc`)
- `test/dao/stream/base64_test.cljc` (and `git diff test/dao/stream/base64_test.cljc`)
- `test/yang/php/antlr/linked_harness.cljc` (and `git diff test/yang/php/antlr/linked_harness.cljc`)
- `test/yang/php/antlr/dart_linked_test.cljc`
- `test/yang/php/antlr/dart_linked_packets.cljc`
- `src/cljc/yang/php/antlr/prelude.cljc`
- `src/cljc/yang/php/antlr/prelude_module.cljc`
- `src/cljc/yang/php/antlr/publication.cljc`
- `src/cljc/yang/php/antlr/siteset.cljc`
- `src/cljc/yin/vm/linker/publish.cljc`
- `src/cljc/dao/jing.cljc`, `src/cljc/dao/jing/cbor.cljc`, `src/cljc/dao/jing/mem.cljc`
- `collab/yang-php/1795099946000-engineer-dart-leg-r1.gpt-6.1-terra.findings.md`
- `collab/yang-php/1795106342000-engineer-dart-leg-r2.gpt-6.1-terra.findings.md`
- `/tmp/dartleg-r2-agg.out` (specifically around lines 1230 and 5154–5168 showing 3 rows x 3 backends = 9/9 passed on Dart: `:inc-mutual-once`, `:dyn-shares-locals`, `:dyn-does-not-touch-globals`, all `status 0 fatal nil`, before hitting `177:14` / SIGTERM during row 4 `:dyn-param-and-return`)

## Context summary (verify directly in source/logs; treat prior notes as claims)

- Round 1 (`gpt-6.1-terra`) added the `:runtime` option in `linked_harness.cljc` (replacing JVM-only `with-redefs`) and `dart_linked_test.cljc` + `dart_linked_packets.cljc` (17 rows).
- Round 2 (`gpt-6.1-terra`) replaced `dao.stream.base64/text-pattern` regex with an iterative scan in `src/cljc/dao/stream/base64.cljc` after the Dart `RegExp` threw Stack Overflow on the ~4M-char PHP prelude content-pair payload (which caused `:absent` link refusal).
- When the seat ran `bb src/dev/cljd_agg.clj` unsandboxed on `jupiter`, Dart publication of the PHP prelude completed 3 times and 3 rows (`:inc-mutual-once` via `@linked/published`, `:dyn-shares-locals` via `(linked/make-published)`, `:dyn-does-not-touch-globals` via `(linked/make-published)`) passed across `:register`, `:stack`, and `:semantic` (9/9) before timing out/freezing at ~3h (`177:14`) inside row 4 (`:dyn-param-and-return`), which was the 3rd consecutive `:dynscope-v1` row calling `(linked/make-published)`.

## Required Rulings (answer all 4 explicitly with `file:line` evidence)

1. **Dart 4M-prelude publication hot-path bottleneck:** Why does `(linked/make-published)` take tens of minutes on Dart vs seconds on JVM? Trace `publish/publish-module!` -> `prelude/module-spec` -> `siteset` / linearization / `dao.jing.cbor` / `dao.jing` BLAKE3 / `dao.stream.base64` / `dao.jing.mem` on `cljd`, and identify the exact `file:line` algorithmic/data-structure bottlenecks (including e.g. Dart `Uint8List` / `String` / CBOR / BLAKE3 / content-pair base64 / `put!` loops).
2. **Per-row `(linked/make-published)` in `dart_linked_test.cljc`:** Why did `:dynscope-v1` (6 of 17 rows) call `(linked/make-published)` fresh per row? Under `docs/design/datom.world.md` and `test/yang/php/antlr/linked_harness.cljc` (fresh `link/make-pair`, fresh VMs, content-addressed `dao.jing.mem`), can all 17 rows share one published `php` prelude (`@linked/published`, or a cheap per-row overlay/copy over the shared published prelude store) without violating isolation, or is there real mutable/state leakage that originally motivated `with-redefs` on JVM? Compare `linked_harness.cljc` with the JVM `with-redefs` test (`linked_test.clj` / `dynscope` fixtures) to verify.
3. **Assessment of the uncommitted `src/cljc/dao/stream/base64.cljc` constant-stack fix:** Audit `valid?` for exact equivalence with `text-pattern` and edge cases (empty string, trailing `=` / `==` padding rules and valid alphabet before padding, non-ASCII/UTF-16 characters, `nth` / `count` complexity on Dart strings, and `encode`/`decode` symmetry). Rule whether this Layer-0 fix is architecturally sound and ready to land independently (once reviewed/tested).
4. **Recommended next path & acceptance criteria:** Recommend the minimal, invariant-preserving path to close the Dart leg (or defer cleanly), separating architectural defects from harness/performance optimizations and stating explicit verification criteria for any follow-up round.

## Output contract

Do NOT edit files. Evaluate foundational invariants, ownership boundaries, explicit state and control flow, host isolation, CLJ/CLJS/CLJD portability, migration risk, and completion criteria.

Begin your final response **exactly** with:

```text
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
```

Use `severity | file:line | invariant/evidence | recommended correction` for findings, and answer Questions 1–4 in structured sections.

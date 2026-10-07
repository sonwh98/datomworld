Created-GMT: 2026-09-23 16:37:17 GMT
Created-Local: 2026-09-23 23:37:17 +07 (Indochina Time)
Coding-Agent: claude
Session-ID: 1ff9efa8-f088-467a-8507-c94697603dc5

# Task: debruijn-type-preservation -- Universal AST Contract Version 2

Role: Yang Compiler and Universal AST Engineer

Implementers:
- Model: claude-sonnet-5 | Assigned: 2026-09-23 23:37:17 +07 | Status: active | Rationale: AST canonicalization, scalar classification, and de Bruijn projection contract migration per Universal AST and compiler domain scope.

Work in /Users/sto/workspace/worktree-debruijn-type-preservation (your launch directory; branch debruijn-type-preservation, HEAD 1275a5df). Do NOT stage, commit, merge, or push.

---

## Context & Governing Documents

Read these governing documents completely first:
1. `docs/design/datom.world.md` (authoritative, especially Section on "Host Boundaries: host types stay in transforms/adapters; no host type or host quirk crosses onto the stream or contaminates universal representations")
2. `docs/design/yin.vm.debruijn-projection.md` (authoritative contract specification for `:yin.debruijn/*`)
3. `src/cljc/yin/vm/debruijn.cljc` (source implementation of de Bruijn projection)
4. `test/yin/vm/debruijn_test.cljc` (test suite and pinned hash contracts)

### Owner Directive & Problem Statement
The repository owner identified an architectural layer violation in Contract Version 1:
> "JavaScript has no runtime distinction between 1 and 1.0: this in the js layer. it should not be in the de bruijn projection layer"
> "I think yin.vm's debruijn projection should not be lossy by design with respects to type. is there a reason why it is?"
> "yes prioritize type perservation migration now"

In Contract Version 1, `yin.vm.debruijn` intentionally folded integral doubles (e.g. `1.0`) into `int64` (`1`). This accommodated JavaScript's lack of integer/float distinction at runtime, but baked a host-specific limitation into the universal content-addressed AST specification. Under `datom.world.md`, host quirks must remain in host boundary transforms/adapters; the universal de Bruijn projection must be a pure, type-preserving mathematical representation.

---

## File Box (Strictly Bounded)

Allowed edits:
- **EDIT**: `src/cljc/yin/vm/debruijn.cljc` (canonical value table, numeric classification, contract version)
- **EDIT**: `test/yin/vm/debruijn_test.cljc` (pinned contract hashes, distinct int/double assertions)
- **EDIT**: `docs/design/yin.vm.debruijn-projection.md` (update specification to Contract Version 2)
- **EDIT**: `public/chp/blog/yin-vm-vs-unison.blog` (update references to Contract Version 2)

Do NOT edit files outside this file box.

---

## Acceptance Criteria

### 1. Contract Version 2 in `src/cljc/yin/vm/debruijn.cljc`
- Define `(def contract-version 2)` and include `[:yin.debruijn/dimension :dim/contract-version contract-version]` in `descriptor`.
- Update `canonical-value-table`:
  - Set `:integral-double-folding false`.
  - Remove `:int64-integral-double-collision` from `:declared-limits`.
  - Ensure `:int64` and `:double` are disjoint scalar types.
- Fix `numeric-class` and `double-class`:
  - On JVM and Dart: Any `Double` or `Float` is classified as `:double`. It is NEVER coerced or folded to `:int64`. `1.0` produces `:double`, `1` produces `:int64`.
  - On CLJS: Safe integer values classify as `:int64` and non-integers as `:double`. When reading/decoding foreign records from the stream claiming `:yin.debruijn/type :double` for an integral value (or vice versa where unsupported), refuse at the boundary with `:unsupported-value`.
- Collections:
  - `{1 :a, 1.0 :b}` must not collide under `canonical-value` on JVM and Dart.
  - `#{1 1.0}` must not collide under `canonical-value` on JVM and Dart.

### 2. Pinned Hashes & Tests in `test/yin/vm/debruijn_test.cljc`
- Re-pin the descriptor hash in `test/yin/vm/debruijn_test.cljc` to the new Contract Version 2 hash.
- Re-pin the essay fingerprint hash to its Contract Version 2 root hash.
- Assert that `(project (lam '[x] (app (v '+) (v 'x) (lit 1))))` and `(project (lam '[x] (app (v '+) (v 'x) (lit 1.0))))` produce DISTINCT fingerprints and distinct record content on JVM and Dart.
- Ensure all 78 tests in `yin.vm.debruijn-test` pass.
- Ensure `yin.vm.pipeline-test` passes.

### 3. Documentation in `docs/design/yin.vm.debruijn-projection.md`
- Document Contract Version 2 changes: `:integral-double-folding false`, `:int64` and `:double` disjoint.
- Explain the architectural boundary: why JS IEEE-754 limitations remain in JS adapters and do not contaminate the universal de Bruijn projection.
- Keep typography strictly pure ASCII, line lengths <= 80 columns.

### 4. Code Standards & Parity
- Pure ASCII only (no non-ASCII unicode quotation marks or symbols).
- Line length strictly <= 80 columns.
- Zero Kondo warnings (`clj -M:kondo`).
- Cljstyle check clean (`cljstyle check`).
- Tri-host verification: `bb test:clj`, `bb test:cljs`, and `bb test:cljd`.

---

## Verification

Before declaring completion:
1. Run tests in `/Users/sto/workspace/worktree-debruijn-type-preservation`:
   ```sh
   clojure -M:test -n yin.vm.debruijn-test
   clojure -M:test -n yin.vm.pipeline-test
   bb test:clj
   bb test:cljs
   bb test:cljd
   ```
2. Lint and format checks:
   ```sh
   clj -M:kondo --lint src/cljc/yin/vm/debruijn.cljc test/yin/vm/debruijn_test.cljc
   cljstyle check src/cljc/yin/vm/debruijn.cljc test/yin/vm/debruijn_test.cljc
   ```
3. Check line lengths and ASCII:
   Ensure no lines > 80 columns and strictly pure ASCII.

---

## Response Format

Begin your response exactly with:
```text
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: claude
Session-ID: 1ff9efa8-f088-467a-8507-c94697603dc5
```

Report:
1. Changed files and diffstat.
2. Exact test outcomes and assertion counts across JVM, CLJS, and CLJD.
3. Updated descriptor and essay fingerprint hashes.
4. Evidence of pure ASCII, <= 80 columns, and zero kondo/cljstyle warnings.

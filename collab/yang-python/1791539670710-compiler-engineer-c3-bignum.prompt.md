Created-GMT: 2026-10-09 09:54:30 GMT
Created-Local: 2026-10-09 16:54:30 ICT
Coding-Agent: claude
Session-ID: e517f558-c063-45d3-9f21-9e5265a48dd3

# Task: yang.python Phase C3 Slices S3–S5 (Exact Integers: BigInt Literals, Promotion/Demotion & Operations)
Role: Compiler Engineer
Implementers:
- Model: claude-sonnet-5-5 | Assigned: 2026-10-09 16:54:30 ICT | Status: active | Rationale: Lead Compiler Engineer for Track 2 / Phase C3 BigInt Literals & Arithmetic

You are the Compiler Engineer implementing Track 2 (Phase C3 Slices S3–S5) in the dedicated worktree:
`/Users/sto/workspace/datomworld/.claude/worktrees/yang-python-c3-bignum`

## Governing Architecture & Rules
- Governing Specification: `docs/design/yang.antlr.md` (§8.5.4 Exact integers, rulings 1 to 14).
- Related Ruling: `collab/1790874940000-architect-python-c3-bignum-design.gpt-6-astra.findings.md`.
- Prior Landed Slices:
  - C3-S1: exact-integer module (`54536317`).
  - C3-S2: numeric-key, `hash()` and `is` rulings 6 to 8 (`be1f8d06`).

## Scope of Phase C3 Slices S3–S5
1. **Source Literals Beyond 2^53 (Section 8.5.4, ruling 3)**:
   - A source literal within `+/-(2^53 - 1)` stays a native `:literal`.
   - A larger integer literal lowers to an application of the integer module's parse function (`integer/parse-int-radix`) over its canonical radix-16 hex string.
   - Preserves de Bruijn image encoding domain (`:bigint` is out of domain on de Bruijn value table).
   - Ensure decimal, hex, octal, and binary spellings of the same large value lower identically.
   - Enforce digit budget: `lower/open-stage` takes `:yang.python.antlr/max-digits`. Decimal literals exceeding budget produce an explicit diagnostic; hex/oct/bin literals are exempt.
2. **Promotion & Demotion Discipline (Ruling 1 & 2)**:
   - Mandatory demotion: any arithmetic operation whose result fits back within native safe integer range (`-(2^53-1)` to `2^53-1` on JS, `-2^63` to `2^63-1` on Dart) MUST be demoted to native host representation.
   - Verify equality (`==`), identity (`is`), truthiness, hashing, and CBOR bytes are invariant to carrier.
3. **Operations & Conversions (Rulings 9 & 10)**:
   - Integer `/` is correctly rounded from exact ratio to float64.
   - Mixed arithmetic (BigInt with float) converts BigInt through checked conversion and preserves float64 carrier rules (§8.5.5).
   - Infinite two's complement bitwise operations (`~`, `&`, `|`, `^`, `<<`, `>>`).
4. **Verification & Tests**:
   - Run tests in `test/yang/python/antlr/exact_int_test.cljc` and `test/yang/python/antlr/c3_gate_test.cljc`.
   - Verify cross-host parity across JVM (`bb test:clj`), Node (`bb test:cljs`), and Dart.
   - Clean linter (`clj -M:kondo`).

## Reporting
Deliver the complete implementation, verify across all three hosts, and record your findings in:
`collab/1791539670710-compiler-engineer-c3-bignum.claude-sonnet-5-5.findings.md`

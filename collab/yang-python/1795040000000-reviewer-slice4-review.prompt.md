Created-GMT: 2026-11-20 1x:xx GMT
Created-Local: 2026-11-20 1x:xx +07 (+0700)
Coding-Agent: gemini-3.8-flash via agy (independent family from the GLM-family author per team.md's independence rule)
Status: SLICE 4 REVIEW (parallel-lane rule: review N concurrent with slice N+1's implementation; review must complete before slice N+1 merges)

# Review: py-bytes — the bytes slice (READ-ONLY)

Author: a zcode-subagent round (GLM family), branch `py-bytes` (four commits + three regen/pins commits; rebased onto current master after the slice-3 P1 fix landed). The worktree /Users/sto/workspace/datomworld-py-bytes holds the branch. Read-only: edit NOTHING.

The author's claims to verify against the code (lower.cljc bytes-literal lowering ~the string-prefix branch; prelude.cljc the bytes type, `py/bytes-repr`, `py/bytes-from-text`, str.encode):

1. **Value encoding**: `{:py/type :bytes :items [...ints...]}` — an ordinary guest-visible map like the other prelude types. Given the provenance-forgery precedent (the exception wrapper's guest-visible stamp was forgeable), is there ANY surface where a guest could forge bytes semantics that must be runtime-trusted? (bytes has no provenance semantics — assess honestly; likely clean.)
2. **CPython semantics parity** (the language reference is the bar):
   - indexing → INT (not a 1-byte bytes); slicing → bytes incl. negative/step; `b""[0]` → IndexError.
   - iteration yields ints; `in` accepts int OR bytes (subsequence search) — verify the subsequence case actually searches, not just equality.
   - ordering lexicographic BY BYTE VALUE; `==` by value; bytes vs str comparison → False/TypeError shapes as CPython dictates.
   - `.decode()` full UTF-8 validation claimed: overlong encodings, surrogates, range — this is the deepest claim; verify the validation logic actually rejects the classic cases (overlong 'c0 80', surrogate 'ed a0 80', >0x10FFFF).
   - `.decode('ascii')` rejects >127. `str.encode()` symmetric. UnicodeDecodeError/UnicodeEncodeError under ValueError in the class hierarchy.
   - `.hex()` lowercase zero-padded. startswith/endswith bytes-only (TypeError otherwise).
3. **Literal rules**: `b/rb/br` prefixes; adjacent-token concatenation; `\x` exactly 2 hex digits; octal ≤3 digits in range; `\u`/`\U`/`\N` refuse at LOWERING (CPython errors too — is the timing/message honest?); non-ASCII source bytes refused at the parse stage.
4. **The re-mints**: the prelude move required the py manifest re-mint (`dht-import-v1.generate.clj`, two byte-identical runs) and the linked-prelude count pin 71→79 (the two Unicode error classes + five method implementations) — verify these artifacts are consistent with the prelude diff, not hand-edited.
5. **The flagged pre-existing failure**: the full e2e run shows 44 failures in assert-statement-test ("Dead or forged cell reference" + ZeroDivisionError-args), reproduced identically at base `4bb17fa8` — the author says pre-existing. Assess: (a) does 4bb17fa8 predate the lazy-assert P1 fix (`9a3cad4e`)? If so the ZeroDivision failures are the EAGER-message bug the P1 fix closed — confirm by checking the same test on current master. (b) the "Dead or forged" signature on a JVM e2e run is the cljd regression signature — on the JVM that would be NEW and serious; determine whether those failures also vanish at 9a3cad4e+.
6. **bytearray refusal**: recorded as its own slice — confirm the refusal is loud and named.

## Format

Findings table (P0/P1/P2/P3, file:line, invariant, recommended correction) — the decode-validation and the flagged-failure attribution are mandatory rows — then one verdict line: ACCEPT / FIX-LIST / REJECT. Read-only.

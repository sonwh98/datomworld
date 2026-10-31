# Independent Architectural Review — Slice H3 (Hash & Sign Profile Primitives, Base64 Portability)

- **Reviewer:** deepseek-v4-pro (non-Claude architect)
- **Date:** 2026-10-21
- **Slice:** H3 (expand + migrate) of `docs/design/dao.stream.host-boundary.md` (r10)
- **Worktree:** `/Users/sto/workspace/datomworld-host-h3` (branch `host-h3`)
- **Governing sections reviewed:** §2 (rule), §2.4 (ownership/mutation laws), §3.1 (closure classes), §4.2 (table A `dao.host.hash`/`dao.host.sign`, table B base64/hex), §8 H3 row, §8.1 list 1, §8.2 host-library gate, §14 review checklist.

---

## Verdict: SIGNED OFF

H3 is architecturally compliant with the governing document and all enforceable
invariants. I found no defect that requires a change before merge. Three minor,
non-blocking observations are listed at the end for the engineer's awareness;
none rises to the bar for "SIGNED OFF WITH CHANGES".

---

## 1. Scope reviewed

Full `git diff` plus the three untracked files (`src/cljc/dao/host/hash.cljc`,
`src/cljc/dao/host/sign.cljc`, `test/dao/host/hash_test.cljc`) in the worktree:

- **New profile primitives:** `dao.host.hash` (`blake3-bytes`, `sha256-bytes`);
  `dao.host.sign` (`keypair-from-seed`, `sign`, `verify`).
- **Portable base64:** `dao.stream.base64` rewritten as a pure bitwise codec over
  `dao.host.bytes` (replaces `goog.crypt.base64`, `dart:convert`, `java.util.Base64`).
- **Consumer migrations:** `dao.jing` and `yin.vm.linker.sign`.
- **Gate/closure:** `test/dao/host/gate_test.clj`, `src/dev/host_boundary/closure.clj`,
  the three `closure-{clj,cljs,cljd}.edn`, and `src/dev/affected.clj`.

## 2. Architectural compliance (§14 checklist)

| §14 check | Result |
|---|---|
| Table-A H3 entries are internal & pure (P), not IO | **PASS.** `blake3-bytes`/`sha256-bytes`/`keypair-from-seed`/`sign`/`verify` are deterministic, total-over-declared-domain, synchronous, allocate-only-fresh. All reject non-bytes (and wrong lengths) with `:unsupported-value`/`:out-of-range` refusals, not substituted results. |
| Layering (`dao.host.*` requires nothing in repo; `dao` never requires `yin`) | **PASS.** `dao.host.hash` and `dao.host.sign` require only `dao.host.bytes` + host libraries. `dao.jing` requires `dao.host.{hash,text,bytes}` (all `dao`, no `yin`). `yin.vm.linker.sign` requires `dao.host.{sign,text,bytes}` (yin→dao is permitted). No cycle. |
| Deterministic seed replay vs. fresh entropy (§2.5, §9.1) | **PASS.** `jvm-pair`'s `proxy [SecureRandom]` is the deterministic seed-replay, correctly classified internal and counted `:secure-random 1` in list 2 for `dao.host.sign.cljc`. Fresh entropy `generate` stays in `yin.vm.linker.sign` (CLJ `SecureRandom.`, CLJS `crypto.randomBytes`, CLJD `ed/generateKey`) — deferred, not moved. |
| Freeze rule obligations (i)-(iv) | **PASS.** (i) P-entry immutability asserted in `hash-refusals-and-immutability`; (ii)/(iv) the profile's own M-entry calls (`bytes/copy!`, 11 sites) are enumerated as `:m-entry 11` in list 2 and all operate on freshly-allocated buffers or the provider's `nextBytes` buffer, never a published value. |
| Host-library gate (§8.2) H3 name list | **PASS.** Names `MessageDigest`, `Blake3`, `noble`, `blake3_dart`, `package:crypto`, `ed25519_edwards`, `goog.crypt.base64` added with correct slice `"H3"`; `ed25519_edwards` permitted in `yin/vm/linker/sign.cljc` (`generate`) via the §8.2 special-case. |
| Closure counts regenerated | **PASS.** Gate asserts 148 CLJ / 146 CLJS / 147 CLJD, 139 shared, 161 union — matching the H3 additions (+2 files, +2 shared, +2 union over post-H2). New files tagged `:profile-internal-pure`. |
| Negative & scan-failure tests fail as intended | **PASS.** `library-mode-fails-on-a-fixture-outside-the-profile` asserts the profile files actually name every host library except the now-portable `goog.crypt.base64`; scan-failure and docstring-only (string, never-counted) tests remain. |
| No host-library stragglers outside `dao/host/` | **PASS.** `rg` over `src/cljc` finds `goog.crypt` only in a `base64.cljc` docstring (a string — the reader-based gate correctly ignores it, confirming §8.2's "grep is not the gate") and `ed25519_edwards` only in `sign.cljc` (`generate`). `KeyFactory`/`Signature`/`KeyPairGenerator`/`X509EncodedKeySpec`/`PKCS8EncodedKeySpec`/`MessageDigest`/`Blake3`/`blake3_dart`/`package:crypto` appear nowhere outside `dao/host/`. |
| Named behaviour change (unpaired-surrogate refusal) tested | **PASS.** `surrogate-refusal-in-consumers` asserts `digest-string`, `sha256`, `blake3`, and `sign-trace` message assembly all refuse lone high/low surrogates. |
| No codec-track file touched | **PASS.** `dao.stream.base64` is an H0-H4 file (table B), not a codec-track file (§8.3); the codec-track `codec.cljc` is untouched. |
| Nothing from §9 started | **PASS.** No entropy generator, no storage seam, no parser-stage, no scheduling work introduced. |

## 3. Correctness spot-checks (read, not run)

- **ASN.1 prefixes ported correctly** from hex strings to byte vectors:
  `pkcs8-prefix` = 16 bytes (`302e020100300506032b657004220420`), `spki-prefix` = 12 bytes
  (`302a300506032b6570032100`). Buffer sizes 48 (=16+32) and 44 (=12+32) consistent.
- **base64 decode** handles all three pad cases correctly: `==` (out-len = idx+1),
  `=` (idx+2), none (idx+3 via `:else`), plus the empty string; `decode-table` maps
  chars to indices and pad chars are short-circuited before lookup. The `text?` regex
  guarantees non-pad positions are alphabet chars, so `decode-table c0/c1` never miss.
  Encode uses `unsigned-bit-shift-right` throughout (portable across the three lanes).
- **`dao.host.sign` CLJ** derives the public key as the trailing 32 bytes of the
  X509 SPKI encoding and the private key via a 48-byte PKCS8 — matching the original
  `yin.vm.linker.sign` logic, now driven from host-bytes instead of hex strings.
- **`dao.host.sign` CLJS** uses the Node `crypto` PKCS8/SPKI path unchanged in effect;
  the unguarded `js/require "crypto"` matches the original (`sign-trace`'s `exists?`
  guard is separate and retained in `yin.vm.linker.sign`).
- **`:m-entry 11`** reconciles exactly with 11 distinct `bytes/copy!` call sites
  (jvm-pair 1, keypair-from-seed CLJS 2, sign CLJ 2, sign CLJS 2, verify CLJ 2, verify
  CLJS 2).

## 4. Independent test verification (JVM lane)

Ran the repo's own `bb test:sub:clj` in the worktree; results independently confirm
the claimed CLJ evidence:

| Subsystem | Namespaces | Tests | Failures | Status |
|---|---|---|---|---|
| `dao.host` | 6 | 50 | 0 | pass |
| `dao.stream` + `dao.jing` | 50 | 738 | 0 | pass |
| `yin.vm` | 74 | 1273 | 0 | pass |

(CLJS and CLJD lanes were not re-run here; the code is portable `.cljc` and the
claim is consistent with the JVM result and the reader-arm review above. The one
cross-lane risk — `Uint8List.fromList` over `ed/sign`'s result on Dart — is exercised
by `sign-primitive-vectors`' round-trip in `hash_test.cljc`, which runs on all three
lanes.)

## 5. Minor observations (non-blocking)

1. **Hex encode/decode is triplicated** — `bytes->hex` in `dao.jing`, `to-hex` in
   `yin.vm.linker.sign`, and `bytes->hex`/`hex->bytes` in `hash_test`. The design's
   table B lists "hex" as a portable composition but, unlike base64, does not mandate
   a home; the three copies are all pure and correct. Optional future consolidation,
   not a defect.
2. **`library-references` hardcodes `"src/cljc/yin/vm/linker/sign.cljc"`** as a
   magic string to exempt `ed25519_edwards` (`generate`). Correct and test-only, but
   fragile to a file rename; a category/name-driven exemption would be more robust.
3. **`keypair-from-seed` CLJ double-slices the seed** (once at the call site, once
   inside `jvm-pair`). Negligible; no correctness impact.

## 6. Conclusion

H3 fully satisfies the governing rule and every enforceable invariant of §14. The
hash/sign primitives are genuinely pure and internal, the base64 codec is a correct
portable replacement accepted by its oracle, the two consumers are migrated without
regression, the unpaired-surrogate behaviour change is named and directly tested,
and the host-library gate is correctly extended and enforced. **SIGNED OFF.**

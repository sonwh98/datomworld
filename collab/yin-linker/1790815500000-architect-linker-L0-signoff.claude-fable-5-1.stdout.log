# Architect sign-off: linker over DHT, slice L0

**SIGN-OFF GRANTED, on condition that findings 1 to 4 land in the same commit.** The code is correct on all three hosts and no finding is Critical or High. The conditions are mechanical; no re-review is needed if the three lanes stay green afterwards.

## What I verified

- **Lanes.** JVM full lane: 2,594 tests, 186,830 assertions, 0 failures. Node bundle (built after the last source edit): 2,508 tests, 0 failures, with `sign-test` and `publish-test` both listed. Dart: `flutter test --no-pub` on the compiled sign and publish tests, 10 of 10 passed. I did not re-run the full Dart lane or kondo.
- **Vectors.** The three RFC 8032 §7.1 entries match the RFC from memory, and each host's tests reproduce them. I recomputed both envelope signatures with Node `crypto` outside the implementation: the message is prefix plus canonical bytes, and both signatures match.
- **Crypto sources.** `sign.cljc` contains no curve arithmetic. It uses JDK `Signature`/`KeyFactory`, Node `crypto.sign`/`verify` required at run time, and `ed25519_edwards`. The two DER prefixes are the standard RFC 8410 constants.
- **Dart package.** `ed25519_edwards` 0.3.2 is synchronous: `sign`, `verify`, `public`, `newKeyFromSeed` and `generateKey` return values, with no `Future`. It is a pure-Dart port of Go's `crypto/ed25519`, rejects non-canonical S, and generates keys from `Random.secure()`. It is acceptable. Residual risk: single maintainer, no audit, and no constant-time guarantee when signing.
- **Store corpus.** The publisher reports tree `:refused` (`:undeclared-free`, name `n`) and semantic, stack and register `:ok`. A real shell on `:semantic` requires the module and `(mod/f)` answers 42; `:ast-walker` refuses. The publisher's report is true.
- **Writes nothing.** The undefined-export check and the footprint both run before the first write.

## Findings

| Severity | file:line | Issue | Fix |
|---|---|---|---|
| Medium (1) | `src/cljc/yin/vm/linker/sign.cljc:164` | `key-from-text` wraps the text in `[` `]` and reads one form, so `<valid key>] [42` is accepted (confirmed on the JVM). §6.5 requires one EDN map and nothing else. | Parse under both a vector wrap and a map wrap (`(str "{:k " s "\n}")`) and require both to yield the single key; the first unbalanced closer can end only one wrapper. Put `"\n"` before the closing delimiter. Add the `] [42` and `}} {:x {` cases to `key-file-validation`. |
| Medium (2) | `pubspec.lock` | The lock lacks the new pin. It passes locally only because `.dart_tool/package_config.json` still holds the resolution. | See ruling 2. |
| Medium (3) | `docs/design/yin.vm.linker.dht.md:85, 436-441, 1167-1169, 1453-1455, 1480-1482` | The contract says the store corpus is refused on the semantic format. It is not, and it should not be. | See ruling 1. |
| Medium (4) | `docs/design/yin.vm.linker.dht.md:1179`; `src/cljc/yin/repl/link.cljc:153-204` | The bullet "`yin.repl.link` and the tests share `local-runtime`" is unmet: `yin.repl.link` keeps its own budgeted runtime. Its file is outside L0's list, so L0 could not meet it. The report marks the bullet "Pass" without saying so. | Contract amendment below; adoption moves to L3. |
| Low (5) | `test/yin/vm/linker_manifest_test.cljc:79-80` | The synthetic `'result` export and second manifest are unnecessary. | See ruling 3. |
| Low (6) | `test/yin/vm/linker/publish_test.cljc:104` | The test asserts only `:refused` for the tree format. | Assert `{:status :refused :reason :undeclared-free :name 'n}`. |
| Low (7) | `test/yin/repl/require_test.cljc:90-98, 111-118` | The `store-module` docstring still says only H and R link. The `publish-module` docstring says the overlay replaces manifest fields; it now honours only requires and primitives. | Correct both docstrings. |
| Low (8) | `src/cljc/yin/vm/linker/publish.cljc:18-35` | `definitions` restates the linker's definition rule (`linker.cljc:321-341`) differently: no two-operand check, and exact-map equality on the operator. A three-operand `yin/def` passes the export check and then throws from `materialize-tree!`, writing nothing. | Match on `:type` and `:name`, require exactly two operands, and refuse malformed trees as data. |
| Low (9) | `src/cljc/yin/vm/linker/publish.cljc:48-51` | A required address holding a blob that is not a manifest is accepted and pinned (confirmed). | Refuse when `linker/manifest-defect` is non-nil, or leave it to the L2 closure walk (amendment below). |
| Low (10) | `test/yin/vm/linker/sign_test.cljc:61-83`; `sign_vectors.edn` | §6.4 puts the tamper cases in the vector file; they are built in test code. The "principal" and "another key" cases both stop at the principal pre-check, so none reaches the verifier with a consistent wrong key. The positive control asserts only the absence of `:bad-proof`. | Add a case with envelope and declared key for TEST 2 but TEST 1's signature. Assert the control resolves `:ok`. Commit the no-prefix signature as a fixed hex. |
| Low (11) | `src/cljc/yin/vm/linker/sign.cljc:53-60` | The JVM derives the public key by feeding the seed through a `SecureRandom` proxy, which relies on how the JDK's key generator draws bytes. It fails closed, and the RFC vectors catch it only at test time. | Comment the dependency and request the `SunEC` provider by name. |
| Low (12) | `src/cljc/yin/vm/linker/sign.cljc:103, 119` | In a browser, `sign-envelope` throws a `ReferenceError` instead of a reasoned refusal (§6.4). `verify-envelope` is correctly false. | Throw `ex-info` with `:reason :yin.link.sign/no-primitive`. Can wait for L4. |
| Nit (13) | `src/cljc/yin/vm/linker.cljc:1991-1994`; `publish.cljc:40, 88` | `local-runtime` sits under the "Publishing" banner. The tree is lowered twice per publish. | Move the banner; pass the tree through. |

## Rulings

### 1. Store corpus, semantic format: the contract is wrong; the linker does not change

The semantic scanner works in the vector's linear order, where the definition of `n` precedes every application site, so the read is discharged. The module evaluates on the semantic VM. Only the tree scanner retains the read. I inherited the error from the stale `store-module` docstring. Amended text:

§2, row at line 85:
> | A module whose export reads another module-level definition from inside a lambda body links on the semantic, stack and register backends. The tree scanner alone retains the read (its path order places the operator's body before the operand that defines the name), and the manifest cannot declare it. | `require_test.cljc:90-98`; `linker_test.cljc` `a-definition-dominating-every-application-discharges-a-body-occurrence`; `yin.vm.linker.md` 4.2 step 5a |

§5.3, replacing lines 436-441:
> **What links where.** Step 5a of `yin.vm.linker.md` is unchanged by this epic. A module whose exported lambda reads another module-level definition is published with `:links` showing the tree format refused (`:undeclared-free`, naming the read) and the semantic, stack and register formats ok, exactly as the starting tree behaves. Such a module loads and evaluates on the semantic, stack and register VMs. `:links` is the linker's own verdict per format; the publisher never restates it. The publisher prints which.

§12 L0, replacing the `:links` bullet:
> - `:links` reports ok on all four formats for the closed corpus. For the store corpus it reports the tree format refused `:undeclared-free` naming the read, and semantic, stack and register ok.

§12 L5, replacing lines 1453-1455:
> - The store corpus (an export reading a module-level definition) evaluates on the semantic, stack and register VMs and refuses on the walker with the linker's reason.

§13, replacing the step 5a deferral:
> - **Step 5a for module-level reads in the tree format.** Which modules link on the walker is the linker's rule, not this epic's.

### 2. `pubspec.lock`: yes, L0 includes it

The lock is the companion of the pin and carries the package's content hash, which is the supply-chain pin for a crypto dependency. Without it, every fresh checkout dirties the tree on its first pub get.

Run `flutter pub get`, not `upgrade`. The diff should add exactly `ed25519_edwards` 0.3.2 (direct main) and `adaptive_number` 1.0.0 (transitive); `crypto`, `collection` and `convert` are already locked. Reject any other movement.

§12 L0 file list, amended:
> `pubspec.yaml` and `pubspec.lock` (the Dart Ed25519 package and its resolution),

### 3. The manifest helper's synthetic export: not as written; drop it

I redefined the helper in memory to publish with `:exports #{}` and return the publisher's own address. `yin.vm.linker-manifest-test` passes unchanged: 19 tests, 90 assertions, 0 failures. The one test that needs `'result` builds its own manifest. Remove the `assoc` and the second `materialize!`, so the fixture is exactly what the one publisher produced. Hostile manifests stay where they are, built explicitly per test.

### Further contract amendments

§12 L0, replacing the last bullet (finding 4):
> - `yin.vm.linker/local-runtime` exists and the linker tests' runtime delegates to it; no behaviour changes. `yin.repl.link` adopts it in L3.

§12 L3, added acceptance bullet:
> - `yin.repl.link` builds its DHT-source link runtime with `yin.vm.linker/local-runtime`. The attempt budget of the `:content-store` and `:content-client` sources is unchanged.

§12 L2: the file list omits `publish.cljc`, yet §5.2 requires the publisher to walk its closure. Add `src/cljc/yin/vm/linker/publish.cljc` to the files, and this bullet:
> - `publish-module!` walks the closure it minted and refuses unless the walk is `:complete`; a `:requires` address that holds no valid manifest is refused by that walk.

## Notes

- This review made no edits. The accidental full JVM lane run wrote gitignored JFR files under `target/jfr`; `git status` is unchanged.
- The doc file is outside L0's list. The amendments above are the Architect's and can ride in the L0 commit or a preceding `docs(design)` commit.
- The three hosts' verifiers may disagree on adversarial encodings that only the key holder can craft (small-order keys, non-canonical points). This is not an L0 defect; it is worth one line in §13.

**NOT SIGNED OFF.**

Read all 1,021 lines, both reviews, doctrine, and repository evidence, including more than a dozen cited source/oracle files. No edits.

`H` = [dao.stream.host-boundary.md](/Users/sto/workspace/datomworld/docs/design/dao.stream.host-boundary.md). Source paths below are relative to `src/`.

- **A1 — Resolved for placement:** §§2.3, 2.4, 7 correctly require in-instance primitives and acknowledge porting beyond streams. ABI §§4–5 and wasm’s seven imports support this; no second ABI is introduced. The operational definition still needs correction below.
- **A2 — Partly resolved:** §§4.1/4.4 remove callable entropy generators and timer thunks, but explicitly retain consumer callbacks inside `step!`.
- **A3 — Partly resolved:** §3.2 lists **26** in-scope UUID calls, not 24/25. The 27th is excluded `dao/postgraphics/terminal.cljc:14`. No listed UUID site is missing; counts and broader closure claims are wrong.
- **A4 — Partly resolved:** §4.3 genuinely enumerates 66 functions, but omits carrier operations and migration assignments; runtime prerequisites do not satisfy the stated final gate.
- **A5 — Partly resolved:** Aliasing, malformed UTF-8, Unicode version, stability and corpus exceptions are improved. Fixtures exist, but grouped suites are not an entry-to-case coverage matrix. Base64 tests already exist; integer/sign suites contain 25/4 deftests, not 26/5.
- **A6 — Partly resolved:** H2 names all eight external shim consumers found, including three Python tests. Its pre-deletion empty-grep condition still matches the shim’s own namespace; H1’s direct tests are correctly required.
- **A7 — Partly resolved:** §4.2 supplies the missing claim-file operations, but its atomic-replacement failure guarantee is unsound.
- **A8 — Partly resolved:** §§8–11 remove invented performance numbers and record OQ1–OQ6. Cost, risk and file-disjointness remain understated.

| ID | Severity | File:line | Evidence | Correction |
|---|---|---|---|---|
| R1 | High | H:169,224,872 | “Equal calls differ” classifies weak-reference reads as IO, then exempts them. Mutable byte/array entries also contradict the profile’s blanket purity claim. | Define the boundary first, distinguish owned runtime state from external facts, and specify mutation/ownership laws. Preserve §12 as an owner proposal; do not present this inconsistent definition as verbatim doctrine. |
| R2 | Blocking | H:590–610 | Stream cursors are non-destructive (`cljc/dao/stream.cljc:159–173`): independent readers can reuse entropy. No allocation, partial-batch, loss or blocked-resumption protocol is specified. `fresh-id`’s 32 hex characters fail `ucf/custody.cljc:50–56`’s UUID grammar. DHT initialization draws multiple values synchronously (`space/dht.cljc:255–269`). | Specify exclusive allocation and resumable consumption, bootstrap identities, preserved UUID spelling, and DHT failure/continuation behavior. Emit async completions as data; moving callbacks into `step!` does not remove them. |
| R3 | High | H:318–358 | This is lexical grep, not resolved closure analysis. `jing/coordinate.cljc:80` selects a blocking driver whose `clj/dao/jing/content/driver.clj:169–206` reads clocks and sleeps. `sign.cljc:79` is deterministic seed replay, not fresh entropy. Regex also appears in custody/datagram. | Commit a transitive, lane-specific closure with classifications; correct 27 total/26 scoped UUID counts and all residual assignments. |
| R4 | High | H:507–579 | Missing operations include JVM Decimal/Ratio construction/access (`jing/cbor.cljc:483–632`). `yin/vm/debruijn_code.cljc:232–245,315` retains float/BigInteger interop without a migration assignment. Btree’s actual default is JVM `:soft` (`btree.cljc:39–44`), not universally strong. | Complete the manifest/runtime mapping and assign migrations. Reconcile retained runtime arms with H0’s final allowlist and impossible H2 greps. |
| R5 | High | H:476–477 | `store/fs.cljc:367–385` renames before directory sync; sync failure can leave new HEAD visible. | Distinguish pre-rename failure, replacement visibility and durability uncertainty; test post-rename failure explicitly. |
| R6 | High | H:744–775 | Mandatory identities require edits to codec-owned tests: `ws_test.cljc:11`, `remote_route_test.cljc:24` construct identity-less rings. §9 also says UCF untouched despite H5 custody/holder migrations. | Allocate source **and test** files, provide staged compatibility or dependencies, and re-estimate H5. Base64 replacement and new refusal laws are not verbatim code motion. |

J1 arm-adding should wait. J0b/J0c probes can proceed; J0a is **not wholly unaffected**—its source adaptations require coordination.

OQ7/OQ8 appropriately seek owner decisions. OQ9 alternatives both can avoid core generators; its recommendation overclaims exclusivity. OQ10 may ask about accepting a residual, but compiler counters need hygienic naming, not merely resetting numbering; transaction `gensym` needs separate treatment.

Not verified: executable lane parity, performance, crash/race behavior, exhaustive resolved closure/oracle coverage, or produced wasm imports. These remain proposed gates, not demonstrated properties.

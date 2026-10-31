Completed-GMT: 2026-09-23 07:44:27 GMT
Completed-Local: 2026-09-23 14:44:27 +07 (Indochina Time)
Coding-Agent: glm
Session-ID: 95116c74-2f68-4276-99a8-56291df8587e

## 1. Verdict: **READY WITH CHANGES**

One P1 and two P2s, all localized to the two new artifact files and cheap to fix before commit. The evidence fixtures, grammar pinning, CBOR alignment, and file-box discipline all verify clean. The changes do not require re-doing any H0 work — they are corrections to the classification table and the lint specification so that H1 can safely implement against them.

## 2. Findings

**P1 | test/dao/jing/hash_registry_contract_test.cljc:346-357 | the pinned lint specification cannot flag any actual violation spelling in this repository.**
`validation-targets` is `#{'dao.jing/content-hash 'dao.jing/segment-key}` and `validation-target-call?` matches only fully-qualified symbols. Every representative flagged form in §4 uses the fully-qualified spelling. But every real Class-3 site uses the `jing` alias: `mem.cljc:29`, `file.cljc:76`, `remote.cljc:41`, `dht.cljc:162`, `node.cljc:114`, `storage.cljc:57`, `index.cljc:315`, `content.cljc:101,137`, `macro.cljc:276`, `completion.cljc:292`, `ledger.cljc:187,195,203`, `vm.cljc:1248` — all `(jing/content-hash …)` / `(jing/segment-key …)` in equality positions. A lint implemented to this pinned decision procedure and run over the repo returns zero findings on unconverted code — a false green that defeats the guard's stated purpose ("if they were left unconverted or regressed back", classification doc §Architectural lint target). Worse, the spec's own docstring argues *for* the limitation ("Namespace-qualified symbols, never bare/unqualified names — this is exactly what keeps `dao.jing.cbor/content-hash` out of scope"), so H1 would read alias-exclusion as intentional. **Fix:** pin var-resolution as the procedure — resolve each symbol in its namespace context to a Var, compare against `#{#'dao.jing/content-hash #'dao.jing/segment-key}`. The test itself already proves this preserves the CBOR exclusion: line 421 asserts `(not= #'jing/content-hash #'jing-cbor/content-hash)` (distinct vars, `dao.jing.cbor/content-hash` is real, `cbor.cljc:890`). Add a must-flag representative case in the aliased spelling, e.g. `(= (jing/content-hash payload) (jing/segment-hash address))`, and a must-not-flag case for `jing-cbor/content-hash` under an alias.

**P2 | docs/design/dao.jing.call-site-classification.md, Class 2 row `yin.vm.semantic` and Class 3 #15 | the two semantic.cljc sites are cross-assigned.**
Actual code: `semantic.cljc:617-619` computes `actual (when claimed (jing/segment-key (mapv canonical instructions)))` and throws on `(not= claimed actual)` — this is the claimed-address **validation**, in exactly the re-mint-and-compare shape Class 3 exists to convert. `semantic.cljc:731` (`address (jing/segment-key v)` inside `load-vector`) is the **mint**; no comparison occurs at 725-733. The table lists 618 as a mint and 725-733 as the claimed-segment verification — inverted. **Failure scenario:** H1 following the table converts `load-vector`'s mint to `segment-matches?` (nothing claimed exists there) and leaves the real check at 619 re-minting with the default — after the BLAKE3 flip, loading any SHA-256-claimed segment throws `hash-mismatch`. The lint would catch the residual (it is an equality-position target call — but only once P1's alias fix lands, which compounds P1's importance). **Fix:** Class 2 row → `semantic.cljc:731` only; Class 3 #15 → `semantic.cljc:610-629` (comparison at 619). The #15 "current shape" prose already describes 618's shape correctly; only the assignment and location move.

**P2 | docs/design/dao.jing.call-site-classification.md:9-10 | completeness claim is false — four real mint call sites are absent.**
The intro claims to inventory "every Jing hash and address operation across `src/` and `test/`". Missing from Class 2, verified by grep: `yin/vm/pipeline.cljc:112` (`(jing/materialize! projected-store (:envelope projection))`); `yin/vm.cljc:938` (`id (jing/segment-key body)` — semantic-bytecode row mint); `dao/data/btree/storage.cljc:115` (HydrationStorage `-store` async-branch cache mint) and `:122-123` (sync-branch source+cache mints of freshly built node blobs). All are default-following mints needing no H1 edit, so behavioral risk is nil — but the artifact's entire value is being the complete reviewed list the lint's exemption set and H2's coverage criterion draw from, and `test/` is claimed in scope yet not inventoried at all. **Fix:** add the four rows; either inventory test/ or narrow the intro to `src/` with one line of rationale (test sites are regenerated wholesale in H1).

**P3 | docs/design/dao.jing.call-site-classification.md, Class 3 #7 | "current shape" description is factually wrong.**
The dht peer-fetched value is *not* validated "via backend `validate-address-payload!` reuse": the actual code at `dht.cljc:224-226` is an inline `(= address (jing/segment-key (:value res)))` — the re-mint-and-compare family, like the yin.vm sites. "grid-fallback branch" also mislabels `make-get`'s `some`-over-peers lookup. Disposition unchanged; correct the description so H1's converter recognizes the shape.

**P3 | test/dao/jing/hash_registry_contract_test.cljc:263-269 | reference `segment-matches?` catches all exceptions, broader than both the design and the sign-off.**
The design (hash-registry.md:241) makes `segment-matches?` total over malformed/unsupported/noncanonical/mismatched/refusal; the architect sign-off (findings.md:65) explicitly bounds implementation: "catch only the canonical encoder's documented refusal class, not arbitrary provider or programming failures." The reference's catch-all `Throwable/Object/:default → false` plus the docstring "Never throws" overpins a broader contract than H1 is authorized to implement. No current test distinguishes the two (all false-cases are genuine refusal/malformed classes), so nothing breaks — but the spec text should say "catch only the encoder's documented refusal, rethrow everything else" so H1 doesn't inherit the catch-all.

**P3 | test lanes | only the JVM lane was run.** The implementer ran `clojure -M:test` and kondo; the file's `:cljs`/`:cljd` branches (`read-text` :41-45, `bytes->hex` :244-253) were never executed. They mirror `cbor_fixtures.cljc`'s proven pattern so risk is low; note it so H1's three-lane run is not treated as already de-risked.

**P3 (cosmetic) | hash_registry_contract_test.cljc:362-364 | dead set element.** `equality-form?`'s set contains `'(clojure.core/=)` (a list); `(first form)` is always a symbol, so that entry can never match. Coverage is unaffected (the qualified symbols are separately present).

## 3. Required changes before commit

1. (P1) Re-pin the lint specification to var-resolution with aliased representative cases, per §2 above.
2. (P2) Correct the semantic.cljc pair assignment (Class 2 → 731 only; Class 3 #15 → 610-629).
3. (P2) Add the four missing Class-2 mint rows and narrow or honor the "every operation across src/ and test/" claim.
4. Fold P3s into the same edit.

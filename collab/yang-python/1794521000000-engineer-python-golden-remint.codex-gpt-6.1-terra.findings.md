# Python golden re-mint findings

Base: `2506a81e7260fd3c4438f27e0ee5b90cc38e43ea` (origin/master). Pushed branch `python-golden-remint`, head `ce9c6fdf6cf38e8e17593b042b080405c179119e`.

The documented corpus generator ran twice, both rc 0. SHA-256 for both writes: `ee91e25e7d74fe85fd5d61f593ff1dd48e70f218c8605f108e14460dc1269f61`. The committed corpus retains this hash.

Initial jupiter reproduction, three named namespaces: rc 1, `Ran 61 tests containing 451 assertions. 9 failures, 0 errors.` All nine were the named stale pins/counts. Focused seeded-builtins reproduction: rc 1, `Ran 1 tests containing 6 assertions. 2 failures, 0 errors.` The values were `type` and 26. Initial shiva `node target/node-tests.js --test=yang.python.antlr.linked-safepoint-test`: rc 0, `Ran 16 tests containing 60 assertions. 0 failures, 0 errors.` Its slow tests, including the manifest test, were skipped; no safepoint pin was changed.

Final verification on pushed head:

- Jupiter `clojure -M:test -n yang.python.antlr.float-address-test -n yang.python.antlr.linked-prelude-test -n yang.python.antlr.prelude-parity-test`: rc 0, `Ran 61 tests containing 451 assertions. 0 failures, 0 errors.`
- Jupiter `clojure -M:test -i :slow -n yang.python.antlr.dht-import-test`: rc 0, `Ran 1 tests containing 127 assertions. 0 failures, 0 errors.`
- Shiva `node target/node-tests.js --test=yang.python.antlr.float-address-test,yang.python.antlr.linked-prelude-test,yang.python.antlr.prelude-parity-test,yang.python.antlr.linked-safepoint-test`: rc 0, `Ran 77 tests containing 238 assertions. 0 failures, 0 errors.` Slow-guarded tests including both manifest tests were skipped.
- Shiva `DATOM_SLOW_TESTS=1 node target/node-tests.js --test=yang.python.antlr.dht-import-test`: rc 0 (the `set -e` shell advanced to the next command), `Ran 5 tests containing 158 assertions. 0 failures, 0 errors.`

The Node corpus and JVM corpus passed against the same committed artifact. After the corpus passed, its original shell started a duplicate of the independently completed Node pin command; that redundant process was stopped by exact PID, so the enclosing SSH command exited 255. The separately run Node pin command above completed with rc 0.

The prescribed Node safepoint invocation skipped `pysp-manifest-address-test` under its slow guard, so it does not establish the signature of the nightly safepoint failure. No safepoint file was changed; that remaining attribution is reported for follow-up.

## 2026-11-13 — r2 slow safepoint

At base `ce9c6fdf6cf38e8e17593b042b080405c179119e`, both shiva and jupiter printed the same actual pysp manifest address `:segment/blake3-5f1ba8b4a18fb4ebfd072521936bef31a8cad0fe99bf24d1e888925ab9f9b7d8`, against the old `b85905d8...` pin. This differs from the earlier nightly-tree address cited in the dispatch but is identical across the two hosts at this branch head.

- Initial jupiter `clojure -M:test -i :slow -n yang.python.antlr.linked-safepoint-test`: rc 1, `Ran 10 tests containing 53 assertions. 1 failures, 0 errors.` The sole failure was the manifest pin.
- Initial shiva `DATOM_SLOW_TESTS=1 node target/node-tests.js --test=yang.python.antlr.linked-safepoint-test`: rc 1, `Ran 16 tests containing 113 assertions. 1 failures, 0 errors.` The sole failure was the same manifest pin.

The re-pin was pushed to `python-golden-remint` at `588ce3b5adccd6ec751fc23b626a7c4427a6f099`. Final checks completed below.

- Final jupiter `clojure -M:test -i :slow -n yang.python.antlr.linked-safepoint-test`: rc 0 (shell advanced to sanity), `Ran 10 tests containing 53 assertions. 0 failures, 0 errors.`
- Final jupiter `clojure -M:test -n yang.python.antlr.linked-prelude-test`: rc 0, `Ran 14 tests containing 125 assertions. 0 failures, 0 errors.`
- Final shiva `DATOM_SLOW_TESTS=1 node target/node-tests.js --test=yang.python.antlr.linked-safepoint-test`: rc 0 (shell advanced to sanity), `Ran 16 tests containing 113 assertions. 0 failures, 0 errors.`
- Final shiva `node target/node-tests.js --test=yang.python.antlr.float-address-test,yang.python.antlr.linked-prelude-test,yang.python.antlr.prelude-parity-test,yang.python.antlr.linked-safepoint-test`: rc 0, `Ran 77 tests containing 238 assertions. 0 failures, 0 errors.` Slow guards were skipped in this sanity command; the separate slow safepoint run above exercised them.

---

## REVIEW (qwen3.8-max) — REQUEST CHANGES

Completed-GMT: 2026-11-13 00:11:13 GMT
Completed-Local: 2026-11-13 07:11:13 +07 (Asia/Ho_Chi_Minh)
Reviewer: qwen3.8-max (non-codex, per the independence rule). Read-only: git + file reads +
`shasum`/`python3` over `git show` output. No test suite was run. No file other than this one was
written. No sibling reviewer log was read (none exists beside my own stdout capture).

**Verdict is REQUEST CHANGES on attribution text only. Every value in the round is correct and
machine-verified; items 1–3 are a docs correction and need no re-verification.**

### Landed before the verdict — read this first

`refs/heads/master` on origin is now `588ce3b5`. Local reflog:
`588ce3b5 refs/remotes/origin/master@{2026-11-13 06:48:11 +0700}: update by push`
(previous entry `2506a81e @{2026-11-13 04:44:07 +0700}`). My opening `git ls-remote origin
refs/heads/master` still answered `2506a81e`, so the branch landed roughly two minutes into this
review. This verdict therefore cannot gate the landing; items 1–3 are a follow-up correction owed
on top of master.

Consequence for the next seat: the brief's command `git diff origin/master...origin/python-golden-remint`
is now **degenerate (empty)**. The range actually reviewed, pinned by SHA:

    git diff 2506a81e 588ce3b5          # == ce9c6fdf + 588ce3b5 over base 2506a81e

Also: this clone's local `refs/heads/master` is `f554e65e` ("docs: rename yang.cic/yin.cic to
dao.li"), which is **not** an ancestor of the new origin/master (`git merge-base master 588ce3b5`
= `2506a81e`). That local commit was never pushed and now needs a rebase onto `588ce3b5`.

### Q1 — NO. Three riders name a cause that provably cannot be true; two changed literals carry no rider.

Per-literal audit of all 5 files (43 insertions / 38 deletions):

| file | changed literal(s) | rider present? |
|---|---|---|
| `float_address_test.cljc` | 5 addresses — `prelude-addresses-test` ×1, `program-addresses-test` ×4 | 2 riders: "…with **P9 math builtins** and provenance adoption" / "**P9 math builtins** and provenance adoption re-minted them again" |
| `linked_prelude_test.cljc:190` | `70` → `71` (`count prelude/runtime-keys`) | **none** |
| `linked_prelude_test.cljc` `manifest-address` | 1 address | rider: "**P9 math builtins** and provenance adoption moved it again" |
| `linked_safepoint_test.cljc` `pysp-manifest-address` | 1 address | rider: "**P9 math builtins** and provenance adoption move the pinned `py` requirement again" |
| `prelude_parity_test.cljc` | `"type"` **inserted into `builtin-function-names`**; `25`→`26`; two prose "twenty-five"→"twenty-six" | count words only — **no rider naming the content change** |

**1. "P9 math builtins" is a false cause for every Python surface.** `f82afe44` is
`fix(yang.php): P9 math builtins per PHP 8.3`; its touch set is `src/cljc/yang/php/antlr/prelude_p9.cljc`,
`test/yang/php/antlr/builtin_surfaces_test.clj`, `test/yang/php/antlr/programs_p9.cljc`. Its merge
`a2c4b1b7` adds only `docs/agents/known-gaps.md` + a collab findings file. Neither commit touches a
single Python file (`git show --name-only --format='' a2c4b1b7 f82afe44 | grep -i python` → empty),
and `src/cljc/yang/python/antlr/prelude.cljc` contains zero references to `php`, `prelude_p9` or P9.
The relay's own hypothesis ("P9 … should move BOTH hosts' prelude uniformly") was never
discriminated — the r1 findings do not record the `a2c4b1b7` bisect point the relay asked for — and
it is false. The relay's `FINAL ATTRIBUTION` then propagated "the P9 + provenance content merges"
into `ce9c6fdf`'s subject line, so the false cause is now in the **tracked** tree in five places
(4 riders + 1 commit subject) while the findings file that could have corrected it is untracked
(`git ls-files --error-unmatch` on it → not known to git).

**2. The true cause set, measured:** the Python exception-track / provenance series —
`04e58031` (python R2 reception), `2ab72b31` (R3), `9c97fae3` (egress), `00ca2d1c` (provenance
wiring), `7f10befa` (reject malformed provenance conditions), merged by `8018005d`. Those are the
only commits touching `src/cljc/yang/python/antlr/prelude.cljc` since 2026-11-01.

**3. `prelude_parity_test.cljc` is not a re-pin and is unattributed.** Inserting `"type"` into
`builtin-function-names` is an edit to expected *content*, which the brief's own rule makes a
REQUEST-CHANGES trigger. Substantively it is correct and necessary — see Q5 — but the docstring was
only renumbered ("twenty-five"→"twenty-six"), never told *why*, so the file no longer explains
itself and the true cause (`04e58031`) is recorded nowhere in the tracked tree.

Not a defect: the insertion position is right (`len, type, isinstance, …` matches
`prelude/builtin-functions` table order at base), and the assertion that consumes the vector is a
**set** comparison, so the vector's pre-existing order drift vs the source (`isinstance, hash,
divmod` vs `isinstance, divmod, hash`) cannot mask anything. No sibling stale pin survives: the
only other "25 Python functions" string in the tree is `docs/orchestrator-log.md:10801`, a
historical log entry that should not be edited.

### Q2 — YES, and it is provable to the byte. Independently reproduced SHA-256.

`git show 588ce3b5:test/resources/yang/python/dht-import-v1.edn | shasum -a 256` →
`ee91e25e7d74fe85fd5d61f593ff1dd48e70f218c8605f108e14460dc1269f61` — **exactly** the findings'
two-run hash. (Base `2506a81e` hashes `49336ba8…`.) Git blob `cb1533f0` → `3b3e4f23`, 268621 →
269401 bytes.

Correction to the brief's premise: this is **not** "consistent with a full rewrite". It is a
**partial** regeneration — 16 of 201 lines, `--numstat` 16/16 — and that is the stronger result:

- Structure identical: 202 lines both, header `dht-import-v1` unchanged, 192 blob rows both
  (unique, sorted by address in both), 10 non-blob lines both.
- `{:principals …}` and `{:importers …}` lines are **byte-identical** (the fixture CSTs did not
  move). All 6 differing non-blob lines kept their exact length (86→86, 454→454, 2045→2045,
  820→820, 432→432, 2541→2541) — only address literals rotated in place.
- Address set: **10 out, 10 in, 182 byte-identical.** The 10 are 5 `yin.module` blobs (units
  m/k/t/e + republished k) and 5 `yang.site` records that embed their manifests. Every unit
  address named in `{:units …}` resolves to a present blob row in both revisions.
- Per-unit decode, same-unit pairing: the `:effects` set grows by exactly **+2** in every unit
  (m 5→7, k 5→7, t 6→8, e 5→7) and every module blob grows by exactly **+78 bytes**
  (2160→2238 ×3, 2425→2503, 2848→2926). The 5 `yang.site` blobs stay **632 bytes** — they only
  re-hash. Total blob delta 390 bytes ×2 (hex) = 780 = the exact file delta.
- 78 bytes is precisely the CBOR cost of the two new footprint entries: `d81b 82 70
  "dao.jing/keyword" 82 64 "cell" 6a "provenance"` = 37, plus `… 64 "cell" 6e "new-provenance"` =
  41. All 5 new module blobs contain the string `provenance`; **none** of the 5 old ones do.

So the corpus delta is *exactly* `:cell/provenance` + `:cell/new-provenance` entering every unit's
footprint — the provenance adoption and nothing else. A hand edit could not produce ten new blake3
addresses that stay internally consistent with the header, the three index lines and each other.
The generator is checked in at `test/resources/yang/python/dht-import-v1.generate.clj` and
documents both the rerun rule and the determinism argument ("Every input is checked in, and Ed25519
signatures are deterministic, so a rerun on an unchanged tree rewrites the same data").

Note: nothing in the corpus delta is attributable to P9 either — independent confirmation of item 1.

### Q3 — PASS.

`git diff --name-status 2506a81e 588ce3b5` → exactly 5 files, all `M`, all under `test/`.
`git diff --name-only 2506a81e 588ce3b5 -- . ':(exclude)test/**'` → empty (nothing in `src/`,
`docs/`, config). `test/yang/call/conformance.clj` blob `a8bc2110` at both ends — untouched.
No `src/` file changed, so the pinned addresses cannot have been moved *by* this round.

One correction for the standing contract's wording: there is **no `manifest-golden` path** in the
tree at `588ce3b5` (`git ls-tree -r --name-only | grep -i golden` returns only
`test/yin/vm/semantic/scanner_goldens.cljc`, `test/resources/yin/vm/semantic/scanner-golden-c4.generate.clj`
and collab scratch logs). Both scanner-golden files are untouched here. The contract is satisfied;
the name in the brief is stale and should be fixed wherever it is written down.

### Q4 — Substantially complete. Two single-host gaps, both non-blocking; three rc values are inferred, not captured.

Assertion counts prove nothing was silently skipped on the JVM lane: `linked_prelude_test` has 14
deftests (8 `^:slow`) and jupiter reported "Ran 14"; `float_address(12) + linked_prelude(14) +
prelude_parity(35) = 61` and jupiter reported "Ran 61". Corroboration: the r1 jupiter reproduction
reported **9** failures, which is exactly the relay's named set (float-address 5 + runtime-keys 1 +
`manifest-address-test` 1 + seeded-builtins 2) — so the JVM lane does execute the `^:slow` bodies.

| surface | jupiter (JVM) | shiva (Node) |
|---|---|---|
| dht-import slow | rc 0, 1 test/127 asserts (`-i :slow`) | rc 0, 5 tests/158 asserts (`DATOM_SLOW_TESTS=1`) |
| float-address ×5 (both deftests are **fast**) | rc 0 | rc 0 |
| linked-prelude runtime-keys 71 | rc 0 (×2) | rc 0 |
| linked-prelude `manifest-address-test` (`^:slow`) | rc 0 | **NOT RUN** (gap A) |
| prelude-parity `seeded-builtins-test` (fast) | rc 0 | rc 0 |
| linked-safepoint `pysp-manifest-address-test` (`^:slow`) | rc 0, 10 tests/53 | rc 0, 16 tests/113 |

**4. Gap A (only single-host surface):** the linked-prelude manifest pin never ran under Node's slow
tier — the findings say so themselves ("Slow-guarded tests including both manifest tests were
skipped") and there is no `DATOM_SLOW_TESTS=1 node … --test=yang.python.antlr.linked-prelude-test`
anywhere in the record. **Materially mitigated:** the pin's value
`59efa8b3793d9737d09b74c7304c086405084d486c76b8dc986c88870357b744` is byte-identical to the corpus
`{:py …}` address, and `dht_import_test.cljc:442-443` ("every node's installed py is the artifact's")
asserts exactly that on Node — which ran green on shiva with `DATOM_SLOW_TESTS=1`. The value is
therefore Node-corroborated; only the pin assertion itself is JVM-only. Cheap to close:

    DATOM_SLOW_TESTS=1 node target/node-tests.js --test=yang.python.antlr.linked-prelude-test

**Gap B (minor):** jupiter's dht-import command used `-i :slow`, so only the 1 slow deftest ran;
that namespace's 4 fast deftests (`units-publish-after-what-they-import`, `cyclic-components-…`,
`an-unsupplied-import-…`, `published-site-sets-verify`) appear only in shiva's 5-test run. Outside
the merge bar (the failing JVM surface was the slow deftest) and unaffected by a corpus re-mint, but
the JVM record is thinner than the Node one.

**5. rc provenance.** Three green results are recorded as inferred rather than captured —
"rc 0 (the `set -e` shell advanced to the next command)" for jupiter slow safepoint and shiva slow
safepoint, and the shiva slow dht-import "rc 0 (the `set -e` shell advanced…)". A `set -e` shell
advancing is consistent with rc 0 but is not rc 0. The disclosed PID-kill incident (a duplicate Node
pin command stopped by exact PID, enclosing SSH exiting 255) makes this worth tightening: append
`&& echo RC=$?` or run each command standalone so the number is measured.

**6. Carry-over is sound.** `588ce3b5` changes only `linked_safepoint_test.cljc` (verified:
`git diff --name-only ce9c6fdf 588ce3b5` → that one file) and the corpus blob is identical at both
commits (`3b3e4f23` twice). So the float-address / prelude-parity / dht-import results measured at
`ce9c6fdf` remain valid at `588ce3b5`; re-running only safepoint + linked-prelude at the new head
was the right scope.

**7. Two disclosures I accept as honest.** (a) The pysp actual `5f1ba8b4…` differs from the address
the dispatch cited for the nightly; it is identical across both hosts at this head, and since the
branch's base *is* current `origin/master` content-wise (only test files differ), nightly should
reproduce it. Still unreconciled against the nightly log, and the findings say so. (b) The relay
predicted the dht-import slow assertion count would move 127→124; jupiter reports 127. Assertion
counts are not pinned and the corpus legitimately changed shape, so this is immaterial — but neither
document explains it.

### Q5 — NO, the diff does not show why. And the brief's own hypothesis is wrong: it is not the provenance primitive.

`runtime-keys` = 9 fixed keys + `(mapv second builtin-classes)` + `(mapv second builtin-functions)`
+ `(mapv first method-implementations)` (`src/cljc/yang/python/antlr/prelude.cljc:3710-3722`).
Counted directly off the source at each revision:

| revision | fixed | classes | functions | methods | total |
|---|---|---|---|---|---|
| `04e58031^` | 9 | 23 | 25 | 13 | **70** |
| `04e58031` | 9 | 23 | **26** | 13 | **71** |
| `2ab72b31` / `9c97fae3` / `00ca2d1c` / `7f10befa` / `2506a81e` | 9 | 23 | 26 | 13 | **71** |

The new key is **`py.b/type`**, added by `04e58031` ("python R2 reception: wrap foreign payloads in
a generated ForeignException") — `git log -S'"type" py.b/type' -- src/cljc/yang/python/antlr/prelude.cljc`
names that commit and no other. The classes table (23) and method table (13) are unchanged across
the entire provenance series, so the provenance primitive adds **zero** runtime keys. The pin went
stale at `04e58031` (2026-11-11) and the last pin edit before it was `cc6789cd` (2026-10-18, 69→70),
which is an ancestor — the arithmetic closes exactly.

**It is not papering a regression.** `type` is genuinely in the source table at base, at position 2,
and the paired `prelude_parity` change (25→26 + the `"type"` element) is the same fact seen from the
other side. Two independent green runs on two hosts assert it. The rider, though, is silent — and
the neighbouring riders that *do* speak attribute the move to a PHP commit and to "the provenance
primitive", neither of which is the cause.

### Numbered items

1. **[blocking-for-the-record, docs-only]** Strike "P9 math builtins" from all four riders and from
   `ce9c6fdf`'s subject. It is a yang.php-only change (`f82afe44` / `a2c4b1b7` touch no Python file)
   and cannot move a Python address. Replace with the measured cause set: `04e58031` + `2ab72b31` +
   `9c97fae3` + `00ca2d1c` + `7f10befa`, merged by `8018005d`.
2. **[blocking-for-the-record, docs-only]** Give `linked_prelude_test.cljc:190` (70→71) a rider
   naming `py.b/type` from `04e58031` — the count is currently the one changed literal with no
   explanation at all, and the obvious wrong guess ("provenance added a key") is what the dispatch
   itself made.
3. **[blocking-for-the-record, docs-only]** Give `prelude_parity_test.cljc`'s `builtin-function-names`
   a rider naming `04e58031` for the `"type"` insertion, and put the attribution in a **commit
   message body**. Precedent `e6380a2f` carries a full body (bisect points, precedent SHAs,
   re-derivation sites, verification counts); `ce9c6fdf` and `588ce3b5` are subject-only, and the
   findings file that holds the real evidence is untracked — so the commit body is the only durable
   record.
4. **[non-blocking]** Close gap A with the one-line shiva slow run above, and record it.
5. **[non-blocking]** Capture rc directly (`&& echo RC=$?`) instead of inferring it from a `set -e`
   shell advancing.
6. **[non-blocking]** Reconcile, or explicitly drop, the two open discrepancies in item 7
   (pysp address vs the nightly's cited value; the 127-vs-124 assertion count).

**Fix hazard — read before editing.** Confine items 1–3 to the four **test** files and the commit
message. Do **not** add these riders to `src/cljc/yang/python/antlr/prelude.cljc`: that file is the
hashed input, and precedent `bb3976f8` shows a re-pin round touching it (7 lines). Editing a source
docstring re-mints every address in all four pin files *and* the corpus, i.e. the entire round would
have to be regenerated and re-verified on both hosts. Test-file docstrings are inert — none of the
pinned addresses is computed over a test file.

### The single strongest reason this is safe to land

**The corpus delta is arithmetically closed and self-identifying.** Ten addresses out, ten in, 182
byte-identical; the five `yang.site` blobs keep their exact 632-byte size (pure re-hash); the five
`yin.module` blobs each grow by **exactly 78 bytes**, which is to the byte the CBOR encoding of
`:cell/provenance` (37) + `:cell/new-provenance` (41); 5 × 78 × 2 hex chars = 780 = the exact file
delta; every non-blob line that differs keeps its exact length; the committed file's SHA-256
reproduces the generator's two-run claim independently; and all five new module blobs contain
`provenance` where none of the five old ones do. Regenerated data cannot be hand-faked into that
shape, so no value in this round is wrong — the only defect is *why* the tree says the values moved.

### The single strongest reason it is not safe as-committed

The tracked tree now records a **false cause** in five places (four in-file riders plus `ce9c6fdf`'s
subject) for Python address movement, and the one count change that most needs explaining (70→71)
explains nothing. The next seat who bisects a red Python pin will be sent into `yang.php`. That is
precisely the failure the attribution discipline exists to prevent, and unlike the values it will not
be caught by any test.

**Recommended disposition:** land the values (already done, `588ce3b5` == `origin/master`), then a
single follow-up `docs`-class commit fixing items 1–3 in the four test files, with a body carrying
the cause set, the 78-byte accounting and the verification matrix above. No re-mint, no re-run.

## 2026-11-13 — r3 rider attribution correction

Fresh worktree based on `origin/master` at `588ce3b5adccd6ec751fc23b626a7c4427a6f099`; pushed `python-golden-remint-r3` at `74689f1b4316b35cb12b7e71c85e2a044457afb5`. Four test files changed, 15 insertions and 10 deletions, entirely in explanatory strings. No golden values changed.

Verified `runtime-keys` derives builtin keys from `builtin-functions`, and `04e58031` adds `["type" py.b/type ...]`; the extra runtime key is `py.b/type`, not the private `dsg__condition-exception` provenance mint helper. The same commit introduced the `type` builtin and the 25-to-26 count increase.

Checks: `git diff --check` rc 0; `clj -M:kondo --lint` on all four files rc 0, `errors: 0, warnings: 0`. No tests or generator runs, as requested. The commit body records the cause set, 78-byte accounting and review verification matrix, including the disclosed Node linked-prelude slow-assertion gap and inferred return codes.

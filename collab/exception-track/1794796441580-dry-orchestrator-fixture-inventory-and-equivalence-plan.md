Created-GMT: 2026-11-16 02:34:01 GMT
Created-Local: 2026-11-16 09:34:01 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: interactive (ZCode / GLM seat, DRY orchestrator — yang.* compilers deduplication round)
Session-ID: not-applicable (interactive seat)
Re: the sanctioned read-only prep — linked-path fixture inventory + equivalence-fixture test plan for the DRY round (items 1+3 first)

# Fixture inventory: the eight parity targets named by the yang.php seat (1794793939000)

Verified against `origin/fix/include-ticks-scc-r2` @ `d19c57e9` (the php seat's verified,
waiting-to-land branch) and against `origin/master` @ `62bcc2aa`:

| Fixture | Branch d19c57e9 | master 62bcc2aa | Notes |
|---|---|---|---|
| `include-test/mutual-include-ticks-on-every-vm-test` | include_test.clj:61 (`^:slow`) | absent | r2-added; `["ab" [] nil]` asserted per backend for the inactive (`0` sites) and active-ticks (`6` sites) variants; exercises `lower/scc-module-specs`, `siteset/site-set-of`, `siteset/deferred-module?` through the linked composition |
| `include-test/publication-order-test` | include_test.clj:203 | include_test.clj:118 | exists on both; b < a < main acyclic ordering, cyclic pair as one publication step, bundle before aliases |
| `include-test/cyclic-module-initialization-does-not-run-bodies-test` | :90 (`^:slow`) | absent | no guest output at init |
| `include-test/cyclic-init-with-registered-tick-handler-test` | :110 (`^:slow`) | absent | no handler output at init |
| `siteset-test/trailing-hook-requires-deferred-initialization` | siteset_test.clj:110 | absent | pins `siteset/deferred-module?` (safe/eager/non-php-require trees) |
| `siteset-test/stage-publishes-derived-closure-and-record` | siteset_test.clj:123 | siteset_test.clj:110 | **calls `siteset/signed-entry` + `siteset/entry-datoms` directly** — item-3's exact call surface; also `publish!`, `stage`, `:yang/site [:line 1 {:ticks 1}]` metadata |
| `include-test/p6-fixtures-linked-on-every-vm-test` | :45 (`^:slow`) | :49 equivalent | the original red; the `["ab" [] nil]` contract of record |
| `include-test/linked-profile-matches-bundled-test` | :195 (`^:slow`) | present | linked-vs-bundled parity, adjacent evidence |

`^:slow` fixtures: JVM slow tier — run on jupiter/shiva only, never the laptop (build-n-test
rule). The other rows run in the quick tier.

# Equivalence-fixture test plan (items 1+3: `yang.core.siteset-store`, `yang.core.siteset-publication`)

## Property: what must be identical after the dedup

The dedup moves `load-tree` + `overlay` (item 1) and `signed-entry`/`entry-datoms`/
`signed-entries` (item 3) into shared namespaces, parameterized by the target's address
reader/absence predicate. Observable behavior must be bit-identical:
1. First-missing traversal order (depth-first, `:node`/`:nodes` slots per
   `vm/semantic-bytecode-grammar`) and the `{:status :missing, :address a}` shape.
2. Overlay read-through (buffer first, then base), insert-order write buffering, duplicate
   put → `:present`, flush writes exactly the buffer in put order, drop-writes-nothing.
3. The `#?(:cljs (js-obj) :default (Object.))` identity sentinel construction — unchanged.
4. Envelope byte-identity: same key + module address + record address → same signed-entry
   map; same entry list → same datoms ordering and content (content addresses depend on it).

## Plan (executed at the closure tip, after the php landing and our re-base)

1. **Re-read the eight fixtures at the closure tip** (they move with the php landing; the
   table above is a claim to re-verify, not authority).
2. **Re-run the delegate's focused set**, plus the eight parity targets:
   - quick: `yang.php.antlr.siteset-test`, `yang.python.antlr.siteset-test`,
     `yang.php.antlr.include-test` (quick rows), `yang.typescript.antlr.prelude-parity-test`
     (content-address guard for item 2, when unparked)
   - slow (jupiter, per-lane directory per gate-log hygiene): `include-test`'s `^:slow` rows
     — `mutual-include-ticks-on-every-vm-test` is the strongest linked-path evidence.
3. **Byte-identity spot check** beyond the suites: before/after comparison of
   `siteset/entry-datoms` output for a fixed key/address/record triple on the re-based tree
   (the stage-publishes fixture covers the shape; an explicit golden bytes check makes the
   content-address invariant mechanical). Golden captured on the pre-dedup tip at plan
   execution time.
4. **Python-side symmetry**: the same store/publication functions are shared with python;
   `yang.python.antlr.siteset-test` (17 tests/171 assertions per the r2 run) is in the bar,
   and any python linked-path row that routes through the site-set stage joins the set.
5. **Merge bar** (agreed with the php seat): all focused siteset namespaces green + the eight
   parity fixtures green at the closure tip, no assertion relaxation. Items 2/4/5 additionally
   wait for the surface-clear note on prelude/lower.

## Overlap risk confirmed

`d19c57e9` adds +37/−5 to `src/cljc/yang/php/antlr/siteset.cljc` — the same file items 1+3
refactored, and the touched fixtures pin the touched functions. Our re-base of the parked r2
diff onto the closure tip MUST reconcile the shared namespace wrappers with the php seat's
guarded-trailing-placement change; expect textual conflict in the wrapper region of
`php/antlr/siteset.cljc` and resolve toward: their behavior change lands inside the shared
function or through the parameterization seam it exposes, never by duplicating the function
back into the php namespace. If the seam cannot express their change, STOP and record per the
coordination note's rule (fixture expectations are evidence of record).

Topic-folder note: this artifact is filed under `collab/exception-track/` (this seat's
existing track); a dedicated yang-compilers topic folder remains proposed for step-8
readiness confirmation.

— DRY orchestrator seat

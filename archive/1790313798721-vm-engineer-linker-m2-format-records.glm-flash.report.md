# M2 implementation report (GLM-5.3-Flash subagent, verbatim extract)

M2 implemented — four format records, identity-directed match, bounded worklist
(worktree /Users/sto/workspace/datomworld-ucf-phase2, branch ucf-phase2; nothing
staged or committed; diff = exactly the 4 file-box files)

src/cljc/yin/vm/linker.cljc
- :hash-fn replaced by :identity-fn + :identity-matches-fn; :free-names-fn renamed
  :obligations-fn; :contract, :row-defect-fn, :parts-fn added — all four records per
  section 5: stack-format (:232, "b1"), register-format (:245, "r1"), semantic-format
  (:259, "v2", jing/segment-key identity), ast-format (:276, "v2", vm/validate-rows
  whole, row-local-defect per row, body-child-ids parts).
- New exports: semantic-free-names (:141 — the UCF 7.3.2 :var scan with transitive
  out-of-line closure binding, all-free fallback for non-conforming layouts; validated
  12/12-equivalent to completion/segment-free-names before landing), row-local-defect
  (:195 — runs vm/validate-rows' four row-local rules over one body, whole-tree rules
  never reported), plus private tree-free-names (:180, the shipped vm/free-names over
  the assembled tree) and body-child-ids (:211, moved from content.cljc).
- fetch (:513) now runs step 0 admission (:contract-mismatch before any lookup), step 1
  index, step 2 as the bounded BFS worklist (fetch-parts :458; per-part read ->
  address-verify -> :row-defect-fn before any child is enqueued; :max-parts/:max-depth/
  :max-bytes each refuse :parts-limit naming bound and address; fetch-one :433), step 3
  :identity-matches-fn (still runs for the storage-derived formats), step 4 over the
  assembled {id [id tag & slots]} tree for the multi-part format, step 5 obligations vs
  receiver, step 6 returning :obligations and, for trees, :parts — no branch on :format
  anywhere. New optional 6th arg opts (:contract + bounds) — the spec pins no M2 fetch
  signature; bounds must reach fetch for :parts-limit. publish! (:596) mints via
  :identity-fn. Refusal vocabulary gained :contract-mismatch, :parts-limit (:39).
  Fallbacks/pairing untouched.

src/cljc/yin/vm/content.cljc — load-rows, fetch-vector, body-child-ids, the absent
sentinel retired; materialize-tree!/materialize-vector! kept verbatim as the mint side
("publish! for those formats", section 9); docstring rewritten (ASCII).

test/yin/vm/linker_test.cljc — the B6 matrix now runs for all four formats via the
extended formats table (fetch-returns-the-verified-image :304, absent :373-ish,
corruption, RPC, DHT :495 with a per-address GridNet serving mode, :hash-mismatch :548
including the swapped-index and foreign-identity-under-:sha256 cases, structural defect
:597 over all four, unresolved/shadowed, stream + WebSocket transfer, execution parity
:900 now including walker and semantic-VM loads). New M2 deftests: contract-mismatch
with a read-counting store (:340), vector index entry at another valid vector (:569),
parts/obligations payload (:643), no :parts for single-payload (:661), absent child row
naming it (:673), corrupt child row naming it (:686), bad-tag child with zero reads of
its slots (:702), all three bounds -> :parts-limit (:725), :sha256-minted vector and
two-row tree verifying under their own algorithm while the default is :blake3 (:762).

test/yin/vm/content_test.cljc — the retired loaders' callers moved to fetch with
ast-format/semantic-format (:54, :64); corpus round-trip, file reopen, metadata,
shared-subtree dedup and the alias-column assertions all preserved through the new
reader; load-rows-and-fetch-vector-refuse-bad-content became fetch-refuses-bad-content
(:200, same refusal classes as refusals, not throws).

Verification (JVM, mise, full suite in the worktree): final run
Ran 2036 tests containing 180792 assertions. 0 failures, 0 errors. — baseline at HEAD
was 2027 / 180638 / 0 (runs 2-3); +9 tests, +154 assertions, no assertion lost.
cljstyle check clean on all four files; kondo 0 errors / 0 warnings; every added/edited
line pure ASCII and <= 80 columns.

Pre-existing behavior touched: (1) the retired loaders and their throwing surfaces
(spec-mandated) — their test assertions were re-expressed as fetch refusals, not
dropped; the content-test corpus receiver binds the corpus's deliberately-unbound names
(x, f) as bare legacy primitive entries, since a free-env binding refuses
:shadowed-free (D11). (2) The DHT test's GridNet gained an any-address serving key; H/R
behavior unchanged. (3) Known drift left untouched: completion.cljc vm-fetch's
docstring still cites the retired loaders — completion.cljc is not in the file box, and
its :fetch contract (address -> payload | nil) is unchanged. (4) Pre-existing flake
observed, unrelated to these files: one baseline run reported 1 failure and one
intermediate run showed the-deposit-medium-outlives-the-socket
(test/dao/stream/slice_test.clj, a timing-bound WebSocket bootstrap test) fail once;
three later full runs including the final one are fully green.

Status: COMPLETE

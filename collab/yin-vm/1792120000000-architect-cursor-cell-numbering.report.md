Completed-GMT: 2026-10-11 19:30:35 GMT
Completed-Local: 2026-10-12 02:30:35 Asia/Bangkok

(Host clock as read by `date -u` at completion; the brief's Created-GMT
of 2026-10-12 10:00 is later than this host's clock.)

# Architect report: discovery-order numbering for cursor cells

Deliverable: `docs/design/yin.vm.semantic-register-vm.cursor-cell-numbering.md`
(about 420 lines: findings F1-F7, the rule D1-D5, a proof, amendments
A1-A10, acceptance items 12-20, owner questions). Nothing staged or
committed. The 6a worktree was not edited; its code was read through the
shared git object store (`git show srvm-phase6a:…`, commit `d77da9f0`),
because the sandbox blocks the p6a path.

## Answers in brief

1. **Positions.** Every non-guest position is ordered: fixed slots
   (`:act`/`:tail`/`:kframe`/`:wait`/`:env`/`:closure`/`:reified`/
   `:pending` C and V slots, root slots 5/6/8/15), ordered vectors
   (`frames`, `:k`, `:vec`, `:list`) and keyed rows with structural keys
   (`:bindings` by symbol bytes, `:store` by key bytes, `:window` by
   register, `:parked-table` by P, installs by name, module stores by
   M). The only unordered positions are guest `:set` elements and guest
   `:map` entries (keys are V). The environment's key order is canonical
   (Jing bytes of the symbol, the emitted order). Table in doc F1.
2. **Rule.** Depth-first pre-order traversal (D1), the n-th unnumbered
   cursor cell met gets `cc-n` (D2); no first-occurrence spelling, every
   occurrence stays `[:cursor-cell :yin.k/cc-n]` and the census stays
   the contents table; install children are numbered as their own
   tasks (unchanged); the positions step is subsumed because discovery
   order already encodes each cell's first position.
3. **Unordered containers.** Entered in bytewise order of a content
   encoding `CE` that spells numbered cells by number and unnumbered by
   body (stream identity, descriptor, position). Equal runs are tie
   groups, deferred, re-examined to a fixpoint as other occurrences
   assign numbers (resolves the review's `'a #{X Y}`, `'b X` case). The
   residue is a tie group whose unnumbered cells occur only in tie
   groups. Orbit rule (D4): if each such cell's occurrences all lie in
   one element, enter in any order (lemma: rows are order-independent).
   Otherwise refuse (D5). Recommended kind: `:yin.k/non-portable`
   `:unresolved-cursor-cells` at lift, `:yin.k/undecodable`
   `:unresolved-cursor-cells` at decode, **not** `:resource-limit`:
   it is not a budget and §10.10 forbids a resource refusal standing in
   for a canonicality verdict. The refused class (the loop plus 2-cycle
   and the seven-cell fixtures) is acceptable as a documented limitation
   and can be narrowed later without changing any id numbered today.
4. **Canonicality.** Proved in doc §3: structure-only and
   renaming-invariant by induction (no step reads a source id);
   permutation-invariant because unordered containers are read in `CE`
   order, plus the orbit lemma; terminating in ≤ n fixpoint rounds of
   linear work; no label growth. Prototype: 400 random states, renamed
   and shuffled, 0 invariance failures (6a also 0); 1 refusal vs 6a's 2.
5. **Impact.** Delete in `rows.cljc` (6a): `check-label-bytes!`
   (212-230), `positions` (285-325), `classes` (326-330), `refine`
   (331-337), `search` (338-354), label seeding (252-255), budget keys
   `:search`/`:label-bytes` (200-202). Keep `ce` (257-284) with the
   numbered/unnumbered spelling. Add traversal, fixpoint, orbit rule,
   refusal (60-80 lines). Unchanged: emit/emit-numbered/canonical-under,
   census order (140-156), decode's recompute-and-compare (570-575),
   numbering before frames (576-578), `:bytes`/`:rows`/`:depth`/`:work`.
   Pinned ids: `fab223…` (two-cell fixture) and `c4a7c7…` (frame)
   **unchanged**; the ten-cell census root unchanged; `55ff168e…` (loop
   plus 2-cycle) becomes a refusal; new pins `a3713a5e…` (review
   counterexample), `4993b5b1…` (`[x x y]`), `536c810b…` (`[x y y]`,
   same as 6a), `07eda35d…` (`#{X Y}`, same as 6a), `da69c679…`,
   `49d4478c…` (orbit fixtures). The seven-cell fixture goes from a
   `:label-bytes` refusal to a D5 refusal. Effort: one slice (code +
   tests + amendments A1-A9), one implementation round, one review.
6. **Portable ids in the VM.** Every candidate (id counter, creation
   sequence, hash of occurrence and counter, random) names history, not
   structure: structurally equal states carry different ids, so "same
   structure, same hash" fails; the cross-engine law would need engines
   to agree on minting history; lower must keep a second name beside
   its fresh resource entry and relift needs a collision-free global
   scheme. Recommend against. Discovery order gives the local identity a
   structural name instead (the n-th cell met, the de Bruijn move).
7. **Recommendation: (a)**, adopt now while 6a is unmerged and 6b
   unstarted. (b) costs nothing now but burdens 6b, the harness and
   every reviewer with an exhaustive search and growing labels; (c)
   needs VM, census, law and harness changes across three or more
   rounds.

## Verified in code (file:line)

- `rows.cljc:37-57` row table (position inventory); `:148-156`
  `ordered-slots` (keyed structural order); `:140-145` census numeric
  order; `:185-189` child numbered in its own scope; `:236-355`
  `canonical-numbering` (labels, `ce`, `positions`, `refine`, `search`);
  `:200-202` budget keys; `:212-230` label-byte bound; `:570-578`
  decode recompute-and-compare, then `validate-frames`.
- `rows_test.cljc:28` fixture; `:159` alias fixtures; `:171` symmetric
  pair with `{:search 1}`; `:206` numbering before frame defect;
  `:307-329` pinned roots incl. loop plus 2-cycle; `:331` ten cells;
  `:392-404` seven-cell fixture refused by `[:label-bytes]`; `:406-421`
  label-bytes test.
- `engine.cljc:227-230` `gen-id`, `:305-313` `cursor-cell?`, `:316-328`
  `issue-ref`, `:538-573` cursor minting with `seq-n`, `:2317-2323`
  `gensym` (for Q6).
- Example state run (`collab/example-suspended-state.clj` through the
  probe project): one cursor cell `:cursor-1` in env `c`, window
  register 2 and the `:next` pending; all ordered positions.
- 6a tests reproduced in the probe project: 21 tests, 324 assertions,
  0 failures. Prototype `collab/numbering-probe/src/probe/discovery.clj`
  (run: `clojure -M -e "(require 'probe.discovery) (probe.discovery/-main)"`
  from `collab/numbering-probe`): outputs as tabulated in doc §6; hand
  derivations for the two-cell fixture, review counterexample and
  `[x x y]` match the codec.

## Unverified

- Uncommitted changes in the p6a worktree, if any, are invisible to me
  (read via the branch tip only).
- The prototype numbers install children through 6a's `emit-numbered`,
  which calls 6a's `canonical-numbering` for the child; the random
  generator uses no children. Child scope is unchanged by design.
- The random generator does not run `rows/decode` on its states (decode
  recomputes 6a's numbering and would mismatch by construction);
  acceptance item 18 requires decode in the real test.
- CBOR symbol order (length-first) was not inspected; the two-cell
  derivation holds under every candidate entry order (doc §6).
- Three-host runs, kondo: not run (documents only).

## Owner questions

1. Accept the D5 refusal class as a documented limitation of body
   version 3 (recommended yes)?
2. Refusal kind `:unresolved-cursor-cells` (recommended) versus the
   orchestrator's `:resource-limit`?

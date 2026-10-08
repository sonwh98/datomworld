# yin.vm.linker.md spec completion audit (master @ df7cf1f4)

Role: QA and verification subagent. Read-only audit; the only file this
audit created is this report. Scope: the landed state of
`docs/design/yin.vm.linker.md` (r11 consensus) on master, including the
linker-over-DHT design pass (`docs/design/yin.vm.linker.dht.md`, slices
L0-L5, landed as df7cf1f4) and the interim week's refactors. Test
suites were NOT run; lane evidence is cited from the orchestrator log
(entry "2026-10-01 21:10 +0700", tree master@df7cf1f4): JVM 2,808 tests
/ 225,640 assertions / 0 failures (post-rebase re-verify), Node 2,656 /
91,265 / 0, CLJD 2,568 passed / 0 failed. HEAD 6fb8d704 is df7cf1f4
plus two docs-only commits; code state equals df7cf1f4. The working
tree carries uncommitted in-flight edits (design docs, dht sources and
tests) belonging to a parallel session; this audit judges committed
master only.

## 1. Milestone verdicts (spec section 9)

M1: SATISFIED. Rename landed as f8d051b9
("rename yin.vm.debruijn-linker to yin.vm.linker (M1)"). No
`debruijn_linker.cljc` remains; `src/cljc/yin/vm/linker.cljc` and
`test/yin/vm/linker_test.cljc` exist. The B6 predecessor's status line
reads "superseded by docs/design/yin.vm.linker.md (M1 rename,
2026-09-25)".

M2: SATISFIED. Landed as 19719d66. All four format records
(`ast-format`, `semantic-format`, `stack-format`, `register-format`)
exist in `src/cljc/yin/vm/linker.cljc` (lines 652-733) with the full
spec slot set: `:format`, `:contract`, `:identity-fn`,
`:identity-matches-fn`, `:row-defect-fn`, `:validate-fn`,
`:obligations-fn`, `:definitions-fn`, `:applications-fn`, `:parts-fn`.
Step 2 is the bounded worklist (`:max-parts`, `:max-depth`,
`:max-bytes`). M2's named tests are all present in linker_test.cljc
(absent child row, corrupt child row, bad-tag child fetches no slots,
parts-limit, hash-mismatch on a swapped index entry, sha256-minted
payload verifying under its own algorithm). `load-rows` and
`fetch-vector` are gone; `yin.vm.content` itself was later dissolved
(237f020a, interim week) with the grammars moved beside the formats.

M3: SATISFIED. Landed as 9779044c, later rebuilt over
`dao.jing.content` (8894d81f) per the dao.stream.remote plan. The
stepped core exists (`link-state` 1419, `request-link` 1579, `step`
1832, `abandon` 1878); `fetch` is host policy over a
`{:state :drive}` runtime (1920) and holds no content handle;
`verify` (1355) and `discharge` (1032) are exported. The M3 test list
is landed in `test/yin/vm/linker_step_test.cljc`: the refusal matrix
through step, the one-part-per-step pending sequence,
function/handle requests refused `:invalid-request`, the local-fetch
traffic test over a handle-free state (`a-local-fetch-is-exactly-
the-stepped-traffic-over-a-handle-free-state`), and the DHT handle
behind the served boundary (`the-dht-behind-the-served-boundary-
answers-a-link`).

M4: SATISFIED (one optional delivery path explicitly deferred).
Landed as cb96de0b (authority slices A1/A2), 44cda2cb (manifests and
derivation records), c2f9d56e (require lowered through the linker
with sealed private resources), d01466c8 + e1cf4217 (UCF amendments),
merges m4-s3/s4/a3/s5, plus 3c64be22 (attach/discharge hardening).
Evidence: `src/cljc/yin/vm/linker/authority.cljc` implements the
section 8.2 policy (signature and attested-log proof kinds, per-
principal sequence with floors, dedup/equivocation/replay passes,
`honored-seq`); `module.cljc` has `register-host-module` (profile
class enforcement, host module entered as an already-linked manifest)
and `link-module`; `engine.cljc` has the two wait states, the install
child with the section 7.3 phases (loading/running/parked/validated/
linked/refused, `advance-install` 1204, `link-install` 1141 with the
four acts: lift, origin check `:foreign-image`, attach, per-receiver
lower), module stores (`:module-stores`, active `:store-of` context),
the private `:resources` table and sealed references
(`:forged-resource-reference`). The r6/r7 hash-safe restore and
grow-only returns are in the kernels with an offset table
(`attach_image_test.cljc`, 24 tests). The M4 test list is covered
across linker_require_test.cljc (27 tests), linker_manifest_test.cljc
(20), linker_authority_test.cljc (17),
linker_authority_ingestion_test.cljc (9), attach_image, effect_forgery
(9) and rule_r (22). Deferred (recorded by the subordinate DHT design,
section 13): resolving a transitive require by pinned address and
delivery of a dependency closure as one ordered response (spec 7.4's
optional ahead-of-time closure); SCC identity remains an open decision
(spec section 12).

M5: SATISFIED. Landed as 42ad2666 ("Wire the linker into yin.repl so
require works at the prompt") with `src/cljc/yin/repl/link.cljc` (the
interpreter box; link pair per VM, content pair per source; step 5b
deferred to the receiver per 7.2 step 7), `test/yin/repl/
require_test.cljc` (21 tests; `a-require-at-the-prompt-links-installs-
and-resumes-test` runs a doseq over all four backends; the H and R
backends link one manifest B0-equal). The clock-free pending run with
`(abandon)` and the `:link-policy` composition (`:manual` default,
function, `:keep`; `:lease` refused until dao.lease wiring) match the
spec's open-decision note and `yin.repl.link-policy.md`. The DHT
source composition (L3-L5: require parks and completes on the load's
own event, publish by signed name from indexed code, end-to-end
process test) extends M5 per `yin.vm.linker.dht.md`.

## 2. File box (spec section 10)

All items landed. Renames: linker.cljc and linker_test.cljc (M1). New:
`linker/authority.cljc`, `yin/repl/link.cljc`,
`require_test.cljc` (repl), `linker_manifest_test.cljc`,
`linker_authority_ingestion_test.cljc`, `linker_step_test.cljc`,
`linker_authority_test.cljc`, `linker_require_test.cljc` -- all
present. The DHT pass added four namespaces beyond the box
(`linker/{closure,dht,publish,sign}.cljc`, sign vectors), per its own
slice plan. Edited: module.cljc, engine.cljc, the four kernels
(attach-image, offset table, `:store-of`, frame changes), ffi/
completion/await (private `:resources`), repl.cljc, and the UCF
amendments -- all landed. "Must not change": `image-hash`,
`register-hash`, the opcode tables and the kernels' decode/dispatch
are untouched by linker work; the storage encoder did change when the
CBOR landing swapped `segment-key` onto canonical CBOR (3ddaa21b),
which the spec itself anticipates for storage-derived identities
(section 3). Exports named by the box (fetch, verify, discharge,
link-state, request-link, step, abandon, publish!, the four formats,
manifest-format, record-format, scanners, row-local-defect,
index-from-datoms, address-attribute, trusted/verifying-fallback,
refused, refused?, ok?, refusal-reasons) all exist in linker.cljc;
`local-runtime` was added by L0.

## 3. Completion criteria (spec section 11)

1. One fetch and one step, no format branching: SATISFIED. fetch/step
   dispatch through the format record; the only case-on-format in the
   file is the verifying policy's re-lowering (`relowered`, 2126) and
   the manifest flow's semantic recomputation -- the per-format
   re-lowering the spec itself prescribes (8.1), not fetch/step
   branching.
2. No global loader/registry/callback/cache: SATISFIED. linker.cljc
   holds no atom/defonce; all state is `link-state` or arguments; the
   request key set refuses functions and handles; streams carry plain
   data.
3. Tri-host parity, 0 kondo, clean cljstyle: SATISFIED on the
   df7cf1f4 lineage by the orchestrator's lane evidence cited in the
   header (suites not re-run by this audit).
4. ASCII and <= 80 columns, both files: PARTIAL. Both files are pure
   ASCII, and linker.cljc respects the bound, but the spec carries two
   over-long lines added by post-r11 edits: line 791 (94 cols, the
   dao.jing.content request vocabulary) and line 1750 (89 cols, the
   r-revision ingestion sentence).
5. B6 criteria for all four formats through fetch and step:
   PARTIALLY SATISFIED. The refusal matrix, B0 equality of fetched
   versus local execution, fallbacks, and the DHT-peer mismatch case
   are covered on all formats (linker_test.cljc). B6 criterion 1
   (cross-host transfer over remote streams, receiver holding only
   identity and index) lost its linker-level test: the
   WebSocket-transfer test was retired with `dao.jing.remote` when
   `dao.jing.content` replaced it (8894d81f; the retirement note is in
   linker_test.cljc before `images-transfer-over-dao-stream`).
   Remote-stream content is now exercised at the dao.jing.content
   layer (test/dao/jing/content_test.cljc, content/step_test.cljc),
   not through the linker.
6. Identity-not-requested refused everywhere, every algorithm:
   SATISFIED (wrong-program-at-the-indexed-address, a-vector-index-
   entry-at-another-vector, sha256-minted-payloads-verify-under-their-
   own-algorithm, pinned-identities-are-host-independent).
7. `:contract-mismatch` before validation: SATISFIED (request-defect
   orders shape, format record, then contract; a-request-naming-
   another-contract-is-contract-mismatch; the-format-record-is-
   admitted-before-the-contract).
8. Malformed row refused before children; bounds produce
   `:parts-limit`: SATISFIED (tree-with-a-bad-tag-child-fetches-none-
   of-its-slots, max-bytes-is-checked-before-decode, the-parts-budget-
   is-enforced-at-enqueue-time, exceeding-a-composition-bound...).
9. Local fetch produces exactly the stepped traffic and holds no
   handle: SATISFIED (a-local-fetch-is-exactly-the-stepped-traffic-
   over-a-handle-free-state).
10. require on four backends over ring buffers; the JVM WebSocket
    content path: PARTIALLY SATISFIED. The four-backend ring-buffer
    require is landed and green (require_test.cljc; linker_require_
    test.cljc). The WebSocket clause is not satisfiable today: the
    content-pair WS transport was retired with dao.jing.remote
    (8894d81f) and not re-established; see item 5.
11. Registry holds only the portable slice: SATISFIED by construction
    (module/link-module and engine/link-install publish
    manifest/derivation/slice/stores; register-host-module enters the
    slice; no code that arrived by linking is held as a function).
12. UCF amendments (7.4.1, 7.4.3, 7.5.1 `:yin.k/binding` and
    `:yin.k/store-of` and sealed references, 7.5.1/7.5.3/7.6.2
    resource decode targets and store model): SATISFIED; all present
    in yin.vm.universal-continuation-format.md (committed d01466c8,
    e1cf4217).
13. Swapped-image and swapped-name refusals before load: SATISFIED
    (linker_manifest_test: records-leading-from-another-tree, a-record-
    claiming-another-tree-s-image, a-manifest-declaring-another-name).
14. Authority policy at a fixed snapshot: SATISFIED (two-proven-
    assertions-refuse-ambiguous-name; bare/bad-signature/attested-
    copied unauthenticated; undeclared-principal-ignored; snapshot-
    advance-rebuilds-never-rereads; the ingestion suite adds datom
    permutation invariance and orphan-proof joining).
15. Occurrence dominance rules (5a): SATISFIED (use-before-every-
    definition, conditional-definition-discharges-nothing, definition-
    dominating-every-application, application-before-definition
    retains, body-occurrence-with-no-application-site, unreadable-
    vector-layout-degrades-conservatively).
16. Distinct ids for two children and the root: SATISFIED
    (install-children-and-the-root-mint-distinct-ids-test).
17. attach-image, hash-safe restore, grow-only returns, rebasing:
    SATISFIED (attach_image_test.cljc: parked entries restore after
    attach at the same pcs; the register call in flight survives a
    nested attach; continuations lifted after attach rebase; two tasks
    of different lengths each apply the export).
18. Verifying vs trusted derivation policies: SATISFIED (an-
    unimplemented-profile-is-unverified-derivation-when-verifying; a-
    tree-the-lowering-cannot-run-is-unverified-derivation; trusted-
    fallback-names-its-trust; verifying-fallback-re-lowers-the-named-
    root).
19. Contract refusal with no content request and no validator:
    SATISFIED (admission precedes step 1 in request-defect; covered by
    the step tests).
20. Re-export origin, `:foreign-image`, `:binding-mismatch`: SATISFIED
    (an-origin-the-scheduler-never-verified-is-foreign-test; a-marker-
    of-another-binding-discipline-is-refused-test).
21. Authority duplicate/replay/equivocation/retraction: SATISFIED
    (exact-duplicate-envelope-is-honored-once; replayed-sequence;
    equivocating-pair; dangling-retractions; signed-retraction-removes-
    only-the-assertion-it-names).
22. Four-way manifest through four kernels; schema; contract-missing:
    SATISFIED (one-four-way-manifest-links-through-all-four-kernels;
    another-schema-version-is-a-schema-defect; a-derivation-without-
    a-contract-entry-is-a-defect).
23. Module stores, forged resources, sealed references, resource
    lowering, tail-call input, walker lambda rows: SATISFIED
    (require_test: a-module-closure-reads-and-writes-its-own-store,
    a-module-closure-calling-another-writes-each-store, a-dependency-
    store-crosses-with-the-child-mutations, a-missing-module-store-
    refuses-the-lift, input-after-a-tail-applied-module-closure...;
    effect_forgery_test.cljc; attach_image walker annotation tests).
24. Rule R on every format, contract stamps, omission refused:
    SATISFIED (rule_r_test.cljc, 22 tests; linker_test Rule R refusals
    on all four records; a-manifest-stamping-another-contract-is-
    contract-mismatch; a-request-omitting-the-contract-is-invalid-
    request).

## 4. The DHT design pass: relationship and supersessions

`yin.vm.linker.dht.md` declares itself subordinate to
`yin.vm.linker.md` (status block) and composes with it; it does not
replace the linker's six steps or the stepped core. What it superseded
in composition, all landed L0-L5 (d4d77426, 2dd2733d, 4ef9b729,
b9b00db9, abf7ad9a, df7cf1f4):

- The peer-network row of section 6.1: the linker's content source for
  a DHT composition is the `dao.space.dht` node's own `:local` store
  handle (R1), not a served content client; a module closure is loaded
  onto the node first by the pure closure walker
  (`linker/closure.cljc`, L2), and a link after `:loaded` issues no
  peer request. The M3 "DHT behind serve-content!" test remains valid
  at the linker boundary, as the master spec's 6.1 (amended by
  152ec9bd) states.
- Name authority: the signature proof kind of section 8.2 is made
  concrete as Ed25519 with committed cross-host vectors
  (`linker/sign.cljc`, `sign_vectors.edn`, L0); the attested-log kind
  does not survive this transport (R5). Names resolve from signed
  envelopes in explicitly loaded index snapshots (R4); the same-
  address multi-asserter resolution follows owner decision 4 (R8),
  which refines the fold of 8.2.
- Publishing: `publish-module!`/`module-from-index` replace the test
  helpers; the module is published explicitly by name and export list
  with its tree derived from indexed code (R9, L4), and the footprint
  computation (5.2) feeds `:yin.module/footprint`.
- The section 8.2 policy itself is unchanged; the DHT fold keeps the
  ambiguous-name refusal and honors multiple asserters of one address.

Explicit deferrals the DHT design records (its section 13) that bear
on the master spec: the persistent stepped linker (link-manifest as a
ticker-advanced state machine over the node's rings), transitive
require resolution by pinned address and one-response dependency
closure (master spec 7.4), the `:lease` link policy, key rotation,
step 5a for module-level reads in the tree format (the walker's
scanner retains body reads; the store corpus links on semantic, stack
and register and refuses on the walker -- landed as such in L5's
end-to-end gate).

One documentation staleness: the committed DHT design's status line
still reads "nothing is implemented" although its slices L0-L5 are
landed on master (df7cf1f4). The working tree carries an uncommitted
revision of that document (+378/-138, including a new post-M5
hardening section) from the parallel session; not judged here.

## 5. Overall conclusion

Verdict per milestone: M1 SATISFIED, M2 SATISFIED, M3 SATISFIED, M4
SATISFIED (with the DHT design's explicit deferral of 7.4's optional
one-response closure delivery), M5 SATISFIED. Overall: the r11
specification is implemented and landed on master -- its structure
criteria, refusal vocabulary, authority policy, install machinery and
UCF amendments hold on the df7cf1f4 lineage on all three hosts, with
the linker-over-DHT epic landed as its subordinate composition. The
residue is small and recorded: two over-long lines in the spec itself
(criterion 4), the linker-level remote/WebSocket transfer test retired
with dao.jing.remote and not re-established through the linker
(criteria 5 and 10's second clause), the stale "nothing is
implemented" status line of the landed DHT design, and the deferred
persistent stepped linker and dependency-closure delivery.

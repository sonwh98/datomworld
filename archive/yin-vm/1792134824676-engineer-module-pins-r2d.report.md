Completed-GMT: 2026-10-16 07:13:44 GMT
Completed-Local: 2026-10-16 14:13:44 +07
Coding-Agent: codex
Session-ID: unavailable (provider not exposed)
Role: VM Runtime
Branch: srvm-module-pins
Base: 62121c7e
Brief: collab/1793620000000-engineer-module-pins-r2d.prompt.md

# Module pins r2d: carrier, lower gate, fixtures and docs complete

## Decisions and scope

The r2d address correction is implemented: each module pin carries the entry's
own `(:address entry)` keyword unchanged, or nil for a host entry. Equality is
`=`; no string conversion. Host export profile addresses remain keywords.
Both maps ALWAYS appear in every task's `requires`, including `{}`. Missing
maps refuse `:yin.k/undecodable :requires`. This correction is recorded in the
transport grammar and completed I-8. No additional design stop arose.

The retained r2b/r2c work was preserved. The pending draft patch at
`/tmp/module-pins-r2c-pending-tests.patch` was reused and corrected: valid
context setup, keyword address values, exact malformed decoder paths,
non-conflicting whole-tree requirements, and a lift helper that returns
refusal data rather than throwing. No existing substantive assertion was
removed or weakened. Inline row-root assertions were re-pinned with raw CBOR
proofs; frame-only pins stayed fixed.

## Per-step results

8. COMPLETE. The existing live-free-name discharge walk collects registry
   answers after env, selected store, primitives and installing names. Linked
   entries contribute module/address; host entries contribute module/nil and
   only the used export/profile. A nil host profile refuses
   `:yin.k/non-portable :host-export-profile`. Empty maps always emit.
9. COMPLETE. Decoder and dependency validator require both maps, keyword-or-nil
   module values, keyword host-export values, module symbol keys and qualified
   export symbol keys, plus a reconstruction cardinality guard. Nine published
   malformed cases cover absent keys, non-map slots, non-symbol module keys,
   qualified module keys, bad values and unqualified export keys; exact decoder
   paths are pinned. Existing numeric equality-collapse case is preserved.
10. COMPLETE. `check-modules!` replaces `check-install-admission!`, after
    custody and before attachment. It scans the whole tree, throws terminal
    `:module-pin-mismatch` / `:host-export-mismatch` before any collected
    refusal, then reports one `:yin.k/unsatisfied` with discovery `:complete`
    and only nonempty missing maps. Every unadmitted install child joins that
    collection. No stream attachment occurs on any such refusal.
11. RETAINED / VERIFIED. Driver config forwards `:yin.k/admitted-modules`
    through ordinary lowering and fenced recovery. The parked-installer driver
    regression exercises both absent and admitted config. The lift strips all
    keyword keys in namespace `yin.admit`, matching lower refusal.
12. COMPLETE for this slice. T1–T4 cover used host exports, actual linked
    manifest keyword addresses (`pym.k`), env/store/module-store/primitive
    discharge precedence, complete missing discovery, exact address/profile
    skew, equal satisfaction, extra/missing exports, whole-tree collection,
    terminal priority and zero attaches. Existing T5 gained the complete
    discovery assertion. T8 is a portable Stage E row checking both maps,
    byte-identical re-encoding and exact literal expected maps plus canonical
    byte equality for both terminal refusals and the collected refusal.
    T9/T10 from r2c remain intact. T6/T7 are composition fetch/lease work and
    remain explicitly open outside this slice. No Node/Dart execution claimed.
13. COMPLETE. All five required mutants killed, then source restored. Each
    run used four named pin tests in a fresh JVM; the helper reports test
    counters explicitly (its process exit alone is not the kill criterion):

| Mutant | Named killer | Pass / failures / errors |
|---|---|---|
| Lift records all host exports | `lift-pins-only-registry-answered-live-names` | 33 / 4 / 2 |
| Lower accepts module by name presence | `linked-module-pins-compare-address-before-attach` | 34 / 5 / 0 |
| Lower accepts another profile | `host-export-pins-compare-profiles-and-ignore-unused-exports` | 36 / 3 / 0 |
| Missing discovery reports incomplete | `module-pins-collect-whole-tree-and-prioritize-terminal-mismatch` | 35 / 4 / 0 |
| Attach before module check | `linked-module-pins-compare-address-before-attach` (attach-count assertions) | 30 / 9 / 0 |

Mutant logs: `/tmp/module-pins-r2d-mutant-<name>.log`, with names
`all-exports`, `name-presence`, `different-profile`, `incomplete-discovery`,
`attach-before-check`. No mutant remains.

14. COMPLETE. Re-minted `rows-checkpoint-v3.txt` and `ledger-v2.txt`, plus
    19 inline rows task-root pins. All 53 saved checkpoint packs were decoded
    from `/tmp/module-pins-old-hex.edn`; each new raw pack had ONLY the two
    task `requires` keys removed, resulting content hashes reversed and row
    order restored, and its canonical CBOR compared byte for byte to the saved
    old pack. All 53 comparisons passed; `frame-root` has no task and stayed
    unchanged. The non-canonical fixture retains its deliberate nonminimal
    version encoding, and hash-mismatch retains its deliberately wrong pin.
    All 19 inline task fixtures were captured independently from the original
    source/constructors and passed the same raw CBOR equality, not merely a
    root comparison. All four ledger checkpoint packs passed raw CBOR equality;
    original rows code reproduces `/tmp/module-pins-old-ledger.txt` exactly,
    and the new render equals the new fixture exactly. Its journal changes
    follow only those checkpoint/root references and projection digest.
    Proof logs/scripts are under `/tmp/module-pins-r2d-{mint2,inline-old,
    inline-new,ledger-old,ledger-new}.*`; strip/re-hash implementation is
    `/tmp/module-pins-strip.clj`. No A, descriptor, scanner or non-rows golden
    changed. Full old/new table follows below and is also in I-8.
15. COMPLETE. Transport grammar, continuation §2.5 / §7.1 / §7.2 / Q5, UCF
    §7.6.4 / §7.6.5 / §7.11 and I-8 updated. Q5 and driver seam ledger rows
    closed as implemented in this uncommitted slice; R4's landed status retained.
    Composition fetch/lease row stays open. Linker DHT §7.5.11 already has the
    correct receiver admission and lift-never-carries-sender-evidence amendment
    from the base; no redundant edit made. Lower docstring describes the new
    gate and preserves the flat name-keyed admission limitation.

## Fixture address table

| Fixture | Old address | New address |
|---|---|---|
| first-park | `:segment/blake3-55e427452e8f4391132710f286ca7487489cad4e141a57d4ef0e00fc6cc3211b` | `:segment/blake3-6476ee9f3b8034b8f4531e7a4c8752aaa22252b0c71eef4225ed54d6e551d981` |
| successor | `:segment/blake3-a19cc7890946ecf85620c15cfda10bcb22e91b26a16da411980a1609f41b0be0` | `:segment/blake3-ac85f7f3d7585ec3953d21e9216dd8ed09e969efaf1bad8bf7766cc13047e489` |
| installs | `:segment/blake3-d56bfa712ecfb2c8a6115b854f5ce48648468e8dcefd9096dbeadfbb31b15164` | `:segment/blake3-bbbb24b956178c437ede56b0fed6b5e5a516db3e9a0df3db32e4485f64c8cbbe` |
| parked | `:segment/blake3-90df74e86d05da24b07123ec7a9919a0d84d7519d095181c793686c6d56d350f` | `:segment/blake3-e6760ffc91709f87c5163cfd3b1a80a3ccabb148d841ac95fb02d37e4432d363` |
| halted-root | `:segment/blake3-1911589f713b74cd32fb7f6e5b8b5830c1d8029220e385562a48eeda905ff32e` | `:segment/blake3-427db2c1d2df33061226a34219cb991881f30a12e0bcac471b7c9acf3aecea1d` |
| variant-equal | `:segment/blake3-02512ba47f2df5daea9209eb3473337763fed9fde012bde2b91d3bc08448bd95` | `:segment/blake3-bc5ee607d275e28660a32176f290bbd2f50c3b44e9a2bc26c166d860248b7391` |
| variant-other-root | `:segment/blake3-8357394ab695d9c37d260ca0e834fef28d0a5b92403a1fe7b5bf62f11c2dcb1f` | `:segment/blake3-2692dc553d914382df0a73d6806948c685598e121b94bef7612935abb00c6182` |
| variant-different-intent | `:segment/blake3-8a70333955e8c5f326cc7bb5b6c83d72f4528223922202fdd0a6e92ecd4dc657` | `:segment/blake3-aa96eae24e5e570cad87de9439743364ff3a20424bcf57bc3dc06b3a4ee29bda` |
| variant-counter | `:segment/blake3-a3dba39e660b95fde78d201e4523d2b53178b5bb2ea4b8e7eb92da25c985a716` | `:segment/blake3-9f0d16561ddd5a18307443dd603ebf1ba2784fd4952a51a0ac1a1f0ef61bdc8c` |
| counter-at-bound | `:segment/blake3-c95edb32791a8749d85f1a2f3a442e2b8be997d7bd00e2612c1dedea3188c24f` | `:segment/blake3-7d6a86a9b5d32c2de15324d3cad7384ee28d731c0d1e5101506b6fedef1a937e` |
| hash-mismatch | `:segment/blake3-55e427452e8f4391132710f286ca7487489cad4e141a57d4ef0e00fc6cc3211b` | `:segment/blake3-6476ee9f3b8034b8f4531e7a4c8752aaa22252b0c71eef4225ed54d6e551d981` |
| non-canonical | `:segment/blake3-e8c53ff38a9b2e071704d02c29f381a9efad48b11c1d01010ee3930bb982a771` | `:segment/blake3-4463e32bca3eac46635faba855fa09e08c2ba1bd7b946b055b6442c073fe559d` |
| version-float | `:segment/blake3-2d35a52d211b7bb52d047a7d21453315e7e19a28822eb1f4041624e424fb48af` | `:segment/blake3-0fd10170d3fe436cc62c6739b9a7c577ba5a28867c65a62f18d8b17efa0afed8` |
| version-2 | `:segment/blake3-ebf0b7a50c756ee4790521c9e3757731e82ff622cc4187d0c2f02087f9016750` | `:segment/blake3-e004de28a608b1710d4e6c8056c9e59b88bd41202182626f0e90a54b6439692a` |
| version-absent | `:segment/blake3-663dc955a8754897e577b1c70e61b22deb735d555b08c762ee4ed8373a319f6d` | `:segment/blake3-676b1c7674c9ff3297187ebcc46fcd23c49fd74e5ba20ac4d0ef1f6f2a1f64a4` |
| no-tag | `:segment/blake3-471e0603a07e42cb01db177b418d888c0a7755250ea806ff04c67585fc9bb12e` | `:segment/blake3-9fcc1c1ac0ee00d105c0a4c77f7e62b1f88c5e20e2cfd463be4e8e73c4267809` |
| frame-root | `:segment/blake3-d60d5fe8229761d216bd11758cca433ae1d638f3e4d7d44ba5ee0a837c2aa392` | `:segment/blake3-d60d5fe8229761d216bd11758cca433ae1d638f3e4d7d44ba5ee0a837c2aa392` |
| row-tampered | `:segment/blake3-775034b7ae08c4b725da3ea1e1eeee11c2fae5017a1cc6422765436ab9fc3199` | `:segment/blake3-4665386a5d0f5bca39efc57b02ca7075e5e16db0c8e6fd121afcee5020fbbf3d` |
| bad-kind | `:segment/blake3-6f8190d4565a15a1db5a9d4d4db63d7da15ede48e752369a7e72f1cc014b1a8a` | `:segment/blake3-a6591ea6716950f704e9536febcfeff03d6d34ea16d3bc680344167d3e4fafa9` |
| root-role | `:segment/blake3-a47370033957e1f639dcb888d35365f4039db08310eed366614aed11f01f09a8` | `:segment/blake3-e59c072ad11e22e024be7aa628ccd7dfaf42855c39863a47289c1168298aef04` |
| missing-policy | `:segment/blake3-813ae292f57519863150d1fd5d354c236aece37f7d191ad17d48931e2229d8e3` | `:segment/blake3-b282683f5cf1f820a2a326dd47e3e49dd552631eca0f2bcf04f193edb86d059d` |
| missing-occurrence | `:segment/blake3-8b54d8d60cb0f7f2df360517182e5e61d11fc1b33fe600ad51959a77d91257c1` | `:segment/blake3-fb354867cd646c3bb9bb6077582177e731ea74a5549556100534ef761ef61dec` |
| missing-arbitration | `:segment/blake3-4c41df1a0500802c8270e9994ebe9190d238f02614d46241a065da557391057c` | `:segment/blake3-89276d1ea175cfab23ae2454821e732783bb7ef25ec1560913e11bdcf321528e` |
| missing-next-op-seq | `:segment/blake3-cc9428530949b213bd5c947d90fc0a804f8de7ebc1579b66a019618cce94c1e6` | `:segment/blake3-9aa007fc106d5e984b961c25df93ed4890d37205a72be38ee8fcc3ca6bf8a6f0` |
| fork-policy | `:segment/blake3-a6262e4b3b81e6a7a08c63be8ec1264bbd16d69fe9e4baca6d607adc4fd62316` | `:segment/blake3-4ed3ab0f9183ce6c973bc9183f8d6c671d31c1f00e033628740fd135fab99546` |
| nil-occurrence | `:segment/blake3-1de2d61eb70cfda2a5a1d36ad121548bb32e97cbbc049b0c07f1c2ea4c81312a` | `:segment/blake3-a7ce75bc7b9c2ef36086cd2f346a5135b26fb5a21c16e818db14dbb9174f1f85` |
| occurrence-not-uuid | `:segment/blake3-1a284645645aa5e47fdc4c63eeb2a012f3ec2f10ded7a94547c31464929747cb` | `:segment/blake3-2938ebf530d9abc35f634620bf3bd02664b5c6d2ec08869e56ff9309fd26b509` |
| origin-occurrence-not-uuid | `:segment/blake3-14a869bf4a4e7032a30a3d8b19d7ca382cbbfda6b1acd045cf008059ae4a3574` | `:segment/blake3-ce5ad385d97a680093535bee9470b7bd994ce35cac1b54431274beccaaff9182` |
| malformed-arbitration | `:segment/blake3-53cdc552922f73a1b9ee4700b54e644a95fb9af5a3375dcf6e11d5c2e19c5669` | `:segment/blake3-2a9d7c820735b14b307658065ece189e518c6a8c79d18db7a40b32a1baeccbea` |
| origin-is-self | `:segment/blake3-be847f1643893b65a0fc4f413d4c89386433f9d28efaca48feda2b7b1099e0a4` | `:segment/blake3-31e012e8fb81b41514d6d4dc2a661737614da2d6b812c3935f8f3e986fb33d1c` |
| malformed-origin | `:segment/blake3-7db08d797446b1629c98254ec689639edf951e6bbb948eb25da9b2f3a4c1074e` | `:segment/blake3-31f4302bebe6f54d265fbbac8d6efe2bdd7ca9e0260f27e5e30052b37499a319` |
| origin-nil-emitter | `:segment/blake3-f92492587860214c5a6d15d3721a1659c01cd44dc58a45976967ce38b79059fb` | `:segment/blake3-73cddfb255701b36548eb698b7ac57f80bac8c437db1c7d3d5ea7db7c34e277a` |
| counter-float | `:segment/blake3-8624e221a2a6b17c0aea705bf2b99be1e934bf0722df89464b7714d4ad6902d3` | `:segment/blake3-61a7cf82087568a91708d24b21ca9b37257a1ff6f43f1c92bdcc1990e33076a7` |
| counter-over-bound | `:segment/blake3-908edad2d31f2426ad8122f0e8e5f938b80d949c9b2998c49eaee12685522230` | `:segment/blake3-ca8fa4b0ccfb8576a66b4a0439b470eda9da0e43c80e7bf846b7a76e38e1883d` |
| counter-negative | `:segment/blake3-02b6ba158117d2a2c0696d84e4c0e700c6b8c225d33dec6771d1e77fd993afb9` | `:segment/blake3-0fe84ffe9980965c0901a0fe05a6a937420365704ef5c90f3df7640495f32eab` |
| first-export-counter | `:segment/blake3-3d0f52f46a637bc71f9370f153d1d3081974790dfc34642af1ef698532e414bb` | `:segment/blake3-6dba44e9e9efc3aa5d4a4ebd1236ac17f9120c9f836c5b3dd26a5efd40439895` |
| halted-root-header | `:segment/blake3-320e1d8b88f033a73096545a64c4fa74c1fe5cbd6dbc9c2cdaebee0f194ebca2` | `:segment/blake3-1da4c1ba17674e6b825a7a0f062471f458c3a2ecd69f712d296e78ca3012679c` |
| halted-root-without-origin | `:segment/blake3-8fa2b7652faabf26893499b483d0b94c2e50950faadd6a31e095fa2acf6fd1a8` | `:segment/blake3-ad0a5553f535dedd8c01081a8c2283319b5962713e376caf71970393f64462bf` |
| custody-epoch | `:segment/blake3-401d2721701bedc4033bed80647062a0592958232a47d55370b38ef7453b7abc` | `:segment/blake3-a392fbecad7b253e0a0690dc0a42c0a2ab10e47185b30283f532e75b799f3095` |
| custody-lease | `:segment/blake3-9aef98779c919e4c1a41f5278e77be7e47b133b11f6ac83087d79307becbfcd5` | `:segment/blake3-74466266e6b39b9bace03ba63d5711d728224196991741216ffaf0f6bc2981c1` |
| custody-not-map | `:segment/blake3-3d29f409c18d892f0b553df9ab950460e11a8bdc151a7972f7b3acce1ec6bce4` | `:segment/blake3-789f4de876127be820a3cd1525241d3825dc3aba2909e2d985b33aeac2725a39` |
| child-header | `:segment/blake3-767ec0379e4ba4d8885b7e956c241fff73fac495cbe6a2db5826ae1d4dda13d9` | `:segment/blake3-ef296b519b90a97fb5154b2781cd0618748ced980b23ed5a2632ba6feac8674d` |
| install-without-entry | `:segment/blake3-d8786a650510c9ae020a81924bc2540ea9cf0a0327222a459440060da317ea09` | `:segment/blake3-0de4bc93c7a2e44a34b3235d24cfa77306b8a7d4b66b8e3212e43b89d808e509` |
| op-id-at-counter | `:segment/blake3-66e184533fe4a1b5bc63095c3133b10aaba5684131f1b60f2f9dab1fbda5caed` | `:segment/blake3-84e361819cd79a0c4c640d534c510584345872f54456137efe3405bc4ba26f3a` |
| child-op-id-out-of-range | `:segment/blake3-32de289b7ed1ae55c3ade7283cb67474e25d377f41a1f446e4832d05811772a4` | `:segment/blake3-000ac0472b6280cbb9bc5bcd96330e203bea6fa15d1718ccec6dd110cd2fdf30` |
| duplicate-op-id | `:segment/blake3-f207ceb70ad9e6250d0c1df8592db375b93e0c7e3c6c3210bcb67200fe4d1aed` | `:segment/blake3-2872ed1e74bb0e34eeab876a99e7f8e574053d69afc8f1dc2b347ee30c4df93a` |
| op-id-without-origin | `:segment/blake3-8964674b8260b15843e65fe17947e0bef2b2221584dc0a6471dca40fc216a80c` | `:segment/blake3-0d44ef0b0b10bb0ce136e45bf44bd3d8c57f48fb669755593eb556902a39afdf` |
| op-id-own-occurrence | `:segment/blake3-cdae47e4df1f0ad8347161057dd9408d260b2a598b599530a4c70a8d95081a15` | `:segment/blake3-0a11203ede96db6d91450ba13a6dd8dd11922f742b17efb18d0f1ececc3f5441` |
| op-id-malformed | `:segment/blake3-a75534bc61036b0b4660ad76483a32cd6373b3211db902e43ee3762023c5ea4b` | `:segment/blake3-f8aa256eadf2e7040780a2872334486bf21d1e2aa3f12df4bec395ee73d0885b` |
| op-id-occurrence-not-uuid | `:segment/blake3-c92cd49224a69e03b3c1399ab258a59916c9fb8cb84f975ee94cb0fe71b75069` | `:segment/blake3-78be5e825f451a7ca04717aa572c92b4ee9f6e077763cb82c1cc927f3161ce28` |
| op-id-seq-float | `:segment/blake3-31c112cf188e5208263df2ce755185abb091ae9f25468ec0b553a09224828930` | `:segment/blake3-4832dae2785e0aa69429450d112d0883a0894380767a61128c9b7e560b375bba` |
| op-id-seq-negative | `:segment/blake3-182561edcfc65efc48a78458608325dbb378559added6e7178d69ccd05d8f374` | `:segment/blake3-4a5dba255318f14e7dfae3fbf47d7bfb1078e102e53af89cd7fc8b2f86e21835` |
| fork-op-id | `:segment/blake3-a02c26dc08f351487043a5292ad5f0be276ef44a123fce9ae9f24a308bd5e328` | `:segment/blake3-dcb0611b00a3a1df54c0cd9925574bca90f4e7bd12326787378477eeb4e50d80` |
| rows-test/canonical-roots-are-pinned #1 | `:segment/blake3-fab223d9b3540fdce0ccf2132f8a4041917a158a32295470a4936754742d3ac4` | `:segment/blake3-01c05feeff35291a595f1ff65b4cb912bc7c9ca8acf68d8164b88848053cbe2e` |
| rows-test/canonical-roots-are-pinned #2 | `:segment/blake3-a850fce6317b426090fa65758805c26b3628efe75d16d507095563c1608ab533` | `:segment/blake3-c50089d09d1fd203d23369b225979449fde4a2aeae511eb74fe76c3625c7e80c` |
| rows-test/canonical-roots-are-pinned #3 | `:segment/blake3-4993b5b192de7d903a90f7bc87dde8c7d5ac007c2b013ef62b998dc988282576` | `:segment/blake3-e5c8c82e6079df50b884cbac278d1134aa1c8dd587e9c539bdade7fc93e6669c` |
| rows-test/canonical-roots-are-pinned #4 | `:segment/blake3-536c810b07c7cab1dd812fcb1bbf0c7433dc41af4e39037c5f278e82abf1e032` | `:segment/blake3-e1de5400ed3aca2f8b68390df524135b12ac65deb7be6d18bf324b17ab145c95` |
| rows-test/canonical-roots-are-pinned #5 | `:segment/blake3-e67405ecd326c239a84e29a50ca53dfe503fa6baf20c5b79e685c878a40257d7` | `:segment/blake3-525ba654dc2cc3575abc81b70f399b18b8808c410bbb67bf0f3565ce4d581e14` |
| rows-test/canonical-roots-are-pinned #6 | `:segment/blake3-6e7c7a6b0674c41c21e33d9630d4aaf7fc92e4b30234a9c2a1b8c7bae7792f88` | `:segment/blake3-18ae5ebc90b121ce3ff21c08f61d18b6fb287369b77ac2ab67a3032e0fd744e6` |
| rows-test/discovery-order-numbering #1 | `:segment/blake3-0b9ef61ebd08535b10e136a6452d942fb0598ad2dd5a13665d7756fac69702b5` | `:segment/blake3-e209ab0e6539b30cbfedca526693c7591cb6e96a243252b54a112717a7b0061b` |
| rows-test/discovery-order-numbering #2 | `:segment/blake3-ff124bfc1fd2e6c2b8ce29487204b442b8c1143f71339ccab5efd6e48ccc142f` | `:segment/blake3-137d7203380b85bc7d555951225f653c6bcbc577900fffbd32ac6983f1fae456` |
| rows-test/occurrence-count-separates-members #1 | `:segment/blake3-2d9873a8f860440d056768d02172a59f28856f804677a5ac0eaba8a8b6edbcf7` | `:segment/blake3-8d0639824c8f9cb6dc5ba6a6b7f15e3ab840d15ac003ddd97ad3f4e06814081a` |
| rows-test/occurrence-count-separates-members #2 | `:segment/blake3-726a9fbb35cfe76b6d3caf63c5f7e4ce61d4e73d34efda9c3674eb73db830da1` | `:segment/blake3-2dfc65d2d06637ce2a99732aeefc6634c8831e39a993e98eeb75f3015ae3f444` |
| rows-test/occurrence-count-separates-members #3 | `:segment/blake3-d84480d4d7e186937a9165be103f7cfaf0d3f4ced83ff65f5be3e906a48107a2` | `:segment/blake3-6dc110420a7be0afe3d725f4fa879465321114a7be4777c19cfd6225ac84969b` |
| rows-test/interchangeable-ties #1 | `:segment/blake3-07eda35d34844aa8e1ce89f07395bd877a49e7a69bdabea55bcf22e600671494` | `:segment/blake3-6eff99968d148843d4354e207cb25d302e175654ddc57c1b6d4ebed0aef544de` |
| rows-test/interchangeable-ties #2 | `:segment/blake3-da69c679d10e5356b17cdb24909a43d7475df9114bba9c09a8f98ac1a35b971e` | `:segment/blake3-284223c34bef7b455856c0f946dbbac2671e859dafbd210089ab04dbbd0134a2` |
| rows-test/interchangeable-ties #3 | `:segment/blake3-49d4478ce74e89a973c9640498342dfea85867eff1662b9656f7e6b9bcd44e93` | `:segment/blake3-67dfa215ee3c1cbc0c9a14cf1dc82a82e4aae2f672950d70d83b52c239f520b5` |
| rows-test/interchangeable-ties #4 | `:segment/blake3-18b70de6d4533d5ca797464568b5dd7812ca4b2f36a1e8fb64ef823a2eb3da45` | `:segment/blake3-ff4ab85017768eb2b7bf6347d03e1bb4aeb0d167ff716adcadf1b43cb54b31ae` |
| rows-test/alias-pattern-is-part-of-the-key #1 | `:segment/blake3-387f294a2f1963f2f2241e8527d8698dedff5d50de4f46bb762b456a01375090` | `:segment/blake3-8cc2defe531a8581d4b15f7fabb28aeb9bbacaa16a15bb5f6cba13cdd9643fea` |
| rows-test/alias-pattern-is-part-of-the-key #2 | `:segment/blake3-be62ba43d9d07dc7b5e29375e195ab839ab37b721074af816e540615f6167ee9` | `:segment/blake3-10d92587620fd86b56ccbfd10348c69ebc06903f00afb87772b28d2ce180f42a` |
| rows-test/alias-pattern-is-part-of-the-key #3 | `:segment/blake3-5376f133e83957e727611ee977a7b565e8e3c6b41a916f7a1857ecaf84f83907` | `:segment/blake3-b20e118772567652309a63ace21fb24ea76dd089e389a844d78bb840728eee51` |
| rows-test/alias-pattern-is-part-of-the-key #4 | `:segment/blake3-0193312113d9c4928b148f220ef82a623c397e1b98cd67fac8712d1fc286a032` | `:segment/blake3-415b8a3bebf6b0b21bb054f31c4f6a1bc5e0fc1613605b77addd00f6fd935aef` |
| ledger checkpoint 1 | `:segment/blake3-860855f35aae9d61ca1b7ce21ed7c6cacb5fb7d536b5f363d42f604a694b74fa` | `:segment/blake3-e674be239b6dd62e5b3b279b43d11d7d5fe5d853fe93becb92a8a71a6d6399f7` |
| ledger checkpoint 2 | `:segment/blake3-f3c7c3c2d3c9c7c64b10b4cd46f711df1befd76bd366f03a40cc157db3ce0d34` | `:segment/blake3-5e240ede63c00f163d700e431c4e09762428f1b0de4ed48e5fea0fbf50b90d92` |
| ledger checkpoint 3 | `:segment/blake3-c31a1847cd8466aafe6220fca1972b72924a6c69eef3e1260970619ec80d787d` | `:segment/blake3-51ca5000cf870c9bd7c2c284dc8a8a38734ef48fc3e3ebd48fe525943d34bfb9` |
| ledger checkpoint 4 | `:segment/blake3-ab97dc5502245c63ab49b45088b88989fa143616cadf6e298b8ae90d9694d711` | `:segment/blake3-42b4357517e46880bcf804e32657f483088d0b5821b36249cdd38aa110afb79e` |

Ledger projection digest: `2b26e75e8969428761c4f7813d1ffc886d30d160f2f3e8f80076bbe24ef1f227` → `419e3aa652d79cab6921cf7a6c02972bcfe2d3b88ba472bc03ac8f1a935f27d2`.

## Verification

- Rows and Stage E iteration: **52 tests, 895 assertions, 0 failures,
  0 errors** (`/tmp/module-pins-r2d-rows4.log`). This precedes the additional
  actual linked-manifest test; the whole quick run below includes that test.
- Unrestricted focused run: **669 tests, 18,560 assertions, 0 failures,
  0 errors**, exit 0 (`/tmp/module-pins-r2d-focused-unrestricted.log`). Includes
  rows/custody/checkpoint, holder.*, safepoint harness, frame/layout, Stage E,
  linker/manifest/mint, engine/layering and dao.jing namespaces. The initial
  command named `yin.vm.remote-test` instead of `yin.vm.ucf.remote-test`;
  the correctly named namespace was therefore run separately, as recorded
  below. The whole quick tier also includes it.
- Supplemental unrestricted remote namespace: **23 tests, 225 assertions,
  0 failures, 0 errors**, exit 0 (`/tmp/module-pins-r2d-remote-unrestricted.log`).
- Kondo via `clojure -M:kondo --lint` over every changed Clojure file:
  **0 errors, 0 warnings** (`/tmp/module-pins-r2d-kondo.log`). The standalone
  clj-kondo executable was unavailable; the project alias succeeded.
- Whole unrestricted JVM quick tier, `clojure -M:test -e :slow`:
  **4,033 tests, 249,981 assertions, 0 failures, 0 errors**, exit 0.
  Log: `/tmp/module-pins-r2d-quick-unrestricted.log`.
- `git diff --check`: clean. Index remains empty. Only the two rows fixture
  resource files changed; all other fixture and golden paths are unchanged.
- No Node or Dart lane invoked, as instructed. No src/yang or test/yang edits,
  fetch code, staging or commits. All pre-existing collab artifacts retained.

## Proposed commit split

1. **Code:** rows collector, map validation, complete module/profile and install
   admission gate; include the small admission-key strip/docstring cleanup.
2. **Tests + fixtures:** T1–T5/T8, retained T9/T10, malformed corpus,
   re-minted checkpoint/ledger files and inline rows pins. This depends on (1);
   code and tests/fixtures should land together to keep the carrier gate green.
3. **Docs:** corrected keyword grammar, completed dated I-8 and full address
   table, continuation/UCF amendments and closed implementation ledger rows.
4. **Driver seam:** config retention/forwarding in ordinary and fenced lower
   paths and the parked-installer regression. This remains separable from
   (1)–(3), provided the receiving composition supplies the admission map.

No remaining design stop or unfinished implementation in this slice.

READY

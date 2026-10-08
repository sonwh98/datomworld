Design pass complete. The full findings are below and are saved at `/Users/sto/.claude/plans/read-collab-1790806000000-architect-link-iridescent-snail.md`. Plan mode blocked writing into `collab/`, so the findings file was not staged there.

# Architect findings: load and evaluate code from dao.jing.dht via the linker

Read-only pass on master c66809fa. This is a lead design, not a sign-off. A second opinion follows.

## What the tree holds today

These facts shape every ruling below.

- **The linker has two content kinds.** A `dao.jing` handle served in process over ring buffers, or a remote content-pair client. One attempt runs the whole manifest link synchronously against a fresh link state and fresh cursors for at most 64 drive rounds, then reports pending. The remote drive advances nothing but a counter. See `src/cljc/yin/repl/link.cljc:106-227`.
- **The DHT node reads local only and stages misses around the handle.** The index loader walks against a probing handle, fetches each missing blob with a content request, and repeats. See `src/cljc/dao/space/dht.cljc:457-597`. This is the mechanism dao.jing.dht.md section 5.1 prescribes.
- **No REPL store holds any program's rows.** The tree materializer has no caller in `src/`. The index holds AST datoms whose address facts point at row addresses that exist nowhere. A manifest's tree address would be absent from every REPL store today.
- **A REPL definition is an application of the variable `yin/def` to a literal symbol.** Definitions are derivable by query. No definition-name fact exists and none is needed.
- **The only manifest publisher is a test helper**, duplicated in two test namespaces. Nothing in `src/` mints a manifest.
- **The authority policy is implemented and wired into nothing.** The fold over signed envelope datoms exists in `src/cljc/yin/vm/linker/authority.cljc`. The REPL takes the name environment as a plain map.
- **No host driver re-checks a pending run.** The shell's re-check step exists at `src/cljc/yin/repl.cljc:1848`, but the ticker never calls it. A parked require progresses only when the user types a line. This settles the open question in the link-policy doc, section 4: the drivers cannot.
- **The host-function pattern is settled.** The REPL's `dao.space.dht` module already threads the node through the query serve and answers load and query calls as effects on the call pair.

## 1. Content source

**Ruling.** The node from `dao.space.dht/join` is the linker's content source, composed as the local kind over the node's own store handle. The manifest's closure is loaded onto the node before the link, by the same staged-miss mechanism the index loader uses.

A content client over the node's request and answer rings is admissible by shape, since those rings speak the plain content convention. It cannot work with the REPL's link interpreter as written. Each attempt mints a fresh state at the newest cursor, so an answer the DHT produces on a later tick lands for a state that no longer exists. The remote drive also cannot step the node, which has one step owner and a clock reading the drive lacks. Fixing that means rewriting the manifest link flow as a persistent stepped state machine the ticker advances beside the node. That is the right end state and it stays deferred, because it changes the linker core rather than the composition.

The staged path needs no linker change:

1. Generalize the index loader into a generic load that takes a walk function over a probing handle. The index loader becomes one call of it. The dao namespace stays ignorant of yin.
2. The module walk is the linker itself. Linking over a probing local runtime throws the missing signal on the first absent blob, the loader fetches it, and the walk repeats. Every blob the link touches, transitive requires included, is discovered by walking and listed nowhere. That is derive, don't persist.
3. After the load reports loaded, the REPL's answer step links from the local store in one attempt. Before that, it starts the load and reports pending.

The REPL's node is one value shared by the query host module and the link source. Both thread it through their serve. A plain Clojure program holds the same node and calls the same functions. The node's busy flag already keeps the ticker at base cadence during loads.

The local runtime helper exists twice in tests and once inside the REPL. It moves into `src` once.

## 2. What gets published

**Ruling.** Both, and they are different kinds of thing. The index is facts. The linkable module is content: rows, images, derivation records, manifest. The name binding is one envelope datom pair in the index. No new datom family is added for the module image.

Against derive-don't-persist:

- The tree is derivable from the index and the lowered images are derivable from the tree. But the linker's contract is content fetched by address and verified under the algorithm the address carries. Re-deriving a row on each content request would make the content server structure-aware and re-encode on every fetch. So rows and images are materialized as content. They are content-addressed and idempotent.
- The manifest and derivation records are the publisher's claims and the ledger's record of a lowering. No query can derive them. They are persisted as the linker design already specifies.
- The name binding is persisted as exactly one thing: the section 8.2 assertion envelope and its proof, as datoms in the code index. The name environment is derived from those datoms at each snapshot.
- The H and R index stays inside the manifest and is merged link-locally, as today.

**Recommendation, owner decision 1.** Materialize every evaluated program's rows into the index store each round. The cost is one content write per row. The benefit is that the index's address facts finally name real content, and any evaluated program becomes linkable by tree address without a manifest. The publish banner already says the whole code index is shared.

## 3. Naming and discovery

**Ruling.** The index-derived name environment, with the authority policy as the derivation. A published name-environment root is rejected. Direct addresses stay as the zero-cost escape hatch.

- **Index-derived.** Publishing a module transacts one signed assertion envelope into the session's index as one transaction. The covered index the DHT already replicates carries it. A reader that loads an index derives its name environment by querying the envelope and proof datoms, feeding them to the existing ingestion and fold, with the index manifest address as the snapshot. The only address handed out of band is the one S5 already hands over.
- **A published root** would be a second thing to discover for facts the index already holds. Rejected.
- **Direct addresses** remain: a plain name map, plus a flag binding a name to a manifest address. That is the composition as sole asserter, the first shape in section 8.2.

The minimal honest step: a reader learns names from the indexes it has loaded. A name published after the reader loaded is invisible until the reader loads the publisher's newer index manifest, by address, out of band. That is S5's contract and this pass does not widen it. Latest-root discovery, key rotation and revocation stay deferred.

**Proof kind is signatures, not attested logs.** The DHT erases carriers by design, so an attested proof dies the moment an envelope leaves its log. A signature over the envelope's canonical bytes survives any carrier. The verify function is composition-supplied host code, as the authority namespace already assumes. Ed25519 is in the JDK and in Node's crypto module. Dart needs a package, which is owner decision 2.

## 4. Authority and trust

- **Who may bind a name.** A principal the reader declares, with a proof that verifies. An undeclared principal's envelope is discarded. The declaration is composition data, from a flag or the create-state map, and is never read from the index it authenticates.
- **What stops shadowing.** Two layers. A peer cannot write into another node's index, because the DHT replicates content by address and the reader chose which manifests to load. Inside the fold, two manifests for one name from declared principals refuse as ambiguous with every address and asserter named, and no tie-break exists. A name is shadowed only by a principal the reader itself declared, and then the reader is told rather than served.
- **What trust reduces to.** Under verifying derivation, the default, the receiver re-lowers the tree and checks the name, contracts, derivations and every image by content. The single trusted claim is that principal P asserted name N resolves to manifest M.
- **ShiBi.** As a tuple space, ShiBi arrives later in two places: the authority declarations a workspace admits can become tuples a composition queries, and write capability into a shared index is where authorization lands, as the publication section of the DHT doc already says. Now, the declared principal set must remain composition data. Deriving the root of trust from the index it authenticates would verify a claim against a key the same claim supplied.

## 5. Evaluation semantics

**Entry into a session** is unchanged from the linker design. A require parks on the link pair, the interpreter resolves the name and links from the node's local store with deferred discharge, the response correlates by id, step 5b discharges against the receiving task's live state, the install child runs, and each task lowers its own bindings. The only new branch is in the answer step: with a DHT source, a closure not yet local starts a load and answers pending, and a failed load answers absent with the load's reason as its cause.

**Link policy and pending.** Manual stays the default. A pending require now has a progress source that is not a typed line, so the host ticker must call the shell's re-check after a tick in which the node reported a load event. The check counter already resets on progress, so function policies behave.

**Lease** stays deferred. The DHT already gives a data-visible deadline: a get that exhausts candidates or its tick budget ends in a miss fact, the load fails with that reason, the link is refused, and the require's error carries it. No clock enters the REPL.

**Across the four VMs.** Each kernel names its format and contract through the module kernel protocol. The manifest carries the tree and three derivation records, so publish must mint all four images. The H-for-R fallback still applies. Under verifying derivation the tree rows must be local for every lowered format, which the walk guarantees. The existing B0-equality test extends to a manifest loaded from a peer.

**Failure as data.** The link response carries the linker's closed refusal vocabulary, the fold's ambiguous and absent outcomes with diagnostics, and absent with the DHT's miss reasons from a failed load. Unauthenticated and undeclared-principal diagnostics travel beside absent so the REPL can print why a name visibly present in the index did not resolve. Not found from a DHT is not authoritative absence, so a refused require can be retried, and the identity carry from M5 keeps a late answer from settling a later require.

## 6. Slices

Order: L0 and L1 in parallel, then L2, L3, L4, L5.

**L0. Publisher and runtime in src, plain Clojure.**
- Promote the local runtime into the linker namespace.
- New `src/cljc/yin/vm/linker/publish.cljc`: the test publisher promoted, plus assertion and retraction envelope minting with a sign seam.
- An Ed25519 host seam for keygen, sign and verify over canonical bytes.
- Acceptance: both existing manifest test namespaces use the src publisher unchanged. A signed envelope verifies through the fold on every host with the seam. A tampered byte fails as bad proof.

**L1. Generic staged load in dao.space.dht.**
- A load taking a walk function. The index loader reimplemented over it. Status and events unchanged, with a kind added.
- Acceptance: existing DHT tests pass unchanged. A walk over an arbitrary blob closure across two mesh nodes fetches every missing blob. Every miss reason maps to a load-failed reason.

**L2. Module load and link over the DHT, plain Clojure end to end by address.**
- New `src/cljc/yin/vm/linker/dht.cljc`, depending on the DHT and linker namespaces, not on the REPL: load a module, read its status, link after loaded, and derive a name environment from loaded indexes.
- Acceptance over the mesh seam: A publishes and announces, acknowledged. B loads by address, links on all four formats under verifying derivation, and runs equal to local. A missing image on every peer fails absent with the miss reason. A swapped record fails as derivation mismatch. A non-publishing A makes B's load exhaust.

**L3. REPL: DHT link source, pending, re-check.**
- The link composition gains a DHT kind chosen when the index store is a DHT store. The answer step starts loads. The serve takes and returns the node as the query serve does.
- The ticker calls the shell's re-check after node events.
- The DHT host module gains load-module and module-status calls.
- Acceptance: with a DHT store and a direct name map, a require on a reader whose peer holds the module parks, prints a loading line, completes on a later tick without a typed line, and the function answers. Link-policy tests still hold. A solo node refuses absent at once.

**L4. Names in the index: publish and derive.**
- The indexer commits envelope datoms as a second packet kind and materializes rows per round, per owner decision 1.
- A REPL publish host function builds the module tree from the index's defining programs, publishes on the node store, transacts the signed assertion, announces, and prints the manifest address.
- Startup flags declare principals and load or mint the process keypair. The banner prints the public key beside what the node shares.
- The link source's name environment becomes a function of the loaded index snapshots, rebuilt at each serve, with fold diagnostics printed once per change.
- Acceptance: B hydrates A's index and resolves the name with provenance naming A. An undeclared principal is reported and not resolved. Two declared principals on one name print ambiguous with both. A retraction removes exactly its assertion.

**L5. End to end across processes**, extending the existing process test.
- Process A publishes a function as a module over the anchor. Process B, on the JVM and the Node leg, hydrates A's index, declares A's key, requires the module, waits through pending, and evaluates it on the semantic and stack VMs.
- Plain Clojure does the same through join, load-index, the derived name environment, load-module and link, then runs the image, asserting equality with A.

## 7. Owner decisions

1. **Rows per round.** Materialize every evaluated program's rows each round, recommended, or only at explicit publish.
2. **Signatures from day one, and Dart.** Add an Ed25519 package for Dart, recommended, or let Dart readers use direct addresses until then. Inventing an unproven trusted-index mode would be a third trust policy and is not recommended.
3. **Module granularity and spelling.** Explicit publish of named exports deriving the tree from the index, recommended, versus publishing a source form, versus auto-publishing every definition.
4. **Implicit network on require.** A require under a DHT store starts a load without further consent, recommended and stated in the banner, or requires an explicit load first.
5. **Name environment scope.** Fold over every loaded index, recommended, or only the hydrated HEAD's.
6. **Key material.** A keypair minted per process and printed, or loaded from a file across restarts. Rotation and revocation stay open.
7. **Confirm the deferrals.** The persistent stepped linker over the node's rings, latest-root discovery, and the lease link policy are not built in this epic.

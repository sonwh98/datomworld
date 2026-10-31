The core claims check out so far: the link attempt restarts with fresh cursors, and `dao.space.dht` stages index misses through the node’s local handle. I also found a likely acceptance gap: the proposed module walk needs to prove it fetches everything verifying derivation will read, not just the image selected by one link request.
# Architect second opinion

**Overall: AGREE-WITH-CHANGES.** The lead identifies the right composition boundary, but the proposed staged load has an unproved closure walk, the authority policy is weaker than the prose claims, and several slice acceptances would pass without exercising the property they are meant to establish. This is a read-only review of `c66809fa`; I did not run tests or change files.

## What the tree holds today

| Lead claim | Verification |
|---|---|
| A link attempt starts fresh and a remote drive cannot advance the node. | **Confirmed.** Each attempt creates link state and new local cursors; the remote drive only increments its round counter. An exhausted attempt returns pending. [link.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/repl/link.cljc:167), [link.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/repl/link.cljc:198), [link.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/repl/link.cljc:206). |
| DHT index loading stages misses into the node’s local store. | **Confirmed.** The probing handle signals a missing address, `advance-load` requests it through the node client, then repeats the index walk. [dht.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/space/dht.cljc:457), [dht.cljc](/Users/sto/workspace/datomworld/src/cljc/dao/space/dht.cljc:539). |
| REPL code index address facts do not mean the program tree rows are stored. | **Confirmed in the current path.** Projection writes `:yin/address` facts; the round’s materialization drains the *index publication* intake. These are distinct from `yin.vm/materialize-tree!`, used by linker tests. [index.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/repl/index.cljc:93), [index.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/repl/index.cljc:300), [linker_test.cljc](/Users/sto/workspace/datomworld/test/yin/vm/linker_test.cljc:325). |
| Definition names can be queried without a new fact. | **Confirmed.** The current AST projection records the tree, and the query test locates `yin/def` through its operator and literal operand. [index.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/repl/index.cljc:102), [query_test.cljc](/Users/sto/workspace/datomworld/test/dao/space/query_test.cljc:541). |
| There is no production manifest publisher. | **Confirmed with a qualification.** `linker/publish!` stores an individual format image; manifest assembly is in test helpers, not a production module publisher. [linker.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/vm/linker.cljc:1992), [linker_test.cljc](/Users/sto/workspace/datomworld/test/yin/vm/linker_test.cljc:325). |
| Authority fold is not wired into REPL resolution. | **Confirmed.** The fold exists, while the REPL composition receives a plain `name-env` map and looks names up directly. [authority.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/vm/linker/authority.cljc:258), [link.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/repl/link.cljc:106), [link.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/repl/link.cljc:230). |
| No host ticker re-checks a pending run. | **Confirmed.** `recheck-pending` exists, but `step-all` advances DHT and driver without calling it. [repl.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/repl.cljc:1848), [main.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/repl/main.cljc:286). |

The lead’s statement that **two declared principals always cause ambiguity is incorrect**. The fold returns success when multiple accepted assertions name the *same address*, retaining both asserters in provenance. It refuses only when there is more than one distinct address. [authority.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/vm/linker/authority.cljc:226).

## 1. Content source — **AGREE-WITH-CHANGES**

Using the node from `dao.space.dht/join` and its local handle is a sound first composition. A direct content client over the node’s rings is not viable with today’s REPL attempt lifecycle: late answers can outlive the fresh state and cursors that requested them. Deferring a persistent stepped link coordinator is reasonable.

The proposed “run the linker against a probing handle” needs a proof before L1/L2 are called complete. The index walker exposes missing blobs by throwing; the module linker may instead turn a missing content answer into a normal `:absent` refusal. Also, linking one selected format does not necessarily walk every image needed by another format or by transitive requirements. Define a module closure walker with explicit outcomes—**missing address, verified complete, invalid content**—and test a missing blob at every level. Keep the DHT node as the sole step owner; do not have a linker drive it internally.

## 2. Published content — **AGREE-WITH-CHANGES**

Publish an index for query and a module content closure for linking. Content-addressed tree rows and lowered images are valid *materialized outputs* of a derivation; they are not extra canonical facts asserting a second meaning for the AST. The manifest and derivation records carry publication claims that a query over code facts cannot supply. This fits the scoped “derive, don’t persist” rule, which specifically warns against adding derivable tags, slots, or fields to canonical structures. [datom.world.md](/Users/sto/workspace/datomworld/docs/design/datom.world.md:151).

I **disagree** with materializing every evaluated program’s rows every round. It adds storage and a public content consequence to ordinary evaluation, while the proposed user operation is explicit module publication. Materialize the chosen module tree and closure at publish time. If `:yin/address` in the index promises a retrievable row today, either fulfill that separate index contract deliberately or document its current meaning; do not use the linker epic to silently broaden every evaluation’s publication.

## 3. Naming and discovery — **AGREE-WITH-CHANGES**

Deriving names from signed envelope and proof datoms in an explicitly loaded index snapshot is the smallest honest discovery step. It preserves the existing out-of-band index-manifest handoff. The fold already assembles envelope/proof pairs and accepts a composition-supplied verifier. [authority.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/vm/linker/authority.cljc:63), [authority.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/vm/linker/authority.cljc:258).

An attested-carrier proof does not survive this transport: the index read is not the original stream identity that attested the assertion. Require a portable signature for DHT-discovered names. Specify canonical signed bytes, key encoding, principal identifier, and cross-host test vectors before selecting host crypto adapters. Ed25519 is a plausible choice, but “JDK and Node have it” is not yet a complete JVM/Node/Dart seam. Direct manifest addresses should remain available without a signature-based name claim.

Do not imply that loading an index means subscribing to its future HEAD. A new snapshot still requires its address to be handed to the reader.

## 4. Authority — **AGREE-WITH-CHANGES**

Declared principals must come from reader composition, never from the index they authenticate. The current fold correctly discards undeclared principals and failed proofs. [authority.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/vm/linker/authority.cljc:283). Keep ambiguity refusal for *different manifest addresses*; explicitly accept or change the current same-address consensus rule. Either way, diagnostics must retain all asserting principals.

A chosen index manifest is a **scope of considered claims**, not a write-authentication guarantee. Content addressing proves which snapshot was loaded; signatures and local declarations decide whose name claims count. ShiBi may later supply declarations or write capabilities as composition inputs, but a tuple read from the candidate index cannot establish its own authority.

## 5. Evaluation entry — **AGREE-WITH-CHANGES**

Keep the existing link request and deferred discharge path for each receiving VM. The four format records are already supplied to REPL link state. [link.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/repl/link.cljc:70), [link.cljc](/Users/sto/workspace/datomworld/src/cljc/yin/repl/link.cljc:184). DHT loading must return a pending outcome until verified local content is ready; a failed load should become a correlated refusal with the DHT reason as data.

Wire the ticker to re-check **on a relevant load completion or failure event**, and preserve the pending request’s identity. Avoid re-checking every pending run on every generic node event: unrelated traffic would consume function-policy checks and may cause needless repeated links. A bounded re-check after a relevant event should also handle multiple pending module loads.

Deferring `:lease` is sound. A DHT request budget supplies a bounded fetch outcome, but it is **not** a lease for the whole pending run. State that distinction. Test failures on all four VMs, including the register fallback, as well as successful equality.

## 6. Slices L0–L5 — **AGREE-WITH-CHANGES**

The broad order works, with these gates changed:

| Slice | Acceptance that must be added or corrected |
|---|---|
| **L0** | Keep the publisher plain Clojure and host neutral. Verify canonical signature vectors across every supported host; tampering with envelope, proof, name, and manifest must fail. |
| **L1** | Generic staged loading must distinguish a signaled missing blob from an invalid walk, preserve DHT miss reasons, and prove a fetched blob is verified before reporting loaded. |
| **L2** | Exercise a missing manifest, row, derivation record, each format image, and a transitive requirement. Linking one format successfully does **not** prove the complete four-format closure was loaded. |
| **L3** | Test a pending require completing after a DHT event with **no typed line**; test unrelated events, repeated events, abandonment, late answers, and function link policies. “Solo node refuses at once” is too strong if the node first has to exhaust a bounded lookup. |
| **L4** | Derive names from a specified set of loaded immutable snapshots. Test undeclared, bad signature, conflicting addresses, same-address multi-asserter behavior, retraction, and stale snapshots. Materialize rows on explicit publish under the decision above. |
| **L5** | Process A publishes; B receives an index-manifest address, loads it, resolves and evaluates by name. Run plain Clojure through the **same load API** used by the yin.repl host functions, including failure results. Cover all four VMs across the supported hosts, rather than only semantic and stack on JVM/Node. |

The host-function reuse invariant needs an explicit interface gate: the REPL host module should only adapt effect requests and responses to the plain Clojure `join → load-index → derive-names → load-module → link` operations. It must not contain a second loader. The node remains peer to peer; publication permission and chosen trust roots are per-node composition, not privileged network roles. `dao.jing` remains passive byte storage, and code remains represented by datoms even when link images are cached as content.

## 7. Consolidated OWNER decisions

| # | Second-opinion recommendation |
|---|---|
| **1. Rows per round** | **Disagree with lead.** Materialize module rows on explicit publish. Resolve the existing `:yin/address` retrieval contract separately. |
| **2. Signatures and Dart** | **Agree with signatures for DHT names.** Specify canonical bytes and cross-host vectors first. If Dart cannot verify in this slice, permit direct-address linking there and do not claim Dart name resolution is complete. |
| **3. Granularity** | **Agree:** explicit named-export publication derived from indexed code. Define how multiple defining programs, free names, and transitive requirements form one module before implementation. |
| **4. Network on require** | **Agree conditionally:** an already configured DHT link source may start a bounded load on `require`; expose pending and failure as data, and state this behavior in the REPL help/banner. |
| **5. Name scope** | **Agree with folding explicitly loaded snapshots**, provided the composition records their addresses and has deterministic handling of repeated assertions and conflicts. Do not silently include every blob in the local store. |
| **6. Key material** | **Choose a loaded, stable key for durable publisher identity.** An ephemeral per-process key is useful for tests or temporary sessions, but printing one does not make a persistent name trustworthy across restarts. Define key loss and replacement behavior. |
| **7. Deferrals** | **Agree:** persistent stepped ring linker, latest-root discovery, and lease policy can wait. Record the staged loader as an interim implementation with a closure-walk acceptance gate. |

**Missing owner rulings:** decide whether same-address assertions from multiple declared principals count as consensus or ambiguity; whether an index’s `:yin/address` guarantees row retrieval before explicit module publication; and whether the first release promises signed name loading on Dart or only direct-address loading there.

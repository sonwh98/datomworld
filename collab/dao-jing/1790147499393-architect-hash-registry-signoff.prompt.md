Created-GMT: 2026-09-23 07:12:00 GMT
Created-Local: 2026-09-23 14:12:00 +07 (Indochina Time)
Coding-Agent: codex
Session-ID: 01a0cad7-2f30-7b82-ae67-288922310f75 (resume of your hash-registry design session)

# Task: architect-hash-registry-signoff — final architectural sign-off on multihash content addressing design

Role: Lead System Architect

Implementers:
- Model: gpt-5.6-sol | Assigned: 2026-09-23 14:12:00 +07 | Status: active | Rationale: author and Lead System Architect, resumed for formal sign-off after independent review and reconciliation

Work in /Users/sto/workspace/datomworld (your launch directory; branch master). READ-ONLY. Do not edit files, do not run git add/commit.

## Context

You previously authored and revised `docs/design/dao.jing.hash-registry.md` across three committed revisions:
- `5de9e7b1`: Initial draft with legacy framing and mixed-storage migration.
- `67585e52`: Reworked to permanent, first-class multi-algorithm multihash addressing without migration phases.
- `b7a765eb`: Dropped the encoding-profile axis (`:jing.print/v1`) per owner instruction, producing pure multihash `:segment/<algorithm-id>-<hex>`.

An independent architectural review of `b7a765eb` was performed by `claude-fable-5-1` (report at `archive/1790142588167-reviewer-hash-registry-pure-multihash.claude-fable-5-1.findings.md`), which gave a verdict of **READY to proceed to H0**, with one P2 finding and six P3 polish items.

The orchestrator has reconciled those findings into `docs/design/dao.jing.hash-registry.md` on master (commit `a2e64161`):
1. **P2 (4th Copy Path):** Added `dao.data.btree.storage/store-tree-async` at `src/cljc/dao/data/btree/storage.cljc:340` as a fourth address-preserving copy site. Flushes of cache-minted blobs to remote storage must supply the source address/algorithm (e.g. via `put-content` or an explicit address-supplying operation) rather than calling un-parameterized `request-materialize` / `materialize-async-fn`, which would incorrectly re-mint under the remote's ambient default. Added to H1/H2 criteria and test obligations.
2. **Parser simplification (P3):** Specified algorithm identifiers as `[a-z0-9]+`, parsed by splitting on the first `-` then exact registry table lookup, removing prefix-matching ambiguity machinery.
3. **Total `segment-matches?` (P3):** Specified that `segment-matches?` returns `false` on canonical-encoder refusal (making it a total verification predicate), while minting operations (`materialize!`, `segment-key`) continue to throw.
4. **Remote mint classification (P3):** Classified `request-materialize` and `materialize-async-fn` as default-only remote helpers, with explicit `{:algorithm}` options scoped across public minting APIs.
5. **Multi-algorithm proof (P3):** Dropped the conflicting "default-changing test seam" (which contradicted the immutable registry invariant); multi-algorithm coexistence is verified by minting under explicit `{:algorithm :sha256}` while default remains BLAKE3.
6. **CBOR doc alignment (P3):** Made updating `docs/design/dao.jing.cbor.md` (replacing legacy SHA-256 address phrasing with multihash addresses) an explicit H0 completion criterion.
7. **Lint target (P3):** Qualified the architectural lint target explicitly to `dao.jing/content-hash` and `dao.jing/segment-key`.

## Read First

- `docs/design/datom.world.md`
- `docs/design/dao.jing.hash-registry.md` on master (commit `a2e64161`)
- `docs/design/dao.jing.cbor.md` (Addressing and clean break)

## Your Review & Sign-Off Task

Perform a read-only architectural evaluation of the reconciled design:

1. **Foundational Invariants & Clean Break:**
   - Confirm that the pure-multihash design strictly adheres to datom.world architectural foundations (`dao.jing` is syntax; explicit causality; no hidden global state; clean break without backward-compat shims).
2. **Address-Directed Operations & Copy Paths:**
   - Confirm that the classification of `store-tree-async` and the prescription to pass the source address/algorithm to remote flushes are architecturally sound.
   - Confirm that address verification remains strictly address-directed via `segment-matches?`.
3. **Total Predicate on Encoder Refusal:**
   - Confirm that returning `false` on encoder refusal in `segment-matches?` while throwing in `materialize!` is the right contract.
4. **Sign-Off Verdict:**
   - Explicitly state whether you grant **Lead System Architect Sign-Off** for `docs/design/dao.jing.hash-registry.md` to proceed to Phase H0 implementation.
   - Specify the exact boundaries, frozen contracts, and acceptance criteria governing Phase H0.

## Deliverable

Begin your final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>
Coding-Agent: codex
Session-ID: 01a0cad7-2f30-7b82-ae67-288922310f75

Then report:
- Evaluation of the reconciled changes (P2 copy path, parser, total predicate, remote minting)
- Formal Sign-Off statement (GRANTED or WITHHELD)
- Directives and constraints for Phase H0

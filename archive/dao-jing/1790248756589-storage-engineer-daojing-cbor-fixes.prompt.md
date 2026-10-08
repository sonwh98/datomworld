Created-GMT: 2026-09-24 11:19:16 GMT
Created-Local: 2026-09-24 18:19:16 +0700
Coding-Agent: claude
Session-ID: 0905c1a1-ff26-4582-9cb5-ec25468e7593

# Task: Resolve Adversarial Review Findings for DaoJing CBOR Codec Swap

Role: DaoSpace and DaoJing Storage Engineer

Implementers:
- Status-Event: 2026-09-24 17:12:43 +0700 | Model: claude-sonnet-5 | Status: completed | Rationale: Initial CBOR codec swap implementation
- Model: claude-sonnet-5 | Assigned: 2026-09-24 18:19:16 +0700 | Status: active | Rationale: Reconcile review findings from gpt-5.6-sol adversarial review

Resume session 0905c1a1-ff26-4582-9cb5-ec25468e7593 to resolve the adversarial review findings on branch `dao-jing-cbor-swap`.

Review Findings to Resolve:

1. [P1] Mutable byte arrays exposed in `src/cljc/dao/jing/mem.cljc:104`:
   - The public handle exposes raw `:state` containing mutable host byte arrays (`byte[]`, `Uint8Array`, `Uint8List`). Callers can mutate stored bytes in place, corrupting content-addressed storage.
   - `mem/entries` decodes without address verification at line 118.
   - Fix: Keep the atom inaccessible, expose a closure-based snapshot view, and use `segment-value` for verified decoding. Add mutation-isolation tests for both input and exposed state/output.

2. [P1] Encode-once contract violation in `src/cljc/dao/jing.cljc:457`:
   - `segment-key` encodes once, then `materialize!` passes the original value to the backend; memory and file encode it again at `mem.cljc:38` and `file.cljc:344`. This violates the encode-once contract in `docs/design/dao.jing.cbor.md:98`.
   - Fix: Encode once in `dao.jing/materialize!`, derive the address from those bytes, and pass canonical bytes to the storage backends via a clean byte-store interface (`:put-bytes-fn` / `:get-bytes-fn` or equivalent handle contract). Keep public APIs value-facing.

3. [P2] Documentation Sync in `docs/design/dao.jing.md`:
   - Line 190 still declares order-normalized printer current, lists it as open item at line 486, and describes file records as `[address payload]` at line 407.
   - Fix: Update the doc to match the canonical CBOR codec, byte-store contracts, and `[digest payload-bytes]` framing. Retire superseded open items.

4. [P2] CBOR File Replay Fail-Closed Tests in `test/dao/jing/file_test.cljc:222`:
   - Fail-closed tests currently inject raw EDN text.
   - Fix: Construct genuine CBOR frames for replay refusal classes: wrong CBOR shapes, bad digest lengths, digest mismatches, non-canonical payload bytes, and byte-mutation round trips.

5. [P3] Formatting & Pure ASCII:
   - Ensure `psset_fixtures.cljc`, `digest-table.edn`, and touched source files are strictly <= 80 columns and 100% pure ASCII.

Run focused checks and whole test suites across JVM, Node, and Dart.

Begin the final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS local-timezone-name>

Report changed files, exact test/check outcomes, unresolved concerns, and any incomplete work.

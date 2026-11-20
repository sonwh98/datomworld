Created-GMT: 2026-11-20 17:45:09 GMT
Created-Local: 2026-11-21 00:45:09 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: agy
Session-ID: caa2fca1-4ce3-44d6-8f61-8f6313602d02

# Task: Step-7 adversarial review of fix/aa174a11-master-lint-reds (store-write-audit-test + hash-registry-contract-test)

Role: Adversarial Code Reviewer and Security Auditor

Implementers:
- Model: agy native research subagent (Gemini family) | Assigned: 2026-11-21 00:45:09 +07 (Asia/Ho_Chi_Minh) | Status: active | Rationale: Independent cross-family Step-7 review of codex/gpt-6.2-sol author round (owner-authorized native agy subagent)

Completed-GMT: 2026-11-20 17:49:58 GMT
Completed-Local: 2026-11-21 00:49:58 +07 (Asia/Ho_Chi_Minh)

# Step-7 Adversarial Code & Security Review: `fix/aa174a11-master-lint-reds`

Reviewed `collab/yang-php/1795163989182-qa-engineer-master-reds-aa174a11.diff.patch` (read-only; `git apply --check` and `git apply --stat` verified cleanly against `HEAD` `9cf135c2`, pre-image blob hashes `58e8e86b`, `7ad41076`, `94d2a31a`) against `docs/design/dao.jing.hash-registry.md`, `test/yin/vm/store_write_audit_test.clj`, and `docs/design/datom.world.md`.

## Actionable Findings

No actionable findings

## Verification Summary by Focus Area

### 1. Store-Write Allowlist Soundness
- **`src/cljc/yang/php/antlr/eval_coordinator.cljc` (`compile-and-publish*!`, lines 56–89):**
  - Destructures `store` at line 59 and passes it only to `publication/publish!` (line 69 -> `publish/publish-module!`, `jing/materialize!`, and `siteset/publish!` in `src/cljc/yang/php/antlr/eval_publication.cljc:10-23`) and into the stage context map `{:store store :datoms [] :principals []}` passed to `siteset/stage` (line 76).
  - `siteset/stage` (`src/cljc/yang/php/antlr/siteset.cljc:253-268`) wraps `(:store ctx)` in `yang.core.siteset-store/overlay` (`src/cljc/yang/core/siteset_store.cljc:56-88`), which exclusively invokes `:get-bytes-fn` and `:put-bytes-fn` on a `dao.jing` byte-store handle. This matches the shape of `yin.vm.linker.interpreter/dht-context` (`src/cljc/yin/vm/linker/interpreter.cljc:153-161`, allowlisted at `test/yin/vm/store_write_audit_test.clj:351-352`) and `yang.php.antlr.siteset/stage` (`test/yin/vm/store_write_audit_test.clj:300-301`).
  - Inspected the entire `eval_coordinator.cljc` (lines 1–128) and `serve` (lines 109–128): `serve` only updates `:cursor` and `:pending` on `state`; no VM program `:store` mutations or residual escapes (`store_write_audit_test.clj:48-56`) exist.
- **`test/yin/vm/store_write_audit_test.clj` (patch lines 57–70, allowlist lines 302–304):**
  - Adds `"src/cljc/yang/php/antlr/eval_coordinator.cljc" {["compile-and-publish*!" :map] 1}`, matching `top-level-name` (`store_write_audit_test.clj:234-238`) and the single `:map` site containing `:store` at `eval_coordinator.cljc:76`. Satisfies `docs/design/datom.world.md:127-130`.

### 2. Hash-Registry & Security Boundary Soundness
- **`src/cljc/yin/vm/module.cljc` (`code-entry-handler`, lines 700–716; patch lines 1–13):**
  - Line 709 replaces `(not= address (jing/segment-key manifest))` with `(not (jing/segment-matches? address manifest))`.
  - **Address-directed & non-minting verification (`docs/design/dao.jing.hash-registry.md:47-61, 227-248`):** `dao.jing/segment-matches?` (`src/cljc/dao/jing.cljc:303-317`) parses `address` via `parse-segment-address` (`dao.jing.cljc:241-268`), verifies membership in the closed immutable `registry` (`:blake3` and `:sha256` at `dao.jing.cljc:97-105`), validates exact lowercase hex digest length (`64` chars) and canonical reconstruction (`(= canonical address)`), computes `(canonical-bytes manifest)` and `(digest-bytes algorithm bs)` using the address-carried algorithm (never `default-hash-algorithm`), and never mints.
  - **Fail-closed & edge-case behavior (including untrusted guest `:code/entry` inputs via `src/cljc/yin/vm/engine.cljc:2834-2838`):**
    - Nil requested address: refused at line 708 `(nil? address)` -> `:manifest-mismatch`.
    - Host-registered modules (`register-host-module`, `src/cljc/yin/vm/module.cljc:227-267`): stored with `:address nil` (line 264) and `:slice` instead of `:bindings` (line 266); refused with `:manifest-mismatch` whether requested with `nil` (`nil? address`) or any non-nil address (`not= address (:address entry)`).
    - Malformed, non-keyword/string, uppercase hex, wrong-length, non-canonical, or unknown-algorithm addresses (even if a tampered registry entry also holds that bad `:address`): `parse-segment-address` returns `nil`, so `segment-matches?` returns `false` -> `:manifest-mismatch`.
    - Unhashable `manifest` (CBOR encoder refusal): `segment-matches?` wraps `canonical-bytes` and `digest-bytes` in `(try ... (catch #?(:cljd Object :clj Throwable :cljs :default) _ false))` (`dao.jing.cljc:311-316`), failing closed with `:manifest-mismatch` rather than throwing an unhandled exception (an improvement over the previous `segment-key` call, which threw on encoder refusal).
    - Tampered `manifest` or `:yin.module/name` mismatch (including a `:manifest nil` entry): refused by `(not (jing/segment-matches? address manifest))` or `(not= module (:yin.module/name manifest))` at lines 709–710 (`module` is guaranteed non-nil symbol by line 704).
    - Valid matching `:blake3` or `:sha256` address/manifest: passes lines 708–710, then enforces `:export-undeclared` (line 711) and `:binding-missing` (line 712) before returning `(get (:bindings entry) export)`.
- **`link-module` & repository-wide equality-validation sweep:**
  - `src/cljc/yin/vm/module.cljc:95` (`:address (jing/segment-key manifest)` in `link-module`, lines 80–100) is a mint-only Class-2 operation with no incoming address parameter and is appropriately untouched.
  - Searched all of `src/` (`src/clj`, `src/cljs`, `src/cljd`, `src/cljc`) for `segment-key` and `content-hash`: outside `src/cljc/dao/jing.cljc` and `src/cljc/dao/jing/file.cljc` (exempted at `test/dao/jing/hash_registry_contract_test.cljc:463-464`), no remaining equality comparison against `dao.jing/segment-key` or `dao.jing/content-hash` exists.

### 3. CLJ / CLJS / CLJD Portability & Test Coverage
- **`test/yin/vm/code_entry_test.cljc` (patch lines 14–56):**
  - Uses only portable `clojure.test` (`deftest`, `is`, `testing`), `doseq`, `keyword`, `str`, `assoc`, `dao.jing/segment-key`, `dao.jing/segment-digest` (`src/cljc/dao/jing.cljc:287-293`), `yin.vm.module/default-registry`, `yin.vm.module/assoc-module`, and `yin.vm.module/code-entry-handler`. Zero host-specific symbols, Java interop, or non-portable constructs.
  - `installed-export-is-verified-with-the-address-algorithm` directly proves red-to-green behavior for explicit `:sha256` manifests (`:callable`) and covers tampered manifest, `nil` requested address, `nil` stored and requested addresses, malformed `:segment/sha256-xyz` (stored and requested), and unknown algorithm `:segment/sha512-<64-hex>` (stored and requested). Combined with `installed-export-is-address-pinned` (`code_entry_test.cljc:8-36`) and `dao.jing.hash-registry-contract-test` (`hash_registry_contract_test.cljc:171-264`), negative/regression coverage is solid.

### 4. Scope & Cleanliness
- Exact patch diffstat verified via `git apply --stat` / `--check`:
  - `src/cljc/yin/vm/module.cljc` (`1 insertion(+), 1 deletion(-)`)
  - `test/yin/vm/code_entry_test.cljc` (`29 insertions(+), 1 deletion(-)`)
  - `test/yin/vm/store_write_audit_test.clj` (`3 insertions(+)`)
  - Total: `3 files changed, 33 insertions(+), 2 deletions(-)`, cleanly touching only the 3 allowlisted files.
- Non-blocking note outside patch scope: `docs/design/dao.jing.call-site-classification.md` (frozen at `ba88f769` + phase 8ab) does not list `yin.vm.module/code-entry-handler` (introduced in `fc10b03b`) or the `yang.php.antlr.eval_*` mint sites (introduced in `70d8d066`), which predates this 3-file lint-fix patch.

## Verdict

**READY TO COMMIT**

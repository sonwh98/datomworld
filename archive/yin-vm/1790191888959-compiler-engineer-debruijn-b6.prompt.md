Created-GMT: 2026-09-23 19:31:28 GMT
Created-Local: 2026-09-24 02:31:28 +0700
Coding-Agent: claude
Session-ID: b983c4d3-f952-4de3-9c75-2f465ee9872b

# Task: Implement Phase B6 — de Bruijn Linker over dao.stream (yin.vm.debruijn-linker)

Role: Compiler Engineer

Implementers:
- Model: claude-opus-5-5 | Assigned: 2026-09-24 02:42:00 +0700 | Status: active | Rationale: Implement Phase B6 linker over dao.stream supporting both stack and register formats

## Working Directory

You must work strictly inside the dedicated worktree:
  `/Users/sto/workspace/worktree-debruijn-b6`

Do not touch or modify the main repository tree `/Users/sto/workspace/datomworld`.

## What to Read First

1. `/Users/sto/workspace/datomworld/docs/design/datom.world.md` -- governing invariants
2. `/Users/sto/workspace/datomworld/docs/design/yin.vm.debruijn.linker.md` -- the normative Phase B6 design document
3. `/Users/sto/workspace/datomworld/docs/design/yin.vm.debruijn.stack.md` -- stack dimension and B6 context
4. `/Users/sto/workspace/datomworld/docs/design/yin.vm.debruijn.register.md` -- register dimension and R5 context
5. Existing code to reuse:
   - `src/cljc/dao/jing.cljc` (`segment-matches?`, `segment-key`, `get`, `materialize!`)
   - `src/cljc/dao/jing/dht.cljc` (`create-content-dht`, `IDhtNet`)
   - `src/cljc/dao/jing/remote.cljc` (`content-client`, `default-handlers`)
   - `src/cljc/yin/vm/debruijn_code.cljc` (`image-hash`, `image-defect`)
   - `src/cljc/yin/vm/debruijn_register_code.cljc` (`register-hash`, `register-image-defect`)
   - `src/cljc/yin/vm/debruijn_linearize.cljc` (`lift`, `lower-stack`, `adapt`)
   - `src/cljc/yin/vm/debruijn_register_compile.cljc` (`lift`, `lower-register`)
6. `docs/agents/build-n-test.md` -- testing and linting commands

## Governing Architectural Invariants

1. **B6 is used by both the stack VM and the register VM for linking code over `dao.stream`**.
2. **The linker fetches code over `dao.stream`, but the code itself is stored in `dao.jing`**.
3. Storage addresses (`segment-key`) and VM identities ($H$ and $R$) are separate preimages:
   - Step 2 checks address integrity via `(jing/segment-matches? address value)`.
   - Step 3 checks identity integrity via `(= identity ((:hash-fn format) value))`.
4. No hidden mutations, callbacks, global registries, or host type leakage. All outcomes are qualified data.

## File Box

```text
New: src/cljc/yin/vm/debruijn_linker.cljc
New: test/yin/vm/debruijn_linker_test.cljc
Existing edits: none
Must not change: merged projection namespace, image-hash, register-hash,
                 dao.stream, dao.jing, dao.jing.dht, dao.jing.remote,
                 named VM semantics
```

## Deliverables

### 1. `src/cljc/yin/vm/debruijn_linker.cljc`

Implement the `yin.vm.debruijn-linker` namespace:

- **Free-name scanners**:
  - `(defn stack-free-names [instruction-vector] ...)`
    Scans all `:load-free` operands in the canonical stack instruction vector.
  - `(defn register-free-names [register-image-map] ...)`
    Scans all `:load-free` operands across all bodies in the register image map.

- **Format records**:
  - `(def stack-format ...)`
    ```clojure
    {:format        :yin.debruijn.code
     :hash-fn       debruijn-code/image-hash
     :validate-fn   debruijn-code/image-defect
     :free-names-fn stack-free-names}
    ```
  - `(def register-format ...)`
    ```clojure
    {:format        :yin.debruijn.register
     :hash-fn       debruijn-register-code/register-hash
     :validate-fn   debruijn-register-code/register-image-defect
     :free-names-fn register-free-names}
    ```

- **The six-step `fetch` function**:
  `(defn fetch [handle index format identity] ...)`
  Strictly executing the six steps in order:
  1. `address <- (index identity)`
     If absent -> return `{:status :refused, :reason :absent, :identity identity}`
  2. `value <- (jing/get handle address absent)`
     If absent -> return `{:status :refused, :reason :absent, :address address}`
     If `(not (jing/segment-matches? address value))` ->
       return `{:status :refused, :reason :address-mismatch, :address address, :value value}`
  3. If `(not= identity ((:hash-fn format) value))` ->
       return `{:status :refused, :reason :hash-mismatch, :expected identity, :actual ((:hash-fn format) value)}`
  4. If defect `((:validate-fn format) value)` ->
       return defect (e.g. `{:status :refused, :reason :descriptor-defect, ...}` or the validator's defect map)
  5. Check free names `((:free-names-fn format) value)` against receiver environment:
     - Unresolved free name -> return `{:status :refused, :reason :unresolved-free, :name sym}`
     - Free name shadowed by store/env -> return `{:status :refused, :reason :shadowed-free, :name sym}`
  6. Return verified image: `{:status :ok, :format (:format format), :identity identity, :value value}` (or return the raw verified image directly, matching design conventions).

- **Same-root pairing**:
  - `(defn verify-same-root-pairing [root H R source-datoms] ...)`
    Re-lowers `source-datoms` locally (using `yin.vm.debruijn-linearize/adapt` for stack $H$ and `yin.vm.debruijn-register-compile/lower-register` after `yin.vm.debruijn-resolve/resolve` for register $R$).
    Confirms recomputed stack hash equals $H$ and recomputed register hash equals $R$.
    If matching -> returns true / `{:status :ok}`.
    If mismatch -> returns `{:status :refused, :reason :pairing-mismatch, :root root, :expected {:H H :R R}, :actual {:H actual-H :R actual-R}}`.

- **Refusal outcome predicates / constructors**:
  - `(defn refused? [res] ...)`
  - Standard qualified error maps per Section 4.3 of the design doc.

### 2. `test/yin/vm/debruijn_linker_test.cljc`

Comprehensive test suite covering all 17 completion criteria in Section 11 of `docs/design/yin.vm.debruijn.linker.md`:
- Testing both `stack-format` ($H$) and `register-format` ($R$).
- Step 1: `:absent` on missing index entry or unreachable store address.
- Step 2: `:address-mismatch` on local storage corruption and corrupt client RPC response.
- Step 2/DHT: DHT peer mismatch handled by `make-get` returning `:absent`.
- Step 3: `:hash-mismatch` when value does not match requested identity (including contract version or descriptor disagreement).
- Step 4: Structural validator defect refused before load.
- Step 4 (register): Live-set defect refused before load.
- Step 5: `:unresolved-free` for a `:load-free` name that cannot be resolved.
- Step 5: `:shadowed-free` for a `:load-free` name shadowed by receiver environment.
- Same-root pairing:
  - Trusted fallback outcome.
  - Verifying fallback accepting honest pair and refusing swapped pair with `:pairing-mismatch`.
- Execution parity: fetched image lifted to semantic VM and executed under B0 normalizer matches local execution.
- Cross-host transfer over `dao.stream` / `dao.jing.remote`.

## Verification Standards

- All lines $\le 80$ columns.
- 100% pure ASCII (no Unicode quotes, em-dashes, etc.).
- `cljstyle check src/cljc/yin/vm/debruijn_linker.cljc test/yin/vm/debruijn_linker_test.cljc` must be clean.
- `clj -M:kondo --lint src/cljc/yin/vm/debruijn_linker.cljc test/yin/vm/debruijn_linker_test.cljc` must return 0 errors, 0 warnings.
- Run tests:
  - JVM: `clojure -M:test -n yin.vm.debruijn-linker-test`
  - CLJS: `bb test:cljs`
  - CLJD: `bb test:cljd`

Begin your final response exactly with:
Completed-GMT: <YYYY-MM-DD HH:MM:SS GMT>
Completed-Local: <YYYY-MM-DD HH:MM:SS +0700 ICT>
Session-ID: 7b7c3d21-74ac-4b75-b1e4-12b1be6cf9cb

Then summarize files created, tests run, test results, and confirm all invariants.

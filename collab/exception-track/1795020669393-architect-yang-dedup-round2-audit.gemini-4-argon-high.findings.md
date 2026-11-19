Created-GMT: 2026-11-18 17:06:28 GMT
Created-Local: 2026-11-19 00:06:28 +07 (Asia/Ho_Chi_Minh)
Coding-Agent: agy
Session-ID: b6f5ac15-ed18-491a-8ac1-2e43ddfdf0eb
# Task: yang-compilers-dedup-round2-audit
Role: Architect (read-only audit)

I will start by reading [1795020669393-architect-yang-dedup-round2-audit.prompt.md](file:///Users/sto/workspace/datomworld/collab/exception-track/1795020669393-architect-yang-dedup-round2-audit.prompt.md) and [datom.world.md](file:///Users/sto/workspace/datomworld/docs/design/datom.world.md) to understand the task requirements and architectural rules.
I will now read the governance criteria in [malleability.md](file:///Users/sto/workspace/datomworld/docs/agents/malleability.md), the Round 1 audit report in [1794725189567-engineer-yang-compilers-dedup-audit.gpt-6.1-terra.stdout.log](file:///Users/sto/workspace/datomworld/collab/exception-track/1794725189567-engineer-yang-compilers-dedup-audit.gpt-6.1-terra.stdout.log), and the architect's Round 1 malleability review in [1794853997820-architect-dedup-malleability-review.gpt-6.1-sol.findings.md](file:///Users/sto/workspace/datomworld/collab/exception-track/1794853997820-architect-dedup-malleability-review.gpt-6.1-sol.findings.md).
I will read the end of the Round 1 audit log to see the final report and its "leave target-specific" rulings.
I will now inventory all files and line counts across `src/cljc/yang/`, `src/clj/yang/`, `src/cljs/yang/`, `src/cljd/yang/`, and `src/jolt/yang/`.
I will now read the CST and IO platform files (`src/clj/yang/antlr/cst.clj`, `src/cljs/yang/antlr/cst.cljs`, `src/cljd/yang/antlr/cst.cljd`, `src/jolt/yang/antlr/cst.jolt`, `src/clj/yang/typescript/antlr/cst.clj`, `src/cljc/yang/php/antlr/cst.cljc`, `src/clj/yang/io.clj`, and `src/cljs/yang/io.cljs`) to compare their structure and identify shared vs. host-specific code.
I will now read `yang.antlr/export.cljc`, `yang.antlr/packet.cljc`, `yang.python.antlr/parser.cljc`, and the four `yang.php.antlr/eval_*.cljc` files.
I will now read the `src/cljc/yang/core/*.cljc` namespaces and root `src/cljc/yang/{frontend,stage,safepoint,tails}.cljc` files to inspect the shared substrate and evaluate the P2-1 public interface bounds.
I will now read the per-target `safepoint`, `siteset`, `publication`, `repl`, `uast`, and `builtins` files across PHP, Python, and TypeScript.
I will now read `src/cljc/yang/clojure/prelude.cljc`, `src/cljc/yang/php/antlr/prelude_module.cljc`, and the emitter sections of the PHP, Python, and TypeScript `prelude.cljc` files.
I will now read `src/cljc/yang/php/antlr/prelude_p9.cljc` and inspect key regions of `src/cljc/yang/php/antlr/lower.cljc`, `src/cljc/yang/python/antlr/lower.cljc`, `src/cljc/yang/typescript/antlr/lower.cljc`, and `src/cljc/yang/clojure.cljc`.
I will now read `src/cljc/yang/clojure.cljc` to inspect the Clojure compiler surface and compare it against `yang.core.*`.
I will now check all public vars and their external callers across `src/` and `test/` for `yang.core.prelude`, `yang.core.lower`, `yang.core.module`, and the rest of `yang.core.*` to evaluate P2-1 against `docs/agents/malleability.md`.
I will now check exact qualified/referred callers for every public function in `yang.core.prelude`, `yang.core.lower`, `yang.core.module`, `yang.core.claims`, and `yang.core.uast` across `src/` and `test/`.
I will read the full output from the caller check for `yang.core.lower` and `yang.core.module`.
I will now run an analysis across all `yang` namespaces to find shared function/var names and compare their implementations.
I will read the full duplicate-name table from the script output.
I will now inspect `define` in the PHP, Python, and TypeScript preludes and check how `yang.php.antlr.lower` uses `yang.core.lower` compared to Python and TypeScript.
I will now search for `vconj` and other repeated idioms across `src/cljc/yang/`.
I will read the `yang.php.antlr.lower` matches from the search output.
I will read the end of the search output to see the remaining matches in `yang.php.antlr.lower`.
I will read lines 160–250 of the search output.
I will now inspect `deps.edn` and the directory layout of `src/clj`, `src/cljs`, `src/cljd`, and `src/jolt` to see how host-specific namespaces are organized across the repository.
I will now read [src/jolt/README.md](file:///Users/sto/workspace/datomworld/src/jolt/README.md) and the relevant sections of [docs/design/yang.antlr.md](file:///Users/sto/workspace/datomworld/docs/design/yang.antlr.md) to verify the host boundary rules for `yang.antlr.cst`.
I will now read sections 4.3, 5.1, and 5.5 of [docs/design/yang.antlr.md](file:///Users/sto/workspace/datomworld/docs/design/yang.antlr.md).
I will now check all references to `yang.io` across `src/` and `test/`.
I will check if any governance or ABI audit test inspects `yang/io` or `src/cljc` I/O boundaries.
I will read [docs/design/datom.world.abi.md](file:///Users/sto/workspace/datomworld/docs/design/datom.world.abi.md) to ensure any recommendations respect the repository's host boundary and ABI rules.
I will now read lines 520–545 of `src/cljc/yang/python/antlr/lower.cljc` to check how `module-symbol` is implemented there.
I will now check all references to `evidence-record`, `evidence-datom`, and `yang.python.antlr.uast` across `src/` and `test/`.
I will check the public function surface and external callers of `yang.antlr.packet` and `yang.antlr.export`.
I will now capture the current GMT and local (+07) timestamps for the audit report header.
Completed-GMT: 2026-11-18 17:06:28 GMT
Completed-Local: 2026-11-19 00:06:28 +07

## 1. Current `yang.*` Surface Inventory (post-`1604aabe`)

Total across all `src/*/yang/` code and docs: **37,925 lines** across **59 files** (**37,178 lines** of code across **56 source files**, plus **747 lines** across 3 markdown docs).

### Shared Core & Root (`src/cljc/yang/`) — 6,053 lines (19 code files)
- **Root stages & Clojure compiler:**
  - [clojure.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc) — 3,121 lines
  - [clojure/prelude.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure/prelude.cljc) — 248 lines
  - [safepoint.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/safepoint.cljc) — 496 lines
  - [frontend.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/frontend.cljc) — 224 lines
  - [stage.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/stage.cljc) — 83 lines
  - [tails.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/tails.cljc) — 56 lines
- **ANTLR packet & export (`src/cljc/yang/antlr/`):**
  - [packet.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/antlr/packet.cljc) — 487 lines (17 public `defn`, 3 public `def`)
  - [export.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/antlr/export.cljc) — 159 lines (7 public `defn`)
- **Core shared substrate (`src/cljc/yang/core/`):**
  - [prelude.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/core/prelude.cljc) — 198 lines (16 public `defn`, 1 private `defn-`)
  - [call.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/core/call.cljc) — 197 lines (1 public `defn`, 6 public `def`)
  - [publication.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/core/publication.cljc) — 172 lines (2 public `defn`, 2 private `defn-`)
  - [uast.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/core/uast.cljc) — 151 lines (11 public `defn`, 3 public `def`)
  - [lower.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/core/lower.cljc) — 111 lines (12 public `defn`)
  - [module.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/core/module.cljc) — 103 lines (8 public `defn`)
  - [stage.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/core/stage.cljc) — 95 lines (4 public `defn`)
  - [claims.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/core/claims.cljc) — 74 lines (3 public `defn`, 2 private `defn-`)
  - [names.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/core/names.cljc) — 69 lines (1 public `defn`, 3 private `defn-`)
  - [siteset_store.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/core/siteset_store.cljc) — 67 lines (2 public `defn`)
  - [siteset_publication.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/core/siteset_publication.cljc) — 31 lines (3 public `defn`)

### Target ANTLR Trees (`src/cljc/yang/{php,python,typescript}/antlr/`) — 29,805 lines (30 files)
- **PHP (`src/cljc/yang/php/antlr/` — 13,441 lines, 16 files):**
  - [prelude.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/prelude.cljc) (6,358), [lower.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/lower.cljc) (4,214), [prelude_p9.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/prelude_p9.cljc) (1,169), [prelude_module.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/prelude_module.cljc) (431), [siteset.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/siteset.cljc) (296), [scope.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/scope.cljc) (227), [builtins.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/builtins.cljc) (181), [eval_coordinator.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/eval_coordinator.cljc) (136), [eval_admission.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/eval_admission.cljc) (87), [cst.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/cst.cljc) (72), [publication.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/publication.cljc) (71), [safepoint.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/safepoint.cljc) (70), [eval_transport.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/eval_transport.cljc) (34), [eval_publication.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/eval_publication.cljc) (24), [quoted.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/quoted.cljc) (15), [demo_packets.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/demo_packets.cljc) (6).
- **Python (`src/cljc/yang/python/antlr/` — 8,332 lines, 10 files):**
  - [prelude.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/prelude.cljc) (3,820), [lower.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/lower.cljc) (2,371), [siteset.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/siteset.cljc) (488), [safepoint.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/safepoint.cljc) (476), [scope.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/scope.cljc) (387), [render.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/render.cljc) (190), [repl_probe.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/repl_probe.cljc) (170), [parser.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/parser.cljc) (159), [repl.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/repl.cljc) (108), [publication.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/publication.cljc) (80), [uast.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/uast.cljc) (44).
- **TypeScript (`src/cljc/yang/typescript/antlr/` — 8,032 lines, 5 files):**
  - [prelude.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/typescript/antlr/prelude.cljc) (4,445), [lower.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/typescript/antlr/lower.cljc) (2,406), [scope.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/typescript/antlr/scope.cljc) (787), [safepoint.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/typescript/antlr/safepoint.cljc) (233), [render.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/typescript/antlr/render.cljc) (161).

### Host Platform Variants (`src/{clj,cljs,cljd,jolt}/yang/`) — 1,320 lines (7 files)
- [src/clj/yang/antlr/cst.clj](file:///Users/sto/workspace/datomworld/src/clj/yang/antlr/cst.clj) — 221 lines
- [src/cljs/yang/antlr/cst.cljs](file:///Users/sto/workspace/datomworld/src/cljs/yang/antlr/cst.cljs) — 221 lines
- [src/cljd/yang/antlr/cst.cljd](file:///Users/sto/workspace/datomworld/src/cljd/yang/antlr/cst.cljd) — 217 lines
- [src/jolt/yang/antlr/cst.jolt](file:///Users/sto/workspace/datomworld/src/jolt/yang/antlr/cst.jolt) — 459 lines
- [src/clj/yang/typescript/antlr/cst.clj](file:///Users/sto/workspace/datomworld/src/clj/yang/typescript/antlr/cst.clj) — 79 lines
- [src/clj/yang/io.clj](file:///Users/sto/workspace/datomworld/src/clj/yang/io.clj) — 60 lines
- [src/cljs/yang/io.cljs](file:///Users/sto/workspace/datomworld/src/cljs/yang/io.cljs) — 63 lines

---

## 2. Candidate Clusters: Evidence, Proposed Seam, Savings, and Risk

### Cluster C1: Remaining Site-Set Store Reader & Publication Primitives (`yang.php.antlr.siteset` vs `yang.python.antlr.siteset`)

Round 1 extracted `load-tree` and `overlay` into [siteset_store.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/core/siteset_store.cljc#L6-L67) and `signed-entry`, `entry-datoms`, `signed-entries` into [siteset_publication.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/core/siteset_publication.cljc#L6-L31), but left two identical sub-clusters duplicated in [php/antlr/siteset.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/siteset.cljc) and [python/antlr/siteset.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/siteset.cljc):

1. **Segment reading & absence sentinels (`read-at`, `absent?`, `missing`, `load-tree` wrapper):**
   - [php/antlr/siteset.cljc:51-57](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/siteset.cljc#L51-L57) and [148-160](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/siteset.cljc#L148-L160):
     ```clojure
     (defn- read-at
       [handle address]
       (when (jing/segment-address? address)
         (let [bytes ((:get-bytes-fn handle) address ::absent)]
           (cond
             (or (nil? bytes) (= ::absent bytes)) ::absent
             (jing/segment-bytes-match? address bytes) (jing/segment-value address bytes)))))
     ...
     (defn- absent? [x] (= ::absent x))
     (defn- missing [address] {:status :missing, :address address})
     (defn- load-tree [handle root] (store/load-tree read-at absent? handle root))
     ```
   - [python/antlr/siteset.cljc:106-131](file:///Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/siteset.cljc#L106-L131) and [305-307](file:///Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/siteset.cljc#L305-L307):
     ```clojure
     (defn- read-at
       [handle address]
       (if-not (jing/segment-address? address)
         nil
         (let [bs ((:get-bytes-fn handle) address ::absent)]
           (cond
             (= ::absent bs) ::absent
             (nil? bs) ::absent
             (jing/segment-bytes-match? address bs) (jing/segment-value address bs)
             :else nil))))
     (defn- absent? [x] (= ::absent x))
     (defn- missing [address] {:status :missing, :address address})
     ...
     (defn- load-tree [handle root] (store/load-tree read-at absent? handle root))
     ```
   - Notice that `::absent` is never leaked across a public boundary (both namespaces test it solely with `absent?` and convert it to `{:status :missing, :address ...}`).

2. **Site-set projection and record publication (`site-set-of`, `record`, `publish!`):**
   - [php/antlr/siteset.cljc:27-48](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/siteset.cljc#L27-L48):
     ```clojure
     (defn site-set-of
       [ast]
       (let [env (encoder/project (encoder/source-envelope
                                    :yang.php.antlr/site-set [:site-set 0] [ast]))
             tree (nth (:yin/batch env) (:yin/root env))
             origin (vm/source-origin (:yin/source-medium env)
                                      (:yin/batch-token env) (:yin/root env))
             sites (safepoint/sites (:yin/frontend-metadata env) origin (:root tree))]
         {:root (:root tree), :set (safepoint/site-set sites)}))

     (defn publish!
       [handle manifest ast]
       (let [{:keys [root set]} (site-set-of ast)
             set-address (jing/materialize! handle (vec set))
             record {:yang.site/manifest manifest, :yang.site/root root,
                     :yang.site/revision frontend-revision,
                     :yang.site/profile profile-address, :yang.site/set set-address}]
         {:record record, :record-address (jing/materialize! handle record),
          :set-address set-address, :set set}))
     ```
   - [python/antlr/siteset.cljc:67-103](file:///Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/siteset.cljc#L67-L103) is identical except `site-set-of` uses `:yang.python.antlr/site-set` as the medium and includes `:tree tree` in the returned map (which PHP callers ignore).

- **Proposed seam:**
  - Move `read-at`, `absent?`, and `missing` into [yang.core.siteset-store](file:///Users/sto/workspace/datomworld/src/cljc/yang/core/siteset_store.cljc) (raising its public surface from 2 to **5** entry points: `read-at`, `absent?`, `missing`, `load-tree`, `overlay` — within the 2–5 bound) and simplify `store/load-tree` to `[handle root]` (keep the 4-arg arity if transitionally needed).
  - Add `site-set-of` (`[medium ast]`, returning `{:root (:root tree), :tree tree, :set (safepoint/site-set sites)}`) and `publish!` (`[handle medium frontend-revision profile-address manifest ast]`) to [yang.core.siteset-publication](file:///Users/sto/workspace/datomworld/src/cljc/yang/core/siteset_publication.cljc) (raising its public surface from 3 to **5** entry points — within the 2–5 bound).
- **Estimated net savings:** **~50–60 lines** across `php/antlr/siteset.cljc` and `python/antlr/siteset.cljc`.
- **Risk:** **Low.** Content addresses are unchanged (`:yang.site/*` record keys and `safepoint/site-set` vector materialization are identical; medium identity is passed in).

---

### Cluster C2: Duplicate Host-Export Resolution & Free-Name Calculation Between `yang.core.module` and `yang.core.prelude`

[yang.core.module](file:///Users/sto/workspace/datomworld/src/cljc/yang/core/module.cljc#L9-L14) already `:require`s `[yang.core.prelude :as prelude]`, yet duplicates two functions from [yang.core.prelude](file:///Users/sto/workspace/datomworld/src/cljc/yang/core/prelude.cljc):

- [core/prelude.cljc:111-115](file:///Users/sto/workspace/datomworld/src/cljc/yang/core/prelude.cljc#L111-L115) vs [core/module.cljc:59-66](file:///Users/sto/workspace/datomworld/src/cljc/yang/core/module.cljc#L59-L66):
  ```clojure
  ;; yang.core.prelude/free-name-vec:
  (defn free-name-vec [ast]
    (vec (sort-by str (remove (conj (defined-keys ast) 'yin/def) (free-names ast)))))

  ;; yang.core.module/free-primitives:
  (defn free-primitives [refusal-key refusal registry covered ast]
    (let [free (remove (conj (prelude/defined-keys ast) 'yin/def)
                       (prelude/free-names ast))]
      (into {}
            (keep (partial declare-free-name refusal-key refusal registry covered))
            (sort-by str free))))
  ```
- [core/prelude.cljc:123-135](file:///Users/sto/workspace/datomworld/src/cljc/yang/core/prelude.cljc#L123-L135) vs [core/module.cljc:37-56](file:///Users/sto/workspace/datomworld/src/cljc/yang/core/module.cljc#L37-L56):
  ```clojure
  ;; yang.core.prelude/host-declaration:
  (defn host-declaration [registry n]
    (if-let [profile (vm/profile-of vm/primitives n)]
      [n profile]
      (when-let [address (module/host-export-profile registry n)]
        (let [entry (module/module-entry (:modules registry) (symbol (namespace n)))
              f (get-in entry [:slice (symbol (name n))])]
          [n {:yin.k/profile address,
              :yin.k/effects (get (:callable-effects registry) f #{})}]))))

  ;; yang.core.module/declare-free-name:
  (defn declare-free-name [refusal-key refusal registry covered n]
    (if (and (symbol? n) (some? (namespace n))
             (contains? covered (symbol (namespace n))))
      nil
      (if-let [profile (vm/profile-of vm/primitives n)]
        [n profile]
        (if-let [address (module/host-export-profile registry n)]
          (let [entry (module/module-entry (:modules registry)
                                           (symbol (namespace n)))
                f (get-in entry [:slice (symbol (name n))])]
            [n {:yin.k/profile address,
                :yin.k/effects
                (get (:callable-effects registry) f #{})}])
          (throw (ex-info "the module reads an undeclared name"
                          {refusal-key refusal, :name n}))))))
  ```

- **Proposed seam:** Have `yang.core.module/declare-free-name` delegate to `(or (prelude/host-declaration registry n) (throw ...))` and `yang.core.module/free-primitives` iterate over `(prelude/free-name-vec ast)`. This also allows [yang.core.module](file:///Users/sto/workspace/datomworld/src/cljc/yang/core/module.cljc#L13-L14) to drop its `[yin.vm :as vm]` and `[yin.vm.module :as module]` requires entirely (reducing its outgoing dependency count `out` from 5 to 3).
- **Estimated net savings:** **~15 lines** and **-2 dependency edges** on `yang.core.module`.
- **Risk:** **None** (exact behavioral identity).

---

### Cluster C3: Unwired Round-1 Extractions Across `lower.cljc`, `prelude.cljc`, and `core/call.cljc`

Five call sites were left inline when Round 1's shared helpers landed in `1604aabe`:

1. **Inlined `core-module/module-tree` (`prelude/wide-app`) in 3 packet builders:**
   - [php/antlr/lower.cljc:4021-4023](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/lower.cljc#L4021-L4023) (`module-packet`) and [4080-4082](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/lower.cljc#L4080-L4082) (`classmap-packet`):
     ```clojure
           operands (into [(core-module/require-node prelude/module-name)] defs)
           params (mapv (fn [i] (symbol (str "%d" i))) (range (count operands)))]
       (u/mark-tails (apply u/app (u/lam params (u/lit nil)) operands))))
     ```
   - [python/antlr/lower.cljc:2112-2115](file:///Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/lower.cljc#L2112-L2115) (`module-packet`):
     ```clojure
           operands (into [(require-node 'yang.call)
                           (require-node prelude/module-name)] defs)
           params (mapv (fn [i] (symbol (str "%d" i))) (range (count operands)))]
       (u/mark-tails (apply u/app (u/lam params (u/lit nil)) operands))))
     ```
   - Compare with [core/prelude.cljc:58-60](file:///Users/sto/workspace/datomworld/src/cljc/yang/core/prelude.cljc#L58-L60) (`wide-app` / `core-module/module-tree`, already imported in both files):
     ```clojure
     (defn wide-app [nodes]
       (let [params (mapv (fn [i] (symbol (str "%d" i))) (range (count nodes)))]
         (u/mark-tails (apply u/app (u/lam params (u/lit nil)) nodes))))
     ```
2. **Inlined `core/build-vector` in PHP `args-vector`:**
   - [php/antlr/lower.cljc:870-873](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/lower.cljc#L870-L873):
     ```clojure
     (defn- args-vector
       "A vector of the nodes `nodes`, evaluated left to right."
       [nodes]
       (reduce (fn [acc node] (app* 'php/vconj acc node)) (u/lit []) nodes))
     ```
   - Round 1 wired Python (`lower.cljc:236`) and TypeScript (`lower.cljc:181`) to [core/lower.cljc:109-111](file:///Users/sto/workspace/datomworld/src/cljc/yang/core/lower.cljc#L109-L111) (`(core/build-vector 'php/vconj nodes)`), missing PHP's `args-vector` because of its different name. (Also [php/antlr/lower.cljc:274-276](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/lower.cljc#L274-L276) wraps `core/app*` in `(defn- app* [op-sym & args] (apply core/app* op-sym args))` instead of `(def ^:private app* core/app*)` as in Python and TypeScript.)
3. **Inlined `core/definitions->uast` in `python/antlr/prelude.cljc` and `yang.core.call`:**
   - [python/antlr/prelude.cljc:3684-3686](file:///Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/prelude.cljc#L3684-L3686):
     ```clojure
     (defn- definitions->uast
       [defs]
       (u/seq-nodes (map (fn [[k form]] (u/def! k (u/sexp->uast form))) defs)))
     ```
     which is verbatim [core/prelude.cljc:196-198](file:///Users/sto/workspace/datomworld/src/cljc/yang/core/prelude.cljc#L196-L198) (`(core/definitions->uast defs)`).
   - [core/call.cljc:158-165](file:///Users/sto/workspace/datomworld/src/cljc/yang/core/call.cljc#L158-L165):
     ```clojure
     (def uast
       "The bundled definitions, in source order and tail marked."
       (u/mark-tails
         (reduce (fn [ast [key form]]
                   (u/then ast (u/def! key (u/sexp->uast form))))
                 (u/def! (ffirst definitions)
                         (u/sexp->uast (second (first definitions))))
                 (rest definitions))))
     ```
     which is `(u/mark-tails (core/definitions->uast definitions))`.

- **Proposed seam:** Replace the 6 inline implementations with calls to the already-imported `core-module/module-tree`, `core/build-vector`, `core/app*`, and `core/definitions->uast`.
- **Estimated net savings:** **~18–22 lines**.
- **Risk:** **None** (100% AST and row-address preserving).

---

### Cluster C4: `src/clj/yang/io.clj` vs `src/cljs/yang/io.cljs`

- **Evidence:** [src/clj/yang/io.clj:1-60](file:///Users/sto/workspace/datomworld/src/clj/yang/io.clj#L1-L60) (60 lines) and [src/cljs/yang/io.cljs:1-63](file:///Users/sto/workspace/datomworld/src/cljs/yang/io.cljs#L1-L63) (63 lines) define the exact same 6 functions (`read-forms`, `read-source`, `compile-file`, `compile-string`, `write-ast`, `compile-and-save`). Four of the six functions are character-for-character identical:
  ```clojure
  ;; Identical in both src/clj/yang/io.clj:12-17,32-60 and src/cljs/yang/io.cljs:14-19,35-63:
  (defn- read-forms [source file-name]
    (let [r (reader-types/indexing-push-back-reader source 1 file-name)]
      (loop [forms []]
        (let [form (reader/read {:eof ::eof} r)]
          (if (= form ::eof) forms (recur (conj forms form)))))))

  (defn compile-file [file-path]
    (let [forms (read-source file-path)] (mapv yang/compile forms)))

  (defn compile-string [source-str]
    (let [forms (read-forms source-str "<string>")]
      (mapv yang/compile forms)))

  (defn compile-and-save [source-path output-path]
    (let [ast (compile-file source-path)]
      (write-ast ast output-path)
      ast))
  ```
  Only `read-source` (JVM `io/reader` vs Node `(.readFileSync fs file-path "utf8")`) and `write-ast` (`spit` vs `(.writeFileSync fs ...)`) differ, and [yang.clojure:31-36](file:///Users/sto/workspace/datomworld/src/cljc/yang/clojure.cljc#L31-L36) already uses reader conditionals for `clojure.tools.reader` vs `cljs.tools.reader`.
- **Proposed seam:** Consolidate `src/clj/yang/io.clj` and `src/cljs/yang/io.cljs` into `src/cljc/yang/io.cljc` using `#?(:clj ... :cljs ...)` for the `ns` header and the 2 host file-I/O lines in `read-source` and `write-ast`.
- **Estimated net savings:** **~50 lines** (eliminates one entire file).
- **Risk:** **Low** (only JVM and Node load `yang.io`; `cljd` and `jolt` do not use it).

---

### Cluster C5: CST Host Exporters (`src/{clj,cljs,cljd,jolt}/yang/antlr/cst.*`) and `yang.antlr.export`

- **Audit of the CST triple + Jolt variant:**
  - [src/clj/yang/antlr/cst.clj](file:///Users/sto/workspace/datomworld/src/clj/yang/antlr/cst.clj) (221 lines), [src/cljs/yang/antlr/cst.cljs](file:///Users/sto/workspace/datomworld/src/cljs/yang/antlr/cst.cljs) (221 lines), and [src/cljd/yang/antlr/cst.cljd](file:///Users/sto/workspace/datomworld/src/cljd/yang/antlr/cst.cljd) (217 lines) are **genuinely host-specific** for ~80% of their bodies and **should NOT be collapsed into a single `src/cljc/yang/antlr/cst.cljc`**:
    - Each targets a different ANTLR4 runtime API with pervasive host type hints (`^Lexer`, `^Parser`, `^"[Lorg.antlr.v4.runtime.dfa.DFA;"` on JVM; `^js` and CJS/ESM `.-default` fallback on Node; `^a/Lexer`, `^a/Parser`, and Dart typed-list `.toList` copying in `fresh-dfa` on ClojureDart).
    - Listener creation uses `proxy` on JVM ([cst.clj:81](file:///Users/sto/workspace/datomworld/src/clj/yang/antlr/cst.clj#L81)), property mutation on Node ([cst.cljs:97](file:///Users/sto/workspace/datomworld/src/cljs/yang/antlr/cst.cljs#L97)), and `reify :extends` on Dart ([cst.cljd:81](file:///Users/sto/workspace/datomworld/src/cljd/yang/antlr/cst.cljd#L81)).
    - `first-failure` diverges because JVM's `CommonTokenStream` fills lazily on `NoViableAltException` ([cst.clj:193-194](file:///Users/sto/workspace/datomworld/src/clj/yang/antlr/cst.clj#L193-L194)), whereas JS and Dart fill to EOF during error reporting and must intercept `sync(i)` via property mutation ([cst.cljs:194-198](file:///Users/sto/workspace/datomworld/src/cljs/yang/antlr/cst.cljs#L194-L198)) or `reify :extends` ([cst.cljd:186-192](file:///Users/sto/workspace/datomworld/src/cljd/yang/antlr/cst.cljd#L186-L192)).
    - [src/jolt/yang/antlr/cst.jolt](file:///Users/sto/workspace/datomworld/src/jolt/yang/antlr/cst.jolt) (459 lines) is a whole-file Jolt host override over `jolt.ffi` and `libjolt_parser`'s binary `JPX1` layout; it shares no recognizer objects with the other three hosts.
  - **What IS genuinely duplicated across the CST files:**
    1. **Internal duplication in `src/clj/yang/antlr/cst.clj`:** Unlike [cst.cljs:70-81](file:///Users/sto/workspace/datomworld/src/cljs/yang/antlr/cst.cljs#L70-L81) and [cst.cljd:62-71](file:///Users/sto/workspace/datomworld/src/cljd/yang/antlr/cst.cljd#L62-L71), which extract `own-lexer!` and `own-parser!`, [cst.clj:132-148](file:///Users/sto/workspace/datomworld/src/clj/yang/antlr/cst.clj#L132-L148) (`parse-unit`) and [cst.clj:196-210](file:///Users/sto/workspace/datomworld/src/clj/yang/antlr/cst.clj#L196-L210) (`first-failure`) duplicate the 14-line `LexerATNSimulator.` and `ParserATNSimulator.` installation with array type hints:
       ```clojure
       ;; Repeated twice in src/clj/yang/antlr/cst.clj (lines 132-148 and 196-210):
       _ (.setInterpreter lexer
                          (LexerATNSimulator. lexer
                                              ^ATN (:lexer-atn worker)
                                              ^"[Lorg.antlr.v4.runtime.dfa.DFA;" (:lexer-dfa worker)
                                              ^PredictionContextCache (:lexer-cache worker)))
       ...
       _ (.setInterpreter parser
                          (ParserATNSimulator. parser
                                               ^ATN (:parser-atn worker)
                                               ^"[Lorg.antlr.v4.runtime.dfa.DFA;" (:parser-dfa worker)
                                               ^PredictionContextCache (:parser-cache worker)))
       ```
    2. **Cross-host export & lexer-span plumbing in `parse-unit` and `error-listener`:** All three ANTLR runtime hosts ([cst.clj:84-94, 126-153](file:///Users/sto/workspace/datomworld/src/clj/yang/antlr/cst.clj#L84-L94), [cst.cljs:106-116, 144-159](file:///Users/sto/workspace/datomworld/src/cljs/yang/antlr/cst.cljs#L106-L116), [cst.cljd:94-105, 137-152](file:///Users/sto/workspace/datomworld/src/cljd/yang/antlr/cst.cljd#L94-L105)) plus [cst.jolt:405-423](file:///Users/sto/workspace/datomworld/src/jolt/yang/antlr/cst.jolt#L405-L423) repeat the lexer-error span calculation `[(export/offset-at offsets a) (export/offset-at offsets b)]` and the `cps`/`offsets`/`export/result`/`export/export-nodes` ceremony.
- **Proposed seam:**
  1. Factor `own-lexer!` and `own-parser!` in [src/clj/yang/antlr/cst.clj](file:///Users/sto/workspace/datomworld/src/clj/yang/antlr/cst.clj), matching `cst.cljs` and `cst.cljd`.
  2. Add `lexer-span` (`[offsets start stop] [(offset-at offsets start) (offset-at offsets stop)]`) and `export-tree` (`[profile unit source errors ops-fn tree]`) to [yang.antlr.export](file:///Users/sto/workspace/datomworld/src/cljc/yang/antlr/export.cljc).
- **Estimated net savings:** **~25–30 lines** across `cst.clj`, `cst.cljs`, `cst.cljd`, `cst.jolt`.
- **Risk:** **Low** (keeps host interop in per-host files while moving pure offset/export composition to `yang.antlr.export`).

---

### Cluster C6: PHP Eval-Bridge Rejection Envelope & Correlation Extraction (`yang.php.antlr.eval_*`)

- **Audit of `eval_*.cljc` (`eval_admission`, `eval_coordinator`, `eval_publication`, `eval_transport` — 281 lines total):**
  - **Does any of it belong in `yang.core` today?** **No.**
    - [eval_admission.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/eval_admission.cljc) (87 lines) validates and decodes a PHP Latin-1 byte-carrier string (1 code unit per raw byte `0..255`) into UTF-8 and emits `:yang.php/diagnostic`. Python and TypeScript strings are not raw byte carriers.
    - [eval_publication.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/eval_publication.cljc) (24 lines) wires `yang.php.antlr.lower/eval-module-spec` and `yang.php.antlr.siteset/publish!`.
    - While [eval_transport.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/eval_transport.cljc) (34 lines) and the bounded stream loop `serve` in [eval_coordinator.cljc:117-136](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/eval_coordinator.cljc#L117-L136) use `:yang.compile/*` keys (except `:yang.php/source`), PHP is currently the **only** target with a runtime `eval` service (Python refuses `eval_input` at [python/antlr/lower.cljc:97](file:///Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/lower.cljc#L97); TypeScript has none). Per [malleability.md](file:///Users/sto/workspace/datomworld/docs/agents/malleability.md#L131) (*"Extract when: The structure has already emerged in your code. You're just formalizing what exists, not designing from scratch"*), lifting `eval_transport`/`serve` to `yang.core` before a second language implements runtime `eval` would be premature abstraction.
  - **Internal duplication inside `eval_*.cljc`:**
    The correlation key projection `[:yang.compile/request :yang.compile/unit :yang.compile/provenance]` and rejection map construction `{:yang.compile/outcome :yang.compile/rejected, :yang.compile/reason ...}` are repeated **6 times** across [eval_admission.cljc:77-86](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/eval_admission.cljc#L77-L86) and [eval_coordinator.cljc:61-69, 82-84, 92-94, 106-113](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/eval_coordinator.cljc#L61-L113):
    ```clojure
    ;; eval_admission.cljc:77-86
    diagnostic (assoc (select-keys request [:yang.compile/request :yang.compile/unit
                                            :yang.compile/provenance])
                      :yang.compile/outcome :yang.compile/rejected
                      :yang.compile/reason :yang.compile/unsupported
                      :yang.compile/diagnostic diagnostic)
    reason (assoc (select-keys request [:yang.compile/request :yang.compile/unit
                                        :yang.compile/provenance])
                  :yang.compile/outcome :yang.compile/rejected
                  :yang.compile/reason :yang.compile/invalid-request
                  :yang.compile/detail reason)

    ;; eval_coordinator.cljc:61-69 (and 3 more times at lines 82, 92, 106)
    correlation (when (map? request)
                  (select-keys request [:yang.compile/request :yang.compile/unit
                                        :yang.compile/provenance]))
    ...
    (merge correlation {:yang.compile/outcome :yang.compile/rejected
                        :yang.compile/reason :yang.compile/invalid-source
                        :yang.compile/diagnostics (:yang.cst/errors cst)})
    ```
- **Proposed seam:** Add `correlation` (`[request]`) and `rejected` (`[request reason extra]`) to [yang.php.antlr.eval-transport](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/eval_transport.cljc) (raising its public surface from 2 to 4 entry points) and call `transport/rejected` from both `eval-admission` and `eval-coordinator`.
- **Estimated net savings:** **~18–22 lines**.
- **Risk:** **None.**

---

### Cluster C7: Literal Host-Double & Radix-Digit Accumulators in `lower.cljc` (PHP, Python, TypeScript)

1. **3-way reader conditional for `host-double` (`Double/parseDouble` / `js/parseFloat` / `double/parse`):**
   - [php/antlr/lower.cljc:312-317](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/lower.cljc#L312-L317):
     ```clojure
     (u/lit (cbor/float64 #?(:cljd (double/parse t)
                             :clj (Double/parseDouble t)
                             :cljs (js/parseFloat t))))
     ```
   - [python/antlr/lower.cljc:337-340](file:///Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/lower.cljc#L337-L340):
     ```clojure
     (u/lit {:py/float (cbor/float64
                         #?(:cljd (double/parse t)
                            :clj (Double/parseDouble t)
                            :cljs (js/parseFloat t)))})
     ```
   - [typescript/antlr/lower.cljc:380-384](file:///Users/sto/workspace/datomworld/src/cljc/yang/typescript/antlr/lower.cljc#L380-L384):
     ```clojure
     (defn- host-double
       [t]
       #?(:cljd (double/parse t)
          :clj (Double/parseDouble t)
          :cljs (js/parseFloat t)))
     ```
2. **Exact radix-digit accumulator (repeated 6 times across the 3 lowerers):**
   - [python/antlr/lower.cljc:347-355](file:///Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/lower.cljc#L347-L355) (`escape-value`), [typescript/antlr/lower.cljc:304-307](file:///Users/sto/workspace/datomworld/src/cljc/yang/typescript/antlr/lower.cljc#L304-L307) (`hex-value`) and [412-416](file:///Users/sto/workspace/datomworld/src/cljc/yang/typescript/antlr/lower.cljc#L412-L416) (`radix-int`), and [php/antlr/lower.cljc:209-218](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/lower.cljc#L209-L218) (`hex-value`, `octal-value`) and [2343-2345](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/lower.cljc#L2343-L2345) (`digits-value`):
     ```clojure
     (reduce (fn [acc ch]
               (+ (* acc radix)
                  (str/index-of "0123456789abcdef" (str/lower-case (str ch)))))
             0
             digits)
     ```
   - Note: per Round 1's binding ruling, validation and syntax diagnostics (`syntax!`) must remain in the target callers; only the pure digit accumulator `escape-value` (`[digits radix]`) and `host-double` (`[t]`) are target-neutral.
- **Proposed seam:** If `yang.core.lower` is kept unsplit (see §5), wait or add only if paired with a P2-1 split; if literal helpers are grouped, expose `host-double` and `radix-digits` (`[digits radix]`) alongside `code-point->str`.
- **Estimated net savings:** **~20–25 lines** across the 3 `lower.cljc` files.
- **Risk:** **Low**, provided target-specific regex validation and `syntax!` messages remain in the callers and `yang.core.lower`'s public count is managed per §5.

---

## 3. Ranked Top Opportunities (`Savings × Soundness × Malleability-Benefit`)

| Rank | Cluster | Summary | Est. Savings | Soundness | Malleability Benefit & Verdict |
|---|---|---|---|---|---|
| **1** | **C1** | Lift `read-at`, `absent?`, `missing` into [yang.core.siteset-store](file:///Users/sto/workspace/datomworld/src/cljc/yang/core/siteset_store.cljc) (2→5 public fns) and `site-set-of`, `publish!` into [yang.core.siteset-publication](file:///Users/sto/workspace/datomworld/src/cljc/yang/core/siteset_publication.cljc) (3→5 public fns). | **50–60 lines** | High | Completes Round 1's site-set extraction while keeping both new namespaces within the 2–5 bounded complexity target and removing the curried `read-at`/`absent?` plumbing from `load-tree`. |
| **2** | **C2 + P2-1 hygiene** | Delegate [yang.core.module/declare-free-name](file:///Users/sto/workspace/datomworld/src/cljc/yang/core/module.cljc#L37-L56) and `free-primitives` to `yang.core.prelude/host-declaration` and `free-name-vec`; drop `yin.vm` and `yin.vm.module` requires from `yang.core.module`; mark caller-less `strip-form`, `placeholder` (`yang.core.prelude`), `evidence-record`, `evidence-datom` (`yang.core.claims`), and `utf8-length`, `source-error` (`yang.antlr.packet`) private (`defn-`). | **~15 lines + -2 dep edges + -6 public vars** | High | Eliminates duplicated primitive/host-export resolution inside `yang.core` itself, reduces `yang.core.module`'s outgoing coupling (`out: 5→3`), and trims accidental public API surface area. |
| **3** | **C3** | Wire the 6 missed Round-1 call sites to existing `yang.core` helpers: `core-module/module-tree` in [php/antlr/lower.cljc:4021,4080](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/lower.cljc#L4021-L4023) and [python/antlr/lower.cljc:2112](file:///Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/lower.cljc#L2112-L2115); `core/build-vector` in [php/antlr/lower.cljc:870](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/lower.cljc#L870-L873) (`args-vector`); `core/definitions->uast` in [python/antlr/prelude.cljc:3684](file:///Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/prelude.cljc#L3684-L3686) and [core/call.cljc:158](file:///Users/sto/workspace/datomworld/src/cljc/yang/core/call.cljc#L158-L165). | **18–22 lines** | High | Zero new public functions in `yang.core`; finishes wiring the exact abstractions extracted in `1604aabe`. |
| **4** | **C4** | Unify [src/clj/yang/io.clj](file:///Users/sto/workspace/datomworld/src/clj/yang/io.clj) (60 lines) and [src/cljs/yang/io.cljs](file:///Users/sto/workspace/datomworld/src/cljs/yang/io.cljs) (63 lines) into `src/cljc/yang/io.cljc`. | **~50 lines (-1 file)** | High | Eliminates 4 verbatim duplicated functions (`read-forms`, `compile-file`, `compile-string`, `compile-and-save`) across JVM and Node. |
| **5** | **C5** | Factor `own-lexer!` and `own-parser!` in [src/clj/yang/antlr/cst.clj](file:///Users/sto/workspace/datomworld/src/clj/yang/antlr/cst.clj#L132-L148) (matching `.cljs`/`.cljd`) and lift `lexer-span` / `export-tree` into [yang.antlr.export](file:///Users/sto/workspace/datomworld/src/cljc/yang/antlr/export.cljc). | **25–30 lines** | High | Removes internal simulator-setup duplication in `cst.clj` and centralizes error-span/export orchestration without tangling host interop in reader conditionals. |
| **6** | **C6** | Factor `correlation` and `rejected` envelope builders into [yang.php.antlr.eval-transport](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/eval_transport.cljc) for the 6 rejection sites in `eval_admission` and `eval_coordinator`. | **18–22 lines** | High | Improves local cohesion inside the PHP eval cluster without prematurely lifting PHP-only eval machinery to `yang.core`. |
| **7** | **C7** | Share `host-double` and `radix-digits` across PHP/Python/TypeScript `lower.cljc` (contingent on §5 P2-1 disposition). | **20–25 lines** | Medium-High | Removes 3 copies of the 3-host float parser reader conditional and 6 copies of the radix accumulator, but adds 2 entry points to `yang.core.lower` unless paired with a split. |

---

## 4. Explicit Leave-Alone List (with Reasons)

1. **The CST platform triple ([src/clj/yang/antlr/cst.clj](file:///Users/sto/workspace/datomworld/src/clj/yang/antlr/cst.clj), [src/cljs/yang/antlr/cst.cljs](file:///Users/sto/workspace/datomworld/src/cljs/yang/antlr/cst.cljs), [src/cljd/yang/antlr/cst.cljd](file:///Users/sto/workspace/datomworld/src/cljd/yang/antlr/cst.cljd)) and [src/jolt/yang/antlr/cst.jolt](file:///Users/sto/workspace/datomworld/src/jolt/yang/antlr/cst.jolt) as separate per-host files:**
   - The host-free parse-tree walk, synthetic-token policy, and failure classification already live in [yang.antlr.export](file:///Users/sto/workspace/datomworld/src/cljc/yang/antlr/export.cljc) and [yang.antlr.packet](file:///Users/sto/workspace/datomworld/src/cljc/yang/antlr/packet.cljc). What remains in `cst.{clj,cljs,cljd}` is per-host ANTLR4 runtime interop (`into-array DFA` vs JS `.map` vs Dart typed-list `.toList`; `proxy` vs JS property assignment vs Dart `reify :extends`; JVM lazy token buffer vs JS/Dart `sync` interception). Merging them into a single `.cljc` file would turn every function into a 3-way reader conditional. `cst.jolt` is a native C++ FFI and `JPX1` binary buffer decoder that must remain a `.jolt` whole-file override under `src/jolt/`.
2. **Per-target parser/CST adapters ([yang.python.antlr.parser](file:///Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/parser.cljc), [yang.php.antlr.cst](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/cst.cljc), [yang.typescript.antlr.cst](file:///Users/sto/workspace/datomworld/src/clj/yang/typescript/antlr/cst.clj)):**
   - Each encodes genuinely different target contracts: Python manages Jolt native-worker lifecycle (`available?`, `close-worker`, `with-worker`, `close-stage`, `reset-stage`, `:parser-budget` catch in `first-failure`); PHP rejects non-standard generics (`{:yang.php/not-php :generics}`), computes the UTF-8 `:yang.php/line-starts` side table, and exposes `make-eval-worker` pushing `PhpLexer/PHP` mode; TypeScript is JVM-only and parameterizes worker creation and `:yang.cst/options {:dialect dialect}` by `:ts`/`:js`.
3. **Lifting `yang.php.antlr.eval_*` to `yang.core`:**
   - PHP is the only target with a runtime `eval` service today. `eval_admission` is specific to PHP's Latin-1 byte-carrier string representation, and `eval_publication` is specific to PHP's `eval-module-spec`. Lifting `eval_transport` or `eval_coordinator/serve` to `yang.core` before a second language implements runtime `eval` violates the emergence rule in [malleability.md](file:///Users/sto/workspace/datomworld/docs/agents/malleability.md#L131).
4. **PHP prelude extensions ([prelude_module.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/prelude_module.cljc), [prelude_p9.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/prelude_p9.cljc), [builtins.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/builtins.cljc), [quoted.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/quoted.cljc)):**
   - These are modular partitions of PHP 8.3's own guest runtime (`include`/`require`/`extract`/`compact`/autoload, string/array/JSON/math builtins, and the PHP 8.3 builtin catalogue/constants) using `defquoted` for ClojureDart EDN deserialization. None duplicates Python, TypeScript, or Clojure compiler code.
5. **Alias shim [yang.python.antlr.uast](file:///Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/uast.cljc) (44 lines) and prelude `free-names`/`defined-keys` re-exports:**
   - While `yang.python.antlr.uast` is a pure 1-to-1 alias of `yang.core.uast`, it is referenced by 3 `src/` namespaces, 14 test suites, and [src/dev/jolt_j2_lane.clj:100](file:///Users/sto/workspace/datomworld/src/dev/jolt_j2_lane.clj#L100). Consistent with the Round 1 malleability review disposition, keeping established test/harness alias surfaces avoids gratuitous churn.
6. **SCC bundle AST rewrites in [php/antlr/lower.cljc:4100-4157](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/lower.cljc#L4100-L4157) and [python/antlr/lower.cljc:2194-2291](file:///Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/lower.cljc#L2194-L2291):**
   - Although both sort members, assign `m<i>-spec`/`m<i>-body` exports, and emit `core-module/alias-spec` wrappers, their bundle AST rewrites differ semantically (Python rewrites `spec` references, rewrites `py/import-from` to `cyclic-import-from`, and wraps bodies in an `:yang.python.antlr/initializing` `try-finally` guard; PHP rewrites only `body` and `try-require`).
7. **`register-integer-module` across [php/antlr/prelude.cljc:6337](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/prelude.cljc#L6337-L6348), [python/antlr/prelude.cljc:3795](file:///Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/prelude.cljc#L3795-L3805), and [typescript/antlr/prelude.cljc:4424](file:///Users/sto/workspace/datomworld/src/cljc/yang/typescript/antlr/prelude.cljc#L4424-L4435):**
   - PHP checks both `max-bits >= 4096` and `max-digits >= 1000` (refusal `:yang.php/limits`), whereas Python and TypeScript check only `max-bits >= 53` (refusal `:<ns>/max-bits`). Factoring this into `yang.core.prelude` would add a dependency on `yin.vm.integer` for negligible gain.
8. **All Round-1 binding target-specific rulings (re-verified, no contrary evidence):**
   - Guest prelude definition vectors (`php`, `py`, `ts`, `clojure`, `core/call`) and continuation handler-stack definitions;
   - Float/digit rendering in [python/antlr/render.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/render.cljc) vs [typescript/antlr/render.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/typescript/antlr/render.cljc);
   - Scope walkers ([php/antlr/scope.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/scope.cljc), [python/antlr/scope.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/scope.cljc), [typescript/antlr/scope.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/typescript/antlr/scope.cljc)) and `wrap-escape`;
   - Site verification/admission and `derive-closure` in [php/antlr/siteset.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/siteset.cljc) vs [python/antlr/siteset.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/siteset.cljc) (including PHP's `deferred-module?` check and empty-set policy vs Python's detailed defect classification);
   - Site discovery policy (`discover` in PHP checks `(= (:yin.module/asserted-by env) (sign/principal %))`; Python checks signature membership in `publics`);
   - Per-target `publication.cljc` wrappers (already thin configurations over `yang.core.publication`).

---

## 5. Judgement on P2-1 (`yang.core.prelude` at 16 public, `yang.core.lower` at 12 public)

### A. Public/Private Boundary Audit Across `yang.core.*` and `yang.antlr.*`
Caller analysis across `src/` and `test/` shows **6 public functions with zero external callers** that violate the [malleability.md](file:///Users/sto/workspace/datomworld/docs/agents/malleability.md#L78-L83) rule (*"Mark a function private (`defn-`) if it is called only from within its own namespace"*):
1. [yang.core.prelude/strip-form](file:///Users/sto/workspace/datomworld/src/cljc/yang/core/prelude.cljc#L29) — 0 external callers (called only at line 49 in `module-definitions`).
2. [yang.core.prelude/placeholder](file:///Users/sto/workspace/datomworld/src/cljc/yang/core/prelude.cljc#L37) — 0 external callers (called only at line 48 in `module-definitions`).
3. [yang.core.claims/evidence-record](file:///Users/sto/workspace/datomworld/src/cljc/yang/core/claims.cljc#L12) — 0 external callers (called only at line 41 in `ensure-evidence`).
4. [yang.core.claims/evidence-datom](file:///Users/sto/workspace/datomworld/src/cljc/yang/core/claims.cljc#L21) — 0 external callers (called only at line 42 in `ensure-evidence`).
5. [yang.antlr.packet/utf8-length](file:///Users/sto/workspace/datomworld/src/cljc/yang/antlr/packet.cljc#L73) — 0 external callers (called only at lines 85 and 95).
6. [yang.antlr.packet/source-error](file:///Users/sto/workspace/datomworld/src/cljc/yang/antlr/packet.cljc#L123) — 0 external callers (called only at lines 149, 161, 166 in `transform`).

*(Note: [yang.core.prelude/host-declaration](file:///Users/sto/workspace/datomworld/src/cljc/yang/core/prelude.cljc#L123) also has 0 external callers today, but becomes externally called by `yang.core.module` under Cluster C2.)*

### B. Verdict on Splitting `yang.core.prelude` (16 public → 14 after `defn-`)
- **Judgement: SPLIT (into 2 namespaces, or fold registry admission into `yang.core.module`).**
- **Why:** `yang.core.prelude` mixes two concerns with **disjoint outgoing dependencies**:
  1. **Pure UAST/S-exp Prelude Emission & AST Free-Name Scanning** (`define`, `strip`, `module-definitions`, `wide-app`, `module-tree`, `exports`, `free-names`, `defined-keys`, `free-name-vec`, `balanced-definitions->uast`, `definitions->uast`): depends **only** on `clojure.walk` and `yang.core.uast` (`out: 2`).
  2. **Host-Registry Resolution & Linker Module Admission** (`host-declaration`, `module-spec`, `admit`, plus private `refusal`): depends on `yin.vm` and `yin.vm.module`.
- Because Cluster 2 is also what [yang.core.module](file:///Users/sto/workspace/datomworld/src/cljc/yang/core/module.cljc) does (leading directly to the Cluster C2 duplication of `host-declaration` inside `declare-free-name`), extracting `host-declaration`, `module-spec` (or unifying it with `yang.core.module/module-spec`), and `admit` into `yang.core.prelude-admission` (3 public entry points, within the 2–5 bound) removes `yin.vm` and `yin.vm.module` from `yang.core.prelude` (`out: 4 → 2`).
- If tighter bounds on `yang.core.prelude` are desired, the pure AST scope queries (`free-names`, `defined-keys`, `free-name-vec` — 3 public entry points, `out: 0`) also form a natural self-contained cluster.

### C. Verdict on Splitting `yang.core.lower` (12 public, 111 lines)
- **Judgement: DO NOT SPLIT now (hold at 12; split only if literal/escape helpers from Cluster C7 are added).**
- **Why:**
  1. All 12 functions in [yang.core.lower](file:///Users/sto/workspace/datomworld/src/cljc/yang/core/lower.cljc) have 2–3 external callers across the exact same three consumers ([php/antlr/lower.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/php/antlr/lower.cljc), [python/antlr/lower.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/python/antlr/lower.cljc), [typescript/antlr/lower.cljc](file:///Users/sto/workspace/datomworld/src/cljc/yang/typescript/antlr/lower.cljc)). None can be made private.
  2. The entire file is 111 lines with low outgoing coupling (`out: 2` — `yang.antlr.packet` and `yang.core.uast`).
  3. Per [malleability.md](file:///Users/sto/workspace/datomworld/docs/agents/malleability.md#L120-L129), 12 functions is the *evaluation trigger*, and structural clustering (*"Share the exact same set of external dependencies"*) governs whether a split improves the dependency graph. Splitting a 111-line file into `yang.core.lower-diagnostics` (3 fns: `unsupported!`, `syntax!`, `default-arm`) and `yang.core.lower` (9 fns) today would force all three target `lower.cljc` files to add an extra `:require` edge (+3 outgoing edges across the graph) without decoupling any consumer.
  4. **Conditional trigger:** If Cluster C7 (`host-double` and `radix-digits`) is extracted, extract it together with `code-point->str` into `yang.core.literals` (3 public entry points: `code-point->str`, `host-double`, `radix-digits`, `out: 0`), which simultaneously lands C7 and drops `yang.core.lower` from 12 to 11 public functions.

AUDIT VERDICT: Complete site-set deduplication in `yang.core.siteset-store` (`read-at`/`absent?`/`missing`) and `yang.core.siteset-publication` (`site-set-of`/`publish!`), eliminate the `yang.core.module` vs `yang.core.prelude` `host-declaration`/`free-name-vec` duplication, and wire the 6 missed Round-1 call sites (`module-tree`, `build-vector`, `definitions->uast`).

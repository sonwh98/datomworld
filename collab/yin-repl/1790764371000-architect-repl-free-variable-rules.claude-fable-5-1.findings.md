Completed-GMT: 2026-09-30 10:37:45 GMT
Completed-Local: 2026-09-30 17:37:45 +07 (+0700)

# Architect ruling: occurrence-aware free-variable rules through the REPL `q`

Role: Lead System Architect. Model: claude-fable-5-1. Read-only; no files edited.

## 0. Correction to the finding

Cause (3) is misdescribed. `yin.vm/occurrence-rules` is not written against `[$ ?lam :yin/type :lambda]` triples. It uses the row pattern `[?lam :lambda ?params _]` with no source, so it reads the default `$` (`src/cljc/yin/vm.cljc:1641`).

That is a worse defect than a missing function. In the REPL, `$` is the datom index, so the pattern matches nothing and every name is reported free: a silent wrong answer, not a refusal. Supplying the two host functions alone would not fix this.

## 1. Which fix: (a) + (b), reject (c)

**Ruling.** The production rule set becomes pure portable data: one non-recursive rule over explicitly named `$ast` and `$occ`, using engine builtins only. The engine gains exactly one generic builtin, `subvec`. No yin-specific builtin, no bridge change, no named rule-set registry.

Replace `yin.vm/occurrence-rules` with:

```clojure
'[[(occ-bound? ?root ?path ?name)
   [$occ ?root ?lam-path ?lam]
   [(count ?lam-path) ?n]
   [(count ?path) ?m]
   [(< ?n ?m)]
   [(subvec ?path 0 ?n) ?lam-path]
   [$ast ?lam :lambda ?params _]
   [(identity ?params) [?name ...]]]]
```

- **Meaning is unchanged:** a name is bound at `[root path]` iff a `:lambda` occurrence of the same root sits at a proper prefix of `path` and lists the name in its params. It stays generic over every §2.3 tag and root-scoped through the `$occ` join.
- **`member?` needs no builtin:** `[(identity ?params) [?name ...]]` is the collection binding form from commit 2f030c66, already used across the bridge at `test/yin/repl/query_test.cljc:246`.
- **`path-pop` is replaced by a prefix test,** which needs `subvec`. It is the vector sibling of the existing `subs` builtin, so the vocabulary grows by one generic word.
- **Clause order is load-bearing:** `[(< ?n ?m)]` guards the `subvec` range. `plan-where` reorders only runs of adjacent pattern clauses, and here every pattern clause is separated by function clauses, so the order holds. State this in the docstring.
- **Heads:** `p-up` and `occ-anc` disappear; no source outside the rule set calls them. `occ-bound?` keeps its head and arity.

**Verified.** I ran this rule on the JVM (with `subvec` supplied under `:fns` as a stand-in for the builtin) against the current production rules. Seven trees were unioned into one relation: the mixed `((fn [x] x) x)` case, the two cross-tree trees, nested shadowing, the whole-grammar `:if`/`:stream/*` tree, a bare variable, and an empty-params lambda. The `[name path]` results were identical for every root. It has not been run on CLJS or CLJD, nor through the bridge.

**One property changes.** Today a call with `?root` unbound throws ("Rule head var not bound by rule body"). The new body binds `?root` from `$occ`, so a positive call with a free root enumerates the roots where the name is bound at that path. That is relationally sound and cannot cross trees. Inside `not`, the engine still demands every variable bound. Rewrite the docstring sentence at `vm.cljc:1633-1634`; no test pins the old behaviour.

**`subvec` builtin spec** (`src/cljc/dao/space/query.cljc`, `builtins` map):
- Arities `(subvec v start)` and `(subvec v start end)`.
- Refuse with an `ex-info` in the style of `require-numeric!` ("query builtin subvec …") when `v` is not a vector or the bounds are not integers with `0 <= start <= end <= (count v)`. The refusal text must be the same on every host.
- The result must satisfy `vector?`, be `content=` to the equal plain vector, and encode as a CBOR vector on all three hosts. Normalise with `(into [] …)` if any host's subvector type does not.

**Why not the others.**
- **(a) alone, with `path-pop`/`member?` as builtins:** puts yin path vocabulary into `dao.space.query`, and still leaves the wrong-source defect in §0.
- **(c) named rule sets resolved server-side:** the bridge would hold yin-specific knowledge, a name and version registry, and host functions behind a keyword. That is the silent shell installation the approved design rejected. It would also leave the rule set non-portable for every other peer, which conflicts with sharing code over the stream linker.
- **A `:fns` option on the bridge:** host functions cannot be portable data, and `q`'s contract stays data-only.
- **A new parent/ancestor relation from the indexer:** derive, don't persist. The prefix query derives it.

**yin.vm's own callers change, mechanically.** The rule set now names `$ast`, so callers bind the row relation as `$ast` instead of `$` and drop the `:fns` option:
- `yin.vm/free-names` (`vm.cljc:1653`): `:in $ast $occ % ?root`, `[$ast ?v :variable ?name]`.
- `yin.vm.linker/tree-occurrence-query` and its call (`linker.cljc:307-316`, `:410-413`): same.
- `yin.vm.completion/segment-scope-rules` (`completion.cljc:174-181`): replace `[(member? ?params ?name)]` with `[(identity ?params) [?name ...]]`; drop `{:fns vm/occurrence-fns}` at `:207`.
- Delete `yin.vm/occurrence-fns`. No deployed stores, so a clean break.

## 2. User-facing REPL form

Free variables of one root, with the root chosen by address:

```clojure
(require 'dao.space.query)

(def occurrence-rules
  '[[(occ-bound? ?root ?path ?name)
     [$occ ?root ?lam-path ?lam]
     [(count ?lam-path) ?n]
     [(count ?path) ?m]
     [(< ?n ?m)]
     [(subvec ?path 0 ?n) ?lam-path]
     [$ast ?lam :lambda ?params _]
     [(identity ?params) [?name ...]]]])

(dao.space.query/q
  '[:find [?name ...]
    :in $ast $occ % ?root
    :where
    [$occ ?root ?path ?v]
    [$ast ?v :variable ?name]
    (not (occ-bound? ?root ?path ?name))]
  occurrence-rules
  root)
```

The user gets `root` from the session itself, with no new shell command:
- Every root: `[:find [?root ...] :in $occ :where [$occ ?root [] ?root]]`.
- The root of a given round: add `$` to `:in`, take `?round` as an input, and lead with `[?m :yin.repl/round ?round] [?m :yin.repl/root ?root]`. Those facts are already written by `yin.repl.index/packet->tx-data` (`index.cljc:111-115`).

The answer is the raw free set. It includes `yin/def` where present, because `yin.vm/free-names` removes the definition operator in host code after the query. Document that; do not add a rule for it.

**Rules stay opt-in, and the shell ships no binding for them.** The 2026-09-27 owner ruling made even `q` user-required and treats an extra language binding as a separate policy. A host module cannot export a data constant today: `register-host-module` requires a function profile for every export. The rule set is eight lines of data the user defines once. The shell's duty is to document the literal (see §4).

## 3. The slice-3 refusal test

Delete `occurrence-rules-passed-as-data-only-refuse-due-to-missing-host-fns` from slice 3. It pins a design defect as intended behaviour, and its comment will be false once slice 4 lands.

Slice 4 replaces it with the positive test slice 3 originally required. Do not keep it as a host-function guard:
- A host function in `q`'s arguments is already refused by the portability gate (`query.cljc:478`).
- An options map other than `{:view …}` is already refused by `view-of`.

Slice 4 adds one small guard in `test/yin/repl/query_test.cljc` if not already present: a rule naming an unknown function refuses with `::query-failed`, and `{:fns …}` as options refuses with `::invalid-input`.

## 4. Slicing, files, acceptance

**Slice 3 (before commit):** remove the refusal test only. The other six tests stand. Its acceptance item 1 is carried to slice 4. It stays tests-only.

**Slice 4 (follow-up, touches `src/`, needs its own Architect-role sign-off, not by a Claude model alone):**

| File | Change |
|---|---|
| `src/cljc/dao/space/query.cljc` | add the `subvec` builtin per §1 |
| `src/cljc/yin/vm.cljc` | new `occurrence-rules` and docstring; `free-names` on `$ast`; delete `occurrence-fns` |
| `src/cljc/yin/vm/linker.cljc` | `tree-occurrence-query` on `$ast`; drop `:fns` |
| `src/cljc/yin/vm/completion.cljc` | `identity` idiom in `segment-scope-rules`; drop `:fns` |
| `src/cljc/yin/repl/query.cljc` | namespace docstring only: point to `yin.vm/occurrence-rules` as the opt-in `%` value |
| `test/dao/space/query_test.cljc` | production-rule queries move to `$ast`; the unscoped contrast fixture keeps its own private `path-pop`/`member?` |
| `test/yin/repl/ast_query_e2e_test.cljc` | positive tests below |
| `docs/design/yin.vm.code-as-tuples.md` §4.5, §6.3; `yin.vm.dependency-completion.md` ~:181 | replace `p-up`/`occ-anc`/`member?`-under-`:fns` text with the prefix rule and the `identity` idiom |

**Acceptance:**
1. **Builtin:** `subvec` unit tests for both arities, each refusal, and result type and encoding, on CLJ, CLJS and CLJD.
2. **Equivalence:** the existing `occurrence-aware-rules-resolve-mixed-free-and-bound-rows`, `occurrence-rules-are-root-scoped-across-trees` and `occurrence-walk-covers-the-whole-grammar` pass with no `:fns`. The unscoped contrast assertion still shows the cross-tree misclassification.
3. **Callers:** the `rule_r`, `linker` and `completion` suites are unchanged in outcome, including §7.7.1 tree-to-segment conformance of `:free-names`.
4. **REPL end to end, all four VMs, real input lines:**
   - After `((fn [x] (+ x y)) 1)`, the §2 query with `vm/occurrence-rules` passed as quoted data returns exactly `+` and `y`.
   - After `((fn [x] x) x)`, it returns `x`.
   - After `(fn [x] x)` and `(fn [y] x)` in one session, each root is classified by its own binder.
   - The round-join form selects the same root as the address form.
   - The rule set defined with the user's own `(def …)` line gives the same answer. This proves `%`, `...` and `_` survive the reader on every host.
5. **Guard:** the two refusals in §3.
6. **Lanes:** kondo clean; JVM, Node and Dart lanes green, with "Testing <ns>" seen for the touched namespaces.

## 5. OWNER decisions

1. **One new query-language word.** `subvec` joins the `dao.space.query` builtins, so it becomes part of what any peer must implement to run shared rule sets. Recommended: accept.
2. **Shell posture for the rule set.** Recommended and assumed: no binding; the user defines or pastes the data. The alternative is a first-prompt or on-require binding, which needs host modules to export data constants and reverses the "minimal shell" posture.
3. **Raw set versus `free-names`.** Through `q` the user sees `yin/def` as a free name where a definition is present; `yin.vm/free-names` hides it. Recommended: keep `q` raw and document it.
4. **Unscoped positive calls now enumerate instead of throwing** (§1). Recommended: accept; it is sound and cannot cross trees.

Plan mode was active for this session. I wrote no plan file, because the task forbids edits and the ruling above is the deliverable.

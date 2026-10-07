(ns yin.vm.content
  "The mint side of code content in `dao.jing` (U10, sections 2.1/4.1 of
   `docs/design/yin.vm.code-as-tuples.md`; D3: individual rows, canonical
   -- no pack format).

   A tree's rows materialize individually, each body `[tag & slots]` under
   its own row id, so shared subtrees are shared across trees at one
   address; a canonical instruction vector materializes as the exact
   vector under its `(jing/segment-key v)` address -- per UCF section
   7.3.4 the payload at a code address *is* the vector, and the segment's
   `:yin.code/hash` is that address, recorded in the VM's alias column
   when `yin.vm.semantic/load-vector` loads what `yin.vm.linker/fetch`
   returned for the `:yin.semantic/code` format.

   These two are `publish!` for the two storage-derived formats (section
   9 of `docs/design/yin.vm.linker.md`): the composition mints with them
   and holds the identity index itself -- the root row id, the vector
   address -- which is its own address. The read side is
   `yin.vm.linker/fetch` with `ast-format` or `semantic-format`: the
   linker's bounded step-2 worklist replaces the retired
   `load-rows`/`fetch-vector` loaders, and their throwing surfaces are
   the linker's refusals (`:absent`, `:address-mismatch`,
   `:descriptor-defect`). A metadata-bearing literal is either carried
   through by the backend (the memory backend stores values verbatim) or
   refused by it before the write (the file backend fails closed when its
   own text codec would not hash back); no store is left unopenable."
  (:require [dao.jing :as jing]
            [yin.vm :as vm]
            [yin.vm.code :as code]))


(defn materialize-tree!
  "Materialize every row of one projected tree `{:root id, :rows {id row}}`
   individually under its own address (D3): each row's body
   `(subvec row 1)` through `jing/materialize!`, which derives exactly the
   row's id. The tree is validated (§7.4) first, so an invalid tree is
   refused before the first write. Returns the root row id — the tree's
   address (§4.1)."
  [handle {:keys [root rows] :as tree}]
  (when-let [{:keys [rule path id]} (vm/validate-rows tree)]
    (throw (ex-info "Cannot materialize rows that fail validation"
                    (cond-> {:rule rule}
                      path (assoc :path path)
                      id (assoc :id id)))))
  (doseq [row (vals rows)]
    (jing/materialize! handle (subvec row 1)))
  root)


(defn materialize-vector!
  "Materialize one canonical instruction vector as the exact payload at its
   own `(jing/segment-key v)` address (UCF §7.3.4: nothing else may hash
   there). The vector is validated (§7.5) first, so a malformed vector is
   refused before the write. Returns the address — the segment's
   `:yin.code/hash`."
  [handle v]
  (when-let [defect (code/well-formed-vector? v)]
    (throw (ex-info "Cannot materialize a vector that fails validation"
                    {:defect defect})))
  (jing/materialize! handle v))

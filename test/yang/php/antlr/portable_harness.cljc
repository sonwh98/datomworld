(ns yang.php.antlr.portable-harness
  "What the JVM, Node and Dart lanes share: the host composition the PHP
   profile runs over, the `packet` helper that builds a CST packet from a
   nested form, and a parserless run of a packet on the AST walker."
  (:require
    [dao.stream :as stream]
    [yang.php.antlr.lower :as lower]
    [yang.php.antlr.prelude :as prelude]
    [yin.vm :as vm]
    [yin.vm.data :as data]
    [yin.vm.integer :as integer]
    [yin.vm.module :as module]
    [yin.vm.test-utils :as tu]))


(def integer-limits
  "The `integer` limits the PHP compositions here install."
  {::integer/max-bits 100000, ::integer/max-digits 4300})


(def data-limits {::data/max-items 1048576})


(defn host-registry
  "The registry with the cell, data and integer modules installed, admitted
   by the PHP profile."
  []
  (-> (module/empty-registry)
      module/register-cell-module
      (data/register-data-module data-limits)
      (prelude/register-integer-module integer-limits)
      prelude/admit))


(defn packet
  "A CST packet from a nested form: `[rule child ...]` for a rule (a keyword)
   and `[type text]` (type a string) for a token. Spans are zero-width: the
   lowering reads them only for diagnostics. Ids are preorder, as the parser
   numbers them, so generated names match a parsed packet's."
  [form]
  (let [out (volatile! [])]
    (letfn [(walk
              [f]
              (let [id (count @out)]
                (if (string? (first f))
                  (vswap! out conj {:id id, :kind :token, :type (first f),
                                    :text (second f), :span [0 0]})
                  (do (vswap! out conj nil)
                      (let [kids (mapv walk (rest f))]
                        (vswap! out assoc id {:id id, :kind :rule,
                                              :rule (name (first f)),
                                              :children kids, :span [0 0]}))))
                id))]
      (walk form))
    {:yang.cst/version 1,
     :yang.cst/unit [:hand 0],
     :yang.cst/grammar "hand",
     :yang.cst/outcome :yang.cst/ok,
     :yang.cst/root 0,
     :yang.cst/nodes @out,
     :yang.cst/complete true}))


(defn render
  "The text a unit prints (ASCII here: the carrier is the text), then, for an
   uncaught error, the fatal line (no path)."
  [{:php/keys [out uncaught]}]
  (str out
       (when uncaught
         (str "PHP Fatal error:  Uncaught " (:class uncaught) ": " (:message uncaught)))))


(defn lower-through-stage
  "`{:ast a}` or `{:diagnostics d}` for `pk` through the lowering stage."
  [pk]
  (let [cst-log (tu/new-memory-log)
        program (tu/new-memory-log)
        diagnostics (tu/new-memory-log)]
    (stream/append! cst-log pk)
    (lower/step-stage (lower/open-stage cst-log program diagnostics
                                        {:medium lower/program-medium}))
    (let [problems (tu/drain diagnostics)]
      (if (seq problems)
        {:diagnostics problems}
        (let [{:yin/keys [batch root]} (first (tu/drain program))]
          {:ast (nth batch root)})))))


(defn run-packet
  "The rendered output of packet `pk` on the AST walker, or its diagnostics."
  [pk]
  (let [{:keys [ast diagnostics]} (lower-through-stage pk)]
    (if diagnostics
      {:diagnostics diagnostics}
      (render (vm/value (vm/eval (tu/create-vm {:modules (host-registry)}) ast))))))

(ns yang.tails
  "Tail marks over the Universal AST, shared by every stage that builds or
   rewrites a tree. A tail call pushes no frame on the VMs that honour the
   mark, so a mark is only sound for the tree it was computed over: a stage
   that wraps a body must strip every mark and recompute them over the whole
   derived tree, or a wrapped body's old tail calls would skip the wrapper."
  (:require
    [yin.vm :as vm]))


(defn mark-tails
  "Set `:tail? true` on every application in tail position of a lambda body,
   through `if` branches. Definitions are never marked. Lowering relies on
   this for loops, which are self-applied lambdas: the recursive call and the
   sequencing lambda around it are both tail calls, so an iteration grows no
   continuation on the VMs that honour the mark."
  [node]
  (letfn [(walk
            [node tail?]
            (case (:type node)
              :lambda (assoc node :body (walk (:body node) true))
              :if (assoc node
                         :test (walk (:test node) false)
                         :consequent (walk (:consequent node) tail?)
                         :alternate (walk (:alternate node) tail?))
              :application
              (let [definition? (= 'yin/def (:name (:operator node)))
                    node (assoc node
                                :operator (walk (:operator node) false)
                                :operands (mapv #(walk % false)
                                                (:operands node)))]
                (if (and tail? (not definition?))
                  (assoc node :tail? true)
                  node))
              node))]
    (walk node false)))


(defn strip-tails
  "`node` with every `:tail?` mark removed, at every depth and under every
   node type the row grammar knows, so `mark-tails` starts from nothing."
  [node]
  (let [slots (get vm/semantic-bytecode-grammar (:type node))]
    (reduce (fn [n [field kind]]
              (case kind
                :node (update n field strip-tails)
                :nodes (update n field #(mapv strip-tails %))
                n))
            (dissoc node :tail?)
            slots)))


(defn remark-tails
  "Strip every tail mark and recompute them over the whole of `node`."
  [node]
  (mark-tails (strip-tails node)))

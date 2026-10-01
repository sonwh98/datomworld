(ns yang.antlr.packet
  "Reading a portable CST packet (docs/design/yang.antlr.md §5.5; the shape
   is documented on `yang.antlr.cst`). Portable and language-agnostic:
   child edges are data, so the tree is validated before anything walks
   it.")


(defn- malformed!
  [message data]
  (throw (ex-info message
                  (merge {:yang.python.antlr/diagnostic
                          :yang.python.antlr/malformed-cst}
                         data))))


(defn validate!
  "Return the packet when its nodes form one tree rooted at 0 with preorder
   ids equal to their index and every child referenced exactly once, after
   its parent; otherwise throw a qualified malformed-cst error."
  [packet]
  (let [nodes (:yang.cst/nodes packet)
        n (count nodes)]
    (when-not (and (vector? nodes) (pos? n) (= 0 (:yang.cst/root packet)))
      (malformed! "CST packet has no root" {}))
    (loop [i 0
           seen #{}]
      (if (< i n)
        (let [node (nth nodes i)
              kids (:children node)]
          (when-not (= i (:id node))
            (malformed! "CST node id is not its index" {:index i}))
          (when-not (contains? #{:rule :token} (:kind node))
            (malformed! "CST node has an unknown kind" {:id i}))
          (doseq [k kids]
            (when-not (and (integer? k) (< i k n) (not (contains? seen k)))
              (malformed! "CST child edge is not a tree edge"
                          {:parent i, :child k})))
          (recur (inc i) (into seen kids)))
        (when-not (= (dec n) (count seen))
          (malformed! "CST has nodes unreachable from the root"
                      {:reachable (count seen), :nodes n}))))
    packet))


(defn node
  [packet id]
  (nth (:yang.cst/nodes packet) id))


(defn root
  [packet]
  (node packet (:yang.cst/root packet)))


(defn children
  [packet n]
  (mapv #(node packet %) (:children n)))


(defn rule?
  ([n] (= :rule (:kind n)))
  ([n rule-name] (and (= :rule (:kind n)) (= rule-name (:rule n)))))


(defn token?
  ([n] (= :token (:kind n)))
  ([n text] (and (= :token (:kind n)) (= text (:text n)))))


(defn child-rules
  "The rule children of `n` named `rule-name`, in order."
  [packet n rule-name]
  (filterv #(rule? % rule-name) (children packet n)))


(defn has-token?
  [packet n text]
  (boolean (some #(token? % text) (children packet n))))

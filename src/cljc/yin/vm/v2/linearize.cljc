(ns yin.vm.v2.linearize
  "Lowering: Universal AST datoms (`:yin/*`) → linear executable datoms
   (`:yin.code/*`), per `docs/design/yin.vm.semantic.md` §5.

   Lowering is VM lineage and knows no surface syntax. It is a recursive
   descent in the walker's evaluation order (operator, then operands left to
   right), emitting the main sequence first and lambda bodies out of line
   after it. Labels are symbolic while emitting and are resolved to
   instruction refs in a second pass: the output carries refs, never pc
   integers.

   Lowering reads the AST datom index directly rather than reconstructing
   maps, because every instruction names the AST entity it came from in
   `:yin.code/source` and reconstructed maps have no entities."
  (:require [dao.datom :as datom]
            [yin.vm.v2 :as vm]))


(def unsupported
  "Node types outside both evaluators' supported corpus (§2.4), rejected at
   lowering with an error naming the node."
  #{:yin/macro-expand :vm/store-update})


(def ^:private label-attrs
  "Operand attributes whose emitted value is a label, resolved to the
   instruction entity at that label's pc."
  #{:yin.code/target :yin.code/body})


(defn- reject-unsupported!
  [type node]
  (when (contains? unsupported type)
    (throw (ex-info (str "Cannot lower unsupported node " type)
                    {:type type, :node node}))))


(def ^:private min-safe-id
  "Lowest integer exactly representable by every supported host (a JS
   number's safe range). Code tempids stay at or above it so the ids of one
   segment are distinct on CLJ, CLJS, and CLJD alike."
  -9007199254740991)


(defn- plain-data?
  "True when `x` is data a code datom may carry (§2.5): scalars and
   collections of them, never a host function or object. Metadata travels
   with the value, so it must be plain data too."
  [x]
  (or (nil? x)
      (and (cond (or (boolean? x) (number? x) (string? x) (keyword? x)
                     (symbol? x))
                 true
                 (map? x) (and (every? plain-data? (keys x))
                               (every? plain-data? (vals x)))
                 (coll? x) (every? plain-data? x)
                 :else false)
           (plain-data? (meta x)))))


(defn- reject-host-value!
  [source attr v]
  (when-not (plain-data? v)
    (throw (ex-info (str "Cannot lower a host value into " attr)
                    {:node source, :attr attr, :value v}))))


(defn- flatten-program
  "The §5.3 flattening from `root`. Returns `[code labels]`: `code` is a
   vector of `{:op :source :operands [[attr v] ..]}` in pc order, and `labels`
   maps each label to the pc it marks."
  [get-attr root]
  (let [code (atom [])
        labels (atom {})
        bodies (atom [])
        label-count (atom 0)
        fresh! #(swap! label-count inc)
        mark! (fn [l] (swap! labels assoc l (count @code)))
        emit! (fn [source op & operands]
                (let [operands (partition 2 operands)]
                  (doseq [[a v] operands
                          :when (not (contains? label-attrs a))]
                    (reject-host-value! source a v))
                  (swap! code conj
                         {:op op, :source source, :operands operands})))]
    (letfn
      [(lower-node
         [e]
         (let [type (get-attr e :yin/type)]
           (reject-unsupported! type e)
           (case type
             :literal (emit! e :const :yin.code/value (get-attr e :yin/value))
             :variable (emit! e :var :yin.code/name (get-attr e :yin/name))
             :lambda (let [l (fresh!)]
                       (swap! bodies conj [l e (get-attr e :yin/body)])
                       (emit! e :closure
                              :yin.code/params (get-attr e :yin/params)
                              :yin.code/body l))
             :application (let [operands (get-attr e :yin/operands)]
                            (lower-node (get-attr e :yin/operator))
                            (emit! e :push)
                            (doseq [o operands]
                              (lower-node o)
                              (emit! e :push))
                            (emit! e :call
                                   :yin.code/argc (count operands)
                                   :yin.code/tail? (boolean
                                                     (get-attr e :yin/tail?))))
             :if (let [else (fresh!)
                       end (fresh!)]
                   (lower-node (get-attr e :yin/test))
                   (emit! e :branch-false :yin.code/target else)
                   (lower-node (get-attr e :yin/consequent))
                   (emit! e :jump :yin.code/target end)
                   (mark! else)
                   (lower-node (get-attr e :yin/alternate))
                   (mark! end))
             :dao.stream.apply/call (let [operands (get-attr e :yin/operands)]
                                      (doseq [o operands]
                                        (lower-node o)
                                        (emit! e :push))
                                      (emit! e :ffi-call
                                             :yin.code/ffi-op (get-attr e :yin/op)
                                             :yin.code/argc (count operands)))
             :stream/make (emit! e :stream-make
                                 :yin.code/buffer (get-attr e :yin/buffer))
             :stream/put (do (lower-node (get-attr e :yin/target))
                             (emit! e :push)
                             (lower-node (get-attr e :yin/val-node))
                             (emit! e :stream-put))
             :stream/cursor (do (lower-node (get-attr e :yin/source))
                                (emit! e :stream-cursor))
             :stream/next (do (lower-node (get-attr e :yin/source))
                              (emit! e :stream-next))
             :stream/close (do (lower-node (get-attr e :yin/source))
                               (emit! e :stream-close))
             :vm/gensym (emit! e :gensym :yin.code/prefix (get-attr e :yin/prefix))
             :vm/store-get (emit! e :store-get :yin.code/key (get-attr e :yin/key))
             :vm/store-put (emit! e :store-put
                                  :yin.code/key (get-attr e :yin/key)
                                  :yin.code/value (get-attr e :yin/value))
             :vm/park (emit! e :park)
             :vm/current-continuation (emit! e :current-continuation)
             :vm/resume (do (lower-node (get-attr e :yin/val-node))
                            (emit! e :resume
                                   :yin.code/parked-id (get-attr e :yin/parked-id)))
             (throw (ex-info (str "Cannot lower node of type " type)
                             {:type type, :node e})))))]
      (lower-node root)
      (emit! root :halt)
      ;; A body may hold lambdas of its own, which append to `bodies` while
      ;; this loop runs.
      (loop [i 0]
        (when-let [[l lambda body] (get @bodies i)]
          (mark! l)
          (lower-node body)
          (emit! lambda :return)
          (recur (inc i))))
      [@code @labels])))


(defn lower
  "Lower one AST program, as `[e a v t m]` datoms, to one code segment.

   Options:
     :t        transaction id (default 0)
     :id-start the segment tempid; instruction pc n is `id-start - 1 - n`.
               Defaults to below every entity id in the input, so the code
               and its AST can travel in one batch without colliding.

   Throws naming the node on an unsupported, unknown, or missing node type,
   or on a host value (a function or object) in a data operand or its
   metadata. Throws on a supplied :t that is not an integer, and on an
   :id-start that is not a negative integer, whose range leaves the
   host-safe integers, or whose range collides with an input entity."
  ([datoms] (lower datoms {}))
  ([datoms opts]
   (let [datoms (vec datoms)
         {:keys [get-attr root-id error]} (vm/index-datoms datoms)
         _ (when error
             (throw (ex-info "Cannot lower a program with a dangling root" error)))
         _ (when (nil? root-id)
             (throw (ex-info "Cannot lower a program with no root" {})))
         [code labels] (flatten-program get-attr root-id)
         input-ids (set (map first datoms))
         ;; A supplied option is validated as given, even when falsey.
         seg (if (contains? opts :id-start)
               (:id-start opts)
               (dec (reduce min (- datom/first-user-id) input-ids)))
         _ (when-not (and (integer? seg) (neg? seg))
             (throw (ex-info "Cannot lower with an :id-start that is not a negative integer"
                             {:id-start seg})))
         ;; pcs allocate seg-1 .. seg-length, below seg and so all negative.
         ;; They are distinct on every host only while the whole range stays
         ;; within min-safe-id; the sum is exact because both terms are safe.
         _ (when (< seg (+ min-safe-id (count code)))
             (throw (ex-info "Cannot lower with code tempids below the host-safe integer range"
                             {:id-start seg, :length (count code),
                              :min-safe-id min-safe-id})))
         last-id (- seg (count code))
         collisions (sort (filter #(and (number? %) (<= last-id % seg)) input-ids))
         _ (when (seq collisions)
             (throw (ex-info "Cannot lower with code tempids colliding with input entities"
                             {:id-start seg, :last-id last-id, :collisions (vec collisions)})))
         pc->eid #(- seg 1 %)
         t (get opts :t 0)
         _ (when-not (integer? t)
             (throw (ex-info "Cannot lower with a :t that is not an integer" {:t t})))
         m datom/default-op]
     (into [[seg :yin.code/type :segment t m]
            [seg :yin.code/length (count code) t m]
            [seg :yin.code/derived-from root-id t m]]
           (mapcat (fn [pc {:keys [op source operands]}]
                     (let [e (pc->eid pc)]
                       (concat [[e :yin.code/segment seg t m]
                                [e :yin.code/pc pc t m]
                                [e :yin.code/op op t m]]
                               (map (fn [[a v]]
                                      [e a
                                       (if (contains? label-attrs a)
                                         (pc->eid (get labels v))
                                         v)
                                       t m])
                                    operands)
                               [[e :yin.code/source source t m]])))
                   (range)
                   code)))))


(defn- ast-children
  "Structural child nodes of an AST map. Literal values and other data
   operands are opaque: a literal map shaped like a node is not a node."
  [node]
  (case (:type node)
    :lambda [(:body node)]
    :application (cons (:operator node) (:operands node))
    :dao.stream.apply/call (:operands node)
    :if [(:test node) (:consequent node) (:alternate node)]
    :stream/put [(:target node) (:val node)]
    (:stream/cursor :stream/next :stream/close) [(:source node)]
    :vm/resume [(:val node)]
    nil))


(defn lower-ast
  "`lower` ∘ `yin.vm.v2/ast->datoms`. Unsupported nodes are rejected here
   first, because `ast->datoms` cannot represent them."
  ([ast] (lower-ast ast {}))
  ([ast opts]
   (doseq [node (tree-seq map? ast-children ast)]
     (reject-unsupported! (:type node) node))
   (lower (vm/ast->datoms ast (select-keys opts [:t])) opts)))


(defn ast-loader
  "Adapt a code loader `(fn [vm code-datoms])` into one for a program stream
   carrying AST datoms: §3.1's `(comp vm-load-program lower)` for a binary
   loader. The composition makes this choice; no evaluator learns which form
   travels."
  [load-program]
  (fn [vm datoms] (load-program vm (lower datoms))))

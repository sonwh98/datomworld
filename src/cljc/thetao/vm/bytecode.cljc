(ns thetao.vm.bytecode
  (:require
    [thetao.vm.common :as common]
    [thetao.vm.content-store :as store]))


(def continuation-dimension
  {:dim/name :thetao.vm/d_cont_bc
   :dim/arity 6
   :dim/slots [[:vm/program :hash :control]
               [:vm/ip :int :control]
               [:vm/env :hash :environment]
               [:vm/frame :hash :frame]
               [:vm/parent :hash :parent]
               [:cont/blocked-on :value :status]]
   :dim/encoding :canonical-edn
   :dim/projection-to :d3
   :dim/lift-from :d3})


(declare compile-node)


(defn- emit-node
  [nodes node]
  [(count nodes) (conj nodes node)])


(defn compile-node
  [nodes ast]
  (case (:type ast)
    :literal
    (emit-node nodes (common/->LiteralNode :literal (:value ast)))

    :variable
    (emit-node nodes (common/->VariableNode :variable (:name ast)))

    :lambda
    (let [[body-ref nodes*] (compile-node nodes (:body ast))]
      (emit-node nodes* (common/->LambdaNode :lambda (vec (:params ast)) body-ref)))

    :if
    (let [[test-ref nodes*] (compile-node nodes (:test ast))
          [then-ref nodes**] (compile-node nodes* (:consequent ast))
          [else-ref nodes***] (compile-node nodes** (:alternate ast))]
      (emit-node nodes*** (common/->IfNode :if test-ref then-ref else-ref)))

    :application
    (let [[op-ref nodes*] (compile-node nodes (:operator ast))
          [operand-refs nodes**]
          (reduce (fn [[refs acc] operand]
                    (let [[ref acc*] (compile-node acc operand)]
                      [(conj refs ref) acc*]))
                  [[] nodes*]
                  (:operands ast))]
      (emit-node nodes** (common/->ApplicationNode :application op-ref operand-refs (:tail? ast))))

    :stream/make
    (emit-node nodes (common/->StreamMakeNode :stream/make (:buffer ast)))

    :stream/cursor
    (let [[source-ref nodes*] (compile-node nodes (:source ast))]
      (emit-node nodes* (common/->StreamCursorNode :stream/cursor source-ref)))

    :stream/next
    (let [[source-ref nodes*] (compile-node nodes (:source ast))]
      (emit-node nodes* (common/->StreamNextNode :stream/next source-ref)))

    :stream/put
    (let [[target-ref nodes*] (compile-node nodes (:target ast))
          [value-ref nodes**] (compile-node nodes* (:val ast))]
      (emit-node nodes** (common/->StreamPutNode :stream/put target-ref value-ref)))

    :stream/close
    (let [[source-ref nodes*] (compile-node nodes (:source ast))]
      (emit-node nodes* (common/->StreamCloseNode :stream/close source-ref)))

    :vm/current-continuation
    (emit-node nodes (common/->CurrentContinuationNode :vm/current-continuation))

    :vm/park
    (emit-node nodes (common/->ParkNode :vm/park))

    :vm/resume
    (let [[cont-ref nodes*] (compile-node nodes (:continuation ast))
          [value-ref nodes**] (compile-node nodes* (:val ast))]
      (emit-node nodes** (common/->ResumeNode :vm/resume cont-ref value-ref)))

    :dao.stream.apply/call
    (let [[operand-refs nodes*]
          (reduce (fn [[refs acc] operand]
                    (let [[ref acc*] (compile-node acc operand)]
                      [(conj refs ref) acc*]))
                  [[] nodes]
                  (:operands ast))]
      (emit-node nodes* (common/->DaoCallNode :dao.stream.apply/call (:op ast) operand-refs)))

    (throw (ex-info "Unsupported Thetao AST for bytecode compiler"
                    {:ast ast}))))


(defn compile-program
  [ast]
  (let [[root nodes] (compile-node [] ast)]
    {:kind :bytecode
     :root root
     :nodes nodes
     :dimension continuation-dimension
     :hash (store/content-hash nodes)}))

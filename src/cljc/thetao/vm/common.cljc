(ns thetao.vm.common
  (:require
    [dao.stream :as ds]
    [thetao.vm.content-store :as store]
    [thetao.vm.events :as events]
    [thetao.vm.streams :as streams]))


(defrecord Primitive
  [name invoke])


(defn primitive
  [name f]
  (->Primitive name f))


(def default-primitives
  {'+ (primitive '+ +)
   '- (primitive '- -)
   '* (primitive '* *)
   '/ (primitive '/ /)
   '< (primitive '< <)
   '> (primitive '> >)
   '<= (primitive '<= <=)
   '>= (primitive '>= >=)
   '= (primitive '= =)
   'not (primitive 'not not)
   'nil? (primitive 'nil? nil?)
   'identity (primitive 'identity identity)
   'vector (primitive 'vector vector)
   'first (primitive 'first first)
   'second (primitive 'second second)})


(declare consume-local-id)
(declare blocked-ready?)
(declare pump-vm)
(declare pump-effect-responses)
(declare pump-stream-blocks)
(declare apply-outcome)


(defn create-base-vm
  [kind {:keys [env] :as _opts}]
  (let [runtime-streams (streams/open-runtime-streams)
        content-store (atom {})
        vm0 {:kind kind
             :status :idle
             :loaded-program nil
             :next-local-id 1025
             :next-t 1
             :streams runtime-streams
             :stream-cursors {:execution {:position 0}
                              :effect-response {:position 0}
                              :event-drain {:position 0}
                              :fact-drain {:position 0}}
             :streams-by-id {}
             :cursors-by-id {}
             :envs {}
             :continuations {}
             :blocked {}
             :halted-values []
             :result nil
             :content-store content-store
             :emitted-hashes {}
             :root-env-id nil
             :next-request-id 1}
        [vm1 env-id] (consume-local-id vm0)
        vm2 (assoc-in vm1 [:envs env-id]
                      {:bindings (merge default-primitives (or env {}))
                       :parent nil})]
    (assoc vm2 :root-env-id env-id)))


(defn alloc-local-id
  [vm]
  [vm (:next-local-id vm)])


(defn consume-local-id
  [vm]
  (let [id (:next-local-id vm)]
    [(update vm :next-local-id inc) id]))


(defn next-t
  [vm]
  [vm (:next-t vm)])


(defn consume-t
  [vm]
  (let [t (:next-t vm)]
    [(update vm :next-t inc) t]))


(defn consume-request-id
  [vm]
  (let [request-id (:next-request-id vm)]
    [(update vm :next-request-id inc) request-id]))


(defn execution-stream
  [vm]
  (get-in vm [:streams :execution]))


(defn event-stream
  [vm]
  (get-in vm [:streams :event]))


(defn fact-stream
  [vm]
  (get-in vm [:streams :fact]))


(defn effect-stream
  [vm]
  (get-in vm [:streams :effect]))


(defn effect-response-stream
  [vm]
  (get-in vm [:streams :effect-response]))


(defn content-store
  [vm]
  (:content-store vm))


(defn quiescent?
  [vm]
  (let [exec-result (ds/next (execution-stream vm)
                             (get-in vm [:stream-cursors :execution]))]
    (and (not (map? exec-result))
         (not-any? #(blocked-ready? vm %)
                   (vals (:blocked vm))))))


(defn blocked-ready?
  [vm {:keys [blocked-on]}]
  (case (:kind blocked-on)
    :stream/next
    (let [cursor (get-in vm [:cursors-by-id (:cursor-id blocked-on)])
          stream (get-in vm [:streams-by-id (:stream-id cursor)])
          result (ds/next stream {:position (:position cursor)})]
      (not= :blocked result))

    :stream/put
    (let [stream (get-in vm [:streams-by-id (:stream-id blocked-on)])]
      (= :ok (:result (ds/put! stream (:value blocked-on)))))

    :dao.stream.apply/call
    (boolean
      (first
        (filter (fn [[e a _v _t _m]]
                  (and (= e (:request-id blocked-on))
                       (= a :thetao/effect-response)))
                (streams/stream-values (effect-response-stream vm)))))

    false))


(defn resolve-node
  [program ref]
  (if (= :bytecode (:kind program))
    (nth (:nodes program) ref)
    ref))


(defn truthy?
  [v]
  (not (or (nil? v) (= false v))))


(defn env-record
  [vm env-id]
  (get (:envs vm) env-id))


(defn lookup-env
  [vm env-id sym]
  (loop [cursor env-id]
    (if-let [env (get (:envs vm) cursor)]
      (let [bindings (:bindings env)]
        (if (contains? bindings sym)
          (get bindings sym)
          (if-let [parent (:parent env)]
            (recur parent)
            (throw (ex-info "Unbound Thetao variable"
                            {:symbol sym})))))
      (throw (ex-info "Thetao environment not found" {:env-id cursor})))))


(defn extend-env
  [vm parent-id params args]
  (let [env-id (:next-local-id vm)
        vm* (update vm :next-local-id inc)]
    [(update vm* :envs assoc env-id
             {:bindings (zipmap params args)
              :parent parent-id})
     env-id]))


(defn make-stream-ref
  [stream-id]
  {:type :thetao/stream
   :id stream-id})


(defn make-cursor-ref
  [cursor-id]
  {:type :thetao/cursor
   :id cursor-id})


(defn make-continuation-ref
  [cont]
  {:type :thetao/continuation
   :local-id (:local-id cont)
   :hash (:hash cont)
   :representation (:representation cont)})


(defn stream-ref?
  [value]
  (and (map? value) (= :thetao/stream (:type value))))


(defn cursor-ref?
  [value]
  (and (map? value) (= :thetao/cursor (:type value))))


(defn continuation-ref?
  [value]
  (and (map? value) (= :thetao/continuation (:type value))))


(defrecord LiteralNode
  [type value])


(defrecord VariableNode
  [type name])


(defrecord LambdaNode
  [type params body])


(defrecord IfNode
  [type test consequent alternate])


(defrecord ApplicationNode
  [type operator operands tail?])


(defrecord StreamMakeNode
  [type buffer])


(defrecord StreamCursorNode
  [type source])


(defrecord StreamNextNode
  [type source])


(defrecord StreamPutNode
  [type target val])


(defrecord StreamCloseNode
  [type source])


(defrecord CurrentContinuationNode
  [type])


(defrecord ParkNode
  [type])


(defrecord ResumeNode
  [type continuation val])


(defrecord DaoCallNode
  [type op operands])


(defrecord Closure
  [program program-hash params body-ref env-id])


(defn closure-value
  [program params body-ref env-id]
  (->Closure program (:hash program) params body-ref env-id))


(defn primitive?
  [value]
  (instance? Primitive value))


(defn closure?
  [value]
  (instance? Closure value))


(declare canonical-value)
(declare materialize-continuation)


(defn canonical-env
  [vm env-id]
  (let [{:keys [bindings parent]} (env-record vm env-id)]
    {:bindings (into (sorted-map)
                     (map (fn [[k v]]
                            [k (canonical-value vm v)]))
                     bindings)
     :parent (when parent (store/store! (:content-store vm)
                                        (canonical-env vm parent)))}))


(defn canonical-frame
  [vm frame]
  (-> frame
      (dissoc :program)
      (update :fn #(when % (canonical-value vm %)))
      (update :stream-ref #(when % (canonical-value vm %)))
      (update :continuation #(when % (canonical-value vm %)))
      (update :evaluated #(when % (mapv (partial canonical-value vm) %)))))


(defn continuation-by-id
  [vm cont-id]
  (get-in vm [:continuations cont-id]))


(defn canonical-value
  [vm value]
  (cond
    (primitive? value)
    {:type :thetao/primitive
     :name (:name value)}

    (closure? value)
    {:type :thetao/closure
     :program-hash (:program-hash value)
     :params (:params value)
     :body-ref (:body-ref value)
     :env-hash (store/store! (:content-store vm)
                             (canonical-env vm (:env-id value)))}

    (continuation-ref? value)
    {:type :thetao/continuation
     :hash (:hash value)
     :representation (:representation value)}

    (cursor-ref? value)
    (let [cursor (get-in vm [:cursors-by-id (:id value)])]
      {:type :thetao/cursor
       :stream-id (:stream-id cursor)
       :position (:position cursor)})

    (stream-ref? value)
    value

    (and (map? value) (:program value))
    (-> value
        (dissoc :program)
        (assoc :program-hash (:program-hash value)))

    :else value))


(defn canonical-continuation
  [vm cont]
  {:representation (:representation cont)
   :program-hash (get-in cont [:program :hash])
   :mode (:mode cont)
   :control (:control cont)
   :env-hash (store/store! (:content-store vm)
                           (canonical-env vm (:env-id cont)))
   :frames (mapv (partial canonical-frame vm) (:frames cont))
   :parent-hash (some-> (:parent-id cont)
                        (continuation-by-id vm)
                        :hash)})


(defn materialize-continuation
  [vm cont]
  (if (:local-id cont)
    [vm cont]
    (let [[vm* cont-id] (consume-local-id vm)
          cont* (assoc cont :local-id cont-id)
          hash (store/store! (:content-store vm)
                             (canonical-continuation vm* cont*))
          cont** (assoc cont* :hash hash)
          vm** (assoc-in vm* [:continuations cont-id] cont**)]
      [vm** cont**])))


(defn emit-persistent-datom
  [vm stream-key datom]
  (store/store! (:content-store vm) datom)
  (ds/put! (get-in vm [:streams stream-key]) datom)
  vm)


(defn emit-event
  [vm event]
  (let [[vm* t] (consume-t vm)]
    (emit-persistent-datom vm* :event (events/event-datom t event))))


(defn ensure-hash-emitted
  [vm entity-id hash]
  (if (= hash (get-in vm [:emitted-hashes entity-id]))
    vm
    (let [[vm* t] (consume-t vm)
          vm** (assoc-in vm* [:emitted-hashes entity-id] hash)]
      (emit-persistent-datom vm** :event (events/hash-datom entity-id hash t)))))


(defn emit-fact
  [vm e a v]
  (let [[vm* t] (consume-t vm)]
    (emit-persistent-datom vm* :fact [e a v t 0])))


(defn emit-effect-request
  [vm request-id op args]
  (let [[vm* t] (consume-t vm)]
    (emit-persistent-datom vm* :effect
                           [request-id :thetao/effect-request
                            {:op op :args args}
                            t 0])))


(defn enqueue-continuation
  [vm cont event]
  (let [[vm* cont*] (materialize-continuation vm cont)
        vm** (ensure-hash-emitted vm* (:local-id cont*) (:hash cont*))]
    (ds/put! (execution-stream vm**) cont*)
    (-> vm**
        (emit-event event)
        (assoc :status :ready))))


(defn make-root-continuation
  [representation program env-id]
  {:representation representation
   :program program
   :mode :eval
   :control (:root program)
   :env-id env-id
   :frames []
   :parent-id nil})


(defn load-program
  [vm compile-fn ast]
  (assoc vm :loaded-program (compile-fn ast)))


(defn enqueue-root
  [vm]
  (if-let [program (:loaded-program vm)]
    (enqueue-continuation vm
                          (make-root-continuation (:kind vm)
                                                  program
                                                  (:root-env-id vm))
                          :continuation/enqueued)
    (throw (ex-info "Thetao VM has no loaded program" {}))))


(defn capture-template
  [cont]
  (-> cont
      (assoc :mode :return)
      (dissoc :value)))


(defn register-blocked
  [vm cont blocked-on]
  (let [[vm* template] (materialize-continuation vm (capture-template cont))
        cont-ref (make-continuation-ref template)]
    [(-> vm*
         (ensure-hash-emitted (:local-id template) (:hash template))
         (emit-event :continuation/blocked)
         (assoc-in [:blocked (:local-id template)]
                   {:continuation cont-ref
                    :blocked-on blocked-on
                    :result cont-ref})
         (assoc :status :blocked
                :result cont-ref))
     cont-ref]))


(defn resume-continuation
  [vm cont-ref value]
  (let [template (continuation-by-id vm (:local-id cont-ref))
        resumed (-> template
                    (assoc :mode :return
                           :value value)
                    (dissoc :local-id :hash))
        vm* (update vm :blocked dissoc (:local-id cont-ref))]
    (enqueue-continuation vm* resumed :continuation/resumed)))


(defn apply-function
  [vm f args frames]
  (cond
    (primitive? f)
    (let [invoke (:invoke f)
          c (count args)]
      {:kind :return
       :vm vm
       :value (case c
                0 (invoke)
                1 (invoke (nth args 0))
                2 (let [a (nth args 0) b (nth args 1)]
                    (invoke a b))
                3 (let [a (nth args 0) b (nth args 1) c (nth args 2)]
                    (invoke a b c))
                (apply invoke args))
       :frames frames})

    (closure? f)
    (let [[vm* env-id] (extend-env vm (:env-id f) (:params f) args)]
      {:kind :jump
       :vm vm*
       :program (:program f)
       :control (:body-ref f)
       :env-id env-id
       :frames frames})

    :else
    (throw (ex-info "Attempted to apply non-callable Thetao value"
                    {:value f :args args}))))


(defn boundary-successor
  [cont value]
  [(assoc cont :mode :return :value value)])


(declare reduce-continuation)


(defn step
  [vm]
  (let [vm* (pump-vm vm)
        cursor (get-in vm* [:stream-cursors :execution])
        result (ds/next (execution-stream vm*) cursor)]
    (if (map? result)
      (let [cont (:ok result)
            vm** (assoc-in vm* [:stream-cursors :execution] (:cursor result))
            vm*** (emit-event vm** :continuation/started)
            outcome (reduce-continuation vm*** cont)]
        (apply-outcome vm*** cont outcome))
      (cond
        (seq (:blocked vm*)) (assoc vm* :status :blocked)
        (seq (:halted-values vm*)) (assoc vm* :status :halted)
        :else (assoc vm* :status :idle)))))


(defn progress-stamp
  [vm]
  [(get-in vm [:stream-cursors :execution :position])
   (get-in vm [:stream-cursors :effect-response :position])
   (count (:blocked vm))
   (count (:halted-values vm))
   (count (event-stream vm))
   (count (fact-stream vm))
   (count (effect-stream vm))])


(defn run
  [vm]
  (loop [state vm
         last-stamp nil
         remaining 100000]
    (let [stamp (progress-stamp state)]
      (if (or (= stamp last-stamp) (zero? remaining))
        state
        (recur (step state) stamp (dec remaining))))))


(defn pump-vm
  [vm]
  (-> vm
      pump-effect-responses
      pump-stream-blocks))


(defn pump-effect-responses
  [vm]
  (loop [state vm]
    (let [cursor (get-in state [:stream-cursors :effect-response])
          result (ds/next (effect-response-stream state) cursor)]
      (if (map? result)
        (let [[request-id attr value _t _m] (:ok result)
              state* (assoc-in state [:stream-cursors :effect-response]
                               (:cursor result))
              blocked-entry (first (filter (fn [[_ {:keys [blocked-on]}]]
                                             (and (= :dao.stream.apply/call
                                                     (:kind blocked-on))
                                                  (= request-id
                                                     (:request-id blocked-on))))
                                           (:blocked state*)))]
          (if (and (= attr :thetao/effect-response) blocked-entry)
            (let [[_ {:keys [continuation]}] blocked-entry]
              (recur (resume-continuation state* continuation value)))
            (recur state*)))
        state))))


(defn pump-stream-blocks
  [vm]
  (reduce (fn [state [_ {:keys [continuation blocked-on]}]]
            (case (:kind blocked-on)
              :stream/next
              (let [cursor (get-in state [:cursors-by-id (:cursor-id blocked-on)])
                    stream (get-in state [:streams-by-id (:stream-id cursor)])
                    result (ds/next stream {:position (:position cursor)})]
                (cond
                  (map? result)
                  (let [state* (assoc-in state
                                         [:cursors-by-id (:cursor-id blocked-on)]
                                         {:stream-id (:stream-id cursor)
                                          :position (get-in result [:cursor :position])})]
                    (resume-continuation state* continuation (:ok result)))

                  (= :end result)
                  (resume-continuation state continuation nil)

                  :else state))

              :stream/put
              (let [stream (get-in state [:streams-by-id (:stream-id blocked-on)])
                    put-result (ds/put! stream (:value blocked-on))]
                (if (= :ok (:result put-result))
                  (resume-continuation state continuation (:value blocked-on))
                  state))

              state))
          vm
          (:blocked vm)))


(defn apply-outcome
  [vm current-cont outcome]
  (let [base-vm (or (:vm outcome) vm)]
    (case (:kind outcome)
      :halt
      (-> base-vm
          (emit-event :continuation/halted)
          (update :halted-values conj (:value outcome))
          (assoc :result (:value outcome)
                 :status :halted))

      :block
      (first (register-blocked base-vm
                               (:continuation outcome)
                               (:blocked-on outcome)))

      :enqueue
      (reduce (fn [state {:keys [continuation event]}]
                (enqueue-continuation state
                                      (assoc continuation :parent-id
                                             (:local-id current-cont))
                                      event))
              base-vm
              (:successors outcome))

      (throw (ex-info "Unknown Thetao outcome" {:outcome outcome})))))


(defn eval-node
  [vm program env-id frames node]
  (case (:type node)
    :literal
    {:kind :return
     :vm vm
     :value (:value node)
     :frames frames}

    :variable
    {:kind :return
     :vm vm
     :value (lookup-env vm env-id (:name node))
     :frames frames}

    :lambda
    {:kind :return
     :vm vm
     :value (closure-value program (:params node) (:body node) env-id)
     :frames frames}

    :if
    {:kind :jump
     :vm vm
     :program program
     :control (:test node)
     :env-id env-id
     :frames (conj frames {:frame :if
                           :program program
                           :env-id env-id
                           :then (:consequent node)
                           :else (:alternate node)})}

    :application
    {:kind :jump
     :vm vm
     :program program
     :control (:operator node)
     :env-id env-id
     :frames (conj frames {:frame :apply-op
                           :program program
                           :env-id env-id
                           :args (:operands node)})}

    :stream/make
    (let [stream-id (:next-local-id vm)
          vm* (update vm :next-local-id inc)
          stream (ds/open! {:type :ringbuffer
                            :capacity (or (:buffer node) 1024)})
          vm** (update vm* :streams-by-id assoc stream-id stream)
          vm*** (emit-fact vm** stream-id :thetao/stream-status :open)]
      {:kind :return
       :vm vm***
       :value (make-stream-ref stream-id)
       :frames frames})

    :stream/cursor
    {:kind :jump
     :vm vm
     :program program
     :control (:source node)
     :env-id env-id
     :frames (conj frames {:frame :stream/cursor
                           :program program})}

    :stream/next
    {:kind :jump
     :vm vm
     :program program
     :control (:source node)
     :env-id env-id
     :frames (conj frames {:frame :stream/next
                           :program program})}

    :stream/put
    {:kind :jump
     :vm vm
     :program program
     :control (:target node)
     :env-id env-id
     :frames (conj frames {:frame :stream/put-target
                           :program program
                           :env-id env-id
                           :val-node (:val node)})}

    :stream/close
    {:kind :jump
     :vm vm
     :program program
     :control (:source node)
     :env-id env-id
     :frames (conj frames {:frame :stream/close
                           :program program})}

    :vm/current-continuation
    {:kind :capture-current
     :vm vm}

    :vm/park
    {:kind :park
     :vm vm}

    :vm/resume
    {:kind :jump
     :vm vm
     :program program
     :control (:continuation node)
     :env-id env-id
     :frames (conj frames {:frame :vm/resume-cont
                           :program program
                           :env-id env-id
                           :val-node (:val node)})}

    :dao.stream.apply/call
    (if (seq (:operands node))
      {:kind :jump
       :vm vm
       :program program
       :control (first (:operands node))
       :env-id env-id
       :frames (conj frames {:frame :dao-call
                             :program program
                             :env-id env-id
                             :op (:op node)
                             :evaluated []
                             :remaining (vec (rest (:operands node)))})}
      {:kind :dao-call
       :vm vm
       :op (:op node)
       :args []
       :frames frames})

    (throw (ex-info "Unsupported Thetao node" {:node node}))))


(defn continue-return
  [vm _program env-id frames value]
  (if (empty? frames)
    {:kind :halt
     :value value}
    (let [frame (peek frames)
          frames* (pop frames)]
      (case (:frame frame)
        :if
        {:kind :jump
         :vm vm
         :program (:program frame)
         :control (if (truthy? value) (:then frame) (:else frame))
         :env-id (:env-id frame)
         :frames frames*}

        :apply-op
        (if-let [args (seq (:args frame))]
          {:kind :jump
           :vm vm
           :program (:program frame)
           :control (first args)
           :env-id (:env-id frame)
           :frames (conj frames* {:frame :apply-args
                                  :program (:program frame)
                                  :env-id (:env-id frame)
                                  :fn value
                                  :evaluated []
                                  :remaining (next args)})}
          (apply-function vm value [] frames*))

        :apply-args
        (let [evaluated (conj (:evaluated frame) value)
              remaining (:remaining frame)]
          (if remaining
            {:kind :jump
             :vm vm
             :program (:program frame)
             :control (first remaining)
             :env-id (:env-id frame)
             :frames (conj frames* (assoc frame
                                          :evaluated evaluated
                                          :remaining (next remaining)))}
            (apply-function vm (:fn frame) evaluated frames*)))

        :stream/cursor
        (do
          (when-not (stream-ref? value)
            (throw (ex-info "stream/cursor expects a stream reference"
                            {:value value})))
          (let [[vm* cursor-id] (consume-local-id vm)]
            {:kind :return
             :vm (assoc-in vm* [:cursors-by-id cursor-id]
                           {:stream-id (:id value)
                            :position 0})
             :value (make-cursor-ref cursor-id)
             :frames frames*}))

        :stream/next
        (do
          (when-not (cursor-ref? value)
            (throw (ex-info "stream/next expects a cursor reference"
                            {:value value})))
          (let [cursor (get-in vm [:cursors-by-id (:id value)])
                stream (get-in vm [:streams-by-id (:stream-id cursor)])
                result (ds/next stream {:position (:position cursor)})]
            (cond
              (map? result)
              {:kind :return
               :vm (assoc-in vm [:cursors-by-id (:id value)]
                             {:stream-id (:stream-id cursor)
                              :position (get-in result [:cursor :position])})
               :value (:ok result)
               :frames frames*}

              (= :end result)
              {:kind :return
               :vm vm
               :value nil
               :frames frames*}

              :else
              {:kind :block
               :vm vm
               :continuation {:representation (:kind vm)
                              :program (:program frame)
                              :mode :return
                              :env-id env-id
                              :frames frames*}
               :blocked-on {:kind :stream/next
                            :cursor-id (:id value)
                            :stream-id (:stream-id cursor)}})))

        :stream/put-target
        {:kind :jump
         :vm vm
         :program (:program frame)
         :control (:val-node frame)
         :env-id (:env-id frame)
         :frames (conj frames* {:frame :stream/put-value
                                :program (:program frame)
                                :stream-ref value})}

        :stream/put-value
        (do
          (when-not (stream-ref? (:stream-ref frame))
            (throw (ex-info "stream/put expects a stream reference"
                            {:value (:stream-ref frame)})))
          (let [stream (get-in vm [:streams-by-id (:id (:stream-ref frame))])
                put-result (ds/put! stream value)
                vm* (emit-fact vm
                               (:id (:stream-ref frame))
                               :thetao/stream-put
                               value)]
            (if (= :ok (:result put-result))
              {:kind :return
               :vm vm*
               :value value
               :frames frames*}
              {:kind :block
               :vm vm*
               :continuation {:representation (:kind vm)
                              :program (:program frame)
                              :mode :return
                              :env-id env-id
                              :frames frames*}
               :blocked-on {:kind :stream/put
                            :stream-id (:id (:stream-ref frame))
                            :value value}})))

        :stream/close
        (do
          (when-not (stream-ref? value)
            (throw (ex-info "stream/close expects a stream reference"
                            {:value value})))
          (let [stream (get-in vm [:streams-by-id (:id value)])
                _ (ds/close! stream)
                vm* (emit-fact vm (:id value) :thetao/stream-status :closed)]
            {:kind :return
             :vm vm*
             :value nil
             :frames frames*}))

        :vm/resume-cont
        {:kind :jump
         :vm vm
         :program (:program frame)
         :control (:val-node frame)
         :env-id (:env-id frame)
         :frames (conj frames* {:frame :vm/resume-value
                                :program (:program frame)
                                :continuation value})}

        :vm/resume-value
        (do
          (when-not (continuation-ref? (:continuation frame))
            (throw (ex-info "vm/resume expects a continuation reference"
                            {:value (:continuation frame)})))
          {:kind :enqueue
           :vm vm
           :successors [{:continuation {:representation (:kind vm)
                                        :program (:program frame)
                                        :mode :return
                                        :value value
                                        :env-id env-id
                                        :frames frames*}
                         :event :continuation/enqueued}
                        {:continuation (-> (continuation-by-id vm
                                                               (:local-id
                                                                 (:continuation frame)))
                                           (assoc :mode :return
                                                  :value value)
                                           (dissoc :local-id :hash))
                         :event :continuation/resumed}]})

        :dao-call
        (let [evaluated (conj (:evaluated frame) value)]
          (if (seq (:remaining frame))
            {:kind :jump
             :vm vm
             :program (:program frame)
             :control (first (:remaining frame))
             :env-id (:env-id frame)
             :frames (conj frames* (assoc frame
                                          :evaluated evaluated
                                          :remaining (vec (rest (:remaining frame)))))}
            {:kind :dao-call
             :vm vm
             :op (:op frame)
             :args evaluated
             :frames frames*}))

        (throw (ex-info "Unknown Thetao frame" {:frame frame}))))))


(defn reduce-continuation
  [vm cont]
  (loop [state vm
         program (:program cont)
         env-id (:env-id cont)
         frames (vec (:frames cont))
         mode (:mode cont)
         control (:control cont)
         value (:value cont)]
    (case mode
      :eval
      (let [node (resolve-node program control)
            outcome (eval-node state program env-id frames node)]
        (case (:kind outcome)
          :return (recur (:vm outcome)
                         program
                         env-id
                         (:frames outcome)
                         :return
                         control
                         (:value outcome))

          :jump (recur (:vm outcome)
                       (:program outcome)
                       (:env-id outcome)
                       (:frames outcome)
                       :eval
                       (:control outcome)
                       nil)

          :capture-current
          (let [[state* captured] (materialize-continuation
                                    state
                                    {:representation (:kind state)
                                     :program program
                                     :mode :return
                                     :env-id env-id
                                     :frames frames})
                state** (ensure-hash-emitted state*
                                             (:local-id captured)
                                             (:hash captured))
                captured-ref (make-continuation-ref captured)]
            {:kind :enqueue
             :vm state**
             :successors [{:continuation {:representation (:kind state)
                                          :program program
                                          :mode :return
                                          :value captured-ref
                                          :env-id env-id
                                          :frames frames}
                           :event :continuation/enqueued}]})

          :park
          {:kind :block
           :vm state
           :continuation {:representation (:kind state)
                          :program program
                          :mode :return
                          :env-id env-id
                          :frames frames}
           :blocked-on {:kind :park}}

          :dao-call
          (let [[state* request-id] (consume-request-id state)
                state** (-> state*
                            (emit-effect-request request-id (:op outcome)
                                                 (:args outcome))
                            (emit-event :effect/requested))]
            {:kind :block
             :vm state**
             :continuation {:representation (:kind state)
                            :program program
                            :mode :return
                            :env-id env-id
                            :frames (:frames outcome)}
             :blocked-on {:kind :dao.stream.apply/call
                          :request-id request-id}})

          outcome))

      :return
      (let [outcome (continue-return state program env-id frames value)]
        (case (:kind outcome)
          :return (recur (:vm outcome)
                         program
                         env-id
                         (:frames outcome)
                         :return
                         control
                         (:value outcome))

          :jump (recur (:vm outcome)
                       (:program outcome)
                       (:env-id outcome)
                       (:frames outcome)
                       :eval
                       (:control outcome)
                       nil)

          :dao-call
          (let [[state* request-id] (consume-request-id (:vm outcome))
                state** (-> state*
                            (emit-effect-request request-id (:op outcome)
                                                 (:args outcome))
                            (emit-event :effect/requested))]
            {:kind :block
             :vm state**
             :continuation {:representation (:kind state)
                            :program program
                            :mode :return
                            :env-id env-id
                            :frames (:frames outcome)}
             :blocked-on {:kind :dao.stream.apply/call
                          :request-id request-id}})

          outcome)))))


(defn drain-events
  [vm]
  (streams/stream-values (event-stream vm)))

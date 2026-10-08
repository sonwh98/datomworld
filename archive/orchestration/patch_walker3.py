p = 'src/cljc/yin/vm/ucf/handoff.cljc'
s = open(p).read()


def rep(old, new, tag):
    global s
    if old not in s:
        if new in s:
            print("already", tag)
            return
        raise Exception("missing " + tag)
    s = s.replace(old, new, 1)
    print("applied", tag)


rep('''      (let [ctx {:code code :layout (:yin.k/layout body)}]''',
    '''      (let [ctx {:code code
                 :layout (:yin.k/layout body)
                 :parked (:yin.k/parked body)}]''', "ctx")

# walker helpers before validate-body-v2
rep('''(defn- validate-body-v2
  "The version-2 body grammar both ends run''', '''(defn- kw-chain
  [k]
  (take-while some? (iterate :yin.k/next k)))


(defn- body-registers
  "Every register map a body carries: its waits', its parked records'
   and those of the continuation values reachable in its roots."
  [body]
  (concat (map :yin.k/registers (:yin.k/frames body))
          (vals (:yin.k/parked body))
          (keep :yin.k/registers
                (mapcat #(tagged-of :yin.k/frame %) (reachable-values body)))))


(defn- walker-roots
  "Every row id the walker body names as a root: its frames' nodes and
   its closures' lambda rows."
  [body]
  (into #{}
        (concat
          (keep :yin.k/node (mapcat #(kw-chain (:yin.k/k %))
                                    (body-registers body)))
          (map :yin.k/segment
               (mapcat #(tagged-of :yin.k/closure %)
                       (reachable-values body))))))


(defn- walker-closure!
  "The set of row ids reachable from `roots` through the carried `code`;
   a reference no carried row answers is undecodable."
  [code roots]
  (loop [work (vec roots), seen #{}]
    (if-some [id (peek work)]
      (let [work (pop work)]
        (if (contains? seen id)
          (recur work seen)
          (let [row (get code id)]
            (when-not (vector? row)
              (undecodable! {:yin.k/segment id :yin.k/path [:yin.k/code]
                             :yin.k/kind :missing-row}))
            (recur (into work (row-children row)) (conj seen id)))))
      seen)))


(defn- validate-walker-rows!
  "Every row of the walker body validates under the v3 grammar from each
   of its roots: arity, slot kinds, saturation, resolution, acyclicity
   and Rule R."
  [code roots]
  (doseq [root (sort-by str roots)]
    (let [closure (walker-closure! code [root])]
      (when-some [defect (vm/validate-rows
                           {:root root
                            :rows (select-keys code closure)})]
        (undecodable! {:yin.k/segment root :yin.k/defect defect
                       :yin.k/path [:yin.k/code root]})))))


(defn- walker-bookkeeping!
  "A parked record whose continuation is the FFI response wrapper is
   correlation bookkeeping: allowed only when a live FFI pending of this
   task names its id, sharing its next and environment, and never the
   active parked record."
  [body]
  (let [justified
        (into {}
              (keep (fn [frame]
                      (let [pending (:yin.k/pending frame)]
                        (when (contains? #{:ffi :ffi-request}
                                         (:yin.k/reason pending))
                          [(:yin.k/call-id pending)
                           (:yin.k/registers frame)]))))
              (:yin.k/frames body))]
    (doseq [[pid r] (:yin.k/parked body)
            :let [k (:yin.k/k r)]
            :when (= :dao.stream.apply/eval-call (:yin.k/type k))]
      (let [frame-r (get justified pid)]
        (when (or (nil? frame-r)
                  (not= (:yin.k/next k) (:yin.k/next (:yin.k/k frame-r)))
                  (not= (:yin.k/env r) (:yin.k/env frame-r)))
          (undecodable! {:yin.k/parked-id pid :yin.k/kind :ffi-bookkeeping
                         :yin.k/path [:yin.k/parked pid]}))
        (when (and (= :parked (:yin.k/kind body))
                   (= pid (:yin.k/parked-id body)))
          (undecodable! {:yin.k/parked-id pid :yin.k/kind :ffi-bookkeeping
                         :yin.k/path [:yin.k/parked-id]}))))))


(defn- validate-body-v2
  "The version-2 body grammar both ends run''', "walker-helpers")

rep('''      (let [named (into (referenced-segments-v2 body)
                        (:yin.k/layout body))
            carried (set (keys code))]''', '''      (when (= :walker engine)
        (walker-bookkeeping! body))
      (let [named (if (= :walker engine)
                    (let [roots (walker-roots body)
                          closure (walker-closure! code roots)]
                      (validate-walker-rows! code roots)
                      closure)
                    (into (referenced-segments-v2 body)
                          (:yin.k/layout body)))
            carried (set (keys code))]''', "named")

# ---------------------------------------------------------------- lower
rep('''(defn- decode-registers-v2
  "One version-2 register map back in the receiver's native coordinates:''', '''(defn- decode-kw
  "The native walker continuation chain a wire frame chain denotes:
   addressed nodes are the receiver's decoded rows, runtime fields come
   back as values, and an absent environment stays absent."
  [node-of decode kw]
  (when (some? kw)
    (let [t (:yin.k/type kw)
          base (cond-> {:type t
                        :next (decode-kw node-of decode (:yin.k/next kw))}
                 (contains? kw :yin.k/env)
                 (assoc :env (decode-env-v2 decode (:yin.k/env kw))))
          node (node-of (:yin.k/node kw))]
      (case t
        (:eval-operator :eval-test :eval-stream-put-target
         :eval-stream-cursor-source :eval-stream-next-cursor
         :eval-stream-close-source)
        (assoc base :frame node)

        :eval-operand
        (assoc base :frame (assoc node
                                  :operator-evaluated? true
                                  :fn (decode (:yin.k/function kw))
                                  :evaluated (mapv decode
                                                   (:yin.k/evaluated kw))))

        :dao.stream.apply/eval-operand
        (assoc base :frame {:op (:op node)
                            :operands (:operands node)
                            :evaluated (mapv decode (:yin.k/evaluated kw))})

        :eval-stream-put-val
        (assoc base
               :frame node
               :stream-ref (decode (:yin.k/stream-ref kw)))

        :dao.stream.apply/request-sent
        (assoc base :parked-id (:yin.k/parked-id kw) :op (:yin.k/op kw))

        :dao.stream.apply/eval-call
        (cond-> base
          (contains? kw :yin.k/call-id) (assoc :call-id (:yin.k/call-id kw)))

        :eval-define (assoc base :name (:yin.k/name kw))
        :eval-resume-val (assoc base :parked-id (:yin.k/parked-id kw))))))


(defn- decode-registers-v2
  "One version-2 register map back in the receiver's native coordinates:''', "decode-kw")

rep('''  [engine decode {:keys [aliases code]} r]
  (case engine
    :stack''', '''  [engine decode {:keys [aliases code node-of]} r]
  (case engine
    :walker
    {:env (decode-env-v2 decode (:yin.k/env r))
     :k (decode-kw node-of decode (:yin.k/k r))}
    :stack''', "decode-walker")

rep('''                           (partial decode-registers-v2 engine decode
                                    {:aliases aliases
                                     :code (:yin.k/code body)})''', '''                           (partial decode-registers-v2 engine decode
                                    {:aliases aliases
                                     :code (:yin.k/code body)
                                     :node-of (partial walker/row-node recv')})''', "ctx-node-of")

open(p, 'w').write(s)

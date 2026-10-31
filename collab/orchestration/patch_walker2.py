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


# -------------------------------------------------------------- export wiring
rep('''             walked (if (pc-profile? engine)
                      (census-v2 engine vm)''', '''             walked (if (and v2? (not= :semantic engine))
                      (census-v2 engine vm)''', "walked")
rep('''                                   (when-not (pc-profile? engine)
                                     (safepoint-kinds-at vm (:segment rec)
                                                         (:pc rec)))''', '''                                   (when-not (and v2? (not= :semantic engine))
                                     (safepoint-kinds-at vm (:segment rec)
                                                         (:pc rec)))''', "parked-loop")
rep('''               segments (into (:segments @found) (map first) layout)
               images (into {} layout)''', '''               walker-code (when (= :walker engine)
                             (walker-code-v2 vm found))
               segments (if walker-code
                          (set (keys walker-code))
                          (into (:segments @found) (map first) layout))
               images (into {} layout)''', "segments")
rep('''               code (into {}
                          (map (fn [a]
                                 (if-some [v (fetch a)]
                                   [a v]
                                   (refuse! :yin.k/unsatisfied
                                            {:yin.k/segment a}))))
                          (sort-by str segments))''', '''               code (or walker-code
                        (into {}
                              (map (fn [a]
                                     (if-some [v (fetch a)]
                                       [a v]
                                       (refuse! :yin.k/unsatisfied
                                                {:yin.k/segment a}))))
                              (sort-by str segments)))''', "code")

# lift-frame! kinds for walker
rep('''  (let [engine (:engine (:v2 @found))
        pc? (pc-profile? engine)
        kinds (when-not pc?
                (safepoint-kinds-at vm (:segment entry) (:pc entry)))''', '''  (let [engine (:engine (:v2 @found))
        pc? (pc-profile? engine)
        walker? (= :walker engine)
        kinds (when-not (or pc? walker?)
                (safepoint-kinds-at vm (:segment entry) (:pc entry)))''', "kinds")
rep('''    (when-not (if pc?
                (contains? (get site-reasons (site-op engine entry)) wire)
                (some #(contains? (get wire-of-kind %) wire) kinds))''', '''    (when-not (cond
                walker? true
                pc? (contains? (get site-reasons (site-op engine entry)) wire)
                :else (some #(contains? (get wire-of-kind %) wire) kinds))''', "kinds-check")

# ---------------------------------------------------------------- validation
rep('''(defn- validate-registers-v2
  "One register map under the body's profile''', '''(def ^:private kw-node-tags
  "The row tag each node-bearing walker frame type addresses."
  {:eval-operator :application
   :eval-operand :application
   :eval-test :if
   :dao.stream.apply/eval-operand :dao.stream.apply/call
   :eval-stream-put-target :stream/put
   :eval-stream-put-val :stream/put
   :eval-stream-cursor-source :stream/cursor
   :eval-stream-next-cursor :stream/next
   :eval-stream-close-source :stream/close})


(def ^:private kw-fields
  "The closed arm fields of each walker frame type beyond `:yin.k/type`,
   `:yin.k/next` and the optional `:yin.k/env`."
  {:eval-operator #{:yin.k/node}
   :eval-operand #{:yin.k/node :yin.k/function :yin.k/evaluated}
   :eval-test #{:yin.k/node}
   :dao.stream.apply/eval-operand #{:yin.k/node :yin.k/evaluated}
   :dao.stream.apply/request-sent #{:yin.k/parked-id :yin.k/op}
   :dao.stream.apply/eval-call #{}
   :eval-stream-put-target #{:yin.k/node}
   :eval-stream-put-val #{:yin.k/node :yin.k/stream-ref}
   :eval-stream-cursor-source #{:yin.k/node}
   :eval-stream-next-cursor #{:yin.k/node}
   :eval-stream-close-source #{:yin.k/node}
   :eval-define #{:yin.k/name}
   :eval-resume-val #{:yin.k/parked-id}})


(defn- row-definition?
  "True when application row `row` is a Rule R definition."
  [code row]
  (let [op (get code (nth row 2))]
    (boolean (and op (= :variable (nth op 1))
                  (= vm/definition-operator (nth op 2))))))


(defn- validate-kw!
  "The closed walker frame chain `kw` (nil ends it), outermost last:
   every frame's type and fields, each addressed node a carried row of
   the right tag, applications that are not Rule R definitions, and
   partial evaluations shorter than their operand lists."
  [{:keys [code parked]} kw path]
  (loop [k kw, p path]
    (when (some? k)
      (let [t (:yin.k/type k)]
        (when-not (contains? kw-fields t)
          (undecodable! {:yin.k/path (conj p :yin.k/type)
                         :yin.k/kind :frame-type}))
        (closed! k (into #{:yin.k/type :yin.k/next} (get kw-fields t))
                 #{:yin.k/env} p)
        (when (contains? k :yin.k/env)
          (validate-env-v2 (:yin.k/env k) (conj p :yin.k/env)))
        (when-some [tag (get kw-node-tags t)]
          (let [row (get code (:yin.k/node k))]
            (when-not (and (vector? row) (= tag (nth row 1)))
              (undecodable! {:yin.k/path (conj p :yin.k/node)
                             :yin.k/kind :frame-node}))
            (when (and (= :application tag) (row-definition? code row))
              (undecodable! {:yin.k/path (conj p :yin.k/node)
                             :yin.k/kind :definition-frame}))
            (when (contains? #{:eval-operand
                               :dao.stream.apply/eval-operand} t)
              (let [ev (:yin.k/evaluated k)]
                (when-not (and (vector? ev) (< (count ev) (count (nth row 3))))
                  (undecodable! {:yin.k/path (conj p :yin.k/evaluated)
                                 :yin.k/kind :evaluated}))))))
        (case t
          :eval-stream-put-val
          (when-not (marker? (:yin.k/stream-ref k))
            (undecodable! {:yin.k/path (conj p :yin.k/stream-ref)}))
          :eval-define
          (when-not (and (symbol? (:yin.k/name k))
                         (not (vm/reserved-name? (:yin.k/name k))))
            (undecodable! {:yin.k/path (conj p :yin.k/name)}))
          :eval-resume-val
          (when-not (contains? parked (:yin.k/parked-id k))
            (undecodable! {:yin.k/path (conj p :yin.k/parked-id)}))
          :dao.stream.apply/request-sent
          (when-not (keyword? (:yin.k/op k))
            (undecodable! {:yin.k/path (conj p :yin.k/op)}))
          nil)
        (recur (:yin.k/next k) (conj p :yin.k/next))))))


(defn- validate-registers-v2
  "One register map under the body's profile''', "validate-kw")

rep('''    (undecodable! {:yin.k/kind :unsupported-profile :yin.k/path path})))


(defn- check-frame-pc-v2''', '''    :walker
    (do (closed! r #{:yin.k/env :yin.k/k} #{} path)
        (validate-env-v2 (:yin.k/env r) (conj path :yin.k/env))
        (validate-kw! ctx (:yin.k/k r) (conj path :yin.k/k)))

    (undecodable! {:yin.k/kind :unsupported-profile :yin.k/path path})))


(defn- check-frame-pc-v2''', "validate-registers-walker")

rep('''    (:stack :register)
    (let [composed (compose-v2 engine (mapv code (:yin.k/layout registers)))''', '''    :walker
    (let [top (:yin.k/k registers)
          t (:yin.k/type top)
          reason (:yin.k/reason pending)
          bad! (fn []
                 (undecodable! {:yin.k/kind :ffi-correlation
                                :yin.k/reason reason
                                :yin.k/path path}))]
      (case reason
        :ffi (when-not (and (= :dao.stream.apply/eval-call t)
                            (= (:yin.k/call-id pending) (:yin.k/call-id top)))
               (bad!))
        :ffi-request (when-not (and (= :dao.stream.apply/request-sent t)
                                    (= (:yin.k/call-id pending)
                                       (:yin.k/parked-id top))
                                    (= (:yin.k/request-op pending)
                                       (:yin.k/op top)))
                       (bad!))
        (when (contains? #{:dao.stream.apply/eval-call
                           :dao.stream.apply/request-sent}
                         t)
          (bad!))))
    (:stack :register)
    (let [composed (compose-v2 engine (mapv code (:yin.k/layout registers)))''', "check-frame-walker")

rep('''      (undecodable! {:yin.k/kind :unsupported-profile}))))


(defn- empty-image?''', '''      :walker
      (when-not (and (vector? v) (< 1 (count v)) (= a (first v))
                     (= a (jing/segment-key (subvec v 1))))
        (refuse! :yin.k/hash-mismatch {:yin.k/segment a}))
      (undecodable! {:yin.k/kind :unsupported-profile}))))


(defn- empty-image?''', "code-walker")

rep('''        (undecodable! {:yin.k/kind :unsupported-profile})))))


(defn- parked-site!''', '''        :walker
        (let [row v]
          (when-not (and (= :named (:yin.k/binding m))
                         (= :yin.ast/code (:yin.k/format m))
                         (nil? (:yin.k/entry m))
                         (vector? row) (= :lambda (nth row 1))
                         (= (:yin.k/params m) (nth row 2)))
            (undecodable! {:yin.k/segment a :yin.k/kind :closure-code})))
        (undecodable! {:yin.k/kind :unsupported-profile})))))


(defn- parked-site!''', "closure-walker")

open(p, 'w').write(s)

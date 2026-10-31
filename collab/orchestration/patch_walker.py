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


# ---------------------------------------------------------------- requires
rep("            [yin.vm.completion :as completion]",
    "            [yin.vm.ast-walker :as walker]\n            [yin.vm.completion :as completion]", "require")

# ------------------------------------------------------ observed-wire/engine
rep('''(defn- observed-wire
  "The wire variant the observed entry itself carries, from its shape
   alone: a `:next` wait with a call id is a sent FFI response reader;
   a `:put` wait holding its request is a retained call.  nil when the
   entry is no variant at all."
  [entry]
  (case (:reason entry)
    :next (if (:call-id entry) :ffi :next)
    :put (if (:request-sent entry) :ffi-request :put)''',
    '''(defn- walker-ffi-frame
  "The FFI wrapper frame the walker entry `entry` rides on top of, or
   nil: a response reader's `eval-call` carrying its call id, or a
   retained request's `request-sent`."
  [entry]
  (let [k (:k entry)]
    (case (:type k)
      :dao.stream.apply/eval-call (when (contains? k :call-id) k)
      :dao.stream.apply/request-sent k
      nil)))


(defn- entry-call-id
  "The FFI call id of `entry`: its own under the positional and
   semantic profiles, the wrapper frame's under the walker."
  [engine entry]
  (if (= :walker engine)
    (let [k (walker-ffi-frame entry)]
      (or (:call-id k) (:parked-id k)))
    (:call-id entry)))


(defn- observed-wire
  "The wire variant the observed entry itself carries, from its shape
   alone: a `:next` wait with a call id is a sent FFI response reader;
   a `:put` wait holding its request is a retained call.  The walker
   carries that identity in its wrapper frame.  nil when the entry is no
   variant at all."
  ([entry] (observed-wire entry nil))
  ([entry engine]
   (if (= :walker engine)
     (case (:reason entry)
       :next (if (= :dao.stream.apply/eval-call (:type (walker-ffi-frame entry)))
               :ffi :next)
       :put (if (= :dao.stream.apply/request-sent
                   (:type (walker-ffi-frame entry)))
              :ffi-request :put)
       (observed-wire entry nil))
     (observed-wire* entry))))


(defn- observed-wire*
  [entry]
  (case (:reason entry)
    :next (if (:call-id entry) :ffi :next)
    :put (if (:request-sent entry) :ffi-request :put)''', "observed-wire")
rep("(declare pc-profile? site-op site-reasons)",
    "(declare pc-profile? site-op site-reasons observed-wire*)", "declare")

# lift-pending! engine
rep('''  [vm serve! found encode entry]
  (case (observed-wire entry)
    :next''', '''  [vm serve! found encode entry]
  (case (observed-wire entry (:engine (:v2 @found)))
    :next''', "lift-pending-dispatch")
rep('''    (cond-> {:yin.k/reason :ffi
             :yin.k/call-id (:call-id entry)''', '''    (cond-> {:yin.k/reason :ffi
             :yin.k/call-id (entry-call-id (:engine (:v2 @found)) entry)''', "ffi-call-id")
rep('''           :yin.k/call-id (:call-id entry)
           :yin.k/request-envelope (encode envelope)
           :yin.k/request-op (or (apply2/request-op envelope)
                                 (:op entry))''', '''           :yin.k/call-id (entry-call-id (:engine (:v2 @found)) entry)
           :yin.k/request-envelope (encode envelope)
           :yin.k/request-op (or (apply2/request-op envelope)
                                 (:op entry)
                                 (:op (:k entry)))''', "ffi-request-id")
rep('''        wire (observed-wire entry)]
    (when (nil? wire)''', '''        wire (observed-wire entry engine)]
    (when (nil? wire)''', "lift-frame-wire")

# ------------------------------------------------ walker lift (before regs-v2)
rep('''(defn- registers-v2
  "One register map of the body's profile''', '''(defn- node-row!
  "The root row id of AST `node` through the v3 row codec, its rows
   joining the export's own.  Extra AST metadata never travels; an AST
   the codec cannot project is not portable."
  [found node]
  (let [{:keys [root rows]}
        (try (vm/ast->semantic-bytecode node)
             (catch #?(:cljd Object :clj Throwable :cljs :default) _
               (non-portable! :non-canonicalizable {:yin.k/hint :ast})))]
    (swap! found update :rows merge rows)
    root))


(defn- walker-frame-v2
  "One native walker continuation frame (`nil` ends the chain) as its
   closed wire map: the type and next, the saved environment when the
   frame has one, and the arm's own fields.  An addressed node is its
   row; every dynamic field is a value."
  [found encode k]
  (when (some? k)
    (let [t (:type k)
          f (:frame k)
          next-k (walker-frame-v2 found encode (:next k))
          base (cond-> {:yin.k/type t :yin.k/next next-k}
                 (contains? k :env)
                 (assoc :yin.k/env (encode-env-v2 found encode (:env k))))
          node #(assoc base :yin.k/node (node-row! found %))]
      (case t
        (:eval-operator :eval-test :eval-stream-put-target
         :eval-stream-cursor-source :eval-stream-next-cursor
         :eval-stream-close-source)
        (node f)

        :eval-operand
        (do (when-not (true? (:operator-evaluated? f))
              (non-portable! :inconsistent-state {:yin.k/hint :operator}))
            (assoc (node (dissoc f :operator-evaluated? :fn :evaluated))
                   :yin.k/function (encode (:fn f))
                   :yin.k/evaluated (mapv encode (or (:evaluated f) []))))

        :dao.stream.apply/eval-operand
        (assoc (node {:type :dao.stream.apply/call
                      :op (:op f)
                      :operands (:operands f)})
               :yin.k/evaluated (mapv encode (or (:evaluated f) [])))

        :eval-stream-put-val
        (assoc (node f) :yin.k/stream-ref (encode (:stream-ref k)))

        :dao.stream.apply/request-sent
        (assoc base :yin.k/parked-id (:parked-id k) :yin.k/op (:op k))

        :dao.stream.apply/eval-call
        (cond-> base
          (contains? k :call-id) (assoc :yin.k/call-id (:call-id k)))

        :eval-define
        (assoc base :yin.k/name (:name k))

        :eval-resume-val
        (assoc base :yin.k/parked-id (:parked-id k))

        (non-portable! :inconsistent-state {:yin.k/hint :frame})))))


(defn- walker-registers-v2
  [found encode {:keys [env k]}]
  {:yin.k/env (encode-env-v2 found encode env)
   :yin.k/k (walker-frame-v2 found encode k)})


(defn- walker-code-v2
  "The walker's code map: the rows the encoded frames projected, and
   the dependency closure, through the source's own held rows, of every
   closure's lambda row.  A name the source holds no row for is not
   addressed."
  [vm found]
  (let [held (:rows vm)
        closure
        (loop [work (vec (:segments @found)), seen #{}]
          (if-some [id (peek work)]
            (let [work (pop work)]
              (if (contains? seen id)
                (recur work seen)
                (let [row (get held id)]
                  (when (nil? row)
                    (non-portable! :unaddressed-segment {:yin.k/hint id}))
                  (recur (into work (row-children row)) (conj seen id)))))
            seen))]
    (merge (into {} (map (fn [id] [id (get held id)])) closure)
           (:rows @found))))


(defn- registers-v2
  "One register map of the body's profile''', "walker-lift")
rep('''    :register (register-registers-v2 found encode vm rec)
    (non-portable! :unsupported-kernel {:yin.k/hint engine})))''',
    '''    :register (register-registers-v2 found encode vm rec)
    :walker (walker-registers-v2 found encode rec)
    (non-portable! :unsupported-kernel {:yin.k/hint engine})))''', "walker-dispatch")

# row-children helper + ffi-bookkeeping, before census
rep(''';; -----------------------------------------------------------------------------
;; The positional profiles' sites, census and layouts''', '''(defn- row-children
  "The row ids a v3 row `[id tag & slots]` references, by its slots'
   `:node` and `:nodes` kinds."
  [row]
  (mapcat (fn [[_ kind] v]
            (case kind :node [v] :nodes v nil))
          (get vm/semantic-bytecode-grammar (nth row 1))
          (drop 2 row)))


(defn- ffi-bookkeeping?
  "A walker parked record that is FFI correlation bookkeeping, not an
   explicit park: its continuation is the response wrapper."
  [rec]
  (= :dao.stream.apply/eval-call (:type (:k rec))))


;; -----------------------------------------------------------------------------
;; The positional profiles' sites, census and layouts''', "row-children")

rep('''(defn- explicit-park?
  [engine vm rec]
  (if (pc-profile? engine)
    (= :park (site-op engine rec))
    (boolean (some #(= :explicit-park %)
                   (safepoint-kinds-at vm (:segment rec) (:pc rec))))))''', '''(defn- explicit-park?
  [engine vm rec]
  (case engine
    :walker (not (ffi-bookkeeping? rec))
    (:stack :register) (= :park (site-op engine rec))
    (boolean (some #(= :explicit-park %)
                   (safepoint-kinds-at vm (:segment rec) (:pc rec))))))''', "explicit-park")

rep('''  [engine vm]
  {:yin.k/refusals []
   :yin.k/missing {}
   :yin.k/store (:store vm)
   :yin.k/scheduler
   {:yin.k/parked (into {}
                        (filter (fn [[_ rec]] (explicit-park? engine vm rec)))
                        (:parked vm))}})''', '''  [engine vm]
  (let [justified (into #{}
                        (keep #(when (walker-ffi-frame %)
                                 (entry-call-id :walker %)))
                        (:wait-set vm))]
    {:yin.k/refusals []
     :yin.k/missing {}
     :yin.k/store (:store vm)
     :yin.k/scheduler
     {:yin.k/parked (into {}
                          (filter (fn [[pid rec]]
                                    (or (explicit-park? engine vm rec)
                                        (and (= :walker engine)
                                             (contains? justified pid)))))
                          (:parked vm))}}))''', "census")
open(p, 'w').write(s)

(ns yang.php.antlr.portable-harness
  "What the JVM, Node and Dart lanes share: the host composition the PHP
   profile runs over, the `packet` helper that builds a CST packet from a
   nested form, and a parserless run of a packet on the AST walker."
  (:require
    [dao.stream :as stream]
    [dao.stream.memory-log :as memory-log]
    [dao.stream.ringbuffer :as ringbuffer]
    [yang.php.antlr.lower :as lower]
    [yang.php.antlr.prelude :as prelude]
    [yin.vm :as vm]
    [yin.vm.ast-walker :as ast-walker]
    [yin.vm.data :as data]
    [yin.vm.integer :as integer]
    [yin.vm.module :as module]))


(def ^:private capability-secret "yin.vm.test-utils/capability-secret")


(defn make-stream
  "The composition's stream constructor: a deterministic ring buffer, answering
   the create outcome the engine consumes. The lifecycle streams below come
   from this same constructor, so request consumption and response/diagnostic
   emission travel composition-supplied streams rather than vectors."
  ([] (make-stream nil))
  ([capacity]
   (ringbuffer/create!
     {:dao.stream/type ringbuffer/transport-type,
      ringbuffer/capacity-key (or capacity vm/default-stream-capacity)})))


(defn- check-ok!
  [result what]
  (when-not (= :dao.stream/ok (:dao.stream/outcome result))
    (throw (ex-info (str "PHP lifecycle stream failed: " what) {:result result})))
  result)


(defn- lifecycle-stream
  "One open lifecycle stream handle from the composition's own constructor."
  [what]
  (:dao.stream/handle (check-ok! (make-stream) what)))


(defn create-vm
  "An AST walker composed with the harness's deterministic ring-buffer stream."
  ([] (create-vm {}))
  ([opts]
   (ast-walker/create-vm
     (merge {:make-stream (fn [capacity] (make-stream capacity)),
             :capability-secret capability-secret}
            opts))))


(defn- new-memory-log
  []
  (let [result (memory-log/create! {:dao.stream/type memory-log/transport-type})]
    (if (= :dao.stream/ok (:dao.stream/outcome result))
      (:dao.stream/handle result)
      (throw (ex-info "PHP harness memory-log creation failed" {:result result})))))


(defn- drain
  [handle]
  (let [start (get-in (stream/cursor handle stream/anchor-oldest)
                      [:dao.stream/cursor])]
    (loop [cursor start, acc []]
      (let [result (stream/next handle cursor)]
        (if (= :dao.stream/ok (:dao.stream/outcome result))
          (recur (:dao.stream/cursor result)
                 (conj acc (:dao.stream/value result)))
          acc)))))


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


(defn linked-registry
  "`host-registry` over the default registry, whose `require` handlers the
   linked profile needs."
  []
  (-> (module/default-registry)
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
  [{:php/keys [out uncaught fatal]}]
  (str out
       (when uncaught
         (str "PHP Fatal error:  Uncaught " (:class uncaught) ": " (:message uncaught)))
       (when fatal
         (str "PHP Fatal error:  " fatal))))


(defn lower-through-stage
  "`{:ast a :warnings w}` or `{:diagnostics d}` for `pk` through the lowering
   stage. Warning-severity diagnostics do not stop the program. With a request
   record, it rides the composition path as `run-main`'s second operand."
  ([pk] (lower-through-stage pk nil))
  ([pk request]
   (let [cst-log (new-memory-log)
         program (new-memory-log)
         diagnostics (new-memory-log)]
     (stream/append! cst-log pk)
     (lower/step-stage (lower/open-stage cst-log program diagnostics
                                         (cond-> {:medium lower/program-medium}
                                           (some? request) (assoc :request request))))
     (let [{warnings true, problems false}
           (group-by #(= :warning (:severity %)) (drain diagnostics))]
       (if (seq problems)
         {:diagnostics problems}
         (let [{:yin/keys [batch root]} (first (drain program))]
           {:ast (nth batch root), :warnings (vec warnings)}))))))


(defn run-packet-raw
  "The unit's value, `{:php/out :php/diag :php/uncaught}`, of packet `pk` on
   the AST walker, or `{:diagnostics d}`."
  ([pk] (run-packet-raw pk nil))
  ([pk request]
   (let [{:keys [ast diagnostics]} (lower-through-stage pk request)]
     (if diagnostics
       {:diagnostics diagnostics}
       (vm/value (vm/eval (create-vm {:modules (host-registry)}) ast))))))


(defn- consume-one
  "The single value appended to `handle`, read back through a fresh oldest
   cursor: real stream consumption, not a vector pass."
  [handle]
  (let [start (:dao.stream/cursor (check-ok! (stream/cursor handle stream/anchor-oldest)
                                             "request cursor"))
        result (check-ok! (stream/next handle start) "request read")]
    (:dao.stream/value result)))


(defn- emit-all!
  "Every record of `records` appended to the composition-supplied `handle`:
   real stream emission, then drained back by the caller."
  [handle records what]
  (doseq [record records]
    (check-ok! (stream/append! handle record) what)))


(defn run-lifecycle-raw
  "The full request/response/diagnostics lifecycle for packet `pk` over
   composition-supplied streams: `request` (nil keeps the empty defaults) is
   appended to the request stream and consumed back from it; the consumed
   record rides the composition path as `run-main`'s second operand; the run's
   response and diagnostic records are emitted on the response and diagnostics
   streams. Returns `{:value v :request consumed :response [...] :diag [...]}`,
   the drained streams, or `{:diagnostics d}` when lowering refuses."
  [pk request]
  (let [request-stream (lifecycle-stream "request create")
        response-stream (lifecycle-stream "response create")
        diagnostics-stream (lifecycle-stream "diagnostics create")]
    (when (some? request)
      (check-ok! (stream/append! request-stream request) "request append"))
    (let [consumed (if (some? request) (consume-one request-stream) nil)
          {:keys [ast diagnostics]} (lower-through-stage pk consumed)]
      (if diagnostics
        {:diagnostics diagnostics}
        (let [v (vm/value (vm/eval (create-vm {:modules (host-registry)}) ast))]
          (emit-all! response-stream (:php/response v) "response append")
          (emit-all! diagnostics-stream (:php/diag v) "diagnostics append")
          {:value v,
           :request consumed,
           :response (drain response-stream),
           :diag (drain diagnostics-stream)})))))


(defn run-packet
  "The rendered output of packet `pk` on the AST walker, or its diagnostics."
  [pk]
  (let [r (run-packet-raw pk)]
    (if (:diagnostics r) r (render r))))

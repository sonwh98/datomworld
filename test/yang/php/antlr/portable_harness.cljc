(ns yang.php.antlr.portable-harness
  "What the JVM, Node and Dart lanes share: the host composition the PHP
   profile runs over, the `packet` helper that builds a CST packet from a
   nested form, and a parserless run of a packet on the AST walker."
  (:require
    [dao.stream :as stream]
    [dao.stream.memory-log :as memory-log]
    [dao.stream.ringbuffer :as ringbuffer]
    [yang.core.uast :as u]
    [yang.php.antlr.lower :as lower]
    [yang.php.antlr.prelude :as prelude]
    [yin.vm :as vm]
    [yin.vm.ast-walker :as ast-walker]
    [yin.vm.engine :as engine]
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
  "The registry with the cell, data, integer and stream modules installed,
   admitted by the PHP profile. The stream module is what lets execution
   emit response and diagnostic records on composition-supplied streams
   during the run."
  []
  (-> (module/empty-registry)
      module/register-cell-module
      (data/register-data-module data-limits)
      (prelude/register-integer-module integer-limits)
      module/register-stream-module
      prelude/admit))


(defn linked-registry
  "`host-registry` over the default registry, whose `require` handlers the
   linked profile needs."
  []
  (-> (module/default-registry)
      module/register-cell-module
      (data/register-data-module data-limits)
      (prelude/register-integer-module integer-limits)
      module/register-stream-module
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


(defn operand-node
  "A `run-main` operand node the composition supplies: the harness converts
   the opaque reference (a request-stream, response-stream or diagnostics
   reference) to UAST itself — lowering never embeds composition data, and
   request records never travel as compiled UAST content."
  [value]
  (u/sexp->uast (or value :php/null)))


(def request-node
  "The `run-main` second-operand node the composition supplies: the attached
   request-stream reference (or nil for the empty defaults). The record is
   consumed from the stream at runtime, never embedded in the program."
  operand-node)


(defn lower-through-stage
  "`{:ast a :warnings w}` or `{:diagnostics d}` for `pk` through the lowering
   stage. Warning-severity diagnostics do not stop the program. `request-ref`
   is the attached request-stream reference the harness composed as
   `run-main`'s second operand — lowering itself never sees request data, and
   the record reaches the runtime only through the stream at execution time.
   With `response-ref`/`diag-ref`, the composition-supplied stream references
   execution emits on during the run ride as the third and fourth operands."
  ([pk] (lower-through-stage pk nil nil nil))
  ([pk request-ref] (lower-through-stage pk request-ref nil nil))
  ([pk request-ref response-ref diag-ref]
   (let [cst-log (new-memory-log)
         program (new-memory-log)
         diagnostics (new-memory-log)]
     (stream/append! cst-log pk)
     (lower/step-stage (lower/open-stage cst-log program diagnostics
                                         (cond-> {:medium lower/program-medium}
                                           (some? request-ref) (assoc :request-node
                                                                      (operand-node request-ref))
                                           (some? response-ref) (assoc :response-node
                                                                       (operand-node response-ref))
                                           (some? diag-ref) (assoc :diag-node
                                                                   (operand-node diag-ref)))))
     (let [{warnings true, problems false}
           (group-by #(= :warning (:severity %)) (drain diagnostics))]
       (if (seq problems)
         {:diagnostics problems}
         (let [{:yin/keys [batch root]} (first (drain program))]
           {:ast (nth batch root), :warnings (vec warnings)}))))))


(defn- attach-request-stream
  "The composition-supplied request stream carrying `request`, attached under
   the VM's capability secret, answering `[request-ref vm]`. The sealed
   reference — never the record — is what the program composes; `run-main`
   consumes the record from the stream at runtime."
  [vm request]
  (let [request-stream (lifecycle-stream "request create")]
    (check-ok! (stream/append! request-stream request) "request append")
    (engine/attach-resource vm request-stream)))


(defn run-packet-raw
  "The unit's value, `{:php/out :php/diag :php/uncaught}`, of packet `pk` on
   the AST walker, or `{:diagnostics d}`. A request rides the attached
   request stream (consumed at runtime), never the compiled program."
  ([pk] (run-packet-raw pk nil))
  ([pk request]
   (let [vm (create-vm {:modules (host-registry)})
         [request-ref vm] (if (some? request)
                            (attach-request-stream vm request)
                            [nil vm])
         {:keys [ast diagnostics]} (lower-through-stage pk request-ref)]
     (if diagnostics
       {:diagnostics diagnostics}
       (vm/value (vm/eval vm ast))))))


(defn- attach-lifecycle-streams
  "The AST walker VM with the composition-supplied `request-stream`,
   `response-stream` and `diagnostics-stream` handles attached under its
   capability secret, answering `[vm request-ref response-ref diag-ref]`
   (a nil request keeps the empty defaults and composes no reference): the
   sealed references the program consumes and emits on DURING execution."
  [request response-stream diagnostics-stream]
  (let [vm (create-vm {:modules (host-registry)})
        [request-ref vm] (if (some? request)
                           (attach-request-stream vm request)
                           [nil vm])
        [response-ref vm] (engine/attach-resource vm response-stream)
        [diag-ref vm] (engine/attach-resource vm diagnostics-stream)]
    [vm request-ref response-ref diag-ref]))


(defn run-lifecycle-raw
  "The full request/response/diagnostics lifecycle for packet `pk` over
   composition-supplied streams: `request` (nil keeps the empty defaults) is
   appended to the request stream; the attached stream reference rides the
   composition path as `run-main`'s second operand, and the
   response/diagnostics stream references ride as its third and fourth
   operands. `run-main` consumes the request record from the stream at
   runtime — the record never enters the compiled program. Execution emits
   response and diagnostic records onto those streams DURING the run (never
   copied after completion); the runtime no longer appends to cells on this
   path. Returns
   `{:value v :request request :response [...] :diag [...]}`, the drained
   streams, or `{:diagnostics d}` when lowering refuses."
  [pk request]
  (let [response-stream (lifecycle-stream "response create")
        diagnostics-stream (lifecycle-stream "diagnostics create")
        [vm request-ref response-ref diag-ref]
        (attach-lifecycle-streams request response-stream diagnostics-stream)
        {:keys [ast diagnostics]} (lower-through-stage pk request-ref response-ref diag-ref)]
    (if diagnostics
      {:diagnostics diagnostics}
      (let [v (vm/value (vm/eval vm ast))]
        {:value v,
         :request request,
         :response (drain response-stream),
         :diag (drain diagnostics-stream)}))))


(defn run-packet
  "The rendered output of packet `pk` on the AST walker, or its diagnostics."
  [pk]
  (let [r (run-packet-raw pk)]
    (if (:diagnostics r) r (render r))))

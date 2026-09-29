(ns yin.vm.ffi.remote-serve.responder
  "The FFI apply responder at the possessing peer: the interpreter that
   reads apply requests off a VM's call-in stream and appends apply
   responses to its call-out stream, over the call pair an export
   binding (`yin.vm.ffi.remote-serve`) serves to a remote VM.

   It is a separate namespace because it is a separate observer of the
   same streams. The binding's mirror serves stream OPERATIONS on
   call-in and call-out -- a remote VM's append! lands on call-in, its
   next reads call-out -- and interprets no value; this responder
   interprets the apply VALUES and knows nothing of the channel, the
   table or the leases. Neither drives the other: the one drive owner
   calls `rs/step` and `step` in turn.

   `open!` exports call-in as a remote WRITER and call-out as a remote
   READER through the binding's own `serve!`, so the binding's
   injected authority gate (`::rs/admit?`) and surface policy decide
   whether this peer's handlers are reachable at all -- there is no
   second, default gate that admits everything. The handler map is
   required and explicit.

   `step` is one `dao.stream.apply/serve-once!` over the caller-owned
   server state the responder value carries: the request is read
   whole, as the VM appended it -- extra keys and all, never rebuilt
   from op and args -- its id answered on call-out, and a computed
   response retained with its successor across `full`, so no handler
   runs twice. Two responder-level rules sit on top:

   - A gap on the call-in cursor is terminal loss (as
     `yin.vm.ffi/bridge-step` treats it locally): a request was evicted
     and the parked call that made it could never be answered. The
     responder reports `::request-lost` and closes call-out, so every
     VM waiting on that pair reads end and raises the portable
     `:dao.stream.apply/ended` loss (`yin.vm.ffi/response-lost`)
     instead of parking forever.
   - Once the binding no longer serves call-in or call-out -- retired,
     closed, or reclaimed by its lease -- the responder is terminal
     (`::retired`) and invokes no further handler for that tenure.

   The responder is a plain value threaded by its drive owner; nothing
   here loops, waits or reads a clock."
  (:require [dao.stream :as stream]
            [dao.stream.apply :as apply2]
            [yin.vm.ffi.remote-serve :as rs]))


(defn- handlers?
  "An explicit handler map: operation keyword -> function."
  [m]
  (and (map? m)
       (every? keyword? (keys m))
       (every? fn? (vals m))))


(defn remote-descriptor
  "The portable remote descriptor for a served marker `{:dao.stream/identity
   id :dao.stream/channel cd}` -- what a remote peer attaches."
  [served]
  {:dao.stream/type :dao.stream/remote
   :dao.stream/identity (:dao.stream/identity served)
   :dao.stream/channel (:dao.stream/channel served)})


(defn- refused
  [option reason]
  {::status ::refused, ::refusals [{::option option, ::reason reason}]})


(defn- served-surface
  [binding served]
  (get-in (rs/table binding) [(:dao.stream/identity served) :surface]))


(defn- export
  "Serve call-in and call-out through `binding`, or name the refusal.
   Returns `[in out nil]` or `[_ _ refusal]`."
  [binding call-in call-out]
  (let [in (rs/serve! binding call-in)
        out (when in (rs/serve! binding call-out))]
    [in out
     (cond
       (nil? in) (refused ::call-in ::unservable)
       (not (contains? (served-surface binding in) :writer))
       (refused ::call-in ::surface)
       (nil? out) (refused ::call-out ::unservable)
       (not (contains? (served-surface binding out) :reader))
       (refused ::call-out ::surface))]))


(defn open!
  "Export the call pair `::call-in`/`::call-out` through `binding` and
   assemble the responder over `::handlers`, or refuse.

   `opts`:
     ::call-in   the VM's call-in stream at this peer: this responder
                 reads it locally, a remote VM appends to it -- served
                 with a surface containing :writer
     ::call-out  the VM's call-out stream at this peer: this responder
                 appends to it, a remote VM reads it -- served with a
                 surface containing :reader
     ::handlers  the explicit handler map, op keyword -> fn; no default

   The request cursor is minted from call-in itself before anything is
   served. A refusal -- no handler map, a call-in that mints no
   cursor, either endpoint unservable under the binding's gate and
   surface policy, or served without the surface it needs -- retires
   every identity this call served, so no provisional export outlives
   it; handles already served before it stay served.

   Returns `{::binding b ::call-in h ::call-out h ::handlers m
   ::endpoint apply-endpoint ::state apply-server-state}` --
   `::endpoint` is the `dao.stream.apply/endpoint` of the two remote
   descriptors a remote VM attaches -- or `{::status ::refused
   ::refusals [...]}`."
  [binding {::keys [call-in call-out handlers]}]
  (cond
    (not (rs/binding? binding)) (refused ::binding ::malformed)
    (nil? handlers) (refused ::handlers ::missing)
    (not (handlers? handlers)) (refused ::handlers ::malformed)
    (not (stream/reader? call-in)) (refused ::call-in ::malformed)
    (not (stream/writer? call-out)) (refused ::call-out ::malformed)
    :else
    (let [c (stream/cursor call-in :dao.stream/oldest)]
      (if-not (= :dao.stream/ok (:dao.stream/outcome c))
        (refused ::call-in ::no-cursor)
        (let [before (into #{} (map :identity) (rs/entries binding))
              [in out refusal] (export binding call-in call-out)]
          (if refusal
            (do (doseq [e (rs/entries binding)]
                  (when-not (contains? before (:identity e))
                    (rs/retire! binding (:identity e))))
                refusal)
            {::binding binding
             ::call-in call-in
             ::call-out call-out
             ::handlers handlers
             ::served {::call-in in, ::call-out out}
             ::endpoint (apply2/endpoint (remote-descriptor in)
                                         (remote-descriptor out))
             ::state (apply2/server-state (:dao.stream/cursor c))}))))))


(defn- still-served?
  [r]
  (let [b (::binding r)]
    (and (rs/served? b (get-in r [::served ::call-in :dao.stream/identity]))
         (rs/served? b (get-in r [::served ::call-out :dao.stream/identity])))))


(defn- close-call-out!
  [r]
  (let [out (::call-out r)]
    (when (stream/closable? out)
      (stream/close! out))))


(defn step
  "One responder pass: at most one request read and one response
   append (`dao.stream.apply/serve-once!`). Returns serve-once!'s own
   result -- `:dao.stream.apply/outcome` and its extra keys -- with the
   responder carrying the new state under `::responder`; the drive
   owner threads that value into its next `step`.

   Outcomes beyond serve-once!'s: `::request-lost` when the call-in
   cursor gaps (call-out is closed, the responder terminal), and
   `::retired` when the binding no longer serves the pair. Once
   terminal, every step answers `:dao.stream.apply/terminal` and
   invokes nothing."
  [r]
  (let [s (::state r)
        with (fn [result s']
               (assoc result ::responder (assoc r ::state s')))]
    (cond
      (:terminal s)
      (with {:dao.stream.apply/outcome :dao.stream.apply/terminal} s)

      (not (still-served? r))
      (with {:dao.stream.apply/outcome ::retired}
            (assoc s :terminal ::retired))

      :else
      (let [result (apply2/serve-once! (::handlers r) (::call-in r)
                                       (::call-out r) s)
            s' (:dao.stream.apply/state result)]
        (if (= :dao.stream.apply/gap (:dao.stream.apply/outcome result))
          (do (close-call-out! r)
              (with (assoc result :dao.stream.apply/outcome ::request-lost)
                    (assoc s' :terminal ::request-lost)))
          (with result s'))))))

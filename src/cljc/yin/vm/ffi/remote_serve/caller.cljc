(ns yin.vm.ffi.remote-serve.caller
  "Call-out readiness at the calling peer: the composition step that
   makes a remote call pair ready for a VM before the VM is built.

   A VM built over a supplied call pair needs its call-out cursor at
   construction (`yin.vm/empty-state` refuses a supplied `:call-out`
   without `:call-out-cursor`). Over reflections of a served endpoint
   that cursor is a remote answer: the reflection's first `cursor`
   answers retry while its request crosses, and only a later ask, after
   the drive owner has moved the channel, finds the answer filed. The VM
   must not learn that cadence -- readiness belongs to the driver, not
   the stream -- so it lives here, in the caller's composition.

   `open` attaches both reflections of an apply endpoint and checks the
   caller token. `step` asks the call-out reflection once for a
   `:dao.stream/newest` cursor: the cursor lands after every response
   already on the shared stream, so this caller reads only responses to
   requests it sends afterwards. `step` yields `::pending` on retry or
   blocked, `::ready` with the minted cursor once it is filed, and is
   terminal (`::refused`, `::exhausted`) otherwise; `::max-attempts`
   bounds the asks. The drive owner steps its channel between steps.
   `vm-opts` then answers the construction options -- the pair, the
   cursor, and the caller token -- for any VM's `create-vm`.

   Every reflection this step acquires is either handed to a VM
   composition through `vm-opts` or closed: a call-out that does not
   attach closes the call-in already attached, and `step` closes both
   when it ends `::refused` or `::exhausted`. A ready caller whose owner
   builds no VM is torn down with `close!`. A reflection's close is
   local, and it releases the reflection's outstanding and pending work.

   The caller value is a plain value threaded by its owner; nothing here
   loops, waits or reads a clock."
  (:require [dao.stream :as stream]
            [dao.stream.apply :as apply2]
            [yin.vm :as vm]))


(def default-max-attempts
  "How many cursor asks `step` makes before it gives up as `::exhausted`."
  16)


(defn- refused
  [option reason]
  {::status ::refused, ::refusals [{::option option, ::reason reason}]})


(defn- attach
  [attach! descriptor]
  (let [r (try (attach! descriptor)
               (catch #?(:cljd Object :clj Throwable :cljs :default) _ nil))]
    (when (= :dao.stream/ok (:dao.stream/outcome r))
      (:dao.stream/handle r))))


(defn- close-quietly
  "Close reflection `h`. Close is idempotent and total over ok; a
   handle that throws on close has nothing left to release here."
  [h]
  (try (stream/close! h)
       (catch #?(:cljd Object :clj Throwable :cljs :default) _ nil)))


(defn close!
  "Tear down a caller whose readiness ends without a VM: close both
   reflections it holds, once. Idempotent; a caller refused at `open`
   holds none. Returns the caller, `::closed? true`, which `vm-opts`
   no longer answers for. `step` calls this itself on its terminal
   failures; a caller whose pair went to a VM must not be closed here --
   the VM composition owns those reflections."
  [caller]
  (if (or (::closed? caller) (nil? (::call-in caller)))
    caller
    (do (close-quietly (::call-in caller))
        (close-quietly (::call-out caller))
        (assoc caller ::closed? true))))


(defn- portable-id?
  "True when the call ids this caller mints survive `codec` -- encoded,
   decoded back. No codec asserts nothing more than plain data."
  [codec caller-id]
  (let [id [caller-id :parked-0]]
    (or (nil? codec)
        (try (= id ((:decode codec) ((:encode codec) id)))
             (catch #?(:cljd Object :clj Throwable :cljs :default) _ false)))))


(defn open
  "Attach the call pair of apply `::endpoint` through `::attach!` (the
   caller's attacher, `(fn [descriptor] -> attach outcome)`), for the
   caller tenure named by `::caller-id` -- a token the composition mints,
   unique per tenure (`yin.vm/ffi-caller-id?`). Optional:
   `::max-attempts` (default `default-max-attempts`), and `::codec`, the
   channel codec `{:encode f :decode f}` the call ids must survive.

   Returns the caller value, `::status ::pending`, or
   `{::status ::refused ::refusals [...]}` before anything is attached
   when an option is malformed, or naming the reflection that did not
   attach."
  [opts]
  (let [{::keys [endpoint attach! caller-id max-attempts codec]} opts
        max-attempts (or max-attempts default-max-attempts)]
    (cond
      (not (fn? attach!)) (refused ::attach! ::malformed)
      (not (apply2/endpoint? endpoint)) (refused ::endpoint ::malformed)
      (not (vm/ffi-caller-id? caller-id)) (refused ::caller-id ::malformed)
      (not (portable-id? codec caller-id)) (refused ::caller-id ::unportable)
      (not (and (int? max-attempts) (pos? max-attempts)))
      (refused ::max-attempts ::malformed)
      :else
      (if-some [in (attach attach! (apply2/endpoint-request endpoint))]
        (if-some [out (attach attach! (apply2/endpoint-response endpoint))]
          {::status ::pending,
           ::call-in in,
           ::call-out out,
           ::caller-id caller-id,
           ::attempts 0,
           ::max-attempts max-attempts}
          (do (close-quietly in)
              (refused ::call-out ::unattached)))
        (refused ::call-in ::unattached)))))


(defn step
  "Ask the call-out reflection once for a `:dao.stream/newest` cursor.
   `ok` makes the caller `::ready` with that cursor; retry or blocked
   leaves it `::pending`, or `::exhausted` once `::max-attempts` asks
   have gone unanswered; any other outcome refuses it with that
   `::outcome`. Both terminal failures close the caller's reflections
   (`close!`). A caller that is not pending is returned unchanged."
  [caller]
  (if-not (= ::pending (::status caller))
    caller
    (let [r (stream/cursor (::call-out caller) stream/anchor-newest)
          o (:dao.stream/outcome r)
          attempts (inc (::attempts caller))]
      (cond
        (= :dao.stream/ok o)
        (assoc caller
               ::status ::ready
               ::attempts attempts
               ::call-out-cursor (:dao.stream/cursor r))
        (or (= :dao.stream/blocked o) (:dao.stream/retry? r))
        (if (< attempts (::max-attempts caller))
          (assoc caller ::attempts attempts)
          (close! (assoc caller ::status ::exhausted ::attempts attempts)))
        :else (close! (assoc caller
                             ::status ::refused
                             ::attempts attempts
                             ::outcome o))))))


(defn ready?
  "True when the caller holds a minted cursor and was not torn down."
  [caller]
  (and (= ::ready (::status caller)) (not (::closed? caller))))


(defn call-out-cursor
  "The minted call-out cursor of a ready caller, else nil."
  [caller]
  (when (ready? caller) (::call-out-cursor caller)))


(defn vm-opts
  "The construction options a ready caller hands any VM's `create-vm`:
   `:call-in`, `:call-out`, `:call-out-cursor` and `:ffi-caller-id`.
   nil when the caller is not ready."
  [caller]
  (when (ready? caller)
    {:call-in (::call-in caller),
     :call-out (::call-out caller),
     :call-out-cursor (::call-out-cursor caller),
     :ffi-caller-id (::caller-id caller)}))

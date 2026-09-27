(ns dao.stream.middleware
  "DaoStream middleware: a handle-level adapter value, one `in` transform
   over the operation map and one `out` transform over the outcome map
   (docs/design/dao.stream.middleware.md).

   A middleware is a value, never a callback: every transform here is
   called downward by the caller and returns at once. `wrap` composes a
   chain over a handle; `apply-request` is the one place an operation map
   becomes a protocol call; `gate` and `present` are the two generic
   middlewares; `encryption` and `metering` are the spec's exemplars;
   `channel-allow-list` is the trivial policy this repository ships with.

   The position rule is structural, not a convention: `wrap` validates
   every `out` result against the permitted changes and raises on a
   violation, so a filter cannot be expressed. Each transform is still
   total, returns at once, never loops, retries, waits or touches the
   inner handle, and maps inner position p to outer position p."
  (:require [dao.stream :as stream]))


;; =============================================================================
;; The one dispatch point
;; =============================================================================

(defn- dispatch
  "Apply one operation map to a plain handle: the place an operation map
   becomes a protocol call. An op outside the middleware vocabulary
   (cursor, next, append!) is a defect in the host's own assembly -- the
   mirror validates ops before this -- so it throws, per the contract's
   reservation of exceptions (dao.stream.md, Result Convention)."
  [h req]
  (let [[arg] (:dao.stream.remote/args req)]
    (case (:dao.stream.remote/op req)
      :dao.stream/cursor (stream/cursor h arg)
      :dao.stream/next (stream/next h arg)
      :dao.stream/append! (stream/append! h arg)
      (throw (ex-info "dao.stream.middleware: unknown operation"
                      {:op (:dao.stream.remote/op req)})))))


(defprotocol IMiddlewareHandle
  "Internal seam of this namespace: the handle `wrap` returns answers one
   whole chain pass under a caller-supplied context. `apply-request` uses
   it so a composition (the mirror step of dao.stream.remote.md) can run
   a table entry's middleware with its own context, exactly as the
   wrapped handle's protocol methods run it with the local caller's {}.
   Call `apply-request`, never this."

  (operate-through
    [handle ctx req]
    "Run the wrapped chain for operation map `req` under `ctx`."))


(defn apply-request
  "Applies one operation map to a handle: `(apply-request h ctx req) ->
   outcome`. On a plain handle this is the protocol call and `ctx` is
   unused. On a handle `wrap` returned, this is the whole chain pass
   under `ctx`: the entry point the mirror step composes with a channel
   context. Extra qualified keys on `req` pass through untouched."
  [h ctx req]
  (if (satisfies? IMiddlewareHandle h)
    (operate-through h ctx req)
    (dispatch h req)))


;; =============================================================================
;; wrap
;; =============================================================================

(def ^:private position-keys
  "The outcome keys an `out` result may never change: the outcome kind,
   the stream identity, and every key naming the operation, the request
   id or a position -- a cursor, an anchor or a gap recovery cursor.
   Adding any of these where the outcome had none is a change too."
  [:dao.stream/outcome
   :dao.stream/cursor
   :dao.stream/anchor
   :dao.stream/identity
   :dao.stream.remote/op
   :dao.stream.remote/id])


(defn- position-preserving?
  "True when `result`, the outcome an `out` transform returns for the
   `outcome` it received, makes none of the changes the position rule
   forbids: no key of the outcome is dropped, the outcome kind, the
   identity, the operation, the request id and every cursor, anchor and
   gap recovery cursor are unchanged, and -- except for `next`'s
   :dao.stream/value on :dao.stream/ok, the one replacement a value
   transform owns -- the value is unchanged. Open keys may be added.
   Structural checks over the two outcome maps only, never a walk over
   elements."
  [req outcome result]
  (and (map? result)
       (every? #(contains? result %) (keys outcome))
       (every? (fn [k]
                 (and (= (contains? outcome k) (contains? result k))
                      (= (get outcome k) (get result k))))
               position-keys)
       (let [value-allowed? (and (= :dao.stream/next
                                    (:dao.stream.remote/op req))
                                 (= :dao.stream/ok
                                    (:dao.stream/outcome result)))]
         (or value-allowed?
             (and (= (contains? outcome :dao.stream/value)
                     (contains? result :dao.stream/value))
                  (= (get outcome :dao.stream/value)
                     (get result :dao.stream/value)))))))


(defn- run-outs
  "Run every `out` transform from the innermost outward over `outcome`,
   for the middlewares at chain indices below `upto`. Each result is
   validated against the position rule: an `out` that changes anything
   beyond the permitted replacements is a defect in the host's own
   assembly of the chain and raises, per the contract's reservation of
   exceptions (dao.stream.md, Result Convention) -- a filter is then
   impossible, not merely discouraged."
  [chain ctx req outcome upto]
  (reduce
    (fn [out i]
      (let [result ((:dao.stream.middleware/out (nth chain i))
                    ctx req out)]
        (if (position-preserving? req out result)
          result
          (throw (ex-info "dao.stream.middleware: out broke the position rule"
                          {:middleware i
                           :op (:dao.stream.remote/op req)
                           :received out
                           :returned result})))))
    outcome
    (range (dec upto) -1 -1)))


(defn- operate
  "One pass of `chain` over `req` under `ctx`: every `in` from the
   outermost inward, the final request applied to `h` with
   `apply-request` (which recurses into an inner wrapped handle, so
   `ctx` carries through nesting), every `out` from the innermost
   outward. An `in` answering an outcome map short-circuits: the inner
   handle is not consulted and only the middlewares outside it
   transform the outcome."
  [h chain ctx req]
  (loop [i 0, req req]
    (if (= i (count chain))
      (run-outs chain ctx req (apply-request h ctx req) (count chain))
      (let [res ((:dao.stream.middleware/in (nth chain i)) ctx req)]
        (if (stream/outcome-map? res)
          (run-outs chain ctx req res i)
          (recur (inc i) res))))))


(defn- instantiated
  "Instantiate one middleware of a chain at wrap: when its definition
   declares a `:dao.stream.middleware/state` constructor, `wrap` calls
   it once, here, and specializes the definition's transforms to the
   state minted. The state is therefore state of the wrapped handle --
   one definition wrapped over two handles holds two states, each
   minted by its own wrap (dao.stream.middleware.md, gate). A
   definition that declares no constructor wraps as it is."
  [mw]
  (if-some [make (:dao.stream.middleware/state mw)]
    (let [s (make)]
      {:dao.stream.middleware/in
       (fn [ctx req] ((:dao.stream.middleware/in mw) s ctx req))
       :dao.stream.middleware/out
       (fn [ctx req out] ((:dao.stream.middleware/out mw) s ctx req out))})
    mw))


(defn wrap
  "Compose `chain`, a vector of middleware values applied outermost
   first, over handle `h`. The returned handle implements exactly the
   protocols `h` implements -- descriptor, which every handle carries,
   plus its reader, writer and closable surfaces. On each protocol call
   it builds the operation map, runs every `in` outermost inward,
   applies the result to `h` with `apply-request` under the local
   caller's {} context, and runs every `out` innermost outward, each
   `out` result validated against the position rule -- a result beyond
   the permitted changes raises, so a filter cannot hide in a chain.
   `wrap` also mints each middleware definition's declared state once,
   here, so that state belongs to this wrapped handle. `descriptor` and
   `close!` delegate to `h` unchanged: no middleware sees them. The
   surface branches below are plain reify forms, one per combination,
   because a handle implements its surface and no other."
  [h chain]
  (let [chain (mapv instantiated chain)
        flow (fn [ctx req] (operate h chain ctx req))
        r? (stream/reader? h)
        w? (stream/writer? h)
        c? (stream/closable? h)]
    (cond
      (and r? w? c?)
      (reify
        IMiddlewareHandle
        (operate-through [_ ctx req] (flow ctx req))


        stream/IDaoStreamDescriptor

        (descriptor [_] (stream/descriptor h))


        stream/IDaoStreamReader

        (cursor
          [_ anchor]
          (flow {} {:dao.stream.remote/op :dao.stream/cursor
                    :dao.stream.remote/args [anchor]}))

        (next
          [_ c]
          (flow {} {:dao.stream.remote/op :dao.stream/next
                    :dao.stream.remote/args [c]}))


        stream/IDaoStreamWriter

        (append!
          [_ v]
          (flow {} {:dao.stream.remote/op :dao.stream/append!
                    :dao.stream.remote/args [v]}))


        stream/IDaoStreamClosable

        (close! [_] (stream/close! h)))

      (and r? w?)
      (reify
        IMiddlewareHandle
        (operate-through [_ ctx req] (flow ctx req))


        stream/IDaoStreamDescriptor

        (descriptor [_] (stream/descriptor h))


        stream/IDaoStreamReader

        (cursor
          [_ anchor]
          (flow {} {:dao.stream.remote/op :dao.stream/cursor
                    :dao.stream.remote/args [anchor]}))

        (next
          [_ c]
          (flow {} {:dao.stream.remote/op :dao.stream/next
                    :dao.stream.remote/args [c]}))


        stream/IDaoStreamWriter

        (append!
          [_ v]
          (flow {} {:dao.stream.remote/op :dao.stream/append!
                    :dao.stream.remote/args [v]})))

      (and r? c?)
      (reify
        IMiddlewareHandle
        (operate-through [_ ctx req] (flow ctx req))


        stream/IDaoStreamDescriptor

        (descriptor [_] (stream/descriptor h))


        stream/IDaoStreamReader

        (cursor
          [_ anchor]
          (flow {} {:dao.stream.remote/op :dao.stream/cursor
                    :dao.stream.remote/args [anchor]}))

        (next
          [_ c]
          (flow {} {:dao.stream.remote/op :dao.stream/next
                    :dao.stream.remote/args [c]}))


        stream/IDaoStreamClosable

        (close! [_] (stream/close! h)))

      (and w? c?)
      (reify
        IMiddlewareHandle
        (operate-through [_ ctx req] (flow ctx req))


        stream/IDaoStreamDescriptor

        (descriptor [_] (stream/descriptor h))


        stream/IDaoStreamWriter

        (append!
          [_ v]
          (flow {} {:dao.stream.remote/op :dao.stream/append!
                    :dao.stream.remote/args [v]}))


        stream/IDaoStreamClosable

        (close! [_] (stream/close! h)))

      r?
      (reify
        IMiddlewareHandle
        (operate-through [_ ctx req] (flow ctx req))


        stream/IDaoStreamDescriptor

        (descriptor [_] (stream/descriptor h))


        stream/IDaoStreamReader

        (cursor
          [_ anchor]
          (flow {} {:dao.stream.remote/op :dao.stream/cursor
                    :dao.stream.remote/args [anchor]}))

        (next
          [_ c]
          (flow {} {:dao.stream.remote/op :dao.stream/next
                    :dao.stream.remote/args [c]})))

      w?
      (reify
        IMiddlewareHandle
        (operate-through [_ ctx req] (flow ctx req))


        stream/IDaoStreamDescriptor

        (descriptor [_] (stream/descriptor h))


        stream/IDaoStreamWriter

        (append!
          [_ v]
          (flow {} {:dao.stream.remote/op :dao.stream/append!
                    :dao.stream.remote/args [v]})))

      c?
      (reify
        IMiddlewareHandle
        (operate-through [_ ctx req] (flow ctx req))


        stream/IDaoStreamDescriptor

        (descriptor [_] (stream/descriptor h))


        stream/IDaoStreamClosable

        (close! [_] (stream/close! h)))

      :else
      (reify
        IMiddlewareHandle
        (operate-through [_ ctx req] (flow ctx req))


        stream/IDaoStreamDescriptor

        (descriptor [_] (stream/descriptor h))))))


;; =============================================================================
;; gate, the authorize step
;; =============================================================================

(def ^:private none-marker
  "The decision `verify` receives when the gate has none: no minted
   cursor, no value observed yet, or a value cleared by a failed read.
   A policy that must fail closed refuses it."
  {:dao.stream.middleware/none true})


(def ^:private ended-marker
  "The decision `verify` receives once the gate is ended: the medium
   answered end, so no later operation reads it."
  {:dao.stream.middleware/ended true})


(defn gate
  "The authorize step: `(gate {:dao.stream.middleware/verify verify
   :dao.stream.middleware/decision medium})` -> middleware definition.
   `wrap` mints the gate's state once per wrapped handle and, minting
   it, calls `cursor` :oldest on the decision medium once: the read
   cursor and the last value are state of the wrapped handle, so one
   definition may be wrapped over many handles, each with its own
   cursor. Each operation retries the mint once when it has no cursor,
   then calls `next` on the medium once, plus the one recovery re-read
   after a gap; when the medium answers end the gate becomes ended and
   reads no more. `verify` is `(fn [decision ctx req] -> nil |
   reason)`, the adapter contract of dao.shibi.md: nil passes the
   request through; a reason short-circuits with :dao.stream/refused.
   Serialize calls on one wrapped handle; the gate has no queue, lock
   or wait."
  [opts]
  (let [verify (:dao.stream.middleware/verify opts)
        decision (:dao.stream.middleware/decision opts)
        blank {:cursor nil :value? false :value nil :ended? false}
        mint!
        (fn []
          (let [r (stream/cursor decision stream/anchor-oldest)]
            (when (= :dao.stream/ok (:dao.stream/outcome r))
              (:dao.stream/cursor r))))

        store! (fn [state s] (vreset! state s) s)

        absorb
        (fn [s r]
          (case (:dao.stream/outcome r)
            :dao.stream/ok
            {:cursor (:dao.stream/cursor r) :value? true
             :value (:dao.stream/value r) :ended? false}

            :dao.stream/blocked s

            :dao.stream/end
            {:cursor nil :value? false :value nil :ended? true}

            :dao.stream/gap
            ;; One recovery re-read; never a third read this operation.
            (let [rec (:dao.stream/cursor r)
                  r2 (stream/next decision rec)]
              (case (:dao.stream/outcome r2)
                :dao.stream/ok
                {:cursor (:dao.stream/cursor r2) :value? true
                 :value (:dao.stream/value r2) :ended? false}

                :dao.stream/blocked
                {:cursor rec :value? false :value nil :ended? false}

                :dao.stream/end
                {:cursor nil :value? false :value nil :ended? true}

                ;; A recovery gap adopts the recovery cursor and stays
                ;; without a value.
                :dao.stream/gap
                {:cursor (:dao.stream/cursor r2) :value? false
                 :value nil :ended? false}

                ;; Any other recovery outcome clears cursor and value;
                ;; the mint is retried afresh next operation.
                {:cursor nil :value? false :value nil :ended? false}))

            ;; Any other non-ok outcome clears value and cursor; the
            ;; mint is retried afresh next operation.
            {:cursor nil :value? false :value nil :ended? false}))

        latest
        (fn [state]
          (let [s0 @state]
            (if (:ended? s0)
              ended-marker
              (do
                (when-not (:cursor s0)
                  (when-let [c (mint!)]
                    (store! state (assoc s0 :cursor c))))
                (let [s1 @state]
                  (when (:cursor s1)
                    (store! state (absorb s1 (stream/next decision
                                                          (:cursor s1)))))
                  (let [s2 @state]
                    (cond (:ended? s2) ended-marker
                          (:value? s2) (:value s2)
                          :else none-marker)))))))]
    {:dao.stream.middleware/state
     (fn []
       (let [state (volatile! blank)]
         (when-let [c (mint!)]
           (vswap! state assoc :cursor c))
         state))
     :dao.stream.middleware/in
     (fn [state ctx req]
       (let [reason (verify (latest state) ctx req)]
         (if (nil? reason)
           req
           {:dao.stream/outcome :dao.stream/refused
            :dao.stream.middleware/reason reason})))
     :dao.stream.middleware/out
     (fn [_state _ctx _req outcome] outcome)}))


;; =============================================================================
;; present, the credential step
;; =============================================================================

(defn present
  "The credential step: `(present {:dao.stream.middleware/present f})`
   -> middleware, where `f` is `(fn [ctx req] -> credential)`. Its `in`
   associates the result under :dao.stream.remote/credential on the
   operation map; its `out` is identity."
  [opts]
  (let [present-fn (:dao.stream.middleware/present opts)]
    {:dao.stream.middleware/in
     (fn [ctx req]
       (assoc req :dao.stream.remote/credential (present-fn ctx req)))
     :dao.stream.middleware/out
     (fn [_ctx _req outcome] outcome)}))


;; =============================================================================
;; Exemplars
;; =============================================================================

(defn encryption
  "The spec's value-encryption exemplar. `encipher` and `decipher` come
   from a library cipher; the key is an argument to whatever built them.
   `in` on append! replaces the value with its ciphertext, answering
   :dao.stream/invalid-value when the value cannot be transformed on the
   way in. `out` on an ok from next replaces :dao.stream/value with the
   plaintext or, when it cannot be transformed on the way out, with
   {:dao.stream.middleware/undecodable true :dao.stream.middleware/raw
   v}, the outcome and cursor untouched. Cursors are never touched."
  [opts]
  (let [encipher (:dao.stream.middleware/encipher opts)
        decipher (:dao.stream.middleware/decipher opts)]
    {:dao.stream.middleware/in
     (fn [_ctx req]
       (if (= :dao.stream/append! (:dao.stream.remote/op req))
         (try
           (update-in req [:dao.stream.remote/args 0] encipher)
           (catch #?(:cljd Object :clj Exception :cljs js/Error) _
             {:dao.stream/outcome :dao.stream/invalid-value}))
         req))
     :dao.stream.middleware/out
     (fn [_ctx req outcome]
       (if (and (= :dao.stream/next (:dao.stream.remote/op req))
                (= :dao.stream/ok (:dao.stream/outcome outcome)))
         (try
           (update outcome :dao.stream/value decipher)
           (catch #?(:cljd Object :clj Exception :cljs js/Error) _
             (assoc outcome :dao.stream/value
                    {:dao.stream.middleware/undecodable true
                     :dao.stream.middleware/raw
                     (:dao.stream/value outcome)})))
         outcome))}))


(defn metering
  "The spec's metering exemplar: `out` appends {:meter/identity id
   :meter/op op :meter/outcome kind} to the composed metering stream
   `sink` and returns the outcome unchanged. The side effect is a
   stream emission, never a retry or a wait."
  [opts]
  (let [sink (:meter/stream opts)
        id (:meter/identity opts)]
    {:dao.stream.middleware/in
     (fn [_ctx req] req)
     :dao.stream.middleware/out
     (fn [_ctx req outcome]
       (stream/append! sink
                       {:meter/identity id
                        :meter/op (:dao.stream.remote/op req)
                        :meter/outcome (:dao.stream/outcome outcome)})
       outcome)}))


;; =============================================================================
;; The trivial policy this repository ships with
;; =============================================================================

(defn channel-allow-list
  "An allow-list `verify` for `gate`, keyed on
   :dao.stream.remote/channel in ctx and ignoring the credential and the
   decision: nil for a channel identity in `allowed`, a refusal reason
   for anything else, a missing channel included. The reason vocabulary
   itself belongs to dao.shibi.md; this one is composition-owned."
  [allowed]
  (fn [_decision ctx _req]
    (if (contains? allowed (:dao.stream.remote/channel ctx))
      nil
      :dao.stream.middleware/channel-not-allowed)))

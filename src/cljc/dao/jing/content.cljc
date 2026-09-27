(ns dao.jing.content
  "Content lookup as a request and response service over remote streams
   (docs/design/dao.stream.remote.md, section 5; the successor of the
   deleted dao.jing.remote). One payload convention, spoken by every half
   of the service and by the linker's own client:

     {:jing/request r :jing/get address}
       answered by {:jing/request r :jing/found? b :jing/bytes b64};
     {:jing/request r :jing/put address :jing/bytes b64}
       answered by {:jing/request r :jing/result :inserted | :present}.

   r is a self-minted random value, so callers sharing one answers stream
   do not collide. Bytes travel as padded standard Base64 text in the
   application value; message boundaries are the channel codec's, one
   application value per message, so the vocabulary rides Transit-JSON
   text or canonical CBOR alike.

   This namespace is the serving half: serve-step, the interpreter over a
   local requests reader and answers writer against a dao.jing handle --
   the Linda shape, one peer's table entry pair and its policy. The
   client half is dao.jing.content.step; the JVM blocking driver is
   dao.jing.content.driver; the callback facade is
   dao.jing.content.async. Every put passes through the one ingress
   canonicality check, dao.jing/accept-bytes!."
  (:require [dao.jing :as jing]
            [dao.stream :as stream]))


(def get-op
  "The request key of a content read."
  :jing/get)


(def put-op
  "The request key of a content write."
  :jing/put)


(defn- valid-handle!
  "Throw unless `handle` exposes the byte-store effects the interpreter
   calls; the error is a composition defect, not a wire event."
  [handle]
  (when-not (ifn? (:put-bytes-fn handle))
    (throw (ex-info "a content handle must expose a :put-bytes-fn"
                    {:handle handle})))
  (when-not (ifn? (:get-bytes-fn handle))
    (throw (ex-info "a content handle must expose a :get-bytes-fn"
                    {:handle handle})))
  handle)


(defn- get-request?
  "True only for the exact read request {:jing/request r :jing/get a}."
  [v]
  (and (map? v) (= #{:jing/request :jing/get} (set (keys v)))))


(defn- put-request?
  "True only for the exact write request {:jing/request r :jing/put a
   :jing/bytes b64}."
  [v]
  (and (map? v)
       (= #{:jing/request :jing/put :jing/bytes} (set (keys v)))))


(defn- append-answer!
  "Deliver one answer value; the writer's refusal loses that one answer,
   exactly as any transport loses a message -- the caller's own retry or
   timeout policy owns the loss, never the interpreter."
  [answers value]
  (stream/append! answers value)
  nil)


(defn- serve-one!
  "Answer one well-formed request, or drop it as malformed wire input:
   a value that is neither an exact read nor an exact write, a read of a
   non-address, and a write whose bytes fail the ingress check are all
   dropped -- no answer, the cursor advances past them, and the caller's
   outstanding request owns the silence. A backend verdict outside
   #{:inserted :present} is a composition defect and throws here, before
   the cursor moves, so the same request is re-read once the backend is
   fixed (the observer's effect rule, docs/design/dao.jing.md)."
  [handle answers v]
  (let [r (get v :jing/request)
        missing #?(:clj (Object.) :cljs (js-obj) :cljd (Object.))]
    (cond
      (and (get-request? v) (jing/segment-address? (get v get-op)))
      (let [address (get v get-op)
            result ((:get-bytes-fn handle) address missing)]
        (if (identical? result missing)
          (append-answer! answers {:jing/request r, :jing/found? false,
                                   :jing/bytes nil})
          (append-answer! answers {:jing/request r, :jing/found? true,
                                   :jing/bytes (jing/bytes->base64
                                                 result)})))

      (and (put-request? v) (jing/segment-address? (get v put-op)))
      (let [address (get v put-op)
            verdict (if-some [bs (try (jing/accept-bytes!
                                        address (get v :jing/bytes))
                                      (catch #?(:cljd Object
                                                :clj Throwable
                                                :cljs :default)
                                             _
                                        nil))]
                      ((:put-bytes-fn handle) address bs)
                      ::refused)]
        (when-not (= ::refused verdict)
          (when-not (#{:inserted :present} verdict)
            (throw (ex-info "backend returned an invalid put result"
                            {:address address, :result verdict})))
          (append-answer! answers {:jing/request r, :jing/result verdict})))

      ;; anything else is malformed wire input, dropped below the step
      :else nil)))


(defn serve-step
  "One non-waiting advance of the content interpreter: answer at most
   `budget` requests read from `requests` at `cursor`, appending each
   answer to `answers`, and return the successor cursor. Like its
   sibling the mirror step, this holds no state between calls beyond the
   reader's cursor, and a blocked read stops the step. This is an
   interpreter step -- it performs stream operations -- under a
   single-owner precondition: one caller, one cursor thread."
  [handle requests cursor answers budget]
  (valid-handle! handle)
  (loop [cursor cursor
         left budget]
    (if (pos? left)
      (let [r (stream/next requests cursor)]
        (if (= :dao.stream/ok (:dao.stream/outcome r))
          (do (serve-one! handle answers (:dao.stream/value r))
              (recur (:dao.stream/cursor r) (dec left)))
          cursor))
      cursor)))

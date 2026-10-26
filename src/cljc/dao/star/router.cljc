(ns dao.star.router
  "The dao.star Mode 1 router (docs/design/dao.star.md, Routing and Sessions
   and request kinds).

   A stepped interpreter: each pass reads the ingress with its own cursor to
   `blocked`, then drains the session-facts and lineage-lease cursors to
   `blocked` folding them into the index, and only then classifies what the
   ingress gave it.  Unknown is judged after both drains, never before, which
   is what makes a just-published token impossible to answer as begin.

   The router holds no token table of its own: the index is derived from the
   two media it reads, and it forwards a request to the medium of the session
   its token names, with every later terminal event of that exchange.  The
   boundary never routes, and a gap forwards nothing."
  (:require [dao.star.facts :as facts]
            [dao.star.lease :as lease]
            [dao.stream :as stream]
            #?(:clj [dao.stream.remote]))
  #?(:clj (:import [dao.stream.remote ReflectionHandle])))


(defn- reflection?
  [handle]
  #?(:clj (instance? ReflectionHandle handle)
     :default (and false handle)))


(defn classify
  "Pure: the kind of request `(method, path)` under `index`.
     {:kind :begin}                         `/`, or a token of an ended or
                                            unknown lineage
     {:kind :subscribe :lineage S}          `/s/<token>`, token live
     {:kind :input :lineage S :token t}     `/k/<token>`, token current
     {:kind :stale :lineage S :token t}     `/k/<token>`, token consumed
     {:kind :unrouted}                      any other path"
  [index path]
  (let [[_ head token] (re-matches #"/(s|k)/([^/]+)" (str path))
        tok (when head (facts/resolve-token index token))]
    (cond
      (= "/" path) {:kind :begin}
      (nil? head) {:kind :unrouted}
      (contains? #{:ended :unknown} (:kind tok)) {:kind :begin}
      (= "s" head) {:kind :subscribe :lineage (:lineage tok) :token token}
      (= :current (:kind tok)) {:kind :input :lineage (:lineage tok) :token token}
      :else {:kind :stale :lineage (:lineage tok) :token token})))


(defn make-router
  "Assemble the router.  Config:
     :ingress        the boundary's ingress medium (may be a reflection)
     :facts          the session-facts medium, a direct handle
     :lease          the lineage-lease medium, a direct handle
     :begin          the begin handler's medium (handle)
     :session-medium (fn [lineage] -> handle or nil)
     :facts-declaration  the facts medium's declaration, `:retention :complete`
   The facts and lease handles are classification media: a reflection answers
   `blocked` while its request is pending, which says nothing about the source,
   so the router refuses at assembly (ex-info `:refused :co-located`) unless
   both are direct handles declared `:co-located? true`."
  [{:keys [ingress facts lease begin session-medium co-located?] :as config}]
  (lease/check-facts-declaration! (:facts-declaration config))
  (when-not (and co-located?
                 (not (reflection? facts))
                 (not (reflection? lease)))
    (throw (ex-info "the router reads its classification media directly: declare :co-located? true with direct (non-reflection) handles"
                    {:refused :co-located})))
  (when-not (and ingress facts lease begin (fn? session-medium))
    (throw (ex-info "the router needs :ingress :facts :lease :begin :session-medium"
                    {:refused :config :config (dissoc config :ingress :facts :lease :begin)})))
  (atom {:config config
         :ingress-cursor (lease/oldest-cursor ingress)
         :facts-cursor (lease/oldest-cursor facts)
         :lease-cursor (lease/oldest-cursor lease)
         :index facts/empty-index
         :routes {}
         :gaps 0}))


(defn index
  [router]
  (:index @router))


(defn- forward!
  "Append `message` to `handle`; a non-ok answer is loss, dropped silently."
  [handle message]
  (when handle
    (= :dao.stream/ok (:dao.stream/outcome (stream/append! handle message)))))


(defn- route!
  [router event]
  (let [{:keys [config index]} @router
        x (:http/exchange event)]
    (case (:http/event event)
      :http/request
      (let [c (classify index (:http/path event))
            target (case (:kind c)
                     :begin (:begin config)
                     :unrouted nil
                     ((:session-medium config) (:lineage c)))]
        (when (and target
                   (forward! target {:exchange x :kind (:kind c) :token (:token c)
                                     :lineage (:lineage c) :event event}))
          (swap! router assoc-in [:routes x] target)))

      (:http/ended :http/closed)
      (when-let [target (get-in @router [:routes x])]
        (swap! router update :routes dissoc x)
        (forward! target {:exchange x :kind :terminal :event event}))

      nil)))


(defn router-step!
  "One pass, in the spec's order.  Answers the router."
  [router]
  (let [s @router
        {:keys [ingress facts lease]} (:config s)
        in (lease/drain ingress (:ingress-cursor s))
        fs (lease/drain facts (:facts-cursor s))
        ls (lease/drain lease (:lease-cursor s))]
    (swap! router
           (fn [s]
             (-> s
                 (assoc :ingress-cursor (:cursor in)
                        :facts-cursor (:cursor fs)
                        :lease-cursor (:cursor ls))
                 (update :gaps + (if (:gap? in) 1 0))
                 (update :index #(reduce facts/index-step % (:values fs)))
                 (update :index #(reduce facts/index-step % (:values ls))))))
    (doseq [event (:values in)
            :when (map? event)]
      (route! router event))
    router))

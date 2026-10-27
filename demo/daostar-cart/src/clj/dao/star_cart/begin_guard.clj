(ns dao.star-cart.begin-guard
  "The begin guard: a host interpreter between the router's begin medium and
   the driver's.  A begin whose request is SSE-shaped — its path is /s/… or
   /k/…, i.e. a Datastar subscribe or input naming a token this composition
   no longer knows (a restart happened, or the lineage lapsed) — is answered
   HERE with an event-stream response carrying a `Session expired` patch of
   the app body, so the browser's SSE reader gets something it can render
   instead of the begin HTML document it would retry on forever.  True begins
   (path /) are forwarded to the driver untouched."
  (:require [clojure.string :as str]
            [dao.star.lease :as lease]
            [dao.stream :as stream]))


(defn- shaped-sse?
  [event]
  (let [p (str (:http/path event))]
    (or (str/starts-with? p "/s/") (str/starts-with? p "/k/"))))


(defn- expired-effects
  [x app-id]
  [{:http/exchange x :http/effect :http/head :http/status 200
    :http/headers {"content-type" "text/event-stream"}}
   {:http/exchange x :http/effect :http/chunk
    :http/body (str "event: datastar-patch-elements\ndata: elements <div id=\""
                    app-id "\"><p class=\"expired\">Session expired. "
                    "<a href=\"/\">Continue</a></p></div>\n\n")}
   {:http/exchange x :http/effect :http/end}])


(defn make
  "Guard state over `router-begin` (what the router forwards) and
   `driver-begin` (what the driver reads)."
  [router-begin driver-begin]
  (atom {:router-begin router-begin :driver-begin driver-begin
         :cursor (lease/oldest-cursor router-begin)}))


(defn step!
  "One pass: drain the router's begin medium; answer SSE-shaped stale begins
   with the expired patch (effects appended to `egress`), forward the rest.
   Answers the guard."
  [g egress app-id]
  (let [{:keys [router-begin driver-begin cursor]} @g
        r (lease/drain router-begin cursor)]
    (swap! g assoc :cursor (:cursor r))
    (doseq [m (:values r)
            :when (map? m)]
      (if (shaped-sse? (:event m))
        (doseq [e (expired-effects (:exchange m) app-id)]
          (stream/append! egress e))
        (stream/append! driver-begin m))))
  g)

(ns yin.shell.session
  "The composition surface outside `yin.repl`
   (docs/design/yin.repl.dht-composition.md sections 1, 3 and 7).

   A session is the engine value beside the transport state the engine
   never owns and never steps: `{:engine <the yin.repl state> :node <the
   transport's step-owner value, or nil> :key <the publisher's stable key,
   or nil> :limits <the query bounds, or nil for the engine's>}`.  This
   namespace is where the composition-side adapters of section 7 meet the
   engine's public operations, so a host entry point drives one value
   instead of four and never reaches into the engine for a node.

   It owns no socket, atom, promise, callback, clock or namespace global
   either: every input is an argument, every outcome plain data, and the
   ordering contract of section 3 is the engine's and the publication
   medium's, not something restated here.  Store opening, HEAD
   persistence, announce, hydration, transport stepping and shutdown stay
   with the composition that supplies `:node` — this namespace only
   advances what it is handed."
  (:require [yin.repl :as repl]
            [yin.shell.completion :as completion]
            [yin.shell.linker :as shell.linker]
            [yin.shell.query :as shell.query]))


(defn make
  "A session over an `engine` and the transport state its composition
   owns: `node`, the transport's step-owner value or nil for a composition
   with no transport, and `publisher-key`, the publisher's stable key or
   nil for one that publishes nothing.  `limits` optionally bounds the
   query and name answers; nil takes the engine's own bounds.

   `:awaiting` is the service round's own state — the signed publications
   whose local completion a parked call still waits on
   (`yin.shell.query/serve-services`) — and starts empty: the composition
   carries it across ticks exactly as it carries the node, and the engine
   never sees it."
  ([engine] (make engine nil nil nil))
  ([engine node] (make engine node nil nil))
  ([engine node publisher-key] (make engine node publisher-key nil))
  ([engine node publisher-key limits]
   {:engine engine
    :node node
    :key publisher-key
    :limits limits
    :awaiting {}}))


(defn transport-state
  "The session's transport half: everything a composition carries across
   ticks apart from the engine — its `node`, the publisher's key, the query
   bounds and the service round's own `:awaiting` correlations.

   A host entry point keeps this beside the engine value its driver reads,
   and joins the two into `make`'s shape for each composition round
   (`yin.repl.main/session-of`), so a node never lives inside the engine map
   and no composition function has to guess which key hides one.  It is
   `make`'s answer minus `:engine`, and nothing else."
  ([node] (transport-state node nil nil))
  ([node publisher-key] (transport-state node publisher-key nil))
  ([node publisher-key limits]
   {:node node
    :key publisher-key
    :limits limits
    :awaiting {}}))


(defn serve
  "One composition round over both media the engine parks on: the link
  request media (`yin.shell.linker/serve-links`) and the query call media
  (`yin.shell.query/serve-services`).  Answers `{:session s' :progress?
  bool}`, `:progress?` true when either round moved something — the fact a
  host's ticker spends another round on, and the fact a parked run is
  resumed on.

  The linker round runs first and its successor node is the one the
  service round reads, so a load a link started this round is visible to a
  `dao.space.dht` call answered in it.  The service round's publication
  correlations are carried on `:awaiting`, so a signed publish parked on
  its local publication completion is answered by a later tick and never
  committed twice.  `budget` bounds each round and defaults to each
  adapter's own."
  ([session] (serve session nil))
  ([{:keys [engine node] :as session} budget]
   (let [linked (if budget
                  (shell.linker/serve-links
                    (shell.linker/from-engine engine node) budget)
                  (shell.linker/serve-links
                    (shell.linker/from-engine engine node)))
         engine (cond-> (shell.linker/install engine (:composition linked))
                  (and (:pending-run engine) (seq (:pending linked)))
                  (assoc-in [:pending-run :links] (:pending linked)))
         served-compose {:engine engine
                         :node (:node (:composition linked))
                         :key (:key session)
                         :limits (:limits session)
                         :awaiting (:awaiting session)}
         served (if budget
                  (shell.query/serve-services served-compose budget)
                  (shell.query/serve-services served-compose))]
     {:session (assoc session
                      :engine (:engine (:composition served))
                      :node (:node (:composition served))
                      :awaiting (:awaiting (:composition served)))
      :progress? (boolean (or (:progress? linked) (:progress? served)))})))


(defn recheck-on-tick
  "One host tick: serve both media, then let the engine re-check a run it
  has parked.  Answers `[session' text]` exactly as the engine's own
  `yin.repl/recheck-on-tick` does.

  `events` are the composition's own transport and follower events for the
  tick.  They are translated before they cross
  (`yin.shell.completion/completion-events`), so the engine sees only the
  generic completions correlated to the work it parked and never a
  transport keyword.  A round that made progress resumes the run with
  that progress carried into the driver (`yin.repl/recheck-pending`'s
  three-argument arity), so the parked VM runs at once: the composition
  has consumed the request and delivered its response, and no further
  round of internal progress need occur first.  A round that made none
  leaves the resume to a matching completion."
  ([session events] (recheck-on-tick session events nil))
  ([session events budget]
   (let [served (serve session budget)
         session (:session served)
         engine (:engine session)
         [engine' text]
         (if (:progress? served)
           (repl/recheck-pending engine nil true)
           (repl/recheck-on-tick engine
                                 (completion/completion-events session
                                                               events)))]
     [(assoc session :engine engine') text])))


(defn recheck-pending
  "One re-check of the parked run without a tick's events — the step an
  unattended host drives at its own cadence.  Answers `[session' text]`,
  `text` nil when nothing is pending.

  `progress` is the readiness a composition carries into the driver: true
  when it has already answered a request the parked run waits on — a
  publication result it delivered, a service response it appended — so
  the engine runs the VM at once instead of requiring another serving
  round to make progress first
  (docs/design/yin.repl.dht-composition.md section 7).  It defaults to
  false, a re-check of a run nothing has moved."
  ([session] (recheck-pending session false))
  ([session progress]
   (let [[engine text] (repl/recheck-pending (:engine session) nil progress)]
     [(assoc session :engine engine) text])))


(defn recheck-after-input
  "Serve requests made by newly evaluated input and report its parked run."
  [session]
  (let [{session :session progress? :progress?} (serve session)]
    (recheck-pending session progress?)))


(defn settle-publication
  "`session` after the composition's local publication service has been
  stepped and every result it delivered consumed.  A composition that
  services its own medium over transport of its own has no local runner,
  and this is then the settle alone: it calls it once it has delivered,
  before it reports the round settled
  (docs/design/yin.repl.dht-composition.md sections 3-4)."
  [session]
  (update session :engine
          (fn [engine]
            (repl/settle-publication (repl/serve-publication engine)))))

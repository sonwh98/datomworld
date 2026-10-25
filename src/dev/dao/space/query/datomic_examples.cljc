(ns dao.space.query.datomic-examples
  "Consumer examples for `dao.space.query.datomic/q`: the five web-app
   workload classes of docs/design/dao.space.query.datomic-compat.md,
   each over an in-memory current view. Examples only: nothing here adds
   public API, and every function builds its own small data.

   The sources are explicit materialized values (`core/current` over a
   relation of d5 rows), which is what a bounded call admits. A deployment
   composes its own snapshot into such a value outside the query's
   deadline; a published index is refused by bounded calls until the
   checked-traversal phase lands.

   Bounds shown: `:timeout` (milliseconds, a cooperative deadline),
   `:dao.space.query.datomic/max-steps` (an explicit work allowance that
   the composition supplies alongside the deadline), `:offset`/`:limit`
   (an unordered slice of the finished rows, NOT stable ordered paging and
   NOT a bound on the work done to build them)."
  (:require [dao.space.query :as core]
            [dao.space.query.datomic :as dq]))


(def ^:private d5-rows
  "d5 rows [e a v t m] (m 1 asserts): three people, two notes per session."
  [[1 :person/name "Ada" 1 1] [1 :person/age 37 1 1] [1 :person/email "a@x" 1 1]
   [2 :person/name "Grace" 1 1] [2 :person/age 45 1 1]
   [3 :person/name "Linus" 1 1] [3 :person/age 37 1 1]
   [10 :session/id "s1" 1 1] [11 :session/id "s2" 1 1]
   [100 :note/session 10 1 1] [100 :note/text "alpha" 1 1]
   [101 :note/session 10 1 1] [101 :note/text "beta" 1 1]
   [102 :note/session 11 1 1] [102 :note/text "gamma" 1 1]])


(defn view
  "The explicit current view the examples query."
  []
  (core/current (core/relation d5-rows)))


(defn entity-view
  "Workload 1, entity views: a finite pull projection for a detail panel,
   with a missing attribute omitted and a default supplied. `:db/id` is
   included; the result is plain data ready to project into HTML."
  []
  (dq/q '[:find (pull ?e [:person/name :person/email [:person/age :default 0]])
          :where [?e :person/name "Ada"]]
        (view)))


(defn list-surface
  "Workload 2, list surfaces: a filtered current-fact query with a slice,
   a deadline and a work allowance. The slice is unordered; a stable order
   is the application's policy, established outside this call."
  []
  (dq/q {:query '[:find ?e ?name
                  :where [?e :person/name ?name] [?e :person/age 37]]
         :args [(view)]
         :offset 0
         :limit 10
         :timeout 250
         :dao.space.query.datomic/max-steps 100000}))


(defn session-scoped-read
  "Workload 3, session-scoped reads: the session id is an ordinary scalar
   argument, so two logical sessions over one view stay isolated."
  [session-id]
  (dq/q '[:find ?text
          :in $ ?sid
          :where [?s :session/id ?sid] [?n :note/session ?s] [?n :note/text ?text]]
        (view) session-id))


(defn view-summary
  "Workload 4, view summaries: deterministic counts, grouped."
  []
  (dq/q '[:find ?s (count ?n)
          :where [?n :note/session ?s]]
        (view)))


(defn bounded-failure
  "Workload 5, bounded failure: an exhausted allowance is a structured
   error, never a partial relation. Returns a small map the application
   can project into an HTTP response WITHOUT serializing the exception.
   Only the discriminator and the minimal context are used; the session's
   facts are untouched."
  []
  (try
    {:ok (dq/q {:query '[:find ?e :where [?e :person/name _]]
                :args [(view)]
                :dao.space.query.datomic/max-steps 3})}
    (catch #?(:cljd Object :clj Throwable :cljs :default) e
      (let [data (ex-data e)]
        {:error (:dao.space.query.datomic/error data)
         :category (:cognitect.anomalies/category data)
         :phase (:phase data)}))))

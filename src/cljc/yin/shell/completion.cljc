(ns yin.shell.completion
  "Event filtering, composition-side
   (docs/design/yin.repl.dht-composition.md section 7).

   A transport step answers events in its own vocabulary.  The engine may
   see none of it: `completion-events` translates the two outcomes a parked
   `(require ...)` can wait on into correlated generic completions, keyed
   by the identity the engine already holds for the parked work — the
   manifest address of a load, the principal id of a followed head.  An
   event that names neither is dropped here rather than forwarded, so the
   engine re-checks only matching parked work, ignores everything else,
   and resumes each completion once.

   The two generic shapes the engine's `yin.repl/recheck-on-tick` reads
   are `{:op :dependency :manifest address :status :loaded|:load-failed}`
   and `{:op :principal :principal id :status :installed}`.  Both are
   plain data and neither names a backend; this namespace is the only
   place on either side of the seam that knows which keyword a load or a
   head install arrived under.")


(def load-outcomes
  "The transport load outcomes that end a wait: a closure that arrived and
   one that failed are both completions, and a parked require re-checks on
   either — the refusal it then reads is the link's, not the event's."
  #{:loaded :load-failed})


(defn dependency-event
  "The generic completion for one transport load outcome `event`, or nil
   for an event that is not a load ending."
  [event]
  (let [status (:dao.space.dht/event event)]
    (when (contains? load-outcomes status)
      {:op :dependency
       :manifest (:manifest event)
       :status status})))


(defn principal-event
  "The generic completion for one follower outcome `event`, or nil for an
   event that is not a head installed.  A head that moved or failed is not
   a completion: nothing parked is waiting to be told about it, and a name
   the move displaced is re-resolved on the round that re-checks."
  [event]
  (when (= :installed (:yin.head/event event))
    {:op :principal
     :principal (:principal event)
     :status :installed}))


(defn completion-events
  "`events` — the transport and follower events one composition step
  produced — as the vector of generic completions the engine may see, in
  the order they arrived.  `composition` is the composition value, so a
  translation that must consult what the composition follows or has
  loaded can; the two translations here are keyed by the event alone.

  An event that is neither a load ending nor a head install contributes
  nothing, so a step's banners, acknowledgements and refusals never cross
  inward."
  ([events] (completion-events nil events))
  ([_composition events]
   (into []
         (keep (fn [event]
                 (or (dependency-event event)
                     (principal-event event))))
         events)))

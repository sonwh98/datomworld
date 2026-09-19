(ns dao.stream.waitset
  "The multiplexed wait-set for DaoStream: one complete ordered sweep over
  every parked waiter, as a host-agnostic library.

  The waitset is a value, `{:waiting []}`, and it holds only what waits.
  `check` takes it beside a host-composed resolver and the host's store,
  performs one synchronous interpreter step — polling live media, appending
  for a parked writer — and returns the successor waitset beside that
  call's results. The sweep has explicit stream effects, so it is never
  described as pure; what is immutable is the threading of the store and
  the waitset.

  Entries are the host's data. The library interprets only `:reason` —
  `:next` reads, `:put` appends — and every other field is opaque, stored
  and returned exactly as the host gave it. A resolver maps each whole
  entry to a live handle and the cursor to read from or the value to
  append, and moves a reader's cursor in the store; both functions run
  immediately inside `check` and are never retained, and the library never
  adds a resolved handle or polling cursor to any entry.

  Woken entries are returned, never dispatched: no callbacks, no queues,
  no disposition functions. This namespace multiplexes readiness; it does
  not schedule work — the host loop owns everything between calls."
  (:require [dao.stream :as stream]))


(def ^:private wait-reasons
  "The reasons the library interprets. Only two outcomes can change on
   their own: `blocked` for a reader, `full` for a writer."
  #{:next :put})


(defn empty-waitset
  "The waitset that parks nothing. This value is the whole threaded state."
  []
  {:waiting []})


(defn park
  "Append `entry` to `waitset`'s `:waiting` and return the successor
   waitset. Total: it rejects nothing, because `check` classifies
   everything — a malformed entry is woken by the sweep as a diagnostic,
   never a park-time error. Total over the waitset it is handed too: a
   missing or nil `:waiting` parks onto a fresh vector, so park order —
   wake order among co-waiters — is preserved whatever the host threaded."
  [waitset entry]
  (update waitset :waiting (fnil conj []) entry))


(defn- woken-result
  "The result map for one woken entry: the stored entry verbatim beside
   the status its poll resolved with. `cursor` is present only when the
   poll produced one — a reader's successor or a gap's recovery position;
   a writer resolves with no cursor."
  [entry status value cursor]
  (cond-> {:entry entry :status status :value value}
    cursor (assoc :cursor cursor)))


(defn- diagnostic-result
  "The result map for an entry the library cannot poll at all: the stored
   entry verbatim and its qualified status. No stream operation was
   performed, so there is no value."
  [entry status]
  {:entry entry :status status})


(defn- answer-defect
  "Why `answer` cannot be interpreted as an outcome of `op`, or nil when it
   can, using the contract's own validation. `:invalid` marks an answer
   that is not an outcome map the contract can vouch for — a non-map, a
   missing or unqualified `:dao.stream/outcome`, or a declared outcome
   missing its required keys — which folds into the waitset's own
   diagnostic. `:unauthorized` marks a well-formed outcome outside the
   operation's declared set, which stays terminal under its own keyword
   (the plan's rule for undeclared outcomes) and is distinguished here so
   it does not collide with the fold."
  [op answer]
  (let [defect (stream/validate-outcome op answer)]
    (cond
      (nil? defect) nil
      (= :unauthorized-outcome (:error defect)) :unauthorized
      :else :invalid)))


(defn- poll-next
  "Poll one resolved `:next` entry with a synchronous read. Returns nil
   while the entry must keep waiting — `blocked` is the only reader
   outcome that can change on its own — and otherwise the woken result
   plus `:advance-cursor`, the position the store must move to before
   later entries resolve: the successor on `ok`, the recovery position on
   `gap`. `end` resolves as the immediate path yields it, and every other
   outcome — cursor-mismatch, invalid-cursor, transport-error, and
   anything undeclared — is terminal under its own keyword.

   An answer that is not an outcome map the contract can vouch for — a
   non-map, a missing or unqualified outcome, a declared outcome missing
   its required keys — folds into the qualified terminal status
   `:dao.stream.waitset/invalid-answer` instead of waking as an
   unnameable `:status nil`. A poll that throws — a `:stream` that fails
   its protocol, a defective transport — folds into the same diagnostic:
   the entry cannot be trusted to poll again, and an uninterpretable
   answer is terminal data, never an exception and never a wait."
  [entry resolved]
  (try
    (let [result (stream/next (:stream resolved) (:cursor resolved))
          o (:dao.stream/outcome result)]
      (if (= :invalid (answer-defect :next result))
        {:wake (diagnostic-result entry :dao.stream.waitset/invalid-answer)}
        (case o
          :dao.stream/ok
          (let [cursor (:dao.stream/cursor result)]
            {:wake (woken-result entry :ok (:dao.stream/value result) cursor)
             :advance-cursor cursor})

          :dao.stream/blocked nil

          :dao.stream/end
          {:wake (woken-result entry :end nil nil)}

          :dao.stream/gap
          (let [cursor (:dao.stream/cursor result)]
            {:wake (woken-result entry :dao.stream/gap :dao.stream/gap cursor)
             :advance-cursor cursor})

          ;; Terminal under its own keyword, never a wait: waiting on an
          ;; answer the library cannot interpret would spin.
          {:wake (woken-result entry o o nil)})))
    (catch #?(:cljd Object :clj Throwable :cljs :default) _
      {:wake (diagnostic-result entry :dao.stream.waitset/invalid-answer)})))


(defn- poll-put
  "Poll one resolved `:put` entry by appending — the append is an effect
   of this poll, and `check` returns the state that records it. Returns
   nil while the entry must keep waiting — `full` is the only writer
   outcome that can change on its own — and otherwise the woken result.
   `ok` resolves with the appended value and no cursor; `closed` is a
   terminal append failure under its own keyword, never a reader's `end`;
   every other outcome, declared or not, is terminal under its own
   keyword.

   A resolution with no `:value` appends nil: the transport is the one
   that answers `invalid-value`, and the engine reads its `:datom` the
   same loose way. An answer that is not an outcome map, and a poll that
   throws — a `:stream` failing its writer protocol, a defective
   transport — fold into `:dao.stream.waitset/invalid-answer`, exactly as
   the reader's poll does: the entry cannot be trusted to poll again."
  [entry resolved]
  (try
    (let [value (:value resolved)
          result (stream/append! (:stream resolved) value)
          o (:dao.stream/outcome result)]
      (if (= :invalid (answer-defect :append! result))
        {:wake (diagnostic-result entry :dao.stream.waitset/invalid-answer)}
        (case o
          :dao.stream/ok {:wake (woken-result entry :ok value nil)}
          :dao.stream/full nil
          {:wake (woken-result entry o o nil)})))
    (catch #?(:cljd Object :clj Throwable :cljs :default) _
      {:wake (diagnostic-result entry :dao.stream.waitset/invalid-answer)})))


(defn check
  "Run one complete ordered sweep over `waitset`'s entries: a synchronous,
  state-threaded interpreter step with explicit stream effects — it calls
  `next` on live media and a parked writer's `append!` — so it is never
  described as pure.

  `resolver` is host composition, two synchronous functions over whole
  entries:

    {:resolve (fn [store entry] ...)          ; => {:stream h :cursor c :value v} or nil
     :advance (fn [store entry cursor] ...)}  ; => store'

  `:resolve` maps an entry to its live handle plus the cursor to read from
  (`:next`) or the value to append (`:put`), or answers nil when it
  cannot. `:advance` returns the store with that entry's reader cursor
  moved to `cursor`. Both are invoked immediately, per entry, and never
  retained — the shape of `reduce`'s `f`, not an event callback. The store
  is opaque to this library: it is never dereferenced here, only passed
  through.

  Returns

    {:waitset {:waiting [...]}   ; thread this into the next check
     :woken   [...]              ; this call's results only; host-owned
     :store   store'}            ; the host commits this

  Every entry is polled every call, one at a time in wait-set order — no
  budget, no scan position. A still-waiting entry (`blocked` for a reader,
  `full` for a writer) is retained exactly as stored. A resolved entry
  leaves `:waiting` and appears in `:woken` as

    {:entry <the stored entry> :status ... :value ... :cursor ...}

  where `:cursor` is present only for a reader whose poll returned one.
  The library never adds a resolved handle or polling cursor to any entry
  it retains or returns, and `:woken` never accumulates: check the
  returned `:waitset` again and it answers only what moved since.

  Two laws are preserved from the engine this sweep generalizes.
  Shared-cursor write-back: a woken reader's successor — or recovery
  cursor, on gap — is advanced in the store before later entries resolve,
  so co-waiters on one cursor read distinct values and a co-waiter behind
  a gap reads from the recovery position; wait-set order is therefore wake
  order, and nothing reorders `:waiting`. A parked writer retries by
  appending: the append is an effect of the poll, so no caller may drop a
  returned `:waitset` or `:store`.

  Classification is total, per operation. For a `:next` entry only
  `blocked` waits: `ok` resolves with the value and its successor, `end`
  as `{:value nil :status :end}`, `gap` with its recovery cursor, and
  cursor-mismatch, invalid-cursor and transport-error under their own
  qualified keywords. For a `:put` entry only `full` waits: `ok` resolves
  with the appended value and no cursor; closed, invalid-value and
  transport-error are terminal append failures under their own keywords —
  writer `closed` is never a reader's `end`. Any outcome outside the
  operation's declared set is terminal under its own keyword, never a
  wait.

  Three diagnostics are the library's own, and they also never wait: an
  entry whose `:resolve` answers nil or without a `:stream` wakes with
  `:status :dao.stream.waitset/unresolved`; an entry whose `:reason` is
  neither `:next` nor `:put` wakes with
  `:status :dao.stream.waitset/unsupported-reason`; and a poll that
  answers something other than an outcome map — or throws, because its
  handle fails the protocol it was resolved to — wakes with
  `:status :dao.stream.waitset/invalid-answer`, the entry no longer
  being one that can be trusted to poll. In every case no `:advance` is
  called, the store is unchanged, the original entry is returned intact,
  and the entry leaves `:waiting`.

  Resolvers must not throw. A resolver throw voids the sweep: `check`
  does not return, and a caller that re-threads its previous waitset
  into a later call re-polls every entry — re-appending writers that
  already appended. The engine's sweep behaves identically, and this is
  stated as the contract on resolvers rather than caught: host
  composition failing is a host defect the library cannot name, and
  wrapping it would hide it inside a diagnostic."
  [waitset resolver store]
  (let [{resolve-fn :resolve, advance-fn :advance} resolver]
    (loop [remaining (:waiting waitset)
           store store
           waiting []
           woken []]
      (if (seq remaining)
        (let [entry (first remaining)
              reason (:reason entry)]
          (if (contains? wait-reasons reason)
            (let [resolved (resolve-fn store entry)]
              (if (and (map? resolved) (:stream resolved))
                (if-some [{:keys [wake advance-cursor]}
                          (case reason
                            :next (poll-next entry resolved)
                            :put (poll-put entry resolved))]
                  (recur (rest remaining)
                         (cond-> store
                           advance-cursor (advance-fn entry advance-cursor))
                         waiting
                         (conj woken wake))
                  (recur (rest remaining)
                         store
                         (conj waiting entry)
                         woken))
                (recur (rest remaining)
                       store
                       waiting
                       (conj woken (diagnostic-result
                                     entry
                                     :dao.stream.waitset/unresolved)))))
            (recur (rest remaining)
                   store
                   waiting
                   (conj woken (diagnostic-result
                                 entry
                                 :dao.stream.waitset/unsupported-reason)))))
        {:waitset {:waiting waiting}
         :woken woken
         :store store}))))

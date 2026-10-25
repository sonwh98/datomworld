(ns yang.core.claims
  "The claims appender protocol (docs/design/yang.typescript.md §12.7, lift
   L3). Every claims producer appends a root's claims through
   `append-claims!`, which first makes sure the root has an evidence record:
   a ledger datom holding a stream-minted cursor taken before the first claim
   about the root, so a later recheck replays from it. This namespace holds
   the protocol; the ledger is the composition's, injected as a map."
  (:require
    [dao.stream :as stream]))


(defn evidence-record
  "The evidence record of `root` on the claims medium: its logical-stream
   identity and a cursor minted by the caller."
  [stream-identity cursor root]
  {:yang.claims/stream stream-identity,
   :yang.claims/cursor cursor,
   :yang.claims/root root})


(defn evidence-datom
  "The ledger datom `[root :yang.claims/evidence record]`."
  [record]
  [(:yang.claims/root record) :yang.claims/evidence record])


(defn- ok?
  [outcome]
  (= :dao.stream/ok (:dao.stream/outcome outcome)))


(defn- ensure-evidence
  "`{:evidence record}` for `root`, minting and recording one when the ledger
   has none, else `{:refused reason :outcome o}`."
  [claims-stream {:keys [find-evidence record-evidence!]} root]
  (if-let [existing (find-evidence root)]
    {:evidence existing}
    (let [c (stream/cursor claims-stream stream/anchor-newest)]
      (if (ok? c)
        (let [id (:dao.stream/identity (stream/descriptor claims-stream))
              record (evidence-record id (:dao.stream/cursor c) root)]
          (record-evidence! (evidence-datom record))
          {:evidence record})
        {:refused :yang.claims/cursor-unavailable, :outcome c}))))


(defn append-claims!
  "Append `claims` about `root` to the claims medium `claims-stream`.
   `ledger` is `{:find-evidence (fn [root] record-or-nil)
                 :record-evidence! (fn [datom] ...)}`. When `root` has no
   evidence record, mint the `:dao.stream/newest` cursor, record the evidence
   datom, then append the claims; otherwise just append them. Minting
   precedes the first claim, so the cursor is at or before it. Answers
   `{:status :ok :evidence record :appended n}`, or
   `{:status :refused :reason r :outcome o}` on the first failure."
  [claims-stream ledger root claims]
  (let [{:keys [evidence refused outcome]} (ensure-evidence claims-stream ledger root)]
    (if refused
      {:status :refused, :reason refused, :outcome outcome}
      (reduce (fn [acc claim]
                (let [r (stream/append! claims-stream claim)]
                  (if (ok? r)
                    (update acc :appended inc)
                    (reduced {:status :refused,
                              :reason :yang.claims/append-failed,
                              :outcome r}))))
              {:status :ok, :evidence evidence, :appended 0}
              claims))))

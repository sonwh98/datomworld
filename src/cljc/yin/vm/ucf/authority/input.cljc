(ns yin.vm.ucf.authority.input
  "The input protocol of M-next C slice C10: durable input records and
   their replay (UCF 7.7.5, 7.7.8, 7.11.1 fenced-custody clause 3;
   docs/design/yin.vm.linker.dht.md 14.2.2 and 14.2.3 step 7).

   A holder records every nondeterministic input its task observes: a
   stream read, an FFI result or a link result.  A request is

     {:yin.k/occurrence o :dao.lease/lease l :yin.k/epoch e
      :yin.k/input-seq k :yin.k/source s :yin.k/observed v}

   attributed to the holder by the composition's resolver.  `s` names
   the stream or call and the kind, `{:yin.k/kind kind :yin.k/name n}`
   with kind one of `source-kinds`; `v` is the outcome as data, a gap
   and its successor cursor included.  `record-input!` commits it as one
   `:yin.k/input` fact in one authority transition.

   Ordering.  Each root occurrence has one input sequence, dense in k
   from 0, shared by every tenure of the occurrence.  Install children
   have no sequence of their own: they draw from the root's, in the
   order the driver delivered their inputs.  A k past the next one is a
   gap and is refused; the ledger fold refuses a non-dense k too.

   Acknowledgment.  `:recorded` is answered only after the durable
   commit.  Equal content at a recorded k answers `:replayed`; other
   content is refused `:input-conflict` and commits nothing.  The
   tenure check runs first: an exhausted occurrence answers
   `:suspended`, a lease with no binding on the occurrence or a request
   not attributed to its bound holder is refused, and a lapsed lease or
   a wrong epoch is `:stale`, even for a k already recorded.
   A quarantined occurrence still records inputs: UCF 7.7.8 suspends
   admissions on a quarantine, and an input is evidence, not an effect.

   Frontier.  A lease's frontier is the count of input records of its
   occurrence at the lease's grant transaction.  A regranted holder
   replays records 0 to frontier - 1 in order (`inputs`,
   `replay-input`), checks each source, and fails closed on a mismatch.
   At the frontier the prefix has ended; live observation resumes and
   is recorded from there.  The prefix never extends past the frontier,
   so a holder's own later records are not replayed to it, and an empty
   prefix (frontier 0) is distinct from missing evidence, which fails
   closed.

   The rule for D.  The driver delivers an input to the task only after
   `record-input!` answers `:recorded` or `:replayed`, or `replay-input`
   answers `:replayed`.  The authority cannot see dependence: a driver
   that uses an unrecorded input is caught only later, by an intent
   conflict at admission."
  (:require [dao.jing.cbor :as cbor]
            [yin.vm.ucf.authority :as authority]
            [yin.vm.ucf.custody :as custody]
            [yin.vm.ucf.ledger :as ledger]))


(def source-kinds
  "The kinds of input a source names."
  #{:yin.k/read :yin.k/ffi-result :yin.k/link-result})


(def max-seq
  "The input sequence bound: the count of an occurrence's inputs stays
   at most 2^52-1, so the largest recordable k is 2^52-2."
  ledger/max-exact)


(def ^:private request-keys
  #{:yin.k/occurrence :dao.lease/lease :yin.k/epoch :yin.k/input-seq
    :yin.k/source :yin.k/observed})


(defn request
  "The input request for the k-th input of occurrence `o`, observed under
   lease `l` at epoch `e` from `source`."
  [o l e k source observed]
  {:yin.k/occurrence o
   :dao.lease/lease l
   :yin.k/epoch e
   :yin.k/input-seq k
   :yin.k/source source
   :yin.k/observed observed})


(defn- source?
  [s]
  (and (map? s)
       (= #{:yin.k/kind :yin.k/name} (set (keys s)))
       (contains? source-kinds (:yin.k/kind s))
       (some? (:yin.k/name s))))


(defn- portable?
  [x]
  (try (some? (cbor/encode x))
       (catch #?(:cljd Object :clj Throwable :cljs :default) _
         false)))


(defn- well-formed?
  [r]
  (and (map? r)
       (= request-keys (set (keys r)))
       (custody/occurrence? (:yin.k/occurrence r))
       (some? (:dao.lease/lease r))
       (custody/exact? (:yin.k/epoch r))
       (custody/exact? (:yin.k/input-seq r))
       (source? (:yin.k/source r))
       (some? (:yin.k/observed r))
       (portable? r)))


(defn- status
  ([s] {:yin.k/status s})
  ([s reason] {:yin.k/status s :yin.k/reason reason}))


(defn- tenure
  "The tenure answer for lease `l` at epoch `e`, attributed to `author`,
   on occurrence `o`, from the C5/C6 projection (grant, binding, epoch):
   nil when current.  Slice C7's admission shares this contract; merge
   the two helpers when both land."
  [p o l e author]
  (let [known (get-in p [:occurrences o])
        granted (get-in p [:leases l])
        live (:dao.lease/lease known)]
    (cond
      (not= o (:yin.k/occurrence granted)) (status :refused :unbound-lease)
      (not= author (:dao.lease/holder granted)) (status :refused :wrong-author)
      (not (and (= l live) (= e (:yin.k/epoch known))))
      (cond-> {:yin.k/status :stale :yin.k/observed-epoch (:yin.k/epoch known)}
        (some? live) (assoc :yin.k/observed-lease live))
      :else nil)))


(defn- content
  [source observed]
  {:yin.k/source source :yin.k/observed observed})


(defn- decision
  "Decide request `r` from `author` against projection p, with input
   sequence bound `bound`."
  [author r bound]
  (fn [p]
    (let [{o :yin.k/occurrence l :dao.lease/lease e :yin.k/epoch
           k :yin.k/input-seq s :yin.k/source v :yin.k/observed} r
          known (get-in p [:occurrences o])
          recorded (:yin.k/inputs known)
          n (count recorded)
          reply (fn [x] {::authority/reply x})]
      (cond
        (nil? known) (reply (status :refused :unknown-occurrence))
        (:yin.k/exhausted known) (reply (status :suspended :exhausted))
        :else
        (if-let [t (tenure p o l e author)]
          (reply t)
          (cond
            (< k n)
            (let [rec (nth recorded k)]
              (reply
                (if (and (cbor/content= s (:yin.k/source rec))
                         (cbor/content= v (:yin.k/observed rec)))
                  {:yin.k/status :replayed :yin.k/occurrence o
                   :yin.k/input-seq k}
                  {:yin.k/status :refused :yin.k/reason :input-conflict
                   :yin.k/input-seq k
                   :yin.k/recorded-input (content (:yin.k/source rec)
                                                  (:yin.k/observed rec))
                   :yin.k/observed-input (content s v)})))
            (> k n)
            (reply {:yin.k/status :refused :yin.k/reason :input-gap
                    :yin.k/input-seq k :yin.k/next-input-seq n})
            (> (inc k) bound) (reply (status :suspended :bound))
            :else
            {::authority/facts [{:yin.k/custody :yin.k/input
                                 :yin.k/occurrence o
                                 :dao.lease/lease l
                                 :yin.k/input-seq k
                                 :yin.k/source s
                                 :yin.k/observed v}]
             ::authority/reply {:yin.k/status :recorded :yin.k/occurrence o
                                :yin.k/input-seq k}}))))))


(defn record-input!
  "Record input request `r` (see `request`), attributed to `author`, on
   authority `a`.  `opts` may carry ::max-seq, a lower sequence bound
   for tests.  Answers

     {:yin.k/status :recorded :yin.k/occurrence o :yin.k/input-seq k
      :dao.space/t t}                     ; after the durable commit
     {:yin.k/status :replayed :yin.k/occurrence o :yin.k/input-seq k}
     {:yin.k/status :stale :yin.k/observed-epoch e
      :yin.k/observed-lease l}            ; the lease when one is live
     {:yin.k/status :refused :yin.k/reason r ...}
     {:yin.k/status :suspended :yin.k/reason r}

   with refusal reasons :malformed-request, :unknown-occurrence,
   :unbound-lease, :wrong-author, :input-conflict (carrying
   `:yin.k/recorded-input` and `:yin.k/observed-input`, each
   `{:yin.k/source s :yin.k/observed v}`) and :input-gap (carrying
   `:yin.k/next-input-seq`); and suspension reasons :exhausted, :bound
   (k + 1 past the sequence bound), or the authority's own :poisoned,
   :uncertain-append and :uninstalled.  A closed authority answers
   `{:yin.k/status :closed}`.  Only :recorded commits."
  ([a author r] (record-input! a author r nil))
  ([a author r opts]
   (if (well-formed? r)
     (authority/transition! a (decision author r (get opts ::max-seq max-seq)))
     (status :refused :malformed-request))))


(defn frontier
  "The frontier of lease `l` in projection `p`: the count of input
   records of its occurrence committed before its grant transaction.
   Nil when p is nil (a poisoned or closed authority) or records no
   grant of l."
  [p l]
  (when-let [granted (get-in p [:leases l])]
    (count (take-while #(< (:dao.space/t %) (:dao.space/t granted))
                       (get-in p [:occurrences (:yin.k/occurrence granted)
                                  :yin.k/inputs])))))


(defn inputs
  "The replay prefix of lease `l` in projection `p`, for a regranted
   holder:

     {:yin.k/occurrence o :dao.lease/lease l :yin.k/frontier n
      :yin.k/inputs [{:yin.k/input-seq k :yin.k/source s
                      :yin.k/observed v} ...]}   ; k from 0 to n - 1

   or `{:yin.k/status :suspended :yin.k/reason :unavailable}` when p is
   nil, and `{:yin.k/status :refused :yin.k/reason :unbound-lease}` when
   p records no grant of l.  Missing evidence is never an empty prefix."
  [p l]
  (let [granted (get-in p [:leases l])
        o (:yin.k/occurrence granted)]
    (cond
      (nil? p) (status :suspended :unavailable)
      (nil? granted) (status :refused :unbound-lease)
      :else
      (let [n (frontier p l)]
        {:yin.k/occurrence o
         :dao.lease/lease l
         :yin.k/frontier n
         :yin.k/inputs (into []
                             (comp (take n)
                                   (map-indexed
                                     (fn [k rec]
                                       {:yin.k/input-seq k
                                        :yin.k/source (:yin.k/source rec)
                                        :yin.k/observed
                                        (:yin.k/observed rec)})))
                             (get-in p [:occurrences o :yin.k/inputs]))}))))


(defn replay-input
  "The k-th input for a holder replaying `prefix`, an `inputs` answer,
   that asks it of `source`:

     {:yin.k/status :replayed :yin.k/input-seq k :yin.k/observed v}
     {:yin.k/status :refused :yin.k/reason :source-mismatch
      :yin.k/input-seq k :yin.k/recorded-source s
      :yin.k/observed-source source}     ; fail closed
     {:yin.k/status :live :yin.k/input-seq k}   ; at or past the frontier

   A prefix that carries a status (missing evidence) is answered as it
   is, and one without a frontier is refused :no-prefix: neither is
   ever live."
  [prefix k source]
  (let [n (get prefix :yin.k/frontier)]
    (cond
      (contains? prefix :yin.k/status) prefix
      (not (custody/exact? n)) (status :refused :no-prefix)
      (>= k n) {:yin.k/status :live :yin.k/input-seq k}
      :else
      (let [rec (nth (:yin.k/inputs prefix) k)]
        (if (cbor/content= source (:yin.k/source rec))
          {:yin.k/status :replayed :yin.k/input-seq k
           :yin.k/observed (:yin.k/observed rec)}
          {:yin.k/status :refused :yin.k/reason :source-mismatch
           :yin.k/input-seq k :yin.k/recorded-source (:yin.k/source rec)
           :yin.k/observed-source source})))))

(ns dao.stream.remote-meet-test
  "The meeting board convention of dao.stream.remote.md (section 4):
   a `:meet/here` posting carries the channel attachment identity M
   itself observed, never a claim the payload sent -- the whole basis
   of punching through a restricted NAT; a registration is a board
   record, not a lease; a meeting request past the bound is refused,
   present, never silent, at the gate and again at grant time; one
   interpreter pass handles a bounded fanout of requests, the cursor
   preserved for the next pass; `:meet/pair` grants a lease-governed
   relay pair and carries the complete grant to the holder on the
   board, the holder's renewal medium wired into the judge, so
   renewal rides the convention; the NAT proofs simulate: restricted
   NAT is outbound-only and the punch is a coordinated simultaneous
   send whose direct request-answer sequences are asserted, symmetric
   NAT refuses the direct path both ways and forces the relay
   exchange through the granted pair; a pair whose holder stops
   renewing answers not-found after duration plus tolerance, and a
   reconnect renews through the carried grant (docs/design/dao.lease.md)."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.lease :as lease]
            [dao.stream :as stream]
            [dao.stream.remote :as remote]
            [dao.stream.remote-meet :as meet]
            [dao.stream.remote-pair :as pair]
            [dao.stream.ringbuffer :as ringbuffer]))


;; =============================================================================
;; The toy: one channel per simulated peer, to M's one table
;; =============================================================================

(defn- ring
  [capacity]
  (:dao.stream/handle
    (ringbuffer/create! {:dao.stream/type :dao.stream/ringbuffer
                         :dao.stream.ringbuffer/capacity capacity})))


(defn- toy
  "One simulated peer's own channel to M, `channel-id` naming the wire
   channel descriptor -- not the reflexive address: M's own
   mirror-step observes the address as the identity of `ab`, the ring
   buffer it reads this peer's requests from (`address` below), a
   value this namespace never chooses and the peer's own payload
   never carries -- distinct per peer, exactly as two different UDP
   sockets would be."
  [channel-id]
  (let [ab (ring 64)
        ba (ring 64)]
    {:channel {:dao.stream/type :dao.stream.test/channel
               :dao.stream/identity channel-id}
     :ab ab
     :ba ba
     :a-end {:reader ba :writer ab}
     :b-end {:reader ab :writer ba}}))


(defn- address
  "The reflexive address M's mirror-step observes for peer `t`: the
   identity of the ring buffer it reads `t`'s requests from."
  [t]
  (:dao.stream/identity (stream/descriptor (:ab t))))


(defn- served-peer
  "M's own mirror side of one peer's toy, over the shared `table`."
  [table t]
  {:table table
   :reader (:reader (:b-end t))
   :writer (:writer (:b-end t))
   :mirror (atom (:dao.stream/cursor
                   (stream/cursor (:reader (:b-end t)) :dao.stream/oldest)))})


(defn- serve!
  [peer]
  (swap! (:mirror peer)
         #(remote/mirror-step @(:table peer) (:reader peer) %
                              (:writer peer))))


(defn- attacher-for
  "One remote/attacher over `t`'s channel, its one link shared by
   every identity attached through it from here -- callers that need
   more than one identity on one toy must reuse this, never build a
   second attacher over the same physical buffers: two attachers each
   mint their own ids from zero, and a shared wire risks one's
   answer being absorbed by the other's outstanding id of the same
   number."
  ([t] (attacher-for t {}))
  ([t opts]
   (remote/attacher
     (merge opts {:dao.stream.remote/channels {(:channel t) (:a-end t)}}))))


(defn- attach
  [attach! t identity]
  (:dao.stream/handle
    (attach! {:dao.stream/type :dao.stream/remote
              :dao.stream/identity identity
              :dao.stream/channel (:channel t)})))


(defn- values
  [h]
  (loop [c (:dao.stream/cursor (stream/cursor h :dao.stream/oldest))
         acc []]
    (let [r (stream/next h c)]
      (if (= :dao.stream/ok (:dao.stream/outcome r))
        (recur (:dao.stream/cursor r) (conj acc (:dao.stream/value r)))
        acc))))


(defn- oldest-cursor!
  "The reflection's :oldest cursor, resolved: sent, served, then
   re-asked so the filed answer is returned, exactly the two-call
   idiom every reflection read needs."
  [r peer]
  (serve! peer)
  (stream/cursor r :dao.stream/oldest)
  (serve! peer)
  (:dao.stream/cursor (stream/cursor r :dao.stream/oldest)))


(defn- read-all!
  "Drain reflection `r`'s served stream fully to `:dao.stream/blocked`,
   serving `peer` between reads as needed, returning every value seen.
   A blocked read is retried, served, once more before treating the
   stream as caught up -- the same send-then-ask idiom `next` needs
   throughout dao.stream.remote-test."
  [r peer]
  (loop [c (oldest-cursor! r peer) acc [] stalled 0]
    (if (>= stalled 3)
      acc
      (let [n (stream/next r c)]
        (case (:dao.stream/outcome n)
          :dao.stream/ok (recur (:dao.stream/cursor n)
                                (conj acc (:dao.stream/value n)) 0)
          :dao.stream/blocked (do (serve! peer) (recur c acc (inc stalled)))
          acc)))))


;; =============================================================================
;; The meeting and its lease judge, wired together
;; =============================================================================

(defn- resolver
  [source _fact]
  source)


(defn- meeting-system
  "M's meeting state plus a composed lease judge over fresh ring
   buffers, `reclaim-fn` closing over the meeting: `m`'s own reclaim
   dissoc's a pair's two identities from `table` and decrements
   `active-pairs`, both this namespace's own bookkeeping."
  [{:keys [max-pairs duration tolerance holder fanout]}]
  (let [table (atom {})
        board (ring 32)
        requests (ring 32)
        decision (ring 1)
        judge-atom (atom nil)
        m (meet/meeting {:requests requests :board board :table table
                         :capacity 8 :max-pairs max-pairs
                         :decision decision :judge-atom judge-atom
                         :duration duration :ring! ring :self :M
                         :incarnation "epoch-1"
                         :advertised-channel {:dao.stream/type :dao.stream.test/channel
                                              :dao.stream/identity :M}
                         :fanout fanout})
        ticks (ring 16)
        facts (ring 16)
        writer (ring 16)
        judge-config (lease/make-judge
                       {:cadence {:ms 1} :units {:ms 1}
                        :tolerance tolerance
                        :resolver resolver
                        :resolver-bindings #{:per-author-media}
                        :reclaim (meet/reclaim-fn m)
                        :writer writer
                        :self :M
                        :ticks [{:handle ticks
                                 :cursor (:dao.stream/cursor
                                           (stream/cursor
                                             ticks :dao.stream/oldest))}]
                        :media [{:handle facts
                                 :cursor (:dao.stream/cursor
                                           (stream/cursor
                                             facts :dao.stream/oldest))
                                 :source holder
                                 :medium {:retention :evict-oldest
                                          :capacity 8
                                          :value-domain :portable-values
                                          :attribution :per-author-media}}]})]
    (reset! judge-atom (:judge judge-config))
    {:m m :table table :board board :requests requests :decision decision
     :ticks ticks :facts facts :judge-step (:step judge-config)
     :judge-atom judge-atom}))


(defn- meet-table
  [sys]
  (merge (meet/entries {:requests (:requests sys) :board (:board sys)
                        :decision (:decision sys)})
         @(:table sys)))


(defn- tick!
  [sys ms]
  (stream/append! (:ticks sys) (lease/tick {:ms ms}))
  (swap! (:judge-atom sys) (:judge-step sys))
  (meet/cleanup! (:m sys)))


;; =============================================================================
;; A meet/here posting carries the observed address, not a claim
;; =============================================================================

(deftest meet-here-tags-the-observed-address-test
  (let [toy-a (toy "addr-a")
        toy-b (toy "addr-b")
        sys (meeting-system {:max-pairs 10 :duration {:ms 100}
                             :tolerance {:ms 0} :holder (address toy-a)})
        peer-a (served-peer (atom (meet-table sys)) toy-a)
        peer-b (served-peer (atom (meet-table sys)) toy-b)
        ra-req (attach (attacher-for toy-a) toy-a :meet-requests)
        rb-req (attach (attacher-for toy-b) toy-b :meet-requests)]
    (stream/append! ra-req {:meet/here "peer-a"})
    (serve! peer-a)
    (stream/append! rb-req {:meet/here "peer-b"})
    (serve! peer-b)
    (meet/step! (:m sys))
    (let [postings (values (:board sys))]
      (is (= #{(address toy-a) (address toy-b)}
             (into #{} (map :meet/reflexive postings)))
          "the board carries the channel identity M itself observed
           for each peer -- neither payload named an address at all")
      (is (= #{"peer-a" "peer-b"} (into #{} (map :meet/seen postings))))
      (is (not-any? #(contains? % :dao.lease/lease) postings)
          "a registration is a board record, not a lease: no lease id
           is minted for it, nothing granted, nothing judged"))))


;; =============================================================================
;; Bounded meeting work: the gate refuses past the bound, present
;; =============================================================================

(deftest bound-gate-refuses-past-the-bound-test
  (let [toy-a (toy "addr-a")
        sys (meeting-system {:max-pairs 1 :duration {:ms 1000}
                             :tolerance {:ms 0} :holder (address toy-a)})
        peer-a (served-peer (atom (meet-table sys)) toy-a)
        events (ring 16)
        ra-req (attach (attacher-for toy-a
                                     {:dao.stream.remote/events events})
                       toy-a :meet-requests)]
    (meet/step! (:m sys))
    (testing "within the bound, the first ask's append! is honored"
      (stream/append! ra-req {:meet/pair "peer-b"})
      (serve! peer-a)
      (stream/descriptor ra-req)
      (is (= :dao.stream/ok (:dao.stream/outcome (last (values events))))
          "the server's own answer to the append!, seen on the event
           writer -- the reflection's own append! return is only the
           local send's acceptance, never the server's answer")
      (meet/step! (:m sys))
      (is (= 1 (meet/active-pairs (:m sys)))))
    (testing "past the bound, the gate refuses the append! itself,
              present, not silent -- observed on the event writer,
              never as a filed answer a later op returns"
      ;; one further step! publishes the decision reflecting the
      ;; grant just above -- the gate reads only what was already
      ;; published, never the meeting's own live state directly
      (meet/step! (:m sys))
      (stream/append! ra-req {:meet/pair "peer-c"})
      (serve! peer-a)
      (stream/descriptor ra-req)
      (is (= :dao.stream/refused (:dao.stream/outcome (last (values events))))
          "the append! itself was refused, not merely its request
           unhonored"))))


;; =============================================================================
;; Bounded meeting work: capacity holds at grant time
;; =============================================================================

(deftest grant-time-capacity-refuses-excess-test
  (let [toy-a (toy "addr-a")
        toy-b (toy "addr-b")
        sys (meeting-system {:max-pairs 1 :duration {:ms 1000}
                             :tolerance {:ms 0} :holder (address toy-a)})
        peer-a (served-peer (atom (meet-table sys)) toy-a)
        peer-b (served-peer (atom (meet-table sys)) toy-b)
        events-a (ring 16)
        events-b (ring 16)
        attach-a! (attacher-for toy-a
                                {:dao.stream.remote/events events-a})
        attach-b! (attacher-for toy-b
                                {:dao.stream.remote/events events-b})
        ra-req (attach attach-a! toy-a :meet-requests)
        rb-req (attach attach-b! toy-b :meet-requests)
        ra-board (attach attach-a! toy-a :meet-board)
        rb-board (attach attach-b! toy-b :meet-board)]
    ;; one step! publishes the count that still has room: both asks
    ;; are accepted against that one publication, both queued
    (meet/step! (:m sys))
    (stream/append! ra-req {:meet/pair "peer-b"})
    (serve! peer-a)
    (stream/descriptor ra-req)
    (stream/append! rb-req {:meet/pair "peer-c"})
    (serve! peer-b)
    (stream/descriptor rb-req)
    (testing "both asks were accepted: the gate had one published
              count with room, and it is never asked retroactively"
      (is (= :dao.stream/ok (:dao.stream/outcome (last (values events-a)))))
      (is (= :dao.stream/ok (:dao.stream/outcome (last (values events-b))))))
    (meet/step! (:m sys))
    (testing "capacity enforced at grant time: the first ask is
              granted, every excess one refused, a present answer the
              asker reads through its own reflection"
      (is (= 1 (meet/active-pairs (:m sys)))
          "one grant, not two: the second could not exceed max-pairs")
      (let [seen-a (read-all! ra-board peer-a)
            seen-b (read-all! rb-board peer-b)
            grant-a (first (filter #(and (contains? % :dao.lease/grant)
                                         (= (address toy-a)
                                            (:meet/asker %)))
                                   seen-a))
            refusal-b (first (filter #(and (contains? % :meet/refused)
                                           (= (address toy-b)
                                              (:meet/asker %)))
                                     seen-b))]
        (is (= "peer-b" (:meet/pair-for grant-a))
            "the ask that arrived first was granted, its posting
             naming A the asker M itself observed")
        (is (= 1 (count (filter #(contains? % :meet/refused) seen-b)))
            "the queued excess ask was refused, not silently dropped")
        (is (= "peer-c" (:meet/pair-for refusal-b))
            "the refusal names the ask it refuses")
        (is (= :dao.stream.remote-meet/past-bound
               (:meet/refused refusal-b))
            "and why, present on the board the asker's own reflection
             reads")
        (is (some #(contains? % :dao.lease/grant) seen-b)
            "the board is one shared stream: B reads A's grant too --
             the refusal is what answers B's own ask")))))


;; =============================================================================
;; Bounded meeting work: the fanout of one driver step
;; =============================================================================

(deftest fanout-bounds-one-step-test
  (let [toy-a (toy "addr-a")
        sys (meeting-system {:max-pairs 10 :duration {:ms 1000}
                             :tolerance {:ms 0} :holder (address toy-a)
                             :fanout 2})
        peer-a (served-peer (atom (meet-table sys)) toy-a)
        attach-a! (attacher-for toy-a)
        ra-req (attach attach-a! toy-a :meet-requests)
        ra-board (attach attach-a! toy-a :meet-board)]
    (stream/append! ra-req {:meet/here "a"})
    (stream/append! ra-req {:meet/here "b"})
    (stream/append! ra-req {:meet/here "c"})
    (stream/append! ra-req {:meet/here "d"})
    (serve! peer-a)
    (meet/step! (:m sys))
    (is (= ["a" "b"] (mapv :meet/seen (read-all! ra-board peer-a)))
        "one pass handles exactly its fanout: two of the four queued
         asks answered, the drain stopped there, not run to blocked")
    (meet/step! (:m sys))
    (is (= ["a" "b" "c" "d"]
           (mapv :meet/seen (read-all! ra-board peer-a)))
        "the remainder is handled on the next pass, in order -- the
         cursor was preserved where the fanout spent itself")))


;; =============================================================================
;; The simulated network: two peers behind NATs, M the meeting peer
;; =============================================================================

(defn- nat-net
  "The simulated public internet between two peers, each behind its
   own NAT, with `kind` the filtering behavior under simulation.
   `:restricted` is outbound-only: a datagram is delivered only when
   the receiving socket has itself sent to the sender's address
   before, so each peer's requests reach M over the channel it
   dialed while direct peer-to-peer is refused until both sides have
   punched. `:symmetric` is endpoint-dependent mapping and filtering
   on both sides: the address a peer is reachable on is a mapping
   per destination, so the reflexive address the meeting peer
   observed matches no mapping peer to peer and no direct datagram
   is ever delivered -- relay is forced. Sends ride :flight until a
   `nat-step!` filters them; a refused datagram is recorded in
   :drops, a present fact the proofs assert, never a silent loss in
   the model."
  [kind addr-a addr-b]
  (atom {:kind kind
         :addr {:a addr-a :b addr-b}
         :peer-at {addr-a :a addr-b :b}
         :sent {:a #{} :b #{}}
         :flight []
         :drops []}))


(defn- nat-writer
  "One peer's direct socket writer toward the other's address:
   `append!` records the outbound mapping the send creates (section
   4: a send may name an explicit destination from an already-bound
   socket; hole punching depends on it) and queues the datagram for
   the network's filter, answering ok -- outbound acceptance; whether
   the datagram lands is the NAT's decision, not the sender's."
  [net from to-addr]
  (reify stream/IDaoStreamWriter
    (append!
      [_ v]
      (swap! net (fn [n]
                   (-> n
                       (update-in [:sent from] conj to-addr)
                       (update :flight conj {:from from
                                             :to-addr to-addr
                                             :v v}))))
      {:dao.stream/outcome :dao.stream/ok})))


(defn- nat-delivers?
  "The destination peer's own NAT rule over one in-flight datagram:
   restricted delivers only when the receiving socket has itself
   already sent to the sender's address -- the mapping the datagram's
   source must match to be admitted; symmetric delivers a direct
   peer-to-peer datagram never, the source address matching no
   mapping the destination holds toward this sender."
  [{:keys [kind sent peer-at addr]} {:keys [from to-addr]}]
  (let [to (get peer-at to-addr)]
    (boolean
      (and to
           (case kind
             :restricted (contains? (get sent to) (get addr from))
             :symmetric false)))))


(defn- nat-step!
  "One pass of the simulated network: every in-flight datagram is run
   through its destination's NAT rule, delivered onto the peer's
   inbound ring or recorded among the drops. Returns
   `{:delivered n :dropped n}`."
  [net inbound]
  (let [{:keys [flight] :as n} @net
        {yes true no false} (group-by #(nat-delivers? n %) flight)]
    (doseq [d yes]
      (stream/append! (get inbound (get (:peer-at @net) (:to-addr d)))
                      (:v d)))
    (swap! net assoc :flight [] :drops (into (:drops n) no))
    {:delivered (count yes) :dropped (count no)}))


(defn- direct-peer
  "One peer's end of the simulated direct wire: `inbound` the ring
   its mirror reads datagrams from, its answers leaving through the
   NAT writer toward the other peer's address, over an empty table
   of its own -- a peer serving nothing under the punched identity
   answers not-found, an answer like any other (section 4: a path
   exists when an answer, even a not-found error, carrying one of
   this peer's outstanding ids arrives). Served with `serve!` like
   any peer."
  [net who inbound]
  (let [other (get {:a :b :b :a} who)]
    {:table (atom {})
     :reader inbound
     :writer (nat-writer net who (get-in @net [:addr other]))
     :mirror (atom (:dao.stream/cursor
                     (stream/cursor inbound :dao.stream/oldest)))}))


(defn- punch-request
  "The descriptor request the punch sends, one fresh id per send
   (section 4: each side sends a descriptor request with a fresh id
   to the other's reflexive address): the identity named is the
   asker's own, one the answering peer serves nothing under, whose
   not-found is still the answer that confirms the path."
  [who id]
  {:dao.stream/identity (str "punch-" (name who))
   :dao.stream.remote/op :dao.stream/descriptor
   :dao.stream.remote/args []
   :dao.stream.remote/id id})


(defn- relay-outer
  "The outer descriptor one peer attaches for the relay: a pair
   channel descriptor over `channel`, this peer's own channel to M,
   reading `read-id` and writing `write-id` -- the same two table
   identities the board posted, named through this peer's channel
   (2.2: two remote descriptors naming one identity through different
   channels reach one stream). The outer identity is the service the
   pair reaches, because the attach probe must name an identity the
   far side serves."
  [channel identity read-id write-id]
  {:dao.stream/type :dao.stream/remote
   :dao.stream/identity identity
   :dao.stream/channel
   {:dao.stream/type :dao.stream/pair
    :dao.stream/identity (str identity "-pair")
    :dao.stream.remote/in
    {:dao.stream/type :dao.stream/remote
     :dao.stream/identity read-id
     :dao.stream/channel channel}
    :dao.stream.remote/out
    {:dao.stream/type :dao.stream/remote
     :dao.stream/identity write-id
     :dao.stream/channel channel}}})


;; =============================================================================
;; restricted NAT, outbound-only: the punch is direct, after the meet
;; =============================================================================

(deftest restricted-nat-punch-test
  (let [toy-a (toy "addr-a")
        toy-b (toy "addr-b")
        sys (meeting-system {:max-pairs 10 :duration {:ms 1000}
                             :tolerance {:ms 0} :holder (address toy-a)})
        peer-a (served-peer (atom (meet-table sys)) toy-a)
        peer-b (served-peer (atom (meet-table sys)) toy-b)
        attach-a! (attacher-for toy-a)
        attach-b! (attacher-for toy-b)
        ra-req (attach attach-a! toy-a :meet-requests)
        rb-req (attach attach-b! toy-b :meet-requests)
        ra-board (attach attach-a! toy-a :meet-board)
        rb-board (attach attach-b! toy-b :meet-board)
        net (nat-net :restricted (address toy-a) (address toy-b))
        in-a (ring 16)
        in-b (ring 16)
        direct-a (direct-peer net :a in-a)
        direct-b (direct-peer net :b in-b)]
    (stream/append! ra-req {:meet/here "peer-a"})
    (serve! peer-a)
    (stream/append! rb-req {:meet/here "peer-b"})
    (serve! peer-b)
    (meet/step! (:m sys))
    (let [postings-a (read-all! ra-board peer-a)
          postings-b (read-all! rb-board peer-b)
          addr-of-a (:meet/reflexive
                      (first (filter #(= "peer-a" (:meet/seen %))
                                     postings-b)))
          addr-of-b (:meet/reflexive
                      (first (filter #(= "peer-b" (:meet/seen %))
                                     postings-a)))]
      (testing "each peer learned the other's address only from the
                board, and it is the address M itself observed"
        (is (= (address toy-a) addr-of-a))
        (is (= (address toy-b) addr-of-b)))
      (testing "outbound-only is real: a lone direct send before any
                punch is refused by B's NAT -- nothing lands, the
                drop a recorded fact"
        (stream/append! (nat-writer net :a addr-of-b)
                        (punch-request :a 101))
        (let [r (nat-step! net {:a in-a :b in-b})]
          (is (= 0 (:delivered r)))
          (is (= 1 (:dropped r)))
          (is (empty? (values in-b))
              "B observed nothing: the request never crossed")))
      (testing "the punch, a coordinated simultaneous send: each
                peer sends its descriptor request to the address the
                board carried; both mappings now exist, both deliver"
        (stream/append! (nat-writer net :a addr-of-b)
                        (punch-request :a 102))
        (stream/append! (nat-writer net :b addr-of-a)
                        (punch-request :b 201))
        (let [r (nat-step! net {:a in-a :b in-b})]
          (is (= 2 (:delivered r)))
          (is (= 0 (:dropped r))))
        (serve! direct-b)
        (serve! direct-a)
        (nat-step! net {:a in-a :b in-b})
        (testing "the direct request-answer sequences: each peer
                  holds an answer carrying its own outstanding id
                  from the other's address -- a path exists, the
                  whole of STUN as facts on a stream"
          (let [from-b (values in-a)
                from-a (values in-b)
                ;; each ring carries the other side's punch request
                ;; as well -- the datagrams both ways -- the answers
                ;; being the values that carry an error
                answers (fn [vs]
                          (filter #(contains? % :dao.stream.remote/error)
                                  vs))]
            (is (= [102] (mapv :dao.stream.remote/id (answers from-b)))
                "A holds B's answer to its punch, correlated by the
                 id A minted")
            (is (every? #(= :dao.stream.remote/not-found
                            (:dao.stream.remote/error %))
                        (answers from-b))
                "B serves nothing under the punched identity: the
                 not-found error is still the confirming answer")
            (is (= [201] (mapv :dao.stream.remote/id (answers from-a)))
                "and B holds A's answer to its punch the same way")
            (is (every? #(= :dao.stream.remote/not-found
                            (:dao.stream.remote/error %))
                        (answers from-a)))
            (is (= 1 (count (:drops @net)))
                "exactly the lone pre-punch send was ever refused;
                 nothing of the punch or its answers was")))))))


;; =============================================================================
;; symmetric NAT: the direct path is refused, the relay carries
;; =============================================================================

(deftest symmetric-nat-forced-relay-test
  (let [toy-a (toy "addr-a")
        toy-b (toy "addr-b")
        sys (meeting-system {:max-pairs 10 :duration {:ms 1000}
                             :tolerance {:ms 0} :holder (address toy-a)})
        table-atom (atom (meet-table sys))
        peer-a (served-peer table-atom toy-a)
        peer-b (served-peer table-atom toy-b)
        attach-a! (attacher-for toy-a)
        attach-b! (attacher-for toy-b)
        ra-req (attach attach-a! toy-a :meet-requests)
        rb-req (attach attach-b! toy-b :meet-requests)
        ra-board (attach attach-a! toy-a :meet-board)
        rb-board (attach attach-b! toy-b :meet-board)
        net (nat-net :symmetric (address toy-a) (address toy-b))
        in-a (ring 16)
        in-b (ring 16)]
    (stream/append! ra-req {:meet/here "peer-a"})
    (serve! peer-a)
    (stream/append! rb-req {:meet/here "peer-b"})
    (serve! peer-b)
    (meet/step! (:m sys))
    (let [postings-a (read-all! ra-board peer-a)
          postings-b (read-all! rb-board peer-b)
          addr-of-a (:meet/reflexive
                      (first (filter #(= "peer-a" (:meet/seen %))
                                     postings-b)))
          addr-of-b (:meet/reflexive
                      (first (filter #(= "peer-b" (:meet/seen %))
                                     postings-a)))]
      (testing "symmetric NAT refuses the direct path both ways,
                even with both mappings punched -- the reflexive
                address M observed is a mapping for M, for neither
                peer; punching is unreliable and relay is forced"
        (stream/append! (nat-writer net :a addr-of-b)
                        (punch-request :a 301))
        (stream/append! (nat-writer net :b addr-of-a)
                        (punch-request :b 401))
        (let [r (nat-step! net {:a in-a :b in-b})]
          (is (= 0 (:delivered r)))
          (is (= 2 (:dropped r)))
          (is (empty? (values in-a)))
          (is (empty? (values in-b)))))
      ;; A asks for the pair; M grants, posts, and the board carries
      (stream/append! ra-req {:meet/pair "peer-b"})
      (serve! peer-a)
      (meet/step! (:m sys))
      (reset! table-atom (meet-table sys))
      (let [post-a (first (filter #(contains? % :meet/pair-for)
                                  (read-all! ra-board peer-a)))
            post-b (first (filter #(contains? % :meet/pair-for)
                                  (read-all! rb-board peer-b)))
            pair-id (:dao.lease/lease post-b)
            in-id (get-in post-a [:dao.stream.remote/in
                                  :dao.stream/identity])
            out-id (get-in post-a [:dao.stream.remote/out
                                   :dao.stream/identity])
            ;; B, the asked-for peer, mirrors in and out into its own
            ;; pair end: reading out, writing in, over the same two
            ;; buffers, and serves one stream of its own behind its
            ;; NAT -- the service the relay exists to reach
            scratch (ring 8)
            b-table (atom {"b-svc" {:handle scratch
                                    :surface #{:reader :writer}}})
            b-read (attach attach-b! toy-b out-id)
            b-write (attach attach-b! toy-b in-id)
            relay-mirror (atom (oldest-cursor! b-read peer-b))
            serve-relay! (fn []
                           (swap! relay-mirror
                                  #(remote/mirror-step @b-table
                                                       b-read %
                                                       b-write)))
            churn! (fn []
                     (serve! peer-a)
                     (serve! peer-b)
                     (serve-relay!))
            relay-read-all!
            (fn [r]
              (let [ask!
                    (fn []
                      (loop [n 0]
                        (stream/cursor r :dao.stream/oldest)
                        (dotimes [_ 4] (churn!))
                        (or (:dao.stream/cursor
                              (stream/cursor r :dao.stream/oldest))
                            (when (< n 6) (recur (inc n))))))]
                (loop [c (ask!) acc [] stalled 0]
                  (if (or (nil? c) (>= stalled 6))
                    acc
                    (let [n (stream/next r c)]
                      (if (= :dao.stream/ok (:dao.stream/outcome n))
                        (recur (:dao.stream/cursor n)
                               (conj acc (:dao.stream/value n))
                               0)
                        (do (dotimes [_ 4] (churn!))
                            (recur c acc (inc stalled)))))))))
            attach-pair-a! (pair/attacher
                             {:dao.stream.remote.pair/attach! attach-a!})
            ra-svc (:dao.stream/handle
                     (attach-pair-a!
                       (relay-outer (:channel toy-a) "b-svc"
                                    in-id out-id)))]
        (is (some? post-a) "the relay grant was posted on the board")
        (is (= pair-id (:dao.lease/lease post-a))
            "both peers read the one grant, each through its own
             reflection")
        (testing "A attaches a pair channel over the posted
                  descriptors, riding dao.stream.remote-pair's own
                  convention"
          (is (= :dao.stream/ok
                 (:dao.stream/outcome (stream/descriptor ra-svc)))
              "attached ok at once, the deferred confirmation"))
        (testing "the relay exchange through the granted pair"
          (stream/append! scratch
                          {:relay/from "peer-b" :relay/text "hello"})
          (is (= [{:relay/from "peer-b" :relay/text "hello"}]
                 (relay-read-all! ra-svc))
              "A reads B's own stream through the granted pair: the
               request rode A's channel to M's out ring, B's mirrored
               pair end fetched and answered it, M interpreting
               nothing it relayed")
          (stream/append! ra-svc
                          {:relay/from "peer-a" :relay/text "ping"})
          (dotimes [_ 6] (churn!))
          (is (= [{:relay/from "peer-a" :relay/text "ping"}]
                 (rest (values scratch)))
              "and B's own stream carries A's request: the exchange
               is two-way, both ends writing through the pair")
          (stream/append! scratch
                          {:relay/from "peer-b" :relay/text "pong"})
          (is (= [{:relay/from "peer-b" :relay/text "hello"}
                  {:relay/from "peer-a" :relay/text "ping"}
                  {:relay/from "peer-b" :relay/text "pong"}]
                 (relay-read-all! ra-svc))
              "the full request-answer sequence through the granted
               pair, read from the start: B's hello, A's ping -- A's
               own write comes back through the pair, it rode B's
               stream -- and B's pong, with the direct path refused
               by the simulation above; the relay carried every
               value, the board only introduced the peers"))))))


;; =============================================================================
;; A pair whose holder stops renewing answers not-found
;; =============================================================================

(deftest lease-reclaim-as-not-found-test
  (let [toy-a (toy "addr-a")
        sys (meeting-system {:max-pairs 10 :duration {:ms 10}
                             :tolerance {:ms 0} :holder (address toy-a)})
        table-atom (atom (meet-table sys))
        peer-a (served-peer table-atom toy-a)
        attach-a! (attacher-for toy-a)
        ra-req (attach attach-a! toy-a :meet-requests)]
    (stream/append! ra-req {:meet/pair "peer-b"})
    (serve! peer-a)
    ;; step! drains the ask and queues the grant; only the NEXT judge
    ;; pass seeds it (dao.lease.md, author-grant), so the tick that
    ;; establishes tenure-start must come after, never before
    (meet/step! (:m sys))
    (tick! sys 1)
    (reset! table-atom (meet-table sys))
    (let [postings (values (:board sys))
          posting (first (filter #(contains? % :meet/pair-for) postings))
          in-id (get-in posting [:dao.stream.remote/in :dao.stream/identity])
          renewal-id (get-in posting [:dao.stream.remote/renewal
                                      :dao.stream/identity])]
      (is (contains? @(:table sys) in-id) "granted, intact at once")
      (is (some? (:handle (get @(:table sys) renewal-id)))
          "the grant created the holder's renewal medium too")
      (testing "no renewal is ever appended: the holder has stopped;
                past duration + tolerance the judge reclaims"
        (tick! sys 20)
        (is (not (contains? @(:table sys) in-id))
            "the idempotent reclaim removed the table entry")
        (is (not (contains? @(:table sys) renewal-id))
            "the renewal medium went with the subject, together")
        (is (= 0 (meet/active-pairs (:m sys))))
        (let [in-refl (attach attach-a! toy-a in-id)]
          (reset! table-atom (meet-table sys))
          (serve! peer-a)
          (let [ans (stream/next in-refl 0)]
            (is (= :dao.stream/transport-error (:dao.stream/outcome ans)))
            (is (= :dao.stream.remote/not-found
                   (:dao.stream.remote/reason ans)))))))))


;; =============================================================================
;; A reconnect renews through the carried grant, never an injection
;; =============================================================================

(deftest reconnect-renews-through-the-carried-grant-test
  (let [toy-a (toy "addr-a")
        sys (meeting-system {:max-pairs 10 :duration {:ms 10}
                             :tolerance {:ms 0} :holder (address toy-a)})
        events (ring 16)
        table-atom (atom (meet-table sys))
        peer-a (served-peer table-atom toy-a)
        attach-a! (attacher-for toy-a {:dao.stream.remote/events events})
        ra-req (attach attach-a! toy-a :meet-requests)
        ra-board (attach attach-a! toy-a :meet-board)]
    (stream/append! ra-req {:meet/pair "peer-b"})
    (serve! peer-a)
    (meet/step! (:m sys))
    (tick! sys 1)
    (reset! table-atom (meet-table sys))
    (let [posting (first (filter #(contains? % :meet/pair-for)
                                 (read-all! ra-board peer-a)))
          grant (:dao.lease/grant posting)
          lease-id (:dao.lease/lease posting)
          in-id (get-in posting [:dao.stream.remote/in
                                 :dao.stream/identity])
          out-id (get-in posting [:dao.stream.remote/out
                                  :dao.stream/identity])
          renewal-id (get-in posting [:dao.stream.remote/renewal
                                      :dao.stream/identity])]
      (is (some? posting) "the relay grant was posted on the board")
      (testing "the carriage is a COMPLETE grant: status, lease,
                subject, holder and duration, observed by the holder
                before acting (dao.lease.md, The holder)"
        (is (= :dao.lease/accepted (:dao.lease/status grant)))
        (is (= lease-id (:dao.lease/lease grant)))
        (is (= [in-id out-id] (:dao.lease/subject grant)))
        (is (= (address toy-a) (:dao.lease/holder grant))
            "the holder is the address M itself observed, not a
             claim the ask carried")
        (is (= {:ms 10} (:dao.lease/duration grant))))
      (testing "renewal carriage is wired into the judge: the grant
                created the holder's renewal medium, a writer entry
                in M's table, the judge's own fact medium attributed
                to the holder"
        (is (= #{:writer} (:surface (get @(:table sys) renewal-id)))))
      ;; the holder renews THROUGH the convention: a reflection on
      ;; the renewal medium's descriptor, append, the source's ok
      ;; observed on the event writer
      (let [ra-renewal (attach attach-a! toy-a renewal-id)]
        (stream/append! ra-renewal (lease/renewal lease-id))
        (serve! peer-a)
        (stream/descriptor ra-renewal)
        (testing "the source's ok observed on the event writer: the
                  holder's bound advances on this, never on the
                  append's own outbound acceptance"
          (is (some #(and (= renewal-id (:dao.stream/identity %))
                          (= :dao.stream/ok (:dao.stream/outcome %))
                          (not (contains? % :dao.stream/descriptor)))
                    (values events))
              "the renewal medium's append! answered ok at M"))
        (tick! sys 8)
        (is (= {:ms 8}
               (get-in @(:judge-atom sys)
                       [:ledger lease-id :last-observation]))
            "the judge counted the renewal, attributed to the holder
             by the medium wired at grant time")
        (is (= {:ms 1}
               (get-in @(:judge-atom sys)
                       [:ledger lease-id :tenure-start]))
            "the tenure start no renewal moves")
        (tick! sys 14)
        (is (contains? @(:table sys) in-id)
            "intact at 14: six since the renewal, well within the
             duration it bought -- the thirteen since tenure start
             would have lapsed the pair")
        (is (= 1 (meet/active-pairs (:m sys))))
        (testing "the reconnect: attach! again on the pair's identity
                  finds the pair intact, not gone"
          (let [in-refl (attach attach-a! toy-a in-id)]
            (reset! table-atom (meet-table sys))
            (serve! peer-a)
            (is (not= :dao.stream.remote/not-found
                      (:dao.stream.remote/reason
                        (stream/next in-refl 0)))
                "the reconnect finds the pair alive")))))))


(deftest s5-publication-correlation-and-cleanup-test
  (let [sys (meeting-system {:max-pairs 2 :duration {:ms 10} :tolerance {:ms 0} :holder :a})
        m (:m sys)
        request {:meet/pair :b :meet/from :a :meet/request :fresh
                 :meet/peer :a :meet/incarnation :requester}]
    (tick! sys 1)
    (stream/append! (:requests sys) request)
    (stream/append! (:requests sys) request)
    (stream/append! (:requests sys) 42)
    (stream/append! (:requests sys) {:meet/here :a :meet/pair :b})
    (meet/step! m 0)
    (let [posts (filterv :meet/pair-for (values (:board sys)))
          post (first posts)
          in-id (get-in post [:dao.stream.remote/in :dao.stream/identity])
          out-id (get-in post [:dao.stream.remote/out :dao.stream/identity])
          renewal-id (get-in post [:dao.stream.remote/renewal :dao.stream/identity])
          owned (mapv #(get-in @(:table sys) [% :handle]) [in-id out-id renewal-id])
          reclaim (meet/reclaim-fn m)]
      (is (= 2 (count posts)))
      (is (= (first posts) (second posts)))
      (is (= 1 (meet/active-pairs m)))
      (is (= (:advertised-channel @m) (get-in post [:dao.stream.remote/in :dao.stream/channel])))
      (is (= :fresh (:meet/request post)))
      (is (= 2 (count (:diagnostics @m))))
      (is (= (:dao.lease/lease post) (get-in @(:table sys) [in-id :dao.lease/lease])))
      (is (= 2 (count (:facts @(:judge-atom sys)))))
      (reclaim [in-id out-id])
      (reclaim [in-id out-id])
      (meet/cleanup! m)
      (is (= 1 (count (:facts @(:judge-atom sys)))))
      (is (= 0 (meet/active-pairs m)))
      (is (every? #(= :dao.stream/closed (:dao.stream/outcome (stream/append! % :after))) owned))
      (is (empty? (:renewals @m)))
      (is (empty? (:correlations @m))))))


(deftest s5-incarnation-and-epoch-admission-test
  (let [a (meeting-system {:max-pairs 2 :duration {:ms 10} :tolerance {:ms 0} :holder :a})
        b (meeting-system {:max-pairs 2 :duration {:ms 10} :tolerance {:ms 0} :holder :a})]
    (swap! (:m a) assoc :incarnation "before" :max-grants 1)
    (swap! (:m b) assoc :incarnation "after")
    (doseq [sys [a b]]
      (tick! sys 1)
      (stream/append! (:requests sys) {:meet/pair :b :meet/from :a})
      (meet/step! (:m sys) 0))
    (is (not= (:dao.lease/lease (first (values (:board a))))
              (:dao.lease/lease (first (values (:board b))))))
    (let [post (first (values (:board a)))
          subject (mapv #(get-in post [% :dao.stream/identity]) [:dao.stream.remote/in :dao.stream.remote/out])]
      ((meet/reclaim-fn (:m a)) subject)
      (stream/append! (:requests a) {:meet/pair :c :meet/from :a})
      (meet/step! (:m a) 1)
      (is (= 0 (meet/active-pairs (:m a))))
      (is (= :dao.stream.remote-meet/past-bound (:meet/refused (last (values (:board a)))))))))

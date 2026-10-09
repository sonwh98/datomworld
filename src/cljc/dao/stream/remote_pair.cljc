(ns dao.stream.remote-pair
  "The pair channel of dao.stream.remote.md (3.3): a pair descriptor
   names two remote descriptors, `:dao.stream.remote/in` and
   `:dao.stream.remote/out`. `attach!` on it returns a channel whose
   reader is a reflection of `in` and whose writer is a reflection of
   `out` -- when a third peer serves both from its table, two peers
   that cannot reach each other have a channel, and the third peer
   runs nothing but mirror steps over two ring buffers, never
   interpreting the requests and answers inside.

   `attacher` is the composition's channel-end source for pair
   descriptors, used the same way `dao.stream.remote/attacher` is:
   `(attacher opts) -> attach!`, and `(attach! descriptor) -> result`
   for a `:dao.stream/remote` descriptor whose `:dao.stream/channel`
   is a pair descriptor.

   **An `in` gap ends the channel** (3.3): the pair reader holds the
   reading cursor on `in`'s reflection; when `next` there answers
   `:dao.stream/gap`, frames were lost and which cannot be said, so
   the reader answers `:dao.stream/end` from then on -- the gap is
   never reported to an inner reader as a source's own gap. An `in`
   transport-error naming not-found or channel-gone -- a relay pair
   reclaimed at its serving peer -- ends the channel the same way; a
   retryable transport error does not. The outer
   link then runs its ordinary channel-loss path (2.4): outstanding
   ids are abandoned, each abandoned `append!` is reported
   `append-unknown`, and reattachment is the caller's, by `attach!` on
   the same pair descriptor, which mints a fresh reading cursor on
   `in` at `:newest`."
  (:require [dao.stream :as stream]
            [dao.stream.remote :as remote]
            [dao.stream.ringbuffer :as ringbuffer]))


;; =============================================================================
;; The pair descriptor (3.3)
;; =============================================================================

(defn- pair-channel-descriptor?
  "True when `d` is a pair channel descriptor: it names two remote
   descriptors, `:dao.stream.remote/in` and `:dao.stream.remote/out`."
  [d]
  (and (map? d)
       (= :dao.stream/pair (:dao.stream/type d))
       (map? (:dao.stream.remote/in d))
       (map? (:dao.stream.remote/out d))))


(defn- valid-pair-descriptor?
  "True when `d` is a `:dao.stream/remote` descriptor naming a pair
   channel."
  [d]
  (and (stream/valid-descriptor? d)
       (= :dao.stream/remote (:dao.stream/type d))
       (pair-channel-descriptor? (:dao.stream/channel d))))


;; =============================================================================
;; The pair reader: in's gap translated to end (3.3)
;; =============================================================================

(defn- terminal?
  [r]
  (or (contains? #{:dao.stream/gap :dao.stream/end :dao.stream/closed}
                 (:dao.stream/outcome r))
      (contains? #{:dao.stream.remote/not-found :dao.stream.remote/channel-gone}
                 (:dao.stream.remote/reason r))))


(defn- close-owned!
  [h]
  (when (stream/closable? h)
    (try (stream/close! h)
         (catch #?(:cljd dynamic :clj Throwable :cljs :default) _error
           {:dao.stream/outcome :dao.stream/transport-error :exception? true}))))


(defn- pair-reader
  "Project one fresh newest binding into a bounded medium. Every observer
   threads its own opaque cursor; observing answers cannot consume requests."
  [in ended? capacity cd encoded-size value-limit]
  (let [position (atom nil)
        ring (:dao.stream/handle
               (ringbuffer/create! {:dao.stream/type :dao.stream/ringbuffer
                                    :dao.stream.ringbuffer/capacity capacity}))
        initialize! (fn []
                      (when-not (or @ended? @position)
                        (let [r (stream/cursor in stream/anchor-newest)]
                          (cond
                            (= :dao.stream/ok (:dao.stream/outcome r))
                            (reset! position (:dao.stream/cursor r))
                            (terminal? r) (reset! ended? true)))))
        project! (fn []
                   (initialize!)
                   (when (and @position (not @ended?))
                     (let [r (stream/next in @position)]
                       (cond
                         (= :dao.stream/ok (:dao.stream/outcome r))
                         (if (and encoded-size (> (encoded-size (:dao.stream/value r)) value-limit))
                           (do (reset! ended? true) false)
                           (do (reset! position (:dao.stream/cursor r))
                               (stream/append! ring (:dao.stream/value r))
                               true))
                         (terminal? r) (reset! ended? true)))))
        reader (reify
                 stream/IDaoStreamDescriptor
                 (descriptor
                   [_]
                   {:dao.stream/outcome :dao.stream/ok
                    :dao.stream/descriptor cd
                    :dao.stream/identity (:dao.stream/identity cd)})


                 stream/IDaoStreamReader

                 (cursor
                   [_ anchor]
                   (initialize!)
                   (if @ended?
                     {:dao.stream/outcome :dao.stream/end}
                     (stream/cursor ring anchor)))

                 (next
                   [_ c]
                   (if @ended?
                     {:dao.stream/outcome :dao.stream/end}
                     (do (project!)
                         (let [r (stream/next ring c)]
                           (if (or @ended? (= :dao.stream/gap (:dao.stream/outcome r)))
                             (do (reset! ended? true)
                                 {:dao.stream/outcome :dao.stream/end})
                             r)))))


                 stream/IDaoStreamClosable

                 (close!
                   [_]
                   (reset! ended? true)
                   (stream/close! ring)))]
    {:reader reader :ring ring :project! project! :initialize! initialize!
     :initialized? #(some? @position)}))


(def policy-keys
  [:dao.stream.remote/events :dao.stream.remote/resend-after
   :dao.stream.remote/budget :dao.stream.remote/drain-budget
   :dao.stream.remote/max-outstanding :dao.stream.remote/max-filed
   :dao.stream.remote/give-up-after :dao.stream.remote/request-prefix])


(defn- build-pair-entry!
  [lower-attach! cd policy capacity encoded-size value-limit]
  (let [in-r (lower-attach! (:dao.stream.remote/in cd))]
    (if-not (= :dao.stream/ok (:dao.stream/outcome in-r))
      in-r
      (let [in (:dao.stream/handle in-r)
            out-r (try (lower-attach! (:dao.stream.remote/out cd))
                       (catch #?(:cljd dynamic :clj Throwable :cljs :default) _error
                         {:dao.stream/outcome :dao.stream/transport-error
                          :dao.stream.remote/reason ::attach-failed
                          :exception? true}))]
        (if-not (= :dao.stream/ok (:dao.stream/outcome out-r))
          (do (close-owned! in) out-r)
          (let [out (:dao.stream/handle out-r)
                ended? (atom false)
                projection (pair-reader in ended? capacity cd encoded-size value-limit)
                writer (reify stream/IDaoStreamWriter
                         (append!
                           [_ v]
                           (cond
                             @ended? {:dao.stream/outcome :dao.stream/closed}
                             (and encoded-size (> (encoded-size v) value-limit))
                             {:dao.stream/outcome :dao.stream/invalid-value}
                             :else
                             (let [r (stream/append! out v)]
                               (when (terminal? r) (reset! ended? true))
                               r))))
                inner (remote/links
                        (assoc policy :dao.stream.remote/request-prefix (str (random-uuid))
                               :dao.stream.remote/channels
                               {cd {:reader (:reader projection) :writer writer}}))]
            {:dao.stream/outcome :dao.stream/ok :ended? ended?
             :inner inner :in in :out out :writer writer
             :projection projection :handles (atom [])}))))))


(defn links
  "Owned, bounded pair assembly. :attach and :resolve share entries by the
   complete descriptor; :step takes descriptor and supplied now. :channel-end
   exposes independent reader/writer observers and initialization status.
   :release! retires only owned reflections, never their source media.
   Lower channels are stepped separately by their owner."
  [opts]
  (let [lower (:dao.stream.remote.pair/attach! opts)
        policy (select-keys opts policy-keys)
        capacity (get opts :dao.stream.remote.pair/capacity 64)
        limit (get opts :dao.stream.remote.pair/max-entries 64)
        budget (get opts :dao.stream.remote.pair/projection-budget 64)
        reflection-limit (get opts :dao.stream.remote.pair/max-reflections 128)
        value-limit (get opts :dao.stream.remote.pair/value-bytes 65536)
        encoded-size (:encoded-size opts)
        _ (when-not (every? #(and (integer? %) (pos? %)) [capacity limit budget reflection-limit value-limit])
            (throw (ex-info "invalid pair bounds" {:capacity capacity :limit limit :budget budget})))
        ;; Validate the link policy before acquiring lower handles.
        _ (remote/links (assoc policy :dao.stream.remote/channels {}))
        pairs (atom {})
        release! (fn [cd]
                   (when-some [e (get @pairs cd)]
                     (reset! (:ended? e) true)
                     ;; Drain end first: outstanding appends become unknown once.
                     ((:release! (:inner e)) cd)
                     (doseq [h @(:handles e)] (close-owned! h))
                     (doseq [h [(:in e) (:out e) (get-in e [:projection :ring])]]
                       (close-owned! h))
                     (swap! pairs dissoc cd))
                   {:dao.stream/outcome :dao.stream/ok})
        reap! (fn []
                (doseq [[cd e] @pairs]
                  (when @(:ended? e) (release! cd))))
        entry! (fn [cd]
                 (reap!)
                 (or (get @pairs cd)
                     (if (>= (count @pairs) limit)
                       {:dao.stream/outcome :dao.stream/full}
                       (let [e (build-pair-entry! lower cd policy capacity encoded-size value-limit)]
                         (when (= :dao.stream/ok (:dao.stream/outcome e))
                           (swap! pairs assoc cd e))
                         e))))]
    {:open! (fn [cd now]
              (if-not (pair-channel-descriptor? cd)
                {:dao.stream/outcome :dao.stream/invalid-descriptor}
                (let [e (entry! cd)]
                  (when (:inner e) ((:step (:inner e)) cd now))
                  (select-keys e [:dao.stream/outcome]))))
     :attach (fn [d]
               (if-not (valid-pair-descriptor? d)
                 {:dao.stream/outcome :dao.stream/invalid-descriptor}
                 (let [e (entry! (:dao.stream/channel d))]
                   (when (:handles e)
                     (swap! (:handles e) #(filterv (fn [h] (not (:closed? (remote/confirmation h)))) %)))
                   (cond
                     (not (:inner e)) e
                     (>= (count @(:handles e)) reflection-limit) {:dao.stream/outcome :dao.stream/full}
                     :else
                     (let [r ((:attach (:inner e)) d)]
                       (when-some [h (:dao.stream/handle r)]
                         (swap! (:handles e) conj h))
                       r)))))
     :resolve (fn [cd n]
                (if-not (pair-channel-descriptor? cd)
                  {:dao.stream/outcome :dao.stream/invalid-descriptor}
                  (let [e (entry! cd)]
                    (if (:inner e) ((:resolve (:inner e)) cd n) e))))
     :step (fn [cd now]
             (when-some [e (get @pairs cd)]
               ;; Stamp before projection can issue lower cursor probes.
               ((:step (:inner e)) cd now)
               (loop [remaining budget]
                 (when (and (pos? remaining)
                            ((get-in e [:projection :project!])))
                   (recur (dec remaining))))
               (let [r ((:step (:inner e)) cd now)]
                 (when (or @(:ended? e) (:dao.stream.remote/channel-gone? r))
                   (release! cd))
                 r)))
     :channel-end (fn [cd]
                    (when-some [e (get @pairs cd)]
                      ((get-in e [:projection :initialize!]))
                      {:reader (get-in e [:projection :reader]) :writer (:writer e)
                       :initialized? ((get-in e [:projection :initialized?]))}))
     :release! release!
     :entries #(count @pairs)}))


(defn attacher
  "Compatibility projection of links; route owners use the full assembly."
  [opts]
  (:attach (links opts)))

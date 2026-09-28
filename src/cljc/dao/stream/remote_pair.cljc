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
            [dao.stream.remote :as remote]))


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

(defn- pair-reader
  "A channel reader over `in`, a reflection: every `cursor` mints
   afresh on `in` at `:newest`, ignoring the anchor asked -- the pair
   is a live channel, not a history browse, and a reattachment's fresh
   cursor is the same mint this makes on first use. `next` follows
   `in`'s own cursor, an internal position no caller threads back
   here; an `in` gap or end, and an `in` transport-error naming
   not-found or channel-gone -- a reclaimed relay pair -- mark the
   reader ended in `ended?`, shared with the caller so a retired pair
   end can be told from a live one, and every further read, forever,
   answers `:dao.stream/end` without asking `in` again; any other
   transport error passes through, the binding not ended."
  [in ended?]
  (let [cursor (atom nil)
        mint! (fn []
                (let [r (stream/cursor in stream/anchor-newest)]
                  (when (= :dao.stream/ok (:dao.stream/outcome r))
                    (reset! cursor (:dao.stream/cursor r)))))]
    (reify
      stream/IDaoStreamReader

      (cursor
        [_ _anchor]
        (if @ended?
          {:dao.stream/outcome :dao.stream/end}
          (do (mint!)
              (if-some [c @cursor]
                {:dao.stream/outcome :dao.stream/ok :dao.stream/cursor c}
                {:dao.stream/outcome :dao.stream/transport-error
                 :dao.stream/retry? true}))))

      (next
        [_ _c]
        (if @ended?
          {:dao.stream/outcome :dao.stream/end}
          (do (when-not @cursor (mint!))
              (if-not @cursor
                {:dao.stream/outcome :dao.stream/transport-error
                 :dao.stream/retry? true}
                (let [r (stream/next in @cursor)]
                  (case (:dao.stream/outcome r)
                    :dao.stream/ok
                    (do (reset! cursor (:dao.stream/cursor r)) r)

                    (:dao.stream/gap :dao.stream/end)
                    (do (reset! ended? true)
                        {:dao.stream/outcome :dao.stream/end})

                    :dao.stream/transport-error
                    (if (contains? #{:dao.stream.remote/not-found
                                     :dao.stream.remote/channel-gone}
                                   (:dao.stream.remote/reason r))
                      (do (reset! ended? true)
                          {:dao.stream/outcome :dao.stream/end})
                      r)

                    r))))))


      stream/IDaoStreamClosable

      (close!
        [_]
        (reset! ended? true)
        {:dao.stream/outcome :dao.stream/ok}))))


;; =============================================================================
;; The pair attacher
;; =============================================================================

(defn- build-pair-entry!
  "Attach `in` and `out` through `lower-attach!`, the composition's
   attach entry for `:dao.stream/remote` descriptors, wrap them as one
   channel end for `remote/attacher`'s `:dao.stream.remote/channels`,
   and build the inner attacher scoped to that one entry. Neither
   answering ok answers that attach's own failing result."
  [lower-attach! pair-cd policy]
  (let [in-r (lower-attach! (:dao.stream.remote/in pair-cd))]
    (if-not (= :dao.stream/ok (:dao.stream/outcome in-r))
      in-r
      (let [out-r (lower-attach! (:dao.stream.remote/out pair-cd))]
        (if-not (= :dao.stream/ok (:dao.stream/outcome out-r))
          out-r
          (let [ended? (atom false)
                reader (pair-reader (:dao.stream/handle in-r) ended?)
                out-h (:dao.stream/handle out-r)
                inner (remote/attacher
                        (merge policy
                               {:dao.stream.remote/channels
                                {pair-cd {:reader reader :writer out-h}}}))]
            {:dao.stream/outcome :dao.stream/ok
             :ended? ended?
             :inner inner}))))))


(defn attacher
  "The pair channel's attach entry. `opts` carries
   `:dao.stream.remote.pair/attach!`, the composition's attach entry
   for `:dao.stream/remote` descriptors, used here to attach one
   pair's `in` and `out`; `:dao.stream.remote/events`,
   `:dao.stream.remote/resend-after` and `:dao.stream.remote/budget`
   pass through as the served identity's own link policy, exactly as
   `dao.stream.remote/attacher` takes them. One pair channel end is
   built per distinct pair descriptor value and shared by every
   identity attached through it -- `dao.stream.remote/attacher`'s own
   one-link-per-channel-descriptor sharing, here for a channel this
   namespace builds instead of one the composition wires statically.
   Once a pair's `in` gaps the built end is retired; the next attach!
   on the same pair descriptor value builds a fresh one, `in`'s cursor
   minted afresh at `:newest` -- attach! on the same pair descriptor
   resumes."
  [opts]
  (let [lower-attach! (:dao.stream.remote.pair/attach! opts)
        policy (select-keys opts [:dao.stream.remote/events
                                  :dao.stream.remote/resend-after
                                  :dao.stream.remote/budget])
        pairs (atom {})]
    (fn [descriptor]
      (if-not (valid-pair-descriptor? descriptor)
        {:dao.stream/outcome :dao.stream/invalid-descriptor}
        (let [pair-cd (:dao.stream/channel descriptor)
              cached (get @pairs pair-cd)]
          (if (and cached (not @(:ended? cached)))
            ((:inner cached) descriptor)
            (let [built (build-pair-entry! lower-attach! pair-cd policy)]
              (if-not (= :dao.stream/ok (:dao.stream/outcome built))
                built
                (do (swap! pairs assoc pair-cd built)
                    ((:inner built) descriptor))))))))))

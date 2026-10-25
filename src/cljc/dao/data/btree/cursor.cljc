(ns dao.data.btree.cursor
  "A pure, stepped, ascending cursor over PERSISTED B-tree node blobs
   (docs/design Phase 4b seam; the seam spec r7 as amended by r8).

   The cursor never reads. It is an immutable value that a driver advances:

     (start descriptor comparator)  -> cursor      no I/O; PINS the comparator
     (step cursor)                  -> outcome     one local transition
     (supply cursor response)       -> cursor      hand over the requested node

   Outcomes of `step` (exactly one):

     {:status :continue  :cursor c}                local progress, step again
     {:status :need-node :address a :cursor c}     fetch ONE node, then `supply`
     {:status :item      :value k :cursor c}       one stored key, in order
     {:status :done      :cursor c}                terminal

   The descriptor is {:root address-or-nil :count n :branching-factor b
   :lower nil-or-{:key k :inclusive? bool} :upper nil-or-{:key k
   :inclusive? bool}}. A nil bound is unbounded; {:key nil ...} is a genuine
   nil key bound. `start` does not retain the branching factor at all (it is
   accepted in the descriptor and ignored), and this namespace never rejects
   a node for occupancy, fill or key count (those are builder policy, not
   persisted-format rules).

   The persisted shapes are those of `dao.data.btree/node->blob`:
   a leaf is {:keys [...]}; a branch is {:level n :keys [...] :addresses
   [...]} whose keys are CHILD MAXIMA. A response is {:address a :node blob}.

   ---- Protocol -------------------------------------------------------------

   One request is pending at a time. Stepping a waiting cursor returns the
   same request and makes no progress (idempotent). `supply` throws a
   structured error (ex-data :dao.data.btree.cursor/error) when: no node is
   pending (:unexpected-response); the address differs from the pending one
   (:address-mismatch); or the request was already satisfied
   (:already-satisfied). Only the MOST RECENT satisfied address is remembered,
   so a stale response naming an earlier satisfied address, when nothing is
   pending, is classified :unexpected-response rather than :already-satisfied;
   every such path rejects, so the distinction is diagnostic only. A supplied node is validated by later `step`
   transitions, never inside `supply`; a malformed node is a structured
   failure (:malformed-node, :inconsistent-level, :unordered-keys,
   :separator-mismatch), never a silent empty or an endless traversal.

   ---- Transitions (no multi-node recursive descent, no hidden loop) -------

   begin -> request the root (an empty tree is :done with ZERO requests);
   validate (chunked) -> enter; enter a branch: choose a child (seek by child
   maxima, else leftmost), push an immutable path frame, request ONE child;
   enter a leaf: position (seek) and yield keys one at a time; an exhausted
   leaf ascends: each popped frame is one :continue, the next sibling is
   requested, and the descent is again one request per level; the upper bound
   stops before emitting a key outside it; no bounds is a full walk. Every
   transition does O(1) work bounded by constants: validation advances at
   most `validation-chunk` keys per step and a seek is a binary search over a
   node's keys (at most 32 comparisons).

   Validation (the ONLY checks): leaf/branch shape; a branch has a positive
   integer level, vector keys and addresses of EQUAL length, every address
   present; keys strictly ordered under the pinned comparator; a child's level
   is strictly lower than its parent's (leaves are 0); a fetched child's
   maximum compares EQUAL to the parent separator under the pinned comparator
   (comparison equality, never content identity); a response matches the
   pending address. Yielded keys are also checked strictly increasing across
   leaves. That check is STRICT because of a safety premise: persisted trees
   are built from sorted sets, which cannot hold comparator-equal elements, so
   two comparator-equal consecutive keys are unrepresentable in this format
   (kind-tied values survive only through differing later comparator fields).
   A future editor must neither weaken the check to non-strict nor extend it
   to a format, such as multiset blobs, where comparator-equal neighbors are
   legal and it would falsely reject. Nothing else: roots, single-child spines and partial final chunks
   read fine. Validation is incremental and never fetches a sibling to
   validate the selected path; it does not certify the whole tree.

   Path frames hold the immutable branch blob, its address, level, and the
   selected child position (which is also the advancement position). No frame
   holds a storage handle, execution state or clock; cursor state is a local
   value and is not promised to be serializable. This namespace calls nothing
   that restores implicitly (no resident-root, node-child, slice, seq on a
   set) and acquires no dependency on datoms, DaoSpace or any comparator.

   ---- Comparator, sentinels and the stopping rule (r8) ---------------------

   Bounds and node ordering use the comparator pinned at `start`; content
   equality must never terminate a comparator-equal range. Prefix scans are a
   caller policy, not inferred by the cursor: the caller seeks with a lower
   bound, takes keys while its own prefix test (for DaoSpace, `same-slot?`,
   a comparator tie on the scanned slot) holds, and stops requesting work at
   the first key outside the prefix. The scan must CONTINUE across values
   that compare equal but differ in content identity (1 and 1.0, 1.0M and
   1.00M, 0.0 and -0.0, which interleave by the later comparator fields);
   content-strict filtering happens AFTER candidate production. Never
   manufacture a generic upper key by replacing unspecified fields with nil:
   under nil-first ordering that is an incorrect upper boundary for a prefix
   range.

   Seek keys are canonical datom slot positions [e a v t m], never
   physically reordered to match a comparator's field priority. Under the
   nil-first comparison of sentinel fields:

     | Selected access path               | Lower sentinel       |
     |------------------------------------|----------------------|
     | EAVT, entity e                     | [e nil nil nil nil]  |
     | AEVT, attribute a                  | [nil a nil nil nil]  |
     | AVET, attribute a and value v      | [nil a v nil nil]    |
     | VAET, value v                      | [nil nil v nil nil]  |
     | Full EAVT walk                     | no lower bound       |

   These are seek keys, not stored datoms. (This is usage documentation only:
   the namespace depends on none of it.)")


(def validation-chunk
  "The most keys one validating transition examines."
  32)


(defn- fail
  [kind message data]
  (throw (ex-info message (assoc data ::error kind))))


;; ---------------------------------------------------------------------------
;; start
;; ---------------------------------------------------------------------------

(defn start
  "A new cursor over the tree described by `descriptor`, pinning
   `comparator` (a two-argument function returning a negative, zero or
   positive number) for its whole life. Performs no I/O."
  [descriptor comparator]
  (let [{:keys [root lower upper], cnt :count} descriptor]
    (when-not (fn? comparator)
      (fail :invalid-descriptor "a comparator function is required" {}))
    (when-not (and (integer? cnt) (not (neg? cnt)))
      (fail :invalid-descriptor ":count must be a nonnegative integer" {}))
    (when (and (pos? cnt) (nil? root))
      (fail :invalid-descriptor "a non-empty tree needs a root address"
            {:count cnt}))
    (doseq [[k b] [[:lower lower] [:upper upper]]]
      (when-not (or (nil? b) (and (map? b) (contains? b :key)))
        (fail :invalid-descriptor "a bound is nil or {:key k :inclusive? bool}"
              {:bound k})))
    {::phase (if (or (zero? cnt) (nil? root)) :done :begin)
     ::cmp comparator
     ::root root
     ::lower lower
     ::upper upper
     ::seeking? (some? lower)
     ::path []
     ::leaf nil
     ::pending nil
     ::incoming nil
     ::vi 0
     ::shaped? false
     ::last-key nil
     ::has-last? false
     ::satisfied nil}))


;; ---------------------------------------------------------------------------
;; supply
;; ---------------------------------------------------------------------------

(defn supply
  "Hand the requested node to a waiting cursor. Returns a cursor that the
   next `step` calls validate. Throws a structured error when nothing is
   pending, the address differs, or the request was already satisfied."
  [cursor response]
  (let [pending (::pending cursor)
        address (:address response)]
    (cond
      (not= :awaiting (::phase cursor))
      (if (and (some? (::satisfied cursor)) (= address (::satisfied cursor)))
        (fail :already-satisfied "the request was already satisfied"
              {:address address})
        (fail :unexpected-response "no node is pending" {:address address}))

      (not= address (:address pending))
      (fail :address-mismatch "response does not match the pending address"
            {:expected (:address pending), :actual address})

      :else
      (assoc cursor
             ::phase :validate
             ::pending nil
             ::incoming {:address address, :blob (:node response)}
             ::vi 0
             ::shaped? false
             ::satisfied address))))


;; ---------------------------------------------------------------------------
;; helpers
;; ---------------------------------------------------------------------------

(defn- first-ge
  "First index in keys whose key compares >= k; (count keys) when none.
   A binary search: at most 32 comparisons."
  [cmp ks k]
  (loop [low 0
         high (count ks)]
    (if (< low high)
      (let [mid (quot (+ low high) 2)]
        (if (neg? (cmp (nth ks mid) k))
          (recur (inc mid) high)
          (recur low mid)))
      low)))


(defn- need
  [cursor address]
  {:status :need-node
   :address address
   :cursor (assoc cursor ::phase :awaiting ::pending {:address address})})


(defn- continue
  [cursor]
  {:status :continue, :cursor cursor})


(defn- node-level
  [blob]
  (if (contains? blob :addresses) (:level blob) 0))


(defn- check-shape!
  "Constant-work shape checks of an incoming node; returns nil or throws."
  [cursor]
  (let [{:keys [address blob]} (::incoming cursor)
        branch? (and (map? blob) (contains? blob :addresses))]
    (when-not (map? blob)
      (fail :malformed-node "node is not a map" {:address address}))
    (when-not (vector? (:keys blob))
      (fail :malformed-node "node :keys is not a vector" {:address address}))
    (when (zero? (count (:keys blob)))
      (fail :malformed-node "node has no keys" {:address address}))
    (if branch?
      (do
        (when-not (and (integer? (:level blob)) (pos? (:level blob)))
          (fail :malformed-node "branch needs a positive integer level"
                {:address address}))
        (when-not (vector? (:addresses blob))
          (fail :malformed-node "branch :addresses is not a vector"
                {:address address}))
        (when-not (= (count (:keys blob)) (count (:addresses blob)))
          (fail :malformed-node "branch keys and addresses differ in length"
                {:address address})))
      (when (contains? blob :level)
        (fail :malformed-node "a leaf carries no level" {:address address})))
    (when-let [parent (peek (::path cursor))]
      (when-not (< (node-level blob) (:level parent))
        (fail :inconsistent-level "child level is not lower than its parent's"
              {:address address, :level (node-level blob),
               :parent-level (:level parent)})))
    nil))


(defn- validate-step
  [cursor]
  (if-not (::shaped? cursor)
    (do (check-shape! cursor)
        (continue (assoc cursor ::shaped? true ::vi 0)))
    (let [cmp (::cmp cursor)
          {:keys [address blob]} (::incoming cursor)
          ks (:keys blob)
          addrs (:addresses blob)
          n (count ks)
          from (::vi cursor)
          to (min n (+ from validation-chunk))]
      (loop [i from]
        (when (< i to)
          (when (and addrs (nil? (nth addrs i)))
            (fail :malformed-node "branch child address missing"
                  {:address address, :index i}))
          (when (and (< (inc i) n) (not (neg? (cmp (nth ks i) (nth ks (inc i))))))
            (fail :unordered-keys "node keys are not strictly ordered"
                  {:address address, :index i}))
          (recur (inc i))))
      (if (< to n)
        (continue (assoc cursor ::vi to))
        (do
          (when-let [parent (peek (::path cursor))]
            (let [sep (nth (get-in parent [:blob :keys]) (:pos parent))]
              (when-not (zero? (cmp (nth ks (dec n)) sep))
                (fail :separator-mismatch
                      "child maximum differs from the parent separator"
                      {:address address, :parent (:address parent)}))))
          (continue (assoc cursor ::phase :enter)))))))


(defn- enter-step
  [cursor]
  (let [cmp (::cmp cursor)
        {:keys [address blob]} (::incoming cursor)
        ks (:keys blob)
        lower (::lower cursor)
        seeking? (::seeking? cursor)
        base (assoc cursor ::incoming nil)]
    (if (contains? blob :addresses)
      (let [idx (if seeking? (first-ge cmp ks (:key lower)) 0)]
        (cond
          (< idx (count ks))
          (need (assoc base ::path (conj (::path cursor)
                                         {:address address
                                          :blob blob
                                          :level (:level blob)
                                          :pos idx}))
                (nth (:addresses blob) idx))

          (empty? (::path cursor))
          (continue (assoc base ::phase :done))

          :else
          (fail :separator-mismatch "no child can hold the sought key"
                {:address address})))
      (let [idx (if seeking? (first-ge cmp ks (:key lower)) 0)
            idx (if (and seeking?
                         (not (:inclusive? lower))
                         (< idx (count ks))
                         (zero? (cmp (nth ks idx) (:key lower))))
                  (inc idx)
                  idx)]
        (continue (assoc base
                         ::phase :yield
                         ::seeking? false
                         ::leaf {:address address, :blob blob, :pos idx}))))))


(defn- yield-step
  [cursor]
  (let [cmp (::cmp cursor)
        {:keys [blob pos] :as leaf} (::leaf cursor)
        ks (:keys blob)]
    (if (>= pos (count ks))
      (continue (assoc cursor ::phase :advance ::leaf nil))
      (let [k (nth ks pos)
            upper (::upper cursor)]
        (when (and (::has-last? cursor)
                   (not (pos? (cmp k (::last-key cursor)))))
          (fail :unordered-keys "keys are not increasing across the traversal"
                {:address (:address leaf)}))
        (if (and upper
                 (let [d (cmp k (:key upper))]
                   (not (or (neg? d) (and (:inclusive? upper) (zero? d))))))
          {:status :done
           :cursor (assoc cursor ::phase :done ::leaf nil)}
          {:status :item
           :value k
           :cursor (assoc cursor
                          ::leaf (assoc leaf :pos (inc pos))
                          ::last-key k
                          ::has-last? true)})))))


(defn- advance-step
  [cursor]
  (let [path (::path cursor)]
    (if (empty? path)
      {:status :done, :cursor (assoc cursor ::phase :done)}
      (let [frame (peek path)
            next-pos (inc (:pos frame))
            addrs (get-in frame [:blob :addresses])]
        (if (< next-pos (count addrs))
          (need (assoc cursor ::path (conj (pop path) (assoc frame :pos next-pos)))
                (nth addrs next-pos))
          (continue (assoc cursor ::path (pop path))))))))


;; ---------------------------------------------------------------------------
;; step
;; ---------------------------------------------------------------------------

(defn step
  "One local transition of `cursor`: see the namespace docstring for the four
   outcomes. Stepping a waiting cursor returns its pending request unchanged;
   stepping a finished cursor returns :done."
  [cursor]
  (case (::phase cursor)
    :begin (need (assoc cursor ::seeking? (some? (::lower cursor)))
                 (::root cursor))
    :awaiting {:status :need-node
               :address (:address (::pending cursor))
               :cursor cursor}
    :validate (validate-step cursor)
    :enter (enter-step cursor)
    :yield (yield-step cursor)
    :advance (advance-step cursor)
    :done {:status :done, :cursor cursor}))

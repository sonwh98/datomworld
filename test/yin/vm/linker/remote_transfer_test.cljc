(ns yin.vm.linker.remote-transfer-test
  "In-process reference for linker transfers over remote ring streams
   (docs/design/yin.vm.linker.md, section 11). Both legs run on each
   lane's host; cross-host-transfer-test supplies the JVM publisher and
   separate Dart receiver required by criteria 5a and 10.

   The publisher holds a dao.jing store and serves its content pair
   through dao.stream.remote. The receiver starts with the identity
   (H or R), index, and channel, and attaches reflections of the two
   content streams. The fetched contract-pinned images lift to named
   vectors, load, and execute B0-equal to local execution. A swapped
   identity, corrupt payload, and absent address are qualified refusals.
   The shared channel is a pair of ring buffers."
  (:require [clojure.test :refer [deftest is testing]]
            [dao.jing :as jing]
            [dao.jing.cbor :as cbor]
            [dao.jing.content :as jing-content]
            [dao.jing.mem :as mem]
            [dao.stream :as stream]
            [dao.stream.remote :as remote]
            [dao.stream.ringbuffer :as ring]
            [yin.vm :as vm]
            [yin.vm.debruijn-linearize :as dl]
            [yin.vm.debruijn-register-compile :as rc]
            [yin.vm.debruijn-vm-contract-test :as b0]
            [yin.vm.linker :as linker]
            [yin.vm.semantic :as semantic]
            [yin.vm.test-utils :as tu]))


;; =============================================================================
;; Fixtures: two programs and the two contract-pinned formats
;; =============================================================================

(defn- lit
  [v]
  {:type :literal, :value v})


(defn- v
  [s]
  {:type :variable, :name s})


(defn- lam
  [params body]
  {:type :lambda, :params params, :body body})


(defn- app
  [op & args]
  {:type :application, :operator op, :operands (vec args)})


(defn- tail
  [node]
  (assoc node :tail? true))


(def worked-example
  "`((fn [x] (+ x 1)) 10)`: one free name, `+`."
  (app (lam '[x] (tail (app (v '+) (v 'x) (lit 1)))) (lit 10)))


(def other-program
  "`(* 6 7)`: the image a swapped index entry would deliver instead."
  (app (v '*) (lit 6) (lit 7)))


(defn- stack-image
  "The H image `ast` lowers to (section 5.3)."
  [ast]
  (:image (dl/adapt (second (vm/ast->datoms-with-root ast)))))


(defn- register-image
  "The R image `ast` lowers to (section 5.4)."
  [ast]
  (:image (rc/adapt (second (vm/ast->datoms-with-root ast)))))


(def ^:private pinned-formats
  "The two contract-pinned formats, each beside the lowering that mints
   its images and the lift that raises a fetched image to a named
   vector (section 3: H and R)."
  [[:H linker/stack-format stack-image dl/lift]
   [:R linker/register-format register-image rc/lift]])


(def ^:private receiver-env
  "The receiving host's free-name environment: the full primitive
   registry, nothing shadowing."
  {:primitives vm/primitives})


(defn- requested
  "The fetch options of a receiver that runs `format`'s own contract
   (section 4.2, step 0): the request names the contract it runs."
  [format]
  {:contract (:contract format)})


;; =============================================================================
;; The two legs
;; =============================================================================

(defn- ring-handle
  "One open ring-buffer medium of `capacity` elements."
  [capacity]
  (:dao.stream/handle
    (ring/create! {:dao.stream/type ring/transport-type
                   ring/capacity-key capacity})))


(def ^:private channel-descriptor
  "The one channel the two legs share.  Both served streams ride it,
   under their own identities, on one link -- the remote machinery's
   one-link-per-channel rule."
  {:dao.stream/type :dao.stream.test/channel
   :dao.stream/identity "linker-transfer-channel"})


(defn- publisher-leg
  "The publishing leg: a dao.jing store, its content pair on two ring
   buffers, and the mirror that reaches the pair across the channel.
   It holds the images; what crosses to the receiver is only what the
   receiver's own requests draw out."
  []
  (let [requests (ring-handle 64)
        responses (ring-handle 64)
        in (ring-handle 64)
        out (ring-handle 64)
        oldest (fn [h]
                 (:dao.stream/cursor
                   (stream/cursor h :dao.stream/oldest)))]
    {:store (mem/create-content-mem)
     :requests requests
     :responses responses
     :in in
     :out out
     :table {"content-requests"
             {:handle requests, :surface #{:reader :writer}}
             "content-answers"
             {:handle responses, :surface #{:reader :writer}}}
     :mirror-cursor (atom (oldest in))
     :serve-cursor (atom (oldest requests))}))


(defn- pump!
  "One advance of the publishing leg: the mirror answers every channel
   request pending against its table, then the content interpreter
   answers every content request the mirror let through.  The leg owns
   no thread; the receiver's drive calls it, exactly as the leg's own
   ticker would on a host of its own."
  [{:keys [in out table mirror-cursor serve-cursor store requests
           responses]}]
  (swap! mirror-cursor #(remote/mirror-step table in % out))
  (swap! serve-cursor
         (fn [cursor]
           (loop [c cursor]
             (let [c' (jing-content/serve-step store requests c
                                               responses 32)]
               (if (= c c') c' (recur c')))))))


(defn- receiver-leg
  "The receiving leg's harness: a reflection of each stream of the
   content pair, attached through the channel, and a link state
   holding only `format`'s record, the identity-to-address index
   `index`, and the pair.  No store, no handle, no image: exactly the
   starting state criterion 5a names.  Returns `fetch`'s runtime."
  [pub format index]
  (let [cd channel-descriptor
        attach! (remote/attacher
                  {:dao.stream.remote/channels
                   {cd {:reader (:out pub), :writer (:in pub)}}})
        attach (fn [identity]
                 (:dao.stream/handle
                   (attach! {:dao.stream/type :dao.stream/remote
                             :dao.stream/identity identity
                             :dao.stream/channel cd})))]
    {:state (linker/link-state
              {:formats {(:format format) format}
               :indexes {(:format format) index}
               :content {:requests (attach "content-requests")
                         :answers (attach "content-answers")
                         :cursor :dao.stream/newest}})
     :drive (fn [state] (pump! pub) state)}))


(defn- publish
  "Mint `image` onto the publishing leg for `format`; return its
   identity and the index -- the claim, and all the receiver holds."
  [pub format image]
  (let [datom (linker/publish! (:store pub) format image)]
    {:identity (first datom)
     :index (linker/index-from-datoms format [datom])}))


;; =============================================================================
;; B0 execution parity
;; =============================================================================

(defn- named-value
  "The B0-normalized value `ast` evaluates to, executed locally."
  [ast]
  (b0/normalize (vm/value (vm/eval (tu/create-vm) ast))))


(defn- lifted-value
  "The B0-normalized value the named vector `named-vector` runs to
   under the semantic VM."
  [named-vector]
  (b0/normalize
    (vm/value (vm/run (semantic/load-vector (semantic/create-vm)
                                            named-vector
                                            vm/semantic-contract)))))


;; =============================================================================
;; Criterion 5a: the transfer, and criterion 5b: B0-equal execution
;; =============================================================================

(deftest an-image-transfers-to-a-receiver-holding-identity-and-index
  (doseq [[label format mint lift] pinned-formats]
    (testing label
      (let [pub (publisher-leg)
            image (mint worked-example)
            {:keys [identity index]} (publish pub format image)
            res (linker/fetch (receiver-leg pub format index)
                              (:format format) identity receiver-env
                              (requested format))]
        (is (linker/ok? res) (pr-str res))
        (is (= (:format format) (:format res)))
        (is (= identity (:identity res)))
        (is (= (get index identity) (:address res)))
        (is (= image (:value res))
            "the image the publisher minted is the image received")
        (is (= '[+] (mapv :name (:obligations res)))
            "the free `+` is the image's one retained obligation")
        (is (= (named-value worked-example)
               (lifted-value (lift (:value res))))
            "lifted, the transferred image runs under the semantic VM
             B0-equal to the local evaluation")
        (jing/close! (:store pub))))))


;; =============================================================================
;; Criterion 6 and 5c: the refusals over the same channel
;; =============================================================================

(deftest a-swapped-identity-is-refused-over-the-stream
  (doseq [[label format mint _lift] pinned-formats]
    (testing label
      (let [pub (publisher-leg)
            wanted ((:identity-fn format) (mint worked-example))
            [actual _attr address] (linker/publish! (:store pub) format
                                                    (mint other-program))
            index {wanted address}
            res (linker/fetch (receiver-leg pub format index)
                              (:format format) wanted receiver-env
                              (requested format))]
        (is (= {:status :refused, :reason :hash-mismatch,
                :expected wanted, :actual actual}
               res)
            "the index's claim is checked, never trusted: the address
             served, the identity refused")
        (jing/close! (:store pub))))))


(defn- corrupting
  "The store answering every read it holds with corrupted bytes: the
   canonical bytes of another value, which no longer hash to the
   address they are served under."
  [store]
  (assoc store
         :get-bytes-fn
         (fn [address not-found]
           (let [x ((:get-bytes-fn store) address not-found)]
             (if (identical? x not-found)
               x
               (jing/canonical-bytes [:tampered (cbor/decode x)]))))))


(deftest a-corrupt-payload-is-refused-over-the-stream
  (doseq [[label format mint _lift] pinned-formats]
    (testing label
      (let [pub (publisher-leg)
            image (mint worked-example)
            {:keys [identity index]} (publish pub format image)
            corrupt (assoc pub :store (corrupting (:store pub)))
            res (linker/fetch (receiver-leg corrupt format index)
                              (:format format) identity receiver-env
                              (requested format))]
        (is (= :address-mismatch (:reason res)) (pr-str res))
        (is (= (get index identity) (:address res)))
        (is (not (contains? res :value))
            "mismatched bytes are refused undecoded")
        (jing/close! (:store pub))))))


(deftest an-address-the-publisher-lacks-is-absent-over-the-stream
  (doseq [[label format mint _lift] pinned-formats]
    (testing label
      (let [pub (publisher-leg)
            identity ((:identity-fn format) (mint worked-example))
            index {identity (jing/segment-key [:none])}
            res (linker/fetch (receiver-leg pub format index)
                              (:format format) identity receiver-env
                              (requested format))]
        (is (= {:status :refused, :reason :absent,
                :address (jing/segment-key [:none])}
               res)
            "not found on the stream is the linker's :absent")
        (jing/close! (:store pub))))))

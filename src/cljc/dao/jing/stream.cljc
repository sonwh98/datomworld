(ns dao.jing.stream
  "The explicit Jing/transport boundary adapter (D7's condition, U10).

   \"`dao.jing.cbor` runs seamlessly on either\" stream protocol means an
   adapter here, not the storage codec becoming the transport codec: Jing
   content crosses a `dao.stream` as its canonical bytes —
   `dao.jing/canonical-bytes`, the exact bytes the content address digests
   — never as a bare payload value, so no transport's value domain ever
   re-encodes or silently normalizes the content (Transit carries no
   metadata at all, and neither profile is Jing's normalization domain).

   The wrapped form is named on both lanes: `{:dao.jing/canonical-bytes …}`
   carrying, on the `dao.stream.cbor` profile, the bytes themselves as a
   CBOR byte string, and on the `dao.stream.transit-json` profile, a vector
   of octets (0–255) — D7's named lossless representation in lieu of a
   rejected Base64 wrap. `unwrap-bytes` accepts exactly the form
   `wrap-bytes` produced for that profile and throws on anything else, so a
   byte string that crossed the wrong lane fails loudly at the boundary
   instead of being reinterpreted.

   Jing's own content-address/store migration (`dao.jing.cbor.md`) is
   unchanged by this namespace: it changes what the canonical bytes are,
   and the adapter then carries the new bytes the same way."
  (:require [dao.jing :as jing]
            [dao.stream.codec :as codec])
  ;; :cljd first: the cljd host pass also matches :clj, so a :clj-first
  ;; conditional would still reach the Dart compiler.
  #?@(:cljd [(:import ["dart:typed_data" Uint8List])]))


(defn- checked-profile
  "The profile itself, or a throw: nil, {} and malformed profiles are
   rejected before any lane is chosen."
  [profile]
  (when-not (codec/profile? profile)
    (throw (ex-info "dao.jing.stream: not a valid stream codec profile"
                    {:profile profile})))
  profile)


(defn- octets
  "A payload's bytes as plain 0-255 integers — the transit lane's lossless
   representation, host byte-sign conventions left behind."
  [bs]
  (mapv #(bit-and 0xff %) bs))


(defn- byte-payload
  "Host bytes (byte[] / Uint8Array / Uint8List) from 0-255 integers."
  [xs]
  #?(:clj (byte-array (mapv unchecked-byte xs))
     :cljs (js/Uint8Array.from (to-array xs))
     :cljd (Uint8List.fromList xs)))


(defn wrap-bytes
  "Wrap canonical Jing bytes as one stream value for the codec profile's
   lane: the bytes themselves as a byte string when the profile carries host
   bytes (`dao.stream.cbor`), a named vector of octets otherwise
   (`dao.stream.transit-json`). A nil or malformed profile throws."
  [profile bs]
  (checked-profile profile)
  (if (codec/bytes? profile)
    {:dao.jing/canonical-bytes bs}
    {:dao.jing/canonical-bytes (octets bs)}))


(defn unwrap-bytes
  "Recover the canonical Jing bytes one stream value carries, the inverse of
   `wrap-bytes` for the same profile. Throws on a value of the wrong shape
   or one wrapped for the other lane: a CBOR byte string reaching the
   Transit unwrap is a payload that crossed the wrong boundary."
  [profile value]
  (checked-profile profile)
  (let [reject (fn [detail]
                 (throw (ex-info "dao.jing.stream: value does not carry Jing bytes for this profile"
                                 (assoc detail :subprotocol (codec/codec-name profile)))))]
    (when-not (and (map? value) (= 1 (count value))
                   (contains? value :dao.jing/canonical-bytes))
      (reject {:value value}))
    (let [carried (:dao.jing/canonical-bytes value)]
      (if (codec/bytes? profile)
        (if (codec/host-bytes? carried)
          carried
          (reject {:carried carried}))
        (if (and (vector? carried)
                 (not (codec/host-bytes? carried))
                 (every? #(and (int? %) (<= 0 % 255)) carried))
          (byte-payload carried)
          (reject {:carried carried}))))))


(defn wrap-payload
  "Wrap one Jing payload's canonical bytes for the profile's lane — the
   boundary operation itself. There is no unwrap-payload: the canonical
   print is not invertible here, and reconstruction is the receiver's read
   of the bytes (`clojure.edn` over the text, as the file backend replays
   frames); what crosses the stream is the addressed byte stream, verified
   by `(jing/segment-matches? ...)` against the claimed address."
  [profile v]
  (wrap-bytes profile (jing/canonical-bytes v)))

(ns thetao.vm.content-store
  #?(:cljs
     (:require
       [goog.crypt :as gcrypt]
       [goog.crypt.Sha256])))


(defn canonicalize
  [value]
  (cond
    (map? value)
    (->> value
         (map (fn [[k v]] [(canonicalize k) (canonicalize v)]))
         (sort-by (comp pr-str first))
         vec)

    (vector? value)
    (mapv canonicalize value)

    (seq? value)
    (mapv canonicalize value)

    (set? value)
    (->> value
         (map canonicalize)
         (sort-by pr-str)
         vec)

    :else value))


#?(:clj
   (defn- sha256-bytes
     [s]
     (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
       (.digest digest (.getBytes s "UTF-8"))))

   :cljs
   (defn- sha256-bytes
     [s]
     (let [digest (goog.crypt.Sha256.)]
       (.update digest (gcrypt/stringToUtf8ByteArray s))
       (.digest digest))))


#?(:clj
   (defn- bytes->hex
     [bs]
     (apply str (map #(format "%02x" (bit-and % 0xff)) bs)))

   :cljs
   (defn- bytes->hex
     [bs]
     (apply str (map #(let [v (bit-and % 0xff)]
                        (if (< v 16)
                          (str "0" (.toString v 16))
                          (.toString v 16)))
                     bs))))


(defn content-hash
  [value]
  (let [encoded (pr-str (canonicalize value))]
    (str "sha256:" (bytes->hex (sha256-bytes encoded)))))


(defn store!
  [store-atom value]
  (let [hash (content-hash value)]
    (swap! store-atom assoc hash (canonicalize value))
    hash))


(defn lookup
  [store-atom hash]
  (get @store-atom hash))

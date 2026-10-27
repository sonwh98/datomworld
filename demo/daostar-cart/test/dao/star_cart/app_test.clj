(ns dao.star-cart.app-test
  "The demo end to end on loopback sockets: the shop and the grid on one port,
   over the real boundary, leases, router and driver, with the catalog served
   from dao.space."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [dao.star-cart.edge :as edge]
            [dao.star-cart.store :as store]
            [dao.star-cart.support :as sup]))


(def ^:dynamic *site* nil)


(use-fixtures :each
  (fn [t]
    (let [site (sup/start-site!)]
      (try (binding [*site* site] (t))
           (finally ((:stop! site)))))))


(defn- port
  []
  (:port *site*))


(defn- named
  []
  (remove #(sup/blank? (:name %)) (store/items (:store *site*))))


(defn- subscribed
  "Begin a lineage, subscribe, and wait for the first projection.  Answers
   `{:sse :root :token}`."
  [prefix]
  (let [{:keys [root]} (sup/begin! (port) prefix)
        sse (sup/open-sse (port) (str prefix "/s/" root))]
    (is (sup/wait-for 8000 #(str/includes? @(:text sse) "datastar-patch-elements")) "first projection")
    {:sse sse :root root :token (sup/token-of @(:text sse))}))


(defn- sse-text
  [{:keys [sse]}]
  @(:text sse))


(deftest the-full-page-renders-the-real-catalog-with-real-image-urls
  (let [{:keys [page root]} (sup/begin! (port) "")
        urls (set (map #(:image/url %) (map (fn [i] {:image/url (first (:images i))}) (named))))]
    (is (str/starts-with? page "HTTP/1.1 200"))
    (is (str/includes? page "<!doctype html>"))
    (is (some? root))
    (let [sse (sup/open-sse (port) (str "/s/" root))]
      (is (sup/wait-for 8000 #(str/includes? @(:text sse) "Catalog as of last page load.")))
      (let [text @(:text sse)
            srcs (map (comp #(java.net.URLDecoder/decode (str/replace % "+" "%2B") "UTF-8") second)
                      (re-seq #"<img src=\"(/images/[^\"]+)\"" text))]
        (is (= 247 (count (named))))
        (is (= (count (named)) (count srcs)) "one image per real item")
        (is (every? urls srcs) "each src is a catalog :image/url"))
      (sup/close-sse sse))))


(deftest the-image-urls-on-the-first-page-resolve
  (let [{:keys [sse]} (subscribed "")
        srcs (distinct (map (comp #(java.net.URLDecoder/decode (str/replace % "+" "%2B") "UTF-8") second)
                            (re-seq #"<img src=\"(/images/[^\"]+)\"" (sse-text {:sse sse}))))]
    (is (seq srcs))
    (doseq [u (take 12 srcs)
            :let [enc (str/replace u " " "%20")
                  r (sup/http (port) "GET" enc)]]
      (is (= 200 (:status r)) u)
      (is (re-find #"(?i)content-type: image/" (:text r)) u)
      (is (> (count (:bytes r)) 500) u))
    (sup/close-sse sse)))


(deftest ^:slow every-image-url-resolves
  (let [urls (distinct (mapcat :images (named)))]
    (doseq [u urls
            :let [r (sup/http (port) "GET" (str/replace u " " "%20"))]]
      (is (= 200 (:status r)) u))))


(deftest add-to-cart-parks-and-resumes-with-state-intact
  (let [[a b] (take 2 (vals (into (sorted-map) (map (juxt (comp str/trim :name) identity)
                                                    (filter #(pos? (:price %)) (named))))))
        {:keys [sse token]} (subscribed "")
        add (fn [tok slug] (sup/token-of (sup/input! (port) "" tok {:act "add" :arg slug})))
        t1 (add token (:key a))
        t2 (add t1 (:key b))
        t3 (add t2 (:key a))]
    (is (every? some? [t1 t2 t3]))
    (is (= 4 (count (set [token t1 t2 t3]))) "one successor per consumed input")
    (let [cart (sup/input! (port) "" t3 {:act "nav" :arg "cart"})
          qty (fn [name] (second (re-find (re-pattern (str "<td>" (java.util.regex.Pattern/quote (str/escape name {\< "&lt;" \> "&gt;" \& "&amp;"})) "</td>.*?<span class=\"qty\">(\\d+)</span>")) cart)))]
      (is (str/includes? cart "Cart (3)"))
      (is (str/includes? cart (str/escape (:name a) {\< "&lt;" \> "&gt;" \& "&amp;"})))
      (is (= "2" (qty (:name a))) "a third add shows qty 2: the state lives in the continuation")
      (is (str/includes? cart "Total: <strong>"))
      (let [expected (+ (* 2 (:price a)) (:price b))]
        (is (str/includes? cart (str "Total: <strong>$" (quot expected 100) "." (format "%02d" (rem expected 100)) "</strong>")))))
    (testing "the open subscription was patched with each state"
      (is (sup/wait-for 5000 #(str/includes? (sse-text {:sse sse}) "Cart (3)"))))
    (sup/close-sse sse)))


(deftest a-stale-token-is-harmless
  (let [[a] (named)
        {:keys [sse token]} (subscribed "")
        t1 (sup/token-of (sup/input! (port) "" token {:act "add" :arg (:key a)}))
        stale (sup/input! (port) "" token {:act "add" :arg (:key a)})]
    (is (some? t1))
    (is (str/includes? stale "Cart (1)") "the cart did not change")
    (is (not (str/includes? stale "Cart (2)")))
    (is (= t1 (sup/token-of stale)) "re-projected at the current occurrence")
    (sup/close-sse sse)))


(deftest a-reconnect-re-projects-the-current-cart
  (let [[a] (named)
        {:keys [sse root token]} (subscribed "")
        _ (sup/input! (port) "" token {:act "add" :arg (:key a)})
        _ (sup/close-sse sse)
        again (sup/open-sse (port) (str "/s/" root))]
    (is (sup/wait-for 8000 #(str/includes? @(:text again) "Cart (1)")) "subscribe consumes nothing and shows the cart")
    (sup/close-sse again)))


(deftest the-grid-lists-the-catalog-and-saves-edits-the-shop-sees-on-navigation
  (let [[a] (named)
        shop (subscribed "")
        old-price (str "$" (quot (:price a) 100) "." (format "%02d" (rem (:price a) 100)))
        grid (subscribed "/grid")]
    (testing "the spreadsheet lists every item"
      (is (= 249 (sup/count-matches #"<tr><td class=\"th-img\">" (sse-text grid)))))
    (testing "a cell edit saves on change, then saved after the transact"
      ;; every consumed input (the cell AND the interpreter's confirmation)
      ;; advances the occurrence, so each post goes to the CURRENT token,
      ;; read from the stream exactly as the browser's $tok signal does
      (let [reply (sup/input! (port) "/grid" (:token grid)
                              {:act "cell" :arg (str (:key a) "|price") :val "4242"})]
        (is (str/includes? reply "saving") "the program renders saving first")
        (is (sup/wait-for 8000 #(str/includes? (sse-text grid) "class=\"status saved\"")) "then saved, after the transact")
        (is (= 4242 (:price (store/item (:store *site*) (:key a)))))
        (let [reply2 (sup/input! (port) "/grid" (sup/token-of (sse-text grid))
                                 {:act "cell" :arg (str (:key a) "|description") :val "edited"})]
          (is (sup/wait-for 8000 #(str/includes? (sse-text grid) "edited")))
          (is (= "edited" (:description (store/item (:store *site*) (:key a))))))))
    (testing "the shop's open view is stale until navigation"
      (is (not (str/includes? (sse-text shop) "$42.42")))
      (is (str/includes? (sse-text shop) old-price))
      (let [r (sup/input! (port) "" (:token shop) {:act "nav" :arg "catalog"})]
        (is (str/includes? r "$42.42") "the next navigation runs a fresh query")))
    (testing "a rejected cell reports why and changes nothing"
      (let [_ (sup/input! (port) "/grid" (sup/token-of (sse-text grid))
                          {:act "cell" :arg (str (:key a) "|price") :val "-5"})]
        (is (sup/wait-for 8000 #(str/includes? (sse-text grid) "rejected: price must be a whole number")))
        (is (= 4242 (:price (store/item (:store *site*) (:key a)))))))
    (testing "the tagories cell applies the whole set (add and remove)"
      (let [_ (sup/input! (port) "/grid" (sup/token-of (sse-text grid))
                          {:act "cell" :arg (str (:key a) "|tagories") :val "Alpha Tag, Beta Tag"})]
        (is (sup/wait-for 8000 #(str/includes? (sse-text grid) "class=\"status saved\"")))
        (is (= ["Alpha Tag" "Beta Tag"] (:tagories (store/item (:store *site*) (:key a)))))))
    (testing "the images cell applies the whole set (existing paths only)"
      (let [_ (sup/input! (port) "/grid" (sup/token-of (sse-text grid))
                          {:act "cell" :arg (str (:key a) "|images") :val "/images/36.png, /images/nope.png"})]
        (is (sup/wait-for 8000 #(str/includes? (sse-text grid) "rejected: image file does not exist")))
        (is (= ["/images/36.png"] (:images (store/item (:store *site*) (:key a)))))))
    (testing "the edit was written to the catalog file"
      (is (= 4242 (:item/price (first (filter #(= (:key a) (:item/slug %))
                                              (:items (store/read-catalog (:catalog-path *site*)))))))))
    (sup/close-sse (:sse shop))
    (sup/close-sse (:sse grid))))


(deftest static-traversal-is-refused-over-the-wire
  (doseq [path ["/images/../deps.edn" "/images/%2e%2e/deps.edn" "/grid/images/../deps.edn"
                "/images//etc/passwd"]]
    (let [r (sup/http (port) "GET" path)]
      (is (= 404 (:status r)) path)
      (is (not (str/includes? (:text r) ":paths")) path)))
  (is (= "image/png"
         (second (re-find #"(?i)content-type: (image/png)" (:text (sup/http (port) "GET" "/images/7..png")))))))


(deftest the-edge-helper-agrees-with-the-wire
  (is (= "/s/x" (edge/strip-grid "/grid/s/x"))))

(ns dao.star-cart.view
  "Pure projections for the dao.star cart's shop face, and the helpers the grid
   face shares: HTML escaping, money, the Datastar SSE framing, and the begin
   page.

   Datastar is pinned at `datastar-version`.  Its SSE vocabulary at that
   version: an event named `datastar-patch-elements` whose data lines are
   prefixed `elements ` (the HTML to morph, matched by element id), and
   `datastar-patch-signals` whose data lines are prefixed `signals `.  An event
   ends with a blank line.  Actions are `data-on:<event>` attributes calling
   `@get`/`@post`; the request's signals ride the query (GET) or JSON body."
  (:require [clojure.string :as str]))


(def datastar-version "1.0.0-RC.7")


(defn esc
  "HTML-escape text for element content and double-quoted attributes."
  [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&#39;")
      ;; CR and LF are SSE line terminators: never emit them raw
      (str/replace "\r" "&#13;")
      (str/replace "\n" "&#10;")))


(defn js-lit
  "A single-quoted JavaScript string literal for use inside an attribute."
  [s]
  (esc (str "'" (-> (str s)
                    (str/replace "\\" "\\\\")
                    (str/replace "'" "\\'")
                    (str/replace "\r" "\\r")
                    (str/replace "\n" "\\n")
                    (str/replace "\u2028" "\\u2028")
                    (str/replace "\u2029" "\\u2029"))
            "'")))


(defn json-lit
  "A double-quoted JSON string literal (for signal patches)."
  [s]
  (str "\"" (-> (str s)
                (str/replace "\\" "\\\\")
                (str/replace "\"" "\\\"")
                (str/replace "\r" "\\r")
                (str/replace "\n" "\\n")
                (str/replace "\u2028" "\\u2028")
                (str/replace "\u2029" "\\u2029"))
       "\""))


(defn enc-path
  "Make an asset path safe inside an attribute and a URL: the characters that
   would end an attribute, a path or start a query are percent-encoded.  Other
   characters (spaces aside) are left for the browser."
  [p]
  (reduce (fn [s [from to]] (str/replace s from to))
          (str p)
          [["%" "%25"] [" " "%20"] ["#" "%23"] ["?" "%3F"] ["\"" "%22"]
           ["'" "%27"] ["<" "%3C"] [">" "%3E"]]))


(defn money
  "Prices are minor units (cents): 2400 renders $24.00."
  [cents]
  (let [c (or cents 0)
        r (rem c 100)]
    (str "$" (quot c 100) "." (if (< r 10) (str "0" r) r))))


(defn sse-elements
  "One `datastar-patch-elements` event carrying `html`, newlines folded."
  [html]
  (str "event: datastar-patch-elements\ndata: elements "
       (str/replace html #"\r\n|\r|\n" " ") "\n\n"))


(defn sse-signals
  "One `datastar-patch-signals` event carrying the JSON text `json`."
  [json]
  (str "event: datastar-patch-signals\ndata: signals " json "\n\n"))


(defn act
  "A click action: set the `act`/`arg` signals and post to the occurrence token.
   `prefix` is \"\" for the shop and \"/grid\" for the grid."
  ([prefix token a] (act prefix token a nil))
  ([prefix token a arg]
   (str "$act=" (js-lit a) "; $arg=" (js-lit (or arg "")) "; @post('"
        prefix "/k/" (esc token) "')")))


(defn button
  [prefix token label a arg]
  (str "<button type=\"button\" data-on:click=\"" (act prefix token a arg) "\">"
       (esc label) "</button>"))


(defn begin-page
  "The complete HTML document at the root: the vendored script, the
   stylesheet, base signals, a subscription to the lineage's token, and
   `pre` — markup placed before the app body (the search input renders there
   once, as a sibling of every patched element)."
  [{:keys [prefix title signals root pre app-id] :or {app-id "app-body"}}]
  (str "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
       "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
       "<title>" (esc title) "</title>"
       "<link rel=\"preconnect\" href=\"https://fonts.googleapis.com\">"
       "<link rel=\"preconnect\" href=\"https://fonts.gstatic.com\" crossorigin>"
       "<link href=\"https://fonts.googleapis.com/css2?family=Inter:wght@400;600&family=Rajdhani:wght@600;700&display=swap\" rel=\"stylesheet\">"
       "<link rel=\"stylesheet\" href=\"/css/cart.css\">"
       "<script type=\"module\" src=\"/js/datastar.js\"></script></head>"
       "<body data-signals=\"" (esc signals) "\" data-init=\"@get('" prefix "/s/" (esc root) "',{retry:'always'})\">"
       pre
       "<div id=\"" app-id "\"><p class=\"loading\">Loading…</p></div></body></html>"))


;; -----------------------------------------------------------------------------
;; Shop
;; -----------------------------------------------------------------------------

(defn- first-image
  [item]
  (first (:images item)))


(defn- cart-lines
  "The cart model (slug -> qty, zero entries dropped) joined with the catalog."
  [catalog cart]
  (vec (for [[slug qty] (sort-by key cart)
             :when (and (number? qty) (pos? qty))
             :let [item ((:item catalog) slug)]
             :when item]
         (assoc item :qty qty))))


(defn cart-total
  [lines]
  (reduce + 0 (map #(* (:qty %) (or (:price %) 0)) lines)))


(defn- card
  [token item]
  (str "<article class=\"card\">"
       (if-let [img (first-image item)]
         (str "<img src=\"" (enc-path img) "\" alt=\"" (esc (:name item)) "\" loading=\"lazy\">")
         "<div class=\"noimg\"></div>")
       "<h3>" (esc (:name item)) "</h3>"
       "<p class=\"price\">" (money (:price item)) "</p>"
       "<div class=\"row\">" (button "" token "View" "item" (:key item))
       (button "" token "Add" "add" (:key item)) "</div></article>"))


(defn- search-box
  "The catalog's search field.  Rendered ONCE, on the begin page, and never
   re-projected: the input is outside every patched element, so typing is
   never clobbered by a morph.  The input binds the `search` signal; the
   debounced handler reads `$search` (event is out of scope under
   `__debounce`) and posts to `/k/` + the `tok` signal, which every
   projection updates to the CURRENT occurrence — so the input never posts
   to the long-consumed root token."
  [_root]
  (str "<section class=\"search\"><input type=\"search\" placeholder=\"Search products…\""
       " data-bind:search"
       " data-on:input__debounce.150ms=\"$act='search'; $arg=$search; @post('/k/'+$tok)\""
       " autocomplete=\"off\"></section>"))


(defn- matches?
  "Case-insensitive substring match of `q` against the item's name.
   An empty query matches everything."
  [q item]
  (let [t (str/trim (str q))]
    (or (empty? t)
        (str/includes? (str/lower-case (str (:name item)))
                       (str/lower-case t)))))


(defn- in-tagory?
  "An empty tagory selection matches everything; otherwise the item must
   carry the tagory."
  [tagory item]
  (or (empty? (str/trim (str tagory)))
      (some #(= tagory %) (:tagories item))))


(defn- tagory-chips
  "The tagory row: All, then one chip per tagory; the selected one is
   highlighted.  A chip is a button with the `tagory` act."
  [token tagories selected]
  (str "<section class=\"tagories\">"
       (button "" token "All" "tagory" "")
       (apply str (for [t tagories]
                    (str "<button type=\"button\" class=\"chip"
                         (when (= t selected) " on")
                         "\" data-on:click=\"" (act "" token "tagory" t) "\">"
                         (esc t) "</button>")))
       "</section>"))


(defn- header
  "The nav tabs strip (the projection's changing part of the header): the
   brand and the search input live on the begin page, never patched."
  [token cart-count]
  (str "<nav class=\"tabs\">"
       (button "" token "All Products" "nav" "catalog")
       (button "" token (str "Cart (" cart-count ")") "nav" "cart")
       "</nav>"))


(defn- disclaimer-banner
  []
  (str "<div class=\"disclaimer\"><span class=\"badge\">⚠ Notice</span>"
       "<span class=\"main\">Used / AS-IS / BER / Returns / Refurbished</span>"
       "<span class=\"sub\">Consoles, Controllers and Electronic Components</span></div>"))


(defn- hero
  "The landing hero, in the production site's voice and copy."
  []
  (str "<section class=\"hero\"><span class=\"tag\">⚡ Gaming Stock &amp; Controllers</span>"
       "<h2>Gaming Hardware &amp; Controllers</h2>"
       "<p>Browse our inventory of consoles, pro controllers, replacement parts, and gaming electronics.</p>"
       "<div class=\"perks\">"
       "<span class=\"perk\">⚠️ <strong>AS-IS / Refurbished</strong> Hardware</span>"
       "<span class=\"perk\">🎮 <strong>Controllers &amp; Parts</strong> In Stock</span>"
       "<span class=\"perk\">⚡ <strong>Fast Dispatch</strong> Worldwide Logistics</span>"
       "<span class=\"perk\">🔒 <strong>Web3 &amp; Crypto</strong> Fast Checkout</span>"
       "</div></section>"))


(defn- catalog-page
  [catalog token q tagory]
  (let [shown (filter #(and (matches? q %) (in-tagory? tagory %))
                      (remove #(empty? (str/trim (str (:name %)))) ((:items catalog))))]
    (str (disclaimer-banner)
         (hero)
         (tagory-chips token ((:tagories catalog)) tagory)
         "<div class=\"section-head\"><h2>All Products</h2>"
         "<span class=\"count\">" (count shown) " items</span></div>"
         "<section class=\"grid\">"
         (apply str (map #(card token %) shown))
         "</section>")))


(defn- item-page
  [catalog token slug]
  (if-let [item ((:item catalog) slug)]
    (str "<section class=\"detail\"><div class=\"gallery\">"
         (apply str (for [img (:images item)]
                      (str "<img src=\"" (enc-path img) "\" alt=\"" (esc (:name item)) "\">")))
         "</div><div><h2>" (esc (:name item)) "</h2><p class=\"price\">" (money (:price item)) "</p>"
         "<p>" (esc (:description item)) "</p>"
         "<p class=\"tags\">" (apply str (for [t (:tagories item)] (str "<span>" (esc t) "</span>"))) "</p>"
         "<div class=\"row\">" (button "" token "Add to cart" "add" slug)
         (button "" token "Back" "nav" "catalog") "</div></div></section>")
    (str "<p>That item is gone.</p>" (button "" token "Back" "nav" "catalog"))))


(defn- cart-page
  [catalog token cart]
  (let [lines (cart-lines catalog cart)]
    (if (empty? lines)
      "<section class=\"cart\"><h2>Your cart</h2><p>The cart is empty.</p></section>"
      (str "<section class=\"cart\"><h2>Your cart</h2><table>"
           (apply str
                  (for [l lines]
                    (str "<tr><td>" (esc (:name l)) "</td><td>" (money (:price l)) "</td><td>"
                         (button "" token "−" "remove" (:key l))
                         " <span class=\"qty\">" (:qty l) "</span> "
                         (button "" token "+" "add" (:key l))
                         "</td><td>" (money (* (:qty l) (:price l))) "</td></tr>")))
           "</table><p class=\"total\">Total: <strong>" (money (cart-total lines)) "</strong></p>"
           (button "" token "Checkout" "checkout" nil) "</section>"))))


(defn- footer
  "The site footer, production copy, plus the demo's honesty note."
  []
  (str "<footer class=\"site\">"
       "<div class=\"ftitle\">Inventory Condition Disclaimer</div>"
       "<p>Used / AS-IS / BER / Returns / Refurbished</p>"
       "<p>Consoles, Controllers and Electronic Components</p>"
       "<p class=\"copy\">© 2026 TT Game Stock — dao.star cart demo. Catalog as of last page load.</p>"
       "</footer>"))


(defn shop-project
  "`(project-fn occurrence-id model)` for the shop, over `catalog`, a map of
   `:items (fn [])` and `:item (fn [slug])`.  Pure: re-projecting a stored
   model re-reads the catalog, so a stale view is a view of the catalog as of
   its last projection.

   The patched element is `#app-body`: the search input on the begin page is
   its SIBLING, so a morph never touches it while the user types.  Every
   projection is preceded by a signals patch updating `tok` to the current
   occurrence: the persistent search input posts to `/k/${tok}`, so it
   always names the CURRENT token, never the long-consumed root (the
   stale-token defect the reviewer found)."
  [catalog token model]
  (let [cart (:cart model)
        n (reduce + 0 (filter pos? (vals cart)))]
    (str (sse-signals (str "{\"tok\":" (json-lit token) "}"))
         (sse-elements
           (str "<div id=\"app-body\">" (header token n)
                "<main>"
                (case (:page model)
                  "item" (item-page catalog token (:item model))
                  "cart" (cart-page catalog token cart)
                  "checkout" "<section class=\"cart\"><h2>Order received</h2><p>This is a demo: no payment was taken.</p></section>"
                  (catalog-page catalog token (:search model) (:tagory model)))
                "</main>" (footer) "</div>")))))


(defn shop-begin
  [root]
  (begin-page {:prefix "" :title "TT Game Stock | Next-Gen Video Game Controllers" :root root
               :signals (str "{act: '', arg: '', search: '', tok: " (json-lit root) "}")
               :pre (str "<header class=\"top\"><div class=\"brand\">"
                         "<div class=\"name\">TT <span>GAME</span> STOCK</div>"
                         "<div class=\"subtitle\">PRO CONTROLLERS &amp; GEAR</div>"
                         "</div>" (search-box root) "</header>")}))

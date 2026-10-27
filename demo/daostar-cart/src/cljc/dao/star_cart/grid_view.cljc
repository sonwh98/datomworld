(ns dao.star-cart.grid-view
  "Pure projection for the grid face.  Every action URL carries the `/grid`
   prefix, because the host edge strips it before the grid's own boundary."
  (:require [dao.star-cart.view :as v]))


(def prefix "/grid")


(defn- btn
  [token label a arg]
  (v/button prefix token label a arg))


(defn- row
  [token sel item]
  (str "<li class=\"" (if (= sel (:key item)) "sel" "") "\"><span class=\"nm\">" (v/esc (:name item))
       "</span><span class=\"pr\">" (v/money (:price item)) "</span>"
       "<button type=\"button\" data-on:click=\"$name=" (v/js-lit (:name item)) "; $price=" (v/js-lit (:price item))
       "; $desc=" (v/js-lit (:description item)) "; " (v/act prefix token "select" (:key item)) "\">Edit</button></li>"))


(defn- editor
  [token item]
  (str "<section class=\"editor\"><h2>" (v/esc (:name item)) "</h2>"
       "<label>Name <input data-bind:name placeholder=\"name\" value=\"" (v/esc (:name item)) "\"></label>"
       "<label>Price (cents) <input data-bind:price inputmode=\"numeric\" value=\"" (:price item) "\"></label>"
       "<label>Description <textarea data-bind:desc>" (v/esc (:description item)) "</textarea></label>"
       "<div class=\"row\">"
       "<button type=\"button\" data-on:click=\"$name=" (v/js-lit (:name item)) "; $price=" (v/js-lit (:price item))
       "; $desc=" (v/js-lit (:description item)) "\">Reset fields</button>"
       (btn token "Save" "save" nil) (btn token "Close" "cancel" nil) "</div>"
       "<h3>Images</h3><ul class=\"imgs\">"
       (apply str (for [u (:images item)]
                    (str "<li><img src=\"" (v/enc-path u) "\" alt=\"\"><span>" (v/esc u) "</span>"
                         (btn token "Remove" "rmimg" u) "</li>")))
       "</ul><div class=\"row\"><input data-bind:img placeholder=\"/images/…\">"
       (btn token "Add image" "addimg" nil) "</div>"
       "<h3>Tagories</h3><ul class=\"tags\">"
       (apply str (for [t (:tagories item)]
                    (str "<li><span>" (v/esc t) "</span>" (btn token "Remove" "rmtag" t) "</li>")))
       "</ul><div class=\"row\"><input data-bind:tag placeholder=\"tagory\">"
       (btn token "Add tagory" "addtag" nil) "</div></section>"))


(defn- status
  [model]
  (case (:status model)
    "saving" "<p class=\"status saving\">saving</p>"
    "saved" "<p class=\"status saved\">saved</p>"
    "rejected" (str "<p class=\"status rejected\">rejected: " (v/esc (:msg model)) "</p>")
    ""))


(defn- new-item-form
  "The create form, shown when no item is selected.  The typed fields ride
   the `name`/`price`/`desc` signals; Create emits the `create` act."
  [token]
  (str "<section class=\"editor\"><h2>New item</h2>"
       "<label>Name <input data-bind:name placeholder=\"name\"></label>"
       "<label>Price (cents) <input data-bind:price inputmode=\"numeric\" placeholder=\"2400\"></label>"
       "<label>Description <textarea data-bind:desc placeholder=\"description\"></textarea></label>"
       "<div class=\"row\">" (btn token "Create" "create" nil) "</div></section>"))


(defn grid-project
  "`(project-fn occurrence-id model)` for the grid, over `catalog` (`:items`,
   `:item`): the item list, and the editor for the selected item, read fresh."
  [catalog token model]
  (let [sel (:sel model)
        item (when sel ((:item catalog) sel))]
    (str (v/sse-signals (str "{\"tok\":" (v/json-lit token) "}"))
         (v/sse-elements
           (str "<div id=\"app\"><header><h1>Catalog grid</h1><a href=\"/\">Shop</a></header>"
                "<p class=\"warn\">No authentication: anyone who can reach this port can edit.</p>"
                (status model)
                "<div class=\"cols\"><ul class=\"items\">"
                (apply str (map #(row token sel %) ((:items catalog))))
                "</ul>" (if item (editor token item) (new-item-form token))
                "</div></div>")))))


(defn grid-begin
  [root]
  (v/begin-page {:prefix prefix :title "dao.star cart grid" :root root
                 :app-id "app"
                 :signals "{act: '', arg: '', name: '', price: '', desc: '', img: '', tag: ''}"}))

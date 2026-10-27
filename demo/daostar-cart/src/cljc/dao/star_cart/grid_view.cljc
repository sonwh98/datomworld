(ns dao.star-cart.grid-view
  "Pure projection for the grid face.  Every action URL carries the `/grid`
   prefix, because the host edge strips it before the grid's own boundary."
  (:require [clojure.string :as str]
            [dao.star-cart.view :as v]))


(def prefix "/grid")


(defn- status
  [model]
  (case (:status model)
    "saving" "<p class=\"status saving\">saving</p>"
    "saved" "<p class=\"status saved\">saved</p>"
    "rejected" (str "<p class=\"status rejected\">rejected: " (v/esc (:msg model)) "</p>")
    ""))


(defn- new-item-form
  "The create row under the table.  The typed fields ride the
   `name`/`price`/`desc` signals; Create emits the `create` act."
  [token]
  (str "<section class=\"editor\"><h2>New item</h2>"
       "<div class=\"newrow\">"
       "<label>Name <input data-bind:name placeholder=\"name\"></label>"
       "<label>Price (cents) <input data-bind:price inputmode=\"numeric\" placeholder=\"2400\"></label>"
       "<label>Description <input data-bind:desc placeholder=\"description\"></label>"
       "</div>"
       "<div class=\"row\"><button type=\"button\" data-on:click=\"$act='create'; @post('/grid/k/'+$tok)\">Create</button></div></section>"))


(defn- cell-post
  "The change act of one spreadsheet cell: the arg is \"<slug>|<field>\",
   the value rides the `val` signal.  The change event has `event` in scope
   (only __debounce handlers lose it)."
  [slug field]
  (str "data-on:change=\"$act='cell'; $arg='" (v/js-lit (str slug "|" field))
       "'; $val=event.target.value; @post('/grid/k/'+$tok)\""))


(defn- sheet-row
  "One spreadsheet row: thumbnail, then the four editable cells and the
   images cell.  Save is on change (blur)."
  [item]
  (let [slug (:key item)]
    (str "<tr>"
         "<td class=\"th-img\">"
         (if-let [img (first (:images item))]
           (str "<img src=\"" (v/enc-path img) "\" alt=\"" (v/esc (:name item)) "\">")
           "<div class=\"noimg\"></div>")
         "</td>"
         "<td><input type=\"text\" value=\"" (v/esc (:name item)) "\" " (cell-post slug "name") "></td>"
         "<td><input type=\"number\" inputmode=\"numeric\" value=\"" (or (:price item) 0) "\" " (cell-post slug "price") "></td>"
         "<td><input type=\"text\" value=\"" (v/esc (str (:description item))) "\" " (cell-post slug "description") "></td>"
         "<td><input type=\"text\" class=\"tagcell\" value=\"" (v/esc (str/join ", " (:tagories item))) "\" " (cell-post slug "tagories") "></td>"
         "<td><input type=\"text\" class=\"imgcell\" value=\"" (v/esc (str/join ", " (:images item))) "\" " (cell-post slug "images") "></td>"
         "</tr>")))


(defn grid-project
  "`(project-fn occurrence-id model)` for the grid, over `catalog` (`:items`):
   a spreadsheet — one row per item, four editable columns, save on change
   (blur); the create form sits under the table.  Read fresh: the table
   always reflects the catalog as of the last projection."
  [catalog token model]
  (str (v/sse-signals (str "{\"tok\":" (v/json-lit token) "}"))
       (v/sse-elements
         (str "<div id=\"app\"><header><h1>Catalog grid</h1><a href=\"/\">Shop</a></header>"
              "<p class=\"warn\">No authentication: anyone who can reach this port can edit. "
              "Cells save when you leave them.</p>"
              (status model)
              "<div class=\"sheetwrap\"><table class=\"sheet\"><thead><tr>"
              "<th class=\"th-img\"></th><th>Name</th><th>Price (cents)</th><th>Description</th>"
              "<th>Tagories (comma-separated)</th><th>Images (comma-separated)</th>"
              "</tr></thead><tbody>"
              (apply str (map #(sheet-row %) ((:items catalog))))
              "</tbody></table></div>"
              (new-item-form token)
              "</div>"))))


(defn grid-begin
  [root]
  (v/begin-page {:prefix prefix :title "dao.star cart grid" :root root
                 :app-id "app"
                 :signals "{act: '', arg: '', name: '', price: '', desc: '', entered: false}"}))

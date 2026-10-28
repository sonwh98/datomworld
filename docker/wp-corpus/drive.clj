;; W0a corpus driver (babashka). Runs one tier of the ordered transcript against
;; the WordPress php service and appends its requests to requests-<tier>.edn.
;;
;;   bb drive.clj BASE-URL OUT-DIR TIER      ; TIER is t0 | t1 | t2
;;
;; Every request carries X-Corpus-Request: <tier>-<nn>-<name>; the recorder
;; (db.php) names its JSONL file by that header. The cookie jar persists across
;; tiers in OUT-DIR/jar.edn so t2 reuses t1's login. Statuses are recorded, not
;; asserted: the converter and the integrity test judge them.
(require '[babashka.http-client :as http]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str])


(import '(java.security MessageDigest))

(def pw "w0a-Corpus-Pass-1")
(def user "admin")


(defn sha256
  [^String s]
  (apply str (map #(format "%02x" (bit-and % 0xff))
                  (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8")))))


(defn parse-cookies
  [resp]
  (let [h (get-in resp [:headers "set-cookie"])]
    (into {} (for [c (cond (nil? h) [] (string? h) [h] :else h)
                   :let [[kv] (str/split c #";")
                         [k v] (str/split kv #"=" 2)]
                   :when (and k v (not= v "deleted"))]
               [k v]))))


(defn run-tier
  [base out tier]
  (let [jar-file (io/file out "jar.edn")
        jar (atom (if (.exists jar-file) (edn/read-string (slurp jar-file)) {}))
        log (atom [])
        n (atom 0)
        req! (fn [label method path & {:keys [form follow]}]
               (let [id (format "%s-%02d-%s" tier (swap! n inc) label)
                     resp (http/request
                            {:method method
                             :uri (str base path)
                             :headers (cond-> {"X-Corpus-Request" id
                                               "Cookie" (str/join "; " (map (fn [[k v]] (str k "=" v)) @jar))}
                                        form (assoc "Content-Type" "application/x-www-form-urlencoded"))
                             :body (when form
                                     (str/join "&" (for [[k v] form]
                                                     (str (java.net.URLEncoder/encode (name k) "UTF-8") "="
                                                          (java.net.URLEncoder/encode (str v) "UTF-8")))))
                             :follow-redirects false
                             :throw false})]
                 (swap! jar merge (parse-cookies resp))
                 (swap! log conj {:id id :method (str/upper-case (name method)) :uri path
                                  :status (:status resp)
                                  :response-sha256 (sha256 (or (:body resp) ""))})
                 (println id (:status resp))
                 resp))
        hidden* (fn [html]
                  (into {} (for [s (re-seq #"<input[^>]*type=['\"]hidden['\"][^>]*>" html)
                                 :let [nm (second (re-find #"name=['\"]([^'\"]*)['\"]" s))
                                       v (or (second (re-find #"value=['\"]([^'\"]*)['\"]" s)) "")]
                                 :when nm]
                             [nm v])))
        get! #(apply req! %1 :get %2 %&)
        post! (fn [label path form] (req! label :post path :form form))]
    (case tier
      "t0" (do (get! "install-form" "/wp-admin/install.php")
               (get! "install-step1" "/wp-admin/install.php?step=1")
               (post! "install-step2" "/wp-admin/install.php?step=2"
                      {:weblog_title "W0a Corpus" :user_name user :admin_password pw
                       :admin_password2 pw :pw_weak "1" :admin_email "admin@example.test"
                       :Submit "Install WordPress" :language ""}))
      "t1" (do (get! "front" "/")
               (get! "single-post" "/?p=1")
               (get! "page" "/?page_id=2")
               (get! "search" "/?s=hello")
               (get! "not-found" "/?p=9999")
               (get! "feed" "/?feed=rss2")
               (get! "rest-posts" "/?rest_route=/wp/v2/posts")
               (get! "login-form" "/wp-login.php")
               (post! "login-bad" "/wp-login.php"
                      {:log user :pwd "wrong" :wp-submit "Log In" :redirect_to "/wp-admin/" :testcookie "1"})
               (swap! jar assoc "wordpress_test_cookie" "WP%20Cookie%20check")
               (post! "login-ok" "/wp-login.php"
                      {:log user :pwd pw :wp-submit "Log In" :redirect_to "/wp-admin/" :testcookie "1"})
               (post! "comment" "/wp-comments-post.php"
                      {:comment "A corpus comment" :author "Visitor" :email "v@example.test"
                       :url "" :submit "Post Comment" :comment_post_ID "1" :comment_parent "0"}))
      "t2" (do (get! "dashboard" "/wp-admin/")
               (get! "edit" "/wp-admin/edit.php")
               (let [r (get! "post-new" "/wp-admin/post-new.php")
                     f (hidden* (:body r))]
                 (post! "post-publish" "/wp-admin/post.php"
                        (merge f {"post_title" "Corpus post" "content" "Body of the corpus post."
                                  "publish" "Publish" "post_status" "publish" "visibility" "public"
                                  "action" "editpost" "originalaction" "editpost"})))
               (let [r (get! "options-general" "/wp-admin/options-general.php")
                     f (hidden* (:body r))]
                 (post! "options-save" "/wp-admin/options.php"
                        (merge f {"blogname" "W0a Corpus" "blogdescription" "recorded" "submit" "Save Changes"})))
               (get! "plugins" "/wp-admin/plugins.php")
               (get! "themes" "/wp-admin/themes.php")
               (get! "users" "/wp-admin/users.php")
               (get! "upload" "/wp-admin/upload.php")
               (get! "edit-comments" "/wp-admin/edit-comments.php")
               (let [r (get! "edit-comments-nonce" "/wp-admin/edit-comments.php?comment_status=moderated")
                     m (re-find #"comment\.php\?[^\"']*action=approvecomment[^\"']*" (or (:body r) ""))]
                 (when m (get! "comment-approve" (str "/wp-admin/" (str/replace m "&amp;" "&")))))
               (post! "heartbeat" "/wp-admin/admin-ajax.php?action=heartbeat"
                      {:action "heartbeat" :interval "60" :_nonce "x"})
               (get! "wp-cron" "/wp-cron.php?doing_wp_cron")
               (get! "final-front" "/")))
    (spit jar-file (pr-str @jar))
    (spit (io/file out (str "requests-" tier ".edn")) (pr-str {:tier tier :requests @log}))))


(let [[base out tier] *command-line-args*]
  (run-tier base out tier))

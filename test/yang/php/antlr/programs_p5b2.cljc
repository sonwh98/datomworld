(ns yang.php.antlr.programs-p5b2
  "P5b2 request-lifecycle completions: hand-derived PHP 8.3 expectations for
  the request merge, header/cookie/status and error-triggering fixes. Every
  fixture stays `:oracle :pending` for the shiva php:8.3 oracle pass.")


(defn request-array
  [entries]
  {:php/type :array, :index (into {} entries), :order (mapv first entries)})


(def base-request
  {:php.request/method "POST"
   :php.request/uri "/b2"
   :php.request/query (request-array [])
   :php.request/headers (request-array [])
   :php.request/cookies (request-array [])
   :php.request/body (request-array [])
   :php.request/server (request-array [])
   :php.request/env (request-array [])
   :php.request/files (request-array [])})


(defn- fx
  [id source request out & {:as more}]
  (merge {:id id, :group :p5b2-lifecycle, :source source, :request request,
          :out out, :oracle :pending}
         more))


(def numeric-merge-request
  (assoc base-request
         :php.request/query (request-array
                              [["ids" (request-array [[0 "10"] [1 "20"]])]
                               ["shared" "get"]])
         :php.request/body (request-array
                             [["ids" (request-array [[0 "30"]])]
                              ["shared" "post"]])))


(def sparse-merge-request
  (assoc base-request
         :php.request/query (request-array
                              [["ids" (request-array [[1 "a"] [5 "b"]])]])
         :php.request/body (request-array
                             [["ids" (request-array [[5 "B"] [9 "c"]])]])))


(def nested-int-merge-request
  (assoc base-request
         :php.request/query (request-array
                              [["m" (request-array [[0 (request-array [["x" "1"]])]])]])
         :php.request/body (request-array
                             [["m" (request-array [[0 (request-array [["y" "2"]])] [2 "z"]])]])))


(def nested-merge-request
  (assoc base-request
         :php.request/query (request-array
                              [["cfg" (request-array [["a" "1"] ["b" "2"]])]])
         :php.request/body (request-array
                             [["cfg" (request-array [["b" "3"] ["c" "4"]])]])))


(def fixtures
  [(fx :request-merge-numeric
       "<?php echo $_REQUEST['ids'][0], $_REQUEST['ids'][1], $_REQUEST['shared'];"
       numeric-merge-request "3020post"
       :diag [] :oracle :pending)
   (fx :request-merge-nested
       "<?php echo $_REQUEST['cfg']['a'], $_REQUEST['cfg']['b'], $_REQUEST['cfg']['c'];"
       nested-merge-request "134"
       :diag [] :oracle :pending)
   (fx :request-merge-sparse-integers
       "<?php echo $_REQUEST['ids'][1], $_REQUEST['ids'][5], $_REQUEST['ids'][9];"
       sparse-merge-request "aBc"
       :diag [] :oracle :pending)
   (fx :request-merge-nested-integer-collision
       "<?php echo $_REQUEST['m'][0]['x'], $_REQUEST['m'][0]['y'], $_REQUEST['m'][2];"
       nested-int-merge-request "12z"
       :diag [] :oracle :pending)
   (fx :status-answers-previous
       "<?php $a = http_response_code(201); $b = http_response_code(202); var_dump($a); var_dump($b); var_dump(http_response_code());"
       base-request "int(200)\nint(201)\nint(202)\n"
       :diag [] :oracle :pending :http-profile true
       :response [{:php.response/status 201}
                  {:php.response/status 202}
                  {:php.response/body "int(200)\nint(201)\nint(202)\n"}])
   (fx :header-replace-and-status-arg
       "<?php header('X-A: 1', false); header('X-B: 2', true, 202); echo http_response_code();"
       base-request "202"
       :diag [] :oracle :pending :http-profile true
       :response [{:php.response/header "X-A: 1", :replace false}
                  {:php.response/header "X-B: 2", :replace true}
                  {:php.response/status 202}
                  {:php.response/body "202"}])
   (fx :setcookie-positional-options
       "<?php var_dump(setcookie('s', 'v', 1700003600, '/app', 'example.com', true, true));"
       base-request "bool(true)\n"
       :diag [] :oracle :pending
       :response [{:php.response/cookie
                   {:name "s", :value "v",
                    :options {:expires 1700003600, :path "/app",
                              :domain "example.com",
                              :secure true, :httponly true}}}
                  {:php.response/body "bool(true)\n"}])
   (fx :setcookie-options-array
       "<?php var_dump(setcookie('o', 'w', ['expires' => 1700003600, 'path' => '/', 'samesite' => 'Lax']));"
       base-request "bool(true)\n"
       :diag [] :oracle :pending
       :response [{:php.response/cookie
                   {:name "o", :value "w",
                    :options {:expires 1700003600, :path "/",
                              :samesite "Lax"}}}
                  {:php.response/body "bool(true)\n"}])
   (fx :trigger-user-error-is-fatal
       "<?php echo 'A'; trigger_error('boom', E_USER_ERROR); echo 'C';"
       base-request "A"
       :diag [[256 "boom"]] :oracle :pending
       :fatal "boom" :status 255)
   (fx :trigger-user-error-runs-shutdown-and-buffers
       (str "<?php ob_start(); echo 'buf';"
            " register_shutdown_function(function() { echo 'S'; });"
            " trigger_error('boom', E_USER_ERROR);")
       base-request "bufS"
       :diag [[256 "boom"]] :oracle :pending
       :fatal "boom" :status 255)
   (fx :trigger-user-error-suppresses-destructors
       (str "<?php class D { function __destruct() { echo 'D'; } }"
            " $o = new D;"
            " register_shutdown_function(function() { echo 'S'; });"
            " echo 'B'; trigger_error('boom', E_USER_ERROR);")
       base-request "BS"
       :diag [[256 "boom"]] :oracle :pending
       :fatal "boom" :status 255)
   (fx :trigger-user-error-handled-continues
       (str "<?php set_error_handler(function($n, $s) { echo 'H'; return true; }, E_USER_ERROR);"
            " trigger_error('boom', E_USER_ERROR); echo 'C';")
       base-request "HC"
       :diag [] :oracle :pending
       :response [{:php.response/body "HC"}])
   (fx :trigger-user-error-false-returning-falls-back
       (str "<?php set_error_handler(function($n, $s) { return false; }, E_USER_ERROR);"
            " echo 'A'; trigger_error('boom', E_USER_ERROR); echo 'C';")
       base-request "A"
       :diag [[256 "boom"]] :oracle :pending
       :fatal "boom" :status 255)
   (fx :trigger-invalid-level-throws-value-error
       "<?php var_dump(trigger_error('x', 9999));"
       base-request ""
       :diag [] :oracle :pending
       :uncaught {:class "ValueError"
                  :message "trigger_error(): Argument #2 ($error_level) must be one of E_USER_ERROR, E_USER_WARNING, E_USER_NOTICE, or E_USER_DEPRECATED"})
   (fx :trigger-invalid-level-caught
       (str "<?php try { trigger_error('x', E_WARNING); }"
            " catch (ValueError $e) { echo 'caught'; }")
       base-request "caught"
       :diag [] :oracle :pending
       :response [{:php.response/body "caught"}])
   (fx :handler-mask-saved-and-restored
       (str "<?php set_error_handler(function($n, $s) { echo 'A', $n; return true; }, E_USER_WARNING);"
            " set_error_handler(function($n, $s) { echo 'B', $n; return true; }, E_USER_NOTICE);"
            " trigger_error('w1', E_USER_WARNING);"
            " trigger_error('n1', E_USER_NOTICE);"
            " restore_error_handler();"
            " trigger_error('w2', E_USER_WARNING);"
            " restore_error_handler();"
            " trigger_error('w3', E_USER_WARNING);")
       base-request "B1024A512"
       :diag [[512 "w1"] [512 "w3"]] :oracle :pending
       :response [{:php.response/body "B1024A512"}])
   (fx :http-response-code-zero-is-getter
       "<?php $a = http_response_code(201); $b = http_response_code(0); var_dump($a); var_dump($b); var_dump(http_response_code());"
       base-request "int(200)\nint(201)\nint(201)\n"
       :diag [] :oracle :pending :http-profile true
       :response [{:php.response/status 201}
                  {:php.response/body "int(200)\nint(201)\nint(201)\n"}])
   (fx :header-status-line-sets-code
       "<?php header('HTTP/1.1 404 Not Found'); echo http_response_code();"
       base-request "404"
       :diag [] :oracle :pending :http-profile true
       :response [{:php.response/header "HTTP/1.1 404 Not Found", :replace true}
                  {:php.response/status 404}
                  {:php.response/body "404"}])
   (fx :header-replace-is-case-insensitive
       "<?php header('X-Name: 1'); header('x-name: 2'); echo 'done';"
       base-request "done"
       :diag [] :oracle :pending
       :response [{:php.response/header "x-name: 2", :replace true}
                  {:php.response/body "done"}])
   (fx :setcookie-unknown-option-is-value-error
       "<?php var_dump(setcookie('o', 'w', ['bogus' => 1]));"
       base-request ""
       :diag [] :oracle :pending
       :uncaught {:class "ValueError"
                  :message "setcookie(): option \"bogus\" is invalid"})
   (fx :setcookie-numeric-option-key-is-value-error
       "<?php var_dump(setcookie('o', 'w', ['path' => '/', 1 => 2]));"
       base-request ""
       :diag [] :oracle :pending
       :uncaught {:class "ValueError"
                  :message "setcookie(): option array cannot have numeric keys"})
   (fx :setcookie-extra-positional-is-argument-count-error
       "<?php var_dump(setcookie('s', 'v', 0, '/', 'example.com', false, false, 'Lax'));"
       base-request ""
       :diag [] :oracle :pending
       :uncaught {:class "ArgumentCountError"
                  :message "setcookie() expects at most 7 arguments, 8 given"})
   (fx :setcookie-extra-array-positional-is-argument-count-error
       "<?php var_dump(setcookie('o', 'w', ['path' => '/'], 'extra'));"
       base-request ""
       :diag [] :oracle :pending
       :uncaught {:class "ArgumentCountError"
                  :message "setcookie(): Expects exactly 3 arguments when argument #3 ($expires_or_options) is an array"})
   (fx :setcookie-array-options-coerced
       "<?php var_dump(setcookie('o', 'w', ['expires' => '1700003600', 'secure' => 1, 'samesite' => 'Lax']));"
       base-request "bool(true)\n"
       :diag [] :oracle :pending
       :response [{:php.response/cookie
                   {:name "o", :value "w",
                    :options {:expires 1700003600, :secure true,
                              :samesite "Lax"}}}
                  {:php.response/body "bool(true)\n"}])
   (fx :http-response-code-zero-after-output-is-getter
       "<?php http_response_code(201); echo 'x'; var_dump(http_response_code(0));"
       base-request "xint(201)\n"
       :diag [] :oracle :pending :http-profile true
       :response [{:php.response/status 201}
                  {:php.response/body "xint(201)\n"}])
   (fx :setcookie-option-names-case-insensitive
       "<?php var_dump(setcookie('o', 'w', ['SameSite' => 'Lax', 'SECURE' => true]));"
       base-request "bool(true)\n"
       :diag [] :oracle :pending
       :response [{:php.response/cookie
                   {:name "o", :value "w",
                    :options {:samesite "Lax", :secure true}}}
                  {:php.response/body "bool(true)\n"}])])

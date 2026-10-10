(ns yin.vm.semantic-register.corpus
  "The phase-3 corpus of `yin.vm.semantic-register-vm.md` §10: map ASTs
   covering every §3.1 production, each with its golden register-shaped
   vector and code address. The same literal goldens are compiled on
   JVM, Node and Dart, so a test asserting them on every host is the
   cross-host determinism gate.")


(defn lit
  [x]
  {:type :literal, :value x})


(defn v
  [s]
  {:type :variable, :name s})


(defn lam
  [params body]
  {:type :lambda, :params params, :body body})


(defn app
  [op & args]
  {:type :application, :operator op, :operands (vec args)})


(defn tail
  [node]
  (assoc node :tail? true))


(defn if-node
  [t c a]
  {:type :if, :test t, :consequent c, :alternate a})


(defn resume
  [id x]
  {:type :vm/resume, :parked-id id, :val x})


(defn def!
  [k x]
  (app (v 'yin/def) (lit k) x))


(def ^:private shared-call
  "One node referenced from two sites: lowered twice (§3.3 item 1)."
  (app (v 'g) (lit 1)))


(def programs
  "`[name ast]` in a fixed order."
  [[:literal (lit 42)]
   [:variable (v 'x)]
   [:worked-example (app (v 'f) (app (v 'g) (v 'x)) (v 'y))]
   [:zero-arity-call (app (v 'f))]
   [:nested-calls (app (v 'f) (app (v 'g) (app (v 'h) (v 'x))) (app (v 'k) (v 'y)))]
   [:lambda-application
    (app (lam '[x] (tail (app (v '+) (v 'x) (lit 1)))) (lit 10))]
   [:nested-lambdas
    (app (app (lam '[a] (tail (lam '[b] (tail (app (v '+) (v 'a) (v 'b))))))
              (lit 1))
         (lit 2))]
   [:body-queue-order
    ;; main holds two lambdas; the first body holds a third, which joins
    ;; the queue behind the second.
    (app (lam '[p] (tail (app (v 'p) (lam '[q] (v 'q)))))
         (lam '[r] (v 'r)))]
   [:if (if-node (v 'c) (lit 1) (lit 2))]
   [:if-in-test (if-node (if-node (lit true) (lit false) (lit true)) (lit :a) (lit :b))]
   [:nested-if-same-rd
    (if-node (v 'a) (if-node (v 'b) (lit 1) (lit 2)) (if-node (v 'c) (lit 3) (lit 4)))]
   [:if-operand (app (v 'f) (if-node (v 'c) (lit 1) (lit 2)) (v 'y))]
   [:if-in-tail
    (app (lam '[n]
              (if-node (app (v '<) (v 'n) (lit 1))
                       (lit :done)
                       (tail (app (v 'loop) (app (v '-) (v 'n) (lit 1))))))
         (lit 3))]
   [:all-terminal-arms
    (if-node (v 'c) (resume :p (lit 1)) (resume :q (lit 2)))]
   [:all-tail-arms
    (app (lam '[n] (if-node (v 'n) (tail (app (v 'f) (v 'n))) (tail (app (v 'g)))))
         (lit 0))]
   [:define (def! 'x (lit 5))]
   [:define-call (def! 'y (app (v '+) (lit 1) (lit 2)))]
   [:streams
    {:type :stream/put,
     :target {:type :stream/make, :buffer 4},
     :val {:type :stream/next,
           :source {:type :stream/cursor,
                    :source {:type :stream/close, :source (v 's)}}}}]
   [:stream-make-default {:type :stream/make}]
   [:ffi-call {:type :dao.stream.apply/call, :op :op/echo, :operands [(lit 1) (v 'x)]}]
   [:ffi-call-no-args {:type :dao.stream.apply/call, :op :op/ping, :operands []}]
   [:gensym (app (v 'list) {:type :vm/gensym} {:type :vm/gensym, :prefix "g"})]
   [:store-ops
    (app (v 'vector)
         {:type :vm/store-put, :key 'k, :val 5}
         {:type :vm/store-get, :key 'k}
         {:type :vm/store-put, :key :n, :val {:a [1 2]}}
         {:type :vm/store-get, :key 7})]
   [:current-continuation (app (v 'f) {:type :vm/current-continuation})]
   [:park (app (v 'f) {:type :vm/park} (v 'x))]
   [:resume-body (resume :p (lit 7))]
   [:resume-arm (if-node (v 'c) {:type :vm/park} (resume :p1 (lit 7)))]
   [:resume-operand (app (v 'f) (resume :p (v 'x)) (v 'y))]
   [:resume-lambda-body (app (lam '[x] (resume :p (v 'x))) (lit 1))]
   [:closure-in-arm-in-body
    (app (lam '[x] (if-node (v 'x) (lam '[y] (v 'y)) (lam '[z] (v 'z)))) (lit true))]
   [:shared-occurrence (app (v '+) shared-call shared-call)]
   [:define-then-call
    (app (lam '[a b] (tail (app (v 'list) (v 'a) (v 'b))))
         (def! 'n (lit 10))
         (app (v 'inc) (v 'n)))]])


(def goldens
  "name → `{:vector v :address A}`: the golden projection of each
   program and its `ucf/code-address`. `:worked-example` is §3.3's
   `(f (g x) y)` with `tail? false`."
  '{:literal
    {:vector [[:const 0 42]
              [:halt 0]],
     :address :segment/blake3-552def595ddfa1fcbf9ce5bc7062e9672801a15aa6d516c435575a5c0c5bea6a},
    :variable
    {:vector [[:var 0 x]
              [:halt 0]],
     :address :segment/blake3-d92b417e9a9e1b3f493cc5772c63b3927dd76f2bc6aaecbc6bec340b584d10a3},
    :worked-example
    {:vector [[:var 1 f]
              [:var 3 g]
              [:var 4 x]
              [:call 2 3 [4] false]
              [:var 5 y]
              [:call 0 1 [2 5] false]
              [:halt 0]],
     :address :segment/blake3-4102d6c838757d8ceeb175ae09abea2033fe12eb9bc9e8c48d8b689fd8fbf70f},
    :zero-arity-call
    {:vector [[:var 1 f]
              [:call 0 1 [] false]
              [:halt 0]],
     :address :segment/blake3-ad36f5d17b504f8a17529fd05bec576693fa0ecce817b129147e154d15fc653f},
    :nested-calls
    {:vector [[:var 1 f]
              [:var 3 g]
              [:var 5 h]
              [:var 6 x]
              [:call 4 5 [6] false]
              [:call 2 3 [4] false]
              [:var 8 k]
              [:var 9 y]
              [:call 7 8 [9] false]
              [:call 0 1 [2 7] false]
              [:halt 0]],
     :address :segment/blake3-211824977382999101ced7a1fd2ee9c0f305ae83c525392d35603b80c223989f},
    :lambda-application
    {:vector [[:closure 1 [x] 4]
              [:const 2 10]
              [:call 0 1 [2] false]
              [:halt 0]
              [:var 1 +]
              [:var 2 x]
              [:const 3 1]
              [:call 0 1 [2 3] true]
              [:return 0]],
     :address :segment/blake3-e69e9d68b973f5e99250af794625546c03406a8a8d634e546512d48d38220f62},
    :nested-lambdas
    {:vector [[:closure 2 [a] 6]
              [:const 3 1]
              [:call 1 2 [3] false]
              [:const 4 2]
              [:call 0 1 [4] false]
              [:halt 0]
              [:closure 0 [b] 8]
              [:return 0]
              [:var 1 +]
              [:var 2 a]
              [:var 3 b]
              [:call 0 1 [2 3] true]
              [:return 0]],
     :address :segment/blake3-d96ebbca00670ef9340379723f3fdd911a5c6b0d7b817077d9e12b0d567e9275},
    :body-queue-order
    {:vector [[:closure 1 [p] 4]
              [:closure 2 [r] 8]
              [:call 0 1 [2] false]
              [:halt 0]
              [:var 1 p]
              [:closure 2 [q] 10]
              [:call 0 1 [2] true]
              [:return 0]
              [:var 0 r]
              [:return 0]
              [:var 0 q]
              [:return 0]],
     :address :segment/blake3-721798d32f78fa34adf2ebb185757c7681b960492149dad9b8a60d2b86be2c9d},
    :if
    {:vector [[:var 1 c]
              [:branch-false 1 4]
              [:const 0 1]
              [:jump 5]
              [:const 0 2]
              [:halt 0]],
     :address :segment/blake3-0200053b8e57a13dd3ebe47457877863cdc4248414bf3c72c3da4922011ad72d},
    :if-in-test
    {:vector [[:const 2 true]
              [:branch-false 2 4]
              [:const 1 false]
              [:jump 5]
              [:const 1 true]
              [:branch-false 1 8]
              [:const 0 :a]
              [:jump 9]
              [:const 0 :b]
              [:halt 0]],
     :address :segment/blake3-6c112613bf4846c34de0a7ce51a73511c143982c60867041a2ba4c410fc6ff1c},
    :nested-if-same-rd
    {:vector [[:var 1 a]
              [:branch-false 1 8]
              [:var 2 b]
              [:branch-false 2 6]
              [:const 0 1]
              [:jump 7]
              [:const 0 2]
              [:jump 13]
              [:var 3 c]
              [:branch-false 3 12]
              [:const 0 3]
              [:jump 13]
              [:const 0 4]
              [:halt 0]],
     :address :segment/blake3-33f56ab7801d06a943520c26f1fe19d1d8f61011ac5985a171b62aaaca4ad0ba},
    :if-operand
    {:vector [[:var 1 f]
              [:var 3 c]
              [:branch-false 3 5]
              [:const 2 1]
              [:jump 6]
              [:const 2 2]
              [:var 4 y]
              [:call 0 1 [2 4] false]
              [:halt 0]],
     :address :segment/blake3-35eb14731ce995f397302491a46d7eef3ccd57ec805455f35310d528ade3f1c8},
    :if-in-tail
    {:vector [[:closure 1 [n] 4]
              [:const 2 3]
              [:call 0 1 [2] false]
              [:halt 0]
              [:var 2 <]
              [:var 3 n]
              [:const 4 1]
              [:call 1 2 [3 4] false]
              [:branch-false 1 11]
              [:const 0 :done]
              [:jump 17]
              [:var 5 loop]
              [:var 7 -]
              [:var 8 n]
              [:const 9 1]
              [:call 6 7 [8 9] false]
              [:call 0 5 [6] true]
              [:return 0]],
     :address :segment/blake3-55181be47e8d2c04eacef67bb669ab3b74139a3d7302c717d8f82055e834c205},
    :all-terminal-arms
    {:vector [[:var 1 c]
              [:branch-false 1 5]
              [:const 2 1]
              [:resume :p 2]
              [:jump 7]
              [:const 3 2]
              [:resume :q 3]
              [:halt 0]],
     :address :segment/blake3-782de519ce5cdb1f1e8cddd0bdb1dace8dbd8ffb46b68430e7c678ad624e1be3},
    :all-tail-arms
    {:vector [[:closure 1 [n] 4]
              [:const 2 0]
              [:call 0 1 [2] false]
              [:halt 0]
              [:var 1 n]
              [:branch-false 1 10]
              [:var 2 f]
              [:var 3 n]
              [:call 0 2 [3] true]
              [:jump 12]
              [:var 4 g]
              [:call 0 4 [] true]
              [:return 0]],
     :address :segment/blake3-c80c38bf738b067ffe76520bdb878823606c9d8acfaa38106515a3828fe78a5c},
    :define
    {:vector [[:const 1 5]
              [:define 0 x 1]
              [:halt 0]],
     :address :segment/blake3-495bca90d0f29a00b347ff78615e56d0490971cb5d80f36b104df59a7cbb4091},
    :define-call
    {:vector [[:var 2 +]
              [:const 3 1]
              [:const 4 2]
              [:call 1 2 [3 4] false]
              [:define 0 y 1]
              [:halt 0]],
     :address :segment/blake3-5f4d54430d0ad9aceedf94377705ad004b7055fff53dfa079e4783ea748b730a},
    :streams
    {:vector [[:stream-make 1 4]
              [:var 5 s]
              [:stream-close 4 5]
              [:stream-cursor 3 4]
              [:stream-next 2 3]
              [:stream-put 0 1 2]
              [:halt 0]],
     :address :segment/blake3-5198f2a10e5514fcb3f0026a6b167a59d63d83628920355244ba3465f5656ddb},
    :stream-make-default
    {:vector [[:stream-make 0 1024]
              [:halt 0]],
     :address :segment/blake3-2903775f49a16e716d3f9a77901ccf9c2df2f140cf147b77f7149b0fd70680c8},
    :ffi-call
    {:vector [[:const 1 1]
              [:var 2 x]
              [:ffi-call 0 :op/echo [1 2]]
              [:halt 0]],
     :address :segment/blake3-8f9ebb4ba9ca6acdfc3b8e16c07f9b18ce30511bfad0160064d7f8a8849642ca},
    :ffi-call-no-args
    {:vector [[:ffi-call 0 :op/ping []]
              [:halt 0]],
     :address :segment/blake3-95cb9cb90cd6162399e045b8be5fb82a2896c764e1098c1edc238ab0a4095ba0},
    :gensym
    {:vector [[:var 1 list]
              [:gensym 2 "id"]
              [:gensym 3 "g"]
              [:call 0 1 [2 3] false]
              [:halt 0]],
     :address :segment/blake3-889887efc97bb9cd97c229c3c74546d54826f1b7de54d63b9fcce673bec57a28},
    :store-ops
    {:vector [[:var 1 vector]
              [:store-put 2 k 5]
              [:store-get 3 k]
              [:store-put 4 :n {:a [1 2]}]
              [:store-get 5 7]
              [:call 0 1 [2 3 4 5] false]
              [:halt 0]],
     :address :segment/blake3-6d013e7fcc020f8a2109d39e2fa14b087cb120ae8444812a73fe63db1d624886},
    :current-continuation
    {:vector [[:var 1 f]
              [:current-continuation 2]
              [:call 0 1 [2] false]
              [:halt 0]],
     :address :segment/blake3-8f91ecfb2f77d21b3daad38079c695b97ed375495afcc95c1b79dd1de80c6d6d},
    :park
    {:vector [[:var 1 f]
              [:park 2]
              [:var 3 x]
              [:call 0 1 [2 3] false]
              [:halt 0]],
     :address :segment/blake3-fd4389fab8f779695fa8a3ec7f1da37d9312abe4c1ef40b710cb12b06b1823b3},
    :resume-body
    {:vector [[:const 1 7]
              [:resume :p 1]
              [:halt 0]],
     :address :segment/blake3-b6488508108e9a8b4e02da10a57be47165348f60170c06e16eaeaa133f75db9b},
    :resume-arm
    {:vector [[:var 1 c]
              [:branch-false 1 4]
              [:park 0]
              [:jump 6]
              [:const 2 7]
              [:resume :p1 2]
              [:halt 0]],
     :address :segment/blake3-2755d0a38a782b8e9e1dc819629cb10395a4bc1662a88d39dc3a0c574c7e9433},
    :resume-operand
    {:vector [[:var 1 f]
              [:var 3 x]
              [:resume :p 3]
              [:var 4 y]
              [:call 0 1 [2 4] false]
              [:halt 0]],
     :address :segment/blake3-6232057bf2c13f65dc4bd16babfbfb02051d656641fe0d8bdd42a3c20c389837},
    :resume-lambda-body
    {:vector [[:closure 1 [x] 4]
              [:const 2 1]
              [:call 0 1 [2] false]
              [:halt 0]
              [:var 1 x]
              [:resume :p 1]
              [:return 0]],
     :address :segment/blake3-f68f835f0adeea8c1655691b893bf2f8bd42ace7093ac0606bde55ed5069ba71},
    :closure-in-arm-in-body
    {:vector [[:closure 1 [x] 4]
              [:const 2 true]
              [:call 0 1 [2] false]
              [:halt 0]
              [:var 1 x]
              [:branch-false 1 8]
              [:closure 0 [y] 10]
              [:jump 9]
              [:closure 0 [z] 12]
              [:return 0]
              [:var 0 y]
              [:return 0]
              [:var 0 z]
              [:return 0]],
     :address :segment/blake3-5e6d19860079ce89fbc4445f37b4954a19723c251f047b292c0bbfe47cb78be5},
    :shared-occurrence
    {:vector [[:var 1 +]
              [:var 3 g]
              [:const 4 1]
              [:call 2 3 [4] false]
              [:var 6 g]
              [:const 7 1]
              [:call 5 6 [7] false]
              [:call 0 1 [2 5] false]
              [:halt 0]],
     :address :segment/blake3-0ac698f2923510f888e659a7bff055c9e17501999bd29d2db37d36ffab26a6e5},
    :define-then-call
    {:vector [[:closure 1 [a b] 8]
              [:const 3 10]
              [:define 2 n 3]
              [:var 5 inc]
              [:var 6 n]
              [:call 4 5 [6] false]
              [:call 0 1 [2 4] false]
              [:halt 0]
              [:var 1 list]
              [:var 2 a]
              [:var 3 b]
              [:call 0 1 [2 3] true]
              [:return 0]],
     :address :segment/blake3-6c2a1d84e93f7b7ca79ea74727382ecea4c17f510ac646d86f1c4359b078da18}})

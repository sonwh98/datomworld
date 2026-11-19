# fixorder-jvm-order-RED.log — digest of the 126.5 MB raw log (untracked in the authoring worktree, exceeds GitHub's 100 MB limit)
# Raw file sha256: d0b0fe59ab3279e94bc94901286fdb3dfe1df76832ce544174f90ed5a0164ad9
# Captured: 10 tests / 91,115 failures, 0 errors — the ruling's required red run before the emitter fix.
# Tail (summary + last failure blocks):
, :operator {:type :variable, :name +}, :operands [{:type :variable, :name n} {:type :literal, :value 27}], :yang/call-site 1} {:type :literal, :value :k31} {:type :application, :operator {:type :variable, :name +}, :operands [{:type :variable, :name n} {:type :literal, :value 31}], :yang/call-site 1}], :tail? true, :yang/call-site 1}]))

FAIL in (colliding-keys-canonical-winner-test) (clojure_collection_order_test.cljc:778)
both insertion orders yield the canonically last value
:a-first :ast-walker
expected: (= {0 winner} value)
  actual: (not (= {0 :a} {0 :b}))

FAIL in (colliding-keys-canonical-winner-test) (clojure_collection_order_test.cljc:778)
both insertion orders yield the canonically last value
:a-first :semantic
expected: (= {0 winner} value)
  actual: (not (= {0 :a} {0 :b}))

FAIL in (colliding-keys-canonical-winner-test) (clojure_collection_order_test.cljc:778)
both insertion orders yield the canonically last value
:a-first :stack
expected: (= {0 winner} value)
  actual: (not (= {0 :a} {0 :b}))

FAIL in (colliding-keys-canonical-winner-test) (clojure_collection_order_test.cljc:778)
both insertion orders yield the canonically last value
:a-first :register
expected: (= {0 winner} value)
  actual: (not (= {0 :a} {0 :b}))

FAIL in (entry-points-order-test) (clojure_collection_order_test.cljc:824)
the convention entries (argument-vector unit, lower-unit) emit
            one canonical operand vector, ordered by the same comparator
the unit's constructed maps agree
expected: (apply = roots-a)
  actual: (not (apply #object[clojure.core$_EQ_ 0x79f127e "clojure.core$_EQ_@79f127e"] [[:segment/blake3-201f235cbac998f43954454e9df0eb633717e79de535776f3f51e198a3759b10 :segment/blake3-1126c34e62ff860408a500d89651bcf9f2b0cc4c1ff2b6723889bf8d3f2df848 :segment/blake3-b0396996ca392704c0f0c18ae40c1c2cc3fc9403bffb17099a2f44d840af6a8e :segment/blake3-257ddbf1ffac0bf8683c8fb59fa3cba81bfef9755a74117d8299d173a7177151 :segment/blake3-6b3ddd431908e19f07cafee60bfe4cd10b299605e834ecabcf58772ceae3cbad] [:segment/blake3-201f235cbac998f43954454e9df0eb633717e79de535776f3f51e198a3759b10 :segment/blake3-3851b65ed8cab8b14d94a17e261193b88c158921093f869ccf2399b1527b86e1 :segment/blake3-863eb54c46541d42424fef3875f546085cb14c56e36f1a8b745bfb28764d7a5e] [:segment/blake3-201f235cbac998f43954454e9df0eb633717e79de535776f3f51e198a3759b10 :segment/blake3-3851b65ed8cab8b14d94a17e261193b88c158921093f869ccf2399b1527b86e1 :segment/blake3-95f140d76889b4af4da71bfcdb204614ccb2b707abeddfaecce2b2c22b6ae65f] [:segment/blake3-201f235cbac998f43954454e9df0eb633717e79de535776f3f51e198a3759b10 :segment/blake3-92058f0e00acfb6e44be40711e4f7b0fac422356a5aaa7c1a0b9e366df68bf75 :segment/blake3-89d1ca91c9a8f285018cc35fef263f71c9fb8820c0d2187d42d2d785cb4ec2fa]]))

FAIL in (entry-points-order-test) (clojure_collection_order_test.cljc:829)
compiler-generated maps (the arity helper) are covered
expected: (ascending? (pair-keys (first (arity-map-constructors (yang/compile-program cohort-forms true yang/unit-opts)))))
  actual: (not (ascending? [[:segment/blake3-81b62c1e0dbf783ce555376690c9cffdee2eaca50ff9da4247397a785d9340cf :segment/blake3-1db5dbd048522aa048759e5fb8ae47ccbf9e158906554b362080d77cf30a6178] [:segment/blake3-8930bf5e7b03638896bc3bef03d4f1ae648e7460ed4b488bef2b9d5f619cbd90 :segment/blake3-e60543d220d61497d02184c1f7a087c15c3a20d0f305cae88647987f5ec99e3b] [:segment/blake3-1e03667ec9fa05b20761de3b84f623962235ff218fce26401bbcdd9d3aabeca8 :segment/blake3-3156a0708410c1e18d4eb15f567790a8c1ac683f219e720a847b0e21e54ca778]]))

FAIL in (arity-map-canonical-order-test) (clojure_collection_order_test.cljc:640)
the reproduced named case: argument-vector and lower-unit members :argument-vector
expected: (= named-arity-key-order (key-literals c))
  actual: (not (= [:yin.k/fn :yin.vm.data/reason :yin.k/argc] [:yin.vm.data/reason :yin.k/argc :yin.k/fn]))

FAIL in (arity-map-canonical-order-test) (clojure_collection_order_test.cljc:641)
the reproduced named case: argument-vector and lower-unit members :argument-vector
expected: (= named-arity-operand-roots (child-roots c))
  actual: (not (= [:segment/blake3-201f235cbac998f43954454e9df0eb633717e79de535776f3f51e198a3759b10 :segment/blake3-1e03667ec9fa05b20761de3b84f623962235ff218fce26401bbcdd9d3aabeca8 :segment/blake3-3156a0708410c1e18d4eb15f567790a8c1ac683f219e720a847b0e21e54ca778 :segment/blake3-81b62c1e0dbf783ce555376690c9cffdee2eaca50ff9da4247397a785d9340cf :segment/blake3-1db5dbd048522aa048759e5fb8ae47ccbf9e158906554b362080d77cf30a6178 :segment/blake3-8930bf5e7b03638896bc3bef03d4f1ae648e7460ed4b488bef2b9d5f619cbd90 :segment/blake3-e60543d220d61497d02184c1f7a087c15c3a20d0f305cae88647987f5ec99e3b] [:segment/blake3-201f235cbac998f43954454e9df0eb633717e79de535776f3f51e198a3759b10 :segment/blake3-81b62c1e0dbf783ce555376690c9cffdee2eaca50ff9da4247397a785d9340cf :segment/blake3-1db5dbd048522aa048759e5fb8ae47ccbf9e158906554b362080d77cf30a6178 :segment/blake3-8930bf5e7b03638896bc3bef03d4f1ae648e7460ed4b488bef2b9d5f619cbd90 :segment/blake3-e60543d220d61497d02184c1f7a087c15c3a20d0f305cae88647987f5ec99e3b :segment/blake3-1e03667ec9fa05b20761de3b84f623962235ff218fce26401bbcdd9d3aabeca8 :segment/blake3-3156a0708410c1e18d4eb15f567790a8c1ac683f219e720a847b0e21e54ca778]))

FAIL in (arity-map-canonical-order-test) (clojure_collection_order_test.cljc:642)
the reproduced named case: argument-vector and lower-unit members :argument-vector
expected: (ascending? (pair-keys c))
  actual: (not (ascending? [[:segment/blake3-81b62c1e0dbf783ce555376690c9cffdee2eaca50ff9da4247397a785d9340cf :segment/blake3-1db5dbd048522aa048759e5fb8ae47ccbf9e158906554b362080d77cf30a6178] [:segment/blake3-8930bf5e7b03638896bc3bef03d4f1ae648e7460ed4b488bef2b9d5f619cbd90 :segment/blake3-e60543d220d61497d02184c1f7a087c15c3a20d0f305cae88647987f5ec99e3b] [:segment/blake3-1e03667ec9fa05b20761de3b84f623962235ff218fce26401bbcdd9d3aabeca8 :segment/blake3-3156a0708410c1e18d4eb15f567790a8c1ac683f219e720a847b0e21e54ca778]]))

FAIL in (arity-map-canonical-order-test) (clojure_collection_order_test.cljc:646)
the reproduced named case: argument-vector and lower-unit members :argument-vector
expected: (contains? #{(quote identity) (quote replay)} (:value (nth (:operands c) 2)))
  actual: (not (contains? #{identity replay} :arity))

FAIL in (arity-map-canonical-order-test) (clojure_collection_order_test.cljc:648)
the reproduced named case: argument-vector and lower-unit members :argument-vector
expected: (= :arity (:value (nth (:operands c) 4)))
  actual: (not (= :arity nil))

FAIL in (arity-map-canonical-order-test) (clojure_collection_order_test.cljc:649)
the reproduced named case: argument-vector and lower-unit members :argument-vector
expected: (= (quote data/count) (get-in (nth (:operands c) 6) [:operator :name]))
  actual: (not (= data/count nil))

FAIL in (arity-map-canonical-order-test) (clojure_collection_order_test.cljc:640)
the reproduced named case: argument-vector and lower-unit members :lower-unit
expected: (= named-arity-key-order (key-literals c))
  actual: (not (= [:yin.k/fn :yin.vm.data/reason :yin.k/argc] [:yin.vm.data/reason :yin.k/argc :yin.k/fn]))

FAIL in (arity-map-canonical-order-test) (clojure_collection_order_test.cljc:641)
the reproduced named case: argument-vector and lower-unit members :lower-unit
expected: (= named-arity-operand-roots (child-roots c))
  actual: (not (= [:segment/blake3-201f235cbac998f43954454e9df0eb633717e79de535776f3f51e198a3759b10 :segment/blake3-1e03667ec9fa05b20761de3b84f623962235ff218fce26401bbcdd9d3aabeca8 :segment/blake3-3156a0708410c1e18d4eb15f567790a8c1ac683f219e720a847b0e21e54ca778 :segment/blake3-81b62c1e0dbf783ce555376690c9cffdee2eaca50ff9da4247397a785d9340cf :segment/blake3-1db5dbd048522aa048759e5fb8ae47ccbf9e158906554b362080d77cf30a6178 :segment/blake3-8930bf5e7b03638896bc3bef03d4f1ae648e7460ed4b488bef2b9d5f619cbd90 :segment/blake3-e60543d220d61497d02184c1f7a087c15c3a20d0f305cae88647987f5ec99e3b] [:segment/blake3-201f235cbac998f43954454e9df0eb633717e79de535776f3f51e198a3759b10 :segment/blake3-81b62c1e0dbf783ce555376690c9cffdee2eaca50ff9da4247397a785d9340cf :segment/blake3-1db5dbd048522aa048759e5fb8ae47ccbf9e158906554b362080d77cf30a6178 :segment/blake3-8930bf5e7b03638896bc3bef03d4f1ae648e7460ed4b488bef2b9d5f619cbd90 :segment/blake3-e60543d220d61497d02184c1f7a087c15c3a20d0f305cae88647987f5ec99e3b :segment/blake3-1e03667ec9fa05b20761de3b84f623962235ff218fce26401bbcdd9d3aabeca8 :segment/blake3-9152e54b99b0c4c686d8ac56747a62eb193d4086fef3202d885ce380d2d7aa9f]))

FAIL in (arity-map-canonical-order-test) (clojure_collection_order_test.cljc:642)
the reproduced named case: argument-vector and lower-unit members :lower-unit
expected: (ascending? (pair-keys c))
  actual: (not (ascending? [[:segment/blake3-81b62c1e0dbf783ce555376690c9cffdee2eaca50ff9da4247397a785d9340cf :segment/blake3-1db5dbd048522aa048759e5fb8ae47ccbf9e158906554b362080d77cf30a6178] [:segment/blake3-8930bf5e7b03638896bc3bef03d4f1ae648e7460ed4b488bef2b9d5f619cbd90 :segment/blake3-e60543d220d61497d02184c1f7a087c15c3a20d0f305cae88647987f5ec99e3b] [:segment/blake3-1e03667ec9fa05b20761de3b84f623962235ff218fce26401bbcdd9d3aabeca8 :segment/blake3-9152e54b99b0c4c686d8ac56747a62eb193d4086fef3202d885ce380d2d7aa9f]]))

FAIL in (arity-map-canonical-order-test) (clojure_collection_order_test.cljc:646)
the reproduced named case: argument-vector and lower-unit members :lower-unit
expected: (contains? #{(quote identity) (quote replay)} (:value (nth (:operands c) 2)))
  actual: (not (contains? #{identity replay} :arity))

FAIL in (arity-map-canonical-order-test) (clojure_collection_order_test.cljc:648)
the reproduced named case: argument-vector and lower-unit members :lower-unit
expected: (= :arity (:value (nth (:operands c) 4)))
  actual: (not (= :arity nil))

FAIL in (arity-map-canonical-order-test) (clojure_collection_order_test.cljc:649)
the reproduced named case: argument-vector and lower-unit members :lower-unit
expected: (= (quote data/count) (get-in (nth (:operands c) 6) [:operator :name]))
  actual: (not (= data/count nil))

FAIL in (arity-map-canonical-order-test) (clojure_collection_order_test.cljc:640)
the reproduced named case: argument-vector and lower-unit members :lower-unit
expected: (= named-arity-key-order (key-literals c))
  actual: (not (= [:yin.k/fn :yin.vm.data/reason :yin.k/argc] [:yin.vm.data/reason :yin.k/argc :yin.k/fn]))

FAIL in (arity-map-canonical-order-test) (clojure_collection_order_test.cljc:641)
the reproduced named case: argument-vector and lower-unit members :lower-unit
expected: (= named-arity-operand-roots (child-roots c))
  actual: (not (= [:segment/blake3-201f235cbac998f43954454e9df0eb633717e79de535776f3f51e198a3759b10 :segment/blake3-1e03667ec9fa05b20761de3b84f623962235ff218fce26401bbcdd9d3aabeca8 :segment/blake3-3156a0708410c1e18d4eb15f567790a8c1ac683f219e720a847b0e21e54ca778 :segment/blake3-81b62c1e0dbf783ce555376690c9cffdee2eaca50ff9da4247397a785d9340cf :segment/blake3-1db5dbd048522aa048759e5fb8ae47ccbf9e158906554b362080d77cf30a6178 :segment/blake3-8930bf5e7b03638896bc3bef03d4f1ae648e7460ed4b488bef2b9d5f619cbd90 :segment/blake3-e60543d220d61497d02184c1f7a087c15c3a20d0f305cae88647987f5ec99e3b] [:segment/blake3-201f235cbac998f43954454e9df0eb633717e79de535776f3f51e198a3759b10 :segment/blake3-81b62c1e0dbf783ce555376690c9cffdee2eaca50ff9da4247397a785d9340cf :segment/blake3-1db5dbd048522aa048759e5fb8ae47ccbf9e158906554b362080d77cf30a6178 :segment/blake3-8930bf5e7b03638896bc3bef03d4f1ae648e7460ed4b488bef2b9d5f619cbd90 :segment/blake3-e60543d220d61497d02184c1f7a087c15c3a20d0f305cae88647987f5ec99e3b :segment/blake3-1e03667ec9fa05b20761de3b84f623962235ff218fce26401bbcdd9d3aabeca8 :segment/blake3-3156a0708410c1e18d4eb15f567790a8c1ac683f219e720a847b0e21e54ca778]))

FAIL in (arity-map-canonical-order-test) (clojure_collection_order_test.cljc:642)
the reproduced named case: argument-vector and lower-unit members :lower-unit
expected: (ascending? (pair-keys c))
  actual: (not (ascending? [[:segment/blake3-81b62c1e0dbf783ce555376690c9cffdee2eaca50ff9da4247397a785d9340cf :segment/blake3-1db5dbd048522aa048759e5fb8ae47ccbf9e158906554b362080d77cf30a6178] [:segment/blake3-8930bf5e7b03638896bc3bef03d4f1ae648e7460ed4b488bef2b9d5f619cbd90 :segment/blake3-e60543d220d61497d02184c1f7a087c15c3a20d0f305cae88647987f5ec99e3b] [:segment/blake3-1e03667ec9fa05b20761de3b84f623962235ff218fce26401bbcdd9d3aabeca8 :segment/blake3-3156a0708410c1e18d4eb15f567790a8c1ac683f219e720a847b0e21e54ca778]]))

FAIL in (arity-map-canonical-order-test) (clojure_collection_order_test.cljc:646)
the reproduced named case: argument-vector and lower-unit members :lower-unit
expected: (contains? #{(quote identity) (quote replay)} (:value (nth (:operands c) 2)))
  actual: (not (contains? #{identity replay} :arity))

FAIL in (arity-map-canonical-order-test) (clojure_collection_order_test.cljc:648)
the reproduced named case: argument-vector and lower-unit members :lower-unit
expected: (= :arity (:value (nth (:operands c) 4)))
  actual: (not (= :arity nil))

FAIL in (arity-map-canonical-order-test) (clojure_collection_order_test.cljc:649)
the reproduced named case: argument-vector and lower-unit members :lower-unit
expected: (= (quote data/count) (get-in (nth (:operands c) 6) [:operator :name]))
  actual: (not (= data/count nil))

FAIL in (arity-map-canonical-order-test) (clojure_collection_order_test.cljc:661)
the anonymous arity error, without and with reader position :anonymous
expected: (ascending? (pair-keys c))
  actual: (not (ascending? [[:segment/blake3-81b62c1e0dbf783ce555376690c9cffdee2eaca50ff9da4247397a785d9340cf :segment/blake3-1db5dbd048522aa048759e5fb8ae47ccbf9e158906554b362080d77cf30a6178] [:segment/blake3-8930bf5e7b03638896bc3bef03d4f1ae648e7460ed4b488bef2b9d5f619cbd90 :segment/blake3-e60543d220d61497d02184c1f7a087c15c3a20d0f305cae88647987f5ec99e3b] [:segment/blake3-476c5dac5555b48ceb9b8f09b90630b669e6e83d1f9c093568ee3626c4f3cc2e :segment/blake3-2550c4d0092928e7ad8950b9b434bf8cfca125164ba6600ba75e8103995e322d]]))

FAIL in (arity-map-canonical-order-test) (clojure_collection_order_test.cljc:663)
the anonymous arity error, without and with reader position :anonymous
expected: (= [:yin.k/kind :yin.vm.data/reason :yin.k/argc] (key-literals c))
  actual: (not (= [:yin.k/kind :yin.vm.data/reason :yin.k/argc] [:yin.vm.data/reason :yin.k/argc :yin.k/kind]))

FAIL in (arity-map-canonical-order-test) (clojure_collection_order_test.cljc:665)
the anonymous arity error, without and with reader position :anonymous
expected: (= :anonymous (:value (nth (:operands c) 2)))
  actual: (not (= :anonymous :arity))

FAIL in (arity-map-canonical-order-test) (clojure_collection_order_test.cljc:661)
the anonymous arity error, without and with reader position :anonymous-with-source
expected: (ascending? (pair-keys c))
  actual: (not (ascending? [[:segment/blake3-81b62c1e0dbf783ce555376690c9cffdee2eaca50ff9da4247397a785d9340cf :segment/blake3-1db5dbd048522aa048759e5fb8ae47ccbf9e158906554b362080d77cf30a6178] [:segment/blake3-8930bf5e7b03638896bc3bef03d4f1ae648e7460ed4b488bef2b9d5f619cbd90 :segment/blake3-e60543d220d61497d02184c1f7a087c15c3a20d0f305cae88647987f5ec99e3b] [:segment/blake3-476c5dac5555b48ceb9b8f09b90630b669e6e83d1f9c093568ee3626c4f3cc2e :segment/blake3-2550c4d0092928e7ad8950b9b434bf8cfca125164ba6600ba75e8103995e322d] [:segment/blake3-58a8f53032ce6533b31ce801072c947e35d87c5aa19faeaca595443d89c4dc9b :segment/blake3-fb4a46fdf560d1d00aaa7108d416c6d955596c9678f09aef34c6f2ff1ba70953]]))

FAIL in (arity-map-canonical-order-test) (clojure_collection_order_test.cljc:666)
the anonymous arity error, without and with reader position :anonymous-with-source
expected: (= [:yin.k/kind :yin.k/source :yin.vm.data/reason :yin.k/argc] (key-literals c))
  actual: (not (= [:yin.k/kind :yin.k/source :yin.vm.data/reason :yin.k/argc] [:yin.vm.data/reason :yin.k/argc :yin.k/kind :yin.k/source]))

FAIL in (arity-map-canonical-order-test) (clojure_collection_order_test.cljc:668)
the anonymous arity error, without and with reader position :anonymous-with-source
expected: (= {:line 7, :column 2} (:value (nth (:operands c) 4)))
  actual: (not (= {:line 7, :column 2} nil))

FAIL in (legacy-artifact-loads-and-runs-test) (clojure_collection_order_test.cljc:1006)
the old order is not the new canonical order
recompiling the same source mints the canonicalized tree
expected: (not= (:root (vm/ast->semantic-bytecode recompiled)) (:root payload))
  actual: (not (not= :segment/blake3-49d9ef3191b305b26e67a803c9e74104dc57d1c5f036147b3fd2352d256f5e64 :segment/blake3-49d9ef3191b305b26e67a803c9e74104dc57d1c5f036147b3fd2352d256f5e64))

FAIL in (legacy-artifact-loads-and-runs-test) (clojure_collection_order_test.cljc:1011)
the fresh compilation canonicalizes and changes the winner
expected: (ascending? (pair-keys ctor))
  actual: (not (ascending? [[:segment/blake3-f4e3d36c45da15b1c95e895de7e464eab5bf2373da95f5aee7dcc6e6ef86f6b8 :segment/blake3-b185918990b11ee142de7689277b4987387b2835215cb5b230c2a0568616a584] [:segment/blake3-628495f3675898de29025fc07fa75aa1d16df9a63edaf08dad7c3c278606ce63 :segment/blake3-398f68e0e5eb30fd9625582cb063f9e02133387de7b3efe07c51ec5d50472879]]))

FAIL in (legacy-artifact-loads-and-runs-test) (clojure_collection_order_test.cljc:1012)
the fresh compilation canonicalizes and changes the winner
expected: (not= (child-roots ctor) (vec (:old-operand-roots payload)))
  actual: (not (not= [:segment/blake3-201f235cbac998f43954454e9df0eb633717e79de535776f3f51e198a3759b10 :segment/blake3-f4e3d36c45da15b1c95e895de7e464eab5bf2373da95f5aee7dcc6e6ef86f6b8 :segment/blake3-b185918990b11ee142de7689277b4987387b2835215cb5b230c2a0568616a584 :segment/blake3-628495f3675898de29025fc07fa75aa1d16df9a63edaf08dad7c3c278606ce63 :segment/blake3-398f68e0e5eb30fd9625582cb063f9e02133387de7b3efe07c51ec5d50472879] [:segment/blake3-201f235cbac998f43954454e9df0eb633717e79de535776f3f51e198a3759b10 :segment/blake3-f4e3d36c45da15b1c95e895de7e464eab5bf2373da95f5aee7dcc6e6ef86f6b8 :segment/blake3-b185918990b11ee142de7689277b4987387b2835215cb5b230c2a0568616a584 :segment/blake3-628495f3675898de29025fc07fa75aa1d16df9a63edaf08dad7c3c278606ce63 :segment/blake3-398f68e0e5eb30fd9625582cb063f9e02133387de7b3efe07c51ec5d50472879]))

FAIL in (legacy-artifact-loads-and-runs-test) (clojure_collection_order_test.cljc:1016)
the fresh compilation canonicalizes and changes the winner
expected: (not= {0 :b} (:ast-walker (run-form program {})))
  actual: (not (not= {0 :b} {0 :b}))

FAIL in (executable-order-and-trace-test) (clojure_collection_order_test.cljc:705)
keys precede their values; pairs evaluate in canonical order
both insertion orders evaluate one canonical trace
expected: (= (into #{} (map :log) (vals traces-a)) (into #{} (map :log) (vals traces-b)))
  actual: (not (= #{[:k1 :v1 :k2 :v2]} #{[:k2 :v2 :k1 :v1]}))

FAIL in (executable-order-and-trace-test) (clojure_collection_order_test.cljc:720)
keys precede their values; pairs evaluate in canonical order set value collisions keep one member; pure results unchanged
:ast-walker
expected: (= #{3 2} value)
  actual: (not (= #{3 2} #{1 3 2}))

FAIL in (executable-order-and-trace-test) (clojure_collection_order_test.cljc:720)
keys precede their values; pairs evaluate in canonical order set value collisions keep one member; pure results unchanged
:semantic
expected: (= #{3 2} value)
  actual: (not (= #{3 2} #{1 3 2}))

FAIL in (executable-order-and-trace-test) (clojure_collection_order_test.cljc:720)
keys precede their values; pairs evaluate in canonical order set value collisions keep one member; pure results unchanged
:stack
expected: (= #{3 2} value)
  actual: (not (= #{3 2} #{1 3 2}))

FAIL in (executable-order-and-trace-test) (clojure_collection_order_test.cljc:720)
keys precede their values; pairs evaluate in canonical order set value collisions keep one member; pure results unchanged
:register
expected: (= #{3 2} value)
  actual: (not (= #{3 2} #{1 3 2}))

Ran 10 tests containing 91387 assertions.
91115 failures, 0 errors.

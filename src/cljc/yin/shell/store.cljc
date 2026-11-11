(ns yin.shell.store
  "The composition's store-selection factory
   (docs/design/yin.repl.dht-composition.md sections 3 and 6).

   The engine's own selection (`yin.repl.store`) knows two stores —
   `mem` and `file:<dir>` — and answers a generic `dao.jing` content
   handle for each.  Every storage implementation past those two is a
   composition's: it owns the scheme, the checked spec, the open, and the
   transport state the open produces.  This namespace is that factory for
   the shell compositions, so a host entry point resolves one
   `--index-store` value against one table instead of asking the engine
   about a backend the engine must never name.

   It owns no socket, atom, promise, callback, clock or namespace global:
   selection is a pure dispatch, and the state an open produces is
   answered as two separate values — the engine's handle and the
   composition's node — never as one handle with a node inside it."
  (:require [yin.repl.store :as store]
            [yin.shell.dht :as shell.dht]
            [yin.shell.query :as shell.query]
            [yin.shell.session :as session]))


(def dht-scheme
  "The store scheme the DHT composition registers with the engine's
   parser, as plain data: a prefix to match, a label for the refusal and
   help text, and a directory-to-spec projection.  The engine matches the
   prefix and calls `:parse`; it never learns what the answer means."
  {:prefix shell.dht/dht-scheme
   :label (str shell.dht/dht-scheme "<dir>")
   :type :dht
   :parse (fn [dir] {:type :dht :dir dir})})


(def schemes
  "Every scheme this factory adds to the engine's own two, in the order
   the parser tries them."
  [dht-scheme])


(defn parse-arg
  "The `--index-store` value as a spec, over the engine's two schemes and
   `schemes`: `:mem`, `{:type :file :dir dir}`, or `{:type :dht :dir
   dir}`.  A missing value, an unknown scheme and an empty directory are
   refused with every supported form named, so the refusal an operator
   reads lists the composition's stores too."
  [value]
  (store/parse-arg value schemes))


(defn composition-spec?
  "True when `spec` names a store a composition opens rather than one the
   engine's own selection does."
  [spec]
  (boolean (and (map? spec)
                (some #(= (:type %) (:type spec)) schemes))))


(defn checked-spec
  "Validate an `:index-store-spec` over both selections: the engine's own
   `:mem` and `{:type :file :dir dir}`, and each registered composition's
   own checked spec.  Anything else is refused with its reason."
  [spec]
  (if (composition-spec? spec)
    (shell.dht/checked-spec spec)
    (store/checked-spec spec)))


(defn open
  "Open the store `spec` names, answering the two values of section 3's
   ownership split:

   ```clojure
   {:index-store <the generic dao.jing handle the engine is constructed
                  with>
    :node <the composition's transport step-owner value, or nil for a
           store with no transport>
    :recovery <what the open validated, or nil>
    :publication-policy <the immutable generic policy of section 5, or nil>
    :retain-index-on-reset? bool}
   ```

   `opts` reaches the composition that opens.  They are either its own
   names (`yin.shell.dht/open`'s `:key`, `:ws`, `:write-heads!`,
   `:open-store`) or the host driver's neutral ones (`:publisher-key`,
   `:ws-host`), translated here, so an engine entry point never learns what
   a composition calls its own inputs.  `:principals`, `:admission` and
   `:name-env` join the prepared `:link-source` of `:engine-inputs`: the
   further `yin.repl/create-state` options this composition injects, so a
   `(reset)` or a `(vm ...)` rebuild reconstructs a session that still
   resolves signed names and still serves the composition's modules.
   `:session` is the transport half of the composition session
   (`yin.shell.session/transport-state`), which a host carries beside its
   engine value and never inside it.

   A refusal releases whatever the failed open acquired, exactly once, and
   propagates as itself.

   The engine is handed `:index-store` and never `:node`; the composition
   keeps the node and steps it
   (docs/design/yin.repl.dht-composition.md sections 3 and 6)."
  ([spec] (open spec {}))
  ([spec opts]
   (if (composition-spec? spec)
     (let [key (or (:key opts) (:publisher-key opts))
           opened (shell.dht/open spec {:key key
                                        :ws (or (:ws opts) (:ws-host opts))
                                        :write-heads! (:write-heads! opts)
                                        :open-store (:open-store opts)})]
       (assoc opened
              :engine-inputs
              {:link-source (shell.dht/link-source
                              spec
                              {:key key
                               :principals (:principals opts)
                               :admission (get opts :admission {})
                               :name-env (:name-env opts)})
               :host-modules shell.query/registrations}
              :session (session/transport-state (:node opened) key)))
     (let [handle (store/open (store/checked-spec spec))]
       {:index-store handle
        :node nil
        :recovery (:recovery handle)
        :publication-policy (:publication-policy handle)
        :retain-index-on-reset? (store/retain-on-reset? handle)
        :engine-inputs nil
        :session nil}))))


(defn durable?
  "True when `store` is a durable directory store the engine's own
   selection opened (yin.repl.store/durable?)."
  [store]
  (store/durable? store))


(defn retain-on-reset?
  "True when a session rebuild continues `store`'s published index rather
   than starting an empty one: the store's declared policy, or the
   durable directory's own fact when it declares none
   (yin.repl.store/retain-on-reset?)."
  [store]
  (store/retain-on-reset? store))


(defn close!
  "Release `store`'s lifecycle resources.  Idempotent, and the one place a
   composition's resource owner closes the store it opened
   (docs/design/yin.repl.dht-composition.md sections 3 and 6)."
  [store]
  (store/close! store))

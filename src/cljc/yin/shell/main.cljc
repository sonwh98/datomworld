(ns yin.shell.main
  "The outer host entry point: the engine's driver plus this project's
   storage composition.

   `yin.repl.main` is the engine's host driver — cadence, one step owner,
   the line producers and the bounded shutdown drain — and it names no
   transport, no peer, no key file and no store past `mem` and `file:<dir>`.
   Everything past that reaches it as one plain-data value, `composition`,
   assembled here out of the composition's own namespaces: the command line
   (`yin.shell.cli`), the store factory (`yin.shell.store`), the session
   where the section 7 adapters meet the engine (`yin.shell.session`), and
   the DHT composition's transport lifecycle (`yin.shell.dht`).

   This is the namespace the shipped binaries run — `deps.edn`'s
   `:clj-yin-repl` and `:jolt-yin-repl`, shadow-cljs's `:yin-repl` build and
   the ClojureDart `:main` — and it is why the engine's own dependency
   closure can stay free of a storage implementation
   (docs/design/yin.repl.dht-composition.md sections 2, 6 and 10, Gate A):
   the require runs this way, and never the other way round.  `yin.shell.cli`
   consumes the engine driver's primitives (`parse-int`, `plain-args`); the
   engine driver consumes nothing of a composition's except the value handed
   to it, so there is no cycle to break and nothing to resolve at runtime.

   This namespace owns no socket, atom, promise, callback, clock or namespace
   global of its own: `composition` is one constant map of function values,
   and each host's `-main` is a single call into the engine driver's `main!`."
  (:require [yin.repl.main :as repl.main]
            [yin.shell.cli :as cli]
            [yin.shell.dht :as shell.dht]
            [yin.shell.session :as shell.session]
            [yin.shell.store :as shell.store]))


(def composition
  "The plain-data bundle this project's hosts hand `yin.repl.main` as its
   `:composition`: every key that driver documents, and nothing that reaches
   past it.

   The command-line half is `yin.shell.cli/hooks` — the flags, the
   subcommands, the key file, the help and the banner text.  The rest is the
   store and its transport lifecycle, each entry one function value taken
   statically from the namespace that owns it, so the wiring is identical on
   the JVM, on Node and on Dart and no host resolves a symbol at runtime
   (docs/design/yin.repl.dht-composition.md sections 6 and 7).

   The lifecycle functions all take the one composition session
   `{:engine … :node … :key …}` and are driven by the engine driver's single
   tick: `:step` advances the transport and answers its lines and its own
   events, `:recheck-on-tick` serves the link and call media the engine
   parks on and translates those events before they cross inward, and
   `:stop!`/`:stopped?`/`:close!` are the one resource owner's exit — asked
   once, completed by `:step`, and forced only by the drain's last resort."
  (merge (cli/hooks)
         {:composition-spec? shell.store/composition-spec?
          :open shell.store/open
          :close-store! shell.store/close!
          :step shell.dht/step
          :recheck-on-tick shell.session/recheck-on-tick
          :recheck-after-input shell.session/recheck-after-input
          :stop! shell.dht/stop!
          :stopped? shell.dht/stopped?
          :close! shell.dht/close!
          :busy? shell.dht/busy?
          :refusal shell.dht/refusal
          :admitting? shell.dht/admitting?
          :startup-lines shell.dht/follow-lines}))


#?(:cljd nil
   :jolt nil
   :clj
   (defn -main
     "The shipped JVM entry point: the engine's driver over `composition`."
     [& args]
     (repl.main/main! args composition)))


#?(:jolt
   (defn -main
     "The shipped jolt entry point: the engine's driver over `composition`."
     [& args]
     (repl.main/main! args composition)))


#?(:cljs
   (defn -main
     "The shipped Node entry point: the engine's driver over `composition`."
     [& args]
     (repl.main/main! args composition)))


#?(:cljd
   (do
     (defn -main
       "The shipped Dart entry point: the engine's driver over `composition`."
       [& args]
       (repl.main/main! args composition))

     (defn ^{:dart/name main} run-main
       [args]
       (apply -main args))))


(defn startup
  "Parse the arguments and compose the shell with this project's
   shipped storage composition."
  ([args] (repl.main/startup args {:composition composition}))
  ([args opts] (repl.main/startup args (assoc opts :composition composition))))


(defn parse-args
  "Parse the arguments using this project's shipped storage composition."
  [args]
  (repl.main/parse-args args composition))


(defn expand-args
  "Expand subcommands using this project's shipped storage composition."
  [args]
  (repl.main/expand-args args composition))

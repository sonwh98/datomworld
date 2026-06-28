# datom.world
A world built on datoms

datom.world is a multi-platform system built on **datoms** and **streams**. It treats all computation as stream processing, where functions consume and produce streams of datoms.

Datoms are immutable tuples in an open moduli space, graded by dimension *n*. The vocabulary of dimensions is open: applications declare new dimensions as needed. The canonical persistent fact is the 5-tuple `[e a v t m]` (entity, attribute, value, transaction, metadata), known as *d5*. Shorter projections — `[v]` (d1, content-addressed blobs) and `[s a v]` (d3, RDF-style triples) — serve as universal floors: d1 for content addressing, d3 for semantic interpretability of fact-shaped data.

**Core components:**
- **Yang**: Compiler frontend that transforms source code (Clojure/Python/PHP) into Universal AST datoms
- **Yin VM**: Family of CESK continuation machines (stack‑based, register‑based, semantic) that execute projections of the Universal AST
- **DaoDB**: Persistent datom store with Datalog queries over indexed datom streams
- **DaoStream**: Stream transport foundation modeling all IO as streams
- **DaoSpace**: Tuple space for stigmergic coordination via shared datom streams
- **PostGraphics**: A backend-neutral graphics frame vocabulary plus a reference Flutter terminal. Producers emit frame programs as data; terminals interpret them as drawing.
- **Shibi**: Capability tokens for authentication and authorization in stream descriptors

**Philosophy:** Everything is data, everything is a stream. Functions are interpreters that consume streams (often datoms) and transform them into higher‑dimensional structures. Structure emerges from constraints, not global ontologies. Graphs are constructed from tuples, not assumed.

## Live Demo

Try the live demo at [https://datom.world/demo.html](https://datom.world/demo.html)

## Development Prerequisites

This project uses [mise](https://mise.jdx.dev/) to manage development tools.

To install the required versions of Java, Clojure, Node.js, and Flutter, run:

```bash
mise install
```

## Development

Start the browser demo build:

```bash
clj -M:cljs -m shadow.cljs.devtools.cli watch demo
```

or open a CLJ REPL and start the build from there:

```bash
clj -M:cljs -m shadow.cljs.devtools.cli clj-repl
shadow.user=> (shadow/watch :demo)
```

Start a CLJS REPL:

```bash
clj -M:cljs -m shadow.cljs.devtools.cli cljs-repl demo
cljs.user=> (js/alert 1)
```

Open http://localhost:9000 (or try the live demo at [https://datom.world/demo.html](https://datom.world/demo.html))

## Testing

The project uses unified auto-discovery for tests across all three compilation targets. Any namespace on the test path ending in `-test` will be automatically discovered and executed.

We use Babashka (`bb`) as a unified task runner.

To run all tests across all platforms:
```bash
bb test
```

Or you can run tests for specific platforms:
```bash
bb test:clj   # Runs JVM tests
bb test:cljs  # Runs Node tests via shadow-cljs
bb test:cljd  # Runs Dart tests
```

## Flutter Prototype

The active ClojureDart demo boots a Flutter surface backed by `dao.postgraphics/postgraphics-widget` and a remote Yin REPL. The demo entrypoint is `src/cljd/datomworld/demo/main.cljd`, which currently launches `datomworld.demo.mr-clean`.

### Android Emulator

Install toolchains first:
```bash
mise install
```

A dedicated Android emulator helper is included:
```bash
bin/run-android-emulator.sh
```

That script:
- boots the `Datomworld_Pixel_3_API_34` AVD
- waits for Android boot completion
- runs `flutter run` against that emulator instead of your phone

Useful variants:
```bash
bin/run-android-emulator.sh --boot-only
bin/run-android-emulator.sh --avd Pixel_3_API_34
```

If you want to launch the emulator manually:
```bash
emulator -avd Datomworld_Pixel_3_API_34
flutter run -d emulator-5554
```

### Remote REPL UI Prototype

The prototype screen starts a REPL server on port `7777` and exposes helpers for pushing either raw PostGraphics frames or `mr-clean` UI compiled into PostGraphics.

Connect from a desktop REPL:
```bash
mise exec -- clj -M:yin-repl
```

Then from the Yin REPL:
```clojure
(connect "daostream:ws://<ip>:7777")
(show-demo-ui!)
(show-sample-frame!)
(clear-frame!)
```

Push a raw frame:
```clojure
(set-frame!
 [{:op/kind :frame/clear :color [0 0 0 1]}
  {:op/kind :draw/fill-rect
   :rect [20 20 120 60]
   :color [0.2 0.6 1 1]}])
```

Or compile `mr-clean` UI directly:
```clojure
(set-ui!
 [:column
  [:rect {:width 80 :height 24}]
  [:text {:value "hello" :font-size 18}]])
```

## Agent Tzu

Agent Tzu is an autonomous agent built on `dao.stream` that can interact with OpenAI-compatible LLMs to perform tasks like fact extraction (datoms), natural language reconstruction, and generating PostGraphics animations.

For information on how to configure Agent Tzu with different LLM providers (OpenAI, DeepSeek, Groq, Ollama, etc.), see [src/cljc/agent/llm-configuration.md](src/cljc/agent/llm-configuration.md).

### Agent Tzu REPL

You can interact with Agent Tzu through a command-line REPL. First, set up your environment variables by copying the example file:

```bash
cp src/cljc/agent/env.example.sh env.sh
# Edit env.sh to add your API key and choose your provider
source env.sh
clj -M -m agent.tzu
```

## Yin REPL

Launch the interactive Yin REPL to experiment with the Yin VM and manipulate datoms directly.

### Clojure (JVM)
```bash
clj -M:yin-repl
```

### ClojureScript (Node.js)

First, compile the ClojureScript source to a Node.js script:

```bash
clj -M:cljs -m shadow.cljs.devtools.cli compile yin-repl
```

Then, run the compiled script:

```bash
node target/yin-repl.js
```

### ClojureDart (cljd)
The ClojureDart source for the Yin REPL is located in `src/cljd/yin/repl_main/cljd.cljd`.

Compile the ClojureDart namespace to Dart:

```bash
clj -M:cljd compile yin.repl-main.cljd
```

Then run the generated Dart entry point:

```bash
dart run bin/yin_repl_main.dart
```

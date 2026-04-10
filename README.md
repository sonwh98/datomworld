# datom.world
A world built on datoms

datom.world is a multi-platform system built on **datoms** (immutable 5‑tuples `[e a v t m]`) and **streams**. It treats all computation as stream processing, where functions consume and produce streams of datoms.

**Core components:**
- **Yang**: Compiler frontend that transforms source code (Clojure/Python/PHP) into Universal AST datoms
- **Yin VM**: Family of CESK continuation machines (stack‑based, register‑based, semantic) that execute projections of the Universal AST
- **DaoDB**: Persistent datom store with Datalog queries over indexed datom streams
- **DaoStream**: Stream transport foundation modeling all IO as streams
- **DaoSpace**: Tuple space for stigmergic coordination via shared datom streams
- **DaoFlow**: Massively parallel computation runtime that interprets datom streams as unified workloads across CPU/GPU, spanning graphics rendering, scientific simulation, and reactive graphical user interfaces
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

### Clojure (JVM)
Run the unit tests for the Clojure backend:
```bash
clj -M:test
```

### ClojureScript (Node.js)
Compile and run the tests for the frontend/CLJS logic using the Clojure CLI:
```bash
clj -M:cljs -m shadow.cljs.devtools.cli compile test && node target/node-tests.js
```

## Dao REPL

Launch the interactive Dao REPL to experiment with the Yin VM and manipulate datoms directly.

### Clojure (JVM)
```bash
clj -M:dao-repl
```

### ClojureScript (Node.js)

First, compile the ClojureScript source to a Node.js script:

```bash
clj -M:cljs -m shadow.cljs.devtools.cli compile dao-repl
```

Then, run the compiled script:

```bash
node target/dao-repl.js
```

### ClojureDart (cljd)
The ClojureDart source for the Dao REPL is located in `src/cljd/dao/repl_main/cljd.cljd`.

Compile the ClojureDart namespace to Dart:

```bash
clj -M:cljd compile dao.repl-main.cljd
```

Then run the generated Dart entry point:

```bash
dart run bin/dao_repl_main.dart
```

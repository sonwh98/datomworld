# datomworld
A world built on data

## Prerequisites

This project uses [mise](https://mise.jdx.dev/) to manage development tools.

To install the required versions of Java, Clojure, Node.js, and Flutter, run:

```bash
mise install
```

## Development

Start the browser build:

```bash
npx shadow-cljs watch datomworld
```

or open a CLJ REPL and start the build from there:

```bash
npx shadow-cljs clj-repl
shadow.user=> (shadow/watch :datomworld)
```

Start a CLJS REPL:

```bash
npx shadow-cljs cljs-repl datomworld
cljs.user=> (js/alert 1)
```

Open http://localhost:9000

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

### Dart / Flutter
Run the Dart unit tests (including the Yin VM Dart implementation):
```bash
flutter test
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

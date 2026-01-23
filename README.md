# datomworld
A world built on data

```bash
% npx shadow-cljs watch datomworld
```

or

```bash
% npx shadow-cljs clj-repl
shadow.user=> (shadow/watch :datomworld) 
```

start cljs REPL

```bash
% npx shadow-cljs cljs-repl datomworld

cljs.user=> (js/alert 1)

```

open http://localhost:9000

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

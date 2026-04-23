# Flutter REPL Demo: Local Run Guide

This demo starts a Dao REPL server inside the Flutter app on port `7777`.
It demonstrates dynamically calling CLJD functions from a remote Dao REPL to change the Flutter UI at runtime.

## Prerequisites

- Install [mise](https://mise.jdx.dev/getting-started.html)
- Android device connected over USB with `adb`

All tool dependencies for this demo are managed by the repo's [`mise.toml`](../../../../mise.toml), including:

- Java
- Clojure
- Flutter
- Android SDK / `adb`

Install the managed toolchain from the repo root:

```bash
mise install
```

You can either activate `mise` in your shell, or run commands through `mise exec -- ...`.
The examples below use `mise exec --` so they work even if your shell is not activated.

## 1. Compile the CLJD sources

The Flutter app runs generated Dart from `lib/cljd-out`, so compile CLJD before starting the app:

```bash
mise exec -- clj -M:cljd compile dao.stream.ws dao.repl datomworld.demo.repl datomworld.main
```

## 2. Clean and run the app

```bash
mise exec -- flutter clean
mise exec -- flutter run
```

The app should show the REPL demo screen with `status: listening`.

## 3. Forward the REPL port over USB

USB forwarding is the most reliable local setup:

```bash
mise exec -- adb forward tcp:7777 tcp:7777
```

This makes the Android app's port `7777` reachable from your laptop as `localhost:7777`.

## 4. Connect from the desktop Dao REPL

Start a JVM Dao REPL:

```bash
mise exec -- clj -M:dao-repl
```

Connect to the Flutter app:

```clojure
(connect "daostream:ws://localhost:7777")
```

After the connection succeeds:

- the desktop REPL should print `Connected to ws://localhost:7777`
- the Android app should change from `status: listening` to `status: client connected`

## 5. Try a few remote evaluations

Basic eval:

```clojure
1
(+ 1 2 3)
```

Change the button label in the app:

```clojure
(set-button-text! "wassup")
```

Override the button handler:

```clojure
(def button-handler (fn [] "Hi from remote REPL"))
```

## 6. Disconnect

```clojure
(disconnect)
```

## Troubleshooting

If the desktop REPL says `Connected...` but evaluation times out:

1. Make sure the app screen says `status: client connected`
2. If it still says `status: listening`, redo:
   ```bash
   mise exec -- adb forward tcp:7777 tcp:7777
   ```
3. Recompile CLJD and fully restart the app:
   ```bash
   mise exec -- clj -M:cljd compile dao.stream.ws dao.repl datomworld.demo.repl datomworld.main
   mise exec -- flutter clean
   mise exec -- flutter run
   ```

Do not rely on hot reload for REPL server changes. Restart the app after recompiling CLJD.

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

If you are in China and `mise install` cannot download Flutter, install Flutter from the mirror first:

```bash
bin/download-flutter-mirror.sh
```

This script resolves the exact Flutter release from the China mirror, downloads it with a visible `curl` progress bar, unpacks it, and registers it with `mise`.

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

### Running Flutter in China

If `flutter run` stalls during Android build with output like:

```text
Running Gradle task 'assembleDebug'...
[        ] Downloading https://storage.googleapis.com/download.flutter.io/...
```

the blocker is usually not your app code. The Android build is trying to fetch Flutter engine artifacts from Google storage.

`mise.toml` can point `mise` at the China mirror for the initial Flutter SDK download, but Android Gradle still needs the mirror environment variables in the process that runs `flutter`.

Run Flutter with the China mirror exported explicitly:

```bash
FLUTTER_STORAGE_BASE_URL=https://storage.flutter-io.cn \
PUB_HOSTED_URL=https://pub.flutter-io.cn \
mise exec -- flutter run
```

Use the same pattern for other Flutter commands:

```bash
FLUTTER_STORAGE_BASE_URL=https://storage.flutter-io.cn \
PUB_HOSTED_URL=https://pub.flutter-io.cn \
mise exec -- flutter clean
```

Why this is necessary:

- `PUB_HOSTED_URL` redirects Dart and Flutter package downloads to the China mirror
- `FLUTTER_STORAGE_BASE_URL` redirects Flutter engine and artifact downloads away from Google storage
- without these variables, Gradle may still try to fetch Android Flutter artifacts from `storage.googleapis.com`, which is often slow or unreachable in China

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

If `mise exec -- flutter run` hangs at `Running Gradle task 'assembleDebug'...`:

1. Rerun it with the China mirror variables exported:
   ```bash
   FLUTTER_STORAGE_BASE_URL=https://storage.flutter-io.cn \
   PUB_HOSTED_URL=https://pub.flutter-io.cn \
   mise exec -- flutter run -v
   ```
2. If Flutter itself is missing or `mise` reports the configured Flutter version as missing, reinstall it from the mirror:
   ```bash
   bin/download-flutter-mirror.sh
   ```
3. If needed, verify that the mirror variables are visible inside `mise`:
   ```bash
   FLUTTER_STORAGE_BASE_URL=https://storage.flutter-io.cn \
   PUB_HOSTED_URL=https://pub.flutter-io.cn \
   mise exec -- env | rg 'FLUTTER_STORAGE_BASE_URL|PUB_HOSTED_URL'
   ```

# Dao REPL Usage Guide

The Dao REPL can operate as both a local interactive shell and a WebSocket server, allowing remote connections for evaluation.

## Starting the REPL Server

You can start a REPL server on a specific port using the `--port` flag. This is supported on both the JVM and Dart platforms.

### Clojure (JVM)
To run the JVM REPL server with a local prompt:
```bash
clj -M:dao-repl --port 8080
```

To run the JVM REPL server in **headless** mode:
```bash
clj -M:dao-repl --port 8080 --headless
```

### ClojureDart (Dart VM)
To compile the Dart REPL entrypoint:
```bash
clj -M:cljd compile dao.repl-main.cljd
```

To run the Dart REPL server:
```bash
dart bin/dao_repl_main.dart --port 8080
```

### Node.js (ClojureScript)
To compile the Node.js REPL entrypoint:
```bash
clj -M:cljs -m shadow.cljs.devtools.cli compile dao-repl
```

To run the Node.js REPL server:
```bash
node target/dao-repl.js --port 8080
```

## Connecting to a Remote REPL

Once a REPL server is running, you can connect to it from another interactive JVM REPL using the `connect` command.

1. Start a local REPL:
   ```bash
   clj -M:dao-repl
   ```

2. Connect to the remote server:
   ```clojure
   dao> (connect "daostream:ws://localhost:8080")
   Connected to ws://localhost:8080
   ```

3. Evaluate expressions remotely:
   All subsequent inputs (except local-only commands) will be sent to the remote REPL for evaluation.
   ```clojure
   dao> (+ 1 2)
   3
   ```

4. Disconnect to return to local evaluation:
   ```clojure
   dao> (disconnect)
   Disconnected from remote shell
   ```

## Command Reference

| Command | Description |
|---------|-------------|
| `(help)` | Show available commands |
| `(connect "url")` | Connect to a remote DaoStream WebSocket |
| `(disconnect)` | Disconnect from the current remote session |
| `(repl-state)` | Show status of the current REPL (local and remote) |
| `(vm :type)` | Switch the evaluation VM (`:semantic`, `:register`, `:stack`, `:ast-walker`) |
| `(lang :lang)` | Switch input language (`:clojure`, `:python`, `:php`) |
| `(telemetry)` | Enable/disable telemetry output |
| `(quit)` | Exit the REPL |

(connect "daostream:ws://10.153.137.250:7777")

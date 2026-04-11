# Dao REPL Usage Guide

The Dao REPL can operate as both a local interactive shell and a WebSocket server, allowing remote connections for evaluation.

## Starting the REPL Server

You can start a REPL server on a specific port using the `--port` flag. This is supported on both the JVM and Dart platforms.

### Clojure (JVM)
To start an interactive REPL that also listens for remote connections:
```bash
clj -M:dao-repl --port 8080
```

To start a server in **headless** mode (no local prompt):
```bash
clj -M:dao-repl --port 8080 --headless
```

### ClojureDart (Dart VM)
To start the REPL on the Dart platform:
```bash
dart bin/dao_repl_main.dart --port 8080
```

### Node.js (ClojureScript)
To start the REPL on Node.js, you first need to compile it using `shadow-cljs`:

1. Compile the build:
   ```bash
   npx shadow-cljs compile dao-repl
   ```

2. Run the generated script:
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

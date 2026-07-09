# Yin REPL Usage Guide

The Yin REPL can operate as both a local interactive shell and a WebSocket server, allowing remote connections for evaluation.

## Starting the REPL Server

You can start a REPL server on a specific port using the `--port` flag. By default, the server binds to `127.0.0.1` (localhost only) for security. To bind to a different interface (e.g. `0.0.0.0` to allow external connections), use the `--host` flag. This is supported across JVM, Node.js, and Dart VM platforms.

### Clojure (JVM)
To run the JVM REPL server with a local prompt:
```bash
clj -M:clj-yin-repl --port 8080
```

To run the JVM REPL server in **headless** mode:
```bash
clj -M:clj-yin-repl --port 8080 --headless
```

### Node.js (ClojureScript)
To compile and run the Node.js REPL server directly in one command:
```bash
clj -M:cljs-yin-repl --port 8080
```

To run the Node.js REPL server in **headless** mode:
```bash
clj -M:cljs-yin-repl --port 8080 --headless
```

### ClojureDart (Dart VM)
To compile and run the ClojureDart REPL server directly in one command:
```bash
clj -M:cljd-yin-repl --port 8080
```

To run the ClojureDart REPL server in **headless** mode:
```bash
clj -M:cljd-yin-repl --port 8080 --headless
```

## Connecting to a Remote REPL

Once a REPL server is running, you can connect to it from another interactive JVM REPL using the `connect` command.

1. Start a local REPL:
   ```bash
   clj -M:yin-repl
   ```

2. Connect to the remote server:
   ```clojure
   yin> (connect "daostream:ws://localhost:8080")
   Connected to ws://localhost:8080
   ```

3. Evaluate expressions remotely:
   All subsequent inputs (except local-only commands) will be sent to the remote REPL for evaluation.
   ```clojure
   yin> (+ 1 2)
   3
   ```

4. Disconnect to return to local evaluation:
   ```clojure
   yin> (disconnect)
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

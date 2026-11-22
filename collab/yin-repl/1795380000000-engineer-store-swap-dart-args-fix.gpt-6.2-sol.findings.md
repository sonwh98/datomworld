# Host-free DHT argument tests

The fix is uncommitted on `store-swap-p1`.

- `test/yin/repl/dht_args_test.cljc:13` gives both join tests explicit scratch paths for `--dir` and `--key`, so subcommand expansion can run without a home directory. The scratch directory differs from `reader` to preserve the check that `--index-store dht:reader` wins in all three flag positions.
- `test/yin/repl/dht_args_test.cljc:47` extends the startup fixture with an in-memory key-loader stub alongside its existing store stub. The test keeps its startup, peer, and follow assertions and uses no key file or durable store.

## Verification

```sh
timeout 120s clojure -M:test -n yin.repl.dht-args-test
```

Exit 0. Four tests and 26 assertions passed with zero failures and zero errors.

The same JVM namespace passed all 26 assertions with home discovery forced to return nil, matching the condition that exposed the Dart failures.

```sh
timeout 120s clojure -Sdeps '{:aliases {:args-check {:extra-paths ["test"]}}}' -M:args-check -e '(require (quote yin.repl.dht-args-test)) (let [result (with-redefs [yin.shell.cli/home-dir* (constantly nil)] (clojure.test/run-tests (quote yin.repl.dht-args-test)))] (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1)))'
```

Node also exited 0 with four tests and 26 assertions, zero failures, and zero errors. Autorun was disabled, and the runtime selected only this namespace with `HOME` unset. The temporary dependency symlink was removed after the run.

```sh
ln -s /Users/sto/workspace/datomworld/node_modules node_modules && { trap 'unlink node_modules' EXIT; PATH="/Users/sto/.local/share/mise/installs/java/21.0.2/bin:$PATH" timeout 120s clojure -M:cljs -m shadow.cljs.devtools.cli compile test --config-merge '{:ns-regexp "^yin[.]repl[.]dht-args-test$" :autorun false}' && env -u HOME timeout 120s node target/node-tests.js --test=yin.repl.dht-args-test; }
```

`git diff --check` exited 0. This round used the JVM and Node to exercise the no-home condition.

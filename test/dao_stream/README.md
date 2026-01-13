# DaoStream Tests

## JVM/Clojure Tests

Run with:
```bash
clj -M:test -n dao-stream.file-stream-test
```

All tests passing: ✅ 11 tests, 46 assertions

## Node.js/ClojureScript Tests

The Node.js tests are in `file_stream_node_test.cljs`.

### Running Tests

**Option 1: Using shadow-cljs directly (recommended)**
```bash
npx shadow-cljs compile test
node target/node-tests.js
```

**Option 2: Using deps.edn alias**
```bash
clj -M:test-node
```

**Watch mode:**
```bash
clj -M:test-node-watch
```

### Test Coverage

The Node.js test suite mirrors the JVM tests:
- test-create-stream
- test-open-stream
- test-write-single-datom
- test-write-multiple-datoms
- test-read-datoms
- test-read-with-limit
- test-status
- test-close-stream
- test-datom-helper-functions
- test-retract-datom
- test-filter-datoms-client-side
- test-multiple-streams
- test-automatic-directory-creation
- test-persistence-across-instances

### Troubleshooting

If tests aren't found:
1. Ensure `test` is in shadow-cljs.edn `:source-paths`
2. Clean the build cache: `rm -rf .shadow-cljs target`
3. Verify Node.js is installed: `node --version`
4. Check namespace matches file name (hyphens → underscores)

## Browser Tests

Browser tests require an interactive test page with the File System Access API.

*Status:* To be implemented - requires browser test harness with file picker integration.

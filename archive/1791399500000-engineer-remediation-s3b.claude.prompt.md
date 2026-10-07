You are the Implementation Engineer for Track B Slice S3b.
Model: Claude Opus 5.5.
Repository root: /Users/sto/workspace/datomworld-stream-s3a
Branch: stream-crossmachine-s3b

The independent adversarial review by Codex has completed with verdict `REVISE`:
Read carefully: `collab/1791399000000-reviewer-stream-s3b.codex.findings.md`

You must fix all required revisions:
1. R1 (P2: Bind Port vs Advertised Port):
   - In `src/cljc/yin/repl/serve.cljc`: in `base`, pass `:bind-port bind-port` inside `:spec` (or maintain both `:port advertised-port` and `:bind-port bind-port`).
   - In `src/cljc/dao/stream/remote_channel.cljc`: in `serve`'s `:bind!` call, use `:bind-port (or (:bind-port spec) (:port spec))`, matching `:bind-host (or (:bind-host spec) (:host spec))`.
   - Ensure `descriptor-of` continues to name `:port (:port spec)` (the advertised port).
   - Add regression tests in `test/yin/repl/serve_test.cljc` (or `remote_channel_test.cljc`) testing unequal bind-port vs advertised-port (e.g. listener binds 8080, advertised URL / descriptor names 9090).

2. R2 (P2: Nil identity in multi-identity dial):
   - In `src/cljc/dao/stream/remote_channel.cljc`: in `attach-identities`, test sequence exhaustion independently of identity value (e.g. `(if (empty? identities) ...)` or loop with `(if (empty? remaining) ...)`).
   - Also check `valid-target?` for `:identities`: if nil identities are unsupported, explicitly validate `(every? some? ids)` or handle nil properly without dropping elements.
   - Add regression tests in `test/dao/stream/remote_channel_test.cljc` proving sequence exhaustion and handle keys match requested identities.

3. R3 (Gate D10 Test Boundary Scope):
   - In `test/yin/repl/dht_head_test.cljc`: migrate any direct `:ws/` descriptor and require of `dao.stream.ws` to use the shared transport / net fixture or clean descriptors so that `grep -ln ":ws/\|dao.stream.ws\b\|ws-project" test/yin/repl/*.clj* | grep -v "host/"` matches NOTHING.
   - Document any host adapter exception if applicable.

4. R4 (Formatting):
   - Run `cljstyle fix test/dao/stream/remote_channel_test.cljc` (and any other modified files) so that `cljstyle check` exits 0.

5. Address Codex note on drain-gap / teardown:
   - Ensure `:released` is consistently recorded if observability warrants it, and add targeted test coverage.

6. Verify:
   - Run `clojure -M:test -n dao.stream.remote-channel-test -n yin.repl.connect-test -n yin.repl.serve-test -n yin.repl.serve-connect-wire-test`
   - Run D10 grep gates.
   - Run `clj -M:kondo --lint src test`
   - Run `cljstyle check`

Update `collab/1791398000000-engineer-stream-s3b.claude-opus-5-5.findings.md` or append a section detailing your remediation.
Do not commit. Await review.

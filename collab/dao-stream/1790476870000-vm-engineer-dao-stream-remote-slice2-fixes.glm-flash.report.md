Both P1 findings fixed; only the two permitted files touched; gate-confirmed behavior preserved (nine ratified readings, mirror dispatch order, no privilege roles — all pre-existing tests unchanged and green).

**Fix 1 — refused send no longer registered (P1, remote.cljc:278)**
`/Users/sto/workspace/datomworld/src/cljc/dao/stream/remote.cljc:278-289`: `send-request!` now calls `register!` only when the writer answered `:dao.stream/ok`. A send refused with `full` leaves the request unsent and nothing outstanding, so `outstanding-for` (remote.cljc:549) does not strand the next ask — the retry cases re-send on the next ask, exactly per 2.4's "leaves the request unsent and the operation answers as though unanswered". This covers the attach probe (remote.cljc:823) and cursor/next sends; `refl-append` (remote.cljc:620-628) already registered only accepted appends and is untouched.

**Fix 2 — installed outcomes keyed by served identity AND cursor (P1, remote.cljc:374)**
- `remote.cljc:369-387` `install-more!`: installs each more outcome under `[:filed-cursors [identity cur]]`, with `identity` taken from the answer envelope — reflections of the same stream share the same key, so sharing on the link is retained.
- `remote.cljc:524-534` `filed-next!`: consumes the installed outcome at `[(:identity @refl) c]`; a next on one identity can no longer return another stream's outcome at an equal cursor.
- `remote.cljc:217-226`: `new-link` docstring updated to state the identity+cursor keying. No other consumer of `:filed-cursors` exists.

**Tests added** (`/Users/sto/workspace/datomworld/test/dao/stream/remote_test.cljc`)
- Helper `full-then-forward-writer` (:148): a writer refusing the first n appends with `:dao.stream/full`, then forwarding.
- Helper `list-stream` (:163): a minimal served reader whose cursors are bare positions, so two served identities can hold equal cursor values (ringbuffer cursors embed a random-uuid identity and can never be `=`).
- `a-full-refused-send-leaves-nothing-outstanding` (:583): attach and first read pass through a refusing writer; the read answers unanswered-style (`transport-error` + `retry?`), nothing crosses; when the writer recovers the very next ask sends (1 cursor request on the wire — 0 under the old code, which strands the outstanding entry), and after one `serve!` the following ask returns the source's own cursor outcome.
- `installed-outcomes-stay-with-their-own-stream` (:488): two identities "p"/"q" on one link, equal cursor values asserted; each reflection's next returns only its own stream's outcomes (`:a1/:a2` vs `:b1/:b2`); a third reflection of "p" shares p's installed outcome without a request, and q's installed outcome is untouched.
- ns docstring extended with the two new proofs.
- Bite check: with both fixes temporarily reverted, exactly these two tests fail (6 assertions); all 18 pre-existing tests pass. Fixed file restored from /tmp; no git state operations used.

**Lane verification (sequential, solo, exact counts)**
- JVM `mise exec -- clojure -M:test`: Ran 2,254 tests containing 183,208 assertions. 0 failures, 0 errors. (baseline 2,252/183,193/0; +2/+15)
- Node `mise exec -- bb test:cljs`: Ran 2,166 tests containing 49,814 assertions. 0 failures, 0 errors. (baseline 2,164/49,799/0; +2/+15)
- Dart `mise exec -- bb test:cljd`: All tests passed! — 2,128 passed. (baseline 2,126; +2; new tests verified present in the fresh `test/cljd-out/dao/stream/remote-test_test.dart`)

Hygiene: `cljstyle check` clean on both files; `clj-kondo` 0 errors, 0 warnings; pure ASCII; no line over 80 columns; no leftover diagnostics; no tracked file modified and no commit/stage/checkout/reset/stash performed.

Status: COMPLETE
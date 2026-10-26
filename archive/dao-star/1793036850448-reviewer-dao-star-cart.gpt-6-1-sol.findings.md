Created-GMT: 2026-10-27 00:27:30 GMT  
Created-Local: 2026-10-27 03:27:30 +07  
Coding-Agent: codex  
Session-ID: pending (provider-generated)  
Role: Reviewer (adversarial)

**Verdict: NOT SIGNED OFF**

Read-only static review completed. No edits, staging, commits, or suite reruns.

1. **HIGH | [edge.clj:44](/Users/sto/workspace/datomworld/.claude/worktrees/dao-star-demo/src/clj/dao/star_cart/edge.clj:44) | Parent-directory symlinks escape the asset root.** `startsWith` checks the lexical path, while `NOFOLLOW_LINKS` rejects only a symlink at the final component. With `public/images/link → /etc`, `/images/link/passwd` passes both checks and serves an external regular file. Image validation inherits this weakness. | Resolve and check real-path containment, or reject symlinks throughout the relative path. Add an intermediate-directory symlink test.

2. **MEDIUM | [catalog.clj:23](/Users/sto/workspace/datomworld/.claude/worktrees/dao-star-demo/src/clj/dao/star_cart/catalog.clj:23) | Valid tagories merge after restart.** Tempids depend solely on `(hash name)`. On the configured Clojure runtime, `"Aa"` and `"BB"` collide. Add them to different items, save, and restart: boot assigns both names to one entity, so both items acquire both tagories. Removing either name then removes their shared link. | Allocate collision-free tempids per distinct name. Pin add/save/reload/remove with this collision pair.

3. **MEDIUM | [view.cljc:91](/Users/sto/workspace/datomworld/.claude/worktrees/dao-star-demo/src/cljc/dao/star_cart/view.cljc:91) | The promised automatic reconnect is absent.** `@get` uses RC.7’s default `retry: "auto"`. The vendored client retries a successful SSE stream’s clean EOF only with `retry: "always"`. A lease-driven clean close therefore ends the subscription without reconnecting. `a-reconnect-re-projects-the-current-cart` manually opens another socket and cannot catch this. | Set `retry: 'always'` on subscription GETs and verify browser reconnection after a shortened cap.

4. **MEDIUM | [store.clj:122](/Users/sto/workspace/datomworld/.claude/worktrees/dao-star-demo/src/clj/dao/star_cart/store.clj:122) | Concurrent saves can regress the persisted catalog.** The atom serializes datom updates, but snapshots run outside that serialization. Save A can capture an older snapshot, save B can write a newer snapshot, and A can rename last. Both changes remain in memory while B disappears after restart. The concurrency test checks only memory. The current single effects loop avoids this interleaving, but the store’s advertised concurrency guarantee does not hold. | Serialize the complete update-and-snapshot operation per store; test forced rename ordering and reopen the resulting file.

5. **MEDIUM | [grid_effects.clj:56](/Users/sto/workspace/datomworld/.claude/worktrees/dao-star-demo/src/clj/dao/star_cart/grid_effects.clj:56) | Accepted encoded image URLs render as broken URLs.** Validation decodes `/images/a%20b.png` and finds `a b.png`, then stores the encoded spelling. `enc-path` subsequently changes `%20` to `%2520`; the edge decodes once and looks for a literal `%20` filename. Existing product filenames contain spaces. | Store a canonical decoded path, or consistently preserve an encoded representation. Test an encoded spelling of an existing filename.

6. **MEDIUM | [view.cljc:58](/Users/sto/workspace/datomworld/.claude/worktrees/dao-star-demo/src/cljc/dao/star_cart/view.cljc:58) | Valid field contents damage projection and subsequent edits.** SSE generation replaces LF with spaces but leaves CR untouched. Multiline descriptions embedded by `js-lit` become flattened browser signal values; selecting an item and saving only its price can consequently overwrite its description. Accepted CR characters split SSE lines because RC.7 treats CR as a line terminator. | Escape JavaScript line terminators and preserve HTML content through correct SSE framing. Cover LF, CRLF, and bare CR through selection and save.

7. **MEDIUM | [store.clj:99](/Users/sto/workspace/datomworld/.claude/worktrees/dao-star-demo/src/clj/dao/star_cart/store.clj:99) | Default local runs silently omit persistence.** `bb cart` supplies no `CATALOG_PATH`, and `main` provides no local default. `snapshot!` then does nothing while saves still report success, contrary to the unconditional snapshot-on-save description. Docker supplies the variable correctly. | Provide a writable local default, or explicitly announce and document memory-only operation when the variable is absent.

The following requested properties passed inspection, subject to those findings:

- One `assemble` function creates two independent compositions; `/grid` rewriting and static dispatch remain at the host edge. The approved `jvm/handler` visibility change introduces no logic change.
- Both programs perform stream operations only. Cart state remains in the continuation; catalog writes and validation reside in the host interpreter.
- The ordinary save flow emits `saving`, transacts, then processes confirmation through the driver before projecting `saved`. Null-exchange effects are ignored by the HTTP boundary. Independent ring-buffer cursors support the second observer.
- Flattened refs, unchanged scalar skips, and explicit retract metadata match the actual transaction/query semantics.
- Snapshot replacement uses a sibling temporary file and atomic rename. Missing-file seeding and write-failure fallback are present.
- Field bounds, local-image restrictions, HTML escaping, no-auth disclosure, and stale-until-navigation disclosure are present. Both blank placeholders are explicitly acknowledged.
- The 256-exchange limits and one-hour caps are configured. Supplied loopback, smoke, lint, and deployment evidence supports the exercised paths; it does not establish browser behavior, forced persistence races, symlink containment, or collision-safe reloads.
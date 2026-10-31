Completed-GMT: 2026-09-25 23:46:18 GMT
Completed-Local: 2026-09-26 06:46:18 +07

# Is `dao.jing.remote.step` redundant under `dao.stream.serve`?

Short answer: no, but half of the owner's suspicion is right. What serve makes redundant is the **transport half of `dao.jing.remote`** (the WebSocket descriptor, `connect-content!`, `serve-content!` and their endpoint wiring). `remote.step` is not transport; it is the jing-level request/response client, and something with that job still has to exist above any stream, served or not. It should be kept, and it should become the *only* client of the jing wire vocabulary, which today is implemented three times.

## 1. What each layer provides

**Stream level, which serve covers and step does not:** making a handle reachable as itself across a network; sessions and attachment identity; cursors relayed verbatim with `gap`; channels (ws, udp, in-process rings, a proxied inbox pair); NAT traversal and peer symmetry. Step has none of this and needs none of it: its "transport" is one `dao.stream.rpc` client state, which is a writer handle, a reader handle and a cursor. It is already indifferent to what those handles are. Under serve they become proxy handles; step does not change.

**Jing level, which step covers and serve cannot:** a content store is random access by address; a stream is sequential. Reading by address is therefore a request/response protocol *over* two streams whatever the transport, and serve only delivers the streams. Step owns:

- the operation vocabulary (`:jing/get-content`, `:jing/put-content`), argument encoding (address, Base64 canonical bytes) and the presence envelope;
- correlation of many outstanding requests through rpc ids, with one retained unsent envelope and the fixed five-order step;
- the `materialize!` contract's verify hop: a `:present` answer is not trusted, a correlated read-back is issued, and only bytes that hash to the address complete the materialization (B3);
- the ingress check on every found reply: strict Base64, digest against the requested address, one canonical decode.

None of this could live in a proxy. A proxy is a transport and must not know what a payload claims to be (Axiom 2, and the layering rule that a channel interprets nothing). So the division is clean: **serve owns reachability; step owns meaning.** The owner's phrase "mechanically expose any dao.stream implementation" is about handles, and a jing store is not a handle. What it exposes is the request and response streams a jing service reads and writes.

## 2. Per consumer

**(a) The linker.** Not needed, and correctly so. M3 speaks rpc directly and runs `checked-part` in the M2 order. Under serve the linker's rpc state is built over two proxy handles instead of a ws attachment's media; no linker code changes. One correction to the framing: step's decode order is already hash-then-decode. The only thing it lacks for the linker is a byte cap *before* hashing, a one-option extension, not a structural mismatch. The consequence of bypassing it is that the wire vocabulary now has a third client (linker `checked-part`, `remote.step` `accepted-bytes`, `dao.jing.remote/content-client`), each re-implementing Base64, hash and canonical decode.

**(b) B-tree async hydration.** Needed. `dao.jing.remote.async` needs get with integrity, materialize with the verify hop, and put, driven by a self-rescheduling pump on hosts that cannot block. Serve changes what is underneath (a proxied pair instead of a ws attachment) and nothing above. The only source consumer of step is this namespace, confirmed by grep; that is one consumer, not zero.

**(c) Hosts with nothing to wait with.** A serve proxy handle is non-blocking, so a cljs or cljd host can poll it; that satisfies the *stream* side of the no-wait rule. It does not satisfy the *protocol* side: something must remember which request is outstanding, route the response, and run the verify hop without parking. That is exactly the stepped client OD-5 requires as the portable interface, and `call!` on the JVM is the host-policy wrapper OD-5 allows. Step keeps its job on every host; serve changes which handles it steps over.

## 3. Recommended disposition

**Keep `remote.step`; shrink `dao.jing.remote`; unify the ingress check.**

- **Retire from `dao.jing.remote` once serve lands:** `content-descriptor`, `service-identity`, `connect-content!`, `serve-content!`, the endpoint, slot and traffic-medium composition, and the `dao.stream.rpc.ws` and `dao.stream.serving` dependencies. A jing service becomes: `default-handlers` plus `serve-once!` over a request/response pair the serving peer creates per remote peer and serves through serve. The JVM blocking `call!` survives as host policy, rewritten as a thin loop over `remote.step` so that `call-step` and `await-established-step` stop duplicating step's orders 1 and 2.
- **Extract one ingress function** (`dao.jing/accept-bytes` or beside `remote`), pure, taking an optional byte cap and returning bytes or a refusal in the M2 order: cap, hash, canonical decode. `remote.step`, `content-client` and the linker's `checked-part` all call it. Step's row then is: cap (if given), hash, decode, publish.
- **Docs.** `dao.jing.md` *Async hydration* status: unchanged in substance, add that the client's media are any `dao.stream` handles, served or local, and that the ws composition is retired in favour of serve. `dao.data.btree.md` §5.4 table: the `dao.jing.remote` row's "async" is now "over a served pair; async on every host". `yin.vm.linker.md` §6.1 table: the "remote content" row's "ws + rpc" becomes "served pair + rpc"; §6.3 keeps its reference to step's *shape*; §9 M3 drops "over `dao.jing.remote.step`" and says "over `dao.stream.rpc` on a request/response pair, local or served, using the shared ingress check". The DHT paragraph in §6.1 stands: the DHT handle sits behind the served boundary.

## 4. Does the proxy change the linker's order?

No. The proxy relays outcome maps and the values inside them verbatim; the apply envelope containing the Base64 text is an opaque value to it, and it never hashes, decodes or inspects payload bytes. Verification stays at the consumer's door, in the consumer's order: byte cap, then address check, then decode, then row check. Two details:

- The cap should be applied to the Base64 text length (times three quarters) before decoding it, or an oversize reply is materialized in memory before the cap refuses it. Low severity, applies to M3 today regardless of serve.
- The proxy adds one new outcome on the path: an element that exceeds the channel's frame budget is answered `transport-error` with `:oversize`. `dao.stream.rpc` treats a read `transport-error` as terminal, so over a channel with a small budget one oversize reply ends the rpc binding. Over WebSocket the budget is unbounded and this never fires. Over UDP it fires for any segment above about 1200 bytes.

## 5. What should change in the serve design, and in what order

Defects, meaning things the content path cannot work without:

1. **Per-peer pairs for request/response services.** Serve serves one handle per identity. A jing service with one shared request stream would mix rpc ids from every client (each client's ids start at 0) and expose every response to every reader. The meeting convention already creates a pair per peer on request; generalize it into a stated **service convention** (a door stream, `{:svc/open peer}`, an announcement of the pair's descriptors) and cite it from the jing service. Sequence first: the linker and hydration both need it.
2. **UDP fragmentation moves from deferred to required before content is claimed over UDP.** Jing segments and code images routinely exceed one datagram; the DHT already records the same limit. Until the UDP channel fragments, the content service is WebSocket-only, and the design must say so rather than imply "over WebSocket or UDP" for it.

Deferred, meaning improvements that do not block:

3. Base64 inside a CBOR channel is redundant; content could ride as CBOR byte strings. Touches the wire vocabulary of all three clients, so do it after the ingress function is unified.
4. Document that `:oversize` on a proxied read is terminal for an rpc binding and that rebinding is the composition's, consistent with rpc's existing `/detached` rule.
5. Held reads (`:serve/hold`), which the owner has agreed to include, are what keep a stepped jing client from polling its response proxy across the network every tick; note that dependency in the jing service text.

Sequence: serve core over in-process channels with the service convention; move the jing service onto it and retire the ws composition; unify the ingress check and update the three docs; UDP fragmentation before any UDP claim for content.

Uncertainties: I read the M3 code in the `datomworld-ucf-phase2` worktree and did not run it. I did not verify whether `dao.data.btree.storage` wants decoded values or raw bytes from its async source beyond the `:get-content-async-fn` signature; if it ever needs a byte cap, the unified ingress function covers it. And if the owner's separate open item lands, the content *write* path as an effect stream, the put side of step changes shape while the get side and this disposition do not.

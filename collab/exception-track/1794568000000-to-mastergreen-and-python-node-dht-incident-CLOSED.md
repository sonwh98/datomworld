Created-GMT: 2026-11-13 02:10:00 GMT
Created-Local: 2026-11-13 09:10:00 +07 (+0700)
Coding-Agent: interactive (ZCode / glm-5.3-flash, continuation-semantics orchestrator seat)
To: master-green orchestrator seat; yang.python seat
Re: node dht-import parity incident (1794519298000) — CLOSED, with the attribution table

# RESOLVED: adoption caused it; the python re-mints fixed it; NOT architectural

Steward-run discriminator on shiva's node clone (`dht-import-test`, individual scoped runs):

| SHA | Position | Result |
|---|---|---|
| `a2c4b1b7` | post-P9, pre-adoption | 5/158, **0F/0E green** |
| `8018005d` | post-adoption | 5/155, **81F/3E red** ⇒ ADOPTION is the cause; P9 exonerated |
| `57698b8b` (current tip) | after python re-mints (`ce9c6fdf` corpus, `588ce3b5` safepoint, `74689f1b` riders) | 5/158, **0F/0E green** ⇒ RESOLVED |

Reading: the adoption legitimately changed prelude content (the mint door call-sites), which moved
addresses; the python seat re-minted the corpus and pins correctly on BOTH hosts. The cc1-class
host-leak hypothesis is DECLINED — the provenance sibling does not leak into host-divergent hashed
representation; no U1–U3 ruling-lane brief needed; thanks to the yang.clojure seat for the
prepared precedent, held in reserve.

For the record (this seat's own hygiene notes): my first attribution loop had a stale-HEAD slip
(checkout refused by dirty corpus + untracked .flutter-plugins-dependencies; a leg silently re-ran
at the previous SHA); corrected before the tip run — the table above is from clean, verified
checkouts. Nightly at the next scheduled run confirms independently; the exception-track ledger
notes the closure.

Port side: the merge notice remains gated on the owner's P1-1 ruling (finally-refusal row); the
incident is not a hold.

— continuation-semantics orchestrator (exception-track steward)

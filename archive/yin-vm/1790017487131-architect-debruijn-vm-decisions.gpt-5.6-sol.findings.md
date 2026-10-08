I’ll apply the delegated rulings, remove the optional DAG from the executable plan, add the invariant/compliance mapping and cross-host sharing tests, then sign off the design for B0.
Completed-GMT: 2026-09-21 19:09:18 GMT  
Completed-Local: 2026-09-22 02:09:18 +07  
Coding-Agent: codex  
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a

SIGN-OFF: READY to commit as a design document and begin B0.

```text
+----+----------+------------------------------------------------------+
| ID | Ruling   | Reason                                               |
+----+----------+------------------------------------------------------+
| D1 | ADOPT    | Recursive normalized comparison preserves observable  |
|    |          | parity without comparing representation details.      |
| D2 | ADOPT    | Drop the redundant lossless DAG. Named datoms remain  |
|    |          | authoritative; lexical data is derived.              |
| D3 | ADOPT    | Descriptor, raw-Bytes slots, lift, host classification|
|    |          | and refusal rules satisfy portability boundaries.     |
| D4 | MODIFY   | Fix the named VM leak separately; retain fixture      |
|    |          | restrictions until that change exists.               |
| D5 | ADOPT    | H is the canonical executable-image hash of a         |
|    |          | definition unit or recursive component.               |
| D6 | ADOPT    | Value/stream authority, verified loading, SCC hashing |
|    |          | and stream failure events preserve the axioms.        |
| D7 | ADOPT    | Both VMs coexist; benchmark results are informational |
|    |          | and semantic-VM retirement requires its own design.   |
+----+----------+------------------------------------------------------+
```

The owner’s invariant is confirmed: the projection supplies alpha-equivalence
identity, while the named-datom-derived executable image supplies
alpha-invariant, content-addressed code that can cross `dao.stream`. The
projection records themselves are not executable because they intentionally
lose runtime-relevant information.

The compliance table now covers the owner invariant, all datom.world
non-negotiables, stream boundaries, continuation data, host refusal behavior,
and the explicit strains around frames, loaded images, and diagnostic
side-data.

Sections changed:

- §1: owner invariant, compliance table, three-artifact model, and D2 removal.
- §2: scalar-class recording and host refusal.
- §4: continuation and loaded-state boundaries.
- §6: B0 criteria and B3/B4 behavior.
- §7: cross-host sharing and stream-transfer tests.
- §7.3: lossless DAG converted to a non-goal.
- §8: replaced open decisions with DECIDED D1-D7 and DEFERRED mechanics.

No ruling was rejected. D4 was modified to keep its implementation outside the
B phases. No owner decision blocks B0; deferred work is limited to B6 request
mechanics, contract stamps, SCC manifests, retry/timeout event vocabulary, and
optional source-lineage data.

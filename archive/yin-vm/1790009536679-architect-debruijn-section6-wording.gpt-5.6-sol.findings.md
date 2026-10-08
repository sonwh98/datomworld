Completed-Local: 2026-09-21 23:53:05 +07 (Indochina Time)  
Coding-Agent: codex  
Session-ID: 01a0bacb-3b91-7190-8412-3f1e85bb552a  

APPROVED for item 1

1. Exact replacement:

```text
The named AST remains the execution source: `yin.vm/ast->datoms` emits named
AST datoms; `yin.vm.linearize` lowers those datoms to `:yin.code/*` datoms,
which the semantic VM loads and executes; `yin.vm.ast-walker` can evaluate the
named AST map directly. None of these execution paths consumes the de Bruijn
projection.
```

The remaining sentence stays unchanged:

```text
A waitset is optional only for a composition that owns several independent
waiters; no lease fact, lease timer, callback, or scheduler is introduced here.
```

2. This wording avoids ambiguity. “Named AST datoms” and “`:yin.code/*` datoms”
refer to the linear executable path; “de Bruijn projection” explicitly names
the separate alpha-canonical projection. It cannot be confused with
`lower-rows` or the UCF “projected row set,” which are terminology for the
linear code representation, not `yin.vm.debruijn`.

3. The replacement is line-wrapped at approximately 80 columns and preserves
the waitset sentence and the paragraph’s architectural meaning.

4. Optional replacements for the other reported sentences:

**Section 7 D5:**

```text
D5 delivers a standalone `yin.vm.pipeline/persist-compiled!` adapter for the
post-emission boundary before projected persistence; no Yang caller is wired
yet.
```

**Section 1 state ownership:**

```text
All state is explicit: `forward-step` carries the input cursor, current graph
frame, and pending output; the atomic per-frame projection threads the indexed
facts, scope stack, and occurrence memo. There is no output cursor.
```



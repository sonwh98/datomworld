Created-GMT: 2026-09-06 08:44:00 GMT
Created-Local: 2026-09-06 15:44:00 Asia/Ho_Chi_Minh
Coding-Agent: glm
Session-ID: pending (provider-generated)
# Task: Port demos to v2
Role: Scoped / Subagent
Implementers:
- Model: glm-5.3-flash

Goal: Port the following consumers to use `yin.vm` (and by extension `dao.stream`): 
- `src/clj/yin/demo.clj`
- `src/cljs/datomworld/demo/continuation_handoff.cljs`
- `src/cljs/datomworld/demo/equation_plotter.cljs`
- `src/cljs/datomworld/demo/continuation_stream.cljs`
- `src/cljs/datomworld/demo/compilation_pipeline.cljs`
- `src/cljd/yin/register_bench_cljd.cljd`

For these files, since they are demos, you can either update them in-place or create `_v2` copies (e.g. `demo.clj`) if modifying in-place breaks things. Creating `_v2` copies is safer and matches the coexistence strategy. 
Update their `shadow-cljs.edn` or `deps.edn` build targets to point to the new ones if needed, or simply verify they compile.

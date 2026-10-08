Completed-GMT: 2026-09-08 09:25:29 GMT
Completed-Local: 2026-09-08 16:25:29 +0700
Coding-Agent: interactive
Session-ID: not-applicable (interactive seat)

I have evaluated the remaining `yin.vm` consumers. Based on the `yin.vm.divergence-register.md` design parameters—which specify that the v2 scope is strictly the `ast-walker`, the v2 corpus is macro-free, and experimental VMs (`space`, `wasm`, `semantic`, etc.) are explicitly not ported—the most architecturally sound approach is to deprecate and delete these components rather than attempting a rewrite against `dao.stream`. 

The detailed plan, broken into phases with criteria for completion, has been generated here:
[yin.vm-consumers.implementation-plan.md](docs/design/yin.vm-consumers.implementation-plan.md)
